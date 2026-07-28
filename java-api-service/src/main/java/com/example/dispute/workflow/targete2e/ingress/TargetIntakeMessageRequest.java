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
        TargetIntakeActivationGrant activation) {

    private static final Duration COMMAND_DEADLINE = Duration.ofHours(1);

    public TargetIntakeMessageRequest {
        requireText(caseId, "caseId", 128);
        requireText(roomId, "roomId", 128);
        requireText(messageId, "messageId", 128);
        Objects.requireNonNull(messageType, "messageType must not be null");
        attachmentRefs = List.copyOf(Objects.requireNonNull(attachmentRefs, "attachmentRefs"));
        Objects.requireNonNull(actor, "actor must not be null");
        requireText(idempotencyKey, "idempotencyKey", 256);
        requireText(traceId, "traceId", 256);
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(activation, "activation must not be null");
        if (!caseId.equals(activation.caseId())) {
            throw new IllegalArgumentException("message case does not match activation");
        }
    }

    public Instant commandDeadlineAt() {
        return createdAt.plus(COMMAND_DEADLINE).truncatedTo(ChronoUnit.MICROS);
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
