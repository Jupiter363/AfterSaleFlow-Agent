package com.example.dispute.workflow.application.intake.exchange;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Strict unique-member, RFC 8785 and Draft 2020-12 validator for exchanged bytes. */
public final class IntakeExchangeCanonicalPayloadValidator {

    private static final Map<String, SchemaBinding> SCHEMAS = Map.of(
            "intake-domain-snapshot.v2",
                    new SchemaBinding("intake-domain-snapshot.schema.json", "snapshot_hash"),
            "intake-turn-event.v2",
                    new SchemaBinding("intake-turn-event.schema.json", "event_hash"),
            "intake-turn-proposal.v2",
                    new SchemaBinding("intake-turn-proposal.schema.json", "proposal_hash"));
    private static final String RESOURCE_ROOT = "contracts/agent-platform/intake/v2/";

    private final ObjectMapper mapper;
    private final Map<String, JsonSchema> schemas;

    public IntakeExchangeCanonicalPayloadValidator() {
        this.mapper = JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        JsonSchemaFactory factory =
                JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        this.schemas = SCHEMAS.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey,
                entry -> loadSchema(factory, entry.getValue().resourceName())));
    }

    public JsonNode requireValid(
            String schemaVersion, String expectedSha256, long expectedSize, byte[] payload) {
        SchemaBinding binding = SCHEMAS.get(schemaVersion);
        JsonSchema schema = schemas.get(schemaVersion);
        if (binding == null || schema == null) {
            throw rejected("Intake exchange schema is not supported");
        }
        int maximum = "intake-turn-proposal.v2".equals(schemaVersion)
                ? IntakeExchangeContract.PROPOSAL_MAX_BYTES
                : IntakeExchangeContract.payloadMaximum(schemaVersion);
        if (payload == null
                || payload.length == 0
                || payload.length != expectedSize
                || payload.length > maximum) {
            throw rejected("Intake exchange payload size differs from its receipt");
        }
        JsonNode document = parse(payload);
        Set<ValidationMessage> errors = schema.validate(document);
        if (!errors.isEmpty()) {
            throw rejected("Intake exchange payload violates " + schemaVersion);
        }
        byte[] canonical = ContractJson.canonicalize(document);
        if (!MessageDigest.isEqual(payload, canonical)) {
            throw rejected("Intake exchange payload is not RFC 8785 canonical JSON");
        }
        String canonicalHash;
        try {
            canonicalHash = com.example.dispute.workflow.application.intake.IntakeContractHashes
                    .canonicalHashExcluding(document, binding.hashField());
        } catch (RuntimeException failure) {
            throw rejected("Intake exchange payload self-hash cannot be computed", failure);
        }
        JsonNode selfHash = document.get(binding.hashField());
        if (selfHash == null
                || !selfHash.isTextual()
                || !canonicalHash.equals(selfHash.textValue())
                || !canonicalHash.equals(expectedSha256)) {
            throw rejected("Intake exchange payload self-hash differs from its receipt");
        }
        return document.deepCopy();
    }

    private JsonNode parse(byte[] payload) {
        try (JsonParser parser = mapper.createParser(payload)) {
            JsonNode document = mapper.readTree(parser);
            if (document == null || !document.isObject() || parser.nextToken() != null) {
                throw rejected("Intake exchange payload must be one JSON object");
            }
            return document;
        } catch (IntakeExchangeAuthorityValidationPort.Rejected failure) {
            throw failure;
        } catch (IOException failure) {
            throw rejected("Intake exchange payload is not unique-member JSON", failure);
        }
    }

    private static JsonSchema loadSchema(JsonSchemaFactory factory, String resourceName) {
        String resource = RESOURCE_ROOT + resourceName;
        try (InputStream input = IntakeExchangeCanonicalPayloadValidator.class
                .getClassLoader()
                .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("missing classpath resource " + resource);
            }
            return factory.getSchema(input);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot load Intake exchange schema " + resource, failure);
        }
    }

    private static IntakeExchangeAuthorityValidationPort.Rejected rejected(String message) {
        return new IntakeExchangeAuthorityValidationPort.Rejected(message);
    }

    private static IntakeExchangeAuthorityValidationPort.Rejected rejected(
            String message, Throwable cause) {
        return new IntakeExchangeAuthorityValidationPort.Rejected(message, cause);
    }

    private record SchemaBinding(String resourceName, String hashField) {}
}
