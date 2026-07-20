package com.example.dispute.workflow.application.intake;

import java.util.Objects;

/** Java-trusted values that every field of a loaded proposal must match exactly. */
public record IntakeProposalAuthority(
        String commandId,
        String logicalRunId,
        String attemptId,
        String caseId,
        long roomEpoch,
        String threadId,
        String actorScopeHash,
        String agentSessionId,
        long cognitiveRevision,
        String sourceSnapshotHash,
        String sourceEventHash,
        IntakeTurnProposal.ProfileVersions profileVersions) {

    public IntakeProposalAuthority {
        commandId = IntakeContractSupport.identifier(commandId, "commandId");
        logicalRunId = IntakeContractSupport.identifier(logicalRunId, "logicalRunId");
        attemptId = IntakeContractSupport.identifier(attemptId, "attemptId");
        caseId = IntakeContractSupport.identifier(caseId, "caseId");
        IntakeContractSupport.nonNegative(roomEpoch, "roomEpoch");
        threadId = IntakeContractSupport.threadId(threadId);
        actorScopeHash = IntakeContractSupport.sha256(actorScopeHash, "actorScopeHash");
        agentSessionId = IntakeContractSupport.identifier(agentSessionId, "agentSessionId");
        IntakeContractSupport.positive(cognitiveRevision, "cognitiveRevision");
        sourceSnapshotHash =
                IntakeContractSupport.sha256(sourceSnapshotHash, "sourceSnapshotHash");
        if (sourceEventHash != null) {
            sourceEventHash = IntakeContractSupport.sha256(sourceEventHash, "sourceEventHash");
        }
        profileVersions = Objects.requireNonNull(profileVersions, "profileVersions");
    }

    public void requireMatches(IntakeTurnProposal proposal) {
        boolean matches =
                commandId.equals(proposal.commandId())
                        && logicalRunId.equals(proposal.logicalRunId())
                        && attemptId.equals(proposal.attemptId())
                        && caseId.equals(proposal.caseId())
                        && roomEpoch == proposal.roomEpoch()
                        && threadId.equals(proposal.threadId())
                        && actorScopeHash.equals(proposal.actorScopeHash())
                        && agentSessionId.equals(proposal.agentSessionId())
                        && cognitiveRevision == proposal.cognitiveRevision()
                        && sourceSnapshotHash.equals(proposal.sourceSnapshotHash())
                        && Objects.equals(sourceEventHash, proposal.sourceEventHash())
                        && profileVersions.equals(proposal.profileVersions());
        if (!matches) {
            throw new IntakeFinalizationRejectedException(
                    "INTAKE_PROPOSAL_AUTHORITY_MISMATCH",
                    "loaded proposal does not match the trusted finalization authority");
        }
    }
}
