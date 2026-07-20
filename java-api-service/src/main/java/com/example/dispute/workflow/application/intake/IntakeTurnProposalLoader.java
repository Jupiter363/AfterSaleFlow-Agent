package com.example.dispute.workflow.application.intake;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Reloads and validates the immutable proposal before any formal transaction can begin. */
public final class IntakeTurnProposalLoader {

    private static final String SCHEMA_RESOURCE =
            "contracts/agent-platform/intake/v2/intake-turn-proposal.schema.json";

    private final IntakeImmutableProposalReader reader;
    private final ObjectMapper mapper;
    private final JsonSchema schema;

    public IntakeTurnProposalLoader(IntakeImmutableProposalReader reader) {
        this(reader, classpathSchema());
    }

    public IntakeTurnProposalLoader(IntakeImmutableProposalReader reader, Path schemaPath) {
        this(reader, fileSchema(schemaPath));
    }

    private IntakeTurnProposalLoader(
            IntakeImmutableProposalReader reader, SchemaResource schemaResource) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.mapper = JsonMapper.builder().findAndAddModules().build();
        this.mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.schema = loadSchema(schemaResource);
    }

    public LoadedProposal load(
            IntakeProposalReference reference, IntakeProposalAuthority authority) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(authority, "authority");
        IntakeImmutableProposalReader.StoredProposal stored;
        try {
            stored = Objects.requireNonNull(reader.load(reference), "stored proposal");
        } catch (IntakeFinalizationRejectedException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IntakeFinalizationRejectedException(
                    "INTAKE_PROPOSAL_LOAD_FAILED", "proposal object could not be loaded", failure);
        }
        requireExactReceipt(reference, stored);
        byte[] payload = stored.payload();
        if (payload == null
                || payload.length != stored.sizeBytes()
                || payload.length != reference.sizeBytes()) {
            throw rejected("INTAKE_PROPOSAL_SIZE_MISMATCH", "proposal payload size differs");
        }

        JsonNode document = parse(payload);
        validateSchema(document);
        byte[] canonical = canonicalize(document);
        if (!MessageDigest.isEqual(payload, canonical)) {
            throw rejected(
                    "INTAKE_PROPOSAL_NOT_CANONICAL", "proposal object is not RFC 8785 canonical");
        }
        String proposalHash;
        try {
            proposalHash = IntakeContractHashes.canonicalHashExcluding(document, "proposal_hash");
        } catch (RuntimeException failure) {
            throw new IntakeFinalizationRejectedException(
                    "INTAKE_PROPOSAL_HASH_INVALID", "proposal self-hash cannot be computed", failure);
        }
        if (!proposalHash.equals(document.required("proposal_hash").asText())
                || !proposalHash.equals(reference.sha256())
                || !proposalHash.equals(stored.contentSha256())) {
            throw rejected(
                    "INTAKE_PROPOSAL_HASH_MISMATCH",
                    "proposal self-hash, reference hash, and object receipt differ");
        }

        IntakeTurnProposal proposal;
        try {
            proposal = mapper.treeToValue(document, IntakeTurnProposal.class);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new IntakeFinalizationRejectedException(
                    "INTAKE_PROPOSAL_DECODE_INVALID", "proposal cannot be decoded", failure);
        }
        authority.requireMatches(proposal);
        return new LoadedProposal(reference, proposal);
    }

    private static void requireExactReceipt(
            IntakeProposalReference reference,
            IntakeImmutableProposalReader.StoredProposal stored) {
        if (!reference.artifactId().equals(stored.artifactId())
                || !reference.schemaVersion().equals(stored.schemaVersion())
                || !reference.uri().equals(stored.uri())
                || !reference.objectVersion().equals(stored.objectVersion())
                || !reference.sha256().equals(stored.contentSha256())
                || reference.sizeBytes() != stored.sizeBytes()) {
            throw rejected(
                    "INTAKE_PROPOSAL_REFERENCE_MISMATCH",
                    "immutable object receipt differs from the trusted proposal reference");
        }
    }

    private JsonNode parse(byte[] payload) {
        try {
            JsonNode document = mapper.readTree(payload);
            if (document == null || !document.isObject()) {
                throw rejected(
                        "INTAKE_PROPOSAL_SCHEMA_INVALID", "proposal must be a JSON object");
            }
            return document;
        } catch (IntakeFinalizationRejectedException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new IntakeFinalizationRejectedException(
                    "INTAKE_PROPOSAL_JSON_INVALID", "proposal is not valid JSON", failure);
        }
    }

    private void validateSchema(JsonNode document) {
        Set<ValidationMessage> errors = schema.validate(document);
        if (!errors.isEmpty()) {
            String detail = errors.stream()
                    .map(ValidationMessage::getMessage)
                    .sorted()
                    .collect(Collectors.joining("; "));
            throw rejected("INTAKE_PROPOSAL_SCHEMA_INVALID", detail);
        }
    }

    private static byte[] canonicalize(JsonNode document) {
        try {
            return ContractJson.canonicalize(document);
        } catch (RuntimeException failure) {
            throw new IntakeFinalizationRejectedException(
                    "INTAKE_PROPOSAL_CANONICALIZATION_FAILED",
                    "proposal cannot be canonicalized",
                    failure);
        }
    }

    private static JsonSchema loadSchema(SchemaResource resource) {
        ObjectMapper mapper = JsonMapper.builder().build();
        try (InputStream input = resource.open()) {
            JsonNode schemaDocument = mapper.readTree(input);
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(schemaDocument);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot load the frozen Intake proposal schema", failure);
        }
    }

    private static SchemaResource classpathSchema() {
        return () -> {
            InputStream input = IntakeTurnProposalLoader.class
                    .getClassLoader()
                    .getResourceAsStream(SCHEMA_RESOURCE);
            if (input == null) {
                throw new IOException("missing classpath resource " + SCHEMA_RESOURCE);
            }
            return input;
        };
    }

    private static SchemaResource fileSchema(Path schemaPath) {
        Path normalized = Objects.requireNonNull(schemaPath, "schemaPath")
                .toAbsolutePath()
                .normalize();
        return () -> Files.newInputStream(normalized);
    }

    private static IntakeFinalizationRejectedException rejected(String code, String message) {
        return new IntakeFinalizationRejectedException(code, message);
    }

    public record LoadedProposal(
            IntakeProposalReference reference, IntakeTurnProposal proposal) {
        public LoadedProposal {
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(proposal, "proposal");
        }
    }

    @FunctionalInterface
    private interface SchemaResource {
        InputStream open() throws IOException;
    }
}
