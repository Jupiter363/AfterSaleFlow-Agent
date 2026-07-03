package com.example.dispute.workflow.temporal;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface EvidenceWindowActivities {
    void expire(String caseId);
}
