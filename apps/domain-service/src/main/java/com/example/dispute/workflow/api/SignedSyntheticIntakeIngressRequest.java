package com.example.dispute.workflow.api;

import com.example.dispute.workflow.application.epoch.RoomEpochSelectionContext.TrafficSource;
import com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticAdmissionPort.AdmissionAttempt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.example.dispute.workflow.temporal.room.intake.IntakeWorkflowCommand;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SignedSyntheticIntakeIngressRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") String signingKeyId,
        @NotBlank @Size(max = 16_384) String compactJws,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String signedEnvelopeHash,
        @NotBlank @Pattern(regexp = "grt\\.v1\\.[0-9a-f]{32}") String threadId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") String agentSessionId,
        @Positive long deadlineEpochMillis,
        @NotNull @Valid RetryBudgetRequest retryBudget,
        @Positive long roomEpoch,
        @Positive long fencingToken,
        @Positive long commandSequence,
        @NotNull IntakeParty party,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String actorScopeHash,
        @NotBlank @Size(max = 1024) @Pattern(regexp = "^(s3|minio|urn):.+") String payloadRef,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String payloadHash,
        @PositiveOrZero @Max(1_073_741_824L) long payloadSizeBytes,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String requestHash,
        @PositiveOrZero long expectedProcessRevision) {

    AdmissionAttempt toAttempt() {
        return new AdmissionAttempt(
                "intake-signed-synthetic-admission-attempt.v1",
                TrafficSource.AUTHENTICATED_SIGNED_SYNTHETIC,
                signingKeyId,
                compactJws,
                signedEnvelopeHash,
                threadId,
                agentSessionId,
                deadlineEpochMillis,
                retryBudget.toRetryBudget());
    }

    IntakeWorkflowCommand toInertCommand(String tenantSurrogate, String caseId, String commandId) {
        return new IntakeWorkflowCommand(
                "intake-workflow-command.v1",
                commandId,
                tenantSurrogate,
                caseId,
                roomEpoch,
                fencingToken,
                commandSequence,
                IntakeCommandType.INTAKE_MESSAGE,
                party,
                actorScopeHash,
                payloadRef,
                payloadHash,
                "intake.operation:" + caseId + ":" + commandId,
                requestHash);
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object ignored) {
        throw new IllegalArgumentException("unknown signed synthetic Intake field: " + name);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RetryBudgetRequest(
            @Min(0) int providerAttemptsRemaining,
            @Min(0) int activityAttemptsRemaining,
            @Min(0) int repairsRemaining) {

        RetryBudget toRetryBudget() {
            return new RetryBudget(
                    "intake-retry-budget.v1",
                    providerAttemptsRemaining,
                    activityAttemptsRemaining,
                    repairsRemaining);
        }

        @JsonAnySetter
        public void rejectUnknownField(String name, Object ignored) {
            throw new IllegalArgumentException("unknown retry budget field: " + name);
        }
    }
}
