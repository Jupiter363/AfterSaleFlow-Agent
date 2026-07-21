package com.example.dispute.workflow.caseprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpochReceipt;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.ActiveChildDescriptor;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.ActiveChildKind;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessSnapshot;
import com.example.dispute.workflow.temporal.caseprocess.ProvisioningCommitment;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
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
    assertThat(carry.activeRoomRevision()).isNull();
    assertThat(carry.protocolErrorOrigin()).isNull();
    assertThat(carry.provisioningManualRecoveryRequired()).isFalse();
  }

  @Test
  void readsLegacyCarryWithProtocolErrorAndMissingOrigin() throws Exception {
    String legacyJson =
        """
        {
          "schemaVersion": "case-process-carry-state.v1",
          "tenantSurrogate": null,
          "caseId": null,
          "activeRoomType": null,
          "activeRoomEpoch": -1,
          "activeChildWorkflowId": null,
          "observedProcessRevision": 0,
          "nextCommandSequence": 1,
          "nextCaseEventSequence": 1,
          "processedCommandCount": 0,
          "processedEventCount": 0,
          "recentCommands": [],
          "bufferedEvents": [],
          "highestObservedEventSequence": 0,
          "runGeneration": 1,
          "commandRecoveryAttempts": 0,
          "eventRecoveryAttempts": 0,
          "commandManualRecoveryRequired": false,
          "eventManualRecoveryRequired": false,
          "protocolErrorCode": "INTAKE_CHILD_ACTIVE_BINDING_INVALID",
          "closedRooms": [],
          "activeFencingToken": 0,
          "activeChildWorkflowRunId": null,
          "provisioningCommitments": [],
          "highestProvisionedEpochs": []
        }
        """;

    CaseProcessCarryState carry = mapper.readValue(legacyJson, CaseProcessCarryState.class);

    assertThat(carry.protocolErrorCode()).isEqualTo("INTAKE_CHILD_ACTIVE_BINDING_INVALID");
    assertThat(carry.protocolErrorOrigin()).isNull();
    assertThat(carry.provisioningManualRecoveryRequired()).isFalse();
  }

  @Test
  void provisioningManualRecoveryOutcomeSurvivesCarryRoundTrip() throws Exception {
    com.fasterxml.jackson.databind.node.ObjectNode node =
        mapper.valueToTree(CaseProcessCarryState.initial());
    node.put("provisioningManualRecoveryRequired", true);

    CaseProcessCarryState restored =
        mapper.readValue(mapper.writeValueAsBytes(node), CaseProcessCarryState.class);

    assertThat(restored.provisioningManualRecoveryRequired()).isTrue();
    assertThat(
            mapper
                .readValue(mapper.writeValueAsBytes(restored), CaseProcessCarryState.class)
                .provisioningManualRecoveryRequired())
        .isTrue();
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
    assertThat(snapshot.activeRoomRevision()).isNull();
    assertThat(snapshot.protocolErrorOrigin()).isNull();
    assertThat(snapshot.provisioningManualRecoveryRequired()).isFalse();
  }

  @Test
  void typedDescriptorMustMatchCurrentCommitmentAndReceiptRunPin() {
    ProvisionRoomEpoch request = typedProvision();
    ProvisionRoomEpochReceipt receipt = receipt(request, "receipt-run");
    ProvisioningCommitment commitment =
        new ProvisioningCommitment(
            request.updateId(), request.payloadSha256(), request, receipt);
    ActiveChildDescriptor mismatched = descriptor(request, "descriptor-run");

    assertThatThrownBy(
            () ->
                carry(request, mismatched, List.of(commitment), request.initialRoomRevision()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("provisioning commitment");

    ActiveChildDescriptor matching = descriptor(request, receipt.roomWorkflowRunId());
    CaseProcessCarryState carry =
        carry(request, matching, List.of(commitment), request.initialRoomRevision() + 2);
    assertThat(carry.activeChildDescriptor()).isEqualTo(matching);
    assertThat(carry.activeRoomRevision()).isEqualTo(request.initialRoomRevision() + 2);
  }

  private static CaseProcessCarryState carry(
      ProvisionRoomEpoch request,
      ActiveChildDescriptor descriptor,
      List<ProvisioningCommitment> commitments,
      long roomRevision) {
    return new CaseProcessCarryState(
        "case-process-carry-state.v1",
        request.tenantSurrogate(),
        request.caseId(),
        request.roomType(),
        request.roomEpoch(),
        request.roomWorkflowId(),
        request.initialProcessRevision(),
        request.firstCommandSequence(),
        request.firstCaseEventSequence(),
        0,
        0,
        List.of(),
        List.of(),
        request.lastCaseEventSequence(),
        1,
        0,
        0,
        false,
        false,
        null,
        List.of(),
        request.fencingToken(),
        descriptor.startedRunId(),
        commitments,
        List.of(new CaseProcessCarryState.ProvisionedRoomEpochHighWater(
            request.roomType(), request.roomEpoch())),
        descriptor,
        roomRevision,
        null);
  }

  private static ActiveChildDescriptor descriptor(ProvisionRoomEpoch request, String runId) {
    return new ActiveChildDescriptor(
        ActiveChildKind.TYPED_INTAKE,
        request.selectionSchemaVersion(),
        request.writerMode(),
        request.caseWorkflowType(),
        request.caseWorkflowBuildId(),
        request.roomWorkflowType(),
        request.roomWorkflowBuildId(),
        request.roomType(),
        request.roomEpoch(),
        request.fencingToken(),
        request.roomWorkflowId(),
        runId,
        "a".repeat(64),
        "b".repeat(64));
  }

  private static ProvisionRoomEpoch typedProvision() {
    return new ProvisionRoomEpoch(
        ProvisionRoomEpoch.SCHEMA_VERSION,
        "epoch-intake-3",
        "tenant-legacy",
        "CASE_Legacy",
        "room-intake-3",
        RoomType.INTAKE,
        3,
        7,
        5,
        11,
        "ACTIVE",
        "INTAKE",
        "ACTIVE",
        WriterMode.SHADOW,
        CaseProcessWorkflowProtocol.caseWorkflowId("tenant-legacy", "CASE_Legacy"),
        CaseProcessWorkflowProtocol.roomWorkflowId("CASE_Legacy", RoomType.INTAKE, 3),
        "room-epoch-selection.v2",
        "case-process-contract.v1",
        CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
        "case-control.synthetic.v1",
        "IntakeRoomWorkflow",
        "intake-room.synthetic.v1",
        "intake.v2",
        "2.0.0",
        "intake-checkpoint.v2",
        "agent-stream.v2",
        3,
        4,
        4,
        5,
        Instant.parse("2026-07-21T10:00:00Z"),
        null,
        null,
        Instant.parse("2026-07-21T08:00:00Z"));
  }

  private static ProvisionRoomEpochReceipt receipt(
      ProvisionRoomEpoch request, String roomWorkflowRunId) {
    return new ProvisionRoomEpochReceipt(
        "provision-room-epoch-receipt.v1",
        request.epochId(),
        request.tenantSurrogate(),
        request.caseId(),
        request.roomId(),
        request.roomType(),
        request.roomEpoch(),
        request.fencingToken(),
        request.initialProcessRevision(),
        request.initialRoomRevision(),
        request.macroPhase(),
        request.currentRoom(),
        request.roomPhase(),
        request.projectedDeadlineAt(),
        request.writerMode(),
        request.selectionSchemaVersion(),
        request.processContractVersion(),
        request.caseWorkflowType(),
        request.caseWorkflowBuildId(),
        request.roomWorkflowType(),
        request.roomWorkflowBuildId(),
        request.graphKey(),
        request.graphVersion(),
        request.checkpointSchemaVersion(),
        request.streamProtocol(),
        request.lastCommandSequence(),
        request.lastCaseEventSequence(),
        request.firstCommandSequence(),
        request.firstCaseEventSequence(),
        request.projectionRef(),
        request.projectionSha256(),
        request.requestedAt(),
        request.caseWorkflowId(),
        "case-run",
        request.roomWorkflowId(),
        roomWorkflowRunId,
        request.payloadSha256());
  }
}
