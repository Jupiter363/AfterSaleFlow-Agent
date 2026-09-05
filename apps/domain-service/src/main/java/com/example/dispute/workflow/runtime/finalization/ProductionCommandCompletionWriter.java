package com.example.dispute.workflow.runtime.finalization;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;

/**
 * Validates the admitted command and appends completion only when the Graph owns completion.
 *
 * <p>Room strategies never own this write. The common finalizer invokes it only after the
 * immutable target receipt has been appended or replay-verified.
 * Review advisory Graphs share a human decision command: its formal Outcome or non-execution
 * disposition completes that command, never the advisory receipt. Admission validation still runs.
 */
@FunctionalInterface
public interface ProductionCommandCompletionWriter {

    void complete(ExecuteAgentRunRequest request, ProductionFinalizationReceipt receipt);
}
