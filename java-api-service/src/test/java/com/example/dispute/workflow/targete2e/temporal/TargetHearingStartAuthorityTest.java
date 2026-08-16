package com.example.dispute.workflow.targete2e.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.targete2e.temporal.room.hearing.TargetHearingBootstrapActivities;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomStart;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TargetHearingStartAuthorityTest {

  @Test
  void configuredPartyWindowFlowsFromBootstrapAuthorityIntoExactHearingStart() {
    ProvisionRoomEpoch provision = provision();
    TargetHearingBootstrapActivities.Binding authority = authority(provision, 1_200);

    HearingRoomStart first =
        TargetTypedRoomCaseProcessDispatcher.targetHearingStart(
            provision, authority, authority.partyStageWindowSeconds());
    HearingRoomStart replay =
        TargetTypedRoomCaseProcessDispatcher.targetHearingStart(
            provision, authority, authority.partyStageWindowSeconds());

    assertThat(first).isEqualTo(replay);
    assertThat(first.partyStageWindowSeconds()).isEqualTo(1_200);
    assertThat(first.partyStageWindowSeconds()).isNotEqualTo(300);
    assertThat(first.openedAt()).isEqualTo(provision.requestedAt());
    assertThat(first.hearingDeadlineAt()).isEqualTo(provision.projectedDeadlineAt());

    assertThatThrownBy(
            () ->
                TargetTypedRoomCaseProcessDispatcher.targetHearingStart(
                    provision, authority, 0))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("party-stage window authority");
  }

  private static TargetHearingBootstrapActivities.Binding authority(
      ProvisionRoomEpoch provision, long partyStageWindowSeconds) {
    return new TargetHearingBootstrapActivities.Binding(
        provision.roomId(),
        provision.epochId(),
        provision.roomEpoch(),
        provision.fencingToken(),
        provision.initialProcessRevision(),
        provision.initialRoomRevision(),
        "COURT_PREPARING",
        1,
        "user-local",
        "merchant-local",
        partyStageWindowSeconds);
  }

  private static ProvisionRoomEpoch provision() {
    String tenant = "tenant-run001";
    String caseId = "QA_TARGET_HEARING_1";
    long roomEpoch = 3;
    Instant requestedAt = Instant.parse("2026-08-16T15:30:00Z");
    return new ProvisionRoomEpoch(
        ProvisionRoomEpoch.SCHEMA_VERSION,
        "epoch-target-hearing-1",
        tenant,
        caseId,
        "room-target-hearing-1",
        RoomType.HEARING,
        roomEpoch,
        6,
        4,
        19,
        "ACTIVE",
        "HEARING",
        "ACTIVE",
        WriterMode.TEMPORAL,
        CaseProcessWorkflowProtocol.caseWorkflowId(tenant, caseId),
        CaseProcessWorkflowProtocol.roomWorkflowId(caseId, RoomType.HEARING, roomEpoch),
        TargetTypedRoomProtocol.SELECTION_SCHEMA_VERSION,
        TargetTypedRoomProtocol.PROCESS_CONTRACT_VERSION,
        TargetTypedRoomProtocol.CASE_WORKFLOW_TYPE,
        "local-hearing-case-build",
        TargetTypedRoomProtocol.workflowType(RoomType.HEARING),
        "local-hearing-control-build",
        TargetTypedRoomProtocol.GRAPH_KEY,
        TargetTypedRoomProtocol.GRAPH_VERSION,
        TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION,
        TargetTypedRoomProtocol.STREAM_PROTOCOL,
        9,
        11,
        10,
        12,
        requestedAt.plusSeconds(10_800),
        null,
        null,
        requestedAt);
  }
}
