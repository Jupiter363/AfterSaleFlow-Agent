from __future__ import annotations

from hashlib import sha256
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
JAVA_MAIN = (
    ROOT
    / "java-api-service/src/main/java/com/example/dispute/workflow/shadow/evidence"
)
JAVA_TEST = (
    ROOT
    / "java-api-service/src/test/java/com/example/dispute/workflow/shadow/evidence"
)
BULKHEAD = JAVA_MAIN / "EvidenceBulkheadPolicy.java"
NO_SINK = JAVA_MAIN / "EvidenceNoFormalSinkGuard.java"
BULKHEAD_TEST = JAVA_TEST / "EvidenceBulkheadPolicyTest.java"
NO_SINK_TEST = JAVA_TEST / "EvidenceNoFormalSinkGuardTest.java"
PYTHON_AGENT = ROOT / "python-agent-service"
GRAPH_BASE_MIGRATION = PYTHON_AGENT / "migrations/graph/G004_graph_fanout_bulkhead.sql"
GRAPH_HARDENING_MIGRATION = (
    PYTHON_AGENT / "migrations/graph/G005_graph_fanout_fairness_and_cancellation.sql"
)
POSTGRES_BULKHEAD = PYTHON_AGENT / "app/graph_runtime/postgres_bulkhead.py"
EVIDENCE_RUNTIME = PYTHON_AGENT / "app/graphs/evidence/runtime.py"
EVIDENCE_GRAPH = PYTHON_AGENT / "app/graphs/evidence/graph.py"
EVIDENCE_NODES = PYTHON_AGENT / "app/graphs/evidence/nodes.py"
GRAPH_LIFECYCLE = PYTHON_AGENT / "app/api/graph_lifecycle.py"

AUTHORITY_ORDER = [
    "VERIFY_ROOM_GRAPH_COMMAND_SCHEMA_AND_REQUEST_HASH",
    "LOAD_EXACT_IMMUTABLE_MANIFEST_URI",
    "VERIFY_FULL_SNAPSHOT_PAYLOAD_SHA256_AND_SIZE",
    "VERIFY_INTERNAL_MANIFEST_RFC8785_SELF_HASH",
    "VERIFY_DIRECT_JAVA_ES256_MANIFEST_SIGNATURE",
    "DERIVE_AND_MATCH_RFC8785_ACTOR_SCOPE_HASH",
    "VERIFY_TRANSPORT_AND_REGISTRY_TERMINAL_OUTPUT_PIN",
    "VERIFY_INTERNAL_ITEM_ASSESSMENT_OUTPUT_PIN",
    "ENFORCE_DISTINCT_JAVA_ROOM_AND_GRAPH_LEASE_FENCES",
]


def test_e0_owned_java_contracts_exist_and_are_not_auto_discovered() -> None:
    for path in (BULKHEAD, NO_SINK, BULKHEAD_TEST, NO_SINK_TEST):
        assert path.is_file(), path

    production = "\n".join(
        path.read_text(encoding="utf-8") for path in (BULKHEAD, NO_SINK)
    )
    assert not re.search(
        r"(?m)^\s*@(?:Bean|Component|Configuration|Repository|Service)\b",
        production,
    )
    assert "io.temporal" not in production
    assert "org.springframework" not in production
    assert "com.example.dispute.evidence.application" not in production


def test_bulkhead_is_fair_bounded_cancellable_and_releases_exactly_once() -> None:
    source = BULKHEAD.read_text(encoding="utf-8")
    test_source = BULKHEAD_TEST.read_text(encoding="utf-8")

    for required in (
        "MAX_ROOM_CONCURRENCY = 8",
        "MAX_ACQUIRE_TIMEOUT",
        "new ReentrantLock(true)",
        "tenantQueueCapacity",
        "globalQueueCapacity",
        "waiters.peekFirst() == waiter",
        "awaitNanos",
        "catch (InterruptedException cancelled)",
        "remove(waiter)",
        "AtomicBoolean released",
        "released.compareAndSet(false, true)",
        "does not claim cross-replica GRAPH-016 coordination",
    ):
        assert required in source

    for required in (
        "admitsQueuedRequestsInGlobalFifoOrder",
        "timeoutAndInterruptedCancellationRemoveQueueEntries",
        "boundsGlobalAndTenantQueuesWithClosedReasonLabels",
        "leaseReleaseIsIdempotentAndMetricsNeverUseRoomOrTenantIds",
    ):
        assert required in test_source


def test_bulkhead_metric_labels_are_a_closed_bounded_enum_product() -> None:
    source = BULKHEAD.read_text(encoding="utf-8")
    labels_start = source.index("public record MetricLabels")
    labels_end = source.index("public static final class BulkheadRejectedException")
    labels = source[labels_start:labels_end]

    assert 'Map.of("component", component, "outcome", outcome, "scope", scope)' in labels
    assert "tenantId" not in labels
    assert "roomId" not in labels
    assert set(re.findall(r'\b(?:ADMITTED|REJECTED|TIMED_OUT|CANCELLED|RELEASED)\("', source)) == {
        'ADMITTED("',
        'REJECTED("',
        'TIMED_OUT("',
        'CANCELLED("',
        'RELEASED("',
    }


def test_no_sink_guard_pins_direct_java_authority_and_exact_validation_order() -> None:
    source = NO_SINK.read_text(encoding="utf-8")
    order_start = source.index("public static List<AuthorityValidationStep>")
    order_end = source.index("private static void rejectIf", order_start)
    order_source = source[order_start:order_end]
    actual_order = re.findall(r"AuthorityValidationStep\.([A-Z0-9_]+)", order_source)

    assert actual_order == AUTHORITY_ORDER
    for required in (
        '"evidence-batch-proposal.v1"',
        '"evidence-item-assessment.v1"',
        '"RFC8785_SHA256_OF_VERIFIED_ROOM_GRAPH_COMMAND_ACTOR_SCOPE"',
        "DIRECT_JAVA_ES256_SIGNATURE",
        "SIGNED_MANIFEST_JAVA_ROOM_FENCE",
        "CURRENT_GRAPH_LEASE_FENCE",
        "!fenceTokensInterchangeable",
    ):
        assert required in source


def test_no_sink_guard_rejects_unsafe_contracts_before_closed_assembly_creation() -> None:
    source = NO_SINK.read_text(encoding="utf-8")
    test_source = NO_SINK_TEST.read_text(encoding="utf-8")
    assembly_creation = source.index("SignedSyntheticAssembly.fromVerified(contract)")

    for required in (
        "authorizationProofRefPresent()",
        "ExecutionAllocation.TEMPORAL",
        "ReachableCapability::formalWriter",
        "Violation.FORMAL_WRITER_REACHABLE",
    ):
        assert required in source
        assert source.index(required) < assembly_creation

    for required in (
        "disabledModeProducesNoAssembly",
        "rejectsTemporalOrActiveAllocationBeforeAssembly",
        "rejectsFormalWriterReachabilityAndAuthorizationProofReferenceBeforeAssembly",
        "rejectsAuthorityOrderManifestAuthorityAndActorScopeDrift",
        "rejectsTerminalTransportRegistryOrItemAssessmentPinDrift",
        "rejectsInterchangeableOrSourceDriftedFences",
        "closedAssemblyCannotReachApplicationServiceWriterOrExecutableCallback",
    ):
        assert required in test_source

    assert "FORMAL_EVIDENCE_WRITER(true)" in source
    assert "FORMAL_EVIDENCE_FINALIZATION_WRITER(true)" in source
    assert "case DISABLED -> verifyDisabled(contract)" in source
    assert "case SHADOW -> verifySignedSyntheticShadow(contract)" in source
    assert "case ACTIVE -> throw rejected(Violation.ACTIVE_RUNTIME)" in source


def test_closed_assembly_has_no_caller_executable_or_application_dependency_slot() -> None:
    source = NO_SINK.read_text(encoding="utf-8")
    test_source = NO_SINK_TEST.read_text(encoding="utf-8")
    assembly_start = source.index("public static final class SignedSyntheticAssembly")
    assembly_end = source.index("public record ActorScopeHashPin", assembly_start)
    assembly = source[assembly_start:assembly_end]

    for forbidden in (
        "java.util.function",
        "Supplier<",
        "Runnable",
        "Callable",
        "Class.forName",
        "ServiceLoader",
        "BeanFactory",
    ):
        assert forbidden not in source
    assert "Object " not in assembly
    assert "ReachableCapability" not in assembly.split("fromVerified", 1)[0]
    assert "Set<SyntheticCapability> capabilities" in assembly
    assert "private SignedSyntheticAssembly(" in assembly
    assert "method.getParameterTypes()" in test_source
    assert "getDirectDependenciesFromSelf()" in test_source


def test_e1_adds_an_additive_postgresql_graph_fanout_contract() -> None:
    """GRAPH-016 is cross-replica Graph PostgreSQL work, never the Java local fallback."""
    for path in (GRAPH_BASE_MIGRATION, GRAPH_HARDENING_MIGRATION, POSTGRES_BULKHEAD):
        assert path.is_file(), path

    base = GRAPH_BASE_MIGRATION.read_text(encoding="utf-8").lower()
    hardening = GRAPH_HARDENING_MIGRATION.read_text(encoding="utf-8").lower()
    source = POSTGRES_BULKHEAD.read_text(encoding="utf-8")
    normalized = source.lower()

    # G004 is already accepted and immutable; G005 owns all follow-up hardening.
    assert sha256(GRAPH_BASE_MIGRATION.read_bytes()).hexdigest() == (
        "f1b631cd6eb8a704c4a48b36fcfc422f22ea1efc349b4aabc72ca53e61c1a551"
    )
    assert "create table" in base
    for required in ("permit", "queue", "lease", "fenc"):
        assert required in base
    assert "agent_graph_fanout_turn_sequence" not in base
    assert "drop table" not in base
    assert "drop table" not in hardening

    for public_type in (
        "PostgresGraphFanoutBulkhead",
        "PostgresBulkheadConfig",
        "GraphBulkheadScope",
        "GraphPermitFenceContext",
        "PostgresBulkheadPermit",
    ):
        assert public_type in source

    # The database arbitrates a fair durable queue and atomically owns all three limits.
    assert re.search(
        r"for update(?:\s+of\s+\w+(?:\s*,\s*\w+)*)?\s+skip locked",
        hardening,
    )
    for required in (
        "ORDER BY",
        "enqueued",
        "room",
        "tenant",
        "global",
        "queue",
        "timed_out",
    ):
        assert required.lower() in "\n".join((base, hardening))
    assert "agent_graph_fanout_turn_sequence" in hardening
    assert "agent_graph_register_fanout_tenant_turn" in hardening
    assert "order by first_queue_sequence, tenant_key" in hardening
    assert "perform agent_graph_register_fanout_tenant_turn" in hardening
    assert "trg_agent_graph_register_fanout_tenant_turn" not in hardening
    assert "join agent_graph_fanout_tenant_turn tenant_turn" in hardening
    assert re.search(
        r"order by\s+tenant_turn\.last_granted_sequence,\s*permit\.queue_sequence",
        hardening,
    )
    assert "coalesce(tenant_turn.last_granted_sequence, 0)" not in hardening
    assert "nextval('agent_graph_fanout_turn_sequence')" in hardening
    assert "earlier.room_key = permit.room_key" in hardening
    assert "earlier.queue_sequence < permit.queue_sequence" in hardening

    acquire = hardening.split(
        "create or replace function agent_graph_acquire_fanout_permit", 1
    )[1]
    acquire = acquire.split(
        "create or replace function agent_graph_finish_fanout_permit", 1
    )[0]
    assert "from agent_graph_fanout_permit_owner_generation owner_generation" in acquire
    assert "owner_generation.permit_owner_id = selected_permit_owner_id" in acquire
    assert "message = 'graph_fanout_takeover_owner_reused'" in acquire

    finish = hardening.split(
        "create or replace function agent_graph_finish_fanout_permit", 1
    )[1]
    finish = finish.split(
        "create function agent_graph_cancel_or_release_fanout_permit", 1
    )[0]
    assert "agent_graph_assert_current_fanout_lease" not in finish
    assert "lease_expires_at > clock_timestamp()" not in finish
    for required in (
        "permit_fencing_token = selected_permit_fence",
        "permit_owner_id = selected_permit_owner_id",
        "graph_lease_owner_id = selected_graph_owner_id",
        "graph_lease_fencing_token = selected_graph_fence",
    ):
        assert required in finish

    cleanup = hardening.split(
        "create function agent_graph_cancel_or_release_fanout_permit", 1
    )[1]
    assert "status in ('queued', 'granted')" in cleanup
    assert "when status = 'queued' then 'cancelled' else 'released'" in cleanup
    assert "agent_graph_assert_current_fanout_lease" not in cleanup
    for required in (
        "request_id = selected_request_id",
        "permit_owner_id = selected_permit_owner_id",
        "thread_id = selected_thread_id and command_id = selected_command_id",
        "graph_lease_owner_id = selected_graph_owner_id",
        "graph_lease_fencing_token = selected_graph_fence",
    ):
        assert required in cleanup

    # Python invokes the database contract rather than reproducing a process-local scheduler.
    for required in (
        "agent_graph_acquire_fanout_permit",
        "agent_graph_renew_fanout_permit",
        "agent_graph_finish_fanout_permit",
        "agent_graph_cancel_or_release_fanout_permit",
        "agent_graph_validate_fanout_recovery",
    ):
        assert required in normalized
        assert f"select result.* from {required}(" in normalized
    assert "select (agent_graph_" not in normalized

    # Admission cannot outlive cancellation, expiry, takeover, or a process crash.
    for required in ("cancel", "expire", "takeover", "recover", "release"):
        assert required in normalized


def test_e1_validates_distinct_permit_graph_and_java_fences() -> None:
    source = POSTGRES_BULKHEAD.read_text(encoding="utf-8")
    runtime = EVIDENCE_RUNTIME.read_text(encoding="utf-8")
    combined = "\n".join((source, runtime))

    for required in (
        "permit_fencing_token",
        "graph_lease_fencing_token",
        "java_room_fencing_token",
        "thread_id",
        "command_id",
    ):
        assert required in combined

    # A permit is valid only under the current Graph lease and its own fence is not a Java fence.
    assert "GraphPermitFenceContext" in source
    assert "EVIDENCE_RUNTIME_FENCE_BINDING_MISMATCH" in runtime


def test_e1_evidence_send_uses_the_postgres_bulkhead_without_local_fallback() -> None:
    runtime = EVIDENCE_RUNTIME.read_text(encoding="utf-8")
    graph = EVIDENCE_GRAPH.read_text(encoding="utf-8")
    nodes = EVIDENCE_NODES.read_text(encoding="utf-8")
    lifecycle = GRAPH_LIFECYCLE.read_text(encoding="utf-8")
    production_path = "\n".join((runtime, graph, nodes, lifecycle))
    bundle_factory = runtime[
        runtime.index("def build_evidence_runtime_bundle") : runtime.index(
            "\ndef validate_evidence_recovery_state"
        )
    ]

    assert "PostgresGraphFanoutBulkhead" in production_path
    assert re.search(r"\bGraphFanoutBulkhead\b", production_path) is None
    assert "EVIDENCE_RUNTIME_DISABLED" in runtime
    assert "EVIDENCE_RUNTIME_MODE_FORBIDDEN" in runtime
    assert bundle_factory.index('runtime_mode == "DISABLED"') < bundle_factory.index(
        "if not isinstance(checkpointer, FencedPostgresSaver)"
    )


def test_e1_keeps_evidence_runtime_closed_to_disabled_or_signed_synthetic_shadow() -> None:
    runtime = EVIDENCE_RUNTIME.read_text(encoding="utf-8")
    production_path = "\n".join(
        path.read_text(encoding="utf-8")
        for path in (EVIDENCE_RUNTIME, EVIDENCE_GRAPH, EVIDENCE_NODES, GRAPH_LIFECYCLE)
    )

    assert 'EvidenceRuntimeMode = Literal["DISABLED", "SIGNED_SYNTHETIC_SHADOW"]' in runtime
    assert 'runtime_mode="SIGNED_SYNTHETIC_SHADOW"' in runtime
    assert '"formal_sink_eligible": False' in runtime
    for forbidden in (
        "EvidenceGraphResultFinalizer",
        "EvidenceFinalizationLedger",
        "ExecutionAllocation.TEMPORAL",
        "formal_evidence_sink",
        "real_case_shadow",
    ):
        assert forbidden not in production_path
