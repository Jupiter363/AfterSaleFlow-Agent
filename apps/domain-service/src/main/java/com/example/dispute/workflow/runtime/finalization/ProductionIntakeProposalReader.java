package com.example.dispute.workflow.runtime.finalization;

import com.example.dispute.workflow.application.intake.IntakeContractHashes;
import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.intake.IntakeImmutableProposalReader;
import com.example.dispute.workflow.application.intake.IntakeProposalReference;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.regex.Pattern;

/** Resolves and reads an exact immutable proposal, checking every receipt field and its bytes. */
public final class ProductionIntakeProposalReader
        implements IntakeImmutableProposalReader, ProductionIntakeProposalReferenceResolver {

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final ObjectMapper JSON_MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private final ProductionIntakeProposalStore store;

    public ProductionIntakeProposalReader(ProductionIntakeProposalStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public IntakeProposalReference resolve(ArtifactPointer pointer) {
        Objects.requireNonNull(pointer, "pointer");
        var metadata = Objects.requireNonNull(
                store.resolve(pointer), "proposal store returned no metadata");
        if (!pointer.artifactId().equals(metadata.artifactId())
                || !pointer.schemaVersion().equals(metadata.schemaVersion())
                || !pointer.uri().equals(metadata.uri())
                || !pointer.sha256().equals(metadata.sha256())) {
            throw rejected(
                    "INTAKE_PROPOSAL_METADATA_MISMATCH",
                    "proposal metadata differs from the Graph artifact pointer");
        }
        return new IntakeProposalReference(
                metadata.artifactId(),
                metadata.schemaVersion(),
                metadata.uri(),
                metadata.objectVersion(),
                metadata.sha256(),
                metadata.sizeBytes());
    }

    @Override
    public StoredProposal load(IntakeProposalReference reference) {
        Objects.requireNonNull(reference, "reference");
        var stored = Objects.requireNonNull(
                store.readExact(reference), "proposal store returned no object");
        byte[] payload = stored.payload();
        if (!reference.artifactId().equals(stored.artifactId())
                || !reference.schemaVersion().equals(stored.schemaVersion())
                || !reference.uri().equals(stored.uri())
                || !reference.objectVersion().equals(stored.objectVersion())
                || !reference.sha256().equals(stored.sha256())
                || reference.sizeBytes() != stored.sizeBytes()
                || payload == null
                || payload.length != stored.sizeBytes()) {
            throw rejected(
                    "INTAKE_PROPOSAL_OBJECT_MISMATCH",
                    "proposal object differs from its immutable reference");
        }
        requireCanonicalSelfHash(reference, payload);
        return new StoredProposal(
                stored.artifactId(),
                stored.schemaVersion(),
                stored.uri(),
                stored.objectVersion(),
                stored.sha256(),
                stored.sizeBytes(),
                payload);
    }

    private static void requireCanonicalSelfHash(IntakeProposalReference reference, byte[] payload) {
        JsonNode document = parseObject(payload);
        byte[] canonical;
        try {
            canonical = ContractJson.canonicalize(document);
        } catch (RuntimeException failure) {
            throw rejected(
                    "INTAKE_PROPOSAL_OBJECT_HASH_MISMATCH",
                    "proposal bytes cannot be canonicalized",
                    failure);
        }
        if (!MessageDigest.isEqual(payload, canonical)) {
            throw rejected(
                    "INTAKE_PROPOSAL_OBJECT_HASH_MISMATCH",
                    "proposal bytes are not RFC 8785 canonical");
        }

        JsonNode embeddedHash = document.get("proposal_hash");
        if (embeddedHash == null
                || !embeddedHash.isTextual()
                || !SHA256.matcher(embeddedHash.textValue()).matches()
                || !reference.sha256().equals(embeddedHash.textValue())) {
            throw rejected(
                    "INTAKE_PROPOSAL_OBJECT_HASH_MISMATCH",
                    "proposal self-hash differs from the immutable content hash");
        }

        String calculated;
        try {
            calculated = IntakeContractHashes.canonicalHashExcluding(document, "proposal_hash");
        } catch (RuntimeException failure) {
            throw rejected(
                    "INTAKE_PROPOSAL_OBJECT_HASH_MISMATCH",
                    "proposal self-hash cannot be computed",
                    failure);
        }
        if (!calculated.equals(embeddedHash.textValue())) {
            throw rejected(
                    "INTAKE_PROPOSAL_OBJECT_HASH_MISMATCH",
                    "proposal bytes differ from the immutable content hash");
        }
    }

    private static JsonNode parseObject(byte[] payload) {
        try (JsonParser parser = JSON_MAPPER.createParser(payload)) {
            JsonNode document = JSON_MAPPER.readTree(parser);
            if (document == null || !document.isObject() || parser.nextToken() != null) {
                throw rejected(
                        "INTAKE_PROPOSAL_OBJECT_HASH_MISMATCH",
                        "proposal payload must be one JSON object");
            }
            return document;
        } catch (IntakeFinalizationRejectedException failure) {
            throw failure;
        } catch (IOException failure) {
            throw rejected(
                    "INTAKE_PROPOSAL_OBJECT_HASH_MISMATCH",
                    "proposal payload is not unique-member JSON",
                    failure);
        }
    }

    private static IntakeFinalizationRejectedException rejected(String code, String message) {
        return new IntakeFinalizationRejectedException(code, message);
    }

    private static IntakeFinalizationRejectedException rejected(
            String code, String message, Throwable cause) {
        return new IntakeFinalizationRejectedException(code, message, cause);
    }
}
