package com.example.dispute.workflow.room.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityExecutionState;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityProtocol;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityProtocol.ActivityRequest;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityProtocol.InvocationMode;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceActivityProtocol.ReceiptLookupResult;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceFinalizationReceiptRef;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.RetryOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EvidenceRoomWorkflowWorkerRecoveryTest {

  @Test
  void lostActivityResponseRecoversOnlyFromAnExplicitCommittedReceipt() throws Exception {
    WorkflowExecutionHistory history;
    RecordingReceiptLedger ledger = new RecordingReceiptLedger();
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      String taskQueue = "phase5-evidence-receipt-recovery";
      Worker worker = environment.newWorker(taskQueue);
      worker.registerWorkflowImplementationTypes(ReceiptRecoveryWorkflowImpl.class);
      worker.registerActivitiesImplementations(ledger);
      environment.start();

      ReceiptRecoveryWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
          ReceiptRecoveryWorkflow.class,
          WorkflowOptions.newBuilder()
              .setWorkflowId("evidence-room-recovery:CASE_P5_SYNTHETIC_ACTIVITY:7")
              .setTaskQueue(taskQueue)
              .build());
      ActivityRequest request = EvidenceRoomActivityContractTest.request(InvocationMode.INITIAL_LOOKUP);
      WorkflowClient.start(workflow::run, request);

      EvidenceActivityExecutionState result =
          WorkflowStub.fromTyped(workflow).getResult(EvidenceActivityExecutionState.class);
      assertThat(result.permitsProgress()).isTrue();
      assertThat(result.committedReceipt()).isEqualTo(ledger.receipt);
      assertThat(ledger.calls.get()).isEqualTo(2);
      history = environment.getWorkflowClient().fetchHistory(
          "evidence-room-recovery:CASE_P5_SYNTHETIC_ACTIVITY:7");
    }

    WorkflowReplayer.replayWorkflowExecution(history, ReceiptRecoveryWorkflowImpl.class);
  }

  @WorkflowInterface
  public interface ReceiptRecoveryWorkflow {

    @WorkflowMethod
    EvidenceActivityExecutionState run(ActivityRequest request);
  }

  public static final class ReceiptRecoveryWorkflowImpl implements ReceiptRecoveryWorkflow {

    private final EvidenceRoomActivities activities = Workflow.newActivityStub(
        EvidenceRoomActivities.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
            .build());

    @Override
    public EvidenceActivityExecutionState run(ActivityRequest request) {
      EvidenceActivityExecutionState state = EvidenceActivityProtocol.begin(request);
      try {
        activities.loadCommittedReceipt(request);
      } catch (ActivityFailure lostResponse) {
        state = EvidenceActivityProtocol.activityResponseLost(state);
      }

      ActivityRequest reconciliationRequest = new ActivityRequest(
          request.schemaVersion(),
          request.operationType(),
          request.tenantSurrogate(),
          request.caseId(),
          request.roomEpoch(),
          request.fencingToken(),
          request.manifestHash(),
          request.processRevision(),
          request.roomRevision(),
          request.operationKey(),
          request.requestHash(),
          InvocationMode.RETRY_RECONCILE_ONLY);
      ReceiptLookupResult lookup = activities.loadCommittedReceipt(reconciliationRequest);
      return EvidenceActivityProtocol.reconcile(state, reconciliationRequest, lookup);
    }
  }

  private static final class RecordingReceiptLedger implements EvidenceRoomActivities {

    private final AtomicInteger calls = new AtomicInteger();
    private EvidenceFinalizationReceiptRef receipt;

    @Override
    public ReceiptLookupResult loadCommittedReceipt(ActivityRequest request) {
      if (calls.incrementAndGet() == 1) {
        receipt = EvidenceRoomActivityContractTest.receipt(request);
        throw ApplicationFailure.newFailure("simulated completion response loss", "RESPONSE_LOST");
      }
      return ReceiptLookupResult.committed(receipt);
    }
  }
}
