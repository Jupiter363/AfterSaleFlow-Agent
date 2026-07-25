package com.example.dispute.workflow.contract.outcome.v1;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class OutcomeContractCodec {

    public static final int MAX_ENCODED_BYTES = 32_768;

    private static final Map<String, Class<?>> TYPES = Map.ofEntries(
            Map.entry("outcome-workflow-start.schema.json", OutcomeWorkflowStart.class),
            Map.entry("outcome-reviewer-decision-receipt.schema.json", OutcomeReviewDecisionReceipt.class),
            Map.entry("outcome-sla-escalation-receipt.schema.json", OutcomeSlaEscalationReceipt.class),
            Map.entry("outcome-operation-command.schema.json", OutcomeOperationCommand.class),
            Map.entry("outcome-operation-receipt.schema.json", OutcomeOperationReceipt.class),
            Map.entry("outcome-execution-attempt-observation.schema.json", OutcomeExecutionAttemptObservation.class),
            Map.entry("outcome-attempt-reconciliation-receipt.schema.json", OutcomeAttemptReconciliationReceipt.class),
            Map.entry("outcome-compensation-receipt.schema.json", OutcomeCompensationReceipt.class),
            Map.entry("outcome-closure-receipt.schema.json", OutcomeClosureReceipt.class),
            Map.entry("outcome-evaluation-receipt.schema.json", OutcomeEvaluationReceipt.class),
            Map.entry("outcome-process-projection.schema.json", OutcomeProjection.class),
            Map.entry("outcome-synthetic-noop-receipt.schema.json", OutcomeSyntheticNoopReceipt.class));

    private final ObjectMapper mapper;
    private final Map<String, JsonSchema> schemas;

    public OutcomeContractCodec(Path contractRoot) {
        mapper = JsonMapper.builder().findAndAddModules().build();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        Map<String, JsonSchema> loaded = new LinkedHashMap<>();
        for (String schemaFile : TYPES.keySet()) {
            Path path = contractRoot.toAbsolutePath().normalize().resolve(schemaFile);
            try (InputStream input = Files.newInputStream(path)) {
                loaded.put(schemaFile, factory.getSchema(mapper.readTree(input)));
            } catch (IOException exception) {
                throw new IllegalStateException("cannot load Outcome contract " + schemaFile, exception);
            }
        }
        schemas = Map.copyOf(loaded);
    }

    public Set<String> schemaFiles() {
        return TYPES.keySet();
    }

    public <T> T decode(String schemaFile, JsonNode instance, Class<T> type) {
        Class<?> registered = registeredType(schemaFile);
        if (!registered.equals(type)) {
            throw new IllegalArgumentException(schemaFile + " requires " + registered.getSimpleName());
        }
        validate(schemaFile, instance);
        try {
            return mapper.treeToValue(instance, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(schemaFile + " cannot be decoded", exception);
        }
    }

    public JsonNode encode(String schemaFile, Object value) {
        Class<?> registered = registeredType(schemaFile);
        if (!registered.isInstance(value)) {
            throw new IllegalArgumentException(schemaFile + " requires " + registered.getSimpleName());
        }
        JsonNode instance = mapper.valueToTree(value);
        validate(schemaFile, instance);
        return instance;
    }

    public void validate(String schemaFile, JsonNode instance) {
        registeredType(schemaFile);
        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(instance);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(schemaFile + " is not serializable", exception);
        }
        if (bytes.length > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException(schemaFile + " exceeds max encoded bytes");
        }
        Set<ValidationMessage> errors = schemas.get(schemaFile).validate(instance);
        if (!errors.isEmpty()) {
            String detail = errors.stream()
                    .map(ValidationMessage::getMessage)
                    .sorted()
                    .collect(Collectors.joining("; "));
            throw new IllegalArgumentException(schemaFile + " is invalid: " + detail);
        }
        validateJavaSemantics(schemaFile, instance);
        if ("outcome-process-projection.schema.json".equals(schemaFile)) {
            validateProjectionSemantics(instance);
        }
    }

    private static void validateJavaSemantics(String schemaFile, JsonNode instance) {
        try {
            if ("outcome-workflow-start.schema.json".equals(schemaFile)) {
                OutcomeWireTypes.reviewWindow(
                        Instant.parse(instance.required("review_opened_at").textValue()),
                        Instant.parse(instance.required("review_deadline_at").textValue()));
            }
            if (instance.has("source_revision")) {
                OutcomeWireTypes.eventOrder(
                        instance.required("source_revision").longValue(),
                        instance.required("revision").longValue(),
                        instance.required("committed_event_sequence").longValue());
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    schemaFile + " is invalid: " + exception.getMessage(), exception);
        }
    }

    private static void validateProjectionSemantics(JsonNode instance) {
        String phase = instance.path("phase").asText();
        boolean terminal = "CLOSED".equals(phase) || "EVALUATED".equals(phase);
        if (terminal
                && instance.required("terminal_success_receipt_count").longValue()
                        != instance.required("required_operation_count").longValue()) {
            throw new IllegalArgumentException(
                    "outcome-process-projection.schema.json is invalid: "
                            + "terminal_success_receipt_count must equal required_operation_count");
        }
    }

    private static Class<?> registeredType(String schemaFile) {
        Class<?> type = TYPES.get(schemaFile);
        if (type == null) {
            throw new IllegalArgumentException("unknown Outcome schema: " + schemaFile);
        }
        return type;
    }
}
