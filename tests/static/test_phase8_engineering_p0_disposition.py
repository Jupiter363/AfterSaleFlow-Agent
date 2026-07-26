from __future__ import annotations

import ast
import copy
import hashlib
import json
from pathlib import Path
from typing import Any, Callable

import pytest
from jsonschema import Draft202012Validator

from scripts.phase8.candidate import p0_disposition


ROOT = Path(__file__).resolve().parents[2]
SCHEMA_PATH = (
    ROOT / "contracts/agent-platform/phase8/engineering-p0-disposition.schema.json"
)
MODULE_PATH = ROOT / "scripts/phase8/candidate/p0_disposition.py"


def _sha1(character: str) -> str:
    return character * 40


def _sha256(character: str) -> str:
    return character * 64


def _candidate() -> dict[str, Any]:
    inventory = [
        {
            "change": "MODIFIED",
            "git_blob_sha": _sha1("1"),
            "mode": "100644",
            "path": "java-api-service/src/main/resources/db/migration/V046__phase8.sql",
            "sha256": _sha256("1"),
        },
        {
            "change": "ADDED",
            "git_blob_sha": _sha1("2"),
            "mode": "100644",
            "path": "scripts/phase8/candidate/run_checkpoint.py",
            "sha256": _sha256("2"),
        },
    ]
    return {
        "accepted_a8_sha": _sha1("a"),
        "changed_inventory": inventory,
        "changed_inventory_sha256": p0_disposition.canonical_sha256(inventory),
        "commit_sha": _sha1("b"),
        "diff_sha256": _sha256("3"),
        "scope_inventory_sha256": _sha256("0"),
        "sole_parent_sha": _sha1("c"),
        "tree_sha": _sha1("d"),
    }


def _command_contract() -> dict[str, Any]:
    return {
        "git_blob_sha": _sha1("4"),
        "path": p0_disposition.COMMAND_CONTRACT_PATH,
        "payload_sha256": _sha256("4"),
        "sha256": _sha256("5"),
    }


def _trusted_builder() -> dict[str, Any]:
    blobs = [
        {
            "git_blob_sha": _sha1("6"),
            "path": "scripts/phase8/candidate/github_witness.py",
            "sha256": _sha256("7"),
        },
    ]
    return {
        "trusted_code_blob_bundle": {
            "blobs": blobs,
            "sha256": p0_disposition.canonical_sha256(blobs),
        },
        "trusted_code_sha": _sha1("e"),
        "trusted_code_tree_sha": _sha1("f"),
        "trusted_workflow_blob": {
            "git_blob_sha": _sha1("5"),
            "path": p0_disposition.TRUSTED_WORKFLOW_PATH,
            "sha256": _sha256("6"),
        },
        "trusted_workflow_sha": _sha1("7"),
        "trusted_workflow_tree_sha": _sha1("8"),
    }


def _witness(
    candidate: dict[str, Any],
    command_contract: dict[str, Any],
    trusted_builder: dict[str, Any],
) -> dict[str, Any]:
    receipt = {
        "MIG-006": "PENDING_PROMOTION",
        "MIG-007": "PENDING_PROMOTION",
        "MIG-008": "PENDING_PROMOTION",
        "acceptance_key": _sha256("a"),
        "accepted": True,
        "accepted_a8_sha": candidate["accepted_a8_sha"],
        "artifact": {
            "id": 9097,
            "name": "phase8-engineering-witness-987654321-2",
            "sha256": _sha256("8"),
            "subject_filename": "phase8-engineering-witness.tar",
        },
        "attestation": {
            "bundle_sha256": _sha256("b"),
            "certificate_sha256": _sha256("c"),
            "offline_verified": True,
            "online_verified": True,
            "predicate_authority": (
                "BUILDER_CONTROLLED_UNTRUSTED_UNTIL_LOCAL_RECOMPUTE"
            ),
            "predicate_sha256": _sha256("d"),
            "trusted_root_sha256": _sha256("e"),
            "verified_timestamps": [
                {
                    "timestamp": "2026-07-25T11:59:00Z",
                    "type": "Tlog",
                    "uri": "https://rekor.sigstore.dev",
                }
            ],
        },
        "attestation_composite_sha256": _sha256("b"),
        "authority_ceiling": p0_disposition.AUTHORITY_CEILING,
        "caller_workflow_binding": {
            "file_sha256": _sha256("f"),
            "git_blob_sha1": _sha1("9"),
            "mode": "100644",
            "path": ".github/workflows/phase8-engineering-caller.yml",
            "trusted_workflow_sha": trusted_builder["trusted_workflow_sha"],
        },
        "candidate_sha": candidate["commit_sha"],
        "candidate_tree_sha": candidate["tree_sha"],
        "command_artifact_set_sha256": _sha256("9"),
        "command_contract_payload_sha256": command_contract["payload_sha256"],
        "event": "push",
        "ledger_durability": (
            "EXTERNAL_RUN_DIRECTORY_PLACEHOLDER_NOT_GLOBAL_REPLAY_AUTHORITY"
        ),
        "production_authority": False,
        "production_promotion": "FORBIDDEN",
        "repository": p0_disposition.EXPECTED_REPOSITORY,
        "repository_id": p0_disposition.EXPECTED_REPOSITORY_ID,
        "run_attempt": 2,
        "run_id": 987654321,
        "schema_version": p0_disposition.ATTESTATION_RECEIPT_SCHEMA_VERSION,
        "signer_workflow": (
            f"{p0_disposition.EXPECTED_REPOSITORY}/"
            f"{p0_disposition.TRUSTED_WORKFLOW_PATH}"
        ),
        "source_ref": "refs/heads/codex/p8-production-hardening",
        "scope_inventory_sha256": candidate["scope_inventory_sha256"],
        "trusted_code_sha": trusted_builder["trusted_code_sha"],
        "trusted_code_tree_sha": trusted_builder["trusted_code_tree_sha"],
        "trusted_transition_sha256": _sha256("7"),
        "trusted_workflow_sha": trusted_builder["trusted_workflow_sha"],
        "trusted_workflow_tree_sha": trusted_builder["trusted_workflow_tree_sha"],
    }
    receipt["attestation_composite_sha256"] = (
        p0_disposition.calculate_verified_attestation_composite_sha256(
            candidate_sha=receipt["candidate_sha"],
            candidate_tree_sha=receipt["candidate_tree_sha"],
            accepted_a8_sha=receipt["accepted_a8_sha"],
            scope_inventory_sha256=receipt["scope_inventory_sha256"],
            command_contract_payload_sha256=receipt["command_contract_payload_sha256"],
            artifact_subject_sha256=receipt["artifact"]["sha256"],
            caller_workflow_file_sha256=receipt["caller_workflow_binding"][
                "file_sha256"
            ],
            caller_workflow_git_blob_sha1=receipt["caller_workflow_binding"][
                "git_blob_sha1"
            ],
            command_artifact_set_sha256=receipt["command_artifact_set_sha256"],
            trusted_code_sha=receipt["trusted_code_sha"],
            trusted_code_tree_sha=receipt["trusted_code_tree_sha"],
            trusted_transition_sha256=receipt["trusted_transition_sha256"],
            trusted_workflow_sha=receipt["trusted_workflow_sha"],
            trusted_workflow_tree_sha=receipt["trusted_workflow_tree_sha"],
            run_id=receipt["run_id"],
            run_attempt=receipt["run_attempt"],
        )
    )
    receipt["acceptance_key"] = hashlib.sha256(
        (
            f"{receipt['candidate_sha']}|{receipt['trusted_code_sha']}|"
            f"{receipt['trusted_workflow_sha']}|{receipt['run_id']}|"
            f"{receipt['run_attempt']}|{receipt['artifact']['sha256']}|"
            f"{receipt['attestation_composite_sha256']}"
        ).encode("ascii")
    ).hexdigest()
    return receipt


def _closed_findings() -> dict[str, list[str]]:
    return {
        "consolidated": [
            "P0-AUTHORITY-001",
            "P0-DATA-001",
            "P0-DATA-002",
        ],
    }


def _lane_bindings(
    candidate: dict[str, Any],
    command_contract: dict[str, Any],
    trusted_builder: dict[str, Any],
    witness: dict[str, Any],
) -> dict[str, Any]:
    artifact = witness["artifact"]
    caller = witness["caller_workflow_binding"]
    return {
        "accepted_a8_sha": candidate["accepted_a8_sha"],
        "acceptance_key": witness["acceptance_key"],
        "artifact_id": artifact["id"],
        "artifact_name": artifact["name"],
        "artifact_subject_filename": artifact["subject_filename"],
        "artifact_subject_sha256": artifact["sha256"],
        "attestation_composite_sha256": witness["attestation_composite_sha256"],
        "caller_workflow_file_sha256": caller["file_sha256"],
        "caller_workflow_git_blob_sha1": caller["git_blob_sha1"],
        "candidate_changed_inventory_sha256": candidate["changed_inventory_sha256"],
        "candidate_commit_sha": candidate["commit_sha"],
        "candidate_diff_sha256": candidate["diff_sha256"],
        "candidate_scope_inventory_sha256": candidate["scope_inventory_sha256"],
        "candidate_sole_parent_sha": candidate["sole_parent_sha"],
        "candidate_tree_sha": candidate["tree_sha"],
        "command_contract_git_blob_sha": command_contract["git_blob_sha"],
        "command_contract_payload_sha256": command_contract["payload_sha256"],
        "command_contract_sha256": command_contract["sha256"],
        "command_artifact_set_sha256": witness["command_artifact_set_sha256"],
        "github_run_attempt": witness["run_attempt"],
        "github_run_id": witness["run_id"],
        "repository": witness["repository"],
        "repository_id": witness["repository_id"],
        "signer_workflow": witness["signer_workflow"],
        "source_ref": witness["source_ref"],
        "trusted_code_blob_bundle_sha256": trusted_builder["trusted_code_blob_bundle"][
            "sha256"
        ],
        "trusted_code_sha": trusted_builder["trusted_code_sha"],
        "trusted_code_tree_sha": trusted_builder["trusted_code_tree_sha"],
        "trusted_transition_sha256": witness["trusted_transition_sha256"],
        "trusted_workflow_git_blob_sha": trusted_builder["trusted_workflow_blob"][
            "git_blob_sha"
        ],
        "trusted_workflow_sha": trusted_builder["trusted_workflow_sha"],
        "trusted_workflow_tree_sha": trusted_builder["trusted_workflow_tree_sha"],
    }


def _authority() -> dict[str, Any]:
    return {
        "authority_ceiling": p0_disposition.AUTHORITY_CEILING,
        "capabilities": {
            key: False for key in p0_disposition.PRODUCTION_CAPABILITY_KEYS
        },
        "cryptographic_production_attestation": False,
        "migrations": {
            "MIG-006": "PENDING_PROMOTION",
            "MIG-007": "PENDING_PROMOTION",
            "MIG-008": "PENDING_PROMOTION",
        },
        "production_reuse": "FORBIDDEN",
    }


def _document() -> dict[str, Any]:
    candidate = _candidate()
    command_contract = _command_contract()
    trusted_builder = _trusted_builder()
    witness = _witness(candidate, command_contract, trusted_builder)
    findings = _closed_findings()
    composite = witness["attestation_composite_sha256"]
    bindings = _lane_bindings(candidate, command_contract, trusted_builder, witness)
    document = {
        "additional_fields": "DENY",
        "attestation_composite_sha256": composite,
        "authority": _authority(),
        "candidate": candidate,
        "command_contract": command_contract,
        "contract_kind": p0_disposition.CONTRACT_KIND,
        "open_p0_count": 0,
        "producer_identity": "trusted-witness-producer",
        "review_lanes": [
            {
                "bindings": copy.deepcopy(bindings),
                "closed_finding_ids": findings[lane],
                "lane": lane,
                "open_p0_count": 0,
                "reviewed_at": f"2026-07-25T12:00:0{index}Z",
                "reviewed_topics": list(p0_disposition.LANE_TOPICS[lane]),
                "reviewer_identity": f"reviewer-{lane}",
                "self_approved": False,
                "status": "ALL_P0_CLOSED",
            }
            for index, lane in enumerate(p0_disposition.LANE_ORDER, start=1)
        ],
        "review_scope": "CONSOLIDATED_POST_INTEGRATION_P0_ONLY",
        "reviewed_topics": list(p0_disposition.REVIEW_TOPICS),
        "repository": p0_disposition.EXPECTED_REPOSITORY,
        "repository_id": p0_disposition.EXPECTED_REPOSITORY_ID,
        "schema_version": p0_disposition.SCHEMA_VERSION,
        "self_approved": False,
        "status": "ALL_P0_CLOSED",
        "trusted_builder": trusted_builder,
        "witness_artifact": witness,
    }
    return p0_disposition.seal_p0_disposition(document)


def _expected(document: dict[str, Any]) -> dict[str, Any]:
    return {
        "expected_candidate_bindings": copy.deepcopy(document["candidate"]),
        "expected_command_contract_binding": copy.deepcopy(
            document["command_contract"]
        ),
        "expected_trusted_builder_binding": copy.deepcopy(document["trusted_builder"]),
        "expected_github_attestation_receipt": copy.deepcopy(
            document["witness_artifact"]
        ),
        "expected_closed_finding_ids_by_lane": _closed_findings(),
        "expected_reviewer_identities_by_lane": {
            lane["lane"]: lane["reviewer_identity"] for lane in document["review_lanes"]
        },
        "expected_reviewed_at_by_lane": {
            lane["lane"]: lane["reviewed_at"] for lane in document["review_lanes"]
        },
        "expected_producer_identity": document["producer_identity"],
    }


def _validate(document: dict[str, Any], expected: dict[str, Any] | None = None):
    return p0_disposition.validate_p0_disposition(
        document, **(_expected(_document()) if expected is None else expected)
    )


def _reseal(document: dict[str, Any]) -> None:
    document["self_seal"] = p0_disposition.self_seal_for(document)


def _refresh_attestation(document: dict[str, Any]) -> str:
    candidate = document["candidate"]
    command_contract = document["command_contract"]
    trusted_builder = document["trusted_builder"]
    witness = document["witness_artifact"]
    witness.update(
        {
            "accepted_a8_sha": candidate["accepted_a8_sha"],
            "candidate_sha": candidate["commit_sha"],
            "candidate_tree_sha": candidate["tree_sha"],
            "command_contract_payload_sha256": command_contract["payload_sha256"],
            "scope_inventory_sha256": candidate["scope_inventory_sha256"],
            "trusted_code_sha": trusted_builder["trusted_code_sha"],
            "trusted_code_tree_sha": trusted_builder["trusted_code_tree_sha"],
            "trusted_workflow_sha": trusted_builder["trusted_workflow_sha"],
            "trusted_workflow_tree_sha": trusted_builder["trusted_workflow_tree_sha"],
        }
    )
    witness["caller_workflow_binding"]["trusted_workflow_sha"] = trusted_builder[
        "trusted_workflow_sha"
    ]
    composite = p0_disposition.calculate_verified_attestation_composite_sha256(
        candidate_sha=witness["candidate_sha"],
        candidate_tree_sha=witness["candidate_tree_sha"],
        accepted_a8_sha=witness["accepted_a8_sha"],
        scope_inventory_sha256=witness["scope_inventory_sha256"],
        command_contract_payload_sha256=witness["command_contract_payload_sha256"],
        artifact_subject_sha256=witness["artifact"]["sha256"],
        caller_workflow_file_sha256=witness["caller_workflow_binding"]["file_sha256"],
        caller_workflow_git_blob_sha1=witness["caller_workflow_binding"][
            "git_blob_sha1"
        ],
        command_artifact_set_sha256=witness["command_artifact_set_sha256"],
        trusted_code_sha=witness["trusted_code_sha"],
        trusted_code_tree_sha=witness["trusted_code_tree_sha"],
        trusted_transition_sha256=witness["trusted_transition_sha256"],
        trusted_workflow_sha=witness["trusted_workflow_sha"],
        trusted_workflow_tree_sha=witness["trusted_workflow_tree_sha"],
        run_id=witness["run_id"],
        run_attempt=witness["run_attempt"],
    )
    witness["attestation_composite_sha256"] = composite
    witness["acceptance_key"] = hashlib.sha256(
        (
            f"{witness['candidate_sha']}|{witness['trusted_code_sha']}|"
            f"{witness['trusted_workflow_sha']}|{witness['run_id']}|"
            f"{witness['run_attempt']}|{witness['artifact']['sha256']}|{composite}"
        ).encode("ascii")
    ).hexdigest()
    document["attestation_composite_sha256"] = composite
    bindings = _lane_bindings(candidate, command_contract, trusted_builder, witness)
    for lane in document["review_lanes"]:
        lane["bindings"] = copy.deepcopy(bindings)
    _reseal(document)
    return composite


def _mutate_mixed_t8_replay(document: dict[str, Any]) -> None:
    builder = document["trusted_builder"]
    builder["trusted_code_sha"] = _sha1("0")
    builder["trusted_code_tree_sha"] = _sha1("1")
    builder["trusted_code_blob_bundle"]["blobs"][0]["sha256"] = _sha256("2")
    builder["trusted_code_blob_bundle"]["sha256"] = p0_disposition.canonical_sha256(
        builder["trusted_code_blob_bundle"]["blobs"]
    )
    builder["trusted_workflow_sha"] = _sha1("2")
    builder["trusted_workflow_tree_sha"] = _sha1("3")
    builder["trusted_workflow_blob"]["sha256"] = _sha256("0")
    document["witness_artifact"]["trusted_workflow_sha"] = _sha1("2")


def test_schema_is_strict_draft_2020_12_and_repository_document_validates() -> None:
    schema = p0_disposition.load_schema()
    Draft202012Validator.check_schema(schema)
    document = _document()
    assert _validate(document) == document
    assert SCHEMA_PATH == p0_disposition.SCHEMA_PATH
    assert hashlib.sha256(SCHEMA_PATH.read_bytes()).hexdigest() == (
        p0_disposition.SCHEMA_SHA256
    )


def test_real_receipt_composite_and_acceptance_key_bind_every_lane() -> None:
    document = _document()
    candidate = document["candidate"]
    receipt = document["witness_artifact"]
    composite = document["attestation_composite_sha256"]
    assert candidate["changed_inventory_sha256"] != candidate["scope_inventory_sha256"]
    assert receipt["scope_inventory_sha256"] == candidate["scope_inventory_sha256"]
    assert composite == receipt["attestation_composite_sha256"]
    assert len(receipt["acceptance_key"]) == 64
    assert all(
        character in "0123456789abcdef" for character in receipt["acceptance_key"]
    )
    assert not hasattr(p0_disposition, "attestation_composite_payload")
    assert not hasattr(p0_disposition, "attestation_composite_sha256")
    assert all(
        lane["bindings"]["attestation_composite_sha256"] == composite
        and lane["bindings"]["acceptance_key"] == receipt["acceptance_key"]
        and lane["bindings"]["candidate_changed_inventory_sha256"]
        == candidate["changed_inventory_sha256"]
        and lane["bindings"]["candidate_scope_inventory_sha256"]
        == candidate["scope_inventory_sha256"]
        and lane["bindings"]["trusted_transition_sha256"]
        == receipt["trusted_transition_sha256"]
        for lane in document["review_lanes"]
    )


def test_candidate_scope_inventory_cannot_diverge_from_verified_receipt() -> None:
    document = _document()
    document["candidate"]["scope_inventory_sha256"] = _sha256("1")
    document["review_lanes"][0]["bindings"][
        "candidate_scope_inventory_sha256"
    ] = _sha256("1")
    expected = _expected(document)
    _reseal(document)

    with pytest.raises(
        p0_disposition.P0DispositionValidationError,
        match="attestation receipt differs from candidate",
    ):
        _validate(document, expected)


def test_lane_cannot_substitute_candidate_scope_inventory_binding() -> None:
    document = _document()
    document["review_lanes"][0]["bindings"][
        "candidate_scope_inventory_sha256"
    ] = _sha256("1")
    _reseal(document)

    with pytest.raises(
        p0_disposition.P0DispositionValidationError,
        match="not bound to the exact candidate and witness",
    ):
        _validate(document)


@pytest.mark.parametrize(
    "field",
    ("trusted_transition_sha256", "acceptance_key"),
)
def test_verified_receipt_transition_or_acceptance_substitution_is_rejected(
    field: str,
) -> None:
    document = _document()
    document["witness_artifact"][field] = _sha256("0")
    document["review_lanes"][0]["bindings"][field] = _sha256("0")
    _reseal(document)

    with pytest.raises(
        p0_disposition.P0DispositionValidationError,
        match="witness, report, or attestation binding differs from trusted intake",
    ):
        _validate(document)


def test_exact_15_topics_are_covered_by_one_consolidated_lane() -> None:
    assert len(p0_disposition.REVIEW_TOPICS) == 15
    assert len(set(p0_disposition.REVIEW_TOPICS)) == 15
    assert p0_disposition.LANE_ORDER == ("consolidated",)
    assert (
        tuple(
            topic
            for lane in p0_disposition.LANE_ORDER
            for topic in p0_disposition.LANE_TOPICS[lane]
        )
        == p0_disposition.REVIEW_TOPICS
    )
    assert len(p0_disposition.LANE_TOPICS["consolidated"]) == 15


@pytest.mark.parametrize(
    "mutation",
    (
        lambda doc: doc["reviewed_topics"].pop(),
        lambda doc: doc["reviewed_topics"].__setitem__(0, "topology-policy"),
        lambda doc: doc["review_lanes"][0]["reviewed_topics"].pop(),
        lambda doc: doc["review_lanes"][0]["reviewed_topics"].__setitem__(
            0, "observability-privacy"
        ),
        lambda doc: doc["review_lanes"].append(copy.deepcopy(doc["review_lanes"][0])),
    ),
    ids=(
        "topic-omission",
        "topic-substitution",
        "lane-topic-omission",
        "lane-topic-substitution",
        "duplicate-lane",
    ),
)
def test_topic_or_lane_omission_substitution_and_duplication_fail_closed(
    mutation: Callable[[dict[str, Any]], None],
) -> None:
    document = _document()
    mutation(document)
    _reseal(document)
    with pytest.raises(p0_disposition.P0DispositionValidationError):
        _validate(document)


@pytest.mark.parametrize(
    "mutation",
    (
        lambda doc: doc["review_lanes"][0].__setitem__(
            "reviewer_identity", doc["producer_identity"]
        ),
        lambda doc: doc.__setitem__("self_approved", True),
        lambda doc: doc["review_lanes"][0].__setitem__("self_approved", True),
    ),
    ids=("producer-reviewer", "root-self-approval", "lane-self-approval"),
)
def test_reviewer_independence_and_self_approval_are_fail_closed(
    mutation: Callable[[dict[str, Any]], None],
) -> None:
    document = _document()
    mutation(document)
    _reseal(document)
    with pytest.raises(p0_disposition.P0DispositionValidationError):
        _validate(document)


@pytest.mark.parametrize(
    "mutation",
    (
        lambda doc: doc["candidate"].__setitem__("tree_sha", _sha1("0")),
        lambda doc: doc["candidate"]["changed_inventory"][0].__setitem__(
            "sha256", _sha256("0")
        ),
        lambda doc: doc["command_contract"].__setitem__("sha256", _sha256("0")),
        lambda doc: doc["trusted_builder"].__setitem__("trusted_code_sha", _sha1("0")),
        lambda doc: doc["trusted_builder"]["trusted_workflow_blob"].__setitem__(
            "sha256", _sha256("0")
        ),
        lambda doc: doc["trusted_builder"].__setitem__(
            "trusted_workflow_sha", _sha1("0")
        ),
        lambda doc: doc["witness_artifact"].__setitem__(
            "command_artifact_set_sha256", _sha256("0")
        ),
        lambda doc: doc["witness_artifact"].__setitem__("acceptance_key", _sha256("0")),
        lambda doc: doc["witness_artifact"].__setitem__(
            "trusted_transition_sha256", _sha256("0")
        ),
        lambda doc: doc["witness_artifact"]["artifact"].__setitem__(
            "sha256", _sha256("0")
        ),
        lambda doc: doc["review_lanes"][0]["bindings"].__setitem__(
            "candidate_diff_sha256", _sha256("0")
        ),
        lambda doc: doc.__setitem__("attestation_composite_sha256", _sha256("0")),
        lambda doc: doc["review_lanes"][0]["bindings"].__setitem__(
            "attestation_composite_sha256", _sha256("0")
        ),
    ),
    ids=(
        "candidate-tree",
        "candidate-inventory",
        "command-contract",
        "trusted-builder-sha",
        "trusted-workflow-blob",
        "trusted-workflow-sha",
        "command-artifact-set",
        "attestation-key",
        "trusted-transition",
        "witness-subject",
        "lane-binding",
        "top-level-composite",
        "lane-composite",
    ),
)
def test_forged_candidate_command_t8_report_or_attestation_is_rejected(
    mutation: Callable[[dict[str, Any]], None],
) -> None:
    document = _document()
    mutation(document)
    if "changed_inventory" in document["candidate"]:
        document["candidate"]["changed_inventory_sha256"] = (
            p0_disposition.canonical_sha256(document["candidate"]["changed_inventory"])
        )
    _reseal(document)
    with pytest.raises(p0_disposition.P0DispositionValidationError):
        _validate(document)


@pytest.mark.parametrize(
    "mutation",
    (
        lambda doc: doc["witness_artifact"].__setitem__(
            "trusted_transition_sha256", _sha256("0")
        ),
        lambda doc: (
            doc["candidate"].__setitem__("commit_sha", _sha1("0")),
            doc["candidate"].__setitem__("tree_sha", _sha1("1")),
            doc["candidate"].__setitem__("sole_parent_sha", _sha1("2")),
        ),
        lambda doc: (
            doc["witness_artifact"]["artifact"].__setitem__("sha256", _sha256("0")),
            doc["witness_artifact"].__setitem__(
                "command_artifact_set_sha256", _sha256("1")
            ),
        ),
        lambda doc: doc["command_contract"].__setitem__("payload_sha256", _sha256("0")),
        _mutate_mixed_t8_replay,
        lambda doc: doc["witness_artifact"].__setitem__(
            "source_ref", "refs/heads/replayed-candidate"
        ),
    ),
    ids=(
        "old-witness-same-run",
        "cross-candidate",
        "mixed-report-subject",
        "mixed-command-contract",
        "mixed-t8-code-workflow",
        "source-ref-replay",
    ),
)
def test_exact_verified_receipt_rejects_same_run_and_mixed_replays(
    mutation: Callable[[dict[str, Any]], None],
) -> None:
    replay = _document()
    mutation(replay)
    _refresh_attestation(replay)
    with pytest.raises(p0_disposition.P0DispositionValidationError):
        _validate(replay)


@pytest.mark.parametrize(
    "mutation",
    (
        lambda doc: doc.__setitem__("status", "OPEN"),
        lambda doc: doc.__setitem__("open_p0_count", 1),
        lambda doc: doc["review_lanes"][0].__setitem__("status", "OPEN"),
        lambda doc: doc["review_lanes"][0].__setitem__("open_p0_count", 1),
        lambda doc: doc["review_lanes"][0]["closed_finding_ids"].clear(),
        lambda doc: doc["review_lanes"][0]["closed_finding_ids"].append(
            "P0-DATA-FORGED"
        ),
    ),
    ids=(
        "root-status",
        "root-open-count",
        "lane-status",
        "lane-open-count",
        "missing-closed-finding",
        "forged-closed-finding",
    ),
)
def test_open_status_or_untrusted_closed_finding_ids_are_rejected(
    mutation: Callable[[dict[str, Any]], None],
) -> None:
    document = _document()
    mutation(document)
    _reseal(document)
    with pytest.raises(p0_disposition.P0DispositionValidationError):
        _validate(document)


@pytest.mark.parametrize(
    "mutation",
    (
        lambda doc: doc["authority"].__setitem__("production_reuse", "ALLOWED"),
        lambda doc: doc["authority"].__setitem__(
            "cryptographic_production_attestation", True
        ),
        lambda doc: doc["authority"]["migrations"].__setitem__("MIG-006", "PASS"),
        lambda doc: doc["authority"]["capabilities"].__setitem__("promotion", True),
        lambda doc: doc["authority"]["capabilities"].__setitem__(
            "scheduler_off_activation", True
        ),
        lambda doc: doc["authority"]["capabilities"].__setitem__(
            "production_traffic", True
        ),
    ),
    ids=(
        "production-reuse",
        "production-attestation",
        "migration-pass",
        "promotion",
        "scheduler-off",
        "production-traffic",
    ),
)
def test_all_production_authority_and_migrations_remain_closed(
    mutation: Callable[[dict[str, Any]], None],
) -> None:
    document = _document()
    mutation(document)
    _reseal(document)
    with pytest.raises(p0_disposition.P0DispositionValidationError):
        _validate(document)


@pytest.mark.parametrize(
    "target",
    (
        "root",
        "candidate",
        "inventory",
        "command",
        "builder",
        "blob-bundle",
        "witness",
        "lane",
        "lane-bindings",
        "authority",
        "capabilities",
        "seal",
    ),
)
def test_unknown_fields_are_denied_at_every_object_boundary(target: str) -> None:
    document = _document()
    targets = {
        "root": document,
        "candidate": document["candidate"],
        "inventory": document["candidate"]["changed_inventory"][0],
        "command": document["command_contract"],
        "builder": document["trusted_builder"],
        "blob-bundle": document["trusted_builder"]["trusted_code_blob_bundle"],
        "witness": document["witness_artifact"],
        "lane": document["review_lanes"][0],
        "lane-bindings": document["review_lanes"][0]["bindings"],
        "authority": document["authority"],
        "capabilities": document["authority"]["capabilities"],
        "seal": document["self_seal"],
    }
    targets[target]["unexpected"] = True
    if target != "seal":
        _reseal(document)
    with pytest.raises(p0_disposition.P0DispositionValidationError, match="schema"):
        _validate(document)


def test_root_exact_keys_do_not_depend_on_repository_schema(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    document = _document()
    document["unexpected"] = True
    monkeypatch.setattr(p0_disposition, "load_schema", lambda: {})
    with pytest.raises(
        p0_disposition.P0DispositionValidationError,
        match="schema-independent exact-key set",
    ):
        _validate(document)


def test_schema_substitution_and_weakening_are_rejected_by_frozen_bytes(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    weak_schema = tmp_path / SCHEMA_PATH.name
    weak_schema.write_text(
        json.dumps({"$schema": "https://json-schema.org/draft/2020-12/schema"}),
        encoding="utf-8",
    )
    monkeypatch.setattr(p0_disposition, "SCHEMA_PATH", weak_schema)
    with pytest.raises(
        p0_disposition.P0DispositionValidationError,
        match="trusted frozen bytes",
    ):
        p0_disposition.load_schema(weak_schema)


def test_repository_and_repository_id_are_bound_at_root_composite_key_and_lanes() -> (
    None
):
    document = _document()
    witness = document["witness_artifact"]
    assert witness["repository"] == "Jupiter363/AfterSaleFlow-Agent"
    assert witness["repository_id"] == "1282437633"
    assert document["repository"] == witness["repository"]
    assert document["repository_id"] == witness["repository_id"]
    assert all(
        lane["bindings"]["repository"] == witness["repository"]
        and lane["bindings"]["repository_id"] == witness["repository_id"]
        for lane in document["review_lanes"]
    )
    assert (
        document["attestation_composite_sha256"]
        == witness["attestation_composite_sha256"]
    )


def test_fork_repository_substitution_fails_even_with_recomputed_expected_bindings(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    document = _document()
    document["witness_artifact"]["repository"] = "attacker/AfterSaleFlow-Agent"
    _refresh_attestation(document)
    monkeypatch.setattr(p0_disposition, "load_schema", lambda: {})
    with pytest.raises(
        p0_disposition.P0DispositionValidationError,
        match="identity or authority drifted",
    ):
        _validate(document, _expected(document))


def test_repository_id_substitution_fails_even_with_recomputed_expected_bindings(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    document = _document()
    document["repository_id"] = "555555555"
    document["witness_artifact"]["repository_id"] = "555555555"
    _refresh_attestation(document)
    monkeypatch.setattr(p0_disposition, "load_schema", lambda: {})
    with pytest.raises(
        p0_disposition.P0DispositionValidationError,
        match="identity or authority drifted",
    ):
        _validate(document, _expected(document))


def test_duplicate_keys_are_rejected_before_schema_validation() -> None:
    raw = p0_disposition.canonical_json_bytes(_document()).replace(
        b'"schema_version":"phase8-engineering-p0-disposition.v1"',
        b'"schema_version":"phase8-engineering-p0-disposition.v1",'
        b'"schema_version":"phase8-engineering-p0-disposition.v1"',
        1,
    )
    with pytest.raises(p0_disposition.P0DispositionValidationError, match="duplicate"):
        p0_disposition.parse_bounded_json_bytes(raw)


@pytest.mark.parametrize(
    "raw",
    (
        b"",
        b"[]",
        b"\xef\xbb\xbf{}",
        b'{"x":NaN}',
        b'\xff{"x":1}',
    ),
)
def test_parser_rejects_empty_nonobject_bom_nonfinite_and_non_utf8(raw: bytes) -> None:
    with pytest.raises(p0_disposition.P0DispositionValidationError):
        p0_disposition.parse_bounded_json_bytes(raw)


def test_parser_enforces_byte_depth_node_and_string_resource_ceilings() -> None:
    with pytest.raises(
        p0_disposition.P0DispositionValidationError, match="byte length"
    ):
        p0_disposition.parse_bounded_json_bytes(
            b'{"x":"' + b"a" * p0_disposition.MAX_DOCUMENT_BYTES + b'"}'
        )
    deep: Any = "leaf"
    for _ in range(p0_disposition.MAX_JSON_DEPTH + 2):
        deep = [deep]
    with pytest.raises(p0_disposition.P0DispositionValidationError, match="depth"):
        p0_disposition.parse_bounded_json_bytes(json.dumps({"x": deep}).encode("utf-8"))
    nodes = {"x": [0] * (p0_disposition.MAX_JSON_NODES + 1)}
    with pytest.raises(p0_disposition.P0DispositionValidationError, match="node"):
        p0_disposition.parse_bounded_json_bytes(json.dumps(nodes).encode("utf-8"))
    oversized_string = {"x": "a" * (p0_disposition.MAX_STRING_BYTES + 1)}
    with pytest.raises(p0_disposition.P0DispositionValidationError, match="string"):
        p0_disposition.parse_bounded_json_bytes(
            json.dumps(oversized_string).encode("utf-8")
        )


def test_adversarial_document_is_bounded_before_any_deepcopy(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    document = _document()
    expected = _expected(document)
    document["producer_identity"] = "a" * (p0_disposition.MAX_STRING_BYTES + 1)

    def forbidden_deepcopy(value: Any) -> Any:
        raise AssertionError(f"deepcopy ran before document bounds: {type(value)!r}")

    monkeypatch.setattr(p0_disposition.copy, "deepcopy", forbidden_deepcopy)
    with pytest.raises(
        p0_disposition.P0DispositionValidationError,
        match="oversized string",
    ):
        _validate(document, expected)


def test_adversarial_expected_binding_is_bounded_before_any_deepcopy(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    document = _document()
    expected = _expected(document)
    deep: Any = "leaf"
    for _ in range(p0_disposition.MAX_JSON_DEPTH + 2):
        deep = [deep]
    expected["expected_candidate_bindings"]["changed_inventory"] = deep

    def forbidden_deepcopy(value: Any) -> Any:
        raise AssertionError(f"deepcopy ran before expected bounds: {type(value)!r}")

    monkeypatch.setattr(p0_disposition.copy, "deepcopy", forbidden_deepcopy)
    with pytest.raises(
        p0_disposition.P0DispositionValidationError,
        match="depth limit",
    ):
        _validate(document, expected)


def test_oversized_raw_input_is_rejected_before_json_decode(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def forbidden_loads(*args: Any, **kwargs: Any) -> Any:
        raise AssertionError("json.loads ran before raw byte bounds")

    monkeypatch.setattr(p0_disposition.json, "loads", forbidden_loads)
    with pytest.raises(
        p0_disposition.P0DispositionValidationError,
        match="byte length",
    ):
        p0_disposition.parse_bounded_json_bytes(
            b" " * (p0_disposition.MAX_DOCUMENT_BYTES + 1)
        )


def test_validation_requires_exact_independently_derived_expected_shapes() -> None:
    document = _document()
    expected = _expected(document)
    expected["expected_candidate_bindings"].pop("sole_parent_sha")
    with pytest.raises(
        p0_disposition.P0DispositionValidationError, match="unexpected shape"
    ):
        _validate(document, expected)


def test_self_seal_is_canonical_drift_detection_not_execution_or_production_authority() -> (
    None
):
    document = _document()
    assert document["self_seal"] == p0_disposition.self_seal_for(document)
    assert document["self_seal"]["proves_execution_authenticity"] is False
    assert document["self_seal"]["proves_production_authority"] is False
    document["self_seal"]["payload_sha256"] = _sha256("0")
    with pytest.raises(p0_disposition.P0DispositionValidationError, match="self-seal"):
        _validate(document)


def test_module_has_no_process_network_secret_or_dynamic_execution_capability() -> None:
    tree = ast.parse(MODULE_PATH.read_text(encoding="utf-8"))
    imported_roots: set[str] = set()
    calls: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            imported_roots.update(alias.name.split(".")[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module:
            imported_roots.add(node.module.split(".")[0])
        elif isinstance(node, ast.Call):
            if isinstance(node.func, ast.Name):
                calls.add(node.func.id)
            elif isinstance(node.func, ast.Attribute):
                calls.add(node.func.attr)
    assert imported_roots.isdisjoint(
        {"requests", "socket", "subprocess", "urllib", "http", "secrets"}
    )
    assert calls.isdisjoint(
        {"eval", "exec", "compile", "system", "popen", "run", "check_output"}
    )
