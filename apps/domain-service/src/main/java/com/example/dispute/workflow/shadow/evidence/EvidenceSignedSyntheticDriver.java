package com.example.dispute.workflow.shadow.evidence;

import java.time.Duration;
import java.util.Objects;

/**
 * Runs one bounded, Java-signed synthetic local parity comparison.
 *
 * <p>The driver has no formal writer, production object resolver, Temporal allocation, callback,
 * or sink dependency. It returns text-free telemetry to its engineering caller only.
 */
public final class EvidenceSignedSyntheticDriver {

    private final EvidenceNoFormalSinkGuard guard;
    private final EvidenceBulkhead bulkhead;
    private final EvidenceShadowParityService parityService;

    public EvidenceSignedSyntheticDriver(
            EvidenceNoFormalSinkGuard guard,
            EvidenceBulkhead bulkhead,
            EvidenceShadowParityService parityService) {
        this.guard = Objects.requireNonNull(guard, "guard must not be null");
        this.bulkhead = Objects.requireNonNull(bulkhead, "bulkhead must not be null");
        this.parityService = Objects.requireNonNull(parityService, "parityService must not be null");
    }

    public EvidenceShadowParityService.ParityComparison runLocalParity(
            EvidenceNoFormalSinkGuard.AssemblyContract contract,
            EvidenceBulkheadPolicy.AdmissionKey key,
            Duration timeout,
            String comparisonKeyHash,
            EvidenceShadowParityService.ParitySnapshot legacy,
            EvidenceShadowParityService.ParitySnapshot shadow)
            throws InterruptedException {
        Objects.requireNonNull(comparisonKeyHash, "comparisonKeyHash must not be null");
        Objects.requireNonNull(legacy, "legacy must not be null");
        Objects.requireNonNull(shadow, "shadow must not be null");
        try (EvidenceBulkhead.LocalParityLease ignored = bulkhead.acquire(
                guard, contract, key, timeout)) {
            return parityService.compare(comparisonKeyHash, legacy, shadow);
        }
    }

    /** The driver cannot be selected as a production permit authority. */
    public boolean isProductionPermitAuthority() {
        return false;
    }
}
