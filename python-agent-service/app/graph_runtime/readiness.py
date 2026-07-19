"""Read-only, fail-closed readiness probes for Graph PostgreSQL."""

from __future__ import annotations

import asyncio
from collections.abc import Mapping, Sequence
from typing import Any, Final

from app.graph_runtime.migrations import (
    GraphMigration,
    expected_checkpoint_migration,
    graph_application_signature,
    graph_verification_hash,
    load_graph_migrations,
    pinned_package_versions,
)
from app.graph_runtime.persistence_models import (
    GraphGatewayMode,
    GraphReadinessConfig,
    GraphReadinessReport,
)


REQUIRED_RELATIONS: Final[tuple[str, ...]] = (
    "checkpoint_migrations",
    "checkpoints",
    "checkpoint_blobs",
    "checkpoint_writes",
    "graph_schema_migration",
    "graph_runtime_control",
    "graph_thread_registry",
    "agent_graph_command",
    "agent_graph_command_attempt",
    "agent_graph_result",
    "agent_graph_lease",
    "agent_graph_invocation_nonce",
    "agent_graph_version_registry",
    "agent_graph_version_active_reference",
    "agent_graph_shadow_comparison",
    "agent_graph_shadow_cleanup_receipt",
)
RUNTIME_DELETE_FORBIDDEN_RELATIONS: Final[tuple[str, ...]] = REQUIRED_RELATIONS
RUNTIME_APPEND_ONLY_RELATIONS: Final[tuple[str, ...]] = (
    "agent_graph_result",
    "agent_graph_invocation_nonce",
    "agent_graph_shadow_comparison",
    "agent_graph_shadow_cleanup_receipt",
)

CONSISTENCY_QUERIES: Final[tuple[tuple[str, str], ...]] = (
    (
        "command_checkpoint_consistent",
        """
        select exists (
            select 1
              from agent_graph_command command
             where command.status in ('RESULT_CHECKPOINTED', 'COMPLETED')
               and (
                   command.committed_checkpoint_id is null
                   or command.result_ref is null
                   or command.result_hash is null
                   or command.result_checkpointed_at is null
               )
             limit 1
        ) as inconsistent
        """,
    ),
    (
        "checkpoint_rows_consistent",
        """
        select exists (
            select 1
              from agent_graph_command command
             where command.committed_checkpoint_id is not null
               and not exists (
                   select 1
                     from checkpoints checkpoint
                    where checkpoint.thread_id = command.thread_id
                      and checkpoint.checkpoint_ns = coalesce(
                          command.committed_checkpoint_ns, ''
                      )
                      and checkpoint.checkpoint_id = command.committed_checkpoint_id
                      and checkpoint.metadata ->> 'graph_thread_id' = command.thread_id
                      and checkpoint.metadata ->> 'graph_command_id' = command.command_id
                      and checkpoint.metadata ->> 'graph_request_hash' = command.request_hash
                      and checkpoint.metadata ->> 'graph_room_epoch' = command.room_epoch::text
                      and checkpoint.metadata ->> 'graph_key' = command.graph_key
                      and checkpoint.metadata ->> 'graph_version' = command.graph_version
                      and checkpoint.metadata ->> 'graph_checkpoint_schema_version'
                          = command.checkpoint_schema_version
                      and checkpoint.metadata ->> 'graph_fencing_token'
                          = command.fencing_token::text
                      and checkpoint.metadata ->> 'graph_result_hash'
                          is not distinct from command.result_hash
                      and checkpoint.metadata ->> 'graph_result_ref'
                          is not distinct from command.result_ref
                )
             limit 1
        ) as inconsistent
        """,
    ),
    (
        "active_fences_consistent",
        """
        select exists (
            select 1
              from agent_graph_command command
             where command.status = 'EXECUTING'
               and not exists (
                   select 1
                     from agent_graph_lease lease
                    where lease.thread_id = command.thread_id
                      and lease.command_id = command.command_id
                      and lease.fencing_token = command.fencing_token
                      and lease.released_at is null
                      and lease.cancelled_at is null
               )
             limit 1
        ) as inconsistent
        """,
    ),
    (
        "thread_versions_consistent",
        """
        select exists (
            select 1
              from graph_thread_registry thread
              left join agent_graph_version_registry registry
                on registry.graph_key = thread.graph_key
               and registry.graph_version = thread.graph_version
               and registry.checkpoint_schema_version = thread.checkpoint_schema_version
             where registry.graph_key is null or not registry.loadable
             limit 1
        ) as inconsistent
        """,
    ),
    (
        "command_threads_consistent",
        """
        select exists (
            select 1
              from agent_graph_command command
              join graph_thread_registry thread on thread.thread_id = command.thread_id
             where row(
                       command.room_epoch,
                       command.graph_key,
                       command.graph_version,
                       command.checkpoint_schema_version
                   ) is distinct from row(
                       thread.room_epoch,
                       thread.graph_key,
                       thread.graph_version,
                       thread.checkpoint_schema_version
                   )
             limit 1
        ) as inconsistent
        """,
    ),
    (
        "completed_results_consistent",
        """
        select exists (
            select 1
              from agent_graph_command command
             where command.status = 'COMPLETED'
               and not exists (
                   select 1
                     from agent_graph_result result
                    where result.thread_id = command.thread_id
                      and result.command_id = command.command_id
                      and result.request_hash = command.request_hash
                      and result.result_hash = command.result_hash
                      and result.result_ref = command.result_ref
                      and result.checkpoint_ns = coalesce(
                          command.committed_checkpoint_ns, ''
                      )
                      and result.checkpoint_id = command.committed_checkpoint_id
               )
             limit 1
        ) as inconsistent
        """,
    ),
)


class _ReadinessFailure(RuntimeError):
    def __init__(self, code: str, checks: Mapping[str, bool]) -> None:
        super().__init__(code)
        self.code = code
        self.checks = dict(checks)


class GraphPersistenceReadinessProbe:
    """Validate storage identity and integrity without owning DDL or repair."""

    def __init__(
        self,
        config: GraphReadinessConfig,
        pool: Any | None = None,
        *,
        migrations: Sequence[GraphMigration] | None = None,
    ) -> None:
        self._config = config
        self._pool = pool
        self._migrations = tuple(migrations) if migrations is not None else None

    async def check(self) -> GraphReadinessReport:
        if self._config.mode is GraphGatewayMode.DISABLED:
            return GraphReadinessReport.disabled()
        if self._config.mode is not GraphGatewayMode.SHADOW:
            return self._failed("GRAPH_MODE_FORBIDDEN", {})
        if self._pool is None:
            return self._failed("GRAPH_POOL_MISSING", {"pool_available": False})

        try:
            async with asyncio.timeout(self._config.timeout_seconds):
                return await self._check_shadow()
        except TimeoutError:
            return self._failed("GRAPH_READINESS_TIMEOUT", {"bounded_probe": False})
        except _ReadinessFailure as failure:
            return self._failed(failure.code, failure.checks)
        except Exception:
            return self._failed("GRAPH_READINESS_FAILED", {})

    async def _check_shadow(self) -> GraphReadinessReport:
        checks: dict[str, bool] = {"pool_available": True}
        migrations = self._migrations or load_graph_migrations()

        async with self._pool.connection(timeout=self._config.timeout_seconds) as connection:
            async with connection.transaction():
                await connection.execute("set transaction read only")
                await self._check_identity(connection, checks)
                await self._check_privileges(connection, checks)
                await self._check_relations(connection, checks)
                await self._check_migrations(connection, checks, migrations)
                await self._check_control(connection, checks, migrations)
                await self._check_consistency(connection, checks)

        checks["bounded_probe"] = True
        return GraphReadinessReport(
            ready=True,
            mode=GraphGatewayMode.SHADOW,
            code="GRAPH_PERSISTENCE_READY",
            checks=checks,
        )

    async def _check_identity(self, connection: Any, checks: dict[str, bool]) -> None:
        row = await self._fetchone(
            connection,
            """
            select current_database() as database_name,
                   current_user as user_name,
                   current_schema() as schema_name
            """,
        )
        checks["database_identity"] = bool(
            row
            and row["database_name"] == self._config.expected_database
            and row["user_name"] == self._config.expected_user
            and row["schema_name"] == self._config.schema
        )
        self._require(checks["database_identity"], "GRAPH_DATABASE_IDENTITY", checks)

    async def _check_privileges(self, connection: Any, checks: dict[str, bool]) -> None:
        row = await self._fetchone(
            connection,
            """
            select has_database_privilege(
                       current_user, current_database(), 'CREATE'
                   ) as can_create_database,
                   has_database_privilege(
                       current_user, current_database(), 'CONNECT'
                   ) as can_connect_database,
                   has_database_privilege(
                       current_user, current_database(), 'TEMPORARY'
                   ) as can_create_temporary,
                   has_schema_privilege(
                       current_user, %s, 'CREATE'
                   ) as can_create_schema,
                   has_schema_privilege(
                       current_user, %s, 'USAGE'
                   ) as can_use_schema,
                   exists (
                       select 1
                         from pg_class relation
                         join pg_namespace namespace
                           on namespace.oid = relation.relnamespace
                        where namespace.nspname = %s
                           and pg_get_userbyid(relation.relowner) = current_user
                   ) as owns_relation,
                   has_table_privilege(
                       current_user, %s || '.agent_graph_command', 'SELECT'
                   ) as can_read_command,
                   has_table_privilege(
                       current_user, %s || '.agent_graph_command', 'INSERT'
                   ) and has_table_privilege(
                       current_user, %s || '.agent_graph_command', 'UPDATE'
                   ) as can_write_command,
                   has_table_privilege(
                       current_user, %s || '.checkpoints', 'INSERT'
                   ) and has_table_privilege(
                       current_user, %s || '.checkpoints', 'UPDATE'
                   ) and has_table_privilege(
                       current_user, %s || '.checkpoint_blobs', 'INSERT'
                   ) and has_table_privilege(
                       current_user, %s || '.checkpoint_writes', 'INSERT'
                   ) and has_table_privilege(
                       current_user, %s || '.checkpoint_writes', 'UPDATE'
                   ) as can_write_checkpoints,
                   has_table_privilege(
                       current_user, %s || '.agent_graph_version_registry', 'SELECT'
                   ) as can_read_registry,
                   has_table_privilege(
                       current_user, %s || '.agent_graph_version_registry', 'INSERT'
                   ) or has_table_privilege(
                       current_user, %s || '.agent_graph_version_registry', 'UPDATE'
                   ) or has_table_privilege(
                       current_user, %s || '.agent_graph_version_registry', 'DELETE'
                   ) as can_mutate_registry,
                   has_table_privilege(
                       current_user, %s || '.graph_schema_migration', 'INSERT'
                   ) or has_table_privilege(
                       current_user, %s || '.graph_runtime_control', 'UPDATE'
                   ) as can_mutate_control,
                    exists (
                        select 1
                          from unnest(%s::text[]) as forbidden(relation_name)
                         where has_table_privilege(
                             current_user,
                             %s || '.' || forbidden.relation_name,
                             'DELETE'
                         )
                    ) as can_delete_runtime_rows,
                    exists (
                        select 1
                          from unnest(%s::text[]) as append_only(relation_name)
                         where has_table_privilege(
                             current_user,
                             %s || '.' || append_only.relation_name,
                             'UPDATE'
                         )
                    ) or has_table_privilege(
                        current_user,
                        %s || '.agent_graph_shadow_cleanup_receipt',
                        'INSERT'
                    ) as can_mutate_append_only
            """,
            (
                *((self._config.schema,) * 17),
                list(RUNTIME_DELETE_FORBIDDEN_RELATIONS),
                self._config.schema,
                list(RUNTIME_APPEND_ONLY_RELATIONS),
                self._config.schema,
                self._config.schema,
            ),
        )
        checks["runtime_role_read_only"] = bool(
            row
            and not row["can_create_database"]
            and not row["can_create_temporary"]
            and not row["can_create_schema"]
            and row["can_connect_database"]
            and row["can_use_schema"]
            and not row["owns_relation"]
            and row["can_read_command"]
            and row["can_write_command"]
            and row["can_write_checkpoints"]
            and row["can_read_registry"]
            and not row["can_mutate_registry"]
            and not row["can_mutate_control"]
            and not row["can_delete_runtime_rows"]
            and not row["can_mutate_append_only"]
        )
        self._require(checks["runtime_role_read_only"], "GRAPH_RUNTIME_ROLE_PRIVILEGED", checks)

    async def _check_relations(self, connection: Any, checks: dict[str, bool]) -> None:
        cursor = await connection.execute(
            """
            select required.relation_name,
                   to_regclass(%s || '.' || required.relation_name) is not null as present
              from unnest(%s::text[]) as required(relation_name)
             order by required.relation_name
            """,
            (self._config.schema, list(REQUIRED_RELATIONS)),
        )
        rows = await cursor.fetchall()
        present = {row["relation_name"] for row in rows if row["present"]}
        checks["required_relations"] = present == set(REQUIRED_RELATIONS)
        self._require(checks["required_relations"], "GRAPH_RELATION_MISSING", checks)

    async def _check_migrations(
        self,
        connection: Any,
        checks: dict[str, bool],
        migrations: Sequence[GraphMigration],
    ) -> None:
        package_versions = pinned_package_versions()
        cursor = await connection.execute(
            """
            select version, sha256, package_versions
              from graph_schema_migration
             order by version
            """
        )
        rows = await cursor.fetchall()
        actual = tuple((row["version"], row["sha256"], row["package_versions"]) for row in rows)
        expected = tuple(
            (migration.version, migration.sha256, package_versions) for migration in migrations
        )
        checks["application_migrations"] = actual == expected
        self._require(
            checks["application_migrations"],
            "GRAPH_MIGRATION_CHECKSUM",
            checks,
        )

        row = await self._fetchone(
            connection,
            "select array_agg(v order by v) as versions from checkpoint_migrations",
        )
        checks["checkpointer_migrations"] = bool(
            row
            and tuple(row["versions"] or ()) == tuple(range(expected_checkpoint_migration() + 1))
        )
        self._require(
            checks["checkpointer_migrations"],
            "GRAPH_CHECKPOINTER_MIGRATION",
            checks,
        )

    async def _check_control(
        self,
        connection: Any,
        checks: dict[str, bool],
        migrations: Sequence[GraphMigration],
    ) -> None:
        row = await self._fetchone(
            connection,
            """
            select environment_generation, restore_verification_hash,
                   migration_status, restore_status,
                   expected_application_signature, expected_checkpoint_migration,
                   verification_hash
              from graph_runtime_control
             where control_key = 'primary'
            """,
        )
        signature = graph_application_signature(tuple(migrations))
        checkpoint_migration = expected_checkpoint_migration()
        package_versions = pinned_package_versions()
        verification_hash = graph_verification_hash(
            database_name=self._config.expected_database or "",
            schema=self._config.schema,
            environment_generation=self._config.expected_environment_generation or "",
            restore_verification_hash=(self._config.expected_restore_verification_hash or ""),
            application_signature=signature,
            checkpoint_migration=checkpoint_migration,
            package_versions=package_versions,
        )
        checks["restore_marker"] = bool(
            row
            and row["environment_generation"] == self._config.expected_environment_generation
            and row["restore_verification_hash"] == self._config.expected_restore_verification_hash
            and row["migration_status"] == "CURRENT"
            and row["restore_status"] == "VERIFIED"
            and row["expected_application_signature"] == signature
            and row["expected_checkpoint_migration"] == checkpoint_migration
            and row["verification_hash"] == verification_hash
        )
        self._require(checks["restore_marker"], "GRAPH_RESTORE_UNVERIFIED", checks)

    async def _check_consistency(self, connection: Any, checks: dict[str, bool]) -> None:
        for check_name, query in CONSISTENCY_QUERIES:
            row = await self._fetchone(connection, query)
            checks[check_name] = bool(row and not row["inconsistent"])
            self._require(checks[check_name], "GRAPH_RESTORE_INCONSISTENT", checks)

    @staticmethod
    async def _fetchone(
        connection: Any,
        query: str,
        params: tuple[Any, ...] | None = None,
    ) -> Mapping[str, Any] | None:
        cursor = await connection.execute(query, params)
        return await cursor.fetchone()

    @staticmethod
    def _require(passed: bool, code: str, checks: Mapping[str, bool]) -> None:
        if not passed:
            raise _ReadinessFailure(code, checks)

    def _failed(self, code: str, checks: Mapping[str, bool]) -> GraphReadinessReport:
        return GraphReadinessReport(
            ready=False,
            mode=self._config.mode,
            code=code,
            checks=dict(checks),
        )


async def check_graph_persistence_readiness(
    config: GraphReadinessConfig,
    pool: Any | None = None,
) -> GraphReadinessReport:
    return await GraphPersistenceReadinessProbe(config, pool).check()
