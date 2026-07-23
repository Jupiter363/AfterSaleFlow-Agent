from __future__ import annotations

import copy
import importlib.util
import json
import subprocess
import sys
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/run_phase6_entry_checkpoint.py"
SPEC = importlib.util.spec_from_file_location("run_phase6_entry_checkpoint", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
runner = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = runner
SPEC.loader.exec_module(runner)

CANDIDATE = "a" * 40


def _ready_matrix() -> dict:
    matrix = copy.deepcopy(runner.load_matrix())
    matrix["document_status"] = "ENTRY_CANDIDATE_READY"
    matrix["gate"]["entry_decision"] = "CONTRACT_CANDIDATE_READY"
    matrix["gate"]["observed_entry_state"] = {
        "phase_5_engineering_checkpoint": "PASS",
        "phase_6_engineering_exception": "ADR_0015_ACCEPTED_FOR_ENGINEERING_ONLY",
        "next_phase_permission": "PHASE_6_ENGINEERING_ONLY",
        "MIG-004": "PENDING_PROMOTION",
        "MIG-005": "PENDING_PROMOTION",
        "P6.0": "NOT_RUN",
        "MIG-006": "PENDING_PROMOTION",
    }
    matrix["batches"]["batch_0_entry"]["status"] = "READY_FOR_EXACT_SHA_BATCH_0"
    return matrix


def test_accepted_plan_lists_exact_sources_but_blocks_a_second_execution() -> None:
    plan = runner.entry_plan(CANDIDATE)

    assert plan["phase"] == 6
    assert plan["candidate_commit"] == CANDIDATE
    assert plan["execution_allowed"] is False
    assert plan["executed_source_count"] == 0
    assert plan["execution_order"] == list(runner.COMMAND_ORDER)
    assert {item["id"]: item["report"] for item in plan["commands"]} == (
        runner.SOURCE_REPORTS
    )
    assert "P6_CONTRACT_CANDIDATE_NOT_READY" in plan["blocked_reasons"]
    assert "P6_BATCH_0_NOT_READY" in plan["blocked_reasons"]
    assert plan["runtime_restrictions"] == {
        "real_case_data": "forbidden",
        "real_case_shadow": "forbidden",
        "temporal_hearing_allocation": "forbidden",
        "formal_hearing_graph_sink": "forbidden",
        "canary_or_promotion": "forbidden",
    }


def test_execute_rejects_blocked_gate_before_any_source_or_run_directory(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    calls: list[str] = []
    monkeypatch.setattr(
        runner,
        "_record_source",
        lambda **_: calls.append("source") or ({}, False),
    )
    run_root = tmp_path / "must-not-exist"

    with pytest.raises(
        runner.EvidenceError, match="Phase 6 entry execution is blocked"
    ):
        runner.execute_checkpoint(
            candidate_commit=CANDIDATE,
            run_root=run_root,
            environment_id="blocked-static-test",
            resume=False,
            classifications=(),
        )

    assert calls == []
    assert not run_root.exists()


def test_cli_help_and_accepted_plan_do_not_execute_sources() -> None:
    help_result = subprocess.run(
        [sys.executable, str(SCRIPT), "--help"],
        cwd=ROOT,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    plan_result = subprocess.run(
        [sys.executable, str(SCRIPT), "--candidate-commit", CANDIDATE],
        cwd=ROOT,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    assert help_result.returncode == 0
    assert plan_result.returncode == 0
    plan = json.loads(plan_result.stdout)
    assert plan["execution_allowed"] is False
    assert plan["executed_source_count"] == 0


def test_gate_requires_phase5_authority_permission_candidate_and_ready_batch() -> None:
    matrix = _ready_matrix()
    assert runner.gate_blockers(matrix) == []

    for mutation in (
        lambda item: item["gate"]["observed_entry_state"].update(
            phase_5_engineering_checkpoint="NOT_RECORDED"
        ),
        lambda item: item["gate"]["observed_entry_state"].update(
            phase_6_engineering_exception="NOT_RECORDED"
        ),
        lambda item: item["gate"].update(
            accepted_phase_5_checkpoint_sha="0" * 40
        ),
        lambda item: item["gate"]["observed_entry_state"].update(
            next_phase_permission="BLOCKED"
        ),
        lambda item: item["gate"].update(entry_decision="BLOCKED"),
        lambda item: item["batches"]["batch_0_entry"].update(
            status="CONTRACT_CANDIDATE_ASSEMBLY"
        ),
    ):
        changed = _ready_matrix()
        mutation(changed)
        assert runner.gate_blockers(changed)


def test_report_path_with_spaces_and_metacharacters_remains_one_argv(
    tmp_path: Path,
) -> None:
    item = runner._source_contracts(runner.load_matrix())["python_phase6_entry"]
    raw_path = tmp_path / "space & literal report.xml"

    arguments = runner._format_command(item, raw_path, "unused")

    assert arguments[-1] == f"--junitxml={raw_path.resolve()}"
    assert arguments.count(arguments[-1]) == 1


def test_manifest_is_sealed_and_only_same_sha_infra_can_resume(
    tmp_path: Path,
) -> None:
    run_root = tmp_path / "phase6-entry-attempt"
    run_root.mkdir()
    manifest = runner._initial_manifest(
        candidate=CANDIDATE,
        environment_id="seal-test",
        run_root=run_root,
    )
    manifest["pending_failure"] = {
        "id": "java_phase6_entry",
        "candidate_commit": CANDIDATE,
        "exit_code": 1,
        "failure_classification": "UNCLASSIFIED",
    }
    runner._write_manifest(run_root / runner.MANIFEST_NAME, manifest)
    persisted = json.loads(
        (run_root / runner.MANIFEST_NAME).read_text(encoding="utf-8")
    )
    runner._assert_execution_manifest_seal(persisted)

    assert (
        runner._classify_pending_failure(persisted, {"java_phase6_entry": "INFRA"})
        is True
    )
    assert persisted["pending_failure"] is None
    assert persisted["quarantined_attempts"][0]["failure_classification"] == "INFRA"
    assert persisted["quarantined_attempts_reused"] is False

    blocked = copy.deepcopy(manifest)
    assert (
        runner._classify_pending_failure(blocked, {"java_phase6_entry": "PRODUCT"})
        is False
    )
    assert blocked["status"] == "CANDIDATE_BLOCKED"


def test_resume_revalidates_quarantined_infra_artifact_hashes(tmp_path: Path) -> None:
    run_root = tmp_path / "phase6-infra-artifacts"
    attempt_root = run_root / "attempts" / "java_phase6_entry-01"
    attempt_root.mkdir(parents=True)
    stdout = attempt_root / "stdout.log"
    stderr = attempt_root / "stderr.log"
    stdout.write_text("original stdout\n", encoding="utf-8")
    stderr.write_text("original stderr\n", encoding="utf-8")
    manifest = runner._initial_manifest(
        candidate=CANDIDATE,
        environment_id="infra-artifact-test",
        run_root=run_root,
    )
    manifest["status"] = "RUNNING"
    manifest["quarantined_attempts"] = [
        {
            "id": "java_phase6_entry",
            "candidate_commit": CANDIDATE,
            "failure_classification": "INFRA",
            "stdout_path": stdout.relative_to(run_root).as_posix(),
            "stdout_sha256": runner._sha256(stdout),
            "stderr_path": stderr.relative_to(run_root).as_posix(),
            "stderr_sha256": runner._sha256(stderr),
            "raw_reports": [],
        }
    ]
    runner._write_manifest(run_root / runner.MANIFEST_NAME, manifest)
    stdout.write_text("tampered stdout\n", encoding="utf-8")

    with pytest.raises(runner.EvidenceError, match="stdout SHA-256 drifted"):
        runner._load_resume_manifest(run_root, CANDIDATE)


def test_hypothetical_ready_gate_schedules_all_four_without_claiming_entry_pass(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    matrix = _ready_matrix()
    observed: list[str] = []
    monkeypatch.setattr(runner, "load_matrix", lambda: matrix)
    monkeypatch.setattr(
        runner, "assert_clean_detached_candidate", lambda *_args, **_kw: None
    )
    monkeypatch.setattr(runner, "_assert_candidate_unchanged", lambda *_: None)
    monkeypatch.setattr(
        runner,
        "capture_environment",
        lambda _: {"snapshot_sha256": "c" * 64},
    )

    def record_source(**arguments: object) -> tuple[dict[str, object], bool]:
        command_id = str(arguments["command_id"])
        observed.append(command_id)
        return {
            "id": command_id,
            "candidate_commit": CANDIDATE,
            "accepted": True,
        }, True

    monkeypatch.setattr(runner, "_record_source", record_source)
    manifest = runner.execute_checkpoint(
        candidate_commit=CANDIDATE,
        run_root=tmp_path / "fresh-phase6-entry",
        environment_id="ready-static-test",
        resume=False,
        classifications=(),
    )

    assert observed == list(runner.COMMAND_ORDER)
    assert manifest["status"] == runner.GREEN_STATUS
    assert manifest["contract_gate"] == "P6.0_NOT_RUN"
    assert manifest["implementation_authorized"] is False
    assert manifest["MIG-004"] == "PENDING_PROMOTION"
    assert manifest["MIG-005"] == "PENDING_PROMOTION"
    assert manifest["MIG-006"] == "PENDING_PROMOTION"
    assert "PASS" not in manifest["status"]
