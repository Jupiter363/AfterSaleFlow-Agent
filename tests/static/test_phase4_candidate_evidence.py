from __future__ import annotations

import copy
import importlib.util
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/generate_phase4_candidate_evidence.py"
SPEC = importlib.util.spec_from_file_location(
    "generate_phase4_candidate_evidence", SCRIPT
)
assert SPEC is not None and SPEC.loader is not None
evidence = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = evidence
SPEC.loader.exec_module(evidence)

CANDIDATE = "a" * 40
BASE = "b" * 40


def _case(
    *,
    source: str,
    classname: str,
    name: str,
    status: str = "passed",
    with_output: bool = False,
) -> evidence.TestCase:
    element = ET.Element(
        "testcase", {"classname": classname, "name": name, "time": "0.001"}
    )
    if status != "passed":
        ET.SubElement(element, status)
    if with_output:
        ET.SubElement(element, "system-out").text = "synthetic diagnostic"
    return evidence.TestCase(
        source=source,
        suite=classname,
        classname=classname,
        name=name,
        duration=0.001,
        status=status,
        element=element,
    )


def _selector_case(filename: str, selector: str) -> evidence.TestCase:
    class_pattern, separator, name_pattern = selector.partition("#")
    assert separator
    classname = class_pattern.replace("*", "com.example.").replace("?", "x")
    if filename == "frontend-phase4-junit.xml":
        classname = classname.removeprefix("frontend/")
    name = name_pattern.replace("*", "fixture").replace("?", "x")
    return _case(source=filename, classname=classname, name=name)


def _path_case(filename: str, selector: str, index: int) -> evidence.TestCase:
    frontend = filename == "frontend-phase4-junit.xml"
    prefix = evidence._path_classname(selector, frontend=frontend)
    classname = prefix
    if not frontend and not selector.endswith(".py"):
        classname += ".test_fixture"
    return _case(
        source=filename,
        classname=classname,
        name=f"test_source_scope_{index}",
        with_output=index == 0,
    )


def _fixture_source_reports(
    tmp_path: Path,
    matrix: dict,
    policy: dict,
    *,
    candidate: str = CANDIDATE,
) -> Path:
    source_dir = tmp_path / "source"
    source_dir.mkdir(parents=True)
    commands = evidence.focused_commands(matrix)
    cases: dict[str, dict[tuple[str, str], evidence.TestCase]] = {
        filename: {} for filename in evidence.SOURCE_REPORTS.values()
    }

    def add(filename: str, case: evidence.TestCase) -> None:
        cases[filename][(case.classname, case.name)] = case

    for command_id, filename in evidence.SOURCE_REPORTS.items():
        command = commands[command_id]["command"]
        if command_id in {"python_phase_4", "static_phase_4"}:
            selectors = evidence.PYTEST_PATH_PATTERN.findall(command)
            for index, selector in enumerate(selectors):
                add(filename, _path_case(filename, selector, index))
        elif command_id == "frontend_phase_4":
            selectors = evidence.FRONTEND_PATH_PATTERN.findall(command)
            for index, selector in enumerate(selectors):
                add(filename, _path_case(filename, selector, index))
        else:
            for classname in evidence._deduplicated_java_classes(matrix):
                add(
                    filename,
                    _case(
                        source=filename,
                        classname=f"com.example.{classname}",
                        name="sourceScope",
                    ),
                )

    mappings = {**policy["overrides"], **policy["baseline_overrides"]}
    for mapping in mappings.values():
        for selector in mapping["test_selectors"]:
            if selector.startswith("tests.static."):
                filename = "static-phase4-junit.xml"
            elif selector.startswith("tests."):
                filename = "python-phase4-junit.xml"
            elif selector.startswith("frontend/"):
                filename = "frontend-phase4-junit.xml"
            else:
                filename = "java-phase4-junit.xml"
            add(filename, _selector_case(filename, selector))

    filename_to_command = {
        filename: command_id
        for command_id, filename in evidence.SOURCE_REPORTS.items()
    }
    for filename, values in cases.items():
        evidence.write_junit(
            source_dir / filename,
            name=filename.removesuffix(".xml"),
            cases=list(values.values()),
            candidate_commit=candidate,
            command_id=filename_to_command[filename],
        )
    return source_dir


def _fixture_execution_manifest(
    tmp_path: Path, matrix: dict, source_dir: Path
) -> dict:
    commands = evidence.focused_commands(matrix)
    records = []
    environment = {
        "environment_id": "phase4-fixture",
        "captured_at": "2026-07-22T00:00:00+00:00",
        "host": {"system": "fixture", "release": "fixture", "machine": "fixture"},
        "tools": {
            name: {"available": True, "version": "fixture"}
            for name in ("python", "git", "java", "node")
        },
        "dependency_manifests": [],
    }
    environment["snapshot_sha256"] = evidence._json_sha256(environment)
    for command_id, filename in evidence.SOURCE_REPORTS.items():
        item = commands[command_id]
        command = item["command"]
        raw_path = tmp_path / "attempts" / command_id / "raw-junit.xml"
        if command_id in {"python_phase_4", "static_phase_4"}:
            executed_argv = [
                *evidence._split_approved_command(command),
                f"--junitxml={raw_path}",
            ]
        elif command_id == "frontend_phase_4":
            executed_argv = [
                *evidence._split_approved_command(command),
                "--reporter=junit",
                f"--outputFile={raw_path}",
            ]
        else:
            approved = evidence._split_approved_command(command)
            executed_argv = [
                *approved[:-1],
                "-Dsurefire.reportNameSuffix=p4-aaaaaaaaaaaa-12345678",
                "test",
            ]
        executed = evidence.render_command_argv(executed_argv)
        records.append(
            {
                "id": command_id,
                "candidate_commit": CANDIDATE,
                "cwd": item["cwd"],
                "matrix_command": command,
                "matrix_command_sha256": evidence.hashlib.sha256(
                    command.encode("utf-8")
                ).hexdigest(),
                "executed_command": executed,
                "executed_argv": executed_argv,
                "executed_command_sha256": evidence.hashlib.sha256(
                    executed.encode("utf-8")
                ).hexdigest(),
                "started_at": "2026-07-22T00:00:00+00:00",
                "finished_at": "2026-07-22T00:01:00+00:00",
                "duration_seconds": 60.0,
                "exit_code": 0,
                "environment_sha256": environment["snapshot_sha256"],
                "stdout_path": f"logs/{command_id}.stdout.log",
                "stdout_sha256": "0" * 64,
                "stderr_path": f"logs/{command_id}.stderr.log",
                "stderr_sha256": "0" * 64,
                "raw_reports": [],
                "failure_classification": "NONE",
                "accepted": True,
                "report": filename,
                "report_path": f"source/{filename}",
                "report_sha256": evidence._sha256(source_dir / filename),
                **{
                    field: evidence.parse_junit(source_dir / filename).totals[field]
                    for field in ("tests", "failures", "errors", "skipped")
                },
            }
        )
    manifest = {
        "schema_version": evidence.EXECUTION_MANIFEST_SCHEMA,
        "phase": 4,
        "candidate_commit": CANDIDATE,
        "attempt_id": tmp_path.name,
        "status": "PASS",
        "verification_started_at": "2026-07-22T00:00:00+00:00",
        "verification_finished_at": "2026-07-22T00:01:00+00:00",
        "environment": environment,
        "commands": records,
        "quarantined_attempts": [],
        "pending_failure": None,
        "quarantined_attempts_reused": False,
        "promotion_gate": "PENDING",
        "MIG-003": "PENDING_PROMOTION",
        "MIG-004": "PENDING_PROMOTION",
    }
    evidence.seal_execution_manifest(manifest)
    return manifest


def _materialize_execution_manifest(
    tmp_path: Path, manifest: dict, source_dir: Path
) -> Path:
    dependency_paths = (
        "python-agent-service/requirements.lock",
        "java-api-service/pom.xml",
        "frontend/pnpm-lock.yaml",
    )
    manifest["environment"]["dependency_manifests"] = [
        {"path": dependency, "sha256": evidence._sha256(evidence.ROOT / dependency)}
        for dependency in dependency_paths
    ]
    manifest["environment"].pop("snapshot_sha256", None)
    manifest["environment"]["snapshot_sha256"] = evidence._json_sha256(
        manifest["environment"]
    )
    for record in manifest["commands"]:
        record["environment_sha256"] = manifest["environment"]["snapshot_sha256"]
        raw = tmp_path / "attempts" / record["id"] / "raw-junit.xml"
        raw.parent.mkdir(parents=True, exist_ok=True)
        raw.write_bytes((source_dir / record["report"]).read_bytes())
        record["raw_reports"] = [
            {
                "path": raw.relative_to(tmp_path).as_posix(),
                "sha256": evidence._sha256(raw),
            }
        ]
        for stream in ("stdout", "stderr"):
            path = tmp_path / record[f"{stream}_path"]
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"{record['id']} {stream}\n", encoding="utf-8")
            record[f"{stream}_sha256"] = evidence._sha256(path)
    evidence.seal_execution_manifest(manifest)
    manifest_path = tmp_path / evidence.EXECUTION_MANIFEST_NAME
    evidence._write_json(manifest_path, manifest)
    return manifest_path


def _add_quarantined_java_infra_attempt(
    tmp_path: Path, manifest: dict
) -> dict[str, Path]:
    accepted = next(
        record for record in manifest["commands"] if record["id"] == "java_phase_4"
    )
    attempt = copy.deepcopy(accepted)
    quarantine_root = tmp_path / "quarantined" / "java_phase_4-attempt-1"
    raw_path = quarantine_root / "raw-junit.xml"
    stdout_path = quarantine_root / "stdout.log"
    stderr_path = quarantine_root / "stderr.log"
    raw_path.parent.mkdir(parents=True, exist_ok=True)
    accepted_raw = tmp_path / accepted["raw_reports"][0]["path"]
    raw_path.write_bytes(accepted_raw.read_bytes())
    stdout_path.write_text("java_phase_4 quarantined stdout\n", encoding="utf-8")
    stderr_path.write_text("java_phase_4 quarantined stderr\n", encoding="utf-8")
    attempt.update(
        {
            "exit_code": 1,
            "accepted": False,
            "failure_classification": "INFRA",
            "raw_reports": [
                {
                    "path": raw_path.relative_to(tmp_path).as_posix(),
                    "sha256": evidence._sha256(raw_path),
                }
            ],
            "stdout_path": stdout_path.relative_to(tmp_path).as_posix(),
            "stdout_sha256": evidence._sha256(stdout_path),
            "stderr_path": stderr_path.relative_to(tmp_path).as_posix(),
            "stderr_sha256": evidence._sha256(stderr_path),
        }
    )
    manifest["quarantined_attempts"] = [attempt]
    return {
        "raw_junit": raw_path,
        "stdout": stdout_path,
        "stderr": stderr_path,
    }


def test_matrix_expands_java_template_and_declares_exact_artifacts() -> None:
    matrix = evidence.load_matrix()
    commands = evidence.focused_commands(matrix)
    java_command = commands["java_phase_4"]["command"]

    assert evidence.JAVA_PLACEHOLDER not in java_command
    for classname in evidence._deduplicated_java_classes(matrix):
        assert classname in java_command
    assert set(
        matrix["batches"]["P4-BATCH-3"]["evidence"]["required_files"]
    ) == evidence.EXPECTED_FILES


def test_execution_manifest_authenticates_commands_environment_logs_and_reports(
    tmp_path: Path,
) -> None:
    matrix = evidence.load_matrix()
    policy = evidence._load_yaml(evidence.POLICY_PATH)
    source_dir = _fixture_source_reports(tmp_path, matrix, policy)
    manifest = _fixture_execution_manifest(tmp_path, matrix, source_dir)
    manifest_path = _materialize_execution_manifest(tmp_path, manifest, source_dir)

    loaded = evidence.load_execution_manifest(
        path=manifest_path,
        candidate_commit=CANDIDATE,
        matrix=matrix,
        source_dir=source_dir,
    )
    assert loaded["status"] == "PASS"
    assert {record["id"] for record in loaded["commands"]} == set(
        evidence.SOURCE_REPORTS
    )

    manifest["commands"][0]["duration_seconds"] = 59.0
    evidence._write_json(manifest_path, manifest)
    with pytest.raises(evidence.EvidenceError, match="manifest SHA-256 drifted"):
        evidence.load_execution_manifest(
            path=manifest_path,
            candidate_commit=CANDIDATE,
            matrix=matrix,
            source_dir=source_dir,
        )

    manifest["commands"][0]["duration_seconds"] = 60.0
    manifest["commands"][0]["exit_code"] = 1
    evidence.seal_execution_manifest(manifest)
    evidence._write_json(manifest_path, manifest)
    with pytest.raises(evidence.EvidenceError, match="source command was not accepted"):
        evidence.load_execution_manifest(
            path=manifest_path,
            candidate_commit=CANDIDATE,
            matrix=matrix,
            source_dir=source_dir,
        )


@pytest.mark.parametrize(
    ("artifact", "error"),
    [
        ("stdout", "java_phase_4 quarantined stdout SHA-256 drifted"),
        ("stderr", "java_phase_4 quarantined stderr SHA-256 drifted"),
        ("raw_junit", "java_phase_4 quarantined raw JUnit SHA-256 drifted"),
    ],
)
def test_resealed_manifest_rejects_tampered_quarantined_infra_artifacts(
    tmp_path: Path,
    artifact: str,
    error: str,
) -> None:
    matrix = evidence.load_matrix()
    policy = evidence._load_yaml(evidence.POLICY_PATH)
    source_dir = _fixture_source_reports(tmp_path, matrix, policy)
    manifest = _fixture_execution_manifest(tmp_path, matrix, source_dir)
    manifest_path = _materialize_execution_manifest(tmp_path, manifest, source_dir)
    artifacts = _add_quarantined_java_infra_attempt(tmp_path, manifest)
    evidence.seal_execution_manifest(manifest)
    evidence._write_json(manifest_path, manifest)
    assert evidence.load_execution_manifest(
        path=manifest_path,
        candidate_commit=CANDIDATE,
        matrix=matrix,
        source_dir=source_dir,
    )["status"] == "PASS"

    artifacts[artifact].write_bytes(b"tampered quarantined evidence\n")
    previous_seal = manifest["manifest_sha256"]
    manifest["quarantined_attempts"][0]["duration_seconds"] = 59.0
    evidence.seal_execution_manifest(manifest)
    assert manifest["manifest_sha256"] != previous_seal
    evidence._write_json(manifest_path, manifest)

    with pytest.raises(evidence.EvidenceError, match=error):
        evidence.load_execution_manifest(
            path=manifest_path,
            candidate_commit=CANDIDATE,
            matrix=matrix,
            source_dir=source_dir,
        )


def test_assemble_cannot_turn_an_unvalidated_manifest_dictionary_into_pass(
    tmp_path: Path,
) -> None:
    matrix = evidence.load_matrix()
    policy = evidence._load_yaml(evidence.POLICY_PATH)
    source_dir = _fixture_source_reports(tmp_path, matrix, policy)
    manifest = _fixture_execution_manifest(tmp_path, matrix, source_dir)
    manifest_path = tmp_path / evidence.EXECUTION_MANIFEST_NAME
    evidence._write_json(manifest_path, manifest)

    with pytest.raises(evidence.EvidenceError, match="dependency manifest hashes"):
        evidence.assemble_evidence(
            matrix=matrix,
            policy=policy,
            source_dir=source_dir,
            output_dir=tmp_path / "fabricated-pass",
            release_id="phase-4-fabricated",
            base_commit=BASE,
            candidate_commit=CANDIDATE,
            engineering_started_at="2026-07-21T00:00:00+00:00",
            verification_started_at="2026-07-22T00:00:00+00:00",
            verification_finished_at="2026-07-22T00:01:00+00:00",
            execution_manifest_path=manifest_path,
        )


def test_assemble_reloads_and_rejects_tampered_quarantined_raw_junit(
    tmp_path: Path,
) -> None:
    matrix = evidence.load_matrix()
    policy = evidence._load_yaml(evidence.POLICY_PATH)
    source_dir = _fixture_source_reports(tmp_path, matrix, policy)
    manifest = _fixture_execution_manifest(tmp_path, matrix, source_dir)
    manifest_path = _materialize_execution_manifest(tmp_path, manifest, source_dir)
    artifacts = _add_quarantined_java_infra_attempt(tmp_path, manifest)
    evidence.seal_execution_manifest(manifest)
    evidence._write_json(manifest_path, manifest)
    validated = evidence.load_execution_manifest(
        path=manifest_path,
        candidate_commit=CANDIDATE,
        matrix=matrix,
        source_dir=source_dir,
    )
    assert len(validated["quarantined_attempts"]) == 1

    artifacts["raw_junit"].write_bytes(b"tampered after validation\n")
    previous_seal = manifest["manifest_sha256"]
    manifest["quarantined_attempts"][0]["duration_seconds"] = 59.0
    evidence.seal_execution_manifest(manifest)
    assert manifest["manifest_sha256"] != previous_seal
    evidence._write_json(manifest_path, manifest)

    with pytest.raises(
        evidence.EvidenceError,
        match="java_phase_4 quarantined raw JUnit SHA-256 drifted",
    ):
        evidence.assemble_evidence(
            matrix=matrix,
            policy=policy,
            source_dir=source_dir,
            output_dir=tmp_path / "must-not-exist",
            release_id="phase-4-reload-guard",
            base_commit=BASE,
            candidate_commit=CANDIDATE,
            engineering_started_at="2026-07-21T00:00:00+00:00",
            verification_started_at="2026-07-22T00:00:00+00:00",
            verification_finished_at="2026-07-22T00:01:00+00:00",
            execution_manifest_path=manifest_path,
        )
    assert not (tmp_path / "must-not-exist").exists()


def test_frontend_policy_selector_accepts_vitest_suite_prefix() -> None:
    case = _case(
        source="frontend-phase4-junit.xml",
        classname="src/api/agentStream.test.js",
        name="agent stream protocol > discovers active room runs after refresh with actor isolation headers",
    )
    assert evidence._selector_matches(
        "frontend-phase4-junit.xml",
        case,
        "frontend/src/api/agentStream.test.js#discovers active room runs after refresh with actor isolation headers",
    )


def test_source_reports_reject_mixed_candidate_failure_skip_and_command(
    tmp_path: Path,
) -> None:
    matrix = evidence.load_matrix()
    policy = evidence._load_yaml(evidence.POLICY_PATH)
    source_dir = _fixture_source_reports(tmp_path, matrix, policy)

    mixed = source_dir / "frontend-phase4-junit.xml"
    tree = ET.parse(mixed)
    tree.getroot().set("candidate_commit", "c" * 40)
    tree.write(mixed, encoding="utf-8", xml_declaration=True)
    with pytest.raises(evidence.EvidenceError, match="candidate binding"):
        evidence.consume_source_reports(
            source_dir=source_dir,
            output_dir=tmp_path / "mixed-output",
            candidate_commit=CANDIDATE,
            matrix=matrix,
        )

    source_dir = _fixture_source_reports(tmp_path / "failed", matrix, policy)
    failed_path = source_dir / "python-phase4-junit.xml"
    evidence.write_junit(
        failed_path,
        name="failed",
        cases=[
            _case(
                source=failed_path.name,
                classname="tests.failed",
                name="test_failed",
                status="failure",
            )
        ],
        candidate_commit=CANDIDATE,
        command_id="python_phase_4",
    )
    with pytest.raises(evidence.EvidenceError, match="all-pass, zero-skip"):
        evidence.consume_source_reports(
            source_dir=source_dir,
            output_dir=tmp_path / "failed-output",
            candidate_commit=CANDIDATE,
            matrix=matrix,
        )

    source_dir = _fixture_source_reports(tmp_path / "wrong-command", matrix, policy)
    wrong_path = source_dir / "static-phase4-junit.xml"
    tree = ET.parse(wrong_path)
    tree.getroot().set("source_command_id", "p4_entry_static")
    tree.write(wrong_path, encoding="utf-8", xml_declaration=True)
    with pytest.raises(evidence.EvidenceError, match="command binding"):
        evidence.consume_source_reports(
            source_dir=source_dir,
            output_dir=tmp_path / "wrong-command-output",
            candidate_commit=CANDIDATE,
            matrix=matrix,
        )


def test_policy_coverage_requires_every_real_test_selector(tmp_path: Path) -> None:
    matrix = evidence.load_matrix()
    policy = evidence._load_yaml(evidence.POLICY_PATH)
    source_dir = _fixture_source_reports(tmp_path, matrix, policy)
    output_dir = tmp_path / "normalized"
    output_dir.mkdir()
    reports = evidence.consume_source_reports(
        source_dir=source_dir,
        output_dir=output_dir,
        candidate_commit=CANDIDATE,
        matrix=matrix,
    )

    checks = evidence.build_check_coverage(
        matrix=matrix,
        policy=policy,
        candidate_commit=CANDIDATE,
        reports=reports,
    )
    baselines = evidence.build_baseline_coverage(
        matrix=matrix,
        policy=policy,
        candidate_commit=CANDIDATE,
        reports=reports,
    )
    assert checks["summary"]["total"] == sum(
        len(values) for values in matrix["check_ids"].values()
    )
    assert baselines["summary"]["total"] == sum(
        len(values) for values in matrix["baseline_ids"].values()
    )
    assert next(
        row for row in checks["checks"] if row["id"] == "MIG-004"
    )["status"] == "PENDING_PROMOTION"

    incomplete = copy.deepcopy(policy)
    incomplete["overrides"]["GRAPH-020"]["test_selectors"].append(
        "tests.graphs.intake.test_graph#test_did_not_run"
    )
    with pytest.raises(evidence.EvidenceError, match="evidence selector did not run"):
        evidence.build_check_coverage(
            matrix=matrix,
            policy=incomplete,
            candidate_commit=CANDIDATE,
            reports=reports,
        )


def test_assembles_exact_bundle_and_keeps_both_migrations_pending(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    matrix = evidence.load_matrix()
    policy = evidence._load_yaml(evidence.POLICY_PATH)
    source_dir = _fixture_source_reports(tmp_path, matrix, policy)
    execution_manifest = _fixture_execution_manifest(tmp_path, matrix, source_dir)
    execution_manifest_path = _materialize_execution_manifest(
        tmp_path, execution_manifest, source_dir
    )
    output_dir = tmp_path / "phase-4"
    monkeypatch.setattr(
        evidence,
        "_change_summary",
        lambda base, candidate: {
            "commits": 8,
            "files_changed": 40,
            "insertions": 1000,
            "deletions": 10,
        },
    )

    metrics = evidence.assemble_evidence(
        matrix=matrix,
        policy=policy,
        source_dir=source_dir,
        output_dir=output_dir,
        release_id="phase-4-fixture",
        base_commit=BASE,
        candidate_commit=CANDIDATE,
        engineering_started_at="2026-07-21T00:00:00+00:00",
        verification_started_at="2026-07-22T00:00:00+00:00",
        verification_finished_at="2026-07-22T00:01:00+00:00",
        execution_manifest_path=execution_manifest_path,
    )
    second_output = tmp_path / "phase-4-repeat"
    evidence.assemble_evidence(
        matrix=matrix,
        policy=policy,
        source_dir=source_dir,
        output_dir=second_output,
        release_id="phase-4-fixture",
        base_commit=BASE,
        candidate_commit=CANDIDATE,
        engineering_started_at="2026-07-21T00:00:00+00:00",
        verification_started_at="2026-07-22T00:00:00+00:00",
        verification_finished_at="2026-07-22T00:01:00+00:00",
        execution_manifest_path=execution_manifest_path,
    )

    assert {path.name for path in output_dir.iterdir()} == evidence.EXPECTED_FILES
    assert metrics["candidate_commit"] == CANDIDATE
    assert metrics["candidate_verification"]["failures"] == 0
    assert metrics["source_execution_manifest"] == {
        "name": evidence.EXECUTION_MANIFEST_NAME,
        "sha256": evidence._sha256(
            output_dir / evidence.EXECUTION_MANIFEST_NAME
        ),
    }
    assert metrics["status"] == {
        "engineering_checkpoint": "PASS",
        "promotion_gate": "PENDING",
        "next_phase_permission": "PHASE_5_ENGINEERING_ONLY",
        "MIG-003": "PENDING_PROMOTION",
        "MIG-004": "PENDING_PROMOTION",
    }
    assert (output_dir / "candidate-commit.txt").read_text(encoding="utf-8") == (
        CANDIDATE + "\n"
    )
    assert {
        path.name: path.read_bytes() for path in output_dir.iterdir()
    } == {
        path.name: path.read_bytes() for path in second_output.iterdir()
    }

    external = evidence.json.loads(
        (output_dir / "external-gates.json").read_text(encoding="utf-8")
    )
    assert {
        gate: value["status"]
        for gate, value in external["promotion_gates"].items()
    } == {
        "MIG-003": "PENDING_PROMOTION",
        "MIG-004": "PENDING_PROMOTION",
    }
    assert all(
        gate["status"] == "PENDING_EXTERNAL"
        for gate in external["external_gates"]
    )

    for filename in evidence.SOURCE_REPORTS.values():
        report = evidence.parse_junit(output_dir / filename)
        assert report.candidate_commit == CANDIDATE
        assert not {
            child.tag.rsplit("}", 1)[-1]
            for case in report.cases
            for child in case.element
        } & {"system-out", "system-err"}

    for filename in evidence.DERIVED_REPORTS.values():
        root = ET.parse(output_dir / filename).getroot()
        hashes = {
            item.get("name")
            for item in root.findall("./properties/property")
        }
        assert hashes == {
            f"source_sha256.{source}" for source in evidence.SOURCE_REPORTS.values()
        }


def test_promotion_or_runtime_relaxation_is_rejected() -> None:
    policy = evidence._load_yaml(evidence.POLICY_PATH)
    promoted = copy.deepcopy(policy)
    promoted["promotion_gates"]["MIG-004"]["status"] = "PASS"
    with pytest.raises(evidence.EvidenceError, match="preserve MIG-003 and MIG-004"):
        evidence.build_external_gates(policy=promoted, candidate_commit=CANDIDATE)

    enabled = copy.deepcopy(policy)
    enabled["formal_writer_allowed"] = True
    with pytest.raises(evidence.EvidenceError, match="restrictions were relaxed"):
        evidence.build_external_gates(policy=enabled, candidate_commit=CANDIDATE)


def test_candidate_preflight_requires_fixed_detached_clean_head(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    with pytest.raises(evidence.EvidenceError, match="must be under .codex-run"):
        evidence.assert_candidate_run_directory(evidence.ROOT)
    with pytest.raises(evidence.EvidenceError, match="must be under .codex-run"):
        evidence.assert_candidate_run_directory(evidence.ROOT / "python-agent-service" / "run")
    evidence.assert_candidate_run_directory(evidence.ROOT / ".codex-run" / "candidate")
    evidence.assert_candidate_run_directory(tmp_path / "candidate")

    responses = {
        "rev-parse": subprocess.CompletedProcess(
            ["git"], 0, stdout=CANDIDATE + "\n", stderr=""
        ),
        "status": subprocess.CompletedProcess(["git"], 0, stdout="", stderr=""),
        "symbolic-ref": subprocess.CompletedProcess(["git"], 1, stdout="", stderr=""),
    }
    monkeypatch.setattr(
        evidence, "_git_result", lambda *args, **kwargs: responses[args[0]]
    )
    evidence.assert_clean_detached_candidate(CANDIDATE, repository=tmp_path)

    responses["rev-parse"] = subprocess.CompletedProcess(
        ["git"], 0, stdout="c" * 40 + "\n", stderr=""
    )
    with pytest.raises(evidence.EvidenceError, match="does not match fixed SHA"):
        evidence.assert_clean_detached_candidate(CANDIDATE, repository=tmp_path)

    responses["rev-parse"] = subprocess.CompletedProcess(
        ["git"], 0, stdout=CANDIDATE + "\n", stderr=""
    )
    responses["status"] = subprocess.CompletedProcess(
        ["git"], 0, stdout=" M tracked.py\0", stderr=""
    )
    with pytest.raises(evidence.EvidenceError, match="not clean"):
        evidence.assert_clean_detached_candidate(CANDIDATE, repository=tmp_path)

    responses["status"] = subprocess.CompletedProcess(["git"], 0, stdout="", stderr="")
    responses["symbolic-ref"] = subprocess.CompletedProcess(
        ["git"], 0, stdout="refs/heads/candidate\n", stderr=""
    )
    with pytest.raises(evidence.EvidenceError, match="must be detached"):
        evidence.assert_clean_detached_candidate(CANDIDATE, repository=tmp_path)
