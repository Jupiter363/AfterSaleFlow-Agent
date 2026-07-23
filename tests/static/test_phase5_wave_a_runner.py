from __future__ import annotations

import json
from pathlib import Path

import pytest

from scripts import run_phase5_wave_a_checkpoint as runner


CANDIDATE = "a" * 40
EXPECTED_JAVA_CLASSES = (
    "EvidenceRoomWorkflowTest",
    "EvidenceRoomWorkflowReplayTest",
    "EvidenceRoomActivityContractTest",
    "EvidenceRoomWorkflowWorkerRecoveryTest",
    "EvidenceV2ContractFixtureTest",
    "EvidenceAssetAuthorizationTest",
    "JdbcEvidenceGraphBindingStoreTest",
    "EvidenceGraphCommandFactoryTest",
    "EvidenceGraphResultFinalizerTest",
    "EvidenceFinalizationReceiptTest",
    "EvidenceAgentTurnServiceTest",
    "EvidenceProcessProjectionAdapterTest",
    "EvidenceBulkheadPolicyTest",
    "EvidenceNoFormalSinkGuardTest",
    "EvidenceSubmissionServiceTest",
    "EvidenceCompletionServiceTest",
    "EvidenceDossierFreezerTest",
)


def _task_bindings(candidate: str = CANDIDATE) -> dict:
    return {
        "schema_version": runner.TASK_BINDINGS_SCHEMA,
        "candidate_commit": candidate,
        "tasks": [
            {
                "id": task_id,
                "commit": f"{index:x}" * 40,
                "review_partner": reviewer,
                "p0_review": "PASS",
                "t0": {"result": "PASS", "command_ids": list(command_ids)},
            }
            for index, (task_id, (reviewer, command_ids)) in enumerate(
                runner.TASK_REQUIREMENTS.items(), start=1
            )
        ],
    }


def test_wave_a_plan_is_closed_after_integration_without_opening_candidate() -> None:
    matrix = runner.load_matrix()
    plan = runner.candidate_plan(CANDIDATE)

    assert plan["batch"] == "P5-BATCH-1"
    assert plan["execution_order"] == list(runner.COMMAND_ORDER)
    assert [item["report"] for item in plan["commands"]] == list(
        runner.SOURCE_REPORTS.values()
    )
    assert plan["concurrency"] == {
        "heavy": 1,
        "light": 2,
        "maven_tokens": 1,
        "maven_fork_count": 1,
        "runner_execution": "sequential",
    }
    assert plan["execution_gate"] == {
        "accepted_wave_a_base_commit": "496d0d459b97000f62742fe064d8ef70956ea419",
        "wave_a": "INTEGRATED",
        "wave_b": "READY",
        "execute_allowed": False,
    }
    assert matrix["waves"]["candidate_wave"]["status"] == (
        "BLOCKED_ON_WAVE_B_AND_ENGINEERING_EVIDENCE"
    )
    assert matrix["gate"]["accepted_entry_state"]["promotion_gate"] == "PENDING"
    assert matrix["gate"]["traffic_constraints"]["promotion_allowed"] is False
    assert plan["runtime_restrictions"]["promotion"] == "forbidden"
    assert set(plan["runtime_restrictions"].values()) <= {
        "none",
        "forbidden",
    }


def test_wave_a_commands_are_exact_deduplicated_and_have_one_maven_token() -> None:
    matrix = runner.load_matrix()
    batch = matrix["batches"][runner.BATCH_ID]
    commands = runner.load_source_commands(matrix)
    java = commands["p5_wave_a_java"]["command"]
    selector = next(token for token in java.split() if token.startswith("-Dtest="))
    java_classes = tuple(selector.removeprefix("-Dtest=").split(","))

    assert java_classes == EXPECTED_JAVA_CLASSES
    assert len(java_classes) == len(set(java_classes)) == 17
    assert "EvidenceReducerPropertyTest" not in java_classes
    assert java.split().count("-DforkCount=1") == 1
    assert batch["requires_tasks"] == list(runner.TASK_REQUIREMENTS)
    assert "P5-R1" not in batch["requires_tasks"]
    assert batch["completed_by_task"] == "P5-R1"
    assert tuple(commands) == runner.COMMAND_ORDER
    assert not any(
        forbidden in " ".join(item["command"] for item in commands.values()).lower()
        for forbidden in ("frontend", "playwright", "docker", "real provider")
    )


def test_task_bindings_require_exact_review_and_t0_records(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    path = tmp_path / "bindings.json"
    path.write_text(json.dumps(_task_bindings()) + "\n", encoding="utf-8")
    ancestors: list[tuple[str, str, str]] = []
    monkeypatch.setattr(
        runner,
        "_assert_ancestor",
        lambda ancestor, candidate, context: ancestors.append(
            (ancestor, candidate, context)
        ),
    )

    document = runner.load_task_bindings(path, CANDIDATE)

    assert document["tasks"] == _task_bindings()["tasks"]
    assert len(ancestors) == len(runner.TASK_REQUIREMENTS)
    assert all(candidate == CANDIDATE for _, candidate, _ in ancestors)

    invalid = _task_bindings()
    invalid["tasks"][0]["p0_review"] = "PENDING"
    path.write_text(json.dumps(invalid) + "\n", encoding="utf-8")
    with pytest.raises(runner.shared.EvidenceError, match="review or T0"):
        runner.load_task_bindings(path, CANDIDATE)


def test_source_command_injection_is_candidate_report_only(tmp_path: Path) -> None:
    commands = runner.load_source_commands()
    raw = tmp_path / "raw.xml"

    python_argv = runner._command_argv_for_source(
        "p5_wave_a_python",
        commands["p5_wave_a_python"]["command"],
        raw,
        report_suffix="p5-wa-aaaaaaaaaaaa-12345678",
    )
    java_argv = runner._command_argv_for_source(
        "p5_wave_a_java",
        commands["p5_wave_a_java"]["command"],
        raw,
        report_suffix="p5-wa-aaaaaaaaaaaa-12345678",
    )

    assert python_argv[-1] == f"--junitxml={raw.resolve()}"
    assert java_argv[-2:] == [
        "-Dsurefire.reportNameSuffix=p5-wa-aaaaaaaaaaaa-12345678",
        "test",
    ]
    assert java_argv.count("-DforkCount=1") == 1


def test_source_record_normalizes_junit_with_candidate_and_command_binding(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    repository = tmp_path / "repo"
    (repository / "python-agent-service").mkdir(parents=True)
    run_root = repository / ".codex-run" / "wave-a"
    run_root.mkdir(parents=True)
    monkeypatch.setattr(runner, "ROOT", repository)
    monkeypatch.setattr(runner, "_assert_candidate_unchanged", lambda *_args: None)

    def fake_run(
        argv: list[str], _cwd: Path, stdout: Path, stderr: Path
    ) -> tuple[str, str, float, int]:
        junit = Path(next(item for item in argv if item.startswith("--junitxml=")).split("=", 1)[1])
        junit.write_text(
            '<testsuite name="fixture" tests="1" failures="0" errors="0" '
            'skipped="0" time="0.01">'
            '<testcase classname="fixture.python" name="passes" time="0.01" />'
            "</testsuite>\n",
            encoding="utf-8",
            newline="\n",
        )
        stdout.write_text("pass\n", encoding="utf-8", newline="\n")
        stderr.write_text("", encoding="utf-8", newline="\n")
        return (
            "2026-07-23T00:00:00+00:00",
            "2026-07-23T00:00:01+00:00",
            1.0,
            0,
        )

    monkeypatch.setattr(runner.shared, "_run_shell", fake_run)
    matrix_item = runner.load_source_commands()["p5_wave_a_python"]

    record, passed = runner._record_source(
        command_id="p5_wave_a_python",
        candidate=CANDIDATE,
        run_root=run_root,
        matrix_item=matrix_item,
        environment_sha256="e" * 64,
    )

    assert passed
    report = runner.shared.parse_junit(
        run_root / "source" / runner.SOURCE_REPORTS["p5_wave_a_python"]
    )
    assert report.candidate_commit == CANDIDATE
    assert report.command_id == "p5_wave_a_python"
    assert record["report_sha256"] == runner.shared._sha256(report.path)


@pytest.mark.parametrize(
    ("failures", "errors", "skipped"),
    ((1, 0, 0), (0, 1, 0), (0, 0, 1)),
)
def test_source_record_rejects_mixed_or_skipped_junit(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    failures: int,
    errors: int,
    skipped: int,
) -> None:
    repository = tmp_path / "repo"
    (repository / "python-agent-service").mkdir(parents=True)
    run_root = repository / ".codex-run" / "wave-a"
    run_root.mkdir(parents=True)
    monkeypatch.setattr(runner, "ROOT", repository)
    monkeypatch.setattr(runner, "_assert_candidate_unchanged", lambda *_args: None)

    def fake_run(
        argv: list[str], _cwd: Path, stdout: Path, stderr: Path
    ) -> tuple[str, str, float, int]:
        outcome = (
            '<failure message="fixture failure" />'
            if failures
            else '<error message="fixture error" />'
            if errors
            else '<skipped message="fixture skip" />'
        )
        junit = Path(
            next(item for item in argv if item.startswith("--junitxml=")).split(
                "=", 1
            )[1]
        )
        junit.write_text(
            f'<testsuite name="fixture" tests="2" failures="{failures}" '
            f'errors="{errors}" skipped="{skipped}" time="0.01">'
            '<testcase classname="fixture.python" name="passes" time="0.01" />'
            f'<testcase classname="fixture.python" name="mixed" time="0">{outcome}'
            "</testcase>"
            "</testsuite>\n",
            encoding="utf-8",
            newline="\n",
        )
        stdout.write_text("mixed result\n", encoding="utf-8", newline="\n")
        stderr.write_text("", encoding="utf-8", newline="\n")
        return (
            "2026-07-23T00:00:00+00:00",
            "2026-07-23T00:00:01+00:00",
            1.0,
            0,
        )

    monkeypatch.setattr(runner.shared, "_run_shell", fake_run)

    record, passed = runner._record_source(
        command_id="p5_wave_a_python",
        candidate=CANDIDATE,
        run_root=run_root,
        matrix_item=runner.load_source_commands()["p5_wave_a_python"],
        environment_sha256="e" * 64,
    )

    assert not passed
    assert record["accepted"] is False
    assert record["failure_classification"] == "UNCLASSIFIED"
    assert "not all-pass zero-skip" in record["failure_reason"]
    assert not (run_root / "source" / runner.SOURCE_REPORTS["p5_wave_a_python"]).exists()


def test_only_infra_classification_can_resume_same_sha() -> None:
    pending = {
        "id": "p5_wave_a_java",
        "failure_classification": "UNCLASSIFIED",
    }
    manifest = {
        "status": "REQUIRES_CLASSIFICATION",
        "pending_failure": pending,
        "quarantined_attempts": [],
        "verification_finished_at": None,
    }

    assert runner._classify_pending_failure(
        manifest, {"p5_wave_a_java": "INFRA"}
    )
    assert manifest["status"] == "RUNNING"
    assert manifest["quarantined_attempts"][0]["failure_classification"] == "INFRA"

    blocked = {
        "status": "REQUIRES_CLASSIFICATION",
        "pending_failure": dict(pending, failure_classification="UNCLASSIFIED"),
        "quarantined_attempts": [],
        "verification_finished_at": None,
    }
    assert not runner._classify_pending_failure(
        blocked, {"p5_wave_a_java": "PRODUCT"}
    )
    assert blocked["status"] == "CANDIDATE_BLOCKED"


def test_cli_plan_reports_closed_wave_a_execution_gate(
    capsys: pytest.CaptureFixture[str],
) -> None:
    assert runner.main(["--candidate-commit", CANDIDATE]) == 0
    plan = json.loads(capsys.readouterr().out)
    assert plan["execution_gate"] == {
        "accepted_wave_a_base_commit": "496d0d459b97000f62742fe064d8ef70956ea419",
        "wave_a": "INTEGRATED",
        "wave_b": "READY",
        "execute_allowed": False,
    }


def test_cli_rejects_repeat_wave_a_execution_before_source_launch(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
    tmp_path: Path,
) -> None:
    run_root = tmp_path / "repeat-wave-a"
    bindings = tmp_path / "bindings.json"

    def reject_source_launch(**_kwargs: object) -> tuple[dict, bool]:
        pytest.fail("post-integration Wave A attempted to launch a source command")

    monkeypatch.setattr(runner, "_record_source", reject_source_launch)

    assert runner.main(
        [
            "--candidate-commit",
            CANDIDATE,
            "--execute",
            "--run-dir",
            str(run_root),
            "--task-bindings",
            str(bindings),
        ]
    ) == 2
    captured = capsys.readouterr()
    assert captured.out == ""
    assert "P5-BATCH-1 matrix state is not execution-ready" in captured.err
    assert not run_root.exists()
