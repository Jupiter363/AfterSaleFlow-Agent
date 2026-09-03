package com.example.dispute.workflow.room;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpochReceipt;
import com.example.dispute.workflow.temporal.caseprocess.CaseDomainEventRef;
import com.example.dispute.workflow.temporal.room.common.RoomControlSnapshot;
import com.example.dispute.workflow.temporal.room.common.RoomControlStart;
import com.example.dispute.workflow.temporal.room.common.RoomControlWorkflow;
import com.example.dispute.workflow.temporal.room.common.RoomControlWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoomControlWorkflowReplayTest {

  @Test
  void preRolloverRoomHistoryReplaysWithoutSchedulingANewTimer() throws Exception {
    String workflowId = "room-workflow:CASE_RoomReplay:EVIDENCE:0";
    io.temporal.common.WorkflowExecutionHistory legacyHistory;
    try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
      Worker worker = environment.newWorker(CaseProcessWorkflowProtocol.ROOM_CONTROL_TASK_QUEUE);
      worker.registerWorkflowImplementationTypes(LegacyRoomControlWorkflowImpl.class);
      environment.start();
      WorkflowClient client = environment.getWorkflowClient();
      RoomControlWorkflow workflow =
          client.newWorkflowStub(
              RoomControlWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setWorkflowId(workflowId)
                  .setTaskQueue(CaseProcessWorkflowProtocol.ROOM_CONTROL_TASK_QUEUE)
                  .build());
      WorkflowClient.start(
          workflow::run,
          new RoomControlStart(
              "room-control-start.v1",
              "tenant-room-replay",
              "CASE_RoomReplay",
              RoomType.EVIDENCE,
              0,
              "case-process:tenant-room-replay:CASE_RoomReplay",
              1,
              1));
      workflow.close("REPLAY_CAPTURE_COMPLETE");
      WorkflowStub.fromTyped(workflow).getResult(Void.class);
      legacyHistory = client.fetchHistory(workflowId);
    }

    WorkflowReplayer.replayWorkflowExecution(legacyHistory, RoomControlWorkflowImpl.class);
  }

  public static final class LegacyRoomControlWorkflowImpl implements RoomControlWorkflow {

    private RoomControlStart start;
    private boolean closeRequested;
    private String closeReason;

    @Override
    public void run(RoomControlStart start) {
      this.start = start;
      Workflow.await(() -> closeRequested);
      Workflow.await(Workflow::isEveryHandlerFinished);
    }

    @Override
    public void commandAccepted(CaseCommandRef command) {}

    @Override
    public void domainEventCommitted(CaseDomainEventRef event) {}

    @Override
    public void close(String reasonCode) {
      closeRequested = true;
      closeReason = reasonCode;
    }

    @Override
    public RoomControlSnapshot state() {
      return new RoomControlSnapshot(
          "room-control-snapshot.v1",
          start == null ? null : start.tenantSurrogate(),
          start == null ? null : start.caseId(),
          start == null ? null : start.roomType(),
          start == null ? 0 : start.roomEpoch(),
          null,
          0,
          0,
          0,
          0,
          0,
          List.of(),
          List.of(),
          closeRequested,
          closeReason,
          null);
    }

    @Override
    public ProvisionRoomEpochReceipt provisioningReceipt() {
      return null;
    }
  }
}
