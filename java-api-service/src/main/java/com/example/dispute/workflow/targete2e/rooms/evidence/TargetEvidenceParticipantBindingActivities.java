package com.example.dispute.workflow.targete2e.rooms.evidence;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.Objects;

/** Locks the target Evidence epoch and resolves its two real, active case participants. */
@ActivityInterface
public interface TargetEvidenceParticipantBindingActivities {
  @ActivityMethod(name = "BindTargetEvidenceParticipants")
  Binding bind(Request request);

  record Request(String tenantSurrogate, String caseId, long roomEpoch, long fencingToken) {
    public Request {
      required(tenantSurrogate, "tenantSurrogate");
      required(caseId, "caseId");
      if (roomEpoch < 0 || fencingToken < 1) {
        throw new IllegalArgumentException("target Evidence participant route is invalid");
      }
    }
  }

  record Binding(
      String tenantSurrogate,
      String caseId,
      long roomEpoch,
      long fencingToken,
      String initiatorParticipantId,
      String respondentParticipantId,
      String bindingHash) {
    public Binding {
      required(tenantSurrogate, "tenantSurrogate");
      required(caseId, "caseId");
      required(initiatorParticipantId, "initiatorParticipantId");
      required(respondentParticipantId, "respondentParticipantId");
      if (roomEpoch < 0
          || fencingToken < 1
          || initiatorParticipantId.equals(respondentParticipantId)
          || bindingHash == null
          || !bindingHash.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("target Evidence participant binding is invalid");
      }
    }
  }

  private static void required(String value, String field) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}")) {
      throw new IllegalArgumentException(field + " must be a bounded identifier");
    }
  }
}
