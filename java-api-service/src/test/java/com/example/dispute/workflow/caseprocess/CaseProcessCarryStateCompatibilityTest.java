package com.example.dispute.workflow.caseprocess;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.ActiveChildDescriptor;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.ActiveChildKind;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessSnapshot;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

class CaseProcessCarryStateCompatibilityTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void readsLegacyCarryWithoutDescriptorAndNeverInfersTypedKind() throws Exception {
    String legacyJson =
        """
        {
          "schemaVersion": "case-process-carry-state.v1",
          "tenantSurrogate": "tenant-legacy",
          "caseId": "CASE_Legacy",
          "activeRoomType": "INTAKE",
          "activeRoomEpoch": 3,
          "activeChildWorkflowId": "room:CASE_Legacy:intake:3",
          "observedProcessRevision": 7,
          "nextCommandSequence": 4,
          "nextCaseEventSequence": 5,
          "processedCommandCount": 3,
          "processedEventCount": 4,
          "recentCommands": [],
          "bufferedEvents": [],
          "highestObservedEventSequence": 4,
          "runGeneration": 1,
          "commandRecoveryAttempts": 0,
          "eventRecoveryAttempts": 0,
          "commandManualRecoveryRequired": false,
          "eventManualRecoveryRequired": false,
          "protocolErrorCode": null,
          "closedRooms": [],
          "activeFencingToken": 0,
          "activeChildWorkflowRunId": "legacy-run",
          "provisioningCommitments": [],
          "highestProvisionedEpochs": []
        }
        """;

    CaseProcessCarryState carry = mapper.readValue(legacyJson, CaseProcessCarryState.class);

    assertThat(carry.activeChildDescriptor()).isNull();
    assertThat(carry.activeChildWorkflowId()).isEqualTo("room:CASE_Legacy:intake:3");
  }

  @Test
  void explicitGenericDescriptorRoundTripsWithoutChangingTheV1SchemaKey() throws Exception {
    ActiveChildDescriptor descriptor =
        new ActiveChildDescriptor(
            ActiveChildKind.GENERIC_ROOM_CONTROL,
            "room-epoch-selection.v2",
            WriterMode.SHADOW,
            CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
            "case-control.synthetic.v1",
            "IntakeRoomWorkflow",
            "intake-room.synthetic.v1",
            RoomType.INTAKE,
            3,
            7,
            "room:CASE_Legacy:intake:3",
            "legacy-run");
    CaseProcessCarryState carry =
        new CaseProcessCarryState(
            "case-process-carry-state.v1",
            "tenant-legacy",
            "CASE_Legacy",
            RoomType.INTAKE,
            3,
            descriptor.workflowId(),
            7,
            4,
            5,
            3,
            4,
            java.util.List.of(),
            java.util.List.of(),
            4,
            1,
            0,
            0,
            false,
            false,
            null,
            java.util.List.of(),
            7,
            descriptor.startedRunId(),
            java.util.List.of(),
            java.util.List.of(),
            descriptor);

    CaseProcessCarryState restored =
        mapper.readValue(mapper.writeValueAsBytes(carry), CaseProcessCarryState.class);

    assertThat(restored.schemaVersion()).isEqualTo("case-process-carry-state.v1");
    assertThat(restored.activeChildDescriptor()).isEqualTo(descriptor);
    assertThat(restored.activeChildDescriptor().kind())
        .isEqualTo(ActiveChildKind.GENERIC_ROOM_CONTROL);
  }

  @Test
  void readsOriginalSnapshotWithoutNewObservableChildFields() throws Exception {
    String legacyJson =
        """
        {
          "schemaVersion": "case-process-snapshot.v1",
          "workflowId": "case:tenant-legacy:CASE_Legacy",
          "workflowRunId": "run-legacy",
          "tenantSurrogate": "tenant-legacy",
          "caseId": "CASE_Legacy",
          "macroPhase": "CONTROL_PLANE_SHADOW",
          "activeRoomType": "INTAKE",
          "activeRoomEpoch": 3,
          "activeChildWorkflowId": "room:CASE_Legacy:intake:3",
          "observedProcessRevision": 7,
          "nextCommandSequence": 4,
          "nextCaseEventSequence": 5,
          "processedCommandCount": 3,
          "processedEventCount": 4,
          "pendingCommandCount": 0,
          "bufferedEventCount": 0,
          "recentCommandCount": 0,
          "highestObservedCommandSequence": 3,
          "highestObservedEventSequence": 4,
          "runGeneration": 1,
          "blockedReason": "NONE",
          "protocolErrorCode": null,
          "recentCommandIds": [],
          "activeFencingToken": 7,
          "activeChildWorkflowRunId": "legacy-run",
          "provisioningCommitmentCount": 1,
          "activeProvisioningSha256": null
        }
        """;

    CaseProcessSnapshot snapshot = mapper.readValue(legacyJson, CaseProcessSnapshot.class);

    assertThat(snapshot.activeChildKind()).isNull();
    assertThat(snapshot.activeSelectionSchemaVersion()).isNull();
    assertThat(snapshot.activeRoomWorkflowType()).isNull();
    assertThat(snapshot.activeRoomWorkflowBuildId()).isNull();
  }
}
