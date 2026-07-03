package com.example.dispute.evidence.application;

public record EvidenceVerificationCommand(
        boolean hashValid,
        boolean signatureValid,
        boolean sourceTrusted,
        boolean mimeValid,
        boolean sizeAllowed,
        boolean duplicate,
        boolean agentFlagsConflict,
        String agentFindingsJson) {

    public EvidenceVerificationCommand {
        if (agentFindingsJson == null || agentFindingsJson.isBlank()) {
            throw new IllegalArgumentException("agentFindingsJson must not be blank");
        }
    }
}
