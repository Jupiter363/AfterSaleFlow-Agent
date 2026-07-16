package com.example.dispute.room.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Service;

/** Applies lifecycle-only transitions to a semantically complete case matrix. */
@Service
public class IntakeMatrixLifecycleService {

    private final CaseIntakeDossierRepository repository;
    private final ObjectMapper objectMapper;

    public IntakeMatrixLifecycleService(
            CaseIntakeDossierRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void freezeRespondentTimeout(FulfillmentCaseEntity dispute) {
        CaseIntakeDossierEntity dossier =
                repository.findByCaseIdAndRoomType(dispute.getId(), RoomType.INTAKE)
                        .orElseThrow(() -> new IllegalStateException("intake dossier not found"));
        ObjectNode root = readObject(dossier.getDossierJson());
        JsonNode candidate = root.path("case_fact_matrix");
        if (!candidate.isObject()
                || !"case_fact_matrix.v2".equals(
                        candidate.path("schema_version").asText())) {
            return; // Legacy cases keep their existing compatibility projection.
        }
        ObjectNode matrix = ((ObjectNode) candidate).deepCopy();
        String kind = matrix.path("matrix_kind").asText();
        if ("BILATERAL_FROZEN".equals(kind)
                || "RESPONDENT_TIMEOUT_FROZEN".equals(kind)) {
            return;
        }
        if (!"INITIATOR_FROZEN".equals(kind)) {
            throw new IllegalStateException("unsupported case matrix lifecycle state " + kind);
        }
        ObjectNode parent = objectMapper.createObjectNode();
        parent.put("matrix_id", matrix.path("matrix_id").asText());
        parent.put("matrix_version", matrix.path("matrix_version").asInt());
        parent.put("content_hash", matrix.path("content_hash").asText());
        String timeoutRef = "INTAKE_TIMEOUT_" + dispute.getId();
        ActorRole respondent =
                dispute.getInitiatorRole() == ActorRole.USER
                        ? ActorRole.MERCHANT
                        : ActorRole.USER;
        matrix.set("parent_ref", parent);
        matrix.put("matrix_version", matrix.path("matrix_version").asInt() + 1);
        matrix.put(
                "matrix_id",
                "CASE_MATRIX_TIMEOUT_"
                        + parent.path("content_hash").asText().substring(0, 16).toUpperCase());
        matrix.put("matrix_kind", "RESPONDENT_TIMEOUT_FROZEN");
        ArrayNode sourceRefs = (ArrayNode) matrix.withArray("source_refs");
        sourceRefs.add(timeoutRef);
        ObjectNode generation = matrix.withObjectProperty("generation_ref");
        generation.put("actor_role", respondent.name());
        generation.put("source_stage", "RESPONDENT_TIMEOUT");
        generation.put("latest_source_ref", timeoutRef);
        generation.put(
                "source_context_hash",
                sha256(dispute.getId() + ":" + parent.path("content_hash").asText()));

        ArrayNode rows = (ArrayNode) matrix.path("fact_rows");
        for (JsonNode value : rows) {
            ObjectNode row = (ObjectNode) value;
            String initiatorStance =
                    row.path("positions")
                            .path(dispute.getInitiatorRole().name())
                            .path("stance")
                            .asText("NOT_ADDRESSED");
            String status =
                    List.of("CONFIRM", "DENY", "PARTIAL").contains(initiatorStance)
                            ? "ONE_SIDED"
                            : "UNRESOLVED";
            ObjectNode alignment = row.withObjectProperty("party_alignment");
            alignment.put("status", status);
            alignment.putNull("agreed_statement");
            alignment.put("conflict_summary", "被发起方未在统一截止时间前完成接待陈述。");
            row.put("requires_resolution", true);
        }
        matrix.set("fact_indexes", factIndexes(rows));
        matrix.remove("content_hash");
        matrix.put("content_hash", canonicalHash(matrix));
        root.set("case_fact_matrix", matrix);
        String updated = write(root);
        dossier.replaceWith(
                updated,
                dossier.getQualityScore(),
                dossier.isReadyForNextStep(),
                dossier.getAdmissionRecommendation(),
                dossier.getSourceTurnNo(),
                "evidence-deadline");
        repository.save(dossier);
        dispute.refreshIntakeResult(updated, "evidence-deadline");
    }

    private ObjectNode factIndexes(ArrayNode rows) {
        ObjectNode indexes = objectMapper.createObjectNode();
        for (String key : List.of(
                "not_computed_fact_ids",
                "agreed_fact_ids",
                "partially_agreed_fact_ids",
                "contested_fact_ids",
                "one_sided_fact_ids",
                "unresolved_fact_ids",
                "core_fact_ids",
                "requires_resolution_fact_ids")) {
            indexes.putArray(key);
        }
        for (JsonNode row : rows) {
            String id = row.path("fact_id").asText();
            String status = row.path("party_alignment").path("status").asText();
            if ("ONE_SIDED".equals(status)) {
                indexes.withArray("one_sided_fact_ids").add(id);
            } else {
                indexes.withArray("unresolved_fact_ids").add(id);
            }
            if ("CORE".equals(row.path("materiality").asText())) {
                indexes.withArray("core_fact_ids").add(id);
            }
            indexes.withArray("requires_resolution_fact_ids").add(id);
        }
        return indexes;
    }

    private String canonicalHash(JsonNode value) {
        return sha256(canonicalize(value).toString());
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            value.properties().forEach(entry -> names.add(entry.getKey()));
            names.sort(String::compareTo);
            names.forEach(name -> sorted.set(name, canonicalize(value.path(name))));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            value.forEach(item -> array.add(canonicalize(item)));
            return array;
        }
        return value.deepCopy();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private ObjectNode readObject(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            if (!node.isObject()) {
                throw new IllegalStateException("intake dossier must be an object");
            }
            return (ObjectNode) node;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid intake dossier", exception);
        }
    }

    private String write(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize intake dossier", exception);
        }
    }
}
