package com.example.dispute.workflow.application.intake;

import com.example.dispute.room.application.IntakeConfirmationCommand;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchOperation;
import java.util.Objects;

/** Loads and verifies the immutable Java command body referenced by a branch Activity. */
@FunctionalInterface
public interface IntakeFormalBranchCommandResolver {

    ResolvedBranchCommand resolve(BranchCommitRequest request);

    record ResolvedBranchCommand(
            BranchOperation operation,
            String payloadSchema,
            String payloadRef,
            String payloadHash,
            IntakeConfirmationCommand confirmation,
            String cancellationReason) {

        public ResolvedBranchCommand {
            operation = Objects.requireNonNull(operation, "operation");
            if (payloadSchema == null
                    || !payloadSchema.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
                throw new IllegalArgumentException("payloadSchema is invalid");
            }
            if (payloadRef == null || payloadRef.isBlank() || payloadRef.length() > 1024) {
                throw new IllegalArgumentException("payloadRef is invalid");
            }
            if (payloadHash == null || !payloadHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("payloadHash is invalid");
            }
            if (operation == BranchOperation.CANCEL) {
                if (confirmation != null) {
                    throw new IllegalArgumentException("cancellation cannot carry confirmation");
                }
                if (cancellationReason != null && cancellationReason.length() > 2000) {
                    throw new IllegalArgumentException("cancellationReason is too long");
                }
            } else {
                Objects.requireNonNull(confirmation, "confirmation");
                if (cancellationReason != null) {
                    throw new IllegalArgumentException("confirmation cannot carry cancellationReason");
                }
                boolean expectedAdmissible = operation != BranchOperation.INITIATOR_REJECT;
                if (confirmation.admissible() != expectedAdmissible) {
                    throw new IllegalArgumentException(
                            "resolved confirmation does not match the branch operation");
                }
            }
        }

        public void requireMatches(BranchCommitRequest request) {
            if (operation != request.operation()
                    || !payloadRef.equals(request.envelope().commandPayloadRef())
                    || !payloadHash.equals(request.envelope().commandPayloadHash())) {
                throw new IntakeFinalizationRejectedException(
                        "INTAKE_BRANCH_PAYLOAD_MISMATCH",
                        "resolved branch command does not match the immutable request reference");
            }
        }
    }
}
