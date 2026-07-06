package com.example.dispute.room.application;

import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.RoomType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record EvidenceAgentTurnCommand(
        @JsonProperty("case_id") String caseId,
        @JsonProperty("room_type") RoomType roomType,
        @JsonProperty("turn_source") String turnSource,
        @JsonProperty("actor_role") String actorRole,
        @JsonProperty("actor_id") String actorId,
        @JsonProperty("current_party_message") Message currentMessage,
        @JsonProperty("case_intake_dossier") JsonNode latestCaseIntakeDossier,
        @JsonProperty("available_evidence") List<AvailableEvidence> availableEvidence,
        @JsonProperty("recent_turns") List<IntakeRecentTurn> recentTurns) {

    public EvidenceAgentTurnCommand {
        availableEvidence =
                availableEvidence == null ? List.of() : List.copyOf(availableEvidence);
        recentTurns = recentTurns == null ? List.of() : List.copyOf(recentTurns);
    }

    public record Message(
            @JsonProperty("message_id") String messageId,
            @JsonProperty("message_type") MessageType messageType,
            String role,
            String text,
            @JsonProperty("attachment_refs") List<String> attachmentRefs) {

        public Message {
            attachmentRefs = attachmentRefs == null ? List.of() : List.copyOf(attachmentRefs);
        }
    }

    public record AvailableEvidence(
            @JsonProperty("evidence_id") String evidenceId,
            @JsonProperty("evidence_type") String evidenceType,
            @JsonProperty("source_type") String sourceType,
            String content,
            @JsonProperty("parsed_text") String parsedText,
            @JsonProperty("occurred_at") String occurredAt,
            @JsonProperty("submitted_by_role") String submittedByRole,
            String visibility,
            @JsonProperty("content_url") String contentUrl,
            boolean redacted,
            @JsonProperty("parse_status") String parseStatus,
            @JsonProperty("original_filename") String originalFilename) {}
}
