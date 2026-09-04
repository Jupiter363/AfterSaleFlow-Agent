package com.example.dispute.workflow.runtime.rooms.evidence;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.Objects;

/** CONTROL-side bridge which turns durable Evidence material into a typed room trigger. */
@ActivityInterface
public interface TargetEvidenceCommandBridgeActivities {
  @ActivityMethod(name = "BindTargetEvidenceAgentRun")
  TargetEvidenceAgentRunTrigger bindEvidenceAgentRun(BindRequest request);

  record BindRequest(CaseCommandRef command, long roomFencingToken, long expectedRoomRevision) {
    public BindRequest {
      Objects.requireNonNull(command, "command");
      if (roomFencingToken < 1 || expectedRoomRevision < 0) {
        throw new IllegalArgumentException("target Evidence bridge fence or revision is invalid");
      }
    }
  }
}
