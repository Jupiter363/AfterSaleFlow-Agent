package com.example.dispute.workflow.application.command;

import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.regex.Pattern;

public record AcceptCaseCommand(
        CommandType commandType,
        RoomType roomType,
        long roomEpoch,
        PayloadRef payloadRef,
        long expectedProcessRevision,
        Instant deadlineAt) {

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern PAYLOAD_URI = Pattern.compile("^(s3|minio|urn):.+");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final long MAX_PAYLOAD_SIZE_BYTES = 1_073_741_824L;

    public AcceptCaseCommand {
        Objects.requireNonNull(commandType, "commandType must not be null");
        Objects.requireNonNull(roomType, "roomType must not be null");
        Objects.requireNonNull(payloadRef, "payloadRef must not be null");
        Objects.requireNonNull(deadlineAt, "deadlineAt must not be null");
        deadlineAt = deadlineAt.truncatedTo(ChronoUnit.MICROS);
        if (roomEpoch < 0) {
            throw new IllegalArgumentException("roomEpoch must be non-negative");
        }
        if (expectedProcessRevision < 0) {
            throw new IllegalArgumentException("expectedProcessRevision must be non-negative");
        }
        validatePayload(payloadRef);
        assertRoomMatchesCommand(commandType, roomType);
    }

    private static void validatePayload(PayloadRef payload) {
        if (!IDENTIFIER.matcher(payload.schemaVersion()).matches()) {
            throw new IllegalArgumentException("payload schema version is invalid");
        }
        if (payload.uri().length() > 1024 || !PAYLOAD_URI.matcher(payload.uri()).matches()) {
            throw new IllegalArgumentException("payload URI is invalid");
        }
        try {
            if (!URI.create(payload.uri()).isAbsolute()) {
                throw new IllegalArgumentException("payload URI is invalid");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("payload URI is invalid", exception);
        }
        if (!SHA256.matcher(payload.sha256()).matches()) {
            throw new IllegalArgumentException("payload sha256 is invalid");
        }
        if (payload.sizeBytes() < 0 || payload.sizeBytes() > MAX_PAYLOAD_SIZE_BYTES) {
            throw new IllegalArgumentException("payload size is outside the supported range");
        }
    }

    private static void assertRoomMatchesCommand(CommandType commandType, RoomType roomType) {
        RoomType expectedRoom =
                switch (commandType) {
                    case CASE_OPEN, INTAKE_MESSAGE, INTAKE_CONFIRM, INTAKE_CANCEL ->
                            RoomType.INTAKE;
                    case EVIDENCE_SUBMIT, PARTY_EVIDENCE_COMPLETE -> RoomType.EVIDENCE;
                    case HEARING_STATEMENT, HEARING_EVIDENCE_BATCH -> RoomType.HEARING;
                    case REVIEW_DECISION, EXECUTE_APPROVED_PLAN, CLOSE_CASE -> RoomType.REVIEW;
                };
        if (roomType != expectedRoom) {
            throw new IllegalArgumentException(
                    "command " + commandType + " must target room " + expectedRoom);
        }
    }
}
