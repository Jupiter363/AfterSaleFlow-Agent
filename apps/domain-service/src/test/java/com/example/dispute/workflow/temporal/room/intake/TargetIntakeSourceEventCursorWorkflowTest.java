package com.example.dispute.workflow.temporal.room.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TargetIntakeSourceEventCursorWorkflowTest {

  private static final String TASK_QUEUE = "target-intake-source-cursor-test";
  private static final String TENANT = "tenant-target-cursor";
  private static final String CASE_ID = "CASE_TARGET_CURSOR_1";
  private static final long ROOM_EPOCH = 1;
  private static final long FENCE = 17;
  private static final String INITIATOR_SCOPE = "8".repeat(64);
  private static final String RESPONDENT_SCOPE = "9".repeat(64);

  private TestWorkflowEnvironment environment;
  private IntakeRoomWorkflow workflow;

  @BeforeEach
  void setUp() {
    environment = TestWorkflowEnvironment.newInstance();
    Worker worker = environment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
    environment.start();
    workflow =
        environment
            .getWorkflowClient()
            .newWorkflowStub(
                IntakeRoomWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setWorkflowId("target-intake-source-cursor:" + CASE_ID)
                    .setTaskQueue(TASK_QUEUE)
                    .build());
    WorkflowClient.start(workflow::run, start());
    tick();
  }

  @AfterEach
  void tearDown() {
    environment.close();
  }

  @Test
  void twoSourceFormalPairsRecoverBufferedGapsAndReplayWithoutFixedOffset() {
    IntakeWorkflowCommand first = message(1, "CMD_SOURCE_1");
    workflow.commandAccepted(first);
    tick();
    workflow.domainEventCommitted(formalTurn(2, "EVENT_FORMAL_2", first));
    tick();

    assertThat(workflow.state().protocolErrorCode()).isEqualTo("EVENT_SEQUENCE_GAP");
    assertThat(workflow.state().nextEventSequence()).isEqualTo(1);
    assertThat(workflow.state().pendingCommandId()).isEqualTo(first.commandId());

    TargetIntakeSourceEventRef sourceOne = source(1, "EVENT_SOURCE_1");
    workflow.targetSourceEventObserved(sourceOne);
    tick();

    assertThat(workflow.state().protocolErrorCode()).isNull();
    assertThat(workflow.state().nextEventSequence()).isEqualTo(3);
    assertThat(workflow.state().processedEventCount()).isEqualTo(1);
    assertThat(workflow.state().pendingCommand()).isNull();
    assertThat(workflow.state().roomPhase()).isEqualTo(IntakeRoomPhase.READY_TO_CONFIRM);

    workflow.targetSourceEventObserved(sourceOne);
    tick();
    assertThat(workflow.state().nextEventSequence()).isEqualTo(3);
    assertThat(workflow.state().processedEventCount()).isEqualTo(1);

    IntakeWorkflowCommand second = message(2, "CMD_SOURCE_2");
    workflow.commandAccepted(second);
    tick();
    workflow.domainEventCommitted(formalTurn(4, "EVENT_FORMAL_4", second));
    tick();

    assertThat(workflow.state().protocolErrorCode()).isEqualTo("EVENT_SEQUENCE_GAP");
    assertThat(workflow.state().nextEventSequence()).isEqualTo(3);
    assertThat(workflow.state().pendingCommandId()).isEqualTo(second.commandId());

    workflow.targetSourceEventObserved(source(3, "EVENT_SOURCE_3"));
    tick();

    assertThat(workflow.state().protocolErrorCode()).isNull();
    assertThat(workflow.state().nextEventSequence()).isEqualTo(5);
    assertThat(workflow.state().processedEventCount()).isEqualTo(2);
    assertThat(workflow.state().pendingCommand()).isNull();
    assertThat(workflow.state().lastEventId()).isEqualTo("EVENT_FORMAL_4");
  }

  @Test
  void tenConsecutiveTurnsConsumeTheCompleteGlobalTimelineCursor() {
    long formalSequence = 1;

    for (long round = 1; round <= 10; round++) {
      IntakeWorkflowCommand command = message(round, "CMD_TEN_TURN_" + round);
      workflow.commandAccepted(command);
      tick();

      assertThat(workflow.lastCommandDecision().status()).isEqualTo("ACCEPTED");
      assertThat(workflow.lastCommandDecision().commandId()).isEqualTo(command.commandId());
      assertThat(workflow.state().pendingCommandId()).isEqualTo(command.commandId());

      workflow.domainEventCommitted(
          formalTurn(formalSequence, "EVENT_TEN_FORMAL_" + round, command));
      tick();

      IntakeRoomSnapshot formalized = workflow.state();
      assertThat(formalized.pendingCommand()).isNull();
      assertThat(formalized.processedCommandCount()).isEqualTo(round);
      assertThat(formalized.processedEventCount()).isEqualTo(round);
      assertThat(formalized.nextCommandSequence()).isEqualTo(round + 1);
      assertThat(formalized.nextEventSequence()).isEqualTo(formalSequence + 1);
      assertThat(formalized.protocolErrorCode()).isNull();

      workflow.targetSourceEventObserved(
          cursor(
              formalSequence + 1,
              "EVENT_TEN_READY_" + round,
              "INTAKE_PROJECTION_READY"));
      tick();
      assertThat(workflow.state().nextEventSequence()).isEqualTo(formalSequence + 2);
      assertThat(workflow.state().processedEventCount()).isEqualTo(round);
      assertThat(workflow.state().protocolErrorCode()).isNull();

      if (round < 10) {
        workflow.targetSourceEventObserved(
            source(formalSequence + 2, "EVENT_TEN_ROOM_" + (round + 1)));
        tick();
        assertThat(workflow.state().nextEventSequence()).isEqualTo(formalSequence + 3);
        assertThat(workflow.state().processedEventCount()).isEqualTo(round);
        assertThat(workflow.state().protocolErrorCode()).isNull();
        formalSequence += 3;
      }
    }

    IntakeRoomSnapshot completed = workflow.state();
    assertThat(completed.nextCommandSequence()).isEqualTo(11);
    assertThat(completed.nextEventSequence()).isEqualTo(30);
    assertThat(completed.processedCommandCount()).isEqualTo(10);
    assertThat(completed.processedEventCount()).isEqualTo(10);
    assertThat(completed.pendingCommand()).isNull();
    assertThat(completed.lastAgentRunRef().logicalRunId()).isEqualTo("RUN_CMD_TEN_TURN_10");
    assertThat(completed.lastGraphExecutionRef().graphCommandId()).isEqualTo("CMD_TEN_TURN_10");
    assertThat(completed.protocolErrorCode()).isNull();
  }

  @Test
  void futureSourceCursorsDrainInOrderAndExactDuplicateIsIdempotent() {
    TargetIntakeSourceEventRef readiness =
        cursor(2, "EVENT_FUTURE_READY_2", "INTAKE_PROJECTION_READY");
    TargetIntakeSourceEventRef roomMessage = source(3, "EVENT_FUTURE_ROOM_3");

    workflow.targetSourceEventObserved(readiness);
    workflow.targetSourceEventObserved(readiness);
    workflow.targetSourceEventObserved(roomMessage);
    tick();

    assertThat(workflow.state().nextEventSequence()).isEqualTo(1);
    assertThat(workflow.state().processedEventCount()).isZero();
    assertThat(workflow.state().protocolErrorCode())
        .isEqualTo("TARGET_SOURCE_EVENT_SEQUENCE_GAP");

    workflow.targetSourceEventObserved(source(1, "EVENT_FUTURE_SOURCE_1"));
    tick();

    assertThat(workflow.state().nextEventSequence()).isEqualTo(4);
    assertThat(workflow.state().processedEventCount()).isZero();
    assertThat(workflow.state().protocolErrorCode()).isNull();
  }

  @Test
  void futureSourceCursorIdentityAndSequenceConflictsFailClosed() {
    IntakeRoomWorkflow sequenceConflict = newWorkflow("future-sequence-conflict", start());
    sequenceConflict.targetSourceEventObserved(
        cursor(2, "EVENT_FUTURE_SEQUENCE_A", "INTAKE_PROJECTION_READY"));
    tick();
    sequenceConflict.targetSourceEventObserved(
        source(2, "EVENT_FUTURE_SEQUENCE_B"));
    tick();

    assertThat(sequenceConflict.state().nextEventSequence()).isEqualTo(1);
    assertThat(sequenceConflict.state().protocolErrorCode())
        .isEqualTo("TARGET_SOURCE_EVENT_SEQUENCE_ID_CONFLICT");

    IntakeRoomWorkflow idConflict = newWorkflow("future-id-conflict", start());
    idConflict.targetSourceEventObserved(
        cursor(2, "EVENT_FUTURE_REUSED_ID", "INTAKE_PROJECTION_READY"));
    tick();
    idConflict.targetSourceEventObserved(
        cursor(
            2,
            "EVENT_FUTURE_REUSED_ID",
            "INTAKE_PROJECTION_READY",
            hash(9)));
    tick();

    assertThat(idConflict.state().nextEventSequence()).isEqualTo(1);
    assertThat(idConflict.state().protocolErrorCode())
        .isEqualTo("TARGET_SOURCE_EVENT_ID_REUSE_CONFLICT");
  }

  @Test
  void formalEventTypeIsRejectedBeforeFutureCursorBuffering() {
    workflow.targetSourceEventObserved(
        cursor(2, "EVENT_FUTURE_FORMAL_AS_CURSOR", IntakeDomainEventType.TURN_NEEDS_INPUT.name()));
    tick();

    assertThat(workflow.state().nextEventSequence()).isEqualTo(1);
    assertThat(workflow.state().protocolErrorCode())
        .isEqualTo("TARGET_SOURCE_EVENT_TYPE_NOT_ALLOWED");

    workflow.targetSourceEventObserved(source(1, "EVENT_AFTER_REJECTED_FORMAL_1"));
    workflow.targetSourceEventObserved(
        cursor(2, "EVENT_AFTER_REJECTED_FORMAL_READY_2", "INTAKE_PROJECTION_READY"));
    tick();

    assertThat(workflow.state().nextEventSequence()).isEqualTo(3);
    assertThat(workflow.state().protocolErrorCode()).isNull();
  }

  @Test
  void formalTypeGapAndScopeMismatchFailClosedWithoutClearingPendingCommand() {
    IntakeWorkflowCommand pending = message(1, "CMD_FAIL_CLOSED");
    workflow.commandAccepted(pending);
    tick();

    workflow.targetSourceEventObserved(
        new TargetIntakeSourceEventRef(
            TargetIntakeSourceEventRef.SCHEMA_VERSION,
            "EVENT_FORMAL_AS_CURSOR",
            1,
            IntakeDomainEventType.TURN_NEEDS_INPUT.name(),
            TENANT,
            CASE_ID,
            RoomType.INTAKE,
            ROOM_EPOCH,
            FENCE,
            hash(1)));
    tick();
    assertFailClosed("TARGET_SOURCE_EVENT_TYPE_NOT_ALLOWED", pending.commandId(), 1);

    workflow.targetSourceEventObserved(source(2, "EVENT_GAP"));
    tick();
    assertFailClosed("TARGET_SOURCE_EVENT_SEQUENCE_GAP", pending.commandId(), 1);

    workflow.targetSourceEventObserved(
        new TargetIntakeSourceEventRef(
            TargetIntakeSourceEventRef.SCHEMA_VERSION,
            "EVENT_WRONG_CASE",
            1,
            TargetIntakeSourceEventRef.ROOM_MESSAGE_CREATED,
            TENANT,
            "CASE_OTHER",
            RoomType.INTAKE,
            ROOM_EPOCH,
            FENCE,
            hash(1)));
    tick();
    assertFailClosed("TARGET_SOURCE_EVENT_SCOPE_MISMATCH", pending.commandId(), 1);

    workflow.targetSourceEventObserved(
        cursor(1, "EVENT_READY_1", "INTAKE_PROJECTION_READY"));
    tick();
    assertThat(workflow.state().protocolErrorCode()).isNull();
    assertThat(workflow.state().nextEventSequence()).isEqualTo(3);
    assertThat(workflow.state().pendingCommandId()).isEqualTo(pending.commandId());

    workflow.targetSourceEventObserved(source(1, "EVENT_UNKNOWN_REPLAY"));
    tick();
    assertFailClosed(
        "TARGET_SOURCE_EVENT_SEQUENCE_ID_CONFLICT", pending.commandId(), 3);
  }

  @Test
  void duplicateSourceRetriesBufferedFormalWithoutMaskingItsExactFailure() {
    IntakeWorkflowCommand pending = message(1, "CMD_DUPLICATE_RETRY");
    workflow.commandAccepted(pending);
    tick();
    workflow.domainEventCommitted(
        nonTurnFormal(2, "EVENT_INVALID_FORMAL_2", pending, IntakeDomainEventType.CANCELLED));
    tick();
    assertThat(workflow.state().protocolErrorCode()).isEqualTo("EVENT_SEQUENCE_GAP");

    TargetIntakeSourceEventRef source = source(1, "EVENT_SOURCE_RETRY_1");
    workflow.targetSourceEventObserved(source);
    tick();
    assertThat(workflow.state().protocolErrorCode())
        .isEqualTo("EVENT_TYPE_NOT_ALLOWED_FOR_COMMAND");
    assertThat(workflow.state().nextEventSequence()).isEqualTo(2);
    assertThat(workflow.state().pendingCommandId()).isEqualTo(pending.commandId());

    workflow.targetSourceEventObserved(source);
    tick();
    assertThat(workflow.state().protocolErrorCode())
        .isEqualTo("EVENT_TYPE_NOT_ALLOWED_FOR_COMMAND");
    assertThat(workflow.state().nextEventSequence()).isEqualTo(2);
    assertThat(workflow.state().pendingCommandId()).isEqualTo(pending.commandId());
  }

  @Test
  void ordinaryIntakeLaneCannotUseTheTargetSourceCursorSignal() {
    IntakeRoomStart ordinaryProfile = ordinaryStart();
    assertThat(ordinaryProfile.targetE2eCandidate()).isFalse();
    IntakeRoomWorkflow ordinary = newWorkflow("ordinary", ordinaryProfile);

    ordinary.targetSourceEventObserved(source(1, "EVENT_ORDINARY_SOURCE_1"));
    tick();

    assertThat(ordinary.state().protocolErrorCode())
        .isEqualTo("TARGET_SOURCE_EVENT_LANE_NOT_AUTHORIZED");
    assertThat(ordinary.state().nextEventSequence()).isEqualTo(1);
    assertThat(ordinary.state().processedEventCount()).isZero();
    assertThat(ordinary.state().pendingCommand()).isNull();
  }

  @Test
  void sourceAndFormalEventIdsCannotBeReusedAcrossObservationTypes() {
    IntakeWorkflowCommand pending = message(1, "CMD_CROSS_TYPE_ID");
    workflow.commandAccepted(pending);
    tick();
    workflow.targetSourceEventObserved(source(1, "EVENT_CROSS_TYPE_ID"));
    tick();

    workflow.domainEventCommitted(formalTurn(2, "EVENT_CROSS_TYPE_ID", pending));
    tick();

    assertThat(workflow.state().protocolErrorCode()).isEqualTo("EVENT_ID_REUSE_CONFLICT");
    assertThat(workflow.state().nextEventSequence()).isEqualTo(2);
    assertThat(workflow.state().pendingCommandId()).isEqualTo(pending.commandId());

    IntakeRoomWorkflow reverse = newWorkflow("reverse-id", start());
    IntakeWorkflowCommand reversePending = message(1, "CMD_REVERSE_CROSS_TYPE_ID");
    reverse.commandAccepted(reversePending);
    tick();
    reverse.domainEventCommitted(
        formalTurn(2, "EVENT_REVERSE_CROSS_TYPE_ID", reversePending));
    tick();
    reverse.targetSourceEventObserved(source(1, "EVENT_REVERSE_CROSS_TYPE_ID"));
    tick();

    assertThat(reverse.state().protocolErrorCode()).isEqualTo("EVENT_ID_REUSE_CONFLICT");
    assertThat(reverse.state().nextEventSequence()).isEqualTo(1);
    assertThat(reverse.state().pendingCommandId()).isEqualTo(reversePending.commandId());
  }

  @Test
  void differentIdsCannotOccupyOneSourceOrFormalSequence() {
    IntakeWorkflowCommand pending = message(1, "CMD_SEQUENCE_ID_CONFLICT");
    workflow.commandAccepted(pending);
    tick();
    workflow.domainEventCommitted(formalTurn(2, "EVENT_FORMAL_2_A", pending));
    tick();
    workflow.domainEventCommitted(formalTurn(2, "EVENT_FORMAL_2_B", pending));
    tick();
    assertThat(workflow.state().protocolErrorCode()).isEqualTo("EVENT_SEQUENCE_ID_CONFLICT");

    workflow.targetSourceEventObserved(source(1, "EVENT_SOURCE_1_A"));
    tick();
    assertThat(workflow.state().protocolErrorCode()).isNull();
    assertThat(workflow.state().nextEventSequence()).isEqualTo(3);
    assertThat(workflow.state().lastEventId()).isEqualTo("EVENT_FORMAL_2_A");

    IntakeRoomWorkflow sourceConflict = newWorkflow("source-conflict", start());
    sourceConflict.targetSourceEventObserved(source(1, "EVENT_SOURCE_SAME_SEQUENCE_A"));
    tick();
    sourceConflict.targetSourceEventObserved(source(1, "EVENT_SOURCE_SAME_SEQUENCE_B"));
    tick();

    assertThat(sourceConflict.state().protocolErrorCode())
        .isEqualTo("TARGET_SOURCE_EVENT_SEQUENCE_ID_CONFLICT");
    assertThat(sourceConflict.state().nextEventSequence()).isEqualTo(2);
  }

  private void assertFailClosed(String error, String pendingCommandId, long nextEventSequence) {
    assertThat(workflow.state().protocolErrorCode()).isEqualTo(error);
    assertThat(workflow.state().nextEventSequence()).isEqualTo(nextEventSequence);
    assertThat(workflow.state().processedEventCount()).isZero();
    assertThat(workflow.state().pendingCommandId()).isEqualTo(pendingCommandId);
  }

  private void tick() {
    environment.sleep(Duration.ofSeconds(1));
  }

  private IntakeRoomWorkflow newWorkflow(String suffix, IntakeRoomStart workflowStart) {
    IntakeRoomWorkflow created =
        environment
            .getWorkflowClient()
            .newWorkflowStub(
                IntakeRoomWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setWorkflowId("target-intake-source-cursor:" + CASE_ID + ":" + suffix)
                    .setTaskQueue(TASK_QUEUE)
                    .build());
    WorkflowClient.start(created::run, workflowStart);
    tick();
    return created;
  }

  private static IntakeRoomStart start() {
    return new IntakeRoomStart(
        "intake-room-start.v1",
        TENANT,
        CASE_ID,
        ROOM_EPOCH,
        FENCE,
        3,
        2,
        1,
        1,
        "target-control-build",
        "2.0.0",
        "intake-checkpoint.v2",
        "all-rooms-prompt.target-e2e.v2",
        "target-e2e.contract-blocked",
        "target-e2e-intake-output.v1",
        "all-rooms-policy.target-e2e.v1",
        "all-rooms-guardrail.target-e2e.v1",
        "tools.none.v1",
        INITIATOR_SCOPE,
        RESPONDENT_SCOPE);
  }

  private static IntakeRoomStart ordinaryStart() {
    return new IntakeRoomStart(
        "intake-room-start.v1",
        TENANT,
        CASE_ID,
        ROOM_EPOCH,
        FENCE,
        3,
        2,
        1,
        1,
        "ordinary-control-build",
        "2.0.0",
        "intake-checkpoint.v2",
        "intake-prompt.v2",
        "intake-model.synthetic.v1",
        "intake-turn-proposal.v2",
        "intake-policy.v2",
        "intake-guardrail.v2",
        "no-tools.v1",
        INITIATOR_SCOPE,
        RESPONDENT_SCOPE);
  }

  private static IntakeWorkflowCommand message(long sequence, String commandId) {
    return new IntakeWorkflowCommand(
        "intake-workflow-command.v1",
        commandId,
        TENANT,
        CASE_ID,
        ROOM_EPOCH,
        FENCE,
        sequence,
        IntakeCommandType.INTAKE_MESSAGE,
        IntakeParty.INITIATOR,
        INITIATOR_SCOPE,
        "urn:after-sale-flow:intake-command:" + commandId,
        hash(sequence),
        "intake.operation:" + CASE_ID + ":" + commandId,
        hash(sequence + 1));
  }

  private static IntakeDomainEventRef formalTurn(
      long sequence, String eventId, IntakeWorkflowCommand command) {
    String resultHash = hash(sequence + 2);
    return new IntakeDomainEventRef(
        "intake-domain-event-ref.v1",
        eventId,
        "urn:after-sale-flow:intake-event:" + eventId,
        hash(sequence + 3),
        sequence,
        IntakeDomainEventType.TURN_READY_TO_CONFIRM,
        IntakeParty.INITIATOR,
        command.commandId(),
        TENANT,
        CASE_ID,
        ROOM_EPOCH,
        FENCE,
        INITIATOR_SCOPE,
        command.operationKey(),
        command.requestHash(),
        resultHash,
        3 + sequence,
        2 + sequence,
        new IntakeAgentRunRef(
            "intake-agent-run-ref.v1",
            "RUN_" + command.commandId(),
            "ATTEMPT_" + command.commandId(),
            resultHash),
        new IntakeGraphExecutionRef(
            "intake-graph-execution-ref.v1",
            "grt.v1." + "a".repeat(32),
            command.commandId(),
            "intake.v2",
            "2.0.0",
            "CHECKPOINT_" + command.commandId(),
            "urn:after-sale-flow:graph-result:" + command.commandId(),
            resultHash,
            "urn:after-sale-flow:intake-proposal:" + command.commandId(),
            hash(sequence + 4)));
  }

  private static IntakeDomainEventRef nonTurnFormal(
      long sequence,
      String eventId,
      IntakeWorkflowCommand command,
      IntakeDomainEventType eventType) {
    return new IntakeDomainEventRef(
        "intake-domain-event-ref.v1",
        eventId,
        "urn:after-sale-flow:intake-event:" + eventId,
        hash(sequence + 3),
        sequence,
        eventType,
        IntakeParty.INITIATOR,
        command.commandId(),
        TENANT,
        CASE_ID,
        ROOM_EPOCH,
        FENCE,
        INITIATOR_SCOPE,
        command.operationKey(),
        command.requestHash(),
        hash(sequence + 2),
        3 + sequence,
        2 + sequence,
        null,
        null);
  }

  private static TargetIntakeSourceEventRef source(long sequence, String eventId) {
    return cursor(sequence, eventId, TargetIntakeSourceEventRef.ROOM_MESSAGE_CREATED);
  }

  private static TargetIntakeSourceEventRef cursor(
      long sequence, String eventId, String eventType) {
    return cursor(sequence, eventId, eventType, hash(sequence));
  }

  private static TargetIntakeSourceEventRef cursor(
      long sequence, String eventId, String eventType, String payloadHash) {
    return new TargetIntakeSourceEventRef(
        TargetIntakeSourceEventRef.SCHEMA_VERSION,
        eventId,
        sequence,
        eventType,
        TENANT,
        CASE_ID,
        RoomType.INTAKE,
        ROOM_EPOCH,
        FENCE,
        payloadHash);
  }

  private static String hash(long value) {
    return Integer.toString((int) (Math.abs(value) % 10)).repeat(64);
  }
}
