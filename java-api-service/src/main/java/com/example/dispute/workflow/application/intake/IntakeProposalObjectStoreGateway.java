package com.example.dispute.workflow.application.intake;

/** Low-level immutable proposal-store client used only behind the policy-enforcing reader. */
@FunctionalInterface
public interface IntakeProposalObjectStoreGateway {

    IntakeImmutableProposalReader.StoredProposal read(IntakeProposalReference reference);

    /** Explicit transient transport or service failure that can consume the Activity retry budget. */
    final class RetryableAccessException extends RuntimeException {

        private final RetryableReason reason;

        public RetryableAccessException(
                RetryableReason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = java.util.Objects.requireNonNull(reason, "reason");
        }

        public RetryableReason reason() {
            return reason;
        }
    }

    /** Explicit permanent object-store result; it is never retried by the formal Activity. */
    final class PermanentAccessException extends RuntimeException {

        private final Reason reason;

        public PermanentAccessException(Reason reason, String message) {
            super(message);
            this.reason = java.util.Objects.requireNonNull(reason, "reason");
        }

        public PermanentAccessException(Reason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = java.util.Objects.requireNonNull(reason, "reason");
        }

        public Reason reason() {
            return reason;
        }
    }

    enum Reason {
        NOT_FOUND("INTAKE_PROPOSAL_OBJECT_NOT_FOUND"),
        VERSION_MISMATCH("INTAKE_PROPOSAL_OBJECT_VERSION_MISMATCH"),
        ACCESS_DENIED("INTAKE_PROPOSAL_OBJECT_ACCESS_DENIED"),
        REFERENCE_INVALID("INTAKE_PROPOSAL_OBJECT_REFERENCE_INVALID");

        private final String rejectionCode;

        Reason(String rejectionCode) {
            this.rejectionCode = rejectionCode;
        }

        public String rejectionCode() {
            return rejectionCode;
        }
    }

    enum RetryableReason {
        TIMEOUT,
        SERVER_ERROR
    }
}
