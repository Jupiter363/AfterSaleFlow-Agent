"""Trusted adapters shared by a future durable ``intake.v2`` executor binding.

The generic Phase 3 executor cannot publish an Intake result yet: its terminal checkpoint
materializer owns ``terminal_draft`` and ``result_json``, while the frozen Intake state owns those
channels for ``intake-turn-proposal.v2``.  This module contains only the parts that can be assembled
without weakening that checkpoint contract.
"""

from __future__ import annotations

from dataclasses import dataclass
import json
import re
from typing import Any, Protocol, cast
from urllib.parse import unquote, urlsplit

from langgraph.checkpoint.base import BaseCheckpointSaver

from app.config import GraphShadowBindingSettings
from app.contracts.v1.codec import canonical_sha256, canonical_sha256_omitting, canonicalize
from app.contracts.v1.models import RoomGraphCommand
from app.graph_runtime.errors import GraphContractError
from app.graph_runtime.gateway import GatewayExecution
from app.graph_runtime.identity import RoomType, ThreadRecord
from app.graph_runtime.state import VersionPinsState
from app.graphs.intake.contracts import (
    IntakeDomainSnapshot,
    IntakeTurnEvent,
    IntakeTurnProposal,
)
from app.graphs.intake.lcel import INTAKE_SYSTEM_PROMPT
from app.graphs.intake.runtime import IntakeRuntimeBundle, build_intake_runtime_bundle
from app.graphs.intake.state import IntakeGraphBindings, IntakeGraphStateV2, IntakeTurnContext
from app.graphs.intake.validators import validate_terminal_proposal
from app.model_runtime.profiles import (
    ModelInvocationPolicy,
    ModelProfile,
    system_prompt_sha256,
)
from app.model_runtime.transports import ModelTransport


INTAKE_GRAPH_KEY = "intake.v2"
INTAKE_STATE_SCHEMA = "intake-graph-state.v2"
INTAKE_OUTPUT_SCHEMA = "intake-turn-proposal.v2"
INTAKE_TOOL_POLICY = "no-tools.v1"
INTAKE_SNAPSHOT_SCHEMA = "intake-domain-snapshot.v2"
INTAKE_EVENT_SCHEMA = "intake-turn-event.v2"
INTAKE_PROPOSAL_MAX_BYTES = 65_536


@dataclass(frozen=True, slots=True)
class LoadedIntakePayload:
    """Exact immutable-object receipt and bytes returned by a deployment-owned loader."""

    artifact_id: str
    schema_version: str
    uri: str
    sha256: str
    size_bytes: int
    object_version: str
    canonical_payload: bytes

    def __post_init__(self) -> None:
        if (
            re.fullmatch(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$", self.object_version) is None
            or not isinstance(self.canonical_payload, bytes)
            or not self.canonical_payload
            or len(self.canonical_payload) != self.size_bytes
        ):
            raise TypeError("loaded Intake payload receipt is invalid")


@dataclass(frozen=True, slots=True)
class CanonicalIntakeProposal:
    """Canonical proposal bytes ready for an immutable proposal store."""

    artifact_id: str
    schema_version: str
    sha256: str
    size_bytes: int
    canonical_payload: bytes


@dataclass(frozen=True, slots=True)
class StoredIntakeProposal:
    """Immutable proposal-store receipt used by the generic result pointer."""

    artifact_id: str
    schema_version: str
    uri: str
    object_version: str
    sha256: str
    size_bytes: int

    def __post_init__(self) -> None:
        if (
            self.schema_version != INTAKE_OUTPUT_SCHEMA
            or re.fullmatch(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$", self.artifact_id) is None
            or re.fullmatch(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$", self.object_version) is None
            or re.fullmatch(r"^[0-9a-f]{64}$", self.sha256) is None
            or self.size_bytes < 1
            or self.size_bytes > INTAKE_PROPOSAL_MAX_BYTES
            or not _is_immutable_object_uri(self.uri)
        ):
            raise TypeError("stored Intake proposal receipt is invalid")


class IntakeInputLoader(Protocol):
    """Load only the exact immutable object authorized by one admitted execution."""

    async def load(self, execution: GatewayExecution) -> LoadedIntakePayload: ...


class IntakeProposalStore(Protocol):
    """Idempotently store one canonical proposal under its exact terminal authority."""

    async def put(
        self,
        execution: GatewayExecution,
        *,
        proposal: CanonicalIntakeProposal,
        checkpoint_ns: str,
        checkpoint_id: str,
        cognitive_revision: int,
    ) -> StoredIntakeProposal: ...


def require_exact_intake_binding(configured: GraphShadowBindingSettings) -> None:
    """Reject a manifest that relabels another state/profile as ``intake.v2``."""

    if configured.graph_key != INTAKE_GRAPH_KEY:
        raise GraphContractError("Intake adapter requires the exact intake.v2 graph key")
    if (
        configured.state_schema_version != INTAKE_STATE_SCHEMA
        or configured.output_schema_version != INTAKE_OUTPUT_SCHEMA
        or configured.tool_policy_version != INTAKE_TOOL_POLICY
        or configured.allowed_room_types != ("INTAKE",)
    ):
        raise GraphContractError("intake.v2 manifest differs from the frozen Intake binding")


def decode_authorized_intake_ingress(
    *,
    command: RoomGraphCommand,
    loaded: LoadedIntakePayload,
) -> IntakeTurnContext:
    """Decode bytes only after manifest authorization and recheck every immutable reference field."""

    reference = command.event_ref or command.domain_snapshot_ref
    expected_schema = (
        INTAKE_EVENT_SCHEMA if command.event_ref is not None else INTAKE_SNAPSHOT_SCHEMA
    )
    actual_receipt = (
        loaded.artifact_id,
        loaded.schema_version,
        loaded.uri,
        loaded.sha256,
        loaded.size_bytes,
    )
    expected_receipt = (
        reference.artifact_id,
        reference.schema_version,
        reference.uri,
        reference.sha256,
        reference.size_bytes,
    )
    if actual_receipt != expected_receipt or reference.schema_version != expected_schema:
        raise GraphContractError(
            "loaded Intake payload differs from its authorized manifest reference"
        )
    payload = loaded.canonical_payload
    if not isinstance(payload, bytes) or not payload or len(payload) != reference.size_bytes:
        raise GraphContractError("loaded Intake payload size differs from its immutable reference")
    try:
        document = json.loads(payload, object_pairs_hook=_unique_object)
    except (TypeError, ValueError, UnicodeDecodeError) as error:
        raise GraphContractError("loaded Intake payload is not unique-member JSON") from error
    if not isinstance(document, dict) or canonicalize(document) != payload:
        raise GraphContractError("loaded Intake payload is not canonical JSON")
    hash_field = "event_hash" if command.event_ref is not None else "snapshot_hash"
    if canonical_sha256_omitting(document, hash_field) != reference.sha256:
        raise GraphContractError("loaded Intake payload hash differs from its immutable reference")
    try:
        if command.event_ref is not None:
            typed = IntakeTurnEvent.model_validate(document)
            kind = "EVENT"
        else:
            typed = IntakeDomainSnapshot.model_validate(document)
            kind = "SNAPSHOT"
    except ValueError as error:
        raise GraphContractError("loaded Intake payload violates its frozen schema") from error
    _require_payload_command_binding(command, typed.model_dump(mode="json"))
    return IntakeTurnContext(
        cast(Any, kind),
        cast(Any, typed.model_dump(mode="json", exclude_none=True)),
    )


def build_intake_execution_state(execution: GatewayExecution) -> IntakeGraphStateV2:
    """Initialize one new private Intake thread entirely from trusted command/registry data."""

    command, record = _execution_command_and_record(execution)
    if (
        record.cognitive_revision != 0
        or record.last_checkpoint_ns is not None
        or record.last_checkpoint_id is not None
    ):
        raise GraphContractError("existing Intake threads must resume their durable checkpoint")
    identity = record.identity
    bindings: IntakeGraphBindings = {
        "schema_version": "intake-graph-bindings.v2",
        "private": {
            "schema_version": "intake-private-binding.v1",
            "tenant_surrogate": identity.tenant_surrogate,
            "case_id": identity.case_id,
            "room_type": "INTAKE",
            "room_epoch": identity.room_epoch,
            "actor_scope_hash": identity.actor_scope_hash,
            "thread_id": identity.thread_id,
            "agent_session_id": identity.agent_session_id,
            "audience": cast(Any, identity.actor_scope.audience.value),
        },
        "command": _command_binding(command),
    }
    state = IntakeRuntimeBundle.initial_state(
        bindings=bindings,
        version_pins=_version_pins(execution),
    )
    # The process saver requires the first durable command checkpoint to advance revision 0.
    state["cognitive_revision"] = 1
    return state


def build_intake_command_patch(execution: GatewayExecution) -> dict[str, IntakeGraphBindings]:
    """Return only mutable command identity for an already checkpointed private thread."""

    command, record = _execution_command_and_record(execution)
    if record.last_checkpoint_id is None:
        raise GraphContractError("Intake event execution requires an existing durable checkpoint")
    identity = record.identity
    return {
        "bindings": {
            "schema_version": "intake-graph-bindings.v2",
            "private": {
                "schema_version": "intake-private-binding.v1",
                "tenant_surrogate": identity.tenant_surrogate,
                "case_id": identity.case_id,
                "room_type": "INTAKE",
                "room_epoch": identity.room_epoch,
                "actor_scope_hash": identity.actor_scope_hash,
                "thread_id": identity.thread_id,
                "agent_session_id": identity.agent_session_id,
                "audience": cast(Any, identity.actor_scope.audience.value),
            },
            "command": _command_binding(command),
        }
    }


def build_governed_intake_runtime(
    *,
    execution: GatewayExecution,
    transport: ModelTransport,
    provider: str,
    model: str,
    checkpointer: BaseCheckpointSaver[Any],
) -> IntakeRuntimeBundle:
    """Compile the existing governed Intake LCEL with the process-owned saver and command policy."""

    command, _ = _execution_command_and_record(execution)
    invocation = command.invocation_context
    registry = execution.admission.registry.binding
    if registry.tool_policy_version != INTAKE_TOOL_POLICY:
        raise GraphContractError(
            "Intake runtime tool policy differs from the frozen no-tools policy"
        )
    profile = ModelProfile(
        profile_id=invocation.model_profile_id,
        provider=provider,
        model=model,
        temperature=0,
        max_output_tokens=8_192,
        tool_allowlist=(),
        max_provider_attempts=2,
    )
    policy = ModelInvocationPolicy(
        invocation_id=command.attempt_id,
        node_name="intake_lcel",
        deadline_at=command.deadline_at,
        provider_attempts_remaining=command.retry_budget.provider_attempts_remaining,
        repairs_remaining=command.retry_budget.repairs_remaining,
        prompt_version=invocation.prompt_profile_id,
        output_schema_version=invocation.output_schema_version,
        policy_version=invocation.policy_version,
        guardrail_version=invocation.guardrail_version,
        trusted_system_sha256=system_prompt_sha256(INTAKE_SYSTEM_PROMPT),
        traceparent=command.traceparent,
    )
    return build_intake_runtime_bundle(
        transport=transport,
        profile=profile,
        policy=policy,
        checkpointer=checkpointer,
    )


def canonical_intake_proposal(
    proposal: IntakeTurnProposal | dict[str, Any],
) -> CanonicalIntakeProposal:
    """Validate and canonicalize real graph output without inventing a storage URI or receipt."""

    document = (
        proposal.model_dump(mode="json", exclude_unset=True)
        if isinstance(proposal, IntakeTurnProposal)
        else dict(proposal)
    )
    validate_terminal_proposal(document)
    payload = canonicalize(document)
    if not payload or len(payload) > INTAKE_PROPOSAL_MAX_BYTES:
        raise GraphContractError("canonical Intake proposal exceeds its storage contract")
    proposal_hash = cast(str, document["proposal_hash"])
    return CanonicalIntakeProposal(
        artifact_id=f"intake.proposal.{proposal_hash[:32]}",
        schema_version=INTAKE_OUTPUT_SCHEMA,
        sha256=proposal_hash,
        size_bytes=len(payload),
        canonical_payload=payload,
    )


def _execution_command_and_record(
    execution: GatewayExecution,
) -> tuple[RoomGraphCommand, ThreadRecord]:
    command = execution.admission.command
    record = execution.thread_record
    if not isinstance(record, ThreadRecord) or record.identity != execution.admission.thread:
        raise GraphContractError("Intake execution has no exact authoritative thread record")
    identity = record.identity
    registry = execution.admission.registry.binding
    invocation = command.invocation_context
    if (
        command.graph_key != INTAKE_GRAPH_KEY
        or command.room_type != "INTAKE"
        or identity.room_type is not RoomType.INTAKE
        or identity.shared_session
        or identity.graph_key != command.graph_key
        or identity.graph_version != command.graph_version
        or identity.checkpoint_schema_version != command.checkpoint_schema_version
        or registry.state_schema_version != INTAKE_STATE_SCHEMA
        or invocation.output_schema_version != INTAKE_OUTPUT_SCHEMA
        or registry.output_schema_version != INTAKE_OUTPUT_SCHEMA
        or registry.tool_policy_version != INTAKE_TOOL_POLICY
        or invocation.tool_capabilities
    ):
        raise GraphContractError("Intake execution differs from its frozen private version binding")
    return command, record


def _version_pins(execution: GatewayExecution) -> VersionPinsState:
    command = execution.admission.command
    invocation = command.invocation_context
    registry = execution.admission.registry.binding
    return {
        "schema_version": "graph-version-pins.v1",
        "graph_key": command.graph_key,
        "graph_version": command.graph_version,
        "checkpoint_schema_version": command.checkpoint_schema_version,
        "state_schema_version": registry.state_schema_version,
        "prompt_version": invocation.prompt_profile_id,
        "model_profile_id": invocation.model_profile_id,
        "output_schema_version": invocation.output_schema_version,
        "policy_version": invocation.policy_version,
        "guardrail_version": invocation.guardrail_version,
        "tool_policy_version": registry.tool_policy_version,
    }


def _command_binding(command: RoomGraphCommand) -> dict[str, str]:
    return {
        "schema_version": "intake-command-binding.v1",
        "command_id": command.command_id,
        "logical_run_id": command.logical_run_id,
        "attempt_id": command.attempt_id,
    }


def _require_payload_command_binding(command: RoomGraphCommand, payload: dict[str, Any]) -> None:
    expected = {
        "tenant_surrogate": command.tenant_surrogate,
        "case_id": command.case_id,
        "room_type": command.room_type,
        "room_epoch": command.room_epoch,
        "thread_id": command.thread_id,
        "actor_scope_hash": canonical_sha256(command.actor_scope.model_dump(mode="json")),
    }
    if any(payload.get(field) != value for field, value in expected.items()):
        raise GraphContractError("loaded Intake payload crosses its signed command binding")


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, member in pairs:
        if key in value:
            raise ValueError(f"duplicate JSON member: {key}")
        value[key] = member
    return value


def _is_immutable_object_uri(value: str) -> bool:
    parsed = urlsplit(value)
    path = unquote(parsed.path)
    return bool(
        parsed.scheme in {"s3", "minio"}
        and parsed.netloc
        and parsed.username is None
        and parsed.password is None
        and not parsed.query
        and not parsed.fragment
        and path.startswith("/")
        and not path.endswith("/")
        and "\\" not in path
        and "//" not in path
        and all(part not in {"", ".", ".."} for part in path.split("/")[1:])
    )


__all__ = [
    "CanonicalIntakeProposal",
    "IntakeInputLoader",
    "IntakeProposalStore",
    "LoadedIntakePayload",
    "StoredIntakeProposal",
    "build_governed_intake_runtime",
    "build_intake_command_patch",
    "build_intake_execution_state",
    "canonical_intake_proposal",
    "decode_authorized_intake_ingress",
    "require_exact_intake_binding",
]
