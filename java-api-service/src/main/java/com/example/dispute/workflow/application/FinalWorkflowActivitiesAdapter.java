package com.example.dispute.workflow.application;

import com.example.dispute.domain.model.RouteType;
import com.example.dispute.workflow.domain.ApprovalValidationResult;
import com.example.dispute.workflow.domain.CaseWorkflowInput;
import com.example.dispute.workflow.domain.CriticActivityResult;
import com.example.dispute.workflow.domain.DeliberationPanelCommand;
import com.example.dispute.workflow.domain.EvidenceSubmissionSignal;
import com.example.dispute.workflow.domain.ExecutionAction;
import com.example.dispute.workflow.domain.ExecutionActionActivityResult;
import com.example.dispute.workflow.domain.ExecutionCommand;
import com.example.dispute.workflow.domain.FrozenDeliberationSnapshot;
import com.example.dispute.workflow.domain.HearingAnalysisActivityCommand;
import com.example.dispute.workflow.domain.HearingAnalysisActivityResult;
import com.example.dispute.workflow.domain.HearingStageActivityResult;
import com.example.dispute.workflow.domain.HearingWorkflowCommand;
import com.example.dispute.workflow.domain.HumanReviewCommand;
import com.example.dispute.workflow.domain.HumanReviewSignal;
import com.example.dispute.workflow.domain.PartyEvidenceSignal;
import com.example.dispute.workflow.temporal.DeliberationPanelActivities;
import com.example.dispute.workflow.temporal.DisputeHearingActivities;
import com.example.dispute.workflow.temporal.ExecutionActivities;
import com.example.dispute.workflow.temporal.FulfillmentDisputeActivities;
import com.example.dispute.workflow.temporal.HumanReviewActivities;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * Transitional production adapter for the new workflow types.
 *
 * <p>It reuses the validated persistence, remedy, review, executor and closure
 * services while the old monolithic workflow type remains registered only for
 * already-running histories. New orchestration never invokes those services
 * directly from workflow code.
 */
@Component
public class FinalWorkflowActivitiesAdapter
        implements FulfillmentDisputeActivities,
                DisputeHearingActivities,
                DeliberationPanelActivities,
                HumanReviewActivities,
                ExecutionActivities {

    private final CaseFulfillmentDisputeActivitiesImpl legacy;
    private final ConcurrentMap<String, HearingAnalysisActivityResult>
            hearingRounds = new ConcurrentHashMap<>();

    public FinalWorkflowActivitiesAdapter(
            CaseFulfillmentDisputeActivitiesImpl legacy) {
        this.legacy = legacy;
    }

    @Override
    public void markTransferred(String caseId, String workflowId) {
        // Routing already persists TRANSFERRED before workflow start.
    }

    @Override
    public String planRemedy(
            String caseId,
            String workflowId,
            String draftId,
            String deliberationId) {
        return legacy.planRemedy(caseId, workflowId);
    }

    @Override
    public String createReviewPacket(
            String caseId,
            String draftId,
            String deliberationId,
            String remedyPlanId) {
        return legacy.createReviewTask(caseId, remedyPlanId);
    }

    @Override
    public void closeCaseAndEvaluate(String caseId) {
        legacy.closeCaseAndEvaluate(caseId);
    }

    @Override
    public void initialize(HearingWorkflowCommand command) {
        legacy.initializeHearing(
                new CaseWorkflowInput(
                        command.caseId(),
                        command.workflowId(),
                        RouteType.FULL_HEARING,
                        command.evidenceWaitTimeout(),
                        command.maxEvidenceRounds()));
    }

    @Override
    public HearingStageActivityResult runStage(
            String caseId,
            String workflowId,
            String stage,
            int round,
            long dossierVersion,
            boolean evidenceTimedOut) {
        String key = workflowId + ":" + round;
        HearingAnalysisActivityResult analysis =
                hearingRounds.computeIfAbsent(
                        key,
                        ignored ->
                                legacy.analyzeHearing(
                                        new HearingAnalysisActivityCommand(
                                                caseId,
                                                workflowId,
                                                round,
                                                evidenceTimedOut,
                                                round > 0)));
        return new HearingStageActivityResult(
                stage,
                true,
                "C2_EVIDENCE_GAP".equals(stage)
                        && analysis.requiresAdditionalEvidence(),
                analysis.manualRequired(),
                "C6_DRAFT_GENERATION".equals(stage)
                        ? analysis.draftId()
                        : null,
                "LEGACY_MIGRATION_" + round + "_" + stage);
    }

    @Override
    public long recordEvidence(EvidenceSubmissionSignal signal) {
        legacy.recordPartyEvidence(
                new PartyEvidenceSignal(
                        signal.partyRole(),
                        signal.submissionId(),
                        signal.evidenceRefs()));
        return 0;
    }

    @Override
    public void persistStageTrace(
            String caseId,
            String workflowId,
            String stage,
            int round,
            long dossierVersion,
            String outputVersion) {
        // Legacy analysis persistence already records every C1-C6 node.
    }

    @Override
    public void complete(
            String caseId,
            String workflowId,
            String status,
            boolean manualRequired,
            boolean evidenceTimedOut) {
        legacy.completeHearing(
                caseId,
                workflowId,
                manualRequired,
                evidenceTimedOut);
        hearingRounds.keySet().removeIf(key -> key.startsWith(workflowId + ":"));
    }

    @Override
    public FrozenDeliberationSnapshot freeze(
            DeliberationPanelCommand command) {
        String source =
                command.caseId()
                        + ":"
                        + command.draftId()
                        + ":"
                        + command.dossierVersion();
        String fingerprint =
                UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8))
                        .toString()
                        .replace("-", "");
        return new FrozenDeliberationSnapshot(
                command.caseId(),
                1,
                command.dossierVersion(),
                1,
                "MIGRATION_RULESET",
                1,
                fingerprint);
    }

    @Override
    public CriticActivityResult runCritic(
            FrozenDeliberationSnapshot snapshot,
            String critic) {
        // Safe migration default: unavailable criticism always forces review.
        return new CriticActivityResult(
                critic,
                "FAILED",
                "BLOCKER",
                List.of("CRITIC_ADAPTER_MIGRATION_PENDING"),
                snapshot.fingerprint());
    }

    @Override
    public String persistReport(
            DeliberationPanelCommand command,
            FrozenDeliberationSnapshot snapshot,
            List<CriticActivityResult> reports,
            String panelResult) {
        return "DELIBERATION_"
                + UUID.nameUUIDFromBytes(
                                (command.workflowId() + ":" + snapshot.fingerprint())
                                        .getBytes(StandardCharsets.UTF_8))
                        .toString()
                        .replace("-", "");
    }

    @Override
    public void recordInvalidDecision(
            HumanReviewCommand command,
            HumanReviewSignal signal,
            String reason) {
        // The reviewer API records its own rejected decision attempt.
    }

    @Override
    public String persistDecision(
            HumanReviewCommand command,
            HumanReviewSignal signal,
            String status) {
        // The reviewer API persists the authoritative decision before Signal.
        return "REVIEW_" + command.reviewPacketId();
    }

    @Override
    public ApprovalValidationResult validateApproval(
            ExecutionCommand command) {
        return new ApprovalValidationResult(
                command.approved(),
                command.approved() ? null : "APPROVAL_REQUIRED");
    }

    @Override
    public ExecutionActionActivityResult executeAction(
            String caseId,
            ExecutionAction action) {
        legacy.executeApprovedPlan(caseId);
        return new ExecutionActionActivityResult(
                action.actionId(), "SUCCEEDED", null);
    }

    @Override
    public ExecutionActionActivityResult lookupAction(
            String caseId,
            ExecutionAction action) {
        return new ExecutionActionActivityResult(
                action.actionId(), "UNKNOWN", null);
    }
}
