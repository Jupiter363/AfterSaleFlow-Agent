package com.example.dispute.workflow.shadow.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory;
import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory.Binding;
import com.example.dispute.workflow.activity.agent.AgentGraphCommandClient;
import com.example.dispute.workflow.activity.agent.AgentGraphReconciliationClient;
import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.ExecutionMode;
import com.example.dispute.workflow.application.intake.IntakeContractHashes;
import com.example.dispute.workflow.application.intake.IntakeDomainSnapshotPublisher;
import com.example.dispute.workflow.application.intake.IntakeDomainSnapshotPublisher.SnapshotRequest;
import com.example.dispute.workflow.application.intake.IntakeGraphBindingStore.WriteReceipt;
import com.example.dispute.workflow.application.intake.IntakeGraphCommandFactory;
import com.example.dispute.workflow.application.intake.IntakeGraphCommandFactory.CommandRequest;
import com.example.dispute.workflow.application.intake.IntakeGraphThreadBinding;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistration;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistrationFactory;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistrationFactory.IssueRequest;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistrationFactory.VersionPins;
import com.example.dispute.workflow.application.intake.IntakeSnapshotReference;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactOperationType;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.GraphStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.example.dispute.workflow.contract.v1.ContractTypes.Usage;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.GraphReconcileResponse;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.shadow.intake.IntakeShadowComparison.Classification;
import com.example.dispute.workflow.shadow.intake.IntakeShadowComparison.Dimension;
import com.example.dispute.workflow.shadow.intake.IntakeShadowComparison.ObservedValue;
import com.example.dispute.workflow.shadow.intake.IntakeShadowParityService.ParitySnapshot;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticRuntimeSource.ActivityAuthority;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticRuntimeSource.GraphArtifacts;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticRuntimeSource.GraphInput;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticRuntimeSource.ParityInput;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticRuntimeSource.SnapshotInput;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityEnvelope;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityInvocation;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityInvocationMode;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ImmutablePayloadRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.OperationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.PinnedVersions;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventType;
import com.example.dispute.workflow.temporal.room.intake.IntakeGraphExecutionRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeOperationKeys;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class IntakeSyntheticRuntimeAdaptersTest {

    private static final String THREAD = "grt.v1.018f6b7ec30a7430982fffc520c8195c";
    private static final String TENANT = "tenant-synthetic";
    private static final String CASE_ID = "CASE_P4_SYNTHETIC_1";
    private static final String SESSION = "AGENT_SESSION_P4_USER_1";
    private static final String COMMAND = "COMMAND_P4_SYNTHETIC_1";
    private static final String REQUEST_HASH = hash(1);
    private static final Instant DEADLINE = Instant.parse("2026-07-20T08:10:00Z");

    @Test
    void publishesThroughTheAuthoritativeSnapshotPublisherAndBuildsTheReceipt() {
        IntakeSyntheticRuntimeSource source = mock(IntakeSyntheticRuntimeSource.class);
        IntakeDomainSnapshotPublisher publisher = mock(IntakeDomainSnapshotPublisher.class);
        SnapshotPublicationRequest activity = snapshotActivity();
        SnapshotRequest publication = snapshotInput();
        IntakeSnapshotReference reference = snapshotReference(publication.threadBinding());
        when(source.loadSnapshot(activity))
                .thenReturn(new SnapshotInput(authority(activity), 4, publication));
        when(publisher.publish(publication)).thenReturn(WriteReceipt.created(reference));

        var adapter = new IntakeSyntheticSnapshotPublicationAdapter(source, publisher);
        var receipt = adapter.publish(activity);

        assertThat(receipt.operation().operationKey()).isEqualTo(activity.operationKey());
        assertThat(receipt.operation().requestHash()).isEqualTo(activity.requestHash());
        assertThat(receipt.operation().resultHash()).isEqualTo(reference.payloadRef().sha256());
        assertThat(receipt.snapshotPointer().objectVersion()).isEqualTo(reference.objectVersion());
        assertThat(receipt.snapshotPointer().sizeBytes()).isEqualTo(reference.payloadRef().sizeBytes());
        verify(publisher).publish(publication);
    }

    @Test
    void rejectsSnapshotAuthorityDriftBeforePublishingPrivateData() {
        IntakeSyntheticRuntimeSource source = mock(IntakeSyntheticRuntimeSource.class);
        IntakeDomainSnapshotPublisher publisher = mock(IntakeDomainSnapshotPublisher.class);
        SnapshotPublicationRequest activity = snapshotActivity();
        ActivityAuthority drift = new ActivityAuthority(
                envelope(ActivityInvocationMode.FIRST_EXECUTION, "other-command"),
                activity.threadId(),
                activity.agentSessionId(),
                activity.operationKey(),
                activity.requestHash());
        when(source.loadSnapshot(activity))
                .thenReturn(new SnapshotInput(drift, 4, snapshotInput()));

        var adapter = new IntakeSyntheticSnapshotPublicationAdapter(source, publisher);

        assertThatThrownBy(() -> adapter.publish(activity))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Activity envelope");
        verify(publisher, never()).publish(any());
    }

    @Test
    void buildsTheFactoryCommandInvokesTheSignedClientAndUsesPersistedObjectMetadata() {
        IntakeSyntheticRuntimeSource source = mock(IntakeSyntheticRuntimeSource.class);
        AgentGraphCommandClient client = mock(AgentGraphCommandClient.class);
        AgentGraphReconciliationClient reconciliation = mock(AgentGraphReconciliationClient.class);
        IntakeGraphCommandFactory commandFactory = new IntakeGraphCommandFactory();
        AgentRunCommandBindingFactory bindingFactory =
                new AgentRunCommandBindingFactory(JsonMapper.builder().findAndAddModules().build());
        GraphExecutionRequest activity = graphActivity(ActivityInvocationMode.FIRST_EXECUTION);
        CommandRequest factoryInput = commandInput();
        GraphInput input = new GraphInput(
                authority(activity),
                factoryInput,
                new AgentRunCommandBindingFactory.Context(
                        "ROOM_P4", "EPOCH_P4", "INTAKE_MESSAGE", "logical-key-p4"),
                1,
                3,
                null,
                false,
                0);
        RoomGraphResult result = graphResult(factoryInput);
        GraphArtifacts artifacts = graphArtifacts(authority(activity), result);
        when(source.loadGraph(activity)).thenReturn(input);
        when(source.loadGraphArtifacts(any())).thenReturn(artifacts);
        when(client.execute(
                        any(ExecuteAgentRunRequest.class),
                        eq(ExecutionMode.EXECUTE_OR_RECONCILE),
                        any(),
                        any(AgentRunCancellationToken.class)))
                .thenAnswer(invocation -> {
                    ExecuteAgentRunRequest execution = invocation.getArgument(0);
                    @SuppressWarnings("unchecked")
                    Consumer<AgentStreamEvent> sink = invocation.getArgument(2);
                    sink.accept(finalEvent(execution, result));
                    return result;
                });

        var adapter = new IntakeSyntheticSignedGraphExecutionAdapter(
                source, commandFactory, bindingFactory, client, reconciliation);
        GraphExecutionReceipt receipt = adapter.execute(activity);

        assertThat(receipt.agentRunRef().logicalRunId()).isEqualTo("RUN_P4_SYNTHETIC_1");
        assertThat(receipt.graphExecutionRef().checkpointId()).isEqualTo(result.checkpointId());
        assertThat(receipt.resultPointer()).isEqualTo(artifacts.result());
        assertThat(receipt.proposalPointer()).isEqualTo(artifacts.proposal());
        verify(client)
                .execute(
                        any(ExecuteAgentRunRequest.class),
                        eq(ExecutionMode.EXECUTE_OR_RECONCILE),
                        any(),
                        any(AgentRunCancellationToken.class));
        verify(reconciliation, never()).reconcile(any(), any());
    }

    @Test
    void rejectsGraphArtifactMetadataThatDoesNotBindTheSignedResult() {
        IntakeSyntheticRuntimeSource source = mock(IntakeSyntheticRuntimeSource.class);
        AgentGraphCommandClient client = mock(AgentGraphCommandClient.class);
        GraphExecutionRequest activity = graphActivity(ActivityInvocationMode.FIRST_EXECUTION);
        CommandRequest factoryInput = commandInput();
        RoomGraphResult result = graphResult(factoryInput);
        when(source.loadGraph(activity))
                .thenReturn(new GraphInput(
                        authority(activity),
                        factoryInput,
                        new AgentRunCommandBindingFactory.Context(
                                "ROOM_P4", "EPOCH_P4", "INTAKE_MESSAGE", "logical-key-p4"),
                        1,
                        3,
                        null,
                        false,
                        0));
        when(client.execute(any(), any(), any(), any())).thenAnswer(invocation -> {
            ExecuteAgentRunRequest execution = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Consumer<AgentStreamEvent> sink = invocation.getArgument(2);
            sink.accept(finalEvent(execution, result));
            return result;
        });
        GraphArtifacts valid = graphArtifacts(authority(activity), result);
        when(source.loadGraphArtifacts(any()))
                .thenReturn(new GraphArtifacts(
                        valid.authority(),
                        new ImmutablePayloadRef(
                                "immutable-payload-ref.v1",
                                valid.result().artifactId(),
                                "GRAPH_RESULT",
                                "room-graph-result.v1",
                                valid.result().uri(),
                                valid.result().objectVersion(),
                                hash(99),
                                valid.result().sizeBytes()),
                        valid.proposal()));
        var adapter = new IntakeSyntheticSignedGraphExecutionAdapter(
                source,
                new IntakeGraphCommandFactory(),
                new AgentRunCommandBindingFactory(
                        JsonMapper.builder().findAndAddModules().build()),
                client,
                mock(AgentGraphReconciliationClient.class));

        assertThatThrownBy(() -> adapter.execute(activity))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("result object hash");
    }

    @Test
    void reconciliationReadsOnlyTheSignedCachedGraphResult() {
        IntakeSyntheticRuntimeSource source = mock(IntakeSyntheticRuntimeSource.class);
        AgentGraphCommandClient client = mock(AgentGraphCommandClient.class);
        AgentGraphReconciliationClient reconciliation = mock(AgentGraphReconciliationClient.class);
        GraphExecutionRequest activity = graphActivity(ActivityInvocationMode.RECONCILE_ONLY);
        CommandRequest factoryInput = commandInput(0);
        RoomGraphCommand command = new IntakeGraphCommandFactory().create(factoryInput);
        RoomGraphResult result = graphResult(factoryInput);
        when(source.loadGraph(activity))
                .thenReturn(new GraphInput(
                        authority(activity),
                        factoryInput,
                        new AgentRunCommandBindingFactory.Context(
                                "ROOM_P4", "EPOCH_P4", "INTAKE_MESSAGE", "logical-key-p4"),
                        1,
                        3,
                        null,
                        false,
                        0));
        when(reconciliation.reconcile(any(), any()))
                .thenReturn(new GraphReconcileResponse(
                        "graph-reconcile-response.v1",
                        GraphReconcileResponse.Disposition.RETURN_CACHED,
                        command.threadId(),
                        command.commandId(),
                        command.requestHash(),
                        command.logicalRunId(),
                        command.attemptId(),
                        command.graphKey(),
                        command.graphVersion(),
                        command.checkpointSchemaVersion(),
                        "checkpoint-ns-p4",
                        result.checkpointId(),
                        "urn:intake:result:p4",
                        result.outputHash(),
                        hash(70),
                        "no-tools.v1",
                        result));
        when(source.loadGraphArtifacts(any()))
                .thenReturn(graphArtifacts(authority(activity), result));
        var adapter = new IntakeSyntheticSignedGraphExecutionAdapter(
                source,
                new IntakeGraphCommandFactory(),
                new AgentRunCommandBindingFactory(
                        JsonMapper.builder().findAndAddModules().build()),
                client,
                reconciliation);

        GraphExecutionReceipt receipt = adapter.execute(activity);

        assertThat(receipt.operation().resultHash()).isEqualTo(result.outputHash());
        verify(reconciliation).reconcile(any(), any());
        verify(client, never()).execute(any(), any(), any(), any());
    }

    @Test
    void returnsOnlyHashBasedParityAfterBindingItToTheExactGraphReceipt() {
        IntakeSyntheticRuntimeSource source = mock(IntakeSyntheticRuntimeSource.class);
        TurnFinalizationRequest request = finalizationActivity();
        ParitySnapshot legacy = parity(20);
        ParitySnapshot shadow = parity(30);
        when(source.loadParity(request))
                .thenReturn(new ParityInput(
                        authority(request),
                        request.graphExecution().operation().resultHash(),
                        request.graphExecution().graphExecutionRef().proposalHash(),
                        legacy,
                        shadow,
                        IntakeDomainEventType.TURN_READY_TO_CONFIRM));

        var observation = new IntakeSyntheticParityObservationAdapter(source).observe(request);

        assertThat(observation.legacy()).isEqualTo(legacy);
        assertThat(observation.shadow()).isEqualTo(shadow);
        assertThat(observation.projectedEventType())
                .isEqualTo(IntakeDomainEventType.TURN_READY_TO_CONFIRM);
        assertThat(observation.shadow().values().values())
                .allSatisfy(value -> assertThat(value.valueHash()).matches("[0-9a-f]{64}"));
    }

    @Test
    void rejectsParityThatIsNotBoundToTheExactGraphResult() {
        IntakeSyntheticRuntimeSource source = mock(IntakeSyntheticRuntimeSource.class);
        TurnFinalizationRequest request = finalizationActivity();
        when(source.loadParity(request))
                .thenReturn(new ParityInput(
                        authority(request),
                        hash(99),
                        request.graphExecution().graphExecutionRef().proposalHash(),
                        parity(20),
                        parity(30),
                        IntakeDomainEventType.TURN_NEEDS_INPUT));

        assertThatThrownBy(() -> new IntakeSyntheticParityObservationAdapter(source).observe(request))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("parity Graph result hash");
    }

    private static SnapshotPublicationRequest snapshotActivity() {
        ActivityEnvelope envelope = envelope(ActivityInvocationMode.FIRST_EXECUTION, COMMAND);
        return new SnapshotPublicationRequest(
                "intake-snapshot-publication-request.v1",
                envelope,
                THREAD,
                SESSION,
                4,
                IntakeOperationKeys.snapshotPublish(CASE_ID, 1, envelope.actorScopeHash(), 4),
                REQUEST_HASH);
    }

    private static GraphExecutionRequest graphActivity(ActivityInvocationMode mode) {
        ActivityEnvelope envelope = envelope(mode, COMMAND);
        return new GraphExecutionRequest(
                "intake-graph-execution-request.v1",
                envelope,
                THREAD,
                SESSION,
                IntakeOperationKeys.graphExecute(CASE_ID, 1, THREAD, COMMAND),
                REQUEST_HASH);
    }

    private static TurnFinalizationRequest finalizationActivity() {
        GraphExecutionRequest graph = graphActivity(ActivityInvocationMode.FIRST_EXECUTION);
        String resultHash = hash(40);
        String proposalHash = hash(41);
        GraphExecutionReceipt receipt = new GraphExecutionReceipt(
                "intake-graph-execution-receipt.v1",
                operation(graph.operationKey(), resultHash),
                new IntakeAgentRunRef(
                        "intake-agent-run-ref.v1", "RUN_P4_SYNTHETIC_1", "ATTEMPT_P4_1", resultHash),
                new IntakeGraphExecutionRef(
                        "intake-graph-execution-ref.v1",
                        THREAD,
                        COMMAND,
                        "intake.v2",
                        "2.0.0",
                        "CHECKPOINT_P4_1",
                        "urn:intake:result:p4",
                        resultHash,
                        "urn:intake:proposal:p4",
                        proposalHash),
                immutable(
                        "RESULT_P4",
                        "GRAPH_RESULT",
                        "room-graph-result.v1",
                        "urn:intake:result:p4",
                        resultHash),
                immutable(
                        "PROPOSAL_P4",
                        "INTAKE_PROPOSAL",
                        "intake-turn-proposal.v2",
                        "urn:intake:proposal:p4",
                        proposalHash));
        return new TurnFinalizationRequest(
                "intake-turn-finalization-request.v1",
                graph.envelope(),
                THREAD,
                SESSION,
                receipt,
                IntakeOperationKeys.turnFinalize(CASE_ID, 1, THREAD, COMMAND, resultHash),
                REQUEST_HASH);
    }

    private static ActivityEnvelope envelope(ActivityInvocationMode mode, String commandId) {
        int activityAttempts = mode == ActivityInvocationMode.RECONCILE_ONLY ? 0 : 1;
        return new ActivityEnvelope(
                "intake-activity-envelope.v1",
                TENANT,
                CASE_ID,
                1,
                2,
                commandId,
                1,
                IntakeCommandType.INTAKE_MESSAGE,
                IntakeParty.INITIATOR,
                binding().registration().actorScopeHash(),
                "urn:intake:command:" + commandId,
                hash(2),
                7,
                2,
                DEADLINE.toEpochMilli(),
                new RetryBudget("intake-retry-budget.v1", 2, activityAttempts, 1),
                new PinnedVersions(
                        "intake-pinned-versions.v1",
                        "intake-workflow.synthetic.v1",
                        "2.0.0",
                        "intake-checkpoint.v2",
                        "intake-prompt.v2",
                        "intake-model.synthetic.v1",
                        "intake-turn-proposal.v2",
                        "intake-policy.v2",
                        "intake-guardrail.v2",
                        "no-tools.v1"),
                new ActivityInvocation(
                        "intake-activity-invocation.v1",
                        mode,
                        mode == ActivityInvocationMode.RECONCILE_ONLY ? 0 : 2));
    }

    private static SnapshotRequest snapshotInput() {
        return new SnapshotRequest(
                "SNAPSHOT_P4_1",
                binding(),
                4,
                2,
                4,
                List.of("SOURCE_P4_1"),
                JsonMapper.builder().build().createObjectNode().put("case_id", CASE_ID),
                JsonMapper.builder().build().createObjectNode().put("intake_status", "OPEN"),
                List.of(),
                JsonMapper.builder().build().createObjectNode().put("schema_version", "intake-dossier.v2"),
                Instant.parse("2026-07-20T08:01:00Z"));
    }

    private static CommandRequest commandInput() {
        return commandInput(1);
    }

    private static CommandRequest commandInput(int activityAttemptsRemaining) {
        IntakeGraphThreadBinding binding = binding();
        return new CommandRequest(
                COMMAND,
                "RUN_P4_SYNTHETIC_1",
                "ATTEMPT_P4_1",
                binding,
                snapshotReference(binding),
                null,
                7,
                "INTAKE_MESSAGE",
                1,
                "intake-agent-profile.v2",
                2,
                activityAttemptsRemaining,
                1,
                DEADLINE,
                "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
                "synthetic-key.v1",
                "synthetic-nonce.v1");
    }

    private static IntakeGraphThreadBinding binding() {
        IntakePrivateThreadRegistrationFactory factory =
                new IntakePrivateThreadRegistrationFactory(() -> THREAD);
        return factory.issue(new IssueRequest(
                "REG_P4_INTAKE_USER_1",
                TENANT,
                CASE_ID,
                1,
                2,
                new IntakePrivateThreadRegistration.ActorScope(
                        "user-synthetic",
                        ActorRole.USER,
                        Audience.USER,
                        List.of("graph.command.execute")),
                SESSION,
                new VersionPins(
                        "2.0.0",
                        "intake-checkpoint.v2",
                        "intake-prompt.v2",
                        "intake-model.synthetic.v1",
                        "intake-policy.v2",
                        "intake-guardrail.v2",
                        "no-tools.v1"),
                WriterMode.SHADOW,
                Instant.parse("2026-07-20T08:00:00Z")));
    }

    private static IntakeSnapshotReference snapshotReference(IntakeGraphThreadBinding binding) {
        var registration = binding.registration();
        return new IntakeSnapshotReference(
                "SNAPSHOT_P4_1",
                registration.registrationId(),
                TENANT,
                CASE_ID,
                1,
                2,
                THREAD,
                registration.actorScopeHash(),
                SESSION,
                new RoomGraphCommand.SnapshotRef(
                        "SNAPSHOT_P4_1",
                        "intake-domain-snapshot.v2",
                        "urn:intake:snapshot:p4",
                        hash(3),
                        512),
                "version-1",
                4,
                2,
                4,
                0,
                Instant.parse("2026-07-20T08:01:00Z"));
    }

    private static RoomGraphResult graphResult(CommandRequest input) {
        RoomGraphCommand command = new IntakeGraphCommandFactory().create(input);
        ArtifactPointer proposal = new ArtifactPointer(
                "PROPOSAL_P4",
                "intake-turn-proposal.v2",
                "urn:intake:proposal:p4",
                hash(41));
        RoomGraphResult unsigned = new RoomGraphResult(
                "room-graph-result.v1",
                command.commandId(),
                command.logicalRunId(),
                command.attemptId(),
                command.graphKey(),
                command.graphVersion(),
                "CHECKPOINT_P4_1",
                1,
                GraphStatus.COMPLETED,
                List.of(),
                List.of(new RoomGraphResult.ArtifactOperation(
                        ArtifactOperationType.PROPOSE_PATCH, proposal)),
                null,
                null,
                null,
                hash(0),
                new Usage(1, 1, 2),
                new RoomGraphResult.ExecutionMetadata(
                        "intake-prompt.v2",
                        "intake-model.synthetic.v1",
                        "intake-turn-proposal.v2",
                        "intake-policy.v2",
                        "intake-guardrail.v2"));
        return new RoomGraphResult(
                unsigned.schemaVersion(),
                unsigned.commandId(),
                unsigned.logicalRunId(),
                unsigned.attemptId(),
                unsigned.graphKey(),
                unsigned.graphVersion(),
                unsigned.checkpointId(),
                unsigned.cognitiveRevision(),
                unsigned.status(),
                unsigned.publicEventProposals(),
                unsigned.artifactOperations(),
                unsigned.needsInput(),
                unsigned.needsReview(),
                unsigned.error(),
                IntakeContractHashes.graphResultHash(unsigned),
                unsigned.usage(),
                unsigned.executionMetadata());
    }

    private static GraphArtifacts graphArtifacts(
            ActivityAuthority authority, RoomGraphResult result) {
        var proposal = result.artifactOperations().getFirst().artifact();
        return new GraphArtifacts(
                authority,
                immutable(
                        "RESULT_P4",
                        "GRAPH_RESULT",
                        "room-graph-result.v1",
                        "urn:intake:result:p4",
                        result.outputHash()),
                immutable(
                        proposal.artifactId(),
                        "INTAKE_PROPOSAL",
                        proposal.schemaVersion(),
                        proposal.uri(),
                        proposal.sha256()));
    }

    private static AgentStreamEvent finalEvent(
            ExecuteAgentRunRequest execution, RoomGraphResult result) {
        return new AgentStreamEvent(
                "agent-stream.v2",
                execution.logicalRunId(),
                execution.attemptId(),
                1,
                StreamEventType.FINAL,
                Audience.USER,
                Instant.parse("2026-07-20T08:02:00Z"),
                new AgentStreamEvent.Payload(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "urn:intake:result:p4",
                        result.outputHash(),
                        null,
                        null));
    }

    private static ImmutablePayloadRef immutable(
            String id, String type, String schema, String uri, String hash) {
        return new ImmutablePayloadRef(
                "immutable-payload-ref.v1", id, type, schema, uri, "version-1", hash, 512);
    }

    private static OperationReceipt operation(String operationKey, String resultHash) {
        return new OperationReceipt(
                "intake-operation-receipt.v1", operationKey, REQUEST_HASH, resultHash, 7, 2);
    }

    private static ActivityAuthority authority(SnapshotPublicationRequest request) {
        return new ActivityAuthority(
                request.envelope(),
                request.threadId(),
                request.agentSessionId(),
                request.operationKey(),
                request.requestHash());
    }

    private static ActivityAuthority authority(GraphExecutionRequest request) {
        return new ActivityAuthority(
                request.envelope(),
                request.threadId(),
                request.agentSessionId(),
                request.operationKey(),
                request.requestHash());
    }

    private static ActivityAuthority authority(TurnFinalizationRequest request) {
        return new ActivityAuthority(
                request.envelope(),
                request.threadId(),
                request.agentSessionId(),
                request.operationKey(),
                request.requestHash());
    }

    private static ParitySnapshot parity(int offset) {
        EnumMap<Dimension, ObservedValue> values = new EnumMap<>(Dimension.class);
        for (Dimension dimension : Dimension.values()) {
            values.put(
                    dimension,
                    new ObservedValue(Classification.VALUE, hash(offset + dimension.ordinal())));
        }
        return new ParitySnapshot(values, Set.of());
    }

    private static String hash(int value) {
        return String.format("%064x", value);
    }
}
