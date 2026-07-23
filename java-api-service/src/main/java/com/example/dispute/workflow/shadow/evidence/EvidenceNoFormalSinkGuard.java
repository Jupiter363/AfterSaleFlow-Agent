package com.example.dispute.workflow.shadow.evidence;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Fail-closed assembly guard for disabled or Java-signed synthetic Evidence execution only. */
public final class EvidenceNoFormalSinkGuard {

    public static final String TERMINAL_OUTPUT_SCHEMA_VERSION = "evidence-batch-proposal.v1";
    public static final String ITEM_ASSESSMENT_OUTPUT_SCHEMA_VERSION =
            "evidence-item-assessment.v1";
    public static final String ACTOR_SCOPE_HASH_SOURCE =
            "RFC8785_SHA256_OF_VERIFIED_ROOM_GRAPH_COMMAND_ACTOR_SCOPE";
    private static final String NONE = "none";

    public Decision verify(AssemblyContract contract) {
        Objects.requireNonNull(contract, "contract must not be null");
        rejectIf(contract.authorizationProofRefPresent(), Violation.AUTHORIZATION_PROOF_REF);
        rejectIf(
                contract.allocation() == ExecutionAllocation.TEMPORAL,
                Violation.TEMPORAL_ALLOCATION);
        rejectIf(
                contract.reachableCapabilities().stream()
                        .anyMatch(ReachableCapability::formalWriter),
                Violation.FORMAL_WRITER_REACHABLE);

        return switch (contract.runtimeMode()) {
            case DISABLED -> verifyDisabled(contract);
            case SHADOW -> verifySignedSyntheticShadow(contract);
            case ACTIVE -> throw rejected(Violation.ACTIVE_RUNTIME);
        };
    }

    /**
     * Verifies the complete contract before invoking the synthetic assembly factory. Disabled mode
     * returns empty and never calls the factory.
     */
    public <T> Optional<T> assembleIfSafe(AssemblyContract contract, Supplier<T> factory) {
        Objects.requireNonNull(factory, "factory must not be null");
        Decision decision = verify(contract);
        if (decision.disposition() == RuntimeDisposition.NO_EXECUTION) {
            return Optional.empty();
        }
        return Optional.of(Objects.requireNonNull(
                factory.get(), "synthetic assembly factory must not return null"));
    }

    private Decision verifyDisabled(AssemblyContract contract) {
        rejectIf(
                contract.allocation() != ExecutionAllocation.DISABLED,
                Violation.RUNTIME_ALLOCATION_MISMATCH);
        rejectIf(
                contract.manifestAuthority() != ManifestAuthority.NONE,
                Violation.MANIFEST_AUTHORITY);
        rejectIf(!contract.validationOrder().isEmpty(), Violation.AUTHORITY_VALIDATION_ORDER);
        rejectIf(!NONE.equals(contract.actorScopeHashSource()), Violation.ACTOR_SCOPE_HASH_SOURCE);
        rejectIf(
                !NONE.equals(contract.terminalTransportOutputSchemaVersion())
                        || !NONE.equals(contract.graphRegistryOutputSchemaVersion())
                        || !NONE.equals(contract.itemLcelParserOutputSchemaVersion()),
                Violation.OUTPUT_SCHEMA_PIN);
        rejectIf(!contract.fenceBinding().disabled(), Violation.FENCE_BINDING);
        rejectIf(!contract.reachableCapabilities().isEmpty(), Violation.DISABLED_REACHABILITY);
        return new Decision(RuntimeDisposition.NO_EXECUTION, SinkDisposition.NO_FORMAL_SINK);
    }

    private Decision verifySignedSyntheticShadow(AssemblyContract contract) {
        rejectIf(
                contract.allocation() != ExecutionAllocation.JAVA_SIGNED_SYNTHETIC_SHADOW,
                Violation.RUNTIME_ALLOCATION_MISMATCH);
        rejectIf(
                contract.manifestAuthority() != ManifestAuthority.DIRECT_JAVA_ES256_SIGNATURE,
                Violation.MANIFEST_AUTHORITY);
        rejectIf(
                !contract.validationOrder().equals(requiredAuthorityValidationOrder()),
                Violation.AUTHORITY_VALIDATION_ORDER);
        rejectIf(
                !ACTOR_SCOPE_HASH_SOURCE.equals(contract.actorScopeHashSource()),
                Violation.ACTOR_SCOPE_HASH_SOURCE);
        rejectIf(
                !TERMINAL_OUTPUT_SCHEMA_VERSION.equals(
                                contract.terminalTransportOutputSchemaVersion())
                        || !TERMINAL_OUTPUT_SCHEMA_VERSION.equals(
                                contract.graphRegistryOutputSchemaVersion()),
                Violation.TERMINAL_OUTPUT_SCHEMA_PIN);
        rejectIf(
                !ITEM_ASSESSMENT_OUTPUT_SCHEMA_VERSION.equals(
                        contract.itemLcelParserOutputSchemaVersion()),
                Violation.ITEM_ASSESSMENT_OUTPUT_SCHEMA_PIN);
        rejectIf(!contract.fenceBinding().signedSynthetic(), Violation.FENCE_BINDING);
        return new Decision(
                RuntimeDisposition.JAVA_SIGNED_SYNTHETIC_SHADOW,
                SinkDisposition.NO_FORMAL_SINK);
    }

    public static List<AuthorityValidationStep> requiredAuthorityValidationOrder() {
        return List.of(
                AuthorityValidationStep.VERIFY_ROOM_GRAPH_COMMAND_SCHEMA_AND_REQUEST_HASH,
                AuthorityValidationStep.LOAD_EXACT_IMMUTABLE_MANIFEST_URI,
                AuthorityValidationStep.VERIFY_FULL_SNAPSHOT_PAYLOAD_SHA256_AND_SIZE,
                AuthorityValidationStep.VERIFY_INTERNAL_MANIFEST_RFC8785_SELF_HASH,
                AuthorityValidationStep.VERIFY_DIRECT_JAVA_ES256_MANIFEST_SIGNATURE,
                AuthorityValidationStep.DERIVE_AND_MATCH_RFC8785_ACTOR_SCOPE_HASH,
                AuthorityValidationStep.VERIFY_TRANSPORT_AND_REGISTRY_TERMINAL_OUTPUT_PIN,
                AuthorityValidationStep.VERIFY_INTERNAL_ITEM_ASSESSMENT_OUTPUT_PIN,
                AuthorityValidationStep.ENFORCE_DISTINCT_JAVA_ROOM_AND_GRAPH_LEASE_FENCES);
    }

    private static void rejectIf(boolean rejected, Violation violation) {
        if (rejected) {
            throw rejected(violation);
        }
    }

    private static GuardRejectedException rejected(Violation violation) {
        return new GuardRejectedException(violation);
    }

    public record AssemblyContract(
            RuntimeMode runtimeMode,
            ExecutionAllocation allocation,
            ManifestAuthority manifestAuthority,
            List<AuthorityValidationStep> validationOrder,
            String actorScopeHashSource,
            String terminalTransportOutputSchemaVersion,
            String graphRegistryOutputSchemaVersion,
            String itemLcelParserOutputSchemaVersion,
            FenceBinding fenceBinding,
            boolean authorizationProofRefPresent,
            Set<ReachableCapability> reachableCapabilities) {

        public AssemblyContract {
            Objects.requireNonNull(runtimeMode, "runtimeMode must not be null");
            Objects.requireNonNull(allocation, "allocation must not be null");
            Objects.requireNonNull(manifestAuthority, "manifestAuthority must not be null");
            validationOrder = List.copyOf(
                    Objects.requireNonNull(validationOrder, "validationOrder must not be null"));
            Objects.requireNonNull(actorScopeHashSource, "actorScopeHashSource must not be null");
            Objects.requireNonNull(
                    terminalTransportOutputSchemaVersion,
                    "terminalTransportOutputSchemaVersion must not be null");
            Objects.requireNonNull(
                    graphRegistryOutputSchemaVersion,
                    "graphRegistryOutputSchemaVersion must not be null");
            Objects.requireNonNull(
                    itemLcelParserOutputSchemaVersion,
                    "itemLcelParserOutputSchemaVersion must not be null");
            Objects.requireNonNull(fenceBinding, "fenceBinding must not be null");
            reachableCapabilities = Set.copyOf(Objects.requireNonNull(
                    reachableCapabilities, "reachableCapabilities must not be null"));
        }

        public static AssemblyContract disabled() {
            return new AssemblyContract(
                    RuntimeMode.DISABLED,
                    ExecutionAllocation.DISABLED,
                    ManifestAuthority.NONE,
                    List.of(),
                    NONE,
                    NONE,
                    NONE,
                    NONE,
                    FenceBinding.disabledBinding(),
                    false,
                    Set.of());
        }

        public static AssemblyContract javaSignedSyntheticShadow(
                long javaRoomFence, long graphLeaseFence) {
            return new AssemblyContract(
                    RuntimeMode.SHADOW,
                    ExecutionAllocation.JAVA_SIGNED_SYNTHETIC_SHADOW,
                    ManifestAuthority.DIRECT_JAVA_ES256_SIGNATURE,
                    requiredAuthorityValidationOrder(),
                    ACTOR_SCOPE_HASH_SOURCE,
                    TERMINAL_OUTPUT_SCHEMA_VERSION,
                    TERMINAL_OUTPUT_SCHEMA_VERSION,
                    ITEM_ASSESSMENT_OUTPUT_SCHEMA_VERSION,
                    FenceBinding.signedSynthetic(javaRoomFence, graphLeaseFence),
                    false,
                    Set.of(
                            ReachableCapability.SYNTHETIC_COMPARISON_LEDGER,
                            ReachableCapability.BOUNDED_TELEMETRY));
        }
    }

    public record FenceBinding(
            FenceSource javaRoomFenceSource,
            FenceSource graphLeaseFenceSource,
            JavaRoomFence javaRoomFence,
            GraphLeaseFence graphLeaseFence,
            boolean fenceTokensInterchangeable) {

        public FenceBinding {
            Objects.requireNonNull(
                    javaRoomFenceSource, "javaRoomFenceSource must not be null");
            Objects.requireNonNull(
                    graphLeaseFenceSource, "graphLeaseFenceSource must not be null");
            Objects.requireNonNull(javaRoomFence, "javaRoomFence must not be null");
            Objects.requireNonNull(graphLeaseFence, "graphLeaseFence must not be null");
        }

        public static FenceBinding disabledBinding() {
            return new FenceBinding(
                    FenceSource.NONE,
                    FenceSource.NONE,
                    new JavaRoomFence(0),
                    new GraphLeaseFence(0),
                    false);
        }

        public static FenceBinding signedSynthetic(long javaRoomFence, long graphLeaseFence) {
            return new FenceBinding(
                    FenceSource.SIGNED_MANIFEST_JAVA_ROOM_FENCE,
                    FenceSource.CURRENT_GRAPH_LEASE_FENCE,
                    new JavaRoomFence(javaRoomFence),
                    new GraphLeaseFence(graphLeaseFence),
                    false);
        }

        private boolean disabled() {
            return javaRoomFenceSource == FenceSource.NONE
                    && graphLeaseFenceSource == FenceSource.NONE
                    && javaRoomFence.token() == 0
                    && graphLeaseFence.token() == 0
                    && !fenceTokensInterchangeable;
        }

        private boolean signedSynthetic() {
            return javaRoomFenceSource == FenceSource.SIGNED_MANIFEST_JAVA_ROOM_FENCE
                    && graphLeaseFenceSource == FenceSource.CURRENT_GRAPH_LEASE_FENCE
                    && javaRoomFence.token() > 0
                    && graphLeaseFence.token() > 0
                    && !fenceTokensInterchangeable;
        }
    }

    public record JavaRoomFence(long token) {

        public JavaRoomFence {
            if (token < 0) {
                throw new IllegalArgumentException("Java room fence must not be negative");
            }
        }
    }

    public record GraphLeaseFence(long token) {

        public GraphLeaseFence {
            if (token < 0) {
                throw new IllegalArgumentException("Graph lease fence must not be negative");
            }
        }
    }

    public enum RuntimeMode {
        DISABLED,
        SHADOW,
        ACTIVE
    }

    public enum ExecutionAllocation {
        DISABLED,
        JAVA_SIGNED_SYNTHETIC_SHADOW,
        TEMPORAL
    }

    public enum ManifestAuthority {
        NONE,
        DIRECT_JAVA_ES256_SIGNATURE
    }

    public enum FenceSource {
        NONE,
        SIGNED_MANIFEST_JAVA_ROOM_FENCE,
        CURRENT_GRAPH_LEASE_FENCE
    }

    public enum ReachableCapability {
        SYNTHETIC_COMPARISON_LEDGER(false),
        BOUNDED_TELEMETRY(false),
        FORMAL_EVIDENCE_WRITER(true),
        FORMAL_EVIDENCE_FINALIZATION_WRITER(true);

        private final boolean formalWriter;

        ReachableCapability(boolean formalWriter) {
            this.formalWriter = formalWriter;
        }

        public boolean formalWriter() {
            return formalWriter;
        }
    }

    public enum AuthorityValidationStep {
        VERIFY_ROOM_GRAPH_COMMAND_SCHEMA_AND_REQUEST_HASH,
        LOAD_EXACT_IMMUTABLE_MANIFEST_URI,
        VERIFY_FULL_SNAPSHOT_PAYLOAD_SHA256_AND_SIZE,
        VERIFY_INTERNAL_MANIFEST_RFC8785_SELF_HASH,
        VERIFY_DIRECT_JAVA_ES256_MANIFEST_SIGNATURE,
        DERIVE_AND_MATCH_RFC8785_ACTOR_SCOPE_HASH,
        VERIFY_TRANSPORT_AND_REGISTRY_TERMINAL_OUTPUT_PIN,
        VERIFY_INTERNAL_ITEM_ASSESSMENT_OUTPUT_PIN,
        ENFORCE_DISTINCT_JAVA_ROOM_AND_GRAPH_LEASE_FENCES
    }

    public enum RuntimeDisposition {
        NO_EXECUTION,
        JAVA_SIGNED_SYNTHETIC_SHADOW
    }

    public enum SinkDisposition {
        NO_FORMAL_SINK
    }

    public enum Violation {
        ACTIVE_RUNTIME,
        TEMPORAL_ALLOCATION,
        RUNTIME_ALLOCATION_MISMATCH,
        MANIFEST_AUTHORITY,
        AUTHORITY_VALIDATION_ORDER,
        ACTOR_SCOPE_HASH_SOURCE,
        OUTPUT_SCHEMA_PIN,
        TERMINAL_OUTPUT_SCHEMA_PIN,
        ITEM_ASSESSMENT_OUTPUT_SCHEMA_PIN,
        FENCE_BINDING,
        AUTHORIZATION_PROOF_REF,
        FORMAL_WRITER_REACHABLE,
        DISABLED_REACHABILITY
    }

    public record Decision(RuntimeDisposition disposition, SinkDisposition sinkDisposition) {}

    public static final class GuardRejectedException extends IllegalStateException {

        private final Violation violation;

        private GuardRejectedException(Violation violation) {
            super("Evidence synthetic assembly rejected: " + violation.name());
            this.violation = violation;
        }

        public Violation violation() {
            return violation;
        }
    }
}
