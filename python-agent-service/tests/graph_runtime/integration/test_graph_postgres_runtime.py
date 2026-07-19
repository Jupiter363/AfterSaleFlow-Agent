from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import json
from urllib.parse import quote

import psycopg
from psycopg import AsyncConnection, sql
from psycopg.errors import InsufficientPrivilege
from psycopg.rows import dict_row
import pytest
from testcontainers.postgres import PostgresContainer

from app.graph_runtime.checkpoint import (
    FencedPostgresSaver,
    TerminalResultMaterializer,
    bind_fence_context,
    bind_terminal_result_context,
    create_graph_pool,
)
from app.contracts.v1.models import ExecutionMetadata, Usage
from app.contracts.v1.codec import canonical_sha256_omitting
from app.graph_runtime.errors import GraphCommandHashConflictError, GraphNonceReplayError
from app.graph_runtime.ledger import CommandBinding, InvocationNonce, PostgresCommandLedger
from app.graph_runtime.migrations import (
    GraphMigrationRunner,
    acquire_graph_schema_advisory_lock,
    release_graph_schema_advisory_lock,
)
from app.graph_runtime.lease import LeaseAcquisitionKind, PostgresLeaseRepository
from app.graph_runtime.persistence_models import (
    GraphGatewayMode,
    GraphFenceContext,
    GraphFenceError,
    GraphMigrationError,
    GraphPoolConfig,
    GraphReadinessConfig,
)
from app.graph_runtime.readiness import GraphPersistenceReadinessProbe
from app.graph_runtime.recovery import PostgresRecoveryCoordinator
from app.graph_runtime.registry import CommandProfileBinding
from app.graph_runtime.result import CompletedDraft, ResultBindings
from app.graph_runtime.restore_validation import GraphRestoreValidationRunner
from langgraph.checkpoint.base import empty_checkpoint


pytestmark = pytest.mark.graph_postgres

IMAGE = (
    "public.ecr.aws/docker/library/postgres:16-alpine@"
    "sha256:e013e867e712fec275706a6c51c966f0bb0c93cfa8f51000f85a15f9865a28cb"
)
DATABASE = "graph_db"
SCHEMA = "graph_runtime"
OWNER = "graph_owner"
MIGRATOR = "graph_migrator"
RUNTIME = "graph_runtime"
RETENTION = "graph_retention"
MIGRATOR_PASSWORD = "migration-test-password"
RUNTIME_PASSWORD = "runtime-test-password"
RETENTION_PASSWORD = "retention-test-password"
GENERATION = "integration-generation-1"
RESTORE_HASH = "a" * 64
STATE_SCHEMA_HASH = "c" * 64
BINDING_HASH = "d" * 64
THREAD_ID = f"grt.v1.{'1' * 32}"
COMMAND_ID = "command-integration-1"


@dataclass(frozen=True, slots=True)
class _Database:
    admin_dsn: str
    migration_dsn: str
    runtime_dsn: str


@pytest.fixture(scope="module")
def graph_database() -> _Database:
    with PostgresContainer(
        IMAGE,
        username="postgres",
        password="postgres-test-password",
        dbname=DATABASE,
        driver=None,
    ) as container:
        host = container.get_container_host_ip()
        port = int(container.get_exposed_port(5432))
        admin_dsn = _dsn(host, port, "postgres", "postgres-test-password")
        migration_dsn = _dsn(host, port, MIGRATOR, MIGRATOR_PASSWORD)
        runtime_dsn = _dsn(host, port, RUNTIME, RUNTIME_PASSWORD)
        _provision_roles(admin_dsn)
        yield _Database(admin_dsn, migration_dsn, runtime_dsn)


@pytest.mark.asyncio
async def test_real_migrations_restore_readiness_and_runtime_acl(
    graph_database: _Database,
) -> None:
    first = await _migration_runner(graph_database).run()
    assert set(first.applied) | set(first.already_current) == {"G001", "G002", "G003"}

    second = await _migration_runner(graph_database).run()
    assert second.applied == ()
    assert second.already_current == ("G001", "G002", "G003")

    restore = await GraphRestoreValidationRunner(
        graph_database.migration_dsn,
        schema=SCHEMA,
        expected_user=MIGRATOR,
        owner_role=OWNER,
        environment_generation=GENERATION,
        restore_verification_hash=RESTORE_HASH,
    ).run()
    assert restore.environment_generation == GENERATION
    assert restore.restore_verification_hash == RESTORE_HASH
    assert "completed_results_consistent" in restore.checks

    pool = create_graph_pool(
        graph_database.runtime_dsn,
        GraphPoolConfig(
            schema=SCHEMA,
            min_size=1,
            max_size=2,
            max_waiting=2,
            acquire_timeout_seconds=5,
            connect_timeout_seconds=5,
        ),
    )
    await pool.open(wait=True, timeout=10)
    try:
        report = await GraphPersistenceReadinessProbe(
            GraphReadinessConfig(
                mode=GraphGatewayMode.SHADOW,
                expected_database=DATABASE,
                expected_user=RUNTIME,
                expected_environment_generation=GENERATION,
                expected_restore_verification_hash=RESTORE_HASH,
                schema=SCHEMA,
                timeout_seconds=5,
            ),
            pool,
        ).check()
    finally:
        await pool.close(timeout=10)

    assert report.ready, report
    assert report.code == "GRAPH_PERSISTENCE_READY"
    assert all(report.checks.values())

    with psycopg.connect(graph_database.runtime_dsn, autocommit=True) as connection:
        connection.execute(sql.SQL("set search_path to {}, pg_catalog").format(sql.Identifier(SCHEMA)))
        with pytest.raises(InsufficientPrivilege):
            connection.execute("delete from agent_graph_command")
        with pytest.raises(InsufficientPrivilege):
            connection.execute("update agent_graph_result set result_ref = result_ref")
        with pytest.raises(InsufficientPrivilege):
            connection.execute("create table forbidden_runtime_table (id integer)")


@pytest.mark.asyncio
async def test_real_schema_advisory_lock_rejects_a_competing_migrator(
    graph_database: _Database,
) -> None:
    await _migration_runner(graph_database).run()
    async with await AsyncConnection.connect(
        graph_database.migration_dsn,
        autocommit=True,
        prepare_threshold=0,
        row_factory=dict_row,
    ) as connection:
        await connection.execute(sql.SQL("set role {}").format(sql.Identifier(OWNER)))
        lock_key = await acquire_graph_schema_advisory_lock(connection, SCHEMA)
        try:
            with pytest.raises(GraphMigrationError, match="already running"):
                await _migration_runner(graph_database).run()
        finally:
            await release_graph_schema_advisory_lock(connection, lock_key)

    recovered = await _migration_runner(graph_database).run()
    assert recovered.applied == ()
    assert recovered.already_current == ("G001", "G002", "G003")


@pytest.mark.asyncio
async def test_real_fenced_saver_rejects_stale_writer_and_restores_after_pool_restart(
    graph_database: _Database,
) -> None:
    await _migration_runner(graph_database).run()
    await _seed_executable_command(graph_database)

    pool = _runtime_pool(graph_database)
    await pool.open(wait=True, timeout=10)
    first_fence = _fence(owner_id="worker-1", fencing_token=1)
    first_config = bind_fence_context(
        {"configurable": {"thread_id": THREAD_ID, "checkpoint_ns": "hearing"}},
        first_fence,
    )
    saver = FencedPostgresSaver(pool, acquire_timeout_seconds=5)
    try:
        first_saved = await saver.aput(first_config, empty_checkpoint(), {}, {})
        first_checkpoint_id = first_saved["configurable"]["checkpoint_id"]

        async with pool.connection(timeout=5) as connection:
            async with connection.transaction():
                leases = PostgresLeaseRepository()
                await leases.release(
                    connection,
                    thread_id=THREAD_ID,
                    command_id=COMMAND_ID,
                    owner_id="worker-1",
                    fencing_token=1,
                )
                takeover = await leases.acquire(
                    connection,
                    thread_id=THREAD_ID,
                    command_id=COMMAND_ID,
                    owner_id="worker-2",
                )
                assert takeover.kind is LeaseAcquisitionKind.TAKEOVER
                assert takeover.lease.fencing_token == 2
                await connection.execute(
                    """
                    insert into agent_graph_command_attempt (
                        attempt_id, thread_id, command_id, attempt_no,
                        owner_id, fencing_token, attempt_status
                    ) values (
                        'attempt-integration-2', %s, %s, 1,
                        'worker-2', 2, 'EXECUTING'
                    )
                    """,
                    (THREAD_ID, COMMAND_ID),
                )

        with pytest.raises(GraphFenceError, match="stale"):
            await saver.aput(first_config, empty_checkpoint(), {}, {})

        second_fence = _fence(owner_id="worker-2", fencing_token=2)
        second_config = bind_fence_context(
            {"configurable": {"thread_id": THREAD_ID, "checkpoint_ns": "hearing"}},
            second_fence,
        )
        second_saved = await saver.aput(second_config, empty_checkpoint(), {}, {})
        second_checkpoint_id = second_saved["configurable"]["checkpoint_id"]

        terminal_checkpoint = empty_checkpoint()
        terminal_checkpoint["channel_values"] = {
            "cognitive_revision": 4,
            "terminal_draft": {"status": "COMPLETED"},
            "usage_by_invocation": {
                "invocation-integration": {
                    "input_tokens": 1,
                    "output_tokens": 1,
                    "total_tokens": 2,
                }
            },
            "result_json": {"pending": True},
        }
        terminal_checkpoint["channel_versions"] = {"result_json": "v-result-1"}
        terminal_config = bind_terminal_result_context(
            second_config,
            _terminal_materializer(),
        )
        terminal_saved = await saver.aput(
            terminal_config,
            terminal_checkpoint,
            {},
            {"result_json": "v-result-1"},
        )
        terminal_checkpoint_id = terminal_saved["configurable"]["checkpoint_id"]
    finally:
        await pool.close(timeout=10)

    assert second_checkpoint_id != first_checkpoint_id
    assert terminal_checkpoint_id != second_checkpoint_id

    replacement_pool = _runtime_pool(graph_database)
    await replacement_pool.open(wait=True, timeout=10)
    try:
        replacement = FencedPostgresSaver(replacement_pool, acquire_timeout_seconds=5)
        restored = await replacement.aget_tuple(second_config)
        async with replacement_pool.connection(timeout=5) as connection:
            async with connection.transaction():
                ledger = PostgresCommandLedger()
                command_before = await ledger.load(
                    connection,
                    thread_id=THREAD_ID,
                    command_id=COMMAND_ID,
                )
                recovery = PostgresRecoveryCoordinator(
                    ledger=ledger,
                    leases=PostgresLeaseRepository(),
                )
                completed, result = await recovery.reconcile_terminal(
                    connection,
                    binding=command_before.binding,
                    owner_id="worker-2",
                )
                cached, cached_result = await recovery.reconcile_terminal(
                    connection,
                    binding=command_before.binding,
                    owner_id="worker-never-acquires",
                )
    finally:
        await replacement_pool.close(timeout=10)

    assert restored is not None
    assert restored.config["configurable"]["checkpoint_id"] == terminal_checkpoint_id
    assert restored.config["configurable"]["__trusted_graph_fence_context__"] == second_fence
    assert completed.status.value == "COMPLETED"
    assert completed.committed_checkpoint_id == terminal_checkpoint_id
    assert result.checkpoint_id == terminal_checkpoint_id
    assert cached == completed
    assert cached_result == result


@pytest.mark.asyncio
async def test_real_command_ledger_is_hash_idempotent_and_nonce_replay_safe(
    graph_database: _Database,
) -> None:
    await _migration_runner(graph_database).run()
    await _seed_executable_command(graph_database, ensure_lease=False)
    ledger = PostgresCommandLedger()
    binding = _command_binding("command-ledger-1", variant="one")
    now = datetime.now(timezone.utc)
    first_nonce = _nonce("delivery-jti-1", now)
    second_nonce = _nonce("delivery-jti-2", now + timedelta(seconds=1))

    pool = _runtime_pool(graph_database)
    await pool.open(wait=True, timeout=10)
    try:
        async with pool.connection(timeout=5) as connection:
            async with connection.transaction():
                first = await ledger.register_with_nonce(
                    connection,
                    binding=binding,
                    nonce=first_nonce,
                )
            async with connection.transaction():
                duplicate = await ledger.register_with_nonce(
                    connection,
                    binding=binding,
                    nonce=second_nonce,
                )

            assert first.created
            assert not duplicate.created
            assert duplicate.command == first.command

            with pytest.raises(GraphNonceReplayError):
                async with connection.transaction():
                    await ledger.register_with_nonce(
                        connection,
                        binding=binding,
                        nonce=second_nonce,
                    )

            conflict = _command_binding("command-ledger-1", variant="two")
            with pytest.raises(GraphCommandHashConflictError):
                async with connection.transaction():
                    await ledger.register_with_nonce(
                        connection,
                        binding=conflict,
                        nonce=_nonce("delivery-jti-3", now + timedelta(seconds=2)),
                    )

            async with connection.transaction():
                referenced_keys = await ledger.referenced_verification_key_ids(connection)
                nonce_count = (
                    await (
                        await connection.execute(
                            """
                            select count(*) as count
                              from agent_graph_invocation_nonce
                             where thread_id = %s and command_id = %s
                            """,
                            (THREAD_ID, binding.command_id),
                        )
                    ).fetchone()
                )["count"]
    finally:
        await pool.close(timeout=10)

    assert referenced_keys == frozenset({"key-1"})
    assert nonce_count == 2


def _migration_runner(database: _Database) -> GraphMigrationRunner:
    return GraphMigrationRunner(
        database.migration_dsn,
        schema=SCHEMA,
        expected_user=MIGRATOR,
        owner_role=OWNER,
        runtime_role=RUNTIME,
        retention_role=RETENTION,
        environment_generation=GENERATION,
    )


def _runtime_pool(database: _Database):
    return create_graph_pool(
        database.runtime_dsn,
        GraphPoolConfig(
            schema=SCHEMA,
            min_size=1,
            max_size=2,
            max_waiting=2,
            acquire_timeout_seconds=5,
            connect_timeout_seconds=5,
        ),
    )


def _fence(*, owner_id: str, fencing_token: int) -> GraphFenceContext:
    return GraphFenceContext(
        thread_id=THREAD_ID,
        command_id=COMMAND_ID,
        owner_id=owner_id,
        fencing_token=fencing_token,
        request_hash=_request_document("execution")["request_hash"],
        room_epoch=3,
        graph_key="hearing_flow",
        graph_version="hearing_flow.v2",
        checkpoint_schema_version="hearing_checkpoint.v2",
    )


def _terminal_materializer() -> TerminalResultMaterializer:
    return TerminalResultMaterializer(
        thread_id=THREAD_ID,
        request_hash=_request_document("execution")["request_hash"],
        draft=CompletedDraft(status="COMPLETED"),
        bindings=ResultBindings(
            command_id=COMMAND_ID,
            logical_run_id="run-integration-1",
            attempt_id="attempt-integration-2",
            graph_key="hearing_flow",
            graph_version="hearing_flow.v2",
            checkpoint_id="pending",
            cognitive_revision=4,
            public_event_proposals=(),
            artifact_operations=(),
            usage=Usage(input_tokens=1, output_tokens=1, total_tokens=2),
            execution_metadata=ExecutionMetadata(
                prompt_version="prompt.v1",
                model_profile_id="model.v1",
                schema_version="output.v1",
                policy_version="policy.v1",
                guardrail_version="guardrail.v1",
            ),
        ),
    )


def _command_binding(command_id: str, *, variant: str) -> CommandBinding:
    request_json = _request_document(variant)
    return CommandBinding(
        thread_id=THREAD_ID,
        command_id=command_id,
        request_schema_version="room-graph-command.v1",
        request_json=request_json,
        request_hash=request_json["request_hash"],
        room_epoch=3,
        graph_key="hearing_flow",
        graph_version="hearing_flow.v2",
        checkpoint_schema_version="hearing_checkpoint.v2",
        profile=CommandProfileBinding(
            command_schema_version="room-graph-command.v1",
            prompt_version="prompt.v1",
            model_profile_id="model.v1",
            output_schema_version="output.v1",
            policy_version="policy.v1",
            guardrail_version="guardrail.v1",
            tool_policy_version="tools.v1",
        ),
        deadline_at=datetime.now(timezone.utc) + timedelta(minutes=10),
    )


def _request_document(variant: str) -> dict[str, object]:
    request_json: dict[str, object] = {
        "schema_version": "room-graph-command.v1",
        "logical_run_id": "run-integration-1",
        "attempt_id": "attempt-integration-2",
        "variant": variant,
        "retry_budget": {
            "provider_attempts_remaining": 2,
            "activity_attempts_remaining": 2,
            "repairs_remaining": 1,
        },
        "request_hash": "0" * 64,
    }
    request_hash = canonical_sha256_omitting(request_json, "request_hash")
    request_json["request_hash"] = request_hash
    return request_json


def _nonce(jti: str, issued_at: datetime) -> InvocationNonce:
    return InvocationNonce(
        issuer="java-api-service",
        key_id="key-1",
        jti=jti,
        issued_at=issued_at,
        token_expires_at=issued_at + timedelta(seconds=30),
        retained_until=issued_at + timedelta(hours=24),
    )


async def _seed_executable_command(
    database: _Database,
    *,
    ensure_lease: bool = True,
) -> None:
    binding = _command_binding(COMMAND_ID, variant="execution")
    async with await AsyncConnection.connect(
        database.migration_dsn,
        autocommit=True,
        prepare_threshold=0,
        row_factory=dict_row,
    ) as connection:
        await connection.execute(sql.SQL("set role {}").format(sql.Identifier(OWNER)))
        await connection.execute(
            sql.SQL("set search_path to {}, pg_catalog, pg_temp").format(
                sql.Identifier(SCHEMA)
            )
        )
        await connection.execute(
            """
            insert into agent_graph_version_registry (
                graph_key, graph_version, checkpoint_schema_version,
                registry_state, state_schema_version, state_schema_hash,
                command_schema_version, result_schema_version,
                prompt_version, model_profile_id, output_schema_version,
                policy_version, guardrail_version, tool_policy_version,
                binding_hash, code_build_id, loadable, activated_at
            ) values (
                'hearing_flow', 'hearing_flow.v2', 'hearing_checkpoint.v2',
                'SHADOW', 'hearing_state.v2', %s,
                'room-graph-command.v1', 'room-graph-result.v1',
                'prompt.v1', 'model.v1', 'output.v1',
                'policy.v1', 'guardrail.v1', 'tools.v1',
                %s, 'integration-build', true, clock_timestamp()
            ) on conflict do nothing
            """,
            (STATE_SCHEMA_HASH, BINDING_HASH),
        )

    pool = _runtime_pool(database)
    await pool.open(wait=True, timeout=10)
    try:
        async with pool.connection(timeout=5) as connection:
            async with connection.transaction():
                await connection.execute(
                    """
                    insert into graph_thread_registry (
                        thread_id, tenant_surrogate, case_id, room_type, room_epoch,
                        actor_scope_json, actor_scope_hash, agent_session_id,
                        shared_session, graph_key, graph_version,
                        checkpoint_schema_version
                    ) values (
                        %s, 'tenant-integration', 'case-integration', 'HEARING', 3,
                        '{"audience":"PUBLIC"}'::jsonb, %s, 'session-integration',
                        true, 'hearing_flow', 'hearing_flow.v2', 'hearing_checkpoint.v2'
                    ) on conflict do nothing
                    """,
                    (THREAD_ID, "e" * 64),
                )
                await connection.execute(
                    """
                    insert into agent_graph_command (
                        thread_id, command_id, request_schema_version,
                        request_json, request_hash, execution_mode, room_epoch,
                        graph_key, graph_version, checkpoint_schema_version,
                        prompt_version, model_profile_id, output_schema_version,
                        policy_version, guardrail_version, tool_policy_version,
                        deadline_at, status, attempt_count, fencing_token, started_at
                    ) values (
                        %s, %s, 'room-graph-command.v1', %s::jsonb, %s,
                        'SHADOW', 3, 'hearing_flow', 'hearing_flow.v2',
                        'hearing_checkpoint.v2', 'prompt.v1', 'model.v1',
                        'output.v1', 'policy.v1', 'guardrail.v1', 'tools.v1',
                        clock_timestamp() + interval '10 minutes',
                        'EXECUTING', 1, 1, clock_timestamp()
                    ) on conflict do nothing
                    """,
                    (
                        THREAD_ID,
                        COMMAND_ID,
                        json.dumps(binding.request_json, separators=(",", ":")),
                        binding.request_hash,
                    ),
                )
                if not ensure_lease:
                    return
                await connection.execute(
                    """
                    insert into agent_graph_lease (
                        thread_id, command_id, owner_id, fencing_token,
                        lease_expires_at
                    ) values (
                        %s, %s, 'worker-1', 1,
                        clock_timestamp() + interval '29 seconds'
                    ) on conflict (thread_id) do update
                    set command_id = excluded.command_id,
                        owner_id = excluded.owner_id,
                        fencing_token = excluded.fencing_token,
                        lease_expires_at = excluded.lease_expires_at,
                        acquired_at = clock_timestamp(),
                        renewed_at = clock_timestamp(),
                        released_at = null,
                        cancelled_at = null,
                        cancelled_by_command_id = null,
                        lease_revision = agent_graph_lease.lease_revision + 1
                    """,
                    (THREAD_ID, COMMAND_ID),
                )
    finally:
        await pool.close(timeout=10)


def _dsn(host: str, port: int, user: str, password: str) -> str:
    return (
        f"postgresql://{quote(user, safe='')}:{quote(password, safe='')}"
        f"@{host}:{port}/{DATABASE}"
    )


def _provision_roles(admin_dsn: str) -> None:
    with psycopg.connect(admin_dsn, autocommit=True) as connection:
        connection.execute(
            sql.SQL("create role {} nologin nosuperuser nocreatedb nocreaterole inherit")
            .format(sql.Identifier(OWNER))
        )
        for role, password in (
            (MIGRATOR, MIGRATOR_PASSWORD),
            (RUNTIME, RUNTIME_PASSWORD),
            (RETENTION, RETENTION_PASSWORD),
        ):
            connection.execute(
                sql.SQL(
                    "create role {} login nosuperuser nocreatedb nocreaterole "
                    "noinherit noreplication password {}"
                ).format(sql.Identifier(role), sql.Literal(password))
            )
        connection.execute(
            sql.SQL("grant {} to {}").format(
                sql.Identifier(OWNER), sql.Identifier(MIGRATOR)
            )
        )
        connection.execute(
            sql.SQL("create schema {} authorization {}").format(
                sql.Identifier(SCHEMA), sql.Identifier(OWNER)
            )
        )
        connection.execute("revoke all on schema public from public")
        connection.execute(
            sql.SQL("revoke all on schema {} from public").format(sql.Identifier(SCHEMA))
        )
        connection.execute(
            sql.SQL("revoke all on database {} from public").format(sql.Identifier(DATABASE))
        )
        connection.execute(
            sql.SQL("grant connect on database {} to {}, {}, {}").format(
                sql.Identifier(DATABASE),
                sql.Identifier(MIGRATOR),
                sql.Identifier(RUNTIME),
                sql.Identifier(RETENTION),
            )
        )
        connection.execute(
            sql.SQL("revoke temporary on database {} from {}, {}, {}, {}").format(
                sql.Identifier(DATABASE),
                sql.Identifier(OWNER),
                sql.Identifier(MIGRATOR),
                sql.Identifier(RUNTIME),
                sql.Identifier(RETENTION),
            )
        )
        connection.execute(
            sql.SQL("grant usage on schema {} to {}, {}").format(
                sql.Identifier(SCHEMA),
                sql.Identifier(RUNTIME),
                sql.Identifier(RETENTION),
            )
        )
