package com.example.dispute.workflow.room.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.temporal.room.intake.IntakeCommandDecision;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.example.dispute.workflow.temporal.room.intake.IntakeReceiptType;
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
    workflow.commandAccepted(command(1, "CMD_INIT_MESSAGE", IntakeCommandType.INTAKE_MESSAGE, IntakeParty.INITIATOR));
    assertPhase(IntakeRoomPhase.AGENT_RUNNING);
    workflow.domainReceiptCommitted(receipt(1, "RCP_INIT_READY", "CMD_INIT_MESSAGE", IntakeReceiptType.TURN_READY_TO_CONFIRM, IntakeParty.INITIATOR));
    assertPhase(IntakeRoomPhase.READY_TO_CONFIRM);

    workflow.commandAccepted(command(2, "CMD_INIT_CONFIRM", IntakeCommandType.INTAKE_CONFIRM, IntakeParty.INITIATOR));
    assertDecision("ACCEPTED", null);
    workflow.domainReceiptCommitted(receipt(2, "RCP_INIT_ACCEPT", "CMD_INIT_CONFIRM", IntakeReceiptType.INITIATOR_ACCEPTED, IntakeParty.INITIATOR));
    assertPhase(IntakeRoomPhase.WAITING_PARTY);
    assertThat(workflow.state().initiatorComplete()).isTrue();
    assertThat(workflow.state().respondentUnlocked()).isTrue();

    environment.sleep(Duration.ofDays(30));
    assertPhase(IntakeRoomPhase.WAITING_PARTY);

    workflow.commandAccepted(command(3, "CMD_RESP_MESSAGE", IntakeCommandType.INTAKE_MESSAGE, IntakeParty.RESPONDENT));
    assertPhase(IntakeRoomPhase.AGENT_RUNNING);
    workflow.domainReceiptCommitted(receipt(3, "RCP_RESP_READY", "CMD_RESP_MESSAGE", IntakeReceiptType.TURN_READY_TO_CONFIRM, IntakeParty.RESPONDENT));
    assertPhase(IntakeRoomPhase.READY_TO_CONFIRM);
    workflow.commandAccepted(command(4, "CMD_RESP_CONFIRM", IntakeCommandType.INTAKE_CONFIRM, IntakeParty.RESPONDENT));
    assertDecision("ACCEPTED", null);
    workflow.domainReceiptCommitted(receipt(4, "RCP_RESP_CONFIRM", "CMD_RESP_CONFIRM", IntakeReceiptType.RESPONDENT_CONFIRMED, IntakeParty.RESPONDENT));

    IntakeRoomSnapshot terminal =
        WorkflowStub.fromTyped(workflow).getResult(IntakeRoomSnapshot.class);
    assertThat(terminal.roomPhase()).isEqualTo(IntakeRoomPhase.COMPLETED);
    assertThat(terminal.terminalReason()).isEqualTo(IntakeTerminalReason.ADMITTED);
    assertThat(terminal.respondentComplete()).isTrue();
  }

  @Test
  void duplicateAndOutOfOrderCommandsDoNotRepeatOrSkipWork() {
    IntakeWorkflowCommand first =
        command(1, "CMD_DUPLICATE", IntakeCommandType.INTAKE_MESSAGE, IntakeParty.INITIATOR);
    workflow.commandAccepted(first);
    assertPhase(IntakeRoomPhase.AGENT_RUNNING);
    workflow.commandAccepted(first);
    assertDecision("DUPLICATE", null);
    assertThat(workflow.state().processedCommandCount()).isEqualTo(1);

    workflow.commandAccepted(command(3, "CMD_GAP", IntakeCommandType.INTAKE_CANCEL, IntakeParty.INITIATOR));
    assertDecision("REJECTED", "COMMAND_SEQUENCE_GAP");
    assertThat(workflow.state().nextCommandSequence()).isEqualTo(2);

    workflow.commandAccepted(command(2, "CMD_CANCEL", IntakeCommandType.INTAKE_CANCEL, IntakeParty.INITIATOR));
    assertDecision("ACCEPTED", null);
    workflow.domainReceiptCommitted(
        receiptForCommand(
            1,
            2,
            "RCP_CANCEL",
            "CMD_CANCEL",
            IntakeReceiptType.CANCELLED,
            IntakeParty.INITIATOR));
    IntakeRoomSnapshot terminal =
        WorkflowStub.fromTyped(workflow).getResult(IntakeRoomSnapshot.class);
    assertThat(terminal.terminalReason()).isEqualTo(IntakeTerminalReason.CANCELLED);
    assertThat(terminal.processedCommandCount()).isEqualTo(2);
  }

  @Test
  void respondentCannotActOrCancelWhileLocked() {
    workflow.commandAccepted(command(1, "CMD_RESP_LOCKED", IntakeCommandType.INTAKE_MESSAGE, IntakeParty.RESPONDENT));
    assertDecision("REJECTED", "RESPONDENT_LOCKED");
    workflow.commandAccepted(command(2, "CMD_RESP_CANCEL", IntakeCommandType.INTAKE_CANCEL, IntakeParty.RESPONDENT));
    assertDecision("REJECTED", "RESPONDENT_CANCEL_FORBIDDEN");
    assertThat(workflow.state().roomPhase()).isEqualTo(IntakeRoomPhase.OPEN);
    assertThat(workflow.state().processedCommandCount()).isEqualTo(2);
  }

  @Test
  void receiptSequenceGapIsFailClosedAndCorrectReceiptCanRecover() {
    workflow.commandAccepted(command(1, "CMD_READY", IntakeCommandType.INTAKE_MESSAGE, IntakeParty.INITIATOR));
    assertPhase(IntakeRoomPhase.AGENT_RUNNING);
    workflow.domainReceiptCommitted(receipt(2, "RCP_GAP", "CMD_READY", IntakeReceiptType.TURN_READY_TO_CONFIRM, IntakeParty.INITIATOR));
    tick();
    assertThat(workflow.state().protocolErrorCode()).isEqualTo("RECEIPT_SEQUENCE_GAP");
    assertThat(workflow.state().nextEventSequence()).isEqualTo(1);

    workflow.domainReceiptCommitted(receipt(1, "RCP_READY", "CMD_READY", IntakeReceiptType.TURN_READY_TO_CONFIRM, IntakeParty.INITIATOR));
    assertPhase(IntakeRoomPhase.READY_TO_CONFIRM);
    assertThat(workflow.state().protocolErrorCode()).isNull();
  }

  @Test
  void temporalPayloadTypesContainNoPrivateMessageTextField() {
    for (Class<?> type : Arrays.asList(IntakeWorkflowCommand.class, IntakeDomainReceipt.class)) {
      assertThat(Arrays.stream(type.getRecordComponents()).map(component -> component.getName()))
          .noneMatch(name -> name.equals("text") || name.equals("messageText") || name.equals("payload"));
    }
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
        3,
        2,
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
        "no-tools.v1");
  }

  private static IntakeWorkflowCommand command(
      long sequence,
      String commandId,
      IntakeCommandType type,
      IntakeParty party) {
    String hash = hash(sequence);
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
        hash(8),
        "REF_" + commandId,
        hash,
        "intake.operation:" + CASE_ID + ":" + commandId,
        hash);
  }

  private static IntakeDomainReceipt receipt(
      long sequence,
      String receiptId,
      String commandId,
      IntakeReceiptType type,
      IntakeParty party) {
    return receiptForCommand(sequence, sequence, receiptId, commandId, type, party);
  }

  private static IntakeDomainReceipt receiptForCommand(
      long eventSequence,
      long commandSequence,
      String receiptId,
      String commandId,
      IntakeReceiptType type,
      IntakeParty party) {
    IntakeWorkflowCommand command = commandFor(commandSequence, commandId, party);
    return new IntakeDomainReceipt(
        "intake-domain-receipt.v1",
        receiptId,
        commandId,
        TENANT,
        CASE_ID,
        ROOM_EPOCH,
        FENCE,
        eventSequence,
        3 + eventSequence,
        2 + eventSequence,
        type,
        party,
        command.operationKey(),
        command.requestHash(),
        hash(eventSequence + 4),
        hash(eventSequence + 5));
  }

  private static IntakeWorkflowCommand commandFor(
      long sequence,
      String commandId,
      IntakeParty party) {
    IntakeCommandType type =
        commandId.contains("MESSAGE") || commandId.contains("READY")
            ? IntakeCommandType.INTAKE_MESSAGE
            : commandId.contains("CANCEL")
                ? IntakeCommandType.INTAKE_CANCEL
                : IntakeCommandType.INTAKE_CONFIRM;
    return command(sequence, commandId, type, party);
  }

  private static String hash(long value) {
    int digit = (int) (Math.abs(value) % 10);
    return Integer.toString(digit).repeat(64);
  }
}
