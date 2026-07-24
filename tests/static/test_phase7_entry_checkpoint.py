from __future__ import annotations

import copy
import importlib.util
import json
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest
import yaml


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/run_phase7_entry_checkpoint.py"
SPEC = importlib.util.spec_from_file_location("run_phase7_entry_checkpoint", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
runner = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = runner
SPEC.loader.exec_module(runner)

C7 = "0aa260f722fced0eba4314bd4793e415b5bf0b05"
CANDIDATE = "a" * 40


def _candidate_matrix() -> dict:
    process = subprocess.run(
        [
            "git",
            "show",
            f"{C7}:plans/phase-7-outcome-pilot-test-batches.yaml",
        ],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    value = yaml.safe_load(process.stdout)
    assert isinstance(value, dict)
    return value


EXACT_C7_MATRIX = _candidate_matrix()


@pytest.fixture(autouse=True)
def _use_exact_c7_candidate_matrix(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        runner, "load_matrix", lambda: copy.deepcopy(EXACT_C7_MATRIX)
    )


def _ready_matrix() -> dict:
    matrix = copy.deepcopy(EXACT_C7_MATRIX)
    matrix["document_status"] = "ENTRY_CANDIDATE_READY"
    return matrix


def test_plan_maps_all_four_sources_without_executing_them() -> None:
    plan = runner.entry_plan(CANDIDATE)

    assert plan["phase"] == 7
    assert plan["candidate_commit"] == CANDIDATE
    assert plan["execution_allowed"] is True
    assert plan["executed_source_count"] == 0
    assert plan["execution_order"] == list(runner.COMMAND_ORDER)
    assert tuple(runner.COMMAND_ORDER) == (
        "static_phase7_entry",
        "python_phase7_entry",
        "java_phase7_entry",
        "frontend_phase7_entry",
    )
    assert {item["id"]: item["report"] for item in plan["commands"]} == (
        runner.SOURCE_REPORTS
    )
    assert plan["blocked_reasons"] == []
    assert plan["contract_gate"] == "P7.0_NOT_RUN"
    assert plan["implementation_authorized"] is False
    assert plan["MIG-006"] == "PENDING_PROMOTION"
    assert plan["MIG-007"] == "PENDING_PROMOTION"
    assert plan["runtime_restrictions"] == {
        "formal_outcome_workflow": "forbidden",
        "temporal_outcome_allocation": "forbidden",
        "real_tool_effects": "forbidden",
        "real_case_shadow": "forbidden",
        "production_traffic": "forbidden",
        "canary_or_promotion": "forbidden",
    }


def test_cli_help_and_ready_plan_do_not_execute_sources(
    capsys: pytest.CaptureFixture[str],
) -> None:
    help_result = subprocess.run(
        [sys.executable, str(SCRIPT), "--help"],
        cwd=ROOT,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    assert help_result.returncode == 0
    assert runner.main(["--candidate-commit", CANDIDATE]) == 0
    plan = json.loads(capsys.readouterr().out)
    assert plan["execution_allowed"] is True
    assert plan["executed_source_count"] == 0


def test_execute_rejects_blocked_gate_before_source_or_directory(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    calls: list[str] = []
    monkeypatch.setattr(
        runner,
        "_record_source",
        lambda **_: calls.append("source") or ({}, False),
    )
    blocked = _ready_matrix()
    blocked["gate"]["entry_decision"] = "PASS"
    monkeypatch.setattr(runner, "load_matrix", lambda: blocked)
    run_root = tmp_path / "must-not-exist"

    with pytest.raises(runner.EvidenceError, match="Phase 7 entry execution is blocked"):
        runner.execute_checkpoint(
            candidate_commit=CANDIDATE,
            run_root=run_root,
            environment_id="blocked-static-test",
            resume=False,
            classifications=(),
        )

    assert calls == []
    assert not run_root.exists()


def test_gate_requires_checkpoint_exception_permission_candidate_and_batch() -> None:
    assert runner.gate_blockers(_ready_matrix()) == []

    mutations = (
        lambda item: item["gate"]["accepted_upstream_state"].update(
            phase_6_engineering_checkpoint="NOT_RECORDED"
        ),
        lambda item: item["gate"]["accepted_upstream_state"].update(
            phase_7_engineering_exception="NOT_RECORDED"
        ),
        lambda item: item["gate"].update(accepted_phase_6_checkpoint_A6="0" * 40),
        lambda item: item["gate"]["accepted_upstream_state"].update(
            next_phase_permission="BLOCKED"
        ),
        lambda item: item["gate"].update(entry_decision="PASS"),
        lambda item: item["batches"]["batch_0_entry"].update(
            status="READY_FOR_EXACT_SHA_BATCH_0"
        ),
        lambda item: item["gate"]["traffic_constraints"].update(
            real_tool_effect_allowed=True
        ),
    )
    for mutation in mutations:
        changed = _ready_matrix()
        mutation(changed)
        assert runner.gate_blockers(changed)


def test_command_report_contract_and_windows_wrapper_are_argv_safe(
    tmp_path: Path,
) -> None:
    contracts = runner._source_contracts(runner.load_matrix())
    assert list(contracts) == list(runner.COMMAND_ORDER)
    assert {
        command_id: item["report"] for command_id, item in contracts.items()
    } == runner.SOURCE_REPORTS

    python_item = contracts["python_phase7_entry"]
    raw_path = tmp_path / "space & literal report.xml"
    python_argv = runner._format_command(python_item, raw_path, "unused", ROOT)
    report_arg = f"--junitxml={raw_path.resolve()}"
    assert python_argv[-1] == report_arg
    assert python_argv.count(report_arg) == 1

    java_argv = runner._format_command(
        contracts["java_phase7_entry"],
        tmp_path / "unused.xml",
        "phase7-wrapper-test",
        ROOT / "java-api-service",
    )
    wrapper = Path(java_argv[0])
    assert wrapper.is_absolute()
    assert wrapper.name.lower() in {"mvnw", "mvnw.cmd"}
    assert wrapper.is_file()


def test_full_four_source_contract_is_code_owned_not_candidate_owned() -> None:
    contracts = runner._source_contracts(runner.load_matrix())
    assert tuple(contracts) == tuple(runner.FROZEN_SOURCE_CONTRACTS)
    for command_id, frozen in runner.FROZEN_SOURCE_CONTRACTS.items():
        actual = contracts[command_id]
        for field, expected in frozen.items():
            value = actual.get(field)
            if field == "resource_class":
                value = value.lower()
            assert value == expected, f"{command_id}.{field}"


def test_environment_dependency_hashes_use_candidate_blob_not_windows_worktree_bytes(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    worktree_bytes = b"name=value\r\n"
    candidate_blob = b"name=value\n"
    host = {
        "environment_id": "eol-test",
        "captured_at": "2026-07-24T00:00:00+00:00",
        "host": {},
        "tools": {},
        "dependency_manifests": [
            {
                "path": "fixture.lock",
                "sha256": runner.hashlib.sha256(worktree_bytes).hexdigest(),
            }
        ],
        "snapshot_sha256": "host-snapshot-is-replaced",
    }
    monkeypatch.setattr(runner, "_capture_environment_host", lambda *_: host)
    monkeypatch.setattr(
        runner.subprocess,
        "run",
        lambda *_args, **_kwargs: SimpleNamespace(
            returncode=0,
            stdout=candidate_blob,
            stderr=b"",
        ),
    )

    snapshot = runner.capture_environment("eol-test")
    dependency = snapshot["dependency_manifests"][0]
    assert dependency == {
        "path": "fixture.lock",
        "sha256": runner.hashlib.sha256(candidate_blob).hexdigest(),
        "byte_source": "CANDIDATE_GIT_BLOB",
    }
    assert dependency["sha256"] != runner.hashlib.sha256(worktree_bytes).hexdigest()
    unsigned = dict(snapshot)
    digest = unsigned.pop("snapshot_sha256")
    assert digest == runner._json_sha256(unsigned)


@pytest.mark.parametrize(
    ("command_id", "field", "value"),
    (
        ("static_phase7_entry", "cwd", "frontend"),
        (
            "static_phase7_entry",
            "command",
            "D:\\miniconda\\python.exe -m pytest -q "
            "tests/static/test_phase7_outcome_contracts.py --junitxml={raw_report}",
        ),
        ("static_phase7_entry", "expected_report_count", 2),
        ("static_phase7_entry", "selected_test_file_count", 1),
        ("static_phase7_entry", "minimum_tests", 1),
        (
            "java_phase7_entry",
            "command",
            r".\mvnw.cmd -DforkCount=1 -Dtest=ReviewControllerTest "
            r"-Dsurefire.reportNameSuffix={report_suffix} test",
        ),
        ("java_phase7_entry", "raw_report_glob", "target/forged/*.xml"),
        ("frontend_phase7_entry", "minimum_tests", 1),
    ),
)
def test_candidate_matrix_cannot_shrink_or_replace_source_contract(
    command_id: str, field: str, value: object
) -> None:
    changed = copy.deepcopy(runner.load_matrix())
    commands = changed["batches"]["batch_0_entry"]["source_commands"]
    item = next(record for record in commands if record["id"] == command_id)
    item[field] = value

    with pytest.raises(runner.EvidenceError, match="immutable source contract|drifted"):
        runner._source_contracts(changed)


def test_contract_candidate_allowlist_is_exactly_code_owned_and_cannot_be_widened() -> None:
    matrix = runner.load_matrix()
    assert tuple(matrix["gate"]["contract_candidate_allowed_paths"]) == (
        runner.FROZEN_CONTRACT_CANDIDATE_ALLOWLIST
    )
    assert runner._allowed_candidate_patterns(matrix) == (
        runner.FROZEN_CONTRACT_CANDIDATE_ALLOWLIST
    )


@pytest.mark.parametrize(
    "widening",
    (
        "java-api-service/src/main/**",
        "java-api-service/src/main/java/com/example/dispute/workflow/config/TemporalWorkerConfiguration.java",
        "python-agent-service/app/**",
        "frontend/src/**",
    ),
)
def test_candidate_controlled_matrix_cannot_widen_the_frozen_allowlist(
    widening: str,
) -> None:
    changed = copy.deepcopy(runner.load_matrix())
    changed["gate"]["contract_candidate_allowed_paths"].append(widening)

    with pytest.raises(runner.EvidenceError, match="allowlist drifted"):
        runner._allowed_candidate_patterns(changed)


def test_fresh_execution_checks_clean_detached_candidate_before_sources(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    calls: list[str] = []
    monkeypatch.setattr(runner, "load_matrix", _ready_matrix)
    monkeypatch.setattr(runner, "_assert_upstream_authority", lambda *_: None)
    monkeypatch.setattr(runner, "assert_contract_only_candidate", lambda *_: [])

    def reject_candidate(*_args: object, **_kwargs: object) -> None:
        calls.append("candidate-check")
        raise runner.EvidenceError("candidate worktree is not clean and detached")

    monkeypatch.setattr(runner, "assert_clean_detached_candidate", reject_candidate)
    monkeypatch.setattr(
        runner,
        "_record_source",
        lambda **_: calls.append("source") or ({}, False),
    )
    run_root = tmp_path / "fresh"

    with pytest.raises(runner.EvidenceError, match="clean and detached"):
        runner.execute_checkpoint(
            candidate_commit=CANDIDATE,
            run_root=run_root,
            environment_id="candidate-static-test",
            resume=False,
            classifications=(),
        )

    assert calls == ["candidate-check"]
    assert not run_root.exists()


@pytest.mark.parametrize(
    "forbidden_path",
    (
        "java-api-service/src/main/java/com/example/dispute/review/application/ReviewApplicationService.java",
        "java-api-service/src/main/java/com/example/dispute/workflow/application/epoch/ConfiguredRoomEpochSelector.java",
        "java-api-service/src/main/java/com/example/dispute/workflow/config/TemporalWorkerConfiguration.java",
        "java-api-service/src/main/resources/db/migration/V045__outcome_operation_receipt_compensation.sql",
    ),
)
def test_contract_candidate_rejects_product_selector_worker_and_v045_before_sources(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, forbidden_path: str
) -> None:
    calls: list[str] = []
    monkeypatch.setattr(runner, "load_matrix", _ready_matrix)
    monkeypatch.setattr(runner, "_assert_upstream_authority", lambda *_: None)
    monkeypatch.setattr(runner, "assert_base_ancestor", lambda *_: None)
    monkeypatch.setattr(
        runner.subprocess,
        "run",
        lambda *_args, **_kwargs: SimpleNamespace(
            returncode=0,
            stdout=f"M\t{forbidden_path}\n",
            stderr="",
        ),
    )
    monkeypatch.setattr(
        runner,
        "_record_source",
        lambda **_: calls.append("source") or ({}, False),
    )
    run_root = tmp_path / "scope-must-not-exist"

    with pytest.raises(
        runner.EvidenceError, match="non-contract path|forbidden product/runtime path"
    ):
        runner.execute_checkpoint(
            candidate_commit=CANDIDATE,
            run_root=run_root,
            environment_id="scope-static-test",
            resume=False,
            classifications=(),
        )

    assert calls == []
    assert not run_root.exists()


def test_manifest_is_sealed_and_only_same_sha_infra_can_resume(
    tmp_path: Path,
) -> None:
    run_root = tmp_path / "phase7-entry-attempt"
    run_root.mkdir()
    manifest = runner._initial_manifest(
        candidate=CANDIDATE,
        environment_id="seal-test",
        run_root=run_root,
    )
    manifest["pending_failure"] = {
        "id": "java_phase7_entry",
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
        runner._classify_pending_failure(persisted, {"java_phase7_entry": "INFRA"})
        is True
    )
    assert persisted["pending_failure"] is None
    assert persisted["quarantined_attempts"][0]["failure_classification"] == "INFRA"
    assert persisted["quarantined_attempts_reused"] is False

    blocked = copy.deepcopy(manifest)
    assert (
        runner._classify_pending_failure(blocked, {"java_phase7_entry": "PRODUCT"})
        is False
    )
    assert blocked["status"] == "CANDIDATE_BLOCKED"


def test_resume_revalidates_candidate_and_quarantined_artifact_hashes(
    tmp_path: Path,
) -> None:
    run_root = tmp_path / "phase7-infra-artifacts"
    attempt_root = run_root / "attempts" / "java_phase7_entry-01"
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
            "id": "java_phase7_entry",
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

    with pytest.raises(runner.EvidenceError, match="another candidate SHA"):
        runner._load_resume_manifest(run_root, "f" * 40)

    stdout.write_text("tampered stdout\n", encoding="utf-8")
    with pytest.raises(runner.EvidenceError, match="stdout SHA-256 drifted"):
        runner._load_resume_manifest(run_root, CANDIDATE)


def test_hypothetical_ready_gate_runs_all_four_but_never_claims_p7_pass(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    observed: list[str] = []
    monkeypatch.setattr(runner, "load_matrix", _ready_matrix)
    monkeypatch.setattr(runner, "_assert_upstream_authority", lambda *_: None)
    monkeypatch.setattr(runner, "assert_contract_only_candidate", lambda *_: [])
    monkeypatch.setattr(
        runner, "assert_clean_detached_candidate", lambda *_args, **_kwargs: None
    )
    monkeypatch.setattr(runner, "_assert_candidate_unchanged", lambda *_: None)
    monkeypatch.setattr(
        runner,
        "capture_environment",
        lambda _: {"snapshot_sha256": "c" * 64},
    )
    monkeypatch.setattr(runner, "_validate_resume_manifest", lambda *_: None)

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
        run_root=tmp_path / "fresh-phase7-entry",
        environment_id="ready-static-test",
        resume=False,
        classifications=(),
    )

    assert observed == list(runner.COMMAND_ORDER)
    assert manifest["status"] == runner.GREEN_STATUS
    assert manifest["contract_gate"] == "P7.0_NOT_RUN"
    assert manifest["implementation_authorized"] is False
    assert manifest["MIG-006"] == "PENDING_PROMOTION"
    assert manifest["MIG-007"] == "PENDING_PROMOTION"
    assert "PASS" not in manifest["status"]
