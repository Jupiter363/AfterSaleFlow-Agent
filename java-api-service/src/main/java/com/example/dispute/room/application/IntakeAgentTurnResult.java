package com.example.dispute.room.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record IntakeAgentTurnResult(
        @JsonProperty("room_utterance") String roomUtterance,
        @JsonProperty("dossier_patch") JsonNode dossierPatch,
        @JsonProperty("scroll_snapshot") JsonNode scrollSnapshot,
        @JsonProperty("canvas_operations") JsonNode canvasOperations,
        @JsonProperty("memory_frame") JsonNode memoryFrame,
        @JsonProperty("admission_recommendation") String admissionRecommendation,
        @JsonProperty("missing_fields") List<String> missingFields,
        @JsonProperty("knowledge_query_intent") boolean knowledgeQueryIntent,
        @JsonProperty("knowledge_answer_mode") String knowledgeAnswerMode,
        double confidence) {

    public IntakeAgentTurnResult {
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
    }
}
