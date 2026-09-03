package com.example.dispute.workflow.shadow.intake;

import com.example.dispute.workflow.shadow.intake.IntakeShadowParityService.ParitySnapshot;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticRuntimeSource.ActivityAuthority;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventType;
import java.util.Objects;

/** Supplies text-free legacy/shadow observations from their authoritative comparison readers. */
public interface IntakeSyntheticParityMaterialSource {

    ParityMaterial load(ParityMaterialQuery query);

    record ParityMaterialQuery(
            ActivityAuthority authority,
            TurnFinalizationRequest request,
            String parityBaselineRef,
            String parityBaselineHash) {

        public ParityMaterialQuery {
            Objects.requireNonNull(authority, "authority must not be null");
            Objects.requireNonNull(request, "request must not be null");
            Objects.requireNonNull(parityBaselineRef, "parityBaselineRef must not be null");
            Objects.requireNonNull(parityBaselineHash, "parityBaselineHash must not be null");
        }
    }

    record ParityMaterial(
            ParitySnapshot legacy,
            ParitySnapshot shadow,
            IntakeDomainEventType projectedEventType) {

        public ParityMaterial {
            Objects.requireNonNull(legacy, "legacy must not be null");
            Objects.requireNonNull(shadow, "shadow must not be null");
            Objects.requireNonNull(projectedEventType, "projectedEventType must not be null");
        }
    }
}
