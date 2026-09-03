package com.example.dispute.workflow.shadow.intake;

import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory;
import com.example.dispute.workflow.activity.agent.AgentGraphCommandClient;
import com.example.dispute.workflow.activity.agent.AgentGraphReconciliationClient;
import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.ExecutionMode;
import com.example.dispute.workflow.application.intake.IntakeContractHashes;
import com.example.dispute.workflow.application.intake.IntakeGraphCommandFactory;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactOperationType;
import com.example.dispute.workflow.contract.v1.ContractTypes.GraphStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.GraphReconcileResponse;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticRuntimeSource.GraphArtifactQuery;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticRuntimeSource.GraphArtifacts;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticRuntimeSource.GraphInput;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityInvocationMode;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ImmutablePayloadRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.OperationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeGraphExecutionRef;
import java.time.Instant;
import java.util.Objects;

/** Builds the exact Intake command and invokes only the signed Graph client boundaries. */
public final class IntakeSyntheticSignedGraphExecutionAdapter
        implements IntakeSignedSyntheticGraphExecutionPort {

    private final IntakeSyntheticRuntimeSource source;
    private final IntakeGraphCommandFactory commandFactory;
    private final AgentRunCommandBindingFactory bindingFactory;
    private final AgentGraphCommandClient commandClient;
    private final AgentGraphReconciliationClient reconciliationClient;

    public IntakeSyntheticSignedGraphExecutionAdapter(
            IntakeSyntheticRuntimeSource source,
            IntakeGraphCommandFactory commandFactory,
            AgentRunCommandBindingFactory bindingFactory,
            AgentGraphCommandClient commandClient,
            AgentGraphReconciliationClient reconciliationClient) {
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.commandFactory = Objects.requireNonNull(commandFactory, "commandFactory must not be null");
        this.bindingFactory = Objects.requireNonNull(bindingFactory, "bindingFactory must not be null");
        this.commandClient = Objects.requireNonNull(commandClient, "commandClient must not be null");
        this.reconciliationClient =
                Objects.requireNonNull(reconciliationClient, "reconciliationClient must not be null");
    }

    @Override
    public GraphExecutionReceipt execute(GraphExecutionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        GraphInput input =
                Objects.requireNonNull(source.loadGraph(request), "Graph input must not be null");
        IntakeSyntheticRuntimeAuthority.requireMatches(input.authority(), request);
        requireFactoryInput(input.command(), request);

        RoomGraphCommand command =
                Objects.requireNonNull(commandFactory.create(input.command()), "Graph command must not be null");
        requireCommand(command, request);
        var binding = bindingFactory.bind(input.bindingContext(), command);
        ExecuteAgentRunRequest execution = new ExecuteAgentRunRequest(
                ExecuteAgentRunRequest.SCHEMA_VERSION,
                command.logicalRunId(),
                input.attemptNo(),
                input.attemptLimit(),
                "agent-stream.v2",
                binding.logicalInputHash(),
                input.previousAttemptId(),
                input.resetRequired(),
                input.publicSequenceOffset(),
                command);

        AgentRunCancellationToken cancellation = new AgentRunCancellationToken();
        GraphResponse response = executeSigned(request, execution, cancellation);
        requireResult(response.result(), command);
        GraphArtifacts artifacts = Objects.requireNonNull(
                source.loadGraphArtifacts(new GraphArtifactQuery(
                        request, command, response.result(), response.resultRef())),
                "Graph artifacts must not be null");
        IntakeSyntheticRuntimeAuthority.requireMatches(artifacts.authority(), request);
        requireArtifacts(artifacts, response.result(), response.resultRef());

        String resultHash = response.result().outputHash();
        var proposal = response.result().artifactOperations().getFirst().artifact();
        return new GraphExecutionReceipt(
                "intake-graph-execution-receipt.v1",
                new OperationReceipt(
                        "intake-operation-receipt.v1",
                        request.operationKey(),
                        request.requestHash(),
                        resultHash,
                        request.envelope().processRevision(),
                        request.envelope().roomRevision()),
                new IntakeAgentRunRef(
                        "intake-agent-run-ref.v1",
                        command.logicalRunId(),
                        command.attemptId(),
                        resultHash),
                new IntakeGraphExecutionRef(
                        "intake-graph-execution-ref.v1",
                        command.threadId(),
                        command.commandId(),
                        command.graphKey(),
                        command.graphVersion(),
                        response.result().checkpointId(),
                        artifacts.result().uri(),
                        resultHash,
                        proposal.uri(),
                        proposal.sha256()),
                artifacts.result(),
                artifacts.proposal());
    }

    private GraphResponse executeSigned(
            GraphExecutionRequest activityRequest,
            ExecuteAgentRunRequest execution,
            AgentRunCancellationToken cancellation) {
        if (activityRequest.envelope().invocation().mode()
                == ActivityInvocationMode.RECONCILE_ONLY) {
            GraphReconcileResponse reconciled = Objects.requireNonNull(
                    reconciliationClient.reconcile(execution, cancellation),
                    "Graph reconciliation response must not be null");
            requireReconciliation(reconciled, execution.command());
            return new GraphResponse(reconciled.result(), reconciled.resultRef());
        }

        TextFreeStreamObserver observer = new TextFreeStreamObserver(execution);
        RoomGraphResult result = Objects.requireNonNull(
                commandClient.execute(
                        execution,
                        ExecutionMode.EXECUTE_OR_RECONCILE,
                        observer::observe,
                        cancellation),
                "Graph result must not be null");
        return new GraphResponse(result, observer.requireFinalResultRef(result));
    }

    private static void requireFactoryInput(
            IntakeGraphCommandFactory.CommandRequest input, GraphExecutionRequest request) {
        var envelope = request.envelope();
        IntakeSyntheticRuntimeAuthority.requireRegistration(
                envelope, request.threadId(), request.agentSessionId(), input.threadBinding());
        IntakeSyntheticRuntimeAuthority.requireEqual(
                input.commandId(), envelope.commandId(), "Graph command id");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                input.processRevision(), envelope.processRevision(), "Graph process revision");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                input.stageSequence(), envelope.commandSequence(), "Graph stage sequence");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                input.deadlineAt(),
                Instant.ofEpochMilli(envelope.deadlineEpochMillis()),
                "Graph deadline");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                input.providerAttemptsRemaining(),
                envelope.retryBudget().providerAttemptsRemaining(),
                "Graph provider budget");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                input.activityAttemptsRemaining(),
                envelope.retryBudget().activityAttemptsRemaining(),
                "Graph Activity budget");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                input.repairsRemaining(),
                envelope.retryBudget().repairsRemaining(),
                "Graph repair budget");
    }

    private static void requireCommand(RoomGraphCommand command, GraphExecutionRequest request) {
        var envelope = request.envelope();
        IntakeSyntheticRuntimeAuthority.requireEqual(
                command.commandId(), envelope.commandId(), "created Graph command id");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                command.tenantSurrogate(), envelope.tenantSurrogate(), "created Graph tenant");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                command.caseId(), envelope.caseId(), "created Graph case");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                command.roomEpoch(), envelope.roomEpoch(), "created Graph room epoch");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                command.threadId(), request.threadId(), "created Graph thread");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                command.processRevision(), envelope.processRevision(), "created Graph process revision");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                command.graphVersion(),
                envelope.pinnedVersions().graphVersion(),
                "created Graph version");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                command.checkpointSchemaVersion(),
                envelope.pinnedVersions().checkpointSchemaVersion(),
                "created Graph checkpoint schema");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                command.invocationContext().promptProfileId(),
                envelope.pinnedVersions().promptVersion(),
                "created Graph prompt version");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                command.invocationContext().modelProfileId(),
                envelope.pinnedVersions().modelProfileId(),
                "created Graph model profile");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                command.invocationContext().outputSchemaVersion(),
                envelope.pinnedVersions().outputSchemaVersion(),
                "created Graph output schema");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                command.invocationContext().policyVersion(),
                envelope.pinnedVersions().policyVersion(),
                "created Graph policy version");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                command.invocationContext().guardrailVersion(),
                envelope.pinnedVersions().guardrailVersion(),
                "created Graph guardrail version");
    }

    private static void requireResult(RoomGraphResult result, RoomGraphCommand command) {
        IntakeSyntheticRuntimeAuthority.requireEqual(
                result.commandId(), command.commandId(), "Graph result command");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                result.logicalRunId(), command.logicalRunId(), "Graph result logical run");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                result.attemptId(), command.attemptId(), "Graph result attempt");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                result.graphKey(), command.graphKey(), "Graph result key");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                result.graphVersion(), command.graphVersion(), "Graph result version");
        if (result.status() == GraphStatus.FAILED || result.error() != null) {
            throw new IllegalStateException("synthetic Intake Graph returned a failed result");
        }
        if (!result.publicEventProposals().isEmpty()
                || result.artifactOperations().size() != 1
                || result.artifactOperations().getFirst().operation()
                        != ArtifactOperationType.PROPOSE_PATCH
                || !"intake-turn-proposal.v2"
                        .equals(result.artifactOperations().getFirst().artifact().schemaVersion())) {
            throw new SecurityException("synthetic Intake Graph crossed the proposal-only boundary");
        }
        IntakeSyntheticRuntimeAuthority.requireEqual(
                result.executionMetadata().promptVersion(),
                command.invocationContext().promptProfileId(),
                "Graph result prompt version");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                result.executionMetadata().modelProfileId(),
                command.invocationContext().modelProfileId(),
                "Graph result model profile");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                result.executionMetadata().schemaVersion(),
                command.invocationContext().outputSchemaVersion(),
                "Graph result output schema");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                result.executionMetadata().policyVersion(),
                command.invocationContext().policyVersion(),
                "Graph result policy version");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                result.executionMetadata().guardrailVersion(),
                command.invocationContext().guardrailVersion(),
                "Graph result guardrail version");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                result.outputHash(), IntakeContractHashes.graphResultHash(result), "Graph result hash");
    }

    private static void requireArtifacts(
            GraphArtifacts artifacts, RoomGraphResult result, String resultRef) {
        ImmutablePayloadRef resultPointer = artifacts.result();
        ImmutablePayloadRef proposalPointer = artifacts.proposal();
        var proposal = result.artifactOperations().getFirst().artifact();
        IntakeSyntheticRuntimeAuthority.requireEqual(
                resultPointer.artifactSchemaVersion(),
                "room-graph-result.v1",
                "Graph result object schema");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                resultPointer.artifactType(), "GRAPH_RESULT", "Graph result object type");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                resultPointer.uri(), resultRef, "Graph result object URI");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                resultPointer.contentHash(), result.outputHash(), "Graph result object hash");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                proposalPointer.artifactId(), proposal.artifactId(), "Graph proposal artifact id");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                proposalPointer.artifactSchemaVersion(),
                proposal.schemaVersion(),
                "Graph proposal schema");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                proposalPointer.artifactType(), "INTAKE_PROPOSAL", "Graph proposal object type");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                proposalPointer.uri(), proposal.uri(), "Graph proposal object URI");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                proposalPointer.contentHash(), proposal.sha256(), "Graph proposal object hash");
    }

    private static void requireReconciliation(
            GraphReconcileResponse response, RoomGraphCommand command) {
        IntakeSyntheticRuntimeAuthority.requireEqual(
                response.threadId(), command.threadId(), "reconciled Graph thread");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                response.commandId(), command.commandId(), "reconciled Graph command");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                response.requestHash(), command.requestHash(), "reconciled Graph request hash");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                response.logicalRunId(), command.logicalRunId(), "reconciled logical run");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                response.attemptId(), command.attemptId(), "reconciled attempt");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                response.graphKey(), command.graphKey(), "reconciled Graph key");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                response.graphVersion(), command.graphVersion(), "reconciled Graph version");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                response.checkpointSchemaVersion(),
                command.checkpointSchemaVersion(),
                "reconciled checkpoint schema");
        IntakeSyntheticRuntimeAuthority.requireEqual(
                response.resultHash(), response.result().outputHash(), "reconciled result hash");
    }

    private record GraphResponse(RoomGraphResult result, String resultRef) {}

    /** Retains no delta text; the signed client already validates the complete stream protocol. */
    private static final class TextFreeStreamObserver {
        private final ExecuteAgentRunRequest request;
        private String finalResultRef;
        private String finalResultHash;

        private TextFreeStreamObserver(ExecuteAgentRunRequest request) {
            this.request = request;
        }

        private void observe(com.example.dispute.workflow.contract.v1.AgentStreamEvent event) {
            IntakeSyntheticRuntimeAuthority.requireEqual(
                    event.runId(), request.logicalRunId(), "stream logical run");
            IntakeSyntheticRuntimeAuthority.requireEqual(
                    event.attemptId(), request.attemptId(), "stream attempt");
            IntakeSyntheticRuntimeAuthority.requireEqual(
                    event.audience(), request.command().actorScope().audience(), "stream audience");
            if (event.eventType() == StreamEventType.FINAL) {
                finalResultRef = event.payload().finalResultRef();
                finalResultHash = event.payload().finalResultHash();
            }
        }

        private String requireFinalResultRef(RoomGraphResult result) {
            IntakeSyntheticRuntimeAuthority.requireEqual(
                    finalResultHash, result.outputHash(), "stream final result hash");
            if (finalResultRef == null || finalResultRef.isBlank()) {
                throw new IllegalStateException("signed Graph stream returned no final result reference");
            }
            return finalResultRef;
        }
    }
}
