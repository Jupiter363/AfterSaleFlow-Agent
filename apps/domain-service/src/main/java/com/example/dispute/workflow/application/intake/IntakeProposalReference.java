package com.example.dispute.workflow.application.intake;

/** Immutable, version-pinned pointer to one intake-turn-proposal.v2 object. */
public record IntakeProposalReference(
        String artifactId,
        String schemaVersion,
        String uri,
        String objectVersion,
        String sha256,
        long sizeBytes) {

    public static final long MAX_BYTES = 64L * 1024L;

    public IntakeProposalReference {
        artifactId = IntakeContractSupport.identifier(artifactId, "artifactId");
        if (!"intake-turn-proposal.v2".equals(schemaVersion)) {
            throw new IllegalArgumentException("proposal schema must be intake-turn-proposal.v2");
        }
        uri = IntakeContractSupport.immutableUri(uri);
        objectVersion = IntakeContractSupport.boundedText(objectVersion, 128, "objectVersion");
        sha256 = IntakeContractSupport.sha256(sha256, "sha256");
        if (sizeBytes <= 0 || sizeBytes > MAX_BYTES) {
            throw new IllegalArgumentException("proposal size exceeds 64 KiB");
        }
    }
}
