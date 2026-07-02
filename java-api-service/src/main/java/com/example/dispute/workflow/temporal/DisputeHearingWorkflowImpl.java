package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.EvidenceSubmissionSignal;
import com.example.dispute.workflow.domain.HearingStageActivityResult;
import com.example.dispute.workflow.domain.HearingWorkflowCommand;
import com.example.dispute.workflow.domain.HearingWorkflowResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Durable C1-C6 hearing controller.
 *
 * <p>The workflow only orders validated stages and waits on durable
 * Signal/Timer state. Model, network and persistence work is delegated to
 * Activities so replay remains deterministic.
 */
public class DisputeHearingWorkflowImpl
        implements DisputeHearingWorkflow {

    private final DisputeHearingActivities activities =
            Workflow.newActivityStub(
                    DisputeHearingActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofMinutes(2))
                            .setRetryOptions(
                                    RetryOptions.newBuilder()
                                            .setInitialInterval(Duration.ofSeconds(1))
                                            .setMaximumInterval(Duration.ofSeconds(10))
                                            .setMaximumAttempts(3)
                                            .build())
                            .build());

    private final Deque<EvidenceSubmissionSignal> evidenceSignals =
            new ArrayDeque<>();

    @Override
    public HearingWorkflowResult run(HearingWorkflowCommand command) {
        long dossierVersion = command.dossierVersion();
        int round = 0;
        boolean manualRequired = false;
        boolean evidenceTimedOut = false;
        String draftId = null;
        activities.initialize(command);
        try {
            while (true) {
                HearingStageActivityResult c1 =
                        runStage(
                                command,
                                "C1_ISSUE_FRAMING",
                                round,
                                dossierVersion,
                                evidenceTimedOut);
                manualRequired = manualRequired || c1.manualRequired();
                if (!c1.valid()) {
                    return interrupt(
                            command,
                            dossierVersion,
                            evidenceTimedOut,
                            "VALIDATION_INTERRUPTED");
                }
                HearingStageActivityResult c2 =
                        runStage(
                                command,
                                "C2_EVIDENCE_GAP",
                                round,
                                dossierVersion,
                                evidenceTimedOut);
                manualRequired = manualRequired || c2.manualRequired();
                if (!c2.valid()) {
                    return interrupt(
                            command,
                            dossierVersion,
                            evidenceTimedOut,
                            "VALIDATION_INTERRUPTED");
                }
                if (!c2.requiresAdditionalEvidence() || evidenceTimedOut) {
                    break;
                }
                if (round >= command.maxEvidenceRounds()) {
                    manualRequired = true;
                    break;
                }
                HearingStageActivityResult c3 =
                        runStage(
                                command,
                                "C3_EVIDENCE_REQUEST",
                                round,
                                dossierVersion,
                                false);
                manualRequired = manualRequired || c3.manualRequired();
                if (!c3.valid()) {
                    return interrupt(
                            command,
                            dossierVersion,
                            evidenceTimedOut,
                            "VALIDATION_INTERRUPTED");
                }
                boolean received =
                        Workflow.await(
                                command.evidenceWaitTimeout(),
                                () -> !evidenceSignals.isEmpty());
                if (!received) {
                    evidenceTimedOut = true;
                    manualRequired = true;
                    break;
                }
                while (!evidenceSignals.isEmpty()) {
                    long nextVersion =
                            activities.recordEvidence(
                                    evidenceSignals.removeFirst());
                    if (nextVersion > dossierVersion) {
                        dossierVersion = nextVersion;
                    }
                }
                round++;
            }

            for (String stage :
                    new String[] {
                        "C4_EVIDENCE_CROSS_CHECK",
                        "C5_RULE_APPLICATION",
                        "C6_DRAFT_GENERATION"
                    }) {
                HearingStageActivityResult result =
                        runStage(
                                command,
                                stage,
                                round,
                                dossierVersion,
                                evidenceTimedOut);
                manualRequired = manualRequired || result.manualRequired();
                if (!result.valid()) {
                    return interrupt(
                            command,
                            dossierVersion,
                            evidenceTimedOut,
                            "VALIDATION_INTERRUPTED");
                }
                if ("C6_DRAFT_GENERATION".equals(stage)) {
                    draftId = result.draftId();
                }
            }
        } catch (ActivityFailure failure) {
            return interrupt(
                    command,
                    dossierVersion,
                    evidenceTimedOut,
                    "ACTIVITY_INTERRUPTED");
        }
        String status = manualRequired ? "MANUAL_REVIEW_REQUIRED" : "COMPLETED";
        activities.complete(
                command.caseId(),
                command.workflowId(),
                status,
                manualRequired,
                evidenceTimedOut);
        return new HearingWorkflowResult(
                draftId,
                manualRequired,
                evidenceTimedOut,
                dossierVersion,
                status);
    }

    private HearingStageActivityResult runStage(
            HearingWorkflowCommand command,
            String stage,
            int round,
            long dossierVersion,
            boolean evidenceTimedOut) {
        HearingStageActivityResult result =
                activities.runStage(
                        command.caseId(),
                        command.workflowId(),
                        stage,
                        round,
                        dossierVersion,
                        evidenceTimedOut);
        activities.persistStageTrace(
                command.caseId(),
                command.workflowId(),
                stage,
                round,
                dossierVersion,
                result.outputVersion());
        return result;
    }

    private HearingWorkflowResult interrupt(
            HearingWorkflowCommand command,
            long dossierVersion,
            boolean evidenceTimedOut,
            String status) {
        activities.complete(
                command.caseId(),
                command.workflowId(),
                status,
                true,
                evidenceTimedOut);
        return new HearingWorkflowResult(
                null,
                true,
                evidenceTimedOut,
                dossierVersion,
                status);
    }

    @Override
    public void submitEvidence(EvidenceSubmissionSignal signal) {
        evidenceSignals.addLast(signal);
    }
}
