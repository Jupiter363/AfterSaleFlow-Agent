package com.example.dispute.workflow.room.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.temporal.room.intake.IntakeActivityFailureTypes;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityEnvelope;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchOperation;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.FormalFinalizationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ImmutablePayloadRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.OperationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.PinnedVersions;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ReplayDisposition;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventType;
import com.example.dispute.workflow.temporal.room.intake.IntakeGraphExecutionRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeOperationKeys;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomActivities;
import io.temporal.api.enums.v1.TimeoutType;
import io.temporal.activity.ActivityMethod;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.TimeoutFailure;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class IntakeRoomActivityContractTest {

  private static final String CASE_ID = "CASE_P4_B2";
  private static final long ROOM_EPOCH = 3;
  private static final String COMMAND_ID = "COMMAND_P4_B2";
  private static final String THREAD_ID = "grt.v1." + "a".repeat(32);
  private static final String ACTOR_SCOPE_HASH = "b".repeat(64);
  private static final String REQUEST_HASH = "c".repeat(64);
  private static final String RESULT_HASH = "d".repeat(64);
  private static final String PROPOSAL_HASH = "e".repeat(64);

  @Test
  void exposesSevenExplicitStableActivityMethods() {
    Map<String, String> methods =
        Arrays.stream(IntakeRoomActivities.class.getDeclaredMethods())
            .collect(
                Collectors.toMap(
                    method -> method.getName(),
                    method -> method.getAnnotation(ActivityMethod.class).name()));

    assertThat(methods)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "publishSnapshot", "PublishIntakeSnapshot",
                "executeGraph", "ExecuteIntakeGraph",
                "finalizeTurn", "FinalizeIntakeTurn",
                "acceptInitiator", "CommitIntakeInitiatorAcceptance",
                "rejectInitiator", "CommitIntakeInitiatorRejection",
                "cancelIntake", "CommitIntakeCancellation",
                "confirmRespondent", "CommitIntakeRespondentConfirmation"));
  }

  @Test
  void typedRequestsBindEachStageToItsExactOperationKey() {
    ActivityEnvelope message = envelope(IntakeCommandType.INTAKE_MESSAGE, IntakeParty.INITIATOR);
    SnapshotPublicationRequest snapshot =
        new SnapshotPublicationRequest(
            "intake-snapshot-publication-request.v1",
            message,
            THREAD_ID,
            "AGENT_SESSION_P4_B2",
            17,
            IntakeOperationKeys.snapshotPublish(CASE_ID, ROOM_EPOCH, ACTOR_SCOPE_HASH, 17),
            REQUEST_HASH);
    GraphExecutionRequest graph =
        new GraphExecutionRequest(
            "intake-graph-execution-request.v1",
            message,
            THREAD_ID,
            "AGENT_SESSION_P4_B2",
            IntakeOperationKeys.graphExecute(CASE_ID, ROOM_EPOCH, THREAD_ID, COMMAND_ID),
            REQUEST_HASH);
    TurnFinalizationRequest finalization =
        new TurnFinalizationRequest(
            "intake-turn-finalization-request.v1",
            message,
            THREAD_ID,
            "AGENT_SESSION_P4_B2",
            graphExecutionReceipt(),
            IntakeOperationKeys.turnFinalize(
                CASE_ID, ROOM_EPOCH, THREAD_ID, COMMAND_ID, RESULT_HASH),
            REQUEST_HASH);

    assertThat(snapshot.operationKey()).startsWith("intake.snapshot.publish:");
    assertThat(graph.operationKey()).startsWith("intake.graph.execute:");
    assertThat(finalization.operationKey()).startsWith("intake.turn.finalize:");

    assertThatThrownBy(
            () ->
                new GraphExecutionRequest(
                    "intake-graph-execution-request.v1",
                    message,
                    THREAD_ID,
                    "AGENT_SESSION_P4_B2",
                    snapshot.operationKey(),
                    REQUEST_HASH))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("operationKey");
  }

  @Test
  void branchRequestsBindTypePartyAndFrozenKeyWithoutSharingOnePendingKey() {
    BranchCommitRequest accept =
        branchRequest(
            BranchOperation.INITIATOR_ACCEPT,
            IntakeCommandType.INTAKE_CONFIRM,
            IntakeParty.INITIATOR,
            IntakeOperationKeys.initiatorAccept(CASE_ID, ROOM_EPOCH, COMMAND_ID));
    BranchCommitRequest reject =
        branchRequest(
            BranchOperation.INITIATOR_REJECT,
            IntakeCommandType.INTAKE_CONFIRM,
            IntakeParty.INITIATOR,
            IntakeOperationKeys.initiatorReject(CASE_ID, ROOM_EPOCH, COMMAND_ID));
    BranchCommitRequest cancel =
        branchRequest(
            BranchOperation.CANCEL,
            IntakeCommandType.INTAKE_CANCEL,
            IntakeParty.INITIATOR,
            IntakeOperationKeys.cancel(CASE_ID, ROOM_EPOCH, COMMAND_ID));
    BranchCommitRequest respondent =
        branchRequest(
            BranchOperation.RESPONDENT_CONFIRM,
            IntakeCommandType.INTAKE_CONFIRM,
            IntakeParty.RESPONDENT,
            IntakeOperationKeys.respondentConfirm(CASE_ID, ROOM_EPOCH, COMMAND_ID));

    assertThat(
            Set.of(
                accept.operationKey(),
                reject.operationKey(),
                cancel.operationKey(),
                respondent.operationKey()))
        .hasSize(4);
    assertThatThrownBy(
            () ->
                branchRequest(
                    BranchOperation.RESPONDENT_CONFIRM,
                    IntakeCommandType.INTAKE_CONFIRM,
                    IntakeParty.INITIATOR,
                    IntakeOperationKeys.respondentConfirm(CASE_ID, ROOM_EPOCH, COMMAND_ID)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("RESPONDENT");
  }

  @Test
  void receiptsEncodeReplayAndConflictSemantics() {
    OperationReceipt receipt =
        operationReceipt(
            IntakeOperationKeys.turnFinalize(
                CASE_ID, ROOM_EPOCH, THREAD_ID, COMMAND_ID, RESULT_HASH));

    assertThat(receipt.replayDisposition(receipt.operationKey(), receipt.requestHash()))
        .isEqualTo(ReplayDisposition.REPLAY_COMMITTED);
    assertThat(receipt.replayDisposition(receipt.operationKey(), "f".repeat(64)))
        .isEqualTo(ReplayDisposition.CONFLICT);
    assertThat(
            receipt.replayDisposition(
                IntakeOperationKeys.cancel(CASE_ID, ROOM_EPOCH, COMMAND_ID), REQUEST_HASH))
        .isEqualTo(ReplayDisposition.DIFFERENT_OPERATION);

    SnapshotPublicationReceipt snapshot =
        new SnapshotPublicationReceipt(
            "intake-snapshot-publication-receipt.v1",
            operationReceipt(
                IntakeOperationKeys.snapshotPublish(CASE_ID, ROOM_EPOCH, ACTOR_SCOPE_HASH, 17)),
            snapshotPointer(),
            17);
    GraphExecutionReceipt graph = graphExecutionReceipt();
    assertThat(snapshot.operation().requestHash()).isEqualTo(REQUEST_HASH);
    assertThat(graph.graphExecutionRef().resultHash()).isEqualTo(RESULT_HASH);
    assertThat(snapshot.snapshotPointer().objectVersion()).isEqualTo("VERSION_SNAPSHOT_P4_B2");
    assertThat(snapshot.snapshotPointer().artifactId()).isEqualTo("SNAPSHOT_P4_B2");
    assertThat(graph.resultPointer().sizeBytes()).isEqualTo(4096);
    assertThat(graph.proposalPointer().sizeBytes()).isEqualTo(2048);
  }

  @Test
  void finalizationAndBranchReceiptsCarryCommittedAuthorityAndEvents() {
    IntakeDomainEventRef turnEvent =
        domainEvent(
            "EVENT_TURN_P4_B2",
            7,
            IntakeDomainEventType.TURN_READY_TO_CONFIRM,
            IntakeParty.INITIATOR,
            IntakeOperationKeys.turnFinalize(
                CASE_ID, ROOM_EPOCH, THREAD_ID, COMMAND_ID, RESULT_HASH),
            agentRunRef(),
            graphExecutionRef());
    TurnFinalizationReceipt finalization =
        new TurnFinalizationReceipt(
            "intake-turn-finalization-activity-receipt.v1",
            operationReceipt(turnEvent.operationKey()),
            formalFinalizationReceipt(turnEvent),
            turnEvent);
    assertThat(finalization.formalReceipt().domainEventIds()).containsExactly(turnEvent.eventId());
    assertThat(finalization.committedEvent().eventSequence()).isEqualTo(7);
    assertThat(finalization.formalReceipt().formalMessageId()).isEqualTo("MESSAGE_AGENT_P4_B2");

    IntakeDomainEventRef branchEvent =
        domainEvent(
            "EVENT_ACCEPT_P4_B2",
            8,
            IntakeDomainEventType.INITIATOR_ACCEPTED,
            IntakeParty.INITIATOR,
            IntakeOperationKeys.initiatorAccept(CASE_ID, ROOM_EPOCH, COMMAND_ID),
            null,
            null);
    BranchCommitReceipt branch =
        new BranchCommitReceipt(
            "intake-branch-commit-receipt.v1",
            BranchOperation.INITIATOR_ACCEPT,
            operationReceipt(branchEvent.operationKey()),
            branchEvent);
    assertThat(branch.committedEvent().eventType())
        .isEqualTo(IntakeDomainEventType.INITIATOR_ACCEPTED);
  }

  @Test
  void finalizationReceiptRequiresTheExactSessionAndCompleteGraphExecutionIdentity() {
    ActivityEnvelope message = envelope(IntakeCommandType.INTAKE_MESSAGE, IntakeParty.INITIATOR);
    GraphExecutionReceipt graph = graphExecutionReceipt();
    TurnFinalizationRequest request =
        new TurnFinalizationRequest(
            "intake-turn-finalization-request.v1",
            message,
            THREAD_ID,
            "AGENT_SESSION_P4_B2",
            graph,
            IntakeOperationKeys.turnFinalize(
                CASE_ID, ROOM_EPOCH, THREAD_ID, COMMAND_ID, RESULT_HASH),
            REQUEST_HASH);
    IntakeDomainEventRef event =
        domainEvent(
            "EVENT_EXACT_P4_B2",
            7,
            IntakeDomainEventType.TURN_READY_TO_CONFIRM,
            IntakeParty.INITIATOR,
            request.operationKey(),
            agentRunRef(),
            graphExecutionRef());
    TurnFinalizationReceipt exact =
        new TurnFinalizationReceipt(
            "intake-turn-finalization-activity-receipt.v1",
            operationReceipt(request.operationKey()),
            formalFinalizationReceipt(event),
            event);

    exact.requireMatches(request);

    TurnFinalizationReceipt wrongSession =
        new TurnFinalizationReceipt(
            exact.schemaVersion(),
            exact.operation(),
            formalFinalizationReceipt(event, "AGENT_SESSION_OTHER"),
            event);
    assertThatThrownBy(() -> wrongSession.requireMatches(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact request");

    IntakeGraphExecutionRef wrongCheckpoint =
        new IntakeGraphExecutionRef(
            "intake-graph-execution-ref.v1",
            THREAD_ID,
            COMMAND_ID,
            "intake.v2",
            "2.0.0",
            "CHECKPOINT_OTHER",
            graphExecutionRef().resultRef(),
            RESULT_HASH,
            graphExecutionRef().proposalRef(),
            PROPOSAL_HASH);
    IntakeDomainEventRef wrongGraphEvent =
        domainEvent(
            "EVENT_WRONG_GRAPH_P4_B2",
            7,
            IntakeDomainEventType.TURN_READY_TO_CONFIRM,
            IntakeParty.INITIATOR,
            request.operationKey(),
            agentRunRef(),
            wrongCheckpoint);
    TurnFinalizationReceipt wrongGraph =
        new TurnFinalizationReceipt(
            exact.schemaVersion(),
            exact.operation(),
            formalFinalizationReceipt(wrongGraphEvent),
            wrongGraphEvent);
    assertThatThrownBy(() -> wrongGraph.requireMatches(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact request");
  }

  @Test
  void keepsTheFrozenFormalReceiptSeparateFromTheActivityWrapper() {
    Set<String> frozenFields =
        Arrays.stream(FormalFinalizationReceipt.class.getRecordComponents())
            .map(RecordComponent::getName)
            .collect(Collectors.toSet());

    assertThat(frozenFields)
        .containsExactlyInAnyOrder(
            "schemaVersion",
            "operationKey",
            "tenantSurrogate",
            "caseId",
            "roomEpoch",
            "threadId",
            "actorScopeHash",
            "agentSessionId",
            "commandId",
            "logicalRunId",
            "attemptId",
            "resultHash",
            "proposalHash",
            "processRevision",
            "roomRevision",
            "fencingToken",
            "formalMessageId",
            "dossierVersion",
            "matrixVersion",
            "domainEventIds",
            "outboxIds",
            "status",
            "committedAt",
            "receiptHash");
    assertThat(frozenFields).doesNotContain("operation", "committedEvent");
    assertThat(
            Arrays.stream(TurnFinalizationReceipt.class.getRecordComponents())
                .map(RecordComponent::getName))
        .containsExactly("schemaVersion", "operation", "formalReceipt", "committedEvent");
  }

  @Test
  void failureTaxonomyRetriesOnlyInfrastructureAndUnknownTypesFailClosed() {
    assertThat(
            IntakeActivityFailureTypes.isRetryable(
                IntakeActivityFailureTypes.INFRASTRUCTURE_RETRYABLE))
        .isTrue();
    assertThat(IntakeActivityFailureTypes.isRetryable(IntakeActivityFailureTypes.STALE_FENCE))
        .isFalse();
    assertThat(IntakeActivityFailureTypes.isNonRetryable(IntakeActivityFailureTypes.BUSINESS))
        .isTrue();
    assertThat(IntakeActivityFailureTypes.isNonRetryable("UNRECOGNIZED_FAILURE")).isTrue();

    TimeoutFailure timeout =
        new TimeoutFailure(
            "synthetic heartbeat timeout", null, TimeoutType.TIMEOUT_TYPE_HEARTBEAT);
    RuntimeException nested =
        new RuntimeException(
            "outer worker failure",
            ApplicationFailure.newFailureWithCause(
                "timeout wrapper", "UNRECOGNIZED_FAILURE", timeout));
    assertThat(IntakeActivityFailureTypes.classify(nested))
        .isEqualTo(IntakeActivityFailureTypes.INFRASTRUCTURE_RETRYABLE);
    assertThat(
            IntakeActivityFailureTypes.classify(
                ApplicationFailure.newFailure("unknown", "UNRECOGNIZED_FAILURE")))
        .isEqualTo(IntakeActivityFailureTypes.UNCLASSIFIED);
    assertThat(IntakeActivityFailureTypes.classify(new RuntimeException("unknown")))
        .isEqualTo(IntakeActivityFailureTypes.UNCLASSIFIED);
  }

  @Test
  void temporalActivityPayloadRecordsRemainReferenceOnly() {
    Set<String> forbiddenComponents =
        Set.of(
            "text",
            "messageText",
            "payload",
            "proposal",
            "snapshotBody",
            "resultJson",
            "dossier",
            "memoryFrame");
    for (Class<?> type : IntakeActivityProtocol.payloadTypes()) {
      assertThat(type.isRecord()).as(type.getSimpleName()).isTrue();
      assertThat(
              Arrays.stream(type.getRecordComponents())
                  .map(RecordComponent::getName)
                  .collect(Collectors.toSet()))
          .as(type.getSimpleName())
          .doesNotContainAnyElementsOf(forbiddenComponents);
    }
  }

  @Test
  void frozenFinalizerKeyPassesThroughTheCommittedEventReference() {
    String frozenKey =
        "intake.turn.finalize:CASE_P4_SYNTHETIC_1:1:"
            + "grt.v1.018f6b7ec30a7430982fffc520c8195c:"
            + "COMMAND_P4_USER_2:"
            + "a".repeat(64);
    IntakeAgentRunRef run =
        new IntakeAgentRunRef(
            "intake-agent-run-ref.v1", "RUN_P4_USER_2", "ATTEMPT_P4_USER_2_1", "a".repeat(64));
    IntakeGraphExecutionRef graph =
        new IntakeGraphExecutionRef(
            "intake-graph-execution-ref.v1",
            "grt.v1.018f6b7ec30a7430982fffc520c8195c",
            "COMMAND_P4_USER_2",
            "intake.v2",
            "2.0.0",
            "CHECKPOINT_P4_USER_2",
            "urn:after-sale-flow:graph-result:RESULT_P4_USER_2",
            "a".repeat(64),
            "urn:after-sale-flow:intake-proposal:PROPOSAL_P4_USER_2",
            PROPOSAL_HASH);
    IntakeDomainEventRef event =
        new IntakeDomainEventRef(
            "intake-domain-event-ref.v1",
            "EVENT_P4_USER_2",
            "urn:after-sale-flow:intake-event:EVENT_P4_USER_2",
            "8".repeat(64),
            2,
            IntakeDomainEventType.TURN_READY_TO_CONFIRM,
            IntakeParty.INITIATOR,
            "COMMAND_P4_USER_2",
            "tenant-synthetic",
            "CASE_P4_SYNTHETIC_1",
            1,
            2,
            ACTOR_SCOPE_HASH,
            frozenKey,
            REQUEST_HASH,
            "a".repeat(64),
            6,
            3,
            run,
            graph);

    assertThat(event.operationKey()).isEqualTo(frozenKey);
  }

  @Test
  void versionsAndRetryBudgetStayWithinFrozenContracts() {
    assertThatThrownBy(() -> new RetryBudget("intake-retry-budget.v1", 3, 3, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("2/3/1");
    assertThatThrownBy(
            () ->
                new PinnedVersions(
                    "intake-pinned-versions.v1",
                    "intake-workflow.synthetic.v1",
                    "2.0.0",
                    "intake-checkpoint.v2",
                    "intake-prompt.v2",
                    "intake-model.synthetic.v1",
                    "wrong-output.v1",
                    "intake-policy.v2",
                    "intake-guardrail.v2",
                    "no-tools.v1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("intake-turn-proposal.v2");
    assertThatThrownBy(
            () ->
                new ImmutablePayloadRef(
                    "immutable-payload-ref.v1",
                    "A".repeat(129),
                    "INTAKE_PROPOSAL",
                    "intake-turn-proposal.v2",
                    "urn:after-sale-flow:intake-proposal:TOO_LONG",
                    "VERSION_TOO_LONG",
                    PROPOSAL_HASH,
                    1024))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("artifactId");
  }

  private static ActivityEnvelope envelope(IntakeCommandType commandType, IntakeParty party) {
    return new ActivityEnvelope(
        "intake-activity-envelope.v1",
        "tenant-p4-b2",
        CASE_ID,
        ROOM_EPOCH,
        11,
        COMMAND_ID,
        5,
        commandType,
        party,
        ACTOR_SCOPE_HASH,
        "urn:after-sale-flow:intake-command:COMMAND_P4_B2",
        "2".repeat(64),
        19,
        13,
        1_800_000_000_000L,
        new RetryBudget("intake-retry-budget.v1", 2, 3, 1),
        pinnedVersions());
  }

  private static PinnedVersions pinnedVersions() {
    return new PinnedVersions(
        "intake-pinned-versions.v1",
        "intake-workflow.synthetic.v1",
        "2.0.0",
        "intake-checkpoint.v2",
        "intake-prompt.v2",
        "intake-model.synthetic.v1",
        "intake-turn-proposal.v2",
        "intake-policy.v2",
        "intake-guardrail.v2",
        "no-tools.v1");
  }

  private static BranchCommitRequest branchRequest(
      BranchOperation operation,
      IntakeCommandType commandType,
      IntakeParty party,
      String operationKey) {
    return new BranchCommitRequest(
        "intake-branch-commit-request.v1",
        envelope(commandType, party),
        operation,
        operationKey,
        REQUEST_HASH);
  }

  private static OperationReceipt operationReceipt(String operationKey) {
    return new OperationReceipt(
        "intake-operation-receipt.v1", operationKey, REQUEST_HASH, RESULT_HASH, 20, 14);
  }

  private static IntakeAgentRunRef agentRunRef() {
    return new IntakeAgentRunRef(
        "intake-agent-run-ref.v1", "RUN_P4_B2", "ATTEMPT_P4_B2", RESULT_HASH);
  }

  private static IntakeGraphExecutionRef graphExecutionRef() {
    return new IntakeGraphExecutionRef(
        "intake-graph-execution-ref.v1",
        THREAD_ID,
        COMMAND_ID,
        "intake.v2",
        "2.0.0",
        "CHECKPOINT_P4_B2",
        "urn:after-sale-flow:graph-result:RESULT_P4_B2",
        RESULT_HASH,
        "urn:after-sale-flow:intake-proposal:PROPOSAL_P4_B2",
        PROPOSAL_HASH);
  }

  private static GraphExecutionReceipt graphExecutionReceipt() {
    return new GraphExecutionReceipt(
        "intake-graph-execution-receipt.v1",
        operationReceipt(
            IntakeOperationKeys.graphExecute(CASE_ID, ROOM_EPOCH, THREAD_ID, COMMAND_ID)),
        agentRunRef(),
        graphExecutionRef(),
        resultPointer(),
        proposalPointer());
  }

  private static ImmutablePayloadRef snapshotPointer() {
    return new ImmutablePayloadRef(
        "immutable-payload-ref.v1",
        "SNAPSHOT_P4_B2",
        "INTAKE_SNAPSHOT",
        "intake-domain-snapshot.v2",
        "urn:after-sale-flow:intake-snapshot:SNAPSHOT_P4_B2",
        "VERSION_SNAPSHOT_P4_B2",
        "1".repeat(64),
        8192);
  }

  private static ImmutablePayloadRef resultPointer() {
    return new ImmutablePayloadRef(
        "immutable-payload-ref.v1",
        "RESULT_P4_B2",
        "GRAPH_RESULT",
        "room-graph-result.v1",
        "urn:after-sale-flow:graph-result:RESULT_P4_B2",
        "VERSION_RESULT_P4_B2",
        RESULT_HASH,
        4096);
  }

  private static ImmutablePayloadRef proposalPointer() {
    return new ImmutablePayloadRef(
        "immutable-payload-ref.v1",
        "PROPOSAL_P4_B2",
        "INTAKE_PROPOSAL",
        "intake-turn-proposal.v2",
        "urn:after-sale-flow:intake-proposal:PROPOSAL_P4_B2",
        "VERSION_PROPOSAL_P4_B2",
        PROPOSAL_HASH,
        2048);
  }

  private static FormalFinalizationReceipt formalFinalizationReceipt(IntakeDomainEventRef event) {
    return formalFinalizationReceipt(event, "AGENT_SESSION_P4_B2");
  }

  private static FormalFinalizationReceipt formalFinalizationReceipt(
      IntakeDomainEventRef event, String agentSessionId) {
    return new FormalFinalizationReceipt(
        "intake-finalization-receipt.v1",
        event.operationKey(),
        "tenant-p4-b2",
        CASE_ID,
        ROOM_EPOCH,
        THREAD_ID,
        ACTOR_SCOPE_HASH,
        agentSessionId,
        COMMAND_ID,
        "RUN_P4_B2",
        "ATTEMPT_P4_B2",
        RESULT_HASH,
        PROPOSAL_HASH,
        20,
        14,
        11,
        "MESSAGE_AGENT_P4_B2",
        2L,
        null,
        List.of(event.eventId()),
        List.of("OUTBOX_P4_B2"),
        "COMMITTED",
        "2026-07-20T08:03:00Z",
        "9".repeat(64));
  }

  private static IntakeDomainEventRef domainEvent(
      String eventId,
      long eventSequence,
      IntakeDomainEventType eventType,
      IntakeParty party,
      String operationKey,
      IntakeAgentRunRef agentRunRef,
      IntakeGraphExecutionRef graphExecutionRef) {
    return new IntakeDomainEventRef(
        "intake-domain-event-ref.v1",
        eventId,
        "urn:after-sale-flow:intake-event:" + eventId,
        "8".repeat(64),
        eventSequence,
        eventType,
        party,
        COMMAND_ID,
        "tenant-p4-b2",
        CASE_ID,
        ROOM_EPOCH,
        11,
        ACTOR_SCOPE_HASH,
        operationKey,
        REQUEST_HASH,
        RESULT_HASH,
        20,
        14,
        agentRunRef,
        graphExecutionRef);
  }
}
