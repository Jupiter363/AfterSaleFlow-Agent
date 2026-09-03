package com.example.dispute.workflow.targete2e.temporal.intake;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeWorkflowCommand;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.Objects;

/** Resolves the persisted target Intake material before it crosses the CONTROL-to-room boundary. */
@ActivityInterface
public interface TargetIntakeCommandBridgeActivities {

  @ActivityMethod(name = "BindTargetIntakeWorkflowCommand")
  IntakeWorkflowCommand bindCommand(BindRequest request);

  record BindRequest(CaseCommandRef command, long roomFencingToken, long expectedRoomRevision) {
    public BindRequest {
      Objects.requireNonNull(command, "command must not be null");
      if (roomFencingToken < 1 || expectedRoomRevision < 0) {
        throw new IllegalArgumentException("target Intake bridge fence or room revision is invalid");
      }
    }
  }
}
