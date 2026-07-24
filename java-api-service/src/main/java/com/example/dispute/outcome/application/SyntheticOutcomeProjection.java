package com.example.dispute.outcome.application;

import com.example.dispute.workflow.activity.tool.SyntheticNoopExecutionReceipt;
import java.time.Instant;
import java.util.List;

/** Isolated comparison projection; it is deliberately not exposed by the public Outcome controller. */
public record SyntheticOutcomeProjection(
        String fixtureId,
        String workflowId,
        Execution execution,
        Closure closure,
        boolean projectionOnly) {

    public static SyntheticOutcomeProjection from(SyntheticNoopExecutionReceipt receipt) {
        if (receipt == null
                || !receipt.syntheticOnly()
                || receipt.toolInvoked()
                || receipt.externalEffectCreated()
                || receipt.formalBusinessWriteCreated()
                || !receipt.projectionOnly()
                || receipt.closureRelevant()) {
            throw new IllegalArgumentException("only a zero-effect synthetic receipt can be projected");
        }
        return new SyntheticOutcomeProjection(
                receipt.fixtureId(),
                receipt.workflowId(),
                new Execution(
                        "SIMULATED",
                        "OBSERVED_NO_EFFECT",
                        List.of(),
                        List.of(
                                new Receipt(
                                        receipt.operationId(),
                                        receipt.requestHash(),
                                        receipt.receiptHash())),
                        null,
                        null,
                        true,
                        false),
                new Closure("NOT_CLOSURE_ELIGIBLE", null),
                true);
    }

    public record Execution(
            String mode,
            String status,
            List<String> actions,
            List<Receipt> receipts,
            String failureCode,
            String failureMessage,
            boolean syntheticOnly,
            boolean formalReceiptPresent) {
        public Execution {
            actions = List.copyOf(actions);
            receipts = List.copyOf(receipts);
            if (!"SIMULATED".equals(mode)
                    || !"OBSERVED_NO_EFFECT".equals(status)
                    || !actions.isEmpty()
                    || receipts.size() != 1
                    || failureCode != null
                    || failureMessage != null
                    || !syntheticOnly
                    || formalReceiptPresent) {
                throw new IllegalArgumentException("invalid synthetic execution projection");
            }
        }
    }

    public record Receipt(String operationId, String requestHash, String receiptHash) {
        public Receipt {
            if (operationId == null
                    || !operationId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                    || requestHash == null
                    || !requestHash.matches("[0-9a-f]{64}")
                    || receiptHash == null
                    || !receiptHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid synthetic projection receipt");
            }
        }
    }

    public record Closure(String status, Instant closedAt) {
        public Closure {
            if (!"NOT_CLOSURE_ELIGIBLE".equals(status) || closedAt != null) {
                throw new IllegalArgumentException("synthetic no-op cannot project formal closure");
            }
        }
    }
}
