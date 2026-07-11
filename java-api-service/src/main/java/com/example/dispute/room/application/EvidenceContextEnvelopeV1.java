package com.example.dispute.room.application;

import com.example.dispute.room.domain.MessageType;
import com.example.dispute.room.domain.RoomType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

/**
 * Versioned, authorization-filtered evidence context supplied by the Java business boundary.
 *
 * <p>This envelope contains persisted facts and raw participant data only. Prompt construction,
 * semantic fact mapping, evidence-gap analysis, and text fallbacks belong to the Python harness.
 */
public record EvidenceContextEnvelopeV1(
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("captured_at") String capturedAt,
        @JsonProperty("case_snapshot") CaseSnapshot caseSnapshot,
        @JsonProperty("intake_dossier_snapshot") IntakeDossierSnapshot intakeDossierSnapshot,
        @JsonProperty("actor_snapshot") ActorSnapshot actorSnapshot,
        @JsonProperty("current_event") CurrentEvent currentEvent,
        @JsonProperty("visible_evidence") List<VisibleEvidence> visibleEvidence,
        @JsonProperty("private_conversation") PrivateConversation privateConversation,
        @JsonProperty("room_policy") RoomPolicy roomPolicy) {

    public static final String SCHEMA_VERSION = "evidence_context_envelope.v1";

    public EvidenceContextEnvelopeV1 {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported evidence context envelope schema");
        }
        Objects.requireNonNull(capturedAt, "capturedAt must not be null");
        Objects.requireNonNull(caseSnapshot, "caseSnapshot must not be null");
        Objects.requireNonNull(actorSnapshot, "actorSnapshot must not be null");
        Objects.requireNonNull(currentEvent, "currentEvent must not be null");
        Objects.requireNonNull(privateConversation, "privateConversation must not be null");
        Objects.requireNonNull(roomPolicy, "roomPolicy must not be null");
        visibleEvidence = visibleEvidence == null ? List.of() : List.copyOf(visibleEvidence);
    }

    public record CaseSnapshot(
            @JsonProperty("case_id") String caseId,
            @JsonProperty("case_version") long caseVersion,
            @JsonProperty("case_status") String caseStatus,
            @JsonProperty("case_type") String caseType,
            @JsonProperty("dispute_type") String disputeType,
            @JsonProperty("initiator_role") String initiatorRole,
            String title,
            String description,
            @JsonProperty("risk_level") String riskLevel,
            @JsonProperty("route_type") String routeType,
            @JsonProperty("order_id") String orderId,
            @JsonProperty("after_sale_id") String afterSaleId,
            @JsonProperty("logistics_id") String logisticsId,
            @JsonProperty("source_type") String sourceType,
            @JsonProperty("source_system") String sourceSystem,
            @JsonProperty("external_case_ref") String externalCaseRef,
            @JsonProperty("current_room") String currentRoom,
            @JsonProperty("current_deadline_at") String currentDeadlineAt) {}

    public record IntakeDossierSnapshot(
            @JsonProperty("dossier_id") String dossierId,
            @JsonProperty("schema_version") String schemaVersion,
            @JsonProperty("dossier_version") int dossierVersion,
            @JsonProperty("source_turn_no") int sourceTurnNo,
            @JsonProperty("quality_score") int qualityScore,
            @JsonProperty("ready_for_next_step") boolean readyForNextStep,
            @JsonProperty("admission_recommendation") String admissionRecommendation,
            @JsonProperty("updated_at") String updatedAt,
            JsonNode payload) {}

    public record ActorSnapshot(
            @JsonProperty("actor_id") String actorId,
            @JsonProperty("actor_role") String actorRole,
            @JsonProperty("initiator_role") String initiatorRole,
            @JsonProperty("access_session_id") String accessSessionId,
            @JsonProperty("agent_session_id") String agentSessionId,
            @JsonProperty("conversation_scope") String conversationScope,
            @JsonProperty("prompt_profile_id") String promptProfileId,
            @JsonProperty("memory_policy_id") String memoryPolicyId) {}

    public record CurrentEvent(
            @JsonProperty("event_id") String eventId,
            @JsonProperty("event_type") String eventType,
            @JsonProperty("message_type") MessageType messageType,
            @JsonProperty("actor_id") String actorId,
            @JsonProperty("actor_role") String actorRole,
            String text,
            @JsonProperty("attachment_refs") List<String> attachmentRefs,
            @JsonProperty("turn_no") int turnNo,
            @JsonProperty("occurred_at") String occurredAt) {

        public CurrentEvent {
            attachmentRefs = attachmentRefs == null ? List.of() : List.copyOf(attachmentRefs);
            Objects.requireNonNull(eventId, "eventId must not be null");
            Objects.requireNonNull(eventType, "eventType must not be null");
            Objects.requireNonNull(messageType, "messageType must not be null");
            Objects.requireNonNull(actorId, "actorId must not be null");
            Objects.requireNonNull(actorRole, "actorRole must not be null");
            Objects.requireNonNull(occurredAt, "occurredAt must not be null");
            if (turnNo < 1) {
                throw new IllegalArgumentException("turnNo must be positive");
            }
            if ("ROOM_OPENING".equals(eventType)
                    && (messageType != MessageType.AGENT_MESSAGE
                            || text != null
                            || !attachmentRefs.isEmpty())) {
                throw new IllegalArgumentException("room opening must not contain party content");
            }
            if ("PARTY_MESSAGE".equals(eventType)
                    && messageType != MessageType.PARTY_TEXT
                    && messageType != MessageType.PARTY_EVIDENCE_REFERENCE) {
                throw new IllegalArgumentException("party event has an invalid message type");
            }
        }
    }

    public record VisibleEvidence(
            @JsonProperty("evidence_id") String evidenceId,
            @JsonProperty("dossier_id") String dossierId,
            @JsonProperty("evidence_type") String evidenceType,
            @JsonProperty("source_type") String sourceType,
            @JsonProperty("submitted_by_role") String submittedByRole,
            @JsonProperty("submitted_by_id") String submittedById,
            @JsonProperty("original_filename") String originalFilename,
            @JsonProperty("content_type") String contentType,
            @JsonProperty("file_size") Long fileSize,
            @JsonProperty("file_hash") String fileHash,
            @JsonProperty("parsed_text") String parsedText,
            @JsonProperty("parse_status") String parseStatus,
            String visibility,
            boolean desensitized,
            JsonNode metadata,
            JsonNode extraction,
            @JsonProperty("occurred_at") String occurredAt,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("submitted_at") String submittedAt,
            @JsonProperty("submission_status") String submissionStatus,
            @JsonProperty("submission_batch_id") String submissionBatchId,
            @JsonProperty("content_url") String contentUrl) {}

    public record PrivateConversation(
            @JsonProperty("agent_session_id") String agentSessionId,
            @JsonProperty("conversation_scope") String conversationScope,
            @JsonProperty("source_count") int sourceCount,
            boolean truncated,
            @JsonProperty("recent_turns") List<IntakeRecentTurn> recentTurns) {

        public PrivateConversation {
            recentTurns = recentTurns == null ? List.of() : List.copyOf(recentTurns);
            if (sourceCount < recentTurns.size()) {
                throw new IllegalArgumentException("sourceCount cannot be smaller than recentTurns");
            }
        }
    }

    public record RoomPolicy(
            @JsonProperty("room_id") String roomId,
            @JsonProperty("room_type") RoomType roomType,
            @JsonProperty("room_status") String roomStatus,
            @JsonProperty("current_deadline_at") String currentDeadlineAt,
            @JsonProperty("initiator_role") String initiatorRole,
            @JsonProperty("initiator_evidence_required") boolean initiatorEvidenceRequired) {}
}
