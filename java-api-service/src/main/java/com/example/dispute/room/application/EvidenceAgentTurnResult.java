package com.example.dispute.room.application;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;

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
        knowledgeAnswerMode = knowledgeAnswerMode == null ? "NONE" : knowledgeAnswerMode;
    }
}
