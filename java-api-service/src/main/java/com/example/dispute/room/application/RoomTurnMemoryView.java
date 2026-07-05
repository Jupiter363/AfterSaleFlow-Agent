package com.example.dispute.room.application;

import com.example.dispute.room.domain.RoomType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

public record RoomTurnMemoryView(
        @JsonProperty("case_id") String caseId,
        @JsonProperty("room_type") RoomType roomType,
        @JsonProperty("turn_no") int turnNo,
        @JsonProperty("agent_role") String agentRole,
        @JsonProperty("agent_response") String agentResponse,
        @JsonProperty("dossier_patch") JsonNode dossierPatch,
        @JsonProperty("scroll_snapshot") JsonNode scrollSnapshot,
        @JsonProperty("canvas_operations") JsonNode canvasOperations,
        @JsonProperty("memory_frame") JsonNode memoryFrame,
        @JsonProperty("created_at") OffsetDateTime createdAt) {}
