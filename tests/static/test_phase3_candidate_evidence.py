from __future__ import annotations

import ast
import fnmatch
import importlib.util
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/generate_phase3_candidate_evidence.py"
SPEC = importlib.util.spec_from_file_location("generate_phase3_candidate_evidence", SCRIPT)
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
    name: str = "test_evidence",
    status: str = "passed",
) -> evidence.TestCase:
    element = ET.Element(
        "testcase", {"classname": classname, "name": name, "time": "0.001"}
    )
    if status != "passed":
        ET.SubElement(element, status)
    return evidence.TestCase(
        source=source,
        suite=classname,
        classname=classname,
        name=name,
        duration=0.001,
        status=status,
        element=element,
    )


def _selector_case(source: str, selector: str, index: int) -> evidence.TestCase:
    prefix = evidence._selector_prefix(selector)
    classname = prefix if selector.endswith(".py") else f"{prefix}.test_evidence"
    return _case(source=source, classname=classname, name=f"test_{index}")


def _pattern_case(source: str, selector: str) -> evidence.TestCase:
    classname_pattern, separator, name_pattern = selector.partition("#")
    assert separator
    classname = classname_pattern.replace("*", "fixture").replace("?", "x")
    name = name_pattern.replace("*", "fixture").replace("?", "x")
    return _case(source=source, classname=classname, name=name)


def _fixture_reports(
    tmp_path: Path,
    matrix: dict,
    *,
    candidate: str = CANDIDATE,
) -> dict[str, evidence.JUnitReport]:
    commands = evidence._focused_commands(matrix)
    batch_1 = matrix["batches"]["P3-BATCH-1"]
    batch_2 = matrix["batches"]["P3-BATCH-2"]

    python_selectors = {
        *evidence.TEST_PATH_PATTERN.findall(commands["python_phase_3"]["command"]),
        *batch_1["python_tests"],
        *batch_2["python_tests"],
    }
    static_selectors = {
        *evidence.TEST_PATH_PATTERN.findall(
            commands["root_phase_3_static"]["command"]
        ),
        *batch_1["static_tests"],
    }
    java_match = evidence.JAVA_TEST_PATTERN.search(commands["java_phase_3"]["command"])
    assert java_match is not None
    java_classes = java_match.group(1).split(",")

    source_cases = {
        "python_phase_3": [
            _selector_case("python-phase3-junit.xml", selector, index)
            for index, selector in enumerate(sorted(python_selectors))
        ],
        "root_phase_3_static": [
            _selector_case("static-phase3-junit.xml", selector, index)
            for index, selector in enumerate(sorted(static_selectors))
        ],
        "java_phase_3": [
            _case(
                source="java-phase3-junit.xml",
                classname=f"com.example.{classname}",
            )
            for classname in java_classes
        ],
    }
    policy = evidence._load_yaml(evidence.POLICY_PATH)
    filename_to_command = {value: key for key, value in evidence.SOURCE_REPORTS.items()}
    for check_id, override in policy["overrides"].items():
        prefix = check_id.split("-", 1)[0]
        merged = {**policy["defaults"][prefix], **override}
        for selector in merged["test_selectors"]:
            if selector.startswith("tests.static."):
                filename = "static-phase3-junit.xml"
            elif selector.startswith("tests."):
                filename = "python-phase3-junit.xml"
            else:
                filename = "java-phase3-junit.xml"
            assert filename in merged["evidence"], (check_id, selector, filename)
            source_cases[filename_to_command[filename]].append(
                _pattern_case(filename, selector)
            )
    reports = {}
    for command_id, filename in evidence.SOURCE_REPORTS.items():
        unique_cases = {
            (case.classname, case.name): case for case in source_cases[command_id]
        }
        reports[command_id] = evidence.write_junit(
            tmp_path / filename,
            name=filename.removesuffix(".xml"),
            cases=list(unique_cases.values()),
            candidate_commit=candidate,
            command_id=command_id,
        )
    return reports


def test_parse_junit_rejects_declared_count_drift_and_duplicate_identity(
    tmp_path: Path,
) -> None:
    wrong_count = tmp_path / "wrong-count.xml"
    wrong_count.write_text(
        '<testsuite name="suite" tests="2" failures="0" errors="0" skipped="0">'
        '<testcase classname="tests.example" name="test_one" />'
        "</testsuite>",
        encoding="utf-8",
    )
    with pytest.raises(evidence.EvidenceError, match="declares tests=2, actual=1"):
        evidence.parse_junit(wrong_count)

    duplicate = tmp_path / "duplicate.xml"
    duplicate.write_text(
        '<testsuites><testsuite name="suite">'
        '<testcase classname="tests.example" name="test_one" />'
        '<testcase classname="tests.example" name="test_one" />'
        "</testsuite></testsuites>",
        encoding="utf-8",
    )
    with pytest.raises(evidence.EvidenceError, match="duplicate testcase"):
        evidence.parse_junit(duplicate)

    flaky = tmp_path / "flaky.xml"
    flaky.write_text(
        '<testsuite name="suite" tests="1" failures="0" errors="0" skipped="0" flakes="1">'
        '<testcase classname="tests.example" name="test_one" />'
        "</testsuite>",
        encoding="utf-8",
    )
    with pytest.raises(evidence.EvidenceError, match="declares 1 flakes"):
        evidence.parse_junit(flaky)


def test_evidence_timestamps_require_an_explicit_offset() -> None:
    assert evidence._assert_timestamp(
        "2026-07-20T08:00:00+08:00", "started_at"
    ) == "2026-07-20T08:00:00+08:00"
    with pytest.raises(evidence.EvidenceError, match="explicit UTC offset"):
        evidence._assert_timestamp("2026-07-20T08:00:00", "started_at")


def test_source_reports_require_exact_candidate_command_and_matrix_coverage(
    tmp_path: Path,
) -> None:
    matrix = evidence.load_matrix()
    reports = _fixture_reports(tmp_path, matrix)

    loaded = evidence.load_bound_source_reports(tmp_path, CANDIDATE, matrix)
    assert set(loaded) == set(evidence.SOURCE_REPORTS)

    java_path = reports["java_phase_3"].path
    tree = ET.parse(java_path)
    root = tree.getroot()
    root.set("candidate_commit", "c" * 40)
    tree.write(java_path, encoding="utf-8", xml_declaration=True)
    with pytest.raises(evidence.EvidenceError, match="candidate binding"):
        evidence.load_bound_source_reports(tmp_path, CANDIDATE, matrix)


def test_requested_java_class_and_batch_selector_must_have_run(tmp_path: Path) -> None:
    matrix = evidence.load_matrix()
    reports = _fixture_reports(tmp_path, matrix)
    java_command = evidence._focused_commands(matrix)["java_phase_3"]["command"]
    missing_class = next(iter(reports["java_phase_3"].cases)).classname.rsplit(".", 1)[-1]
    retained = tuple(
        case
        for case in reports["java_phase_3"].cases
        if not case.classname.endswith(f".{missing_class}")
    )
    reduced_path = tmp_path / "reduced-java.xml"
    reduced = evidence.write_junit(
        reduced_path,
        name="reduced-java",
        cases=retained,
        candidate_commit=CANDIDATE,
        command_id="java_phase_3",
    )
    with pytest.raises(evidence.EvidenceError, match="requested test classes did not run"):
        evidence._assert_java_classes(reduced, java_command)

    with pytest.raises(evidence.EvidenceError, match="selectors did not run"):
        evidence.select_cases(
            reports["python_phase_3"],
            ["tests/not_present"],
            context="fixture batch",
        )


def test_non_green_source_report_cannot_produce_checkpoint(tmp_path: Path) -> None:
    matrix = evidence.load_matrix()
    reports = _fixture_reports(tmp_path, matrix)
    failed = _case(
        source="python-phase3-junit.xml",
        classname="tests.failed",
        status="failure",
    )
    reports["python_phase_3"] = evidence.write_junit(
        tmp_path / "python-failed.xml",
        name="python-failed",
        cases=[failed],
        candidate_commit=CANDIDATE,
        command_id="python_phase_3",
    )

    with pytest.raises(evidence.EvidenceError, match="not an all-pass, zero-skip"):
        evidence._require_green_source_reports(reports)


def test_policy_expands_every_matrix_id_and_keeps_migration_pending(
    tmp_path: Path,
) -> None:
    matrix = evidence.load_matrix()
    policy = evidence._load_yaml(evidence.POLICY_PATH)
    reports = _fixture_reports(tmp_path, matrix)

    coverage = evidence.build_check_coverage(
        matrix=matrix,
        policy=policy,
        candidate_commit=CANDIDATE,
        reports=reports,
    )

    expected = {
        check_id
        for agent in matrix["agents"].values()
        for check_id in agent["check_ids"]
    }
    assert {item["id"] for item in coverage["checks"]} == expected
    assert coverage["summary"]["total"] == len(expected)
    mig = next(item for item in coverage["checks"] if item["id"] == "MIG-003")
    assert mig["status"] == "PENDING_PROMOTION"
    assert all(
        gate["status"] in {"PENDING_EXTERNAL", "PENDING_PROMOTION"}
        for gate in coverage["external_gates"]
    )

    changed = {
        **policy,
        "overrides": {
            **policy["overrides"],
            "MIG-003": {
                "status": "PASS_ENGINEERING",
                "evidence": ["python-phase3-junit.xml"],
            },
        },
    }
    with pytest.raises(evidence.EvidenceError, match="MIG-003 must remain"):
        evidence.build_check_coverage(
            matrix=matrix,
            policy=changed,
            candidate_commit=CANDIDATE,
            reports=reports,
        )

    incomplete = {
        **policy,
        "overrides": {
            check_id: value
            for check_id, value in policy["overrides"].items()
            if check_id != "GRAPH-001"
        },
    }
    with pytest.raises(evidence.EvidenceError, match="lacks explicit mappings.*GRAPH-001"):
        evidence.build_check_coverage(
            matrix=matrix,
            policy=incomplete,
            candidate_commit=CANDIDATE,
            reports=reports,
        )


def test_policy_selectors_reference_tests_in_the_candidate_suite() -> None:
    matrix = evidence.load_matrix()
    policy = evidence._load_yaml(evidence.POLICY_PATH)
    java_command = evidence._focused_commands(matrix)["java_phase_3"]["command"]
    java_match = evidence.JAVA_TEST_PATTERN.search(java_command)
    assert java_match is not None
    java_classes = java_match.group(1).split(",")

    for check_id, override in policy["overrides"].items():
        prefix = check_id.split("-", 1)[0]
        merged = {**policy["defaults"][prefix], **override}
        for selector in merged["test_selectors"]:
            classname_pattern, separator, name_pattern = selector.partition("#")
            assert separator, (check_id, selector)
            if not classname_pattern.startswith("tests."):
                assert any(
                    fnmatch.fnmatch(classname, classname_pattern)
                    for classname in java_classes
                ), (check_id, selector)
                continue

            relative = Path(*classname_pattern.split(".")).with_suffix(".py")
            service_root = ROOT if classname_pattern.startswith("tests.static.") else (
                ROOT / "python-agent-service"
            )
            path = service_root / relative
            assert path.is_file(), (check_id, selector, path)
            module = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
            test_names = {
                node.name
                for node in module.body
                if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
                and node.name.startswith("test_")
            }
            assert any(fnmatch.fnmatch(name, name_pattern) for name in test_names), (
                check_id,
                selector,
            )


def test_assembles_exact_required_file_set_and_checkpoint_from_one_candidate(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    matrix = evidence.load_matrix()
    policy = evidence._load_yaml(evidence.POLICY_PATH)
    monkeypatch.setattr(evidence, "ROOT", tmp_path)
    monkeypatch.setattr(
        evidence,
        "_change_summary",
        lambda base, candidate: {
            "commits": 7,
            "files_changed": 42,
            "insertions": 1000,
            "deletions": 10,
        },
    )
    output_dir = tmp_path / "test-reports/temporal-first/phase-3-fixture/phase-3"
    output_dir.mkdir(parents=True)
    reports = _fixture_reports(output_dir, matrix)
    commands = [
        evidence.CommandResult(
            command_id=command_id,
            cwd=".",
            command_sha256="d" * 64,
            started_at="2026-07-20T00:00:00+00:00",
            finished_at="2026-07-20T00:00:01+00:00",
            duration_seconds=1.0,
            exit_code=0,
        )
        for command_id in evidence.SOURCE_REPORTS
    ]
    checkpoint = tmp_path / "docs/phase-3-engineering-checkpoint.md"
    checkpoint.parent.mkdir(parents=True)

    metrics, coverage = evidence.assemble_evidence(
        matrix=matrix,
        policy=policy,
        output_dir=output_dir,
        checkpoint_path=checkpoint,
        release_id="phase-3-fixture",
        base_commit=BASE,
        candidate_commit=CANDIDATE,
        engineering_started_at="2026-07-19T00:00:00+00:00",
        verification_started_at="2026-07-20T00:00:00+00:00",
        verification_finished_at="2026-07-20T00:01:00+00:00",
        reports=reports,
        commands=commands,
    )

    required = set(
        matrix["batches"]["P3-BATCH-3"]["evidence"]["required_files"]
    )
    assert {path.name for path in output_dir.iterdir()} == required
    assert metrics["candidate_commit"] == CANDIDATE
    assert metrics["candidate_verification"]["failures"] == 0
    assert coverage["candidate_commit"] == CANDIDATE
    assert (output_dir / "candidate-commit.txt").read_text() == f"{CANDIDATE}\n"
    text = checkpoint.read_text(encoding="utf-8")
    assert "engineering_checkpoint: PASS" in text
    assert "promotion_gate: PENDING" in text
    assert "next_phase_permission: PHASE_4_ENGINEERING_ONLY" in text
    assert "`MIG-003` remains `PENDING_PROMOTION`" in text


def test_runner_injects_candidate_specific_junit_capture(monkeypatch, tmp_path: Path) -> None:
    matrix = evidence.load_matrix()
    repository = tmp_path / "repo"
    (repository / "python-agent-service").mkdir(parents=True)
    (repository / "java-api-service/target/surefire-reports").mkdir(parents=True)
    output_dir = repository / "evidence"
    output_dir.mkdir()
    monkeypatch.setattr(evidence, "ROOT", repository)
    monkeypatch.setattr(evidence, "assert_candidate_unchanged", lambda candidate: None)
    observed_commands: dict[str, str] = {}

    def fake_run(command_id: str, command: str, cwd: Path) -> evidence.CommandResult:
        observed_commands[command_id] = command
        if command_id != "java_phase_3":
            match = re.search(r'--junitxml="([^"]+)"', command)
            assert match is not None
            selectors = evidence.TEST_PATH_PATTERN.findall(
                evidence._focused_commands(matrix)[command_id]["command"]
            )
            raw = Path(match.group(1))
            evidence.write_junit(
                raw,
                name="raw",
                cases=[
                    _selector_case("raw.xml", selector, index)
                    for index, selector in enumerate(selectors)
                ],
                candidate_commit=CANDIDATE,
            )
        else:
            suffix = re.search(r"reportNameSuffix=([^\s]+)", command)
            assert suffix is not None
            expected = evidence.JAVA_TEST_PATTERN.search(
                evidence._focused_commands(matrix)[command_id]["command"]
            )
            assert expected is not None
            report_path = (
                repository
                / "java-api-service/target/surefire-reports"
                / f"TEST-fixture-{suffix.group(1)}.xml"
            )
            evidence.write_junit(
                report_path,
                name="raw-java",
                cases=[
                    _case(source="raw.xml", classname=f"example.{classname}")
                    for classname in expected.group(1).split(",")
                ],
                candidate_commit=CANDIDATE,
            )
        return evidence.CommandResult(
            command_id=command_id,
            cwd=".",
            command_sha256="e" * 64,
            started_at="2026-07-20T00:00:00+00:00",
            finished_at="2026-07-20T00:00:01+00:00",
            duration_seconds=1.0,
            exit_code=0,
        )

    monkeypatch.setattr(evidence, "_run_shell", fake_run)
    reports, results = evidence.run_source_suites(
        matrix=matrix,
        output_dir=output_dir,
        candidate_commit=CANDIDATE,
        release_id="phase-3-fixture",
    )

    assert set(reports) == set(evidence.SOURCE_REPORTS)
    assert len(results) == 3
    assert "--junitxml=" in observed_commands["python_phase_3"]
    assert "--junitxml=" in observed_commands["root_phase_3_static"]
    assert "-Dsurefire.reportNameSuffix=phase3-" in observed_commands["java_phase_3"]
    assert not (output_dir / ".work").exists()
