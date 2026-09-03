package com.example.dispute.workflow.application.intake;

import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import java.util.Objects;

/**
 * Single formal write boundary for a graph-backed Intake turn.
 *
 * <p>The production adapter must execute all domain writes and completion of the
 * {@code domain_operation} row in one database transaction. This port deliberately has no
 * framework stereotype so SHADOW wiring cannot discover it accidentally.
 */
@FunctionalInterface
public interface IntakeFormalCommitPort {

    IntakeFinalizationReceipt commit(CommitCommand command);

    record CommitCommand(
            IntakeGraphFinalizationRequest request,
            IntakeTurnProposalLoader.LoadedProposal loadedProposal,
            CurrentAuthorityRequirement currentAuthority,
            AgentRunFinalEligibilityRequirement agentRunEligibility) {

        public CommitCommand {
            request = Objects.requireNonNull(request, "request");
            loadedProposal = Objects.requireNonNull(loadedProposal, "loadedProposal");
            currentAuthority = Objects.requireNonNull(currentAuthority, "currentAuthority");
            agentRunEligibility = Objects.requireNonNull(agentRunEligibility, "agentRunEligibility");
            if (!request.proposalReference().equals(loadedProposal.reference())) {
                throw new IllegalArgumentException("loaded proposal reference does not match request");
            }
            if (!request.authority().proposalHash().equals(loadedProposal.proposal().proposalHash())) {
                throw new IllegalArgumentException("loaded proposal hash does not match authority");
            }
            IntakeGraphFinalizationRequest.Authority authority = request.authority();
            IntakePrivateThreadRegistration.ActorScope actor =
                    request.threadBinding().registration().actorScope();
            if (!Objects.equals(currentAuthority.tenantSurrogate(), authority.tenantSurrogate())
                    || !Objects.equals(currentAuthority.caseId(), authority.caseId())
                    || currentAuthority.roomEpoch() != authority.roomEpoch()
                    || currentAuthority.fencingToken() != authority.fencingToken()
                    || currentAuthority.processRevision() != authority.processRevision()
                    || currentAuthority.roomRevision() != authority.roomRevision()
                    || !Objects.equals(currentAuthority.stageCode(), authority.stageCode())
                    || currentAuthority.stageSequence() != authority.stageSequence()
                    || !Objects.equals(currentAuthority.actorScopeHash(), authority.actorScopeHash())
                    || !Objects.equals(currentAuthority.agentSessionId(), authority.agentSessionId())
                    || !Objects.equals(currentAuthority.actorId(), actor.actorId())
                    || currentAuthority.actorRole() != actor.actorRole()
                    || currentAuthority.audience() != actor.audience()) {
                throw new IllegalArgumentException("current authority requirement does not match request");
            }
            if (!Objects.equals(agentRunEligibility.caseId(), authority.caseId())
                    || !Objects.equals(agentRunEligibility.commandId(), authority.commandId())
                    || !Objects.equals(agentRunEligibility.logicalRunId(), authority.logicalRunId())
                    || !Objects.equals(agentRunEligibility.attemptId(), authority.attemptId())
                    || !Objects.equals(agentRunEligibility.resultHash(), authority.resultHash())
                    || !Objects.equals(agentRunEligibility.proposalHash(), authority.proposalHash())
                    || !Objects.equals(agentRunEligibility.checkpointId(), authority.checkpointId())
                    || agentRunEligibility.cognitiveRevision() != authority.cognitiveRevision()
                    || agentRunEligibility.fencingToken() != authority.fencingToken()) {
                throw new IllegalArgumentException("AgentRun eligibility requirement does not match request");
            }
        }
    }

    /** Keys that the adapter must lock and revalidate against current participation and stage. */
    record CurrentAuthorityRequirement(
            String tenantSurrogate,
            String caseId,
            long roomEpoch,
            long fencingToken,
            long processRevision,
            long roomRevision,
            String stageCode,
            long stageSequence,
            String actorId,
            ActorRole actorRole,
            Audience audience,
            String actorScopeHash,
            String agentSessionId) {

        public CurrentAuthorityRequirement {
            tenantSurrogate = IntakeContractSupport.identifier(tenantSurrogate, "tenantSurrogate");
            caseId = IntakeContractSupport.identifier(caseId, "caseId");
            IntakeContractSupport.nonNegative(roomEpoch, "roomEpoch");
            IntakeContractSupport.positive(fencingToken, "fencingToken");
            IntakeContractSupport.nonNegative(processRevision, "processRevision");
            IntakeContractSupport.nonNegative(roomRevision, "roomRevision");
            stageCode = IntakeContractSupport.identifier(stageCode, "stageCode");
            IntakeContractSupport.nonNegative(stageSequence, "stageSequence");
            actorId = IntakeContractSupport.identifier(actorId, "actorId");
            actorRole = Objects.requireNonNull(actorRole, "actorRole");
            audience = Objects.requireNonNull(audience, "audience");
            actorScopeHash = IntakeContractSupport.sha256(actorScopeHash, "actorScopeHash");
            agentSessionId = IntakeContractSupport.identifier(agentSessionId, "agentSessionId");
        }
    }

    /** Keys used to lock the persisted attempt and prove it is the sole eligible formal result. */
    record AgentRunFinalEligibilityRequirement(
            String caseId,
            String commandId,
            String logicalRunId,
            String attemptId,
            String resultHash,
            String proposalHash,
            String checkpointId,
            long cognitiveRevision,
            long fencingToken) {

        public AgentRunFinalEligibilityRequirement {
            caseId = IntakeContractSupport.identifier(caseId, "caseId");
            commandId = IntakeContractSupport.identifier(commandId, "commandId");
            logicalRunId = IntakeContractSupport.identifier(logicalRunId, "logicalRunId");
            attemptId = IntakeContractSupport.identifier(attemptId, "attemptId");
            resultHash = IntakeContractSupport.sha256(resultHash, "resultHash");
            proposalHash = IntakeContractSupport.sha256(proposalHash, "proposalHash");
            checkpointId = IntakeContractSupport.identifier(checkpointId, "checkpointId");
            IntakeContractSupport.nonNegative(cognitiveRevision, "cognitiveRevision");
            IntakeContractSupport.positive(fencingToken, "fencingToken");
        }
    }
}
