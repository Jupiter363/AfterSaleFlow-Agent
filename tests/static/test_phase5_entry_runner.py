from __future__ import annotations

import importlib.util
import hashlib
import json
import os
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
    "candidate_commit_file_sha256": "3" * 64,
    "evidence_commit": "d" * 40,
    "phase4_candidate_commit": "e" * 40,
    "engineering_checkpoint": "PASS",
    "next_phase_permission": "PHASE_5_ENGINEERING_ONLY",
    "promotion_gate": "PENDING",
    "MIG-004": "PENDING_PROMOTION",
    "source_execution_manifest_sha256": "2" * 64,
    "source_execution_manifest_git_sha256": "4" * 64,
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
        "document_status": "P5_0_CONTRACT_CANDIDATE_AWAITING_BATCH0",
        "phase_4_engineering_checkpoint": "PASS",
        "next_phase_permission": "PHASE_5_ENGINEERING_ONLY",
        "evidence_v2_closed_contract_set": "FROZEN",
        "entry_decision": "READY_FOR_P5_BATCH_0",
        "execute_allowed": True,
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


def _write_phase4_bundle(tmp_path: Path) -> tuple[Path, Path]:
    source_manifest = tmp_path / "evidence" / "source-execution-manifest.json"
    source_manifest.parent.mkdir(parents=True)
    source_manifest.write_text('{"status":"PASS"}\n', encoding="utf-8")
    checkpoint = source_manifest.parent / "phase-metrics.json"
    checkpoint.write_text(
        json.dumps(
            _accepted_checkpoint_document(runner.shared._sha256(source_manifest))
        )
        + "\n",
        encoding="utf-8",
    )
    (source_manifest.parent / "candidate-commit.txt").write_text(
        "e" * 40 + "\n", encoding="utf-8"
    )
    return checkpoint, source_manifest


def _bundle_git_reader(
    root: Path, overrides: dict[str, bytes] | None = None
):
    overrides = overrides or {}

    def read(*args: str) -> bytes:
        if args[:1] == ("log",):
            return ("d" * 40 + "\n").encode("ascii")
        _, object_name = args
        if object_name in overrides:
            return overrides[object_name]
        relative = object_name.partition(":")[2]
        return (root / relative).read_bytes().replace(b"\r\n", b"\n")

    return read


def test_authenticated_phase4_handoff_binds_git_blob_and_evidence_commit(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    checkpoint, source_manifest = _write_phase4_bundle(tmp_path)
    monkeypatch.setattr(runner, "ROOT", tmp_path)
    shown: list[str] = []

    def git_bytes(*args: str) -> bytes:
        if args[:1] == ("log",):
            return ("d" * 40 + "\n").encode("ascii")
        _, object_name = args
        shown.append(object_name)
        relative = object_name.partition(":")[2]
        return (tmp_path / relative).read_bytes().replace(b"\r\n", b"\n")

    monkeypatch.setattr(
        runner,
        "_git_bytes",
        git_bytes,
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
        checkpoint.read_bytes().replace(b"\r\n", b"\n")
    ).hexdigest()
    assert handoff["candidate_commit_file_sha256"] == hashlib.sha256(
        (source_manifest.parent / "candidate-commit.txt")
        .read_bytes()
        .replace(b"\r\n", b"\n")
    ).hexdigest()
    assert handoff["source_execution_manifest_git_sha256"] == hashlib.sha256(
        source_manifest.read_bytes().replace(b"\r\n", b"\n")
    ).hexdigest()
    assert handoff["evidence_commit"] == "d" * 40
    assert handoff["next_phase_permission"] == "PHASE_5_ENGINEERING_ONLY"
    assert ancestors == [
        (
            "e" * 40,
            "d" * 40,
            "Phase 4 candidate before its evidence commit",
        ),
        ("d" * 40, CANDIDATE, "Phase 4 evidence commit"),
    ]
    bundle_paths = {
        "evidence/phase-metrics.json",
        "evidence/candidate-commit.txt",
        "evidence/source-execution-manifest.json",
    }
    assert set(shown) == {
        *(f"{CANDIDATE}:{path}" for path in bundle_paths),
        *(f"{'d' * 40}:{path}" for path in bundle_paths),
    }


def test_phase4_handoff_rejects_candidate_tree_bundle_blob_drift(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    checkpoint, _ = _write_phase4_bundle(tmp_path)
    candidate_file_object = f"{CANDIDATE}:evidence/candidate-commit.txt"
    monkeypatch.setattr(runner, "ROOT", tmp_path)
    monkeypatch.setattr(
        runner,
        "_git_bytes",
        _bundle_git_reader(tmp_path, {candidate_file_object: b"wrong candidate\n"}),
    )

    with pytest.raises(runner.shared.EvidenceError, match="P5 candidate.*Git blob"):
        runner.authenticate_phase4_handoff(_accepted_matrix(), checkpoint, CANDIDATE)


def test_phase4_handoff_rejects_missing_candidate_tree_bundle_blob(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    checkpoint, _ = _write_phase4_bundle(tmp_path)
    read_bundle = _bundle_git_reader(tmp_path)
    monkeypatch.setattr(runner, "ROOT", tmp_path)

    def missing_blob(*args: str) -> bytes:
        if args[:1] == ("show",) and args[1].endswith(":evidence/candidate-commit.txt"):
            raise runner.shared.EvidenceError("missing Git bundle blob")
        return read_bundle(*args)

    monkeypatch.setattr(runner, "_git_bytes", missing_blob)

    with pytest.raises(runner.shared.EvidenceError, match="missing Git bundle blob"):
        runner.authenticate_phase4_handoff(_accepted_matrix(), checkpoint, CANDIDATE)


def test_phase4_handoff_rejects_symlink_bundle_member(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    checkpoint, _ = _write_phase4_bundle(tmp_path)
    original = Path.is_symlink
    monkeypatch.setattr(
        Path,
        "is_symlink",
        lambda path: path.name == "candidate-commit.txt" or original(path),
    )
    monkeypatch.setattr(runner, "ROOT", tmp_path)

    with pytest.raises(runner.shared.EvidenceError, match="regular non-symlink"):
        runner.authenticate_phase4_handoff(_accepted_matrix(), checkpoint, CANDIDATE)


def test_phase4_handoff_rejects_non_eol_carriage_returns(tmp_path: Path) -> None:
    artifact = tmp_path / "invalid-carriage-return.json"
    artifact.write_bytes(b'{"value":"a\rb"}\n')

    with pytest.raises(runner.shared.EvidenceError, match="unsupported carriage returns"):
        runner._canonical_text_bytes(artifact)


def test_phase4_handoff_rejects_bundle_changed_after_evidence_commit(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    checkpoint, _ = _write_phase4_bundle(tmp_path)
    evidence_source_object = f"{'d' * 40}:evidence/source-execution-manifest.json"
    monkeypatch.setattr(runner, "ROOT", tmp_path)
    monkeypatch.setattr(
        runner,
        "_git_bytes",
        _bundle_git_reader(tmp_path, {evidence_source_object: b"older source manifest\n"}),
    )
    monkeypatch.setattr(runner, "_assert_ancestor", lambda *_args: None)

    with pytest.raises(runner.shared.EvidenceError, match="evidence commit.*Git blob"):
        runner.authenticate_phase4_handoff(_accepted_matrix(), checkpoint, CANDIDATE)


def test_phase4_handoff_rejects_reversed_or_parallel_candidate_evidence_histories(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    checkpoint, _ = _write_phase4_bundle(tmp_path)
    monkeypatch.setattr(runner, "ROOT", tmp_path)
    monkeypatch.setattr(runner, "_git_bytes", _bundle_git_reader(tmp_path))

    def reject_parallel(ancestor: str, candidate: str, context: str) -> None:
        if context.startswith("Phase 4 candidate before"):
            raise runner.shared.EvidenceError("parallel Phase 4 histories")

    monkeypatch.setattr(runner, "_assert_ancestor", reject_parallel)

    with pytest.raises(runner.shared.EvidenceError, match="parallel Phase 4 histories"):
        runner.authenticate_phase4_handoff(_accepted_matrix(), checkpoint, CANDIDATE)


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
            "accepted_phase4_candidate": "e" * 40,
            "accepted_phase4_evidence_commit": "d" * 40,
            "accepted_phase4_checkpoint": "evidence/phase-metrics.json",
            "observed_entry_state": {
                "phase_4_engineering_checkpoint": "PASS",
                "next_phase_permission": "PHASE_5_ENGINEERING_ONLY",
                "evidence_v2_closed_contract_set": "FROZEN",
            },
        },
    }


def test_batch0_rejects_an_unfrozen_evidence_contract_set() -> None:
    matrix = _accepted_matrix()
    matrix["gate"]["observed_entry_state"]["evidence_v2_closed_contract_set"] = (
        "MISSING"
    )

    assert runner._matrix_allows_batch0(matrix) is False


@pytest.mark.parametrize(
    ("field", "value", "message"),
    (
        ("accepted_phase4_candidate", "f" * 40, "checkpoint candidate differs"),
        ("accepted_phase4_evidence_commit", "c" * 40, "evidence commit differs"),
    ),
)
def test_phase4_handoff_rejects_matrix_sha_drift(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    field: str,
    value: str,
    message: str,
) -> None:
    checkpoint, _ = _write_phase4_bundle(tmp_path)
    matrix = _accepted_matrix()
    matrix["gate"][field] = value
    monkeypatch.setattr(runner, "ROOT", tmp_path)
    monkeypatch.setattr(runner, "_git_bytes", _bundle_git_reader(tmp_path))
    monkeypatch.setattr(runner, "_assert_ancestor", lambda *_args: None)

    with pytest.raises(runner.shared.EvidenceError, match=message):
        runner.authenticate_phase4_handoff(matrix, checkpoint, CANDIDATE)


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


def test_java_source_rejects_stale_candidate_specific_surefire_report(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    run_root = tmp_path / ".codex-run" / "phase5-entry-stale-java"
    attempt_dir = run_root / "attempts" / "p5_entry_java-01"
    report_suffix = (
        f"p5-entry-{CANDIDATE[:12]}-"
        f"{hashlib.sha256(str(attempt_dir).encode('utf-8')).hexdigest()[:8]}"
    )
    report_dir = tmp_path / "java-api-service" / "target" / "surefire-reports"
    report_dir.mkdir(parents=True)
    (report_dir / f"TEST-stale-{report_suffix}.xml").write_text(
        "<testsuite tests='1'/>", encoding="utf-8"
    )
    monkeypatch.setattr(runner, "ROOT", tmp_path)
    monkeypatch.setattr(
        runner.shared,
        "_run_shell",
        lambda *_args: pytest.fail("source command must not start with stale reports"),
    )

    with pytest.raises(runner.shared.EvidenceError, match="suffix is not unique"):
        runner._record_source(
            command_id="p5_entry_java",
            candidate=CANDIDATE,
            run_root=run_root,
            matrix_item={
                "cwd": "java-api-service",
                "command": ".\\mvnw.cmd -Dtest=EvidenceApiIntegrationTest test",
            },
            environment_sha256="b" * 64,
        )


def test_java_failure_retains_long_surefire_names_and_seals_pending_manifest(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    run_root = tmp_path / ".codex-run" / "phase5-entry-long-java-report"
    java_root = tmp_path / "java-api-service"
    java_root.mkdir()
    (tmp_path / "python-agent-service").mkdir()
    dependency = tmp_path / "dependency.lock"
    dependency.write_bytes(b"locked\n")
    payload = b"<testsuite tests='1' failures='1'/>\n"
    original_names: list[str] = []

    def run_source(
        argv: list[str], cwd: Path, stdout_path: Path, stderr_path: Path
    ) -> tuple[str, str, float, int]:
        timestamp = runner.shared._utc_now()
        if not stdout_path.parent.name.startswith("p5_entry_java-"):
            junit_argument = next(
                argument for argument in argv if argument.startswith("--junitxml=")
            )
            Path(junit_argument.partition("=")[2]).write_bytes(
                b"<testsuite tests='1' failures='0' errors='0' skipped='0' "
                b"time='0.01'><testcase classname='fixture' name='passes'/>"
                b"</testsuite>\n"
            )
            stdout_path.write_bytes(b"")
            stderr_path.write_bytes(b"")
            return timestamp, timestamp, 0.0, 0
        suffix_argument = next(
            argument
            for argument in argv
            if argument.startswith("-Dsurefire.reportNameSuffix=")
        )
        suffix = suffix_argument.partition("=")[2]
        report_dir = cwd / "target" / "surefire-reports"
        report_dir.mkdir(parents=True)
        retained_dir = (
            run_root / "attempts" / "p5_entry_java-01" / "raw-surefire"
        )
        fixed_length = len(f"TEST--A-{suffix}.xml")
        target_length = min(240, 245 - len(str(report_dir.resolve())) - 1)
        class_length = target_length - fixed_length
        assert class_length > 0
        for marker in ("A", "B"):
            name = f"TEST-{'L' * class_length}-{marker}-{suffix}.xml"
            path = report_dir / name
            assert len(str(path.resolve())) <= 245
            assert len(str((retained_dir / name).resolve())) > 260
            path.write_bytes(payload)
            original_names.append(name)
        stdout_path.write_bytes(b"")
        stderr_path.write_bytes(b"java tests failed\n")
        return timestamp, timestamp, 0.0, 1

    monkeypatch.setattr(runner, "ROOT", tmp_path)
    monkeypatch.setattr(runner.shared, "assert_clean_detached_candidate", lambda *_args: None)
    monkeypatch.setattr(runner, "_assert_candidate_unchanged", lambda *_args: None)
    monkeypatch.setattr(
        runner.shared,
        "capture_environment",
        lambda environment_id: {
            "environment_id": environment_id,
            "dependency_manifests": [
                {
                    "path": dependency.name,
                    "sha256": runner.shared._sha256(dependency),
                }
            ],
        },
    )
    monkeypatch.setattr(
        runner,
        "authenticate_phase4_handoff",
        lambda *_args, **_kwargs: HANDOFF,
    )
    monkeypatch.setattr(runner.shared, "_run_shell", run_source)

    manifest = runner.execute_checkpoint(
        candidate_commit=CANDIDATE,
        run_root=run_root,
        environment_id="phase5-long-surefire-test",
        phase4_checkpoint_path=tmp_path / "phase-metrics.json",
        resume=False,
        classifications=(),
    )

    assert manifest["status"] == "REQUIRES_CLASSIFICATION"
    pending = manifest["pending_failure"]
    assert pending["id"] == "p5_entry_java"
    assert pending["exit_code"] == 1
    assert pending["failure_classification"] == "UNCLASSIFIED"
    assert pending["accepted"] is False
    assert [item["original_name"] for item in pending["raw_reports"]] == sorted(
        original_names
    )
    retained_names = [Path(item["path"]).name for item in pending["raw_reports"]]
    assert retained_names == [
        f"0001-{hashlib.sha256(payload).hexdigest()[:16]}.xml",
        f"0002-{hashlib.sha256(payload).hexdigest()[:16]}.xml",
    ]
    assert len(set(retained_names)) == 2
    for item in pending["raw_reports"]:
        retained = run_root / item["path"]
        assert len(str(retained.resolve())) < 260
        assert retained.read_bytes() == payload
        assert item["sha256"] == runner.shared._sha256(retained)

    persisted = json.loads((run_root / runner.MANIFEST_NAME).read_text(encoding="utf-8"))
    assert persisted == manifest
    runner.shared._assert_execution_manifest_seal(persisted)
    assert runner._load_resume_manifest(run_root, CANDIDATE, HANDOFF) == manifest

    manifest_path = run_root / runner.MANIFEST_NAME
    baseline = json.loads(json.dumps(manifest))
    current_suffix = pending["executed_argv"][-2].partition("=")[2]
    first_retained = run_root / pending["raw_reports"][0]["path"]
    forged_retained = first_retained.with_name(
        f"9999-{pending['raw_reports'][0]['sha256'][:16]}.xml"
    )
    forged_retained.write_bytes(first_retained.read_bytes())

    def drift_suffix(document: dict) -> None:
        record = document["pending_failure"]
        record["executed_argv"][-2] = (
            f"-Dsurefire.reportNameSuffix=p5-entry-{CANDIDATE[:12]}-ffffffff"
        )
        record["executed_command"] = runner.shared.render_command_argv(
            record["executed_argv"]
        )
        record["executed_command_sha256"] = hashlib.sha256(
            record["executed_command"].encode("utf-8")
        ).hexdigest()

    def drift_original_name(document: dict) -> None:
        document["pending_failure"]["raw_reports"][0]["original_name"] = (
            f"../TEST-forged-{current_suffix}.xml"
        )

    def drift_short_path(document: dict) -> None:
        document["pending_failure"]["raw_reports"][0]["path"] = (
            runner.shared._relative(forged_retained, run_root)
        )

    def drift_original_order(document: dict) -> None:
        document["pending_failure"]["raw_reports"].reverse()

    def drift_raw_fields(document: dict) -> None:
        document["pending_failure"]["raw_reports"][0]["unexpected"] = True

    for mutate, message in (
        (drift_suffix, "current Surefire report suffix drifted"),
        (drift_original_name, "original name drifted"),
        (drift_short_path, "short path drifted"),
        (drift_original_order, "not unique and sorted"),
        (drift_raw_fields, "raw JUnit fields drifted"),
    ):
        forged = json.loads(json.dumps(baseline))
        mutate(forged)
        runner._write_manifest(manifest_path, forged)
        with pytest.raises(runner.shared.EvidenceError, match=message):
            runner._load_resume_manifest(run_root, CANDIDATE, HANDOFF)

    forged = json.loads(json.dumps(baseline))
    forged["commands"][0]["raw_reports"][0]["original_name"] = (
        f"TEST-forged-{current_suffix}.xml"
    )
    runner._write_manifest(manifest_path, forged)
    with pytest.raises(runner.shared.EvidenceError, match="non-Java raw JUnit"):
        runner._load_resume_manifest(run_root, CANDIDATE, HANDOFF)


@pytest.mark.parametrize(
    ("failure_mode", "reason"),
    (
        ("mkdir", "cannot create retained Surefire directory"),
        ("write", "cannot retain Surefire report"),
    ),
)
def test_java_retention_io_failure_becomes_classifiable_pending_manifest(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    failure_mode: str,
    reason: str,
) -> None:
    run_root = tmp_path / ".codex-run" / f"phase5-entry-java-{failure_mode}-failure"
    java_root = tmp_path / "java-api-service"
    java_root.mkdir()
    original_record_source = runner._record_source

    def run_java(
        argv: list[str], cwd: Path, stdout_path: Path, stderr_path: Path
    ) -> tuple[str, str, float, int]:
        suffix_argument = next(
            argument
            for argument in argv
            if argument.startswith("-Dsurefire.reportNameSuffix=")
        )
        suffix = suffix_argument.partition("=")[2]
        report = cwd / "target" / "surefire-reports" / f"TEST-failed-{suffix}.xml"
        report.parent.mkdir(parents=True)
        report.write_bytes(b"<testsuite tests='1' failures='1'/>\n")
        stdout_path.write_bytes(b"")
        stderr_path.write_bytes(b"java tests failed\n")
        timestamp = runner.shared._utc_now()
        return timestamp, timestamp, 0.0, 1

    def record_source(**arguments: object) -> tuple[dict[str, object], bool]:
        if arguments["command_id"] != "p5_entry_java":
            return {"id": str(arguments["command_id"])}, True
        return original_record_source(**arguments)  # type: ignore[arg-type]

    if failure_mode == "mkdir":
        original_mkdir = Path.mkdir

        def fail_retained_mkdir(path: Path, *args: object, **kwargs: object) -> None:
            if path.name == "raw-surefire":
                raise OSError("simulated retained directory failure")
            original_mkdir(path, *args, **kwargs)  # type: ignore[arg-type]

        monkeypatch.setattr(Path, "mkdir", fail_retained_mkdir)
    else:
        original_open = Path.open

        def fail_retained_write(
            path: Path, mode: str = "r", *args: object, **kwargs: object
        ):
            if path.parent.name == "raw-surefire" and mode == "xb":
                raise OSError("simulated retained report write failure")
            return original_open(path, mode, *args, **kwargs)  # type: ignore[arg-type]

        monkeypatch.setattr(Path, "open", fail_retained_write)

    monkeypatch.setattr(runner, "ROOT", tmp_path)
    monkeypatch.setattr(runner.shared, "assert_clean_detached_candidate", lambda *_args: None)
    monkeypatch.setattr(runner, "_assert_candidate_unchanged", lambda *_args: None)
    monkeypatch.setattr(
        runner.shared,
        "capture_environment",
        lambda _environment_id: {"snapshot_sha256": "c" * 64},
    )
    monkeypatch.setattr(
        runner,
        "authenticate_phase4_handoff",
        lambda *_args, **_kwargs: HANDOFF,
    )
    monkeypatch.setattr(runner.shared, "_run_shell", run_java)
    monkeypatch.setattr(runner, "_record_source", record_source)

    manifest = runner.execute_checkpoint(
        candidate_commit=CANDIDATE,
        run_root=run_root,
        environment_id=f"phase5-java-{failure_mode}-failure",
        phase4_checkpoint_path=tmp_path / "phase-metrics.json",
        resume=False,
        classifications=(),
    )

    assert manifest["status"] == "REQUIRES_CLASSIFICATION"
    pending = manifest["pending_failure"]
    assert pending["id"] == "p5_entry_java"
    assert pending["exit_code"] == 2
    assert pending["failure_classification"] == "UNCLASSIFIED"
    assert pending["accepted"] is False
    assert pending["raw_reports"] == []
    assert reason in pending["failure_reason"]
    persisted = json.loads((run_root / runner.MANIFEST_NAME).read_text(encoding="utf-8"))
    assert persisted == manifest
    runner.shared._assert_execution_manifest_seal(persisted)


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


def test_frontend_dependency_preflight_allows_candidate_relative_directory_link(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    frontend = runner.load_source_commands()["p5_entry_frontend"]
    candidate = tmp_path / "candidate"
    dependencies = tmp_path / "shared-dependencies"
    vitest = dependencies / "vitest" / "vitest.mjs"
    vitest.parent.mkdir(parents=True)
    vitest.write_text("", encoding="utf-8")
    link = candidate / "frontend" / "node_modules"
    link.parent.mkdir(parents=True)
    try:
        link.symlink_to(dependencies, target_is_directory=True)
    except OSError:
        if os.name != "nt":
            pytest.skip("directory symlinks are unavailable")
        process = subprocess.run(
            ["cmd.exe", "/d", "/c", "mklink", "/J", str(link), str(dependencies)],
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        if process.returncode:
            pytest.skip(f"directory junctions are unavailable: {process.stderr}")

    monkeypatch.setattr(runner, "ROOT", candidate)

    assert runner._preflight_failure("p5_entry_frontend", frontend) is None


@pytest.mark.parametrize(
    "required_path",
    [
        "../outside",
        "..\\outside",
        "C:outside",
        "C:/outside",
        "\\Windows\\System32\\cmd.exe",
        "/Windows/System32/cmd.exe",
        "\\\\server\\share\\file",
    ],
)
def test_frontend_dependency_preflight_rejects_non_relative_paths(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, required_path: str
) -> None:
    frontend = dict(runner.load_source_commands()["p5_entry_frontend"])
    frontend["preflight"] = dict(frontend["preflight"], required_path=required_path)
    monkeypatch.setattr(runner, "ROOT", tmp_path)

    with pytest.raises(runner.shared.EvidenceError, match="path escapes"):
        runner._preflight_failure("p5_entry_frontend", frontend)


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


def test_manifest_writer_uses_lf_bytes_independent_of_shared_writer(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    def write_crlf(path: Path, document: object) -> None:
        payload = json.dumps(document, ensure_ascii=False, indent=2) + "\n"
        path.write_bytes(payload.replace("\n", "\r\n").encode("utf-8"))

    monkeypatch.setattr(runner.shared, "_write_json", write_crlf)
    manifest = {"schema_version": runner.SCHEMA_VERSION, "status": "RUNNING"}
    path = tmp_path / runner.MANIFEST_NAME

    runner._write_manifest(path, manifest)

    payload = path.read_bytes()
    assert payload.endswith(b"\n")
    assert b"\r\n" not in payload
    assert json.loads(payload.decode("utf-8")) == manifest
    runner.shared._assert_execution_manifest_seal(manifest)


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
        "load_matrix",
        lambda: {
            "document_status": "ENGINEERING_EXCEPTION_ACCEPTED_AWAITING_PHASE4_CHECKPOINT",
            "gate": {
                "contract_gate_status": "NOT_RUN",
                "entry_decision": "BLOCKED_PENDING_PHASE_4_ENGINEERING_CHECKPOINT",
                "observed_entry_state": {
                    "phase_4_engineering_checkpoint": "NOT_RECORDED",
                    "next_phase_permission": "BLOCKED",
                },
            },
        },
    )
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
