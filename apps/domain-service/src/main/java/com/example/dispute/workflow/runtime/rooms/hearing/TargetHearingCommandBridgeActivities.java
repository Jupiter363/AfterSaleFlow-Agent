package com.example.dispute.workflow.runtime.rooms.hearing;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.Objects;

/** CONTROL activity contract for resolving an admitted Hearing command before starting its graph. */
@ActivityInterface
public interface TargetHearingCommandBridgeActivities {
  @ActivityMethod(name = "BindTargetHearingAgentRun")
  TargetHearingAgentRunTrigger bind(BindRequest request);

  record BindRequest(CaseCommandRef command, long roomFencingToken, long expectedRoomRevision) {
    public BindRequest {
      Objects.requireNonNull(command, "command");
      if (roomFencingToken < 1 || expectedRoomRevision < 0) {
        throw new IllegalArgumentException("invalid Hearing fence or revision");
      }
    }
  }
}
