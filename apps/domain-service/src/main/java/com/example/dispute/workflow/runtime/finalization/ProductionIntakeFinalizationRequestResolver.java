package com.example.dispute.workflow.runtime.finalization;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter;
import com.example.dispute.workflow.application.intake.IntakeAgentRunFinalizationRequestResolver;
import com.example.dispute.workflow.application.intake.IntakeContractHashes;
import com.example.dispute.workflow.application.intake.IntakeFinalizationOperationKey;
import com.example.dispute.workflow.application.intake.IntakeGraphFinalizationRequest;
import com.example.dispute.workflow.application.intake.IntakeProposalReference;
import com.example.dispute.workflow.application.intake.IntakeTurnProposal;
import java.util.Objects;

/** Reconstructs the Java-trusted Intake finalization request from authorized persisted facts. */
public final class ProductionIntakeFinalizationRequestResolver
        implements IntakeAgentRunFinalizationRequestResolver {

    static final String TARGET_EXECUTION_OUTPUT_SCHEMA = "production-runtime-room-proposal-source.v2";
    static final String INTAKE_PROPOSAL_OUTPUT_SCHEMA = "intake-turn-proposal.v2";

    private final ProductionAuthorizedIntakeFinalizationSource source;
    private final ProductionIntakeProposalReferenceResolver proposalReferences;

    public ProductionIntakeFinalizationRequestResolver(
            ProductionAuthorizedIntakeFinalizationSource source,
            ProductionIntakeProposalReferenceResolver proposalReferences) {
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
        requireExactSchema(
                registration.outputSchemaVersion(),
                TARGET_EXECUTION_OUTPUT_SCHEMA,
                "target thread registration output schema");
        requireExactSchema(
                command.request().command().invocationContext().outputSchemaVersion(),
                TARGET_EXECUTION_OUTPUT_SCHEMA,
                "target graph invocation output schema");
        requireExactSchema(
                graphResult.executionMetadata().schemaVersion(),
                TARGET_EXECUTION_OUTPUT_SCHEMA,
                "target graph result output schema");
        IntakeProposalReference proposal = proposalReferences.resolve(
                ProductionAgentRunV2FinalizationFactsProvider.proposal(command.result()));
        var profiles = new IntakeTurnProposal.ProfileVersions(
                registration.graphVersion(),
                registration.checkpointSchemaVersion(),
                registration.promptVersion(),
                registration.modelProfileId(),
                INTAKE_PROPOSAL_OUTPUT_SCHEMA,
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
                profiles,
                TARGET_EXECUTION_OUTPUT_SCHEMA);
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

    private static void requireExactSchema(String actual, String expected, String field) {
        if (!expected.equals(actual)) {
            throw new ProductionFinalizationRejectedException(
                    "PRODUCTION_RUNTIME_INTAKE_SCHEMA_MISMATCH",
                    field + " is not pinned to the target execution schema");
        }
    }
}
