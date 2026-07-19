from __future__ import annotations

import argparse
import copy
import fnmatch
import hashlib
import json
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable, Sequence

import yaml


ROOT = Path(__file__).resolve().parents[1]
MATRIX_PATH = ROOT / "plans/phase-3-graph-lcel-test-batches.yaml"
POLICY_PATH = (
    ROOT
    / "docs/runbooks/temporal-first/phase-3-engineering-evidence-policy.yaml"
)
DEFAULT_CHECKPOINT_PATH = (
    ROOT / "docs/runbooks/temporal-first/phase-3-engineering-checkpoint.md"
)

SOURCE_REPORTS = {
    "python_phase_3": "python-phase3-junit.xml",
    "root_phase_3_static": "static-phase3-junit.xml",
    "java_phase_3": "java-phase3-junit.xml",
}
DERIVED_REPORTS = {
    "P3-BATCH-1": "batch-1-junit.xml",
    "P3-BATCH-2": "batch-2-junit.xml",
    "P3-BATCH-3": "batch-3-junit.xml",
}
ALLOWED_CHECK_STATUSES = {
    "PASS_ENGINEERING",
    "PARTIAL_ENGINEERING",
    "PENDING_EXTERNAL",
    "PENDING_PROMOTION",
}
SHA_PATTERN = re.compile(r"[0-9a-f]{40}")
TEST_PATH_PATTERN = re.compile(r"(?<!\S)(tests/[A-Za-z0-9_./*?-]+)")
JAVA_TEST_PATTERN = re.compile(r"-Dtest=([^\"\s]+)")


class EvidenceError(RuntimeError):
    pass


@dataclass(frozen=True)
class TestCase:
    source: str
    suite: str
    classname: str
    name: str
    duration: float
    status: str
    element: ET.Element

    @property
    def identity(self) -> tuple[str, str, str]:
        return (self.source, self.classname, self.name)

    @property
    def node_id(self) -> str:
        return f"{self.classname}#{self.name}"


@dataclass(frozen=True)
class JUnitReport:
    path: Path
    candidate_commit: str | None
    command_id: str | None
    cases: tuple[TestCase, ...]

    @property
    def totals(self) -> dict[str, int | float]:
        return _totals(self.cases)


@dataclass(frozen=True)
class CommandResult:
    command_id: str
    cwd: str
    command_sha256: str
    started_at: str
    finished_at: str
    duration_seconds: float
    exit_code: int

    def as_dict(self) -> dict[str, Any]:
        return {
            "id": self.command_id,
            "cwd": self.cwd,
            "command_sha256": self.command_sha256,
            "started_at": self.started_at,
            "finished_at": self.finished_at,
            "duration_seconds": self.duration_seconds,
            "exit_code": self.exit_code,
        }


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _write_json(path: Path, document: Any) -> None:
    path.write_text(
        json.dumps(document, ensure_ascii=False, indent=2, sort_keys=False) + "\n",
        encoding="utf-8",
    )


def _parse_nonnegative_int(value: str | None, field: str, path: Path) -> int | None:
    if value is None:
        return None
    try:
        parsed = int(value)
    except ValueError as exception:
        raise EvidenceError(f"{path}: JUnit {field} is not an integer") from exception
    if parsed < 0:
        raise EvidenceError(f"{path}: JUnit {field} is negative")
    return parsed


def _case_status(element: ET.Element) -> str:
    child_names = {_local_name(child.tag) for child in element}
    flaky_nodes = child_names & {
        "flakyFailure",
        "flakyError",
        "rerunFailure",
        "rerunError",
    }
    if flaky_nodes:
        raise EvidenceError(
            "JUnit testcase contains retry/flake outcomes: "
            + ", ".join(sorted(flaky_nodes))
        )
    if "failure" in child_names:
        return "failure"
    if "error" in child_names:
        return "error"
    if "skipped" in child_names:
        return "skipped"
    return "passed"


def _totals(cases: Iterable[TestCase]) -> dict[str, int | float]:
    values = tuple(cases)
    statuses = Counter(case.status for case in values)
    return {
        "tests": len(values),
        "failures": statuses["failure"],
        "errors": statuses["error"],
        "skipped": statuses["skipped"],
        "time": round(sum(case.duration for case in values), 6),
    }


def parse_junit(path: Path, *, source: str | None = None) -> JUnitReport:
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as exception:
        raise EvidenceError(f"invalid JUnit XML {path}: {exception}") from exception
    if _local_name(root.tag) not in {"testsuite", "testsuites"}:
        raise EvidenceError(f"{path}: JUnit root must be testsuite or testsuites")

    report_source = source or path.name
    cases: list[TestCase] = []
    seen: set[tuple[str, str]] = set()
    suites = [root] if _local_name(root.tag) == "testsuite" else [
        element for element in root.iter() if _local_name(element.tag) == "testsuite"
    ]
    for suite in suites:
        suite_name = suite.get("name") or "unnamed-suite"
        suite_cases: list[TestCase] = []
        for element in suite:
            if _local_name(element.tag) != "testcase":
                continue
            classname = element.get("classname")
            name = element.get("name")
            if not classname or not name:
                raise EvidenceError(
                    f"{path}: every JUnit testcase requires classname and name"
                )
            identity = (classname, name)
            if identity in seen:
                raise EvidenceError(f"{path}: duplicate testcase {classname}#{name}")
            seen.add(identity)
            try:
                duration = float(element.get("time", "0") or "0")
            except ValueError as exception:
                raise EvidenceError(
                    f"{path}: testcase {classname}#{name} has invalid time"
                ) from exception
            if duration < 0:
                raise EvidenceError(
                    f"{path}: testcase {classname}#{name} has negative time"
                )
            case = TestCase(
                source=report_source,
                suite=suite_name,
                classname=classname,
                name=name,
                duration=duration,
                status=_case_status(element),
                element=copy.deepcopy(element),
            )
            cases.append(case)
            suite_cases.append(case)
        if suite_cases:
            actual = _totals(suite_cases)
            for field in ("tests", "failures", "errors", "skipped"):
                declared = _parse_nonnegative_int(suite.get(field), field, path)
                if declared is not None and declared != actual[field]:
                    raise EvidenceError(
                        f"{path}: suite {suite_name!r} declares {field}={declared}, "
                        f"actual={actual[field]}"
                    )
            declared_flakes = _parse_nonnegative_int(suite.get("flakes"), "flakes", path)
            if declared_flakes:
                raise EvidenceError(
                    f"{path}: suite {suite_name!r} declares {declared_flakes} flakes"
                )
    if not cases:
        raise EvidenceError(f"{path}: JUnit XML contains no testcases")

    if _local_name(root.tag) == "testsuites":
        actual = _totals(cases)
        for field in ("tests", "failures", "errors", "skipped"):
            declared = _parse_nonnegative_int(root.get(field), field, path)
            if declared is not None and declared != actual[field]:
                raise EvidenceError(
                    f"{path}: root declares {field}={declared}, actual={actual[field]}"
                )
    return JUnitReport(
        path=path,
        candidate_commit=root.get("candidate_commit"),
        command_id=root.get("source_command_id"),
        cases=tuple(cases),
    )


def _format_duration(value: float) -> str:
    return f"{value:.6f}".rstrip("0").rstrip(".") or "0"


def write_junit(
    path: Path,
    *,
    name: str,
    cases: Sequence[TestCase],
    candidate_commit: str,
    command_id: str | None = None,
    source_hashes: dict[str, str] | None = None,
) -> JUnitReport:
    if not cases:
        raise EvidenceError(f"refusing to write empty JUnit report {path}")
    totals = _totals(cases)
    attributes = {
        "name": name,
        "tests": str(totals["tests"]),
        "failures": str(totals["failures"]),
        "errors": str(totals["errors"]),
        "skipped": str(totals["skipped"]),
        "time": _format_duration(float(totals["time"])),
        "candidate_commit": candidate_commit,
    }
    if command_id:
        attributes["source_command_id"] = command_id
    root = ET.Element("testsuites", attributes)
    if source_hashes:
        properties = ET.SubElement(root, "properties")
        for source_name, digest in sorted(source_hashes.items()):
            ET.SubElement(
                properties,
                "property",
                {"name": f"source_sha256.{source_name}", "value": digest},
            )

    grouped: dict[tuple[str, str], list[TestCase]] = defaultdict(list)
    for case in cases:
        grouped[(case.source, case.suite)].append(case)
    for (source, suite_name), suite_cases in sorted(grouped.items()):
        suite_totals = _totals(suite_cases)
        suite = ET.SubElement(
            root,
            "testsuite",
            {
                "name": suite_name,
                "source_report": source,
                "tests": str(suite_totals["tests"]),
                "failures": str(suite_totals["failures"]),
                "errors": str(suite_totals["errors"]),
                "skipped": str(suite_totals["skipped"]),
                "time": _format_duration(float(suite_totals["time"])),
            },
        )
        for case in sorted(suite_cases, key=lambda item: (item.classname, item.name)):
            suite.append(copy.deepcopy(case.element))

    ET.indent(root, space="  ")
    tree = ET.ElementTree(root)
    with path.open("wb") as stream:
        tree.write(stream, encoding="utf-8", xml_declaration=True)
        stream.write(b"\n")
    return parse_junit(path)


def normalize_source_reports(
    raw_paths: Sequence[Path],
    destination: Path,
    *,
    candidate_commit: str,
    command_id: str,
) -> JUnitReport:
    cases: list[TestCase] = []
    for raw_path in raw_paths:
        raw = parse_junit(raw_path, source=destination.name)
        cases.extend(raw.cases)
    return write_junit(
        destination,
        name=destination.stem,
        cases=cases,
        candidate_commit=candidate_commit,
        command_id=command_id,
    )


def _selector_prefix(selector: str) -> str:
    normalized = selector.strip().replace("\\", "/").removeprefix("./")
    normalized = normalized.removeprefix("python-agent-service/")
    if normalized.endswith(".py"):
        normalized = normalized[:-3]
    return normalized.strip("/").replace("/", ".")


def select_cases(
    report: JUnitReport,
    selectors: Sequence[str],
    *,
    context: str,
) -> tuple[TestCase, ...]:
    selected: dict[tuple[str, str, str], TestCase] = {}
    missing: list[str] = []
    for selector in selectors:
        prefix = _selector_prefix(selector)
        matches = [
            case
            for case in report.cases
            if case.classname == prefix or case.classname.startswith(f"{prefix}.")
        ]
        if not matches:
            missing.append(selector)
        for case in matches:
            selected[case.identity] = case
    if missing:
        raise EvidenceError(f"{context}: selectors did not run: {', '.join(missing)}")
    return tuple(selected.values())


def _assert_java_classes(report: JUnitReport, command: str) -> None:
    match = JAVA_TEST_PATTERN.search(command)
    if not match:
        raise EvidenceError("java_phase_3 command has no -Dtest class list")
    expected = {item for item in match.group(1).split(",") if item}
    observed = {
        case.classname.rsplit(".", 1)[-1]
        for case in report.cases
    }
    missing = sorted(expected - observed)
    if missing:
        raise EvidenceError(
            "java_phase_3 requested test classes did not run: " + ", ".join(missing)
        )


def _assert_python_selectors(report: JUnitReport, command: str, command_id: str) -> None:
    selectors = TEST_PATH_PATTERN.findall(command)
    if not selectors:
        raise EvidenceError(f"{command_id} command has no test selectors")
    select_cases(report, selectors, context=command_id)


def _load_yaml(path: Path) -> dict[str, Any]:
    try:
        document = yaml.safe_load(path.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as exception:
        raise EvidenceError(f"cannot load YAML {path}: {exception}") from exception
    if not isinstance(document, dict):
        raise EvidenceError(f"{path}: expected a mapping")
    return document


def load_matrix(path: Path = MATRIX_PATH) -> dict[str, Any]:
    matrix = _load_yaml(path)
    if matrix.get("schema_version") != "phase-test-batches.v1" or matrix.get("phase") != 3:
        raise EvidenceError(f"{path}: not the Phase 3 test matrix")
    batch = matrix.get("batches", {}).get("P3-BATCH-3", {})
    execution = batch.get("execution", {})
    if execution.get("strategy") != "deduplicated_source_suites_then_derived_batch_views":
        raise EvidenceError(f"{path}: unexpected Phase 3 evidence strategy")
    if set(execution.get("source_reports", [])) != set(SOURCE_REPORTS.values()):
        raise EvidenceError(f"{path}: source report contract drift")
    if set(execution.get("derived_views", [])) != set(DERIVED_REPORTS.values()):
        raise EvidenceError(f"{path}: derived report contract drift")
    return matrix


def _focused_commands(matrix: dict[str, Any]) -> dict[str, dict[str, str]]:
    values = matrix["batches"]["P3-BATCH-3"]["focused_commands"]
    commands = {item["id"]: item for item in values}
    if set(commands) != set(SOURCE_REPORTS):
        raise EvidenceError("P3-BATCH-3 focused command IDs drifted")
    return commands


def _assert_candidate(value: str, field: str = "candidate commit") -> str:
    normalized = value.strip().lower()
    if not SHA_PATTERN.fullmatch(normalized):
        raise EvidenceError(f"{field} must be a full lowercase 40-character Git SHA")
    return normalized


def _assert_timestamp(value: str, field: str) -> str:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exception:
        raise EvidenceError(f"{field} must be an ISO-8601 timestamp") from exception
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise EvidenceError(f"{field} must include an explicit UTC offset")
    return value


def _git(*arguments: str, repository: Path = ROOT) -> str:
    process = subprocess.run(
        ["git", *arguments],
        cwd=repository,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    if process.returncode:
        raise EvidenceError(
            f"git {' '.join(arguments)} failed: {process.stderr.strip()}"
        )
    return process.stdout.strip()


def assert_base_ancestor(
    base_commit: str, candidate_commit: str, repository: Path = ROOT
) -> None:
    process = subprocess.run(
        ["git", "merge-base", "--is-ancestor", base_commit, candidate_commit],
        cwd=repository,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    if process.returncode:
        raise EvidenceError(
            f"base commit {base_commit} is not an ancestor of candidate {candidate_commit}"
        )


def assert_clean_candidate(repository: Path = ROOT) -> str:
    candidate = _assert_candidate(_git("rev-parse", "HEAD", repository=repository))
    status = _git(
        "status",
        "--porcelain=v1",
        "--untracked-files=all",
        repository=repository,
    )
    if status:
        raise EvidenceError("candidate repository is not clean:\n" + status)
    return candidate


def assert_candidate_unchanged(candidate_commit: str, repository: Path = ROOT) -> None:
    current = _assert_candidate(_git("rev-parse", "HEAD", repository=repository))
    if current != candidate_commit:
        raise EvidenceError(
            f"candidate changed during verification: {candidate_commit} -> {current}"
        )
    for arguments in (("diff", "--quiet"), ("diff", "--cached", "--quiet")):
        process = subprocess.run(["git", *arguments], cwd=repository, check=False)
        if process.returncode:
            raise EvidenceError("tracked worktree changed during candidate verification")


def _run_shell(command_id: str, command: str, cwd: Path) -> CommandResult:
    started_at = _utc_now()
    started = time.perf_counter()
    process = subprocess.run(command, cwd=cwd, shell=True, check=False)
    duration = time.perf_counter() - started
    finished_at = _utc_now()
    return CommandResult(
        command_id=command_id,
        cwd=cwd.relative_to(ROOT).as_posix() if cwd != ROOT else ".",
        command_sha256=hashlib.sha256(command.encode("utf-8")).hexdigest(),
        started_at=started_at,
        finished_at=finished_at,
        duration_seconds=round(duration, 3),
        exit_code=process.returncode,
    )


def _quoted_path(path: Path) -> str:
    return '"' + str(path.resolve()).replace('"', '\\"') + '"'


def run_source_suites(
    *,
    matrix: dict[str, Any],
    output_dir: Path,
    candidate_commit: str,
    release_id: str,
) -> tuple[dict[str, JUnitReport], list[CommandResult]]:
    commands = _focused_commands(matrix)
    work_dir = output_dir / ".work"
    work_dir.mkdir(parents=True, exist_ok=False)
    reports: dict[str, JUnitReport] = {}
    results: list[CommandResult] = []
    completed = False
    try:
        for command_id in ("python_phase_3", "root_phase_3_static"):
            item = commands[command_id]
            raw_path = work_dir / f"{command_id}-raw.xml"
            command = item["command"].rstrip() + f" --junitxml={_quoted_path(raw_path)}"
            cwd = (ROOT / item["cwd"]).resolve()
            result = _run_shell(command_id, command, cwd)
            results.append(result)
            assert_candidate_unchanged(candidate_commit)
            if result.exit_code:
                raise EvidenceError(
                    f"{command_id} failed with exit code {result.exit_code}; "
                    f"raw report: {raw_path}"
                )
            destination = output_dir / SOURCE_REPORTS[command_id]
            report = normalize_source_reports(
                [raw_path],
                destination,
                candidate_commit=candidate_commit,
                command_id=command_id,
            )
            _assert_python_selectors(report, item["command"], command_id)
            reports[command_id] = report

        command_id = "java_phase_3"
        item = commands[command_id]
        suffix_seed = hashlib.sha256(release_id.encode("utf-8")).hexdigest()[:8]
        suffix = f"phase3-{candidate_commit[:12]}-{suffix_seed}"
        report_dir = (ROOT / item["cwd"] / "target/surefire-reports").resolve()
        stale = list(report_dir.glob(f"TEST-*-{suffix}.xml")) if report_dir.exists() else []
        if stale:
            raise EvidenceError(
                "candidate-specific Surefire reports already exist: "
                + ", ".join(str(path) for path in stale)
            )
        command = item["command"].rstrip()
        if not re.search(r"\btest$", command):
            raise EvidenceError("java_phase_3 command must end in the Maven test goal")
        command = re.sub(
            r"\btest$",
            f"-Dsurefire.reportNameSuffix={suffix} test",
            command,
        )
        result = _run_shell(command_id, command, (ROOT / item["cwd"]).resolve())
        results.append(result)
        assert_candidate_unchanged(candidate_commit)
        if result.exit_code:
            raise EvidenceError(f"{command_id} failed with exit code {result.exit_code}")
        raw_paths = sorted(report_dir.glob(f"TEST-*-{suffix}.xml"))
        if not raw_paths:
            raise EvidenceError(
                f"java_phase_3 produced no candidate-specific Surefire reports ({suffix})"
            )
        destination = output_dir / SOURCE_REPORTS[command_id]
        report = normalize_source_reports(
            raw_paths,
            destination,
            candidate_commit=candidate_commit,
            command_id=command_id,
        )
        _assert_java_classes(report, item["command"])
        reports[command_id] = report
        completed = True
    finally:
        if completed and work_dir.exists():
            shutil.rmtree(work_dir)
    return reports, results


def load_bound_source_reports(
    output_dir: Path,
    candidate_commit: str,
    matrix: dict[str, Any],
) -> dict[str, JUnitReport]:
    commands = _focused_commands(matrix)
    reports: dict[str, JUnitReport] = {}
    for command_id, filename in SOURCE_REPORTS.items():
        report = parse_junit(output_dir / filename)
        if report.candidate_commit != candidate_commit:
            raise EvidenceError(
                f"{filename}: candidate binding {report.candidate_commit!r} "
                f"does not match {candidate_commit}"
            )
        if report.command_id != command_id:
            raise EvidenceError(
                f"{filename}: command binding {report.command_id!r} does not match {command_id}"
            )
        if command_id == "java_phase_3":
            _assert_java_classes(report, commands[command_id]["command"])
        else:
            _assert_python_selectors(report, commands[command_id]["command"], command_id)
        reports[command_id] = report
    return reports


def _require_green_source_reports(reports: dict[str, JUnitReport]) -> None:
    for command_id, report in reports.items():
        totals = report.totals
        if totals["failures"] or totals["errors"] or totals["skipped"]:
            raise EvidenceError(
                f"{command_id} is not an all-pass, zero-skip source report: {totals}"
            )


def write_derived_reports(
    *,
    matrix: dict[str, Any],
    reports: dict[str, JUnitReport],
    output_dir: Path,
    candidate_commit: str,
) -> dict[str, JUnitReport]:
    python_report = reports["python_phase_3"]
    static_report = reports["root_phase_3_static"]
    batch_1 = matrix["batches"]["P3-BATCH-1"]
    batch_2 = matrix["batches"]["P3-BATCH-2"]
    cases_by_batch = {
        "P3-BATCH-1": (
            *select_cases(
                python_report,
                batch_1["python_tests"],
                context="P3-BATCH-1 python_tests",
            ),
            *select_cases(
                static_report,
                batch_1["static_tests"],
                context="P3-BATCH-1 static_tests",
            ),
        ),
        "P3-BATCH-2": select_cases(
            python_report,
            batch_2["python_tests"],
            context="P3-BATCH-2 python_tests",
        ),
        "P3-BATCH-3": tuple(
            case
            for command_id in SOURCE_REPORTS
            for case in reports[command_id].cases
        ),
    }
    source_hashes = {
        report.path.name: _sha256(report.path) for report in reports.values()
    }
    derived: dict[str, JUnitReport] = {}
    for batch_id, cases in cases_by_batch.items():
        identities = [case.identity for case in cases]
        if len(identities) != len(set(identities)):
            raise EvidenceError(f"{batch_id}: derived view contains duplicate testcases")
        filename = DERIVED_REPORTS[batch_id]
        derived[batch_id] = write_junit(
            output_dir / filename,
            name=filename.removesuffix(".xml"),
            cases=cases,
            candidate_commit=candidate_commit,
            source_hashes=source_hashes,
        )
    return derived


def _phase_check_owners(matrix: dict[str, Any]) -> dict[str, str]:
    owners: dict[str, str] = {}
    for owner, agent in matrix["agents"].items():
        for check_id in agent["check_ids"]:
            if check_id in owners:
                raise EvidenceError(f"check ID {check_id} has multiple owners")
            owners[check_id] = owner
    return owners


def build_check_coverage(
    *,
    matrix: dict[str, Any],
    policy: dict[str, Any],
    candidate_commit: str,
    reports: dict[str, JUnitReport],
) -> dict[str, Any]:
    if policy.get("schema_version") != "phase3-engineering-evidence-policy.v1":
        raise EvidenceError(f"{POLICY_PATH}: unsupported schema_version")
    owners = _phase_check_owners(matrix)
    defaults = policy.get("defaults", {})
    overrides = policy.get("overrides", {})
    unknown_overrides = set(overrides) - set(owners)
    if unknown_overrides:
        raise EvidenceError(
            "evidence policy overrides unknown Phase 3 IDs: "
            + ", ".join(sorted(unknown_overrides))
        )
    missing_overrides = set(owners) - set(overrides)
    if missing_overrides:
        raise EvidenceError(
            "evidence policy lacks explicit mappings for Phase 3 IDs: "
            + ", ".join(sorted(missing_overrides))
        )
    known_reports = {report.path.name: report for report in reports.values()}
    checks: list[dict[str, Any]] = []
    for check_id in sorted(owners):
        prefix = check_id.split("-", 1)[0]
        if prefix not in defaults:
            raise EvidenceError(f"evidence policy has no default for {prefix}")
        entry = {**defaults[prefix], **overrides.get(check_id, {})}
        status = entry.get("status")
        evidence = entry.get("evidence", [])
        note = entry.get("note")
        selectors = entry.get("test_selectors", [])
        if status not in ALLOWED_CHECK_STATUSES:
            raise EvidenceError(f"{check_id}: unsupported status {status!r}")
        if check_id == "MIG-003" and status != "PENDING_PROMOTION":
            raise EvidenceError("MIG-003 must remain PENDING_PROMOTION in Phase 3 evidence")
        if not isinstance(evidence, list) or any(item not in known_reports for item in evidence):
            raise EvidenceError(f"{check_id}: evidence must name source reports only")
        if status == "PASS_ENGINEERING" and not evidence:
            raise EvidenceError(f"{check_id}: PASS_ENGINEERING requires evidence")
        if status != "PASS_ENGINEERING" and not note:
            raise EvidenceError(f"{check_id}: {status} requires a note")
        if selectors:
            nodes = {
                case.node_id
                for report_name in evidence
                for case in known_reports[report_name].cases
            }
            missing = [
                selector
                for selector in selectors
                if not any(fnmatch.fnmatch(node, selector) for node in nodes)
            ]
            if missing:
                raise EvidenceError(
                    f"{check_id}: evidence selectors did not run: {', '.join(missing)}"
                )
        check = {
            "id": check_id,
            "owner": owners[check_id],
            "status": status,
            "evidence": evidence,
        }
        if selectors:
            check["test_selectors"] = selectors
        if note:
            check["note"] = note
        checks.append(check)

    summary = Counter(check["status"] for check in checks)
    external_gates = policy.get("external_gates", [])
    for gate in external_gates:
        if gate.get("status") not in {"PENDING_EXTERNAL", "PENDING_PROMOTION"}:
            raise EvidenceError(
                f"external gate {gate.get('id')!r} must remain pending"
            )
        if not gate.get("note"):
            raise EvidenceError(f"external gate {gate.get('id')!r} requires a note")
    return {
        "schema_version": "temporal-first-check-id-coverage.v1",
        "phase": 3,
        "candidate_commit": candidate_commit,
        "scope": policy["scope"],
        "summary": {
            status: summary[status] for status in sorted(ALLOWED_CHECK_STATUSES)
        }
        | {"total": len(checks)},
        "checks": checks,
        "external_gates": external_gates,
    }


def _change_summary(base_commit: str, candidate_commit: str) -> dict[str, int]:
    base = _assert_candidate(base_commit, "base commit")
    _git("cat-file", "-e", f"{base}^{{commit}}")
    commits = int(_git("rev-list", "--count", f"{base}..{candidate_commit}") or "0")
    numstat = _git("diff", "--numstat", base, candidate_commit)
    files = insertions = deletions = 0
    for line in numstat.splitlines():
        if not line:
            continue
        added, removed, _ = line.split("\t", 2)
        files += 1
        if added != "-":
            insertions += int(added)
        if removed != "-":
            deletions += int(removed)
    return {
        "commits": commits,
        "files_changed": files,
        "insertions": insertions,
        "deletions": deletions,
    }


def build_phase_metrics(
    *,
    release_id: str,
    base_commit: str,
    candidate_commit: str,
    engineering_started_at: str,
    verification_started_at: str,
    verification_finished_at: str,
    reports: dict[str, JUnitReport],
    derived: dict[str, JUnitReport],
    commands: Sequence[CommandResult],
) -> dict[str, Any]:
    source_entries = []
    all_cases: list[TestCase] = []
    counts_by_command: dict[str, int] = {}
    for command_id, filename in SOURCE_REPORTS.items():
        report = reports[command_id]
        totals = report.totals
        all_cases.extend(report.cases)
        counts_by_command[command_id] = int(totals["tests"])
        source_entries.append(
            {
                "name": filename,
                "command_id": command_id,
                **{field: totals[field] for field in ("tests", "failures", "errors", "skipped")},
                "sha256": _sha256(report.path),
            }
        )
    identities = [case.identity for case in all_cases]
    if len(identities) != len(set(identities)):
        raise EvidenceError("source reports contain duplicate cross-source testcase identities")
    verification_totals = _totals(all_cases)
    if any(result.exit_code for result in commands):
        raise EvidenceError("cannot create PASS checkpoint from a failed source command")

    batch_entries = []
    for batch_id, filename in DERIVED_REPORTS.items():
        report = derived[batch_id]
        totals = report.totals
        batch_entries.append(
            {
                "id": batch_id,
                **{field: totals[field] for field in ("tests", "failures", "errors", "skipped")},
                "report": filename,
                "sha256": _sha256(report.path),
            }
        )
    started = datetime.fromisoformat(verification_started_at.replace("Z", "+00:00"))
    finished = datetime.fromisoformat(verification_finished_at.replace("Z", "+00:00"))
    if finished < started:
        raise EvidenceError("verification_finished_at predates verification_started_at")
    return {
        "schema_version": "temporal-first-phase-metrics.v1",
        "release_id": release_id,
        "phase": 3,
        "name": "graph-governed-lcel",
        "scope": "SIGNED_SYNTHETIC_SHADOW_ENGINEERING_ONLY",
        "base_commit": _assert_candidate(base_commit, "base commit"),
        "candidate_commit": candidate_commit,
        "engineering_started_at": _assert_timestamp(
            engineering_started_at, "engineering_started_at"
        ),
        "verification_started_at": _assert_timestamp(
            verification_started_at, "verification_started_at"
        ),
        "verification_finished_at": _assert_timestamp(
            verification_finished_at, "verification_finished_at"
        ),
        "verification_wall_clock_seconds": round((finished - started).total_seconds(), 3),
        "change_summary": _change_summary(base_commit, candidate_commit),
        "candidate_verification": {
            "deduplicated_execution": True,
            "distinct_tests": verification_totals["tests"],
            "python_tests": counts_by_command["python_phase_3"],
            "static_tests": counts_by_command["root_phase_3_static"],
            "java_tests": counts_by_command["java_phase_3"],
            "failures": verification_totals["failures"],
            "errors": verification_totals["errors"],
            "skipped": verification_totals["skipped"],
        },
        "commands": [result.as_dict() for result in commands],
        "batch_views": batch_entries,
        "source_reports": source_entries,
        "status": {
            "engineering_checkpoint": "PASS",
            "promotion_gate": "PENDING",
            "next_phase_permission": "PHASE_4_ENGINEERING_ONLY",
        },
    }


def render_checkpoint(
    metrics: dict[str, Any],
    coverage: dict[str, Any],
    evidence_dir: Path,
) -> str:
    verification = metrics["candidate_verification"]
    partial = [
        check["id"]
        for check in coverage["checks"]
        if check["status"] != "PASS_ENGINEERING"
        and check["id"] != "MIG-003"
    ]
    gates = [gate["id"] for gate in coverage["external_gates"]]
    evidence_path = evidence_dir.relative_to(ROOT).as_posix()
    partial_text = ", ".join(f"`{item}`" for item in partial) or "None"
    gate_text = ", ".join(f"`{item}`" for item in gates) or "None"
    return f"""# Phase 3 Graph And Governed LCEL Engineering Checkpoint

- Candidate: `{metrics['candidate_commit']}`
- Evidence: `{evidence_path}/`
- Runtime scope: signed synthetic `SHADOW`; default remains `DISABLED`

```text
engineering_checkpoint: PASS
promotion_gate: PENDING
next_phase_permission: PHASE_4_ENGINEERING_ONLY
```

The candidate ran one deduplicated source execution with
{verification['python_tests']} Python, {verification['static_tests']} static, and
{verification['java_tests']} Java tests. All {verification['distinct_tests']} tests passed with no
failure, error, or skip. `P3-BATCH-1` through `P3-BATCH-3` are derived views of those source
reports and carry the same candidate SHA.

This checkpoint is an engineering result, not a production promotion. Conservative Phase 3 check
statuses that retain an external or production-equivalent facet are: {partial_text}. External gates
remain open for: {gate_text}. Exact evidence and limitations are recorded in
`check-id-coverage.json`.

`MIG-003` remains `PENDING_PROMOTION`. This checkpoint does not authorize a formal room writer,
room migration, production traffic, or Python access to the Domain database. Phase 4 may proceed
for engineering work only under the same `DISABLED` or signed synthetic `SHADOW` restrictions.
"""


def assemble_evidence(
    *,
    matrix: dict[str, Any],
    policy: dict[str, Any],
    output_dir: Path,
    checkpoint_path: Path,
    release_id: str,
    base_commit: str,
    candidate_commit: str,
    engineering_started_at: str,
    verification_started_at: str,
    verification_finished_at: str,
    reports: dict[str, JUnitReport],
    commands: Sequence[CommandResult],
) -> tuple[dict[str, Any], dict[str, Any]]:
    _require_green_source_reports(reports)
    derived = write_derived_reports(
        matrix=matrix,
        reports=reports,
        output_dir=output_dir,
        candidate_commit=candidate_commit,
    )
    coverage = build_check_coverage(
        matrix=matrix,
        policy=policy,
        candidate_commit=candidate_commit,
        reports=reports,
    )
    metrics = build_phase_metrics(
        release_id=release_id,
        base_commit=base_commit,
        candidate_commit=candidate_commit,
        engineering_started_at=engineering_started_at,
        verification_started_at=verification_started_at,
        verification_finished_at=verification_finished_at,
        reports=reports,
        derived=derived,
        commands=commands,
    )
    _write_json(output_dir / "phase-metrics.json", metrics)
    _write_json(output_dir / "check-id-coverage.json", coverage)
    (output_dir / "candidate-commit.txt").write_text(
        candidate_commit + "\n", encoding="utf-8"
    )
    checkpoint_path.write_text(
        render_checkpoint(metrics, coverage, output_dir), encoding="utf-8"
    )
    required = set(
        matrix["batches"]["P3-BATCH-3"]["evidence"]["required_files"]
    )
    actual = {path.name for path in output_dir.iterdir() if path.is_file()}
    if actual != required:
        raise EvidenceError(
            f"Phase 3 evidence file set mismatch: missing={sorted(required - actual)}, "
            f"unexpected={sorted(actual - required)}"
        )
    return metrics, coverage


def _release_id(value: str) -> str:
    if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{2,79}", value):
        raise argparse.ArgumentTypeError(
            "release ID must be 3-80 lowercase letters, digits, dots, underscores, or hyphens"
        )
    return value


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Run and assemble a commit-bound Phase 3 engineering evidence checkpoint."
    )
    parser.add_argument("--release-id", required=True, type=_release_id)
    parser.add_argument("--base-commit", required=True)
    parser.add_argument("--engineering-started-at", required=True)
    parser.add_argument(
        "--output-dir",
        type=Path,
        help="Defaults to test-reports/temporal-first/<release-id>/phase-3.",
    )
    parser.add_argument(
        "--checkpoint-doc", type=Path, default=DEFAULT_CHECKPOINT_PATH
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    output_dir = (
        arguments.output_dir
        or ROOT / "test-reports/temporal-first" / arguments.release_id / "phase-3"
    ).resolve()
    checkpoint_path = arguments.checkpoint_doc.resolve()
    try:
        matrix = load_matrix()
        policy = _load_yaml(POLICY_PATH)
        candidate_commit = assert_clean_candidate()
        base_commit = _assert_candidate(arguments.base_commit, "base commit")
        assert_base_ancestor(base_commit, candidate_commit)
        _assert_timestamp(arguments.engineering_started_at, "engineering_started_at")
        if output_dir.exists():
            raise EvidenceError(f"evidence output already exists: {output_dir}")
        if checkpoint_path.exists():
            raise EvidenceError(f"checkpoint document already exists: {checkpoint_path}")
        output_dir.mkdir(parents=True)
        verification_started_at = _utc_now()
        reports, commands = run_source_suites(
            matrix=matrix,
            output_dir=output_dir,
            candidate_commit=candidate_commit,
            release_id=arguments.release_id,
        )
        verification_finished_at = _utc_now()
        assert_candidate_unchanged(candidate_commit)
        metrics, _ = assemble_evidence(
            matrix=matrix,
            policy=policy,
            output_dir=output_dir,
            checkpoint_path=checkpoint_path,
            release_id=arguments.release_id,
            base_commit=base_commit,
            candidate_commit=candidate_commit,
            engineering_started_at=arguments.engineering_started_at,
            verification_started_at=verification_started_at,
            verification_finished_at=verification_finished_at,
            reports=reports,
            commands=commands,
        )
    except (EvidenceError, OSError) as exception:
        print(f"Phase 3 evidence rejected: {exception}", file=sys.stderr)
        return 2
    print(
        json.dumps(
            {
                "candidate_commit": metrics["candidate_commit"],
                "engineering_checkpoint": "PASS",
                "promotion_gate": "PENDING",
                "evidence_dir": str(output_dir),
                "checkpoint_doc": str(checkpoint_path),
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
