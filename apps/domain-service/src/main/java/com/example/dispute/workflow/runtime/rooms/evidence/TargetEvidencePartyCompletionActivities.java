package com.example.dispute.workflow.runtime.rooms.evidence;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.temporal.caseprocess.TargetRoomProgressReceipt;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomStart;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.Objects;

/** Java-owned durable completion of one bound Evidence party command. */
@ActivityInterface
public interface TargetEvidencePartyCompletionActivities {
  @ActivityMethod(name = "FinalizeTargetEvidencePartyCompletion")
  Result finalizeCompletion(Request request);

  record Request(
      EvidenceRoomStart start,
      TargetEvidenceParticipantBindingActivities.Binding participants,
      CaseCommandRef command,
      long expectedProcessRevision,
      long expectedRoomRevision) {
    public Request {
      start = Objects.requireNonNull(start, "start");
      participants = Objects.requireNonNull(participants, "participants");
      command = Objects.requireNonNull(command, "command");
      if (expectedProcessRevision < 0 || expectedRoomRevision < 0) {
        throw new IllegalArgumentException("target Evidence party completion coordinates are invalid");
      }
    }
  }

  record Result(String completionId, TargetRoomProgressReceipt progressReceipt) {
    public Result {
      if (completionId == null || completionId.isBlank()) {
        throw new IllegalArgumentException("target Evidence completion id is required");
      }
      progressReceipt = Objects.requireNonNull(progressReceipt, "progressReceipt");
    }
  }
}
