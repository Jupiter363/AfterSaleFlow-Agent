package com.example.dispute.workflow.contract.v1;

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
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class AgentPlatformContractCodec {

    private static final Map<String, Class<?>> CONTRACT_TYPES =
            Map.of(
                    "case-command-ref.schema.json", CaseCommandRef.class,
                    "room-graph-command.schema.json", RoomGraphCommand.class,
                    "room-graph-result.schema.json", RoomGraphResult.class,
                    "artifact-ref.schema.json", ArtifactRef.class,
                    "process-projection.schema.json", ProcessProjection.class,
                    "agent-stream-event.schema.json", AgentStreamEvent.class,
                    "agent-execution-manifest.schema.json", AgentExecutionManifest.class);

    private final ObjectMapper mapper;
    private final Map<String, JsonSchema> validators;
    private final Map<String, Integer> limits;

    public AgentPlatformContractCodec(Path contractRoot) {
        this.mapper = JsonMapper.builder().findAndAddModules().build();
        this.mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.validators = new HashMap<>();
        this.limits = new HashMap<>();
        loadContracts(contractRoot.toAbsolutePath().normalize());
    }

    public <T> T decode(String schemaFile, JsonNode instance, Class<T> type) {
        Class<?> registeredType = registeredType(schemaFile);
        if (!registeredType.equals(type)) {
            throw new IllegalArgumentException(
                    schemaFile + " requires " + registeredType.getSimpleName());
        }
        validate(schemaFile, instance);
        try {
            return mapper.treeToValue(instance, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(schemaFile + " cannot be decoded", exception);
        }
    }

    public JsonNode encode(String schemaFile, Object value) {
        Class<?> registeredType = registeredType(schemaFile);
        if (!registeredType.isInstance(value)) {
            throw new IllegalArgumentException(
                    schemaFile + " requires " + registeredType.getSimpleName());
        }
        JsonNode instance = mapper.valueToTree(value);
        validate(schemaFile, instance);
        return instance;
    }

    private void loadContracts(Path contractRoot) {
        JsonSchemaFactory factory =
                JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        try {
            JsonNode matrix =
                    mapper.readTree(
                            contractRoot.resolve("compatibility-matrix.yaml").toFile());
            JsonNode rows = matrix.required("contracts");
            if (!rows.isArray()) {
                throw new IllegalArgumentException(
                        "contract compatibility matrix has no contracts array");
            }
            for (JsonNode row : rows) {
                String schemaFile = row.required("schema_file").asText();
                if (!CONTRACT_TYPES.containsKey(schemaFile)) {
                    throw new IllegalArgumentException(
                            "unknown contract schema in matrix: " + schemaFile);
                }
                int limit = row.required("max_serialized_bytes").intValue();
                if (limit <= 0) {
                    throw new IllegalArgumentException(
                            "invalid max_serialized_bytes for " + schemaFile);
                }
                JsonNode schemaNode = mapper.readTree(contractRoot.resolve(schemaFile).toFile());
                validators.put(schemaFile, factory.getSchema(schemaNode));
                limits.put(schemaFile, limit);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("cannot load agent platform contracts", exception);
        }
        if (!validators.keySet().equals(CONTRACT_TYPES.keySet())) {
            throw new IllegalArgumentException(
                    "compatibility matrix and Java type registry differ");
        }
    }

    private void validate(String schemaFile, JsonNode instance) {
        registeredType(schemaFile);
        byte[] serialized;
        try {
            serialized = mapper.writeValueAsBytes(instance);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(schemaFile + " is not serializable", exception);
        }
        if (serialized.length > limits.get(schemaFile)) {
            throw new IllegalArgumentException(schemaFile + " exceeds max_serialized_bytes");
        }
        Set<ValidationMessage> errors = validators.get(schemaFile).validate(instance);
        if (!errors.isEmpty()) {
            String detail =
                    errors.stream()
                            .map(ValidationMessage::getMessage)
                            .sorted()
                            .collect(Collectors.joining("; "));
            throw new IllegalArgumentException(schemaFile + " is invalid: " + detail);
        }
    }

    private static Class<?> registeredType(String schemaFile) {
        Class<?> type = CONTRACT_TYPES.get(schemaFile);
        if (type == null) {
            throw new IllegalArgumentException("unknown contract schema: " + schemaFile);
        }
        return type;
    }
}
