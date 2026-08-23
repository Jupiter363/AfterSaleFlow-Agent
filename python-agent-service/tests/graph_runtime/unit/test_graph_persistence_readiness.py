from __future__ import annotations

import asyncio
from typing import Any

import pytest

from app.graph_runtime.migrations import (
    expected_checkpoint_migration,
    graph_application_signature,
    graph_verification_hash,
    load_graph_migrations,
    pinned_package_versions,
)
from app.graph_runtime.persistence_models import (
    GraphGatewayMode,
    GraphPersistenceConfigurationError,
    GraphReadinessConfig,
)
from app.graph_runtime.readiness import (
    REQUIRED_RELATIONS,
    GraphPersistenceReadinessProbe,
)


RESTORE_HASH = "a" * 64


def _config(**overrides: Any) -> GraphReadinessConfig:
    values = {
        "mode": GraphGatewayMode.SHADOW,
        "expected_database": "graph_db",
        "expected_user": "graph_runtime",
        "expected_environment_generation": "generation-7",
        "expected_restore_verification_hash": RESTORE_HASH,
        "schema": "graph_runtime",
        "timeout_seconds": 1.0,
    }
    values.update(overrides)
    return GraphReadinessConfig(**values)


class _Cursor:
    def __init__(self, *, row: Any = None, rows: list[Any] | None = None) -> None:
        self._row = row
        self._rows = rows or []

    async def fetchone(self) -> Any:
        return self._row

    async def fetchall(self) -> list[Any]:
        return self._rows


class _Transaction:
    async def __aenter__(self) -> None:
        return None

    async def __aexit__(self, exc_type: Any, exc: Any, traceback: Any) -> None:
        return None


class _Connection:
    def __init__(
        self,
        config: GraphReadinessConfig,
        *,
        can_create: bool = False,
        missing_relation: str | None = None,
        environment_generation: str | None = None,
        unsafe_privilege: str | None = None,
        can_create_temporary: bool = False,
        can_write_checkpoints: bool = True,
        can_execute_fanout: bool = True,
        can_mutate_fanout: bool = False,
        migration_status: str = "CURRENT",
        restore_status: str = "VERIFIED",
        inconsistent_check: str | None = None,
    ) -> None:
        self.config = config
        self.can_create = can_create
        self.missing_relation = missing_relation
        self.environment_generation = (
            environment_generation or config.expected_environment_generation
        )
        self.unsafe_privilege = unsafe_privilege
        self.can_create_temporary = can_create_temporary
        self.can_write_checkpoints = can_write_checkpoints
        self.can_execute_fanout = can_execute_fanout
        self.can_mutate_fanout = can_mutate_fanout
        self.migration_status = migration_status
        self.restore_status = restore_status
        self.inconsistent_check = inconsistent_check
        self.statements: list[str] = []
        self.fanout_privilege_params: Any = None

    def transaction(self) -> _Transaction:
        return _Transaction()

    async def execute(self, query: str, params: Any = None) -> _Cursor:
        normalized = " ".join(query.split()).lower()
        self.statements.append(normalized)
        if normalized == "set transaction read only":
            return _Cursor()
        if "current_database() as database_name" in normalized:
            return _Cursor(
                row={
                    "database_name": self.config.expected_database,
                    "user_name": self.config.expected_user,
                    "schema_name": self.config.schema,
                }
            )
        if "has_database_privilege" in normalized:
            return _Cursor(
                row={
                    "can_create_database": self.can_create,
                    "can_connect_database": True,
                    "can_create_temporary": self.can_create_temporary,
                    "can_create_schema": self.can_create,
                    "can_use_schema": True,
                    "owns_relation": False,
                    "can_read_command": True,
                    "can_write_command": True,
                    "can_write_checkpoints": self.can_write_checkpoints,
                    "can_read_registry": True,
                    "can_mutate_registry": self.unsafe_privilege == "registry",
                    "can_mutate_control": self.unsafe_privilege == "control",
                    "can_delete_runtime_rows": self.unsafe_privilege == "delete",
                    "can_mutate_append_only": self.unsafe_privilege == "append_only",
                }
            )
        if "can_execute_fanout" in normalized:
            self.fanout_privilege_params = params
            return _Cursor(
                row={
                    "can_read_fanout": True,
                    "can_mutate_fanout": self.can_mutate_fanout,
                    "can_execute_fanout": self.can_execute_fanout,
                }
            )
        if "from unnest" in normalized:
            return _Cursor(
                rows=[
                    {
                        "relation_name": relation,
                        "present": relation != self.missing_relation,
                    }
                    for relation in REQUIRED_RELATIONS
                ]
            )
        if "from graph_schema_migration" in normalized:
            packages = pinned_package_versions()
            return _Cursor(
                rows=[
                    {
                        "version": migration.version,
                        "sha256": migration.sha256,
                        "package_versions": packages,
                    }
                    for migration in load_graph_migrations()
                ]
            )
        if "array_agg(v order by v)" in normalized:
            return _Cursor(row={"versions": list(range(expected_checkpoint_migration() + 1))})
        if "from graph_runtime_control" in normalized:
            migrations = load_graph_migrations()
            signature = graph_application_signature(migrations)
            packages = pinned_package_versions()
            generation = self.environment_generation or ""
            verification_hash = graph_verification_hash(
                database_name=self.config.expected_database or "",
                schema=self.config.schema,
                environment_generation=generation,
                restore_verification_hash=RESTORE_HASH,
                application_signature=signature,
                checkpoint_migration=expected_checkpoint_migration(),
                package_versions=packages,
            )
            return _Cursor(
                row={
                    "environment_generation": generation,
                    "restore_verification_hash": RESTORE_HASH,
                    "migration_status": self.migration_status,
                    "restore_status": self.restore_status,
                    "expected_application_signature": signature,
                    "expected_checkpoint_migration": expected_checkpoint_migration(),
                    "verification_hash": verification_hash,
                }
            )
        if "as inconsistent" in normalized:
            return _Cursor(
                row={
                    "inconsistent": bool(
                        self.inconsistent_check and self.inconsistent_check in normalized
                    )
                }
            )
        raise AssertionError(f"unexpected readiness SQL: {normalized}")


class _ConnectionContext:
    def __init__(self, connection: _Connection) -> None:
        self.connection = connection

    async def __aenter__(self) -> _Connection:
        return self.connection

    async def __aexit__(self, exc_type: Any, exc: Any, traceback: Any) -> None:
        return None


class _Pool:
    def __init__(self, connection: _Connection) -> None:
        self.connection_value = connection
        self.calls = 0

    def connection(self, *, timeout: float) -> _ConnectionContext:
        self.calls += 1
        return _ConnectionContext(self.connection_value)


@pytest.mark.asyncio
async def test_disabled_mode_never_touches_a_pool_or_requires_database_config() -> None:
    class ExplodingPool:
        def connection(self, *, timeout: float) -> Any:
            raise AssertionError("DISABLED readiness must not create or probe a pool")

    report = await GraphPersistenceReadinessProbe(
        GraphReadinessConfig(mode=GraphGatewayMode.DISABLED),
        ExplodingPool(),
    ).check()

    assert report.ready
    assert report.code == "GRAPH_DISABLED"
    assert report.checks == {"pool_not_required": True}


def test_shadow_mode_requires_external_database_and_restore_identity() -> None:
    with pytest.raises(GraphPersistenceConfigurationError, match="expected_database"):
        GraphReadinessConfig(mode=GraphGatewayMode.SHADOW)


@pytest.mark.asyncio
async def test_shadow_readiness_uses_only_bounded_read_only_queries() -> None:
    config = _config()
    connection = _Connection(config)
    pool = _Pool(connection)

    report = await GraphPersistenceReadinessProbe(config, pool).check()

    assert report.ready
    assert report.code == "GRAPH_PERSISTENCE_READY"
    assert all(report.checks.values())
    assert pool.calls == 1
    assert connection.statements[0] == "set transaction read only"
    forbidden = ("create ", "alter ", "insert ", "update ", "delete ", "setup")
    assert not any(statement.startswith(forbidden) for statement in connection.statements)
    privilege_probe = next(
        statement
        for statement in connection.statements
        if "has_database_privilege" in statement
    )
    assert ".checkpoints', 'insert'" in privilege_probe
    assert ".checkpoints', 'update'" in privilege_probe
    assert ".checkpoint_blobs', 'insert'" in privilege_probe
    assert ".checkpoint_writes', 'insert'" in privilege_probe
    assert ".checkpoint_writes', 'update'" in privilege_probe
    assert "agent_graph_cancel_or_release_fanout_permit" in repr(
        connection.fanout_privilege_params
    )
    assert "agent_graph_cancel_queued_fanout_permit" not in repr(
        connection.fanout_privilege_params
    )


@pytest.mark.asyncio
async def test_restored_marker_from_another_generation_fails_closed() -> None:
    config = _config()
    connection = _Connection(config, environment_generation="generation-6")

    report = await GraphPersistenceReadinessProbe(config, _Pool(connection)).check()

    assert not report.ready
    assert report.code == "GRAPH_RESTORE_UNVERIFIED"
    assert report.checks["restore_marker"] is False


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("migration_status", "restore_status"),
    [("DIRTY", "UNVERIFIED"), ("CURRENT", "UNVERIFIED")],
)
async def test_dirty_or_unverified_restore_control_fails_closed(
    migration_status: str,
    restore_status: str,
) -> None:
    config = _config()
    connection = _Connection(
        config,
        migration_status=migration_status,
        restore_status=restore_status,
    )

    report = await GraphPersistenceReadinessProbe(config, _Pool(connection)).check()

    assert not report.ready
    assert report.code == "GRAPH_RESTORE_UNVERIFIED"


@pytest.mark.asyncio
async def test_checkpoint_metadata_binding_corruption_fails_restore_readiness() -> None:
    config = _config()
    connection = _Connection(config, inconsistent_check="checkpoint.metadata ->>")

    report = await GraphPersistenceReadinessProbe(config, _Pool(connection)).check()

    assert not report.ready
    assert report.code == "GRAPH_RESTORE_INCONSISTENT"
    assert report.checks["checkpoint_rows_consistent"] is False


@pytest.mark.asyncio
async def test_runtime_role_with_create_privilege_is_not_ready() -> None:
    config = _config()
    report = await GraphPersistenceReadinessProbe(
        config,
        _Pool(_Connection(config, can_create=True)),
    ).check()

    assert not report.ready
    assert report.code == "GRAPH_RUNTIME_ROLE_PRIVILEGED"


@pytest.mark.asyncio
async def test_runtime_role_with_temporary_privilege_is_not_ready() -> None:
    config = _config()
    report = await GraphPersistenceReadinessProbe(
        config,
        _Pool(_Connection(config, can_create_temporary=True)),
    ).check()

    assert not report.ready
    assert report.code == "GRAPH_RUNTIME_ROLE_PRIVILEGED"


@pytest.mark.asyncio
async def test_runtime_role_without_checkpoint_write_privileges_is_not_ready() -> None:
    config = _config()
    report = await GraphPersistenceReadinessProbe(
        config,
        _Pool(_Connection(config, can_write_checkpoints=False)),
    ).check()

    assert not report.ready
    assert report.code == "GRAPH_RUNTIME_ROLE_PRIVILEGED"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("can_execute_fanout", "can_mutate_fanout"),
    [(False, False), (True, True)],
)
async def test_runtime_role_requires_function_only_fanout_authority(
    can_execute_fanout: bool,
    can_mutate_fanout: bool,
) -> None:
    config = _config()
    report = await GraphPersistenceReadinessProbe(
        config,
        _Pool(
            _Connection(
                config,
                can_execute_fanout=can_execute_fanout,
                can_mutate_fanout=can_mutate_fanout,
            )
        ),
    ).check()

    assert not report.ready
    assert report.code == "GRAPH_RUNTIME_ROLE_PRIVILEGED"


@pytest.mark.asyncio
@pytest.mark.parametrize("unsafe_privilege", ["registry", "control", "delete", "append_only"])
async def test_runtime_role_with_admin_or_delete_privilege_is_not_ready(
    unsafe_privilege: str,
) -> None:
    config = _config()
    report = await GraphPersistenceReadinessProbe(
        config,
        _Pool(_Connection(config, unsafe_privilege=unsafe_privilege)),
    ).check()

    assert not report.ready
    assert report.code == "GRAPH_RUNTIME_ROLE_PRIVILEGED"


@pytest.mark.asyncio
async def test_missing_required_relation_is_not_ready() -> None:
    config = _config()
    report = await GraphPersistenceReadinessProbe(
        config,
        _Pool(_Connection(config, missing_relation="agent_graph_lease")),
    ).check()

    assert not report.ready
    assert report.code == "GRAPH_RELATION_MISSING"


@pytest.mark.asyncio
async def test_probe_timeout_fails_closed() -> None:
    config = _config(timeout_seconds=0.01)

    class SlowContext:
        async def __aenter__(self) -> Any:
            await asyncio.sleep(1)

        async def __aexit__(self, exc_type: Any, exc: Any, traceback: Any) -> None:
            return None

    class SlowPool:
        def connection(self, *, timeout: float) -> SlowContext:
            return SlowContext()

    report = await GraphPersistenceReadinessProbe(config, SlowPool()).check()

    assert not report.ready
    assert report.code == "GRAPH_READINESS_TIMEOUT"


@pytest.mark.asyncio
async def test_probe_timeout_rolls_back_before_returning_control_pool_connection() -> None:
    config = _config(timeout_seconds=0.01)
    events: list[str] = []
    query_cancelled = asyncio.Event()

    class TrackingTransaction:
        async def __aenter__(self) -> None:
            events.append("transaction-enter")

        async def __aexit__(self, exc_type: Any, exc: Any, traceback: Any) -> None:
            events.append("transaction-rollback" if exc_type else "transaction-commit")

    class TrackingConnection:
        def transaction(self) -> TrackingTransaction:
            return TrackingTransaction()

        async def execute(self, query: str, params: Any = None) -> _Cursor:
            events.append("query-start")
            await query_cancelled.wait()
            raise RuntimeError("simulated cancelled readiness query")

        async def cancel_safe(self, *, timeout: float) -> None:
            assert timeout > 0
            events.append("cancel-safe")
            query_cancelled.set()

    connection = TrackingConnection()

    class TrackingContext:
        async def __aenter__(self) -> TrackingConnection:
            events.append("pool-enter")
            return connection

        async def __aexit__(self, exc_type: Any, exc: Any, traceback: Any) -> None:
            events.append("pool-exit")

    class TrackingPool:
        def connection(self, *, timeout: float) -> TrackingContext:
            assert timeout == config.timeout_seconds
            return TrackingContext()

    report = await GraphPersistenceReadinessProbe(config, TrackingPool()).check()

    assert not report.ready
    assert report.code == "GRAPH_READINESS_TIMEOUT"
    assert events == [
        "pool-enter",
        "transaction-enter",
        "query-start",
        "cancel-safe",
        "transaction-rollback",
        "pool-exit",
    ]
