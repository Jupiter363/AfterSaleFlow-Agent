from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/generate_phase6_entry_evidence.py"
SPEC = importlib.util.spec_from_file_location("generate_phase6_entry_evidence", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
generator = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = generator
SPEC.loader.exec_module(generator)

runner = generator.runner
CANDIDATE = "a" * 40
BASE = "b" * 40


def _junit(candidate: str, command_id: str) -> bytes:
    return (
        "<?xml version='1.0' encoding='utf-8'?>\n"
        f'<testsuites name="{command_id}" tests="1" failures="0" errors="0" '
        f'skipped="0" time="0.1" candidate_commit="{candidate}" '
        f'source_command_id="{command_id}">\n'
        f'  <testsuite name="{command_id}" tests="1" failures="0" errors="0" '
        'skipped="0" time="0.1">\n'
        f'    <testcase classname="phase6.entry" name="{command_id}" time="0.1" />\n'
        "  </testsuite>\n"
        "</testsuites>\n"
    ).encode("utf-8")


def _green_run(tmp_path: Path) -> tuple[Path, dict[str, object]]:
    run_root = tmp_path / "phase6-entry-run"
    source = run_root / "source"
    source.mkdir(parents=True)
    manifest = runner._initial_manifest(
        candidate=CANDIDATE,
        environment_id="phase6-entry-evidence-test",
        run_root=run_root,
    )
    manifest["environment"] = {
        "environment_id": "phase6-entry-evidence-test",
        "snapshot_sha256": "c" * 64,
    }
    records: list[dict[str, object]] = []
    for command_id in runner.COMMAND_ORDER:
        filename = runner.SOURCE_REPORTS[command_id]
        report = source / filename
        report.write_bytes(_junit(CANDIDATE, command_id))
        records.append(
            {
                "id": command_id,
                "candidate_commit": CANDIDATE,
                "accepted": True,
                "cwd": ".",
                "matrix_command_sha256": "d" * 64,
                "executed_command_sha256": "e" * 64,
                "environment_sha256": "c" * 64,
                "started_at": "2026-07-24T00:00:00.000+00:00",
                "finished_at": "2026-07-24T00:00:00.100+00:00",
                "duration_seconds": 0.1,
                "exit_code": 0,
                "failure_classification": "NONE",
                "report": filename,
                "report_path": f"source/{filename}",
                "report_sha256": runner._sha256(report),
                "tests": 1,
                "failures": 0,
                "errors": 0,
                "skipped": 0,
            }
        )
    manifest["commands"] = records
    manifest["status"] = runner.GREEN_STATUS
    manifest["verification_finished_at"] = "2026-07-24T00:00:01.000+00:00"
    manifest_path = run_root / runner.MANIFEST_NAME
    runner._write_manifest(manifest_path, manifest)
    persisted = json.loads(manifest_path.read_text(encoding="utf-8"))
    return manifest_path, persisted


def test_green_manifest_and_bundle_are_candidate_bound(tmp_path: Path) -> None:
    manifest_path, persisted = _green_run(tmp_path)

    loaded = generator.load_green_manifest(manifest_path, CANDIDATE)
    assert loaded == persisted
    output = tmp_path / "phase-6-entry"
    metrics = generator.assemble_entry_evidence(
        manifest=loaded,
        execution_manifest_path=manifest_path,
        output_dir=output,
        release_id="phase-6-entry-test",
        base_commit=BASE,
        candidate_commit=CANDIDATE,
    )

    assert metrics["candidate_commit"] == CANDIDATE
    assert metrics["base_commit"] == BASE
    assert metrics["result"] == "PASS_AWAITING_EVIDENCE_COMMIT"
    assert metrics["totals"] == {
        "tests": 4,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
        "time": 0.4,
    }
    assert metrics["entry_decision"]["implementation_allowed_before_commit"] is False
    assert metrics["runtime_restrictions"]["formal_hearing_graph_sink"] is False
    assert (output / generator.CANDIDATE_NAME).read_text(encoding="ascii") == (
        CANDIDATE + "\n"
    )
    index = json.loads((output / generator.HASH_INDEX_NAME).read_text(encoding="utf-8"))
    assert index["candidate_commit"] == CANDIDATE
    assert {item["path"] for item in index["artifacts"]} == {
        generator.CANDIDATE_NAME,
        runner.MANIFEST_NAME,
        generator.METRICS_NAME,
        *runner.SOURCE_REPORTS.values(),
    }


def test_manifest_rejects_non_green_or_candidate_drift(tmp_path: Path) -> None:
    manifest_path, persisted = _green_run(tmp_path)
    persisted["status"] = "RUNNING"
    runner._write_manifest(manifest_path, persisted)
    with pytest.raises(runner.EvidenceError, match="not green"):
        generator.load_green_manifest(manifest_path, CANDIDATE)

    manifest_path, _ = _green_run(tmp_path / "second")
    with pytest.raises(runner.EvidenceError, match="candidate SHA drifted"):
        generator.load_green_manifest(manifest_path, "f" * 40)


def test_manifest_rejects_tampered_report_and_extra_source(tmp_path: Path) -> None:
    manifest_path, _ = _green_run(tmp_path)
    source = manifest_path.parent / "source"
    report = source / next(iter(runner.SOURCE_REPORTS.values()))
    report.write_text("tampered", encoding="utf-8")
    with pytest.raises(runner.EvidenceError, match="SHA-256 drifted"):
        generator.load_green_manifest(manifest_path, CANDIDATE)

    manifest_path, _ = _green_run(tmp_path / "second")
    (manifest_path.parent / "source" / "extra.xml").write_text("extra", encoding="utf-8")
    with pytest.raises(runner.EvidenceError, match="contains extras"):
        generator.load_green_manifest(manifest_path, CANDIDATE)


def test_manifest_rejects_migration_or_recovery_gate_drift(tmp_path: Path) -> None:
    manifest_path, persisted = _green_run(tmp_path)
    persisted["MIG-006"] = "PASS"
    runner._write_manifest(manifest_path, persisted)
    with pytest.raises(runner.EvidenceError, match="migration gate drifted"):
        generator.load_green_manifest(manifest_path, CANDIDATE)

    manifest_path, persisted = _green_run(tmp_path / "second")
    persisted["quarantined_attempts_reused"] = True
    runner._write_manifest(manifest_path, persisted)
    with pytest.raises(runner.EvidenceError, match="recovery state drifted"):
        generator.load_green_manifest(manifest_path, CANDIDATE)


def test_bundle_validation_detects_artifact_tamper(tmp_path: Path) -> None:
    manifest_path, persisted = _green_run(tmp_path)
    output = tmp_path / "phase-6-entry"
    metrics = generator.assemble_entry_evidence(
        manifest=persisted,
        execution_manifest_path=manifest_path,
        output_dir=output,
        release_id="phase-6-entry-test",
        base_commit=BASE,
        candidate_commit=CANDIDATE,
    )
    index = json.loads((output / generator.HASH_INDEX_NAME).read_text(encoding="utf-8"))
    (output / generator.CANDIDATE_NAME).write_bytes(("f" * 40 + "\n").encode("ascii"))
    with pytest.raises(runner.EvidenceError, match="candidate binding drifted"):
        generator._validate_bundle(
            output_dir=output,
            candidate=CANDIDATE,
            manifest=persisted,
            metrics=metrics,
            index=index,
            release_id="phase-6-entry-test",
        )


def test_generate_is_atomic_and_rejects_existing_output(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    manifest_path, _ = _green_run(tmp_path)
    output = tmp_path / "evidence"
    monkeypatch.setattr(runner, "assert_candidate_run_directory", lambda *_: None)
    monkeypatch.setattr(runner, "assert_clean_detached_candidate", lambda *_args, **_kw: None)
    monkeypatch.setattr(generator, "assert_base_ancestor", lambda *_: None)

    metrics = generator.generate_entry_evidence(
        release_id="phase-6-entry-test",
        candidate_commit=CANDIDATE,
        base_commit=BASE,
        execution_manifest_path=manifest_path,
        output_dir=output,
    )
    assert metrics["result"] == "PASS_AWAITING_EVIDENCE_COMMIT"
    assert output.is_dir()
    assert not output.with_name(".evidence.assembling").exists()

    with pytest.raises(runner.EvidenceError, match="output or staging path exists"):
        generator.generate_entry_evidence(
            release_id="phase-6-entry-test",
            candidate_commit=CANDIDATE,
            base_commit=BASE,
            execution_manifest_path=manifest_path,
            output_dir=output,
        )
