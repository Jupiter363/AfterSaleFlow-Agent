package com.example.dispute.workflow.application.intake;

import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Java-trusted input to the formal Intake boundary.
 *
 * <p>The object contains references and hashes only; proposal bytes are loaded by the finalizer
 * through {@link IntakeImmutableProposalReader} immediately before the commit port is called.
 */
public record IntakeGraphFinalizationRequest(
        String operationKey,
        String requestHash,
        Authority authority,
        RoomGraphCommand command,
        RoomGraphResult result,
        IntakeGraphThreadBinding threadBinding,
        IntakeSnapshotReference initialSnapshot,
        IntakeEventReference event,
        IntakeProposalReference proposalReference) {

    private static final Pattern OPERATION_KEY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}");

    public IntakeGraphFinalizationRequest {
        if (operationKey == null || !OPERATION_KEY.matcher(operationKey).matches()) {
            throw new IllegalArgumentException("operationKey must be a bounded ASCII identifier");
        }
        requestHash = IntakeContractSupport.sha256(requestHash, "requestHash");
        authority = Objects.requireNonNull(authority, "authority");
        command = Objects.requireNonNull(command, "command");
        result = Objects.requireNonNull(result, "result");
        threadBinding = Objects.requireNonNull(threadBinding, "threadBinding");
        initialSnapshot = Objects.requireNonNull(initialSnapshot, "initialSnapshot");
        proposalReference = Objects.requireNonNull(proposalReference, "proposalReference");
    }

    /** Values copied from the signed Activity/epoch authority and rechecked against every input. */
    public record Authority(
            String tenantSurrogate,
            String caseId,
            long roomEpoch,
            long fencingToken,
            String threadId,
            String actorScopeHash,
            String agentSessionId,
            String commandId,
            String logicalRunId,
            String attemptId,
            String resultHash,
            String proposalHash,
            String checkpointId,
            long cognitiveRevision,
            long processRevision,
            long roomRevision,
            String stageCode,
            long stageSequence,
            IntakeTurnProposal.ProfileVersions profileVersions) {

        public Authority {
            tenantSurrogate = IntakeContractSupport.identifier(tenantSurrogate, "tenantSurrogate");
            caseId = IntakeContractSupport.identifier(caseId, "caseId");
            IntakeContractSupport.nonNegative(roomEpoch, "roomEpoch");
            IntakeContractSupport.positive(fencingToken, "fencingToken");
            threadId = IntakeContractSupport.threadId(threadId);
            actorScopeHash = IntakeContractSupport.sha256(actorScopeHash, "actorScopeHash");
            agentSessionId = IntakeContractSupport.identifier(agentSessionId, "agentSessionId");
            commandId = IntakeContractSupport.identifier(commandId, "commandId");
            logicalRunId = IntakeContractSupport.identifier(logicalRunId, "logicalRunId");
            attemptId = IntakeContractSupport.identifier(attemptId, "attemptId");
            resultHash = IntakeContractSupport.sha256(resultHash, "resultHash");
            proposalHash = IntakeContractSupport.sha256(proposalHash, "proposalHash");
            checkpointId = IntakeContractSupport.identifier(checkpointId, "checkpointId");
            IntakeContractSupport.nonNegative(cognitiveRevision, "cognitiveRevision");
            IntakeContractSupport.nonNegative(processRevision, "processRevision");
            IntakeContractSupport.nonNegative(roomRevision, "roomRevision");
            stageCode = IntakeContractSupport.identifier(stageCode, "stageCode");
            IntakeContractSupport.nonNegative(stageSequence, "stageSequence");
            profileVersions = Objects.requireNonNull(profileVersions, "profileVersions");
        }
    }
}
