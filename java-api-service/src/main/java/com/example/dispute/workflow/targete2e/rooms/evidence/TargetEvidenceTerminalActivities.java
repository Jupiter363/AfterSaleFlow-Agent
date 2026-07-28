package com.example.dispute.workflow.targete2e.rooms.evidence;

import com.example.dispute.workflow.temporal.caseprocess.TargetRoomProgressReceipt;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomStart;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.Objects;

/** Java-owned terminal transition after both fenced Evidence party completions. */
@ActivityInterface
public interface TargetEvidenceTerminalActivities {
  @ActivityMethod(name = "FinalizeTargetEvidenceTerminal")
  TerminalResult finalizeTerminal(TerminalRequest request);

  record TerminalRequest(
      EvidenceRoomStart start,
      long expectedProcessRevision,
      long expectedRoomRevision,
      String initiatorCompletionId,
      String respondentCompletionId) {
    public TerminalRequest {
      start = Objects.requireNonNull(start, "start");
      if (expectedProcessRevision < 0 || expectedRoomRevision < 0
          || initiatorCompletionId == null || initiatorCompletionId.isBlank()
          || respondentCompletionId == null || respondentCompletionId.isBlank()) {
        throw new IllegalArgumentException("target Evidence terminal request is invalid");
      }
    }
  }

  record TerminalResult(TargetRoomProgressReceipt progressReceipt) {
    public TerminalResult {
      progressReceipt = Objects.requireNonNull(progressReceipt, "progressReceipt");
    }
  }
}
