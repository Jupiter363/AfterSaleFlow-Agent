package com.example.dispute.hearing.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import org.erdtman.jcs.JsonCanonicalizer;

/** Canonical JSON validation shared by formal Hearing commands. */
final class HearingFormalPayload {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> DOSSIER_V2_FIELDS =
            Set.of(
                    "schema_version",
                    "trial_dossier_id",
                    "case_id",
                    "frozen_at",
                    "case_matrix_version",
                    "case_matrix_hash",
                    "case_fact_matrix",
                    "evidence_matrix_version",
                    "evidence_matrix_hash",
                    "fact_evidence_matrix",
                    "adjudication_rules",
                    "content_hash");

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
        if ("hearing_question_set.v4".equals(schemaVersion)) {
            requireText(payload, "question_set_hash", contentHash);
            requireHashWithoutField(
                    payload, "question_set_hash", contentHash, "question set");
        } else if ("hearing_answer_bundle.v4".equals(schemaVersion)) {
            requireText(payload, "answer_bundle_hash", contentHash);
            requireHashWithoutField(
                    payload, "answer_bundle_hash", contentHash, "answer bundle");
            requireText(payload, "submission_status", "SUBMITTED");
        } else if (!sha256(canonicalJson(payload)).equals(contentHash)) {
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
        requireText(payload, "schema_version", "trial_dossier.v2");
        requireText(payload, "trial_dossier_id", dossierId);
        requireText(payload, "case_id", caseId);
        requireText(payload, "frozen_at", frozenAt.toString());
        requireText(payload, "content_hash", contentHash);
        requireInteger(payload, "case_matrix_version", caseMatrixVersion);
        requireText(payload, "case_matrix_hash", caseMatrixHash);
        requireInteger(payload, "evidence_matrix_version", evidenceMatrixVersion);
        requireText(payload, "evidence_matrix_hash", evidenceMatrixHash);
        if (!fieldNames(payload).equals(DOSSIER_V2_FIELDS)
                || !payload.path("adjudication_rules").isArray()
                || payload.path("adjudication_rules").isEmpty()
                || !(payload.path("case_fact_matrix") instanceof ObjectNode caseMatrix)
                || !(payload.path("fact_evidence_matrix") instanceof ObjectNode evidenceMatrix)) {
            throw new IllegalArgumentException(
                    "trial_dossier.v2 must contain only frozen matrices and adjudication rules");
        }
        requireText(caseMatrix, "case_id", caseId);
        requireInteger(caseMatrix, "matrix_version", caseMatrixVersion);
        requireText(caseMatrix, "content_hash", caseMatrixHash);
        if (!caseMatrix.path("matrix_id").isTextual()
                || caseMatrix.path("matrix_id").asText().isBlank()) {
            throw new IllegalArgumentException("case matrix_id is absent");
        }
        String caseMatrixId = caseMatrix.path("matrix_id").asText();
        requireText(evidenceMatrix, "case_id", caseId);
        requireInteger(evidenceMatrix, "matrix_version", evidenceMatrixVersion);
        requireText(evidenceMatrix, "content_hash", evidenceMatrixHash);
        requireText(evidenceMatrix, "matrix_status", "FROZEN");
        requireText(
                evidenceMatrix,
                "case_fact_matrix_id",
                caseMatrixId);
        requireInteger(evidenceMatrix, "case_fact_matrix_version", caseMatrixVersion);
        requireText(evidenceMatrix, "case_fact_matrix_hash", caseMatrixHash);
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
            JsonNode draft = payload.path("draft");
            if (!payload.path("public_text").isTextual()
                    || payload.path("public_text").asText().isBlank()
                    || !structuredDraft(draft)
                    || !payload.path("review_responses").isArray()
                    || payload.path("review_responses").isEmpty()) {
                throw new IllegalArgumentException(
                        "Judge V2 artifact must contain the structured draft and review responses");
            }
        } else if (artifactType == HearingArtifactType.JUDGE_PROPOSAL) {
            JsonNode source = payload.path("proposal");
            if (!source.isObject()
                    || !"hearing_judge_v1.v2".equals(
                            source.path("schema_version").asText())
                    || !structuredDraft(source.path("draft"))
                    || !source.path("review_focus").isArray()
                    || source.path("review_focus").isEmpty()
                    || !source.path("public_message").isTextual()
                    || source.path("public_message").asText().isBlank()
                    || source.path("is_final_decision").asBoolean(true)) {
                throw new IllegalArgumentException(
                        "Judge V1 artifact must contain the structured draft and review focus");
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
        if (kind == HearingFormalFinalizer.MatrixKind.INTAKE) {
            requireHashWithoutField(
                    matrix,
                    "content_hash",
                    matrix.path("content_hash").asText(),
                    "case matrix");
            if (!(payload.path("issue_transition_set") instanceof ObjectNode transitions)
                    || !(payload.path("issue_state_set") instanceof ObjectNode states)) {
                throw new IllegalArgumentException(
                        "intake synthesis issue authorities must be JSON objects");
            }
            requireText(
                    transitions, "schema_version", "hearing_issue_transition_set.v4");
            requireHashWithoutField(
                    transitions,
                    "transition_hash",
                    transitions.path("transition_hash").asText(),
                    "issue transition set");
            requireText(states, "schema_version", "hearing_issue_state_set.v4");
            requireHashWithoutField(
                    states,
                    "content_hash",
                    states.path("content_hash").asText(),
                    "issue state set");
        }
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

    private static boolean structuredDraft(JsonNode draft) {
        return draft.isObject()
                && draft.path("decision_action").isTextual()
                && HearingDecisionAction.supports(draft.path("decision_action").asText())
                && draft.path("remedy_orders").isArray()
                && !draft.path("remedy_orders").isEmpty()
                && draft.path("fact_findings").isArray()
                && !draft.path("fact_findings").isEmpty()
                && draft.path("rule_applications").isArray()
                && !draft.path("rule_applications").isEmpty()
                && draft.path("decision_reasoning").isTextual()
                && !draft.path("decision_reasoning").asText().isBlank()
                && draft.path("reviewer_attention").isArray();
    }

    private static Set<String> fieldNames(ObjectNode payload) {
        Set<String> fields = new java.util.HashSet<>();
        payload.fieldNames().forEachRemaining(fields::add);
        return Set.copyOf(fields);
    }

    private static String canonicalJson(JsonNode value) {
        try {
            String json = MAPPER.writeValueAsString(value);
            return new String(new JsonCanonicalizer(json).getEncodedUTF8(), StandardCharsets.UTF_8);
        } catch (IOException impossible) {
            throw new IllegalArgumentException("formal payload cannot be canonicalized", impossible);
        }
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
