from __future__ import annotations

import hashlib
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
    "source_execution_manifest_git_sha256": "4" * 64,
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
    path.write_bytes(
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
        ).encode("utf-8")
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
        report_suffix = f"p5-entry-{candidate[:12]}-{offset:08x}"
        if command_id == "p5_entry_java":
            report_suffix = (
                f"p5-entry-{candidate[:12]}-"
                f"{hashlib.sha256(str(attempt_dir).encode('utf-8')).hexdigest()[:8]}"
            )
        raw_path = attempt_dir / "raw-junit.xml"
        _write_junit(raw_path, candidate, command_id)
        raw_record = {
            "path": runner.shared._relative(raw_path, run_root),
            "sha256": runner.shared._sha256(raw_path),
        }
        if command_id == "p5_entry_java":
            digest = raw_record["sha256"]
            retained = attempt_dir / "raw-surefire" / f"0001-{digest[:16]}.xml"
            retained.parent.mkdir()
            raw_path.replace(retained)
            raw_path = retained
            raw_record = {
                "path": runner.shared._relative(raw_path, run_root),
                "sha256": digest,
                "original_name": f"TEST-fixture-{report_suffix}.xml",
            }
        report_path = source_dir / runner.SOURCE_REPORTS[command_id]
        shutil_source = raw_path.read_bytes()
        report_path.write_bytes(shutil_source)
        stdout_path = attempt_dir / "stdout.log"
        stderr_path = attempt_dir / "stderr.log"
        stdout_path.write_text("fixture pass\n", encoding="utf-8")
        stderr_path.write_text("", encoding="utf-8")
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
    assert metrics["execution_environment"] == {
        "environment_id": "phase5-entry-fixture",
        "snapshot_sha256": json.loads(
            manifest_path.read_text(encoding="utf-8")
        )["environment"]["snapshot_sha256"],
    }
    assert all(
        suite["candidate_commit"] == CANDIDATE
        and suite["environment_sha256"]
        == metrics["execution_environment"]["snapshot_sha256"]
        for suite in metrics["source_suites"]
    )
    index = json.loads((output / "artifact-sha256.json").read_text(encoding="utf-8"))
    assert index["candidate_commit"] == CANDIDATE
    assert {item["path"] for item in index["artifacts"]} == {
        path.name for path in output.iterdir() if path.name != "artifact-sha256.json"
    }
    assert all(
        item["sha256"] == generator.shared._sha256(output / item["path"])
        for item in index["artifacts"]
    )
    assert (output / generator.runner.MANIFEST_NAME).read_bytes() == (
        manifest_path.read_bytes()
    )
    assert not output.with_name(".entry-evidence.assembling").exists()


def test_generator_canonicalizes_crlf_manifest_and_git_clean_filter_bytes(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    _allow_fixture_git_checks(monkeypatch)
    manifest_path = _build_pass_run(tmp_path)
    manifest_path.write_bytes(manifest_path.read_bytes().replace(b"\n", b"\r\n"))
    output = tmp_path / "entry-evidence"

    generator.generate_entry_evidence(
        release_id=RELEASE_ID,
        candidate_commit=CANDIDATE,
        base_commit=BASE,
        execution_manifest_path=manifest_path,
        output_dir=output,
    )

    index = json.loads((output / generator.HASH_INDEX_NAME).read_text(encoding="utf-8"))
    indexed = {item["path"]: item for item in index["artifacts"]}
    for path in output.iterdir():
        payload = path.read_bytes()
        assert b"\r" not in payload
        logical_path = (
            Path("test-reports")
            / "temporal-first"
            / RELEASE_ID
            / "phase-5-entry"
            / path.name
        ).as_posix()
        assert generator._git_hash_object(payload) == generator._git_hash_object(
            payload, logical_path=logical_path
        )
        if path.name in indexed:
            assert indexed[path.name]["sha256"] == generator.shared._sha256(path)
            assert indexed[path.name]["bytes"] == path.stat().st_size
    archived_manifest = output / generator.runner.MANIFEST_NAME
    metrics = json.loads((output / generator.METRICS_NAME).read_text(encoding="utf-8"))
    assert metrics["execution_manifest"]["sha256"] == generator.shared._sha256(
        archived_manifest
    )


def test_git_clean_filter_guard_rejects_crlf_evidence(tmp_path: Path) -> None:
    artifact = tmp_path / generator.METRICS_NAME
    artifact.write_bytes(b'{"result":"PASS"}\r\n')

    with pytest.raises(generator.shared.EvidenceError, match="non-LF line endings"):
        generator._assert_git_clean_filter_stable(
            artifact, release_id=RELEASE_ID
        )


@pytest.mark.parametrize(
    ("updates", "message"),
    [
        ({"status": "FAIL", "batch_0": "FAIL"}, "not a PASS"),
        ({"promotion_gate": "PASS"}, "gate or recovery state drifted"),
        ({"MIG-004": "PASS"}, "gate or recovery state drifted"),
        ({"MIG-005": "PASS"}, "gate or recovery state drifted"),
    ],
)
def test_generator_rejects_failed_or_forged_gate_manifest_even_after_reseal(
    updates: dict[str, str], message: str, tmp_path: Path
) -> None:
    manifest_path = _build_pass_run(tmp_path)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest.update(updates)
    generator.runner._write_manifest(manifest_path, manifest)

    with pytest.raises(generator.shared.EvidenceError, match=message):
        generator.load_pass_manifest(manifest_path, CANDIDATE)


def test_generator_rejects_missing_source_report(tmp_path: Path) -> None:
    manifest_path = _build_pass_run(tmp_path)
    missing = manifest_path.parent / "source" / next(
        iter(generator.runner.SOURCE_REPORTS.values())
    )
    missing.unlink()

    with pytest.raises(generator.shared.EvidenceError, match="missing or escapes"):
        generator.load_pass_manifest(manifest_path, CANDIDATE)


def test_generator_rejects_raw_report_path_escape_even_after_reseal(
    tmp_path: Path,
) -> None:
    manifest_path = _build_pass_run(tmp_path)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    outside = tmp_path / "outside.xml"
    record = manifest["commands"][0]
    _write_junit(outside, CANDIDATE, record["id"])
    record["raw_reports"] = [
        {
            "path": "../outside.xml",
            "sha256": generator.shared._sha256(outside),
        }
    ]
    generator.runner._write_manifest(manifest_path, manifest)

    with pytest.raises(generator.shared.EvidenceError, match="missing or escapes"):
        generator.load_pass_manifest(manifest_path, CANDIDATE)


def test_generator_rejects_source_replacement_during_atomic_assembly(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    _allow_fixture_git_checks(monkeypatch)
    manifest_path = _build_pass_run(tmp_path)
    output = tmp_path / "entry-evidence"
    original_snapshot = generator._archive_snapshot
    replaced = False

    def replace_before_snapshot(
        source: Path,
        destination: Path,
        *,
        context: str,
        expected_sha256: str | None = None,
    ) -> tuple[bytes, str]:
        nonlocal replaced
        if expected_sha256 is not None and not replaced:
            replaced = True
            source.write_text("replaced after authentication\n", encoding="utf-8")
        return original_snapshot(
            source,
            destination,
            context=context,
            expected_sha256=expected_sha256,
        )

    monkeypatch.setattr(generator, "_archive_snapshot", replace_before_snapshot)

    with pytest.raises(generator.shared.EvidenceError, match="changed after PASS"):
        generator.generate_entry_evidence(
            release_id=RELEASE_ID,
            candidate_commit=CANDIDATE,
            base_commit=BASE,
            execution_manifest_path=manifest_path,
            output_dir=output,
        )
    assert not output.exists()
    assert not output.with_name(".entry-evidence.assembling").exists()


def test_generator_rejects_archived_report_replacement_before_publish(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    _allow_fixture_git_checks(monkeypatch)
    manifest_path = _build_pass_run(tmp_path)
    output = tmp_path / "entry-evidence"
    original_source_metrics = generator._source_metrics

    def replace_after_metrics(
        manifest: dict, source_dir: Path
    ) -> tuple[list[dict], dict[str, int | float]]:
        rows, totals = original_source_metrics(manifest, source_dir)
        (source_dir / manifest["commands"][0]["report"]).write_bytes(
            b"replaced after snapshot\n"
        )
        return rows, totals

    monkeypatch.setattr(generator, "_source_metrics", replace_after_metrics)

    with pytest.raises(generator.shared.EvidenceError, match="changed during evidence"):
        generator.generate_entry_evidence(
            release_id=RELEASE_ID,
            candidate_commit=CANDIDATE,
            base_commit=BASE,
            execution_manifest_path=manifest_path,
            output_dir=output,
        )
    assert not output.exists()
    assert not output.with_name(".entry-evidence.assembling").exists()


@pytest.mark.parametrize(
    ("artifact_name", "replacement", "message"),
    (
        (generator.METRICS_NAME, b'{}\n', "metrics changed"),
        (
            generator.HASH_INDEX_NAME,
            json.dumps(
                {
                    "schema_version": generator.HASH_INDEX_SCHEMA,
                    "candidate_commit": CANDIDATE,
                    "artifacts": [],
                }
            ).encode("utf-8")
            + b"\n",
            "artifact index changed",
        ),
    ),
)
def test_generator_revalidates_trusted_bundle_after_final_candidate_check(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    artifact_name: str,
    replacement: bytes,
    message: str,
) -> None:
    monkeypatch.setattr(generator, "assert_base_ancestor", lambda *_args: None)
    manifest_path = _build_pass_run(tmp_path)
    output = tmp_path / "entry-evidence"
    checks = 0

    def replace_during_final_candidate_check(*_args: object, **_kwargs: object) -> None:
        nonlocal checks
        checks += 1
        if checks == 2:
            staging = output.with_name(f".{output.name}.assembling")
            (staging / artifact_name).write_bytes(replacement)

    monkeypatch.setattr(
        generator.shared,
        "assert_clean_detached_candidate",
        replace_during_final_candidate_check,
    )

    with pytest.raises(generator.shared.EvidenceError, match=message):
        generator.generate_entry_evidence(
            release_id=RELEASE_ID,
            candidate_commit=CANDIDATE,
            base_commit=BASE,
            execution_manifest_path=manifest_path,
            output_dir=output,
        )

    assert checks == 2
    assert not output.exists()
    assert not output.with_name(".entry-evidence.assembling").exists()


@pytest.mark.parametrize(
    ("mutation", "message"),
    (
        ("metrics", "metrics changed"),
        ("index", "artifact index changed"),
        ("unexpected", "output file set drifted"),
        ("file_type", "output file set drifted"),
    ),
)
def test_generator_revalidates_content_after_final_git_filter_callbacks(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    mutation: str,
    message: str,
) -> None:
    monkeypatch.setattr(generator, "assert_base_ancestor", lambda *_args: None)
    manifest_path = _build_pass_run(tmp_path)
    output = tmp_path / "entry-evidence"
    original_hash_object = generator._git_hash_object
    final_validation = False
    replaced = False
    checks = 0

    def mark_final_candidate_check(*_args: object, **_kwargs: object) -> None:
        nonlocal checks, final_validation
        checks += 1
        final_validation = checks == 2

    def replace_during_final_filter(
        payload: bytes, *, logical_path: str | None = None
    ) -> str:
        nonlocal replaced
        staging = output.with_name(f".{output.name}.assembling")
        if final_validation and not replaced and mutation != "file_type":
            replaced = True
            if mutation == "metrics":
                (staging / generator.METRICS_NAME).write_bytes(b'{}\n')
            elif mutation == "index":
                generator._write_json_lf(
                    staging / generator.HASH_INDEX_NAME,
                    {
                        "schema_version": generator.HASH_INDEX_SCHEMA,
                        "candidate_commit": CANDIDATE,
                        "artifacts": [],
                    },
                )
            else:
                (staging / "unexpected.txt").write_bytes(b"unexpected\n")
        elif (
            final_validation
            and not replaced
            and logical_path is not None
            and logical_path.endswith(f"/{generator.HASH_INDEX_NAME}")
        ):
            replaced = True
            index_path = staging / generator.HASH_INDEX_NAME
            index_path.unlink()
            index_path.mkdir()
        return original_hash_object(payload, logical_path=logical_path)

    monkeypatch.setattr(
        generator.shared,
        "assert_clean_detached_candidate",
        mark_final_candidate_check,
    )
    monkeypatch.setattr(generator, "_git_hash_object", replace_during_final_filter)

    with pytest.raises(generator.shared.EvidenceError, match=message):
        generator.generate_entry_evidence(
            release_id=RELEASE_ID,
            candidate_commit=CANDIDATE,
            base_commit=BASE,
            execution_manifest_path=manifest_path,
            output_dir=output,
        )

    assert replaced is True
    assert not output.exists()
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


def test_generator_rejects_resealed_accepted_java_without_original_name(
    tmp_path: Path,
) -> None:
    manifest_path = _build_pass_run(tmp_path)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    java_record = next(
        record for record in manifest["commands"] if record["id"] == "p5_entry_java"
    )
    java_record["raw_reports"][0].pop("original_name")
    generator.runner._write_manifest(manifest_path, manifest)

    with pytest.raises(
        generator.shared.EvidenceError,
        match="retained Surefire original name is missing",
    ):
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
