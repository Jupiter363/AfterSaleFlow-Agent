package com.example.dispute.workflow.runtime.rooms.evidence;

import com.example.dispute.workflow.temporal.caseprocess.TargetRoomProgressReceipt;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceRoomStart;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.Objects;

/** Java-owned terminal transition after both fenced Evidence party completions. */
@ActivityInterface
public interface TargetEvidenceTerminalActivities {
  @ActivityMethod(name = "FinalizeTargetEvidenceTerminal")
  TerminalResult finalizeTerminal(TerminalRequest request);

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record TerminalRequest(
      EvidenceRoomStart start,
      long expectedProcessRevision,
      long expectedRoomRevision,
      String initiatorCompletionId,
      String respondentCompletionId,
      String workflowId,
      String workflowRunId,
      String durableWorkflowRunId) {

    /** Keeps the exact v1 Activity payload shape used by histories recorded before P0 authority. */
    public TerminalRequest(
        EvidenceRoomStart start,
        long expectedProcessRevision,
        long expectedRoomRevision,
        String initiatorCompletionId,
        String respondentCompletionId) {
      this(
          start,
          expectedProcessRevision,
          expectedRoomRevision,
          initiatorCompletionId,
          respondentCompletionId,
          null,
          null,
          null);
    }

    /** Keeps the exact single-run identity shape recorded before reset-aware authority. */
    public TerminalRequest(
        EvidenceRoomStart start,
        long expectedProcessRevision,
        long expectedRoomRevision,
        String initiatorCompletionId,
        String respondentCompletionId,
        String workflowId,
        String workflowRunId) {
      this(
          start,
          expectedProcessRevision,
          expectedRoomRevision,
          initiatorCompletionId,
          respondentCompletionId,
          workflowId,
          workflowRunId,
          null);
    }

    public TerminalRequest {
      start = Objects.requireNonNull(start, "start");
      if (expectedProcessRevision < 0 || expectedRoomRevision < 0
          || initiatorCompletionId == null || initiatorCompletionId.isBlank()
          || respondentCompletionId == null || respondentCompletionId.isBlank()
          || initiatorCompletionId.equals(respondentCompletionId)) {
        throw new IllegalArgumentException("target Evidence terminal request is invalid");
      }
      if ((workflowId == null) != (workflowRunId == null)) {
        throw new IllegalArgumentException(
            "target Evidence workflow identity must be bound together");
      }
      if (workflowId != null && (workflowId.isBlank() || workflowRunId.isBlank())) {
        throw new IllegalArgumentException("target Evidence workflow identity is invalid");
      }
      if (durableWorkflowRunId != null
          && (workflowId == null || durableWorkflowRunId.isBlank())) {
        throw new IllegalArgumentException(
            "target Evidence durable workflow authority is invalid");
      }
    }

    public boolean carriesWorkflowIdentity() {
      return workflowId != null;
    }

    public boolean carriesDurableWorkflowAuthority() {
      return durableWorkflowRunId != null;
    }
  }

  record TerminalResult(TargetRoomProgressReceipt progressReceipt) {
    public TerminalResult {
      progressReceipt = Objects.requireNonNull(progressReceipt, "progressReceipt");
    }
  }
}
