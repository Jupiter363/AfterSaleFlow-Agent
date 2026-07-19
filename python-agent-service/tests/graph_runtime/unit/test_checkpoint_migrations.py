from __future__ import annotations

from dataclasses import replace
import inspect
from pathlib import Path

import pytest

from app.graph_runtime.migrations import (
    GraphMigrationRunner,
    MIGRATION_FILENAMES,
    PINNED_PACKAGE_VERSIONS,
    expected_checkpoint_migration,
    graph_application_signature,
    graph_migration_verification_hash,
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
    assert tuple(migration.version for migration in migrations) == ("G001", "G002", "G003")
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
