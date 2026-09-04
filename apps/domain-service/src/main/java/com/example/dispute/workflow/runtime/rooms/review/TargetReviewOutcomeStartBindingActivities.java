package com.example.dispute.workflow.runtime.rooms.review;

import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.Objects;

/** CONTROL activity that reads, locks, and binds Java-owned Review start facts. */
@ActivityInterface
public interface TargetReviewOutcomeStartBindingActivities {
  @ActivityMethod(name = "BindTargetReviewOutcomeStart")
  Result bind(ProvisionRoomEpoch provision);

  record Result(TargetReviewOutcomeStartBindingPort.Binding binding) {
    public Result {
      binding = Objects.requireNonNull(binding, "binding");
    }
  }
}
