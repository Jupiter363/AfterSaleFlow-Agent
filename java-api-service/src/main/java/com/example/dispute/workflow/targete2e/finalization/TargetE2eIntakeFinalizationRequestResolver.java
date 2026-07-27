package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter;
import com.example.dispute.workflow.application.intake.IntakeAgentRunFinalizationRequestResolver;
import com.example.dispute.workflow.application.intake.IntakeContractHashes;
import com.example.dispute.workflow.application.intake.IntakeFinalizationOperationKey;
import com.example.dispute.workflow.application.intake.IntakeGraphFinalizationRequest;
import com.example.dispute.workflow.application.intake.IntakeProposalReference;
import com.example.dispute.workflow.application.intake.IntakeTurnProposal;
import java.util.Objects;

/** Reconstructs the Java-trusted Intake finalization request from authorized persisted facts. */
public final class TargetE2eIntakeFinalizationRequestResolver
        implements IntakeAgentRunFinalizationRequestResolver {

    private final TargetE2eAuthorizedIntakeFinalizationSource source;
    private final TargetE2eIntakeProposalReferenceResolver proposalReferences;

    public TargetE2eIntakeFinalizationRequestResolver(
            TargetE2eAuthorizedIntakeFinalizationSource source,
            TargetE2eIntakeProposalReferenceResolver proposalReferences) {
        this.source = Objects.requireNonNull(source, "source");
        this.proposalReferences = Objects.requireNonNull(proposalReferences, "proposalReferences");
    }

    @Override
    public IntakeGraphFinalizationRequest resolve(
            AgentRunDomainResultCommitter.CommitCommand command) {
        Objects.requireNonNull(command, "command");
        var authorized = source.resolve(command.request(), command.result());
        var state = authorized.state();
        var run = state.run();
        var attempt = state.attempt();
        var projection = state.projection();
        var registration = state.threadBinding().registration();
        var graphResult = command.result().graphResult();
        IntakeProposalReference proposal = proposalReferences.resolve(
                TargetE2eAgentRunV2FinalizationFactsProvider.proposal(command.result()));
        var profiles = new IntakeTurnProposal.ProfileVersions(
                registration.graphVersion(),
                registration.checkpointSchemaVersion(),
                registration.promptVersion(),
                registration.modelProfileId(),
                registration.outputSchemaVersion(),
                registration.policyVersion(),
                registration.guardrailVersion(),
                registration.toolPolicyVersion());
        var authority = new IntakeGraphFinalizationRequest.Authority(
                run.tenantSurrogate(),
                run.caseId(),
                run.roomEpoch(),
                run.fencingToken(),
                registration.threadId(),
                registration.actorScopeHash(),
                registration.agentSessionId(),
                attempt.commandId(),
                run.agentRunId(),
                attempt.attemptId(),
                graphResult.outputHash(),
                proposal.sha256(),
                graphResult.checkpointId(),
                graphResult.cognitiveRevision(),
                run.processRevision(),
                state.epoch().roomRevision(),
                projection.roomPhase(),
                projection.lastCommandSequence(),
                profiles);
        String operationKey = IntakeFinalizationOperationKey.create(
                run.caseId(),
                run.roomEpoch(),
                registration.threadId(),
                attempt.commandId(),
                graphResult.outputHash());
        var unsigned = new IntakeGraphFinalizationRequest(
                operationKey,
                "0".repeat(64),
                authority,
                command.request().command(),
                graphResult,
                state.threadBinding(),
                state.initialSnapshot(),
                state.event(),
                proposal);
        return new IntakeGraphFinalizationRequest(
                operationKey,
                IntakeContractHashes.finalizationRequestHash(unsigned),
                authority,
                unsigned.command(),
                unsigned.result(),
                unsigned.threadBinding(),
                unsigned.initialSnapshot(),
                unsigned.event(),
                unsigned.proposalReference());
    }
}
