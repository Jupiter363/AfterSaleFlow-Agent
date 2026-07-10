package com.example.dispute.workflow.application;

import com.example.dispute.domain.model.RouteType;
import com.example.dispute.hearing.application.HearingOutcomeOrchestrationService;
import com.example.dispute.hearing.application.HearingRoundService;
import com.example.dispute.hearing.domain.SettlementStatus;
import com.example.dispute.hearing.infrastructure.persistence.repository.SettlementProposalRepository;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.room.domain.PhaseClockType;
import com.example.dispute.room.infrastructure.persistence.repository.CasePhaseClockRepository;
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
import com.example.dispute.workflow.domain.ReviewGateSnapshot;
import com.example.dispute.workflow.temporal.DeliberationPanelActivities;
import com.example.dispute.workflow.temporal.DisputeHearingActivities;
import com.example.dispute.workflow.temporal.ExecutionActivities;
import com.example.dispute.workflow.temporal.FulfillmentDisputeActivities;
import com.example.dispute.workflow.temporal.HumanReviewActivities;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import com.example.dispute.infrastructure.persistence.entity.DeliberationReportEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.ApprovalRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.DeliberationReportRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewPacketRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewTaskRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ApprovalRecordRepository approvalRepository;
    private final ReviewPacketRepository packetRepository;
    private final ReviewTaskRepository taskRepository;
    private final DeliberationReportRepository deliberationRepository;
    private final AdjudicationDraftRepository draftRepository;
    private final HearingStateRepository hearingStateRepository;
    private final SettlementProposalRepository settlementProposalRepository;
    private final ObjectMapper objectMapper;
    private final HearingRoundService hearingRoundService;
    private final HearingOutcomeOrchestrationService hearingOutcomeOrchestration;
    private final CasePhaseClockRepository phaseClockRepository;
    private final Clock clock;
    private final ConcurrentMap<String, HearingAnalysisActivityResult>
            hearingRounds = new ConcurrentHashMap<>();

    public FinalWorkflowActivitiesAdapter(
            CaseFulfillmentDisputeActivitiesImpl legacy,
            ApprovalRecordRepository approvalRepository,
            ReviewPacketRepository packetRepository,
            ReviewTaskRepository taskRepository,
            DeliberationReportRepository deliberationRepository,
            AdjudicationDraftRepository draftRepository,
            HearingStateRepository hearingStateRepository,
            SettlementProposalRepository settlementProposalRepository,
            ObjectMapper objectMapper,
            HearingRoundService hearingRoundService,
            HearingOutcomeOrchestrationService hearingOutcomeOrchestration,
            CasePhaseClockRepository phaseClockRepository,
            Clock clock) {
        this.legacy = legacy;
        this.approvalRepository = approvalRepository;
        this.packetRepository = packetRepository;
        this.taskRepository = taskRepository;
        this.deliberationRepository = deliberationRepository;
        this.draftRepository = draftRepository;
        this.hearingStateRepository = hearingStateRepository;
        this.settlementProposalRepository = settlementProposalRepository;
        this.objectMapper = objectMapper;
        this.hearingRoundService = hearingRoundService;
        this.hearingOutcomeOrchestration = hearingOutcomeOrchestration;
        this.phaseClockRepository = phaseClockRepository;
        this.clock = clock;
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
    public ReviewGateSnapshot createReviewPacket(
            String caseId,
            String draftId,
            String deliberationId,
            String remedyPlanId) {
        String taskId = legacy.createReviewTask(caseId, remedyPlanId);
        var task =
                taskRepository
                        .findById(taskId)
                        .orElseThrow(() -> new IllegalStateException("review task not found"));
        var packet =
                packetRepository
                        .findById(task.getPacketId())
                        .orElseThrow(() -> new IllegalStateException("review packet not found"));
        return new ReviewGateSnapshot(
                taskId,
                packet.getId(),
                packet.getPacketVersion(),
                packet.getActionHash(),
                packet.getExpiresAt().toInstant().toEpochMilli(),
                task.getRequiredRole());
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
            boolean evidenceTimedOut,
            boolean finalConvergence,
            int maxHearingRounds) {
        if (finalConvergence && !"C6_DRAFT_GENERATION".equals(stage)) {
            return new HearingStageActivityResult(
                    stage,
                    true,
                    false,
                    false,
                    null,
                    "FINAL_CONVERGENCE_DEFERRED_TO_C6_" + stage);
        }
        String key = workflowId + ":" + round + ":" + finalConvergence;
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
                                                round > 0,
                                                finalConvergence,
                                                maxHearingRounds)));
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
            boolean evidenceTimedOut,
            long dossierVersion,
            String stopReason) {
        if ("DEADLINE_EXPIRED".equals(stopReason)) {
            hearingRoundService.expire(
                    caseId, Math.toIntExact(dossierVersion), "temporal-worker");
        }
        phaseClockRepository
                .findByCaseIdAndClockType(caseId, PhaseClockType.HEARING)
                .ifPresent(
                        phaseClock -> {
                            if ("DEADLINE_EXPIRED".equals(stopReason)) {
                                phaseClock.expire(
                                        OffsetDateTime.now(clock),
                                        "temporal-worker");
                            } else if (stopReason != null) {
                                phaseClock.complete(
                                        OffsetDateTime.now(clock),
                                        stopReason,
                                        "temporal-worker");
                            }
                            phaseClockRepository.save(phaseClock);
                        });
        legacy.completeHearing(
                caseId,
                workflowId,
                manualRequired,
                evidenceTimedOut);
        if (shouldOpenHumanReviewGate(status)) {
            if ("SETTLEMENT_CONFIRMED".equals(stopReason)) {
                ensureSettlementDraft(caseId);
            }
            hearingOutcomeOrchestration.orchestrate(caseId, "temporal-worker");
        }
        hearingRounds.keySet().removeIf(key -> key.startsWith(workflowId + ":"));
    }

    private void ensureSettlementDraft(String caseId) {
        var latestDraft = draftRepository.findFirstByCaseIdOrderByDraftVersionDesc(caseId);
        if (latestDraft
                .map(AdjudicationDraftEntity::getDraftStatus)
                .filter("SETTLEMENT_CONFIRMED"::equals)
                .isPresent()) {
            return;
        }
        var settlement =
                settlementProposalRepository
                        .findTopByCaseIdOrderByProposalVersionDesc(caseId)
                        .filter(
                                proposal ->
                                        proposal.getProposalStatus()
                                                == SettlementStatus.CONFIRMED)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "confirmed settlement is required"));
        var hearingState =
                hearingStateRepository
                        .findByCaseId(caseId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "hearing state not found"));
        int nextVersion =
                latestDraft.map(draft -> draft.getDraftVersion() + 1).orElse(1);
        String proposalText = settlement.getProposalText();
        draftRepository.save(
                AdjudicationDraftEntity.create(
                        "DRAFT_" + UUID.randomUUID().toString().replace("-", ""),
                        caseId,
                        hearingState.getId(),
                        nextVersion,
                        write(List.of("双方已确认一致方案", proposalText)),
                        write(List.of("一致方案由用户与商家共同确认，作为非最终裁决草案来源。")),
                        write(List.of("平台审核员需核验一致方案合法性、可执行性和履约风险。")),
                        write(List.of("核对一致方案对应的订单、库存、物流和售后执行条件")),
                        recommendationFromSettlement(proposalText),
                        new BigDecimal("0.9500"),
                        "双方已达成一致方案：" + proposalText,
                        "deterministic-settlement-draft",
                        null,
                        "SETTLEMENT_CONFIRMED",
                        "temporal-worker"));
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
        String id =
                "DELIBERATION_"
                        + UUID.nameUUIDFromBytes(
                                        (command.workflowId()
                                                        + ":"
                                                        + snapshot.fingerprint())
                                                .getBytes(StandardCharsets.UTF_8))
                                .toString()
                                .replace("-", "");
        if (deliberationRepository.existsById(id)) {
            return id;
        }
        int version =
                deliberationRepository
                                .findFirstByCaseIdOrderByReportVersionDesc(
                                        command.caseId())
                                .map(report -> report.getReportVersion() + 1)
                                .orElse(1);
        List<String> majorRisks =
                reports.stream()
                        .filter(
                                report ->
                                        "HIGH".equals(report.severity())
                                                || "BLOCKER".equals(
                                                        report.severity()))
                        .flatMap(report -> report.blockingIssues().stream())
                        .distinct()
                        .toList();
        deliberationRepository.save(
                DeliberationReportEntity.record(
                        id,
                        command.caseId(),
                        version,
                        command.draftId(),
                        Math.toIntExact(snapshot.dossierVersion()),
                        write(
                                java.util.Map.of(
                                        "panel_result",
                                        panelResult,
                                        "trigger_reasons",
                                        command.triggerReasons(),
                                        "frozen_input_fingerprint",
                                        snapshot.fingerprint(),
                                        "critic_reports",
                                        reports)),
                        write(majorRisks),
                        "[]",
                        write(reports),
                        "[]",
                        "TRACE_" + command.workflowId(),
                        "temporal-worker"));
        return id;
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
        return signal.humanReviewRecordId() == null
                ? "REVIEW_" + command.reviewPacketId()
                : signal.humanReviewRecordId();
    }

    @Override
    public ApprovalValidationResult validateApproval(
            ExecutionCommand command) {
        if (!command.approved()) {
            return new ApprovalValidationResult(false, "APPROVAL_REQUIRED");
        }
        var approval = approvalRepository.findById(command.reviewId());
        if (approval.isEmpty()) {
            return new ApprovalValidationResult(false, "HUMAN_REVIEW_RECORD_NOT_FOUND");
        }
        var record = approval.get();
        if (!record.getActionSnapshotHash().equals(command.actionHash())) {
            return new ApprovalValidationResult(false, "ACTION_HASH_MISMATCH");
        }
        if (record.getReviewPacketVersion() != command.reviewPacketVersion()) {
            return new ApprovalValidationResult(false, "STALE_REVIEW_PACKET");
        }
        if (!"PLATFORM_REVIEWER".equals(record.getReviewerRole())) {
            return new ApprovalValidationResult(false, "UNAUTHORIZED_REVIEWER_ROLE");
        }
        return new ApprovalValidationResult(true, null);
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

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "cannot serialize deliberation report", exception);
        }
    }

    private static String recommendationFromSettlement(String proposalText) {
        String upper = proposalText.toUpperCase(java.util.Locale.ROOT);
        if (upper.contains("REFUND")
                || proposalText.contains("退款")
                || proposalText.contains("退费")
                || proposalText.contains("返款")) {
            return "REFUND_BY_CONFIRMED_SETTLEMENT";
        }
        if (upper.contains("RESHIP")
                || upper.contains("RESEND")
                || proposalText.contains("补发")
                || proposalText.contains("重发")
                || proposalText.contains("重新发")
                || proposalText.contains("再次发")) {
            return "RESHIP_BY_CONFIRMED_SETTLEMENT";
        }
        if (upper.contains("REPLACE")
                || upper.contains("EXCHANGE")
                || proposalText.contains("换货")
                || proposalText.contains("更换")
                || proposalText.contains("调换")) {
            return "REPLACE_BY_CONFIRMED_SETTLEMENT";
        }
        return "SETTLEMENT_CONFIRMED";
    }

    private static boolean shouldOpenHumanReviewGate(String status) {
        return !"ACTIVITY_INTERRUPTED".equals(status)
                && !"VALIDATION_INTERRUPTED".equals(status);
    }
}
