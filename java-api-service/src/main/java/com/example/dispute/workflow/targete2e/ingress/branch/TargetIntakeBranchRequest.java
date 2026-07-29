package com.example.dispute.workflow.targete2e.ingress.branch;

import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.workflow.application.authority.payload.IntakeBranchCommand;
import com.example.dispute.workflow.targete2e.ingress.TargetIntakeActivationGrant;
import java.time.Instant;
import java.util.Objects;

/** Fully bound browser branch command. The activation pins its epoch and retry deadline. */
public record TargetIntakeBranchRequest(
        String caseId,
        AuthenticatedActor actor,
        IntakeBranchCommand command,
        String idempotencyKey,
        String traceId,
        Instant createdAt,
        TargetIntakeActivationGrant activation) {

    public TargetIntakeBranchRequest {
        required(caseId, "caseId", 128);
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(command, "command must not be null");
        required(idempotencyKey, "idempotencyKey", 256);
        required(traceId, "traceId", 256);
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(activation, "activation must not be null");
        if (!caseId.equals(activation.caseId())) {
            throw new IllegalArgumentException("branch case does not match activation");
        }
        if (createdAt.compareTo(activation.expiresAt()) >= 0) {
            throw new IllegalArgumentException("target activation has expired");
        }
    }

    public Instant commandDeadlineAt() {
        return activation.expiresAt();
    }

    private static void required(String value, String field, int maximumLength) {
        if (value == null
                || value.isBlank()
                || value.length() > maximumLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }
}
