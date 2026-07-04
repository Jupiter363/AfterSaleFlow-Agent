package com.example.dispute.workflow.application;

import com.example.dispute.evidence.application.EvidenceCompletionService;
import com.example.dispute.workflow.temporal.EvidenceWindowActivities;
import org.springframework.stereotype.Component;

@Component
public class EvidenceWindowActivitiesAdapter implements EvidenceWindowActivities {

    private final EvidenceCompletionService completionService;

    public EvidenceWindowActivitiesAdapter(EvidenceCompletionService completionService) {
        this.completionService = completionService;
    }

    @Override
    public void warn(String caseId) {
        completionService.warnDeadline(caseId);
    }

    @Override
    public void expire(String caseId) {
        completionService.expire(caseId);
    }
}
