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
from app.contracts.v1.models import (
    MODEL_BY_SCHEMA,
    AgentStreamEventV4,
    GraphReconcileResponse,
    RoomGraphCommand,
)

ROOT = Path(__file__).resolve().parents[4]
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


def test_parallel_stream_projection_binds_local_cursor(codec: ContractCodec) -> None:
    fixture = json.loads(
        (FIXTURE_ROOT / "valid/agent-stream-event-v4-valid.json").read_text(
            encoding="utf-8"
        )
    )
    invalid = deepcopy(fixture["instance"])
    invalid["payload"]["next_local_index"] = 2

    with pytest.raises(ValueError, match="next_local_index"):
        codec.decode(fixture["schema"], invalid)


def test_parallel_stream_generation_reset_is_adjacent(codec: ContractCodec) -> None:
    event = {
        "schema_version": "agent-stream.v4",
        "run_id": "run-parallel-001",
        "attempt_id": "attempt-parallel-001",
        "sequence_no": 4,
        "event_type": "frame_generation_reset",
        "audience": "USER",
        "occurred_at": "2026-08-24T08:00:01Z",
        "payload": {
            "old_frame_id": "frame-dialogue-001",
            "new_frame_id": "frame-dialogue-003",
            "frame_type": "DIALOGUE_FRAME",
            "old_generation": 1,
            "new_generation": 3,
            "reason_code": "OUTPUT_SCHEMA_INVALID",
            "delivery_class": "DURABLE_CONTROL",
        },
    }

    with pytest.raises(ValueError, match="new_generation"):
        codec.decode("agent-stream-event-v4.schema.json", event)


def test_parallel_stream_uses_a_separate_model_from_v3(codec: ContractCodec) -> None:
    fixture = json.loads(
        (FIXTURE_ROOT / "valid/agent-stream-event-v4-valid.json").read_text(
            encoding="utf-8"
        )
    )

    decoded = codec.decode(fixture["schema"], fixture["instance"])

    assert isinstance(decoded, AgentStreamEventV4)


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


@pytest.mark.parametrize("provider_budget", [3, 6])
def test_parallel_intake_command_binds_signed_room_and_aggregate_budget(
    provider_budget: int,
    codec: ContractCodec,
) -> None:
    instance = _parallel_intake_command(provider_budget)

    command = codec.decode("room-graph-command.schema.json", instance)

    assert isinstance(command, RoomGraphCommand)
    assert command.room_id == "ROOM_PARALLEL_1"
    assert command.retry_budget.provider_attempts_remaining == provider_budget
    assert command.is_parallel_intake_command


@pytest.mark.parametrize(
    "mutate",
    [
        lambda value: value["retry_budget"].update(
            {"provider_attempts_remaining": 2}
        ),
        lambda value: value["retry_budget"].update(
            {"provider_attempts_remaining": 7}
        ),
        lambda value: value.pop("room_id"),
        lambda value: value.pop("event_ref"),
        lambda value: value["actor_scope"].update({"audience": "MERCHANT"}),
        lambda value: value["invocation_context"].update(
            {"agent_profile_id": "dispute-intake-officer.v1"}
        ),
    ],
)
def test_parallel_intake_command_rejects_partial_or_foreign_authority(
    mutate,
    codec: ContractCodec,
) -> None:
    instance = _parallel_intake_command(6)
    mutate(instance)

    with pytest.raises(ValueError):
        codec.decode("room-graph-command.schema.json", instance)


def test_shared_output_schema_does_not_route_a_legacy_intake_command(
    codec: ContractCodec,
) -> None:
    fixture = json.loads(
        (FIXTURE_ROOT / "valid/room-graph-command-valid.json").read_text(
            encoding="utf-8"
        )
    )
    instance = deepcopy(fixture["instance"])
    instance["invocation_context"]["output_schema_version"] = (
        "production-runtime-room-proposal-source.v2"
    )

    command = codec.decode("room-graph-command.schema.json", instance)

    assert isinstance(command, RoomGraphCommand)
    assert not command.is_parallel_intake_command


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


def _parallel_intake_command(provider_budget: int) -> dict[str, object]:
    fixture = json.loads(
        (FIXTURE_ROOT / "valid/room-graph-command-valid.json").read_text(
            encoding="utf-8"
        )
    )
    instance = deepcopy(fixture["instance"])
    instance["room_id"] = "ROOM_PARALLEL_1"
    instance["event_ref"] = {
        "artifact_id": "intake.event.parallel-1",
        "schema_version": "intake-turn-event.v2",
        "uri": "urn:intake:event:parallel-1",
        "sha256": "e" * 64,
        "size_bytes": 256,
    }
    instance["invocation_context"]["agent_profile_id"] = (
        "dispute-intake-officer.parallel-frames.v1"
    )
    instance["invocation_context"]["output_schema_version"] = (
        "production-runtime-room-proposal-source.v2"
    )
    instance["retry_budget"]["provider_attempts_remaining"] = provider_budget
    return instance


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
