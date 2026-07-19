from __future__ import annotations

import hashlib
import json
from copy import deepcopy
from pathlib import Path

import pytest

from app.contracts.v1.codec import (
    ContractCodec,
    canonical_sha256,
    canonical_sha256_omitting,
    canonicalize,
)
from app.contracts.v1.models import MODEL_BY_SCHEMA, GraphReconcileResponse, RoomGraphCommand

ROOT = Path(__file__).resolve().parents[3]
CONTRACT_ROOT = ROOT / "contracts/agent-platform/v1"
FIXTURE_ROOT = CONTRACT_ROOT / "fixtures"


@pytest.fixture(scope="module")
def codec() -> ContractCodec:
    return ContractCodec(CONTRACT_ROOT)


@pytest.mark.parametrize("path", sorted((FIXTURE_ROOT / "valid").glob("*.json")))
def test_valid_shared_fixture_round_trips(path: Path, codec: ContractCodec) -> None:
    fixture = json.loads(path.read_text(encoding="utf-8"))
    model = codec.decode(fixture["schema"], fixture["instance"])

    assert isinstance(model, MODEL_BY_SCHEMA[fixture["schema"]])
    encoded = codec.encode(fixture["schema"], model)
    assert encoded == fixture["instance"]


@pytest.mark.parametrize("path", sorted((FIXTURE_ROOT / "invalid").glob("*.json")))
def test_invalid_shared_fixture_fails_closed(path: Path, codec: ContractCodec) -> None:
    fixture = json.loads(path.read_text(encoding="utf-8"))

    with pytest.raises(ValueError):
        codec.decode(fixture["schema"], fixture["instance"])


def test_unknown_schema_file_fails_closed(codec: ContractCodec) -> None:
    with pytest.raises(ValueError, match="unknown contract schema"):
        codec.decode("room-graph-command.v99.schema.json", {})


def test_valid_room_graph_command_uses_opaque_identity_and_exact_self_hash(
    codec: ContractCodec,
) -> None:
    fixture = json.loads(
        (FIXTURE_ROOT / "valid/room-graph-command-valid.json").read_text(encoding="utf-8")
    )
    command = codec.decode(fixture["schema"], fixture["instance"])

    assert isinstance(command, RoomGraphCommand)
    assert command.thread_id == "grt.v1.019bdf9f4a7279d3a23b7fd5c1e4a901"
    assert canonical_sha256_omitting(command, "request_hash") == command.request_hash


def test_duplicate_json_member_fails_before_contract_or_hash_use(
    codec: ContractCodec,
) -> None:
    fixture = json.loads(
        (FIXTURE_ROOT / "valid/room-graph-command-valid.json").read_text(encoding="utf-8")
    )
    raw = json.dumps(fixture["instance"], separators=(",", ":"))
    duplicate = raw.replace(
        '"command_id":"graph-cmd-001",',
        '"command_id":"graph-cmd-forged","command_id":"graph-cmd-001",',
        1,
    )

    with pytest.raises(ValueError, match="duplicate JSON member: command_id"):
        codec.decode(fixture["schema"], duplicate)


def test_schema_valid_shape_still_respects_total_payload_limit(codec: ContractCodec) -> None:
    fixture = json.loads(
        (FIXTURE_ROOT / "valid/room-graph-result-valid.json").read_text(encoding="utf-8")
    )
    instance = fixture["instance"]
    proposal = deepcopy(instance["public_event_proposals"][0])
    proposal["payload_ref"] = "s3://bucket/" + "a" * 980
    instance["public_event_proposals"] = [deepcopy(proposal) for _ in range(100)]

    with pytest.raises(ValueError, match="exceeds max_serialized_bytes"):
        codec.decode(fixture["schema"], instance)


def test_reconcile_wrapper_must_match_its_nested_immutable_result(
    codec: ContractCodec,
) -> None:
    fixture = json.loads(
        (FIXTURE_ROOT / "valid/graph-reconcile-response-valid.json").read_text(encoding="utf-8")
    )
    instance = deepcopy(fixture["instance"])
    instance["result"]["attempt_id"] = "attempt-forged"

    with pytest.raises(ValueError, match="nested result"):
        codec.decode(fixture["schema"], instance)

    decoded = codec.decode(fixture["schema"], fixture["instance"])
    assert isinstance(decoded, GraphReconcileResponse)
    assert decoded.result.output_hash == decoded.result_hash


@pytest.mark.parametrize("path", sorted((FIXTURE_ROOT / "canonical-hash").glob("*.json")))
def test_rfc8785_vectors_match_shared_bytes_and_hash(path: Path) -> None:
    fixture = json.loads(path.read_text(encoding="utf-8"))

    canonical = canonicalize(fixture["input"])
    assert canonical.decode("utf-8") == fixture["canonical_utf8"]
    assert canonical_sha256(fixture["input"]) == fixture["sha256"]
    assert hashlib.sha256(canonical).hexdigest() == fixture["sha256"]
