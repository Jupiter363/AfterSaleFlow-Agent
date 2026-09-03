package com.example.dispute.workflow.shadow.evidence;

import java.time.Duration;
import java.util.Objects;

/**
 * Admission facade for Java-signed synthetic local parity only.
 *
 * <p>This is intentionally not an adapter for production Graph execution. Durable permit queues,
 * leases, recovery, and admission ordering belong to the Graph PostgreSQL runtime.
 */
public final class EvidenceBulkhead {

    public static final String AUTHORITY_SCOPE =
            EvidenceBulkheadPolicy.AUTHORITY_SCOPE;

    private final EvidenceBulkheadPolicy policy;

    public EvidenceBulkhead(EvidenceBulkheadPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    public LocalParityLease acquire(
            EvidenceNoFormalSinkGuard guard,
            EvidenceNoFormalSinkGuard.AssemblyContract contract,
            EvidenceBulkheadPolicy.AdmissionKey key,
            Duration timeout)
            throws InterruptedException {
        Objects.requireNonNull(guard, "guard must not be null");
        Objects.requireNonNull(contract, "contract must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");

        EvidenceNoFormalSinkGuard.SignedSyntheticAssembly assembly = guard.assembleIfSafe(contract)
                .orElseThrow(() -> new LocalParityRejectedException(
                        "Evidence local parity is disabled; no local permit may be acquired"));
        return new LocalParityLease(assembly, policy.acquire(key, timeout));
    }

    /** Always false: production work must use the durable Graph permit authority. */
    public boolean isProductionPermitAuthority() {
        return false;
    }

    public EvidenceBulkheadPolicy.Snapshot snapshot() {
        return policy.snapshot();
    }

    public static final class LocalParityLease implements AutoCloseable {

        private final EvidenceNoFormalSinkGuard.SignedSyntheticAssembly assembly;
        private final EvidenceBulkheadPolicy.Lease lease;

        private LocalParityLease(
                EvidenceNoFormalSinkGuard.SignedSyntheticAssembly assembly,
                EvidenceBulkheadPolicy.Lease lease) {
            this.assembly = assembly;
            this.lease = lease;
        }

        public EvidenceNoFormalSinkGuard.SignedSyntheticAssembly assembly() {
            return assembly;
        }

        @Override
        public void close() {
            lease.close();
        }
    }

    public static final class LocalParityRejectedException extends IllegalStateException {

        private LocalParityRejectedException(String message) {
            super(message);
        }
    }
}
