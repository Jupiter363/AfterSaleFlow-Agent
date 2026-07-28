package com.example.dispute.hearing.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/** Canonical JSON validation shared by formal Hearing commands. */
final class HearingFormalPayload {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HearingFormalPayload() {}

    static ObjectNode requireAction(
            String payloadJson,
            String schemaVersion,
            String contentHash,
            String participantId,
            String participantRole,
            HearingFlowSubmissionStatus submissionStatus) {
        ObjectNode payload = object(payloadJson);
        requireText(payload, "schema_version", schemaVersion);
        if (!sha256(canonicalJson(payload)).equals(contentHash)) {
            throw new IllegalArgumentException("action contentHash is not canonical");
        }
        if (participantId != null && payload.has("participant_id")) {
            requireText(payload, "participant_id", participantId);
        }
        if (participantRole != null) {
            requireText(payload, "participant_role", participantRole);
            requireText(payload, "submission_status", submissionStatus.name());
        }
        return payload;
    }

    static ObjectNode requireDossier(
            String payloadJson,
            String dossierId,
            String caseId,
            String contentHash,
            int caseMatrixVersion,
            String caseMatrixHash,
            int evidenceMatrixVersion,
            String evidenceMatrixHash,
            String questionSetId,
            String requestSetId,
            Instant frozenAt) {
        ObjectNode payload = object(payloadJson);
        requireText(payload, "schema_version", "trial_dossier.v1");
        requireText(payload, "trial_dossier_id", dossierId);
        requireText(payload, "case_id", caseId);
        requireText(payload, "frozen_at", frozenAt.toString());
        requireText(payload, "content_hash", contentHash);
        requireInteger(payload, "case_matrix_version", caseMatrixVersion);
        requireText(payload, "case_matrix_hash", caseMatrixHash);
        requireInteger(payload, "evidence_matrix_version", evidenceMatrixVersion);
        requireText(payload, "evidence_matrix_hash", evidenceMatrixHash);
        requireText(payload, "question_set_id", questionSetId);
        requireText(payload, "request_set_id", requestSetId);
        requireHashWithoutField(payload, "content_hash", contentHash, "dossier");
        return payload;
    }

    static ObjectNode requireDecision(
            String payloadJson,
            HearingArtifactType artifactType,
            String artifactId,
            String contentHash,
            String dossierId,
            String dossierHash,
            String proposalId,
            String proposalHash,
            String reportId,
            String reportHash) {
        ObjectNode payload = object(payloadJson);
        requireText(payload, "schema_version", artifactType.schemaVersion());
        requireText(payload, idField(artifactType), artifactId);
        requireText(payload, "content_hash", contentHash);
        requireText(payload, "trial_dossier_id", dossierId);
        requireText(payload, "trial_dossier_hash", dossierHash);
        if (artifactType != HearingArtifactType.JUDGE_PROPOSAL) {
            requireText(payload, "proposal_id", proposalId);
            requireText(payload, "proposal_content_hash", proposalHash);
        }
        if (artifactType == HearingArtifactType.ADJUDICATION_DRAFT) {
            requireText(payload, "report_id", reportId);
            requireText(payload, "report_content_hash", reportHash);
            if (!payload.path("public_text").isTextual()
                    || !payload.path("draft").path("draft_text").isTextual()
                    || !payload.path("public_text").asText()
                            .equals(payload.path("draft").path("draft_text").asText())) {
                throw new IllegalArgumentException(
                        "displayed Judge V2 text must equal the frozen draft text");
            }
        }
        requireHashWithoutField(payload, "content_hash", contentHash, "decision artifact");
        return payload;
    }

    static ObjectNode object(String json) {
        try {
            JsonNode parsed = MAPPER.readTree(json);
            if (!(parsed instanceof ObjectNode object)) {
                throw new IllegalArgumentException("formal payload must be a JSON object");
            }
            return object;
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("formal payload is not valid JSON", failure);
        }
    }

    static String canonicalJson(String json) {
        return canonicalJson(object(json));
    }

    static String hashCanonical(String json) {
        return sha256(canonicalJson(json));
    }

    static ObjectNode requireMatrixSynthesis(
            String payloadJson,
            HearingFormalFinalizer.MatrixKind kind,
            String contentHash) {
        ObjectNode payload = object(payloadJson);
        requireText(payload, "schema_version", kind.schemaVersion());
        JsonNode matrixNode = payload.path(kind.matrixField());
        if (!(matrixNode instanceof ObjectNode matrix)) {
            throw new IllegalArgumentException("matrix synthesis matrix must be a JSON object");
        }
        requireText(matrix, "schema_version", kind.matrixSchemaVersion());
        if (!hashCanonical(payloadJson).equals(contentHash)) {
            throw new IllegalArgumentException("matrix synthesis contentHash is not canonical");
        }
        return payload;
    }

    private static void requireHashWithoutField(
            ObjectNode payload, String field, String expected, String label) {
        ObjectNode copy = payload.deepCopy();
        copy.remove(field);
        if (!sha256(canonicalJson(copy)).equals(expected)) {
            throw new IllegalArgumentException(label + " contentHash is not canonical");
        }
    }

    private static String idField(HearingArtifactType artifactType) {
        return switch (artifactType) {
            case JUDGE_PROPOSAL -> "proposal_id";
            case JURY_REVIEW_REPORT -> "report_id";
            case ADJUDICATION_DRAFT -> "draft_id";
        };
    }

    private static void requireText(ObjectNode payload, String field, String expected) {
        if (!payload.path(field).isTextual() || !expected.equals(payload.path(field).asText())) {
            throw new IllegalArgumentException(field + " does not match the formal command");
        }
    }

    private static void requireInteger(ObjectNode payload, String field, int expected) {
        if (!payload.path(field).canConvertToInt() || payload.path(field).asInt() != expected) {
            throw new IllegalArgumentException(field + " does not match the formal command");
        }
    }

    private static String canonicalJson(JsonNode value) {
        try {
            return MAPPER.writeValueAsString(canonicalNode(value));
        } catch (JsonProcessingException impossible) {
            throw new IllegalArgumentException("formal payload cannot be canonicalized", impossible);
        }
    }

    private static JsonNode canonicalNode(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = MAPPER.createObjectNode();
            Map<String, JsonNode> fields = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> iterator = value.fields();
            iterator.forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((name, child) -> sorted.set(name, canonicalNode(child)));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode array = MAPPER.createArrayNode();
            value.forEach(child -> array.add(canonicalNode(child)));
            return array;
        }
        return value.deepCopy();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
