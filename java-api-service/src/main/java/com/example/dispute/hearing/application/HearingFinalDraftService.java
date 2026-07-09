package com.example.dispute.hearing.application;

import com.example.dispute.hearing.domain.HearingRoundStatus;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundPartySubmissionEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundPartySubmissionRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundRepository;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.HearingRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.HearingStateEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.workflow.application.HearingAgentClient;
import com.example.dispute.workflow.application.HearingAgentResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HearingFinalDraftService {

    static final String FINAL_DRAFT_NODE = "C6_ADJUDICATION_DRAFT";
    static final String FINAL_DRAFT_RECORD_TYPE = "AGENT_NODE";
    private static final String SYSTEM_AGENT_ID = "presiding-judge";

    private static final Logger log = LoggerFactory.getLogger(HearingFinalDraftService.class);

    private final FulfillmentCaseRepository caseRepository;
    private final HearingStateRepository stateRepository;
    private final HearingRoundRepository roundRepository;
    private final HearingRoundPartySubmissionRepository submissionRepository;
    private final HearingRecordRepository recordRepository;
    private final AdjudicationDraftRepository draftRepository;
    private final AgentRunRepository agentRunRepository;
    private final AgentA2AMessageService a2aMessageService;
    private final HearingAgentClient agentClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public HearingFinalDraftService(
            FulfillmentCaseRepository caseRepository,
            HearingStateRepository stateRepository,
            HearingRoundRepository roundRepository,
            HearingRoundPartySubmissionRepository submissionRepository,
            HearingRecordRepository recordRepository,
            AdjudicationDraftRepository draftRepository,
            AgentRunRepository agentRunRepository,
            AgentA2AMessageService a2aMessageService,
            HearingAgentClient agentClient,
            ObjectMapper objectMapper,
            Clock clock) {
        this.caseRepository = caseRepository;
        this.stateRepository = stateRepository;
        this.roundRepository = roundRepository;
        this.submissionRepository = submissionRepository;
        this.recordRepository = recordRepository;
        this.draftRepository = draftRepository;
        this.agentRunRepository = agentRunRepository;
        this.a2aMessageService = a2aMessageService;
        this.agentClient = agentClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public String ensureDraftForFinalSealedRound(
            String caseId, int finalRoundNo, int maxStatementRounds, String actorId) {
        int draftVersion = finalRoundNo + 1;
        var existing =
                draftRepository.findByCaseIdAndDraftVersion(caseId, draftVersion);
        if (existing.isPresent()) {
            completeStateIfNeeded(caseId, finalRoundNo, existing.get(), actorId);
            return existing.get().getId();
        }

        FulfillmentCaseEntity dispute =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        HearingRoundEntity finalRound =
                roundRepository
                        .findByCaseIdAndRoundNo(caseId, finalRoundNo)
                        .orElseThrow(() -> new IllegalStateException("final hearing round not found"));
        assertFinalRoundSealed(finalRound, finalRoundNo, maxStatementRounds);
        HearingStateEntity hearingState = ensureHearingState(dispute, actorId);

        JsonNode request =
                buildAgentRequest(dispute, hearingState, finalRoundNo, maxStatementRounds);
        long started = System.nanoTime();
        OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        String traceId = "TRACE_HEARING_FINAL_DRAFT_" + caseId;
        HearingAgentResult result = generateDraftResult(caseId, request, traceId);
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        JsonNode raw = result.raw();
        JsonNode draft = raw.path("adjudication_draft").path("draft");
        BigDecimal confidence = BigDecimal.valueOf(draft.path("confidence").asDouble(0.25));
        String agentRunId = "RUN_" + compactUuid();

        AgentRunEntity run =
                agentRunRepository.saveAndFlush(
                        AgentRunEntity.completed(
                                agentRunId,
                                caseId,
                                hearingState.getWorkflowId(),
                                SYSTEM_AGENT_ID,
                                "PRESIDING_JUDGE",
                                "presiding-judge-v1",
                                result.promptVersion(),
                                "evidence-identification-v1",
                                "ruleset-current",
                                result.model(),
                                inputRefs(finalRoundNo),
                                null,
                                "{\"schema_valid\":true}",
                                jsonOrDefault(raw.path("manual_review_reasons"), "[]"),
                                null,
                                latencyMs,
                                null,
                                startedAt,
                                traceId,
                                actorId));
        hearingState.applyAnalysis(
                finalRoundNo,
                FINAL_DRAFT_NODE,
                confidence,
                false,
                result.manualRequired(),
                raw.toString(),
                "[]",
                jsonOrDefault(raw.path("manual_review_reasons"), "[]"),
                OffsetDateTime.now(ZoneOffset.UTC),
                actorId);
        hearingState.complete(result.manualRequired(), actorId);
        stateRepository.save(hearingState);
        recordFinalDraftNodeIfAbsent(
                caseId,
                hearingState,
                finalRoundNo,
                request,
                raw,
                result,
                latencyMs,
                actorId);
        AdjudicationDraftEntity persisted =
                draftRepository.save(
                        AdjudicationDraftEntity.create(
                                "DRAFT_" + compactUuid(),
                                caseId,
                                hearingState.getId(),
                                draftVersion,
                                jsonOrDefault(draft.path("issue_findings"), "[]"),
                                jsonOrDefault(
                                        raw.path("evidence_cross_check").path("findings"),
                                        "[]"),
                                jsonOrDefault(
                                        raw.path("rule_application").path("applications"),
                                        "[]"),
                                jsonOrDefault(draft.path("review_focus"), "[]"),
                                truncate(
                                        draft.path("recommended_outcome")
                                                .asText("REVIEWABLE_DRAFT_REQUIRED"),
                                        128),
                                confidence,
                                draft.path("reasoning_summary")
                                        .asText("AI 法官已生成裁决草案，等待平台审核确认。"),
                                "python-agent-service/" + result.model(),
                                agentRunId,
                                draft.path("draft_status").asText("PENDING_HUMAN_REVIEW"),
                                actorId));
        run.attachOutput(persisted.getId());
        agentRunRepository.save(run);
        return persisted.getId();
    }

    private void completeStateIfNeeded(
            String caseId, int finalRoundNo, AdjudicationDraftEntity draft, String actorId) {
        stateRepository
                .findByCaseId(caseId)
                .ifPresent(
                        state -> {
                            if (state.getCompletedAt() == null) {
                                BigDecimal confidence =
                                        draft.getConfidence() == null
                                                ? BigDecimal.valueOf(0.25)
                                                : draft.getConfidence();
                                state.applyAnalysis(
                                        finalRoundNo,
                                        FINAL_DRAFT_NODE,
                                        confidence,
                                        false,
                                        true,
                                        "{}",
                                        "[]",
                                        "[]",
                                        OffsetDateTime.now(ZoneOffset.UTC),
                                        actorId);
                                state.complete(true, actorId);
                                stateRepository.save(state);
                            }
                        });
    }

    private void assertFinalRoundSealed(
            HearingRoundEntity finalRound, int finalRoundNo, int maxStatementRounds) {
        boolean sealed =
                finalRound.getClosedAt() != null
                        || finalRound.getRoundStatus() == HearingRoundStatus.COMPLETED
                        || finalRound.getRoundStatus() == HearingRoundStatus.FORCED_CLOSED;
        if (!sealed || finalRoundNo < maxStatementRounds) {
            throw new IllegalStateException("final hearing round is not sealed");
        }
    }

    private HearingStateEntity ensureHearingState(
            FulfillmentCaseEntity dispute, String actorId) {
        return stateRepository
                .findByCaseId(dispute.getId())
                .orElseGet(
                        () -> {
                            String workflowId =
                                    dispute.getCurrentWorkflowId() == null
                                                    || dispute.getCurrentWorkflowId().isBlank()
                                            ? "hearing-window-" + dispute.getId()
                                            : dispute.getCurrentWorkflowId();
                            if (dispute.getCurrentWorkflowId() == null
                                    || dispute.getCurrentWorkflowId().isBlank()) {
                                dispute.attachHearingWorkflow(workflowId, actorId);
                                caseRepository.save(dispute);
                            }
                            return stateRepository.save(
                                    HearingStateEntity.start(
                                            "HEARING_" + compactUuid(),
                                            dispute.getId(),
                                            workflowId,
                                            actorId));
                        });
    }

    private JsonNode buildAgentRequest(
            FulfillmentCaseEntity dispute,
            HearingStateEntity hearingState,
            int finalRoundNo,
            int maxStatementRounds) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("case_id", dispute.getId());
        request.put("workflow_id", hearingState.getWorkflowId());
        request.put("user_id", dispute.getUserId());
        request.put("evidence_timeout", false);
        ObjectNode hearingContext = request.putObject("hearing_context");
        hearingContext.put("completed_statement_rounds", finalRoundNo);
        hearingContext.put("max_statement_rounds", maxStatementRounds);
        hearingContext.put("final_convergence", true);
        hearingContext.put("must_produce_final_plan", true);
        hearingContext.put("allow_supplemental_request", false);
        hearingContext.set("courtroom_context", courtroomContext(dispute.getId(), finalRoundNo));
        ArrayNode sealedRounds = hearingContext.putArray("sealed_rounds");
        roundRepository.findAllByCaseIdOrderByRoundNoAsc(dispute.getId())
                .forEach(round -> sealedRounds.add(sealedRound(dispute.getId(), round)));

        ArrayNode claims = request.putArray("claims");
        claims.addObject()
                .put("claim_id", "CLAIM_" + dispute.getId())
                .put("party_type", "USER")
                .put("statement", nullToEmpty(dispute.getDescription()));
        ArrayNode evidence = request.putArray("evidence");
        evidence.addObject()
                .put("evidence_id", "COURTROOM_CONTEXT_" + dispute.getId())
                .put("evidence_type", "HEARING_DOSSIER")
                .put("source_type", "BOOTSTRAP_DOSSIER")
                .put("content", hearingContext.path("courtroom_context").toString());
        request.putArray("policy_candidates");
        return request;
    }

    private ObjectNode sealedRound(String caseId, HearingRoundEntity round) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("round_no", round.getRoundNo());
        node.put("round_status", round.getRoundStatus().name());
        node.put("stop_reason", round.getStopReason() == null ? "" : round.getStopReason().name());
        node.put("summary_json", nullToEmpty(round.getSummaryJson()));
        ArrayNode submissions = node.putArray("party_submissions");
        submissionRepository.findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(caseId, round.getRoundNo())
                .stream()
                .map(this::submissionNode)
                .forEach(submissions::add);
        return node;
    }

    private ObjectNode submissionNode(HearingRoundPartySubmissionEntity submission) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("participant_role", submission.getParticipantRole().name());
        node.put("submission_source", submission.getSubmissionSource().name());
        node.put("submission_json", nullToEmpty(submission.getSubmissionJson()));
        return node;
    }

    private JsonNode courtroomContext(String caseId, int finalRoundNo) {
        ObjectNode context =
                recordRepository
                .findTopByCaseIdAndNodeNameAndRoundNoAndRecordTypeOrderByCreatedAtDesc(
                        caseId,
                        HearingCourtBootstrapService.BOOTSTRAP_NODE,
                        HearingCourtBootstrapService.OPENING_ROUND_NO,
                        HearingCourtBootstrapService.SNAPSHOT_RECORD_TYPE)
                .map(HearingRecordEntity::getOutputJson)
                .map(this::readJsonObject)
                .orElseGet(
                        () -> {
                            ObjectNode fallback = objectMapper.createObjectNode();
                            fallback.put("schema_version", "hearing_bootstrap_dossier.fallback");
                            fallback.put("case_id", caseId);
                            fallback.put("warning", "hearing bootstrap snapshot missing");
                            return fallback;
                        });
        attachJuryA2AReports(context, caseId, finalRoundNo);
        return context;
    }

    private void attachJuryA2AReports(ObjectNode context, String caseId, int finalRoundNo) {
        ArrayNode notes = objectMapper.createArrayNode();
        ObjectNode latestFormalReport = null;
        for (AgentA2AMessageView message : a2aMessageService.findForJudge(caseId, finalRoundNo)) {
            ObjectNode note = notes.addObject();
            note.put("a2a_message_id", message.a2aMessageId());
            note.put("round_no", message.roundNo());
            note.put("from_agent", message.fromAgent());
            note.put("to_agent", message.toAgent());
            note.put("message_type", message.messageType());
            note.set("input_refs", readJson(message.inputRefsJson()));
            note.set("payload", readJson(message.payloadJson()));
            note.put("visibility", message.visibility());
            if (message.agentRunId() != null && !message.agentRunId().isBlank()) {
                note.put("agent_run_id", message.agentRunId());
            }
            if ("JURY_REVIEW_REPORT".equals(message.messageType())) {
                latestFormalReport = note.deepCopy();
            }
        }
        context.set("jury_a2a_notes", notes);
        if (latestFormalReport != null) {
            context.set("jury_review_report", latestFormalReport);
        }
    }

    private HearingAgentResult generateDraftResult(
            String caseId, JsonNode request, String traceId) {
        try {
            HearingAgentResult result =
                    agentClient.analyze(
                            request,
                            traceId,
                            "REQ_HEARING_FINAL_DRAFT_" + caseId);
            if (result == null
                    || result.raw() == null
                    || result.raw().path("adjudication_draft").path("draft").isMissingNode()) {
                throw new IllegalStateException("hearing agent returned no adjudication draft");
            }
            return result;
        } catch (RuntimeException failure) {
            log.warn("Final hearing draft generation degraded for case {}", caseId, failure);
            return fallbackDraftResult(caseId, request, failure);
        }
    }

    private HearingAgentResult fallbackDraftResult(
            String caseId, JsonNode request, RuntimeException failure) {
        ObjectNode raw = objectMapper.createObjectNode();
        raw.put("case_id", caseId);
        raw.put("workflow_id", request.path("workflow_id").asText(""));
        raw.put("workflow_status", "MANUAL_REVIEW_REQUIRED");
        raw.putArray("executed_nodes").add(FINAL_DRAFT_NODE);
        raw.putObject("issue_framing")
                .put(
                        "neutral_summary",
                        "最终庭审材料已封存，系统生成可审核裁决草案。")
                .putArray("issues")
                .add("平台审核员需要复核三轮陈述、案情卷宗和证据证明矩阵。");
        raw.putObject("evidence_gap")
                .put("requires_supplemental_evidence", false)
                .putArray("gaps");
        raw.putObject("evidence_cross_check")
                .putArray("findings")
                .add("现有材料已进入裁决草案阶段，证据真实性与关联性仍需平台审核确认。");
        raw.putObject("rule_application")
                .putArray("applications")
                .add("裁决草案需由平台审核员结合履约、售后、物流与证据规则进行最终确认。");
        ObjectNode draft = raw.putObject("adjudication_draft").putObject("draft");
        draft.put("draft_status", "PENDING_HUMAN_REVIEW");
        draft.put("recommended_outcome", fallbackOutcome(request));
        draft.put(
                "reasoning_summary",
                "AI 法官裁决草案：三轮庭审陈述已封存，系统基于案情卷宗、证据证明矩阵和双方陈述生成非最终处理方案，等待平台审核员确认。");
        draft.putArray("issue_findings")
                .add("三轮陈述已完成，庭审不再要求双方继续补充本轮陈述。");
        draft.put("confidence", 0.25);
        draft.putArray("review_focus")
                .add("核验裁决草案是否准确覆盖案情争点、证据强弱和双方陈述。")
                .add("确认执行方案是否具备履约依据和可操作性。")
                .add("复核模型兜底生成原因：" + safeMessage(failure));
        raw.putArray("manual_review_reasons")
                .add("HEARING_FINAL_DRAFT_FALLBACK")
                .add(safeMessage(failure));
        raw.put("prompt_version", "hearing-final-draft-fallback-v1");
        raw.put("model", "local-final-draft-fallback");
        return new HearingAgentResult(
                raw,
                false,
                true,
                List.of(FINAL_DRAFT_NODE),
                "hearing-final-draft-fallback-v1",
                "local-final-draft-fallback");
    }

    private String fallbackOutcome(JsonNode request) {
        String context = request.toString();
        if (context.contains("退款") || context.toUpperCase().contains("REFUND")) {
            return "REFUND_AFTER_REVIEW";
        }
        if (context.contains("补发") || context.contains("未收到") || context.toUpperCase().contains("NOT_RECEIVED")) {
            return "RESHIP_OR_REFUND_AFTER_SIGNATURE_REVIEW";
        }
        return "REVIEWABLE_REMEDY_AFTER_HEARING";
    }

    private void recordFinalDraftNodeIfAbsent(
            String caseId,
            HearingStateEntity hearingState,
            int finalRoundNo,
            JsonNode request,
            JsonNode raw,
            HearingAgentResult result,
            long latencyMs,
            String actorId) {
        if (recordRepository.existsByWorkflowIdAndNodeNameAndRoundNoAndRecordType(
                hearingState.getWorkflowId(),
                FINAL_DRAFT_NODE,
                finalRoundNo,
                FINAL_DRAFT_RECORD_TYPE)) {
            return;
        }
        recordRepository.save(
                HearingRecordEntity.record(
                        "HREC_" + compactUuid(),
                        caseId,
                        hearingState.getId(),
                        hearingState.getWorkflowId(),
                        FINAL_DRAFT_NODE,
                        finalRoundNo,
                        FINAL_DRAFT_RECORD_TYPE,
                        request.toString(),
                        raw.path("adjudication_draft").toString(),
                        objectMapper
                                .valueToTree(
                                        Map.of(
                                                "final_convergence",
                                                true,
                                                "source",
                                                "hearing-complete"))
                                .toString(),
                        result.promptVersion(),
                        result.model(),
                        latencyMs,
                        null,
                        actorId));
    }

    private ObjectNode readJsonObject(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.isObject() ? (ObjectNode) node : objectMapper.createObjectNode();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid hearing courtroom context json", exception);
        }
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (JsonProcessingException exception) {
            return objectMapper.createObjectNode();
        }
    }

    private static String inputRefs(int finalRoundNo) {
        return "[\"hearing_rounds_1_to_" + finalRoundNo + "\",\"courtroom_context\"]";
    }

    private static String jsonOrDefault(JsonNode value, String fallback) {
        return value == null || value.isMissingNode() || value.isNull() ? fallback : value.toString();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String safeMessage(RuntimeException failure) {
        String message =
                failure.getMessage() == null || failure.getMessage().isBlank()
                        ? failure.getClass().getSimpleName()
                        : failure.getMessage();
        return truncate(message, 160);
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
