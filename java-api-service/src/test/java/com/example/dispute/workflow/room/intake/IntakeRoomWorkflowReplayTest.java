package com.example.dispute.workflow.room.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventType;
import com.example.dispute.workflow.temporal.room.intake.IntakeGraphExecutionRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
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
  private static final String INITIATOR_SCOPE = "8".repeat(64);
  private static final String RESPONDENT_SCOPE = "9".repeat(64);

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

      IntakeWorkflowCommand message =
          command(1, "CMD_REPLAY_MESSAGE", IntakeCommandType.INTAKE_MESSAGE);
      workflow.commandAccepted(message);
      workflow.domainEventCommitted(
          event(
              1,
              "EVENT_REPLAY_READY",
              message,
              IntakeDomainEventType.TURN_READY_TO_CONFIRM));

      IntakeWorkflowCommand confirm =
          command(2, "CMD_REPLAY_CONFIRM", IntakeCommandType.INTAKE_CONFIRM);
      workflow.commandAccepted(confirm);
      workflow.domainEventCommitted(
          event(
              2,
              "EVENT_REPLAY_REJECT",
              confirm,
              IntakeDomainEventType.NOT_ADMISSIBLE));

      IntakeRoomSnapshot result =
          WorkflowStub.fromTyped(workflow).getResult(IntakeRoomSnapshot.class);
      assertThat(result.terminalReason()).isEqualTo(IntakeTerminalReason.NOT_ADMISSIBLE);
      assertThat(result.nextCommandSequence()).isEqualTo(3);
      assertThat(result.nextEventSequence()).isEqualTo(3);
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
        "no-tools.v1",
        INITIATOR_SCOPE,
        RESPONDENT_SCOPE);
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
        INITIATOR_SCOPE,
        "urn:after-sale-flow:intake-command:" + commandId,
        hash(sequence),
        operationKey(commandId),
        hash(sequence));
  }

  private static IntakeDomainEventRef event(
      long eventSequence,
      String eventId,
      IntakeWorkflowCommand command,
      IntakeDomainEventType eventType) {
    String resultHash = hash(eventSequence + 4);
    boolean turnEvent =
        eventType == IntakeDomainEventType.TURN_NEEDS_INPUT
            || eventType == IntakeDomainEventType.TURN_READY_TO_CONFIRM;
    IntakeAgentRunRef agentRunRef =
        turnEvent
            ? new IntakeAgentRunRef(
                "intake-agent-run-ref.v1",
                "RUN_" + command.commandId(),
                "ATTEMPT_" + command.commandId(),
                resultHash)
            : null;
    IntakeGraphExecutionRef graphExecutionRef =
        turnEvent
            ? new IntakeGraphExecutionRef(
                "intake-graph-execution-ref.v1",
                "grt.v1." + "b".repeat(32),
                command.commandId(),
                "intake.v2",
                "2.0.0",
                "CHECKPOINT_" + command.commandId(),
                "urn:after-sale-flow:graph-result:" + command.commandId(),
                resultHash,
                "urn:after-sale-flow:intake-proposal:" + command.commandId(),
                hash(eventSequence + 6))
            : null;
    return new IntakeDomainEventRef(
        "intake-domain-event-ref.v1",
        eventId,
        "urn:after-sale-flow:intake-event:" + eventId,
        hash(eventSequence + 5),
        eventSequence,
        eventType,
        IntakeParty.INITIATOR,
        command.commandId(),
        TENANT,
        CASE_ID,
        EPOCH,
        FENCE,
        INITIATOR_SCOPE,
        command.operationKey(),
        command.requestHash(),
        resultHash,
        eventSequence,
        eventSequence,
        agentRunRef,
        graphExecutionRef);
  }

  private static String operationKey(String commandId) {
    return "intake.operation:" + CASE_ID + ":" + commandId;
  }

  private static String hash(long value) {
    return Long.toString(Math.abs(value) % 10).repeat(64);
  }
}
