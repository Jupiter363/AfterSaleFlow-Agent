import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
JAVA_ROOT = ROOT / "apps/domain-service" / "src" / "main" / "java"
AGENT_STREAM = JAVA_ROOT / "com" / "example" / "dispute" / "agentstream"
APPLICATION = AGENT_STREAM / "application"
DELIVERY = AGENT_STREAM / "infrastructure" / "delivery"
PERSISTENCE = AGENT_STREAM / "infrastructure" / "persistence"
JAVA_TESTS = (
    ROOT
    / "apps/domain-service"
    / "src"
    / "test"
    / "java"
    / "com"
    / "example"
    / "dispute"
    / "agentstream"
)


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_archive_receipt_binds_durable_object_identity_and_nonregressing_hwm() -> None:
    store = _read(PERSISTENCE / "AgentRunStreamArchiveStore.java")

    for marker in (
        "targetPartitionName",
        "firstSequenceNo",
        "lastSequenceNo",
        "eventCount",
        "canonicalEventsHash",
        "objectVersion",
        "objectHash",
        "objectReadbackHash",
        "objectCreationReceiptId",
        "objectCreationReceiptHash",
        "deliveryHighWatermark",
    ):
        assert marker in store
    assert "agent_run_stream_archive_manifest" in store
    assert "agent_run_stream_archive_receipt" in store
    assert "agent_run_stream_delivery_high_watermark" in store
    assert "for key share" in store.lower()
    assert "deliveryHighWatermark != formal.terminalSequenceNo()" in store
    assert "receiptHighWatermark > durableHighWatermark" in store
    assert "on conflict (manifest_id) do nothing" in store.lower()
    assert "on conflict (receipt_id) do nothing" in store.lower()
    assert "!stored.getFirst().equals(expected)" in store
    assert "max(sequence_no)" not in store.lower()


def test_archive_pass_is_derived_from_exact_current_postgres_parity() -> None:
    store = _read(PERSISTENCE / "AgentRunStreamArchiveStore.java")

    assert "compatibilityStore.validateCompatibility(" in store
    assert "return report.requireCompatible();" in store
    assert "compatibility.sequenceParity()" in store
    assert "compatibility.canonicalHashParity()" in store
    assert "compatibility.audienceParity()" in store
    assert "compatibility.visibilityParity()" in store
    assert "compatibility.actorIdParity()" in store
    assert "compatibility.compositeCursorParity()" in store
    assert "compatibility.reconnectParity()" in store
    assert "compatibility.resetParity()" in store
    assert "compatibility.terminalParity()" in store
    assert "compatibilityReportSha256" in store
    assert "sourceEventCount" in store
    assert "targetEventCount" in store
    assert "stream parity changed while recording archive evidence" in store
    assert "archive receipt is not bound to current exact stream parity" in store


def test_stream_013_uses_long_audit_terminal_and_current_immutable_manifest() -> None:
    manifest = _read(PERSISTENCE / "AgentRunStreamRetentionManifest.java")
    store = _read(PERSISTENCE / "AgentRunStreamArchiveStore.java")

    assert "MINIMUM_HOT_RETENTION = Duration.ofHours(24)" in manifest
    assert "releaseCleanupEligible" in manifest
    assert "verifiedReadback()" in manifest
    assert "terminalEventRetained()" in manifest
    assert "immutableManifest()" in manifest
    assert "deliveryHighWatermark() >= terminalSequenceNo" in manifest
    assert "!archiveReceipt.formalBusinessAuthority()" in manifest
    assert "!archiveReceipt.releaseEvidenceComplete()" in manifest
    assert "public AgentRunStreamRetentionManifest(" not in manifest
    assert "public AgentRunStreamRetentionManifest withArchiveReceipt" not in manifest
    assert "withArchiveVerified" not in manifest
    assert "withCompactionVerified" not in manifest

    assert "compatibilityStore.retentionManifest(runId, attemptId)" in store
    assert "private Optional<ArchiveReceipt> findVerifiedReceipt" in store
    assert "select 1 from agent_run_stream_event terminal" in store
    assert "terminal.id = manifest.terminal_event_id" in store
    assert "terminal.event_type = 'final'" in store
    assert "terminal.payload_hash = manifest.terminal_event_sha256" in store
    assert "join agent_execution_manifest current_execution" in store
    assert "current_execution.id = current_run.committed_manifest_id" in store
    assert "current_execution.manifest_sha256 =" in store
    assert "current_run.committed_manifest_hash" in store
    assert "current_run.finalization_status = 'COMMITTED'" in store
    assert "resultSet.getBoolean(\"terminal_event_retained\")" in store
    assert "resultSet.getBoolean(\"immutable_manifest_retained\")" in store
    assert "agent_run_stream_event_delivery terminal" not in store
    assert not re.search(
        r"\b(?:detach\s+partition|drop\s+table|truncate\s+table)\b",
        store,
        re.IGNORECASE,
    )


def test_stream_wrapper_delegates_every_authoritative_read_method() -> None:
    port = _read(APPLICATION / "AgentRunV2StreamStore.java")
    wrapper = _read(DELIVERY / "WakeupPublishingAgentRunV2StreamStore.java")
    mode = _read(PERSISTENCE / "StreamCompatibilityMode.java")

    assert "default Optional<AgentRunStreamRetentionManifest> retentionManifest" in port
    for method in (
        "public List<AgentStreamEvent> replay(",
        "public long durableHighWatermark(",
        "public CompatibilityReport validateCompatibility(",
        "public Optional<AgentRunStreamRetentionManifest> retentionManifest(",
    ):
        assert method in wrapper
    assert "return eventStore.replay(" in wrapper
    assert "return eventStore.durableHighWatermark(" in wrapper
    assert "return eventStore.validateCompatibility(" in wrapper
    assert "archiveStore.retentionManifest(runId, attemptId)" in wrapper
    assert "return OLD_COMPATIBLE;" in mode
    assert "old-only rollback is forbidden after a target-only stream write" in mode


def test_redis_wakeup_is_bounded_and_never_becomes_cursor_authority() -> None:
    publisher = _read(DELIVERY / "RedisAgentRunStreamWakeupPublisher.java")
    subscriber = _read(DELIVERY / "RedisAgentRunStreamWakeupSubscriber.java")
    failover_test = _read(JAVA_TESTS / "delivery" / "RedisAgentRunStreamFailoverTest.java")

    assert "new ArrayBlockingQueue<>(256)" in publisher
    assert "new ThreadPoolExecutor.DiscardOldestPolicy()" in publisher
    assert "PostgreSQL replay remains authoritative" in publisher
    assert "eventService.wakeUp(hint.runId())" in subscriber
    assert "hint.durableHighWatermark()" not in subscriber
    for marker in (
        "redisUnavailableCannotChangeTheDurablePortReceiptOrReplay",
        "droppedDuplicatedAndReorderedHintsReplayOnlyFromThePostgresCursor",
        "queueOverflowDropsHintsButASurvivingHintReplaysEveryPostgresRow",
        "restartUsesThePersistedClientCursorAndIgnoresTheHintHighWatermark",
    ):
        assert marker in failover_test


def test_retention_tests_cover_boolean_and_long_audit_bypasses() -> None:
    test_source = _read(
        JAVA_TESTS / "persistence" / "AgentRunStreamRetentionManifestTest.java"
    )

    for marker in (
        "releaseEligibilityRequiresTheFullVerifiedReceiptAfterTwentyFourHours",
        "booleanVerificationCannotBypassDurableReceiptEvidence",
        "readbackMismatchOrMissingLongAuditBindingsFailClosed",
        "businessAuthorityOrReleaseCompletionCannotComeFromArchiveEvidence",
        "receiptMustBindTheExactFormalTerminalManifest",
    ):
        assert marker in test_source
