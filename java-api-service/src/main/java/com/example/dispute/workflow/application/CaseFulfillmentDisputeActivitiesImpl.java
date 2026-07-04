package com.example.dispute.workflow.application;

import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.ParseStatus;
import com.example.dispute.executor.application.ToolExecutorService;
import com.example.dispute.evaluation.application.CaseClosureService;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.HearingRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.HearingStateEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.infrastructure.persistence.repository.PartySubmissionRepository;
import com.example.dispute.infrastructure.persistence.repository.PolicyRuleRepository;
import com.example.dispute.notification.application.CaseLifecycleNotificationService;
import com.example.dispute.workflow.domain.CaseWorkflowInput;
import com.example.dispute.workflow.domain.HearingAnalysisActivityCommand;
import com.example.dispute.workflow.domain.HearingAnalysisActivityResult;
import com.example.dispute.workflow.domain.PartyEvidenceSignal;
import com.example.dispute.workflow.domain.ReviewerWorkflowSignal;
import com.example.dispute.workflow.temporal.CaseFulfillmentDisputeActivities;
import com.example.dispute.remedy.application.RemedyApplicationService;
import com.example.dispute.review.application.ReviewApplicationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class CaseFulfillmentDisputeActivitiesImpl
        implements CaseFulfillmentDisputeActivities {

    private static final AuthenticatedActor SYSTEM =
            new AuthenticatedActor("temporal-worker", ActorRole.SYSTEM);

    private final FulfillmentCaseRepository caseRepository;
    private final EvidenceItemRepository evidenceRepository;
    private final PolicyRuleRepository policyRepository;
    private final HearingStateRepository stateRepository;
    private final HearingRecordRepository recordRepository;
    private final AdjudicationDraftRepository draftRepository;
    private final AgentRunRepository agentRunRepository;
    private final PartySubmissionRepository submissionRepository;
    private final HearingAgentClient agentClient;
    private final RemedyApplicationService remedyService;
    private final ReviewApplicationService reviewService;
    private final CaseLifecycleNotificationService lifecycleNotifications;
    private final ToolExecutorService toolExecutorService;
    private final CaseClosureService closureService;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public CaseFulfillmentDisputeActivitiesImpl(
            FulfillmentCaseRepository caseRepository,
            EvidenceItemRepository evidenceRepository,
            PolicyRuleRepository policyRepository,
            HearingStateRepository stateRepository,
            HearingRecordRepository recordRepository,
            AdjudicationDraftRepository draftRepository,
            AgentRunRepository agentRunRepository,
            PartySubmissionRepository submissionRepository,
            HearingAgentClient agentClient,
            RemedyApplicationService remedyService,
            ReviewApplicationService reviewService,
            CaseLifecycleNotificationService lifecycleNotifications,
            ToolExecutorService toolExecutorService,
            CaseClosureService closureService,
            AuditRecorder auditRecorder,
            ObjectMapper objectMapper,
            TransactionTemplate transactions) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.policyRepository = policyRepository;
        this.stateRepository = stateRepository;
        this.recordRepository = recordRepository;
        this.draftRepository = draftRepository;
        this.agentRunRepository = agentRunRepository;
        this.submissionRepository = submissionRepository;
        this.agentClient = agentClient;
        this.remedyService = remedyService;
        this.reviewService = reviewService;
        this.lifecycleNotifications = lifecycleNotifications;
        this.toolExecutorService = toolExecutorService;
        this.closureService = closureService;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
    }

    @Override
    public void initializeHearing(CaseWorkflowInput input) {
        transactions.executeWithoutResult(
                ignored -> {
                    FulfillmentCaseEntity disputeCase =
                            caseRepository
                                    .findByIdForUpdate(input.caseId())
                                    .orElseThrow(
                                            () ->
                                                    new IllegalStateException(
                                                            "case not found: "
                                                                    + input.caseId()));
                    if (stateRepository.findByWorkflowId(input.workflowId()).isPresent()) {
                        return;
                    }
                    if (disputeCase.getCaseStatus() == CaseStatus.ROUTED) {
                        disputeCase.startHearing(input.workflowId(), SYSTEM.actorId());
                        caseRepository.save(disputeCase);
                    } else if (disputeCase.getCaseStatus() == CaseStatus.HEARING_OPEN) {
                        disputeCase.attachHearingWorkflow(
                                input.workflowId(), SYSTEM.actorId());
                        caseRepository.save(disputeCase);
                    } else if (!input.workflowId().equals(
                            disputeCase.getCurrentWorkflowId())) {
                        throw new IllegalStateException(
                                "case is already controlled by another workflow");
                    }
                    HearingStateEntity state =
                            stateRepository.save(
                                    HearingStateEntity.start(
                                            "HEARING_" + compactUuid(),
                                            input.caseId(),
                                            input.workflowId(),
                                            SYSTEM.actorId()));
                    auditRecorder.record(
                            SYSTEM,
                            "HEARING_STARTED",
                            "HEARING_STATE",
                            state.getId(),
                            input.caseId(),
                            Map.of("case_status", "ROUTED"),
                            Map.of(
                                    "case_status", "HEARING",
                                    "workflow_id", input.workflowId()));
                });
    }

    @Override
    public HearingAnalysisActivityResult analyzeHearing(
            HearingAnalysisActivityCommand command) {
        JsonNode request =
                transactions.execute(
                        ignored -> buildAgentRequest(command));
        long started = System.nanoTime();
        OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        String traceId = "TRACE_" + command.workflowId();
        HearingAgentResult result;
        try {
            result =
                    agentClient.analyze(
                            request,
                            traceId,
                            "REQ_" + command.workflowId() + "_" + command.roundNo());
        } catch (RuntimeException failure) {
            result = fallbackManualReviewResult(command, failure);
        }
        boolean requiresAdditionalEvidence =
                result.requiresAdditionalEvidence()
                        && command.allowSupplementalRequest();
        final HearingAgentResult analysisResult = result;
        final boolean effectiveRequiresAdditionalEvidence =
                requiresAdditionalEvidence;
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        String agentRunId = "RUN_" + compactUuid();
        String draftId =
                transactions.execute(
                        ignored -> {
                            AgentRunEntity run =
                                    agentRunRepository.saveAndFlush(
                                            AgentRunEntity.completed(
                                                    agentRunId,
                                                    command.caseId(),
                                                    command.workflowId(),
                                                    "presiding-judge",
                                                    "PRESIDING_JUDGE",
                                                    "presiding-judge-v1",
                                                    analysisResult.promptVersion(),
                                                    "dispute-default-v1",
                                                    "ruleset-current",
                                                    analysisResult.model(),
                                                    inputRefs(request),
                                                    null,
                                                    "{\"schema_valid\":true}",
                                                    jsonOrDefault(
                                                            analysisResult.raw()
                                                                    .path(
                                                                            "manual_review_reasons"),
                                                            "[]"),
                                                    null,
                                                    latencyMs,
                                                    null,
                                                    startedAt,
                                                    traceId,
                                                    SYSTEM.actorId()));
                            String persistedDraftId =
                                    persistAnalysis(
                                            command,
                                            request,
                                            analysisResult,
                                            effectiveRequiresAdditionalEvidence,
                                            latencyMs,
                                            agentRunId);
                            run.attachOutput(persistedDraftId);
                            agentRunRepository.save(run);
                            auditRecorder.record(
                                    SYSTEM,
                                    "AGENT_RUN_COMPLETED",
                                    "AGENT_RUN",
                                    agentRunId,
                                    command.caseId(),
                                    Map.of("run_status", "RUNNING"),
                                    Map.of(
                                            "run_status",
                                            "COMPLETED",
                                            "trace_id",
                                            traceId,
                                            "output_ref",
                                            persistedDraftId));
                            return persistedDraftId;
                        });
        if (requiresAdditionalEvidence) {
            FulfillmentCaseEntity dispute =
                    caseRepository
                            .findById(command.caseId())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "case not found: "
                                                            + command.caseId()));
            lifecycleNotifications.supplementRequested(
                    dispute, "hearing-round-" + command.roundNo());
        }
        return new HearingAnalysisActivityResult(
                requiresAdditionalEvidence,
                result.manualRequired(),
                draftId,
                requiresAdditionalEvidence
                        ? "WAITING_EVIDENCE"
                        : "RUNNING");
    }

    private HearingAgentResult fallbackManualReviewResult(
            HearingAnalysisActivityCommand command, RuntimeException failure) {
        ObjectNode raw = objectMapper.createObjectNode();
        raw.put("case_id", command.caseId());
        raw.put("workflow_id", command.workflowId());
        raw.put("workflow_status", "MANUAL_REVIEW_REQUIRED");
        raw.putArray("executed_nodes").add("adjudication_draft_node");
        raw.putObject("issue_framing")
                .put(
                        "neutral_summary",
                        "庭审模型服务暂不可用，系统进入人工审核兜底流程。")
                .putArray("issues")
                .add("模型服务不可用时，需由平台审核员复核当前证据、陈述与一致方案。");
        raw.putObject("evidence_gap")
                .put("requires_supplemental_evidence", false)
                .putArray("gaps");
        raw.putObject("evidence_cross_check")
                .putArray("findings")
                .add("模型服务暂不可用，未执行自动证据交叉核验。");
        raw.putObject("rule_application")
                .putArray("applications")
                .add("模型服务暂不可用，平台规则适用需由审核员人工确认。");
        ObjectNode draft = raw.putObject("adjudication_draft").putObject("draft");
        draft.put("draft_status", "PENDING_HUMAN_REVIEW");
        draft.put("recommended_outcome", "MANUAL_REVIEW_REQUIRED");
        draft.put(
                "reasoning_summary",
                "模型服务暂不可用，系统已将当前证据、庭审陈述和一致方案收敛为非最终草案，等待平台审核员人工确认。");
        draft.putArray("issue_findings")
                .add("模型服务暂不可用，需人工核对双方履约主张和证据完整性。");
        draft.put("confidence", 0.10);
        draft.putArray("review_focus")
                .add("模型服务暂不可用，需人工复核")
                .add("核对双方一致方案、证据完整性和执行动作");
        raw.putArray("manual_review_reasons")
                .add("HEARING_AGENT_UNAVAILABLE")
                .add(safeMessage(failure));
        raw.put("prompt_version", "hearing-fallback-v1");
        raw.put("model", "local-manual-review-fallback");
        return new HearingAgentResult(
                raw,
                false,
                true,
                List.of("adjudication_draft_node"),
                "hearing-fallback-v1",
                "local-manual-review-fallback");
    }

    private JsonNode buildAgentRequest(HearingAnalysisActivityCommand command) {
        FulfillmentCaseEntity disputeCase =
                caseRepository
                        .findById(command.caseId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "case not found: " + command.caseId()));
        ObjectNode request = objectMapper.createObjectNode();
        request.put("case_id", command.caseId());
        request.put("workflow_id", command.workflowId());
        request.put("user_id", disputeCase.getUserId());
        request.put("evidence_timeout", command.evidenceTimedOut());
        ObjectNode hearingContext = request.putObject("hearing_context");
        hearingContext.put("completed_statement_rounds", command.roundNo());
        hearingContext.put("max_statement_rounds", command.maxStatementRounds());
        hearingContext.put("final_convergence", command.finalConvergence());
        hearingContext.put(
                "must_produce_final_plan", command.mustProduceFinalPlan());
        hearingContext.put(
                "allow_supplemental_request",
                command.allowSupplementalRequest());
        ArrayNode claims = request.putArray("claims");
        claims.addObject()
                .put("claim_id", "CLAIM_" + command.caseId())
                .put("party_type", "USER")
                .put("statement", disputeCase.getDescription());
        ArrayNode evidence = request.putArray("evidence");
        evidenceRepository
                .findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
                        command.caseId())
                .stream()
                .filter(item -> item.getParseStatus() == ParseStatus.SUCCEEDED)
                .filter(item -> item.getParsedText() != null && !item.getParsedText().isBlank())
                .limit(100)
                .forEach(
                        item ->
                                evidence.addObject()
                                        .put("evidence_id", item.getId())
                                        .put("evidence_type", item.getEvidenceType())
                                        .put("source_type", item.getSourceType())
                                        .put("content", item.getParsedText()));
        ArrayNode policies = request.putArray("policy_candidates");
        policyRepository
                .findActive(
                        disputeCase.getCaseType(),
                        OffsetDateTime.now(ZoneOffset.UTC))
                .stream()
                .limit(30)
                .forEach(
                        policy ->
                                policies.addObject()
                                        .put("rule_code", policy.getRuleCode())
                                        .put("rule_version", policy.getRuleVersion())
                                        .put(
                                                "rule_text",
                                                policy.getRuleName()
                                                        + "\n"
                                                        + policy.getConditionJson()
                                                        + "\n"
                                                        + policy.getOutcomeJson()));
        return request;
    }

    private String persistAnalysis(
            HearingAnalysisActivityCommand command,
            JsonNode request,
            HearingAgentResult result,
            boolean requiresAdditionalEvidence,
            long latencyMs,
            String agentRunId) {
        HearingStateEntity state =
                stateRepository
                        .findByWorkflowId(command.workflowId())
                        .orElseThrow(() -> new IllegalStateException("hearing state not found"));
        JsonNode raw = result.raw();
        JsonNode draft = raw.path("adjudication_draft").path("draft");
        BigDecimal confidence =
                BigDecimal.valueOf(draft.path("confidence").asDouble(0));
        state.applyAnalysis(
                command.roundNo(),
                lastNode(result.executedNodes()),
                confidence,
                requiresAdditionalEvidence,
                result.manualRequired(),
                raw.toString(),
                jsonOrDefault(raw.path("evidence_gap").path("gaps"), "[]"),
                jsonOrDefault(raw.path("manual_review_reasons"), "[]"),
                OffsetDateTime.now(ZoneOffset.UTC).plusHours(72),
                SYSTEM.actorId());
        stateRepository.save(state);
        for (String node : result.executedNodes()) {
            if (!recordRepository
                    .existsByWorkflowIdAndNodeNameAndRoundNoAndRecordType(
                            command.workflowId(), node, command.roundNo(), "AGENT_NODE")) {
                recordRepository.save(
                        HearingRecordEntity.record(
                                "HREC_" + compactUuid(),
                                command.caseId(),
                                state.getId(),
                                command.workflowId(),
                                node,
                                command.roundNo(),
                                "AGENT_NODE",
                                request.toString(),
                                nodeOutput(raw, node).toString(),
                                objectMapper
                                        .valueToTree(
                                                Map.of(
                                                        "evidence_received",
                                                        command.evidenceReceived(),
                                                        "evidence_timed_out",
                                                        command.evidenceTimedOut()))
                                        .toString(),
                                result.promptVersion(),
                                result.model(),
                                latencyMs,
                                null,
                                SYSTEM.actorId()));
            }
        }
        int version = command.roundNo() + 1;
        return draftRepository
                .findByCaseIdAndDraftVersion(command.caseId(), version)
                .map(AdjudicationDraftEntity::getId)
                .orElseGet(
                        () ->
                                draftRepository
                                        .save(
                                                AdjudicationDraftEntity.create(
                                                        "DRAFT_" + compactUuid(),
                                                        command.caseId(),
                                                        state.getId(),
                                                        version,
                                                        jsonOrDefault(
                                                                draft.path("issue_findings"),
                                                                "[]"),
                                                        jsonOrDefault(
                                                                raw.path(
                                                                                "evidence_cross_check")
                                                                        .path("findings"),
                                                                "[]"),
                                                        jsonOrDefault(
                                                                raw.path("rule_application")
                                                                        .path("applications"),
                                                                "[]"),
                                                        jsonOrDefault(
                                                                draft.path("review_focus"),
                                                                "[]"),
                                                        truncate(
                                                                draft.path(
                                                                                "recommended_outcome")
                                                                        .asText(),
                                                                128),
                                                        confidence,
                                                        draft.path("reasoning_summary")
                                                                .asText(),
                                                        "python-agent-service/"
                                                                + result.model(),
                                                        agentRunId,
                                                        draft.path("draft_status").asText(
                                                                "PENDING_HUMAN_REVIEW"),
                                                        SYSTEM.actorId()))
                                        .getId());
    }

    @Override
    public void recordPartyEvidence(PartyEvidenceSignal signal) {
        transactions.executeWithoutResult(
                ignored -> {
                    var submission =
                            submissionRepository
                                    .findById(signal.submissionId())
                                    .orElseThrow(
                                            () ->
                                                    new IllegalStateException(
                                                            "party submission not found"));
                    stateRepository
                            .findByCaseId(submission.getCaseId())
                            .ifPresent(state -> state.markRunning(SYSTEM.actorId()));
                });
    }

    @Override
    public void recordReviewerSignal(ReviewerWorkflowSignal signal) {
        // The reviewer-facing API persists and audits the decision before signalling.
    }

    @Override
    public void completeHearing(
            String caseId,
            String workflowId,
            boolean manualRequired,
            boolean evidenceTimedOut) {
        transactions.executeWithoutResult(
                ignored -> {
                    HearingStateEntity state =
                            stateRepository
                                    .findByWorkflowId(workflowId)
                                    .orElseThrow(
                                            () ->
                                                    new IllegalStateException(
                                                            "hearing state not found"));
                    if (state.getHearingStatus()
                            != com.example.dispute.domain.model.HearingStatus.COMPLETED) {
                        state.complete(manualRequired, SYSTEM.actorId());
                        stateRepository.save(state);
                        auditRecorder.record(
                                SYSTEM,
                                "HEARING_COMPLETED",
                                "HEARING_STATE",
                                state.getId(),
                                caseId,
                                Map.of("workflow_id", workflowId),
                                Map.of(
                                        "next_stage", "REMEDY_PLANNER",
                                        "manual_required", manualRequired,
                                        "evidence_timed_out", evidenceTimedOut));
                    }
                });
    }

    @Override
    public String planRemedy(String caseId, String workflowId) {
        return remedyService.generateForWorkflow(caseId, workflowId);
    }

    @Override
    public String createReviewTask(String caseId, String remedyPlanId) {
        return reviewService.createForWorkflow(caseId, remedyPlanId);
    }

    @Override
    public void executeApprovedPlan(String caseId) {
        toolExecutorService.executeApprovedActions(
                caseId, "WORKFLOW_EXECUTE:" + caseId, SYSTEM);
    }

    @Override
    public void closeCaseAndEvaluate(String caseId) {
        closureService.close(
                caseId,
                "WORKFLOW_CLOSE:" + caseId,
                SYSTEM,
                "TRACE_EVALUATION_" + caseId,
                "REQ_EVALUATION_" + caseId);
    }

    private static JsonNode nodeOutput(JsonNode raw, String node) {
        return switch (node) {
            case "issue_framing_node" -> raw.path("issue_framing");
            case "evidence_gap_request_node" -> raw.path("evidence_gap");
            case "party_liaison_node" -> raw.path("party_liaison");
            case "evidence_cross_check_node" -> raw.path("evidence_cross_check");
            case "rule_application_node" -> raw.path("rule_application");
            case "adjudication_draft_node" -> raw.path("adjudication_draft");
            default -> raw;
        };
    }

    private static String lastNode(List<String> nodes) {
        return nodes.isEmpty() ? "C0_HEARING_CONTROLLER" : nodes.get(nodes.size() - 1);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String safeMessage(RuntimeException failure) {
        String message =
                failure.getMessage() == null || failure.getMessage().isBlank()
                        ? failure.getClass().getSimpleName()
                        : failure.getMessage();
        return truncate(message, 160);
    }

    private static String jsonOrDefault(JsonNode value, String fallback) {
        return value == null || value.isMissingNode() || value.isNull()
                ? fallback
                : value.toString();
    }

    private String inputRefs(JsonNode request) {
        ArrayNode refs = objectMapper.createArrayNode();
        request.path("evidence")
                .forEach(node -> refs.add(node.path("evidence_id").asText()));
        request.path("policy_candidates")
                .forEach(node -> refs.add(node.path("rule_code").asText()));
        return refs.toString();
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
