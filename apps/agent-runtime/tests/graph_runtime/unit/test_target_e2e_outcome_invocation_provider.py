from __future__ import annotations

from types import SimpleNamespace
from typing import Any

import pytest

from app.contracts.v1.codec import canonical_sha256, canonical_sha256_omitting, canonicalize
from app.contracts.v1.models import RoomGraphCommand, SnapshotRef
from app.graph_runtime.target_e2e_room_adapters import TargetE2EObjectOutcomeInvocationProvider
from app.graphs.outcome import target_e2e as outcome_target_e2e
from app.graphs.outcome.errors import OutcomeReviewContractError
from app.graphs.outcome.state import packet_hash, question_hash, request_hash, version_pins
from app.schemas import ReviewCopilotRequest


ACTION_HASH = "a" * 64
EVENT_HASH = "e" * 64
SNAPSHOT_URI = "urn:target-e2e:object:review-invocation:command-review-001"


class _ImmutableObjectStore:
    def __init__(self, payload: bytes) -> None:
        self._payload = payload
        self.loaded: list[SnapshotRef] = []

    async def load(self, reference: SnapshotRef) -> bytes:
        self.loaded.append(reference)
        return self._payload


def _review_request() -> ReviewCopilotRequest:
    return ReviewCopilotRequest(
        review_id="review-task-001",
        case_id="CASE_P9_SYNTHETIC_001",
        review_packet_version=1,
        reviewer_role="PLATFORM_REVIEWER",
        question="Provide an advisory review of the frozen review packet.",
        available_fact_refs=["FACT_001"],
        available_rule_refs=["RULE_001"],
        available_draft_refs=["DRAFT_001"],
        available_deliberation_refs=["DELIBERATION_001"],
        frozen_packet={
            "packet_id": "packet-001",
            "status": "FROZEN",
        },
    )


def _actor_scope() -> dict[str, Any]:
    return {
        "actor_id": "reviewer-local",
        "actor_role": "PLATFORM_REVIEWER",
        "audience": "PLATFORM_REVIEWER",
        "capabilities": ["case:CASE_P9_SYNTHETIC_001:command:REVIEW_DECISION"],
    }


def _java_invocation_document(*, event_hash: str = EVENT_HASH) -> dict[str, Any]:
    request = _review_request()
    actor_hash = canonical_sha256(_actor_scope())
    return {
        "schema_version": "target-e2e-review-invocation.v1",
        "private_command": {
            "schema_version": "outcome-graph-command.v1",
            "authorization_schema_version": "review-packet-authorization.v1",
            "command_id": "command-review-001",
            "thread_id": f"grt.v1.{'1' * 32}",
            "tenant_surrogate": "legacy-default",
            "case_id": request.case_id,
            "review_task_id": request.review_id,
            "reviewer_actor_hash": actor_hash,
            "packet_id": "packet-001",
            "frozen_packet_ref": SNAPSHOT_URI,
            "frozen_packet_hash": packet_hash(request),
            "frozen_packet_version": request.review_packet_version,
            "action_hash": ACTION_HASH,
            "event_hash": event_hash,
            "review_task_status": "ASSIGNED",
            "review_deadline": "2026-07-30T19:30:00Z",
            "authorized_artifact_refs": {},
            "room_epoch": 0,
            "process_revision": 3,
            "fencing_token": 4,
            "fact_refs": request.available_fact_refs,
            "rule_refs": request.available_rule_refs,
            "draft_refs": request.available_draft_refs,
            "deliberation_refs": request.available_deliberation_refs,
            "question_hash": question_hash(request),
            "request_hash": request_hash(request),
            "version_pins": version_pins(),
        },
        "request": request.model_dump(mode="json"),
    }


def _execution(payload: bytes, document: dict[str, Any]) -> SimpleNamespace:
    snapshot_ref = SnapshotRef(
        artifact_id="review-invocation:command-review-001",
        schema_version="target-e2e-review-invocation.v1",
        uri=SNAPSHOT_URI,
        sha256=canonical_sha256(document),
        size_bytes=len(payload),
    )
    values: dict[str, Any] = {
        "schema_version": "room-graph-command.v1",
        "command_id": "command-review-001",
        "logical_run_id": "target-review-run:001",
        "attempt_id": "target-review-run:001:1",
        "tenant_surrogate": "legacy-default",
        "case_id": "CASE_P9_SYNTHETIC_001",
        "room_type": "REVIEW",
        "room_epoch": 0,
        "graph_key": "all-rooms.target-e2e.v1",
        "graph_version": "target-e2e-graph.2026-07-27.1",
        "checkpoint_schema_version": "target-e2e-checkpoint.v1",
        "thread_id": f"grt.v1.{'1' * 32}",
        "actor_scope": _actor_scope(),
        "process_revision": 3,
        "stage_code": "REVIEW_OUTCOME",
        "stage_sequence": 3,
        "domain_snapshot_ref": snapshot_ref.model_dump(mode="json"),
        "event_ref": {
            "artifact_id": "case-command:command-review-001",
            "schema_version": "target-e2e-review-human-decision-event.v1",
            "uri": "urn:target-e2e:event:review-decision:command-review-001",
            "sha256": EVENT_HASH,
            "size_bytes": 512,
        },
        "invocation_context": {
            "agent_profile_id": "target-e2e.all-rooms.agent.v1",
            "prompt_profile_id": "target-e2e.all-rooms.prompt.v1",
            "model_profile_id": "target-e2e.contract-blocked",
            "output_schema_version": "target-e2e-room-proposal-source.v1",
            "policy_version": "target-e2e.proposal-only.v1",
            "guardrail_version": "target-e2e.guardrails.v1",
            "tool_capabilities": [],
            "envelope_key_id": "java-invocation-es256-1",
            "envelope_nonce": "target-room-nonce:command-review-001",
        },
        "retry_budget": {
            "provider_attempts_remaining": 2,
            "activity_attempts_remaining": 3,
            "repairs_remaining": 1,
        },
        "deadline_at": "2026-07-30T19:30:00Z",
        "traceparent": f"00-{'2' * 32}-{'3' * 16}-01",
        "request_hash": "0" * 64,
    }
    values["request_hash"] = canonical_sha256_omitting(values, "request_hash")
    command = RoomGraphCommand.model_validate(values)
    return SimpleNamespace(
        admission=SimpleNamespace(
            command=command,
            thread=SimpleNamespace(actor_scope_hash=canonical_sha256(_actor_scope())),
        )
    )


def _authority(execution: SimpleNamespace) -> SimpleNamespace:
    return SimpleNamespace(execution=execution, room_fencing_token=4)


@pytest.mark.asyncio
async def test_java_review_invocation_epoch_zero_keeps_action_and_event_hashes_distinct() -> None:
    document = _java_invocation_document()
    payload = canonicalize(document)
    execution = _execution(payload, document)
    store = _ImmutableObjectStore(payload)

    loaded = await TargetE2EObjectOutcomeInvocationProvider(store).load(execution)
    outcome_target_e2e._require_loaded_invocation(_authority(execution), loaded)

    assert loaded.command.room_epoch == 0
    assert loaded.command.action_hash == ACTION_HASH
    assert loaded.command.event_hash == EVENT_HASH
    assert loaded.command.action_hash != loaded.command.event_hash
    assert loaded.event_hash == EVENT_HASH
    assert store.loaded == [execution.admission.command.domain_snapshot_ref]


@pytest.mark.asyncio
async def test_java_review_invocation_rejects_private_event_hash_drift() -> None:
    document = _java_invocation_document(event_hash="f" * 64)
    payload = canonicalize(document)
    execution = _execution(payload, document)
    store = _ImmutableObjectStore(payload)

    loaded = await TargetE2EObjectOutcomeInvocationProvider(store).load(execution)

    with pytest.raises(
        OutcomeReviewContractError,
        match="OUTCOME_TARGET_E2E_LOADED_INPUT_MISMATCH",
    ):
        outcome_target_e2e._require_loaded_invocation(_authority(execution), loaded)
