package com.example.dispute.evidence.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundPartySubmissionEntity;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
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
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvidenceDossierRevisionService {

    public static final int EVIDENCE_EXPLANATION_ROUND = 2;

    private final EvidenceDossierRepository dossierRepository;
    private final CaseRoomRepository roomRepository;
    private final CaseEventService eventService;
    private final ObjectMapper objectMapper;

    public EvidenceDossierRevisionService(
            EvidenceDossierRepository dossierRepository,
            CaseRoomRepository roomRepository,
            CaseEventService eventService,
            ObjectMapper objectMapper) {
        this.dossierRepository = dossierRepository;
        this.roomRepository = roomRepository;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public EvidenceDossierEntity reviseAfterRoundIfNeeded(
            String caseId,
            int roundNo,
            List<HearingRoundPartySubmissionEntity> submissions,
            String actorId) {
        EvidenceDossierEntity previous =
                dossierRepository
                        .findTopByCaseIdOrderByDossierVersionDesc(caseId)
                        .orElseGet(() -> dossierRepository.save(emptyEvidenceBaseline(caseId, actorId)));
        if (roundNo != EVIDENCE_EXPLANATION_ROUND) {
            return previous;
        }
        JsonNode latestMatrix = readTree(previous.getMatrixSummaryJson(), "latest matrix summary");
        if (latestMatrix.path("updated_after_round").asInt(-1) == EVIDENCE_EXPLANATION_ROUND) {
            return previous;
        }

        int previousVersion = previous.getDossierVersion();
        int activeVersion = previousVersion + 1;
        ObjectNode revisedMatrix = matrixObject(latestMatrix);
        revisedMatrix.put("revision_reason", "ROUND_2_EVIDENCE_EXPLANATION_REVIEW");
        revisedMatrix.put("updated_after_round", EVIDENCE_EXPLANATION_ROUND);
        revisedMatrix.put("supersedes_version", previousVersion);
        revisedMatrix.put("active_version", activeVersion);
        revisedMatrix.put(
                "revision_summary",
                "证据书记官已根据第 2 轮双方证据解释更新 active 证据证明矩阵，供法官第 3 轮方案确认和裁决草案使用。");
        revisedMatrix.set("round_2_party_explanations", partyExplanations(submissions));
        if (!revisedMatrix.path("fact_evidence_matrix").isArray()) {
            revisedMatrix.set("fact_evidence_matrix", objectMapper.createArrayNode());
        }
        ArrayNode revisionHistory =
                revisedMatrix.withArrayProperty("revision_history");
        ObjectNode revision = objectMapper.createObjectNode();
        revision.put("from_version", previousVersion);
        revision.put("to_version", activeVersion);
        revision.put("updated_after_round", EVIDENCE_EXPLANATION_ROUND);
        revision.put("updated_by_agent", "evidence-clerk");
        revision.put("actor_id", actorId == null || actorId.isBlank() ? "evidence-clerk" : actorId);
        revisionHistory.add(revision);

        EvidenceDossierEntity active =
                dossierRepository.save(
                        EvidenceDossierEntity.frozen(
                                "EVIDENCE_DOSSIER_" + compactUuid(),
                                caseId,
                                activeVersion,
                                actorId == null || actorId.isBlank() ? "evidence-clerk" : actorId,
                                previous.getSummaryJson(),
                                previous.getTimelineJson(),
                                json(revisedMatrix)));
        recordRevisionEvent(caseId, previousVersion, activeVersion, actorId);
        return active;
    }

    private ArrayNode partyExplanations(List<HearingRoundPartySubmissionEntity> submissions) {
        ArrayNode explanations = objectMapper.createArrayNode();
        for (HearingRoundPartySubmissionEntity submission : submissions) {
            ObjectNode item = objectMapper.createObjectNode();
            ActorRole role = submission.getParticipantRole();
            item.put("party_role", role.name());
            item.put("submission_source", submission.getSubmissionSource().name());
            item.set("statement", readTree(submission.getSubmissionJson(), role.name() + " submission"));
            explanations.add(item);
        }
        return explanations;
    }

    private ObjectNode matrixObject(JsonNode matrix) {
        if (matrix != null && matrix.isObject()) {
            return matrix.deepCopy();
        }
        ObjectNode wrapper = objectMapper.createObjectNode();
        if (matrix != null && matrix.isArray()) {
            wrapper.set("fact_evidence_matrix", matrix.deepCopy());
        }
        return wrapper;
    }

    private EvidenceDossierEntity emptyEvidenceBaseline(String caseId, String actorId) {
        String writer = actorId == null || actorId.isBlank() ? "evidence-clerk" : actorId;
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("evidence_count", 0);
        summary.putArray("evidence_items");
        ObjectNode partySummary = summary.putObject("party_evidence_summary");
        partySummary.set("USER", emptyPartyEvidenceSummary());
        partySummary.set("MERCHANT", emptyPartyEvidenceSummary());
        summary.putArray("verified_facts");
        summary.putArray("contested_facts");
        ArrayNode gaps = summary.putArray("evidence_gaps");
        gaps.add("USER 尚未形成有效证据材料");
        gaps.add("MERCHANT 尚未形成有效证据材料");
        summary.putArray("authenticity_flags");
        summary.put("overall_confidence_score", 0);
        summary.put(
                "handoff_notes",
                "证据室尚未形成有效证据矩阵，第二轮复核将基于双方证据解释和补证记录更新。");
        summary.put("baseline_empty", true);

        ObjectNode matrix = objectMapper.createObjectNode();
        matrix.putArray("fact_evidence_matrix");
        matrix.set("evidence_gaps", gaps.deepCopy());
        matrix.put("handoff_notes", summary.path("handoff_notes").asText());
        matrix.put("baseline_empty", true);
        return EvidenceDossierEntity.frozen(
                "EVIDENCE_DOSSIER_" + compactUuid(),
                caseId,
                1,
                writer,
                json(summary),
                "[]",
                json(matrix));
    }

    private ObjectNode emptyPartyEvidenceSummary() {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.putArray("strong_points");
        summary.putArray("weak_points");
        summary.putArray("missing_items");
        return summary;
    }

    private void recordRevisionEvent(
            String caseId, int previousVersion, int activeVersion, String actorId) {
        CaseRoomEntity hearingRoom =
                roomRepository
                        .findByCaseIdAndRoomType(caseId, RoomType.HEARING)
                        .orElseThrow(() -> new IllegalArgumentException("hearing room not found"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("previous_version", previousVersion);
        payload.put("active_version", activeVersion);
        payload.put("updated_after_round", EVIDENCE_EXPLANATION_ROUND);
        payload.put("revision_reason", "ROUND_2_EVIDENCE_EXPLANATION_REVIEW");
        payload.put(
                "summary",
                "证据书记官已根据第 2 轮证据解释更新 active 证据证明矩阵。");
        eventService.recordLifecycleEvent(
                caseId,
                hearingRoom.getId(),
                "EVIDENCE_DOSSIER_REVISED",
                payload,
                "evidence-dossier-revised:" + activeVersion + ":round-" + EVIDENCE_EXPLANATION_ROUND,
                actorId == null || actorId.isBlank() ? "evidence-clerk" : actorId);
    }

    private JsonNode readTree(String json, String label) {
        try {
            return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid evidence dossier " + label, exception);
        }
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize evidence dossier revision", exception);
        }
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
