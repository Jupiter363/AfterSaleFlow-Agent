package com.example.dispute.workflow.room.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandDecision;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventType;
import com.example.dispute.workflow.temporal.room.intake.IntakeGraphExecutionRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomPhase;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomSnapshot;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomStart;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomWorkflow;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomWorkflowImpl;
import com.example.dispute.workflow.temporal.room.intake.IntakeTerminalReason;
import com.example.dispute.workflow.temporal.room.intake.IntakeWorkflowCommand;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntakeRoomWorkflowTest {

  private static final String TASK_QUEUE = "phase4-intake-workflow-test";
  private static final String TENANT = "tenant-p4-intake";
  private static final String CASE_ID = "CASE_P4_INTAKE_WORKFLOW";
  private static final long ROOM_EPOCH = 1;
  private static final long FENCE = 7;
  private static final long INITIAL_PROCESS_REVISION = 3;
  private static final long INITIAL_ROOM_REVISION = 2;
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
                    .setWorkflowId("intake-room:" + CASE_ID + ":" + ROOM_EPOCH)
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
  void bilateralAdmissionWaitsForIndependentRespondentConfirmation() {
    IntakeWorkflowCommand initiatorMessage =
        command(1, "CMD_INIT_MESSAGE", IntakeCommandType.INTAKE_MESSAGE, IntakeParty.INITIATOR);
    workflow.commandAccepted(initiatorMessage);
    assertPhase(IntakeRoomPhase.AGENT_RUNNING);
    workflow.domainEventCommitted(
        graphEvent(
            1,
            "EVENT_INIT_READY",
            initiatorMessage,
            IntakeDomainEventType.TURN_READY_TO_CONFIRM));
    assertPhase(IntakeRoomPhase.READY_TO_CONFIRM);
    assertThat(workflow.state().lastAgentRunRef().logicalRunId()).isEqualTo("RUN_CMD_INIT_MESSAGE");
    assertThat(workflow.state().lastGraphExecutionRef().graphKey()).isEqualTo("intake.v2");

    IntakeWorkflowCommand initiatorConfirm =
        command(2, "CMD_INIT_CONFIRM", IntakeCommandType.INTAKE_CONFIRM, IntakeParty.INITIATOR);
    workflow.commandAccepted(initiatorConfirm);
    assertDecision("ACCEPTED", null);
    workflow.domainEventCommitted(
        event(
            2,
            "EVENT_INIT_ACCEPT",
            initiatorConfirm,
            IntakeDomainEventType.INITIATOR_ACCEPTED));
    assertPhase(IntakeRoomPhase.WAITING_PARTY);
    assertThat(workflow.state().initiatorComplete()).isTrue();
    assertThat(workflow.state().respondentUnlocked()).isTrue();
    assertThat(workflow.state().activeParty()).isEqualTo(IntakeParty.RESPONDENT);

    environment.sleep(Duration.ofDays(30));
    assertPhase(IntakeRoomPhase.WAITING_PARTY);

    IntakeWorkflowCommand respondentMessage =
        command(3, "CMD_RESP_MESSAGE", IntakeCommandType.INTAKE_MESSAGE, IntakeParty.RESPONDENT);
    workflow.commandAccepted(respondentMessage);
    assertPhase(IntakeRoomPhase.AGENT_RUNNING);
    workflow.domainEventCommitted(
        graphEvent(
            3,
            "EVENT_RESP_READY",
            respondentMessage,
            IntakeDomainEventType.TURN_READY_TO_CONFIRM));
    assertPhase(IntakeRoomPhase.READY_TO_CONFIRM);

    IntakeWorkflowCommand respondentConfirm =
        command(4, "CMD_RESP_CONFIRM", IntakeCommandType.INTAKE_CONFIRM, IntakeParty.RESPONDENT);
    workflow.commandAccepted(respondentConfirm);
    assertDecision("ACCEPTED", null);
    workflow.domainEventCommitted(
        event(
            4,
            "EVENT_RESP_CONFIRM",
            respondentConfirm,
            IntakeDomainEventType.RESPONDENT_CONFIRMED));

    IntakeRoomSnapshot terminal =
        WorkflowStub.fromTyped(workflow).getResult(IntakeRoomSnapshot.class);
    assertThat(terminal.roomPhase()).isEqualTo(IntakeRoomPhase.COMPLETED);
    assertThat(terminal.terminalReason()).isEqualTo(IntakeTerminalReason.ADMITTED);
    assertThat(terminal.respondentComplete()).isTrue();
    assertThat(terminal.readinessParty()).isNull();
    assertThat(terminal.pendingCommand()).isNull();
  }

  @Test
  void rejectedScopeAndBusinessCommandsDoNotConsumeSequenceOrChangeRoomState() {
    workflow.commandAccepted(
        command(1, "CMD_RESP_LOCKED", IntakeCommandType.INTAKE_MESSAGE, IntakeParty.RESPONDENT));
    assertDecision("REJECTED", "RESPONDENT_LOCKED");
    assertUnadvancedOpenState();

    workflow.commandAccepted(
        command(1, "CMD_RESP_CANCEL", IntakeCommandType.INTAKE_CANCEL, IntakeParty.RESPONDENT));
    assertDecision("REJECTED", "RESPONDENT_CANCEL_FORBIDDEN");
    assertUnadvancedOpenState();

    IntakeWorkflowCommand wrongScope =
        commandWithScope(
            1,
            "CMD_WRONG_SCOPE",
            IntakeCommandType.INTAKE_MESSAGE,
            IntakeParty.INITIATOR,
            RESPONDENT_SCOPE,
            hash(1));
    workflow.commandAccepted(wrongScope);
    assertDecision("REJECTED", "COMMAND_ACTOR_SCOPE_MISMATCH");
    assertUnadvancedOpenState();

    workflow.commandAccepted(
        command(1, "CMD_VALID", IntakeCommandType.INTAKE_MESSAGE, IntakeParty.INITIATOR));
    assertDecision("ACCEPTED", null);
    assertThat(workflow.state().nextCommandSequence()).isEqualTo(2);
    assertThat(workflow.state().processedCommandCount()).isEqualTo(1);
    assertThat(workflow.state().roomPhase()).isEqualTo(IntakeRoomPhase.AGENT_RUNNING);
  }

  @Test
  void commandSequenceGapDoesNotConsumeSequenceAndCorrectCommandCanRecover() {
    workflow.commandAccepted(
        command(2, "CMD_COMMAND_GAP", IntakeCommandType.INTAKE_CANCEL, IntakeParty.INITIATOR));
    assertDecision("REJECTED", "COMMAND_SEQUENCE_GAP");
    assertUnadvancedOpenState();

    workflow.commandAccepted(
        command(1, "CMD_AFTER_GAP", IntakeCommandType.INTAKE_MESSAGE, IntakeParty.INITIATOR));
    assertDecision("ACCEPTED", null);
    assertThat(workflow.state().nextCommandSequence()).isEqualTo(2);
    assertThat(workflow.state().processedCommandCount()).isEqualTo(1);
    assertThat(workflow.state().pendingCommandId()).isEqualTo("CMD_AFTER_GAP");
  }

  @Test
  void roomRevisionExplicitlyAcceptsEqualityAndForwardJumps() {
    IntakeWorkflowCommand equalRevisionCommand =
        command(1, "CMD_EQUAL_REVISION", IntakeCommandType.INTAKE_MESSAGE, IntakeParty.INITIATOR);
    workflow.commandAccepted(equalRevisionCommand);
    workflow.domainEventCommitted(
        eventWithRevisions(
            1,
            "EVENT_EQUAL_REVISION",
            equalRevisionCommand,
            IntakeDomainEventType.TURN_NEEDS_INPUT,
            INITIAL_PROCESS_REVISION,
            INITIAL_ROOM_REVISION));

    IntakeRoomSnapshot equal = workflow.state();
    assertThat(equal.processedEventCount()).isEqualTo(1);
    assertThat(equal.processRevision()).isEqualTo(INITIAL_PROCESS_REVISION);
    assertThat(equal.roomRevision()).isEqualTo(INITIAL_ROOM_REVISION);

    IntakeWorkflowCommand jumpRevisionCommand =
        command(2, "CMD_JUMP_REVISION", IntakeCommandType.INTAKE_MESSAGE, IntakeParty.INITIATOR);
    workflow.commandAccepted(jumpRevisionCommand);
    workflow.domainEventCommitted(
        eventWithRevisions(
            2,
            "EVENT_JUMP_REVISION",
            jumpRevisionCommand,
            IntakeDomainEventType.TURN_NEEDS_INPUT,
            INITIAL_PROCESS_REVISION + 10,
            INITIAL_ROOM_REVISION + 20));

    IntakeRoomSnapshot jumped = workflow.state();
    assertThat(jumped.processedEventCount()).isEqualTo(2);
    assertThat(jumped.processRevision()).isEqualTo(INITIAL_PROCESS_REVISION + 10);
    assertThat(jumped.roomRevision()).isEqualTo(INITIAL_ROOM_REVISION + 20);
  }

  @Test
  void commandIdReuseWithDifferentIdentityFailsClosed() {
    IntakeWorkflowCommand first =
        command(1, "CMD_REUSED", IntakeCommandType.INTAKE_MESSAGE, IntakeParty.INITIATOR);
    workflow.commandAccepted(first);
    assertDecision("ACCEPTED", null);

    IntakeWorkflowCommand conflicting =
        commandWithScope(
            2,
            "CMD_REUSED",
            IntakeCommandType.INTAKE_CANCEL,
            IntakeParty.INITIATOR,
            INITIATOR_SCOPE,
            hash(7));
    workflow.commandAccepted(conflicting);
    assertDecision("REJECTED", "COMMAND_ID_REUSE_CONFLICT");
    assertThat(workflow.state().nextCommandSequence()).isEqualTo(2);
    assertThat(workflow.state().pendingCommandId()).isEqualTo("CMD_REUSED");

    workflow.commandAccepted(first);
    assertDecision("DUPLICATE", null);
    assertThat(workflow.state().processedCommandCount()).isEqualTo(1);
  }

  @Test
  void unifiedInboxPreservesCommittedEventBeforeFollowingCommand() {
    IntakeWorkflowCommand message =
        command(1, "CMD_ORDER_MESSAGE", IntakeCommandType.INTAKE_MESSAGE, IntakeParty.INITIATOR);
    workflow.commandAccepted(message);
    assertPhase(IntakeRoomPhase.AGENT_RUNNING);

    IntakeWorkflowCommand confirm =
        command(2, "CMD_ORDER_CONFIRM", IntakeCommandType.INTAKE_CONFIRM, IntakeParty.INITIATOR);
    workflow.domainEventCommitted(
        event(
            1,
            "EVENT_ORDER_READY",
            message,
            IntakeDomainEventType.TURN_READY_TO_CONFIRM));
    workflow.commandAccepted(confirm);
    tick();

    assertThat(workflow.lastCommandDecision().status()).isEqualTo("ACCEPTED");
    assertThat(workflow.lastCommandDecision().commandId()).isEqualTo("CMD_ORDER_CONFIRM");
    assertThat(workflow.state().processedEventCount()).isEqualTo(1);
    assertThat(workflow.state().processedCommandCount()).isEqualTo(2);
    assertThat(workflow.state().pendingCommandId()).isEqualTo("CMD_ORDER_CONFIRM");
  }

  @Test
  void illegalOrCrossPartyEventsDoNotAdvanceAndCorrectEventCanRecover() {
    IntakeWorkflowCommand message =
        command(1, "CMD_EVENT_BIND", IntakeCommandType.INTAKE_MESSAGE, IntakeParty.INITIATOR);
    workflow.commandAccepted(message);
    assertPhase(IntakeRoomPhase.AGENT_RUNNING);

    workflow.domainEventCommitted(
        event(1, "EVENT_ILLEGAL_TYPE", message, IntakeDomainEventType.CANCELLED));
    tick();
    assertThat(workflow.state().protocolErrorCode())
        .isEqualTo("EVENT_TYPE_NOT_ALLOWED_FOR_COMMAND");
    assertEventUnadvanced("CMD_EVENT_BIND");

    workflow.domainEventCommitted(
        eventForParty(
            1,
            "EVENT_WRONG_PARTY",
            message,
            IntakeDomainEventType.TURN_READY_TO_CONFIRM,
            IntakeParty.RESPONDENT));
    tick();
    assertThat(workflow.state().protocolErrorCode()).isEqualTo("EVENT_PENDING_SCOPE_MISMATCH");
    assertEventUnadvanced("CMD_EVENT_BIND");

    workflow.domainEventCommitted(
        event(1, "EVENT_CORRECT", message, IntakeDomainEventType.TURN_READY_TO_CONFIRM));
    assertPhase(IntakeRoomPhase.READY_TO_CONFIRM);
    assertThat(workflow.state().nextEventSequence()).isEqualTo(2);
    assertThat(workflow.state().pendingCommand()).isNull();
  }

  @Test
  void graphCommandReferenceMustMatchPendingWorkflowCommand() {
    IntakeWorkflowCommand message =
        command(1, "CMD_GRAPH_BIND", IntakeCommandType.INTAKE_MESSAGE, IntakeParty.INITIATOR);
    workflow.commandAccepted(message);
    assertPhase(IntakeRoomPhase.AGENT_RUNNING);

    workflow.domainEventCommitted(
        graphEventWithCommandId(
            1,
            "EVENT_GRAPH_MISMATCH",
            message,
            IntakeDomainEventType.TURN_READY_TO_CONFIRM,
            "OTHER_GRAPH_COMMAND"));
    tick();
    assertThat(workflow.state().protocolErrorCode()).isEqualTo("EVENT_GRAPH_COMMAND_MISMATCH");
    assertEventUnadvanced("CMD_GRAPH_BIND");

    workflow.domainEventCommitted(
        graphEvent(
            1,
            "EVENT_GRAPH_MATCH",
            message,
            IntakeDomainEventType.TURN_READY_TO_CONFIRM));
    assertPhase(IntakeRoomPhase.READY_TO_CONFIRM);
  }

  @Test
  void eventSequenceGapAndEventIdConflictAreFailClosedAndRecoverable() {
    IntakeWorkflowCommand message =
        command(1, "CMD_EVENT_SEQUENCE", IntakeCommandType.INTAKE_MESSAGE, IntakeParty.INITIATOR);
    workflow.commandAccepted(message);
    assertPhase(IntakeRoomPhase.AGENT_RUNNING);

    IntakeDomainEventRef gap =
        event(2, "EVENT_GAP", message, IntakeDomainEventType.TURN_READY_TO_CONFIRM);
    workflow.domainEventCommitted(gap);
    tick();
    assertThat(workflow.state().protocolErrorCode()).isEqualTo("EVENT_SEQUENCE_GAP");
    assertEventUnadvanced("CMD_EVENT_SEQUENCE");

    workflow.domainEventCommitted(
        eventWithHash(
            2,
            "EVENT_GAP",
            message,
            IntakeDomainEventType.TURN_READY_TO_CONFIRM,
            hash(3)));
    tick();
    assertThat(workflow.state().protocolErrorCode()).isEqualTo("EVENT_ID_REUSE_CONFLICT");
    assertEventUnadvanced("CMD_EVENT_SEQUENCE");

    workflow.domainEventCommitted(
        event(1, "EVENT_READY", message, IntakeDomainEventType.TURN_READY_TO_CONFIRM));
    assertPhase(IntakeRoomPhase.READY_TO_CONFIRM);

    IntakeDomainEventRef applied =
        event(1, "EVENT_READY", message, IntakeDomainEventType.TURN_READY_TO_CONFIRM);
    workflow.domainEventCommitted(applied);
    tick();
    assertThat(workflow.state().protocolErrorCode()).isNull();
    assertThat(workflow.state().nextEventSequence()).isEqualTo(2);
    assertThat(workflow.state().processedEventCount()).isEqualTo(1);
    assertThat(workflow.state().roomPhase()).isEqualTo(IntakeRoomPhase.READY_TO_CONFIRM);
  }

  @Test
  void initiatorCancellationAfterReadinessClearsPendingReadiness() {
    IntakeWorkflowCommand message =
        command(1, "CMD_CANCEL_READY", IntakeCommandType.INTAKE_MESSAGE, IntakeParty.INITIATOR);
    workflow.commandAccepted(message);
    workflow.domainEventCommitted(
        graphEvent(
            1,
            "EVENT_CANCEL_READY",
            message,
            IntakeDomainEventType.TURN_READY_TO_CONFIRM));
    assertPhase(IntakeRoomPhase.READY_TO_CONFIRM);

    IntakeWorkflowCommand cancel =
        command(2, "CMD_CANCEL", IntakeCommandType.INTAKE_CANCEL, IntakeParty.INITIATOR);
    workflow.commandAccepted(cancel);
    assertDecision("ACCEPTED", null);
    workflow.domainEventCommitted(
        event(2, "EVENT_CANCEL", cancel, IntakeDomainEventType.CANCELLED));

    IntakeRoomSnapshot terminal =
        WorkflowStub.fromTyped(workflow).getResult(IntakeRoomSnapshot.class);
    assertThat(terminal.terminalReason()).isEqualTo(IntakeTerminalReason.CANCELLED);
    assertThat(terminal.initiatorComplete()).isFalse();
    assertThat(terminal.respondentUnlocked()).isFalse();
    assertThat(terminal.readinessParty()).isNull();
    assertThat(terminal.pendingCommand()).isNull();
  }

  @Test
  void temporalPayloadTypesContainOnlyReferencesAndNoPrivateMessageText() {
    for (Class<?> type : Arrays.asList(IntakeWorkflowCommand.class, IntakeDomainEventRef.class)) {
      assertThat(Arrays.stream(type.getRecordComponents()).map(component -> component.getName()))
          .noneMatch(
              name ->
                  name.equals("text")
                      || name.equals("messageText")
                      || name.equals("payload")
                      || name.equals("proposal"));
    }
  }

  @Test
  void onlyTurnEventsCarryPairedAgentRunAndGraphReferences() {
    IntakeWorkflowCommand message =
        command(1, "CMD_REF_SHAPE", IntakeCommandType.INTAKE_MESSAGE, IntakeParty.INITIATOR);
    IntakeDomainEventRef turn =
        graphEvent(
            1,
            "EVENT_REF_SHAPE",
            message,
            IntakeDomainEventType.TURN_READY_TO_CONFIRM);

    assertThatThrownBy(
            () ->
                new IntakeDomainEventRef(
                    turn.schemaVersion(),
                    turn.eventId(),
                    turn.eventRef(),
                    turn.eventHash(),
                    turn.eventSequence(),
                    turn.eventType(),
                    turn.party(),
                    turn.commandId(),
                    turn.tenantSurrogate(),
                    turn.caseId(),
                    turn.roomEpoch(),
                    turn.fencingToken(),
                    turn.actorScopeHash(),
                    turn.operationKey(),
                    turn.requestHash(),
                    turn.resultHash(),
                    turn.processRevision(),
                    turn.roomRevision(),
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("turn events require");

    assertThatThrownBy(
            () ->
                new IntakeDomainEventRef(
                    turn.schemaVersion(),
                    "EVENT_NON_TURN_REFS",
                    "urn:after-sale-flow:intake-event:EVENT_NON_TURN_REFS",
                    turn.eventHash(),
                    turn.eventSequence(),
                    IntakeDomainEventType.CANCELLED,
                    turn.party(),
                    turn.commandId(),
                    turn.tenantSurrogate(),
                    turn.caseId(),
                    turn.roomEpoch(),
                    turn.fencingToken(),
                    turn.actorScopeHash(),
                    turn.operationKey(),
                    turn.requestHash(),
                    turn.resultHash(),
                    turn.processRevision(),
                    turn.roomRevision(),
                    turn.agentRunRef(),
                    turn.graphExecutionRef()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-turn events");
  }

  private void assertUnadvancedOpenState() {
    IntakeRoomSnapshot state = workflow.state();
    assertThat(state.roomPhase()).isEqualTo(IntakeRoomPhase.OPEN);
    assertThat(state.nextCommandSequence()).isEqualTo(1);
    assertThat(state.processedCommandCount()).isZero();
    assertThat(state.pendingCommand()).isNull();
  }

  private void assertEventUnadvanced(String pendingCommandId) {
    IntakeRoomSnapshot state = workflow.state();
    assertThat(state.nextEventSequence()).isEqualTo(1);
    assertThat(state.processedEventCount()).isZero();
    assertThat(state.pendingCommandId()).isEqualTo(pendingCommandId);
    assertThat(state.roomPhase()).isEqualTo(IntakeRoomPhase.AGENT_RUNNING);
  }

  private void assertPhase(IntakeRoomPhase phase) {
    tick();
    assertThat(workflow.state().roomPhase()).isEqualTo(phase);
  }

  private void assertDecision(String status, String reasonCode) {
    tick();
    IntakeCommandDecision decision = workflow.lastCommandDecision();
    assertThat(decision.status()).isEqualTo(status);
    assertThat(decision.reasonCode()).isEqualTo(reasonCode);
  }

  private void tick() {
    environment.sleep(Duration.ofSeconds(1));
  }

  private static IntakeRoomStart start() {
    return new IntakeRoomStart(
        "intake-room-start.v1",
        TENANT,
        CASE_ID,
        ROOM_EPOCH,
        FENCE,
        INITIAL_PROCESS_REVISION,
        INITIAL_ROOM_REVISION,
        1,
        1,
        "intake-workflow.synthetic.v1",
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

  private static IntakeWorkflowCommand command(
      long sequence, String commandId, IntakeCommandType type, IntakeParty party) {
    return commandWithScope(
        sequence, commandId, type, party, scope(party), hash(sequence));
  }

  private static IntakeWorkflowCommand commandWithScope(
      long sequence,
      String commandId,
      IntakeCommandType type,
      IntakeParty party,
      String actorScopeHash,
      String requestHash) {
    return new IntakeWorkflowCommand(
        "intake-workflow-command.v1",
        commandId,
        TENANT,
        CASE_ID,
        ROOM_EPOCH,
        FENCE,
        sequence,
        type,
        party,
        actorScopeHash,
        "urn:after-sale-flow:intake-command:" + commandId,
        hash(sequence),
        operationKey(commandId),
        requestHash);
  }

  private static IntakeDomainEventRef event(
      long eventSequence,
      String eventId,
      IntakeWorkflowCommand command,
      IntakeDomainEventType eventType) {
    return eventForParty(eventSequence, eventId, command, eventType, command.party());
  }

  private static IntakeDomainEventRef eventWithRevisions(
      long eventSequence,
      String eventId,
      IntakeWorkflowCommand command,
      IntakeDomainEventType eventType,
      long processRevision,
      long roomRevision) {
    IntakeDomainEventRef base = event(eventSequence, eventId, command, eventType);
    return new IntakeDomainEventRef(
        base.schemaVersion(),
        base.eventId(),
        base.eventRef(),
        base.eventHash(),
        base.eventSequence(),
        base.eventType(),
        base.party(),
        base.commandId(),
        base.tenantSurrogate(),
        base.caseId(),
        base.roomEpoch(),
        base.fencingToken(),
        base.actorScopeHash(),
        base.operationKey(),
        base.requestHash(),
        base.resultHash(),
        processRevision,
        roomRevision,
        base.agentRunRef(),
        base.graphExecutionRef());
  }

  private static IntakeDomainEventRef eventForParty(
      long eventSequence,
      String eventId,
      IntakeWorkflowCommand command,
      IntakeDomainEventType eventType,
      IntakeParty party) {
    String resultHash = hash(eventSequence + 4);
    TurnReferences references = turnReferences(command, eventType, resultHash, command.commandId());
    return eventWithHash(
        eventSequence,
        eventId,
        command,
        eventType,
        hash(eventSequence + 5),
        party,
        references.agentRunRef(),
        references.graphExecutionRef());
  }

  private static IntakeDomainEventRef eventWithHash(
      long eventSequence,
      String eventId,
      IntakeWorkflowCommand command,
      IntakeDomainEventType eventType,
      String eventHash) {
    String resultHash = hash(eventSequence + 4);
    TurnReferences references = turnReferences(command, eventType, resultHash, command.commandId());
    return eventWithHash(
        eventSequence,
        eventId,
        command,
        eventType,
        eventHash,
        command.party(),
        references.agentRunRef(),
        references.graphExecutionRef());
  }

  private static IntakeDomainEventRef graphEvent(
      long eventSequence,
      String eventId,
      IntakeWorkflowCommand command,
      IntakeDomainEventType eventType) {
    return graphEventWithCommandId(
        eventSequence, eventId, command, eventType, command.commandId());
  }

  private static IntakeDomainEventRef graphEventWithCommandId(
      long eventSequence,
      String eventId,
      IntakeWorkflowCommand command,
      IntakeDomainEventType eventType,
      String graphCommandId) {
    String resultHash = hash(eventSequence + 4);
    TurnReferences references = turnReferences(command, eventType, resultHash, graphCommandId);
    return eventWithHash(
        eventSequence,
        eventId,
        command,
        eventType,
        hash(eventSequence + 5),
        command.party(),
        references.agentRunRef(),
        references.graphExecutionRef());
  }

  private static TurnReferences turnReferences(
      IntakeWorkflowCommand command,
      IntakeDomainEventType eventType,
      String resultHash,
      String graphCommandId) {
    if (eventType != IntakeDomainEventType.TURN_NEEDS_INPUT
        && eventType != IntakeDomainEventType.TURN_READY_TO_CONFIRM) {
      return new TurnReferences(null, null);
    }
    IntakeAgentRunRef agentRunRef =
        new IntakeAgentRunRef(
            "intake-agent-run-ref.v1",
            "RUN_" + command.commandId(),
            "ATTEMPT_" + command.commandId(),
            resultHash);
    IntakeGraphExecutionRef graphExecutionRef =
        new IntakeGraphExecutionRef(
            "intake-graph-execution-ref.v1",
            "grt.v1." + "a".repeat(32),
            graphCommandId,
            "intake.v2",
            "2.0.0",
            "CHECKPOINT_" + command.commandId(),
            "urn:after-sale-flow:graph-result:" + command.commandId(),
            resultHash,
            "urn:after-sale-flow:intake-proposal:" + command.commandId(),
            hash(command.sequence() + 6));
    return new TurnReferences(agentRunRef, graphExecutionRef);
  }

  private static IntakeDomainEventRef eventWithHash(
      long eventSequence,
      String eventId,
      IntakeWorkflowCommand command,
      IntakeDomainEventType eventType,
      String eventHash,
      IntakeParty party,
      IntakeAgentRunRef agentRunRef,
      IntakeGraphExecutionRef graphExecutionRef) {
    return new IntakeDomainEventRef(
        "intake-domain-event-ref.v1",
        eventId,
        "urn:after-sale-flow:intake-event:" + eventId,
        eventHash,
        eventSequence,
        eventType,
        party,
        command.commandId(),
        TENANT,
        CASE_ID,
        ROOM_EPOCH,
        FENCE,
        scope(party),
        command.operationKey(),
        command.requestHash(),
        hash(eventSequence + 4),
        INITIAL_PROCESS_REVISION + eventSequence,
        INITIAL_ROOM_REVISION + eventSequence,
        agentRunRef,
        graphExecutionRef);
  }

  private static String operationKey(String commandId) {
    return "intake.operation:" + CASE_ID + ":" + commandId;
  }

  private static String scope(IntakeParty party) {
    return party == IntakeParty.INITIATOR ? INITIATOR_SCOPE : RESPONDENT_SCOPE;
  }

  private static String hash(long value) {
    int digit = (int) (Math.abs(value) % 10);
    return Integer.toString(digit).repeat(64);
  }

  private record TurnReferences(
      IntakeAgentRunRef agentRunRef, IntakeGraphExecutionRef graphExecutionRef) {}
}
