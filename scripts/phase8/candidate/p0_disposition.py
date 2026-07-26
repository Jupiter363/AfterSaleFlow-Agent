from __future__ import annotations

import copy
import hashlib
import json
import os
import re
import stat
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping, Sequence

from jsonschema import Draft202012Validator, FormatChecker

from scripts.phase8.candidate.github_attestation import (
    calculate_attestation_composite_sha256 as calculate_verified_attestation_composite_sha256,
)


ROOT = Path(__file__).resolve().parents[3]
SCHEMA_PATH = (
    ROOT / "contracts/agent-platform/phase8/engineering-p0-disposition.schema.json"
)
SCHEMA_VERSION = "phase8-engineering-p0-disposition.v1"
CONTRACT_KIND = "PHASE8_ENGINEERING_CANDIDATE_P0_DISPOSITION"
AUTHORITY_CEILING = "PHASE_8_ENGINEERING_CHECKPOINT_ONLY"
EXPECTED_REPOSITORY = "Jupiter363/AfterSaleFlow-Agent"
EXPECTED_REPOSITORY_ID = "1282437633"
SELF_SEAL_CANONICALIZATION = "JSON_SORT_KEYS_COMPACT_UTF8_V1"
ATTESTATION_RECEIPT_SCHEMA_VERSION = "phase8-github-attestation-receipt.v1"
COMMAND_CONTRACT_PATH = (
    "contracts/agent-platform/phase8/engineering-candidate-commands.json"
)
TRUSTED_WORKFLOW_PATH = ".github/workflows/phase8-engineering-witness.yml"
LANE_ORDER = ("consolidated",)
LANE_TOPICS = {
    "consolidated": (
        "active-reference-authority",
        "scheduler-lifecycle",
        "capacity-admission",
        "recovery-rotation",
        "no-production-activation",
        "v046-additive-only",
        "stream-compatibility",
        "retention-archive",
        "redis-hint-only",
        "cleanup-eligibility",
        "topology-policy",
        "observability-privacy",
        "external-gate-fail-closed",
        "test-runtime-supply-chain",
        "evidence-attestation",
    ),
}
REVIEW_TOPICS = tuple(topic for lane in LANE_ORDER for topic in LANE_TOPICS[lane])
PRODUCTION_CAPABILITY_KEYS = (
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
)
MAX_DOCUMENT_BYTES = 512 * 1024
MAX_JSON_DEPTH = 24
MAX_JSON_NODES = 32_768
MAX_STRING_BYTES = 8192
SCHEMA_SHA256 = "3833b674d3151d72ffb884881ccce9fd95c4bcfc04d67ca67c594c89e5494733"

_ROOT_FIELDS = frozenset(
    {
        "additional_fields",
        "attestation_composite_sha256",
        "authority",
        "candidate",
        "command_contract",
        "contract_kind",
        "open_p0_count",
        "producer_identity",
        "review_lanes",
        "review_scope",
        "reviewed_topics",
        "repository",
        "repository_id",
        "schema_version",
        "self_approved",
        "self_seal",
        "status",
        "trusted_builder",
        "witness_artifact",
    }
)

_CANDIDATE_FIELDS = frozenset(
    {
        "accepted_a8_sha",
        "changed_inventory",
        "changed_inventory_sha256",
        "commit_sha",
        "diff_sha256",
        "scope_inventory_sha256",
        "sole_parent_sha",
        "tree_sha",
    }
)
_COMMAND_CONTRACT_FIELDS = frozenset(
    {"git_blob_sha", "path", "payload_sha256", "sha256"}
)
_TRUSTED_BUILDER_FIELDS = frozenset(
    {
        "trusted_code_blob_bundle",
        "trusted_code_sha",
        "trusted_code_tree_sha",
        "trusted_workflow_blob",
        "trusted_workflow_sha",
        "trusted_workflow_tree_sha",
    }
)
_ATTESTATION_RECEIPT_FIELDS = frozenset(
    {
        "MIG-006",
        "MIG-007",
        "MIG-008",
        "acceptance_key",
        "accepted",
        "accepted_a8_sha",
        "artifact",
        "attestation",
        "attestation_composite_sha256",
        "authority_ceiling",
        "caller_workflow_binding",
        "candidate_sha",
        "candidate_tree_sha",
        "command_artifact_set_sha256",
        "command_contract_payload_sha256",
        "event",
        "ledger_durability",
        "production_authority",
        "production_promotion",
        "repository",
        "repository_id",
        "run_attempt",
        "run_id",
        "schema_version",
        "signer_workflow",
        "source_ref",
        "scope_inventory_sha256",
        "trusted_code_sha",
        "trusted_code_tree_sha",
        "trusted_transition_sha256",
        "trusted_workflow_sha",
        "trusted_workflow_tree_sha",
    }
)


class P0DispositionValidationError(ValueError):
    """Raised when an engineering P0 disposition is ambiguous or untrusted."""


def canonical_json_bytes(value: Any) -> bytes:
    try:
        return json.dumps(
            value,
            allow_nan=False,
            ensure_ascii=True,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
    except (TypeError, ValueError) as exception:
        raise P0DispositionValidationError(
            f"P0 disposition is not canonical JSON: {exception}"
        ) from exception


def canonical_sha256(value: Any) -> str:
    return hashlib.sha256(canonical_json_bytes(value)).hexdigest()


def disposition_payload_sha256(document: Mapping[str, Any]) -> str:
    if type(document) is not dict:
        raise P0DispositionValidationError(
            "P0 disposition payload must be one plain JSON object"
        )
    _assert_bounded_value(document, context="P0 disposition payload")
    payload = copy.deepcopy(document)
    payload.pop("self_seal", None)
    return canonical_sha256(payload)


def self_seal_for(document: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "algorithm": "SHA-256",
        "canonicalization": SELF_SEAL_CANONICALIZATION,
        "payload_sha256": disposition_payload_sha256(document),
        "proves_execution_authenticity": False,
        "proves_production_authority": False,
    }


def seal_p0_disposition(document: Mapping[str, Any]) -> dict[str, Any]:
    if type(document) is not dict:
        raise P0DispositionValidationError(
            "P0 disposition seal input must be one plain JSON object"
        )
    _assert_bounded_value(document, context="P0 disposition seal input")
    sealed = copy.deepcopy(document)
    sealed.pop("self_seal", None)
    sealed["self_seal"] = self_seal_for(sealed)
    return sealed


def _reject_duplicate_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise P0DispositionValidationError(
                f"duplicate JSON property rejected: {key!r}"
            )
        result[key] = value
    return result


def _assert_bounded_tree(value: Any, *, context: str = "P0 disposition") -> None:
    nodes = 0
    minimum_bytes = 0
    stack = [(value, 1)]
    while stack:
        current, depth = stack.pop()
        nodes += 1
        if nodes > MAX_JSON_NODES:
            raise P0DispositionValidationError(f"{context} exceeds node limit")
        if depth > MAX_JSON_DEPTH:
            raise P0DispositionValidationError(f"{context} exceeds depth limit")
        if isinstance(current, str):
            encoded_length = len(current.encode("utf-8"))
            if encoded_length > MAX_STRING_BYTES:
                raise P0DispositionValidationError(
                    f"{context} contains an oversized string"
                )
            minimum_bytes += encoded_length + 2
        elif type(current) is dict:
            for key, item in current.items():
                if type(key) is not str:
                    raise P0DispositionValidationError(
                        f"{context} contains a non-string property name"
                    )
                if len(key.encode("utf-8")) > MAX_STRING_BYTES:
                    raise P0DispositionValidationError(
                        f"{context} contains an oversized property name"
                    )
                minimum_bytes += len(key.encode("utf-8")) + 3
                stack.append((item, depth + 1))
        elif type(current) is list:
            stack.extend((item, depth + 1) for item in current)
        elif current is None or type(current) in {bool, int, float}:
            minimum_bytes += 1
        else:
            raise P0DispositionValidationError(f"{context} contains a non-JSON value")
        minimum_bytes += 1
        if minimum_bytes > MAX_DOCUMENT_BYTES:
            raise P0DispositionValidationError(f"{context} exceeds byte limit")


def _assert_bounded_value(value: Any, *, context: str) -> None:
    _assert_bounded_tree(value, context=context)
    if len(canonical_json_bytes(value)) > MAX_DOCUMENT_BYTES:
        raise P0DispositionValidationError(f"{context} exceeds byte limit")


def parse_bounded_json_bytes(raw: bytes) -> dict[str, Any]:
    if type(raw) is not bytes:
        raise P0DispositionValidationError("P0 disposition input must be bytes")
    if not raw or len(raw) > MAX_DOCUMENT_BYTES:
        raise P0DispositionValidationError(
            "P0 disposition byte length is outside the fixed bounds"
        )
    if raw.startswith((b"\xef\xbb\xbf", b"\xff\xfe", b"\xfe\xff")):
        raise P0DispositionValidationError("P0 disposition must be BOM-free UTF-8")
    try:
        document = json.loads(
            raw.decode("utf-8", errors="strict"),
            object_pairs_hook=_reject_duplicate_pairs,
            parse_constant=lambda token: (_ for _ in ()).throw(
                P0DispositionValidationError(
                    f"non-finite JSON number rejected: {token}"
                )
            ),
        )
    except P0DispositionValidationError:
        raise
    except (RecursionError, UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise P0DispositionValidationError(
            "P0 disposition is not strict UTF-8 JSON"
        ) from exception
    if not isinstance(document, dict):
        raise P0DispositionValidationError("P0 disposition root must be an object")
    _assert_bounded_tree(document)
    return document


def _file_identity(metadata: os.stat_result) -> tuple[int, int, int, int, int]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_size,
        metadata.st_mtime_ns,
    )


def load_schema(path: Path = SCHEMA_PATH) -> dict[str, Any]:
    if path != SCHEMA_PATH:
        raise P0DispositionValidationError("only the repository schema may be loaded")
    try:
        before = path.lstat()
    except OSError as exception:
        raise P0DispositionValidationError(
            "P0 disposition schema cannot be inspected"
        ) from exception
    if (
        stat.S_ISLNK(before.st_mode)
        or not stat.S_ISREG(before.st_mode)
        or before.st_size <= 0
        or before.st_size > MAX_DOCUMENT_BYTES
    ):
        raise P0DispositionValidationError(
            "P0 disposition schema must be one bounded regular file"
        )
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
        try:
            opened = os.fstat(descriptor)
            if _file_identity(opened) != _file_identity(before):
                raise P0DispositionValidationError(
                    "P0 disposition schema identity changed before open"
                )
            raw = os.read(descriptor, MAX_DOCUMENT_BYTES + 1)
            if len(raw) != before.st_size or os.read(descriptor, 1):
                raise P0DispositionValidationError(
                    "P0 disposition schema size changed while reading"
                )
        finally:
            os.close(descriptor)
        after = path.lstat()
    except P0DispositionValidationError:
        raise
    except OSError as exception:
        raise P0DispositionValidationError(
            "P0 disposition schema cannot be read without following links"
        ) from exception
    if _file_identity(after) != _file_identity(before):
        raise P0DispositionValidationError(
            "P0 disposition schema identity changed after read"
        )
    if hashlib.sha256(raw).hexdigest() != SCHEMA_SHA256:
        raise P0DispositionValidationError(
            "P0 disposition schema differs from the trusted frozen bytes"
        )
    schema = parse_bounded_json_bytes(raw)
    try:
        Draft202012Validator.check_schema(schema)
    except Exception as exception:
        raise P0DispositionValidationError(
            "P0 disposition schema is not valid Draft 2020-12"
        ) from exception
    return schema


def _schema_error(document: Mapping[str, Any]) -> str | None:
    validator = Draft202012Validator(load_schema(), format_checker=FormatChecker())
    errors = sorted(
        validator.iter_errors(document),
        key=lambda error: tuple(str(part) for part in error.absolute_path),
    )
    if not errors:
        return None
    error = errors[0]
    path = ".".join(str(part) for part in error.absolute_path) or "<root>"
    return f"schema violation at {path}: {error.message}"


def _copy_expected(
    value: Mapping[str, Any], *, context: str, exact_fields: frozenset[str]
) -> dict[str, Any]:
    if type(value) is not dict:
        raise P0DispositionValidationError(
            f"independently derived {context} must be one plain JSON object"
        )
    _assert_bounded_value(value, context=f"independently derived {context}")
    if set(value) != exact_fields:
        raise P0DispositionValidationError(
            f"independently derived {context} has an unexpected shape"
        )
    copied = copy.deepcopy(value)
    return copied


def _parse_reviewed_at(value: str, *, lane: str) -> datetime:
    normalized = value[:-1] + "+00:00" if value.endswith("Z") else value
    try:
        parsed = datetime.fromisoformat(normalized)
    except ValueError as exception:
        raise P0DispositionValidationError(
            f"{lane} reviewed_at is not RFC 3339"
        ) from exception
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise P0DispositionValidationError(
            f"{lane} reviewed_at must include a timezone offset"
        )
    return parsed.astimezone(timezone.utc)


def _assert_candidate_inventory(candidate: Mapping[str, Any]) -> None:
    inventory = candidate["changed_inventory"]
    paths = [item["path"] for item in inventory]
    if paths != sorted(paths):
        raise P0DispositionValidationError(
            "candidate changed inventory must use canonical path order"
        )
    if len({path.casefold() for path in paths}) != len(paths):
        raise P0DispositionValidationError(
            "candidate changed inventory contains a path collision"
        )
    if candidate["changed_inventory_sha256"] != canonical_sha256(inventory):
        raise P0DispositionValidationError(
            "candidate changed inventory hash does not match its exact entries"
        )


def _assert_trusted_builder(builder: Mapping[str, Any]) -> None:
    bundle = builder["trusted_code_blob_bundle"]
    blobs = bundle["blobs"]
    paths = [item["path"] for item in blobs]
    if paths != sorted(paths):
        raise P0DispositionValidationError(
            "trusted-builder blob bundle must use canonical path order"
        )
    if len({path.casefold() for path in paths}) != len(paths):
        raise P0DispositionValidationError(
            "trusted-builder blob bundle contains a path collision"
        )
    if bundle["sha256"] != canonical_sha256(blobs):
        raise P0DispositionValidationError("trusted-builder blob bundle hash drifted")
    workflow = builder["trusted_workflow_blob"]
    if workflow["path"] != TRUSTED_WORKFLOW_PATH:
        raise P0DispositionValidationError("trusted workflow signer blob path drifted")


def _assert_sha(value: Any, context: str) -> None:
    if not isinstance(value, str) or re.fullmatch(r"[0-9a-f]{40}", value) is None:
        raise P0DispositionValidationError(f"{context} must be one Git SHA")


def _assert_sha256(value: Any, context: str) -> None:
    if not isinstance(value, str) or re.fullmatch(r"[0-9a-f]{64}", value) is None:
        raise P0DispositionValidationError(f"{context} must be one SHA-256")


def _assert_exact_object(
    value: Any, expected_fields: set[str], context: str
) -> Mapping[str, Any]:
    if type(value) is not dict or set(value) != expected_fields:
        raise P0DispositionValidationError(f"{context} has an unexpected shape")
    return value


def _assert_github_attestation_receipt(receipt: Mapping[str, Any]) -> None:
    artifact = _assert_exact_object(
        receipt["artifact"],
        {"id", "name", "sha256", "subject_filename"},
        "GitHub attestation artifact",
    )
    attestation = _assert_exact_object(
        receipt["attestation"],
        {
            "bundle_sha256",
            "certificate_sha256",
            "offline_verified",
            "online_verified",
            "predicate_authority",
            "predicate_sha256",
            "trusted_root_sha256",
            "verified_timestamps",
        },
        "GitHub attestation verification",
    )
    caller = _assert_exact_object(
        receipt["caller_workflow_binding"],
        {"file_sha256", "git_blob_sha1", "mode", "path", "trusted_workflow_sha"},
        "GitHub caller workflow binding",
    )
    for field in (
        "accepted_a8_sha",
        "candidate_sha",
        "candidate_tree_sha",
        "trusted_code_sha",
        "trusted_code_tree_sha",
        "trusted_workflow_sha",
        "trusted_workflow_tree_sha",
    ):
        _assert_sha(receipt[field], f"GitHub attestation receipt.{field}")
    for field in (
        "acceptance_key",
        "attestation_composite_sha256",
        "command_artifact_set_sha256",
        "command_contract_payload_sha256",
        "scope_inventory_sha256",
        "trusted_transition_sha256",
    ):
        _assert_sha256(receipt[field], f"GitHub attestation receipt.{field}")
    for field in ("file_sha256",):
        _assert_sha256(caller[field], f"GitHub caller workflow binding.{field}")
    _assert_sha(caller["git_blob_sha1"], "GitHub caller workflow binding.git_blob_sha1")
    for field in (
        "bundle_sha256",
        "certificate_sha256",
        "predicate_sha256",
        "trusted_root_sha256",
    ):
        _assert_sha256(attestation[field], f"GitHub attestation verification.{field}")
    _assert_sha256(artifact["sha256"], "GitHub attestation artifact.sha256")
    verified_composite = calculate_verified_attestation_composite_sha256(
        candidate_sha=receipt["candidate_sha"],
        candidate_tree_sha=receipt["candidate_tree_sha"],
        accepted_a8_sha=receipt["accepted_a8_sha"],
        scope_inventory_sha256=receipt["scope_inventory_sha256"],
        command_contract_payload_sha256=receipt["command_contract_payload_sha256"],
        artifact_subject_sha256=artifact["sha256"],
        caller_workflow_file_sha256=caller["file_sha256"],
        caller_workflow_git_blob_sha1=caller["git_blob_sha1"],
        command_artifact_set_sha256=receipt["command_artifact_set_sha256"],
        trusted_code_sha=receipt["trusted_code_sha"],
        trusted_code_tree_sha=receipt["trusted_code_tree_sha"],
        trusted_transition_sha256=receipt["trusted_transition_sha256"],
        trusted_workflow_sha=receipt["trusted_workflow_sha"],
        trusted_workflow_tree_sha=receipt["trusted_workflow_tree_sha"],
        run_id=receipt["run_id"],
        run_attempt=receipt["run_attempt"],
    )
    if verified_composite != receipt["attestation_composite_sha256"]:
        raise P0DispositionValidationError(
            "GitHub attestation composite differs from its verified receipt identity"
        )
    if (
        receipt["schema_version"] != ATTESTATION_RECEIPT_SCHEMA_VERSION
        or receipt["accepted"] is not True
        or receipt["authority_ceiling"] != AUTHORITY_CEILING
        or receipt["event"] != "push"
        or receipt["production_authority"] is not False
        or receipt["production_promotion"] != "FORBIDDEN"
        or any(
            receipt[gate] != "PENDING_PROMOTION"
            for gate in ("MIG-006", "MIG-007", "MIG-008")
        )
        or receipt["repository"] != EXPECTED_REPOSITORY
        or receipt["repository_id"] != EXPECTED_REPOSITORY_ID
        or receipt["signer_workflow"]
        != f"{EXPECTED_REPOSITORY}/{TRUSTED_WORKFLOW_PATH}"
        or receipt["source_ref"] != "refs/heads/codex/p8-production-hardening"
        or type(receipt["run_id"]) is not int
        or receipt["run_id"] <= 0
        or type(receipt["run_attempt"]) is not int
        or receipt["run_attempt"] <= 0
        or type(artifact["id"]) is not int
        or artifact["id"] <= 0
        or artifact["name"]
        != f"phase8-engineering-witness-{receipt['run_id']}-{receipt['run_attempt']}"
        or artifact["subject_filename"] != "phase8-engineering-witness.tar"
        or caller["mode"] != "100644"
        or caller["path"] != ".github/workflows/phase8-engineering-caller.yml"
        or caller["trusted_workflow_sha"] != receipt["trusted_workflow_sha"]
        or attestation["online_verified"] is not True
        or attestation["offline_verified"] is not True
        or attestation["predicate_authority"]
        != "BUILDER_CONTROLLED_UNTRUSTED_UNTIL_LOCAL_RECOMPUTE"
        or not isinstance(attestation["verified_timestamps"], list)
        or not attestation["verified_timestamps"]
    ):
        raise P0DispositionValidationError(
            "GitHub attestation receipt identity or authority drifted"
        )


def _expected_lane_bindings(
    candidate: Mapping[str, Any],
    command_contract: Mapping[str, Any],
    trusted_builder: Mapping[str, Any],
    witness: Mapping[str, Any],
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


def _assert_authority_ceiling(authority: Mapping[str, Any]) -> None:
    expected = {
        "authority_ceiling": AUTHORITY_CEILING,
        "capabilities": {key: False for key in PRODUCTION_CAPABILITY_KEYS},
        "cryptographic_production_attestation": False,
        "migrations": {
            "MIG-006": "PENDING_PROMOTION",
            "MIG-007": "PENDING_PROMOTION",
            "MIG-008": "PENDING_PROMOTION",
        },
        "production_reuse": "FORBIDDEN",
    }
    if authority != expected:
        raise P0DispositionValidationError(
            "engineering disposition exceeded its production authority ceiling"
        )


def validate_p0_disposition(
    document: Mapping[str, Any],
    *,
    expected_candidate_bindings: Mapping[str, Any],
    expected_command_contract_binding: Mapping[str, Any],
    expected_trusted_builder_binding: Mapping[str, Any],
    expected_github_attestation_receipt: Mapping[str, Any],
    expected_closed_finding_ids_by_lane: Mapping[str, Sequence[str]],
    expected_reviewer_identities_by_lane: Mapping[str, str],
    expected_reviewed_at_by_lane: Mapping[str, str],
    expected_producer_identity: str,
) -> dict[str, Any]:
    """Validate against bindings independently derived by the trusted caller.

    None of the candidate, builder, report, attestation, or finding expectations
    are derived from the submitted disposition itself.
    """

    if type(document) is not dict:
        raise P0DispositionValidationError(
            "P0 disposition must be one plain JSON object"
        )
    _assert_bounded_value(document, context="P0 disposition")
    if set(document) != _ROOT_FIELDS:
        raise P0DispositionValidationError(
            "P0 disposition root violates the schema-independent exact-key set"
        )

    candidate = _copy_expected(
        expected_candidate_bindings,
        context="candidate bindings",
        exact_fields=_CANDIDATE_FIELDS,
    )
    command_contract = _copy_expected(
        expected_command_contract_binding,
        context="command contract binding",
        exact_fields=_COMMAND_CONTRACT_FIELDS,
    )
    trusted_builder = _copy_expected(
        expected_trusted_builder_binding,
        context="trusted-builder binding",
        exact_fields=_TRUSTED_BUILDER_FIELDS,
    )
    witness = _copy_expected(
        expected_github_attestation_receipt,
        context="GitHub attestation receipt",
        exact_fields=_ATTESTATION_RECEIPT_FIELDS,
    )
    _assert_github_attestation_receipt(witness)
    if type(expected_closed_finding_ids_by_lane) is not dict:
        raise P0DispositionValidationError(
            "independently derived closed finding IDs must be one plain object"
        )
    _assert_bounded_value(
        expected_closed_finding_ids_by_lane,
        context="independently derived closed finding IDs",
    )
    if set(expected_closed_finding_ids_by_lane) != set(LANE_ORDER):
        raise P0DispositionValidationError(
            "independently derived closed finding IDs must cover every lane exactly"
        )
    if type(expected_reviewer_identities_by_lane) is not dict:
        raise P0DispositionValidationError(
            "independently derived reviewer identities must be one plain object"
        )
    _assert_bounded_value(
        expected_reviewer_identities_by_lane,
        context="independently derived reviewer identities",
    )
    if set(expected_reviewer_identities_by_lane) != set(LANE_ORDER):
        raise P0DispositionValidationError(
            "independently derived reviewer identities must cover every lane exactly"
        )
    if type(expected_reviewed_at_by_lane) is not dict:
        raise P0DispositionValidationError(
            "independently derived review timestamps must be one plain object"
        )
    _assert_bounded_value(
        expected_reviewed_at_by_lane,
        context="independently derived review timestamps",
    )
    if set(expected_reviewed_at_by_lane) != set(LANE_ORDER):
        raise P0DispositionValidationError(
            "independently derived review timestamps must cover every lane exactly"
        )
    if type(expected_producer_identity) is not str:
        raise P0DispositionValidationError(
            "independently derived producer identity must be a string"
        )
    _assert_bounded_value(
        expected_producer_identity,
        context="independently derived producer identity",
    )

    candidate_document = copy.deepcopy(document)
    error = _schema_error(candidate_document)
    if error:
        raise P0DispositionValidationError(error)

    if candidate_document["schema_version"] != SCHEMA_VERSION:
        raise P0DispositionValidationError("P0 disposition schema version drifted")
    if candidate_document["contract_kind"] != CONTRACT_KIND:
        raise P0DispositionValidationError("P0 disposition contract kind drifted")
    if candidate_document["candidate"] != candidate:
        raise P0DispositionValidationError(
            "candidate bindings differ from the independently derived scope"
        )
    if candidate_document["command_contract"] != command_contract:
        raise P0DispositionValidationError(
            "command contract differs from the independently derived blob"
        )
    if command_contract["path"] != COMMAND_CONTRACT_PATH:
        raise P0DispositionValidationError("command contract path drifted")
    if candidate_document["trusted_builder"] != trusted_builder:
        raise P0DispositionValidationError(
            "T8 trusted-builder bindings differ from the independently derived source"
        )
    if candidate_document["witness_artifact"] != witness:
        raise P0DispositionValidationError(
            "witness, report, or attestation binding differs from trusted intake"
        )
    if witness["repository"] != EXPECTED_REPOSITORY:
        raise P0DispositionValidationError(
            "GitHub witness repository differs from the fixed trusted repository"
        )
    if witness["repository_id"] != EXPECTED_REPOSITORY_ID:
        raise P0DispositionValidationError(
            "GitHub witness repository ID differs from the fixed immutable identity"
        )
    if witness["signer_workflow"] != f"{EXPECTED_REPOSITORY}/{TRUSTED_WORKFLOW_PATH}":
        raise P0DispositionValidationError(
            "GitHub witness signer workflow path drifted"
        )
    if candidate_document["repository"] != EXPECTED_REPOSITORY:
        raise P0DispositionValidationError(
            "top-level GitHub repository differs from the fixed trusted repository"
        )
    if candidate_document["repository_id"] != EXPECTED_REPOSITORY_ID:
        raise P0DispositionValidationError(
            "top-level GitHub repository ID differs from the fixed immutable identity"
        )
    if (
        candidate_document["attestation_composite_sha256"]
        != witness["attestation_composite_sha256"]
    ):
        raise P0DispositionValidationError(
            "top-level attestation composite differs from trusted intake"
        )
    receipt_cross_bindings = {
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
    if any(
        witness[field] != expected for field, expected in receipt_cross_bindings.items()
    ):
        raise P0DispositionValidationError(
            "GitHub attestation receipt differs from candidate or trusted-builder intake"
        )
    if candidate_document["producer_identity"] != expected_producer_identity:
        raise P0DispositionValidationError(
            "producer identity differs from the independently derived identity"
        )

    _assert_candidate_inventory(candidate_document["candidate"])
    _assert_trusted_builder(candidate_document["trusted_builder"])
    if candidate_document["reviewed_topics"] != list(REVIEW_TOPICS):
        raise P0DispositionValidationError(
            "engineering review must cover the exact frozen 15-topic inventory"
        )
    _assert_authority_ceiling(candidate_document["authority"])

    lanes = candidate_document["review_lanes"]
    if [lane["lane"] for lane in lanes] != list(LANE_ORDER):
        raise P0DispositionValidationError(
            "engineering P0 lanes must appear exactly once in fixed order"
        )
    expected_bindings = _expected_lane_bindings(
        candidate,
        command_contract,
        trusted_builder,
        witness,
    )
    producer = expected_producer_identity.casefold()
    reviewer_identities = [lane["reviewer_identity"].casefold() for lane in lanes]
    if producer in reviewer_identities:
        raise P0DispositionValidationError(
            "disposition producer cannot review its own engineering evidence"
        )
    if len(set(reviewer_identities)) != len(LANE_ORDER):
        raise P0DispositionValidationError(
            "engineering review lanes require unique reviewer identities"
        )

    for lane in lanes:
        lane_id = lane["lane"]
        if lane["reviewed_topics"] != list(LANE_TOPICS[lane_id]):
            raise P0DispositionValidationError(
                f"{lane_id} does not cover its exact frozen topic partition"
            )
        expected_findings = expected_closed_finding_ids_by_lane[lane_id]
        if isinstance(expected_findings, (str, bytes)) or not isinstance(
            expected_findings, Sequence
        ):
            raise P0DispositionValidationError(
                f"independently derived closed findings for {lane_id} are invalid"
            )
        expected_finding_list = list(expected_findings)
        if expected_finding_list != sorted(expected_finding_list) or len(
            set(expected_finding_list)
        ) != len(expected_finding_list):
            raise P0DispositionValidationError(
                f"independently derived closed findings for {lane_id} are not canonical"
            )
        if lane["closed_finding_ids"] != expected_finding_list:
            raise P0DispositionValidationError(
                f"{lane_id} closed finding IDs differ from trusted review state"
            )
        if lane["reviewer_identity"] != expected_reviewer_identities_by_lane[lane_id]:
            raise P0DispositionValidationError(
                f"{lane_id} reviewer identity differs from trusted review state"
            )
        if lane["reviewed_at"] != expected_reviewed_at_by_lane[lane_id]:
            raise P0DispositionValidationError(
                f"{lane_id} review timestamp differs from trusted review state"
            )
        if lane["bindings"] != expected_bindings:
            raise P0DispositionValidationError(
                f"{lane_id} is not bound to the exact candidate and witness"
            )
        _parse_reviewed_at(lane["reviewed_at"], lane=lane_id)

    if candidate_document["self_approved"] is not False or any(
        lane["self_approved"] is not False for lane in lanes
    ):
        raise P0DispositionValidationError("self approval is forbidden")
    if candidate_document["status"] != "ALL_P0_CLOSED" or any(
        lane["status"] != "ALL_P0_CLOSED" for lane in lanes
    ):
        raise P0DispositionValidationError("every engineering P0 lane must be closed")
    if candidate_document["open_p0_count"] != 0 or any(
        lane["open_p0_count"] != 0 for lane in lanes
    ):
        raise P0DispositionValidationError(
            "open P0 findings forbid disposition acceptance"
        )
    expected_seal = self_seal_for(candidate_document)
    if candidate_document["self_seal"] != expected_seal:
        raise P0DispositionValidationError("canonical P0 disposition self-seal drifted")
    return candidate_document


def parse_and_validate_p0_disposition(
    raw: bytes,
    **expected_bindings: Any,
) -> dict[str, Any]:
    return validate_p0_disposition(
        parse_bounded_json_bytes(raw),
        **expected_bindings,
    )


__all__ = [
    "ATTESTATION_RECEIPT_SCHEMA_VERSION",
    "AUTHORITY_CEILING",
    "COMMAND_CONTRACT_PATH",
    "CONTRACT_KIND",
    "EXPECTED_REPOSITORY",
    "EXPECTED_REPOSITORY_ID",
    "LANE_ORDER",
    "LANE_TOPICS",
    "MAX_DOCUMENT_BYTES",
    "MAX_JSON_DEPTH",
    "MAX_JSON_NODES",
    "MAX_STRING_BYTES",
    "P0DispositionValidationError",
    "PRODUCTION_CAPABILITY_KEYS",
    "REVIEW_TOPICS",
    "SCHEMA_PATH",
    "SCHEMA_SHA256",
    "SCHEMA_VERSION",
    "TRUSTED_WORKFLOW_PATH",
    "canonical_json_bytes",
    "canonical_sha256",
    "disposition_payload_sha256",
    "load_schema",
    "parse_and_validate_p0_disposition",
    "parse_bounded_json_bytes",
    "seal_p0_disposition",
    "self_seal_for",
    "validate_p0_disposition",
]
