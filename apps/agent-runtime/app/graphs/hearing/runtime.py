from __future__ import annotations

import re
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any, Literal, cast

from pydantic import BaseModel

from app.contracts.v1.codec import canonical_sha256
from app.contracts.v1.models import RoomGraphCommand
from app.graph_runtime.checkpoint import FencedPostgresSaver, bind_fence_context
from app.graph_runtime.gateway import GatewayExecution
from app.graph_runtime.identity import ThreadIdentity
from app.graph_runtime.persistence_models import GraphFenceContext
from app.graphs.hearing.contracts import HearingGraphIdentity, HearingOperation
from app.graphs.hearing.errors import HearingGraphContractError
from app.graphs.hearing.graph import _build_family
from app.graphs.hearing.privacy import validate_hearing_scope_binding
from app.graphs.hearing.state import (
    HearingCommandBindingV1,
    HearingGraphInvocation,
    HearingScopeBindingV1,
    new_hearing_graph_state,
    request_hash,
)


HearingRuntimeMode = Literal["DISABLED", "SIGNED_SYNTHETIC_SHADOW"]
_RUNTIME_BINDING_METADATA_KEY = "hearing_runtime_binding_sha256"
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_REQUIRED_STATE_FIELDS = frozenset(
    {
        "schema_version",
        "graph_identity",
        "version_pins",
        "operation",
        "case_id",
        "workflow_id",
        "stage_sequence",
        "request_schema_version",
        "request_hash",
        "status",
        "cognitive_revision",
        "command_binding",
        "scope_binding",
    }
)
_OPTIONAL_STATE_FIELDS = frozenset(
    {
        "route",
        "ordered_work_item_keys",
        "next_dispatch_index",
        "current_wave_keys",
        "in_flight_keys",
        "work_results",
        "proposal_schema_version",
        "proposal",
    }
)


@dataclass(frozen=True, slots=True)
class HearingRuntimeAuthority:
    """Hearing authority bound to a gateway-verified synthetic command and both fences."""

    execution: GatewayExecution
    operation: HearingOperation
    operation_key: str
    java_room_fencing_token: int
    shared_barrier_receipt_hash: str | None = None


@dataclass(frozen=True, slots=True)
class HearingRuntimeBundle:
    graph: Any
    identity: HearingGraphIdentity
    authority: HearingRuntimeAuthority
    request: BaseModel
    invocation: HearingGraphInvocation
    runtime_mode: Literal["SIGNED_SYNTHETIC_SHADOW"]
    runtime_binding_sha256: str
    command_binding: HearingCommandBindingV1
    scope_binding: HearingScopeBindingV1
    recursion_limit: int

    async def astart(self) -> dict[str, Any]:
        if await self._checkpoint_values() is not None:
            raise HearingGraphContractError("HEARING_RUNTIME_ALREADY_STARTED")
        state = new_hearing_graph_state(
            identity=self.identity,
            operation=self.authority.operation,
            request=self.request,
            command_binding=self.command_binding,
            scope_binding=self.scope_binding,
        )
        result = await self.graph.ainvoke(
            state,
            self._config(),
            context=self.invocation,
            durability="sync",
        )
        self._validate_state(result)
        return cast(dict[str, Any], result)

    async def aresume(self) -> dict[str, Any]:
        values = await self._checkpoint_values()
        if values is None:
            raise HearingGraphContractError("HEARING_RECOVERY_CHECKPOINT_REQUIRED")
        self._validate_state(values)
        result = await self.graph.ainvoke(
            None,
            self._config(),
            context=self.invocation,
            durability="sync",
        )
        self._validate_state(result)
        return cast(dict[str, Any], result)

    async def arun(self) -> dict[str, Any]:
        return (
            await self.astart()
            if await self._checkpoint_values() is None
            else await self.aresume()
        )

    def _config(self) -> dict[str, Any]:
        config: dict[str, Any] = {
            "configurable": {"thread_id": self.command_binding["thread_id"]},
            "metadata": {
                _RUNTIME_BINDING_METADATA_KEY: self.runtime_binding_sha256,
            },
            "max_concurrency": 8,
            "recursion_limit": self.recursion_limit,
        }
        return dict(bind_fence_context(config, self.authority.execution.fence))

    async def _checkpoint_values(self) -> Mapping[str, Any] | None:
        snapshot = await self.graph.aget_state(self._config())
        if not snapshot.values:
            return None
        metadata = snapshot.metadata
        if not isinstance(metadata, Mapping) or metadata.get(
            _RUNTIME_BINDING_METADATA_KEY
        ) != self.runtime_binding_sha256:
            raise HearingGraphContractError("HEARING_RECOVERY_RUNTIME_BINDING_MISMATCH")
        return cast(Mapping[str, Any], snapshot.values)

    def _validate_state(self, state: Mapping[str, Any]) -> None:
        validate_hearing_recovery_state(
            state,
            identity=self.identity,
            operation=self.authority.operation,
            request=self.request,
            command_binding=self.command_binding,
            scope_binding=self.scope_binding,
        )


def build_hearing_runtime_bundle(
    *,
    identity: HearingGraphIdentity,
    authority: HearingRuntimeAuthority,
    request: BaseModel,
    invocation: HearingGraphInvocation,
    checkpointer: FencedPostgresSaver,
    runtime_mode: HearingRuntimeMode,
    recursion_limit: int = 64,
) -> HearingRuntimeBundle:
    if runtime_mode == "DISABLED":
        raise HearingGraphContractError("HEARING_RUNTIME_DISABLED")
    if runtime_mode != "SIGNED_SYNTHETIC_SHADOW":
        raise HearingGraphContractError("HEARING_RUNTIME_MODE_FORBIDDEN")
    if not isinstance(checkpointer, FencedPostgresSaver):
        raise HearingGraphContractError("HEARING_RUNTIME_FENCED_CHECKPOINTER_REQUIRED")
    if type(authority.execution) is not GatewayExecution:
        raise HearingGraphContractError("HEARING_GATEWAY_EXECUTION_REQUIRED")
    if (
        not isinstance(request, BaseModel)
        or invocation.request is not request
        or not callable(invocation.execute)
    ):
        raise HearingGraphContractError("HEARING_INVOCATION_CONTEXT_INVALID")
    if (
        isinstance(recursion_limit, bool)
        or not isinstance(recursion_limit, int)
        or recursion_limit < 16
        or recursion_limit > 256
    ):
        raise HearingGraphContractError("HEARING_RUNTIME_RECURSION_LIMIT_INVALID")

    command, thread, fence = _validated_gateway_binding(identity, authority, request)
    command_binding: HearingCommandBindingV1 = {
        "schema_version": "hearing-command-binding.v1",
        "command_id": command.command_id,
        "operation_key": authority.operation_key,
        "command_request_hash": command.request_hash,
        "thread_id": command.thread_id,
        "tenant_surrogate": command.tenant_surrogate,
        "room_epoch": command.room_epoch,
        "process_revision": command.process_revision,
        "java_room_fencing_token": authority.java_room_fencing_token,
        "graph_lease_owner_id": fence.owner_id,
        "graph_lease_fencing_token": fence.fencing_token,
    }
    refs = [command.domain_snapshot_ref, *([command.event_ref] if command.event_ref else [])]
    scope_binding: HearingScopeBindingV1 = {
        "schema_version": "hearing-scope-binding.v1",
        "state_scope": "SHARED" if thread.shared_session else "ACTOR_PRIVATE",
        "actor_scope_hash": thread.actor_scope_hash,
        "authorized_artifact_refs": [
            {
                "artifact_id": ref.artifact_id,
                "schema_version": ref.schema_version,
                "uri": ref.uri,
                "sha256": ref.sha256,
            }
            for ref in refs
        ],
        "shared_barrier_receipt_hash": authority.shared_barrier_receipt_hash,
    }
    binding_hash = canonical_sha256(
        {
            "schema_version": "hearing-runtime-binding.v1",
            "graph_identity": identity.identity,
            "operation": authority.operation.value,
            "input_hash": request_hash(request),
            "command_binding": command_binding,
            "scope_binding": scope_binding,
        }
    )
    graph = _build_family(identity).compile(checkpointer=checkpointer)
    if graph.checkpointer is not checkpointer:
        raise HearingGraphContractError("HEARING_RUNTIME_CHECKPOINTER_BINDING_INVALID")
    return HearingRuntimeBundle(
        graph=graph,
        identity=identity,
        authority=authority,
        request=request,
        invocation=invocation,
        runtime_mode="SIGNED_SYNTHETIC_SHADOW",
        runtime_binding_sha256=binding_hash,
        command_binding=command_binding,
        scope_binding=scope_binding,
        recursion_limit=recursion_limit,
    )


def validate_hearing_recovery_state(
    state: Mapping[str, Any],
    *,
    identity: HearingGraphIdentity,
    operation: HearingOperation,
    request: BaseModel,
    command_binding: HearingCommandBindingV1,
    scope_binding: HearingScopeBindingV1,
) -> None:
    if not isinstance(state, Mapping):
        raise HearingGraphContractError("HEARING_RECOVERY_STATE_INVALID")
    fields = set(state)
    if not _REQUIRED_STATE_FIELDS <= fields or fields - (
        _REQUIRED_STATE_FIELDS | _OPTIONAL_STATE_FIELDS
    ):
        raise HearingGraphContractError("HEARING_RECOVERY_STATE_FIELDS_INVALID")
    expected = new_hearing_graph_state(
        identity=identity,
        operation=operation,
        request=request,
        command_binding=command_binding,
        scope_binding=scope_binding,
    )
    for field in _REQUIRED_STATE_FIELDS:
        if field == "status":
            continue
        if state.get(field) != expected.get(field):
            raise HearingGraphContractError("HEARING_RECOVERY_BINDING_MISMATCH")
    if state.get("status") not in {"PENDING", "PROPOSED"}:
        raise HearingGraphContractError("HEARING_RECOVERY_STATUS_INVALID")
    validate_hearing_scope_binding(state)
    if state.get("status") == "PROPOSED" and (
        not isinstance(state.get("proposal"), Mapping)
        or not isinstance(state.get("proposal_schema_version"), str)
    ):
        raise HearingGraphContractError("HEARING_RECOVERY_PROPOSAL_INVALID")


def _validated_gateway_binding(
    identity: HearingGraphIdentity,
    authority: HearingRuntimeAuthority,
    request: BaseModel,
) -> tuple[RoomGraphCommand, ThreadIdentity, GraphFenceContext]:
    execution = authority.execution
    command = execution.admission.command
    thread = execution.admission.thread
    fence = execution.fence
    if (
        not isinstance(command, RoomGraphCommand)
        or not isinstance(thread, ThreadIdentity)
        or not isinstance(fence, GraphFenceContext)
    ):
        raise HearingGraphContractError("HEARING_GATEWAY_EXECUTION_INVALID")
    invocation = command.invocation_context
    if (
        command.room_type != "HEARING"
        or command.case_id != getattr(request, "case_id", None)
        or command.stage_sequence != getattr(request, "stage_sequence", None)
        or command.graph_key != identity.graph_key
        or command.graph_version != identity.graph_version
        or command.checkpoint_schema_version != identity.checkpoint_schema_version
        or authority.operation not in identity.operations
        or invocation.prompt_profile_id != identity.prompt_version
        or invocation.model_profile_id != identity.model_profile_id
        or invocation.output_schema_version != identity.output_schema_version
        or invocation.policy_version != identity.policy_version
        or invocation.guardrail_version != identity.guardrail_version
        or invocation.tool_capabilities
        or not command.domain_snapshot_ref.uri.startswith("urn:synthetic-hearing:")
        or (
            command.event_ref is not None
            and not command.event_ref.uri.startswith("urn:synthetic-hearing:")
        )
    ):
        raise HearingGraphContractError("HEARING_GATEWAY_COMMAND_BINDING_MISMATCH")
    registry_binding = getattr(execution.admission.registry, "binding", None)
    if getattr(registry_binding, "tool_policy_version", None) != identity.tool_policy_version:
        raise HearingGraphContractError("HEARING_TOOL_POLICY_BINDING_MISMATCH")
    if (
        fence.thread_id != command.thread_id
        or fence.command_id != command.command_id
        or fence.request_hash != command.request_hash
        or fence.room_epoch != command.room_epoch
        or fence.graph_key != identity.graph_key
        or fence.graph_version != identity.graph_version
        or fence.checkpoint_schema_version != identity.checkpoint_schema_version
    ):
        raise HearingGraphContractError("HEARING_GRAPH_LEASE_FENCE_MISMATCH")
    if (
        isinstance(authority.java_room_fencing_token, bool)
        or not isinstance(authority.java_room_fencing_token, int)
        or authority.java_room_fencing_token < 1
    ):
        raise HearingGraphContractError("HEARING_JAVA_ROOM_FENCE_INVALID")
    expected_key = (
        f"hearing.agent:{command.tenant_surrogate}:{command.case_id}:"
        f"{command.room_epoch}:{command.stage_sequence}:{authority.operation.value}:"
        f"{command.request_hash}"
    )
    if authority.operation_key != expected_key or len(expected_key) > 512:
        raise HearingGraphContractError("HEARING_OPERATION_KEY_MISMATCH")
    if thread.shared_session:
        if (
            not isinstance(authority.shared_barrier_receipt_hash, str)
            or _SHA256.fullmatch(authority.shared_barrier_receipt_hash) is None
        ):
            raise HearingGraphContractError("HEARING_SHARED_BARRIER_REQUIRED")
    elif authority.shared_barrier_receipt_hash is not None:
        raise HearingGraphContractError("HEARING_PRIVATE_SCOPE_BARRIER_FORBIDDEN")
    return command, thread, fence


__all__ = [
    "HearingRuntimeAuthority",
    "HearingRuntimeBundle",
    "HearingRuntimeMode",
    "build_hearing_runtime_bundle",
    "validate_hearing_recovery_state",
]
