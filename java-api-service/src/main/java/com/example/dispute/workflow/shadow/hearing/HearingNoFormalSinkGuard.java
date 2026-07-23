package com.example.dispute.workflow.shadow.hearing;

import java.util.Objects;
import java.util.Set;

/** Closed assembly proof: Hearing shadow may compare and meter, but cannot write business facts. */
public final class HearingNoFormalSinkGuard {

    public Decision verify(AssemblyContract contract) {
        Objects.requireNonNull(contract, "contract must not be null");
        if (contract.allocation() == Allocation.TEMPORAL) {
            throw rejected(Violation.TEMPORAL_ALLOCATION);
        }
        if (contract.capabilities().stream().anyMatch(Capability::formal)) {
            throw rejected(Violation.FORMAL_SINK_REACHABLE);
        }
        if (contract.runtimeMode() == RuntimeMode.DISABLED) {
            if (contract.allocation() != Allocation.DISABLED
                    || contract.signatureAuthority() != SignatureAuthority.NONE
                    || contract.scopeBinding() != ScopeBinding.NONE
                    || !contract.capabilities().isEmpty()) {
                throw rejected(Violation.DISABLED_REACHABILITY);
            }
            return new Decision(Disposition.NO_EXECUTION, SinkDisposition.NO_FORMAL_SINK);
        }
        if (contract.runtimeMode() != RuntimeMode.SIGNED_SYNTHETIC_SHADOW) {
            throw rejected(Violation.ACTIVE_OR_REAL_RUNTIME);
        }
        if (contract.allocation() != Allocation.JAVA_SIGNED_SYNTHETIC_COMPARISON
                || contract.signatureAuthority() != SignatureAuthority.DIRECT_JAVA_ES256
                || contract.scopeBinding() != ScopeBinding.EXACT_FIXTURE_ACTOR_OR_SHARED_HASH) {
            throw rejected(Violation.SIGNATURE_OR_SCOPE_BINDING);
        }
        if (!contract.capabilities().equals(
                Set.of(Capability.ISOLATED_COMPARISON, Capability.BOUNDED_TELEMETRY))) {
            throw rejected(Violation.CAPABILITY_SET);
        }
        return new Decision(
                Disposition.JAVA_SIGNED_SYNTHETIC_COMPARISON,
                SinkDisposition.NO_FORMAL_SINK);
    }

    private static GuardRejectedException rejected(Violation violation) {
        return new GuardRejectedException(violation);
    }

    public record AssemblyContract(
            RuntimeMode runtimeMode,
            Allocation allocation,
            SignatureAuthority signatureAuthority,
            ScopeBinding scopeBinding,
            Set<Capability> capabilities) {

        public AssemblyContract {
            Objects.requireNonNull(runtimeMode, "runtimeMode must not be null");
            Objects.requireNonNull(allocation, "allocation must not be null");
            Objects.requireNonNull(signatureAuthority, "signatureAuthority must not be null");
            Objects.requireNonNull(scopeBinding, "scopeBinding must not be null");
            capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities must not be null"));
        }

        public static AssemblyContract disabled() {
            return new AssemblyContract(
                    RuntimeMode.DISABLED,
                    Allocation.DISABLED,
                    SignatureAuthority.NONE,
                    ScopeBinding.NONE,
                    Set.of());
        }

        public static AssemblyContract signedSynthetic() {
            return new AssemblyContract(
                    RuntimeMode.SIGNED_SYNTHETIC_SHADOW,
                    Allocation.JAVA_SIGNED_SYNTHETIC_COMPARISON,
                    SignatureAuthority.DIRECT_JAVA_ES256,
                    ScopeBinding.EXACT_FIXTURE_ACTOR_OR_SHARED_HASH,
                    Set.of(Capability.ISOLATED_COMPARISON, Capability.BOUNDED_TELEMETRY));
        }
    }

    public enum RuntimeMode { DISABLED, SIGNED_SYNTHETIC_SHADOW, REAL_CASE_SHADOW, ACTIVE }
    public enum Allocation { DISABLED, JAVA_SIGNED_SYNTHETIC_COMPARISON, TEMPORAL }
    public enum SignatureAuthority { NONE, DIRECT_JAVA_ES256 }
    public enum ScopeBinding { NONE, EXACT_FIXTURE_ACTOR_OR_SHARED_HASH }
    public enum Capability {
        ISOLATED_COMPARISON(false), BOUNDED_TELEMETRY(false), FORMAL_FINALIZER(true), REAL_CASE_RESOLVER(true);
        private final boolean formal;
        Capability(boolean formal) { this.formal = formal; }
        boolean formal() { return formal; }
    }
    public enum Disposition { NO_EXECUTION, JAVA_SIGNED_SYNTHETIC_COMPARISON }
    public enum SinkDisposition { NO_FORMAL_SINK }
    public enum Violation {
        TEMPORAL_ALLOCATION,
        FORMAL_SINK_REACHABLE,
        DISABLED_REACHABILITY,
        ACTIVE_OR_REAL_RUNTIME,
        SIGNATURE_OR_SCOPE_BINDING,
        CAPABILITY_SET
    }
    public record Decision(Disposition disposition, SinkDisposition sinkDisposition) {}

    public static final class GuardRejectedException extends IllegalStateException {
        private final Violation violation;
        private GuardRejectedException(Violation violation) {
            super("Hearing synthetic assembly rejected: " + violation);
            this.violation = violation;
        }
        public Violation violation() { return violation; }
    }
}
