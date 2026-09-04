"""Controlled Graph PostgreSQL migration job.

Application replicas call readiness only. This module is the sole owner of checkpointer setup and
G001-G017 DDL under a session advisory lock.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from hashlib import sha256
from importlib.metadata import PackageNotFoundError, version as package_version
import json
import os
from pathlib import Path
from typing import Any, Final
from uuid import uuid4

from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from psycopg import AsyncConnection, sql
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

from app.graph_runtime.persistence_models import (
    GraphMigrationError,
    require_bounded_text,
    require_sql_identifier,
)


MIGRATION_FILENAMES: Final[tuple[str, ...]] = (
    "G001_graph_runtime.sql",
    "G002_graph_version_registry.sql",
    "G003_shadow_comparison.sql",
    "G004_graph_fanout_bulkhead.sql",
    "G005_graph_fanout_fairness_and_cancellation.sql",
    "G006_production_runtime_candidate.sql",
    "G007_graph_thread_checkpoint_parent_chain.sql",
    "G008_graph_thread_fresh_bootstrap.sql",
    "G009_graph_lease_sixty_second_window.sql",
    "G010_production_runtime_activation_month_window.sql",
    "G011_parallel_technical_completion.sql",
    "G012_parallel_subset_technical_completion.sql",
    "G013_graph_fanout_atomic_groups.sql",
    "G014_parallel_receipt_lineage_authority.sql",
    "G015_production_runtime_test_thread_purge.sql",
    "G016_parallel_receipt_abandonment_authority.sql",
    "G017_fanout_command_terminalization_authority.sql",
)
MIGRATIONS_DIRECTORY: Final[Path] = Path(__file__).resolve().parents[2] / "migrations" / "graph"
CONTROL_KEY: Final[str] = "primary"
ZERO_SHA256: Final[str] = "0" * 64
SCHEMA_ADVISORY_LOCK_PREFIX: Final[str] = "after-sale-flow:graph-schema"
REQUIRED_MIGRATION_RELATIONS: Final[tuple[str, ...]] = (
    "checkpoint_migrations",
    "checkpoints",
    "checkpoint_blobs",
    "checkpoint_writes",
    "graph_schema_migration",
    "graph_runtime_control",
    "graph_thread_registry",
    "agent_graph_command",
    "agent_graph_command_attempt",
    "agent_graph_technical_completion",
    "agent_graph_parallel_receipt_execution",
    "agent_graph_parallel_receipt_abandonment",
    "agent_graph_parallel_receipt_cycle",
    "agent_graph_result",
    "agent_graph_lease",
    "agent_graph_invocation_nonce",
    "agent_graph_version_registry",
    "agent_graph_version_active_reference",
    "agent_graph_shadow_comparison",
    "agent_graph_shadow_cleanup_receipt",
    "agent_graph_fanout_config",
    "agent_graph_fanout_tenant_turn",
    "agent_graph_fanout_permit",
    "agent_graph_fanout_permit_owner_generation",
    "agent_graph_production_runtime_activation",
    "agent_graph_production_runtime_environment_generation",
    "agent_graph_production_runtime_activation_lifecycle",
    "agent_graph_production_runtime_synthetic_case_reservation",
    "agent_graph_production_runtime_room_authority",
    "agent_graph_production_runtime_purge_receipt",
)
PINNED_PACKAGE_VERSIONS: Final[dict[str, str]] = {
    "langgraph": "1.2.6",
    "langchain-core": "1.4.9",
    "langgraph-checkpoint-postgres": "3.1.0",
    "psycopg": "3.3.4",
    "psycopg-pool": "3.3.1",
    "PyJWT": "2.13.0",
    "rfc8785": "0.1.4",
    "jsonschema": "4.26.0",
    "opentelemetry-api": "1.44.0",
    "opentelemetry-sdk": "1.44.0",
    "opentelemetry-exporter-otlp-proto-http": "1.44.0",
}

BOOTSTRAP_SQL: Final[str] = """
create table if not exists graph_schema_migration (
    version varchar(64) primary key,
    sha256 varchar(64) not null,
    applied_at timestamptz not null default clock_timestamp(),
    package_versions jsonb not null,
    execution_id varchar(64) not null,
    constraint ck_graph_schema_migration_hash check (sha256 ~ '^[0-9a-f]{64}$')
);

create table if not exists graph_runtime_control (
    control_key varchar(32) primary key,
    environment_generation varchar(64) not null,
    restore_verification_hash varchar(64) not null,
    migration_status varchar(32) not null,
    restore_status varchar(32) not null,
    expected_application_signature varchar(64) not null,
    expected_checkpoint_migration integer not null,
    verified_at timestamptz not null,
    verification_hash varchar(64) not null,
    constraint ck_graph_runtime_control_key check (control_key = 'primary'),
    constraint ck_graph_runtime_migration_status
        check (migration_status in ('CURRENT', 'DIRTY')),
    constraint ck_graph_runtime_restore_status
        check (restore_status in ('VERIFIED', 'UNVERIFIED')),
    constraint ck_graph_runtime_hashes
        check (
            expected_application_signature ~ '^[0-9a-f]{64}$'
            and restore_verification_hash ~ '^[0-9a-f]{64}$'
            and verification_hash ~ '^[0-9a-f]{64}$'
        ),
    constraint ck_graph_runtime_checkpoint_migration
        check (expected_checkpoint_migration >= 0)
);
"""


@dataclass(frozen=True, slots=True)
class GraphMigration:
    version: str
    filename: str
    sql_text: str
    sha256: str


@dataclass(frozen=True, slots=True)
class GraphMigrationReport:
    application_signature: str
    checkpoint_migration: int
    environment_generation: str
    verification_hash: str
    applied: tuple[str, ...]
    already_current: tuple[str, ...]


def load_graph_migrations(directory: Path = MIGRATIONS_DIRECTORY) -> tuple[GraphMigration, ...]:
    if not directory.is_dir():
        raise GraphMigrationError(f"Graph migration directory does not exist: {directory}")
    discovered = {path.name for path in directory.glob("G*.sql")}
    expected = set(MIGRATION_FILENAMES)
    if discovered != expected:
        missing = sorted(expected - discovered)
        unexpected = sorted(discovered - expected)
        raise GraphMigrationError(
            f"Graph migration set mismatch; missing={missing}, unexpected={unexpected}"
        )

    migrations: list[GraphMigration] = []
    for filename in MIGRATION_FILENAMES:
        path = directory / filename
        raw = path.read_bytes()
        if not raw or raw.startswith(b"\xef\xbb\xbf"):
            raise GraphMigrationError(f"Graph migration is empty or has a BOM: {filename}")
        version = filename.split("_", 1)[0]
        migrations.append(
            GraphMigration(
                version=version,
                filename=filename,
                sql_text=raw.decode("utf-8"),
                sha256=sha256(raw).hexdigest(),
            )
        )
    return tuple(migrations)


def graph_application_signature(
    migrations: tuple[GraphMigration, ...] | None = None,
) -> str:
    selected = migrations or load_graph_migrations()
    preimage = "\n".join(f"{item.filename}:{item.sha256}" for item in selected).encode()
    return sha256(preimage).hexdigest()


def expected_checkpoint_migration() -> int:
    migrations = getattr(AsyncPostgresSaver, "MIGRATIONS", None)
    if not migrations:
        raise GraphMigrationError("pinned PostgresSaver exposes no migration contract")
    return len(migrations) - 1


def pinned_package_versions() -> dict[str, str]:
    versions: dict[str, str] = {}
    for package, expected in PINNED_PACKAGE_VERSIONS.items():
        try:
            actual = package_version(package)
        except PackageNotFoundError as failure:
            raise GraphMigrationError(f"required package is not installed: {package}") from failure
        if actual != expected:
            raise GraphMigrationError(
                f"package pin mismatch for {package}: expected {expected}, found {actual}"
            )
        versions[package] = actual
    return versions


def graph_verification_hash(
    *,
    database_name: str,
    schema: str,
    environment_generation: str,
    restore_verification_hash: str,
    application_signature: str,
    checkpoint_migration: int,
    package_versions: dict[str, str],
) -> str:
    payload = {
        "application_signature": application_signature,
        "checkpoint_migration": checkpoint_migration,
        "database_name": database_name,
        "environment_generation": environment_generation,
        "package_versions": package_versions,
        "restore_verification_hash": restore_verification_hash,
        "schema": schema,
    }
    encoded = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8")
    return sha256(encoded).hexdigest()


def graph_migration_verification_hash(
    *,
    database_name: str,
    schema: str,
    environment_generation: str,
    application_signature: str,
    checkpoint_migration: int,
    package_versions: dict[str, str],
) -> str:
    payload = {
        "application_signature": application_signature,
        "checkpoint_migration": checkpoint_migration,
        "database_name": database_name,
        "environment_generation": environment_generation,
        "package_versions": package_versions,
        "schema": schema,
    }
    encoded = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8")
    return sha256(encoded).hexdigest()


def graph_schema_advisory_lock_key(schema: str) -> str:
    """Return the one session-lock identity shared by migration and restore validation."""

    return f"{SCHEMA_ADVISORY_LOCK_PREFIX}:{require_sql_identifier(schema, 'schema')}"


async def acquire_graph_schema_advisory_lock(
    connection: AsyncConnection[Any],
    schema: str,
) -> str:
    """Fail closed instead of waiting without a bound behind another privileged job."""

    lock_key = graph_schema_advisory_lock_key(schema)
    row = await (
        await connection.execute(
            "select pg_try_advisory_lock(hashtextextended(%s, 0)) as acquired",
            (lock_key,),
        )
    ).fetchone()
    if row is None or not row["acquired"]:
        raise GraphMigrationError(
            "another Graph schema migration or restore validation is already running"
        )
    return lock_key


async def release_graph_schema_advisory_lock(
    connection: AsyncConnection[Any],
    lock_key: str,
) -> None:
    row = await (
        await connection.execute(
            "select pg_advisory_unlock(hashtextextended(%s, 0)) as released",
            (lock_key,),
        )
    ).fetchone()
    if row is None or not row["released"]:
        raise GraphMigrationError("Graph schema advisory lock ownership was lost")


class GraphMigrationRunner:
    def __init__(
        self,
        connection_string: str,
        *,
        schema: str = "graph_runtime",
        expected_user: str | None = "graph_migrator",
        owner_role: str = "graph_owner",
        runtime_role: str = "graph_runtime",
        retention_role: str = "graph_retention",
        environment_generation: str | None = None,
        directory: Path = MIGRATIONS_DIRECTORY,
    ) -> None:
        if not connection_string:
            raise GraphMigrationError("migration connection string is required")
        self._connection_string = connection_string
        self._schema = require_sql_identifier(schema, "schema")
        self._expected_user = expected_user
        if expected_user is not None:
            require_bounded_text(expected_user, "expected_user", 63)
        self._owner_role = require_sql_identifier(owner_role, "owner_role")
        self._runtime_role = require_sql_identifier(runtime_role, "runtime_role")
        self._retention_role = require_sql_identifier(retention_role, "retention_role")
        if environment_generation is None:
            raise GraphMigrationError("external environment generation is required")
        self._environment_generation = require_bounded_text(
            environment_generation,
            "environment_generation",
            64,
        )
        self._directory = directory

    async def run(self) -> GraphMigrationReport:
        migrations = load_graph_migrations(self._directory)
        application_signature = graph_application_signature(migrations)
        checkpoint_migration = expected_checkpoint_migration()
        execution_id = f"gm-{uuid4().hex}"
        package_versions = pinned_package_versions()
        applied: list[str] = []
        current: list[str] = []

        async with await AsyncConnection.connect(
            self._connection_string,
            autocommit=True,
            prepare_threshold=0,
            row_factory=dict_row,
        ) as connection:
            database_name = await self._prepare_session(connection)
            lock_key = await acquire_graph_schema_advisory_lock(connection, self._schema)
            try:
                control_exists = await self._relation_exists(connection, "graph_runtime_control")
                if not control_exists:
                    await connection.execute(BOOTSTRAP_SQL, prepare=False)
                schema_current = await self._schema_is_exactly_current(
                    connection,
                    migrations=migrations,
                    checkpoint_migration=checkpoint_migration,
                    package_versions=package_versions,
                )
                if not schema_current:
                    if control_exists:
                        await self._mark_migration_dirty(
                            connection,
                            application_signature=application_signature,
                            checkpoint_migration=checkpoint_migration,
                            database_name=database_name,
                            package_versions=package_versions,
                        )
                    await connection.execute(BOOTSTRAP_SQL, prepare=False)
                    if not control_exists:
                        await self._mark_migration_dirty(
                            connection,
                            application_signature=application_signature,
                            checkpoint_migration=checkpoint_migration,
                            database_name=database_name,
                            package_versions=package_versions,
                        )
                    await AsyncPostgresSaver(connection).setup()
                await self._reject_unknown_applied_versions(connection, migrations)
                for migration in migrations:
                    if await self._apply_one(
                        connection,
                        migration,
                        package_versions=package_versions,
                        execution_id=execution_id,
                    ):
                        applied.append(migration.version)
                    else:
                        current.append(migration.version)
                await self._apply_runtime_grants(connection)
                verification_hash = await self._verify_and_mark_current(
                    connection,
                    migrations=migrations,
                    application_signature=application_signature,
                    checkpoint_migration=checkpoint_migration,
                    database_name=database_name,
                    package_versions=package_versions,
                )
            except BaseException:
                # Closing the direct session releases the lock without masking the root failure.
                raise
            else:
                await release_graph_schema_advisory_lock(connection, lock_key)

        return GraphMigrationReport(
            application_signature=application_signature,
            checkpoint_migration=checkpoint_migration,
            environment_generation=self._environment_generation,
            verification_hash=verification_hash,
            applied=tuple(applied),
            already_current=tuple(current),
        )

    async def _prepare_session(self, connection: AsyncConnection[Any]) -> str:
        identity = await (
            await connection.execute(
                "select session_user as session_user, current_database() as database_name"
            )
        ).fetchone()
        if identity is None:
            raise GraphMigrationError("cannot read Graph migration database identity")
        if self._expected_user is not None and identity["session_user"] != self._expected_user:
            raise GraphMigrationError("Graph migrations are not using the expected migrator role")
        await connection.execute(sql.SQL("set role {}").format(sql.Identifier(self._owner_role)))
        owner = await (await connection.execute("select current_user as current_user")).fetchone()
        if owner is None or owner["current_user"] != self._owner_role:
            raise GraphMigrationError("Graph migrator cannot assume the non-login owner role")
        schema = await (
            await connection.execute(
                """
                select schema_name, schema_owner
                  from information_schema.schemata
                 where schema_name = %s
                """,
                (self._schema,),
            )
        ).fetchone()
        if schema is None:
            raise GraphMigrationError("Graph schema must be preprovisioned by the database owner")
        if schema["schema_owner"] != self._owner_role:
            raise GraphMigrationError("Graph schema is not owned by the configured owner role")
        await connection.execute(
            sql.SQL("set search_path to {}, pg_catalog, pg_temp").format(
                sql.Identifier(self._schema)
            )
        )
        return identity["database_name"]

    async def _relation_exists(
        self,
        connection: AsyncConnection[Any],
        relation: str,
    ) -> bool:
        require_sql_identifier(relation, "relation")
        row = await (
            await connection.execute(
                "select to_regclass(%s) as relation",
                (f"{self._schema}.{relation}",),
            )
        ).fetchone()
        return bool(row and row["relation"] is not None)

    async def _schema_is_exactly_current(
        self,
        connection: AsyncConnection[Any],
        *,
        migrations: tuple[GraphMigration, ...],
        checkpoint_migration: int,
        package_versions: dict[str, str],
    ) -> bool:
        relation_rows = await (
            await connection.execute(
                """
                select required.relation_name,
                       to_regclass(%s || '.' || required.relation_name) is not null as present
                  from unnest(%s::text[]) as required(relation_name)
                 order by required.relation_name
                """,
                (self._schema, list(REQUIRED_MIGRATION_RELATIONS)),
            )
        ).fetchall()
        if {row["relation_name"] for row in relation_rows if row["present"]} != set(
            REQUIRED_MIGRATION_RELATIONS
        ):
            return False

        rows = await (
            await connection.execute(
                """
                select version, sha256, package_versions
                  from graph_schema_migration
                 order by version
                """
            )
        ).fetchall()
        actual = tuple((row["version"], row["sha256"], row["package_versions"]) for row in rows)
        expected = tuple(
            (migration.version, migration.sha256, package_versions) for migration in migrations
        )
        if actual != expected:
            return False

        checkpoint_rows = await (
            await connection.execute("select v as version from checkpoint_migrations order by v")
        ).fetchall()
        return tuple(row["version"] for row in checkpoint_rows) == tuple(
            range(checkpoint_migration + 1)
        )

    async def _mark_migration_dirty(
        self,
        connection: AsyncConnection[Any],
        *,
        application_signature: str,
        checkpoint_migration: int,
        database_name: str,
        package_versions: dict[str, str],
    ) -> None:
        verification_hash = graph_migration_verification_hash(
            database_name=database_name,
            schema=self._schema,
            environment_generation=self._environment_generation,
            application_signature=application_signature,
            checkpoint_migration=checkpoint_migration,
            package_versions=package_versions,
        )
        async with connection.transaction():
            await connection.execute(
                """
                insert into graph_runtime_control (
                    control_key, environment_generation, restore_verification_hash,
                    migration_status, restore_status,
                    expected_application_signature, expected_checkpoint_migration,
                    verified_at, verification_hash
                ) values (
                    %s, %s, %s, 'DIRTY', 'UNVERIFIED', %s, %s,
                    clock_timestamp(), %s
                )
                on conflict (control_key) do update
                set environment_generation = excluded.environment_generation,
                    restore_verification_hash = excluded.restore_verification_hash,
                    migration_status = 'DIRTY',
                    restore_status = 'UNVERIFIED',
                    expected_application_signature = excluded.expected_application_signature,
                    expected_checkpoint_migration = excluded.expected_checkpoint_migration,
                    verified_at = excluded.verified_at,
                    verification_hash = excluded.verification_hash
                """,
                (
                    CONTROL_KEY,
                    self._environment_generation,
                    ZERO_SHA256,
                    application_signature,
                    checkpoint_migration,
                    verification_hash,
                ),
            )

    async def _reject_unknown_applied_versions(
        self,
        connection: AsyncConnection[Any],
        migrations: tuple[GraphMigration, ...],
    ) -> None:
        rows = await (
            await connection.execute("select version from graph_schema_migration order by version")
        ).fetchall()
        actual = {row["version"] for row in rows}
        expected = {migration.version for migration in migrations}
        unknown = sorted(actual - expected)
        if unknown:
            raise GraphMigrationError(f"database has unknown Graph migrations: {unknown}")

    async def _apply_one(
        self,
        connection: AsyncConnection[Any],
        migration: GraphMigration,
        *,
        package_versions: dict[str, str],
        execution_id: str,
    ) -> bool:
        async with connection.transaction():
            existing = await (
                await connection.execute(
                    """
                    select sha256, package_versions
                      from graph_schema_migration
                     where version = %s
                     for update
                    """,
                    (migration.version,),
                )
            ).fetchone()
            if existing is not None:
                if existing["sha256"] != migration.sha256:
                    raise GraphMigrationError(
                        f"checksum conflict for Graph migration {migration.version}"
                    )
                if existing["package_versions"] != package_versions:
                    raise GraphMigrationError(
                        f"package pin conflict for Graph migration {migration.version}"
                    )
                return False

            await connection.execute(migration.sql_text, prepare=False)
            await connection.execute(
                """
                insert into graph_schema_migration (
                    version, sha256, package_versions, execution_id
                ) values (%s, %s, %s, %s)
                """,
                (
                    migration.version,
                    migration.sha256,
                    Jsonb(package_versions),
                    execution_id,
                ),
            )
        return True

    async def _apply_runtime_grants(self, connection: AsyncConnection[Any]) -> None:
        schema = sql.Identifier(self._schema)
        runtime = sql.Identifier(self._runtime_role)
        retention = sql.Identifier(self._retention_role)
        owner = sql.Identifier(self._owner_role)
        runtime_read_write = (
            "checkpoints",
            "checkpoint_blobs",
            "checkpoint_writes",
            "graph_thread_registry",
            "agent_graph_command",
            "agent_graph_command_attempt",
            "agent_graph_lease",
            "agent_graph_production_runtime_room_authority",
            "agent_graph_production_runtime_environment_generation",
            "agent_graph_production_runtime_activation_lifecycle",
        )
        runtime_read_only = (
            "checkpoint_migrations",
            "graph_schema_migration",
            "graph_runtime_control",
            "agent_graph_version_registry",
            "agent_graph_version_active_reference",
            "agent_graph_shadow_cleanup_receipt",
            "agent_graph_fanout_config",
            "agent_graph_fanout_tenant_turn",
            "agent_graph_fanout_permit",
            "agent_graph_fanout_permit_owner_generation",
            "agent_graph_production_runtime_purge_receipt",
        )
        async with connection.transaction():
            await connection.execute(
                sql.SQL("revoke all on all tables in schema {} from {}, {}").format(
                    schema,
                    runtime,
                    retention,
                )
            )
            await connection.execute(
                sql.SQL("revoke create on schema {} from {}, {}").format(
                    schema,
                    runtime,
                    retention,
                )
            )
            await connection.execute(
                sql.SQL("grant usage on schema {} to {}, {}").format(
                    schema,
                    runtime,
                    retention,
                )
            )
            for relation in runtime_read_write:
                await connection.execute(
                    sql.SQL("grant select, insert, update on {}.{} to {}").format(
                        schema,
                        sql.Identifier(relation),
                        runtime,
                    )
                )
            for relation in runtime_read_only:
                await connection.execute(
                    sql.SQL("grant select on {}.{} to {}").format(
                        schema,
                        sql.Identifier(relation),
                        runtime,
                    )
                )
            await connection.execute(
                sql.SQL("grant select, insert on {}.agent_graph_result to {}").format(
                    schema,
                    runtime,
                )
            )
            await connection.execute(
                sql.SQL(
                    "grant select, insert on {}.agent_graph_technical_completion to {}"
                ).format(schema, runtime)
            )
            await connection.execute(
                sql.SQL(
                    "grant select, insert on {}.agent_graph_parallel_receipt_execution to {}"
                ).format(schema, runtime)
            )
            await connection.execute(
                sql.SQL(
                    "grant select, insert on {}.agent_graph_parallel_receipt_cycle to {}"
                ).format(schema, runtime)
            )
            await connection.execute(
                sql.SQL(
                    "grant select, insert on {}.agent_graph_parallel_receipt_abandonment to {}"
                ).format(schema, runtime)
            )
            await connection.execute(
                sql.SQL("grant select, insert on {}.agent_graph_invocation_nonce to {}").format(
                    schema, runtime
                )
            )
            await connection.execute(
                sql.SQL(
                    "grant select, insert on {}.agent_graph_production_runtime_activation to {}"
                ).format(schema, runtime)
            )
            await connection.execute(
                sql.SQL(
                    "grant select, insert on {}.agent_graph_production_runtime_synthetic_case_reservation to {}"
                ).format(schema, runtime)
            )
            await connection.execute(
                sql.SQL("grant select, insert on {}.agent_graph_shadow_comparison to {}").format(
                    schema, runtime
                )
            )
            await connection.execute(
                sql.SQL("grant select, delete on {}.agent_graph_shadow_comparison to {}").format(
                    schema, retention
                )
            )
            await connection.execute(
                sql.SQL("grant select, delete on {}.agent_graph_invocation_nonce to {}").format(
                    schema, retention
                )
            )
            await connection.execute(
                sql.SQL("grant select on {}.agent_graph_shadow_cleanup_receipt to {}").format(
                    schema, retention
                )
            )
            await connection.execute(
                sql.SQL(
                    "grant select on {}.agent_graph_production_runtime_purge_receipt to {}"
                ).format(schema, retention)
            )
            await connection.execute(
                sql.SQL("revoke execute on all functions in schema {} from public, {}, {}").format(
                    schema, runtime, retention
                )
            )
            for routine, argument_types in (
                (
                    "require_parallel_intake_graph_command",
                    ("varchar", "varchar", "varchar"),
                ),
                (
                    "agent_graph_acquire_fanout_permit",
                    (
                        "varchar", "varchar", "varchar", "varchar", "varchar", "varchar",
                        "varchar", "bigint", "varchar", "double precision", "boolean",
                    ),
                ),
                (
                    "agent_graph_acquire_fanout_permit_group",
                    (
                        "varchar", "varchar", "varchar", "varchar", "integer", "varchar",
                        "varchar", "varchar", "bigint", "varchar", "double precision",
                        "boolean",
                    ),
                ),
                (
                    "agent_graph_renew_fanout_permit",
                    ("varchar", "bigint", "varchar", "varchar", "varchar", "bigint", "varchar"),
                ),
                (
                    "agent_graph_finish_fanout_permit",
                    (
                        "varchar", "bigint", "varchar", "varchar", "varchar", "bigint",
                        "varchar", "boolean",
                    ),
                ),
                (
                    "agent_graph_cancel_or_release_fanout_permit",
                    ("varchar", "varchar", "varchar", "varchar", "bigint", "varchar"),
                ),
                (
                    "agent_graph_terminalize_command_fanout_permits",
                    (
                        "varchar", "varchar", "varchar", "varchar",
                        "varchar", "bigint", "varchar", "varchar",
                    ),
                ),
                (
                    "agent_graph_validate_fanout_recovery",
                    ("varchar", "bigint", "varchar", "varchar", "varchar", "bigint", "varchar"),
                ),
            ):
                await connection.execute(
                    sql.SQL("grant execute on function {}.{}({}) to {}").format(
                        schema,
                        sql.Identifier(routine),
                        sql.SQL(", ").join(sql.SQL(item) for item in argument_types),
                        runtime,
                    )
                )
            for routine, argument_types in (
                (
                    "graph_production_runtime_purge_context_allows",
                    ("varchar",),
                ),
                (
                    "purge_production_runtime_test_graph_thread",
                    (
                        "varchar",
                        "varchar",
                        "varchar",
                        "bigint",
                        "varchar",
                        "varchar",
                        "varchar",
                        "varchar",
                        "jsonb",
                    ),
                ),
            ):
                await connection.execute(
                    sql.SQL("grant execute on function {}.{}({}) to {}").format(
                        schema,
                        sql.Identifier(routine),
                        sql.SQL(", ").join(sql.SQL(item) for item in argument_types),
                        retention,
                    )
                )
            await connection.execute(
                sql.SQL(
                    "alter default privileges for role {} in schema {} "
                    "revoke execute on functions from public"
                ).format(owner, schema)
            )

    async def _verify_and_mark_current(
        self,
        connection: AsyncConnection[Any],
        *,
        migrations: tuple[GraphMigration, ...],
        application_signature: str,
        checkpoint_migration: int,
        database_name: str,
        package_versions: dict[str, str],
    ) -> str:
        rows = await (
            await connection.execute(
                """
                select version, sha256, package_versions
                  from graph_schema_migration
                 order by version
                """
            )
        ).fetchall()
        actual = [(row["version"], row["sha256"], row["package_versions"]) for row in rows]
        expected = [
            (migration.version, migration.sha256, package_versions) for migration in migrations
        ]
        if actual != expected:
            raise GraphMigrationError("Graph application migration ledger is incomplete")
        checkpoint_rows = await (
            await connection.execute("select v as version from checkpoint_migrations order by v")
        ).fetchall()
        if tuple(row["version"] for row in checkpoint_rows) != tuple(
            range(checkpoint_migration + 1)
        ):
            raise GraphMigrationError("PostgresSaver migration ledger is not exactly current")

        for table in REQUIRED_MIGRATION_RELATIONS:
            exists = await (
                await connection.execute("select to_regclass(%s) as relation", (table,))
            ).fetchone()
            if exists is None or exists["relation"] is None:
                raise GraphMigrationError(f"Graph migration did not create required table {table}")

        verification_hash = graph_migration_verification_hash(
            database_name=database_name,
            schema=self._schema,
            environment_generation=self._environment_generation,
            application_signature=application_signature,
            checkpoint_migration=checkpoint_migration,
            package_versions=package_versions,
        )
        async with connection.transaction():
            await connection.execute(
                """
                insert into graph_runtime_control (
                    control_key, environment_generation, restore_verification_hash,
                    migration_status, restore_status,
                    expected_application_signature, expected_checkpoint_migration,
                    verified_at, verification_hash
                ) values (
                    %s, %s, %s, 'CURRENT', 'UNVERIFIED', %s, %s, clock_timestamp(), %s
                )
                on conflict (control_key) do update
                set environment_generation = excluded.environment_generation,
                    restore_verification_hash = case
                        when graph_runtime_control.environment_generation
                                = excluded.environment_generation
                         and graph_runtime_control.migration_status = 'CURRENT'
                         and graph_runtime_control.expected_application_signature
                                = excluded.expected_application_signature
                         and graph_runtime_control.expected_checkpoint_migration
                                = excluded.expected_checkpoint_migration
                        then graph_runtime_control.restore_verification_hash
                        else excluded.restore_verification_hash
                    end,
                    migration_status = 'CURRENT',
                    restore_status = case
                        when graph_runtime_control.environment_generation
                                = excluded.environment_generation
                         and graph_runtime_control.migration_status = 'CURRENT'
                         and graph_runtime_control.expected_application_signature
                                = excluded.expected_application_signature
                         and graph_runtime_control.expected_checkpoint_migration
                                = excluded.expected_checkpoint_migration
                        then graph_runtime_control.restore_status
                        else 'UNVERIFIED'
                    end,
                    expected_application_signature = excluded.expected_application_signature,
                    expected_checkpoint_migration = excluded.expected_checkpoint_migration,
                    verified_at = clock_timestamp(),
                    verification_hash = case
                        when graph_runtime_control.environment_generation
                                = excluded.environment_generation
                         and graph_runtime_control.migration_status = 'CURRENT'
                         and graph_runtime_control.expected_application_signature
                                = excluded.expected_application_signature
                         and graph_runtime_control.expected_checkpoint_migration
                                = excluded.expected_checkpoint_migration
                         and graph_runtime_control.restore_status = 'VERIFIED'
                        then graph_runtime_control.verification_hash
                        else excluded.verification_hash
                    end
                """,
                (
                    CONTROL_KEY,
                    self._environment_generation,
                    ZERO_SHA256,
                    application_signature,
                    checkpoint_migration,
                    verification_hash,
                ),
            )
        return verification_hash


async def run_graph_migrations(
    connection_string: str,
    *,
    schema: str = "graph_runtime",
    expected_user: str | None = "graph_migrator",
    owner_role: str = "graph_owner",
    runtime_role: str = "graph_runtime",
    retention_role: str = "graph_retention",
    environment_generation: str | None = None,
) -> GraphMigrationReport:
    return await GraphMigrationRunner(
        connection_string,
        schema=schema,
        expected_user=expected_user,
        owner_role=owner_role,
        runtime_role=runtime_role,
        retention_role=retention_role,
        environment_generation=environment_generation,
    ).run()


async def seed_production_runtime_registry(
    connection_string: str,
    *,
    schema: str,
    expected_user: str,
    owner_role: str,
    bindings_json: str,
) -> None:
    from app.config import GraphProductionBindingSettings

    schema = require_sql_identifier(schema, "schema")
    owner_role = require_sql_identifier(owner_role, "owner_role")

    try:
        candidates = json.loads(bindings_json)
        if not isinstance(candidates, list) or len(candidates) != 1:
            raise ValueError("one production-runtime binding is required")
        binding = GraphProductionBindingSettings.model_validate(candidates[0])
    except (TypeError, ValueError, json.JSONDecodeError) as error:
        raise GraphMigrationError("production-runtime registry seed is invalid") from error

    expected = {
        "graph_key": binding.graph_key,
        "graph_version": binding.graph_version,
        "checkpoint_schema_version": binding.checkpoint_schema_version,
        "registry_state": "ACTIVE_CANDIDATE",
        "state_schema_version": binding.state_schema_version,
        "state_schema_hash": binding.state_schema_hash,
        "command_schema_version": binding.command_schema_version,
        "result_schema_version": binding.result_schema_version,
        "prompt_version": binding.prompt_version,
        "model_profile_id": binding.model_profile_id,
        "output_schema_version": binding.output_schema_version,
        "policy_version": binding.policy_version,
        "guardrail_version": binding.guardrail_version,
        "tool_policy_version": binding.tool_policy_version,
        "binding_hash": binding.binding_hash,
        "code_build_id": binding.code_build_id,
        "loadable": True,
        "registry_revision": 0,
    }
    async with await AsyncConnection.connect(
        connection_string,
        row_factory=dict_row,
    ) as connection:
        async with connection.transaction():
            identity = await (
                await connection.execute("select session_user as session_user")
            ).fetchone()
            if identity is None or identity["session_user"] != expected_user:
                raise GraphMigrationError("production-runtime registry seed user mismatch")
            await connection.execute(
                sql.SQL("set role {}").format(sql.Identifier(owner_role))
            )
            owner = await (
                await connection.execute("select current_user as current_user")
            ).fetchone()
            if owner is None or owner["current_user"] != owner_role:
                raise GraphMigrationError("production-runtime registry seed cannot assume owner role")
            await connection.execute(
                sql.SQL("set local search_path to {}, pg_catalog").format(
                    sql.Identifier(schema)
                )
            )
            await connection.execute(
                """
                insert into agent_graph_version_registry (
                    graph_key, graph_version, checkpoint_schema_version,
                    registry_state, state_schema_version, state_schema_hash,
                    command_schema_version, result_schema_version, prompt_version,
                    model_profile_id, output_schema_version, policy_version,
                    guardrail_version, tool_policy_version, binding_hash,
                    code_build_id, loadable, activated_at, registry_revision
                ) values (
                    %(graph_key)s, %(graph_version)s, %(checkpoint_schema_version)s,
                    'ACTIVE_CANDIDATE', %(state_schema_version)s, %(state_schema_hash)s,
                    %(command_schema_version)s, %(result_schema_version)s,
                    %(prompt_version)s, %(model_profile_id)s,
                    %(output_schema_version)s, %(policy_version)s,
                    %(guardrail_version)s, %(tool_policy_version)s,
                    %(binding_hash)s, %(code_build_id)s, true, clock_timestamp(), 0
                )
                on conflict (graph_key, graph_version, checkpoint_schema_version)
                do nothing
                """,
                expected,
            )
            row = await (
                await connection.execute(
                    """
                    select graph_key, graph_version, checkpoint_schema_version,
                           registry_state, state_schema_version, state_schema_hash,
                           command_schema_version, result_schema_version, prompt_version,
                           model_profile_id, output_schema_version, policy_version,
                           guardrail_version, tool_policy_version, binding_hash,
                           code_build_id, loadable, registry_revision
                      from agent_graph_version_registry
                     where graph_key = %(graph_key)s
                       and graph_version = %(graph_version)s
                       and checkpoint_schema_version = %(checkpoint_schema_version)s
                    """,
                    expected,
                )
            ).fetchone()
            if row is None or dict(row) != expected:
                raise GraphMigrationError("production-runtime registry seed conflicts with storage")


def main() -> None:
    connection_string = os.environ.get("GRAPH_MIGRATION_DATABASE_DSN")
    if not connection_string:
        raise SystemExit("GRAPH_MIGRATION_DATABASE_DSN is required")
    schema = os.environ.get("GRAPH_DB_SCHEMA", "graph_runtime")
    expected_user = os.environ.get("GRAPH_MIGRATION_EXPECTED_USER", "graph_migrator")
    environment_generation = os.environ.get("GRAPH_DB_ENVIRONMENT_GENERATION")
    if not environment_generation:
        raise SystemExit("GRAPH_DB_ENVIRONMENT_GENERATION is required")
    report = asyncio.run(
        run_graph_migrations(
            connection_string,
            schema=schema,
            expected_user=expected_user,
            owner_role=os.environ.get("GRAPH_OWNER_USER", "graph_owner"),
            runtime_role=os.environ.get("GRAPH_RUNTIME_USER", "graph_runtime"),
            retention_role=os.environ.get("GRAPH_RETENTION_USER", "graph_retention"),
            environment_generation=environment_generation,
        )
    )
    target_bindings = os.environ.get("GRAPH_PRODUCTION_RUNTIME_BINDINGS")
    if target_bindings:
        asyncio.run(
            seed_production_runtime_registry(
                connection_string,
                schema=schema,
                expected_user=expected_user,
                owner_role=os.environ.get("GRAPH_OWNER_USER", "graph_owner"),
                bindings_json=target_bindings,
            )
        )
    print(
        json.dumps(
            {
                "status": "CURRENT",
                "application_signature": report.application_signature,
                "checkpoint_migration": report.checkpoint_migration,
                "environment_generation": report.environment_generation,
                "verification_hash": report.verification_hash,
                "applied": report.applied,
                "already_current": report.already_current,
            },
            separators=(",", ":"),
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    main()
