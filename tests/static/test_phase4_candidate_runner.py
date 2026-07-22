from __future__ import annotations

import copy
import importlib.util
import sys
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/run_phase4_candidate_checkpoint.py"
SPEC = importlib.util.spec_from_file_location("run_phase4_candidate_checkpoint", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
runner = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = runner
SPEC.loader.exec_module(runner)

CANDIDATE = "a" * 40


def test_plan_expands_one_sha_and_the_complete_signed_synthetic_chain() -> None:
    plan = runner.candidate_plan(CANDIDATE)
    records = {record["id"]: record for record in plan["commands"]}

    assert plan["candidate_commit"] == CANDIDATE
    assert plan["execution_order"] == list(runner.COMMAND_ORDER)
    assert set(records) == set(runner.SOURCE_REPORTS)
    java = records["java_phase_4"]["matrix_command"]
    python = records["python_phase_4"]["matrix_command"]
    for classname in (
        "JdbcIntakeSignedSyntheticAdmissionPortIntegrationTest",
        "JdbcIntakeSyntheticRuntimeSourceTest",
        "IntakeSyntheticRuntimeMaterialSourceTest",
        "IntakeSyntheticRuntimeAdaptersTest",
        "IntakeExchangeP0Test",
        "IntakeSyntheticShadowConfigurationTest",
        "TemporalWorkerConfigurationTest",
    ):
        assert classname in java
    for test_path in (
        "tests/graph_runtime/unit/test_production_bindings.py",
        "tests/graph_runtime/unit/test_intake_graph_recovery.py",
        "tests/graph_runtime/unit/test_checkpoint_fenced_saver.py",
    ):
        assert test_path in python
    assert plan["runtime_restrictions"] == {
        "real_provider": "forbidden",
        "formal_finalizer": "forbidden",
        "real_case_shadow": "forbidden",
        "promotion": "forbidden",
    }


def test_runtime_material_provider_is_an_explicit_engineering_only_evidence_boundary() -> None:
    policy = yaml.safe_load(
        (
            ROOT
            / "docs/runbooks/temporal-first/phase-4-engineering-evidence-policy.yaml"
        ).read_text(encoding="utf-8")
    )
    material = policy["runtime_material_chain_evidence"]

    assert material == {
        "claim_ceiling": "ENGINEERING_ONLY",
        "evidence": "java-phase4-junit.xml",
        "promotion_effect": "NONE",
        "required_java_test_classes": ["IntakeSyntheticRuntimeMaterialSourceTest"],
    }


def test_junit_transforms_preserve_the_approved_matrix_command(tmp_path: Path) -> None:
    raw = tmp_path / "raw.xml"
    pytest_command = runner._command_for_source(
        "python_phase_4", "python -m pytest tests", raw, report_suffix="unused"
    )
    frontend_command = runner._command_for_source(
        "frontend_phase_4", "node vitest.mjs run tests", raw, report_suffix="unused"
    )
    java_command = runner._command_for_source(
        "java_phase_4",
        ".\\mvnw.cmd -Dtest=FixtureTest test",
        raw,
        report_suffix="p4-aaaaaaaaaaaa-12345678",
    )

    assert pytest_command.startswith("python -m pytest tests --junitxml=")
    assert frontend_command.startswith(
        "node vitest.mjs run tests --reporter=junit --outputFile="
    )
    assert java_command == (
        ".\\mvnw.cmd -Dtest=FixtureTest "
        "-Dsurefire.reportNameSuffix=p4-aaaaaaaaaaaa-12345678 test"
    )


def _failed_manifest() -> dict:
    return {
        "status": "REQUIRES_CLASSIFICATION",
        "pending_failure": {
            "id": "java_phase_4",
            "candidate_commit": CANDIDATE,
            "exit_code": 1,
            "accepted": False,
            "failure_classification": "UNCLASSIFIED",
        },
        "quarantined_attempts": [],
        "verification_finished_at": None,
    }


def test_only_classified_infra_failure_can_resume_the_same_sha() -> None:
    infra = _failed_manifest()
    assert runner._classify_pending_failure(
        infra, {"java_phase_4": "INFRA"}
    ) is True
    assert infra["pending_failure"] is None
    assert infra["quarantined_attempts"][0]["failure_classification"] == "INFRA"
    assert infra["status"] == "RUNNING"

    product = _failed_manifest()
    assert runner._classify_pending_failure(
        product, {"java_phase_4": "PRODUCT"}
    ) is False
    assert product["status"] == "CANDIDATE_BLOCKED"
    assert product["verification_finished_at"] is not None

    unclassified = copy.deepcopy(_failed_manifest())
    assert runner._classify_pending_failure(unclassified, {}) is False
    assert unclassified["status"] == "REQUIRES_CLASSIFICATION"


def test_environment_snapshot_hashes_dependency_manifests_without_environment_values() -> None:
    snapshot = runner.capture_environment("static-fixture")
    digest = snapshot.pop("snapshot_sha256")

    assert digest == runner._json_sha256(snapshot)
    assert snapshot["environment_id"] == "static-fixture"
    assert snapshot["dependency_manifests"]
    assert all(
        set(item) == {"path", "sha256"}
        for item in snapshot["dependency_manifests"]
    )
    assert "environment_variables" not in snapshot
