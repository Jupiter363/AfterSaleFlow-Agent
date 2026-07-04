package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.EvidenceSubmissionSignal;
import com.example.dispute.workflow.domain.HearingStageActivityResult;
import com.example.dispute.workflow.domain.HearingWorkflowCommand;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface DisputeHearingActivities {

    @ActivityMethod
    void initialize(HearingWorkflowCommand command);

    @ActivityMethod
    HearingStageActivityResult runStage(
            String caseId,
            String workflowId,
            String stage,
            int round,
            long dossierVersion,
            boolean evidenceTimedOut,
            boolean finalConvergence,
            int maxHearingRounds);

    @ActivityMethod
    long recordEvidence(EvidenceSubmissionSignal signal);

    @ActivityMethod
    void persistStageTrace(
            String caseId,
            String workflowId,
            String stage,
            int round,
            long dossierVersion,
            String outputVersion);

    @ActivityMethod
    void complete(
            String caseId,
            String workflowId,
            String status,
            boolean manualRequired,
            boolean evidenceTimedOut,
            long dossierVersion,
            String stopReason);
}
