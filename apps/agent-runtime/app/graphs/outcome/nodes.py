from __future__ import annotations

import json
import re
from collections.abc import Mapping
from typing import Any, cast

from langgraph.runtime import Runtime

from app.harness.guardrails import GuardrailChecker
from app.graphs.outcome.errors import OutcomeReviewContractError
from app.graphs.outcome.lcel import invoke_outcome_review_lcel
from app.graphs.outcome.state import (
    MAX_OUTCOME_REVIEW_ENCODED_BYTES,
    OutcomeReviewGraphStateV1,
    OutcomeReviewInvocation,
    OutcomeReviewProjection,
    answer_hash,
    canonical_sha256,
    new_outcome_review_state,
)
from app.schemas import ReviewCopilotAnswer


_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_PERSISTED_FIELDS = frozenset(
    {
        "schema_version",
        "graph_identity",
        "version_pins",
        "command_binding",
        "scope_binding",
        "request_hash",
        "question_hash",
        "status",
        "cognitive_revision",
        "route",
        "advisory_hash",
        "citation_refs",
        "result_hash",
    }
)
_TRANSIENT_FIELDS = frozenset({"advisory", "projection"})


def validate_scope_packet(
    state: OutcomeReviewGraphStateV1,
    runtime: Runtime[OutcomeReviewInvocation],
) -> dict[str, Any]:
    if set(state) - (_PERSISTED_FIELDS | _TRANSIENT_FIELDS):
        raise OutcomeReviewContractError("OUTCOME_REVIEW_STATE_FIELDS_INVALID")
    if state.get("status") != "PENDING" or state.get("route") is not None:
        raise OutcomeReviewContractError("OUTCOME_REVIEW_STATE_TRANSITION_INVALID")
    context = runtime.context
    if context is None or not callable(context.answerer) or not callable(context.validate_answer):
        raise OutcomeReviewContractError("OUTCOME_REVIEW_INVOCATION_CONTEXT_INVALID")
    GuardrailChecker().assert_safe_input(context.request.question)
    command = _command_from_state(state)
    if context.reviewer_actor_hash != command.reviewer_actor_hash:
        raise OutcomeReviewContractError("OUTCOME_REVIEW_REVIEWER_BINDING_MISMATCH")
    expected = new_outcome_review_state(command=command, request=context.request)
    for field in (
        "schema_version",
        "graph_identity",
        "version_pins",
        "command_binding",
        "scope_binding",
        "request_hash",
        "question_hash",
        "cognitive_revision",
    ):
        if state.get(field) != expected[field]:
            raise OutcomeReviewContractError("OUTCOME_REVIEW_FROZEN_SCOPE_MISMATCH")
    return {"status": "VALIDATED", "route": "compose_advisory"}


def compose_advisory(
    state: OutcomeReviewGraphStateV1,
    runtime: Runtime[OutcomeReviewInvocation],
) -> dict[str, Any]:
    if state.get("status") != "VALIDATED" or state.get("route") != "compose_advisory":
        raise OutcomeReviewContractError("OUTCOME_REVIEW_COMPOSE_TRANSITION_INVALID")
    answer = _invoke_and_validate(state, runtime)
    digest = answer_hash(answer)
    citations = _citation_refs(answer)
    return {
        "status": "COMPOSED",
        "advisory_hash": digest,
        "citation_refs": citations,
        "advisory": answer.model_dump(mode="json"),
    }


def project_proposal(
    state: OutcomeReviewGraphStateV1,
    runtime: Runtime[OutcomeReviewInvocation],
) -> dict[str, Any]:
    if state.get("status") != "COMPOSED":
        raise OutcomeReviewContractError("OUTCOME_REVIEW_PROJECT_TRANSITION_INVALID")
    raw_advisory = state.get("advisory")
    if raw_advisory is None:
        # UntrackedValue deliberately disappears from checkpoints. Recomposition is allowed
        # only from the exact frozen request and must reproduce the recorded advisory hash.
        answer = _invoke_and_validate(state, runtime)
    else:
        answer = ReviewCopilotAnswer.model_validate(raw_advisory)
    digest = answer_hash(answer)
    if digest != state.get("advisory_hash"):
        raise OutcomeReviewContractError("OUTCOME_REVIEW_RECOMPOSITION_HASH_MISMATCH")
    citations = _citation_refs(answer)
    if citations != state.get("citation_refs"):
        raise OutcomeReviewContractError("OUTCOME_REVIEW_RECOMPOSITION_REFS_MISMATCH")
    binding = cast(Mapping[str, Any], state["command_binding"])
    projection = OutcomeReviewProjection(
        command_id=cast(str, binding["command_id"]),
        review_task_id=cast(str, binding["review_task_id"]),
        packet_id=cast(str, binding["packet_id"]),
        frozen_packet_ref=cast(str, binding["frozen_packet_ref"]),
        frozen_packet_hash=cast(str, binding["frozen_packet_hash"]),
        frozen_packet_version=cast(int, binding["frozen_packet_version"]),
        action_hash=cast(str, binding["action_hash"]),
        review_task_status=cast(str, binding["review_task_status"]),
        review_deadline=cast(str, binding["review_deadline"]),
        room_epoch=cast(int, binding["room_epoch"]),
        process_revision=cast(int, binding["process_revision"]),
        fencing_token=cast(int, binding["fencing_token"]),
        advisory_hash=digest,
        citation_refs=citations,
        answer=answer,
    )
    projection_payload = projection.model_dump(mode="json")
    encoded_size = len(
        json.dumps(
            projection_payload,
            ensure_ascii=False,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    )
    if encoded_size > MAX_OUTCOME_REVIEW_ENCODED_BYTES:
        raise OutcomeReviewContractError("OUTCOME_REVIEW_RESULT_TOO_LARGE")
    return {
        "status": "PROPOSED",
        "result_hash": canonical_sha256(projection_payload),
        "projection": projection_payload,
    }


def _invoke_and_validate(
    state: OutcomeReviewGraphStateV1,
    runtime: Runtime[OutcomeReviewInvocation],
) -> ReviewCopilotAnswer:
    context = runtime.context
    if context is None:
        raise OutcomeReviewContractError("OUTCOME_REVIEW_INVOCATION_CONTEXT_INVALID")
    command = _command_from_state(state)
    if context.reviewer_actor_hash != command.reviewer_actor_hash:
        raise OutcomeReviewContractError("OUTCOME_REVIEW_REVIEWER_BINDING_MISMATCH")
    expected = new_outcome_review_state(command=command, request=context.request)
    for field in (
        "schema_version",
        "graph_identity",
        "version_pins",
        "command_binding",
        "scope_binding",
        "request_hash",
        "question_hash",
    ):
        if state.get(field) != expected[field]:
            raise OutcomeReviewContractError("OUTCOME_REVIEW_FROZEN_SCOPE_MISMATCH")
    answer = invoke_outcome_review_lcel(answerer=context.answerer, request=context.request)
    return context.validate_answer(context.request, answer)


def _command_from_state(state: OutcomeReviewGraphStateV1):
    from app.graphs.outcome.state import OutcomeReviewPrivateCommand

    binding = state.get("command_binding")
    scope = state.get("scope_binding")
    if not isinstance(binding, Mapping) or not isinstance(scope, Mapping):
        raise OutcomeReviewContractError("OUTCOME_REVIEW_BINDING_INVALID")
    if (
        binding.get("schema_version") != "outcome-review-command-binding.v1"
        or scope.get("schema_version") != "outcome-review-private-scope.v1"
        or scope.get("state_scope") != "REVIEWER_PRIVATE"
    ):
        raise OutcomeReviewContractError("OUTCOME_REVIEW_BINDING_INVALID")
    return OutcomeReviewPrivateCommand(
        authorization_schema_version=binding.get("authorization_schema_version"),
        command_id=binding.get("command_id"),
        thread_id=binding.get("thread_id"),
        tenant_surrogate=binding.get("tenant_surrogate"),
        case_id=binding.get("case_id"),
        review_task_id=binding.get("review_task_id"),
        reviewer_actor_hash=binding.get("reviewer_actor_hash"),
        packet_id=binding.get("packet_id"),
        frozen_packet_ref=binding.get("frozen_packet_ref"),
        frozen_packet_hash=binding.get("frozen_packet_hash"),
        frozen_packet_version=binding.get("frozen_packet_version"),
        action_hash=binding.get("action_hash"),
        event_hash=binding.get("event_hash"),
        review_task_status=binding.get("review_task_status"),
        review_deadline=binding.get("review_deadline"),
        authorized_artifact_refs=dict(
            cast(Mapping[str, str], binding.get("authorized_artifact_refs", {}))
        ),
        room_epoch=binding.get("room_epoch"),
        process_revision=binding.get("process_revision"),
        fencing_token=binding.get("fencing_token"),
        fact_refs=tuple(scope.get("fact_refs", ())),
        rule_refs=tuple(scope.get("rule_refs", ())),
        draft_refs=tuple(scope.get("draft_refs", ())),
        deliberation_refs=tuple(scope.get("deliberation_refs", ())),
        question_hash=state.get("question_hash"),
        request_hash=state.get("request_hash"),
        version_pins=dict(cast(Mapping[str, str], state.get("version_pins", {}))),
    )


def _citation_refs(answer: ReviewCopilotAnswer) -> list[str]:
    refs = {
        *answer.fact_refs,
        *answer.rule_refs,
        *answer.draft_refs,
        *answer.deliberation_refs,
        *(ref for statement in answer.statements for ref in statement.refs),
    }
    ordered = sorted(refs)
    if any(_SHA256.fullmatch(ref) for ref in ordered):
        # Citation identifiers are names, not an opportunity to smuggle opaque payloads.
        raise OutcomeReviewContractError("OUTCOME_REVIEW_CITATION_IDENTIFIER_INVALID")
    return ordered


__all__ = ["compose_advisory", "project_proposal", "validate_scope_packet"]
