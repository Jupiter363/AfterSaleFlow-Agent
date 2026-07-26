from __future__ import annotations

import hashlib
import json
import subprocess
from pathlib import Path
from typing import Any

from scripts.phase8.candidate import candidate_scope, command_contract, p0_disposition


ROOT = Path(__file__).resolve().parents[2]
CANDIDATE = "9233f9b489dff7d9624f2b0f21369d349f104cca"
EVIDENCE_COMMIT = "df97e398f03552bd6689b77925f7af6386fa7e16"
TRUSTED_CODE = "10e69724038a5bea9cdd99f8fc2be5485860d7c9"
TRUSTED_WORKFLOW = "b36485303c46e07213e732a55151faa8cfbead1e"
CANDIDATE_TREE = "f0e0f614331a159b88cee4d56966124c4db8aa79"
RELEASE_ID = "phase-8-20260727-9233f9b4"
EVIDENCE_PREFIX = (
    f"test-reports/temporal-first/{RELEASE_ID}/phase-8-engineering"
)
CHECKPOINT_PATH = "docs/runbooks/temporal-first/phase-8-engineering-checkpoint.md"
TEST_PATH = "tests/static/test_phase8_engineering_checkpoint.py"
INDEX_NAME = "artifact-sha256.json"
MANIFEST_NAME = "phase8-engineering-evidence-manifest.json"
RECEIPT_NAME = "github-attestation-receipt.json"
P0_NAME = "p0-review-disposition.json"
RUN_ID = 30212165760
COMMAND_ARTIFACT_SET_SHA256 = (
    "8fc420d0d3532b69a268caedb234172fc67c7a89c51f716d5a1d298b93a1f9bd"
)
ENGINEERING_COMMAND_TESTS = {
    "wave_a_static": 88,
    "wave_a_java": 2,
    "wave_b_static_and_models": 406,
    "wave_b_java_unit": 30,
    "wave_b_postgresql_integration": 1,
}
REVIEWER = "codex-subagent:/root/p8_attempt15_unique_p0_review"
REVIEWED_AT = "2026-07-26T17:29:58Z"
PRODUCER = "codex-primary:/root"
TRUSTED_CODE_PATHS = (
    "contracts/agent-platform/phase8/engineering-candidate-commands.json",
    "contracts/agent-platform/phase8/engineering-candidate-scope.schema.json",
    "contracts/agent-platform/phase8/engineering-p0-disposition.schema.json",
    "contracts/agent-platform/phase8/github-attestation-policy.json",
    "infra-tests/phase8/runtime/Dockerfile",
    "infra-tests/phase8/runtime/requirements.in",
    "infra-tests/phase8/runtime/requirements.lock",
    "infra-tests/phase8/runtime/runtime-policy.json",
    "java-api-service/mvnw",
    "scripts/phase8/candidate/__init__.py",
    "scripts/phase8/candidate/candidate_scope.py",
    "scripts/phase8/candidate/command_contract.py",
    "scripts/phase8/candidate/github_attestation.py",
    "scripts/phase8/candidate/github_command_runner.py",
    "scripts/phase8/candidate/github_witness.py",
    "scripts/phase8/candidate/p0_disposition.py",
    "scripts/phase8/candidate/runtime_policy.py",
    "tests/static/test_phase8_candidate_scope.py",
    "tests/static/test_phase8_engineering_command_contract.py",
    "tests/static/test_phase8_engineering_p0_disposition.py",
    "tests/static/test_phase8_github_attestation.py",
    "tests/static/test_phase8_github_command_runner.py",
    "tests/static/test_phase8_github_witness.py",
    "tests/static/test_phase8_test_runtime_supply_chain.py",
)
CAPABILITY_KEYS = {
    "canary",
    "cloud_access",
    "database_access",
    "production_traffic",
    "promotion",
    "recovery_execution",
    "scheduler_off_activation",
    "secret_access",
    "temporal_access",
    "v046_production_apply",
    "v046_production_switch",
    "v047_cleanup",
}


def _git(*arguments: str) -> str:
    return subprocess.run(
        ["git", *arguments],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    ).stdout.strip()


def _git_bytes(*arguments: str) -> bytes:
    return subprocess.run(
        ["git", *arguments], cwd=ROOT, check=True, capture_output=True
    ).stdout


def _blob(name: str, *, commit: str = EVIDENCE_COMMIT) -> bytes:
    return _git_bytes("show", f"{commit}:{EVIDENCE_PREFIX}/{name}")


def _strict_json(raw: bytes) -> dict[str, Any]:
    def reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        value: dict[str, Any] = {}
        for key, item in pairs:
            assert key not in value
            value[key] = item
        return value

    value = json.loads(
        raw.decode("utf-8"),
        object_pairs_hook=reject_duplicates,
        parse_constant=lambda token: (_ for _ in ()).throw(
            AssertionError(f"non-finite JSON number: {token}")
        ),
    )
    assert isinstance(value, dict)
    canonical = json.dumps(
        value,
        allow_nan=False,
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    assert raw == canonical
    return value


def _tree_blob(commit: str, path: str) -> tuple[str, str, bytes]:
    mode, object_type, object_id, resolved_path = _git(
        "ls-tree", commit, "--", path
    ).split(maxsplit=3)
    assert object_type == "blob" and resolved_path == path
    return mode, object_id, _git_bytes("show", f"{commit}:{path}")


def _acceptance_commit() -> str | None:
    commits = _git(
        "log", "--diff-filter=A", "--format=%H", "--", CHECKPOINT_PATH
    ).splitlines()
    if not commits:
        assert _git("rev-parse", "HEAD") == EVIDENCE_COMMIT
        return None
    assert len(commits) == 1
    return commits[0]


def _candidate_bindings() -> dict[str, Any]:
    manifest = _git_bytes(
        "show", f"{CANDIDATE}:{candidate_scope.SELF_PATH}"
    )
    scope = candidate_scope.validate(CANDIDATE, manifest)
    inventory = [
        {
            "change": "ADDED" if item["status"] == "A" else "MODIFIED",
            "git_blob_sha": item["git_blob_sha"],
            "mode": item["mode"],
            "path": item["path"],
            "sha256": item["sha256"],
        }
        for item in scope["derived_inventory"]
    ]
    return {
        "accepted_a8_sha": scope["accepted_entry_sha"],
        "changed_inventory": inventory,
        "changed_inventory_sha256": p0_disposition.canonical_sha256(inventory),
        "commit_sha": scope["candidate_sha"],
        "diff_sha256": scope["derived_inventory_sha256"],
        "scope_inventory_sha256": scope["derived_inventory_sha256"],
        "sole_parent_sha": scope["candidate_parent_sha"],
        "tree_sha": scope["candidate_tree_sha"],
    }


def _command_contract_binding() -> dict[str, str]:
    path = p0_disposition.COMMAND_CONTRACT_PATH
    mode, object_id, raw = _tree_blob(TRUSTED_CODE, path)
    assert mode == "100644"
    document = command_contract.validate_command_contract(
        command_contract.parse_bounded_json_bytes(raw)
    )
    return {
        "git_blob_sha": object_id,
        "path": path,
        "payload_sha256": command_contract.contract_payload_sha256(document),
        "sha256": hashlib.sha256(raw).hexdigest(),
    }


def _trusted_builder_binding() -> dict[str, Any]:
    blobs = []
    for path in TRUSTED_CODE_PATHS:
        _mode, object_id, raw = _tree_blob(TRUSTED_CODE, path)
        blobs.append(
            {
                "git_blob_sha": object_id,
                "path": path,
                "sha256": hashlib.sha256(raw).hexdigest(),
            }
        )
    workflow_path = p0_disposition.TRUSTED_WORKFLOW_PATH
    mode, object_id, raw = _tree_blob(TRUSTED_WORKFLOW, workflow_path)
    assert mode == "100644"
    return {
        "trusted_code_blob_bundle": {
            "blobs": blobs,
            "sha256": p0_disposition.canonical_sha256(blobs),
        },
        "trusted_code_sha": TRUSTED_CODE,
        "trusted_code_tree_sha": _git("rev-parse", f"{TRUSTED_CODE}^{{tree}}"),
        "trusted_workflow_blob": {
            "git_blob_sha": object_id,
            "path": workflow_path,
            "sha256": hashlib.sha256(raw).hexdigest(),
        },
        "trusted_workflow_sha": TRUSTED_WORKFLOW,
        "trusted_workflow_tree_sha": _git(
            "rev-parse", f"{TRUSTED_WORKFLOW}^{{tree}}"
        ),
    }


def test_phase8_candidate_evidence_and_acceptance_form_exact_chain() -> None:
    assert _git("rev-list", "--parents", "-n", "1", CANDIDATE).split() == [
        CANDIDATE,
        TRUSTED_WORKFLOW,
    ]
    assert _git("rev-list", "--parents", "-n", "1", EVIDENCE_COMMIT).split() == [
        EVIDENCE_COMMIT,
        CANDIDATE,
    ]
    acceptance = _acceptance_commit()
    if acceptance is None:
        return
    assert _git("rev-list", "--parents", "-n", "1", acceptance).split() == [
        acceptance,
        EVIDENCE_COMMIT,
    ]
    records = [
        line.split("\t", 1)
        for line in _git(
            "diff-tree",
            "--no-commit-id",
            "--name-status",
            "-r",
            "--no-renames",
            acceptance,
        ).splitlines()
    ]
    assert records == [["A", CHECKPOINT_PATH], ["A", TEST_PATH]]
    assert all(_tree_blob(acceptance, path)[0] == "100644" for _, path in records)


def test_phase8_evidence_has_six_canonical_indexed_blobs() -> None:
    records = [
        line.split("\t", 1)
        for line in _git(
            "diff-tree",
            "--no-commit-id",
            "--name-status",
            "-r",
            "--no-renames",
            EVIDENCE_COMMIT,
        ).splitlines()
    ]
    assert len(records) == 6
    assert all(
        status == "A" and path.startswith(f"{EVIDENCE_PREFIX}/")
        for status, path in records
    )
    assert all(_tree_blob(EVIDENCE_COMMIT, path)[0] == "100644" for _, path in records)

    index = _strict_json(_blob(INDEX_NAME))
    indexed = index["artifacts"]
    assert set(index) == {
        "additional_fields",
        "artifacts",
        "candidate_commit",
        "evidence_manifest_sha256",
        "release_id",
        "schema_version",
    }
    assert index["candidate_commit"] == CANDIDATE
    assert index["release_id"] == RELEASE_ID
    assert len(indexed) == 5
    assert {item["path"] for item in indexed} == {
        ".gitattributes",
        "candidate.txt",
        RECEIPT_NAME,
        P0_NAME,
        MANIFEST_NAME,
    }
    for item in indexed:
        raw = _blob(item["path"])
        assert item == {
            "bytes": len(raw),
            "path": item["path"],
            "sha256": hashlib.sha256(raw).hexdigest(),
        }
        if item["path"].endswith(".json"):
            _strict_json(raw)
    assert _blob("candidate.txt") == f"{CANDIDATE}\n".encode()
    assert index["evidence_manifest_sha256"] == hashlib.sha256(
        _blob(MANIFEST_NAME)
    ).hexdigest()


def test_phase8_receipt_and_manifest_keep_production_closed() -> None:
    receipt = _strict_json(_blob(RECEIPT_NAME))
    assert receipt["accepted"] is True
    assert receipt["candidate_sha"] == CANDIDATE
    assert receipt["candidate_tree_sha"] == CANDIDATE_TREE
    assert receipt["trusted_code_sha"] == TRUSTED_CODE
    assert receipt["trusted_workflow_sha"] == TRUSTED_WORKFLOW
    assert receipt["run_id"] == RUN_ID and receipt["run_attempt"] == 1
    assert receipt["command_artifact_set_sha256"] == COMMAND_ARTIFACT_SET_SHA256
    assert receipt["attestation"]["online_verified"] is True
    assert receipt["attestation"]["offline_verified"] is True
    assert receipt["authority_ceiling"] == "PHASE_8_ENGINEERING_CHECKPOINT_ONLY"
    assert receipt["production_authority"] is False
    assert receipt["production_promotion"] == "FORBIDDEN"
    assert receipt["MIG-006"] == receipt["MIG-007"] == receipt["MIG-008"]
    assert receipt["MIG-008"] == "PENDING_PROMOTION"

    manifest = _strict_json(_blob(MANIFEST_NAME))
    assert manifest["engineering_checkpoint"] == "PENDING_A8ENG"
    assert manifest["next_phase_permission"] == "PENDING_A8ENG"
    assert manifest["production_checkpoint"] == "PENDING_EXTERNAL"
    assert manifest["promotion_gate"] == "PENDING"
    assert manifest["source"]["run_id"] == RUN_ID
    assert manifest["candidate"] == {
        "accepted_a8_sha": receipt["accepted_a8_sha"],
        "commit_sha": CANDIDATE,
        "scope_inventory_sha256": receipt["scope_inventory_sha256"],
        "sole_parent_sha": TRUSTED_WORKFLOW,
        "tree_sha": CANDIDATE_TREE,
        "trusted_code_sha": TRUSTED_CODE,
        "trusted_transition_sha256": receipt["trusted_transition_sha256"],
        "trusted_workflow_sha": TRUSTED_WORKFLOW,
    }
    authority = manifest["authority"]
    assert authority["authority_ceiling"] == "PHASE_8_ENGINEERING_CHECKPOINT_ONLY"
    assert authority["production_authority"] is False
    assert authority["production_promotion"] == "FORBIDDEN"
    assert set(authority["capabilities"]) == CAPABILITY_KEYS
    assert all(value is False for value in authority["capabilities"].values())
    assert set(authority["migrations"].values()) == {"PENDING_PROMOTION"}


def test_phase8_p0_disposition_is_independently_bound_and_self_sealed() -> None:
    receipt = _strict_json(_blob(RECEIPT_NAME))
    raw = _blob(P0_NAME)
    document = _strict_json(raw)
    validated = p0_disposition.parse_and_validate_p0_disposition(
        raw,
        expected_candidate_bindings=_candidate_bindings(),
        expected_command_contract_binding=_command_contract_binding(),
        expected_trusted_builder_binding=_trusted_builder_binding(),
        expected_github_attestation_receipt=receipt,
        expected_closed_finding_ids_by_lane={"consolidated": []},
        expected_reviewer_identities_by_lane={"consolidated": REVIEWER},
        expected_reviewed_at_by_lane={"consolidated": REVIEWED_AT},
        expected_producer_identity=PRODUCER,
    )
    assert validated == document
    assert document["status"] == "ALL_P0_CLOSED"
    assert document["open_p0_count"] == 0
    assert document["self_approved"] is False
    assert document["producer_identity"] == PRODUCER
    assert document["review_lanes"][0]["reviewer_identity"] == REVIEWER
    assert document["self_seal"] == p0_disposition.self_seal_for(document)


def test_phase8_checkpoint_records_engineering_only_permission() -> None:
    text = (ROOT / CHECKPOINT_PATH).read_text(encoding="utf-8")
    assert sum(ENGINEERING_COMMAND_TESTS.values()) == 527
    for command, tests in ENGINEERING_COMMAND_TESTS.items():
        assert f"{command}: {tests}" in text
    for claim in (
        CANDIDATE,
        EVIDENCE_COMMIT,
        TRUSTED_CODE,
        TRUSTED_WORKFLOW,
        "engineering_checkpoint: PASS",
        "production_checkpoint: PENDING_EXTERNAL",
        "promotion_gate: PENDING",
        "next_phase_permission: EXTERNAL_PRODUCTION_CHECKPOINT_ONLY",
        "MIG-006: PENDING_PROMOTION",
        "MIG-007: PENDING_PROMOTION",
        "MIG-008: PENDING_PROMOTION",
        "authority_ceiling: PHASE_8_ENGINEERING_CHECKPOINT_ONLY",
        "production_authority: FALSE",
        "v046_production_apply: FORBIDDEN",
        "v046_production_switch: FORBIDDEN",
        "scheduler_off_activation: FORBIDDEN",
        "canary: FORBIDDEN",
        "promotion: FORBIDDEN",
        "v047_cleanup: FORBIDDEN",
        "authenticated_test_total: 527",
        f"command_artifact_set_sha256: {COMMAND_ARTIFACT_SET_SHA256}",
    ):
        assert claim in text
