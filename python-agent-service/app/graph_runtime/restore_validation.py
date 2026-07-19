"""Privileged, one-shot restore validation kept separate from schema migration."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
import json
import os
from typing import Any

from psycopg import AsyncConnection, sql
from psycopg.rows import dict_row

from app.graph_runtime.migrations import (
    CONTROL_KEY,
    ZERO_SHA256,
    acquire_graph_schema_advisory_lock,
    expected_checkpoint_migration,
    graph_application_signature,
    graph_verification_hash,
    load_graph_migrations,
    pinned_package_versions,
    release_graph_schema_advisory_lock,
)
from app.graph_runtime.persistence_models import (
    GraphMigrationError,
    require_bounded_text,
    require_sha256,
    require_sql_identifier,
)


@dataclass(frozen=True, slots=True)
class GraphRestoreValidationReport:
    environment_generation: str
    restore_verification_hash: str
    verification_hash: str
    checks: tuple[str, ...]


class GraphRestoreValidationRunner:
    """Validate restored durable state before publishing a fresh generation marker."""

    def __init__(
        self,
        connection_string: str,
        *,
        schema: str = "graph_runtime",
        expected_user: str | None = "graph_migrator",
        owner_role: str = "graph_owner",
        environment_generation: str | None = None,
        restore_verification_hash: str | None = None,
    ) -> None:
        if not connection_string:
            raise GraphMigrationError("restore validation connection string is required")
        self._connection_string = connection_string
        self._schema = require_sql_identifier(schema, "schema")
        self._expected_user = expected_user
        if expected_user is not None:
            require_bounded_text(expected_user, "expected_user", 63)
        self._owner_role = require_sql_identifier(owner_role, "owner_role")
        self._environment_generation = require_bounded_text(
            environment_generation,
            "environment_generation",
            64,
        )
        self._restore_verification_hash = require_sha256(
            restore_verification_hash,
            "restore_verification_hash",
        )
        if self._restore_verification_hash == ZERO_SHA256:
            raise GraphMigrationError("restore verification receipt cannot be the zero sentinel")

    async def run(self) -> GraphRestoreValidationReport:
        from app.graph_runtime.readiness import CONSISTENCY_QUERIES, REQUIRED_RELATIONS

        migrations = load_graph_migrations()
        packages = pinned_package_versions()
        application_signature = graph_application_signature(migrations)
        checkpoint_migration = expected_checkpoint_migration()
        checks: list[str] = []

        async with await AsyncConnection.connect(
            self._connection_string,
            autocommit=True,
            prepare_threshold=0,
            row_factory=dict_row,
        ) as connection:
            database_name = await self._prepare_session(connection)
            lock_key = await acquire_graph_schema_advisory_lock(connection, self._schema)
            try:
                async with connection.transaction():
                    control = await (
                        await connection.execute(
                            """
                            select environment_generation, migration_status,
                                   expected_application_signature,
                                   expected_checkpoint_migration
                              from graph_runtime_control
                             where control_key = %s
                             for update
                            """,
                            (CONTROL_KEY,),
                        )
                    ).fetchone()
                    if control is None or control != {
                        "environment_generation": self._environment_generation,
                        "migration_status": "CURRENT",
                        "expected_application_signature": application_signature,
                        "expected_checkpoint_migration": checkpoint_migration,
                    }:
                        raise GraphMigrationError(
                            "restore validation does not match the current migration generation"
                        )
                    checks.append("migration_control")

                    rows = await (
                        await connection.execute(
                            """
                            select version, sha256, package_versions
                              from graph_schema_migration
                             order by version
                            """
                        )
                    ).fetchall()
                    expected_rows = [
                        {
                            "version": migration.version,
                            "sha256": migration.sha256,
                            "package_versions": packages,
                        }
                        for migration in migrations
                    ]
                    if rows != expected_rows:
                        raise GraphMigrationError("restore migration ledger is inconsistent")
                    checks.append("application_migrations")

                    checkpoint_rows = await (
                        await connection.execute(
                            "select v as version from checkpoint_migrations order by v"
                        )
                    ).fetchall()
                    if tuple(row["version"] for row in checkpoint_rows) != tuple(
                        range(checkpoint_migration + 1)
                    ):
                        raise GraphMigrationError("restore checkpointer ledger is inconsistent")
                    checks.append("checkpointer_migrations")

                    for relation in REQUIRED_RELATIONS:
                        found = await (
                            await connection.execute(
                                "select to_regclass(%s) as relation",
                                (f"{self._schema}.{relation}",),
                            )
                        ).fetchone()
                        if found is None or found["relation"] is None:
                            raise GraphMigrationError(
                                f"restore is missing required Graph relation {relation}"
                            )
                    checks.append("required_relations")

                    for check_name, query in CONSISTENCY_QUERIES:
                        result = await (await connection.execute(query)).fetchone()
                        if result is None or result["inconsistent"]:
                            raise GraphMigrationError(
                                f"restore consistency check failed: {check_name}"
                            )
                        checks.append(check_name)

                    verification_hash = graph_verification_hash(
                        database_name=database_name,
                        schema=self._schema,
                        environment_generation=self._environment_generation,
                        restore_verification_hash=self._restore_verification_hash,
                        application_signature=application_signature,
                        checkpoint_migration=checkpoint_migration,
                        package_versions=packages,
                    )
                    updated = await (
                        await connection.execute(
                            """
                            update graph_runtime_control
                               set restore_verification_hash = %s,
                                   restore_status = 'VERIFIED',
                                   verified_at = clock_timestamp(),
                                   verification_hash = %s
                             where control_key = %s
                               and environment_generation = %s
                               and migration_status = 'CURRENT'
                               and expected_application_signature = %s
                               and expected_checkpoint_migration = %s
                            returning control_key
                            """,
                            (
                                self._restore_verification_hash,
                                verification_hash,
                                CONTROL_KEY,
                                self._environment_generation,
                                application_signature,
                                checkpoint_migration,
                            ),
                        )
                    ).fetchone()
                    if updated is None:
                        raise GraphMigrationError("restore marker update lost its generation fence")
            except BaseException:
                # Closing the direct session releases the lock without masking the root failure.
                raise
            else:
                await release_graph_schema_advisory_lock(connection, lock_key)

        return GraphRestoreValidationReport(
            environment_generation=self._environment_generation,
            restore_verification_hash=self._restore_verification_hash,
            verification_hash=verification_hash,
            checks=tuple(checks),
        )

    async def _prepare_session(self, connection: AsyncConnection[Any]) -> str:
        identity = await (
            await connection.execute(
                "select session_user as session_user, current_database() as database_name"
            )
        ).fetchone()
        if identity is None:
            raise GraphMigrationError("cannot read restore validation database identity")
        if self._expected_user is not None and identity["session_user"] != self._expected_user:
            raise GraphMigrationError("restore validation is not using the expected role")
        await connection.execute(sql.SQL("set role {}").format(sql.Identifier(self._owner_role)))
        owner = await (await connection.execute("select current_user as current_user")).fetchone()
        if owner is None or owner["current_user"] != self._owner_role:
            raise GraphMigrationError("restore validator cannot assume the Graph owner role")
        await connection.execute(
            sql.SQL("set search_path to {}, pg_catalog, pg_temp").format(
                sql.Identifier(self._schema)
            )
        )
        return identity["database_name"]


async def run_graph_restore_validation(
    connection_string: str,
    *,
    schema: str = "graph_runtime",
    expected_user: str | None = "graph_migrator",
    owner_role: str = "graph_owner",
    environment_generation: str | None = None,
    restore_verification_hash: str | None = None,
) -> GraphRestoreValidationReport:
    return await GraphRestoreValidationRunner(
        connection_string,
        schema=schema,
        expected_user=expected_user,
        owner_role=owner_role,
        environment_generation=environment_generation,
        restore_verification_hash=restore_verification_hash,
    ).run()


def main() -> None:
    connection_string = os.environ.get("GRAPH_MIGRATION_DATABASE_DSN")
    if not connection_string:
        raise SystemExit("GRAPH_MIGRATION_DATABASE_DSN is required")
    report = asyncio.run(
        run_graph_restore_validation(
            connection_string,
            schema=os.environ.get("GRAPH_DB_SCHEMA", "graph_runtime"),
            expected_user=os.environ.get("GRAPH_MIGRATION_EXPECTED_USER", "graph_migrator"),
            owner_role=os.environ.get("GRAPH_OWNER_USER", "graph_owner"),
            environment_generation=os.environ.get("GRAPH_DB_ENVIRONMENT_GENERATION"),
            restore_verification_hash=os.environ.get("GRAPH_DB_RESTORE_VERIFICATION_HASH"),
        )
    )
    print(
        json.dumps(
            {
                "status": "VERIFIED",
                "environment_generation": report.environment_generation,
                "restore_verification_hash": report.restore_verification_hash,
                "verification_hash": report.verification_hash,
                "checks": report.checks,
            },
            separators=(",", ":"),
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    main()
