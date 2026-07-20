package com.example.dispute.workflow.room.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.example.dispute.workflow.temporal.room.intake.IntakeReceiptType;
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
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.Test;

class IntakeRoomWorkflowReplayTest {

  private static final String TENANT = "tenant-p4-replay";
  private static final String CASE_ID = "CASE_P4_INTAKE_REPLAY";
  private static final long EPOCH = 2;
  private static final long FENCE = 11;

  @Test
  void terminalNotAdmissibleHistoryReplaysDeterministically() throws Exception {
    io.temporal.common.WorkflowExecutionHistory history;
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String taskQueue = "phase4-intake-replay-test";
      String workflowId = "intake-room:" + CASE_ID + ":" + EPOCH;
      Worker worker = environment.newWorker(taskQueue);
      worker.registerWorkflowImplementationTypes(IntakeRoomWorkflowImpl.class);
      environment.start();
      WorkflowClient client = environment.getWorkflowClient();
      IntakeRoomWorkflow workflow =
          client.newWorkflowStub(
              IntakeRoomWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setWorkflowId(workflowId)
                  .setTaskQueue(taskQueue)
                  .build());
      WorkflowClient.start(workflow::run, start());
      workflow.commandAccepted(command(1, "CMD_REPLAY_MESSAGE", IntakeCommandType.INTAKE_MESSAGE));
      workflow.domainReceiptCommitted(
          receipt(
              1,
              1,
              "RCP_REPLAY_READY",
              "CMD_REPLAY_MESSAGE",
              IntakeReceiptType.TURN_READY_TO_CONFIRM));
      workflow.commandAccepted(command(2, "CMD_REPLAY_CONFIRM", IntakeCommandType.INTAKE_CONFIRM));
      workflow.domainReceiptCommitted(
          receipt(
              2,
              2,
              "RCP_REPLAY_REJECT",
              "CMD_REPLAY_CONFIRM",
              IntakeReceiptType.NOT_ADMISSIBLE));

      IntakeRoomSnapshot result =
          WorkflowStub.fromTyped(workflow).getResult(IntakeRoomSnapshot.class);
      assertThat(result.terminalReason()).isEqualTo(IntakeTerminalReason.NOT_ADMISSIBLE);
      history = client.fetchHistory(workflowId);
    }

    WorkflowReplayer.replayWorkflowExecution(history, IntakeRoomWorkflowImpl.class);
  }

  private static IntakeRoomStart start() {
    return new IntakeRoomStart(
        "intake-room-start.v1",
        TENANT,
        CASE_ID,
        EPOCH,
        FENCE,
        0,
        0,
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
      long sequence, String commandId, IntakeCommandType type) {
    return new IntakeWorkflowCommand(
        "intake-workflow-command.v1",
        commandId,
        TENANT,
        CASE_ID,
        EPOCH,
        FENCE,
        sequence,
        type,
        IntakeParty.INITIATOR,
        hash(8),
        "REF_" + commandId,
        hash(sequence),
        operationKey(commandId),
        hash(sequence));
  }

  private static IntakeDomainReceipt receipt(
      long eventSequence,
      long commandSequence,
      String receiptId,
      String commandId,
      IntakeReceiptType type) {
    return new IntakeDomainReceipt(
        "intake-domain-receipt.v1",
        receiptId,
        commandId,
        TENANT,
        CASE_ID,
        EPOCH,
        FENCE,
        eventSequence,
        eventSequence,
        eventSequence,
        type,
        IntakeParty.INITIATOR,
        operationKey(commandId),
        hash(commandSequence),
        hash(eventSequence + 4),
        hash(eventSequence + 5));
  }

  private static String operationKey(String commandId) {
    return "intake.operation:" + CASE_ID + ":" + commandId;
  }

  private static String hash(long value) {
    return Long.toString(Math.abs(value) % 10).repeat(64);
  }
}
