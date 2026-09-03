package com.example.dispute.workflow.activity.tool;

/** Unregistered port for the Phase 7 engineering-only no-op adapter. */
public interface SyntheticNoopToolActivity {

    SyntheticNoopExecutionReceipt execute(SyntheticNoopExecutionCommand command);

    @FunctionalInterface
    interface SignatureVerifier {
        boolean verify(SyntheticNoopExecutionCommand command);
    }

    interface ReceiptSigner {
        String signingKeyId();

        String sign(String lowercaseReceiptHash);
    }

    enum FailureClass {
        CONTRACT_INVALID(false, true),
        HASH_OR_ID_CONFLICT(false, true),
        STALE_REVISION_OR_FENCE(false, true),
        TOOL_TRANSIENT_SAFE(true, true),
        TOOL_AMBIGUOUS(false, true);

        private final boolean retryAllowed;
        private final boolean closureBlocking;

        FailureClass(boolean retryAllowed, boolean closureBlocking) {
            this.retryAllowed = retryAllowed;
            this.closureBlocking = closureBlocking;
        }

        public boolean retryAllowed() {
            return retryAllowed;
        }

        public boolean closureBlocking() {
            return closureBlocking;
        }
    }

    final class ExecutionException extends RuntimeException {
        private final FailureClass failureClass;

        public ExecutionException(FailureClass failureClass, String message) {
            super(message);
            this.failureClass = failureClass;
        }

        public FailureClass failureClass() {
            return failureClass;
        }
    }
}
