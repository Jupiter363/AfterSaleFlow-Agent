package com.example.dispute.room.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public record IntakeRecentTurn(
        @JsonProperty("turn_no") int turnNo,
        @JsonProperty("actor_id") String actorId,
        @JsonProperty("answer_role") String answerRole,
        @JsonProperty("answer_content") String answerContent,
        @JsonProperty("agent_role") String agentRole,
        @JsonProperty("agent_response") String agentResponse,
        @JsonProperty("scroll_snapshot") JsonNode scrollSnapshot) {}
