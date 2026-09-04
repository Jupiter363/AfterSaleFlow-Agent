package com.example.dispute.workflow.runtime.ingress;

import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.ActorRole;
import com.example.dispute.room.domain.MessageType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

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
    private static final String RESPONDENT_OPENING_TEXT = "RESPONDENT_OPENING";
    private static final String RESPONDENT_OPENING_IDENTITY_DOMAIN =
            "target-intake-respondent-opening.v1";

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
        switch (sourceType) {
            case INITIAL_FORM -> {
                if (messageType != null || !attachmentRefs.isEmpty()) {
                    throw new IllegalArgumentException(
                            "INITIAL_FORM must not masquerade as a persisted room message");
                }
            }
            case RESPONDENT_OPENING -> {
                if (messageType != null || !attachmentRefs.isEmpty()) {
                    throw new IllegalArgumentException(
                            "RESPONDENT_OPENING must not masquerade as a persisted room message");
                }
                if (actor.role() != ActorRole.USER && actor.role() != ActorRole.MERCHANT) {
                    throw new IllegalArgumentException(
                            "RESPONDENT_OPENING actor must be a case party");
                }
                RespondentOpeningIdentity expected =
                        respondentOpeningIdentity(caseId, roomId, actor, activation);
                if (!RESPONDENT_OPENING_TEXT.equals(text)
                        || !expected.messageId().equals(messageId)
                        || !expected.idempotencyKey().equals(idempotencyKey)
                        || !expected.traceId().equals(traceId)) {
                    throw new IllegalArgumentException(
                            "RESPONDENT_OPENING identity is not canonical");
                }
            }
            case ROOM_MESSAGE ->
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

    public static TargetIntakeMessageRequest respondentOpening(
            String caseId,
            String roomId,
            AuthenticatedActor actor,
            Instant occurredAt,
            TargetIntakeActivationGrant activation) {
        RespondentOpeningIdentity identity =
                respondentOpeningIdentity(caseId, roomId, actor, activation);
        return new TargetIntakeMessageRequest(
                caseId,
                roomId,
                identity.messageId(),
                null,
                RESPONDENT_OPENING_TEXT,
                List.of(),
                actor,
                identity.idempotencyKey(),
                identity.traceId(),
                occurredAt,
                activation,
                SourceType.RESPONDENT_OPENING);
    }

    public Instant commandDeadlineAt() {
        Instant deadline =
                sourceType == SourceType.ROOM_MESSAGE
                        ? createdAt.plus(COMMAND_DEADLINE)
                        : activation.expiresAt();
        return deadline.truncatedTo(ChronoUnit.MICROS);
    }

    public enum SourceType {
        INITIAL_FORM,
        ROOM_MESSAGE,
        RESPONDENT_OPENING
    }

    private static RespondentOpeningIdentity respondentOpeningIdentity(
            String caseId,
            String roomId,
            AuthenticatedActor actor,
            TargetIntakeActivationGrant activation) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(activation, "activation must not be null");
        String token =
                UUID.nameUUIDFromBytes(
                                String.join(
                                                "\n",
                                                RESPONDENT_OPENING_IDENTITY_DOMAIN,
                                                activation.tenantSurrogate(),
                                                caseId,
                                                roomId,
                                                Long.toString(activation.roomEpoch()),
                                                Long.toString(activation.roomFencingToken()),
                                                actor.actorId(),
                                                actor.role().name())
                                        .getBytes(StandardCharsets.UTF_8))
                        .toString()
                        .replace("-", "");
        return new RespondentOpeningIdentity(
                "RESPONDENT_OPENING_" + token,
                "target-intake-respondent-opening:" + token,
                "TRACE_" + token);
    }

    private record RespondentOpeningIdentity(
            String messageId, String idempotencyKey, String traceId) {}

    private static void requireText(String value, String field, int maximumLength) {
        if (value == null
                || value.isBlank()
                || value.length() > maximumLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }
}
