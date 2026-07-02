package com.example.dispute.workflow.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface FulfillmentDisputeActivities {

    @ActivityMethod
    void markTransferred(String caseId, String workflowId);

    @ActivityMethod
    String planRemedy(
            String caseId,
            String workflowId,
            String draftId,
            String deliberationId);

    @ActivityMethod
    String createReviewPacket(
            String caseId,
            String draftId,
            String deliberationId,
            String remedyPlanId);

    @ActivityMethod
    void closeCaseAndEvaluate(String caseId);
}
