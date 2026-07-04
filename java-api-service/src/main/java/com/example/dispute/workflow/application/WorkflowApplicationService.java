package com.example.dispute.workflow.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.common.exception.BusinessException;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AppProperties;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.PartySubmissionEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.infrastructure.persistence.repository.PartySubmissionRepository;
import com.example.dispute.workflow.domain.EvidenceSubmissionSignal;
import com.example.dispute.workflow.domain.DeliberationInterventionMode;
import com.example.dispute.workflow.domain.FulfillmentDisputeCommand;
import com.example.dispute.workflow.domain.HumanReviewSignal;
import com.example.dispute.workflow.temporal.FulfillmentDisputeWorkflow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WorkflowApplicationService {

    private final WorkflowClient workflowClient;
    private final AppProperties properties;
    private final FulfillmentCaseRepository caseRepository;
    private final HearingStateRepository stateRepository;
    private final PartySubmissionRepository submissionRepository;
    private final EvidenceItemRepository evidenceRepository;
    private final AdjudicationDraftRepository draftRepository;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Duration evidenceWaitTimeout;
    private final int maxEvidenceRounds;
    private final DeliberationInterventionMode deliberationMode;
    private final String deliberationMinimumRiskLevel;
    private final int deliberationScoreThreshold;
    private final int deliberationMaxRegenerations;

    public WorkflowApplicationService(
            WorkflowClient workflowClient,
            AppProperties properties,
            FulfillmentCaseRepository caseRepository,
            HearingStateRepository stateRepository,
            PartySubmissionRepository submissionRepository,
            EvidenceItemRepository evidenceRepository,
            AdjudicationDraftRepository draftRepository,
            AuditRecorder auditRecorder,
            ObjectMapper objectMapper,
            TransactionTemplate transactions,
            @Value("${app.temporal.evidence-wait-hours:72}") long evidenceWaitHours,
            @Value("${app.temporal.max-evidence-rounds:2}") int maxEvidenceRounds,
            @Value("${app.temporal.deliberation-mode:FINAL_ONLY}") String deliberationMode,
            @Value("${app.temporal.deliberation-min-risk-level:HIGH}") String deliberationMinimumRiskLevel,
            @Value("${app.temporal.deliberation-score-threshold:80}") int deliberationScoreThreshold,
            @Value("${app.temporal.deliberation-max-regenerations:2}") int deliberationMaxRegenerations) {
        this.workflowClient = workflowClient;
        this.properties = properties;
        this.caseRepository = caseRepository;
        this.stateRepository = stateRepository;
        this.submissionRepository = submissionRepository;
        this.evidenceRepository = evidenceRepository;
        this.draftRepository = draftRepository;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
        this.evidenceWaitTimeout = Duration.ofHours(evidenceWaitHours);
        this.maxEvidenceRounds = maxEvidenceRounds;
        this.deliberationMode = DeliberationInterventionMode.from(deliberationMode);
        this.deliberationMinimumRiskLevel = deliberationMinimumRiskLevel;
        this.deliberationScoreThreshold = deliberationScoreThreshold;
        this.deliberationMaxRegenerations = deliberationMaxRegenerations;
    }

    public WorkflowStartView start(
            String caseId, AuthenticatedActor actor, String idempotencyKey) {
        FulfillmentCaseEntity disputeCase =
                transactions.execute(
                        ignored -> {
                            FulfillmentCaseEntity entity = authorizedCase(caseId, actor);
                            if (entity.getCaseStatus() != CaseStatus.ROUTED
                                    && entity.getCaseStatus() != CaseStatus.HEARING) {
                                throw new BusinessException(
                                        ErrorCode.CASE_STATUS_INVALID,
                                        "case must be routed before workflow start",
                                        Map.of(
                                                "case_status",
                                                entity.getCaseStatus().name()));
                            }
                            return entity;
                        });
        String workflowId = workflowId(caseId);
        FulfillmentDisputeWorkflow workflow =
                workflowClient.newWorkflowStub(
                        FulfillmentDisputeWorkflow.class,
                        WorkflowOptions.newBuilder()
                                .setWorkflowId(workflowId)
                                .setTaskQueue(properties.temporal().taskQueue())
                                .setWorkflowExecutionTimeout(Duration.ofDays(30))
                                .build());
        try {
            WorkflowClient.start(
                    workflow::run,
                    new FulfillmentDisputeCommand(
                            caseId,
                            workflowId,
                            disputeCase.getRouteType(),
                            1,
                            evidenceWaitTimeout,
                            Duration.ofDays(7),
                            maxEvidenceRounds,
                            disputeCase.getRiskLevel().name(),
                            deliberationMode,
                            deliberationMinimumRiskLevel,
                            deliberationScoreThreshold,
                            deliberationMaxRegenerations));
        } catch (WorkflowExecutionAlreadyStarted ignored) {
            // Deterministic workflow IDs make repeated starts idempotent.
        } catch (RuntimeException exception) {
            throw new BusinessException(
                    ErrorCode.WORKFLOW_START_FAILED,
                    "failed to start case workflow",
                    Map.of("case_id", caseId));
        }
        transactions.executeWithoutResult(
                ignored ->
                        auditRecorder.record(
                                actor,
                                "WORKFLOW_START_REQUESTED",
                                "CASE_WORKFLOW",
                                workflowId,
                                caseId,
                                Map.of(),
                                Map.of(
                                        "idempotency_key", idempotencyKey,
                                        "route_type",
                                                disputeCase.getRouteType().name())));
        return new WorkflowStartView(
                caseId,
                workflowId,
                "STARTED",
                disputeCase.getRouteType().name());
    }

    public HearingView getHearing(String caseId, AuthenticatedActor actor) {
        authorizedCaseReadOnly(caseId, actor);
        var state =
                stateRepository
                        .findByCaseId(caseId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.CASE_NOT_FOUND,
                                                "hearing not found",
                                                Map.of("case_id", caseId)));
        String draftId =
                draftRepository
                        .findFirstByCaseIdOrderByDraftVersionDesc(caseId)
                        .map(AdjudicationDraftEntity::getId)
                        .orElse(null);
        return new HearingView(
                state.getId(),
                state.getCaseId(),
                state.getWorkflowId(),
                state.getHearingStatus().name(),
                state.getCurrentNode(),
                state.getRoundNo(),
                state.getConfidence(),
                state.isManualRequired(),
                state.getPendingRequestsJson(),
                state.getWaitingUntil(),
                state.getCompletedAt(),
                draftId);
    }

    public AdjudicationDraftView getLatestDraft(
            String caseId, AuthenticatedActor actor) {
        authorizedCaseReadOnly(caseId, actor);
        AdjudicationDraftEntity draft =
                draftRepository
                        .findFirstByCaseIdOrderByDraftVersionDesc(caseId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.CASE_NOT_FOUND,
                                                "adjudication draft not found",
                                                Map.of("case_id", caseId)));
        return new AdjudicationDraftView(
                draft.getId(),
                draft.getCaseId(),
                draft.getDraftVersion(),
                draft.getRecommendedDecision(),
                draft.getConfidence(),
                draft.getDraftText(),
                readJson(draft.getFactFindingsJson()),
                readJson(draft.getEvidenceAssessmentJson()),
                readJson(draft.getPolicyApplicationJson()),
                readJson(draft.getReviewerAttentionJson()),
                draft.getDraftStatus());
    }

    public PartySubmissionView submitPartyEvidence(
            String caseId,
            String expectedParty,
            String text,
            List<String> evidenceIds,
            AuthenticatedActor actor,
            String idempotencyKey) {
        List<String> safeEvidenceIds =
                evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        String submissionId =
                "SUB_"
                        + UUID.nameUUIDFromBytes(
                                        (caseId
                                                        + ":"
                                                        + expectedParty
                                                        + ":"
                                                        + idempotencyKey)
                                                .getBytes(StandardCharsets.UTF_8))
                                .toString()
                                .replace("-", "");
        PartySubmissionEntity submission =
                transactions.execute(
                        ignored -> {
                            FulfillmentCaseEntity disputeCase =
                                    authorizedCase(caseId, actor);
                            assertParty(expectedParty, disputeCase, actor);
                            assertEvidenceBelongsToCase(caseId, safeEvidenceIds);
                            var existing = submissionRepository.findById(submissionId);
                            if (existing.isPresent()) {
                                if (!java.util.Objects.equals(
                                                existing.get().getSubmissionText(), text)
                                        || !existing
                                                .get()
                                                .getAttachmentIdsJson()
                                                .equals(writeJson(safeEvidenceIds))) {
                                    throw new com.example.dispute.common.exception
                                            .IdempotencyConflictException(
                                            "submission idempotency key was reused");
                                }
                                return existing.get();
                            }
                            PartySubmissionEntity saved =
                                    submissionRepository.save(
                                            PartySubmissionEntity.submit(
                                                    submissionId,
                                                    caseId,
                                                    null,
                                                    expectedParty,
                                                    actor.actorId(),
                                                    "SUPPLEMENTAL_EVIDENCE",
                                                    text,
                                                    writeJson(
                                                            Map.of(
                                                                    "idempotency_key",
                                                                    idempotencyKey)),
                                                    writeJson(safeEvidenceIds)));
                            auditRecorder.record(
                                    actor,
                                    "PARTY_EVIDENCE_SUBMITTED",
                                    "PARTY_SUBMISSION",
                                    saved.getId(),
                                    caseId,
                                    Map.of(),
                                    Map.of(
                                            "party_type", expectedParty,
                                            "evidence_ids", safeEvidenceIds));
                            return saved;
                        });
        String workflowId = workflowIdForCase(caseId);
        signal(
                workflowId,
                workflow ->
                        workflow.submitEvidence(
                                new EvidenceSubmissionSignal(
                                        submission.getId(),
                                        expectedParty,
                                        safeEvidenceIds)));
        return new PartySubmissionView(
                submission.getId(),
                caseId,
                expectedParty,
                safeEvidenceIds,
                workflowId,
                "SIGNALLED");
    }

    public void submitReviewerSignal(
            String caseId,
            String decision,
            String reason,
            AuthenticatedActor actor) {
        if (actor.role() != ActorRole.PLATFORM_REVIEWER
                && actor.role() != ActorRole.ADMIN
                && actor.role() != ActorRole.SYSTEM) {
            throw new ForbiddenException("reviewer role is required");
        }
        authorizedCaseReadOnly(caseId, actor);
        String workflowId = workflowIdForCase(caseId);
        transactions.executeWithoutResult(
                ignored ->
                        auditRecorder.record(
                                actor,
                                "REVIEWER_WORKFLOW_SIGNALLED",
                                "CASE_WORKFLOW",
                                workflowId,
                                caseId,
                                Map.of(),
                                Map.of("decision", decision, "reason", reason)));
        signal(
                workflowId,
                workflow ->
                        workflow.submitReviewDecision(
                                new HumanReviewSignal(
                                        actor.actorId(),
                                        "PLATFORM_REVIEWER",
                                        decision,
                                        1,
                                        "ACTION_HASH_PENDING",
                                        null,
                                        reason)));
    }

    private void signal(
            String workflowId,
            java.util.function.Consumer<FulfillmentDisputeWorkflow> action) {
        try {
            action.accept(
                    workflowClient.newWorkflowStub(
                            FulfillmentDisputeWorkflow.class, workflowId));
        } catch (RuntimeException exception) {
            throw new BusinessException(
                    ErrorCode.WORKFLOW_SIGNAL_FAILED,
                    "failed to signal case workflow",
                    Map.of("workflow_id", workflowId));
        }
    }

    private String workflowIdForCase(String caseId) {
        return stateRepository
                .findByCaseId(caseId)
                .map(state -> state.getWorkflowId())
                .orElse(workflowId(caseId));
    }

    private FulfillmentCaseEntity authorizedCase(
            String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity entity =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.CASE_NOT_FOUND,
                                                "case not found",
                                                Map.of("case_id", caseId)));
        assertCanAccess(entity, actor);
        return entity;
    }

    private void authorizedCaseReadOnly(String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity entity =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.CASE_NOT_FOUND,
                                                "case not found",
                                                Map.of("case_id", caseId)));
        assertCanAccess(entity, actor);
    }

    private static void assertCanAccess(
            FulfillmentCaseEntity entity, AuthenticatedActor actor) {
        boolean allowed =
                switch (actor.role()) {
                    case USER -> actor.actorId().equals(entity.getUserId());
                    case MERCHANT -> actor.actorId().equals(entity.getMerchantId());
                    case CUSTOMER_SERVICE, PLATFORM_REVIEWER, ADMIN, SYSTEM -> true;
                };
        if (!allowed) {
            throw new ForbiddenException("actor cannot access this case");
        }
    }

    private static void assertParty(
            String expectedParty,
            FulfillmentCaseEntity entity,
            AuthenticatedActor actor) {
        boolean valid =
                ("USER".equals(expectedParty)
                                && actor.role() == ActorRole.USER
                                && actor.actorId().equals(entity.getUserId()))
                        || ("MERCHANT".equals(expectedParty)
                                && actor.role() == ActorRole.MERCHANT
                                && actor.actorId().equals(entity.getMerchantId()));
        if (!valid) {
            throw new ForbiddenException(
                    "submission endpoint does not match authenticated party");
        }
    }

    private void assertEvidenceBelongsToCase(
            String caseId, List<String> evidenceIds) {
        if (evidenceIds.isEmpty()) {
            return;
        }
        long matches =
                evidenceRepository.findAllById(evidenceIds).stream()
                        .filter(item -> caseId.equals(item.getCaseId()))
                        .count();
        if (matches != evidenceIds.size()) {
            throw new BusinessException(
                    ErrorCode.EVIDENCE_NOT_FOUND,
                    "one or more evidence items do not belong to this case",
                    Map.of("case_id", caseId));
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize submission", exception);
        }
    }

    private com.fasterxml.jackson.databind.JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot parse persisted workflow json", exception);
        }
    }

    private static String workflowId(String caseId) {
        return "CASEWORKFLOW_" + caseId;
    }
}
