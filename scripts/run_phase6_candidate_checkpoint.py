from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Sequence


ROOT = Path(__file__).resolve().parents[1]
SCHEMA_VERSION = "phase6-candidate-execution-manifest.v1"
SUMMARY_VERSION = "phase6-engineering-verification-summary.v1"
COMMAND_ORDER = ("java", "python", "frontend", "static")
REPORT_NAMES = {
    "java": "java-phase6-junit.xml",
    "python": "python-phase6-junit.xml",
    "frontend": "frontend-phase6-junit.xml",
    "static": "static-phase6-junit.xml",
}
STATIC_TESTS = (
    "tests/static/test_phase6_hearing_contracts.py",
    "tests/static/test_phase6_hearing_pilot_plan.py",
    "tests/static/test_phase6_entry_checkpoint.py",
    "tests/static/test_phase6_entry_evidence.py",
    "tests/static/test_phase6_p6_0_entry_checkpoint.py",
    "tests/static/test_phase6_candidate_runner.py",
    "tests/static/test_temporal_refactor_traceability.py",
)
FRONTEND_TESTS = (
    "src/views/disputes/HearingCourtView.test.js",
    "src/utils/hearingFlow.test.js",
    "src/api/hearing.test.js",
    "src/stores/hearing.test.js",
)
JAVA_ADDITIONAL_TESTS = (
    "DisputeImportServiceTest",
    "DisputeImportServiceIntegrationTest",
    "SimulatedExternalImportTemplateCycleTest",
    "EvidenceCompletionServiceTest",
)
PENDING_GATES = {
    "engineering_checkpoint": "PASS",
    "promotion_gate": "PENDING",
    "next_phase_permission": "PHASE_7_ENGINEERING_ONLY",
    "MIG-004": "PENDING_PROMOTION",
    "MIG-005": "PENDING_PROMOTION",
    "MIG-006": "PENDING_PROMOTION",
}
RUNTIME_RESTRICTIONS = {
    "allowed_modes": ["LEGACY", "DISABLED", "SIGNED_SYNTHETIC_SHADOW"],
    "temporal_hearing_allocation": "forbidden",
    "formal_graph_sink": "forbidden",
    "real_case_shadow": "forbidden",
    "canary": "forbidden",
    "promotion": "forbidden",
}


class CheckpointError(RuntimeError):
    pass


@dataclass(frozen=True)
class CommandSpec:
    command_id: str
    cwd: Path
    argv: tuple[str, ...]


@dataclass(frozen=True)
class CommandResult:
    command_id: str
    cwd: str
    argv: tuple[str, ...]
    started_at: str
    finished_at: str
    duration_seconds: float
    exit_code: int
    stdout_path: Path
    stderr_path: Path
    raw_reports: tuple[Path, ...]


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _write_json(path: Path, value: object) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def _git(*arguments: str) -> str:
    process = subprocess.run(
        ["git", *arguments],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    return process.stdout.strip()


def assert_candidate(candidate: str) -> str:
    if len(candidate) != 40 or any(value not in "0123456789abcdef" for value in candidate):
        raise CheckpointError("candidate must be a full lowercase Git SHA")
    if _git("rev-parse", "HEAD") != candidate:
        raise CheckpointError("candidate does not match HEAD")
    if _git("status", "--porcelain", "--untracked-files=no"):
        raise CheckpointError("candidate worktree must be clean")
    return candidate


def discover_python_tests() -> tuple[str, ...]:
    root = ROOT / "python-agent-service/tests"
    values = sorted(
        path.relative_to(ROOT / "python-agent-service").as_posix()
        for path in root.rglob("*hearing*.py")
        if "__pycache__" not in path.parts
    )
    graph_root = "tests/graphs/hearing"
    values = [value for value in values if not value.startswith(f"{graph_root}/")]
    return (graph_root, *values)


def discover_java_tests() -> tuple[str, ...]:
    test_root = ROOT / "java-api-service/src/test/java"
    values = {
        path.stem
        for path in test_root.rglob("*Test.java")
        if "hearing" in path.relative_to(test_root).as_posix().lower()
    }
    values.update(JAVA_ADDITIONAL_TESTS)
    return tuple(sorted(values))


def command_specs() -> dict[str, CommandSpec]:
    python = os.environ.get(
        "PHASE6_PYTHON",
        "D:/miniconda/python.exe" if os.name == "nt" else sys.executable,
    )
    maven = str(
        ROOT
        / "java-api-service"
        / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    )
    suffix = "phase6-candidate"
    java_classes = ",".join(discover_java_tests())
    return {
        "java": CommandSpec(
            "java",
            ROOT / "java-api-service",
            (
                maven,
                "-q",
                "-DforkCount=1",
                f"-Dtest={java_classes}",
                f"-Dsurefire.reportNameSuffix={suffix}",
                "test",
            ),
        ),
        "python": CommandSpec(
            "python",
            ROOT / "python-agent-service",
            (python, "-m", "pytest", *discover_python_tests(), "-q"),
        ),
        "frontend": CommandSpec(
            "frontend",
            ROOT / "frontend",
            (
                "node",
                "node_modules/vitest/vitest.mjs",
                "run",
                *FRONTEND_TESTS,
                "--minWorkers=1",
                "--maxWorkers=2",
            ),
        ),
        "static": CommandSpec(
            "static",
            ROOT,
            (python, "-m", "pytest", *STATIC_TESTS, "-q"),
        ),
    }


def _timestamp() -> str:
    return datetime.now(timezone.utc).isoformat()


def _run_command(spec: CommandSpec, run_dir: Path) -> CommandResult:
    command_dir = run_dir / "commands" / spec.command_id
    command_dir.mkdir(parents=True, exist_ok=True)
    stdout = command_dir / "stdout.log"
    stderr = command_dir / "stderr.log"
    raw_junit = command_dir / "raw-junit.xml"
    argv = list(spec.argv)
    if spec.command_id == "java":
        report_root = spec.cwd / "target/surefire-reports"
        for stale in report_root.glob("TEST-*-phase6-candidate.xml"):
            stale.unlink()
    if spec.command_id in {"python", "static"}:
        argv.append(f"--junitxml={raw_junit}")
    elif spec.command_id == "frontend":
        argv.extend(("--reporter=default", "--reporter=junit", f"--outputFile.junit={raw_junit}"))

    started_at = _timestamp()
    started = time.monotonic()
    with stdout.open("wb") as out, stderr.open("wb") as err:
        process = subprocess.run(argv, cwd=spec.cwd, stdout=out, stderr=err, check=False)
    finished_at = _timestamp()
    duration = round(time.monotonic() - started, 3)

    if spec.command_id == "java":
        reports = tuple(
            sorted(
                (spec.cwd / "target/surefire-reports").glob(
                    "TEST-*-phase6-candidate.xml"
                )
            )
        )
    else:
        reports = (raw_junit,) if raw_junit.is_file() else ()
    return CommandResult(
        command_id=spec.command_id,
        cwd=spec.cwd.relative_to(ROOT).as_posix() or ".",
        argv=tuple(argv),
        started_at=started_at,
        finished_at=finished_at,
        duration_seconds=duration,
        exit_code=process.returncode,
        stdout_path=stdout,
        stderr_path=stderr,
        raw_reports=reports,
    )


def _suite_nodes(root: ET.Element) -> list[ET.Element]:
    if root.tag == "testsuite":
        return [root]
    return list(root.findall("testsuite"))


def merge_junit(
    sources: Sequence[Path], target: Path, *, candidate: str, command_id: str
) -> dict[str, int]:
    if not sources:
        raise CheckpointError(f"{command_id}: no JUnit reports were produced")
    suites: list[ET.Element] = []
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for source in sources:
        if not source.is_file():
            raise CheckpointError(f"{command_id}: missing JUnit report {source}")
        root = ET.parse(source).getroot()
        for suite in _suite_nodes(root):
            copied = ET.fromstring(ET.tostring(suite, encoding="utf-8"))
            suites.append(copied)
            for field in totals:
                totals[field] += int(copied.attrib.get(field, "0"))
    document = ET.Element(
        "testsuites",
        {
            **{field: str(value) for field, value in totals.items()},
            "candidate_commit": candidate,
            "source_command_id": command_id,
        },
    )
    document.extend(suites)
    ET.indent(document, space="  ")
    target.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(document).write(target, encoding="utf-8", xml_declaration=True)
    return totals


def _record(result: CommandResult, report: Path, totals: dict[str, int], run_dir: Path) -> dict:
    return {
        "id": result.command_id,
        "cwd": result.cwd,
        "argv": list(result.argv),
        "started_at": result.started_at,
        "finished_at": result.finished_at,
        "duration_seconds": result.duration_seconds,
        "exit_code": result.exit_code,
        "stdout": {
            "path": result.stdout_path.relative_to(run_dir).as_posix(),
            "sha256": _sha256(result.stdout_path),
        },
        "stderr": {
            "path": result.stderr_path.relative_to(run_dir).as_posix(),
            "sha256": _sha256(result.stderr_path),
        },
        "report": {
            "path": report.relative_to(run_dir).as_posix(),
            "sha256": _sha256(report),
            **totals,
        },
        "failure_classification": "NONE",
    }


def _verification_summary(candidate: str, records: Sequence[dict]) -> dict:
    by_id = {record["id"]: record for record in records}
    total = sum(record["report"]["tests"] for record in records)
    return {
        "schema_version": SUMMARY_VERSION,
        "candidate_commit": candidate,
        "total_tests": total,
        "sources": {
            command_id: by_id[command_id]["report"] for command_id in COMMAND_ORDER
        },
        "invariants": {
            "FIFTEEN_STAGE_AND_FOURTEEN_EDGE_PROTOCOL": {
                "status": "PASS_ENGINEERING",
                "sources": ["java", "python"],
            },
            "JAVA_FINALIZER_HASH_FENCE_AUTHORITY": {
                "status": "PASS_ENGINEERING",
                "sources": ["java", "static"],
            },
            "TEMPORAL_REPLAY_TIMER_AND_RECEIPT_RECOVERY": {
                "status": "PASS_ENGINEERING",
                "sources": ["java"],
            },
            "BOUNDED_LANGGRAPH_LCEL_AND_RECOVERY": {
                "status": "PASS_ENGINEERING",
                "sources": ["python"],
            },
            "QUERY_PRIVACY_AND_UI_COMPATIBILITY": {
                "status": "PASS_ENGINEERING",
                "sources": ["java", "frontend"],
            },
            "SIGNED_SYNTHETIC_NO_FORMAL_SINK": {
                "status": "PASS_ENGINEERING_ONLY",
                "sources": ["java", "static"],
            },
            "REAL_SHADOW_CANARY_AND_PROMOTION": {
                "status": "PENDING_PROMOTION",
                "sources": [],
            },
        },
        **PENDING_GATES,
        "runtime_restrictions": RUNTIME_RESTRICTIONS,
    }


def run(candidate: str, output: Path) -> Path:
    candidate = assert_candidate(candidate)
    if output.exists():
        if any(output.iterdir()):
            raise CheckpointError("output directory must be absent or empty")
    else:
        output.mkdir(parents=True)
    started_at = _timestamp()
    specs = command_specs()

    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
        first_wave = {
            command_id: executor.submit(_run_command, specs[command_id], output)
            for command_id in ("java", "python", "frontend")
        }
        results = {command_id: future.result() for command_id, future in first_wave.items()}
    results["static"] = _run_command(specs["static"], output)

    if _git("rev-parse", "HEAD") != candidate or _git(
        "status", "--porcelain", "--untracked-files=no"
    ):
        raise CheckpointError("candidate changed during checkpoint execution")

    records: list[dict] = []
    for command_id in COMMAND_ORDER:
        result = results[command_id]
        if result.exit_code != 0:
            raise CheckpointError(
                f"{command_id} failed with exit code {result.exit_code}; evidence retained at {output}"
            )
        report = output / REPORT_NAMES[command_id]
        totals = merge_junit(
            result.raw_reports,
            report,
            candidate=candidate,
            command_id=command_id,
        )
        if totals["failures"] or totals["errors"] or totals["skipped"]:
            raise CheckpointError(f"{command_id}: JUnit is not entirely green")
        records.append(_record(result, report, totals, output))

    (output / "candidate-commit.txt").write_text(
        candidate + "\n", encoding="utf-8", newline="\n"
    )
    summary = _verification_summary(candidate, records)
    _write_json(output / "phase6-verification-summary.json", summary)
    manifest = {
        "schema_version": SCHEMA_VERSION,
        "phase": 6,
        "batch": "P6.9_ENGINEERING_CANDIDATE",
        "candidate_commit": candidate,
        "status": "PASS",
        "started_at": started_at,
        "finished_at": _timestamp(),
        "execution_topology": "ONE_MAVEN_PLUS_TWO_LIGHT_THEN_STATIC",
        "commands": records,
        **PENDING_GATES,
        "runtime_restrictions": RUNTIME_RESTRICTIONS,
    }
    _write_json(output / "phase6-candidate-execution-manifest.json", manifest)

    artifacts = {}
    for path in sorted(value for value in output.rglob("*") if value.is_file()):
        if path.name == "artifact-sha256.json":
            continue
        artifacts[path.relative_to(output).as_posix()] = _sha256(path)
    _write_json(
        output / "artifact-sha256.json",
        {
            "schema_version": "phase6-candidate-artifact-index.v1",
            "candidate_commit": candidate,
            "artifacts": artifacts,
        },
    )
    return output


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run the Phase 6 engineering checkpoint")
    parser.add_argument("--candidate", required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    try:
        output = run(arguments.candidate, arguments.output.resolve())
    except (CheckpointError, OSError, subprocess.SubprocessError) as exception:
        print(f"phase6 checkpoint failed: {exception}", file=sys.stderr)
        return 1
    print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
