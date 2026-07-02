package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.HumanReviewCommand;
import com.example.dispute.workflow.domain.HumanReviewSignal;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface HumanReviewActivities {

    @ActivityMethod
    void recordInvalidDecision(
            HumanReviewCommand command,
            HumanReviewSignal signal,
            String reason);

    @ActivityMethod
    String persistDecision(
            HumanReviewCommand command,
            HumanReviewSignal signal,
            String status);
}
