package com.example.dispute.room.infrastructure.persistence.entity;

import com.example.dispute.infrastructure.persistence.entity.AbstractEntity;
import com.example.dispute.room.domain.MessageSenderType;
import com.example.dispute.room.domain.MessageType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Immutable;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "room_message")
@Immutable
public class RoomMessageEntity extends AbstractEntity {

    @Column(name = "case_id", length = 64, nullable = false)
    private String caseId;

    @Column(name = "room_id", length = 64, nullable = false)
    private String roomId;

    @Column(name = "sequence_no", nullable = false)
    private long sequenceNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", length = 32, nullable = false)
    private MessageSenderType senderType;

    @Column(name = "sender_role", length = 64, nullable = false)
    private String senderRole;

    @Column(name = "sender_id", length = 128, nullable = false)
    private String senderId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "audience_json", nullable = false, columnDefinition = "jsonb")
    private String audienceJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "audience_actor_ids_json", nullable = false, columnDefinition = "jsonb")
    private String audienceActorIdsJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", length = 64, nullable = false)
    private MessageType messageType;

    @Column(name = "message_text", columnDefinition = "text")
    private String messageText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attachment_refs_json", nullable = false, columnDefinition = "jsonb")
    private String attachmentRefsJson;

    @Column(name = "agent_run_id", length = 64)
    private String agentRunId;

    @Column(name = "hearing_round")
    private Integer hearingRound;

    @Column(name = "idempotency_key", length = 128, nullable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "trace_id", length = 128)
    private String traceId;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    protected RoomMessageEntity() {}

    private RoomMessageEntity(String id) {
        super(id);
    }

    public static RoomMessageEntity create(
            String id,
            String caseId,
            String roomId,
            long sequenceNo,
            MessageSenderType senderType,
            String senderRole,
            String senderId,
            String audienceJson,
            MessageType messageType,
            String messageText,
            String attachmentRefsJson,
            String idempotencyKey,
            Instant createdAt,
            String traceId) {
        return create(
                id,
                caseId,
                roomId,
                sequenceNo,
                senderType,
                senderRole,
                senderId,
                audienceJson,
                "[]",
                messageType,
                messageText,
                attachmentRefsJson,
                idempotencyKey,
                createdAt,
                traceId);
    }

    public static RoomMessageEntity create(
            String id,
            String caseId,
            String roomId,
            long sequenceNo,
            MessageSenderType senderType,
            String senderRole,
            String senderId,
            String audienceJson,
            String audienceActorIdsJson,
            MessageType messageType,
            String messageText,
            String attachmentRefsJson,
            String idempotencyKey,
            Instant createdAt,
            String traceId) {
        RoomMessageEntity entity = new RoomMessageEntity(required(id, "id"));
        entity.caseId = required(caseId, "caseId");
        entity.roomId = required(roomId, "roomId");
        entity.sequenceNo = sequenceNo;
        entity.senderType = Objects.requireNonNull(senderType);
        entity.senderRole = required(senderRole, "senderRole");
        entity.senderId = required(senderId, "senderId");
        entity.audienceJson = required(audienceJson, "audienceJson");
        entity.audienceActorIdsJson = required(audienceActorIdsJson, "audienceActorIdsJson");
        entity.messageType = Objects.requireNonNull(messageType);
        entity.messageText = messageText;
        entity.attachmentRefsJson = required(attachmentRefsJson, "attachmentRefsJson");
        entity.idempotencyKey = required(idempotencyKey, "idempotencyKey");
        entity.createdAt = Objects.requireNonNull(createdAt);
        entity.traceId = traceId;
        entity.createdBy = senderId;
        return entity;
    }

    public String getCaseId() { return caseId; }
    public String getRoomId() { return roomId; }
    public long getSequenceNo() { return sequenceNo; }
    public String getSenderRole() { return senderRole; }
    public String getSenderId() { return senderId; }
    public String getAudienceJson() { return audienceJson; }
    public String getAudienceActorIdsJson() { return audienceActorIdsJson == null ? "[]" : audienceActorIdsJson; }
    public MessageType getMessageType() { return messageType; }
    public String getMessageText() { return messageText; }
    public String getAttachmentRefsJson() { return attachmentRefsJson; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
