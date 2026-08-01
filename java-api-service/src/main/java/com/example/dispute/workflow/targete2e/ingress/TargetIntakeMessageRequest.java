package com.example.dispute.workflow.targete2e.ingress;

import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.room.domain.MessageType;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

public record TargetIntakeMessageRequest(
        String caseId,
        String roomId,
        String messageId,
        MessageType messageType,
        String text,
        List<String> attachmentRefs,
        AuthenticatedActor actor,
        String idempotencyKey,
        String traceId,
        Instant createdAt,
        TargetIntakeActivationGrant activation,
        SourceType sourceType) {

    private static final Duration COMMAND_DEADLINE = Duration.ofHours(1);

    public TargetIntakeMessageRequest {
        requireText(caseId, "caseId", 128);
        requireText(roomId, "roomId", 128);
        requireText(messageId, "messageId", 128);
        attachmentRefs = List.copyOf(Objects.requireNonNull(attachmentRefs, "attachmentRefs"));
        Objects.requireNonNull(actor, "actor must not be null");
        requireText(idempotencyKey, "idempotencyKey", 256);
        requireText(traceId, "traceId", 256);
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(activation, "activation must not be null");
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        if (!caseId.equals(activation.caseId())) {
            throw new IllegalArgumentException("message case does not match activation");
        }
        if (sourceType == SourceType.INITIAL_FORM) {
            if (messageType != null || !attachmentRefs.isEmpty()) {
                throw new IllegalArgumentException(
                        "INITIAL_FORM must not masquerade as a persisted room message");
            }
        } else {
            Objects.requireNonNull(messageType, "messageType must not be null");
        }
    }

    /** Source-compatible constructor for ordinary persisted room messages. */
    public TargetIntakeMessageRequest(
            String caseId,
            String roomId,
            String messageId,
            MessageType messageType,
            String text,
            List<String> attachmentRefs,
            AuthenticatedActor actor,
            String idempotencyKey,
            String traceId,
            Instant createdAt,
            TargetIntakeActivationGrant activation) {
        this(
                caseId,
                roomId,
                messageId,
                messageType,
                text,
                attachmentRefs,
                actor,
                idempotencyKey,
                traceId,
                createdAt,
                activation,
                SourceType.ROOM_MESSAGE);
    }

    public static TargetIntakeMessageRequest roomMessage(
            String caseId,
            String roomId,
            String messageId,
            MessageType messageType,
            String text,
            List<String> attachmentRefs,
            AuthenticatedActor actor,
            String idempotencyKey,
            String traceId,
            Instant createdAt,
            TargetIntakeActivationGrant activation) {
        return new TargetIntakeMessageRequest(
                caseId,
                roomId,
                messageId,
                messageType,
                text,
                attachmentRefs,
                actor,
                idempotencyKey,
                traceId,
                createdAt,
                activation,
                SourceType.ROOM_MESSAGE);
    }

    public static TargetIntakeMessageRequest initialForm(
            String caseId,
            String roomId,
            String formSourceId,
            String text,
            AuthenticatedActor actor,
            String idempotencyKey,
            String traceId,
            Instant occurredAt,
            TargetIntakeActivationGrant activation) {
        return new TargetIntakeMessageRequest(
                caseId,
                roomId,
                formSourceId,
                null,
                text,
                List.of(),
                actor,
                idempotencyKey,
                traceId,
                occurredAt,
                activation,
                SourceType.INITIAL_FORM);
    }

    public Instant commandDeadlineAt() {
        Instant deadline =
                sourceType == SourceType.INITIAL_FORM
                        ? activation.expiresAt()
                        : createdAt.plus(COMMAND_DEADLINE);
        return deadline.truncatedTo(ChronoUnit.MICROS);
    }

    public enum SourceType {
        INITIAL_FORM,
        ROOM_MESSAGE
    }

    private static void requireText(String value, String field, int maximumLength) {
        if (value == null
                || value.isBlank()
                || value.length() > maximumLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }
}
