package com.example.dispute.workflow.runtime.finalization;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;

/**
 * Appends the admitted-command completion using the target finalizer's active transaction.
 *
 * <p>Room strategies never own this write. The common finalizer invokes it only after the
 * immutable target receipt has been appended or replay-verified.
 */
@FunctionalInterface
public interface ProductionCommandCompletionWriter {

    void complete(ExecuteAgentRunRequest request, ProductionFinalizationReceipt receipt);
}
