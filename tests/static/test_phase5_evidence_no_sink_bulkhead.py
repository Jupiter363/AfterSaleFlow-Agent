from __future__ import annotations

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


def test_no_sink_guard_rejects_unsafe_contracts_before_factory_invocation() -> None:
    source = NO_SINK.read_text(encoding="utf-8")
    test_source = NO_SINK_TEST.read_text(encoding="utf-8")
    factory_call = source.index("factory.get()")

    for required in (
        "authorizationProofRefPresent()",
        "ExecutionAllocation.TEMPORAL",
        "ReachableCapability::formalWriter",
        "Violation.FORMAL_WRITER_REACHABLE",
    ):
        assert required in source
        assert source.index(required) < factory_call

    for required in (
        "disabledModeDoesNotInvokeAssemblyFactory",
        "rejectsTemporalOrActiveAllocationBeforeAssembly",
        "rejectsFormalWriterReachabilityAndAuthorizationProofReferenceBeforeAssembly",
        "rejectsAuthorityOrderManifestAuthorityAndActorScopeDrift",
        "rejectsTerminalTransportRegistryOrItemAssessmentPinDrift",
        "rejectsInterchangeableOrSourceDriftedFences",
    ):
        assert required in test_source

    assert "FORMAL_EVIDENCE_WRITER(true)" in source
    assert "FORMAL_EVIDENCE_FINALIZATION_WRITER(true)" in source
    assert "case DISABLED -> verifyDisabled(contract)" in source
    assert "case SHADOW -> verifySignedSyntheticShadow(contract)" in source
    assert "case ACTIVE -> throw rejected(Violation.ACTIVE_RUNTIME)" in source
