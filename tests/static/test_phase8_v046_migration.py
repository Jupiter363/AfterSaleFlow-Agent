import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MIGRATION = (
    ROOT
    / "java-api-service"
    / "src"
    / "main"
    / "resources"
    / "db"
    / "migration"
    / "V046__stream_partition_and_retention.sql"
)
MIGRATION_TEST = (
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
    / "AgentRunV2MigrationIntegrationTest.java"
)


def _sql() -> str:
    return MIGRATION.read_text(encoding="utf-8").lower()


def _java() -> str:
    return MIGRATION_TEST.read_text(encoding="utf-8")


def test_v046_is_additive_and_keeps_the_old_store_untouched() -> None:
    sql = _sql()

    assert "v046__stream_partition_and_retention.sql" in MIGRATION.name.lower()
    assert "create table agent_run_stream_event_identity" in sql
    assert "create table agent_run_stream_event_delivery" in sql
    assert not re.search(r"\balter\s+table\s+agent_run_stream_event\b", sql)
    assert not re.search(r"\bdrop\s+table\s+(?:if\s+exists\s+)?agent_run_stream_event\b", sql)
    assert not re.search(r"\btruncate\s+(?:table\s+)?agent_run_stream_event\b", sql)
    assert "v047" not in sql
    assert "formal_business_authority boolean not null default false" in sql
    assert "authority_scope varchar(32) not null default 'delivery_storage_only'" in sql


def test_global_identity_is_unpartitioned_and_hash_bound() -> None:
    sql = _sql()
    identity = sql.split("create table agent_run_stream_event_identity", 1)[1].split(
        "create table agent_run_stream_event_delivery", 1
    )[0]

    assert "event_id varchar(64) primary key" in identity
    assert "canonical_payload_sha256 varchar(64) not null" in identity
    assert "recorded_at timestamptz not null default clock_timestamp()" in identity
    assert "new.recorded_at := clock_timestamp()" in identity
    assert "create trigger trg_stream_event_identity_recorded_at" in identity
    assert "partition by" not in identity
    assert "constraint uq_stream_event_identity_sequence" in identity
    assert re.search(
        r"unique\s*\(\s*stream_protocol,\s*agent_run_id,\s*"
        r"agent_run_attempt_id,\s*sequence_no\s*\)",
        identity,
    )


def test_hwm_foreign_key_has_an_exact_unique_parent_key() -> None:
    sql = _sql()

    exact_parent = re.search(
        r"constraint\s+uq_stream_event_identity_hwm\s+unique\s*\(\s*"
        r"event_id,\s*stream_protocol,\s*agent_run_id,\s*"
        r"agent_run_attempt_id,\s*sequence_no,\s*recorded_at\s*\)",
        sql,
    )
    hwm_fk = re.search(
        r"constraint\s+fk_stream_delivery_hwm_event\s+foreign key\s*\(\s*"
        r"highest_event_id,\s*stream_protocol,\s*agent_run_id,\s*"
        r"agent_run_attempt_id,\s*highest_contiguous_sequence_no,\s*"
        r"highest_event_recorded_at\s*\)\s*references\s+"
        r"agent_run_stream_event_identity\s*\(\s*event_id,\s*"
        r"stream_protocol,\s*agent_run_id,\s*agent_run_attempt_id,\s*"
        r"sequence_no,\s*recorded_at\s*\)",
        sql,
    )

    assert exact_parent, "HWM FK requires an exact matching unique parent key"
    assert hwm_fk, "HWM FK must bind the exact event and authoritative recorded_at"


def test_delivery_target_uses_database_recorded_time_and_a_default_partition() -> None:
    sql = _sql()
    target = sql.split("create table agent_run_stream_event_delivery", 1)[1].split(
        "create table agent_run_stream_delivery_high_watermark", 1
    )[0]

    assert ") partition by range (recorded_at);" in target
    assert "partition of agent_run_stream_event_delivery default" in target
    assert "primary key (event_id, recorded_at)" in target
    assert "constraint fk_stream_event_delivery_identity" in target
    assert "source_event_created_at timestamptz not null" in target
    assert "recorded_at timestamptz not null" in target
    assert "stream_protocol varchar(32) not null default 'agent_stream.v1'" in target
    assert "audience_actor_ids_json jsonb not null default '[]'::jsonb" in target


def test_atomic_delivery_primitive_advances_only_contiguous_hwm() -> None:
    sql = _sql()
    primitive = sql.split("create function record_agent_run_stream_delivery", 1)[1].split(
        "create table agent_run_stream_backfill_cursor", 1
    )[0]

    assert "pg_advisory_xact_lock" in primitive
    assert "insert into agent_run_stream_event_identity" in primitive
    assert "on conflict (event_id) do nothing" in primitive
    assert "canonical payload hash conflicts" in primitive
    assert "insert into agent_run_stream_event_delivery" in primitive
    assert "insert into agent_run_stream_delivery_high_watermark" in primitive
    assert "with recursive contiguous(sequence_no)" in primitive
    assert "update agent_run_stream_delivery_high_watermark" in primitive

    hwm_guard = sql.split("create function enforce_stream_delivery_high_watermark", 1)[
        1
    ].split("create trigger trg_stream_delivery_high_watermark_guard", 1)[0]
    assert "cannot regress" in hwm_guard
    assert "cannot advance across a gap" in hwm_guard
    assert "generate_series" in hwm_guard


def test_backfill_cursor_is_separate_bounded_and_nonregressing() -> None:
    sql = _sql()
    cursor = sql.split("create table agent_run_stream_backfill_cursor", 1)[1].split(
        "create table agent_run_stream_archive_manifest", 1
    )[0]

    assert "source_upper_bound_created_at timestamptz not null" in cursor
    assert "source_upper_bound_event_id varchar(64) not null" in cursor
    assert "batch_limit integer not null default 500" in cursor
    assert "check (batch_limit between 1 and 1000)" in cursor
    assert "backfill cursor cannot regress" in cursor
    assert "must start pending at the bounded snapshot origin" in cursor
    assert "advance must match one bounded source batch" in cursor
    assert "completion requires the exact conflict-free source bound" in cursor
    assert "from agent_run_stream_event source_event" in cursor
    assert "join agent_run_stream_event_delivery target" in cursor
    assert "registry.event_id = source_event.id" in cursor
    assert "target.payload_json = source_event.payload_json" in cursor
    assert "target.source_event_created_at = source_event.created_at" in cursor
    assert "requires matching immutable target delivery rows" in cursor
    assert "delivery_high_watermark" not in cursor


def test_archive_and_migration_evidence_is_hash_bound_and_append_only() -> None:
    sql = _sql()

    for table in (
        "agent_run_stream_archive_manifest",
        "agent_run_stream_archive_receipt",
        "agent_run_stream_migration_receipt",
    ):
        assert f"create table {table}" in sql
        assert f"trg_stream_{table.removeprefix('agent_run_stream_')}_append_only" in sql
        assert f"trg_stream_{table.removeprefix('agent_run_stream_')}_delete_append_only" in sql

    archive = sql.split("create table agent_run_stream_archive_receipt", 1)[1].split(
        "create function enforce_stream_archive_receipt_binding", 1
    )[0]
    for field in (
        "target_partition_name",
        "first_sequence_no",
        "last_sequence_no",
        "event_count",
        "canonical_events_sha256",
        "object_version",
        "object_sha256",
        "object_readback_sha256",
        "delivery_high_watermark",
        "hot_retention_started_at",
        "hot_retention_eligible_at",
    ):
        assert field in archive
    assert "interval '24 hours'" in archive
    assert "object_readback_sha256 = object_sha256" in archive
    assert "ck_stream_archive_receipt_verified_evidence" in archive
    assert "agent-stream-sequence-identity-validation.v1" in archive
    assert "agent-stream-audience-cursor-validation.v1" in archive
    assert '"sequence_contiguous": true' in archive
    assert '"event_identity_exact": true' in archive
    assert '"audience_parity": true' in archive
    assert '"actor_id_parity": true' in archive
    assert '"cursor_parity": true' in archive
    assert "archive receipt delivery high-watermark cannot regress" in sql

    migration = sql.split("create table agent_run_stream_migration_receipt", 1)[1]
    assert "acceptance_status varchar(32) not null default 'pending_external'" in migration
    assert "migration_version varchar(16) not null default '046'" in migration
    assert "'reader_switch'" not in migration
    assert "'writer_switch'" not in migration
    assert "acceptance_status in ('pending_external', 'rejected')" in migration
    assert "target_event_count = source_event_count" in migration
    assert "target_canonical_sha256 = source_canonical_sha256" in migration
    assert "delivery_high_watermark >= 0" in migration
    assert "agent-stream-sequence-identity-validation.v1" in migration
    assert "agent-stream-audience-cursor-validation.v1" in migration
    assert '"sequence_contiguous": true' in migration
    assert '"event_identity_exact": true' in migration
    assert '"audience_parity": true' in migration
    assert '"actor_id_parity": true' in migration
    assert '"cursor_parity": true' in migration


def test_flyway_test_runs_through_v046_and_asserts_additive_contract() -> None:
    java = _java()

    assert ".target(\"40.4\")" in java
    assert "latest.migrate().migrationsExecuted).isPositive()" in java
    assert "latest.migrate().migrationsExecuted).isEqualTo(2)" not in java
    assert "assertV046AdditiveDeliverySchema(connection)" in java
    assert "agent_run_stream_event_delivery:p" in java
    assert "pg_get_expr(child.relpartbound, child.oid) = 'DEFAULT'" in java
    assert "record_agent_run_stream_delivery" in java
    assert "legacyBackfillInsert()" in java
    assert "v2BackfillInsert()" in java
    assert "v2DeliveryInsert(\"EVENT_LEGACY_V2\"" in java
    assert "delivery high-watermark cannot regress" in java
    assert "backfill cursor cannot regress" in java
    assert "backfill cursor requires matching immutable target delivery rows" in java
    assert "ARCHIVE_RECEIPT_V046_EMPTY_EVIDENCE" in java
    assert "MIGRATION_RECEIPT_V046_EMPTY_EVIDENCE" in java
    assert "ck_stream_archive_receipt_verified_evidence" in java
    assert "ck_stream_migration_receipt_success_parity" in java
    assert "truncate agent_run_stream_event_delivery_default" in java
    assert "PENDING_EXTERNAL:DELIVERY_STORAGE_ONLY:false" in java


def test_partition_update_and_direct_default_truncate_are_guarded() -> None:
    sql = _sql()

    assert re.search(
        r"create trigger trg_stream_event_delivery_append_only\s+"
        r"before update on agent_run_stream_event_delivery\s+"
        r"for each row execute function reject_append_only_mutation\(\)",
        sql,
    )
    assert re.search(
        r"create trigger trg_stream_event_delivery_default_truncate_append_only\s+"
        r"before truncate on agent_run_stream_event_delivery_default\s+"
        r"for each statement execute function reject_append_only_mutation\(\)",
        sql,
    )
