package com.example.dispute.workflow.shadow.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.shadow.evidence.EvidenceNoFormalSinkGuard.AssemblyContract;
import com.example.dispute.workflow.shadow.evidence.EvidenceNoFormalSinkGuard.AuthorityValidationStep;
import com.example.dispute.workflow.shadow.evidence.EvidenceNoFormalSinkGuard.ExecutionAllocation;
import com.example.dispute.workflow.shadow.evidence.EvidenceNoFormalSinkGuard.FenceBinding;
import com.example.dispute.workflow.shadow.evidence.EvidenceNoFormalSinkGuard.FenceSource;
import com.example.dispute.workflow.shadow.evidence.EvidenceNoFormalSinkGuard.GraphLeaseFence;
import com.example.dispute.workflow.shadow.evidence.EvidenceNoFormalSinkGuard.GuardRejectedException;
import com.example.dispute.workflow.shadow.evidence.EvidenceNoFormalSinkGuard.JavaRoomFence;
import com.example.dispute.workflow.shadow.evidence.EvidenceNoFormalSinkGuard.ManifestAuthority;
import com.example.dispute.workflow.shadow.evidence.EvidenceNoFormalSinkGuard.ReachableCapability;
import com.example.dispute.workflow.shadow.evidence.EvidenceNoFormalSinkGuard.RuntimeDisposition;
import com.example.dispute.workflow.shadow.evidence.EvidenceNoFormalSinkGuard.RuntimeMode;
import com.example.dispute.workflow.shadow.evidence.EvidenceNoFormalSinkGuard.Violation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class EvidenceNoFormalSinkGuardTest {

    private final EvidenceNoFormalSinkGuard guard = new EvidenceNoFormalSinkGuard();

    @Test
    void disabledModeDoesNotInvokeAssemblyFactory() {
        AtomicBoolean invoked = new AtomicBoolean();

        assertThat(guard.verify(AssemblyContract.disabled()).disposition())
                .isEqualTo(RuntimeDisposition.NO_EXECUTION);
        assertThat(guard.assembleIfSafe(AssemblyContract.disabled(), () -> {
                    invoked.set(true);
                    return "should-not-exist";
                }))
                .isEmpty();
        assertThat(invoked).isFalse();
    }

    @Test
    void javaSignedSyntheticShadowUsesExactAuthorityAndIndependentOutputPins() {
        AssemblyContract contract = AssemblyContract.javaSignedSyntheticShadow(41, 91);
        AtomicBoolean invoked = new AtomicBoolean();

        assertThat(guard.assembleIfSafe(contract, () -> {
                    invoked.set(true);
                    return "synthetic-comparison";
                }))
                .contains("synthetic-comparison");
        assertThat(invoked).isTrue();
        assertThat(contract.terminalTransportOutputSchemaVersion())
                .isEqualTo("evidence-batch-proposal.v1");
        assertThat(contract.graphRegistryOutputSchemaVersion())
                .isEqualTo("evidence-batch-proposal.v1");
        assertThat(contract.itemLcelParserOutputSchemaVersion())
                .isEqualTo("evidence-item-assessment.v1")
                .isNotEqualTo(contract.terminalTransportOutputSchemaVersion());
        assertThat(contract.fenceBinding().javaRoomFence()).isInstanceOf(JavaRoomFence.class);
        assertThat(contract.fenceBinding().graphLeaseFence()).isInstanceOf(GraphLeaseFence.class);
    }

    @Test
    void rejectsTemporalOrActiveAllocationBeforeAssembly() {
        assertRejected(
                Builder.shadow().allocation(ExecutionAllocation.TEMPORAL).build(),
                Violation.TEMPORAL_ALLOCATION);
        assertRejected(
                Builder.shadow().runtimeMode(RuntimeMode.ACTIVE).build(),
                Violation.ACTIVE_RUNTIME);
        assertRejected(
                Builder.shadow().allocation(ExecutionAllocation.DISABLED).build(),
                Violation.RUNTIME_ALLOCATION_MISMATCH);
    }

    @Test
    void rejectsFormalWriterReachabilityAndAuthorizationProofReferenceBeforeAssembly() {
        assertRejected(
                Builder.shadow()
                        .reachableCapabilities(Set.of(ReachableCapability.FORMAL_EVIDENCE_WRITER))
                        .build(),
                Violation.FORMAL_WRITER_REACHABLE);
        assertRejected(
                Builder.shadow()
                        .reachableCapabilities(
                                Set.of(ReachableCapability.FORMAL_EVIDENCE_FINALIZATION_WRITER))
                        .build(),
                Violation.FORMAL_WRITER_REACHABLE);
        assertRejected(
                Builder.shadow().authorizationProofRefPresent(true).build(),
                Violation.AUTHORIZATION_PROOF_REF);
    }

    @Test
    void rejectsAuthorityOrderManifestAuthorityAndActorScopeDrift() {
        List<AuthorityValidationStep> reordered =
                new ArrayList<>(EvidenceNoFormalSinkGuard.requiredAuthorityValidationOrder());
        Collections.swap(reordered, 3, 4);

        assertRejected(
                Builder.shadow().validationOrder(reordered).build(),
                Violation.AUTHORITY_VALIDATION_ORDER);
        assertRejected(
                Builder.shadow().manifestAuthority(ManifestAuthority.NONE).build(),
                Violation.MANIFEST_AUTHORITY);
        assertRejected(
                Builder.shadow().actorScopeHashSource("RAW_ACTOR_SCOPE").build(),
                Violation.ACTOR_SCOPE_HASH_SOURCE);
    }

    @Test
    void rejectsTerminalTransportRegistryOrItemAssessmentPinDrift() {
        assertRejected(
                Builder.shadow().terminalTransportOutputSchemaVersion(
                                "evidence-item-assessment.v1")
                        .build(),
                Violation.TERMINAL_OUTPUT_SCHEMA_PIN);
        assertRejected(
                Builder.shadow().graphRegistryOutputSchemaVersion(
                                "evidence-item-assessment.v1")
                        .build(),
                Violation.TERMINAL_OUTPUT_SCHEMA_PIN);
        assertRejected(
                Builder.shadow().itemLcelParserOutputSchemaVersion(
                                "evidence-batch-proposal.v1")
                        .build(),
                Violation.ITEM_ASSESSMENT_OUTPUT_SCHEMA_PIN);
    }

    @Test
    void rejectsInterchangeableOrSourceDriftedFences() {
        assertRejected(
                Builder.shadow()
                        .fenceBinding(new FenceBinding(
                                FenceSource.CURRENT_GRAPH_LEASE_FENCE,
                                FenceSource.SIGNED_MANIFEST_JAVA_ROOM_FENCE,
                                new JavaRoomFence(41),
                                new GraphLeaseFence(91),
                                false))
                        .build(),
                Violation.FENCE_BINDING);
        assertRejected(
                Builder.shadow()
                        .fenceBinding(new FenceBinding(
                                FenceSource.SIGNED_MANIFEST_JAVA_ROOM_FENCE,
                                FenceSource.CURRENT_GRAPH_LEASE_FENCE,
                                new JavaRoomFence(41),
                                new GraphLeaseFence(91),
                                true))
                        .build(),
                Violation.FENCE_BINDING);
        assertRejected(
                Builder.shadow().fenceBinding(FenceBinding.signedSynthetic(0, 91)).build(),
                Violation.FENCE_BINDING);
    }

    @Test
    void disabledContractCannotDeclareAnyReachableCapability() {
        assertRejected(
                Builder.from(AssemblyContract.disabled())
                        .reachableCapabilities(Set.of(ReachableCapability.BOUNDED_TELEMETRY))
                        .build(),
                Violation.DISABLED_REACHABILITY);
    }

    private void assertRejected(AssemblyContract contract, Violation violation) {
        AtomicBoolean invoked = new AtomicBoolean();
        assertThatThrownBy(() -> guard.assembleIfSafe(contract, () -> {
                    invoked.set(true);
                    return "unsafe";
                }))
                .isInstanceOfSatisfying(
                        GuardRejectedException.class,
                        rejected -> assertThat(rejected.violation()).isEqualTo(violation));
        assertThat(invoked).isFalse();
    }

    private static final class Builder {

        private RuntimeMode runtimeMode;
        private ExecutionAllocation allocation;
        private ManifestAuthority manifestAuthority;
        private List<AuthorityValidationStep> validationOrder;
        private String actorScopeHashSource;
        private String terminalTransportOutputSchemaVersion;
        private String graphRegistryOutputSchemaVersion;
        private String itemLcelParserOutputSchemaVersion;
        private FenceBinding fenceBinding;
        private boolean authorizationProofRefPresent;
        private Set<ReachableCapability> reachableCapabilities;

        private static Builder shadow() {
            return from(AssemblyContract.javaSignedSyntheticShadow(41, 91));
        }

        private static Builder from(AssemblyContract contract) {
            Builder builder = new Builder();
            builder.runtimeMode = contract.runtimeMode();
            builder.allocation = contract.allocation();
            builder.manifestAuthority = contract.manifestAuthority();
            builder.validationOrder = contract.validationOrder();
            builder.actorScopeHashSource = contract.actorScopeHashSource();
            builder.terminalTransportOutputSchemaVersion =
                    contract.terminalTransportOutputSchemaVersion();
            builder.graphRegistryOutputSchemaVersion =
                    contract.graphRegistryOutputSchemaVersion();
            builder.itemLcelParserOutputSchemaVersion =
                    contract.itemLcelParserOutputSchemaVersion();
            builder.fenceBinding = contract.fenceBinding();
            builder.authorizationProofRefPresent = contract.authorizationProofRefPresent();
            builder.reachableCapabilities = contract.reachableCapabilities();
            return builder;
        }

        private Builder runtimeMode(RuntimeMode value) {
            runtimeMode = value;
            return this;
        }

        private Builder allocation(ExecutionAllocation value) {
            allocation = value;
            return this;
        }

        private Builder manifestAuthority(ManifestAuthority value) {
            manifestAuthority = value;
            return this;
        }

        private Builder validationOrder(List<AuthorityValidationStep> value) {
            validationOrder = value;
            return this;
        }

        private Builder actorScopeHashSource(String value) {
            actorScopeHashSource = value;
            return this;
        }

        private Builder terminalTransportOutputSchemaVersion(String value) {
            terminalTransportOutputSchemaVersion = value;
            return this;
        }

        private Builder graphRegistryOutputSchemaVersion(String value) {
            graphRegistryOutputSchemaVersion = value;
            return this;
        }

        private Builder itemLcelParserOutputSchemaVersion(String value) {
            itemLcelParserOutputSchemaVersion = value;
            return this;
        }

        private Builder fenceBinding(FenceBinding value) {
            fenceBinding = value;
            return this;
        }

        private Builder authorizationProofRefPresent(boolean value) {
            authorizationProofRefPresent = value;
            return this;
        }

        private Builder reachableCapabilities(Set<ReachableCapability> value) {
            reachableCapabilities = value;
            return this;
        }

        private AssemblyContract build() {
            return new AssemblyContract(
                    runtimeMode,
                    allocation,
                    manifestAuthority,
                    validationOrder,
                    actorScopeHashSource,
                    terminalTransportOutputSchemaVersion,
                    graphRegistryOutputSchemaVersion,
                    itemLcelParserOutputSchemaVersion,
                    fenceBinding,
                    authorizationProofRefPresent,
                    reachableCapabilities);
        }
    }
}
