from __future__ import annotations

from dataclasses import replace
import inspect
from pathlib import Path

import pytest

from app.graph_runtime.migrations import (
    GraphMigrationRunner,
    MIGRATION_FILENAMES,
    PINNED_PACKAGE_VERSIONS,
    REQUIRED_MIGRATION_RELATIONS,
    acquire_graph_schema_advisory_lock,
    expected_checkpoint_migration,
    graph_application_signature,
    graph_migration_verification_hash,
    graph_schema_advisory_lock_key,
    graph_verification_hash,
    load_graph_migrations,
    pinned_package_versions,
)
from app.graph_runtime.persistence_models import GraphMigrationError
from app.graph_runtime.restore_validation import GraphRestoreValidationRunner


SHA_A = "a" * 64
SHA_B = "b" * 64


def test_repository_migrations_are_exact_ordered_and_hash_bound() -> None:
    migrations = load_graph_migrations()

    assert tuple(migration.filename for migration in migrations) == MIGRATION_FILENAMES
    assert tuple(migration.version for migration in migrations) == (
        "G001",
        "G002",
        "G003",
        "G004",
        "G005",
        "G006",
        "G007",
        "G008",
        "G009",
        "G010",
        "G011",
        "G012",
        "G013",
    )
    assert all(len(migration.sha256) == 64 for migration in migrations)
    assert len(graph_application_signature(migrations)) == 64


def test_migration_loader_rejects_an_unexpected_file(tmp_path: Path) -> None:
    for migration in load_graph_migrations():
        (tmp_path / migration.filename).write_text(migration.sql_text, encoding="utf-8")
    (tmp_path / "G004_unowned.sql").write_text("select 1;", encoding="utf-8")

    with pytest.raises(GraphMigrationError, match="migration set mismatch"):
        load_graph_migrations(tmp_path)


def test_application_signature_changes_when_a_migration_changes() -> None:
    migrations = load_graph_migrations()
    changed = replace(migrations[0], sha256=SHA_A)

    assert graph_application_signature((changed, *migrations[1:])) != (
        graph_application_signature(migrations)
    )


def test_parallel_technical_completion_migration_is_immutable_and_attempt_bound() -> None:
    migration = next(item for item in load_graph_migrations() if item.version == "G011")
    normalized = " ".join(migration.sql_text.split()).lower()

    assert migration.version == "G011"
    assert "'technical_completed'" in normalized
    assert "create table agent_graph_technical_completion" in normalized
    assert "unique (attempt_id, thread_id, command_id, fencing_token)" in normalized
    assert "foreign key (attempt_id, thread_id, command_id, fencing_token)" in normalized
    assert "graph technical completion rows are immutable" in normalized


def test_parallel_receipt_cycle_migration_is_immutable_and_fence_bound() -> None:
    migration = next(item for item in load_graph_migrations() if item.version == "G012")
    normalized = " ".join(migration.sql_text.split()).lower()

    assert migration.version == "G012"
    assert "create table agent_graph_parallel_receipt_cycle" in normalized
    assert "create table agent_graph_parallel_receipt_execution" in normalized
    assert (
        "unique ( thread_id, command_id, attempt_id, receipt_sha256, fencing_token )"
        in normalized
    )
    assert "unique (attempt_id, fencing_token)" in normalized
    assert "foreign key (attempt_id) references agent_graph_command_attempt(attempt_id)" in normalized
    assert "foreign key (attempt_id, thread_id, command_id, fencing_token)" not in normalized
    assert "predecessor_execution_id varchar(128)" in normalized
    assert "provider_call_count_at_admission integer not null" in normalized
    assert "references agent_graph_parallel_receipt_execution(execution_id)" in normalized
    assert "predecessor.provider_call_count_at_admission" in normalized
    assert "completed.receipt_sha256 = execution.receipt_sha256" in normalized
    assert "parallel receipt authority rows are immutable" in normalized
    assert "require_parallel_intake_graph_command" in normalized
    assert "jsonb_typeof(command.request_json -> 'event_ref') = 'object'" in normalized
    assert "parallel command fence handoff failed" not in normalized
    assert "cycle.fencing_token = old.fencing_token" in normalized
    assert "execution.fencing_token = new.fencing_token" in normalized


def test_atomic_provider_group_migration_is_additive_and_weight_bound() -> None:
    migration = next(item for item in load_graph_migrations() if item.version == "G013")
    normalized = " ".join(migration.sql_text.split()).lower()

    assert "add column permit_count integer not null default 1" in normalized
    assert "create function agent_graph_acquire_fanout_permit_group" in normalized
    assert "sum(active.permit_count)" in normalized
    assert "existing.permit_count" in normalized


def test_runtime_package_versions_match_the_frozen_pins() -> None:
    assert pinned_package_versions() == PINNED_PACKAGE_VERSIONS
    assert expected_checkpoint_migration() >= 0


def test_runtime_package_version_drift_fails_closed(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        "app.graph_runtime.migrations.package_version",
        lambda package: "0.0.0" if package == "langgraph" else PINNED_PACKAGE_VERSIONS[package],
    )

    with pytest.raises(GraphMigrationError, match="package pin mismatch for langgraph"):
        pinned_package_versions()


def test_verification_hash_binds_environment_and_restore_receipt() -> None:
    values = {
        "database_name": "graph_db",
        "schema": "graph_runtime",
        "environment_generation": "generation-7",
        "restore_verification_hash": SHA_A,
        "application_signature": SHA_B,
        "checkpoint_migration": 9,
        "package_versions": PINNED_PACKAGE_VERSIONS,
    }
    baseline = graph_verification_hash(**values)

    assert graph_verification_hash(**values) == baseline
    assert (
        graph_verification_hash(**{**values, "environment_generation": "generation-8"}) != baseline
    )
    assert graph_verification_hash(**{**values, "restore_verification_hash": SHA_B}) != baseline

    migration_hash = graph_migration_verification_hash(
        database_name=values["database_name"],
        schema=values["schema"],
        environment_generation=values["environment_generation"],
        application_signature=values["application_signature"],
        checkpoint_migration=values["checkpoint_migration"],
        package_versions=values["package_versions"],
    )
    assert migration_hash != baseline


def test_migration_runner_requires_external_environment_generation() -> None:
    with pytest.raises((GraphMigrationError, ValueError), match="environment generation"):
        GraphMigrationRunner(
            "postgresql://unused",
            environment_generation=None,
        )

    GraphMigrationRunner(
        "postgresql://unused",
        environment_generation="generation-7",
    )


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("schema_row", "message"),
    [
        (None, "must be preprovisioned"),
        (
            {"schema_name": "graph_runtime", "schema_owner": "unexpected_owner"},
            "configured owner role",
        ),
    ],
)
async def test_migration_session_requires_the_preprovisioned_owned_schema(
    schema_row: object,
    message: str,
) -> None:
    class Connection:
        async def execute(self, query: object, params: object = None) -> _Cursor:
            normalized = " ".join(str(query).split()).lower()
            if "session_user" in normalized:
                return _Cursor(
                    {"session_user": "graph_migrator", "database_name": "graph_db"}
                )
            if "set role" in normalized:
                return _Cursor()
            if "current_user" in normalized:
                return _Cursor({"current_user": "graph_owner"})
            if "information_schema.schemata" in normalized:
                assert params == ("graph_runtime",)
                return _Cursor(schema_row)
            raise AssertionError(f"unexpected SQL: {normalized}")

    runner = GraphMigrationRunner(
        "postgresql://unused",
        environment_generation="generation-7",
    )

    with pytest.raises(GraphMigrationError, match=message):
        await runner._prepare_session(  # noqa: SLF001 - validates the role boundary
            Connection()  # type: ignore[arg-type]
        )


@pytest.mark.parametrize(
    ("generation", "restore_hash", "message"),
    [
        (None, SHA_A, "environment_generation"),
        ("generation-7", None, "restore_verification_hash"),
        ("generation-7", "invalid", "restore_verification_hash"),
        ("generation-7", "0" * 64, "zero sentinel"),
    ],
)
def test_restore_validator_requires_a_separate_external_receipt(
    generation: str | None,
    restore_hash: str | None,
    message: str,
) -> None:
    with pytest.raises((GraphMigrationError, ValueError), match=message):
        GraphRestoreValidationRunner(
            "postgresql://unused",
            environment_generation=generation,
            restore_verification_hash=restore_hash,
        )


def test_migration_job_cannot_self_authorize_a_restore_generation() -> None:
    migration_source = inspect.getsource(GraphMigrationRunner._verify_and_mark_current)
    restore_source = inspect.getsource(GraphRestoreValidationRunner.run)

    assert "'UNVERIFIED'" in migration_source
    assert "'CURRENT', 'VERIFIED'" not in migration_source
    assert "set restore_verification_hash = %s" in restore_source
    assert "restore_status = 'VERIFIED'" in restore_source
    assert "CONSISTENCY_QUERIES" in restore_source


class _Cursor:
    def __init__(self, row: object = None, rows: list[object] | None = None) -> None:
        self._row = row
        self._rows = rows or []

    async def fetchone(self) -> object:
        return self._row

    async def fetchall(self) -> list[object]:
        return self._rows


class _Transaction:
    def __init__(self, events: list[str]) -> None:
        self._events = events

    async def __aenter__(self) -> None:
        self._events.append("transaction:enter")

    async def __aexit__(self, exc_type: object, exc: object, traceback: object) -> None:
        self._events.append("transaction:rollback" if exc_type else "transaction:commit")


class _MigrationConnection:
    def __init__(self, existing: object = None) -> None:
        self.existing = existing
        self.events: list[str] = []

    def transaction(self) -> _Transaction:
        return _Transaction(self.events)

    async def execute(
        self,
        query: object,
        params: object = None,
        *,
        prepare: bool | None = None,
    ) -> _Cursor:
        normalized = " ".join(str(query).split()).lower()
        if "from graph_schema_migration" in normalized:
            self.events.append("sql:checksum-for-update")
            assert normalized.endswith("for update")
            return _Cursor(self.existing)
        if normalized.startswith("insert into graph_schema_migration"):
            self.events.append("sql:ledger-insert")
            return _Cursor()
        self.events.append("sql:migration-ddl")
        assert prepare is False
        return _Cursor()


@pytest.mark.asyncio
async def test_checksum_is_locked_and_checked_before_migration_sql() -> None:
    migration = load_graph_migrations()[0]
    connection = _MigrationConnection(
        {"sha256": SHA_A, "package_versions": PINNED_PACKAGE_VERSIONS}
    )
    runner = GraphMigrationRunner(
        "postgresql://unused",
        environment_generation="generation-7",
    )

    with pytest.raises(GraphMigrationError, match="checksum conflict"):
        await runner._apply_one(  # noqa: SLF001 - verifies the migration transaction contract
            connection,  # type: ignore[arg-type]
            migration,
            package_versions=PINNED_PACKAGE_VERSIONS,
            execution_id="gm-test",
        )

    assert connection.events == [
        "transaction:enter",
        "sql:checksum-for-update",
        "transaction:rollback",
    ]


@pytest.mark.asyncio
async def test_migration_sql_and_ledger_insert_share_the_checksum_transaction() -> None:
    migration = load_graph_migrations()[0]
    connection = _MigrationConnection()
    runner = GraphMigrationRunner(
        "postgresql://unused",
        environment_generation="generation-7",
    )

    applied = await runner._apply_one(  # noqa: SLF001 - verifies transaction ordering
        connection,  # type: ignore[arg-type]
        migration,
        package_versions=PINNED_PACKAGE_VERSIONS,
        execution_id="gm-test",
    )

    assert applied
    assert connection.events == [
        "transaction:enter",
        "sql:checksum-for-update",
        "sql:migration-ddl",
        "sql:ledger-insert",
        "transaction:commit",
    ]


@pytest.mark.asyncio
async def test_schema_lock_fails_fast_when_migration_or_restore_owns_it() -> None:
    class Connection:
        async def execute(self, query: str, params: object = None) -> _Cursor:
            assert "pg_try_advisory_lock" in query
            assert params == (graph_schema_advisory_lock_key("graph_runtime"),)
            return _Cursor({"acquired": False})

    with pytest.raises(GraphMigrationError, match="already running"):
        await acquire_graph_schema_advisory_lock(  # type: ignore[arg-type]
            Connection(), "graph_runtime"
        )


def test_migration_and_restore_validation_use_the_same_schema_lock() -> None:
    migration_source = inspect.getsource(GraphMigrationRunner.run)
    restore_source = inspect.getsource(GraphRestoreValidationRunner.run)

    assert "acquire_graph_schema_advisory_lock(connection, self._schema)" in migration_source
    assert "acquire_graph_schema_advisory_lock(connection, self._schema)" in restore_source
    assert graph_schema_advisory_lock_key("graph_runtime") == (
        "after-sale-flow:graph-schema:graph_runtime"
    )


def test_runtime_grants_only_the_race_safe_fanout_cancellation_routine() -> None:
    source = inspect.getsource(GraphMigrationRunner._apply_runtime_grants)

    assert "agent_graph_cancel_or_release_fanout_permit" in source
    assert "agent_graph_cancel_queued_fanout_permit" not in source


@pytest.mark.asyncio
async def test_schema_change_marks_control_dirty_before_any_owned_ddl() -> None:
    events: list[str] = []

    class Transaction:
        async def __aenter__(self) -> None:
            events.append("transaction:enter")

        async def __aexit__(self, exc_type: object, exc: object, traceback: object) -> None:
            events.append("transaction:commit")

    class Connection:
        def transaction(self) -> Transaction:
            return Transaction()

        async def execute(self, query: str, params: object = None) -> _Cursor:
            normalized = " ".join(query.split()).lower()
            assert "'dirty', 'unverified'" in normalized
            assert params is not None
            events.append("sql:dirty-control")
            return _Cursor()

    runner = GraphMigrationRunner(
        "postgresql://unused",
        environment_generation="generation-7",
    )
    await runner._mark_migration_dirty(  # noqa: SLF001 - verifies the fail-closed gate
        Connection(),  # type: ignore[arg-type]
        application_signature=SHA_A,
        checkpoint_migration=9,
        database_name="graph_db",
        package_versions=PINNED_PACKAGE_VERSIONS,
    )

    assert events == ["transaction:enter", "sql:dirty-control", "transaction:commit"]


def test_migration_runner_only_invalidates_restore_when_schema_is_not_current() -> None:
    source = inspect.getsource(GraphMigrationRunner.run)
    current_check = source.index("schema_current = await self._schema_is_exactly_current")
    dirty_mark = source.index("await self._mark_migration_dirty")
    checkpointer_setup = source.index("await AsyncPostgresSaver(connection).setup()")

    assert current_check < dirty_mark < checkpointer_setup
    assert "if not schema_current:" in source


@pytest.mark.asyncio
@pytest.mark.parametrize(("missing_relation", "expected"), [(None, True), ("checkpoints", False)])
async def test_exact_schema_probe_includes_relations_checksums_and_checkpointer_ledger(
    missing_relation: str | None,
    expected: bool,
) -> None:
    migrations = load_graph_migrations()

    class Connection:
        async def execute(self, query: str, params: object = None) -> _Cursor:
            normalized = " ".join(query.split()).lower()
            if "from unnest" in normalized:
                return _Cursor(
                    rows=[
                        {
                            "relation_name": relation,
                            "present": relation != missing_relation,
                        }
                        for relation in REQUIRED_MIGRATION_RELATIONS
                    ]
                )
            if "from graph_schema_migration" in normalized:
                return _Cursor(
                    rows=[
                        {
                            "version": migration.version,
                            "sha256": migration.sha256,
                            "package_versions": PINNED_PACKAGE_VERSIONS,
                        }
                        for migration in migrations
                    ]
                )
            if "from checkpoint_migrations" in normalized:
                return _Cursor(
                    rows=[
                        {"version": version}
                        for version in range(expected_checkpoint_migration() + 1)
                    ]
                )
            raise AssertionError(f"unexpected SQL: {normalized}")

    runner = GraphMigrationRunner(
        "postgresql://unused",
        environment_generation="generation-7",
    )

    assert (
        await runner._schema_is_exactly_current(  # noqa: SLF001 - migration gate contract
            Connection(),  # type: ignore[arg-type]
            migrations=migrations,
            checkpoint_migration=expected_checkpoint_migration(),
            package_versions=PINNED_PACKAGE_VERSIONS,
        )
        is expected
    )
