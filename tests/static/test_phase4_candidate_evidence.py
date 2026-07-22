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
    )

    assert {path.name for path in output_dir.iterdir()} == evidence.EXPECTED_FILES
    assert metrics["candidate_commit"] == CANDIDATE
    assert metrics["candidate_verification"]["failures"] == 0
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
