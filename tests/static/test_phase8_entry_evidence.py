from __future__ import annotations

import copy
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any

import pytest
import yaml

from scripts import generate_phase8_entry_evidence as evidence
from scripts import run_phase8_entry_checkpoint as runner


CANDIDATE = "c" * 40
EVIDENCE = "e" * 40
RELEASE = "phase-8-entry-20260725-cccccccccccc"


def _reviewed_blobs() -> list[dict[str, str]]:
    return [
        {"path": path, "sha256": hashlib.sha256(path.encode()).hexdigest()}
        for path in runner.C8_ALLOWED_PATHS
    ]


def _p0_document(
    source_hashes: dict[str, str] | None = None,
) -> dict[str, Any]:
    artifact_hashes = source_hashes or {
        name: "4" * 64 for name in evidence.SOURCE_HASH_NAMES
    }
    reviewed_blobs = _reviewed_blobs()
    lanes = []
    for index, lane in enumerate(evidence.P0_LANES, start=1):
        reviewer_id = f"reviewer-{index}"
        receipt = {
            "candidate_commit": CANDIDATE,
            "candidate_diff_sha256": hashlib.sha256(
                evidence._canonical_json_bytes([])
            ).hexdigest(),
            "candidate_tree_sha": "1" * 40,
            "closed_finding_ids": list(evidence.P0_LANE_TOPICS[lane]),
            "disposition": "ALL_P0_CLOSED",
            "lane": lane,
            "open_p0_count": 0,
            "reviewed_path_blobs_sha256": hashlib.sha256(
                evidence._canonical_json_bytes(reviewed_blobs)
            ).hexdigest(),
            "reviewed_topics": list(evidence.P0_LANE_TOPICS[lane]),
            "reviewer_id": reviewer_id,
            "schema_version": "phase8-entry-p0-lane-review-receipt.v1",
            "self_approved": False,
            "source_artifact_set_sha256": hashlib.sha256(
                evidence._canonical_json_bytes(artifact_hashes)
            ).hexdigest(),
        }
        lanes.append(
            {
                "disposition": "ALL_P0_CLOSED",
                "lane": lane,
                "open_p0_count": 0,
                "receipt": receipt,
                "review_receipt_sha256": hashlib.sha256(
                    evidence._canonical_json_bytes(receipt)
                ).hexdigest(),
                "reviewer_id": reviewer_id,
                "self_approved": False,
            }
        )
    return {
        "candidate_changed_paths": list(runner.C8_ALLOWED_PATHS),
        "candidate_commit": CANDIDATE,
        "candidate_diff": [],
        "candidate_tree_sha": "1" * 40,
        "closed_finding_ids": list(evidence.P0_TOPICS),
        "cryptographic_production_attestation": False,
        "disposition_author_id": "primary-integrator",
        "independent_disposition": True,
        "open_p0_count": 0,
        "production_reuse": "FORBIDDEN",
        "review_lanes": lanes,
        "review_scope": "CONSOLIDATED_POST_INTEGRATION_P0_ONLY",
        "reviewed_path_blobs": reviewed_blobs,
        "reviewed_topics": list(evidence.P0_TOPICS),
        "schema_version": evidence.P0_REVIEW_SCHEMA,
        "self_approved": False,
        "source_artifact_sha256": artifact_hashes,
        "status": "ALL_P0_CLOSED",
        "trust_ceiling": "ENGINEERING_PROCESS_ATTESTATION_NON_HOSTILE_LOCAL_OPERATOR",
    }


def _environment(argv: list[str]) -> dict[str, Any]:
    value: dict[str, Any] = {
        "architecture": "fixture",
        "candidate_sha": CANDIDATE,
        "candidate_tree_sha": "1" * 40,
        "captured_at": "2026-07-25T00:00:00.000+00:00",
        "command_argv_sha256": runner._json_sha256(argv),
        "dependency_git_blobs": [],
        "environment_id": "synthetic-fixture",
        "git_version": "git version fixture",
        "os": "fixture",
        "os_release": "fixture",
        "python_executable": argv[0],
        "python_implementation": "CPython",
        "python_version": "3.13.0",
        "pytest_plugin_autoload_disabled": True,
        "schema_version": evidence.ENVIRONMENT_SCHEMA,
        "source_git_blobs": [],
        "subprocess_environment_keys": sorted(runner._subprocess_environment()),
        "timezone": "UTC",
    }
    digest = runner._json_sha256(value)
    value["snapshot_sha256"] = digest
    value["environment_sha256"] = digest
    return value


def _junit(tests: int = 24) -> bytes:
    cases = "".join(
        f'<testcase classname="phase8.fixture" name="test_{index}" time="0.001" />'
        for index in range(tests)
    )
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f'<testsuite name="phase8" tests="{tests}" failures="0" errors="0" skipped="0" '
        f'candidate_commit="{CANDIDATE}" source_command_id="{runner.SOURCE_ID}">'
        f"{cases}</testsuite>\n"
    ).encode()


def _manifest(
    payloads: dict[str, bytes],
    run_root: Path = Path("C:/fixture/.codex-run/phase8-fixture"),
) -> dict[str, Any]:
    raw_path = str((run_root / "p" / "02-junit.xml").absolute())
    argv = [
        argument.replace("{absolute_raw_report}", raw_path)
        for argument in runner.ARGV_TEMPLATE
    ]
    environment = _environment(argv)
    command = {
        "accepted": True,
        "argv": argv,
        "argv_sha256": runner._json_sha256(argv),
        "candidate_sha_after": CANDIDATE,
        "candidate_sha_before": CANDIDATE,
        "cwd": ".",
        "duration_ms": 1,
        "ended_at": "2026-07-25T00:00:01.000+00:00",
        "errors": 0,
        "exit_code": 0,
        "failure_classification": "NONE",
        "failures": 0,
        "id": runner.SOURCE_ID,
        "normalized_report_path": evidence.REPORT_NAME,
        "normalized_report_sha256": hashlib.sha256(
            payloads[evidence.REPORT_NAME]
        ).hexdigest(),
        "raw_report_path": evidence.RAW_JUNIT_NAME,
        "raw_report_sha256": hashlib.sha256(
            payloads[evidence.RAW_JUNIT_NAME]
        ).hexdigest(),
        "report_kind": "PYTEST_JUNIT",
        "resource_class": "light",
        "shell": False,
        "skipped": 0,
        "started_at": "2026-07-25T00:00:00.000+00:00",
        "stderr_path": evidence.STDERR_NAME,
        "stderr_sha256": hashlib.sha256(payloads[evidence.STDERR_NAME]).hexdigest(),
        "stdout_path": evidence.STDOUT_NAME,
        "stdout_sha256": hashlib.sha256(payloads[evidence.STDOUT_NAME]).hexdigest(),
        "tests": 24,
    }
    manifest: dict[str, Any] = {
        "MIG-006": "PENDING_PROMOTION",
        "MIG-007": "PENDING_PROMOTION",
        "MIG-008": "PENDING_PROMOTION",
        "accepted_phase_7_candidate_C7": runner.C7,
        "accepted_phase_7_checkpoint_A7": runner.A7,
        "accepted_phase_7_evidence_E7": runner.E7,
        "accepted_phase_7_authority": {"fixture": "accepted"},
        "attempt_ledger": {
            "attempt_number": 1,
            "candidate_sha": CANDIDATE,
            "path": str(
                (
                    run_root.parent / ".phase8-entry-attempts" / f"{CANDIDATE}.json"
                ).absolute()
            ),
            "run_dir": str(run_root.absolute()),
            "sha256": "5" * 64,
        },
        "candidate_changed_paths": list(runner.C8_ALLOWED_PATHS),
        "candidate_commit": CANDIDATE,
        "candidate_diff": [],
        "candidate_parent": runner.A7,
        "candidate_sha": CANDIDATE,
        "candidate_tree_sha": "1" * 40,
        "commands": [command],
        "contract_gate": "P8.0_NOT_RUN",
        "dependency_git_blobs": [],
        "environment": environment,
        "environment_file": evidence.ENVIRONMENT_NAME,
        "environment_sha256": hashlib.sha256(
            evidence._canonical_json_bytes(environment)
        ).hexdigest(),
        "git_tree_clean_after": True,
        "git_tree_clean_before": True,
        "implementation": "REMAINS_BLOCKED_UNTIL_A8",
        "implementation_authorized": False,
        "local_threat_model": "HOSTILE_LOCAL_ADMIN_OR_OPERATOR_OUT_OF_SCOPE",
        "p8_0_contract_gate": "REMAINS_NOT_RUN_UNTIL_A8",
        "phase": 8,
        "production_capabilities": {
            key: False for key in runner.PRODUCTION_CAPABILITY_KEYS
        },
        "production_attestation_requirement": "EXTERNALLY_ATTESTED_CI_OIDC_KMS_OR_EQUIVALENT_SIGNED_EXECUTION_RECEIPT",
        "quarantine_used": False,
        "release": RELEASE,
        "report_reuse_used": False,
        "resume_used": False,
        "retry_count": 0,
        "schema_version": runner.SCHEMA_VERSION,
        "self_seal_trust": "INTEGRITY_ONLY_NOT_PROVENANCE_OR_AUTHORIZATION",
        "status": runner.GREEN_STATUS,
        "verification_finished_at": "2026-07-25T00:00:01.000+00:00",
        "verification_started_at": "2026-07-25T00:00:00.000+00:00",
    }
    return manifest


def _scope() -> dict[str, Any]:
    return {
        "candidate_diff": [],
        "candidate_sha": CANDIDATE,
        "candidate_parent": runner.A7,
        "candidate_tree_sha": "1" * 40,
        "candidate_changed_paths": list(runner.C8_ALLOWED_PATHS),
        "dependency_blobs": [],
        "phase7_authority": {"fixture": "accepted"},
    }


def _write_fixture_run(tmp_path: Path) -> tuple[Path, dict[str, Any]]:
    run_root = tmp_path / ".codex-run" / "phase8-fixture"
    (run_root / "p").mkdir(parents=True)
    junit = _junit()
    payloads = {
        evidence.REPORT_NAME: junit,
        evidence.RAW_JUNIT_NAME: junit,
        evidence.STDOUT_NAME: b"24 passed\n",
        evidence.STDERR_NAME: b"",
    }
    manifest = _manifest(payloads, run_root)
    environment_payload = evidence._canonical_json_bytes(manifest["environment"])
    manifest["environment_sha256"] = hashlib.sha256(environment_payload).hexdigest()
    runner._seal_manifest(manifest)
    payloads[evidence.ENVIRONMENT_NAME] = environment_payload
    payloads[evidence.MANIFEST_NAME] = evidence._canonical_json_bytes(manifest)
    for relative, payload in payloads.items():
        path = run_root.joinpath(*relative.split("/"))
        path.write_bytes(payload)
    return run_root / evidence.MANIFEST_NAME, manifest


@pytest.fixture
def trusted_candidate(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(evidence, "_candidate_scope", lambda candidate: _scope())
    monkeypatch.setattr(
        evidence, "_reviewed_path_blobs", lambda candidate: _reviewed_blobs()
    )
    monkeypatch.setattr(
        evidence, "_assert_git_filter_stable", lambda *args, **kwargs: None
    )


def _assemble(tmp_path: Path, trusted_candidate: None) -> tuple[Path, dict[str, Any]]:
    manifest_path, manifest = _write_fixture_run(tmp_path)
    source_hashes = {
        name: hashlib.sha256(
            manifest_path.parent.joinpath(*name.split("/")).read_bytes()
        ).hexdigest()
        for name in evidence.SOURCE_HASH_NAMES
    }
    p0 = _p0_document(source_hashes)
    snapshot = evidence.P0Snapshot(
        candidate=CANDIDATE,
        path=tmp_path / "external-p0.json",
        forbidden_roots=(),
        payload=evidence._canonical_json_bytes(p0),
        identity=(1, 2, 3),
        version=(1, 1),
        document=p0,
    )
    output = tmp_path / "bundle"
    decision = evidence.assemble_entry_evidence(
        manifest=manifest,
        manifest_path=manifest_path,
        p0_snapshot=snapshot,
        output_dir=output,
        release_id=RELEASE,
        candidate_commit=CANDIDATE,
    )
    return output, decision


def test_exact_bundle_indexes_other_eleven_only(
    tmp_path: Path, trusted_candidate: None
) -> None:
    output, decision = _assemble(tmp_path, trusted_candidate)
    files = {
        path.relative_to(output).as_posix()
        for path in output.rglob("*")
        if path.is_file()
    }
    assert files == evidence.EXPECTED_NAMES
    index = json.loads((output / evidence.INDEX_NAME).read_text(encoding="utf-8"))
    assert [row["path"] for row in index["artifacts"]] == list(evidence.INDEXED_NAMES)
    assert evidence.INDEX_NAME not in {row["path"] for row in index["artifacts"]}
    assert decision["result"] == evidence.RESULT_CEILING
    assert decision["next_phase_permission"] == evidence.NEXT_PERMISSION
    assert decision["implementation_authorized"] is False
    assert not any(decision["runtime_restrictions"].values())


@pytest.mark.parametrize("gate", evidence.MIGRATION_GATES)
def test_decision_cannot_promote_migrations(
    tmp_path: Path, trusted_candidate: None, gate: str
) -> None:
    output, _ = _assemble(tmp_path, trusted_candidate)
    path = output / evidence.DECISION_NAME
    document = json.loads(path.read_text(encoding="utf-8"))
    document[gate] = "PASS"
    evidence._write_json(path, document)
    with pytest.raises(runner.EvidenceError, match="decision drifted"):
        evidence.validate_bundle(output, CANDIDATE, RELEASE)


def test_artifact_index_rejects_self_entry_duplicate_and_hash_drift(
    tmp_path: Path, trusted_candidate: None
) -> None:
    output, _ = _assemble(tmp_path, trusted_candidate)
    path = output / evidence.INDEX_NAME
    original = json.loads(path.read_text(encoding="utf-8"))
    for mutate in (
        lambda value: value["artifacts"].append(copy.deepcopy(value["artifacts"][0])),
        lambda value: value["artifacts"].append(
            {"bytes": 0, "path": evidence.INDEX_NAME, "sha256": "0" * 64}
        ),
        lambda value: value["artifacts"][0].update(sha256="0" * 64),
    ):
        document = copy.deepcopy(original)
        mutate(document)
        evidence._write_json(path, document)
        with pytest.raises(
            runner.EvidenceError, match="artifact index|indexed artifact"
        ):
            evidence.validate_bundle(output, CANDIDATE, RELEASE)
    evidence._write_json(path, original)


def test_bundle_rejects_extra_missing_and_symlink(
    tmp_path: Path, trusted_candidate: None
) -> None:
    output, _ = _assemble(tmp_path, trusted_candidate)
    extra = output / "extra.txt"
    extra.write_text("extra", encoding="utf-8")
    with pytest.raises(runner.EvidenceError, match="exact file set"):
        evidence.validate_bundle(output, CANDIDATE, RELEASE)
    extra.unlink()
    candidate = output / evidence.CANDIDATE_NAME
    payload = candidate.read_bytes()
    candidate.unlink()
    with pytest.raises(runner.EvidenceError, match="exact file set"):
        evidence.validate_bundle(output, CANDIDATE, RELEASE)
    candidate.write_bytes(payload)
    target = output / evidence.STDOUT_NAME
    target.unlink()
    try:
        target.symlink_to(output / evidence.STDERR_NAME)
    except OSError:
        pytest.skip("symlinks are not available")
    with pytest.raises(runner.EvidenceError, match="symlink|reparse"):
        evidence.validate_bundle(output, CANDIDATE, RELEASE)


def test_bundle_rejects_hardlinked_artifact(
    tmp_path: Path, trusted_candidate: None
) -> None:
    output, _ = _assemble(tmp_path, trusted_candidate)
    target = output / evidence.STDOUT_NAME
    payload = target.read_bytes()
    target.unlink()
    outside = tmp_path / "outside.log"
    outside.write_bytes(payload)
    try:
        os.link(outside, target)
    except OSError:
        pytest.skip("hard links are not available")
    with pytest.raises(runner.EvidenceError, match="exactly one filesystem link"):
        evidence.validate_bundle(output, CANDIDATE, RELEASE)


def test_p0_review_requires_three_distinct_independent_lanes(
    trusted_candidate: None,
) -> None:
    valid = _p0_document()
    evidence._validate_p0_document(valid, CANDIDATE)
    cases = []
    duplicate = copy.deepcopy(valid)
    duplicate["review_lanes"][1]["reviewer_id"] = duplicate["review_lanes"][0][
        "reviewer_id"
    ]
    cases.append(duplicate)
    self_review = copy.deepcopy(valid)
    self_review["review_lanes"][0]["reviewer_id"] = "self"
    cases.append(self_review)
    wrong_hash = copy.deepcopy(valid)
    wrong_hash["reviewed_path_blobs"][0]["sha256"] = "0" * 64
    cases.append(wrong_hash)
    open_p0 = copy.deepcopy(valid)
    open_p0["open_p0_count"] = 1
    cases.append(open_p0)
    missing_closure = copy.deepcopy(valid)
    missing_closure["closed_finding_ids"] = []
    cases.append(missing_closure)
    self_approved = copy.deepcopy(valid)
    self_approved["self_approved"] = True
    cases.append(self_approved)
    tampered_receipt = copy.deepcopy(valid)
    tampered_receipt["review_lanes"][0]["receipt"]["candidate_commit"] = "d" * 40
    cases.append(tampered_receipt)
    for document in cases:
        with pytest.raises(
            runner.EvidenceError, match="three independent lane receipts"
        ):
            evidence._validate_p0_document(document, CANDIDATE)


def test_external_p0_requires_absolute_canonical_single_link_file(
    tmp_path: Path, trusted_candidate: None
) -> None:
    path = tmp_path / "p0.json"
    path.write_bytes(evidence._canonical_json_bytes(_p0_document()))
    snapshot = evidence.snapshot_p0_review(
        path.resolve(), CANDIDATE, forbidden_roots=(evidence.ROOT,)
    )
    evidence._revalidate_p0_snapshot(snapshot)
    with pytest.raises(runner.EvidenceError, match="absolute"):
        evidence.snapshot_p0_review(Path(path.name), CANDIDATE, forbidden_roots=())
    path.write_text(json.dumps(_p0_document()), encoding="utf-8")
    with pytest.raises(runner.EvidenceError, match="canonical LF"):
        evidence.snapshot_p0_review(path.resolve(), CANDIDATE, forbidden_roots=())


def test_external_p0_snapshot_detects_post_read_mutation(
    tmp_path: Path, trusted_candidate: None
) -> None:
    path = tmp_path / "p0.json"
    path.write_bytes(evidence._canonical_json_bytes(_p0_document()))
    snapshot = evidence.snapshot_p0_review(
        path.resolve(), CANDIDATE, forbidden_roots=()
    )
    path.write_bytes(
        evidence._canonical_json_bytes(
            {**_p0_document(), "closed_finding_ids": ["P0-X"]}
        )
    )
    with pytest.raises(runner.EvidenceError, match="changed after snapshot"):
        evidence._revalidate_p0_snapshot(snapshot)


@pytest.mark.parametrize(
    "payload",
    [
        b"-----BEGIN PRIVATE KEY-----",
        b"Authorization: Bearer abcdefghijklmnopqrstuvwxyz123456",
        b'password="production-secret"',
        b"party@example.com",
        b"110105194912310020",
        b"<think>hidden chain</think>",
    ],
    ids=(
        "private-key",
        "bearer",
        "assigned-secret",
        "email",
        "chinese-id",
        "hidden-reasoning",
    ),
)
def test_privacy_scan_rejects_secrets_pii_and_hidden_reasoning(payload: bytes) -> None:
    with pytest.raises(runner.EvidenceError, match="secret/PII"):
        evidence._assert_privacy_safe(payload, "fixture")


def test_privacy_fixture_values_do_not_leak_into_real_pytest_junit(
    tmp_path: Path,
) -> None:
    report = tmp_path / "privacy-fixtures.xml"
    sandbox = tmp_path / "sandbox"
    (sandbox / "home").mkdir(parents=True)
    (sandbox / "tmp").mkdir()
    node = (
        f"{Path(__file__).resolve()}::"
        "test_privacy_scan_rejects_secrets_pii_and_hidden_reasoning"
    )
    process = subprocess.run(
        [
            sys.executable,
            "-m",
            "pytest",
            "-q",
            node,
            f"--junitxml={report}",
        ],
        cwd=evidence.ROOT,
        env=runner._subprocess_environment(sandbox),
        shell=False,
        check=False,
        capture_output=True,
    )
    assert process.returncode == 0, process.stderr.decode(errors="replace")
    payload = report.read_bytes()
    raw_fixtures = (
        b"-----BEGIN PRIVATE KEY-----",
        b"Authorization: Bearer abcdefghijklmnopqrstuvwxyz123456",
        b'password="production-secret"',
        b"party@example.com",
        b"110105194912310020",
        b"<think>hidden chain</think>",
    )
    assert not any(fixture in payload for fixture in raw_fixtures)
    assert not any(
        pattern.search(payload) for _, pattern in evidence._SENSITIVE_PATTERNS
    )


def test_chinese_id_detection_ignores_hex_digest_numeric_run() -> None:
    identity_digits = b"110105194912310020"
    with pytest.raises(runner.EvidenceError, match="Chinese identity number"):
        evidence._assert_privacy_safe(identity_digits, "standalone identity fixture")

    digest_context = b"a" * 23 + identity_digits + b"b" * 23
    assert len(digest_context) == 64
    evidence._assert_privacy_safe(digest_context, "hex digest fixture")


def test_raw_and_normalized_junit_must_match_manifest() -> None:
    command = {"tests": 24, "failures": 0, "errors": 0, "skipped": 0}
    with pytest.raises(runner.EvidenceError, match="totals disagree"):
        evidence._assert_raw_junit_matches(_junit(25), _junit(24), CANDIDATE, command)

    missing_binding = _junit().replace(
        f' source_command_id="{runner.SOURCE_ID}"'.encode(), b""
    )
    with pytest.raises(runner.EvidenceError, match="candidate binding"):
        evidence._assert_raw_junit_matches(
            _junit(), missing_binding, CANDIDATE, command
        )


def test_manifest_rejects_non_synthetic_environment_and_runtime_promotion(
    trusted_candidate: None,
) -> None:
    payloads = {
        evidence.REPORT_NAME: _junit(),
        evidence.RAW_JUNIT_NAME: _junit(),
        evidence.STDOUT_NAME: b"",
        evidence.STDERR_NAME: b"",
    }
    manifest = _manifest(payloads)
    runner._seal_manifest(manifest)
    manifest["environment"]["environment_id"] = "production"
    with pytest.raises(runner.EvidenceError, match="strict local/synthetic"):
        evidence._validate_manifest_claims(manifest, CANDIDATE)
    manifest["environment"]["environment_id"] = "local-fixture"
    manifest["MIG-008"] = "PASS"
    with pytest.raises(runner.EvidenceError, match="migration-gate"):
        evidence._validate_manifest_claims(manifest, CANDIDATE)

    privileged = _manifest(payloads)
    runner._seal_manifest(privileged)
    privileged["production_capabilities"]["production_traffic"] = True
    with pytest.raises(runner.EvidenceError, match="authority"):
        evidence._validate_manifest_claims(privileged, CANDIDATE)


def test_manifest_rejects_mixed_attempt_and_raw_report_path(
    trusted_candidate: None,
) -> None:
    payloads = {
        evidence.REPORT_NAME: _junit(),
        evidence.RAW_JUNIT_NAME: _junit(),
        evidence.STDOUT_NAME: b"",
        evidence.STDERR_NAME: b"",
    }
    manifest = _manifest(payloads)
    runner._seal_manifest(manifest)
    manifest["attempt_ledger"]["run_dir"] = "C:/other/.codex-run/other-attempt"
    with pytest.raises(runner.EvidenceError, match="attempt-ledger"):
        evidence._validate_manifest_claims(manifest, CANDIDATE)


def test_assemble_rejects_internally_consistent_manifest_from_another_run(
    tmp_path: Path, trusted_candidate: None
) -> None:
    manifest_path, manifest = _write_fixture_run(tmp_path)
    claimed_run = tmp_path / ".codex-run" / "different-run"
    argv = [
        argument.replace(
            "{absolute_raw_report}", str(claimed_run / "p" / "02-junit.xml")
        )
        for argument in runner.ARGV_TEMPLATE
    ]
    manifest["commands"][0]["argv"] = argv
    manifest["commands"][0]["argv_sha256"] = runner._json_sha256(argv)
    manifest["environment"]["command_argv_sha256"] = runner._json_sha256(argv)
    manifest["environment"]["python_executable"] = argv[0]
    environment_unsigned = dict(manifest["environment"])
    environment_unsigned.pop("snapshot_sha256")
    environment_unsigned.pop("environment_sha256")
    environment_seal = runner._json_sha256(environment_unsigned)
    manifest["environment"]["snapshot_sha256"] = environment_seal
    manifest["environment"]["environment_sha256"] = environment_seal
    manifest["attempt_ledger"] = {
        "attempt_number": 1,
        "candidate_sha": CANDIDATE,
        "path": str(
            claimed_run.parent / ".phase8-entry-attempts" / f"{CANDIDATE}.json"
        ),
        "run_dir": str(claimed_run),
        "sha256": "5" * 64,
    }
    runner._seal_manifest(manifest)
    snapshot = evidence.P0Snapshot(
        CANDIDATE,
        tmp_path / "p0.json",
        (),
        evidence._canonical_json_bytes(_p0_document()),
        (1, 2, 3),
        (1, 1),
        _p0_document(),
    )
    with pytest.raises(runner.EvidenceError, match="another run directory"):
        evidence.assemble_entry_evidence(
            manifest=manifest,
            manifest_path=manifest_path,
            p0_snapshot=snapshot,
            output_dir=tmp_path / "bundle",
            release_id=RELEASE,
            candidate_commit=CANDIDATE,
        )


def test_bundle_rejects_release_repackaging(
    tmp_path: Path, trusted_candidate: None
) -> None:
    output, _ = _assemble(tmp_path, trusted_candidate)
    with pytest.raises(runner.EvidenceError, match="release"):
        evidence.validate_bundle(
            output, CANDIDATE, "phase-8-entry-20260726-cccccccccccc"
        )


def test_p0_source_receipts_bind_exact_runner_artifacts(
    trusted_candidate: None,
) -> None:
    document = _p0_document()
    mismatched = dict(document["source_artifact_sha256"])
    mismatched[evidence.STDOUT_NAME] = "9" * 64
    with pytest.raises(runner.EvidenceError, match="source evidence"):
        evidence._validate_p0_document(document, CANDIDATE, source_hashes=mismatched)


def test_git_object_replacement_is_disabled_and_rejected(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    assert evidence._safe_process_environment()["GIT_NO_REPLACE_OBJECTS"] == "1"

    def fake_run(*args: Any, **kwargs: Any) -> Any:
        return evidence.subprocess.CompletedProcess(
            args[0], 0, stdout=b"refs/replace/deadbeef\n", stderr=b""
        )

    monkeypatch.setattr(evidence.subprocess, "run", fake_run)
    with pytest.raises(runner.EvidenceError, match="replace-object"):
        evidence._assert_no_git_object_substitution()


def test_cleanup_preserves_staging_when_identity_changed(tmp_path: Path) -> None:
    staging = tmp_path / "staging"
    staging.mkdir()
    (staging / "artifact.txt").write_text("preserve", encoding="utf-8")
    parent_identity = evidence._directory_identity(staging.parent.lstat())
    ancestry = evidence._ancestry_identities(staging, "fixture staging")
    actual_identity = evidence._directory_identity(staging.lstat())
    evidence._safe_remove_staging(
        staging,
        staging_identity=(999, *actual_identity[1:]),
        parent_identity=parent_identity,
        ancestry=ancestry,
    )
    assert (staging / "artifact.txt").is_file()
    evidence._safe_remove_staging(
        staging,
        staging_identity=actual_identity,
        parent_identity=parent_identity,
        ancestry=ancestry,
    )
    assert not staging.exists()


def test_yaml_freezes_local_engineering_trust_ceiling() -> None:
    matrix = yaml.safe_load(
        (
            evidence.ROOT / "plans/phase-8-production-hardening-test-batches.yaml"
        ).read_text(encoding="utf-8")
    )
    trust = matrix["gate"]["local_engineering_trust_boundary"]
    p0 = matrix["batches"]["batch_0_entry"]["evidence_schema"][
        "p0_review_disposition_contract"
    ]
    assert trust["operator_threat_model"] == "NON_HOSTILE_LOCAL_ENGINEERING_OPERATOR"
    assert trust["cryptographic_execution_attestation_present"] is False
    assert (
        trust["production_cryptographic_execution_and_operator_attestation"]
        == "REQUIRED_EXTERNAL"
    )
    assert p0 == {
        "attestation_scope": "ENGINEERING_PROCESS_ATTESTATION_NON_HOSTILE_LOCAL_OPERATOR",
        "cryptographic_production_attestation": False,
        "exact_closed_topic_count": 13,
        "fixed_lanes": list(evidence.P0_LANES),
        "per_lane_exact_candidate_tree_diff_source_hash_binding_required": True,
        "per_lane_exact_topic_closure_required": True,
        "production_reuse": "forbidden",
        "self_approved": False,
    }


def test_verify_commit_requires_sole_parent_before_reading_bundle(
    monkeypatch: pytest.MonkeyPatch, trusted_candidate: None
) -> None:
    monkeypatch.setattr(
        evidence,
        "_git_text",
        lambda *args: f"{EVIDENCE} {'d' * 40}\n" if args[0] == "rev-list" else "",
    )
    with pytest.raises(runner.EvidenceError, match="sole parent"):
        evidence.verify_evidence_commit(
            evidence_commit=EVIDENCE,
            candidate_commit=CANDIDATE,
            release_id=RELEASE,
        )


def test_generation_rejects_noncanonical_output_prefix(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, trusted_candidate: None
) -> None:
    manifest_path, _ = _write_fixture_run(tmp_path)
    p0 = tmp_path / "p0.json"
    p0.write_bytes(evidence._canonical_json_bytes(_p0_document()))
    with pytest.raises(runner.EvidenceError, match="exact candidate evidence prefix"):
        evidence.generate_entry_evidence(
            release_id=RELEASE,
            candidate_commit=CANDIDATE,
            execution_manifest_path=manifest_path,
            p0_review_disposition_path=p0,
            output_dir=tmp_path / "arbitrary",
        )


def test_isolated_git_filter_preserves_exact_bytes() -> None:
    logical_root = Path("test-reports/temporal-first/phase8/phase-8-entry")
    pure_root = evidence.PurePosixPath(logical_root.as_posix())
    with evidence._git_filter_repository(pure_root) as (repository, environment):
        evidence._assert_git_filter_stable(
            b"exact\r\nbytes\n",
            (pure_root / evidence.STDOUT_NAME).as_posix(),
            (pure_root / evidence.ATTRIBUTES_NAME).as_posix(),
            repository,
            environment,
            require_lf=False,
        )
