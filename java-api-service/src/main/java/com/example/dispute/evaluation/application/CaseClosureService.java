package com.example.dispute.evaluation.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.common.exception.CaseClosureException;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.ApprovalDecisionType;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.ExecutionStatus;
import com.example.dispute.infrastructure.persistence.entity.ActionRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.entity.ApprovalRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.EvaluationTraceEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.ActionRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.ApprovalRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.EvaluationTraceRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.notification.application.CaseLifecycleNotificationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CaseClosureService {

    private static final List<ApprovalDecisionType> EXECUTABLE_DECISIONS =
            List.of(
                    ApprovalDecisionType.APPROVE,
                    ApprovalDecisionType.MODIFY_AND_APPROVE);

    private final FulfillmentCaseRepository caseRepository;
    private final ApprovalRecordRepository approvalRepository;
    private final ActionRecordRepository actionRepository;
    private final EvaluationTraceRepository evaluationRepository;
    private final AdjudicationDraftRepository draftRepository;
    private final EvidenceItemRepository evidenceRepository;
    private final EvaluationAgentClient evaluationAgent;
    private final AuditRecorder auditRecorder;
    private final CaseLifecycleNotificationService lifecycleNotifications;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public CaseClosureService(
            FulfillmentCaseRepository caseRepository,
            ApprovalRecordRepository approvalRepository,
            ActionRecordRepository actionRepository,
            EvaluationTraceRepository evaluationRepository,
            AdjudicationDraftRepository draftRepository,
            EvidenceItemRepository evidenceRepository,
            EvaluationAgentClient evaluationAgent,
            AuditRecorder auditRecorder,
            CaseLifecycleNotificationService lifecycleNotifications,
            ObjectMapper objectMapper,
            TransactionTemplate transactions) {
        this.caseRepository = caseRepository;
        this.approvalRepository = approvalRepository;
        this.actionRepository = actionRepository;
        this.evaluationRepository = evaluationRepository;
        this.draftRepository = draftRepository;
        this.evidenceRepository = evidenceRepository;
        this.evaluationAgent = evaluationAgent;
        this.auditRecorder = auditRecorder;
        this.lifecycleNotifications = lifecycleNotifications;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ClosureView close(
            String caseId,
            String idempotencyKey,
            AuthenticatedActor actor,
            String traceId,
            String requestId) {
        assertCanClose(actor);
        requireText(idempotencyKey, "idempotencyKey");
        PendingClosure pending =
                transactions.execute(
                        ignored ->
                                prepareClosure(
                                        caseId,
                                        idempotencyKey,
                                        actor));
        if (pending.invokeEvaluation()) {
            try {
                EvaluationAgentResult result =
                        evaluationAgent.analyze(
                                pending.snapshot(), traceId, requestId);
                transactions.executeWithoutResult(
                        ignored ->
                                completeEvaluation(
                                        pending, result, actor));
            } catch (RuntimeException exception) {
                transactions.executeWithoutResult(
                        ignored ->
                                failEvaluation(
                                        pending, exception, actor));
                throw exception;
            }
        }
        return transactions.execute(
                ignored -> closureView(caseId));
    }

    @Transactional(readOnly = true)
    public EvaluationReportView evaluation(
            String caseId, AuthenticatedActor actor) {
        assertCanReadEvaluation(actor);
        FulfillmentCaseEntity disputeCase =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(() -> caseNotFound(caseId));
        if (disputeCase.getCaseStatus() != CaseStatus.CLOSED) {
            throw closureDenied(
                    "evaluation is only available for a closed case",
                    Map.of("case_status", disputeCase.getCaseStatus().name()));
        }
        return evaluationRepository
                .findFirstByCaseIdOrderByEvaluationVersionDesc(caseId)
                .map(this::reportView)
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        ErrorCode.CASE_NOT_FOUND,
                                        "evaluation trace not found",
                                        Map.of("case_id", caseId)));
    }

    @Transactional(readOnly = true)
    public EvaluationMetricsView metrics(AuthenticatedActor actor) {
        assertCanReadEvaluation(actor);
        List<EvaluationTraceEntity> all =
                evaluationRepository.findAllByOrderByCreatedAtDesc();
        List<EvaluationTraceEntity> completed =
                all.stream()
                        .filter(
                                trace ->
                                        "COMPLETED"
                                                .equals(
                                                        trace
                                                                .getEvaluationStatus()))
                        .toList();
        return new EvaluationMetricsView(
                all.size(),
                completed.size(),
                average(completed, "draft_approval_rate"),
                average(completed, "reviewer_modification_rate"));
    }

    private PendingClosure prepareClosure(
            String caseId,
            String idempotencyKey,
            AuthenticatedActor actor) {
        FulfillmentCaseEntity disputeCase =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(() -> caseNotFound(caseId));
        if (disputeCase.getCaseStatus() == CaseStatus.CLOSED) {
            EvaluationTraceEntity existing =
                    evaluationRepository
                            .findFirstByCaseIdOrderByEvaluationVersionDesc(
                                    caseId)
                            .orElseThrow(
                                    () ->
                                            closureDenied(
                                                    "closed case has no evaluation trace",
                                                    Map.of(
                                                            "case_id",
                                                            caseId)));
            if ("COMPLETED".equals(existing.getEvaluationStatus())
                    || "PENDING".equals(existing.getEvaluationStatus())) {
                return new PendingClosure(
                        caseId,
                        existing.getId(),
                        read(existing.getInputSnapshotJson()),
                        false);
            }
            JsonNode snapshot =
                    buildSnapshot(
                            disputeCase,
                            latestApproval(caseId),
                            actionRepository
                                    .findAllByCaseIdOrderByCreatedAtAsc(
                                            caseId));
            existing.retry(snapshot.toString(), actor.actorId());
            evaluationRepository.save(existing);
            return new PendingClosure(
                    caseId, existing.getId(), snapshot, true);
        }
        if (disputeCase.getCaseStatus() != CaseStatus.EXECUTING) {
            throw closureDenied(
                    "case is not ready for closure",
                    Map.of("case_status", disputeCase.getCaseStatus().name()));
        }
        ApprovalRecordEntity approval = latestApproval(caseId);
        List<ActionRecordEntity> actions =
                actionRepository.findAllByCaseIdOrderByCreatedAtAsc(caseId);
        validateCompletedExecution(approval, actions);
        disputeCase.close(actor.actorId());
        caseRepository.save(disputeCase);
        JsonNode snapshot = buildSnapshot(disputeCase, approval, actions);
        EvaluationTraceEntity trace =
                evaluationRepository.save(
                        EvaluationTraceEntity.pending(
                                "EVAL_" + compactUuid(),
                                caseId,
                                1,
                                snapshot.toString(),
                                actor.actorId()));
        auditRecorder.record(
                actor,
                "CASE_CLOSED",
                "FULFILLMENT_CASE",
                caseId,
                caseId,
                Map.of("case_status", CaseStatus.EXECUTING.name()),
                Map.of(
                        "case_status", CaseStatus.CLOSED.name(),
                        "closed_at", disputeCase.getClosedAt().toString(),
                        "idempotency_key", idempotencyKey));
        auditRecorder.record(
                actor,
                "EVALUATION_STARTED",
                "EVALUATION_TRACE",
                trace.getId(),
                caseId,
                Map.of(),
                Map.of(
                        "evaluation_status", "PENDING",
                        "evaluation_version", 1));
        lifecycleNotifications.executionCompleted(disputeCase);
        return new PendingClosure(caseId, trace.getId(), snapshot, true);
    }

    private ApprovalRecordEntity latestApproval(String caseId) {
        return approvalRepository
                .findFirstByCaseIdAndDecisionTypeInOrderByCreatedAtDesc(
                        caseId, EXECUTABLE_DECISIONS)
                .orElseThrow(
                        () ->
                                closureDenied(
                                        "approved execution record is required",
                                        Map.of("case_id", caseId)));
    }

    private void validateCompletedExecution(
            ApprovalRecordEntity approval,
            List<ActionRecordEntity> actions) {
        if (actions.isEmpty()) {
            throw closureDenied(
                    "at least one succeeded action is required",
                    Map.of("approval_record_id", approval.getId()));
        }
        if (actions.stream()
                .anyMatch(
                        action ->
                                action.getExecutionStatus()
                                        != ExecutionStatus.SUCCEEDED)) {
            throw closureDenied(
                    "all approved actions must have succeeded before closure",
                    Map.of("approval_record_id", approval.getId()));
        }
        if (actions.stream()
                .anyMatch(
                        action ->
                                !approval
                                                .getId()
                                                .equals(
                                                        action
                                                                .getApprovalRecordId())
                                        || !approval
                                                .getPlanId()
                                                .equals(
                                                        action.getPlanId()))) {
            throw closureDenied(
                    "action records do not belong to the approved plan",
                    Map.of("approval_record_id", approval.getId()));
        }
        Map<String, Integer> expected =
                expectedActionTypes(read(approval.getApprovedPlanJson()));
        Map<String, Integer> actual = new LinkedHashMap<>();
        actions.forEach(
                action ->
                        actual.merge(action.getActionType(), 1, Integer::sum));
        if (!expected.equals(actual)) {
            throw closureDenied(
                    "every approved action must have one succeeded record",
                    Map.of(
                            "expected_actions", expected,
                            "actual_actions", actual));
        }
    }

    private Map<String, Integer> expectedActionTypes(JsonNode approvedPlan) {
        Map<String, Integer> expected = new LinkedHashMap<>();
        JsonNode actionNodes = approvedPlan.path("actions");
        JsonNode notificationNodes = approvedPlan.path("notifications");
        if (!actionNodes.isArray() || !notificationNodes.isArray()) {
            throw closureDenied(
                    "approved plan snapshot is invalid", Map.of());
        }
        actionNodes.forEach(
                node ->
                        expected.merge(
                                requiredJsonText(node, "action_type"),
                                1,
                                Integer::sum));
        notificationNodes.forEach(
                node -> expected.merge(node.asText(), 1, Integer::sum));
        return expected;
    }

    private JsonNode buildSnapshot(
            FulfillmentCaseEntity disputeCase,
            ApprovalRecordEntity approval,
            List<ActionRecordEntity> actions) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("case_id", disputeCase.getId());
        snapshot.put("case_status", CaseStatus.CLOSED.name());
        snapshot.put(
                "route_type",
                disputeCase.getRouteType() == null
                        ? "UNROUTED"
                        : disputeCase.getRouteType().name());
        snapshot.put("risk_level", disputeCase.getRiskLevel().name());
        snapshot.put(
                "approval_decision", approval.getDecisionType().name());
        snapshot.set(
                "approved_plan", read(approval.getApprovedPlanJson()));
        snapshot.set(
                "adjudication_draft",
                draftRepository
                        .findFirstByCaseIdOrderByDraftVersionDesc(
                                disputeCase.getId())
                        .map(this::draftSnapshot)
                        .orElseGet(objectMapper::createObjectNode));
        ArrayNode actionNodes = snapshot.putArray("action_records");
        actions.forEach(
                action -> {
                    ObjectNode node = actionNodes.addObject();
                    node.put("action_record_id", action.getId());
                    node.put("action_type", action.getActionType());
                    node.put(
                            "execution_status",
                            action.getExecutionStatus().name());
                    node.put("attempt_count", action.getAttemptCount());
                    node.put("review_packet_id", action.getReviewPacketId());
                    node.put(
                            "action_snapshot_hash",
                            action.getActionSnapshotHash());
                    node.set(
                            "evidence_refs",
                            read(action.getEvidenceRefsJson()));
                    node.set("rule_refs", read(action.getRuleRefsJson()));
                    node.set(
                            "agent_run_refs",
                            read(action.getAgentRunRefsJson()));
                    node.put(
                            "external_result_ref",
                            action.getExternalResultRef());
                    node.set("result", read(action.getResultJson()));
                });
        var evidence =
                evidenceRepository
                        .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                                disputeCase.getId());
        ObjectNode evidenceSummary =
                snapshot.putObject("evidence_summary");
        evidenceSummary.put("evidence_count", evidence.size());
        evidenceSummary.put(
                "parsed_evidence_count",
                evidence.stream()
                        .filter(
                                item ->
                                        item.getParseStatus()
                                                == com.example.dispute
                                                        .domain.model
                                                        .ParseStatus.SUCCEEDED)
                        .count());
        ObjectNode policySummary = snapshot.putObject("policy_summary");
        JsonNode draftPolicy =
                snapshot.path("adjudication_draft")
                        .path("policy_application");
        policySummary.put(
                "applied_rule_count",
                draftPolicy.isArray() ? draftPolicy.size() : 0);
        return snapshot;
    }

    private ObjectNode draftSnapshot(AdjudicationDraftEntity draft) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("draft_id", draft.getId());
        node.put("draft_version", draft.getDraftVersion());
        node.put("draft_status", draft.getDraftStatus());
        node.put(
                "recommended_decision", draft.getRecommendedDecision());
        node.put("confidence", draft.getConfidence());
        node.set("fact_findings", read(draft.getFactFindingsJson()));
        node.set(
                "evidence_assessment",
                read(draft.getEvidenceAssessmentJson()));
        node.set(
                "policy_application",
                read(draft.getPolicyApplicationJson()));
        return node;
    }

    private void completeEvaluation(
            PendingClosure pending,
            EvaluationAgentResult result,
            AuthenticatedActor actor) {
        JsonNode report = result.report();
        if (report == null
                || !pending.caseId()
                        .equals(report.path("case_id").asText())
                || !"COMPLETED"
                        .equals(
                                report.path("evaluation_status").asText())
                || !report.path("metric_scores").isObject()
                || !report.path("findings").isArray()
                || report.path("automatic_changes_applied").asBoolean(true)
                || report.path("online_case_mutated").asBoolean(true)) {
            throw new IllegalStateException(
                    "evaluation agent returned an invalid or unsafe report");
        }
        EvaluationTraceEntity trace =
                evaluationRepository
                        .findByIdForUpdate(pending.evaluationTraceId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "evaluation trace disappeared"));
        trace.complete(
                result.evaluatorModel(),
                result.promptVersion(),
                report.path("metric_scores").toString(),
                report.path("findings").toString(),
                report.toString(),
                result.latencyMs(),
                result.tokenUsage(),
                actor.actorId());
        evaluationRepository.save(trace);
        auditRecorder.record(
                actor,
                "EVALUATION_COMPLETED",
                "EVALUATION_TRACE",
                trace.getId(),
                pending.caseId(),
                Map.of("evaluation_status", "PENDING"),
                Map.of(
                        "evaluation_status", "COMPLETED",
                        "evaluator_model", result.evaluatorModel()));
    }

    private void failEvaluation(
            PendingClosure pending,
            RuntimeException exception,
            AuthenticatedActor actor) {
        EvaluationTraceEntity trace =
                evaluationRepository
                        .findByIdForUpdate(pending.evaluationTraceId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "evaluation trace disappeared"));
        trace.fail(
                write(
                        Map.of(
                                "status",
                                "FAILED",
                                "error_type",
                                exception.getClass().getSimpleName())),
                actor.actorId());
        evaluationRepository.save(trace);
        auditRecorder.record(
                actor,
                "EVALUATION_FAILED",
                "EVALUATION_TRACE",
                trace.getId(),
                pending.caseId(),
                Map.of("evaluation_status", "PENDING"),
                Map.of("evaluation_status", "FAILED"));
    }

    private ClosureView closureView(String caseId) {
        FulfillmentCaseEntity disputeCase =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(() -> caseNotFound(caseId));
        EvaluationTraceEntity trace =
                evaluationRepository
                        .findFirstByCaseIdOrderByEvaluationVersionDesc(
                                caseId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "evaluation trace not found"));
        return new ClosureView(
                caseId,
                disputeCase.getCaseStatus(),
                disputeCase.getClosedAt(),
                trace.getId(),
                trace.getEvaluationStatus());
    }

    private EvaluationReportView reportView(EvaluationTraceEntity trace) {
        return new EvaluationReportView(
                trace.getId(),
                trace.getCaseId(),
                trace.getEvaluationVersion(),
                trace.getEvaluationStatus(),
                trace.getEvaluatorModel(),
                trace.getPromptVersion(),
                read(trace.getMetricScoresJson()),
                read(trace.getFindingsJson()),
                read(trace.getReportJson()),
                trace.getLatencyMs(),
                trace.getTokenUsage(),
                trace.getCompletedAt(),
                trace.getCreatedAt());
    }

    private double average(
            List<EvaluationTraceEntity> traces, String metric) {
        return traces.stream()
                .mapToDouble(
                        trace ->
                                read(trace.getMetricScoresJson())
                                        .path(metric)
                                        .asDouble(0))
                .average()
                .orElse(0);
    }

    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "invalid persisted evaluation JSON", exception);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "cannot serialize evaluation data", exception);
        }
    }

    private static String requiredJsonText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw closureDenied(
                    "approved action field is required",
                    Map.of("field", field));
        }
        return value;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void assertCanClose(AuthenticatedActor actor) {
        if (actor.role() != ActorRole.ADMIN
                && actor.role() != ActorRole.SYSTEM) {
            throw new ForbiddenException(
                    "only the workflow or an administrator can close a case");
        }
    }

    private static void assertCanReadEvaluation(AuthenticatedActor actor) {
        if (actor.role() != ActorRole.ADMIN
                && actor.role() != ActorRole.SYSTEM) {
            throw new ForbiddenException(
                    "only an administrator can read evaluation reports");
        }
    }

    private static CaseClosureException closureDenied(
            String message, Map<String, Object> details) {
        return new CaseClosureException(message, details);
    }

    private static NotFoundException caseNotFound(String caseId) {
        return new NotFoundException(
                ErrorCode.CASE_NOT_FOUND,
                "case not found",
                Map.of("case_id", caseId));
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record PendingClosure(
            String caseId,
            String evaluationTraceId,
            JsonNode snapshot,
            boolean invokeEvaluation) {}
}
