package com.example.dispute.workflow.shadow.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.shadow.hearing.HearingNoFormalSinkGuard.Allocation;
import com.example.dispute.workflow.shadow.hearing.HearingNoFormalSinkGuard.AssemblyContract;
import com.example.dispute.workflow.shadow.hearing.HearingNoFormalSinkGuard.Capability;
import com.example.dispute.workflow.shadow.hearing.HearingNoFormalSinkGuard.RuntimeMode;
import com.example.dispute.workflow.shadow.hearing.HearingNoFormalSinkGuard.ScopeBinding;
import com.example.dispute.workflow.shadow.hearing.HearingNoFormalSinkGuard.SignatureAuthority;
import com.example.dispute.workflow.shadow.hearing.HearingNoFormalSinkGuard.Violation;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HearingNoFormalSinkGuardTest {

    private final HearingNoFormalSinkGuard guard = new HearingNoFormalSinkGuard();

    @Test
    void disabledAndSignedSyntheticAreTheOnlyClosedAssemblies() {
        assertThat(guard.verify(AssemblyContract.disabled()).sinkDisposition())
                .isEqualTo(HearingNoFormalSinkGuard.SinkDisposition.NO_FORMAL_SINK);
        assertThat(guard.verify(AssemblyContract.signedSynthetic()).sinkDisposition())
                .isEqualTo(HearingNoFormalSinkGuard.SinkDisposition.NO_FORMAL_SINK);
    }

    @Test
    void rejectsTemporalRealCaseAndFormalCapabilities() {
        assertRejected(new AssemblyContract(
                RuntimeMode.SIGNED_SYNTHETIC_SHADOW,
                Allocation.TEMPORAL,
                SignatureAuthority.DIRECT_JAVA_ES256,
                ScopeBinding.EXACT_FIXTURE_ACTOR_OR_SHARED_HASH,
                Set.of()), Violation.TEMPORAL_ALLOCATION);
        assertRejected(new AssemblyContract(
                RuntimeMode.REAL_CASE_SHADOW,
                Allocation.JAVA_SIGNED_SYNTHETIC_COMPARISON,
                SignatureAuthority.DIRECT_JAVA_ES256,
                ScopeBinding.EXACT_FIXTURE_ACTOR_OR_SHARED_HASH,
                Set.of(Capability.ISOLATED_COMPARISON, Capability.BOUNDED_TELEMETRY)),
                Violation.ACTIVE_OR_REAL_RUNTIME);
        assertRejected(new AssemblyContract(
                RuntimeMode.SIGNED_SYNTHETIC_SHADOW,
                Allocation.JAVA_SIGNED_SYNTHETIC_COMPARISON,
                SignatureAuthority.DIRECT_JAVA_ES256,
                ScopeBinding.EXACT_FIXTURE_ACTOR_OR_SHARED_HASH,
                Set.of(Capability.FORMAL_FINALIZER)), Violation.FORMAL_SINK_REACHABLE);
        assertRejected(new AssemblyContract(
                RuntimeMode.SIGNED_SYNTHETIC_SHADOW,
                Allocation.JAVA_SIGNED_SYNTHETIC_COMPARISON,
                SignatureAuthority.DIRECT_JAVA_ES256,
                ScopeBinding.EXACT_FIXTURE_ACTOR_OR_SHARED_HASH,
                Set.of(Capability.REAL_CASE_RESOLVER)), Violation.FORMAL_SINK_REACHABLE);
    }

    @Test
    void signatureAndScopeBindingAreMandatory() {
        assertRejected(new AssemblyContract(
                RuntimeMode.SIGNED_SYNTHETIC_SHADOW,
                Allocation.JAVA_SIGNED_SYNTHETIC_COMPARISON,
                SignatureAuthority.NONE,
                ScopeBinding.EXACT_FIXTURE_ACTOR_OR_SHARED_HASH,
                Set.of(Capability.ISOLATED_COMPARISON, Capability.BOUNDED_TELEMETRY)),
                Violation.SIGNATURE_OR_SCOPE_BINDING);
    }

    private void assertRejected(AssemblyContract contract, Violation violation) {
        assertThatThrownBy(() -> guard.verify(contract))
                .isInstanceOfSatisfying(
                        HearingNoFormalSinkGuard.GuardRejectedException.class,
                        rejected -> assertThat(rejected.violation()).isEqualTo(violation));
    }
}
