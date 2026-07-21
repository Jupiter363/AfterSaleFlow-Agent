package com.example.dispute.workflow.application.authority.payload;

import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import java.time.OffsetDateTime;
import java.util.Objects;

/** One immutable case-command authority record linked to one immutable payload authority row. */
public record IntakeCommandAuthority(
        String caseCommandId,
        String commandId,
        long caseCommandSequence,
        CommandType commandType,
        IntakeAuthorityRoute route,
        String payloadAuthorityId,
        String requestHash,
        long acceptedRoomRevision,
        ExecutionDisposition executionDisposition,
        OffsetDateTime createdAt) {

    public enum ExecutionDisposition {
        INERT_EXTERNAL_EVENT,
        ACTIVITY_ORCHESTRATED
    }

    public IntakeCommandAuthority {
        identifier(caseCommandId, "caseCommandId", 64);
        identifier(commandId, "commandId", 128);
        if (caseCommandSequence <= 0 || acceptedRoomRevision < 0) {
            throw new IllegalArgumentException("command sequence and accepted room revision are invalid");
        }
        Objects.requireNonNull(commandType, "commandType must not be null");
        if (commandType != CommandType.INTAKE_MESSAGE
                && commandType != CommandType.INTAKE_CONFIRM
                && commandType != CommandType.INTAKE_CANCEL) {
            throw new IllegalArgumentException("commandType must be an Intake command");
        }
        Objects.requireNonNull(route, "route must not be null");
        identifier(payloadAuthorityId, "payloadAuthorityId", 128);
        if (requestHash == null || !requestHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestHash must be a lowercase SHA-256");
        }
        Objects.requireNonNull(executionDisposition, "executionDisposition must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public void requirePayload(IntakePayloadAuthority payload) {
        if (!payloadAuthorityId.equals(payload.payloadAuthorityId())
                || !commandId.equals(payload.commandId())
                || !route.equals(payload.route())) {
            throw new IllegalArgumentException("command does not match the immutable payload authority route");
        }
        ExecutionDisposition expectedDisposition;
        switch (payload.sourceKind()) {
            case EXISTING_PRIVATE_EVENT -> {
                if (commandType != CommandType.INTAKE_MESSAGE) {
                    throw new IllegalArgumentException("payload source kind does not match command type");
                }
                expectedDisposition = ExecutionDisposition.INERT_EXTERNAL_EVENT;
            }
            case SERVER_MINTED_HUMAN_INPUT -> {
                if (commandType != CommandType.INTAKE_MESSAGE) {
                    throw new IllegalArgumentException("payload source kind does not match command type");
                }
                expectedDisposition = ExecutionDisposition.ACTIVITY_ORCHESTRATED;
            }
            case SERVER_CANONICAL_BRANCH -> {
                if (commandType != CommandType.INTAKE_CONFIRM && commandType != CommandType.INTAKE_CANCEL) {
                    throw new IllegalArgumentException("payload source kind does not match command type");
                }
                expectedDisposition = ExecutionDisposition.ACTIVITY_ORCHESTRATED;
            }
            default -> throw new IllegalArgumentException("unsupported payload source kind");
        }
        if (executionDisposition != expectedDisposition) {
            throw new IllegalArgumentException("payload source kind does not match execution disposition");
        }
        if (executionDisposition != ExecutionDisposition.INERT_EXTERNAL_EVENT) {
            throw new IllegalArgumentException("current authority gate permits only inert external events");
        }
    }

    private static void identifier(String value, String field, int maximumLength) {
        if (value == null
                || value.length() > maximumLength
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0," + (maximumLength - 1) + "}")) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
    }
}
