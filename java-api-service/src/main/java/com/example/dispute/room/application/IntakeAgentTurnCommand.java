package com.example.dispute.room.application;

import com.example.dispute.room.domain.RoomType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record IntakeAgentTurnCommand(
        @JsonProperty("case_id") String caseId,
        @JsonProperty("room_type") RoomType roomType,
        @JsonProperty("turn_source") String turnSource,
        @JsonProperty("initial_case_facts") IntakeInitialCaseFacts initialCaseFacts,
        @JsonProperty("current_user_message") IntakeDialogueMessage currentUserMessage,
        @JsonProperty("recent_dialogue_messages")
                List<IntakeDialogueMessage> recentDialogueMessages,
        @JsonProperty("previous_case_detail") JsonNode previousCaseDetail,
        @JsonProperty("initiator_statement_transcript")
                List<IntakeParticipantMessage> initiatorStatementTranscript,
        @JsonProperty("agent_context") AgentInvocationContext agentContext) {

    public IntakeAgentTurnCommand {
        recentDialogueMessages =
                recentDialogueMessages == null ? List.of() : List.copyOf(recentDialogueMessages);
        initiatorStatementTranscript =
                initiatorStatementTranscript == null
                        ? List.of()
                        : List.copyOf(initiatorStatementTranscript);
    }
}
