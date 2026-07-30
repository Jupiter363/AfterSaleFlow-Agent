package com.example.dispute.workflow.targete2e.temporal.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import org.junit.jupiter.api.Test;

class TargetRoomAgentRunFinalizationReceiptWinningAttemptTest {

  @Test
  void keepsTheRootWorkflowCommandWhileRecordingTheWinningAttempt() {
    Fixture fixture = fixture();

    TargetRoomAgentRunFinalizationReceipt receipt =
        TargetRoomAgentRunFinalizationReceipt.completed(
            fixture.request(), fixture.result(), 17, 23);

    assertThat(receipt.commandId()).isEqualTo("hearing-root-command");
    assertThat(receipt.logicalRunId()).isEqualTo("hearing-logical-run");
    assertThat(receipt.attemptId()).isEqualTo("hearing-attempt-2");
    assertThat(receipt.attemptNo()).isEqualTo(2);
  }

  @Test
  void rejectsALaterAttemptThatReusesTheRootGraphCommandIdentity() {
    Fixture fixture = fixture();
    when(fixture.graphResult().commandId()).thenReturn("hearing-root-command");

    assertThatThrownBy(() -> TargetRoomAgentRunFinalizationReceipt.completed(
            fixture.request(), fixture.result(), 17, 23))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("completed AgentRun result does not bind its request");
  }

  @Test
  void rejectsAWinningAttemptOutsideTheRootRequestLimit() {
    Fixture fixture = fixture();
    when(fixture.result().attemptNo()).thenReturn(4L);

    assertThatThrownBy(() -> TargetRoomAgentRunFinalizationReceipt.completed(
            fixture.request(), fixture.result(), 17, 23))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("completed AgentRun result does not bind its request");
  }

  private static Fixture fixture() {
    ExecuteAgentRunRequest request = mock(ExecuteAgentRunRequest.class);
    ExecuteAgentRunResult result = mock(ExecuteAgentRunResult.class);
    RoomGraphCommand command = mock(RoomGraphCommand.class);
    RoomGraphResult graphResult = mock(RoomGraphResult.class);
    String resultHash = "a".repeat(64);

    when(request.agentRunId()).thenReturn("hearing-logical-run");
    when(request.logicalRunId()).thenReturn("hearing-logical-run");
    when(request.attemptId()).thenReturn("hearing-attempt-1");
    when(request.attemptNo()).thenReturn(1L);
    when(request.attemptLimit()).thenReturn(3);
    when(request.command()).thenReturn(command);
    when(command.tenantSurrogate()).thenReturn("tenant-hearing");
    when(command.caseId()).thenReturn("CASE_HEARING_1");
    when(command.roomType()).thenReturn(RoomType.HEARING);
    when(command.roomEpoch()).thenReturn(3L);
    when(command.processRevision()).thenReturn(9L);
    when(command.stageSequence()).thenReturn(5L);
    when(command.commandId()).thenReturn("hearing-root-command");
    when(command.logicalRunId()).thenReturn("hearing-logical-run");
    when(command.graphKey()).thenReturn("all-rooms.target-e2e.v1");
    when(command.graphVersion()).thenReturn("target-e2e-graph.2026-07-27.1");

    when(result.outcome()).thenReturn(ExecuteAgentRunResult.Outcome.COMPLETED);
    when(result.agentRunId()).thenReturn("hearing-logical-run");
    when(result.logicalRunId()).thenReturn("hearing-logical-run");
    when(result.attemptId()).thenReturn("hearing-attempt-2");
    when(result.attemptNo()).thenReturn(2L);
    when(result.graphResult()).thenReturn(graphResult);
    when(result.resultHash()).thenReturn(resultHash);
    when(graphResult.commandId()).thenReturn("hearing-retry-command");
    when(graphResult.logicalRunId()).thenReturn("hearing-logical-run");
    when(graphResult.attemptId()).thenReturn("hearing-attempt-2");
    when(graphResult.graphKey()).thenReturn("all-rooms.target-e2e.v1");
    when(graphResult.graphVersion()).thenReturn("target-e2e-graph.2026-07-27.1");
    when(graphResult.outputHash()).thenReturn(resultHash);
    return new Fixture(request, result, graphResult);
  }

  private record Fixture(
      ExecuteAgentRunRequest request,
      ExecuteAgentRunResult result,
      RoomGraphResult graphResult) {}
}
