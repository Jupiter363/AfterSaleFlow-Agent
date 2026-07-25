from __future__ import annotations

import copy
import hashlib
import json
import os
import stat
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator

from scripts.phase8.candidate import evidence_schema
from scripts.phase8.candidate import run_checkpoint as runner


SHA_A = "a" * 40
SHA_B = "b" * 40
SHA_C = "c" * 40
DIGEST_A = "a" * 64
DIGEST_B = "b" * 64
DIGEST_C = "c" * 64
WHEN = "2026-07-25T12:00:00.000+00:00"


def _review() -> dict[str, object]:
    return {
        "producer_identity": "primary-integrator",
        "reviewers": [
            {"identity": "review-authority", "lane": "authority"},
            {"identity": "review-data", "lane": "data_migration"},
            {"identity": "review-security", "lane": "security_privacy"},
        ],
        "self_approved": False,
    }


def _candidate() -> dict[str, object]:
    paths = sorted(
        (
            *evidence_schema.PHASE8_CONFIGURATION_MANIFEST_PATHS,
            *evidence_schema.PHASE8_DEPLOYMENT_MANIFEST_PATHS,
        )
    )
    blobs = [_path_blob(path) for path in paths]
    return {
        "accepted_entry_sha": runner.ACCEPTED_A8,
        "commit_sha": SHA_A,
        "parent_sha": SHA_B,
        "path_blobs": blobs,
        "path_blobs_sha256": evidence_schema.canonical_sha256(blobs),
        "tree_sha": SHA_C,
    }


def _path_blob(path: str) -> dict[str, str]:
    return {
        "git_blob_sha": hashlib.sha1(path.encode("ascii")).hexdigest(),
        "mode": "100644",
        "path": path,
        "sha256": hashlib.sha256(path.encode("ascii")).hexdigest(),
        "status": "MODIFIED",
    }


def _bundle(paths: tuple[str, ...]) -> dict[str, object]:
    blobs = [
        {
            key: value
            for key, value in _path_blob(path).items()
            if key in {"git_blob_sha", "path", "sha256"}
        }
        for path in paths
    ]
    return {"blobs": blobs, "sha256": evidence_schema.canonical_sha256(blobs)}


def _local_evidence(*, passed: bool = True) -> dict[str, object]:
    configuration = _bundle(evidence_schema.PHASE8_CONFIGURATION_MANIFEST_PATHS)
    deployment = _bundle(evidence_schema.PHASE8_DEPLOYMENT_MANIFEST_PATHS)
    context = {
        "configuration": configuration,
        "context_id": "engineering-context-001",
        "context_sha256": DIGEST_C,
        "deployment_manifest": deployment,
        "environment_identity": "engineering-local-ci",
        "images": [
            {
                "digest": f"sha256:{DIGEST_A}",
                "name": "registry.invalid/after-sale-flow/java-api",
            },
            {
                "digest": f"sha256:{DIGEST_B}",
                "name": "registry.invalid/after-sale-flow/python-agent",
            },
        ],
    }
    commands: list[dict[str, object]] = []
    reports: list[dict[str, object]] = []
    command_ids = list(evidence_schema.FIXED_ENGINEERING_COMMAND_ORDER)
    executed = command_ids if passed else command_ids[:1]
    for order, command_id in enumerate(executed, start=1):
        contract = evidence_schema.FIXED_ENGINEERING_COMMANDS[command_id]
        status = "PASSED" if passed else "FAILED"
        exit_code = 0 if passed else 1
        report_path = f"reports/{order:03d}-{command_id}.json"
        report_sha = hashlib.sha256(command_id.encode("ascii")).hexdigest()
        commands.append(
            {
                "argv": list(contract["argv"]),
                "attempt_id": "attempt-001",
                "candidate_sha": SHA_A,
                "candidate_tree_sha": SHA_C,
                "configuration_sha256": configuration["sha256"],
                "context_id": "engineering-context-001",
                "cwd": contract["cwd"],
                "deployment_manifest_sha256": deployment["sha256"],
                "exit_code": exit_code,
                "finished_at": WHEN,
                "id": command_id,
                "order": order,
                "report_path": report_path,
                "report_sha256": report_sha,
                "shell": False,
                "started_at": WHEN,
                "status": status,
            }
        )
        reports.append(
            {
                "attempt_id": "attempt-001",
                "bytes": 100,
                "candidate_sha": SHA_A,
                "candidate_tree_sha": SHA_C,
                "command_id": command_id,
                "configuration_sha256": configuration["sha256"],
                "context_id": "engineering-context-001",
                "deployment_manifest_sha256": deployment["sha256"],
                "path": report_path,
                "sha256": report_sha,
            }
        )
    document = {
        "MIG-006": "PENDING_PROMOTION",
        "MIG-007": "PENDING_PROMOTION",
        "MIG-008": "PENDING_PROMOTION",
        "attempt_lineage": {
            "attempt_id": "attempt-001",
            "attempt_number": 1,
            "checkpoint_id": "phase8-engineering-checkpoint",
            "previous_attempt_id": None,
        },
        "authority_ceiling": evidence_schema.ENGINEERING_AUTHORITY_CEILING,
        "candidate": _candidate(),
        "command_order": command_ids,
        "commands": commands,
        "engineering_checkpoint": "PASS" if passed else "FAIL",
        "engineering_trust_boundary": {
            "cryptographic_production_attestation": False,
            "threat_model": "NON_HOSTILE_LOCAL_ENGINEERING_OPERATOR",
        },
        "evidence_kind": evidence_schema.ENGINEERING_LOCAL,
        "execution_sandbox": (
            {
                "attempt_id": "attempt-001",
                "backend_id": "fixed-sandbox-backend",
                "backend_kind": "AUTHENTICATED_FIXED_BACKEND",
                "candidate_sha": SHA_A,
                "candidate_tree_sha": SHA_C,
                "configuration_sha256": configuration["sha256"],
                "context_sha256": DIGEST_C,
                "deployment_manifest_sha256": deployment["sha256"],
                "environment_isolated": True,
                "exact_argv_sha256": evidence_schema.canonical_sha256(
                    [
                        {
                            "argv": command["argv"],
                            "cwd": command["cwd"],
                            "id": command["id"],
                            "shell": command["shell"],
                        }
                        for command in commands
                    ]
                ),
                "executor_identity": "sandbox-executor-identity",
                "filesystem_isolated": True,
                "independently_verified": True,
                "network_denied": True,
                "policy_sha256": DIGEST_C,
                "production_credentials_present": False,
                "receipt_authenticated": True,
                "receipt_id": "sandbox-receipt-001",
                "verified_at": WHEN,
            }
            if passed
            else {
                "authority": "TEST_LIFECYCLE_ONLY_NO_CHECKPOINT_PASS",
                "backend_kind": "FIXTURE_ONLY",
                "fixture_only": True,
                "independently_verified": False,
                "network_denial_verified": False,
                "receipt_authenticated": False,
                "receipt_id": "fixture-receipt-001",
            }
        ),
        "next_phase_permission": (
            "EXTERNAL_PRODUCTION_CHECKPOINT_ONLY" if passed else "BLOCKED"
        ),
        "phase": 8,
        "production_capabilities": {
            key: False for key in evidence_schema.PRODUCTION_CAPABILITY_KEYS
        },
        "production_checkpoint": "PENDING_EXTERNAL",
        "promotion_gate": "PENDING" if passed else "FAIL",
        "release_context": context,
        "reports": reports,
        "review": _review(),
        "schema_version": evidence_schema.SCHEMA_VERSION,
        "sensitive_data": {
            "contains_hidden_reasoning": False,
            "contains_pii": False,
            "contains_secrets": False,
        },
        "trust_root_verified": False,
    }
    return evidence_schema.seal_evidence(document)


def _context() -> dict[str, object]:
    return {
        "attempt_lineage": {
            "attempt_id": "attempt-001",
            "attempt_number": 1,
            "checkpoint_id": "phase8-engineering-checkpoint",
            "previous_attempt_id": None,
        },
        "context_id": "engineering-context-001",
        "environment_identity": "engineering-local-ci",
        "evidence_kind": "ENGINEERING_LOCAL",
        "review": _review(),
        "schema_version": runner.CONTEXT_SCHEMA_VERSION,
    }


def _reseal(document: dict[str, object]) -> dict[str, object]:
    return evidence_schema.seal_evidence(document)


def test_schema_is_strict_draft_2020_12_and_local_pass_is_ceiling_bound() -> None:
    schema = evidence_schema.load_schema()
    assert schema["$schema"] == "https://json-schema.org/draft/2020-12/schema"
    Draft202012Validator.check_schema(schema)

    evidence = evidence_schema.validate_evidence(_local_evidence())
    assert evidence["engineering_checkpoint"] == "PASS"
    assert evidence["production_checkpoint"] == "PENDING_EXTERNAL"
    assert evidence["MIG-006"] == "PENDING_PROMOTION"
    assert evidence["MIG-007"] == "PENDING_PROMOTION"
    assert evidence["MIG-008"] == "PENDING_PROMOTION"
    assert not any(evidence["production_capabilities"].values())
    assert evidence["self_seal"]["purpose"] == (
        "BYTE_INTEGRITY_AND_DRIFT_DETECTION_ONLY"
    )
    assert evidence["self_seal"]["proves_execution_authenticity"] is False


@pytest.mark.parametrize(
    ("path", "value"),
    [
        (("production_checkpoint",), "PASS"),
        (("MIG-006",), "PASS"),
        (("production_capabilities", "canary"), True),
        (("trust_root_verified",), True),
    ],
)
def test_engineering_local_cannot_claim_production_authority(
    path: tuple[str, ...], value: object
) -> None:
    evidence = _local_evidence()
    target: dict[str, object] = evidence
    for part in path[:-1]:
        target = target[part]  # type: ignore[assignment]
    target[path[-1]] = value
    with pytest.raises(evidence_schema.EvidenceValidationError):
        evidence_schema.validate_evidence(_reseal(evidence))


def test_fixed_argv_mixed_context_and_report_substitution_are_rejected() -> None:
    evidence = _local_evidence()
    evidence["commands"][0]["argv"].append("--arbitrary")  # type: ignore[index,union-attr]
    with pytest.raises(evidence_schema.EvidenceValidationError, match="arbitrary|drifted"):
        evidence_schema.validate_evidence(_reseal(evidence))

    evidence = _local_evidence()
    evidence["reports"][0]["attempt_id"] = "attempt-999"  # type: ignore[index,union-attr]
    with pytest.raises(evidence_schema.EvidenceValidationError, match="mixed"):
        evidence_schema.validate_evidence(_reseal(evidence))

    evidence = _local_evidence()
    evidence["reports"][0]["sha256"] = DIGEST_C  # type: ignore[index,union-attr]
    with pytest.raises(evidence_schema.EvidenceValidationError, match="substitution"):
        evidence_schema.validate_evidence(_reseal(evidence))


def test_failure_is_a_stopped_ordered_prefix_and_never_external_pass() -> None:
    evidence = evidence_schema.validate_evidence(_local_evidence(passed=False))
    assert len(evidence["commands"]) == 1
    assert evidence["commands"][0]["status"] == "FAILED"
    assert evidence["engineering_checkpoint"] == "FAIL"
    assert evidence["next_phase_permission"] == "BLOCKED"
    assert evidence["production_checkpoint"] == "PENDING_EXTERNAL"

    ignored_failure = _local_evidence()
    ignored_failure["commands"][0]["status"] = "FAILED"  # type: ignore[index,union-attr]
    ignored_failure["commands"][0]["exit_code"] = 1  # type: ignore[index,union-attr]
    with pytest.raises(evidence_schema.EvidenceValidationError):
        evidence_schema.validate_evidence(_reseal(ignored_failure))


def test_self_signoff_and_canonical_inventory_drift_are_rejected() -> None:
    evidence = _local_evidence()
    evidence["review"]["reviewers"][0]["identity"] = "primary-integrator"  # type: ignore[index,union-attr]
    with pytest.raises(evidence_schema.EvidenceValidationError, match="own evidence"):
        evidence_schema.validate_evidence(_reseal(evidence))

    evidence = _local_evidence()
    evidence["candidate"]["path_blobs"].reverse()  # type: ignore[union-attr]
    evidence["candidate"]["path_blobs_sha256"] = evidence_schema.canonical_sha256(  # type: ignore[index]
        evidence["candidate"]["path_blobs"]  # type: ignore[index]
    )
    with pytest.raises(evidence_schema.EvidenceValidationError, match="canonical"):
        evidence_schema.validate_evidence(_reseal(evidence))


def test_external_signed_is_only_an_unverified_shape_and_cannot_pass() -> None:
    external = _local_evidence()
    del external["engineering_trust_boundary"]
    del external["execution_sandbox"]
    external["evidence_kind"] = "EXTERNAL_SIGNED"
    external["authority_ceiling"] = "EXTERNAL_EVIDENCE_SHAPE_ONLY_UNVERIFIED"
    external["next_phase_permission"] = "BLOCKED"
    external["external_signature_envelope"] = {
        "claimed_result": "PASS",
        "signed_payload_sha256": DIGEST_A,
        "signatures": [
            {
                "algorithm": "Ed25519",
                "key_id": f"key-{role.lower()}",
                "role": role,
                "signature": "A" * 64,
                "signer_identity": f"signer-{role.lower()}",
            }
            for role in ("ARCHITECTURE", "JAVA", "PYTHON", "SRE", "SECURITY", "BUSINESS")
        ],
        "trust_roots": [{"fingerprint_sha256": DIGEST_C, "key_id": "root-key"}],
        "verification_status": "UNVERIFIED_REQUIRES_P8_I5_3_TRUST_ROOT_VALIDATION",
    }
    external = _reseal(external)
    validated = evidence_schema.validate_evidence(external)
    assert validated["external_signature_envelope"]["claimed_result"] == "PASS"
    assert validated["production_checkpoint"] == "PENDING_EXTERNAL"
    assert validated["trust_root_verified"] is False

    external["production_checkpoint"] = "PASS"
    with pytest.raises(evidence_schema.EvidenceValidationError):
        evidence_schema.validate_evidence(_reseal(external))


def test_schema_rejects_unknown_fields_and_duplicate_json_properties() -> None:
    evidence = _local_evidence()
    evidence["secret"] = "not-allowed"
    with pytest.raises(evidence_schema.EvidenceValidationError):
        evidence_schema.validate_evidence(_reseal(evidence))
    with pytest.raises(evidence_schema.EvidenceValidationError, match="duplicate"):
        evidence_schema.parse_json_bytes(b'{"phase":8,"phase":7}')


def test_context_rejects_secret_reasoning_self_signoff_and_bad_lineage(
    tmp_path: Path,
) -> None:
    context = _context()
    context["hidden_reasoning"] = "must never be retained"
    path = tmp_path / "context.json"
    path.write_text(json.dumps(context), encoding="utf-8")
    with pytest.raises(runner.CandidateCheckpointError):
        runner.load_context(path.resolve())

    context = _context()
    context["review"]["reviewers"][0]["identity"] = "primary-integrator"  # type: ignore[index,union-attr]
    path.unlink()
    path.write_text(json.dumps(context), encoding="utf-8")
    with pytest.raises(runner.CandidateCheckpointError, match="self-review"):
        runner.load_context(path.resolve())

    context = _context()
    context["attempt_lineage"]["attempt_number"] = 2  # type: ignore[index]
    path.unlink()
    path.write_text(json.dumps(context), encoding="utf-8")
    with pytest.raises(runner.CandidateCheckpointError, match="predecessor"):
        runner.load_context(path.resolve())


def test_context_rejects_hardlinks_and_paths_reject_escape_ads() -> None:
    with pytest.raises(runner.CandidateCheckpointError):
        runner._relative_git_path("../escape", "test")
    with pytest.raises(runner.CandidateCheckpointError):
        runner._relative_git_path("report.json:stream", "test")


def test_context_rejects_hardlink_alias(tmp_path: Path) -> None:
    source = tmp_path / "context.json"
    alias = tmp_path / "context-alias.json"
    source.write_text(json.dumps(_context()), encoding="utf-8")
    try:
        os.link(source, alias)
    except OSError:
        pytest.skip("hard links are unavailable on this filesystem")
    with pytest.raises(runner.CandidateCheckpointError, match="single-link"):
        runner.load_context(alias.resolve())


def test_sensitive_command_output_is_not_retained() -> None:
    context = runner._validate_context(_context(), DIGEST_C)
    report = runner._command_report(
        command_id="phase8_wave_a_static",
        contract=evidence_schema.FIXED_ENGINEERING_COMMANDS["phase8_wave_a_static"],
        candidate=_candidate(),
        context=context,
        release_context=_local_evidence()["release_context"],
        result=runner.ProcessResult(1, b"password=do-not-retain", b""),
        started_at=WHEN,
        finished_at=WHEN,
    )
    assert report["status"] == "FAILED"
    assert report["sensitive_output_rejected"] is True
    assert report["output_retained"] is False
    assert report["stdout_sha256"] != hashlib.sha256(b"password=do-not-retain").hexdigest()


def test_plan_has_no_execution_or_production_capability(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(runner, "_capture_candidate", lambda _candidate, _paths: _candidate_fixture())
    plan = runner.candidate_plan(SHA_A)
    assert plan["mode"] == "PLAN_ONLY"
    assert plan["engineering_checkpoint"] == "NOT_RUN"
    assert plan["production_checkpoint"] == "PENDING_EXTERNAL"
    assert not any(plan["production_capabilities"].values())
    assert all(command["shell"] is False for command in plan["commands"])
    assert "command" not in plan["execution_requires"]


def _candidate_fixture() -> dict[str, object]:
    return _candidate()


def test_cli_defaults_to_plan_and_execution_requires_run_and_context(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.setattr(runner, "candidate_plan", lambda _candidate: {"mode": "PLAN_ONLY"})
    assert runner.main(["--candidate-commit", SHA_A]) == 0
    assert json.loads(capsys.readouterr().out)["mode"] == "PLAN_ONLY"

    assert runner.main(["--candidate-commit", SHA_A, "--execute"]) == 2
    assert "requires explicit --run-dir and --context-file" in capsys.readouterr().err


def test_subprocess_environment_does_not_copy_unrelated_or_secret_values(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("PHASE8_TOP_SECRET", "must-not-cross")
    monkeypatch.setenv("DATABASE_URL", "must-not-cross")
    environment = runner._command_environment()
    assert "PHASE8_TOP_SECRET" not in environment
    assert "DATABASE_URL" not in environment
    assert "NO_PROXY" not in environment
    assert environment["PYTEST_DISABLE_PLUGIN_AUTOLOAD"] == "1"
    assert environment["PYTHONNOUSERSITE"] == "1"
    assert environment["PYTHONDONTWRITEBYTECODE"] == "1"


@pytest.mark.parametrize("replacement", ["README.md", "contracts/fake.schema.json"])
def test_manifest_bundle_rejects_readme_or_schema_path_substitution(
    replacement: str,
) -> None:
    evidence = _local_evidence()
    bundle = evidence["release_context"]["configuration"]  # type: ignore[index]
    bundle["blobs"][0]["path"] = replacement  # type: ignore[index]
    bundle["sha256"] = evidence_schema.canonical_sha256(bundle["blobs"])  # type: ignore[index]
    with pytest.raises(evidence_schema.EvidenceValidationError, match="exact Phase 8 allowlist"):
        evidence_schema.validate_evidence(_reseal(evidence))


@pytest.mark.parametrize("operation", ["omit", "extra"])
def test_manifest_bundle_rejects_omitted_or_extra_manifest(operation: str) -> None:
    evidence = _local_evidence()
    bundle = evidence["release_context"]["deployment_manifest"]  # type: ignore[index]
    if operation == "omit":
        bundle["blobs"].pop()  # type: ignore[union-attr]
    else:
        bundle["blobs"].append(  # type: ignore[union-attr]
            {
                "git_blob_sha": SHA_A,
                "path": "README.md",
                "sha256": DIGEST_A,
            }
        )
    bundle["sha256"] = evidence_schema.canonical_sha256(bundle["blobs"])  # type: ignore[index]
    with pytest.raises(evidence_schema.EvidenceValidationError, match="exact Phase 8 allowlist"):
        evidence_schema.validate_evidence(_reseal(evidence))


def test_manifest_bundle_rejects_invented_blob_digest() -> None:
    evidence = _local_evidence()
    bundle = evidence["release_context"]["deployment_manifest"]  # type: ignore[index]
    bundle["blobs"][0]["sha256"] = DIGEST_C  # type: ignore[index]
    bundle["sha256"] = evidence_schema.canonical_sha256(bundle["blobs"])  # type: ignore[index]
    with pytest.raises(evidence_schema.EvidenceValidationError, match="substituted"):
        evidence_schema.validate_evidence(_reseal(evidence))


def test_context_cannot_choose_manifest_or_image_inventory() -> None:
    context = _context()
    context["configuration"] = {"path": "README.md", "sha256": DIGEST_A}
    with pytest.raises(runner.CandidateCheckpointError, match="fields drifted"):
        runner._validate_context(context, DIGEST_C)

    context = _context()
    context["images"] = [{"name": "invented", "digest": f"sha256:{DIGEST_A}"}]
    with pytest.raises(runner.CandidateCheckpointError, match="fields drifted"):
        runner._validate_context(context, DIGEST_C)


def test_image_inventory_is_derived_and_mixed_digest_is_rejected(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    first = evidence_schema.PHASE8_DEPLOYMENT_MANIFEST_PATHS[0]
    second = evidence_schema.PHASE8_DEPLOYMENT_MANIFEST_PATHS[1]
    image_name = "registry.invalid/after-sale-flow/java-api"

    def payload(_candidate: object, path: str) -> bytes:
        if path == first:
            return f"image: {image_name}@sha256:{DIGEST_A}\n".encode("ascii")
        return b"kind: ConfigMap\n"

    monkeypatch.setattr(runner, "_blob_payload", payload)
    images = runner._derive_images(
        _candidate(), evidence_schema.PHASE8_DEPLOYMENT_MANIFEST_PATHS
    )
    assert images == [{"digest": f"sha256:{DIGEST_A}", "name": image_name}]

    def mixed_payload(_candidate: object, path: str) -> bytes:
        if path == first:
            return f"image: {image_name}@sha256:{DIGEST_A}\n".encode("ascii")
        if path == second:
            return f"image: {image_name}@sha256:{DIGEST_B}\n".encode("ascii")
        return b"kind: ConfigMap\n"

    monkeypatch.setattr(runner, "_blob_payload", mixed_payload)
    with pytest.raises(runner.CandidateCheckpointError, match="mixed digests"):
        runner._derive_images(
            _candidate(), evidence_schema.PHASE8_DEPLOYMENT_MANIFEST_PATHS
        )


def _install_execution_fakes(
    monkeypatch: pytest.MonkeyPatch, approved: Path
) -> None:
    approved.mkdir()
    sentinel_payload = b"exact candidate blob\n"
    inventory = {"sentinel.txt": {"git_blob_sha": SHA_C, "mode": "100644"}}
    images = _local_evidence()["release_context"]["images"]  # type: ignore[index]
    monkeypatch.setattr(runner, "_approved_evidence_root", lambda: approved)
    monkeypatch.setattr(runner, "_git_protected_roots", lambda: ())
    monkeypatch.setattr(runner, "_assert_external_to_git", lambda *_args: None)
    monkeypatch.setattr(runner, "_assert_candidate_object", lambda candidate: candidate)
    monkeypatch.setattr(runner, "_assert_clean_detached_candidate", lambda _candidate: None)
    monkeypatch.setattr(
        runner, "_capture_candidate", lambda _candidate, _paths: copy.deepcopy(_candidate_fixture())
    )
    monkeypatch.setattr(runner, "_tree_inventory", lambda _candidate: copy.deepcopy(inventory))
    monkeypatch.setattr(
        runner,
        "_git_bytes",
        lambda *args: sentinel_payload
        if args == ("cat-file", "blob", SHA_C)
        else (_ for _ in ()).throw(AssertionError(args)),
    )
    monkeypatch.setattr(runner, "_derive_images", lambda *_args: copy.deepcopy(images))


def _context_file(tmp_path: Path) -> Path:
    path = tmp_path / "candidate-context.json"
    path.write_text(json.dumps(_context()), encoding="utf-8")
    return path.resolve()


def test_fixture_pass_exercises_lifecycle_but_cannot_emit_engineering_pass(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    approved = tmp_path / "approved"
    _install_execution_fakes(monkeypatch, approved)
    fixture = runner.FixtureExecution(
        "fixture-receipt-001",
        (runner.ProcessResult(0, b"", b""), runner.ProcessResult(0, b"", b"")),
    )
    run_dir = approved / "phase8-candidate-attempt-001"
    with pytest.raises(runner.CandidateCheckpointError, match="SANDBOX_UNAVAILABLE"):
        runner.process_fixture_lifecycle(
            candidate_commit=SHA_A,
            run_dir=run_dir,
            context_file=_context_file(tmp_path),
            fixture=fixture,
        )
    assert not (run_dir / "candidate-tree").exists()
    assert not (run_dir / runner.EVIDENCE_NAME).exists()
    assert all(
        "-p" in evidence_schema.FIXED_ENGINEERING_COMMANDS[command_id]["argv"]
        for command_id in evidence_schema.FIXED_ENGINEERING_COMMAND_ORDER
    )
    assert all(
        "no:cacheprovider" in evidence_schema.FIXED_ENGINEERING_COMMANDS[command_id]["argv"]
        for command_id in evidence_schema.FIXED_ENGINEERING_COMMAND_ORDER
    )


def test_fixture_failure_stops_first_and_emits_only_engineering_fail(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    approved = tmp_path / "approved"
    _install_execution_fakes(monkeypatch, approved)
    fixture = runner.FixtureExecution(
        "fixture-receipt-001", (runner.ProcessResult(1, b"failed", b""),)
    )
    run_dir = approved / "phase8-candidate-attempt-001"
    evidence = runner.process_fixture_lifecycle(
        candidate_commit=SHA_A,
        run_dir=run_dir,
        context_file=_context_file(tmp_path),
        fixture=fixture,
    )
    assert len(evidence["commands"]) == 1
    assert evidence["engineering_checkpoint"] == "FAIL"
    assert evidence["execution_sandbox"]["backend_kind"] == "FIXTURE_ONLY"
    assert evidence["execution_sandbox"]["independently_verified"] is False
    assert evidence["production_checkpoint"] == "PENDING_EXTERNAL"
    assert not (run_dir / "candidate-tree").exists()


def test_ignored_file_or_context_toctou_fails_closed(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    payload = b"candidate\n"
    inventory = {"sentinel.txt": {"git_blob_sha": SHA_C, "mode": "100644"}}
    monkeypatch.setattr(runner, "_tree_inventory", lambda _candidate: copy.deepcopy(inventory))
    monkeypatch.setattr(runner, "_assert_candidate_object", lambda candidate: candidate)
    monkeypatch.setattr(runner, "_git_bytes", lambda *_args: payload)
    destination = tmp_path / "candidate-tree"
    observed = runner._materialize_candidate_tree(SHA_A, destination)
    (destination / ".pytest_cache").mkdir()
    with pytest.raises(runner.CandidateCheckpointError, match="ignored, untracked"):
        runner._verify_materialized_tree(SHA_A, destination, observed)

    context_path = _context_file(tmp_path)
    snapshot = runner._read_authenticated_file(context_path, "engineering context")
    context_path.write_text(json.dumps({**_context(), "context_id": "changed-context"}))
    with pytest.raises(runner.CandidateCheckpointError, match="changed after authentication"):
        runner._assert_snapshot_unchanged(snapshot, "engineering context")


def test_actual_execute_and_forged_executor_fail_before_any_invocation(tmp_path: Path) -> None:
    class ForgedExecutor:
        invoked = False

        def execute(self) -> None:
            self.invoked = True

    forged = ForgedExecutor()
    with pytest.raises(runner.CandidateCheckpointError, match="SANDBOX_UNAVAILABLE"):
        runner.execute_checkpoint(
            candidate_commit=SHA_A,
            run_dir=tmp_path / "never-created",
            context_file=tmp_path / "never-read.json",
            executor=forged,
        )
    assert forged.invoked is False
    assert not (tmp_path / "never-created").exists()


def test_report_replacement_is_rejected_after_failed_fixture_run(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    approved = tmp_path / "approved"
    _install_execution_fakes(monkeypatch, approved)
    run_dir = approved / "phase8-candidate-attempt-001"
    runner.process_fixture_lifecycle(
        candidate_commit=SHA_A,
        run_dir=run_dir,
        context_file=_context_file(tmp_path),
        fixture=runner.FixtureExecution(
            "fixture-receipt-001", (runner.ProcessResult(1, b"failed", b""),)
        ),
    )
    report = next((run_dir / "reports").glob("*.json"))
    report.write_text("{}\n", encoding="utf-8")
    with pytest.raises(runner.CandidateCheckpointError, match="substituted report"):
        runner.validate_run_evidence(run_dir / runner.EVIDENCE_NAME)


def test_materialized_tree_rejects_concurrent_symlink_substitution(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    payload = b"candidate\n"
    inventory = {"sentinel.txt": {"git_blob_sha": SHA_C, "mode": "100644"}}
    monkeypatch.setattr(runner, "_tree_inventory", lambda _candidate: copy.deepcopy(inventory))
    monkeypatch.setattr(runner, "_assert_candidate_object", lambda candidate: candidate)
    monkeypatch.setattr(runner, "_git_bytes", lambda *_args: payload)
    destination = tmp_path / "candidate-tree"
    observed = runner._materialize_candidate_tree(SHA_A, destination)
    target = destination / "sentinel.txt"
    target.chmod(stat.S_IWRITE | stat.S_IREAD)
    target.unlink()
    try:
        target.symlink_to(tmp_path / "outside")
    except OSError:
        pytest.skip("symlink creation is unavailable")
    with pytest.raises(runner.CandidateCheckpointError, match="symlink"):
        runner._verify_materialized_tree(SHA_A, destination, observed)


def test_run_directory_is_confined_to_approved_root_and_rejects_aliases(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    approved = tmp_path / "approved"
    approved.mkdir()
    main = tmp_path / "main-worktree"
    common_git = main / ".git"
    other = tmp_path / "other-worktree"
    for path in (main, common_git, other):
        path.mkdir(exist_ok=True)
    monkeypatch.setattr(runner, "_approved_evidence_root", lambda: approved.resolve())
    monkeypatch.setattr(
        runner,
        "_git_protected_roots",
        lambda: (main.resolve(), common_git.resolve(), other.resolve()),
    )
    for parent in (main, common_git, other, tmp_path):
        with pytest.raises(runner.CandidateCheckpointError, match="approved local evidence root"):
            runner._prepare_run_directory(
                parent.resolve() / "phase8-candidate-attempt-001", "attempt-001"
            )

    target = tmp_path / "target"
    target.mkdir()
    alias = approved / "phase8-candidate-attempt-001"
    try:
        alias.symlink_to(target, target_is_directory=True)
    except OSError:
        pytest.skip("directory symlink creation is unavailable")
    with pytest.raises(runner.CandidateCheckpointError, match="fresh"):
        runner._prepare_run_directory(alias, "attempt-001")


@pytest.mark.parametrize(
    "raw",
    [
        r"\\server\share\context.json",
        r"\\?\C:\evidence\context.json",
        r"\\.\GLOBALROOT\Device\HarddiskVolume1\context.json",
    ],
)
def test_unc_extended_and_device_paths_are_rejected_before_io(raw: str) -> None:
    with pytest.raises(runner.CandidateCheckpointError, match="UNC, device"):
        runner._assert_local_absolute_path(Path(raw), "context")
