package com.example.dispute.workflow.shadow.intake;

import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventType;
import java.util.Objects;
import java.util.Optional;

/** Durable, isolated storage contract for comparison-only synthetic finalization. */
public interface IntakeSyntheticComparisonLedger {

    CommitResult commit(CommitRequest request);

    Optional<CommitResult> find(TurnFinalizationRequest request);

    record CommitRequest(
            TurnFinalizationRequest finalization,
            IntakeShadowComparison comparison,
            IntakeDomainEventType projectedEventType) {

        public CommitRequest {
            Objects.requireNonNull(finalization, "finalization must not be null");
            Objects.requireNonNull(comparison, "comparison must not be null");
            if (projectedEventType != IntakeDomainEventType.TURN_NEEDS_INPUT
                    && projectedEventType != IntakeDomainEventType.TURN_READY_TO_CONFIRM) {
                throw new IllegalArgumentException(
                        "comparison ledger accepts only synthetic turn projections");
            }
        }
    }

    record CommitResult(
            IntakeShadowComparison comparison,
            TurnFinalizationReceipt receipt,
            boolean created) {

        public CommitResult {
            Objects.requireNonNull(comparison, "comparison must not be null");
            Objects.requireNonNull(receipt, "receipt must not be null");
        }
    }
}
