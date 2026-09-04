package com.example.dispute.workflow.runtime.temporal.room.hearing;

import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.Objects;

/**
 * Creates or strictly replays the durable Hearing start authority before the Hearing child is
 * started. The activity is the only target-lane path that may create the V035/V044 opening rows.
 */
@ActivityInterface
public interface TargetHearingBootstrapActivities {

  String ACTIVATION_PENDING = "TARGET_HEARING_ACTIVATION_PENDING";
  String ACTIVATION_INVALID = "TARGET_HEARING_ACTIVATION_INVALID";

  @ActivityMethod(name = "BootstrapTargetHearing")
  Binding bootstrap(ProvisionRoomEpoch provision);

  /**
   * Gates the child workflow until the bootstrap relay has atomically installed the real room run
   * id and advanced both the epoch and case projection to ACTIVE/READY.
   */
  @ActivityMethod(name = "AwaitTargetHearingActivation")
  void awaitActivation(ActivationRequest request);

  record Binding(
      String flowInstanceId,
      String epochId,
      long roomEpoch,
      long fencingToken,
      long processRevision,
      long roomRevision,
      String stageCode,
      int stageSequence,
      String initiatorParticipantId,
      String respondentParticipantId,
      long partyStageWindowSeconds) {
    public Binding {
      requireIdentifier(flowInstanceId, "flowInstanceId");
      requireIdentifier(epochId, "epochId");
      requireIdentifier(stageCode, "stageCode");
      requireIdentifier(initiatorParticipantId, "initiatorParticipantId");
      requireIdentifier(respondentParticipantId, "respondentParticipantId");
      if (roomEpoch < 0
          || fencingToken < 1
          || processRevision < 0
          || roomRevision < 0
          || (partyStageWindowSeconds != 0
              && (partyStageWindowSeconds < 1 || partyStageWindowSeconds > 1_200))
          || stageSequence != 1
          || !"COURT_PREPARING".equals(stageCode)
          || "production-runtime-initiator".equals(initiatorParticipantId)
          || "production-runtime-respondent".equals(respondentParticipantId)
          || initiatorParticipantId.equals(respondentParticipantId)) {
        throw new IllegalArgumentException("target Hearing bootstrap binding is invalid");
      }
    }

  }

  record ActivationRequest(
      String tenantSurrogate,
      String caseId,
      String flowInstanceId,
      String epochId,
      long roomEpoch,
      long fencingToken,
      long processRevision,
      long roomRevision,
      String roomWorkflowId,
      String roomWorkflowRunId,
      String roomWorkflowBuildId) {
    public ActivationRequest {
      requireIdentifier(tenantSurrogate, "tenantSurrogate");
      requireIdentifier(caseId, "caseId");
      requireIdentifier(flowInstanceId, "flowInstanceId");
      requireIdentifier(epochId, "epochId");
      requireIdentifier(roomWorkflowId, "roomWorkflowId");
      requireIdentifier(roomWorkflowRunId, "roomWorkflowRunId");
      requireIdentifier(roomWorkflowBuildId, "roomWorkflowBuildId");
      if (roomEpoch < 0
          || fencingToken < 1
          || processRevision < 0
          || roomRevision < 0) {
        throw new IllegalArgumentException("target Hearing activation route is invalid");
      }
    }
  }

  static ProvisionRoomEpoch requireHearing(ProvisionRoomEpoch provision) {
    provision = Objects.requireNonNull(provision, "provision");
    if (provision.roomType()
            != com.example.dispute.workflow.contract.v1.ContractTypes.RoomType.HEARING
        || provision.roomWorkflowBuildId() == null
        || provision.roomWorkflowBuildId().isBlank()) {
      throw new IllegalArgumentException("target Hearing bootstrap requires a v2 HEARING provision");
    }
    return provision;
  }

  private static void requireIdentifier(String value, String field) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}")) {
      throw new IllegalArgumentException(field + " must be a bounded identifier");
    }
  }
}
