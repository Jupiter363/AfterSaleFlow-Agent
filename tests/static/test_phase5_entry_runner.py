from __future__ import annotations

import importlib.util
import hashlib
import json
import subprocess
import sys
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/run_phase5_entry_checkpoint.py"
SPEC = importlib.util.spec_from_file_location("run_phase5_entry_checkpoint", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
runner = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = runner
SPEC.loader.exec_module(runner)

CANDIDATE = "a" * 40
HANDOFF = {
    "checkpoint_path": "test-reports/temporal-first/p4/phase-4/phase-metrics.json",
    "checkpoint_sha256": "1" * 64,
    "evidence_commit": "d" * 40,
    "phase4_candidate_commit": "e" * 40,
    "engineering_checkpoint": "PASS",
    "next_phase_permission": "PHASE_5_ENGINEERING_ONLY",
    "promotion_gate": "PENDING",
    "MIG-004": "PENDING_PROMOTION",
    "source_execution_manifest_sha256": "2" * 64,
}


def test_plan_binds_four_entry_sources_to_one_candidate_without_runtime_authority() -> None:
    plan = runner.candidate_plan(CANDIDATE)
    records = {record["id"]: record for record in plan["commands"]}

    assert plan["phase"] == 5
    assert plan["batch"] == "P5-BATCH-0"
    assert plan["candidate_commit"] == CANDIDATE
    assert plan["execution_order"] == list(runner.COMMAND_ORDER)
    assert set(records) == set(runner.SOURCE_REPORTS)
    assert {record["report"] for record in records.values()} == set(
        runner.SOURCE_REPORTS.values()
    )
    assert plan["concurrency"] == {
        "heavy": 1,
        "light": 2,
        "runner_execution": "sequential",
    }
    assert set(plan["runtime_restrictions"].values()) == {"forbidden"}
    assert plan["execution_gate"] == {
        "document_status": "ENGINEERING_EXCEPTION_ACCEPTED_AWAITING_PHASE4_CHECKPOINT",
        "phase_4_engineering_checkpoint": "NOT_RECORDED",
        "next_phase_permission": "BLOCKED",
        "entry_decision": "BLOCKED_PENDING_PHASE_4_ENGINEERING_CHECKPOINT",
        "execute_allowed": False,
    }


def test_accepted_phase4_checkpoint_fixture_grants_only_phase5_engineering() -> None:
    document = _accepted_checkpoint_document("2" * 64)

    assert runner._validate_phase4_checkpoint_document(document) == {
        "phase4_candidate_commit": "e" * 40,
        "engineering_checkpoint": "PASS",
        "next_phase_permission": "PHASE_5_ENGINEERING_ONLY",
        "promotion_gate": "PENDING",
        "MIG-004": "PENDING_PROMOTION",
        "source_execution_manifest_sha256": "2" * 64,
    }


def test_authenticated_phase4_handoff_binds_git_blob_and_evidence_commit(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    source_manifest = tmp_path / "evidence" / "source-execution-manifest.json"
    source_manifest.parent.mkdir(parents=True)
    source_manifest.write_text('{"status":"PASS"}\n', encoding="utf-8")
    source_sha = runner.shared._sha256(source_manifest)
    checkpoint = source_manifest.parent / "phase-metrics.json"
    checkpoint.write_text(
        json.dumps(_accepted_checkpoint_document(source_sha)) + "\n",
        encoding="utf-8",
    )
    (source_manifest.parent / "candidate-commit.txt").write_text(
        "e" * 40 + "\n", encoding="utf-8"
    )
    monkeypatch.setattr(runner, "ROOT", tmp_path)
    monkeypatch.setattr(
        runner,
        "_git_bytes",
        lambda *args: checkpoint.read_bytes()
        if args[:1] == ("show",)
        else ("d" * 40 + "\n").encode("ascii"),
    )
    ancestors: list[tuple[str, str, str]] = []
    monkeypatch.setattr(
        runner,
        "_assert_ancestor",
        lambda ancestor, candidate, context: ancestors.append(
            (ancestor, candidate, context)
        ),
    )

    handoff = runner.authenticate_phase4_handoff(
        _accepted_matrix(), checkpoint, CANDIDATE
    )

    assert handoff["checkpoint_path"] == "evidence/phase-metrics.json"
    assert handoff["checkpoint_sha256"] == hashlib.sha256(
        checkpoint.read_bytes()
    ).hexdigest()
    assert handoff["evidence_commit"] == "d" * 40
    assert handoff["next_phase_permission"] == "PHASE_5_ENGINEERING_ONLY"
    assert ancestors == [
        ("e" * 40, CANDIDATE, "Phase 4 candidate"),
        ("d" * 40, CANDIDATE, "Phase 4 evidence commit"),
    ]


def _accepted_checkpoint_document(source_sha: str) -> dict:
    return {
        "schema_version": "temporal-first-phase-metrics.v1",
        "phase": 4,
        "candidate_commit": "e" * 40,
        "candidate_verification": {
            "failures": 0,
            "errors": 0,
            "skipped": 0,
            "mixed_candidate_results": False,
            "quarantined_attempts_reused": False,
        },
        "source_execution_manifest": {
            "name": "source-execution-manifest.json",
            "sha256": source_sha,
        },
        "status": {
            "engineering_checkpoint": "PASS",
            "promotion_gate": "PENDING",
            "next_phase_permission": "PHASE_5_ENGINEERING_ONLY",
            "MIG-003": "PENDING_PROMOTION",
            "MIG-004": "PENDING_PROMOTION",
        },
    }


def _accepted_matrix() -> dict:
    return {
        "document_status": "P5_0_CONTRACT_CANDIDATE_AWAITING_BATCH0",
        "gate": {
            "contract_gate_status": "NOT_RUN",
            "entry_decision": "READY_FOR_P5_BATCH_0",
            "observed_entry_state": {
                "phase_4_engineering_checkpoint": "PASS",
                "next_phase_permission": "PHASE_5_ENGINEERING_ONLY",
            },
        },
    }


def test_junit_transforms_preserve_matrix_argv_without_a_shell(tmp_path: Path) -> None:
    raw = tmp_path / "entry-report.xml"
    pytest_argv = runner._command_argv_for_source(
        "p5_entry_python",
        "python -m pytest tests/agents/test_evidence_clerk_turn.py",
        raw,
        report_suffix="unused",
    )
    frontend_argv = runner._command_argv_for_source(
        "p5_entry_frontend",
        "node vitest.mjs run evidence.test.js",
        raw,
        report_suffix="unused",
    )
    java_argv = runner._command_argv_for_source(
        "p5_entry_java",
        ".\\mvnw.cmd -Dtest=EvidenceApiIntegrationTest test",
        raw,
        report_suffix="p5-entry-aaaaaaaaaaaa-12345678",
    )

    assert pytest_argv[-1] == f"--junitxml={raw.resolve()}"
    assert frontend_argv[-2:] == [
        "--reporter=junit",
        f"--outputFile={raw.resolve()}",
    ]
    assert java_argv[-2:] == [
        "-Dsurefire.reportNameSuffix=p5-entry-aaaaaaaaaaaa-12345678",
        "test",
    ]


def test_frontend_dependency_preflight_requires_classified_infra_resume(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    frontend = runner.load_source_commands()["p5_entry_frontend"]
    monkeypatch.setattr(runner, "ROOT", tmp_path)
    failure = runner._preflight_failure("p5_entry_frontend", frontend)

    assert failure is not None
    assert "classify INFRA" in failure
    assert "same SHA" in failure

    manifest = _failed_manifest("p5_entry_frontend")
    assert runner._classify_pending_failure(
        manifest, {"p5_entry_frontend": "INFRA"}
    ) is True
    assert manifest["status"] == "RUNNING"
    assert manifest["quarantined_attempts"][0]["failure_classification"] == "INFRA"


def test_non_infra_failure_blocks_the_candidate() -> None:
    for classification in ("PRODUCT", "FIXTURE", "EXTERNAL_GATE"):
        manifest = _failed_manifest("p5_entry_static")
        assert runner._classify_pending_failure(
            manifest, {"p5_entry_static": classification}
        ) is False
        assert manifest["status"] == "CANDIDATE_BLOCKED"
        assert manifest["verification_finished_at"] is not None


def test_initial_manifest_freezes_non_promotion_state_and_phase4_hash(
    tmp_path: Path,
) -> None:
    manifest = runner._initial_manifest(
        candidate=CANDIDATE,
        environment_id="phase5-initial-gate-test",
        run_root=tmp_path / "phase5-initial-gate",
        phase4_handoff=HANDOFF,
    )

    assert {
        field: manifest[field] for field in runner.INITIAL_GATE_FIELDS
    } == runner.INITIAL_GATE_FIELDS
    assert manifest["upstream_phase4_checkpoint"] == HANDOFF
    assert manifest["environment"]["upstream_phase4_checkpoint"] == HANDOFF
    assert runner._validate_environment(manifest) == manifest["environment"][
        "snapshot_sha256"
    ]


def test_resume_rejects_a_tampered_sealed_manifest(tmp_path: Path) -> None:
    run_root = tmp_path / "phase5-entry-seal"
    run_root.mkdir()
    manifest = runner._initial_manifest(
        candidate=CANDIDATE,
        environment_id="phase5-entry-test",
        run_root=run_root,
        phase4_handoff=HANDOFF,
    )
    manifest_path = run_root / runner.MANIFEST_NAME
    runner._write_manifest(manifest_path, manifest)
    tampered = json.loads(manifest_path.read_text(encoding="utf-8"))
    tampered["status"] = "REQUIRES_CLASSIFICATION"
    manifest_path.write_text(json.dumps(tampered), encoding="utf-8")

    with pytest.raises(runner.shared.EvidenceError, match="manifest SHA-256 drifted"):
        runner._load_resume_manifest(run_root, CANDIDATE, HANDOFF)


def test_resume_rejects_a_resealed_forged_accepted_prefix(tmp_path: Path) -> None:
    run_root = tmp_path / "phase5-entry-forged-prefix"
    run_root.mkdir()
    manifest = runner._initial_manifest(
        candidate=CANDIDATE,
        environment_id="phase5-entry-forged-test",
        run_root=run_root,
        phase4_handoff=HANDOFF,
    )
    manifest["commands"] = [{"id": "p5_entry_static"}]
    runner._write_manifest(run_root / runner.MANIFEST_NAME, manifest)

    with pytest.raises(runner.shared.EvidenceError, match="record binding drifted"):
        runner._load_resume_manifest(run_root, CANDIDATE, HANDOFF)


def test_resume_rejects_resealed_pre_finish_gate_forgery(tmp_path: Path) -> None:
    run_root = tmp_path / "phase5-entry-forged-gate"
    run_root.mkdir()
    manifest = runner._initial_manifest(
        candidate=CANDIDATE,
        environment_id="phase5-entry-forged-gate-test",
        run_root=run_root,
        phase4_handoff=HANDOFF,
    )
    manifest.update(
        {
            "batch_0": "PASS",
            "contract_gate": "P5.0_PASS",
            "promotion_gate": "PASS",
            "MIG-004": "PASS",
        }
    )
    runner._write_manifest(run_root / runner.MANIFEST_NAME, manifest)

    with pytest.raises(runner.shared.EvidenceError, match="pre-finish batch_0 drifted"):
        runner._load_resume_manifest(run_root, CANDIDATE, HANDOFF)


def test_blocked_matrix_rejects_before_run_dir_or_source_invocation(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    observed: list[str] = []
    monkeypatch.setattr(
        runner,
        "_record_source",
        lambda **kwargs: observed.append(str(kwargs["command_id"])),
    )
    run_root = tmp_path / "blocked-matrix-must-not-exist"

    with pytest.raises(runner.shared.EvidenceError, match="matrix remains BLOCKED"):
        runner.execute_checkpoint(
            candidate_commit=CANDIDATE,
            run_root=run_root,
            environment_id="blocked-matrix-test",
            phase4_checkpoint_path=tmp_path / "missing-phase-metrics.json",
            resume=False,
            classifications=(),
        )
    assert observed == []
    assert not run_root.exists()


def test_fresh_run_schedules_every_source_once_and_stays_blocked_on_entry_commit(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    run_root = tmp_path / "phase5-entry-fresh"
    observed: list[str] = []
    monkeypatch.setattr(runner.shared, "assert_clean_detached_candidate", lambda *_: None)
    monkeypatch.setattr(runner, "_assert_candidate_unchanged", lambda *_: None)
    monkeypatch.setattr(
        runner.shared,
        "capture_environment",
        lambda _: {"snapshot_sha256": "c" * 64},
    )

    def record_source(**arguments: object) -> tuple[dict[str, str], bool]:
        command_id = str(arguments["command_id"])
        observed.append(command_id)
        return {"id": command_id}, True

    monkeypatch.setattr(runner, "_record_source", record_source)
    monkeypatch.setattr(
        runner,
        "authenticate_phase4_handoff",
        lambda *_args, **_kwargs: HANDOFF,
    )
    manifest = runner.execute_checkpoint(
        candidate_commit=CANDIDATE,
        run_root=run_root,
        environment_id="phase5-entry-test",
        phase4_checkpoint_path=tmp_path / "phase-metrics.json",
        resume=False,
        classifications=(),
    )

    assert observed == list(runner.COMMAND_ORDER)
    assert manifest["status"] == "PASS"
    assert manifest["batch_0"] == "PASS"
    assert manifest["contract_gate"] == "P5.0_AWAITING_ENTRY_EVIDENCE_COMMIT"
    assert manifest["engineering_execution"] == "BLOCKED_UNTIL_ENTRY_EVIDENCE_COMMIT"
    assert manifest["MIG-004"] == "PENDING_PROMOTION"
    assert manifest["MIG-005"] == "PENDING_PROMOTION"

    with pytest.raises(runner.shared.EvidenceError, match="already exists"):
        runner.execute_checkpoint(
            candidate_commit=CANDIDATE,
            run_root=run_root,
            environment_id="phase5-entry-test",
            phase4_checkpoint_path=tmp_path / "phase-metrics.json",
            resume=False,
            classifications=(),
        )


def test_cli_plan_is_non_executing_and_machine_readable() -> None:
    process = subprocess.run(
        [
            sys.executable,
            str(SCRIPT),
            "--candidate-commit",
            CANDIDATE,
        ],
        cwd=ROOT,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )

    assert process.returncode == 0, process.stderr
    plan = json.loads(process.stdout)
    assert plan["candidate_commit"] == CANDIDATE
    assert plan["execution_order"] == list(runner.COMMAND_ORDER)


def _failed_manifest(command_id: str) -> dict:
    return {
        "status": "REQUIRES_CLASSIFICATION",
        "pending_failure": {
            "id": command_id,
            "candidate_commit": CANDIDATE,
            "exit_code": 1,
            "accepted": False,
            "failure_classification": "UNCLASSIFIED",
        },
        "quarantined_attempts": [],
        "verification_finished_at": None,
    }
