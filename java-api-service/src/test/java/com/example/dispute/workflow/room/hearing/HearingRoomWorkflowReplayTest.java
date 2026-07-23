package com.example.dispute.workflow.room.hearing;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.hearing.domain.HearingAuthorityCommit;
import com.example.dispute.workflow.activity.hearing.HearingDomainReceiptAdapter;
import com.example.dispute.workflow.temporal.room.hearing.HearingOperationKeys;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomSnapshot;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomStart;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomWorkflow;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomWorkflowImpl;
import com.example.dispute.workflow.temporal.room.hearing.HearingStageReceipt;
import com.example.dispute.workflow.temporal.room.hearing.HearingWorkflowStage;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HearingRoomWorkflowReplayTest {

  private static final String TASK_QUEUE = "phase6-hearing-room-replay-test";

  private TestWorkflowEnvironment environment;
  private WorkflowClient client;

  @BeforeEach
  void setUp() {
    environment = TestWorkflowEnvironment.newInstance();
    Worker worker = environment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(HearingRoomWorkflowImpl.class);
    environment.start();
    client = environment.getWorkflowClient();
  }

  @AfterEach
  void tearDown() {
    environment.close();
  }

  @Test
  void outOfOrderReceiptsMergeByRevisionAndDuplicatesReplayIdempotently() throws Exception {
    Started started = start("out-of-order");
    HearingStageReceipt first = started.receipts().stageCompletion(
        HearingWorkflowStage.COURT_PREPARING,
        0,
        0,
        1,
        HearingWorkflowStage.CASE_INTRODUCTION,
        null);
    HearingStageReceipt second = started.receipts().stageCompletion(
        HearingWorkflowStage.CASE_INTRODUCTION,
        1,
        1,
        2,
        HearingWorkflowStage.EVIDENCE_INTRODUCTION,
        null);

    started.workflow().stageCompleted(second);
    HearingRoomSnapshot buffered = HearingRoomWorkflowTest.awaitState(
        started.workflow(), state -> state.pendingReceiptRevisions().contains(2L));
    assertThat(buffered.stage()).isEqualTo(HearingWorkflowStage.COURT_PREPARING);
    assertThat(buffered.processRevision()).isZero();

    started.workflow().stageCompleted(first);
    HearingRoomSnapshot merged = HearingRoomWorkflowTest.awaitStage(
        started.workflow(), HearingWorkflowStage.EVIDENCE_INTRODUCTION);
    assertThat(merged.processRevision()).isEqualTo(2);
    assertThat(merged.roomRevision()).isEqualTo(2);
    assertThat(merged.lastCommittedEventSequence()).isEqualTo(2);
    assertThat(merged.pendingReceiptRevisions()).isEmpty();

    started.workflow().stageCompleted(first);
    HearingRoomSnapshot duplicate = HearingRoomWorkflowTest.awaitState(
        started.workflow(), state -> state.duplicateSignalCount() == 1);
    assertThat(duplicate.processRevision()).isEqualTo(2);
    assertThat(duplicate.rejectedSignalCount()).isZero();

    String firstOperationKey = HearingOperationKeys.stageCompletion(
        HearingReceiptTestFactory.TENANT,
        HearingReceiptTestFactory.CASE_ID,
        HearingReceiptTestFactory.ROOM_EPOCH,
        HearingWorkflowStage.COURT_PREPARING,
        HearingWorkflowStage.COURT_PREPARING.sequence());
    HearingStageReceipt conflicting = HearingDomainReceiptAdapter.stage(
        started.receipts().domainReceipt(
            HearingWorkflowStage.COURT_PREPARING,
            0,
            0,
            3,
            HearingAuthorityCommit.OperationType.STAGE,
            firstOperationKey,
            HearingReceiptTestFactory.hash("conflicting-request"),
            HearingWorkflowStage.CASE_INTRODUCTION,
            null,
            "conflict"));
    assertThat(conflicting.committed().receiptId()).isEqualTo(first.committed().receiptId());
    started.workflow().stageCompleted(conflicting);
    HearingRoomSnapshot rejected = HearingRoomWorkflowTest.awaitState(
        started.workflow(), state -> state.rejectedSignalCount() == 1);
    assertThat(rejected.protocolErrorCode())
        .isEqualTo("HEARING_RECEIPT_ID_PAYLOAD_CONFLICT");
    assertThat(rejected.stage()).isEqualTo(HearingWorkflowStage.EVIDENCE_INTRODUCTION);

    WorkflowExecutionHistory history = client.fetchHistory(started.workflowId());
    WorkflowReplayer.replayWorkflowExecution(history, HearingRoomWorkflowImpl.class);
  }

  @Test
  void brokenAuthorityCausalChainFailsClosedAtExpectedRevision() {
    Started started = start("causal-chain");
    HearingStageReceipt wrongRoomRevision = started.receipts().stageCompletion(
        HearingWorkflowStage.COURT_PREPARING,
        0,
        9,
        1,
        HearingWorkflowStage.CASE_INTRODUCTION,
        null);

    started.workflow().stageCompleted(wrongRoomRevision);
    HearingRoomSnapshot rejected = HearingRoomWorkflowTest.awaitState(
        started.workflow(), state -> state.rejectedSignalCount() == 1);
    assertThat(rejected.stage()).isEqualTo(HearingWorkflowStage.COURT_PREPARING);
    assertThat(rejected.status()).isEqualTo("FAILED");
    assertThat(rejected.processRevision()).isZero();
    assertThat(rejected.roomRevision()).isZero();
    assertThat(rejected.pendingReceiptRevisions()).containsExactly(1L);
    assertThat(rejected.protocolErrorCode())
        .isEqualTo("HEARING_RECEIPT_CAUSAL_CHAIN_MISMATCH");
  }

  private Started start(String suffix) {
    Instant openedAt = Instant.ofEpochMilli(environment.currentTimeMillis());
    HearingRoomStart start = HearingReceiptTestFactory.start(openedAt, Duration.ofMinutes(20));
    HearingReceiptTestFactory receipts = new HearingReceiptTestFactory(start);
    String workflowId = "hearing-room:" + HearingReceiptTestFactory.CASE_ID + ':'
        + HearingReceiptTestFactory.ROOM_EPOCH + ":replay:" + suffix;
    HearingRoomWorkflow workflow = client.newWorkflowStub(
        HearingRoomWorkflow.class,
        WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setTaskQueue(TASK_QUEUE)
            .build());
    WorkflowClient.start(workflow::run, start);
    HearingRoomWorkflowTest.awaitStage(workflow, HearingWorkflowStage.COURT_PREPARING);
    return new Started(workflow, workflowId, receipts);
  }

  private record Started(
      HearingRoomWorkflow workflow,
      String workflowId,
      HearingReceiptTestFactory receipts) {}
}
