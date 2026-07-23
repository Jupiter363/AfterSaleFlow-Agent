from __future__ import annotations

import hashlib
import json
import os
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest

from scripts import run_phase5_candidate_checkpoint as runner


CANDIDATE = "a" * 40


def _write_junit(path: Path, candidate: str, command_id: str, classname: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(
        (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            f'<testsuites tests="1" failures="0" errors="0" skipped="0" time="0" '
            f'candidate_commit="{candidate}" source_command_id="{command_id}">\n'
            f'  <testsuite name="{command_id}" tests="1" failures="0" errors="0" '
            'skipped="0" time="0">\n'
            f'    <testcase classname="{classname}" name="passes" time="0" />\n'
            "  </testsuite>\n"
            "</testsuites>\n"
        ).encode("utf-8")
    )


def _pass_manifest(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> tuple[Path, dict]:
    repository = tmp_path / "repo"
    repository.mkdir()
    dependency = repository / "dep.lock"
    dependency.write_text("fixture\n", encoding="utf-8", newline="\n")
    run_root = repository / ".codex-run" / "phase5-candidate"
    (run_root / "source").mkdir(parents=True)
    monkeypatch.setattr(runner, "ROOT", repository)
    monkeypatch.setattr(runner, "DEPENDENCY_MANIFESTS", ("dep.lock",))
    commands = runner.focused_commands()
    environment = {
        "environment_id": "fixture",
        "captured_at": "2026-07-23T00:00:00+00:00",
        "dependency_manifests": [
            {"path": "dep.lock", "sha256": runner._sha256(dependency)}
        ],
    }
    environment["snapshot_sha256"] = runner._json_sha256(environment)
    records = []
    for index, command_id in enumerate(runner.COMMAND_ORDER, start=1):
        attempt_dir = run_root / "attempts" / f"{command_id}-01"
        attempt_dir.mkdir(parents=True)
        stdout = attempt_dir / "stdout.log"
        stderr = attempt_dir / "stderr.log"
        stdout.write_text("pass\n", encoding="utf-8", newline="\n")
        stderr.write_text("", encoding="utf-8", newline="\n")
        suffix = f"p5-{CANDIDATE[:12]}-{index:08x}"
        if command_id == "java_phase_5_deduplicated":
            raw = attempt_dir / "raw-surefire" / f"TEST-fixture-{suffix}.xml"
        else:
            raw = attempt_dir / "raw-junit.xml"
        _write_junit(raw, CANDIDATE, command_id, f"fixture.Source{index}")
        report = run_root / "source" / runner.SOURCE_REPORTS[command_id]
        runner.normalize_source_reports(
            [raw], report, candidate_commit=CANDIDATE, command_id=command_id
        )
        executed_argv = runner._command_argv_for_source(
            command_id,
            commands[command_id]["argv"],
            attempt_dir / "raw-junit.xml",
            report_suffix=suffix,
        )
        executed = runner.trusted.render_command_argv(executed_argv)
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
                "report": runner.SOURCE_REPORTS[command_id],
                "report_path": f"source/{runner.SOURCE_REPORTS[command_id]}",
                "report_sha256": runner._sha256(report),
                "tests": 1,
                "failures": 0,
                "errors": 0,
                "skipped": 0,
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
    manifest_path = run_root / runner.MANIFEST_NAME
    runner._write_manifest(manifest_path, manifest)
    return manifest_path, manifest


def test_plan_builds_four_serial_deduplicated_sources_and_keeps_all_gates() -> None:
    plan = runner.candidate_plan(CANDIDATE)
    commands = runner.focused_commands()

    assert plan["execution_order"] == list(runner.COMMAND_ORDER)
    assert plan["runner_execution"] == "sequential"
    assert plan["candidate_policy"]["same_sha_infra_reruns_per_source"] == 1
    assert plan["candidate_policy"]["unconditional_rerun"] is False
    assert plan["promotion_gate"] == "PENDING"
    assert plan["MIG-004"] == plan["MIG-005"] == "PENDING_PROMOTION"
    assert plan["runtime_restrictions"]["formal_evidence_graph_sink"] == "forbidden"
    assert plan["runtime_restrictions"]["real_case_shadow"] == "forbidden"
    assert plan["runtime_restrictions"]["t3_unified_checkpoint"] == "not_executed"

    python_selectors = runner._pytest_selectors(
        commands["python_phase_5_deduplicated"]["argv"]
    )
    assert "tests/graphs/evidence" in python_selectors
    assert "tests/graphs/evidence/test_graph.py" not in python_selectors
    assert "tests/graphs/evidence/test_recovery.py" not in python_selectors
    assert not any(
        left != right and right.startswith(f"{left.rstrip('/')}/")
        for left in python_selectors
        for right in python_selectors
    )

    java = commands["java_phase_5_deduplicated"]["argv"]
    java_classes = runner._java_classes(java)
    assert java[0] == (r".\mvnw.cmd" if os.name == "nt" else "./mvnw")
    assert len(java_classes) == len(set(java_classes))
    assert java.count("-DforkCount=1") == 1
    assert all(
        forbidden not in " ".join(item["command"] for item in commands.values()).lower()
        for forbidden in ("playwright", "docker", "real_case_shadow", "canary_observation")
    )


def test_command_injection_is_the_only_allowed_argv_delta(tmp_path: Path) -> None:
    commands = runner.focused_commands()
    raw = tmp_path / "raw.xml"
    for index, command_id in enumerate(runner.COMMAND_ORDER, start=1):
        suffix = f"p5-{CANDIDATE[:12]}-{index:08x}"
        argv = runner._command_argv_for_source(
            command_id,
            commands[command_id]["argv"],
            raw,
            report_suffix=suffix,
        )
        if command_id == "java_phase_5_deduplicated":
            assert argv[-2:] == [f"-Dsurefire.reportNameSuffix={suffix}", "test"]
        elif command_id == "frontend_phase_5_deduplicated":
            assert argv[-2:] == ["--reporter=junit", f"--outputFile={raw.resolve()}"]
        else:
            assert argv[-1] == f"--junitxml={raw.resolve()}"


def test_retained_surefire_names_are_bounded_and_suffix_bound() -> None:
    suffix = f"p5-{CANDIDATE[:12]}-1234abcd"
    name = runner._retained_surefire_name(1, suffix)

    assert name == f"TEST-001-{suffix}.xml"
    assert len(name) < 64
    with pytest.raises(runner.EvidenceError, match="identity is invalid"):
        runner._retained_surefire_name(0, suffix)


def test_resealed_manifest_rejects_arbitrary_success_command(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    path, manifest = _pass_manifest(monkeypatch, tmp_path)
    assert runner.load_pass_manifest(path, CANDIDATE)["status"] == "PASS"
    record = manifest["commands"][0]
    record["executed_argv"] = [
        "python",
        "-c",
        "print('forged pass')",
        record["executed_argv"][-1],
    ]
    record["executed_command"] = runner.trusted.render_command_argv(
        record["executed_argv"]
    )
    record["executed_command_sha256"] = hashlib.sha256(
        record["executed_command"].encode("utf-8")
    ).hexdigest()
    runner._write_manifest(path, manifest)

    with pytest.raises(runner.EvidenceError, match="differs from the approved source"):
        runner.load_pass_manifest(path, CANDIDATE)


def test_resealed_manifest_rejects_hidden_or_extra_attempt_directory(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    path, manifest = _pass_manifest(monkeypatch, tmp_path)
    (path.parent / "attempts" / f"{runner.COMMAND_ORDER[0]}-02").mkdir()
    runner._write_manifest(path, manifest)

    with pytest.raises(runner.EvidenceError, match="hidden or missing executions"):
        runner.load_pass_manifest(path, CANDIDATE)


def test_resealed_manifest_rejects_raw_junit_swapped_across_attempts(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    path, manifest = _pass_manifest(monkeypatch, tmp_path)
    python_record = manifest["commands"][0]
    static_record = manifest["commands"][3]
    swapped = static_record["raw_reports"][0]
    python_record["raw_reports"] = [dict(swapped)]
    python_record["executed_argv"][-1] = str(
        (path.parent / swapped["path"]).resolve()
    ).join(["--junitxml=", ""])
    python_record["executed_command"] = runner.trusted.render_command_argv(
        python_record["executed_argv"]
    )
    python_record["executed_command_sha256"] = hashlib.sha256(
        python_record["executed_command"].encode("utf-8")
    ).hexdigest()
    runner._write_manifest(path, manifest)

    with pytest.raises(runner.EvidenceError, match="exact attempt"):
        runner.load_pass_manifest(path, CANDIDATE)


def test_resealed_manifest_rejects_finish_before_a_source(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    path, manifest = _pass_manifest(monkeypatch, tmp_path)
    manifest["verification_finished_at"] = "2026-07-23T00:00:02+00:00"
    runner._write_manifest(path, manifest)

    with pytest.raises(runner.EvidenceError, match="after verification finish"):
        runner.load_pass_manifest(path, CANDIDATE)


@pytest.mark.parametrize("mutation", ["raw_failure", "raw_missing_case"])
def test_resealed_manifest_rejects_source_report_forged_beyond_raw_junit(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, mutation: str
) -> None:
    path, manifest = _pass_manifest(monkeypatch, tmp_path)
    record = manifest["commands"][0]
    raw_path = path.parent / record["raw_reports"][0]["path"]
    tree = ET.parse(raw_path)
    testcase = next(tree.iter("testcase"))
    if mutation == "raw_failure":
        ET.SubElement(testcase, "failure").text = "real failure"
    else:
        parent = next(element for element in tree.iter() if testcase in list(element))
        parent.remove(testcase)
    tree.write(raw_path, encoding="utf-8", xml_declaration=True)
    record["raw_reports"][0]["sha256"] = runner._sha256(raw_path)
    runner._write_manifest(path, manifest)

    with pytest.raises(runner.EvidenceError, match="deterministic raw JUnit normalization"):
        runner.load_pass_manifest(path, CANDIDATE)


def test_only_one_same_sha_infra_rerun_is_permitted() -> None:
    pending = {
        "id": runner.COMMAND_ORDER[0],
        "failure_classification": "UNCLASSIFIED",
    }
    manifest = {
        "status": "REQUIRES_CLASSIFICATION",
        "pending_failure": dict(pending),
        "quarantined_attempts": [],
        "verification_finished_at": None,
    }
    assert runner._classify_pending_failure(
        manifest, {runner.COMMAND_ORDER[0]: "INFRA"}
    )
    assert manifest["status"] == "RUNNING"

    manifest.update(
        status="REQUIRES_CLASSIFICATION",
        pending_failure=dict(pending),
    )
    assert not runner._classify_pending_failure(
        manifest, {runner.COMMAND_ORDER[0]: "INFRA"}
    )
    assert manifest["status"] == "CANDIDATE_BLOCKED"


def test_product_fixture_and_external_gate_block_the_candidate() -> None:
    for classification in ("PRODUCT", "FIXTURE", "EXTERNAL_GATE"):
        manifest = {
            "status": "REQUIRES_CLASSIFICATION",
            "pending_failure": {
                "id": runner.COMMAND_ORDER[0],
                "failure_classification": "UNCLASSIFIED",
            },
            "quarantined_attempts": [],
            "verification_finished_at": None,
        }
        assert not runner._classify_pending_failure(
            manifest, {runner.COMMAND_ORDER[0]: classification}
        )
        assert manifest["status"] == "CANDIDATE_BLOCKED"


def test_matrix_failure_policy_matches_runner_candidate_semantics() -> None:
    matrix = runner.load_matrix()
    failure_policy = matrix["failure_classification"]
    checkpoint = failure_policy["candidate_checkpoint"]

    assert set(failure_policy["required_values"]) == runner.FAILURE_CLASSIFICATIONS
    assert checkpoint == {
        "batch": runner.BATCH_ID,
        "same_sha_resume_allowed_only_for": "INFRA",
        "maximum_same_sha_resumes_per_source": runner.MAX_INFRA_RERUNS_PER_SOURCE,
        "candidate_blocking_values": ["PRODUCT", "FIXTURE", "EXTERNAL_GATE"],
        "repair_requires_new_candidate_sha": True,
        "new_candidate_execution": "full_batch_from_fresh_clean_detached_worktree",
        "prior_candidate_results_reusable": False,
    }
    assert set(checkpoint["candidate_blocking_values"]) == (
        runner.FAILURE_CLASSIFICATIONS
        - {checkpoint["same_sha_resume_allowed_only_for"]}
    )
    assert failure_policy["FIXTURE"]["candidate_checkpoint_action"] == "block_candidate"
    assert "rerun_exact_failed_scope" not in failure_policy["FIXTURE"]["action"]
