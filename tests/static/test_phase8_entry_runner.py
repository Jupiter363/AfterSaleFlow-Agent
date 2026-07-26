from __future__ import annotations

import copy
import functools
import hashlib
from pathlib import Path
import sys
from types import SimpleNamespace

import pytest
import yaml

from scripts import run_phase8_entry_checkpoint as runner


_FROZEN_C8 = "74f4cb6bc2ac78f17aacdb36378e72ff650d60b6"
_FROZEN_MATRIX_BLOB = "3e37d778dcbca8819b12144d8e4f32d7dd54744e"
_FROZEN_MATRIX_PATH = "plans/phase-8-production-hardening-test-batches.yaml"


@functools.lru_cache(maxsize=1)
def _frozen_batch0_matrix() -> dict[str, object]:
    """Load the immutable accepted C8 matrix without reviving the live plan."""
    if runner._git("cat-file", "-t", _FROZEN_C8) != "commit":
        raise AssertionError("accepted C8 is not an available Git commit")
    tree_entry = runner._git("ls-tree", _FROZEN_C8, "--", _FROZEN_MATRIX_PATH)
    expected_entry = (
        f"100644 blob {_FROZEN_MATRIX_BLOB}\t{_FROZEN_MATRIX_PATH}"
    )
    if tree_entry != expected_entry:
        raise AssertionError("accepted C8 matrix blob identity drifted")
    raw = runner._git_bytes("show", f"{_FROZEN_C8}:{_FROZEN_MATRIX_PATH}")
    header = f"blob {len(raw)}\0".encode("ascii")
    if hashlib.sha1(header + raw).hexdigest() != _FROZEN_MATRIX_BLOB:
        raise AssertionError("accepted C8 matrix blob content failed Git SHA-1")
    matrix = yaml.safe_load(raw)
    if (
        not isinstance(matrix, dict)
        or matrix.get("phase") != 8
        or matrix.get("document_status")
        != "FROZEN_CONTRACT_CANDIDATE_AWAITING_BATCH_0"
    ):
        raise AssertionError("accepted C8 matrix is not the frozen Batch 0 contract")
    return matrix


@pytest.fixture(autouse=True)
def _use_frozen_batch0_contract(monkeypatch: pytest.MonkeyPatch) -> None:
    frozen_matrix = _frozen_batch0_matrix()
    frozen_argv = frozen_matrix["batches"]["batch_0_entry"]["source_commands"][0][
        "argv"
    ]
    assert runner.ARGV_TEMPLATE[0] == "D:/miniconda/python.exe"
    assert tuple(frozen_argv) == runner.ARGV_TEMPLATE

    matrix = copy.deepcopy(frozen_matrix)
    normalized_template = list(runner.ARGV_TEMPLATE)
    normalized_template[0] = str(Path(sys.executable).resolve(strict=True))
    matrix["batches"]["batch_0_entry"]["source_commands"][0]["argv"][0] = (
        normalized_template[0]
    )
    monkeypatch.setattr(runner, "ARGV_TEMPLATE", tuple(normalized_template))
    monkeypatch.setattr(runner, "load_matrix", lambda: copy.deepcopy(matrix))


def _fake_candidate_git(*arguments: str, check: bool = True) -> str:
    del check
    if arguments[:1] == ("for-each-ref",):
        return ""
    if arguments[:3] == ("rev-parse", "--git-path", "info/grafts"):
        return "Z:/phase8-entry-tests/no-grafts"
    if arguments[:2] == ("cat-file", "-t"):
        return "commit"
    if arguments[:1] == ("rev-parse",):
        if arguments[1].endswith("^{commit}"):
            return "c" * 40
        if arguments[1].endswith("^{tree}"):
            return "d" * 40
    if arguments[:3] == ("rev-list", "--parents", "-n"):
        return f"{'c' * 40} {runner.A7}"
    if arguments[:2] == ("diff-tree", "--no-commit-id"):
        return "\n".join(
            f"{runner.EXPECTED_CHANGE_STATUS[path]}\t{path}"
            for path in runner.C8_ALLOWED_PATHS
        )
    if arguments[:1] == ("ls-tree",):
        path = arguments[-1]
        return f"100644 blob {'a' * 40}\t{path}"
    raise AssertionError(arguments)


def _junit(tests: int = runner.MINIMUM_TESTS) -> str:
    cases = "".join(
        f'<testcase classname="phase8.contract" name="test_{number}" time="0" />'
        for number in range(tests)
    )
    return (
        f'<testsuite name="phase8" tests="{tests}" failures="0" errors="0" '
        f'skipped="0" time="0">{cases}</testsuite>\n'
    )


def _normalized_junit(tests: int = runner.MINIMUM_TESTS) -> str:
    cases = "".join(
        f'<testcase classname="phase8.contract" name="test_{number}" time="0" />'
        for number in range(tests)
    )
    return (
        f'<testsuites name="phase8" tests="{tests}" failures="0" errors="0" '
        f'skipped="0" time="0" candidate_commit="{"c" * 40}" '
        f'source_command_id="{runner.SOURCE_ID}"><testsuite name="phase8" '
        f'tests="{tests}" failures="0" errors="0" skipped="0" time="0">'
        f"{cases}</testsuite></testsuites>\n"
    )


def _green_bundle(tmp_path: Path) -> tuple[Path, dict[str, object]]:
    run_root = tmp_path / "run"
    (run_root / "p").mkdir(parents=True)
    attempt_dir = tmp_path / ".phase8-entry-attempts"
    attempt_dir.mkdir()
    attempt_path = attempt_dir / f"{'c' * 40}.json"
    attempt_claim = {
        "schema_version": "phase8-entry-attempt-claim.v1",
        "candidate_sha": "c" * 40,
        "attempt_number": 1,
        "run_dir": str(run_root.resolve()),
        "environment_id": "synthetic-phase8-entry-test",
        "claimed_at": "2026-07-24T23:59:58.000+00:00",
        "retry_allowed": False,
        "self_seal_trust": "INTEGRITY_ONLY_NOT_PROVENANCE_OR_AUTHORIZATION",
    }
    runner._write_json(attempt_path, attempt_claim)
    artifacts = {
        "p/00-stdout.log": "all green\n",
        "p/01-stderr.log": "",
        "p/02-junit.xml": _junit(),
        runner.NORMALIZED_REPORT_NAME: _normalized_junit(),
    }
    for relative, content in artifacts.items():
        (run_root / relative).write_text(content, encoding="utf-8", newline="\n")
    command_argv = [
        item.replace(
            "{absolute_raw_report}", str((run_root / "p/02-junit.xml").resolve())
        )
        for item in runner.ARGV_TEMPLATE
    ]
    environment: dict[str, object] = {
        "schema_version": "phase8-entry-source-tree-environment.v1",
        "environment_id": "synthetic-phase8-entry-test",
        "captured_at": "2026-07-24T23:59:59.000+00:00",
        "candidate_sha": "c" * 40,
        "candidate_tree_sha": "d" * 40,
        "os": "TestOS",
        "os_release": "1",
        "architecture": "test",
        "python_version": "3.13",
        "python_implementation": "CPython",
        "python_executable": runner.ARGV_TEMPLATE[0],
        "git_version": "git version test",
        "timezone": "UTC",
        "dependency_git_blobs": [],
        "source_git_blobs": [],
        "command_argv_sha256": runner._json_sha256(command_argv),
        "subprocess_environment_keys": sorted(runner._subprocess_environment()),
        "pytest_plugin_autoload_disabled": True,
    }
    environment_digest = runner._json_sha256(environment)
    environment["snapshot_sha256"] = environment_digest
    environment["environment_sha256"] = environment_digest
    environment_path = run_root / runner.ENVIRONMENT_NAME
    runner._write_json(environment_path, environment)
    command = {
        "id": runner.SOURCE_ID,
        "argv": command_argv,
        "argv_sha256": runner._json_sha256(command_argv),
        "cwd": ".",
        "resource_class": "light",
        "shell": False,
        "started_at": "2026-07-25T00:00:00.000+00:00",
        "ended_at": "2026-07-25T00:00:01.000+00:00",
        "duration_ms": 1000,
        "exit_code": 0,
        "candidate_sha_before": "c" * 40,
        "candidate_sha_after": "c" * 40,
        "stdout_path": "p/00-stdout.log",
        "stdout_sha256": runner._sha256(run_root / "p/00-stdout.log"),
        "stderr_path": "p/01-stderr.log",
        "stderr_sha256": runner._sha256(run_root / "p/01-stderr.log"),
        "raw_report_path": "p/02-junit.xml",
        "raw_report_sha256": runner._sha256(run_root / "p/02-junit.xml"),
        "normalized_report_path": runner.NORMALIZED_REPORT_NAME,
        "normalized_report_sha256": runner._sha256(
            run_root / runner.NORMALIZED_REPORT_NAME
        ),
        "report_kind": "PYTEST_JUNIT",
        "tests": runner.MINIMUM_TESTS,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
        "accepted": True,
        "failure_classification": "NONE",
    }
    manifest: dict[str, object] = {
        "schema_version": runner.SCHEMA_VERSION,
        "phase": 8,
        "release": "phase-8-entry-20260725-cccccccccccc",
        "candidate_sha": "c" * 40,
        "candidate_commit": "c" * 40,
        "accepted_phase_7_candidate_C7": runner.C7,
        "accepted_phase_7_evidence_E7": runner.E7,
        "accepted_phase_7_checkpoint_A7": runner.A7,
        "candidate_parent": runner.A7,
        "candidate_changed_paths": list(runner.C8_ALLOWED_PATHS),
        "candidate_diff": [],
        "candidate_tree_sha": "d" * 40,
        "dependency_git_blobs": [],
        "accepted_phase_7_authority": {},
        "git_tree_clean_before": True,
        "git_tree_clean_after": True,
        "environment": environment,
        "environment_file": runner.ENVIRONMENT_NAME,
        "environment_sha256": runner._sha256(environment_path),
        "commands": [command],
        "status": runner.GREEN_STATUS,
        "contract_gate": "P8.0_NOT_RUN",
        "p8_0_contract_gate": "REMAINS_NOT_RUN_UNTIL_A8",
        "implementation_authorized": False,
        "implementation": "REMAINS_BLOCKED_UNTIL_A8",
        "retry_count": 0,
        "resume_used": False,
        "report_reuse_used": False,
        "quarantine_used": False,
        "MIG-006": "PENDING_PROMOTION",
        "MIG-007": "PENDING_PROMOTION",
        "MIG-008": "PENDING_PROMOTION",
        "production_capabilities": {
            key: False for key in runner.PRODUCTION_CAPABILITY_KEYS
        },
        "self_seal_trust": "INTEGRITY_ONLY_NOT_PROVENANCE_OR_AUTHORIZATION",
        "local_threat_model": "HOSTILE_LOCAL_ADMIN_OR_OPERATOR_OUT_OF_SCOPE",
        "production_attestation_requirement": (
            "EXTERNALLY_ATTESTED_CI_OIDC_KMS_OR_EQUIVALENT_SIGNED_EXECUTION_RECEIPT"
        ),
        "attempt_ledger": {
            "path": str(attempt_path.resolve()),
            "sha256": runner._sha256(attempt_path),
            "candidate_sha": "c" * 40,
            "attempt_number": 1,
            "run_dir": str(run_root.resolve()),
        },
        "verification_started_at": "2026-07-25T00:00:00.000+00:00",
        "verification_finished_at": "2026-07-25T00:00:01.000+00:00",
    }
    runner._seal_manifest(manifest)
    manifest_path = run_root / runner.MANIFEST_NAME
    runner._write_json(manifest_path, manifest)
    return manifest_path, manifest


def _patch_green_dependencies(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    monkeypatch.setattr(runner, "APPROVED_RUN_ROOT", tmp_path)
    monkeypatch.setattr(
        runner,
        "assert_contract_candidate",
        lambda candidate: {
            "candidate_sha": candidate,
            "candidate_parent": runner.A7,
            "candidate_changed_paths": list(runner.C8_ALLOWED_PATHS),
            "candidate_diff": [],
            "candidate_tree_sha": "d" * 40,
            "dependency_blobs": [],
            "phase7_authority": {},
        },
    )


def _rewrite_manifest(path: Path, manifest: dict[str, object]) -> None:
    runner._seal_manifest(manifest)
    path.unlink()
    runner._write_json(path, manifest)


def test_frozen_runner_constants_match_phase8_contract() -> None:
    assert runner.SCHEMA_VERSION == "phase8-entry-execution-manifest.v1"
    assert runner.GREEN_STATUS == (
        "SOURCES_GREEN_AWAITING_SOLE_PARENT_E8_ENTRY_EVIDENCE"
    )
    assert len(runner.C8_ALLOWED_PATHS) == 12
    assert runner.EXPECTED_CHANGE_STATUS == {
        path: ("M" if path == "plans/temporal-langgraph-room-refactor.md" else "A")
        for path in runner.C8_ALLOWED_PATHS
    }
    assert len(runner.SELECTORS) == 5


def test_matrix_exposes_exact_single_shell_false_source() -> None:
    contract = runner.source_contract(runner.load_matrix())
    assert contract["id"] == runner.SOURCE_ID
    assert contract["shell"] is False
    assert contract["minimum_tests"] == 24
    assert tuple(contract["argv"]) == runner.ARGV_TEMPLATE
    assert tuple(contract["selectors"]) == runner.SELECTORS
    batch = runner.load_matrix()["batches"]["batch_0_entry"]
    assert tuple(batch["runner"]["invocation_argv"]) == runner.INVOCATION_TEMPLATE
    assert batch["retry_policy"]["execution_attempt_limit_per_candidate"] == 1
    assert batch["retry_policy"]["retry_allowed"] is False
    assert batch["retry_policy"]["same_sha_retry_allowed_only_for"] == []


def test_git_subprocess_environment_disables_object_replacement() -> None:
    environment = runner._git_environment()
    assert environment["GIT_NO_REPLACE_OBJECTS"] == "1"
    assert "GIT_REPLACE_REF_BASE" not in environment
    assert environment["GIT_CONFIG_NOSYSTEM"] == "1"


def test_git_object_rewrite_guard_rejects_replace_refs(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        runner,
        "_git",
        lambda *args, **kwargs: (
            "refs/replace/" + "a" * 40 if args[:1] == ("for-each-ref",) else ""
        ),
    )
    with pytest.raises(runner.EvidenceError, match="replacement refs"):
        runner.assert_no_git_object_rewrite_state()


def test_git_object_rewrite_guard_rejects_legacy_grafts(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    graft = tmp_path / "grafts"
    graft.write_text(f"{'a' * 40} {'b' * 40}\n", encoding="ascii")

    def fake_git(*args: str, **kwargs) -> str:
        if args[:1] == ("for-each-ref",):
            return ""
        if args[:3] == ("rev-parse", "--git-path", "info/grafts"):
            return str(graft)
        raise AssertionError(args)

    monkeypatch.setattr(runner, "_git", fake_git)
    with pytest.raises(runner.EvidenceError, match="graft"):
        runner.assert_no_git_object_rewrite_state()


@pytest.mark.parametrize(
    ("path", "value", "message"),
    [
        (("gate", "contract_gate_status"), "PASS", "authority ceiling"),
        (("gate", "implementation_authorized"), True, "authority ceiling"),
        (("batches", "batch_0_entry", "source_order"), [], "source order"),
        (
            ("batches", "batch_0_entry", "source_commands", 0, "shell"),
            True,
            "shell",
        ),
        (
            ("batches", "batch_0_entry", "source_commands", 0, "minimum_tests"),
            1,
            "minimum_tests",
        ),
    ],
)
def test_source_contract_rejects_authority_or_command_drift(
    path: tuple[object, ...], value: object, message: str
) -> None:
    matrix = copy.deepcopy(runner.load_matrix())
    cursor: object = matrix
    for key in path[:-1]:
        cursor = cursor[key]  # type: ignore[index]
    cursor[path[-1]] = value  # type: ignore[index]
    with pytest.raises(runner.EvidenceError, match=message):
        runner.source_contract(matrix)


def test_candidate_accepts_exact_parent_status_and_regular_blobs(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(runner, "_git", _fake_candidate_git)
    monkeypatch.setattr(runner, "authenticate_phase7_handoff", lambda: {})
    candidate = runner.assert_contract_candidate("c" * 40)
    assert candidate["candidate_parent"] == runner.A7
    assert candidate["candidate_changed_paths"] == list(runner.C8_ALLOWED_PATHS)
    assert len(candidate["candidate_diff"]) == 12
    assert len(candidate["dependency_blobs"]) == len(runner.DEPENDENCY_PATHS)


def test_exact_phase7_handoff_authenticates_committed_chain_and_scope() -> None:
    authority = runner.authenticate_phase7_handoff()
    assert authority["candidate_C7"] == runner.C7
    assert authority["evidence_E7"] == runner.E7
    assert authority["checkpoint_A7"] == runner.A7
    assert authority["evidence_regular_blob_count"] == 47
    assert authority["evidence_indexed_blob_count"] == 46


def test_candidate_rejects_wrong_or_multiple_parent(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def wrong_parent(*arguments: str, check: bool = True) -> str:
        if arguments[:3] == ("rev-list", "--parents", "-n"):
            return f"{'c' * 40} {'b' * 40} {'a' * 40}"
        return _fake_candidate_git(*arguments, check=check)

    monkeypatch.setattr(runner, "_git", wrong_parent)
    monkeypatch.setattr(runner, "authenticate_phase7_handoff", lambda: {})
    with pytest.raises(runner.EvidenceError, match="sole-parent"):
        runner.assert_contract_candidate("c" * 40)


def test_candidate_rejects_extra_path(monkeypatch: pytest.MonkeyPatch) -> None:
    def extra_path(*arguments: str, check: bool = True) -> str:
        output = _fake_candidate_git(*arguments, check=check)
        if arguments[:2] == ("diff-tree", "--no-commit-id"):
            output += "\nA\tjava-api-service/forbidden.java"
        return output

    monkeypatch.setattr(runner, "_git", extra_path)
    monkeypatch.setattr(runner, "authenticate_phase7_handoff", lambda: {})
    with pytest.raises(runner.EvidenceError, match="extra=.*forbidden"):
        runner.assert_contract_candidate("c" * 40)


def test_candidate_rejects_wrong_status(monkeypatch: pytest.MonkeyPatch) -> None:
    def wrong_status(*arguments: str, check: bool = True) -> str:
        output = _fake_candidate_git(*arguments, check=check)
        if arguments[:2] == ("diff-tree", "--no-commit-id"):
            output = output.replace(
                "M\tplans/temporal-langgraph-room-refactor.md",
                "A\tplans/temporal-langgraph-room-refactor.md",
            )
        return output

    monkeypatch.setattr(runner, "_git", wrong_status)
    monkeypatch.setattr(runner, "authenticate_phase7_handoff", lambda: {})
    with pytest.raises(runner.EvidenceError, match="status="):
        runner.assert_contract_candidate("c" * 40)


@pytest.mark.parametrize("mode_type", ["120000 blob", "040000 tree", "160000 commit"])
def test_candidate_rejects_nonregular_git_object(
    monkeypatch: pytest.MonkeyPatch, mode_type: str
) -> None:
    def nonregular(*arguments: str, check: bool = True) -> str:
        output = _fake_candidate_git(*arguments, check=check)
        if arguments[:1] == ("ls-tree",):
            path = arguments[-1]
            return f"{mode_type} {'a' * 40}\t{path}"
        return output

    monkeypatch.setattr(runner, "_git", nonregular)
    monkeypatch.setattr(runner, "authenticate_phase7_handoff", lambda: {})
    with pytest.raises(runner.EvidenceError, match="regular Git blob"):
        runner.assert_contract_candidate("c" * 40)


def test_execution_requires_exact_clean_detached_head(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    values = {
        ("rev-parse", "HEAD"): "c" * 40,
        ("symbolic-ref", "-q", "HEAD"): "refs/heads/not-detached",
        ("status", "--porcelain=v1", "--untracked-files=all"): "",
    }
    monkeypatch.setattr(runner, "_git", lambda *args, check=True: values.get(args, ""))
    with pytest.raises(runner.EvidenceError, match="detached"):
        runner.assert_clean_detached_candidate("c" * 40)


def test_execution_rejects_dirty_candidate(monkeypatch: pytest.MonkeyPatch) -> None:
    values = {
        ("rev-parse", "HEAD"): "c" * 40,
        ("symbolic-ref", "-q", "HEAD"): "",
        ("status", "--porcelain=v1", "--untracked-files=all"): "?? stray.txt",
    }
    monkeypatch.setattr(runner, "_git", lambda *args, check=True: values.get(args, ""))
    with pytest.raises(runner.EvidenceError, match="clean worktree"):
        runner.assert_clean_detached_candidate("c" * 40)


def test_run_directory_must_be_absolute_fresh_and_external(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setattr(runner, "APPROVED_RUN_ROOT", tmp_path)
    with pytest.raises(runner.EvidenceError, match="absolute"):
        runner.assert_fresh_external_run_directory(Path("relative-run"))
    existing = tmp_path / "phase8-entry-existing"
    existing.mkdir()
    with pytest.raises(runner.EvidenceError, match="fresh"):
        runner.assert_fresh_external_run_directory(existing)


def test_run_directory_rejects_worktree_descendant(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setattr(runner, "APPROVED_RUN_ROOT", tmp_path)
    with pytest.raises(runner.EvidenceError, match="approved external root"):
        runner.assert_fresh_external_run_directory(runner.ROOT / "phase8-entry-run")


def test_candidate_attempt_claim_is_atomic_and_durable(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setattr(runner, "APPROVED_RUN_ROOT", tmp_path)
    run_root = tmp_path / "phase8-entry-candidate-attempt"
    first = runner.claim_candidate_attempt(
        "c" * 40, run_root, "synthetic-phase8-entry-test"
    )
    assert first["attempt_number"] == 1
    assert Path(first["path"]).is_file()
    with pytest.raises(runner.EvidenceError, match="already has.*attempt"):
        runner.claim_candidate_attempt(
            "c" * 40,
            tmp_path / "phase8-entry-second-directory",
            "synthetic-phase8-entry-test",
        )


def test_junit_parser_accepts_realistic_pytest_outer_testsuites(tmp_path: Path) -> None:
    path = tmp_path / "pytest.xml"
    cases = "".join(
        f'<testcase classname="tests.test_phase8" name="test_{index}" time="0.01" />'
        for index in range(24)
    )
    path.write_text(
        '<testsuites name="pytest tests"><testsuite name="pytest" errors="0" '
        f'failures="0" skipped="0" tests="24" time="0.24">{cases}'
        "</testsuite></testsuites>\n",
        encoding="utf-8",
    )
    assert runner._parse_authenticated_junit(path, "pytest JUnit")["tests"] == 24


def test_junit_parser_rejects_forged_root_totals_without_cases(tmp_path: Path) -> None:
    path = tmp_path / "forged.xml"
    path.write_text(
        '<testsuites tests="24" failures="0" errors="0" skipped="0">'
        '<testsuite tests="24" failures="0" errors="0" skipped="0" />'
        "</testsuites>\n",
        encoding="utf-8",
    )
    with pytest.raises(runner.EvidenceError, match="drift|no testcases"):
        runner._parse_authenticated_junit(path, "forged JUnit")


def test_green_manifest_accepts_exact_bound_bundle(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    _patch_green_dependencies(monkeypatch, tmp_path)
    manifest_path, _ = _green_bundle(tmp_path)
    loaded = runner.load_green_manifest(manifest_path, expected_candidate="c" * 40)
    assert loaded["status"] == runner.GREEN_STATUS
    assert loaded["implementation_authorized"] is False


@pytest.mark.parametrize(
    ("mutation", "message"),
    [
        (lambda value: value.update(status="P8_0_PASS"), "not terminal green"),
        (lambda value: value.update(**{"MIG-008": "PASS"}), "MIG-008"),
        (
            lambda value: value.update(implementation_authorized=True),
            "authority ceiling",
        ),
        (lambda value: value.update(retry_count=1), "retry"),
        (
            lambda value: value["production_capabilities"].update(promotion=True),
            "production or implementation capability",
        ),
    ],
)
def test_green_manifest_rejects_authority_or_attempt_drift(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, mutation, message: str
) -> None:
    _patch_green_dependencies(monkeypatch, tmp_path)
    path, manifest = _green_bundle(tmp_path)
    mutation(manifest)
    _rewrite_manifest(path, manifest)
    with pytest.raises(runner.EvidenceError, match=message):
        runner.load_green_manifest(path)


def test_green_manifest_rejects_invalid_self_seal(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    _patch_green_dependencies(monkeypatch, tmp_path)
    path, manifest = _green_bundle(tmp_path)
    manifest["status"] = "tampered"
    path.unlink()
    runner._write_json(path, manifest)
    with pytest.raises(runner.EvidenceError, match="seal"):
        runner.load_green_manifest(path)


def test_green_manifest_rejects_bound_artifact_tamper(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    _patch_green_dependencies(monkeypatch, tmp_path)
    path, _ = _green_bundle(tmp_path)
    (path.parent / "p/00-stdout.log").write_text("tampered\n", encoding="utf-8")
    with pytest.raises(runner.EvidenceError, match="stdout SHA-256"):
        runner.load_green_manifest(path)


def test_green_manifest_rejects_environment_object_drift(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    _patch_green_dependencies(monkeypatch, tmp_path)
    path, manifest = _green_bundle(tmp_path)
    manifest["environment"] = {"different": True}
    _rewrite_manifest(path, manifest)
    with pytest.raises(runner.EvidenceError, match="environment object drifted"):
        runner.load_green_manifest(path)


def test_green_manifest_rejects_junit_count_drift(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    _patch_green_dependencies(monkeypatch, tmp_path)
    path, manifest = _green_bundle(tmp_path)
    manifest["commands"][0]["tests"] = runner.MINIMUM_TESTS + 1
    _rewrite_manifest(path, manifest)
    with pytest.raises(runner.EvidenceError, match="JUnit totals drifted"):
        runner.load_green_manifest(path)


@pytest.mark.parametrize("scope", ["manifest", "command", "environment"])
def test_green_manifest_rejects_extra_schema_fields(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, scope: str
) -> None:
    _patch_green_dependencies(monkeypatch, tmp_path)
    path, manifest = _green_bundle(tmp_path)
    if scope == "manifest":
        manifest["hidden_claim"] = "P8_0_PASS"
    elif scope == "command":
        manifest["commands"][0]["hidden_claim"] = "PASS"
    else:
        environment_path = path.parent / runner.ENVIRONMENT_NAME
        environment = manifest["environment"]
        environment["hidden_claim"] = "PASS"
        environment.pop("snapshot_sha256")
        environment.pop("environment_sha256")
        digest = runner._json_sha256(environment)
        environment["snapshot_sha256"] = digest
        environment["environment_sha256"] = digest
        environment_path.unlink()
        runner._write_json(environment_path, environment)
        manifest["environment_sha256"] = runner._sha256(environment_path)
    _rewrite_manifest(path, manifest)
    with pytest.raises(runner.EvidenceError, match="schema"):
        runner.load_green_manifest(path)


def test_green_manifest_rejects_release_candidate_or_date_drift(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    _patch_green_dependencies(monkeypatch, tmp_path)
    path, manifest = _green_bundle(tmp_path)
    manifest["release"] = "phase-8-entry-20260724-cccccccccccc"
    _rewrite_manifest(path, manifest)
    with pytest.raises(runner.EvidenceError, match="timestamp"):
        runner.load_green_manifest(path)


def test_green_manifest_revalidates_git_candidate_bindings(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    _patch_green_dependencies(monkeypatch, tmp_path)
    path, _ = _green_bundle(tmp_path)
    monkeypatch.setattr(
        runner,
        "assert_contract_candidate",
        lambda candidate: {
            "candidate_sha": candidate,
            "candidate_parent": runner.A7,
            "candidate_changed_paths": list(runner.C8_ALLOWED_PATHS),
            "candidate_diff": [{"path": "forged"}],
            "candidate_tree_sha": "d" * 40,
            "dependency_blobs": [],
            "phase7_authority": {},
        },
    )
    with pytest.raises(runner.EvidenceError, match="candidate_diff"):
        runner.load_green_manifest(path)


def test_green_manifest_rejects_hard_linked_artifact(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    _patch_green_dependencies(monkeypatch, tmp_path)
    path, _ = _green_bundle(tmp_path)
    stdout = path.parent / "p/00-stdout.log"
    alias = path.parent / "stdout-alias.log"
    try:
        alias.hardlink_to(stdout)
    except OSError as exception:
        pytest.skip(f"hard links unavailable: {exception}")
    with pytest.raises(runner.EvidenceError, match="hard-link"):
        runner.load_green_manifest(path)


def test_green_manifest_rejects_sensitive_output_even_when_resealed(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    _patch_green_dependencies(monkeypatch, tmp_path)
    path, manifest = _green_bundle(tmp_path)
    stdout = path.parent / "p/00-stdout.log"
    stdout.write_text("AWS_SECRET_ACCESS_KEY=not-a-real-secret\n", encoding="utf-8")
    manifest["commands"][0]["stdout_sha256"] = runner._sha256(stdout)
    _rewrite_manifest(path, manifest)
    with pytest.raises(runner.EvidenceError, match="forbidden secret"):
        runner.load_green_manifest(path)


def test_source_environment_is_scrubbed_and_sandboxed(tmp_path: Path) -> None:
    environment = runner._subprocess_environment(tmp_path / "sandbox")
    for key in (
        "AWS_ACCESS_KEY_ID",
        "AZURE_CLIENT_SECRET",
        "GOOGLE_APPLICATION_CREDENTIALS",
        "KUBECONFIG",
        "DATABASE_URL",
        "TEMPORAL_ADDRESS",
        "HTTP_PROXY",
        "PYTEST_ADDOPTS",
        "PYTEST_PLUGINS",
    ):
        assert key not in environment
    assert environment["PYTEST_DISABLE_PLUGIN_AUTOLOAD"] == "1"
    assert environment["HOME"].startswith(str(tmp_path.resolve()))
    assert environment["TEMP"].startswith(str(tmp_path.resolve()))


@pytest.mark.parametrize(
    "environment_id",
    ["production-phase8", "local-phase8-secret", "tenant-123", "", "../../escape"],
)
def test_environment_capture_rejects_nonsynthetic_or_sensitive_ids(
    monkeypatch: pytest.MonkeyPatch, environment_id: str
) -> None:
    monkeypatch.setattr(runner, "_git", lambda *args, **kwargs: "git version test")
    with pytest.raises(runner.EvidenceError, match="strict local/synthetic"):
        runner._capture_environment(
            environment_id,
            {
                "candidate_sha": "c" * 40,
                "candidate_tree_sha": "d" * 40,
                "dependency_blobs": [],
            },
            ["D:/miniconda/python.exe"],
        )


def test_cli_requires_execute_for_run_binding(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        runner, "entry_plan", lambda candidate: {"candidate": candidate}
    )
    assert runner.main(["--candidate-sha", "c" * 40]) == 0
    assert (
        runner.main(["--candidate-sha", "c" * 40, "--run-dir", "D:/outside/fresh"]) == 2
    )


def test_cli_execute_requires_run_dir_and_environment() -> None:
    assert runner.main(["--candidate-sha", "c" * 40, "--execute"]) == 2


def test_source_process_is_invoked_with_shell_false(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    observed: dict[str, object] = {}

    def fake_run(argv, **kwargs):
        observed["argv"] = argv
        observed.update(kwargs)
        return SimpleNamespace(returncode=0)

    monkeypatch.setattr(runner.subprocess, "run", fake_run)
    stdout = tmp_path / "stdout.log"
    stderr = tmp_path / "stderr.log"
    _, _, duration, exit_code = runner._run_source(
        ["D:/miniconda/python.exe", "-V"], stdout, stderr, tmp_path / "sandbox"
    )
    assert exit_code == 0 and duration >= 0
    assert observed["shell"] is False
    assert observed["check"] is False
    assert observed["cwd"] == runner.ROOT
