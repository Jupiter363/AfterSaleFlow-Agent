from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/generate_phase7_entry_evidence.py"
SPEC = importlib.util.spec_from_file_location("generate_phase7_entry_evidence", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
generator = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = generator
SPEC.loader.exec_module(generator)

runner = generator.runner
CANDIDATE = "a" * 40
EVIDENCE = "e" * 40
BASE = "d18a1f130a925429e8c2dfd11352cea4ca8673a0"


def _junit(
    candidate: str,
    command_id: str,
    *,
    tests: int = 1,
    failures: int = 0,
    errors: int = 0,
    skipped: int = 0,
) -> bytes:
    cases: list[str] = []
    for index in range(tests):
        if index < failures:
            outcome = "<failure message='fixture failure' />"
        elif index < failures + errors:
            outcome = "<error message='fixture error' />"
        elif index < failures + errors + skipped:
            outcome = "<skipped />"
        else:
            outcome = ""
        cases.append(
            f'    <testcase classname="phase7.entry" name="{command_id}_{index}" '
            f'time="0.1">{outcome}</testcase>\n'
        )
    payload = (
        "<?xml version='1.0' encoding='utf-8'?>\n"
        f'<testsuites name="{command_id}" tests="{tests}" failures="{failures}" '
        f'errors="{errors}" skipped="{skipped}" time="0.1" '
        f'candidate_commit="{candidate}" source_command_id="{command_id}">\n'
        f'  <testsuite name="{command_id}" tests="{tests}" failures="{failures}" '
        f'errors="{errors}" skipped="{skipped}" time="0.1">\n'
    )
    payload += "".join(cases)
    payload += (
        "  </testsuite>\n"
        "</testsuites>\n"
    )
    return payload.encode("utf-8")


def _green_run(tmp_path: Path) -> tuple[Path, dict[str, object]]:
    run_root = tmp_path / "phase7-entry-run"
    source = run_root / "source"
    source.mkdir(parents=True)
    manifest = runner._initial_manifest(
        candidate=CANDIDATE,
        environment_id="phase7-entry-evidence-test",
        run_root=run_root,
    )
    environment_sha256 = manifest["environment"]["snapshot_sha256"]
    contracts = runner._source_contracts(runner.load_matrix())
    records: list[dict[str, object]] = []
    for command_id in runner.COMMAND_ORDER:
        contract = contracts[command_id]
        filename = runner.SOURCE_REPORTS[command_id]
        report = source / filename
        attempt = run_root / "attempts" / f"{command_id}-01"
        attempt.mkdir(parents=True)
        stdout = attempt / "stdout.log"
        stderr = attempt / "stderr.log"
        stdout.write_text("fixture stdout\n", encoding="utf-8", newline="\n")
        stderr.write_text("", encoding="utf-8", newline="\n")
        report_suffix = (
            f"p7-{CANDIDATE[:12]}-"
            f"{runner.hashlib.sha256(str(attempt).encode('utf-8')).hexdigest()[:8]}"
        )
        executed_argv = runner._format_command(
            contract,
            attempt / "raw-junit.xml",
            report_suffix,
            runner.ROOT / contract["cwd"],
        )
        executed_command = runner.render_command_argv(executed_argv)
        raw_reports: list[Path] = []
        if contract["report_kind"] == "SUREFIRE_GLOB":
            raw_dir = attempt / "raw-surefire"
            raw_dir.mkdir()
            for index in range(contract["expected_report_count"]):
                raw_report = raw_dir / f"TEST-Fixture{index}-{report_suffix}.xml"
                quotient, remainder = divmod(
                    contract["minimum_tests"], contract["expected_report_count"]
                )
                raw_report.write_bytes(
                    _junit(
                        CANDIDATE,
                        f"{command_id}_raw{index}",
                        tests=quotient + (1 if index < remainder else 0),
                    )
                )
                raw_reports.append(raw_report)
        else:
            raw_report = attempt / "raw-junit.xml"
            raw_report.write_bytes(
                _junit(CANDIDATE, command_id, tests=contract["minimum_tests"])
            )
            raw_reports.append(raw_report)
        normalized = runner.normalize_source_reports(
            raw_reports,
            report,
            candidate_commit=CANDIDATE,
            command_id=command_id,
        )
        totals = normalized.totals
        records.append(
            {
                "id": command_id,
                "candidate_commit": CANDIDATE,
                "accepted": True,
                "cwd": contract["cwd"],
                "resource_class": contract["resource_class"].lower(),
                "expected_report_count": contract["expected_report_count"],
                "selected_test_file_count": contract["selected_test_file_count"],
                "minimum_tests": contract["minimum_tests"],
                "matrix_command": contract["command"],
                "matrix_command_sha256": runner.hashlib.sha256(
                    contract["command"].encode("utf-8")
                ).hexdigest(),
                "executed_argv": executed_argv,
                "executed_command": executed_command,
                "executed_command_sha256": runner.hashlib.sha256(
                    executed_command.encode("utf-8")
                ).hexdigest(),
                "environment_sha256": environment_sha256,
                "started_at": "2026-07-24T00:00:00.000+00:00",
                "finished_at": "2026-07-24T00:00:00.100+00:00",
                "duration_seconds": 0.1,
                "exit_code": 0,
                "failure_classification": "NONE",
                "report_suffix": report_suffix,
                "raw_report_count": len(raw_reports),
                "raw_reports": [
                    {
                        "path": item.relative_to(run_root).as_posix(),
                        "sha256": runner._sha256(item),
                    }
                    for item in raw_reports
                ],
                "stdout_path": stdout.relative_to(run_root).as_posix(),
                "stdout_sha256": runner._sha256(stdout),
                "stderr_path": stderr.relative_to(run_root).as_posix(),
                "stderr_sha256": runner._sha256(stderr),
                "report": filename,
                "report_path": f"source/{filename}",
                "report_sha256": runner._sha256(report),
                "tests": totals["tests"],
                "failures": totals["failures"],
                "errors": totals["errors"],
                "skipped": totals["skipped"],
            }
        )
    manifest["commands"] = records
    manifest["status"] = runner.GREEN_STATUS
    manifest["verification_finished_at"] = "2026-07-24T00:00:01.000+00:00"
    manifest_path = run_root / runner.MANIFEST_NAME
    runner._write_manifest(manifest_path, manifest)
    persisted = json.loads(manifest_path.read_text(encoding="utf-8"))
    return manifest_path, persisted


@pytest.mark.parametrize("drift", ("argv", "cwd", "command_hash"))
def test_resealed_manifest_rejects_executed_command_binding_drift(
    tmp_path: Path, drift: str
) -> None:
    manifest_path, persisted = _green_run(tmp_path)
    record = persisted["commands"][0]
    if drift == "argv":
        record["executed_argv"] = ["fixture-runner", str(record["id"])]
        record["executed_command"] = runner.render_command_argv(
            record["executed_argv"]
        )
        record["executed_command_sha256"] = runner.hashlib.sha256(
            record["executed_command"].encode("utf-8")
        ).hexdigest()
    elif drift == "cwd":
        record["cwd"] = "frontend"
    else:
        record["matrix_command_sha256"] = "f" * 64
    runner._write_manifest(manifest_path, persisted)

    with pytest.raises(runner.EvidenceError, match="binding|argv|command"):
        generator.load_green_manifest(manifest_path, CANDIDATE)


def test_green_manifest_and_bundle_are_exact_candidate_bound(tmp_path: Path) -> None:
    manifest_path, persisted = _green_run(tmp_path)

    loaded = generator.load_green_manifest(manifest_path, CANDIDATE)
    assert loaded == persisted
    output = tmp_path / "phase-7-entry"
    metrics = generator.assemble_entry_evidence(
        manifest=loaded,
        execution_manifest_path=manifest_path,
        output_dir=output,
        release_id="phase-7-entry-test",
        base_commit=BASE,
        candidate_commit=CANDIDATE,
        changed_paths=["plans/phase-7-outcome-pilot-execution.md"],
    )

    assert metrics["candidate_commit"] == CANDIDATE
    assert metrics["base_commit"] == BASE
    assert metrics["result"] == "PASS_AWAITING_EVIDENCE_COMMIT"
    assert metrics["totals"] == {
        "tests": 127,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
        "time": 12.7,
    }
    assert metrics["entry_decision"]["implementation_allowed_before_commit"] is False
    assert metrics["entry_decision"]["entry_effect_after_commit"] == (
        "P7_0_ENGINEERING_ENTRY_PASS"
    )
    assert metrics["entry_decision"]["next_phase_permission_after_commit"] == (
        "PHASE_7_ENGINEERING_ONLY_AFTER_P7_0_PASS"
    )
    restrictions = metrics["runtime_restrictions"]
    assert restrictions["formal_outcome_graph_sink"] is False
    assert restrictions["temporal_outcome_allocation"] is False
    assert restrictions["real_tool_effects"] is False
    assert (output / generator.CANDIDATE_NAME).read_text(encoding="ascii") == (
        CANDIDATE + "\n"
    )
    index = json.loads(
        (output / generator.HASH_INDEX_NAME).read_text(encoding="utf-8")
    )
    assert index["candidate_commit"] == CANDIDATE
    assert {item["path"] for item in index["artifacts"]} == set(
        generator._indexed_names(loaded)
    )


def test_manifest_rejects_wrong_sha_and_mixed_candidate_reports(
    tmp_path: Path,
) -> None:
    manifest_path, _ = _green_run(tmp_path)
    with pytest.raises(runner.EvidenceError, match="candidate SHA drifted"):
        generator.load_green_manifest(manifest_path, "f" * 40)

    manifest_path, persisted = _green_run(tmp_path / "mixed")
    record = persisted["commands"][1]
    report = manifest_path.parent / str(record["report_path"])
    report.write_bytes(_junit("f" * 40, str(record["id"])))
    record["report_sha256"] = runner._sha256(report)
    runner._write_manifest(manifest_path, persisted)
    with pytest.raises(runner.EvidenceError, match="candidate|binding"):
        generator.load_green_manifest(manifest_path, CANDIDATE)


def test_manifest_rejects_non_green_status_and_non_green_totals(
    tmp_path: Path,
) -> None:
    manifest_path, persisted = _green_run(tmp_path)
    persisted["status"] = "RUNNING"
    runner._write_manifest(manifest_path, persisted)
    with pytest.raises(runner.EvidenceError, match="not green"):
        generator.load_green_manifest(manifest_path, CANDIDATE)

    manifest_path, persisted = _green_run(tmp_path / "failed")
    record = persisted["commands"][0]
    report = manifest_path.parent / str(record["report_path"])
    report.write_bytes(_junit(CANDIDATE, str(record["id"]), failures=1))
    record["report_sha256"] = runner._sha256(report)
    record["failures"] = 1
    runner._write_manifest(manifest_path, persisted)
    with pytest.raises(
        runner.EvidenceError, match="all-pass zero-skip|totals|declares failures"
    ):
        generator.load_green_manifest(manifest_path, CANDIDATE)


@pytest.mark.parametrize(
    ("field", "value"),
    (
        ("contract_gate", "P7.0_PASS"),
        ("implementation_authorized", True),
        ("MIG-006", "PASS"),
        ("MIG-007", "PASS"),
    ),
)
def test_manifest_rejects_forbidden_candidate_scope(
    tmp_path: Path, field: str, value: object
) -> None:
    manifest_path, persisted = _green_run(tmp_path)
    persisted[field] = value
    runner._write_manifest(manifest_path, persisted)

    with pytest.raises(
        runner.EvidenceError, match="gate|scope|drifted|authorized|not green"
    ):
        generator.load_green_manifest(manifest_path, CANDIDATE)


def test_manifest_rejects_report_tampering_extra_source_and_recovery_reuse(
    tmp_path: Path,
) -> None:
    manifest_path, _ = _green_run(tmp_path)
    source = manifest_path.parent / "source"
    report = source / next(iter(runner.SOURCE_REPORTS.values()))
    report.write_text("tampered", encoding="utf-8")
    with pytest.raises(runner.EvidenceError, match="SHA-256 drifted"):
        generator.load_green_manifest(manifest_path, CANDIDATE)

    manifest_path, _ = _green_run(tmp_path / "extra")
    (manifest_path.parent / "source" / "extra.xml").write_text(
        "extra", encoding="utf-8"
    )
    with pytest.raises(runner.EvidenceError, match="contains extras"):
        generator.load_green_manifest(manifest_path, CANDIDATE)

    manifest_path, persisted = _green_run(tmp_path / "reuse")
    persisted["quarantined_attempts_reused"] = True
    runner._write_manifest(manifest_path, persisted)
    with pytest.raises(runner.EvidenceError, match="recovery state drifted"):
        generator.load_green_manifest(manifest_path, CANDIDATE)


def test_bundle_validation_detects_indexed_artifact_tamper(tmp_path: Path) -> None:
    manifest_path, persisted = _green_run(tmp_path)
    output = tmp_path / "phase-7-entry"
    metrics = generator.assemble_entry_evidence(
        manifest=persisted,
        execution_manifest_path=manifest_path,
        output_dir=output,
        release_id="phase-7-entry-test",
        base_commit=BASE,
        candidate_commit=CANDIDATE,
        changed_paths=["plans/phase-7-outcome-pilot-execution.md"],
    )
    index = json.loads(
        (output / generator.HASH_INDEX_NAME).read_text(encoding="utf-8")
    )
    (output / generator.CANDIDATE_NAME).write_bytes(
        ("f" * 40 + "\n").encode("ascii")
    )
    with pytest.raises(runner.EvidenceError, match="candidate binding drifted"):
        generator._validate_bundle(
            output_dir=output,
            candidate=CANDIDATE,
            manifest=persisted,
            metrics=metrics,
            index=index,
            release_id="phase-7-entry-test",
        )


def _bypass_candidate_authority(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(runner, "_assert_upstream_authority", lambda *_: None)
    monkeypatch.setattr(
        generator,
        "assert_contract_only_candidate",
        lambda *_: ["plans/phase-7-outcome-pilot-execution.md"],
    )
    monkeypatch.setattr(runner, "_matrix_at_candidate", lambda *_: runner.load_matrix())


def test_post_commit_verifier_rejects_wrong_parent(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _bypass_candidate_authority(monkeypatch)
    monkeypatch.setattr(
        generator,
        "_git_text",
        lambda *_: f"{EVIDENCE} {'f' * 40}\n",
    )

    with pytest.raises(runner.EvidenceError, match="candidate as its sole parent"):
        generator.verify_evidence_commit(
            evidence_commit=EVIDENCE,
            candidate_commit=CANDIDATE,
            release_id="phase-7-entry-test",
        )


def test_post_commit_verifier_rejects_extra_non_evidence_change(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    _bypass_candidate_authority(monkeypatch)
    release_id = "phase-7-entry-test"
    prefix = f"test-reports/temporal-first/{release_id}/phase-7-entry"
    manifest_path, manifest = _green_run(tmp_path)
    output = tmp_path / "extra-evidence"
    generator.assemble_entry_evidence(
        manifest=manifest,
        execution_manifest_path=manifest_path,
        output_dir=output,
        release_id=release_id,
        base_commit=BASE,
        candidate_commit=CANDIDATE,
        changed_paths=["plans/phase-7-outcome-pilot-execution.md"],
    )
    committed = {
        f"{prefix}/{path.relative_to(output).as_posix()}": path.read_bytes()
        for path in output.rglob("*")
        if path.is_file()
    }
    candidate_blobs = {
        item["path"]: generator._git_bytes("show", f"HEAD:{item['path']}")
        for item in manifest["environment"]["dependency_manifests"]
    }

    def git_text(*arguments: str) -> str:
        if arguments[0] == "rev-list":
            return f"{EVIDENCE} {CANDIDATE}\n"
        assert arguments[0] == "diff-tree"
        records = [f"A\t{path}" for path in sorted(committed)]
        records.append("M\tjava-api-service/src/main/resources/application.yml")
        return "\n".join(records) + "\n"

    monkeypatch.setattr(generator, "_git_text", git_text)
    monkeypatch.setattr(
        generator,
        "_git_bytes",
        lambda *_args: committed[_args[1].split(":", 1)[1]],
    )
    with pytest.raises(
        runner.EvidenceError,
        match="only add its immutable evidence bundle|file set drifted",
    ):
        generator.verify_evidence_commit(
            evidence_commit=EVIDENCE,
            candidate_commit=CANDIDATE,
            release_id=release_id,
        )


def test_post_commit_verifier_accepts_direct_child_evidence_only_commit(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    manifest_path, manifest = _green_run(tmp_path)
    release_id = "phase-7-entry-test"
    output = tmp_path / "committed-evidence"
    generator.assemble_entry_evidence(
        manifest=manifest,
        execution_manifest_path=manifest_path,
        output_dir=output,
        release_id=release_id,
        base_commit=BASE,
        candidate_commit=CANDIDATE,
        changed_paths=["plans/phase-7-outcome-pilot-execution.md"],
    )
    _bypass_candidate_authority(monkeypatch)
    prefix = f"test-reports/temporal-first/{release_id}/phase-7-entry"
    committed = {
        f"{prefix}/{path.relative_to(output).as_posix()}": path.read_bytes()
        for path in output.rglob("*")
        if path.is_file()
    }
    candidate_blobs = {
        item["path"]: generator._git_bytes("show", f"HEAD:{item['path']}")
        for item in manifest["environment"]["dependency_manifests"]
    }

    def git_text(*arguments: str) -> str:
        if arguments[0] == "rev-list":
            return f"{EVIDENCE} {CANDIDATE}\n"
        assert arguments[0] == "diff-tree"
        return "".join(f"A\t{path}\n" for path in sorted(committed))

    def git_bytes(*arguments: str) -> bytes:
        assert arguments[0] == "show"
        _, path = arguments[1].split(":", 1)
        if path in committed:
            return committed[path]
        return candidate_blobs[path]

    monkeypatch.setattr(generator, "_git_text", git_text)
    monkeypatch.setattr(generator, "_git_bytes", git_bytes)
    verified = generator.verify_evidence_commit(
        evidence_commit=EVIDENCE,
        candidate_commit=CANDIDATE,
        release_id=release_id,
    )

    assert verified["status"] == "EVIDENCE_COMMIT_VERIFIED"
    assert verified["sole_parent_verified"] is True
    assert verified["entry_effect"] == "P7_0_ENGINEERING_ENTRY_PASS"
    assert verified["next_phase_permission"] == (
        "PHASE_7_ENGINEERING_ONLY_AFTER_P7_0_PASS"
    )
    assert verified["MIG-006"] == "PENDING_PROMOTION"
    assert verified["MIG-007"] == "PENDING_PROMOTION"


def test_post_commit_verifier_rejects_fake_minima_metrics_and_contradictory_grants(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    manifest_path, manifest = _green_run(tmp_path)
    release_id = "phase-7-entry-negative-test"
    output = tmp_path / "negative-evidence"
    generator.assemble_entry_evidence(
        manifest=manifest,
        execution_manifest_path=manifest_path,
        output_dir=output,
        release_id=release_id,
        base_commit=BASE,
        candidate_commit=CANDIDATE,
        changed_paths=["plans/phase-7-outcome-pilot-execution.md"],
    )
    _bypass_candidate_authority(monkeypatch)
    prefix = f"test-reports/temporal-first/{release_id}/phase-7-entry"
    committed = {
        f"{prefix}/{path.relative_to(output).as_posix()}": path.read_bytes()
        for path in output.rglob("*")
        if path.is_file()
    }
    baseline = dict(committed)
    candidate_blobs = {
        item["path"]: generator._git_bytes("show", f"HEAD:{item['path']}")
        for item in manifest["environment"]["dependency_manifests"]
    }

    def git_text(*arguments: str) -> str:
        if arguments[0] == "rev-list":
            return f"{EVIDENCE} {CANDIDATE}\n"
        assert arguments[0] == "diff-tree"
        return "".join(f"A\t{path}\n" for path in sorted(committed))

    def git_bytes(*arguments: str) -> bytes:
        assert arguments[0] == "show"
        _, path = arguments[1].split(":", 1)
        if path in committed:
            return committed[path]
        return candidate_blobs[path]

    monkeypatch.setattr(generator, "_git_text", git_text)
    monkeypatch.setattr(generator, "_git_bytes", git_bytes)

    static_id = "static_phase7_entry"
    static_name = runner.SOURCE_REPORTS[static_id]
    static_path = f"{prefix}/{static_name}"
    manifest_key = f"{prefix}/{runner.MANIFEST_NAME}"
    fake_report = _junit(CANDIDATE, static_id, tests=1)
    fake_manifest = json.loads(baseline[manifest_key])
    static_record = next(
        item for item in fake_manifest["commands"] if item["id"] == static_id
    )
    static_record["report_sha256"] = runner.hashlib.sha256(fake_report).hexdigest()
    static_record["tests"] = 1
    runner.seal_execution_manifest(fake_manifest)
    committed[static_path] = fake_report
    committed[manifest_key] = generator._json_lf_bytes(fake_manifest)
    with pytest.raises(
        runner.EvidenceError,
        match="minimum|totals|record drifted|does not match raw provenance",
    ):
        generator.verify_evidence_commit(
            evidence_commit=EVIDENCE,
            candidate_commit=CANDIDATE,
            release_id=release_id,
        )

    metrics_key = f"{prefix}/{generator.METRICS_NAME}"
    for mutation in ("totals", "source_suites", "decision_grant", "traffic_grant"):
        committed.clear()
        committed.update(baseline)
        metrics = json.loads(baseline[metrics_key])
        if mutation == "totals":
            metrics["totals"]["tests"] = 1
        elif mutation == "source_suites":
            metrics["source_suites"] = metrics["source_suites"][:-1]
        elif mutation == "decision_grant":
            metrics["entry_decision"]["MIG_006"] = "PASS"
        else:
            metrics["runtime_restrictions"]["production_traffic_allowed"] = True
        committed[metrics_key] = generator._json_lf_bytes(metrics)
        with pytest.raises(runner.EvidenceError, match="metrics|gate|totals|source"):
            generator.verify_evidence_commit(
                evidence_commit=EVIDENCE,
                candidate_commit=CANDIDATE,
                release_id=release_id,
            )


def test_generate_is_atomic_and_rejects_output_collision(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    manifest_path, _ = _green_run(tmp_path)
    output = tmp_path / "evidence"
    monkeypatch.setattr(runner, "assert_candidate_run_directory", lambda *_: None)
    monkeypatch.setattr(
        runner, "assert_clean_detached_candidate", lambda *_args, **_kwargs: None
    )
    monkeypatch.setattr(runner, "_assert_upstream_authority", lambda *_: None)
    monkeypatch.setattr(
        generator,
        "assert_contract_only_candidate",
        lambda *_: ["plans/phase-7-outcome-pilot-execution.md"],
    )

    metrics = generator.generate_entry_evidence(
        release_id="phase-7-entry-test",
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
            release_id="phase-7-entry-test",
            candidate_commit=CANDIDATE,
            base_commit=BASE,
            execution_manifest_path=manifest_path,
            output_dir=output,
        )
