from __future__ import annotations

import copy
import importlib.util
import json
import subprocess
import sys
from pathlib import Path

import pytest
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
        "IntakeReliabilityHarnessTest",
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
    pytest_argv = runner._command_argv_for_source(
        "python_phase_4", "python -m pytest tests", raw, report_suffix="unused"
    )
    frontend_argv = runner._command_argv_for_source(
        "frontend_phase_4", "node vitest.mjs run tests", raw, report_suffix="unused"
    )
    java_argv = runner._command_argv_for_source(
        "java_phase_4",
        ".\\mvnw.cmd -Dtest=FixtureTest test",
        raw,
        report_suffix="p4-aaaaaaaaaaaa-12345678",
    )

    assert pytest_argv == ["python", "-m", "pytest", "tests", f"--junitxml={raw.resolve()}"]
    assert frontend_argv == [
        "node",
        "vitest.mjs",
        "run",
        "tests",
        "--reporter=junit",
        f"--outputFile={raw.resolve()}",
    ]
    assert java_argv == [
        ".\\mvnw.cmd",
        "-Dtest=FixtureTest",
        "-Dsurefire.reportNameSuffix=p4-aaaaaaaaaaaa-12345678",
        "test",
    ]


def test_report_path_metacharacters_remain_one_non_shell_argument(tmp_path: Path) -> None:
    raw = tmp_path / "$(touch-injected)&report.xml"

    arguments = runner._command_argv_for_source(
        "python_phase_4",
        "python -m pytest tests",
        raw,
        report_suffix="unused",
    )

    assert arguments[:-1] == ["python", "-m", "pytest", "tests"]
    assert arguments[-1] == f"--junitxml={raw.resolve()}"

    with pytest.raises(runner.EvidenceError, match="shell control characters"):
        runner._command_argv_for_source(
            "python_phase_4",
            "python -m pytest tests & echo injected",
            raw,
            report_suffix="unused",
        )


def test_resume_rejects_bare_command_ids_that_would_skip_all_sources(
    tmp_path: Path,
) -> None:
    run_root = tmp_path / "attempt-forged"
    run_root.mkdir()
    manifest = runner._initial_manifest(
        candidate=CANDIDATE,
        environment_id="resume-forgery-test",
        run_root=run_root,
    )
    manifest["commands"] = [{"id": command_id} for command_id in runner.COMMAND_ORDER]
    runner._write_manifest(run_root / runner.MANIFEST_NAME, manifest)

    with pytest.raises(
        runner.EvidenceError, match="resume record binding drifted"
    ):
        runner._load_resume_manifest(run_root, CANDIDATE)


def test_resume_rejects_commands_that_are_not_the_ordered_source_prefix(
    tmp_path: Path,
) -> None:
    run_root = tmp_path / "attempt-out-of-order"
    run_root.mkdir()
    manifest = runner._initial_manifest(
        candidate=CANDIDATE,
        environment_id="resume-prefix-test",
        run_root=run_root,
    )
    manifest["commands"] = [{"id": runner.COMMAND_ORDER[1]}]
    runner._write_manifest(run_root / runner.MANIFEST_NAME, manifest)

    with pytest.raises(runner.EvidenceError, match="ordered source prefix"):
        runner._load_resume_manifest(run_root, CANDIDATE)


def test_resume_rejects_unsealed_and_tampered_manifests(tmp_path: Path) -> None:
    run_root = tmp_path / "attempt-seal"
    run_root.mkdir()
    manifest = runner._initial_manifest(
        candidate=CANDIDATE,
        environment_id="resume-seal-test",
        run_root=run_root,
    )
    manifest_path = run_root / runner.MANIFEST_NAME
    runner._write_manifest(manifest_path, manifest)

    sealed = json.loads(manifest_path.read_text(encoding="utf-8"))
    sealed["status"] = "REQUIRES_CLASSIFICATION"
    manifest_path.write_text(json.dumps(sealed), encoding="utf-8")
    with pytest.raises(runner.EvidenceError, match="manifest SHA-256 drifted"):
        runner._load_resume_manifest(run_root, CANDIDATE)

    sealed.pop("manifest_sha256")
    manifest_path.write_text(json.dumps(sealed), encoding="utf-8")
    with pytest.raises(runner.EvidenceError, match="no lowercase manifest SHA-256"):
        runner._load_resume_manifest(run_root, CANDIDATE)


def test_product_blocked_candidate_cannot_resume(tmp_path: Path) -> None:
    run_root = tmp_path / "phase4-blocked-aaaaaaaa"
    run_root.mkdir()
    manifest = runner._initial_manifest(
        candidate=CANDIDATE,
        environment_id="blocked-product-test",
        run_root=run_root,
    )
    manifest["status"] = "CANDIDATE_BLOCKED"
    manifest["verification_finished_at"] = "2026-07-22T00:01:00+00:00"
    runner._write_manifest(run_root / runner.MANIFEST_NAME, manifest)

    with pytest.raises(runner.EvidenceError, match="blocked this candidate"):
        runner._load_resume_manifest(run_root, CANDIDATE)


def test_new_candidate_refuses_an_existing_run_with_old_source_reports(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    new_candidate = "b" * 40
    old_run = tmp_path / "phase4-run-aaaaaaaa-existing"
    old_source = old_run / "source"
    old_source.mkdir(parents=True)
    (old_source / "python-phase4-junit.xml").write_text(
        "old candidate report\n", encoding="utf-8"
    )
    monkeypatch.setattr(runner, "assert_clean_detached_candidate", lambda *_: None)

    with pytest.raises(runner.EvidenceError, match="run directory already exists"):
        runner.execute_checkpoint(
            candidate_commit=new_candidate,
            run_root=old_run,
            environment_id="new-candidate-test",
            resume=False,
            classifications=(),
        )


def test_fresh_candidate_run_schedules_all_four_source_suites(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    new_candidate = "b" * 40
    fresh_run = tmp_path / "phase4-run-bbbbbbbb-fresh"
    observed: list[str] = []
    monkeypatch.setattr(runner, "assert_clean_detached_candidate", lambda *_: None)
    monkeypatch.setattr(runner, "_assert_candidate_unchanged", lambda *_: None)
    monkeypatch.setattr(
        runner,
        "capture_environment",
        lambda _: {"snapshot_sha256": "c" * 64},
    )

    def record_source(**arguments: object) -> tuple[dict[str, str], bool]:
        command_id = str(arguments["command_id"])
        observed.append(command_id)
        return {"id": command_id}, True

    monkeypatch.setattr(runner, "_record_source", record_source)

    manifest = runner.execute_checkpoint(
        candidate_commit=new_candidate,
        run_root=fresh_run,
        environment_id="fresh-candidate-test",
        resume=False,
        classifications=(),
    )

    assert observed == list(runner.COMMAND_ORDER)
    assert [record["id"] for record in manifest["commands"]] == list(
        runner.COMMAND_ORDER
    )
    assert manifest["candidate_commit"] == new_candidate
    assert manifest["status"] == "PASS"


def test_candidate_run_directory_is_confined_inside_the_repository(
    tmp_path: Path,
) -> None:
    with pytest.raises(runner.EvidenceError, match="must be under .codex-run"):
        runner.assert_candidate_run_directory(ROOT / "test-reports" / "forged-run")

    runner.assert_candidate_run_directory(ROOT / ".codex-run" / "phase4-safe")
    runner.assert_candidate_run_directory(tmp_path / "external-phase4-run")


def test_source_process_receives_argv_with_shell_disabled(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    observed: dict[str, object] = {}

    def fake_run(command: list[str], **kwargs: object) -> subprocess.CompletedProcess[str]:
        observed["command"] = command
        observed["shell"] = kwargs["shell"]
        observed["cwd"] = kwargs["cwd"]
        return subprocess.CompletedProcess(command, 0)

    monkeypatch.setattr(runner.subprocess, "run", fake_run)
    command = ["python", "-c", "print('literal & | > payload')"]
    stdout = tmp_path / "stdout.log"
    stderr = tmp_path / "stderr.log"

    _, _, _, exit_code = runner._run_shell(command, tmp_path, stdout, stderr)

    assert exit_code == 0
    assert observed == {
        "command": command,
        "shell": False,
        "cwd": tmp_path,
    }


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
