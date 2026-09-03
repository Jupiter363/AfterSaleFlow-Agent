package com.example.dispute.workflow.targete2e.rooms.review;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.Objects;

/** CONTROL activity that releases only an exactly admitted Review advisory request. */
@ActivityInterface
public interface TargetReviewCommandBridgeActivities {
  @ActivityMethod(name = "BindTargetReviewAgentRun")
  TargetReviewAgentRunTrigger bind(BindRequest request);

  record BindRequest(CaseCommandRef command, long roomFencingToken, long expectedRoomRevision) {
    public BindRequest {
      Objects.requireNonNull(command, "command");
      if (roomFencingToken < 1 || expectedRoomRevision < 0) {
        throw new IllegalArgumentException("invalid Review fence or revision");
      }
    }
  }
}
