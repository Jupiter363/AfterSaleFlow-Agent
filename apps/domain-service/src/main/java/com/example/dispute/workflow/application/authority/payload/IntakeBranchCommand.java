package com.example.dispute.workflow.application.authority.payload;

import com.example.dispute.workflow.application.authority.epoch.EpochPartyAuthority.Party;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.RiskLevel;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Objects;

/** Canonical Java branch payload; it encodes no new formal authority. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record IntakeBranchCommand(
        String schemaVersion,
        String commandId,
        CommandType commandType,
        Party party,
        Operation operation,
        Boolean admissible,
        String disputeType,
        RiskLevel riskLevel,
        String confirmationNote,
        String cancellationReason) {

    public static final String SCHEMA_VERSION = "intake-branch-command.v1";

    public enum Operation {
        INITIATOR_ACCEPT,
        INITIATOR_REJECT,
        RESPONDENT_CONFIRM,
        CANCEL
    }

    public IntakeBranchCommand {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("schemaVersion must be " + SCHEMA_VERSION);
        }
        identifier(commandId, "commandId", 128);
        Objects.requireNonNull(commandType, "commandType must not be null");
        Objects.requireNonNull(party, "party must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        switch (operation) {
            case INITIATOR_ACCEPT, INITIATOR_REJECT, RESPONDENT_CONFIRM -> {
                if (commandType != CommandType.INTAKE_CONFIRM || admissible == null || disputeType == null
                        || riskLevel == null || cancellationReason != null) {
                    throw new IllegalArgumentException("confirmation branch shape is invalid");
                }
                identifier(disputeType, "disputeType", 128);
                if (confirmationNote != null && (confirmationNote.isBlank() || confirmationNote.length() > 2_000)) {
                    throw new IllegalArgumentException("confirmationNote is invalid");
                }
                if ((operation == Operation.INITIATOR_ACCEPT || operation == Operation.RESPONDENT_CONFIRM)
                        && (!admissible || (operation == Operation.INITIATOR_ACCEPT && party != Party.INITIATOR)
                                || (operation == Operation.RESPONDENT_CONFIRM && party != Party.RESPONDENT))) {
                    throw new IllegalArgumentException("admitted branch party/flag is invalid");
                }
                if (operation == Operation.INITIATOR_REJECT
                        && (admissible || party != Party.INITIATOR)) {
                    throw new IllegalArgumentException("rejection branch party/flag is invalid");
                }
            }
            case CANCEL -> {
                if (commandType != CommandType.INTAKE_CANCEL || party != Party.INITIATOR
                        || cancellationReason == null || cancellationReason.length() > 2_000
                        || admissible != null || disputeType != null || riskLevel != null
                        || confirmationNote != null) {
                    throw new IllegalArgumentException("cancellation branch shape is invalid");
                }
            }
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
