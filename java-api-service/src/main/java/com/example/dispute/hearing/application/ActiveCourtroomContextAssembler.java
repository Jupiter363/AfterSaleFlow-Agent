package com.example.dispute.hearing.application;

import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundPartySubmissionEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundPartySubmissionRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingRoundRepository;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingRecordRepository;
import com.example.dispute.tool.application.ToolDefinition;
import com.example.dispute.tool.application.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActiveCourtroomContextAssembler {

    private final HearingRecordRepository hearingRecordRepository;
    private final EvidenceDossierRepository evidenceDossierRepository;
    private final HearingRoundRepository roundRepository;
    private final HearingRoundPartySubmissionRepository submissionRepository;
    private final AgentA2AMessageService a2aMessageService;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public ActiveCourtroomContextAssembler(
            HearingRecordRepository hearingRecordRepository,
            EvidenceDossierRepository evidenceDossierRepository,
            HearingRoundRepository roundRepository,
            HearingRoundPartySubmissionRepository submissionRepository,
            AgentA2AMessageService a2aMessageService,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper) {
        this.hearingRecordRepository = hearingRecordRepository;
        this.evidenceDossierRepository = evidenceDossierRepository;
        this.roundRepository = roundRepository;
        this.submissionRepository = submissionRepository;
        this.a2aMessageService = a2aMessageService;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ObjectNode assemble(String caseId, int throughRoundNo) {
        ObjectNode context =
                hearingRecordRepository
                        .findTopByCaseIdAndNodeNameAndRoundNoAndRecordTypeOrderByCreatedAtDesc(
                                caseId,
                                HearingCourtBootstrapService.BOOTSTRAP_NODE,
                                HearingCourtBootstrapService.OPENING_ROUND_NO,
                                HearingCourtBootstrapService.SNAPSHOT_RECORD_TYPE)
                        .map(record -> readObject(record.getOutputJson(), "hearing bootstrap snapshot"))
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "hearing bootstrap snapshot not found for case "
                                                        + caseId));
        int baselineVersion =
                context.path("source_versions")
                        .path("evidence_dossier_version")
                        .asInt(context.path("evidence_dossier_version").asInt(0));
        var active = evidenceDossierRepository.findTopByCaseIdOrderByDossierVersionDesc(caseId);
        int activeVersion =
                active.map(EvidenceDossierEntity::getDossierVersion).orElse(baselineVersion);
        if (baselineVersion <= 0) {
            baselineVersion = activeVersion;
        }

        ObjectNode sourceVersions = context.withObjectProperty("source_versions");
        sourceVersions.put("evidence_dossier_version", baselineVersion);
        ObjectNode ref = context.putObject("evidence_dossier_ref");
        ref.put("baseline_version", baselineVersion);
        ref.put("active_version", activeVersion);
        active.ifPresent(
                dossier -> {
                    ref.put("active_dossier_id", dossier.getId());
                    ref.put("active_status", dossier.getDossierStatus());
                    context.put("evidence_dossier_version", dossier.getDossierVersion());
                    context.set("evidence_dossier", evidenceDossierContext(dossier));
                });

        attachJuryContext(context, caseId, throughRoundNo);
        context.set("execution_tool_declarations", executionToolDeclarations());
        return context;
    }

    @Transactional(readOnly = true)
    public ObjectNode assembleFinalConvergence(String caseId, int throughRoundNo) {
        ObjectNode context = assemble(caseId, throughRoundNo);
        if (!context.path("jury_review_report").isObject()) {
            throw new IllegalStateException(
                    "formal jury review report is required for final convergence");
        }
        return context;
    }

    @Transactional(readOnly = true)
    public ArrayNode sealedRounds(String caseId, int throughRoundNo) {
        ArrayNode sealedRounds = objectMapper.createArrayNode();
        roundRepository.findAllByCaseIdOrderByRoundNoAsc(caseId).stream()
                .filter(round -> round.getRoundNo() <= throughRoundNo)
                .map(round -> sealedRound(caseId, round))
                .forEach(sealedRounds::add);
        return sealedRounds;
    }

    private ObjectNode sealedRound(String caseId, HearingRoundEntity round) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("round_no", round.getRoundNo());
        node.put("round_status", round.getRoundStatus().name());
        node.put("dossier_version", round.getDossierVersion());
        node.put(
                "stop_reason",
                round.getStopReason() == null ? "" : round.getStopReason().name());
        node.set("summary", readJson(round.getSummaryJson(), "hearing round summary"));
        ArrayNode submissions = node.putArray("party_submissions");
        submissionRepository
                .findAllByCaseIdAndRoundNoOrderBySubmittedAtAsc(caseId, round.getRoundNo())
                .stream()
                .map(this::submissionNode)
                .forEach(submissions::add);
        return node;
    }

    private ObjectNode submissionNode(HearingRoundPartySubmissionEntity submission) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("participant_role", submission.getParticipantRole().name());
        node.put("submission_source", submission.getSubmissionSource().name());
        node.set(
                "submission",
                readJson(submission.getSubmissionJson(), "hearing round party submission"));
        return node;
    }

    private void attachJuryContext(ObjectNode context, String caseId, int throughRoundNo) {
        ArrayNode notes = objectMapper.createArrayNode();
        ObjectNode latestFormalReport = null;
        List<AgentA2AMessageView> messages =
                a2aMessageService.findForJudge(caseId, throughRoundNo);
        if (messages == null) {
            messages = List.of();
        }
        for (AgentA2AMessageView message : messages) {
            ObjectNode note = notes.addObject();
            note.put("a2a_message_id", message.a2aMessageId());
            note.put("round_no", message.roundNo());
            note.put("from_agent", message.fromAgent());
            note.put("to_agent", message.toAgent());
            note.put("message_type", message.messageType());
            note.set("input_refs", readJson(message.inputRefsJson(), "A2A input refs"));
            note.set("payload", readJson(message.payloadJson(), "A2A payload"));
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
        } else {
            context.remove("jury_review_report");
        }
    }

    private ArrayNode executionToolDeclarations() {
        ArrayNode tools = objectMapper.createArrayNode();
        List<ToolDefinition> definitions = toolRegistry.definitions();
        if (definitions == null) {
            return tools;
        }
        for (ToolDefinition definition : definitions) {
            ObjectNode tool = tools.addObject();
            tool.put("action_type", definition.actionType());
            tool.put("tool_name", definition.toolName());
            tool.put("operation", definition.operation());
            tool.put("display_name", definition.displayName());
            tool.put("description", definition.description());
            tool.put("risk_level", definition.riskLevel().name());
            tool.put("simulated", definition.simulated());
            tool.put("requires_approved_plan", definition.requiresApprovedPlan());
        }
        return tools;
    }

    private ObjectNode evidenceDossierContext(EvidenceDossierEntity dossier) {
        JsonNode summary = readJson(dossier.getSummaryJson(), "active evidence summary");
        JsonNode timeline = readJson(dossier.getTimelineJson(), "active evidence timeline");
        JsonNode matrix = readJson(dossier.getMatrixSummaryJson(), "active evidence matrix");
        ObjectNode context = objectMapper.createObjectNode();
        context.put("source", "active_evidence_dossier");
        context.put("dossier_id", dossier.getId());
        context.put("dossier_version", dossier.getDossierVersion());
        context.put("dossier_status", dossier.getDossierStatus());
        context.set(
                "summary",
                summary.isObject() ? summary.deepCopy() : objectMapper.createObjectNode());
        context.set("evidence_items", arrayOrEmpty(summary.path("evidence_items")));
        context.set("timeline", arrayOrEmpty(timeline));
        context.set(
                "fact_evidence_matrix",
                matrix.path("fact_evidence_matrix").isArray()
                        ? matrix.path("fact_evidence_matrix").deepCopy()
                        : arrayOrEmpty(matrix));
        context.set(
                "party_evidence_summary",
                summary.path("party_evidence_summary").isObject()
                        ? summary.path("party_evidence_summary").deepCopy()
                        : objectMapper.createObjectNode());
        context.set("verified_facts", arrayOrEmpty(summary.path("verified_facts")));
        context.set("contested_facts", arrayOrEmpty(summary.path("contested_facts")));
        context.set("evidence_gaps", arrayOrEmpty(summary.path("evidence_gaps")));
        context.set("authenticity_flags", arrayOrEmpty(summary.path("authenticity_flags")));
        context.put(
                "overall_confidence_score",
                summary.path("overall_confidence_score")
                        .asInt(summary.path("confidence_score").asInt(0)));
        context.put(
                "handoff_notes",
                defaultText(
                        summary.path("handoff_notes").asText(null),
                        matrix.path("handoff_notes")
                                .asText("Active evidence dossier has no handoff notes.")));
        ObjectNode rawProjection = context.putObject("raw_projection");
        rawProjection.set("summary_json", summary.deepCopy());
        rawProjection.set("timeline_json", timeline.deepCopy());
        rawProjection.set("matrix_summary_json", matrix.deepCopy());
        return context;
    }

    private ObjectNode readObject(String json, String label) {
        JsonNode node = readJson(json, label);
        if (!node.isObject()) {
            throw new IllegalStateException(label + " must be a JSON object");
        }
        return node.deepCopy();
    }

    private JsonNode readJson(String json, String label) {
        try {
            return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid " + label, exception);
        }
    }

    private ArrayNode arrayOrEmpty(JsonNode node) {
        return node != null && node.isArray()
                ? node.deepCopy()
                : objectMapper.createArrayNode();
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
