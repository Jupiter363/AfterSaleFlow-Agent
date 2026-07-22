package com.example.dispute.workflow.room.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.temporal.room.evidence.EvidenceOperationKeys;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomSignal;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomSnapshot;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomStart;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomWorkflow;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EvidenceRoomWorkflowReplayTest {

  private static final String CASE_ID = "CASE_P5_SYNTHETIC_REPLAY";
  private static final long EPOCH = 8;
  private static final String INITIATOR = "PARTICIPANT_P5_REPLAY_INITIATOR";
  private static final String RESPONDENT = "PARTICIPANT_P5_REPLAY_RESPONDENT";

  @Test
  void warningDuplicateAndCompletionHistoryReplaysDeterministically() throws Exception {
    WorkflowExecutionHistory history;
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String taskQueue = "phase5-evidence-replay-completion";
      String workflowId = "evidence-room:" + CASE_ID + ":" + EPOCH + ":completion";
      EvidenceRoomWorkflow workflow = register(environment, taskQueue, workflowId);
      Instant openedAt = Instant.ofEpochMilli(environment.currentTimeMillis());
      WorkflowClient.start(workflow::run, start(openedAt, Duration.ofHours(2)));

      EvidenceRoomSignal initiator = signal(INITIATOR, "COMPLETE_REPLAY_INITIATOR", 1);
      workflow.partyCompleted(initiator);
      workflow.partyCompleted(initiator);
      environment.sleep(Duration.ofMinutes(90));
      workflow.partyCompleted(signal(RESPONDENT, "COMPLETE_REPLAY_RESPONDENT", 2));

      EvidenceRoomSnapshot result =
          WorkflowStub.fromTyped(workflow).getResult(EvidenceRoomSnapshot.class);
      assertThat(result.terminalReason()).isEqualTo("BOTH_PARTIES_COMPLETED");
      assertThat(result.warningSent()).isTrue();
      assertThat(result.duplicateSignalCount()).isEqualTo(1);
      history = environment.getWorkflowClient().fetchHistory(workflowId);
    }

    WorkflowReplayer.replayWorkflowExecution(history, EvidenceRoomWorkflowImpl.class);
  }

  @Test
  void deadlineExpiryHistoryReplaysDeterministically() throws Exception {
    WorkflowExecutionHistory history;
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String taskQueue = "phase5-evidence-replay-expiry";
      String workflowId = "evidence-room:" + CASE_ID + ":" + EPOCH + ":expiry";
      EvidenceRoomWorkflow workflow = register(environment, taskQueue, workflowId);
      Instant openedAt = Instant.ofEpochMilli(environment.currentTimeMillis());
      WorkflowClient.start(workflow::run, start(openedAt, Duration.ofHours(2)));
      workflow.partyCompleted(signal(INITIATOR, "COMPLETE_REPLAY_EXPIRY", 3));

      EvidenceRoomSnapshot result =
          WorkflowStub.fromTyped(workflow).getResult(EvidenceRoomSnapshot.class);
      assertThat(result.terminalReason()).isEqualTo("DEADLINE_EXPIRED");
      assertThat(result.orderedOperationKeys())
          .endsWith(EvidenceOperationKeys.deadlineExpire(CASE_ID, EPOCH, 1));
      history = environment.getWorkflowClient().fetchHistory(workflowId);
    }

    WorkflowReplayer.replayWorkflowExecution(history, EvidenceRoomWorkflowImpl.class);
  }

  private static EvidenceRoomWorkflow register(
      TestWorkflowEnvironment environment, String taskQueue, String workflowId) {
    Worker worker = environment.newWorker(taskQueue);
    worker.registerWorkflowImplementationTypes(EvidenceRoomWorkflowImpl.class);
    environment.start();
    return environment
        .getWorkflowClient()
        .newWorkflowStub(
            EvidenceRoomWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(taskQueue)
                .build());
  }

  private static EvidenceRoomStart start(Instant openedAt, Duration window) {
    return new EvidenceRoomStart(
        "evidence-room-start.v1",
        "TENANT_P5_SYNTHETIC_REPLAY",
        CASE_ID,
        "ROOM_P5_EVIDENCE_REPLAY",
        EPOCH,
        17,
        INITIATOR,
        RESPONDENT,
        openedAt,
        openedAt.plus(window),
        1,
        2,
        3,
        "evidence-workflow.synthetic.v1");
  }

  private static EvidenceRoomSignal signal(
      String participantId, String completionRequestId, int digit) {
    return new EvidenceRoomSignal(
        "evidence-room-party-completion.v1",
        participantId,
        completionRequestId,
        EvidenceOperationKeys.partyComplete(
            CASE_ID, EPOCH, participantId, completionRequestId),
        Integer.toString(digit).repeat(64));
  }
}
