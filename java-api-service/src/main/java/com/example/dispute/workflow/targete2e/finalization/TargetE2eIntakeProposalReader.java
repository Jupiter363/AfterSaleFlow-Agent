package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.workflow.application.intake.IntakeFinalizationRejectedException;
import com.example.dispute.workflow.application.intake.IntakeImmutableProposalReader;
import com.example.dispute.workflow.application.intake.IntakeProposalReference;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Resolves and reads an exact immutable proposal, checking every receipt field and its bytes. */
public final class TargetE2eIntakeProposalReader
        implements IntakeImmutableProposalReader, TargetE2eIntakeProposalReferenceResolver {

    private final TargetE2eIntakeProposalStore store;

    public TargetE2eIntakeProposalReader(TargetE2eIntakeProposalStore store) {
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
        String calculated = sha256(payload);
        if (!reference.sha256().equals(calculated)) {
            throw rejected(
                    "INTAKE_PROPOSAL_OBJECT_HASH_MISMATCH",
                    "proposal bytes differ from the immutable content hash");
        }
        return new StoredProposal(
                stored.artifactId(),
                stored.schemaVersion(),
                stored.uri(),
                stored.objectVersion(),
                stored.sha256(),
                stored.sizeBytes(),
                payload);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static IntakeFinalizationRejectedException rejected(String code, String message) {
        return new IntakeFinalizationRejectedException(code, message);
    }
}
