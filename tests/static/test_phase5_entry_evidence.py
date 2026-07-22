from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest
import yaml

from scripts import generate_phase5_entry_evidence as generator


ROOT = Path(__file__).resolve().parents[2]
CANDIDATE = "a" * 40
BASE = "b" * 40
RELEASE_ID = "phase-5-entry-fixture"
HANDOFF = {
    "checkpoint_path": "test-reports/temporal-first/p4/phase-4/phase-metrics.json",
    "checkpoint_sha256": "1" * 64,
    "candidate_commit_file_sha256": "3" * 64,
    "evidence_commit": "d" * 40,
    "phase4_candidate_commit": "e" * 40,
    "engineering_checkpoint": "PASS",
    "next_phase_permission": "PHASE_5_ENGINEERING_ONLY",
    "promotion_gate": "PENDING",
    "MIG-004": "PENDING_PROMOTION",
    "source_execution_manifest_sha256": "2" * 64,
}


@pytest.fixture(autouse=True)
def _authenticated_phase4_handoff(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        generator.runner,
        "authenticate_phase4_handoff",
        lambda *_args, **_kwargs: HANDOFF,
    )


def _write_junit(path: Path, candidate: str, command_id: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            f'<testsuites tests="1" failures="0" errors="0" skipped="0" '
            f'time="0.01" candidate_commit="{candidate}" '
            f'source_command_id="{command_id}">\n'
            f'  <testsuite name="{command_id}" tests="1" failures="0" '
            'errors="0" skipped="0" time="0.01">\n'
            f'    <testcase classname="fixture.{command_id}" name="passes" '
            'time="0.01" />\n'
            "  </testsuite>\n"
            "</testsuites>\n"
        ),
        encoding="utf-8",
    )


def _build_pass_run(tmp_path: Path, candidate: str = CANDIDATE) -> Path:
    runner = generator.runner
    run_root = tmp_path / f"phase5-entry-{candidate[:12]}"
    source_dir = run_root / "source"
    source_dir.mkdir(parents=True)
    manifest = runner._initial_manifest(
        candidate=candidate,
        environment_id="phase5-entry-fixture",
        run_root=run_root,
        phase4_handoff=HANDOFF,
    )
    manifest["verification_started_at"] = "2026-07-22T00:00:00+00:00"
    manifest["verification_finished_at"] = "2026-07-22T00:00:04+00:00"
    commands = runner.load_source_commands()
    records = []
    for offset, command_id in enumerate(runner.COMMAND_ORDER, start=1):
        attempt_dir = run_root / "attempts" / f"{command_id}-01"
        raw_path = attempt_dir / "raw-junit.xml"
        if command_id == "p5_entry_java":
            raw_path = attempt_dir / "raw-surefire" / "TEST-fixture.xml"
        _write_junit(raw_path, candidate, command_id)
        report_path = source_dir / runner.SOURCE_REPORTS[command_id]
        shutil_source = raw_path.read_bytes()
        report_path.write_bytes(shutil_source)
        stdout_path = attempt_dir / "stdout.log"
        stderr_path = attempt_dir / "stderr.log"
        stdout_path.write_text("fixture pass\n", encoding="utf-8")
        stderr_path.write_text("", encoding="utf-8")
        report_suffix = f"p5-entry-{candidate[:12]}-{offset:08x}"
        argv = runner._command_argv_for_source(
            command_id,
            commands[command_id]["command"],
            attempt_dir / "raw-junit.xml",
            report_suffix=report_suffix,
        )
        executed = runner.shared.render_command_argv(argv)
        timestamp = f"2026-07-22T00:00:0{offset}+00:00"
        records.append(
            {
                "id": command_id,
                "candidate_commit": candidate,
                "cwd": commands[command_id]["cwd"],
                "matrix_command": commands[command_id]["command"],
                "matrix_command_sha256": _text_sha(commands[command_id]["command"]),
                "executed_command": executed,
                "executed_argv": argv,
                "executed_command_sha256": _text_sha(executed),
                "started_at": timestamp,
                "finished_at": timestamp,
                "duration_seconds": 0.0,
                "exit_code": 0,
                "environment_sha256": manifest["environment"]["snapshot_sha256"],
                "stdout_path": runner.shared._relative(stdout_path, run_root),
                "stdout_sha256": runner.shared._sha256(stdout_path),
                "stderr_path": runner.shared._relative(stderr_path, run_root),
                "stderr_sha256": runner.shared._sha256(stderr_path),
                "raw_reports": [
                    {
                        "path": runner.shared._relative(raw_path, run_root),
                        "sha256": runner.shared._sha256(raw_path),
                    }
                ],
                "failure_classification": "NONE",
                "accepted": True,
                "report": runner.SOURCE_REPORTS[command_id],
                "report_path": runner.shared._relative(report_path, run_root),
                "report_sha256": runner.shared._sha256(report_path),
                "tests": 1,
                "failures": 0,
                "errors": 0,
                "skipped": 0,
            }
        )
    manifest.update(
        {
            "commands": records,
            "status": "PASS",
            "batch_0": "PASS",
            "contract_gate": "P5.0_AWAITING_ENTRY_EVIDENCE_COMMIT",
            "engineering_execution": "BLOCKED_UNTIL_ENTRY_EVIDENCE_COMMIT",
        }
    )
    manifest_path = run_root / runner.MANIFEST_NAME
    runner._write_manifest(manifest_path, manifest)
    return manifest_path


def _text_sha(value: str) -> str:
    import hashlib

    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _allow_fixture_git_checks(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        generator.shared, "assert_clean_detached_candidate", lambda *_args, **_kwargs: None
    )
    monkeypatch.setattr(
        generator, "assert_base_ancestor", lambda *_args, **_kwargs: None
    )


def test_generator_atomically_writes_the_exact_candidate_bound_entry_bundle(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    _allow_fixture_git_checks(monkeypatch)
    manifest_path = _build_pass_run(tmp_path)
    output = tmp_path / "entry-evidence"

    metrics = generator.generate_entry_evidence(
        release_id=RELEASE_ID,
        candidate_commit=CANDIDATE,
        base_commit=BASE,
        execution_manifest_path=manifest_path,
        output_dir=output,
    )

    assert {path.name for path in output.iterdir()} == {
        "candidate.txt",
        "phase5-entry-execution-manifest.json",
        "static-phase5-entry-junit.xml",
        "python-phase5-entry-junit.xml",
        "java-phase5-entry-junit.xml",
        "frontend-phase5-entry-junit.xml",
        "entry-metrics.json",
        "artifact-sha256.json",
    }
    assert (output / "candidate.txt").read_text(encoding="utf-8") == CANDIDATE + "\n"
    assert metrics["result"] == "PASS_AWAITING_EVIDENCE_COMMIT"
    assert metrics["totals"] == {
        "tests": 4,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
        "time": 0.04,
    }
    assert metrics["entry_decision"] == {
        "engineering_execution": "BLOCKED_UNTIL_THIS_EVIDENCE_COMMIT",
        "entry_effect_after_commit": "P5_0_ENGINEERING_ENTRY_PASS",
        "promotion_gate": "PENDING",
        "MIG-004": "PENDING_PROMOTION",
        "MIG-005": "PENDING_PROMOTION",
        "implementation_allowed_before_commit": False,
    }
    assert metrics["upstream_phase4_checkpoint"] == HANDOFF
    index = json.loads((output / "artifact-sha256.json").read_text(encoding="utf-8"))
    assert index["candidate_commit"] == CANDIDATE
    assert {item["path"] for item in index["artifacts"]} == {
        path.name for path in output.iterdir() if path.name != "artifact-sha256.json"
    }
    assert not output.with_name(".entry-evidence.assembling").exists()


def test_generator_rejects_mixed_report_candidate_even_after_manifest_reseal(
    tmp_path: Path,
) -> None:
    manifest_path = _build_pass_run(tmp_path)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    record = manifest["commands"][0]
    for field in ("raw_reports",):
        for item in record[field]:
            path = manifest_path.parent / item["path"]
            _write_junit(path, "c" * 40, record["id"])
            item["sha256"] = generator.shared._sha256(path)
    report_path = manifest_path.parent / record["report_path"]
    _write_junit(report_path, "c" * 40, record["id"])
    record["report_sha256"] = generator.shared._sha256(report_path)
    generator.runner._write_manifest(manifest_path, manifest)

    with pytest.raises(generator.shared.EvidenceError, match="report binding drifted"):
        generator.load_pass_manifest(manifest_path, CANDIDATE)


@pytest.mark.parametrize("classification", ["UNCLASSIFIED", "PRODUCT", "FIXTURE"])
def test_generator_rejects_unaccepted_or_non_infra_attempts(
    classification: str, tmp_path: Path
) -> None:
    manifest_path = _build_pass_run(tmp_path)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["quarantined_attempts"] = [
        {
            "id": "p5_entry_frontend",
            "failure_classification": classification,
        }
    ]
    generator.runner._write_manifest(manifest_path, manifest)

    with pytest.raises(generator.shared.EvidenceError, match="classified INFRA"):
        generator.load_pass_manifest(manifest_path, CANDIDATE)


def test_generator_rejects_an_old_run_from_another_candidate(tmp_path: Path) -> None:
    manifest_path = _build_pass_run(tmp_path, candidate="c" * 40)

    with pytest.raises(generator.shared.EvidenceError, match="candidate SHA drifted"):
        generator.load_pass_manifest(manifest_path, CANDIDATE)


def test_generator_rejects_reauthenticated_phase4_handoff_drift(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    manifest_path = _build_pass_run(tmp_path)
    monkeypatch.setattr(
        generator.runner,
        "authenticate_phase4_handoff",
        lambda *_args, **_kwargs: {**HANDOFF, "checkpoint_sha256": "9" * 64},
    )

    with pytest.raises(generator.shared.EvidenceError, match="live authentication drifted"):
        generator.load_pass_manifest(manifest_path, CANDIDATE)


def test_generator_rejects_existing_output_without_touching_it(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    _allow_fixture_git_checks(monkeypatch)
    manifest_path = _build_pass_run(tmp_path)
    output = tmp_path / "existing-entry-evidence"
    output.mkdir()
    marker = output / "keep.txt"
    marker.write_text("do not replace\n", encoding="utf-8")

    with pytest.raises(generator.shared.EvidenceError, match="output or staging path exists"):
        generator.generate_entry_evidence(
            release_id=RELEASE_ID,
            candidate_commit=CANDIDATE,
            base_commit=BASE,
            execution_manifest_path=manifest_path,
            output_dir=output,
        )
    assert marker.read_text(encoding="utf-8") == "do not replace\n"


def test_phase5_matrix_declares_the_generator_and_exact_entry_file_set() -> None:
    matrix = yaml.safe_load(
        (ROOT / "plans/phase-5-evidence-pilot-test-batches.yaml").read_text(
            encoding="utf-8"
        )
    )
    execution = matrix["batches"]["P5-BATCH-0"]["execution"]

    assert execution["entry_evidence_generator"] == (
        "scripts/generate_phase5_entry_evidence.py"
    )
    assert execution["entry_evidence_required_files"] == [
        "candidate.txt",
        "phase5-entry-execution-manifest.json",
        "static-phase5-entry-junit.xml",
        "python-phase5-entry-junit.xml",
        "java-phase5-entry-junit.xml",
        "frontend-phase5-entry-junit.xml",
        "entry-metrics.json",
        "artifact-sha256.json",
    ]


def test_entry_evidence_cli_help_is_available() -> None:
    process = subprocess.run(
        [sys.executable, str(ROOT / "scripts/generate_phase5_entry_evidence.py"), "--help"],
        cwd=ROOT,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )

    assert process.returncode == 0, process.stderr
    assert "--execution-manifest" in process.stdout
    assert "--base-commit" in process.stdout
