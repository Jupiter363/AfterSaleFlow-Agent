package com.example.dispute.workflow.shadow.intake;

import com.example.dispute.workflow.shadow.intake.IntakeShadowParityService.ParitySnapshot;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventType;
import java.util.Objects;

/** Supplies text-free legacy and synthetic observations for one comparison-only finalization. */
@FunctionalInterface
public interface IntakeSyntheticParityObservationPort {

    Observation observe(TurnFinalizationRequest request);

    record Observation(
            ParitySnapshot legacy,
            ParitySnapshot shadow,
            IntakeDomainEventType projectedEventType) {

        public Observation {
            Objects.requireNonNull(legacy, "legacy must not be null");
            Objects.requireNonNull(shadow, "shadow must not be null");
            if (projectedEventType != IntakeDomainEventType.TURN_NEEDS_INPUT
                    && projectedEventType != IntakeDomainEventType.TURN_READY_TO_CONFIRM) {
                throw new IllegalArgumentException(
                        "synthetic comparison can project only a non-formal turn event");
            }
        }
    }
}
