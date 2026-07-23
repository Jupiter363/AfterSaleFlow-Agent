from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest

from scripts import generate_phase5_wave_a_evidence as generator


CANDIDATE = "a" * 40
BASE = "496d0d459b97000f62742fe064d8ef70956ea419"
RELEASE_ID = "phase-5-wave-a-fixture"


def _task_bindings() -> dict:
    return {
        "schema_version": generator.runner.TASK_BINDINGS_SCHEMA,
        "candidate_commit": CANDIDATE,
        "tasks": [
            {
                "id": task_id,
                "commit": f"{index:x}" * 40,
                "review_partner": reviewer,
                "p0_review": "PASS",
                "t0": {"result": "PASS", "command_ids": list(command_ids)},
            }
            for index, (task_id, (reviewer, command_ids)) in enumerate(
                generator.runner.TASK_REQUIREMENTS.items(), start=1
            )
        ],
    }


def _write_junit(path: Path, candidate: str, command_id: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f'<testsuites tests="1" failures="0" errors="0" skipped="0" '
        f'time="0.01" candidate_commit="{candidate}" '
        f'source_command_id="{command_id}">\n'
        f'  <testsuite name="{command_id}" tests="1" failures="0" '
        'errors="0" skipped="0" time="0.01">\n'
        f'    <testcase classname="fixture.{command_id}" name="passes" '
        'time="0.01" />\n'
        "  </testsuite>\n"
        "</testsuites>\n",
        encoding="utf-8",
        newline="\n",
    )


def _build_pass_run(tmp_path: Path) -> Path:
    runner = generator.runner
    run_root = tmp_path / "phase5-wave-a-aaaaaaaaaaaa"
    source_dir = run_root / "source"
    source_dir.mkdir(parents=True)
    bindings = _task_bindings()
    binding_sha = runner._write_task_bindings(
        run_root / runner.TASK_BINDINGS_NAME, bindings
    )
    manifest = runner._initial_manifest(
        candidate=CANDIDATE,
        environment_id="phase5-wave-a-fixture",
        run_root=run_root,
        task_bindings=bindings,
        task_bindings_sha256=binding_sha,
    )
    manifest["verification_started_at"] = "2026-07-23T00:00:00+00:00"
    manifest["verification_finished_at"] = "2026-07-23T00:00:03+00:00"
    commands = runner.load_source_commands()
    records = []
    for offset, command_id in enumerate(runner.COMMAND_ORDER, start=1):
        attempt_dir = run_root / "attempts" / f"{command_id}-01"
        attempt_dir.mkdir(parents=True)
        stdout = attempt_dir / "stdout.log"
        stderr = attempt_dir / "stderr.log"
        stdout.write_text("fixture pass\n", encoding="utf-8", newline="\n")
        stderr.write_text("", encoding="utf-8", newline="\n")
        report_path = source_dir / runner.SOURCE_REPORTS[command_id]
        _write_junit(report_path, CANDIDATE, command_id)
        raw_path = attempt_dir / "raw-junit.xml"
        _write_junit(raw_path, CANDIDATE, command_id)
        timestamp = f"2026-07-23T00:00:0{offset}+00:00"
        command = commands[command_id]["command"]
        report_suffix = f"p5-wa-{CANDIDATE[:12]}-{offset:08x}"
        executed_argv = runner._command_argv_for_source(
            command_id,
            command,
            raw_path,
            report_suffix=report_suffix,
        )
        raw_record = {
            "path": runner.shared._relative(raw_path, run_root),
            "sha256": runner.shared._sha256(raw_path),
        }
        if command_id == "p5_wave_a_java":
            raw_record["original_name"] = (
                f"TEST-fixture-{report_suffix}.xml"
            )
        executed_command = runner.shared.render_command_argv(executed_argv)
        records.append(
            {
                "id": command_id,
                "candidate_commit": CANDIDATE,
                "cwd": commands[command_id]["cwd"],
                "matrix_command": command,
                "matrix_command_sha256": hashlib.sha256(
                    command.encode("utf-8")
                ).hexdigest(),
                "executed_command": executed_command,
                "executed_argv": executed_argv,
                "executed_command_sha256": hashlib.sha256(
                    executed_command.encode("utf-8")
                ).hexdigest(),
                "started_at": timestamp,
                "finished_at": timestamp,
                "duration_seconds": 0.0,
                "exit_code": 0,
                "environment_sha256": manifest["environment"]["snapshot_sha256"],
                "stdout_path": runner.shared._relative(stdout, run_root),
                "stdout_sha256": runner.shared._sha256(stdout),
                "stderr_path": runner.shared._relative(stderr, run_root),
                "stderr_sha256": runner.shared._sha256(stderr),
                "raw_reports": [raw_record],
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
        commands=records,
        status="PASS",
        batch_1="PASS_AWAITING_EVIDENCE_COMMIT",
        wave_a_barrier="BLOCKED_PENDING_EVIDENCE_COMMIT_AND_CHECKPOINT_ACCEPTANCE",
    )
    manifest_path = run_root / runner.MANIFEST_NAME
    runner._write_manifest(manifest_path, manifest)
    return manifest_path


def _allow_fixture_git_checks(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        generator.shared,
        "assert_clean_detached_candidate",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(generator, "assert_base_ancestor", lambda *_args, **_kwargs: None)
    monkeypatch.setattr(
        generator.runner, "_assert_ancestor", lambda *_args, **_kwargs: None
    )
    monkeypatch.setattr(
        generator,
        "_assert_git_clean_filter_stable",
        lambda *_args, **_kwargs: None,
    )


def test_generator_atomically_writes_exact_eight_file_bundle(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    _allow_fixture_git_checks(monkeypatch)
    manifest_path = _build_pass_run(tmp_path)
    output = tmp_path / "evidence"

    metrics = generator.generate_wave_a_evidence(
        release_id=RELEASE_ID,
        candidate_commit=CANDIDATE,
        base_commit=BASE,
        execution_manifest_path=manifest_path,
        output_dir=output,
    )

    assert {path.name for path in output.iterdir()} == {
        "candidate-commit.txt",
        "task-commit-bindings.json",
        "phase5-wave-a-execution-manifest.json",
        "python-phase5-wave-a-junit.xml",
        "java-phase5-wave-a-junit.xml",
        "static-phase5-wave-a-junit.xml",
        "wave-a-metrics.json",
        "artifact-sha256.json",
    }
    assert metrics["result"] == (
        "PASS_AWAITING_EVIDENCE_COMMIT_AND_CHECKPOINT_ACCEPTANCE"
    )
    assert metrics["totals"] == {
        "tests": 3,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
        "time": 0.03,
    }
    assert metrics["checkpoint_decision"]["wave_b_execution"] == "BLOCKED"
    assert metrics["checkpoint_decision"]["evidence_commit_opens_wave_b"] is False
    assert all(value is False for value in metrics["runtime_restrictions"].values() if isinstance(value, bool))
    index = json.loads((output / generator.HASH_INDEX_NAME).read_text(encoding="utf-8"))
    assert {item["path"] for item in index["artifacts"]} == {
        path.name for path in output.iterdir() if path.name != generator.HASH_INDEX_NAME
    }
    assert not output.with_name(".evidence.assembling").exists()


def test_generator_rejects_tampered_candidate_bound_report(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    _allow_fixture_git_checks(monkeypatch)
    manifest_path = _build_pass_run(tmp_path)
    source = manifest_path.parent / "source" / "python-phase5-wave-a-junit.xml"
    source.write_text(source.read_text(encoding="utf-8").replace(CANDIDATE, "c" * 40))

    with pytest.raises(generator.shared.EvidenceError, match="SHA-256 drifted"):
        generator.generate_wave_a_evidence(
            release_id=RELEASE_ID,
            candidate_commit=CANDIDATE,
            base_commit=BASE,
            execution_manifest_path=manifest_path,
            output_dir=tmp_path / "rejected",
        )


def test_clean_filter_guard_rejects_crlf_and_filter_rewrite(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    artifact = tmp_path / "metrics.json"
    artifact.write_bytes(b'{"result":"PASS"}\r\n')
    with pytest.raises(generator.shared.EvidenceError, match="non-LF"):
        generator._assert_git_clean_filter_stable(
            artifact, release_id=RELEASE_ID
        )

    artifact.write_bytes(b'{"result":"PASS"}\n')
    monkeypatch.setattr(
        generator,
        "_git_hash_object",
        lambda _payload, logical_path=None: "a" * 40
        if logical_path is None
        else "b" * 40,
    )
    with pytest.raises(generator.shared.EvidenceError, match="clean filters"):
        generator._assert_git_clean_filter_stable(
            artifact, release_id=RELEASE_ID
        )


def test_generator_refuses_existing_output_without_mutation(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    _allow_fixture_git_checks(monkeypatch)
    manifest_path = _build_pass_run(tmp_path)
    output = tmp_path / "existing"
    output.mkdir()
    marker = output / "keep.txt"
    marker.write_text("keep\n", encoding="utf-8")

    with pytest.raises(generator.shared.EvidenceError, match="already exists"):
        generator.generate_wave_a_evidence(
            release_id=RELEASE_ID,
            candidate_commit=CANDIDATE,
            base_commit=BASE,
            execution_manifest_path=manifest_path,
            output_dir=output,
        )
    assert marker.read_text(encoding="utf-8") == "keep\n"
