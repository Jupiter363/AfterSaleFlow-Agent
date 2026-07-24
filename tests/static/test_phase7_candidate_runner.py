from __future__ import annotations

import copy
import hashlib
import importlib.util
import stat
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/run_phase7_candidate_checkpoint.py"
SPEC = importlib.util.spec_from_file_location("run_phase7_candidate_checkpoint", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
runner = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = runner
SPEC.loader.exec_module(runner)

CANDIDATE = "a" * 40


def _junit(path: Path, *, tests: int = 3, failures: int = 0, skipped: int = 0) -> None:
    root = ET.Element(
        "testsuite",
        {
            "name": "focused",
            "tests": str(tests),
            "failures": str(failures),
            "errors": "0",
            "skipped": str(skipped),
        },
    )
    for index in range(tests):
        case = ET.SubElement(
            root,
            "testcase",
            {"classname": "Focused", "name": f"case_{index}", "time": "0"},
        )
        if index < failures:
            ET.SubElement(case, "failure")
        elif index < failures + skipped:
            ET.SubElement(case, "skipped")
    ET.ElementTree(root).write(path, encoding="utf-8", xml_declaration=True)


def _accepted_record(
    tmp_path: Path,
    *,
    minimum_tests: int = 2,
    observed_tests: int = 3,
) -> tuple[dict[str, object], dict[str, object]]:
    command_id = "static_phase7_candidate"
    contract = copy.deepcopy(runner.source_contracts()[command_id])
    contract["minimum_tests"] = minimum_tests
    attempt = tmp_path / "a" / "s-01"
    attempt.mkdir(parents=True)
    stdout = attempt / "stdout.log"
    stderr = attempt / "stderr.log"
    raw = attempt / "junit.xml"
    stdout.write_text("stdout\n", encoding="utf-8")
    stderr.write_text("stderr\n", encoding="utf-8")
    _junit(raw, tests=observed_tests)
    report = tmp_path / "r" / runner.SOURCE_REPORTS[command_id]
    report.parent.mkdir()
    normalized = runner.normalize_source_reports(
        [raw], report, candidate_commit=CANDIDATE, command_id=command_id
    )
    for artifact in (stdout, stderr, raw, report):
        runner._freeze_file(artifact)
    run_token = hashlib.sha256(str(tmp_path.resolve()).encode("utf-8")).hexdigest()[:6]
    suffix = f"p7c-{CANDIDATE[:10]}-{run_token}-s01"
    argv = runner._format_command(command_id, contract, raw, suffix, ROOT)
    rendered = runner.render_command_argv(argv)
    totals = normalized.totals
    record: dict[str, object] = {
        "id": command_id,
        "candidate_commit": CANDIDATE,
        "cwd": contract["cwd"],
        "resource_class": contract["resource_class"],
        "expected_report_count": contract["expected_report_count"],
        "selected_test_file_count": contract["selected_test_file_count"],
        "minimum_tests": contract["minimum_tests"],
        "frozen_command": contract["command"],
        "frozen_command_sha256": hashlib.sha256(
            str(contract["command"]).encode("utf-8")
        ).hexdigest(),
        "executed_argv": argv,
        "executed_argv_sha256": runner._json_sha256(argv),
        "executed_command": rendered,
        "executed_command_sha256": hashlib.sha256(rendered.encode("utf-8")).hexdigest(),
        "command_contract_blob_sha256": "b" * 64,
        "report_suffix": suffix,
        "environment_sha256": "e" * 64,
        "exit_code": 0,
        "accepted": True,
        "failure_classification": "NONE",
        "stdout_path": "a/s-01/stdout.log",
        "stdout_sha256": runner._sha256(stdout),
        "stderr_path": "a/s-01/stderr.log",
        "stderr_sha256": runner._sha256(stderr),
        "raw_report_count": 1,
        "raw_reports": [{"path": "a/s-01/junit.xml", "sha256": runner._sha256(raw)}],
        "report": runner.SOURCE_REPORTS[command_id],
        "report_path": f"r/{runner.SOURCE_REPORTS[command_id]}",
        "report_sha256": runner._sha256(report),
        **totals,
    }
    return record, contract


def _validate_record(
    record: dict[str, object], contract: dict[str, object], tmp_path: Path
) -> None:
    runner._validate_record(
        record,
        command_id="static_phase7_candidate",
        candidate=CANDIDATE,
        run_root=tmp_path,
        contract=contract,
        environment_sha256="e" * 64,
        runner_blob_sha256="b" * 64,
        accepted=True,
    )


def test_source_contracts_freeze_exact_selectors_and_provisional_minima() -> None:
    contracts = runner.source_contracts()

    assert tuple(contracts) == runner.COMMAND_ORDER
    assert {key: value["minimum_tests"] for key, value in contracts.items()} == (
        runner.FROZEN_MEASURED_MINIMA
    )
    assert tuple(runner.FROZEN_MEASURED_MINIMA.values()) == (78, 22, 80, 60)
    assert len(runner.STATIC_TESTS) == 9
    assert "tests/static/test_phase7_candidate_runner.py" in runner.STATIC_TESTS
    assert "tests/static/test_phase7_candidate_evidence.py" in runner.STATIC_TESTS
    assert runner.PYTHON_TESTS == (
        "tests/graphs/outcome",
        "tests/agents/test_review_copilot.py",
        "tests/test_evaluation.py",
    )
    java = contracts["java_phase7_candidate"]
    assert java["selected_test_file_count"] == len(runner.JAVA_TESTS)
    assert java["expected_report_count"] == 24
    assert f"-Dtest={','.join(runner.JAVA_TESTS)}" in java["command"]
    assert runner.JAVA_TESTS == (
        "OutcomeWireContractTest",
        "OutcomeProtocolCompatibilityTest",
        "OutcomeRoomWorkflowTest",
        "OutcomeRoomWorkflowTimerTest",
        "OutcomeRoomWorkflowReplayTest",
        "ReviewApplicationServiceV2Test",
        "FrozenReviewPacketTest",
        "ApprovalPolicyEngineTest",
        "ReviewControllerTest",
        "ReviewDecisionConcurrencyTest",
        "OutcomeV045MigrationContractTest",
        "JdbcOutcomeOperationLedgerTest",
        "OutcomeOperationLedgerIntegrationTest",
        "ToolActivityIdempotencyTest",
        "CompensationWorkflowTest",
        "CaseOutcomeServiceTest",
        "CaseOutcomeControllerTest",
        "CaseClosureServiceTest",
        "RestClientEvaluationAgentClientTest",
        "OutcomeClosureEvaluationOrderingTest",
        "OutcomeSyntheticNoopAssemblyTest",
        "ReviewTemporalCommandIntegrationTest",
        "OutcomeReliabilityHarnessTest",
        "OutcomeUnregisteredAssemblyGuardTest",
    )
    assert tuple(Path(path).stem for path in runner.JAVA_TEST_SOURCE_PATHS) == (
        runner.JAVA_TESTS
    )
    assert (
        ROOT / runner.JDBC_OUTCOME_OPERATION_LEDGER_PATH
    ).is_file()
    assert (
        "java-api-service/src/test/java/com/example/dispute/executor/"
        "persistence/JdbcOutcomeOperationLedgerTest.java"
    ) in runner.JAVA_TEST_SOURCE_PATHS
    assert "OutcomeUnregisteredAssemblyGuardTest" in java["command"]
    assert contracts["frontend_phase7_candidate"]["selected_test_file_count"] == 6


def test_candidate_argument_cannot_change_frozen_source_contract() -> None:
    assert runner.source_contracts(CANDIDATE) == runner.source_contracts("f" * 40)


@pytest.mark.parametrize(
    ("gate_field", "batch_name", "blocker"),
    (
        ("batch_1_status", "batch_1_foundation", "P7_BATCH_1_NOT_PASS"),
        ("batch_2_status", "batch_2_integration", "P7_BATCH_2_NOT_PASS"),
    ),
)
def test_batch3_gate_requires_matching_batch1_and_batch2_pass_states(
    gate_field: str, batch_name: str, blocker: str
) -> None:
    matrix = copy.deepcopy(runner.load_matrix())
    matrix["gate"]["batch_1_status"] = "PASS"
    matrix["gate"]["batch_2_status"] = "PASS"
    matrix["batches"]["batch_1_foundation"]["status"] = "PASS"
    matrix["batches"]["batch_2_integration"]["status"] = "PASS"

    assert runner.gate_blockers(matrix) == []

    gate_drift = copy.deepcopy(matrix)
    gate_drift["gate"][gate_field] = "IN_PROGRESS_NOT_PASS"
    assert blocker in runner.gate_blockers(gate_drift)

    batch_drift = copy.deepcopy(matrix)
    batch_drift["batches"][batch_name]["status"] = "IN_PROGRESS_NOT_PASS"
    assert blocker in runner.gate_blockers(batch_drift)


@pytest.mark.parametrize(
    ("platform_name", "wrapper_name"),
    (("nt", "mvnw.cmd"), ("posix", "mvnw")),
)
def test_java_wrapper_is_resolved_to_absolute_checked_in_path(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    platform_name: str,
    wrapper_name: str,
) -> None:
    contract = runner.source_contracts()["java_phase7_candidate"]
    expected_wrapper = (ROOT / "java-api-service" / wrapper_name).resolve()
    monkeypatch.setattr(runner.os, "name", platform_name)
    argv = runner._format_command(
        "java_phase7_candidate",
        contract,
        tmp_path / "unused.xml",
        "p7c-wrapper-test",
        ROOT / "java-api-service",
    )

    assert argv[0] == str(expected_wrapper)
    assert expected_wrapper.is_absolute()
    assert expected_wrapper.is_file()
    assert expected_wrapper.is_relative_to(ROOT)


def test_compact_provenance_paths_fit_budget_and_long_relative_path_is_rejected(
    tmp_path: Path,
) -> None:
    compact = tmp_path / "a" / "j-02" / "raw" / "j-999.xml"
    runner._assert_path_budget(compact, tmp_path)
    assert len(compact.relative_to(tmp_path).as_posix()) < 40

    over_budget = tmp_path / ("x" * (runner.MAX_RELATIVE_PROVENANCE_PATH + 1))
    with pytest.raises(runner.EvidenceError, match="relative path exceeds budget"):
        runner._assert_path_budget(over_budget, tmp_path)


def test_environment_hashes_candidate_git_blobs_not_worktree_bytes(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    host = {
        "environment_id": "git-blob-test",
        "dependency_manifests": [
            {"path": path, "sha256": "host"}
            for path in runner.DEPENDENCY_MANIFEST_PATHS
        ],
        "snapshot_sha256": "host-snapshot",
    }
    blobs = {
        **{
            path: f"{path}\n".encode("utf-8")
            for path in runner.DEPENDENCY_MANIFEST_PATHS
        },
        runner.RUNNER_PATH: b"candidate runner bytes\n",
    }
    monkeypatch.setattr(runner, "_capture_environment_host", lambda _: copy.deepcopy(host))
    monkeypatch.setattr(runner, "_git_bytes", lambda _candidate, path: blobs[path])
    monkeypatch.setattr(runner, "_source_contract_sha256", lambda _candidate: "c" * 64)

    snapshot = runner.capture_environment("git-blob-test", CANDIDATE)

    assert snapshot["dependency_manifests"] == [
        {
            "path": path,
            "sha256": hashlib.sha256(blobs[path]).hexdigest(),
            "byte_source": "CANDIDATE_GIT_BLOB",
        }
        for path in runner.DEPENDENCY_MANIFEST_PATHS
    ]
    assert snapshot["runner"]["sha256"] == hashlib.sha256(
        blobs[runner.RUNNER_PATH]
    ).hexdigest()
    unsigned = dict(snapshot)
    digest = unsigned.pop("snapshot_sha256")
    assert digest == runner._json_sha256(unsigned)


def test_candidate_scope_requires_additive_v045_and_rejects_prior_migration(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(runner, "_assert_candidate", lambda value: value)
    monkeypatch.setattr(runner, "assert_base_ancestor", lambda *_: None)
    monkeypatch.setattr(runner, "_assert_frozen_authority", lambda *_: None)
    monkeypatch.setattr(runner, "_assert_prior_migrations", lambda *_: None)
    monkeypatch.setattr(runner, "_assert_candidate_source_inventory", lambda *_: None)
    monkeypatch.setattr(runner, "_git_output", lambda *args: "b" * 40)
    monkeypatch.setattr(
        runner,
        "_changed_path_records",
        lambda _: [
            {"status": "A", "path": runner.V045_PATH},
            {"status": "A", "path": runner.RUNNER_PATH},
        ],
    )
    snapshot = runner.capture_source_tree(CANDIDATE)
    assert snapshot["v045"] == {"path": runner.V045_PATH, "status": "ADDED_ONLY"}

    monkeypatch.setattr(
        runner,
        "_changed_path_records",
        lambda _: [
            {"status": "A", "path": runner.V045_PATH},
            {
                "status": "M",
                "path": "java-api-service/src/main/resources/db/migration/V044__hearing.sql",
            },
        ],
    )
    with pytest.raises(runner.EvidenceError, match="prior migration"):
        runner.capture_source_tree(CANDIDATE)


def test_candidate_scope_rejects_worker_selector_and_formal_activation_paths(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(runner, "_assert_candidate", lambda value: value)
    monkeypatch.setattr(runner, "assert_base_ancestor", lambda *_: None)
    monkeypatch.setattr(
        runner,
        "_changed_path_records",
        lambda _: [
            {"status": "A", "path": runner.V045_PATH},
            {"status": "M", "path": runner.FROZEN_AUTHORITY_PATHS[0]},
        ],
    )

    with pytest.raises(runner.EvidenceError, match="forbidden authority"):
        runner.capture_source_tree(CANDIDATE)


def test_candidate_scope_rejects_typechanges(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(runner, "_assert_candidate", lambda value: value)
    monkeypatch.setattr(runner, "assert_base_ancestor", lambda *_: None)
    monkeypatch.setattr(
        runner,
        "_changed_path_records",
        lambda _: [
            {"status": "A", "path": runner.V045_PATH},
            {"status": "T", "path": runner.RUNNER_PATH},
        ],
    )

    with pytest.raises(runner.EvidenceError, match="unsupported change T"):
        runner.capture_source_tree(CANDIDATE)


@pytest.mark.parametrize(
    ("mode", "object_type"),
    (("120000", "blob"), ("160000", "commit"), ("040000", "tree")),
)
def test_candidate_git_paths_reject_symlink_submodule_and_tree_entries(
    monkeypatch: pytest.MonkeyPatch, mode: str, object_type: str
) -> None:
    path = runner.V045_PATH
    monkeypatch.setattr(
        runner,
        "_git_tree_entries",
        lambda *_args, **_kwargs: [(mode, object_type, "1" * 40, path)],
    )

    with pytest.raises(runner.EvidenceError, match="regular blob"):
        runner._git_tree_entry(CANDIDATE, path)


def test_fresh_execution_rejects_dirty_candidate_before_creating_output_or_source(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    calls: list[str] = []
    monkeypatch.setattr(runner, "_assert_candidate", lambda value: value)
    monkeypatch.setattr(runner, "load_matrix", lambda *_: {})
    monkeypatch.setattr(runner, "gate_blockers", lambda _: [])
    monkeypatch.setattr(runner, "capture_source_tree", lambda _: {"snapshot_sha256": "s"})
    monkeypatch.setattr(runner, "assert_candidate_run_directory", lambda *_: None)
    monkeypatch.setattr(
        runner,
        "assert_clean_detached_candidate",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            runner.EvidenceError("candidate worktree is dirty")
        ),
    )
    monkeypatch.setattr(
        runner,
        "_record_source",
        lambda **_: calls.append("source") or ({}, False),
    )
    run_root = tmp_path / "run"

    with pytest.raises(runner.EvidenceError, match="dirty"):
        runner.execute_checkpoint(
            candidate_commit=CANDIDATE,
            run_root=run_root,
            environment_id="dirty-test",
            resume=False,
            classifications=(),
        )

    assert calls == []
    assert not run_root.exists()


def test_record_source_rechecks_exact_sha_after_command(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    contract = copy.deepcopy(runner.source_contracts()["static_phase7_candidate"])
    calls = 0

    def check_candidate(*_args: object) -> None:
        nonlocal calls
        calls += 1
        if calls == 2:
            raise runner.EvidenceError("candidate SHA changed")

    monkeypatch.setattr(runner, "_assert_candidate_unchanged", check_candidate)

    def fake_run(
        _argv: list[str], _cwd: Path, stdout: Path, stderr: Path
    ) -> tuple[str, str, float, int]:
        stdout.write_text("stdout\n", encoding="utf-8")
        stderr.write_text("stderr\n", encoding="utf-8")
        return "start", "finish", 0.1, 0

    monkeypatch.setattr(runner, "_run_shell", fake_run)

    with pytest.raises(runner.EvidenceError, match="SHA changed"):
        runner._record_source(
            command_id="static_phase7_candidate",
            candidate=CANDIDATE,
            run_root=tmp_path,
            contract=contract,
            environment_sha256="e" * 64,
            runner_blob_sha256="b" * 64,
        )


def test_accepted_record_rejects_argv_and_minimum_tampering(tmp_path: Path) -> None:
    record, contract = _accepted_record(tmp_path)
    _validate_record(record, contract, tmp_path)

    tampered_argv = copy.deepcopy(record)
    tampered_argv["executed_argv"] = [*record["executed_argv"], "--forged"]
    with pytest.raises(runner.EvidenceError, match="argv, contract"):
        _validate_record(tampered_argv, contract, tmp_path)

    tampered_minimum = copy.deepcopy(record)
    tampered_minimum["minimum_tests"] = 1
    with pytest.raises(runner.EvidenceError, match="argv, contract"):
        _validate_record(tampered_minimum, contract, tmp_path)


def test_accepted_record_rejects_stdout_and_junit_tampering(tmp_path: Path) -> None:
    record, contract = _accepted_record(tmp_path)
    stdout = tmp_path / str(record["stdout_path"])
    stdout.chmod(stdout.stat().st_mode | stat.S_IWUSR)
    stdout.write_text("tampered\n", encoding="utf-8")
    with pytest.raises(runner.EvidenceError, match="stdout SHA-256 drifted"):
        _validate_record(record, contract, tmp_path)

    record, contract = _accepted_record(tmp_path / "junit-case")
    report = tmp_path / "junit-case" / str(record["report_path"])
    report.chmod(report.stat().st_mode | stat.S_IWUSR)
    report.write_text("<testsuite tests='0'/>", encoding="utf-8")
    with pytest.raises(runner.EvidenceError, match="normalized JUnit SHA-256 drifted"):
        _validate_record(record, contract, tmp_path / "junit-case")


def test_resume_accepts_only_ordered_green_prefix(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    manifest = {
        "schema_version": runner.SCHEMA_VERSION,
        "phase": 7,
        "batch": "P7-BATCH-3",
        "candidate_commit": CANDIDATE,
        "accepted_phase_7_candidate_C7": runner.PHASE7_ENTRY_CANDIDATE,
        "accepted_phase_7_evidence_E7": runner.PHASE7_ENTRY_EVIDENCE,
        "decision_ceiling": "PHASE_7_ENGINEERING_CHECKPOINT_ONLY",
        "MIG-006": "PENDING_PROMOTION",
        "MIG-007": "PENDING_PROMOTION",
        "run_root": str(tmp_path.resolve()),
        "attempt_id": tmp_path.name,
        "quarantined_attempts_reused": False,
        "source_contract_sha256": "c" * 64,
        "source_tree": {},
        "environment": {"runner": {"sha256": "b" * 64}},
        "commands": [
            {"id": "python_phase7_candidate"},
            {"id": "static_phase7_candidate"},
        ],
        "quarantined_attempts": [],
        "pending_failure": None,
        "status": "RUNNING",
    }
    monkeypatch.setattr(runner, "_validate_source_tree", lambda *_: None)
    monkeypatch.setattr(runner, "_validate_environment", lambda *_: "e" * 64)
    monkeypatch.setattr(runner, "_source_contract_sha256", lambda *_: "c" * 64)

    with pytest.raises(runner.EvidenceError, match="ordered green source prefix"):
        runner._validate_manifest(manifest, tmp_path, CANDIDATE)


@pytest.mark.parametrize(
    ("status", "pending"),
    (
        (
            "RUNNING",
            {
                "id": "static_phase7_candidate",
                "failure_classification": "UNCLASSIFIED",
            },
        ),
        ("REQUIRES_CLASSIFICATION", None),
        ("FORGED", None),
    ),
)
def test_resume_rejects_inconsistent_execution_state(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    status: str,
    pending: dict[str, str] | None,
) -> None:
    manifest = {
        "schema_version": runner.SCHEMA_VERSION,
        "phase": 7,
        "batch": "P7-BATCH-3",
        "candidate_commit": CANDIDATE,
        "accepted_phase_7_candidate_C7": runner.PHASE7_ENTRY_CANDIDATE,
        "accepted_phase_7_evidence_E7": runner.PHASE7_ENTRY_EVIDENCE,
        "decision_ceiling": "PHASE_7_ENGINEERING_CHECKPOINT_ONLY",
        "MIG-006": "PENDING_PROMOTION",
        "MIG-007": "PENDING_PROMOTION",
        "run_root": str(tmp_path.resolve()),
        "attempt_id": tmp_path.name,
        "quarantined_attempts_reused": False,
        "source_contract_sha256": "c" * 64,
        "source_tree": {},
        "environment": {"runner": {"sha256": "b" * 64}},
        "commands": [],
        "quarantined_attempts": [],
        "pending_failure": pending,
        "status": status,
    }
    monkeypatch.setattr(runner, "_validate_source_tree", lambda *_: None)
    monkeypatch.setattr(runner, "_validate_environment", lambda *_: "e" * 64)
    monkeypatch.setattr(runner, "_source_contract_sha256", lambda *_: "c" * 64)
    record_validation_calls: list[str] = []
    if pending is not None:
        monkeypatch.setattr(
            runner,
            "_validate_record",
            lambda *_args, **_kwargs: record_validation_calls.append("pending")
            or tmp_path / "a" / "s-01",
        )

    with pytest.raises(runner.EvidenceError, match="execution state|failure state"):
        runner._validate_manifest(manifest, tmp_path, CANDIDATE)
    assert record_validation_calls == []


def test_failure_classification_requires_resume_before_run_directory_creation(
    tmp_path: Path,
) -> None:
    run_root = tmp_path / "must-not-exist"

    with pytest.raises(runner.EvidenceError, match="requires --resume"):
        runner.execute_checkpoint(
            candidate_commit=CANDIDATE,
            run_root=run_root,
            environment_id="classification-test",
            resume=False,
            classifications=("static_phase7_candidate=INFRA",),
        )

    assert not run_root.exists()


def test_only_one_classified_same_sha_infra_retry_is_allowed() -> None:
    manifest = {
        "status": "REQUIRES_CLASSIFICATION",
        "pending_failure": {
            "id": "java_phase7_candidate",
            "candidate_commit": CANDIDATE,
            "failure_classification": "UNCLASSIFIED",
        },
        "quarantined_attempts": [],
    }
    classification = {"java_phase7_candidate": "INFRA"}
    assert runner._classify_pending_failure(manifest, classification) is True
    assert manifest["status"] == "RUNNING"

    manifest["pending_failure"] = {
        "id": "java_phase7_candidate",
        "candidate_commit": CANDIDATE,
        "failure_classification": "UNCLASSIFIED",
    }
    assert runner._classify_pending_failure(manifest, classification) is False
    assert manifest["status"] == "CANDIDATE_BLOCKED"


def test_full_lowercase_candidate_sha_is_required() -> None:
    with pytest.raises(runner.EvidenceError, match="full lowercase 40-character"):
        runner.candidate_plan("abc123")


def test_freeze_file_removes_all_write_bits(tmp_path: Path) -> None:
    artifact = tmp_path / "artifact.xml"
    artifact.write_text("immutable\n", encoding="utf-8")
    runner._freeze_file(artifact)
    assert artifact.stat().st_mode & (stat.S_IWUSR | stat.S_IWGRP | stat.S_IWOTH) == 0
