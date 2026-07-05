package com.example.dispute.room.application;

import com.example.dispute.room.domain.RoomType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

public record CaseIntakeDossierView(
        @JsonProperty("case_id") String caseId,
        @JsonProperty("room_type") RoomType roomType,
        @JsonProperty("dossier_version") int dossierVersion,
        @JsonProperty("dossier") JsonNode dossier,
        @JsonProperty("quality_score") int qualityScore,
        @JsonProperty("ready_for_next_step") boolean readyForNextStep,
        @JsonProperty("admission_recommendation") String admissionRecommendation,
        @JsonProperty("source_turn_no") int sourceTurnNo,
        @JsonProperty("updated_at") OffsetDateTime updatedAt) {}

