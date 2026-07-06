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
        @JsonProperty("liability_determined") boolean liabilityDetermined,
        @JsonProperty("remedy_recommended") boolean remedyRecommended,
        @JsonProperty("knowledge_answer_mode") String knowledgeAnswerMode,
        double confidence) {

    public EvidenceAgentTurnResult {
        memoryPatch = memoryPatch == null ? JsonNodeFactory.instance.objectNode() : memoryPatch;
        canvasOperations =
                canvasOperations == null ? JsonNodeFactory.instance.arrayNode() : canvasOperations;
        referencedEvidenceIds =
                referencedEvidenceIds == null ? List.of() : List.copyOf(referencedEvidenceIds);
        knowledgeAnswerMode = knowledgeAnswerMode == null ? "NONE" : knowledgeAnswerMode;
    }
}
