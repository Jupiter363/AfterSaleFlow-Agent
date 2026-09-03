package com.example.dispute.workflow.shadow.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EvidenceBulkheadIntegrationTest {

    @Test
    void admitsOnlyVerifiedSignedSyntheticParityAndReleasesLocalPermit() throws Exception {
        try (EvidenceBulkheadPolicy policy = new EvidenceBulkheadPolicy(
                new EvidenceBulkheadPolicy.Limits(1, 1, 1, 1, 1, Duration.ofSeconds(1)))) {
            EvidenceBulkhead bulkhead = new EvidenceBulkhead(policy);
            EvidenceNoFormalSinkGuard guard = new EvidenceNoFormalSinkGuard();

            try (EvidenceBulkhead.LocalParityLease lease = bulkhead.acquire(
                    guard,
                    EvidenceNoFormalSinkGuard.AssemblyContract.javaSignedSyntheticShadow(3, 7),
                    new EvidenceBulkheadPolicy.AdmissionKey("synthetic-tenant", "synthetic-room"),
                    Duration.ofSeconds(1))) {
                assertThat(lease.assembly().javaRoomFence().token()).isEqualTo(3);
                assertThat(lease.assembly().graphLeaseFence().token()).isEqualTo(7);
                assertThat(policy.snapshot().globalInFlight()).isOne();
            }

            assertThat(policy.snapshot().globalInFlight()).isZero();
            assertThat(bulkhead.isProductionPermitAuthority()).isFalse();
            assertThat(EvidenceBulkhead.AUTHORITY_SCOPE)
                    .isEqualTo("JAVA_SIGNED_SYNTHETIC_LOCAL_PARITY_ONLY");
        }
    }

    @Test
    void disabledTemporalAndFormalSinkContractsCannotAcquireLocalPermit() {
        try (EvidenceBulkheadPolicy policy = new EvidenceBulkheadPolicy(
                new EvidenceBulkheadPolicy.Limits(1, 1, 1, 1, 1, Duration.ofSeconds(1)))) {
            EvidenceBulkhead bulkhead = new EvidenceBulkhead(policy);
            EvidenceNoFormalSinkGuard guard = new EvidenceNoFormalSinkGuard();
            EvidenceBulkheadPolicy.AdmissionKey key =
                    new EvidenceBulkheadPolicy.AdmissionKey("synthetic-tenant", "synthetic-room");

            assertThatThrownBy(() -> bulkhead.acquire(
                            guard,
                            EvidenceNoFormalSinkGuard.AssemblyContract.disabled(),
                            key,
                            Duration.ofSeconds(1)))
                    .isInstanceOf(EvidenceBulkhead.LocalParityRejectedException.class);
            assertThatThrownBy(() -> bulkhead.acquire(
                            guard,
                            new EvidenceNoFormalSinkGuard.AssemblyContract(
                                    EvidenceNoFormalSinkGuard.RuntimeMode.SHADOW,
                                    EvidenceNoFormalSinkGuard.ExecutionAllocation.TEMPORAL,
                                    EvidenceNoFormalSinkGuard.ManifestAuthority.DIRECT_JAVA_ES256_SIGNATURE,
                                    EvidenceNoFormalSinkGuard.requiredAuthorityValidationOrder(),
                                    EvidenceNoFormalSinkGuard.ACTOR_SCOPE_HASH_SOURCE,
                                    EvidenceNoFormalSinkGuard.TERMINAL_OUTPUT_SCHEMA_VERSION,
                                    EvidenceNoFormalSinkGuard.TERMINAL_OUTPUT_SCHEMA_VERSION,
                                    EvidenceNoFormalSinkGuard.ITEM_ASSESSMENT_OUTPUT_SCHEMA_VERSION,
                                    EvidenceNoFormalSinkGuard.FenceBinding.signedSynthetic(3, 7),
                                    false,
                                    java.util.Set.of()),
                            key,
                            Duration.ofSeconds(1)))
                    .isInstanceOf(EvidenceNoFormalSinkGuard.GuardRejectedException.class)
                    .hasMessageContaining("TEMPORAL_ALLOCATION");
            assertThat(policy.snapshot().globalInFlight()).isZero();
        }
    }

    @Test
    void driverProducesOnlyLocalTextFreeParityAndReleasesItsPermit() throws Exception {
        try (EvidenceBulkheadPolicy policy = new EvidenceBulkheadPolicy(
                new EvidenceBulkheadPolicy.Limits(1, 1, 1, 1, 1, Duration.ofSeconds(1)))) {
            EvidenceSignedSyntheticDriver driver = new EvidenceSignedSyntheticDriver(
                    new EvidenceNoFormalSinkGuard(),
                    new EvidenceBulkhead(policy),
                    new EvidenceShadowParityService());
            EvidenceShadowParityService.ParitySnapshot snapshot = paritySnapshot();

            EvidenceShadowParityService.ParityComparison comparison = driver.runLocalParity(
                    EvidenceNoFormalSinkGuard.AssemblyContract.javaSignedSyntheticShadow(3, 7),
                    new EvidenceBulkheadPolicy.AdmissionKey("synthetic-tenant", "synthetic-room"),
                    Duration.ofSeconds(1),
                    "f".repeat(64),
                    snapshot,
                    snapshot);

            assertThat(comparison.verdict())
                    .isEqualTo(EvidenceShadowParityService.Verdict.MATCH);
            assertThat(policy.snapshot().globalInFlight()).isZero();
            assertThat(driver.isProductionPermitAuthority()).isFalse();
        }
    }

    private static EvidenceShadowParityService.ParitySnapshot paritySnapshot() {
        EnumMap<EvidenceShadowParityService.Dimension, EvidenceShadowParityService.ObservedValue>
                values = new EnumMap<>(EvidenceShadowParityService.Dimension.class);
        for (EvidenceShadowParityService.Dimension dimension
                : EvidenceShadowParityService.Dimension.values()) {
            values.put(
                    dimension,
                    new EvidenceShadowParityService.ObservedValue(
                            EvidenceShadowParityService.Classification.VALUE, "a".repeat(64)));
        }
        return new EvidenceShadowParityService.ParitySnapshot(values, Set.of());
    }
}
