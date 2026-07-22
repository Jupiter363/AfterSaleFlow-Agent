from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/run_phase5_entry_checkpoint.py"
SPEC = importlib.util.spec_from_file_location("run_phase5_entry_checkpoint", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
runner = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = runner
SPEC.loader.exec_module(runner)

CANDIDATE = "a" * 40


def test_plan_binds_four_entry_sources_to_one_candidate_without_runtime_authority() -> None:
    plan = runner.candidate_plan(CANDIDATE)
    records = {record["id"]: record for record in plan["commands"]}

    assert plan["phase"] == 5
    assert plan["batch"] == "P5-BATCH-0"
    assert plan["candidate_commit"] == CANDIDATE
    assert plan["execution_order"] == list(runner.COMMAND_ORDER)
    assert set(records) == set(runner.SOURCE_REPORTS)
    assert {record["report"] for record in records.values()} == set(
        runner.SOURCE_REPORTS.values()
    )
    assert plan["concurrency"] == {
        "heavy": 1,
        "light": 2,
        "runner_execution": "sequential",
    }
    assert set(plan["runtime_restrictions"].values()) == {"forbidden"}


def test_junit_transforms_preserve_matrix_argv_without_a_shell(tmp_path: Path) -> None:
    raw = tmp_path / "entry-report.xml"
    pytest_argv = runner._command_argv_for_source(
        "p5_entry_python",
        "python -m pytest tests/agents/test_evidence_clerk_turn.py",
        raw,
        report_suffix="unused",
    )
    frontend_argv = runner._command_argv_for_source(
        "p5_entry_frontend",
        "node vitest.mjs run evidence.test.js",
        raw,
        report_suffix="unused",
    )
    java_argv = runner._command_argv_for_source(
        "p5_entry_java",
        ".\\mvnw.cmd -Dtest=EvidenceApiIntegrationTest test",
        raw,
        report_suffix="p5-entry-aaaaaaaaaaaa-12345678",
    )

    assert pytest_argv[-1] == f"--junitxml={raw.resolve()}"
    assert frontend_argv[-2:] == [
        "--reporter=junit",
        f"--outputFile={raw.resolve()}",
    ]
    assert java_argv[-2:] == [
        "-Dsurefire.reportNameSuffix=p5-entry-aaaaaaaaaaaa-12345678",
        "test",
    ]


def test_frontend_dependency_preflight_requires_classified_infra_resume(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    frontend = runner.load_source_commands()["p5_entry_frontend"]
    monkeypatch.setattr(runner, "ROOT", tmp_path)
    failure = runner._preflight_failure("p5_entry_frontend", frontend)

    assert failure is not None
    assert "classify INFRA" in failure
    assert "same SHA" in failure

    manifest = _failed_manifest("p5_entry_frontend")
    assert runner._classify_pending_failure(
        manifest, {"p5_entry_frontend": "INFRA"}
    ) is True
    assert manifest["status"] == "RUNNING"
    assert manifest["quarantined_attempts"][0]["failure_classification"] == "INFRA"


def test_non_infra_failure_blocks_the_candidate() -> None:
    for classification in ("PRODUCT", "FIXTURE", "EXTERNAL_GATE"):
        manifest = _failed_manifest("p5_entry_static")
        assert runner._classify_pending_failure(
            manifest, {"p5_entry_static": classification}
        ) is False
        assert manifest["status"] == "CANDIDATE_BLOCKED"
        assert manifest["verification_finished_at"] is not None


def test_resume_rejects_a_tampered_sealed_manifest(tmp_path: Path) -> None:
    run_root = tmp_path / "phase5-entry-seal"
    run_root.mkdir()
    manifest = runner._initial_manifest(
        candidate=CANDIDATE,
        environment_id="phase5-entry-test",
        run_root=run_root,
    )
    manifest_path = run_root / runner.MANIFEST_NAME
    runner._write_manifest(manifest_path, manifest)
    tampered = json.loads(manifest_path.read_text(encoding="utf-8"))
    tampered["status"] = "REQUIRES_CLASSIFICATION"
    manifest_path.write_text(json.dumps(tampered), encoding="utf-8")

    with pytest.raises(runner.shared.EvidenceError, match="manifest SHA-256 drifted"):
        runner._load_resume_manifest(run_root, CANDIDATE)


def test_resume_rejects_a_resealed_forged_accepted_prefix(tmp_path: Path) -> None:
    run_root = tmp_path / "phase5-entry-forged-prefix"
    run_root.mkdir()
    manifest = runner._initial_manifest(
        candidate=CANDIDATE,
        environment_id="phase5-entry-forged-test",
        run_root=run_root,
    )
    manifest["commands"] = [{"id": "p5_entry_static"}]
    runner._write_manifest(run_root / runner.MANIFEST_NAME, manifest)

    with pytest.raises(runner.shared.EvidenceError, match="record binding drifted"):
        runner._load_resume_manifest(run_root, CANDIDATE)


def test_fresh_run_schedules_every_source_once_and_stays_blocked_on_entry_commit(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    run_root = tmp_path / "phase5-entry-fresh"
    observed: list[str] = []
    monkeypatch.setattr(runner.shared, "assert_clean_detached_candidate", lambda *_: None)
    monkeypatch.setattr(runner, "_assert_candidate_unchanged", lambda *_: None)
    monkeypatch.setattr(
        runner.shared,
        "capture_environment",
        lambda _: {"snapshot_sha256": "c" * 64},
    )

    def record_source(**arguments: object) -> tuple[dict[str, str], bool]:
        command_id = str(arguments["command_id"])
        observed.append(command_id)
        return {"id": command_id}, True

    monkeypatch.setattr(runner, "_record_source", record_source)
    manifest = runner.execute_checkpoint(
        candidate_commit=CANDIDATE,
        run_root=run_root,
        environment_id="phase5-entry-test",
        resume=False,
        classifications=(),
    )

    assert observed == list(runner.COMMAND_ORDER)
    assert manifest["status"] == "PASS"
    assert manifest["batch_0"] == "PASS"
    assert manifest["contract_gate"] == "P5.0_AWAITING_ENTRY_EVIDENCE_COMMIT"
    assert manifest["engineering_execution"] == "BLOCKED_UNTIL_ENTRY_EVIDENCE_COMMIT"
    assert manifest["MIG-004"] == "PENDING_PROMOTION"
    assert manifest["MIG-005"] == "PENDING_PROMOTION"

    with pytest.raises(runner.shared.EvidenceError, match="already exists"):
        runner.execute_checkpoint(
            candidate_commit=CANDIDATE,
            run_root=run_root,
            environment_id="phase5-entry-test",
            resume=False,
            classifications=(),
        )


def test_cli_plan_is_non_executing_and_machine_readable() -> None:
    process = subprocess.run(
        [
            sys.executable,
            str(SCRIPT),
            "--candidate-commit",
            CANDIDATE,
        ],
        cwd=ROOT,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )

    assert process.returncode == 0, process.stderr
    plan = json.loads(process.stdout)
    assert plan["candidate_commit"] == CANDIDATE
    assert plan["execution_order"] == list(runner.COMMAND_ORDER)


def _failed_manifest(command_id: str) -> dict:
    return {
        "status": "REQUIRES_CLASSIFICATION",
        "pending_failure": {
            "id": command_id,
            "candidate_commit": CANDIDATE,
            "exit_code": 1,
            "accepted": False,
            "failure_classification": "UNCLASSIFIED",
        },
        "quarantined_attempts": [],
        "verification_finished_at": None,
    }
