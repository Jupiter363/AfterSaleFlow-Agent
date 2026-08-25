from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from dataclasses import dataclass, replace
from datetime import datetime, timedelta, timezone
import json
from types import SimpleNamespace
from typing import Any
from urllib.parse import quote
from uuid import uuid4

import psycopg
from psycopg import AsyncConnection, sql
from psycopg.errors import CheckViolation, InsufficientPrivilege
from psycopg.pq import TransactionStatus
from psycopg.rows import dict_row
import pytest
from testcontainers.postgres import PostgresContainer

import app.graph_runtime.checkpoint as checkpoint_module
from app.graph_runtime.checkpoint import (
    FencedPostgresSaver,
    TerminalResultMaterializer,
    bind_fence_context,
    bind_terminal_result_context,
    create_graph_pool,
)
from app.contracts.v1.models import ExecutionMetadata, Usage
from app.contracts.v1.codec import canonical_sha256_omitting
from app.graph_runtime.bulkhead import GraphBulkheadScope, GraphPermitFenceContext
from app.graph_runtime.errors import (
    GraphCommandHashConflictError,
    GraphLeaseLostError,
    GraphNonceReplayError,
    GraphPermitBindingError,
    GraphPermitLostError,
    GraphTerminalBindingError,
)
from app.graph_runtime.gateway import GatewayExecution, GraphCommandGateway
from app.graph_runtime.ledger import CommandBinding, InvocationNonce, PostgresCommandLedger
from app.graph_runtime.migrations import (
    GraphMigrationRunner,
    acquire_graph_schema_advisory_lock,
    load_graph_migrations,
    release_graph_schema_advisory_lock,
)
from app.graph_runtime.lease import LeaseAcquisitionKind, PostgresLeaseRepository
from app.graph_runtime.postgres_bulkhead import (
    PostgresBulkheadConfig,
    PostgresGraphFanoutBulkhead,
)
from app.graph_runtime.persistence_models import (
    GraphBindingError,
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
from app.graph_runtime.target_e2e import TargetE2ERoomProposal, TargetE2ERoomProposalSource
from app.security.invocation_envelope import INVOCATION_CLOCK_SKEW_SECONDS
from langgraph.checkpoint.base import empty_checkpoint
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver


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
STATEMENT_TIMEOUT_MS = 5_000


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
    assert set(first.applied) | set(first.already_current) == {
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
        "G014",
    }

    second = await _migration_runner(graph_database).run()
    assert second.applied == ()
    assert second.already_current == (
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
        "G014",
    )

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
        parallel_guard = connection.execute(
            """
            select require_parallel_intake_graph_command(%s, %s, %s)
            """,
            (f"grt.v1.{'0' * 32}", "missing-command", "0" * 64),
        ).fetchone()
        assert parallel_guard == (False,)
        with pytest.raises(InsufficientPrivilege):
            connection.execute("delete from agent_graph_command")
        with pytest.raises(InsufficientPrivilege):
            connection.execute("update agent_graph_result set result_ref = result_ref")
        with pytest.raises(InsufficientPrivilege):
            connection.execute("create table forbidden_runtime_table (id integer)")
        with pytest.raises(InsufficientPrivilege):
            connection.execute(
                "update agent_graph_fanout_config set global_limit = global_limit"
            )
        with pytest.raises(InsufficientPrivilege):
            connection.execute(
                "insert into agent_graph_fanout_permit (request_id) values ('forbidden')"
            )


@pytest.mark.asyncio
async def test_real_g007_allows_only_bound_same_revision_checkpoint_children(
    graph_database: _Database,
) -> None:
    await _migration_runner(graph_database).run()
    thread_id = f"grt.v1.{uuid4().hex}"
    graph_key = f"checkpoint_parent_{uuid4().hex[:12]}"
    graph_version = "checkpoint_parent.v1"
    checkpoint_schema_version = "checkpoint_parent.v1"
    parent_checkpoint_id = "checkpoint-parent"
    child_checkpoint_id = "checkpoint-child"
    unbound_checkpoint_id = "checkpoint-unbound"
    invalid_checkpoint_id = "checkpoint-invalid"
    metadata = {
        "graph_thread_id": thread_id,
        "graph_room_epoch": 3,
        "graph_key": graph_key,
        "graph_version": graph_version,
        "graph_checkpoint_schema_version": checkpoint_schema_version,
        "graph_cognitive_revision": 1,
    }

    async with await AsyncConnection.connect(
        graph_database.migration_dsn,
        autocommit=True,
        prepare_threshold=0,
        row_factory=dict_row,
    ) as connection:
        await connection.execute(sql.SQL("set role {}").format(sql.Identifier(OWNER)))
        await connection.execute(
            sql.SQL("set search_path to {}, pg_catalog, pg_temp").format(sql.Identifier(SCHEMA))
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
                %s, %s, %s,
                'SHADOW', 'checkpoint_parent.state.v1', %s,
                'room-graph-command.v1', 'room-graph-result.v1',
                'prompt.v1', 'model.v1', 'output.v1',
                'policy.v1', 'guardrail.v1', 'tools.v1',
                %s, 'integration-build', true, clock_timestamp()
            )
            """,
            (graph_key, graph_version, checkpoint_schema_version, STATE_SCHEMA_HASH, BINDING_HASH),
        )
        await connection.execute(
            """
            insert into graph_thread_registry (
                thread_id, tenant_surrogate, case_id, room_type, room_epoch,
                actor_scope_json, actor_scope_hash, agent_session_id,
                graph_key, graph_version, checkpoint_schema_version,
                cognitive_revision, last_checkpoint_ns, last_checkpoint_id
            ) values (
                %s, 'tenant-checkpoint-parent', 'case-checkpoint-parent', 'INTAKE', 3,
                '{"audience":"PUBLIC"}'::jsonb, %s, 'session-checkpoint-parent',
                %s, %s, %s,
                1, 'intake', %s
            )
            """,
            (
                thread_id,
                "e" * 64,
                graph_key,
                graph_version,
                checkpoint_schema_version,
                parent_checkpoint_id,
            ),
        )
        await connection.execute(
            """
            insert into checkpoints (
                thread_id, checkpoint_ns, checkpoint_id, parent_checkpoint_id,
                checkpoint, metadata
            ) values (%s, 'intake', %s, %s, '{}'::jsonb, %s::jsonb)
            """,
            (
                thread_id,
                unbound_checkpoint_id,
                child_checkpoint_id,
                json.dumps({"graph_cognitive_revision": 1}),
            ),
        )
        with pytest.raises(CheckViolation, match="durable parent chain"):
            await connection.execute(
                """
                update graph_thread_registry
                   set last_checkpoint_ns = 'intake', last_checkpoint_id = %s
                 where thread_id = %s
                """,
                (unbound_checkpoint_id, thread_id),
            )

        await connection.execute(
            """
            insert into checkpoints (
                thread_id, checkpoint_ns, checkpoint_id, parent_checkpoint_id,
                checkpoint, metadata
            ) values (%s, 'intake', %s, %s, '{}'::jsonb, %s::jsonb)
            """,
            (thread_id, child_checkpoint_id, parent_checkpoint_id, json.dumps(metadata)),
        )
        await connection.execute(
            """
            update graph_thread_registry
               set last_checkpoint_ns = 'intake', last_checkpoint_id = %s
             where thread_id = %s
            """,
            (child_checkpoint_id, thread_id),
        )

        await connection.execute(
            """
            insert into checkpoints (
                thread_id, checkpoint_ns, checkpoint_id, parent_checkpoint_id,
                checkpoint, metadata
            ) values (%s, 'intake', %s, 'not-the-current-parent', '{}'::jsonb, %s::jsonb)
            """,
            (thread_id, invalid_checkpoint_id, json.dumps(metadata)),
        )
        with pytest.raises(CheckViolation, match="durable parent chain"):
            await connection.execute(
                """
                update graph_thread_registry
                   set last_checkpoint_ns = 'intake', last_checkpoint_id = %s
                 where thread_id = %s
                """,
                (invalid_checkpoint_id, thread_id),
            )
        with pytest.raises(CheckViolation, match="cognitive revision cannot jump"):
            await connection.execute(
                """
                update graph_thread_registry
                   set cognitive_revision = 3
                 where thread_id = %s
                """,
                (thread_id,),
            )


@pytest.mark.asyncio
async def test_real_g008_allows_only_the_fresh_zero_to_two_bootstrap_transition(
    graph_database: _Database,
) -> None:
    await _migration_runner(graph_database).run()
    graph_key = f"fresh_bootstrap_{uuid4().hex[:12]}"
    graph_version = "fresh_bootstrap.v1"
    checkpoint_schema_version = "fresh_bootstrap.v1"
    execution_lane = "TARGET_E2E_CANDIDATE"
    activation_id = "p9act.v1." + "a" * 32
    room_fencing_token = 31
    command_fencing_token = 37
    request_hash = "a" * 64
    result_hash = "b" * 64
    command_hash = "c" * 64
    command_envelope_hash = "d" * 64
    accepted_thread_id = f"grt.v1.{uuid4().hex}"
    empty_pointer_thread_id = f"grt.v1.{uuid4().hex}"
    empty_checkpoint_id_thread_id = f"grt.v1.{uuid4().hex}"
    missing_checkpoint_thread_id = f"grt.v1.{uuid4().hex}"
    metadata_mismatch_thread_id = f"grt.v1.{uuid4().hex}"
    command_id_mismatch_thread_id = f"grt.v1.{uuid4().hex}"
    request_hash_mismatch_thread_id = f"grt.v1.{uuid4().hex}"
    result_mismatch_thread_id = f"grt.v1.{uuid4().hex}"
    missing_command_thread_id = f"grt.v1.{uuid4().hex}"
    non_active_thread_id = f"grt.v1.{uuid4().hex}"
    too_far_thread_id = f"grt.v1.{uuid4().hex}"
    old_pointer_thread_id = f"grt.v1.{uuid4().hex}"
    accepted_checkpoint_id = "fresh-bootstrap-accepted"
    missing_checkpoint_id = "fresh-bootstrap-missing"
    metadata_mismatch_checkpoint_id = "fresh-bootstrap-mismatch"
    command_id_mismatch_checkpoint_id = "fresh-bootstrap-command-id-mismatch"
    request_hash_mismatch_checkpoint_id = "fresh-bootstrap-request-hash-mismatch"
    result_mismatch_checkpoint_id = "fresh-bootstrap-result-mismatch"
    missing_command_checkpoint_id = "fresh-bootstrap-missing-command"
    non_active_checkpoint_id = "fresh-bootstrap-non-active"

    async with await AsyncConnection.connect(
        graph_database.migration_dsn,
        autocommit=True,
        prepare_threshold=0,
        row_factory=dict_row,
    ) as connection:
        await connection.execute(sql.SQL("set role {}").format(sql.Identifier(OWNER)))
        await connection.execute(
            sql.SQL("set search_path to {}, pg_catalog, pg_temp").format(sql.Identifier(SCHEMA))
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
                %s, %s, %s,
                'ACTIVE_CANDIDATE', 'fresh_bootstrap.state.v1', %s,
                'room-graph-command.v1', 'room-graph-result.v1',
                'prompt.v1', 'model.v1', 'output.v1',
                'policy.v1', 'guardrail.v1', 'tools.v1',
                %s, 'integration-build', true, clock_timestamp()
            )
            """,
            (graph_key, graph_version, checkpoint_schema_version, STATE_SCHEMA_HASH, BINDING_HASH),
        )
        for ordinal, thread_id, lifecycle_status, checkpoint_ns, checkpoint_id in (
            (1, accepted_thread_id, "ACTIVE", None, None),
            (2, empty_pointer_thread_id, "ACTIVE", None, None),
            (3, empty_checkpoint_id_thread_id, "ACTIVE", None, None),
            (4, missing_checkpoint_thread_id, "ACTIVE", None, None),
            (5, metadata_mismatch_thread_id, "ACTIVE", None, None),
            (6, command_id_mismatch_thread_id, "ACTIVE", None, None),
            (7, request_hash_mismatch_thread_id, "ACTIVE", None, None),
            (8, result_mismatch_thread_id, "ACTIVE", None, None),
            (9, missing_command_thread_id, "ACTIVE", None, None),
            (10, non_active_thread_id, "CANCELLED", None, None),
            (11, too_far_thread_id, "ACTIVE", None, None),
            (12, old_pointer_thread_id, "ACTIVE", "intake", "already-durable"),
        ):
            await connection.execute(
                """
                insert into graph_thread_registry (
                    thread_id, tenant_surrogate, case_id, room_type, room_epoch,
                    actor_scope_json, actor_scope_hash, agent_session_id,
                    lifecycle_status, graph_key, graph_version, checkpoint_schema_version,
                    last_checkpoint_ns, last_checkpoint_id
                ) values (
                    %s, %s, %s, 'INTAKE', 3,
                    '{"audience":"PUBLIC"}'::jsonb, %s, %s,
                    %s, %s, %s, %s, %s, %s
                )
                """,
                (
                    thread_id,
                    f"tenant-fresh-bootstrap-{ordinal}",
                    f"case-fresh-bootstrap-{ordinal}",
                    "f" * 64,
                    f"session-fresh-bootstrap-{ordinal}",
                    lifecycle_status,
                    graph_key,
                    graph_version,
                    checkpoint_schema_version,
                    checkpoint_ns,
                    checkpoint_id,
                ),
            )

        def checkpoint_metadata(
            thread_id: str,
            command_id: str,
            *,
            revision: int = 2,
            overrides: dict[str, object] | None = None,
        ) -> dict[str, object]:
            metadata = {
                "graph_thread_id": thread_id,
                "graph_room_epoch": 3,
                "graph_key": graph_key,
                "graph_version": graph_version,
                "graph_checkpoint_schema_version": checkpoint_schema_version,
                "graph_cognitive_revision": revision,
                "graph_command_id": command_id,
                "graph_request_hash": request_hash,
                "graph_fencing_token": str(command_fencing_token),
                "graph_result_hash": result_hash,
                "graph_result_ref": f"result/{command_id}",
                "graph_execution_lane": execution_lane,
                "graph_activation_id": activation_id,
                "graph_room_fencing_token": str(room_fencing_token),
                "graph_command_hash": command_hash,
                "graph_command_envelope_hash": command_envelope_hash,
            }
            if overrides is not None:
                metadata.update(overrides)
            return metadata

        async def insert_checkpoint(
            thread_id: str,
            checkpoint_id: str,
            metadata: dict[str, object],
        ) -> None:
            await connection.execute(
                """
                insert into checkpoints (
                    thread_id, checkpoint_ns, checkpoint_id, parent_checkpoint_id,
                    checkpoint, metadata
                ) values (%s, 'intake', %s, null, '{}'::jsonb, %s::jsonb)
                """,
                (thread_id, checkpoint_id, json.dumps(metadata)),
            )

        async def insert_result_checkpointed_command(
            thread_id: str,
            command_id: str,
            checkpoint_id: str,
        ) -> None:
            await connection.execute(
                """
                insert into agent_graph_command (
                    thread_id, command_id, request_schema_version, request_json, request_hash,
                    execution_mode, activation_id, room_fencing_token,
                    command_hash, command_envelope_hash,
                    room_epoch, graph_key, graph_version, checkpoint_schema_version,
                    prompt_version, model_profile_id, output_schema_version,
                    policy_version, guardrail_version, tool_policy_version,
                    deadline_at, status, fencing_token,
                    committed_checkpoint_ns, committed_checkpoint_id,
                    result_ref, result_hash, result_checkpointed_at
                ) values (
                    %s, %s, 'room-graph-command.v1', '{}'::jsonb, %s,
                    %s, %s, %s, %s, %s,
                    3, %s, %s, %s,
                    'prompt.v1', 'model.v1', 'output.v1',
                    'policy.v1', 'guardrail.v1', 'tools.v1',
                    clock_timestamp() + interval '5 minutes', 'RESULT_CHECKPOINTED', %s,
                    'intake', %s, %s, %s, clock_timestamp()
                )
                """,
                (
                    thread_id,
                    command_id,
                    request_hash,
                    execution_lane,
                    activation_id,
                    room_fencing_token,
                    command_hash,
                    command_envelope_hash,
                    graph_key,
                    graph_version,
                    checkpoint_schema_version,
                    command_fencing_token,
                    checkpoint_id,
                    f"result/{command_id}",
                    result_hash,
                ),
            )

        await insert_checkpoint(
            accepted_thread_id,
            accepted_checkpoint_id,
            checkpoint_metadata(accepted_thread_id, "fresh-bootstrap-accepted"),
        )
        await insert_result_checkpointed_command(
            accepted_thread_id,
            "fresh-bootstrap-accepted",
            accepted_checkpoint_id,
        )
        await insert_result_checkpointed_command(
            missing_checkpoint_thread_id,
            "fresh-bootstrap-missing-checkpoint",
            missing_checkpoint_id,
        )
        await insert_checkpoint(
            metadata_mismatch_thread_id,
            metadata_mismatch_checkpoint_id,
            checkpoint_metadata(
                metadata_mismatch_thread_id,
                "fresh-bootstrap-metadata-mismatch",
                revision=1,
            ),
        )
        await insert_result_checkpointed_command(
            metadata_mismatch_thread_id,
            "fresh-bootstrap-metadata-mismatch",
            metadata_mismatch_checkpoint_id,
        )
        for thread_id, checkpoint_id, command_id, overrides in (
            (
                command_id_mismatch_thread_id,
                command_id_mismatch_checkpoint_id,
                "fresh-bootstrap-command-id-mismatch",
                {"graph_command_id": "a-different-command"},
            ),
            (
                request_hash_mismatch_thread_id,
                request_hash_mismatch_checkpoint_id,
                "fresh-bootstrap-request-hash-mismatch",
                {"graph_request_hash": "e" * 64},
            ),
            (
                result_mismatch_thread_id,
                result_mismatch_checkpoint_id,
                "fresh-bootstrap-result-mismatch",
                {"graph_result_hash": "f" * 64},
            ),
        ):
            await insert_checkpoint(
                thread_id,
                checkpoint_id,
                checkpoint_metadata(thread_id, command_id, overrides=overrides),
            )
            await insert_result_checkpointed_command(thread_id, command_id, checkpoint_id)
        await insert_checkpoint(
            missing_command_thread_id,
            missing_command_checkpoint_id,
            checkpoint_metadata(missing_command_thread_id, "fresh-bootstrap-missing-command"),
        )
        await insert_checkpoint(
            non_active_thread_id,
            non_active_checkpoint_id,
            checkpoint_metadata(non_active_thread_id, "fresh-bootstrap-non-active"),
        )
        await insert_result_checkpointed_command(
            non_active_thread_id,
            "fresh-bootstrap-non-active",
            non_active_checkpoint_id,
        )

        await connection.execute(
            """
            update graph_thread_registry
               set cognitive_revision = 2,
                   last_checkpoint_ns = 'intake',
                   last_checkpoint_id = %s
             where thread_id = %s
            """,
            (accepted_checkpoint_id, accepted_thread_id),
        )
        accepted = await (
            await connection.execute(
                """
                select cognitive_revision, last_checkpoint_ns, last_checkpoint_id
                  from graph_thread_registry
                 where thread_id = %s
                """,
                (accepted_thread_id,),
            )
        ).fetchone()
        assert accepted == {
            "cognitive_revision": 2,
            "last_checkpoint_ns": "intake",
            "last_checkpoint_id": accepted_checkpoint_id,
        }

        with pytest.raises(CheckViolation, match="cognitive revision cannot jump"):
            await connection.execute(
                """
                update graph_thread_registry
                   set cognitive_revision = 2
                 where thread_id = %s
                """,
                (empty_pointer_thread_id,),
            )
        with pytest.raises(CheckViolation, match="cognitive revision cannot jump"):
            await connection.execute(
                """
                update graph_thread_registry
                   set cognitive_revision = 2,
                       last_checkpoint_ns = 'intake',
                       last_checkpoint_id = ''
                 where thread_id = %s
                """,
                (empty_checkpoint_id_thread_id,),
            )
        with pytest.raises(CheckViolation, match="cognitive revision cannot jump"):
            await connection.execute(
                """
                update graph_thread_registry
                   set cognitive_revision = 2,
                       last_checkpoint_ns = 'intake',
                       last_checkpoint_id = %s
                 where thread_id = %s
                """,
                (missing_checkpoint_id, missing_checkpoint_thread_id),
            )
        with pytest.raises(CheckViolation, match="cognitive revision cannot jump"):
            await connection.execute(
                """
                update graph_thread_registry
                   set cognitive_revision = 2,
                       last_checkpoint_ns = 'intake',
                       last_checkpoint_id = %s
                 where thread_id = %s
                """,
                (metadata_mismatch_checkpoint_id, metadata_mismatch_thread_id),
            )
        for thread_id, checkpoint_id in (
            (command_id_mismatch_thread_id, command_id_mismatch_checkpoint_id),
            (request_hash_mismatch_thread_id, request_hash_mismatch_checkpoint_id),
            (result_mismatch_thread_id, result_mismatch_checkpoint_id),
        ):
            with pytest.raises(CheckViolation, match="cognitive revision cannot jump"):
                await connection.execute(
                    """
                    update graph_thread_registry
                       set cognitive_revision = 2,
                           last_checkpoint_ns = 'intake',
                           last_checkpoint_id = %s
                     where thread_id = %s
                    """,
                    (checkpoint_id, thread_id),
                )
        with pytest.raises(CheckViolation, match="cognitive revision cannot jump"):
            await connection.execute(
                """
                update graph_thread_registry
                   set cognitive_revision = 2,
                       last_checkpoint_ns = 'intake',
                       last_checkpoint_id = %s
                 where thread_id = %s
                """,
                (missing_command_checkpoint_id, missing_command_thread_id),
            )
        with pytest.raises(CheckViolation, match="cognitive revision cannot jump"):
            await connection.execute(
                """
                update graph_thread_registry
                   set cognitive_revision = 2,
                       last_checkpoint_ns = 'intake',
                       last_checkpoint_id = %s
                 where thread_id = %s
                """,
                (non_active_checkpoint_id, non_active_thread_id),
            )
        with pytest.raises(CheckViolation, match="cognitive revision cannot jump"):
            await connection.execute(
                """
                update graph_thread_registry
                   set cognitive_revision = 3
                 where thread_id = %s
                """,
                (too_far_thread_id,),
            )
        with pytest.raises(CheckViolation, match="cognitive revision cannot jump"):
            await connection.execute(
                """
                update graph_thread_registry
                   set cognitive_revision = 2
                 where thread_id = %s
                """,
                (old_pointer_thread_id,),
            )


@pytest.mark.asyncio
async def test_real_g005_backfills_missing_tenant_turns_in_queue_order(
    graph_database: _Database,
) -> None:
    schema = f"graph_backfill_{uuid4().hex[:12]}"
    async with await AsyncConnection.connect(
        graph_database.admin_dsn,
        autocommit=True,
        prepare_threshold=0,
    ) as admin:
        await admin.execute(
            sql.SQL("create schema {} authorization {}").format(
                sql.Identifier(schema),
                sql.Identifier(OWNER),
            )
        )
    try:
        migrations = load_graph_migrations()
        async with await AsyncConnection.connect(
            graph_database.migration_dsn,
            autocommit=True,
            prepare_threshold=0,
            row_factory=dict_row,
        ) as connection:
            await connection.execute(sql.SQL("set role {}").format(sql.Identifier(OWNER)))
            await connection.execute(
                sql.SQL("set search_path to {}, pg_catalog, pg_temp").format(
                    sql.Identifier(schema)
                )
            )
            await AsyncPostgresSaver(connection).setup()
            async with connection.transaction():
                await connection.execute(
                    sql.SQL("set role {}").format(sql.Identifier(OWNER))
                )
                await connection.execute(
                    sql.SQL("set search_path to {}, pg_catalog, pg_temp").format(
                        sql.Identifier(schema)
                    )
                )
                for migration in migrations[:4]:
                    await connection.execute(migration.sql_text, prepare=False)

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
                    )
                    """,
                    (STATE_SCHEMA_HASH, BINDING_HASH),
                )
                for ordinal, tenant in enumerate(
                    ("tenant-backfill-old", "tenant-backfill-new"),
                    start=201,
                ):
                    thread_id = f"grt.v1.{ordinal:032x}"
                    command_id = f"command-backfill-{ordinal}"
                    request_json = _request_document(f"backfill-{ordinal}")
                    await connection.execute(
                        """
                        insert into graph_thread_registry (
                            thread_id, tenant_surrogate, case_id, room_type, room_epoch,
                            actor_scope_json, actor_scope_hash, agent_session_id,
                            shared_session, graph_key, graph_version,
                            checkpoint_schema_version
                        ) values (
                            %s, %s, %s, 'HEARING', 3,
                            '{"audience":"PUBLIC"}'::jsonb, %s, %s,
                            true, 'hearing_flow', 'hearing_flow.v2',
                            'hearing_checkpoint.v2'
                        )
                        """,
                        (
                            thread_id,
                            tenant,
                            f"case-backfill-{ordinal}",
                            "e" * 64,
                            f"session-backfill-{ordinal}",
                        ),
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
                        )
                        """,
                        (
                            thread_id,
                            command_id,
                            json.dumps(request_json, separators=(",", ":")),
                            request_json["request_hash"],
                        ),
                    )
                    await connection.execute(
                        """
                        insert into agent_graph_lease (
                            thread_id, command_id, owner_id, fencing_token,
                            lease_expires_at
                        ) values (
                            %s, %s, %s, 1,
                            clock_timestamp() + interval '29 seconds'
                        )
                        """,
                        (thread_id, command_id, f"graph-owner-backfill-{ordinal}"),
                    )
                    await connection.execute(
                        """
                        insert into agent_graph_fanout_permit (
                            request_id, tenant_key, room_key, item_key,
                            thread_id, command_id,
                            graph_lease_owner_id, graph_lease_fencing_token,
                            permit_owner_id, wait_deadline_at
                        ) values (
                            %s, %s, %s, %s, %s, %s, %s, 1, %s,
                            clock_timestamp() + interval '20 seconds'
                        )
                        """,
                        (
                            f"permit-backfill-{ordinal}",
                            tenant,
                            f"case-backfill-{ordinal}:HEARING:3",
                            f"item-backfill-{ordinal}",
                            thread_id,
                            command_id,
                            f"graph-owner-backfill-{ordinal}",
                            f"permit-owner-backfill-{ordinal}",
                        ),
                    )

                assert (
                    await (
                        await connection.execute(
                            "select count(*) as count from agent_graph_fanout_tenant_turn"
                        )
                    ).fetchone()
                )["count"] == 0
                await connection.execute(migrations[4].sql_text, prepare=False)
                turns = await (
                    await connection.execute(
                        """
                        select permit.tenant_key, min(permit.queue_sequence) as queue_sequence,
                               tenant_turn.last_granted_sequence as turn_sequence
                          from agent_graph_fanout_permit permit
                          join agent_graph_fanout_tenant_turn tenant_turn
                            on tenant_turn.tenant_key = permit.tenant_key
                         group by permit.tenant_key, tenant_turn.last_granted_sequence
                         order by queue_sequence
                        """
                    )
                ).fetchall()
                assert [row["tenant_key"] for row in turns] == [
                    "tenant-backfill-old",
                    "tenant-backfill-new",
                ]
                assert turns[0]["turn_sequence"] < turns[1]["turn_sequence"]
    finally:
        async with await AsyncConnection.connect(
            graph_database.admin_dsn,
            autocommit=True,
            prepare_threshold=0,
        ) as admin:
            await admin.execute(
                sql.SQL("drop schema {} cascade").format(sql.Identifier(schema))
            )


@pytest.mark.asyncio
async def test_real_durable_fanout_permit_scope_renew_release_and_retry(
    graph_database: _Database,
) -> None:
    await _migration_runner(graph_database).run()
    await _seed_executable_command(graph_database)
    pool = _runtime_pool(graph_database)
    await pool.open(wait=True, timeout=10)
    bulkhead = PostgresGraphFanoutBulkhead(
        pool, PostgresBulkheadConfig.signed_synthetic_defaults()
    )
    await bulkhead.open()
    fence = GraphPermitFenceContext(
        thread_id=THREAD_ID,
        command_id=COMMAND_ID,
        graph_lease_owner_id="worker-1",
        graph_lease_fencing_token=1,
    )
    scope = GraphBulkheadScope.from_graph_identity(
        tenant_surrogate="tenant-integration",
        case_id="case-integration",
        room_type="HEARING",
        room_epoch=3,
        item_key="evidence-1",
    )
    try:
        with pytest.raises(GraphPermitBindingError):
            await bulkhead.acquire(
                GraphBulkheadScope(
                    tenant_key="tenant-forged",
                    room_key=scope.room_key,
                    item_key=scope.item_key,
                ),
                fence,
                request_id="permit-forged",
            )

        first_owner = "permit-generation-1"
        second_owner = "permit-generation-2"
        first = await bulkhead.acquire(
            scope,
            fence,
            request_id="permit-retry",
            owner_id=first_owner,
        )
        first_expiry = first.lease_expires_at
        assert first.permit_fencing_token == 1
        assert await first.renew() >= first_expiry
        assert first._record.revision == 2  # noqa: SLF001 - one-call revision proof
        assert await first.release()
        async with pool.connection(timeout=5) as connection:
            released_row = await (
                await connection.execute(
                    """
                    select status, revision
                      from agent_graph_fanout_permit
                     where request_id = 'permit-retry'
                    """
                )
            ).fetchone()
        assert released_row == {"status": "RELEASED", "revision": 3}

        with pytest.raises(GraphPermitBindingError):
            await bulkhead.takeover(
                scope,
                fence,
                request_id="permit-retry",
                owner_id=first_owner,
            )

        retried = await bulkhead.takeover(
            scope,
            fence,
            request_id="permit-retry",
            owner_id=second_owner,
        )
        assert retried.permit_fencing_token == 2
        await bulkhead._cancel_or_release_best_effort(  # noqa: SLF001 - stale generation proof
            "permit-retry",
            fence,
            first_owner,
        )
        recovered = await bulkhead.validate_recovery(
            "permit-retry",
            2,
            fence,
            owner_id=second_owner,
        )
        assert recovered.status == "GRANTED"
        with pytest.raises(GraphPermitLostError):
            await bulkhead.validate_recovery(
                "permit-retry",
                1,
                fence,
                owner_id=first_owner,
            )
        assert await retried.release()

        with pytest.raises(GraphPermitBindingError):
            await bulkhead.takeover(
                scope,
                fence,
                request_id="permit-retry",
                owner_id=first_owner,
            )
        third = await bulkhead.takeover(
            scope,
            fence,
            request_id="permit-retry",
            owner_id="permit-generation-3",
        )
        assert third.permit_fencing_token == 3
        await bulkhead._cancel_or_release_best_effort(  # noqa: SLF001 - gen-1 delay proof
            "permit-retry",
            fence,
            first_owner,
        )
        third_recovered = await bulkhead.validate_recovery(
            "permit-retry",
            3,
            fence,
            owner_id="permit-generation-3",
        )
        assert third_recovered.status == "GRANTED"
        assert await third.release()
        snapshot = await bulkhead.snapshot()
        assert snapshot.active_global == 0
        assert snapshot.status_counts["RELEASED"] >= 1
    finally:
        await bulkhead.close()
        await pool.close(timeout=10)


@pytest.mark.asyncio
async def test_real_weighted_fanout_group_reserves_capacity_atomically(
    graph_database: _Database,
) -> None:
    await _migration_runner(graph_database).run()
    identities = await _seed_fanout_identities(
        graph_database,
        ("tenant-weighted-group", "tenant-weighted-waiter"),
        start_ordinal=401,
    )
    config = PostgresBulkheadConfig(
        global_limit=3,
        tenant_limit=3,
        room_limit=3,
        global_queue_limit=10,
        tenant_queue_limit=5,
        room_queue_limit=5,
        permit_lease_seconds=20,
        wait_timeout_seconds=10,
        poll_interval_seconds=0.01,
    )
    await _set_fanout_config(graph_database, config)
    pool = _runtime_pool(graph_database)
    await pool.open(wait=True, timeout=10)
    bulkhead = PostgresGraphFanoutBulkhead(pool, config)
    await bulkhead.open()
    group = None
    waiter_task: asyncio.Task[Any] | None = None
    waiter = None
    try:
        group = await bulkhead.acquire(
            identities[0][0],
            identities[0][1],
            request_id="permit-weighted-group",
            owner_id="permit-weighted-group-owner",
            permit_count=3,
        )
        assert group.permit_count == 3
        assert (await bulkhead.snapshot()).active_global == 3

        waiter_task = asyncio.create_task(
            bulkhead.acquire(
                identities[1][0],
                identities[1][1],
                request_id="permit-weighted-waiter",
                owner_id="permit-weighted-waiter-owner",
            )
        )
        for _ in range(300):
            snapshot = await bulkhead.snapshot()
            if snapshot.queued_global == 1:
                break
            await asyncio.sleep(0.01)
        else:
            raise AssertionError("weighted capacity waiter did not queue")

        assert not waiter_task.done()
        async with pool.connection(timeout=5) as connection:
            rows = await (
                await connection.execute(
                    """
                    select request_id, permit_count, status
                      from agent_graph_fanout_permit
                     where request_id in (
                         'permit-weighted-group', 'permit-weighted-waiter'
                     )
                     order by request_id
                    """
                )
            ).fetchall()
        assert rows == [
            {
                "request_id": "permit-weighted-group",
                "permit_count": 3,
                "status": "GRANTED",
            },
            {
                "request_id": "permit-weighted-waiter",
                "permit_count": 1,
                "status": "QUEUED",
            },
        ]

        assert await group.release()
        waiter = await asyncio.wait_for(waiter_task, 3)
        assert waiter.permit_count == 1
        assert (await bulkhead.snapshot()).active_global == 1
        assert await waiter.release()
        assert (await bulkhead.snapshot()).active_global == 0
    finally:
        if waiter_task is not None and not waiter_task.done():
            waiter_task.cancel()
            await asyncio.gather(waiter_task, return_exceptions=True)
        for permit in (group, waiter):
            if permit is not None and not permit.released:
                try:
                    await permit.release()
                except GraphPermitLostError:
                    pass
        await bulkhead.close()
        await pool.close(timeout=10)
        await _set_fanout_config(
            graph_database,
            PostgresBulkheadConfig.signed_synthetic_defaults(),
        )


@pytest.mark.asyncio
async def test_real_task_cancellation_cleans_queued_and_just_granted_permits(
    graph_database: _Database,
) -> None:
    await _migration_runner(graph_database).run()
    identities = await _seed_fanout_identities(
        graph_database,
        ("tenant-cleanup-blocker", "tenant-cleanup-target"),
        start_ordinal=301,
    )
    config = PostgresBulkheadConfig(
        global_limit=1,
        tenant_limit=1,
        room_limit=1,
        global_queue_limit=10,
        tenant_queue_limit=5,
        room_queue_limit=5,
        permit_lease_seconds=20,
        wait_timeout_seconds=10,
        poll_interval_seconds=0.01,
    )
    await _set_fanout_config(graph_database, config)
    pool = _runtime_pool(graph_database)
    await pool.open(wait=True, timeout=10)
    bulkhead = PostgresGraphFanoutBulkhead(pool, config)
    await bulkhead.open()

    class GrantGateBulkhead(PostgresGraphFanoutBulkhead):
        def __init__(self) -> None:
            super().__init__(pool, config)
            self.database_granted = asyncio.Event()

        async def _acquire_once(self, *args: Any, **kwargs: Any) -> Any:
            record = await super()._acquire_once(*args, **kwargs)
            if kwargs["request_id"] == "permit-cleanup-granted" and record.status == "GRANTED":
                self.database_granted.set()
                await asyncio.Event().wait()
            return record

    gated = GrantGateBulkhead()
    await gated.open()
    blocker = None
    queued_task: asyncio.Task[Any] | None = None
    granted_task: asyncio.Task[Any] | None = None
    try:
        blocker = await bulkhead.acquire(
            identities[0][0],
            identities[0][1],
            request_id="permit-cleanup-blocker",
            owner_id="permit-cleanup-blocker-owner",
        )
        queued_task = asyncio.create_task(
            bulkhead.acquire(
                identities[1][0],
                identities[1][1],
                request_id="permit-cleanup-queued",
                owner_id="permit-cleanup-queued-owner",
            )
        )
        for _ in range(300):
            if (await bulkhead.snapshot()).queued_global == 1:
                break
            await asyncio.sleep(0.01)
        else:
            raise AssertionError("queued cancellation fixture did not queue")
        queued_task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await queued_task

        assert await blocker.release()
        granted_scope = GraphBulkheadScope(
            identities[1][0].tenant_key,
            identities[1][0].room_key,
            "item-cleanup-granted",
        )
        granted_task = asyncio.create_task(
            gated.acquire(
                granted_scope,
                identities[1][1],
                request_id="permit-cleanup-granted",
                owner_id="permit-cleanup-granted-owner",
            )
        )
        await asyncio.wait_for(gated.database_granted.wait(), 3)
        granted_task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await granted_task

        async with pool.connection(timeout=5) as connection:
            rows = await (
                await connection.execute(
                    """
                    select request_id, status, revision
                      from agent_graph_fanout_permit
                     where request_id in ('permit-cleanup-queued', 'permit-cleanup-granted')
                     order by request_id
                    """
                )
            ).fetchall()
        assert rows == [
            {
                "request_id": "permit-cleanup-granted",
                "status": "RELEASED",
                "revision": 2,
            },
            {
                "request_id": "permit-cleanup-queued",
                "status": "CANCELLED",
                "revision": 1,
            },
        ]
        snapshot = await bulkhead.snapshot()
        assert snapshot.active_global == snapshot.queued_global == 0
    finally:
        for task in (queued_task, granted_task):
            if task is not None and not task.done():
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)
        if blocker is not None and not blocker.released:
            await blocker.release()
        await gated.close()
        await bulkhead.close()
        await pool.close(timeout=10)
        await _set_fanout_config(
            graph_database,
            PostgresBulkheadConfig.signed_synthetic_defaults(),
        )


@pytest.mark.asyncio
async def test_real_fanout_fairness_resists_continuous_new_tenants(
    graph_database: _Database,
) -> None:
    await _migration_runner(graph_database).run()
    identities = await _seed_fanout_identities(
        graph_database,
        (
            "tenant-fair-a",
            "tenant-fair-blocker",
            "tenant-fair-b",
            "tenant-fair-c",
            "tenant-fair-d",
        ),
    )
    config = PostgresBulkheadConfig(
        global_limit=1,
        tenant_limit=1,
        room_limit=1,
        global_queue_limit=20,
        tenant_queue_limit=10,
        room_queue_limit=5,
        permit_lease_seconds=20,
        wait_timeout_seconds=10,
        poll_interval_seconds=0.01,
    )
    await _set_fanout_config(graph_database, config)
    pool = create_graph_pool(
        graph_database.runtime_dsn,
        GraphPoolConfig(
            schema=SCHEMA,
            min_size=1,
            max_size=8,
            max_waiting=8,
            acquire_timeout_seconds=5,
            connect_timeout_seconds=5,
        ),
    )
    await pool.open(wait=True, timeout=10)
    bulkhead = PostgresGraphFanoutBulkhead(pool, config)
    await bulkhead.open()
    tasks: list[asyncio.Task[Any]] = []
    permits: list[Any] = []

    def item_scope(index: int, item_key: str) -> GraphBulkheadScope:
        scope, _ = identities[index]
        return GraphBulkheadScope(scope.tenant_key, scope.room_key, item_key)

    async def wait_for_queue(expected: int) -> None:
        for _ in range(300):
            if (await bulkhead.snapshot()).queued_global == expected:
                return
            await asyncio.sleep(0.01)
        raise AssertionError(f"durable queue did not reach {expected}")

    try:
        scope_a, fence_a = identities[0]
        warm = await bulkhead.acquire(
            GraphBulkheadScope(scope_a.tenant_key, scope_a.room_key, "item-warm"),
            fence_a,
            request_id="permit-fair-warm",
            owner_id="permit-fair-warm-owner",
        )
        permits.append(warm)
        assert await warm.release()

        blocker_scope, blocker_fence = identities[1]
        blocker = await bulkhead.acquire(
            blocker_scope,
            blocker_fence,
            request_id="permit-fair-blocker",
            owner_id="permit-fair-blocker-owner",
        )
        permits.append(blocker)

        a1 = asyncio.create_task(
            bulkhead.acquire(
                item_scope(0, "item-a1"),
                fence_a,
                request_id="permit-fair-a1",
                owner_id="permit-fair-a1-owner",
            )
        )
        tasks.append(a1)
        await wait_for_queue(1)
        a2 = asyncio.create_task(
            bulkhead.acquire(
                item_scope(0, "item-a2"),
                fence_a,
                request_id="permit-fair-a2",
                owner_id="permit-fair-a2-owner",
            )
        )
        tasks.append(a2)
        await wait_for_queue(2)
        b = asyncio.create_task(
            bulkhead.acquire(
                identities[2][0],
                identities[2][1],
                request_id="permit-fair-b",
                owner_id="permit-fair-b-owner",
            )
        )
        tasks.append(b)
        await wait_for_queue(3)
        c = asyncio.create_task(
            bulkhead.acquire(
                identities[3][0],
                identities[3][1],
                request_id="permit-fair-c",
                owner_id="permit-fair-c-owner",
            )
        )
        tasks.append(c)
        await wait_for_queue(4)

        assert await blocker.release()
        permit_a1 = await asyncio.wait_for(a1, 3)
        permits.append(permit_a1)
        assert not b.done() and not c.done() and not a2.done()
        assert await permit_a1.release()

        permit_b = await asyncio.wait_for(b, 3)
        permits.append(permit_b)
        assert not c.done() and not a2.done()
        assert await permit_b.release()

        permit_c = await asyncio.wait_for(c, 3)
        permits.append(permit_c)
        d = asyncio.create_task(
            bulkhead.acquire(
                identities[4][0],
                identities[4][1],
                request_id="permit-fair-d",
                owner_id="permit-fair-d-owner",
            )
        )
        tasks.append(d)
        await wait_for_queue(2)
        assert await permit_c.release()

        permit_a2 = await asyncio.wait_for(a2, 3)
        permits.append(permit_a2)
        assert not d.done()
        assert await permit_a2.release()

        permit_d = await asyncio.wait_for(d, 3)
        permits.append(permit_d)
        assert await permit_d.release()
    finally:
        for task in tasks:
            if not task.done():
                task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        for permit in permits:
            if not permit.released:
                try:
                    await permit.release()
                except GraphPermitLostError:
                    pass
        await bulkhead.close()
        await pool.close(timeout=10)
        await _set_fanout_config(
            graph_database,
            PostgresBulkheadConfig.signed_synthetic_defaults(),
        )


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
    assert recovered.already_current == (
        "G001",
        "G002",
        "G003",
        "G004",
        "G005",
        "G006",
        "G007",
        "G008",
        "G009",
    )


@pytest.mark.asyncio
async def test_real_fenced_saver_rejects_stale_writer_and_restores_after_pool_restart(
    graph_database: _Database,
) -> None:
    await _migration_runner(graph_database).run()
    await _seed_executable_command(graph_database, include_expired_nonce=True)

    pool = _runtime_pool(graph_database)
    await pool.open(wait=True, timeout=10)
    first_fence = _fence(owner_id="worker-1", fencing_token=1)
    first_config = bind_fence_context(
        {"configurable": {"thread_id": THREAD_ID, "checkpoint_ns": "hearing"}},
        first_fence,
    )
    saver = FencedPostgresSaver(
        pool,
        statement_timeout_ms=STATEMENT_TIMEOUT_MS,
        acquire_timeout_seconds=5,
    )
    try:
        first_checkpoint, first_versions = _revision_checkpoint(1)
        first_saved = await saver.aput(
            first_config,
            first_checkpoint,
            {},
            first_versions,
        )
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
            stale_checkpoint, stale_versions = _revision_checkpoint(2)
            await saver.aput(
                first_config,
                stale_checkpoint,
                {},
                stale_versions,
            )

        second_fence = _fence(owner_id="worker-2", fencing_token=2)
        second_config = bind_fence_context(
            {"configurable": {"thread_id": THREAD_ID, "checkpoint_ns": "hearing"}},
            second_fence,
        )
        second_checkpoint, second_versions = _revision_checkpoint(2)
        second_saved = await saver.aput(
            second_config,
            second_checkpoint,
            {},
            second_versions,
        )
        second_checkpoint_id = second_saved["configurable"]["checkpoint_id"]

        terminal_checkpoint = empty_checkpoint()
        terminal_checkpoint["channel_values"] = {
            "cognitive_revision": 3,
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
        terminal_checkpoint["channel_versions"] = {
            "cognitive_revision": "v-revision-3",
            "result_json": "v-result-1",
        }
        terminal_config = bind_terminal_result_context(
            second_config,
            _terminal_materializer(),
        )
        terminal_saved = await saver.aput(
            terminal_config,
            terminal_checkpoint,
            {},
            {
                "cognitive_revision": "v-revision-3",
                "result_json": "v-result-1",
            },
        )
        terminal_checkpoint_id = terminal_saved["configurable"]["checkpoint_id"]
    finally:
        await pool.close(timeout=10)

    assert second_checkpoint_id != first_checkpoint_id
    assert terminal_checkpoint_id != second_checkpoint_id

    replacement_pool = _runtime_pool(graph_database)
    await replacement_pool.open(wait=True, timeout=10)
    try:
        replacement = FencedPostgresSaver(
            replacement_pool,
            statement_timeout_ms=STATEMENT_TIMEOUT_MS,
            acquire_timeout_seconds=5,
        )
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
                retained_keys = await ledger.referenced_verification_key_ids(connection)
                thread_checkpoint = await (
                    await connection.execute(
                        """
                        select cognitive_revision, last_checkpoint_ns, last_checkpoint_id
                          from graph_thread_registry
                         where thread_id = %s
                        """,
                        (THREAD_ID,),
                    )
                ).fetchone()
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
    assert retained_keys == frozenset({"key-integration-old"})
    assert thread_checkpoint == {
        "cognitive_revision": 3,
        "last_checkpoint_ns": "hearing",
        "last_checkpoint_id": terminal_checkpoint_id,
    }


@pytest.mark.asyncio
@pytest.mark.parametrize("operation", ("checkpoint", "pending_writes"))
async def test_real_bulk_write_does_not_block_renewal_before_final_fence_refresh(
    graph_database: _Database,
    monkeypatch: pytest.MonkeyPatch,
    operation: str,
) -> None:
    await _migration_runner(graph_database).run()
    await _seed_executable_command(graph_database)
    pool = _runtime_pool(graph_database)
    await pool.open(wait=True, timeout=10)
    saver = FencedPostgresSaver(
        pool,
        statement_timeout_ms=STATEMENT_TIMEOUT_MS,
        acquire_timeout_seconds=5,
    )
    fence = _fence(owner_id="worker-1", fencing_token=1)
    config = bind_fence_context(
        {"configurable": {"thread_id": THREAD_ID, "checkpoint_ns": "hearing"}},
        fence,
    )
    if operation == "pending_writes":
        parent, parent_versions = _revision_checkpoint(1)
        config = await saver.aput(
            config,
            parent,  # type: ignore[arg-type]
            {},
            parent_versions,
        )

    async with pool.connection(timeout=5) as connection:
        initial_lease = await (
            await connection.execute(
                """
                update agent_graph_lease
                   set renewed_at = clock_timestamp(),
                       lease_expires_at = clock_timestamp() + interval '2 seconds'
                 where thread_id = %s and command_id = %s
             returning lease_revision, lease_expires_at
                """,
                (THREAD_ID, COMMAND_ID),
            )
        ).fetchone()
        command_deadline_row = await (
            await connection.execute(
                """
                select deadline_at from agent_graph_command
                 where thread_id = %s and command_id = %s
                """,
                (THREAD_ID, COMMAND_ID),
            )
        ).fetchone()
    assert initial_lease is not None
    assert command_deadline_row is not None
    command_deadline_at = command_deadline_row["deadline_at"]
    assert isinstance(command_deadline_at, datetime)

    bulk_written = asyncio.Event()
    release_bulk = asyncio.Event()
    if operation == "checkpoint":
        original_write = saver._write_prepared_checkpoint  # noqa: SLF001

        async def hold_after_checkpoint_write(connection: Any, prepared: Any) -> None:
            await original_write(connection, prepared)
            bulk_written.set()
            await release_bulk.wait()

        monkeypatch.setattr(
            saver,
            "_write_prepared_checkpoint",
            hold_after_checkpoint_write,
        )
        checkpoint, versions = _revision_checkpoint(2)
        checkpoint["channel_values"].update(
            {"blob-a": {"value": "a"}, "blob-b": {"value": "b"}}
        )
        checkpoint["channel_versions"].update({"blob-a": "v-a", "blob-b": "v-b"})
        versions.update({"blob-a": "v-a", "blob-b": "v-b"})
        write_task = asyncio.create_task(
            saver.aput(config, checkpoint, {}, versions)  # type: ignore[arg-type]
        )
    else:
        original_write = saver._write_prepared_pending_writes  # noqa: SLF001

        async def hold_after_pending_write(connection: Any, prepared: Any) -> None:
            await original_write(connection, prepared)
            bulk_written.set()
            await release_bulk.wait()

        monkeypatch.setattr(
            saver,
            "_write_prepared_pending_writes",
            hold_after_pending_write,
        )
        write_task = asyncio.create_task(
            saver.aput_writes(
                config,
                (("custom-channel", {"value": "pending"}),),
                "task-bulk-renew",
            )
        )

    async def renew_exact_lease() -> Any:
        async with pool.connection(timeout=5) as connection:
            async with connection.transaction():
                return await PostgresLeaseRepository().renew(
                    connection,
                    thread_id=THREAD_ID,
                    command_id=COMMAND_ID,
                    owner_id="worker-1",
                    fencing_token=1,
                    command_deadline_at=command_deadline_at,
                )

    renew_task: asyncio.Task[Any] | None = None
    try:
        await asyncio.wait_for(bulk_written.wait(), timeout=2)
        renew_task = asyncio.create_task(renew_exact_lease())
        renewed = await asyncio.wait_for(asyncio.shield(renew_task), timeout=1)
        assert write_task.done() is False
        assert renewed.fencing_token == 1
        assert renewed.owner_id == "worker-1"

        await asyncio.sleep(2.05)
        async with pool.connection(timeout=5) as connection:
            horizon = await (
                await connection.execute(
                    """
                    select clock_timestamp() > %s as crossed_initial_horizon,
                           lease_expires_at > clock_timestamp() as active
                      from agent_graph_lease where thread_id = %s
                    """,
                    (initial_lease["lease_expires_at"], THREAD_ID),
                )
            ).fetchone()
        assert horizon == {"crossed_initial_horizon": True, "active": True}
        assert write_task.done() is False

        release_bulk.set()
        await asyncio.wait_for(write_task, timeout=2)
        async with pool.connection(timeout=5) as connection:
            lease_row = await (
                await connection.execute(
                    """
                    select owner_id, fencing_token, released_at, cancelled_at,
                           lease_revision, lease_expires_at > clock_timestamp() as active
                      from agent_graph_lease where thread_id = %s
                    """,
                    (THREAD_ID,),
                )
            ).fetchone()
            authority = await (
                await connection.execute(
                    """
                    select
                      (select status from agent_graph_command
                        where thread_id = %s and command_id = %s) as command_status,
                      (select count(*) from agent_graph_command_attempt
                        where thread_id = %s and command_id = %s) as attempt_count,
                      (select coalesce(sum(provider_call_count), 0)
                         from agent_graph_command_attempt
                        where thread_id = %s and command_id = %s) as provider_calls
                    """,
                    (
                        THREAD_ID,
                        COMMAND_ID,
                        THREAD_ID,
                        COMMAND_ID,
                        THREAD_ID,
                        COMMAND_ID,
                    ),
                )
            ).fetchone()
            clean = await (await connection.execute("select 1 as clean")).fetchone()
            assert clean == {"clean": 1}
        assert lease_row is not None
        assert lease_row["owner_id"] == "worker-1"
        assert lease_row["fencing_token"] == 1
        assert lease_row["released_at"] is None
        assert lease_row["cancelled_at"] is None
        assert lease_row["lease_revision"] >= initial_lease["lease_revision"] + 2
        assert lease_row["active"] is True
        assert authority == {
            "command_status": "EXECUTING",
            "attempt_count": 0,
            "provider_calls": 0,
        }
    finally:
        release_bulk.set()
        if not write_task.done():
            write_task.cancel()
        await asyncio.gather(write_task, return_exceptions=True)
        if renew_task is not None and not renew_task.done():
            renew_task.cancel()
            await asyncio.gather(renew_task, return_exceptions=True)
        await pool.close(timeout=10)


@pytest.mark.asyncio
async def test_real_post_lease_checkpoint_suffix_releases_heartbeat_within_safety_horizon(
    graph_database: _Database,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    await _migration_runner(graph_database).run()
    await _seed_executable_command(graph_database)
    saver_pool = _runtime_pool(graph_database)
    control_pool = _runtime_pool(graph_database)
    await saver_pool.open(wait=True, timeout=10)
    await control_pool.open(wait=True, timeout=10)
    monkeypatch.setattr(
        checkpoint_module,
        "FENCED_LEASE_SUFFIX_BODY_TIMEOUT_SECONDS",
        0.10,
        raising=False,
    )
    saver = FencedPostgresSaver(
        saver_pool,
        statement_timeout_ms=STATEMENT_TIMEOUT_MS,
        acquire_timeout_seconds=5,
    )
    fence = _fence(owner_id="worker-1", fencing_token=1)
    config = bind_fence_context(
        {"configurable": {"thread_id": THREAD_ID, "checkpoint_ns": "hearing"}},
        fence,
    )
    checkpoint, versions = _revision_checkpoint(1)
    checkpoint["channel_values"].update(
        {"suffix-blob-a": {"value": "a"}, "suffix-blob-b": {"value": "b"}}
    )
    checkpoint["channel_versions"].update(
        {"suffix-blob-a": "v-a", "suffix-blob-b": "v-b"}
    )
    versions.update({"suffix-blob-a": "v-a", "suffix-blob-b": "v-b"})
    checkpoint_id = checkpoint["id"]

    lease_repository = PostgresLeaseRepository()
    async with control_pool.connection(timeout=5) as connection:
        initial_lease = await lease_repository.observe(connection, thread_id=THREAD_ID)
        command_deadline_row = await (
            await connection.execute(
                """
                select deadline_at from agent_graph_command
                 where thread_id = %s and command_id = %s
                """,
                (THREAD_ID, COMMAND_ID),
            )
        ).fetchone()
        baseline = await (
            await connection.execute(
                """
                select
                  (select count(*) from checkpoints
                    where thread_id = %s and checkpoint_ns = 'hearing'
                      and checkpoint_id = %s) as checkpoint_count,
                  (select count(*) from checkpoint_blobs
                    where thread_id = %s and checkpoint_ns = 'hearing') as blob_count,
                  (select count(*) from checkpoint_writes
                    where thread_id = %s and checkpoint_ns = 'hearing') as pending_count,
                  (select committed_checkpoint_id from agent_graph_command
                    where thread_id = %s and command_id = %s) as command_checkpoint_id,
                  (select last_checkpoint_id from graph_thread_registry
                    where thread_id = %s) as thread_checkpoint_id,
                  (select count(*) from agent_graph_command_attempt
                    where thread_id = %s and command_id = %s) as attempt_count,
                  (select coalesce(sum(provider_call_count), 0)
                     from agent_graph_command_attempt
                    where thread_id = %s and command_id = %s) as provider_calls
                """,
                (
                    THREAD_ID,
                    checkpoint_id,
                    THREAD_ID,
                    THREAD_ID,
                    THREAD_ID,
                    COMMAND_ID,
                    THREAD_ID,
                    THREAD_ID,
                    COMMAND_ID,
                    THREAD_ID,
                    COMMAND_ID,
                ),
            )
        ).fetchone()
    assert initial_lease is not None
    assert command_deadline_row is not None
    assert baseline is not None
    command_deadline_at = command_deadline_row["deadline_at"]
    assert isinstance(command_deadline_at, datetime)

    refresh_entered = asyncio.Event()
    release_refresh = asyncio.Event()
    renew_entered = asyncio.Event()
    original_refresh = saver._refresh_locked_fence_lease  # noqa: SLF001
    original_renew = lease_repository.renew

    async def hold_before_refresh(connection: Any, current_fence: Any) -> None:
        refresh_entered.set()
        await release_refresh.wait()
        await original_refresh(connection, current_fence)

    async def observe_real_renew(connection: Any, **kwargs: Any) -> Any:
        renew_entered.set()
        return await original_renew(connection, **kwargs)

    monkeypatch.setattr(saver, "_refresh_locked_fence_lease", hold_before_refresh)
    monkeypatch.setattr(lease_repository, "renew", observe_real_renew)
    gateway = GraphCommandGateway(
        mode=GraphGatewayMode.SHADOW,
        pool=control_pool,
        leases=lease_repository,
        input_authorizer=object(),  # type: ignore[arg-type]
        acquire_timeout_seconds=2,
    )
    execution = GatewayExecution(  # type: ignore[arg-type]
        admission=SimpleNamespace(
            command=SimpleNamespace(deadline_at=command_deadline_at)
        ),
        attempt=SimpleNamespace(),
        lease=initial_lease,
        fence=fence,
    )
    write_task = asyncio.create_task(
        saver.aput(config, checkpoint, {}, versions)  # type: ignore[arg-type]
    )
    renew_task: asyncio.Task[Any] | None = None
    suffix_horizon_seconds = 0.25
    suffix_released_by_horizon = False
    heartbeat_released_by_horizon = False
    try:
        await asyncio.wait_for(refresh_entered.wait(), timeout=2)
        renew_task = asyncio.create_task(gateway.renew_execution(execution))
        await asyncio.wait_for(renew_entered.wait(), timeout=2)
        with pytest.raises(TimeoutError):
            await asyncio.wait_for(asyncio.shield(renew_task), timeout=0.05)

        observation_deadline = (
            asyncio.get_running_loop().time() + suffix_horizon_seconds
        )
        try:
            await asyncio.wait_for(
                asyncio.shield(write_task),
                timeout=suffix_horizon_seconds,
            )
        except TimeoutError:
            pass
        except GraphFenceError:
            pass
        suffix_released_by_horizon = write_task.done()
        remaining_observation = max(
            0.0,
            observation_deadline - asyncio.get_running_loop().time(),
        )
        if suffix_released_by_horizon and not renew_task.done():
            try:
                await asyncio.wait_for(
                    asyncio.shield(renew_task),
                    timeout=remaining_observation,
                )
            except TimeoutError:
                pass
        heartbeat_released_by_horizon = renew_task.done()

        if not write_task.done():
            write_task.cancel()
        write_result = await asyncio.gather(write_task, return_exceptions=True)
        assert len(write_result) == 1
        if suffix_released_by_horizon:
            assert isinstance(write_result[0], GraphFenceError)
        else:
            assert isinstance(write_result[0], asyncio.CancelledError)
        release_refresh.set()

        renewed = await asyncio.wait_for(renew_task, timeout=2)
        assert renewed.thread_id == THREAD_ID
        assert renewed.command_id == COMMAND_ID
        assert renewed.owner_id == "worker-1"
        assert renewed.fencing_token == 1
        assert renewed.revision == initial_lease.revision + 1

        async with control_pool.connection(timeout=5) as connection:
            durable = await (
                await connection.execute(
                    """
                    select
                      (select count(*) from checkpoints
                        where thread_id = %s and checkpoint_ns = 'hearing'
                          and checkpoint_id = %s) as checkpoint_count,
                      (select count(*) from checkpoint_blobs
                        where thread_id = %s and checkpoint_ns = 'hearing') as blob_count,
                      (select count(*) from checkpoint_writes
                        where thread_id = %s and checkpoint_ns = 'hearing') as pending_count,
                      (select committed_checkpoint_id from agent_graph_command
                        where thread_id = %s and command_id = %s) as command_checkpoint_id,
                      (select last_checkpoint_id from graph_thread_registry
                        where thread_id = %s) as thread_checkpoint_id,
                      (select count(*) from agent_graph_command_attempt
                        where thread_id = %s and command_id = %s) as attempt_count,
                      (select coalesce(sum(provider_call_count), 0)
                         from agent_graph_command_attempt
                        where thread_id = %s and command_id = %s) as provider_calls,
                      (select lease_revision from agent_graph_lease
                        where thread_id = %s and command_id = %s) as lease_revision,
                      (select lease_expires_at > clock_timestamp()
                         and released_at is null and cancelled_at is null
                         from agent_graph_lease
                        where thread_id = %s and command_id = %s) as lease_active
                    """,
                    (
                        THREAD_ID,
                        checkpoint_id,
                        THREAD_ID,
                        THREAD_ID,
                        THREAD_ID,
                        COMMAND_ID,
                        THREAD_ID,
                        THREAD_ID,
                        COMMAND_ID,
                        THREAD_ID,
                        COMMAND_ID,
                        THREAD_ID,
                        COMMAND_ID,
                        THREAD_ID,
                        COMMAND_ID,
                    ),
                )
            ).fetchone()
            control_clean = await (
                await connection.execute("select 1 as clean")
            ).fetchone()
        async with saver_pool.connection(timeout=5) as connection:
            saver_clean = await (
                await connection.execute("select 1 as clean")
            ).fetchone()

        assert durable == {
            "checkpoint_count": baseline["checkpoint_count"],
            "blob_count": baseline["blob_count"],
            "pending_count": baseline["pending_count"],
            "command_checkpoint_id": baseline["command_checkpoint_id"],
            "thread_checkpoint_id": baseline["thread_checkpoint_id"],
            "attempt_count": baseline["attempt_count"],
            "provider_calls": baseline["provider_calls"],
            "lease_revision": renewed.revision,
            "lease_active": True,
        }
        assert control_clean == {"clean": 1}
        assert saver_clean == {"clean": 1}
        assert (
            suffix_released_by_horizon,
            heartbeat_released_by_horizon,
        ) == (True, True), (
            "post-lease checkpoint suffix exceeded its safety horizon "
            "while the exact gateway heartbeat remained blocked"
        )
    finally:
        release_refresh.set()
        if not write_task.done():
            write_task.cancel()
        await asyncio.gather(write_task, return_exceptions=True)
        if renew_task is not None and not renew_task.done():
            renew_task.cancel()
            await asyncio.gather(renew_task, return_exceptions=True)
        gateway.cleanup_execution_lease(execution)
        await control_pool.close(timeout=10)
        await saver_pool.close(timeout=10)


@pytest.mark.asyncio
async def test_real_released_final_fence_rolls_back_uncommitted_bulk_checkpoint(
    graph_database: _Database,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    await _migration_runner(graph_database).run()
    await _seed_executable_command(graph_database)
    pool = _runtime_pool(graph_database)
    await pool.open(wait=True, timeout=10)
    saver = FencedPostgresSaver(
        pool,
        statement_timeout_ms=STATEMENT_TIMEOUT_MS,
        acquire_timeout_seconds=5,
    )
    fence = _fence(owner_id="worker-1", fencing_token=1)
    config = bind_fence_context(
        {"configurable": {"thread_id": THREAD_ID, "checkpoint_ns": "hearing"}},
        fence,
    )
    checkpoint, versions = _revision_checkpoint(1)
    checkpoint_id = checkpoint["id"]
    async with pool.connection(timeout=5) as connection:
        baseline = await (
            await connection.execute(
                """
                select
                  (select count(*) from checkpoint_blobs
                    where thread_id = %s and checkpoint_ns = 'hearing') as blob_count,
                  (select committed_checkpoint_id from agent_graph_command
                    where thread_id = %s and command_id = %s) as command_checkpoint_id,
                  (select last_checkpoint_id from graph_thread_registry
                    where thread_id = %s) as thread_checkpoint_id
                """,
                (THREAD_ID, THREAD_ID, COMMAND_ID, THREAD_ID),
            )
        ).fetchone()
    assert baseline is not None
    bulk_written = asyncio.Event()
    release_bulk = asyncio.Event()
    original_write = saver._write_prepared_checkpoint  # noqa: SLF001

    async def hold_after_checkpoint_write(connection: Any, prepared: Any) -> None:
        await original_write(connection, prepared)
        bulk_written.set()
        await release_bulk.wait()

    monkeypatch.setattr(
        saver,
        "_write_prepared_checkpoint",
        hold_after_checkpoint_write,
    )
    write_task = asyncio.create_task(
        saver.aput(config, checkpoint, {}, versions)  # type: ignore[arg-type]
    )

    async def release_exact_lease() -> Any:
        async with pool.connection(timeout=5) as connection:
            async with connection.transaction():
                return await PostgresLeaseRepository().release(
                    connection,
                    thread_id=THREAD_ID,
                    command_id=COMMAND_ID,
                    owner_id="worker-1",
                    fencing_token=1,
                )

    release_task: asyncio.Task[Any] | None = None
    try:
        await asyncio.wait_for(bulk_written.wait(), timeout=2)
        release_task = asyncio.create_task(release_exact_lease())
        released = await asyncio.wait_for(asyncio.shield(release_task), timeout=1)
        assert released.released_at is not None
        assert write_task.done() is False

        release_bulk.set()
        with pytest.raises(
            GraphFenceError,
            match="Graph lease is stale, expired, released, or cancelled",
        ):
            await asyncio.wait_for(write_task, timeout=2)

        async with pool.connection(timeout=5) as connection:
            durable = await (
                await connection.execute(
                    """
                    select
                      (select count(*) from checkpoints
                        where thread_id = %s and checkpoint_ns = 'hearing'
                          and checkpoint_id = %s) as checkpoint_count,
                      (select count(*) from checkpoint_blobs
                        where thread_id = %s and checkpoint_ns = 'hearing') as blob_count,
                      (select committed_checkpoint_id from agent_graph_command
                        where thread_id = %s and command_id = %s) as command_checkpoint_id,
                      (select last_checkpoint_id from graph_thread_registry
                        where thread_id = %s) as thread_checkpoint_id,
                      (select released_at is not null from agent_graph_lease
                        where thread_id = %s) as lease_released
                    """,
                    (
                        THREAD_ID,
                        checkpoint_id,
                        THREAD_ID,
                        THREAD_ID,
                        COMMAND_ID,
                        THREAD_ID,
                        THREAD_ID,
                    ),
                )
            ).fetchone()
        assert durable == {
            "checkpoint_count": 0,
            "blob_count": baseline["blob_count"],
            "command_checkpoint_id": baseline["command_checkpoint_id"],
            "thread_checkpoint_id": baseline["thread_checkpoint_id"],
            "lease_released": True,
        }
    finally:
        release_bulk.set()
        if not write_task.done():
            write_task.cancel()
        await asyncio.gather(write_task, return_exceptions=True)
        if release_task is not None and not release_task.done():
            release_task.cancel()
            await asyncio.gather(release_task, return_exceptions=True)
        await pool.close(timeout=10)


@pytest.mark.asyncio
@pytest.mark.parametrize("pending_binding", ("exact", "stale"))
async def test_real_cross_replica_checkpoint_then_pending_validation_avoids_lease_cycle(
    graph_database: _Database,
    monkeypatch: pytest.MonkeyPatch,
    pending_binding: str,
) -> None:
    await _migration_runner(graph_database).run()
    await _seed_executable_command(graph_database)
    pool = _runtime_pool(graph_database)
    control_pool = _runtime_pool(graph_database)
    await pool.open(wait=True, timeout=10)
    await control_pool.open(wait=True, timeout=10)
    seed_saver = FencedPostgresSaver(
        pool,
        statement_timeout_ms=STATEMENT_TIMEOUT_MS,
        acquire_timeout_seconds=5,
    )
    checkpoint_saver = FencedPostgresSaver(
        pool,
        statement_timeout_ms=STATEMENT_TIMEOUT_MS,
        acquire_timeout_seconds=5,
    )
    pending_saver = FencedPostgresSaver(
        pool,
        statement_timeout_ms=STATEMENT_TIMEOUT_MS,
        acquire_timeout_seconds=5,
    )
    fence = _fence(owner_id="worker-1", fencing_token=1)
    base_config = bind_fence_context(
        {"configurable": {"thread_id": THREAD_ID, "checkpoint_ns": "hearing"}},
        fence,
    )
    checkpoint, versions = _revision_checkpoint(1)
    checkpoint_id = checkpoint["id"]
    checkpoint_config = await seed_saver.aput(
        base_config,
        checkpoint,  # type: ignore[arg-type]
        {},
        versions,
    )

    updated_checkpoint, updated_versions = _revision_checkpoint(2)
    updated_checkpoint["id"] = checkpoint_id
    pending_fence = (
        fence if pending_binding == "exact" else replace(fence, graph_key="outcome_flow")
    )
    pending_config = bind_fence_context(
        {
            "configurable": {
                "thread_id": THREAD_ID,
                "checkpoint_ns": "hearing",
                "checkpoint_id": checkpoint_id,
            }
        },
        pending_fence,
    )
    checkpoint_written = asyncio.Event()
    release_checkpoint = asyncio.Event()
    pending_written = asyncio.Event()
    validation_started = asyncio.Event()
    original_checkpoint_write = checkpoint_saver._write_prepared_checkpoint  # noqa: SLF001
    original_pending_write = pending_saver._write_prepared_pending_writes  # noqa: SLF001
    original_validate = pending_saver._validate_pending_write_target  # noqa: SLF001

    async def hold_after_checkpoint_write(connection: Any, prepared: Any) -> None:
        await original_checkpoint_write(connection, prepared)
        checkpoint_written.set()
        await release_checkpoint.wait()

    async def observe_pending_write(connection: Any, prepared: Any) -> None:
        await original_pending_write(connection, prepared)
        pending_written.set()

    async def observe_checkpoint_validation(
        connection: Any,
        config: Any,
        selected_fence: GraphFenceContext,
    ) -> None:
        validation_started.set()
        await original_validate(connection, config, selected_fence)

    monkeypatch.setattr(
        checkpoint_saver,
        "_write_prepared_checkpoint",
        hold_after_checkpoint_write,
    )
    monkeypatch.setattr(
        pending_saver,
        "_write_prepared_pending_writes",
        observe_pending_write,
    )
    monkeypatch.setattr(
        pending_saver,
        "_validate_pending_write_target",
        observe_checkpoint_validation,
    )
    checkpoint_task = asyncio.create_task(
        checkpoint_saver.aput(
            checkpoint_config,
            updated_checkpoint,  # type: ignore[arg-type]
            {},
            updated_versions,
        )
    )
    pending_task: asyncio.Task[Any] | None = None
    try:
        await asyncio.wait_for(checkpoint_written.wait(), timeout=2)
        pending_task = asyncio.create_task(
            pending_saver.aput_writes(
                pending_config,
                (("custom-channel", {"value": "pending"}),),
                "task-cross-replica",
            )
        )
        await asyncio.wait_for(pending_written.wait(), timeout=2)
        await asyncio.wait_for(validation_started.wait(), timeout=2)
        assert pending_task.done() is False

        async with control_pool.connection(timeout=5) as connection:
            async with connection.transaction():
                command_deadline_row = await (
                    await connection.execute(
                        """
                        select deadline_at from agent_graph_command
                         where thread_id = %s and command_id = %s
                        """,
                        (THREAD_ID, COMMAND_ID),
                    )
                ).fetchone()
                assert command_deadline_row is not None
                command_deadline_at = command_deadline_row["deadline_at"]
                assert isinstance(command_deadline_at, datetime)
                renewed = await asyncio.wait_for(
                    PostgresLeaseRepository().renew(
                        connection,
                        thread_id=THREAD_ID,
                        command_id=COMMAND_ID,
                        owner_id="worker-1",
                        fencing_token=1,
                        command_deadline_at=command_deadline_at,
                    ),
                    timeout=1,
                )
        assert renewed.owner_id == "worker-1"
        assert renewed.fencing_token == 1
        assert checkpoint_task.done() is False
        assert pending_task.done() is False

        release_checkpoint.set()
        await asyncio.wait_for(checkpoint_task, timeout=2)
        if pending_binding == "exact":
            await asyncio.wait_for(pending_task, timeout=2)
        else:
            with pytest.raises(GraphBindingError, match="graph_key"):
                await asyncio.wait_for(pending_task, timeout=2)

        async with pool.connection(timeout=5) as connection:
            durable = await (
                await connection.execute(
                    """
                    select
                      (select count(*) from checkpoints
                        where thread_id = %s and checkpoint_ns = 'hearing'
                          and checkpoint_id = %s) as checkpoint_count,
                      (select count(*) from checkpoint_writes
                        where thread_id = %s and checkpoint_ns = 'hearing'
                          and checkpoint_id = %s) as pending_count,
                      (select committed_checkpoint_id from agent_graph_command
                        where thread_id = %s and command_id = %s) as command_checkpoint_id,
                      (select lease_expires_at > clock_timestamp()
                         and released_at is null and cancelled_at is null
                         from agent_graph_lease where thread_id = %s) as lease_active
                    """,
                    (
                        THREAD_ID,
                        checkpoint_id,
                        THREAD_ID,
                        checkpoint_id,
                        THREAD_ID,
                        COMMAND_ID,
                        THREAD_ID,
                    ),
                )
            ).fetchone()
        assert durable == {
            "checkpoint_count": 1,
            "pending_count": 1 if pending_binding == "exact" else 0,
            "command_checkpoint_id": checkpoint_id,
            "lease_active": True,
        }
    finally:
        release_checkpoint.set()
        if not checkpoint_task.done():
            checkpoint_task.cancel()
        await asyncio.gather(checkpoint_task, return_exceptions=True)
        if pending_task is not None and not pending_task.done():
            pending_task.cancel()
            await asyncio.gather(pending_task, return_exceptions=True)
        await control_pool.close(timeout=10)
        await pool.close(timeout=10)


@pytest.mark.asyncio
async def test_real_blocked_renew_released_after_command_deadline_cannot_mutate_lease(
    graph_database: _Database,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    await _migration_runner(graph_database).run()
    await _seed_executable_command(
        graph_database,
        command_deadline_interval="3 seconds",
    )
    pool = _runtime_pool(graph_database)
    await pool.open(wait=True, timeout=10)
    saver = FencedPostgresSaver(
        pool,
        statement_timeout_ms=STATEMENT_TIMEOUT_MS,
        acquire_timeout_seconds=5,
    )
    fence = _fence(owner_id="worker-1", fencing_token=1)
    config = bind_fence_context(
        {"configurable": {"thread_id": THREAD_ID, "checkpoint_ns": "hearing"}},
        fence,
    )
    checkpoint_holds_lease = asyncio.Event()
    release_checkpoint = asyncio.Event()
    refreshed_inside_checkpoint: dict[str, Any] = {}
    original_lock = saver._lock_fence  # noqa: SLF001
    original_refresh = saver._refresh_locked_fence_lease  # noqa: SLF001

    async def hold_exact_lease_row(
        connection: Any,
        current_fence: GraphFenceContext,
    ) -> None:
        await original_lock(connection, current_fence)
        checkpoint_holds_lease.set()
        await release_checkpoint.wait()

    async def capture_checkpoint_refresh(connection: Any, current_fence: Any) -> None:
        await original_refresh(connection, current_fence)
        row = await (
            await connection.execute(
                """
                select lease_revision, renewed_at, lease_expires_at
                  from agent_graph_lease
                 where thread_id = %s and command_id = %s
                """,
                (THREAD_ID, COMMAND_ID),
            )
        ).fetchone()
        assert row is not None
        refreshed_inside_checkpoint.update(row)

    monkeypatch.setattr(saver, "_lock_fence", hold_exact_lease_row)
    monkeypatch.setattr(saver, "_refresh_locked_fence_lease", capture_checkpoint_refresh)
    checkpoint, versions = _revision_checkpoint(1)
    checkpoint_task = asyncio.create_task(
        saver.aput(config, checkpoint, {}, versions)  # type: ignore[arg-type]
    )
    renew_task: asyncio.Task[Any] | None = None
    try:
        await asyncio.wait_for(checkpoint_holds_lease.wait(), timeout=2)
        async with pool.connection(timeout=5) as connection:
            command_deadline_row = await (
                await connection.execute(
                    """
                    select deadline_at from agent_graph_command
                     where thread_id = %s and command_id = %s
                    """,
                    (THREAD_ID, COMMAND_ID),
                )
            ).fetchone()
        assert command_deadline_row is not None
        command_deadline_at = command_deadline_row["deadline_at"]
        assert isinstance(command_deadline_at, datetime)

        async def renew_after_row_lock() -> Any:
            async with pool.connection(timeout=5) as connection:
                async with connection.transaction():
                    await connection.execute("set local lock_timeout = '5s'")
                    return await PostgresLeaseRepository().renew(
                        connection,
                        thread_id=THREAD_ID,
                        command_id=COMMAND_ID,
                        owner_id="worker-1",
                        fencing_token=1,
                        command_deadline_at=command_deadline_at,
                    )

        renew_task = asyncio.create_task(renew_after_row_lock())
        with pytest.raises(TimeoutError):
            await asyncio.wait_for(asyncio.shield(renew_task), timeout=0.02)
        remaining = (command_deadline_at - datetime.now(timezone.utc)).total_seconds()
        await asyncio.sleep(max(0.0, remaining) + 0.05)
        assert renew_task.done() is False
        release_checkpoint.set()
        await asyncio.wait_for(checkpoint_task, timeout=2)
        with pytest.raises(GraphLeaseLostError):
            await asyncio.wait_for(renew_task, timeout=2)

        async with pool.connection(timeout=5) as connection:
            durable = await (
                await connection.execute(
                    """
                    select lease_revision, renewed_at, lease_expires_at
                      from agent_graph_lease
                     where thread_id = %s and command_id = %s
                    """,
                    (THREAD_ID, COMMAND_ID),
                )
            ).fetchone()
        assert durable == refreshed_inside_checkpoint
    finally:
        release_checkpoint.set()
        if not checkpoint_task.done():
            checkpoint_task.cancel()
            await asyncio.gather(checkpoint_task, return_exceptions=True)
        if renew_task is not None:
            if not renew_task.done():
                renew_task.cancel()
            await asyncio.gather(renew_task, return_exceptions=True)
        await pool.close(timeout=10)


@pytest.mark.asyncio
async def test_real_cancelled_gateway_renew_finishes_transaction_and_returns_control_pool(
    graph_database: _Database,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    await _migration_runner(graph_database).run()
    await _seed_executable_command(graph_database)
    control_pool = _runtime_pool(graph_database)
    locker_pool = _runtime_pool(graph_database)
    await control_pool.open(wait=True, timeout=10)
    await locker_pool.open(wait=True, timeout=10)

    lease_repository = PostgresLeaseRepository()
    async with control_pool.connection(timeout=5) as connection:
        initial_lease = await lease_repository.observe(connection, thread_id=THREAD_ID)
        command_deadline_row = await (
            await connection.execute(
                """
                select deadline_at from agent_graph_command
                 where thread_id = %s and command_id = %s
                """,
                (THREAD_ID, COMMAND_ID),
            )
        ).fetchone()
    assert initial_lease is not None
    assert command_deadline_row is not None
    command_deadline_at = command_deadline_row["deadline_at"]
    assert isinstance(command_deadline_at, datetime)

    lock_acquired = asyncio.Event()
    release_lock = asyncio.Event()
    renew_entered = asyncio.Event()
    transaction_exit_started = asyncio.Event()
    release_acquired_connections = asyncio.Event()
    both_slots_acquired = asyncio.Event()
    acquired_statuses: list[TransactionStatus] = []
    original_renew = lease_repository.renew
    original_connection = control_pool.connection

    async def hold_lease_row() -> None:
        async with locker_pool.connection(timeout=5) as connection:
            async with connection.transaction():
                await connection.execute(
                    "select 1 from agent_graph_lease where thread_id = %s for update",
                    (THREAD_ID,),
                )
                lock_acquired.set()
                await release_lock.wait()

    async def observe_real_renew(connection: Any, **kwargs: Any) -> Any:
        renew_entered.set()
        return await original_renew(connection, **kwargs)

    class ObservedConnection:
        def __init__(self, connection: Any) -> None:
            self._connection = connection

        def __getattr__(self, name: str) -> Any:
            return getattr(self._connection, name)

        class ObservedTransaction:
            def __init__(self, transaction: Any) -> None:
                self._transaction = transaction

            async def __aenter__(self) -> Any:
                return await self._transaction.__aenter__()

            async def __aexit__(self, *args: Any) -> Any:
                transaction_exit_started.set()
                await asyncio.sleep(0)
                return await self._transaction.__aexit__(*args)

        def transaction(self) -> Any:
            return self.ObservedTransaction(self._connection.transaction())

    @asynccontextmanager
    async def observe_gateway_connection(*, timeout: float):
        async with original_connection(timeout=timeout) as connection:
            yield ObservedConnection(connection)

    monkeypatch.setattr(lease_repository, "renew", observe_real_renew)
    gateway = GraphCommandGateway(
        mode=GraphGatewayMode.SHADOW,
        pool=SimpleNamespace(connection=observe_gateway_connection),
        leases=lease_repository,
        input_authorizer=object(),  # type: ignore[arg-type]
        acquire_timeout_seconds=2,
    )
    execution = GatewayExecution(  # type: ignore[arg-type]
        admission=SimpleNamespace(
            command=SimpleNamespace(deadline_at=command_deadline_at)
        ),
        attempt=SimpleNamespace(),
        lease=initial_lease,
        fence=_fence(owner_id="worker-1", fencing_token=1),
    )

    locker_task = asyncio.create_task(hold_lease_row())
    renew_task: asyncio.Task[Any] | None = None
    acquire_tasks: list[asyncio.Task[Any]] = []
    try:
        await asyncio.wait_for(lock_acquired.wait(), timeout=2)
        renew_task = asyncio.create_task(gateway.renew_execution(execution))
        await asyncio.wait_for(renew_entered.wait(), timeout=2)
        renew_task.cancel()
        release_lock.set()
        await asyncio.wait_for(transaction_exit_started.wait(), timeout=2)
        renew_task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await renew_task
        await asyncio.wait_for(locker_task, timeout=2)

        async def acquire_and_hold_slot() -> TransactionStatus:
            async with control_pool.connection(timeout=0.25) as connection:
                row = await (await connection.execute("select 1 as value")).fetchone()
                assert row == {"value": 1}
                acquired_statuses.append(connection.pgconn.transaction_status)
                if len(acquired_statuses) == 2:
                    both_slots_acquired.set()
                await release_acquired_connections.wait()
                return connection.pgconn.transaction_status

        acquire_tasks = [
            asyncio.create_task(acquire_and_hold_slot()),
            asyncio.create_task(acquire_and_hold_slot()),
        ]
        await asyncio.wait_for(both_slots_acquired.wait(), timeout=0.5)
        assert acquired_statuses == [TransactionStatus.IDLE, TransactionStatus.IDLE]
        assert control_pool.get_stats().get("returns_bad", 0) == 0

        async with locker_pool.connection(timeout=5) as connection:
            durable_lease = await lease_repository.observe(connection, thread_id=THREAD_ID)
        assert durable_lease == initial_lease
    finally:
        release_acquired_connections.set()
        release_lock.set()
        if renew_task is not None and not renew_task.done():
            renew_task.cancel()
        if not locker_task.done():
            locker_task.cancel()
        await asyncio.gather(locker_task, *(acquire_tasks or ()), return_exceptions=True)
        if renew_task is not None:
            await asyncio.gather(renew_task, return_exceptions=True)
        await locker_pool.close(timeout=10)
        await control_pool.close(timeout=10)


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

    assert referenced_keys == frozenset({"key-1", "key-integration-old"})
    assert nonce_count == 2


@pytest.mark.asyncio
async def test_candidate_reconciliation_proof_uses_verified_admission_clock_window(
    graph_database: _Database,
) -> None:
    """Historical admission uses the verifier's bounded clock window, read-only."""

    await _migration_runner(graph_database).run()
    ledger = PostgresCommandLedger()
    activation_id = f"p9act.v1.{uuid4().hex}"
    thread_id = f"grt.v1.{uuid4().hex}"
    issuer = "java-api-service"
    key_id = "local-target-graph"
    assert INVOCATION_CLOCK_SKEW_SECONDS == 5
    within_skew_seconds = INVOCATION_CLOCK_SKEW_SECONDS - 3
    beyond_skew_seconds = INVOCATION_CLOCK_SKEW_SECONDS + 1

    def binding_for(command_id: str, *, variant: str) -> CommandBinding:
        base = _command_binding(command_id, variant=variant)
        return replace(
            base,
            thread_id=thread_id,
            execution_lane=GraphGatewayMode.TARGET_E2E_CANDIDATE,
            activation_id=activation_id,
            room_fencing_token=11,
            command_hash="a" * 64,
            command_envelope_hash="b" * 64,
        )

    def candidate_result(binding: CommandBinding):
        logical_run_id = str(binding.request_json["logical_run_id"])
        attempt_id = str(binding.request_json["attempt_id"])
        source = TargetE2ERoomProposalSource(
            schema_version="target-e2e-room-proposal-source.v1",
            room_type="HEARING",
            proposal=TargetE2ERoomProposal(
                schema_version="target-e2e-hearing-proposal.v1",
                proposal_id=f"proposal-{binding.command_id}",
                command_id=binding.command_id,
                logical_run_id=logical_run_id,
                attempt_id=attempt_id,
                payload_schema_version="hearing-proposal.v1",
                payload_ref=f"urn:target-e2e:proposal:{binding.command_id}",
                payload_hash="f" * 64,
                terminal_class="COMPLETED",
                formal_authority=False,
            ),
        )
        fence = GraphFenceContext(
            thread_id=binding.thread_id,
            command_id=binding.command_id,
            owner_id="worker-candidate-proof",
            fencing_token=1,
            request_hash=binding.request_hash,
            room_epoch=binding.room_epoch,
            graph_key=binding.graph_key,
            graph_version=binding.graph_version,
            checkpoint_schema_version=binding.checkpoint_schema_version,
            execution_lane=GraphGatewayMode.TARGET_E2E_CANDIDATE,
            activation_id=activation_id,
            room_fencing_token=11,
            command_hash="a" * 64,
            command_envelope_hash="b" * 64,
            execution_provider="target-e2e-test",
            execution_model="candidate-proof-model",
            environment_id="candidate-proof-env",
            environment_generation=1,
            tenant_surrogate="tenant-candidate-proof",
            case_id="case-candidate-proof",
            room_type="HEARING",
            binding_hash="c" * 64,
            code_build_id="candidate-proof-build",
        )
        return TerminalResultMaterializer(
            thread_id=binding.thread_id,
            request_hash=binding.request_hash,
            draft=CompletedDraft(status="COMPLETED"),
            bindings=ResultBindings(
                command_id=binding.command_id,
                logical_run_id=logical_run_id,
                attempt_id=attempt_id,
                graph_key=binding.graph_key,
                graph_version=binding.graph_version,
                checkpoint_id="candidate-proof-checkpoint",
                cognitive_revision=1,
                public_event_proposals=(),
                artifact_operations=(),
                usage=Usage(input_tokens=0, output_tokens=0, total_tokens=0),
                execution_metadata=ExecutionMetadata(
                    prompt_version=binding.profile.prompt_version,
                    model_profile_id=binding.profile.model_profile_id,
                    schema_version=binding.profile.output_schema_version,
                    policy_version=binding.profile.policy_version,
                    guardrail_version=binding.profile.guardrail_version,
                ),
            ),
            target_proposal_source=source,
        ).materialize("candidate-proof", "candidate-proof-checkpoint", fence=fence)

    async def insert_candidate(
        connection: AsyncConnection[Any],
        binding: CommandBinding,
        *,
        jti: str,
        issued_offset_seconds: int,
        expires_offset_seconds: int,
        insert_result: bool,
    ) -> tuple[datetime, Any | None]:
        result = candidate_result(binding) if insert_result else None
        row = await (
            await connection.execute(
                """
                insert into agent_graph_command (
                    thread_id, command_id, request_schema_version, request_json, request_hash,
                    execution_mode, activation_id, room_fencing_token,
                    command_hash, command_envelope_hash,
                    room_epoch, graph_key, graph_version, checkpoint_schema_version,
                    prompt_version, model_profile_id, output_schema_version,
                    policy_version, guardrail_version, tool_policy_version,
                    deadline_at, status, attempt_count, fencing_token,
                    committed_checkpoint_ns, committed_checkpoint_id,
                    result_ref, result_hash, result_checkpointed_at, registered_at
                ) values (
                    %s, %s, %s, %s::jsonb, %s,
                    'TARGET_E2E_CANDIDATE', %s, %s, %s, %s,
                    %s, %s, %s, %s,
                    %s, %s, %s, %s, %s, %s,
                    %s, 'RESULT_CHECKPOINTED', 1, 1,
                    %s, %s, %s, %s, clock_timestamp(), clock_timestamp()
                )
                returning registered_at
                """,
                (
                    binding.thread_id,
                    binding.command_id,
                    binding.request_schema_version,
                    json.dumps(binding.request_json, separators=(",", ":")),
                    binding.request_hash,
                    binding.activation_id,
                    binding.room_fencing_token,
                    binding.command_hash,
                    binding.command_envelope_hash,
                    binding.room_epoch,
                    binding.graph_key,
                    binding.graph_version,
                    binding.checkpoint_schema_version,
                    binding.profile.prompt_version,
                    binding.profile.model_profile_id,
                    binding.profile.output_schema_version,
                    binding.profile.policy_version,
                    binding.profile.guardrail_version,
                    binding.profile.tool_policy_version,
                    binding.deadline_at,
                    result.checkpoint_ns if result is not None else "candidate-proof",
                    result.checkpoint_id if result is not None else "candidate-proof-checkpoint",
                    result.result_ref if result is not None else "urn:after-sale-flow:graph-result:" + "d" * 64,
                    result.result_hash if result is not None else "d" * 64,
                ),
            )
        ).fetchone()
        assert row is not None
        registered_at = row["registered_at"]
        await connection.execute(
            """
            insert into agent_graph_invocation_nonce (
                issuer, key_id, jti, thread_id, command_id, request_hash,
                issued_at, token_expires_at, retained_until
            ) values (%s, %s, %s, %s, %s, %s, %s, %s, %s)
            """,
            (
                issuer,
                key_id,
                jti,
                binding.thread_id,
                binding.command_id,
                binding.request_hash,
                registered_at + timedelta(seconds=issued_offset_seconds),
                registered_at + timedelta(seconds=expires_offset_seconds),
                registered_at + timedelta(days=1, seconds=issued_offset_seconds),
            ),
        )
        if result is not None:
            await connection.execute(
                """
                insert into agent_graph_result (
                    result_id, thread_id, command_id, request_hash, execution_mode, activation_id,
                    room_fencing_token,
                    command_hash, command_envelope_hash, proposal_hash, result_envelope_hash,
                    proposal_source_json, result_envelope_json,
                    result_schema_version, checkpoint_ns, checkpoint_id, cognitive_revision,
                    terminal_status, result_json, result_ref, result_hash, usage_json
                ) values (
                    %s, %s, %s, %s, %s, %s, %s,
                    %s, %s, %s, %s, %s::jsonb, %s::jsonb,
                    %s, %s, %s, %s, %s, %s::jsonb, %s, %s, %s::jsonb
                )
                """,
                (
                    result.result_id,
                    result.thread_id,
                    result.command_id,
                    result.request_hash,
                    result.execution_lane.value,
                    result.activation_id,
                    result.room_fencing_token,
                    result.command_hash,
                    result.command_envelope_hash,
                    result.proposal_hash,
                    result.result_envelope_hash,
                    json.dumps(dict(result.proposal_source_json or {}), separators=(",", ":")),
                    json.dumps(dict(result.result_envelope_json or {}), separators=(",", ":")),
                    result.result_schema_version,
                    result.checkpoint_ns,
                    result.checkpoint_id,
                    result.cognitive_revision,
                    result.terminal_status,
                    json.dumps(dict(result.result_json), separators=(",", ":")),
                    result.result_ref,
                    result.result_hash,
                    json.dumps(dict(result.usage_json), separators=(",", ":")),
                ),
            )
        return registered_at, result

    accepted = binding_for("candidate-proof-accepted", variant="candidate-proof-accepted")
    within_expiry = binding_for(
        "candidate-proof-within-expiry",
        variant="candidate-proof-within-expiry",
    )
    too_future = binding_for("candidate-proof-too-future", variant="candidate-proof-too-future")
    expired = binding_for("candidate-proof-expired", variant="candidate-proof-expired")

    async with await AsyncConnection.connect(
        graph_database.migration_dsn,
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
                'ACTIVE_CANDIDATE', 'hearing_state.v2', %s,
                'room-graph-command.v1', 'room-graph-result.v1',
                'prompt.v1', 'model.v1', 'output.v1',
                'policy.v1', 'guardrail.v1', 'tools.v1',
                %s, 'candidate-proof-build', true, clock_timestamp()
            )
            """,
            (STATE_SCHEMA_HASH, BINDING_HASH),
        )
        await connection.execute(
            """
            insert into graph_thread_registry (
                thread_id, tenant_surrogate, case_id, room_type, room_epoch,
                actor_scope_json, actor_scope_hash, agent_session_id,
                shared_session, graph_key, graph_version, checkpoint_schema_version
            ) values (
                %s, 'tenant-candidate-proof', 'case-candidate-proof', 'HEARING', 3,
                '{"audience":"PUBLIC"}'::jsonb, %s, 'session-candidate-proof',
                true, 'hearing_flow', 'hearing_flow.v2', 'hearing_checkpoint.v2'
            )
            """,
            (thread_id, "e" * 64),
        )
        await connection.execute(
            """
            insert into agent_graph_target_e2e_activation (
                activation_id, run_nonce, context_hash, environment_id,
                environment_generation, candidate_sha, tenant_surrogate, case_scope,
                allowed_room_types, temporal_namespace, context_json, issued_at, expires_at
            ) values (
                %s, 'candidate-proof-run', %s, 'candidate-proof-env',
                1, %s, 'tenant-candidate-proof', '{}'::jsonb,
                '["HEARING"]'::jsonb, 'candidate-proof-namespace', '{}'::jsonb,
                clock_timestamp() - interval '1 minute', clock_timestamp() + interval '5 minutes'
            )
            """,
            (activation_id, "c" * 64, "d" * 40),
        )
        async with connection.transaction():
            registered_at, expected_result = await insert_candidate(
                connection,
                accepted,
                jti="candidate-proof-accepted-jti",
                issued_offset_seconds=within_skew_seconds,
                expires_offset_seconds=within_skew_seconds + 30,
                insert_result=True,
            )
        assert expected_result is not None
        _, expected_within_expiry_result = await insert_candidate(
            connection,
            within_expiry,
            jti="candidate-proof-within-expiry-jti",
            issued_offset_seconds=-(within_skew_seconds + 30),
            expires_offset_seconds=-within_skew_seconds,
            insert_result=True,
        )
        assert expected_within_expiry_result is not None
        await insert_candidate(
            connection,
            too_future,
            jti="candidate-proof-too-future-jti",
            issued_offset_seconds=beyond_skew_seconds,
            expires_offset_seconds=beyond_skew_seconds + 30,
            insert_result=False,
        )
        await insert_candidate(
            connection,
            expired,
            jti="candidate-proof-expired-jti",
            issued_offset_seconds=-(beyond_skew_seconds + 30),
            expires_offset_seconds=-beyond_skew_seconds,
            insert_result=False,
        )

    pool = _runtime_pool(graph_database)
    await pool.open(wait=True, timeout=10)
    try:
        async with pool.connection(timeout=5) as connection:
            async def counts() -> dict[str, int]:
                row = await (
                    await connection.execute(
                        """
                        select
                            (select count(*) from agent_graph_command where thread_id = %s) as commands,
                            (select count(*) from agent_graph_result where thread_id = %s) as results,
                            (select count(*) from agent_graph_invocation_nonce where thread_id = %s) as nonces
                        """,
                        (thread_id, thread_id, thread_id),
                    )
                ).fetchone()
                assert row is not None
                return {name: int(value) for name, value in row.items()}

            before = await counts()
            first_command, first_result = await ledger.load_candidate_reconciliation_proof(
                connection,
                binding=accepted,
                issuer=issuer,
                key_id=key_id,
            )
            second_command, second_result = await ledger.load_candidate_reconciliation_proof(
                connection,
                binding=accepted,
                issuer=issuer,
                key_id=key_id,
            )
            within_expiry_command, within_expiry_result = (
                await ledger.load_candidate_reconciliation_proof(
                    connection,
                    binding=within_expiry,
                    issuer=issuer,
                    key_id=key_id,
                )
            )
            after = await counts()
            assert (first_command, first_result) == (second_command, second_result)
            assert first_result == expected_result
            assert within_expiry_result == expected_within_expiry_result
            assert within_expiry_command.binding == within_expiry
            assert before == after == {"commands": 4, "results": 2, "nonces": 4}

            terminal_command, terminal_result = await ledger.load_candidate_terminal_proof(
                connection,
                binding=accepted,
                issuer=issuer,
                key_id=key_id,
                jti="candidate-proof-accepted-jti",
                issued_at=registered_at + timedelta(seconds=within_skew_seconds),
                token_expires_at=registered_at
                + timedelta(seconds=within_skew_seconds + 30),
            )
            assert (terminal_command, terminal_result) == (first_command, first_result)

            for binding, rejected_issuer, rejected_key in (
                (too_future, issuer, key_id),
                (expired, issuer, key_id),
                (accepted, "other-issuer", key_id),
                (accepted, issuer, "other-key"),
                (replace(accepted, command_hash="0" * 64), issuer, key_id),
            ):
                with pytest.raises(GraphTerminalBindingError, match="pre-cutoff"):
                    await ledger.load_candidate_reconciliation_proof(
                        connection,
                        binding=binding,
                        issuer=rejected_issuer,
                        key_id=rejected_key,
                    )
    finally:
        await pool.close(timeout=10)


async def _set_fanout_config(
    database: _Database,
    config: PostgresBulkheadConfig,
) -> None:
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
            update agent_graph_fanout_config
               set room_limit = %s,
                   tenant_limit = %s,
                   global_limit = %s,
                   room_queue_limit = %s,
                   tenant_queue_limit = %s,
                   global_queue_limit = %s,
                   permit_lease_seconds = %s,
                   updated_at = clock_timestamp()
             where config_key = 'signed-synthetic'
            """,
            (
                config.room_limit,
                config.tenant_limit,
                config.global_limit,
                config.room_queue_limit,
                config.tenant_queue_limit,
                config.global_queue_limit,
                config.permit_lease_seconds,
            ),
        )


async def _seed_fanout_identities(
    database: _Database,
    tenants: tuple[str, ...],
    *,
    start_ordinal: int = 101,
) -> list[tuple[GraphBulkheadScope, GraphPermitFenceContext]]:
    identities: list[tuple[GraphBulkheadScope, GraphPermitFenceContext]] = []
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
        for ordinal, tenant in enumerate(tenants, start=start_ordinal):
            thread_id = f"grt.v1.{ordinal:032x}"
            command_id = f"command-fair-{ordinal}"
            case_id = f"case-fair-{ordinal}"
            graph_owner_id = f"graph-owner-fair-{ordinal}"
            request_json = _request_document(f"fairness-{ordinal}")
            await connection.execute(
                """
                insert into graph_thread_registry (
                    thread_id, tenant_surrogate, case_id, room_type, room_epoch,
                    actor_scope_json, actor_scope_hash, agent_session_id,
                    shared_session, graph_key, graph_version,
                    checkpoint_schema_version
                ) values (
                    %s, %s, %s, 'HEARING', 3,
                    '{"audience":"PUBLIC"}'::jsonb, %s, %s,
                    true, 'hearing_flow', 'hearing_flow.v2', 'hearing_checkpoint.v2'
                )
                """,
                (
                    thread_id,
                    tenant,
                    case_id,
                    "e" * 64,
                    f"session-fair-{ordinal}",
                ),
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
                )
                """,
                (
                    thread_id,
                    command_id,
                    json.dumps(request_json, separators=(",", ":")),
                    request_json["request_hash"],
                ),
            )
            await connection.execute(
                """
                insert into agent_graph_lease (
                    thread_id, command_id, owner_id, fencing_token,
                    lease_expires_at
                ) values (
                    %s, %s, %s, 1,
                    clock_timestamp() + interval '29 seconds'
                )
                """,
                (thread_id, command_id, graph_owner_id),
            )
            identities.append(
                (
                    GraphBulkheadScope.from_graph_identity(
                        tenant_surrogate=tenant,
                        case_id=case_id,
                        room_type="HEARING",
                        room_epoch=3,
                        item_key=f"item-fair-{ordinal}",
                    ),
                    GraphPermitFenceContext(
                        thread_id=thread_id,
                        command_id=command_id,
                        graph_lease_owner_id=graph_owner_id,
                        graph_lease_fencing_token=1,
                    ),
                )
            )
    return identities


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
            statement_timeout_ms=STATEMENT_TIMEOUT_MS,
        ),
    )


def _revision_checkpoint(revision: int) -> tuple[dict[str, Any], dict[str, str]]:
    checkpoint = empty_checkpoint()
    version = f"v-revision-{revision}"
    checkpoint["channel_values"] = {"cognitive_revision": revision}
    checkpoint["channel_versions"] = {"cognitive_revision": version}
    return checkpoint, {"cognitive_revision": version}


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
            cognitive_revision=3,
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
    include_expired_nonce: bool = False,
    command_deadline_interval: str = "10 minutes",
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
                        clock_timestamp() + %s::interval,
                        'EXECUTING', 1, 1, clock_timestamp()
                    ) on conflict do nothing
                    """,
                    (
                        THREAD_ID,
                        COMMAND_ID,
                        json.dumps(binding.request_json, separators=(",", ":")),
                        binding.request_hash,
                        command_deadline_interval,
                    ),
                )
                await connection.execute(
                    """
                    insert into agent_graph_invocation_nonce (
                        issuer, key_id, jti, thread_id, command_id, request_hash,
                        issued_at, token_expires_at, retained_until
                    ) values (
                        'java-api-service', 'key-integration-old',
                        'integration-delivery-jti', %s, %s, %s,
                        clock_timestamp() - interval '2 minutes',
                        clock_timestamp() - interval '1 minute 1 second',
                        clock_timestamp() + interval '24 hours'
                    ) on conflict do nothing
                    """,
                    (THREAD_ID, COMMAND_ID, binding.request_hash),
                )
                if include_expired_nonce:
                    await connection.execute(
                        """
                        insert into agent_graph_invocation_nonce (
                            issuer, key_id, jti, thread_id, command_id, request_hash,
                            issued_at, token_expires_at, retained_until
                        ) values (
                            'java-api-service', 'key-integration-expired',
                            'integration-expired-jti', %s, %s, %s,
                            clock_timestamp() - interval '25 hours 2 minutes',
                            clock_timestamp() - interval '25 hours 1 minute 1 second',
                            clock_timestamp() - interval '1 hour'
                        ) on conflict do nothing
                        """,
                        (THREAD_ID, COMMAND_ID, binding.request_hash),
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
