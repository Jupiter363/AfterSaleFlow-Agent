package com.example.dispute.workflow.targete2e.temporal.room.hearing;

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

  @ActivityMethod(name = "BootstrapTargetHearing")
  Binding bootstrap(ProvisionRoomEpoch provision);

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
      String respondentParticipantId) {
    public Binding {
      required(flowInstanceId, "flowInstanceId");
      required(epochId, "epochId");
      required(stageCode, "stageCode");
      required(initiatorParticipantId, "initiatorParticipantId");
      required(respondentParticipantId, "respondentParticipantId");
      if (roomEpoch < 1
          || fencingToken < 1
          || processRevision < 0
          || roomRevision < 0
          || stageSequence != 1
          || !"COURT_PREPARING".equals(stageCode)
          || "target-e2e-initiator".equals(initiatorParticipantId)
          || "target-e2e-respondent".equals(respondentParticipantId)
          || initiatorParticipantId.equals(respondentParticipantId)) {
        throw new IllegalArgumentException("target Hearing bootstrap binding is invalid");
      }
    }

    private static void required(String value, String field) {
      if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}")) {
        throw new IllegalArgumentException(field + " must be a bounded identifier");
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
}
