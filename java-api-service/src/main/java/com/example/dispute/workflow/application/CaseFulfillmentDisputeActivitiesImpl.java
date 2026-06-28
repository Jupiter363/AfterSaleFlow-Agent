package com.example.dispute.workflow.application;

import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.ParseStatus;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.HearingRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.HearingStateEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.infrastructure.persistence.repository.PartySubmissionRepository;
import com.example.dispute.infrastructure.persistence.repository.PolicyRuleRepository;
import com.example.dispute.workflow.domain.CaseWorkflowInput;
import com.example.dispute.workflow.domain.HearingAnalysisActivityCommand;
import com.example.dispute.workflow.domain.HearingAnalysisActivityResult;
import com.example.dispute.workflow.domain.PartyEvidenceSignal;
import com.example.dispute.workflow.domain.ReviewerWorkflowSignal;
import com.example.dispute.workflow.temporal.CaseFulfillmentDisputeActivities;
import com.example.dispute.remedy.application.RemedyApplicationService;
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
    private final PartySubmissionRepository submissionRepository;
    private final HearingAgentClient agentClient;
    private final RemedyApplicationService remedyService;
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
            PartySubmissionRepository submissionRepository,
            HearingAgentClient agentClient,
            RemedyApplicationService remedyService,
            AuditRecorder auditRecorder,
            ObjectMapper objectMapper,
            TransactionTemplate transactions) {
        this.caseRepository = caseRepository;
        this.evidenceRepository = evidenceRepository;
        this.policyRepository = policyRepository;
        this.stateRepository = stateRepository;
        this.recordRepository = recordRepository;
        this.draftRepository = draftRepository;
        this.submissionRepository = submissionRepository;
        this.agentClient = agentClient;
        this.remedyService = remedyService;
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
        HearingAgentResult result =
                agentClient.analyze(
                        request,
                        "TRACE_" + command.workflowId(),
                        "REQ_" + command.workflowId() + "_" + command.roundNo());
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        String draftId =
                transactions.execute(
                        ignored ->
                                persistAnalysis(
                                        command, request, result, latencyMs));
        return new HearingAnalysisActivityResult(
                result.requiresAdditionalEvidence(),
                result.manualRequired(),
                draftId,
                result.requiresAdditionalEvidence()
                        ? "WAITING_EVIDENCE"
                        : "RUNNING");
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
            long latencyMs) {
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
                result.requiresAdditionalEvidence(),
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

    private static String jsonOrDefault(JsonNode value, String fallback) {
        return value == null || value.isMissingNode() || value.isNull()
                ? fallback
                : value.toString();
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
