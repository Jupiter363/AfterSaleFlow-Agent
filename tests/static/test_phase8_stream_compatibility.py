from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
JAVA_ROOT = ROOT / "java-api-service" / "src" / "main" / "java"
PERSISTENCE = (
    JAVA_ROOT
    / "com"
    / "example"
    / "dispute"
    / "agentstream"
    / "infrastructure"
    / "persistence"
)
APPLICATION = (
    JAVA_ROOT
    / "com"
    / "example"
    / "dispute"
    / "agentstream"
    / "application"
)
TEST_ROOT = (
    ROOT
    / "java-api-service"
    / "src"
    / "test"
    / "java"
    / "com"
    / "example"
    / "dispute"
    / "agentstream"
    / "persistence"
)


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_runtime_defaults_to_old_compatible_and_switches_fail_closed() -> None:
    mode = _read(PERSISTENCE / "StreamCompatibilityMode.java")
    store = _read(PERSISTENCE / "PostgresAgentRunV2EventStore.java")
    port = _read(APPLICATION / "AgentRunV2StreamStore.java")

    assert "return OLD_COMPATIBLE;" in mode
    assert "StreamCompatibilityMode.defaultMode()" in store
    assert "target-only stream writes require a separately authorized release switch" in store
    assert "old-only rollback is forbidden after a target-only stream write" in mode
    assert "Objects.requireNonNull(parity, \"parity\").requireCompatible()" in mode
    assert "Objects.requireNonNull(rollbackCoverage, \"rollbackCoverage\")" in mode
    assert "targetOnlyWriteObserved()" in mode
    assert "validateRollbackCoverage" in store
    assert "this stream-store decorator cannot authorize a compatibility switch" in port


def test_backfill_is_bounded_resumable_and_uses_atomic_v046_primitive() -> None:
    coordinator = _read(PERSISTENCE / "StreamBackfillCoordinator.java")

    assert "source_upper_bound_created_at" in coordinator
    assert "source_upper_bound_event_id" in coordinator
    assert "order by source.created_at asc, source.id asc" in coordinator
    assert "limit ?" in coordinator
    assert "record_agent_run_stream_delivery" in coordinator
    assert "PROPAGATION_REQUIRES_NEW" in coordinator
    assert "processed_count = processed_count + ?" in coordinator
    assert "highestContiguousSequence" in coordinator
    assert "cursor_status = 'FAILED'" in coordinator
    assert "isImmutableConflict(failure)" in coordinator
    assert "conflict_count = conflict_count + 1" in coordinator


def test_backfill_and_dual_write_share_one_idempotent_source_identity() -> None:
    coordinator = _read(PERSISTENCE / "StreamBackfillCoordinator.java")
    store = _read(PERSISTENCE / "PostgresAgentRunV2EventStore.java")

    assert "'agent_run_stream_event', ?" in coordinator
    assert "'agent_run_stream_event'," in store
    assert "'DUAL_WRITE'" not in store
    assert "stream event identity or canonical payload hash conflicts" in coordinator
    assert "partitioned stream delivery row conflicts with immutable identity" in coordinator


def test_parity_gate_covers_every_required_replay_dimension() -> None:
    store = _read(PERSISTENCE / "PostgresAgentRunV2EventStore.java")
    port = _read(APPLICATION / "AgentRunV2StreamStore.java")

    for dimension in (
        "countParity",
        "canonicalHashParity",
        "sequenceParity",
        "actorIdParity",
        "audienceParity",
        "visibilityParity",
        "resetParity",
        "terminalParity",
        "reconnectParity",
        "compositeCursorParity",
    ):
        assert dimension in store
        assert dimension in port
    assert "sourceContiguous" in store
    assert "targetContiguous" in store
    assert "expectedHighWatermark == targetHighWatermark" in store
    assert "TARGET_REPLAY_SQL" in store
    assert "highest_contiguous_sequence_no" in store
    assert ".requireCompatible()" in store


def test_tests_cover_overlap_conflict_gap_and_target_aware_rollback_contracts() -> None:
    coordinator_test = _read(TEST_ROOT / "StreamBackfillCoordinatorTest.java")
    replay_test = _read(TEST_ROOT / "AgentRunStreamReplayIntegrationTest.java")

    assert "targetOnlyWriteCanNeverRollBackToAnOldOnlyReader" in coordinator_test
    assert "cursorKeepsScanProgressSeparateFromDeliveryHighWatermark" in coordinator_test
    for marker in (
        "backfillThenDualWriteIsIdempotent",
        "dualWriteThenBackfillIsIdempotent",
        "hashConflictRollsBackIdentityTargetAndWatermark",
        "transientFailureLeavesCursorResumable",
        "gappedV1AndV2ParityFailsClosed",
        "postTerminalParityAndRollbackFailClosed",
    ):
        assert marker in replay_test


def test_delivery_storage_never_claims_formal_business_authority() -> None:
    owned_sources = "\n".join(
        _read(path)
        for path in (
            PERSISTENCE / "StreamBackfillCoordinator.java",
            PERSISTENCE / "StreamCompatibilityMode.java",
            PERSISTENCE / "PostgresAgentRunV2EventStore.java",
        )
    ).lower()

    assert "formal_business_authority = true" not in owned_sources
    assert "set finalization_status = 'committed'" not in owned_sources
    assert "update agent_execution_manifest" not in owned_sources
