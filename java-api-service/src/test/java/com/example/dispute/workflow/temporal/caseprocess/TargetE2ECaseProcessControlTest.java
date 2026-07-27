package com.example.dispute.workflow.temporal.caseprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.ActiveChildDescriptor;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.ActiveChildKind;
import com.example.dispute.workflow.targete2e.temporal.TargetTypedRoomProtocol;
import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.Test;

class TargetE2ECaseProcessControlTest {

  @Test
  void targetMarkerSelectsOneFencedTypedChildKindForEveryRoomType() {
    for (RoomType roomType : RoomType.values()) {
      ActiveChildKind kind =
          CaseProcessWorkflowImpl.selectProvisionedChildKind(
              1,
              1,
              "room-epoch-selection.v2",
              TargetTypedRoomProtocol.PROCESS_CONTRACT_VERSION,
              WriterMode.TEMPORAL,
              roomType,
              "CaseProcessWorkflow",
              "p9-case-build",
              TargetTypedRoomProtocol.workflowType(roomType),
              "p9-control-build",
              "all-rooms.target-e2e.v1",
              TargetTypedRoomProtocol.GRAPH_VERSION,
              TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION,
              TargetTypedRoomProtocol.STREAM_PROTOCOL);

      assertThat(kind).isEqualTo(ActiveChildKind.TARGET_TYPED_ROOM);
    }
  }

  @Test
  void unknownHistoricalMarkerRejectsTemporalSelection() {
    assertThatThrownBy(
            () ->
                CaseProcessWorkflowImpl.selectProvisionedChildKind(
                    1,
                    -1,
                    "room-epoch-selection.v2",
                    TargetTypedRoomProtocol.PROCESS_CONTRACT_VERSION,
                    WriterMode.TEMPORAL,
                    RoomType.INTAKE,
                    "CaseProcessWorkflow",
                    "p9-case-build",
                    TargetTypedRoomProtocol.workflowType(RoomType.INTAKE),
                    "p9-control-build",
                    "all-rooms.target-e2e.v1",
                    TargetTypedRoomProtocol.GRAPH_VERSION,
                    TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION,
                    TargetTypedRoomProtocol.STREAM_PROTOCOL))
        .isInstanceOf(ApplicationFailure.class)
        .extracting(failure -> ((ApplicationFailure) failure).getType())
        .isEqualTo("TARGET_TYPED_ROOM_HISTORY_UNSUPPORTED");
  }

  @Test
  void targetDescriptorCarriesExactWorkflowBuildEpochAndFencePins() {
    for (RoomType roomType : RoomType.values()) {
      ActiveChildDescriptor descriptor =
          new ActiveChildDescriptor(
              ActiveChildKind.TARGET_TYPED_ROOM,
              "room-epoch-selection.v2",
              WriterMode.TEMPORAL,
              "CaseProcessWorkflow",
              "p9-case-build",
              TargetTypedRoomProtocol.workflowType(roomType),
              "p9-control-build",
              roomType,
              3,
              17,
              "room-child-3",
              "room-run-3");

      CaseProcessWorkflowImpl.validateTargetTypedDescriptor(descriptor);
      assertThat(descriptor.roomType()).isEqualTo(roomType);
      assertThat(descriptor.roomEpoch()).isEqualTo(3);
      assertThat(descriptor.fencingToken()).isEqualTo(17);
      assertThat(descriptor.roomWorkflowBuildId()).isEqualTo("p9-control-build");
    }
  }

  @Test
  void v1TemporalSelectionCannotBeRelabeledAsGenericRoomControl() {
    assertThatThrownBy(
            () ->
                CaseProcessWorkflowImpl.selectProvisionedChildKind(
                    1,
                    1,
                    "room-epoch-selection.v1",
                    TargetTypedRoomProtocol.PROCESS_CONTRACT_VERSION,
                    WriterMode.TEMPORAL,
                    RoomType.EVIDENCE,
                    "CaseProcessWorkflow",
                    "p9-case-build",
                    null,
                    null,
                    "evidence.v2",
                    TargetTypedRoomProtocol.GRAPH_VERSION,
                    TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION,
                    TargetTypedRoomProtocol.STREAM_PROTOCOL))
        .isInstanceOf(ApplicationFailure.class)
        .extracting(failure -> ((ApplicationFailure) failure).getType())
        .isEqualTo("TARGET_TYPED_ROOM_SELECTION_INVALID");
  }

  @Test
  void targetSelectionCannotBindAWorkflowForAnotherRoomType() {
    assertThatThrownBy(
            () ->
                CaseProcessWorkflowImpl.selectProvisionedChildKind(
                    1,
                    1,
                    "room-epoch-selection.v2",
                    TargetTypedRoomProtocol.PROCESS_CONTRACT_VERSION,
                    WriterMode.TEMPORAL,
                    RoomType.EVIDENCE,
                    "CaseProcessWorkflow",
                    "p9-case-build",
                    TargetTypedRoomProtocol.workflowType(RoomType.HEARING),
                    "p9-control-build",
                    "all-rooms.target-e2e.v1",
                    TargetTypedRoomProtocol.GRAPH_VERSION,
                    TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION,
                    TargetTypedRoomProtocol.STREAM_PROTOCOL))
        .isInstanceOf(ApplicationFailure.class)
        .extracting(failure -> ((ApplicationFailure) failure).getType())
        .isEqualTo("TARGET_TYPED_ROOM_SELECTION_INVALID");
  }

  @Test
  void targetSelectionCannotMixGraphProtocolPins() {
    assertThatThrownBy(
            () ->
                CaseProcessWorkflowImpl.selectProvisionedChildKind(
                    1,
                    1,
                    "room-epoch-selection.v2",
                    TargetTypedRoomProtocol.PROCESS_CONTRACT_VERSION,
                    WriterMode.TEMPORAL,
                    RoomType.REVIEW,
                    TargetTypedRoomProtocol.CASE_WORKFLOW_TYPE,
                    "p9-case-build",
                    TargetTypedRoomProtocol.workflowType(RoomType.REVIEW),
                    "p9-control-build",
                    TargetTypedRoomProtocol.GRAPH_KEY,
                    "target-e2e-graph.mixed",
                    TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION,
                    TargetTypedRoomProtocol.STREAM_PROTOCOL))
        .isInstanceOf(ApplicationFailure.class)
        .extracting(failure -> ((ApplicationFailure) failure).getType())
        .isEqualTo("TARGET_TYPED_ROOM_SELECTION_INVALID");
  }
}
