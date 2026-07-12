package com.example.dispute.room.application;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EvidenceAgentTurnResult(
        @JsonProperty("room_utterance") String roomUtterance,
        @JsonAlias("memory_frame")
        @JsonProperty("memory_patch") JsonNode memoryPatch,
        @JsonProperty("canvas_operations") JsonNode canvasOperations,
        @JsonProperty("referenced_evidence_ids") List<String> referencedEvidenceIds,
        @JsonProperty("verification_suggestions")
                List<EvidenceVerificationSuggestion> verificationSuggestions,
        @JsonProperty("authenticity_flags") List<EvidenceAuthenticityFlag> authenticityFlags,
        @JsonProperty("evidence_assessments") List<EvidenceAssessment> evidenceAssessments,
        @JsonProperty("liability_determined") boolean liabilityDetermined,
        @JsonProperty("remedy_recommended") boolean remedyRecommended,
        @JsonProperty("knowledge_answer_mode") String knowledgeAnswerMode,
        double confidence) {

    public record EvidenceVerificationSuggestion(
            @JsonProperty("evidence_id") String evidenceId,
            @JsonProperty("suggestion") String suggestion,
            @JsonProperty("confidence_score") double confidenceScore) {}

    public record EvidenceAuthenticityFlag(
            @JsonProperty("evidence_id") String evidenceId,
            @JsonProperty("flag_type") String flagType,
            @JsonProperty("description") String description,
            @JsonProperty("severity") String severity) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EvidenceAssessment(
            @JsonProperty("evidence_id") String evidenceId,
            @JsonProperty("analysis_method") String analysisMethod,
            @JsonProperty("inspected_modalities") List<String> inspectedModalities,
            @JsonProperty("fact_links") List<Map<String, Object>> factLinks,
            @JsonProperty("authenticity_score") double authenticityScore,
            @JsonProperty("relevance_score") double relevanceScore,
            @JsonProperty("completeness_score") double completenessScore,
            @JsonProperty("assessment_confidence") double assessmentConfidence,
            List<Map<String, Object>> findings,
            List<String> limitations,
            @JsonProperty("risk_flags") List<Map<String, Object>> riskFlags,
            String recommendation,
            @JsonProperty("human_review") HumanReview humanReview,
            @JsonProperty("asset_audit") Map<String, Object> assetAudit,
            String summary) {

        public EvidenceAssessment {
            inspectedModalities = immutableList(inspectedModalities);
            factLinks = immutableList(factLinks);
            findings = immutableList(findings);
            limitations = immutableList(limitations);
            riskFlags = immutableList(riskFlags);
            assetAudit = assetAudit == null ? Map.of() : Map.copyOf(assetAudit);
            humanReview = humanReview == null ? HumanReview.notRequired() : humanReview;
            validateScore(authenticityScore, "authenticity_score");
            validateScore(relevanceScore, "relevance_score");
            validateScore(completenessScore, "completeness_score");
            validateScore(assessmentConfidence, "assessment_confidence");
            if (!List.of("TEXT_ONLY", "MULTIMODAL", "HYBRID").contains(analysisMethod)) {
                throw new IllegalArgumentException("invalid evidence assessment analysis_method");
            }
            if (!List.of("PLAUSIBLE", "SUSPICIOUS", "NEEDS_HUMAN_REVIEW")
                    .contains(recommendation)) {
                throw new IllegalArgumentException("invalid evidence assessment recommendation");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HumanReview(
            boolean required,
            @JsonProperty("reason_codes") List<String> reasonCodes,
            List<String> instructions) {

        public HumanReview {
            reasonCodes = immutableList(reasonCodes);
            instructions = immutableList(instructions);
        }

        private static HumanReview notRequired() {
            return new HumanReview(false, List.of(), List.of());
        }
    }

    public EvidenceAgentTurnResult(
            String roomUtterance,
            JsonNode memoryPatch,
            JsonNode canvasOperations,
            List<String> referencedEvidenceIds,
            boolean liabilityDetermined,
            boolean remedyRecommended,
            String knowledgeAnswerMode,
            double confidence) {
        this(
                roomUtterance,
                memoryPatch,
                canvasOperations,
                referencedEvidenceIds,
                List.of(),
                List.of(),
                List.of(),
                liabilityDetermined,
                remedyRecommended,
                knowledgeAnswerMode,
                confidence);
    }

    public EvidenceAgentTurnResult {
        memoryPatch = memoryPatch == null ? JsonNodeFactory.instance.objectNode() : memoryPatch;
        canvasOperations =
                canvasOperations == null ? JsonNodeFactory.instance.arrayNode() : canvasOperations;
        referencedEvidenceIds =
                referencedEvidenceIds == null ? List.of() : List.copyOf(referencedEvidenceIds);
        verificationSuggestions =
                verificationSuggestions == null ? List.of() : List.copyOf(verificationSuggestions);
        authenticityFlags = authenticityFlags == null ? List.of() : List.copyOf(authenticityFlags);
        evidenceAssessments =
                evidenceAssessments == null ? List.of() : List.copyOf(evidenceAssessments);
        knowledgeAnswerMode = knowledgeAnswerMode == null ? "NONE" : knowledgeAnswerMode;
    }

    public EvidenceAgentTurnResult(
            String roomUtterance,
            JsonNode memoryPatch,
            JsonNode canvasOperations,
            List<String> referencedEvidenceIds,
            List<EvidenceVerificationSuggestion> verificationSuggestions,
            List<EvidenceAuthenticityFlag> authenticityFlags,
            boolean liabilityDetermined,
            boolean remedyRecommended,
            String knowledgeAnswerMode,
            double confidence) {
        this(
                roomUtterance,
                memoryPatch,
                canvasOperations,
                referencedEvidenceIds,
                verificationSuggestions,
                authenticityFlags,
                List.of(),
                liabilityDetermined,
                remedyRecommended,
                knowledgeAnswerMode,
                confidence);
    }

    private static void validateScore(double score, String field) {
        if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException(field + " must be between 0 and 1");
        }
    }

    private static <T> List<T> immutableList(List<T> value) {
        return value == null ? List.of() : List.copyOf(value);
    }
}
