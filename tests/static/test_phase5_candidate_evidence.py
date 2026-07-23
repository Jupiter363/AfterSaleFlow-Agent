from __future__ import annotations

import hashlib
import json
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest

from scripts import generate_phase5_candidate_evidence as generator


runner = generator.runner
CANDIDATE = "a" * 40
BASE = "b" * 40
RELEASE_ID = "phase-5-candidate-fixture"


def _case(source: str, classname: str, name: str) -> generator.TestCase:
    element = ET.Element(
        "testcase", {"classname": classname, "name": name, "time": "0.001"}
    )
    return generator.TestCase(
        source=source,
        suite=classname,
        classname=classname,
        name=name,
        duration=0.001,
        status="passed",
        element=element,
    )


def _representative(pattern: str) -> str:
    value = pattern.replace("*", "fixture").replace("?", "x")
    while "fixturefixture" in value:
        value = value.replace("fixturefixture", "fixture")
    return value


def _source_cases(
    command_id: str,
    filename: str,
    *,
    omit_selector: tuple[str, str] | None,
) -> list[generator.TestCase]:
    commands = runner.focused_commands()
    argv = commands[command_id]["argv"]
    values: dict[tuple[str, str], generator.TestCase] = {}

    def add(classname: str, name: str) -> None:
        values[(classname, name)] = _case(filename, classname, name)

    if command_id in {generator.PYTHON, generator.STATIC}:
        for index, selector in enumerate(runner._pytest_selectors(argv)):
            classname = generator.trusted._path_classname(selector)
            if not selector.endswith(".py"):
                classname += ".fixture"
            add(classname, f"test_source_scope_{index}")
    elif command_id == generator.FRONTEND:
        for index, selector in enumerate(runner._frontend_selectors(argv)):
            add(generator.trusted._path_classname(selector, frontend=True), f"source scope {index}")
    else:
        for classname in runner._java_classes(argv):
            add(f"fixture.{classname}", "sourceScope")

    mappings = {**generator.BASELINE_SELECTORS, **generator.CHECK_SELECTORS}
    for entries in mappings.values():
        for mapped_command, selector in entries:
            if mapped_command != command_id or (mapped_command, selector) == omit_selector:
                continue
            class_pattern, _, name_pattern = selector.partition("#")
            add(_representative(class_pattern), _representative(name_pattern))
    return list(values.values())


def _build_pass_run(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    *,
    omit_selector: tuple[str, str] | None = None,
) -> Path:
    repository = tmp_path / "repo"
    repository.mkdir()
    dependency = repository / "dep.lock"
    dependency.write_text("fixture\n", encoding="utf-8", newline="\n")
    run_root = repository / ".codex-run" / "phase5-candidate"
    (run_root / "source").mkdir(parents=True)
    monkeypatch.setattr(runner, "ROOT", repository)
    monkeypatch.setattr(runner, "DEPENDENCY_MANIFESTS", ("dep.lock",))
    environment = {
        "environment_id": "phase5-candidate-fixture",
        "captured_at": "2026-07-23T00:00:00+00:00",
        "dependency_manifests": [
            {"path": "dep.lock", "sha256": runner._sha256(dependency)}
        ],
    }
    environment["snapshot_sha256"] = runner._json_sha256(environment)
    commands = runner.focused_commands()
    records = []
    for index, command_id in enumerate(runner.COMMAND_ORDER, start=1):
        attempt_dir = run_root / "attempts" / f"{command_id}-01"
        attempt_dir.mkdir(parents=True)
        stdout = attempt_dir / "stdout.log"
        stderr = attempt_dir / "stderr.log"
        stdout.write_text("pass\n", encoding="utf-8", newline="\n")
        stderr.write_text("", encoding="utf-8", newline="\n")
        suffix = f"p5-{CANDIDATE[:12]}-{index:08x}"
        raw = (
            attempt_dir / "raw-surefire" / f"TEST-fixture-{suffix}.xml"
            if command_id == generator.JAVA
            else attempt_dir / "raw-junit.xml"
        )
        raw.parent.mkdir(parents=True, exist_ok=True)
        filename = runner.SOURCE_REPORTS[command_id]
        cases = _source_cases(
            command_id, filename, omit_selector=omit_selector
        )
        generator.write_junit(
            raw,
            name=f"raw-{command_id}",
            cases=cases,
            candidate_commit=CANDIDATE,
            command_id=command_id,
        )
        report = run_root / "source" / filename
        runner.normalize_source_reports(
            [raw], report, candidate_commit=CANDIDATE, command_id=command_id
        )
        executed_argv = runner._command_argv_for_source(
            command_id,
            commands[command_id]["argv"],
            attempt_dir / "raw-junit.xml",
            report_suffix=suffix,
        )
        executed = generator.trusted.render_command_argv(executed_argv)
        totals = generator.parse_junit(report).totals
        records.append(
            {
                "id": command_id,
                "candidate_commit": CANDIDATE,
                "cwd": commands[command_id]["cwd"],
                "matrix_command": commands[command_id]["command"],
                "matrix_command_sha256": hashlib.sha256(
                    commands[command_id]["command"].encode("utf-8")
                ).hexdigest(),
                "executed_command": executed,
                "executed_argv": executed_argv,
                "executed_command_sha256": hashlib.sha256(
                    executed.encode("utf-8")
                ).hexdigest(),
                "started_at": f"2026-07-23T00:00:0{index}+00:00",
                "finished_at": f"2026-07-23T00:00:0{index}+00:00",
                "duration_seconds": 0.0,
                "exit_code": 0,
                "environment_sha256": environment["snapshot_sha256"],
                "stdout_path": runner.process_runner._relative(stdout, run_root),
                "stdout_sha256": runner._sha256(stdout),
                "stderr_path": runner.process_runner._relative(stderr, run_root),
                "stderr_sha256": runner._sha256(stderr),
                "raw_reports": [
                    {
                        "path": runner.process_runner._relative(raw, run_root),
                        "sha256": runner._sha256(raw),
                    }
                ],
                "failure_classification": "NONE",
                "accepted": True,
                "report": filename,
                "report_path": f"source/{filename}",
                "report_sha256": runner._sha256(report),
                **{
                    field: totals[field]
                    for field in ("tests", "failures", "errors", "skipped")
                },
            }
        )
    manifest = {
        "schema_version": runner.SCHEMA_VERSION,
        "phase": 5,
        "batch": runner.BATCH_ID,
        "candidate_commit": CANDIDATE,
        "attempt_id": run_root.name,
        "status": "PASS",
        "verification_started_at": "2026-07-23T00:00:00+00:00",
        "verification_finished_at": "2026-07-23T00:00:10+00:00",
        "environment": environment,
        "commands": records,
        "quarantined_attempts": [],
        "pending_failure": None,
        "quarantined_attempts_reused": False,
        "runtime_restrictions": runner.RUNTIME_RESTRICTIONS,
        **runner.PENDING_GATES,
    }
    path = run_root / runner.MANIFEST_NAME
    runner._write_manifest(path, manifest)
    return path


def _allow_fixture_git(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        generator.trusted, "assert_clean_detached_candidate", lambda *_args, **_kwargs: None
    )
    monkeypatch.setattr(generator.trusted, "assert_base_ancestor", lambda *_args, **_kwargs: None)
    monkeypatch.setattr(generator, "_change_summary", lambda *_args, **_kwargs: {"files": 1})
    monkeypatch.setattr(generator, "_assert_git_clean_filter_stable", lambda *_args, **_kwargs: None)


def _generate(
    monkeypatch: pytest.MonkeyPatch, manifest_path: Path, output: Path
) -> dict:
    _allow_fixture_git(monkeypatch)
    return generator.generate_evidence(
        release_id=RELEASE_ID,
        candidate_commit=CANDIDATE,
        base_commit=BASE,
        engineering_started_at="2026-07-23T00:00:00+00:00",
        execution_manifest_path=manifest_path,
        output_dir=output,
    )


def test_generator_writes_authenticated_sixteen_file_bundle_and_pending_gates(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    manifest_path = _build_pass_run(monkeypatch, tmp_path)
    output = tmp_path / "evidence"

    metrics = _generate(monkeypatch, manifest_path, output)

    assert {path.name for path in output.iterdir()} == generator.EXPECTED_FILES
    assert metrics["status"] == generator.PASS_STATUS
    assert metrics["runtime_restrictions"]["real_case_shadow"] == "forbidden"
    index = json.loads((output / generator.HASH_INDEX_NAME).read_text(encoding="utf-8"))
    assert {item["path"] for item in index["artifacts"]} == (
        generator.EXPECTED_FILES - {generator.HASH_INDEX_NAME}
    )
    assert runner.MANIFEST_NAME in {item["path"] for item in index["artifacts"]}
    checks = json.loads((output / "check-id-coverage.json").read_text(encoding="utf-8"))
    baselines = json.loads(
        (output / "baseline-id-coverage.json").read_text(encoding="utf-8")
    )
    assert checks["all_required_ids_mapped"] is True
    assert baselines["all_required_ids_mapped"] is True
    assert not any(
        binding["selector"].endswith("#*")
        for document, field in ((checks, "checks"), (baselines, "baselines"))
        for row in document[field]
        for binding in row["bindings"]
    )
    external = json.loads((output / "external-gates.json").read_text(encoding="utf-8"))
    assert external["unified_checkpoint"]["executed"] is False
    assert external["promotion_gates"] == {
        "MIG-004": "PENDING_PROMOTION",
        "MIG-005": "PENDING_PROMOTION",
    }


def test_generator_rejects_missing_explicit_graph_016_case(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    missing = generator.CHECK_SELECTORS["GRAPH-016"][0]
    manifest_path = _build_pass_run(monkeypatch, tmp_path, omit_selector=missing)
    _allow_fixture_git(monkeypatch)

    with pytest.raises(generator.EvidenceError, match="GRAPH-016: evidence selector did not run"):
        generator.assemble_evidence(
            matrix=generator.load_matrix(),
            output_dir=tmp_path / "rejected",
            release_id=RELEASE_ID,
            base_commit=BASE,
            candidate_commit=CANDIDATE,
            engineering_started_at="2026-07-23T00:00:00+00:00",
            execution_manifest_path=manifest_path,
        )


def test_generator_detects_source_toctou_after_authenticated_snapshot(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    manifest_path = _build_pass_run(monkeypatch, tmp_path)
    _allow_fixture_git(monkeypatch)
    original = generator.consume_source_reports

    def mutate_after_capture(**kwargs):
        source = kwargs["source_dir"] / runner.SOURCE_REPORTS[runner.COMMAND_ORDER[0]]
        source.write_bytes(source.read_bytes() + b"\n")
        return original(**kwargs)

    monkeypatch.setattr(generator, "consume_source_reports", mutate_after_capture)
    with pytest.raises(generator.EvidenceError, match="changed during evidence assembly"):
        generator.assemble_evidence(
            matrix=generator.load_matrix(),
            output_dir=tmp_path / "rejected",
            release_id=RELEASE_ID,
            base_commit=BASE,
            candidate_commit=CANDIDATE,
            engineering_started_at="2026-07-23T00:00:00+00:00",
            execution_manifest_path=manifest_path,
        )


def test_generator_detects_staged_document_tamper_before_index(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    manifest_path = _build_pass_run(monkeypatch, tmp_path)
    _allow_fixture_git(monkeypatch)
    original = generator._write_json_lf

    def tamper(path: Path, value: object) -> None:
        original(path, value)
        if path.name == "check-id-coverage.json":
            path.write_bytes(b"{}\n")

    monkeypatch.setattr(generator, "_write_json_lf", tamper)
    with pytest.raises(generator.EvidenceError, match="staged Phase 5 document"):
        generator.assemble_evidence(
            matrix=generator.load_matrix(),
            output_dir=tmp_path / "rejected",
            release_id=RELEASE_ID,
            base_commit=BASE,
            candidate_commit=CANDIDATE,
            engineering_started_at="2026-07-23T00:00:00+00:00",
            execution_manifest_path=manifest_path,
        )


def test_generator_refuses_existing_output_without_mutation(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    manifest_path = _build_pass_run(monkeypatch, tmp_path)
    output = tmp_path / "existing"
    output.mkdir()
    marker = output / "keep.txt"
    marker.write_text("keep\n", encoding="utf-8")
    _allow_fixture_git(monkeypatch)

    with pytest.raises(generator.EvidenceError, match="already exists"):
        generator.generate_evidence(
            release_id=RELEASE_ID,
            candidate_commit=CANDIDATE,
            base_commit=BASE,
            engineering_started_at="2026-07-23T00:00:00+00:00",
            execution_manifest_path=manifest_path,
            output_dir=output,
        )
    assert marker.read_text(encoding="utf-8") == "keep\n"
