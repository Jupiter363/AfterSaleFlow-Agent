package com.example.dispute.workflow.runtime.finalization;

import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ReadyAuthority;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationReceiptLedger.StoredReceipt;
import com.example.dispute.workflow.runtime.finalization.ProductionRoomFinalizationStrategy.ReceiptBindings;
import com.example.dispute.workflow.runtime.finalization.ProductionRoomFinalizationStrategy.TechnicalAuthority;
import java.util.Objects;

/** Formal-transaction companion for one exact-three parallel Intake assembly. */
public interface ProductionIntakeParallelAssemblyFinalizationPort {

    LockedAssembly lockAndRevalidate(
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            ReceiptBindings bindings);

    void markCommitted(LockedAssembly locked, StoredReceipt storedReceipt);

    record LockedAssembly(
            ReadyAuthority authority,
            String commandEnvelopeSha256,
            String targetProposalSha256,
            String resultEnvelopeSha256,
            String graphResultSha256)
            implements TechnicalAuthority {

        public LockedAssembly {
            authority = Objects.requireNonNull(authority, "authority");
            commandEnvelopeSha256 = sha256(commandEnvelopeSha256, "commandEnvelopeSha256");
            targetProposalSha256 = sha256(targetProposalSha256, "targetProposalSha256");
            resultEnvelopeSha256 = sha256(resultEnvelopeSha256, "resultEnvelopeSha256");
            graphResultSha256 = sha256(graphResultSha256, "graphResultSha256");
        }

        private static String sha256(String value, String field) {
            if (value == null || !value.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
            }
            return value;
        }
    }
}
