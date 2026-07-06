package com.example.dispute.room.application;

import com.example.dispute.room.domain.RoomType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record IntakeAgentTurnCommand(
        @JsonProperty("case_id") String caseId,
        @JsonProperty("room_type") RoomType roomType,
        @JsonProperty("turn_source") String turnSource,
        @JsonProperty("lobby_seed") IntakeLobbySeed lobbySeed,
        @JsonProperty("current_user_message") IntakeParticipantMessage currentUserMessage,
        @JsonProperty("latest_scroll_snapshot") JsonNode latestScrollSnapshot,
        @JsonProperty("recent_turns") List<IntakeRecentTurn> recentTurns,
        @JsonProperty("agent_context") AgentInvocationContext agentContext) {

    public IntakeAgentTurnCommand {
        recentTurns = recentTurns == null ? List.of() : List.copyOf(recentTurns);
    }
}
