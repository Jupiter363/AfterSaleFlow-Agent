from __future__ import annotations

import xml.etree.ElementTree as ET
from pathlib import Path

import pytest

from scripts import run_phase6_candidate_checkpoint as runner


def _junit(path: Path, *, tests: int, failures: int = 0) -> None:
    path.write_text(
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
        f"<testsuite name=\"fixture\" tests=\"{tests}\" failures=\"{failures}\" "
        "errors=\"0\" skipped=\"0\">\n"
        "  <testcase classname=\"fixture.Case\" name=\"passes\" />\n"
        "</testsuite>\n",
        encoding="utf-8",
        newline="\n",
    )


def test_candidate_runner_discovers_the_complete_hearing_scope() -> None:
    java = set(runner.discover_java_tests())
    python = runner.discover_python_tests()

    assert {
        "HearingFlowRuntimeServiceTest",
        "HearingRoomWorkflowTest",
        "HearingTemporalLedgerIntegrationTest",
        "HearingFlowControllerTest",
        "HearingSchedulerModeTest",
        "HearingReliabilityHarnessTest",
        "DisputeImportServiceIntegrationTest",
        "EvidenceCompletionServiceTest",
    }.issubset(java)
    assert 20 <= len(java) <= 60
    assert "tests/graphs/hearing" in python
    assert "tests/agents/test_hearing_flow_v2.py" in python
    assert not any("e2e" in value.lower() or "live" in value.lower() for value in python)


def test_candidate_runner_respects_the_test_process_budget() -> None:
    commands = runner.command_specs()

    assert tuple(commands) == runner.COMMAND_ORDER
    assert Path(commands["java"].argv[0]).is_absolute()
    assert Path(commands["java"].argv[0]).is_file()
    assert commands["java"].argv.count("-DforkCount=1") == 1
    assert "--minWorkers=1" in commands["frontend"].argv
    assert "--maxWorkers=2" in commands["frontend"].argv
    assert commands["static"].argv[-1] == "-q"
    rendered = " ".join(value for spec in commands.values() for value in spec.argv).lower()
    assert "playwright" not in rendered
    assert "docker compose" not in rendered
    assert "real_case_shadow" not in rendered
    assert "canary" not in rendered


def test_candidate_runner_merges_and_binds_junit(tmp_path: Path) -> None:
    first = tmp_path / "first.xml"
    second = tmp_path / "second.xml"
    target = tmp_path / "merged.xml"
    _junit(first, tests=2)
    _junit(second, tests=3)

    totals = runner.merge_junit(
        [first, second],
        target,
        candidate="a" * 40,
        command_id="java",
    )

    assert totals == {"tests": 5, "failures": 0, "errors": 0, "skipped": 0}
    root = ET.parse(target).getroot()
    assert root.attrib["candidate_commit"] == "a" * 40
    assert root.attrib["source_command_id"] == "java"
    assert len(root.findall("testsuite")) == 2


def test_candidate_runner_rejects_missing_reports(tmp_path: Path) -> None:
    with pytest.raises(runner.CheckpointError, match="no JUnit"):
        runner.merge_junit(
            [],
            tmp_path / "merged.xml",
            candidate="a" * 40,
            command_id="python",
        )


def test_candidate_summary_never_promotes_synthetic_hearing() -> None:
    records = [
        {
            "id": command_id,
            "report": {
                "path": runner.REPORT_NAMES[command_id],
                "sha256": "a" * 64,
                "tests": 1,
                "failures": 0,
                "errors": 0,
                "skipped": 0,
            },
        }
        for command_id in runner.COMMAND_ORDER
    ]

    summary = runner._verification_summary("b" * 40, records)

    assert summary["engineering_checkpoint"] == "PASS"
    assert summary["next_phase_permission"] == "PHASE_7_ENGINEERING_ONLY"
    assert summary["promotion_gate"] == "PENDING"
    assert summary["MIG-006"] == "PENDING_PROMOTION"
    assert summary["runtime_restrictions"]["temporal_hearing_allocation"] == "forbidden"
    assert summary["invariants"]["REAL_SHADOW_CANARY_AND_PROMOTION"]["status"] == (
        "PENDING_PROMOTION"
    )
