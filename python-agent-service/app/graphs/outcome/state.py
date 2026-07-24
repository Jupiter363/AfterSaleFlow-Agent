from __future__ import annotations

import hashlib
import json
import re
from collections.abc import Callable
from dataclasses import dataclass
from typing import Annotated, Any, Literal

from langgraph.channels import UntrackedValue
from pydantic import BaseModel, ConfigDict, Field
from typing_extensions import NotRequired, TypedDict

from app.graphs.outcome.contracts import OUTCOME_REVIEW_IDENTITY, OutcomeReviewGraphIdentity
from app.graphs.outcome.errors import OutcomeReviewContractError
from app.schemas import ReviewCopilotAnswer, ReviewCopilotRequest


MAX_OUTCOME_REVIEW_ENCODED_BYTES = 32_768
MAX_OUTCOME_REVIEW_REFS = 256
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_IMMUTABLE_REF = re.compile(r"^(?:urn:|[A-Za-z0-9])[A-Za-z0-9._:/-]{0,511}$")
_RFC3339 = re.compile(
    r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$"
)
_AUTHORIZED_ARTIFACT_CATEGORIES = frozenset(
    {
        "case_summary",
        "claims",
        "issues",
        "evidence_matrix",
        "adjudication_draft",
        "remedy_plan",
        "risk_flags",
    }
)


class OutcomeReviewGraphVersionPins(TypedDict):
    graph_key: str
    graph_version: str
    checkpoint_schema_version: str
    state_schema_version: str
    prompt_version: str
    model_profile_id: str
    output_schema_version: str
    policy_version: str
    guardrail_version: str
    tool_policy_version: str


class OutcomeReviewCommandBindingV1(TypedDict):
    schema_version: Literal["outcome-review-command-binding.v1"]
    authorization_schema_version: Literal["review-packet-authorization.v1"]
    command_id: str
    thread_id: str
    tenant_surrogate: str
    case_id: str
    review_task_id: str
    reviewer_actor_hash: str
    packet_id: str
    frozen_packet_ref: str
    frozen_packet_hash: str
    frozen_packet_version: int
    action_hash: str
    review_task_status: str
    review_deadline: str
    authorized_artifact_refs: dict[str, str]
    room_epoch: int
    process_revision: int
    fencing_token: int
    command_request_hash: str


class OutcomeReviewScopeBindingV1(TypedDict):
    schema_version: Literal["outcome-review-private-scope.v1"]
    state_scope: Literal["REVIEWER_PRIVATE"]
    fact_refs: list[str]
    rule_refs: list[str]
    draft_refs: list[str]
    deliberation_refs: list[str]


class OutcomeReviewGraphStateV1(TypedDict):
    schema_version: Literal["outcome.review.graph-state.v1"]
    graph_identity: Literal["outcome/review.v1"]
    version_pins: OutcomeReviewGraphVersionPins
    command_binding: OutcomeReviewCommandBindingV1
    scope_binding: OutcomeReviewScopeBindingV1
    request_hash: str
    question_hash: str
    status: Literal["PENDING", "VALIDATED", "COMPOSED", "PROPOSED"]
    cognitive_revision: int
    route: NotRequired[Literal["compose_advisory"]]
    advisory_hash: NotRequired[str]
    citation_refs: NotRequired[list[str]]
    result_hash: NotRequired[str]
    advisory: NotRequired[Annotated[dict[str, Any], UntrackedValue(dict)]]
    projection: NotRequired[Annotated[dict[str, Any], UntrackedValue(dict)]]


class OutcomeReviewPrivateCommand(BaseModel):
    """Private Java-minted capability. It is not a formal review decision contract."""

    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)

    schema_version: Literal["outcome-graph-command.v1"] = "outcome-graph-command.v1"
    authorization_schema_version: Literal["review-packet-authorization.v1"] = (
        "review-packet-authorization.v1"
    )
    command_id: str = Field(min_length=1, max_length=128)
    thread_id: str = Field(min_length=1, max_length=128)
    tenant_surrogate: str = Field(min_length=1, max_length=128)
    case_id: str = Field(min_length=1, max_length=128)
    review_task_id: str = Field(min_length=1, max_length=128)
    reviewer_actor_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    packet_id: str = Field(min_length=1, max_length=128)
    frozen_packet_ref: str = Field(min_length=1, max_length=512)
    frozen_packet_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    frozen_packet_version: int = Field(ge=1)
    action_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    review_task_status: str = Field(min_length=1, max_length=64)
    review_deadline: str = Field(min_length=20, max_length=35)
    authorized_artifact_refs: dict[str, str]
    room_epoch: int = Field(ge=1)
    process_revision: int = Field(ge=0)
    fencing_token: int = Field(ge=1)
    fact_refs: tuple[str, ...] = ()
    rule_refs: tuple[str, ...] = ()
    draft_refs: tuple[str, ...] = ()
    deliberation_refs: tuple[str, ...] = ()
    question_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    request_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    version_pins: dict[str, str]


class OutcomeReviewProjection(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)

    schema_version: Literal["outcome-graph-result.v1"] = "outcome-graph-result.v1"
    graph_identity: Literal["outcome/review.v1"] = "outcome/review.v1"
    command_id: str
    review_task_id: str
    packet_id: str
    frozen_packet_ref: str
    frozen_packet_hash: str
    frozen_packet_version: int
    action_hash: str
    review_task_status: str
    review_deadline: str
    room_epoch: int
    process_revision: int
    fencing_token: int
    advisory_hash: str
    citation_refs: list[str]
    answer: ReviewCopilotAnswer
    approval_performed: Literal[False] = False
    execution_triggered: Literal[False] = False
    is_final_decision: Literal[False] = False


ReviewAnswerer = Callable[[ReviewCopilotRequest], ReviewCopilotAnswer]
ReviewAnswerValidator = Callable[
    [ReviewCopilotRequest, ReviewCopilotAnswer], ReviewCopilotAnswer
]


@dataclass(frozen=True, slots=True)
class OutcomeReviewInvocation:
    """All sensitive packet, prompt and model material remains outside checkpoint state."""

    request: ReviewCopilotRequest
    reviewer_actor_hash: str
    answerer: ReviewAnswerer
    validate_answer: ReviewAnswerValidator


def canonical_sha256(value: Any) -> str:
    try:
        encoded = json.dumps(
            value,
            ensure_ascii=False,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    except (TypeError, ValueError) as error:
        raise OutcomeReviewContractError("OUTCOME_REVIEW_VALUE_NOT_SERIALIZABLE") from error
    return hashlib.sha256(encoded).hexdigest()


def request_hash(request: ReviewCopilotRequest) -> str:
    return canonical_sha256(request.model_dump(mode="json"))


def packet_hash(request: ReviewCopilotRequest) -> str:
    return canonical_sha256(request.frozen_packet)


def question_hash(request: ReviewCopilotRequest) -> str:
    return hashlib.sha256(request.question.encode("utf-8")).hexdigest()


def answer_hash(answer: ReviewCopilotAnswer) -> str:
    return canonical_sha256(answer.model_dump(mode="json"))


def version_pins(
    identity: OutcomeReviewGraphIdentity = OUTCOME_REVIEW_IDENTITY,
) -> OutcomeReviewGraphVersionPins:
    return {
        "graph_key": identity.graph_key,
        "graph_version": identity.graph_version,
        "checkpoint_schema_version": identity.checkpoint_schema_version,
        "state_schema_version": identity.state_schema_version,
        "prompt_version": identity.prompt_version,
        "model_profile_id": identity.model_profile_id,
        "output_schema_version": identity.output_schema_version,
        "policy_version": identity.policy_version,
        "guardrail_version": identity.guardrail_version,
        "tool_policy_version": identity.tool_policy_version,
    }


def new_outcome_review_state(
    *,
    command: OutcomeReviewPrivateCommand,
    request: ReviewCopilotRequest,
) -> OutcomeReviewGraphStateV1:
    _validate_command(command)
    _validate_request_binding(command, request)
    return {
        "schema_version": OUTCOME_REVIEW_IDENTITY.state_schema_version,
        "graph_identity": "outcome/review.v1",
        "version_pins": version_pins(),
        "command_binding": {
            "schema_version": "outcome-review-command-binding.v1",
            "authorization_schema_version": command.authorization_schema_version,
            "command_id": command.command_id,
            "thread_id": command.thread_id,
            "tenant_surrogate": command.tenant_surrogate,
            "case_id": command.case_id,
            "review_task_id": command.review_task_id,
            "reviewer_actor_hash": command.reviewer_actor_hash,
            "packet_id": command.packet_id,
            "frozen_packet_ref": command.frozen_packet_ref,
            "frozen_packet_hash": command.frozen_packet_hash,
            "frozen_packet_version": command.frozen_packet_version,
            "action_hash": command.action_hash,
            "review_task_status": command.review_task_status,
            "review_deadline": command.review_deadline,
            "authorized_artifact_refs": dict(sorted(command.authorized_artifact_refs.items())),
            "room_epoch": command.room_epoch,
            "process_revision": command.process_revision,
            "fencing_token": command.fencing_token,
            "command_request_hash": command.request_hash,
        },
        "scope_binding": {
            "schema_version": "outcome-review-private-scope.v1",
            "state_scope": "REVIEWER_PRIVATE",
            "fact_refs": list(command.fact_refs),
            "rule_refs": list(command.rule_refs),
            "draft_refs": list(command.draft_refs),
            "deliberation_refs": list(command.deliberation_refs),
        },
        "request_hash": command.request_hash,
        "question_hash": command.question_hash,
        "status": "PENDING",
        "cognitive_revision": 1,
    }


def validate_outcome_review_recovery_state(
    state: dict[str, Any],
    *,
    command: OutcomeReviewPrivateCommand,
    request: ReviewCopilotRequest,
) -> None:
    expected = new_outcome_review_state(command=command, request=request)
    persisted_fields = {
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
    if not isinstance(state, dict) or set(state) - persisted_fields:
        raise OutcomeReviewContractError("OUTCOME_REVIEW_RECOVERY_STATE_FIELDS_INVALID")
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
            raise OutcomeReviewContractError("OUTCOME_REVIEW_RECOVERY_BINDING_MISMATCH")
    if state.get("status") not in {"PENDING", "VALIDATED", "COMPOSED", "PROPOSED"}:
        raise OutcomeReviewContractError("OUTCOME_REVIEW_RECOVERY_STATUS_INVALID")
    if state.get("route") not in {None, "compose_advisory"}:
        raise OutcomeReviewContractError("OUTCOME_REVIEW_RECOVERY_ROUTE_INVALID")
    for field in ("advisory_hash", "result_hash"):
        value = state.get(field)
        if value is not None and (not isinstance(value, str) or _SHA256.fullmatch(value) is None):
            raise OutcomeReviewContractError("OUTCOME_REVIEW_RECOVERY_HASH_INVALID")
    refs = state.get("citation_refs", [])
    authorized_refs = {
        ref
        for group in (
            command.fact_refs,
            command.rule_refs,
            command.draft_refs,
            command.deliberation_refs,
        )
        for ref in group
    }
    if (
        not isinstance(refs, list)
        or len(refs) > MAX_OUTCOME_REVIEW_REFS
        or len(refs) != len(set(refs))
        or any(
            not isinstance(ref, str)
            or _IDENTIFIER.fullmatch(ref) is None
            or ref not in authorized_refs
            for ref in refs
        )
    ):
        raise OutcomeReviewContractError("OUTCOME_REVIEW_RECOVERY_REFS_INVALID")
    if state.get("cognitive_revision") != 1:
        raise OutcomeReviewContractError("OUTCOME_REVIEW_RECOVERY_REVISION_INVALID")
    status = state.get("status")
    if status == "PENDING" and any(
        field in state for field in ("route", "advisory_hash", "citation_refs", "result_hash")
    ):
        raise OutcomeReviewContractError("OUTCOME_REVIEW_RECOVERY_TRANSITION_INVALID")
    if status == "VALIDATED" and (
        state.get("route") != "compose_advisory"
        or any(field in state for field in ("advisory_hash", "citation_refs", "result_hash"))
    ):
        raise OutcomeReviewContractError("OUTCOME_REVIEW_RECOVERY_TRANSITION_INVALID")
    if status == "COMPOSED" and (
        state.get("route") != "compose_advisory"
        or "advisory_hash" not in state
        or "citation_refs" not in state
        or "result_hash" in state
    ):
        raise OutcomeReviewContractError("OUTCOME_REVIEW_RECOVERY_TRANSITION_INVALID")
    if status == "PROPOSED" and (
        state.get("route") != "compose_advisory"
        or "advisory_hash" not in state
        or "citation_refs" not in state
        or "result_hash" not in state
    ):
        raise OutcomeReviewContractError("OUTCOME_REVIEW_RECOVERY_TRANSITION_INVALID")


def _validate_command(command: OutcomeReviewPrivateCommand) -> None:
    if command.version_pins != version_pins():
        raise OutcomeReviewContractError("OUTCOME_REVIEW_VERSION_PINS_MISMATCH")
    if not _IMMUTABLE_REF.fullmatch(command.frozen_packet_ref):
        raise OutcomeReviewContractError("OUTCOME_REVIEW_PACKET_REF_INVALID")
    for identifier in (
        command.command_id,
        command.thread_id,
        command.tenant_surrogate,
        command.case_id,
        command.review_task_id,
        command.packet_id,
        command.review_task_status,
    ):
        if _IDENTIFIER.fullmatch(identifier) is None:
            raise OutcomeReviewContractError("OUTCOME_REVIEW_IDENTIFIER_INVALID")
    groups = (
        command.fact_refs,
        command.rule_refs,
        command.draft_refs,
        command.deliberation_refs,
    )
    all_refs = [ref for group in groups for ref in group]
    if len(all_refs) > MAX_OUTCOME_REVIEW_REFS or len(all_refs) != len(set(all_refs)):
        raise OutcomeReviewContractError("OUTCOME_REVIEW_AUTHORIZED_REFS_INVALID")
    if any(_IDENTIFIER.fullmatch(ref) is None for ref in all_refs):
        raise OutcomeReviewContractError("OUTCOME_REVIEW_AUTHORIZED_REFS_INVALID")
    if _RFC3339.fullmatch(command.review_deadline) is None:
        raise OutcomeReviewContractError("OUTCOME_REVIEW_DEADLINE_INVALID")
    if (
        set(command.authorized_artifact_refs) - _AUTHORIZED_ARTIFACT_CATEGORIES
        or any(
            not isinstance(ref, str) or _IMMUTABLE_REF.fullmatch(ref) is None
            for ref in command.authorized_artifact_refs.values()
        )
    ):
        raise OutcomeReviewContractError("OUTCOME_REVIEW_ARTIFACT_CAPABILITY_INVALID")


def _validate_request_binding(
    command: OutcomeReviewPrivateCommand,
    request: ReviewCopilotRequest,
) -> None:
    request_payload = request.model_dump(mode="json")
    try:
        request_bytes = len(
            json.dumps(
                request_payload,
                ensure_ascii=False,
                allow_nan=False,
                sort_keys=True,
                separators=(",", ":"),
            ).encode("utf-8")
        )
    except (TypeError, ValueError) as error:
        raise OutcomeReviewContractError("OUTCOME_REVIEW_REQUEST_NOT_SERIALIZABLE") from error
    if request_bytes > MAX_OUTCOME_REVIEW_ENCODED_BYTES:
        raise OutcomeReviewContractError("OUTCOME_REVIEW_REQUEST_TOO_LARGE")
    if (
        request.case_id != command.case_id
        or request.review_id != command.review_task_id
        or request.review_packet_version != command.frozen_packet_version
        or request.reviewer_role != "PLATFORM_REVIEWER"
        or request_hash(request) != command.request_hash
        or question_hash(request) != command.question_hash
        or packet_hash(request) != command.frozen_packet_hash
        or tuple(request.available_fact_refs) != command.fact_refs
        or tuple(request.available_rule_refs) != command.rule_refs
        or tuple(request.available_draft_refs) != command.draft_refs
        or tuple(request.available_deliberation_refs) != command.deliberation_refs
    ):
        raise OutcomeReviewContractError("OUTCOME_REVIEW_FROZEN_PACKET_BINDING_MISMATCH")


__all__ = [
    "MAX_OUTCOME_REVIEW_ENCODED_BYTES",
    "MAX_OUTCOME_REVIEW_REFS",
    "OutcomeReviewGraphStateV1",
    "OutcomeReviewInvocation",
    "OutcomeReviewPrivateCommand",
    "OutcomeReviewProjection",
    "answer_hash",
    "canonical_sha256",
    "new_outcome_review_state",
    "packet_hash",
    "question_hash",
    "request_hash",
    "validate_outcome_review_recovery_state",
    "version_pins",
]
