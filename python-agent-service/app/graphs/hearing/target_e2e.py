from __future__ import annotations

import hmac
import re
from collections.abc import AsyncIterator, Awaitable, Callable, Mapping
from dataclasses import dataclass
from datetime import datetime, timezone
from types import MappingProxyType
from typing import Any, Literal, Protocol, cast

from langchain_core.messages import AIMessageChunk
from pydantic import BaseModel, ConfigDict, Field

from app.contracts.v1.codec import canonical_sha256, canonicalize
from app.contracts.v1.models import (
    AgentStreamEvent,
    AgentStreamPayload,
    ArtifactOperation,
    ArtifactPointer,
    ExecutionMetadata,
    RoomGraphCommand,
    Usage,
)
from app.graph_runtime.checkpoint import (
    ExternalTerminalCommit,
    FENCE_CONTEXT_KEY,
    FencedPostgresSaver,
    TerminalResultMaterializer,
    bind_fence_context,
)
from app.graph_runtime.gateway import GatewayExecution
from app.graph_runtime.identity import RoomType, ThreadIdentity
from app.graph_runtime.persistence_models import GraphFenceContext
from app.graph_runtime.result import CompletedDraft, ResultBindings
from app.graph_runtime.target_e2e import TargetE2ERoomProposalSource
from app.model_runtime.callbacks import governed_events_from_chunk
from app.graphs.hearing.contracts import (
    EMPTY_HEARING_TOOL_POLICY,
    HEARING_GRAPH_IDENTITIES,
    HEARING_OPERATION_IDENTITIES,
    HEARING_TARGET_E2E_OPERATION_BINDINGS,
    HearingGraphIdentity,
    HearingOperation,
    HearingTargetE2EOperationBinding,
)
from app.graphs.hearing.errors import HearingGraphContractError
from app.graphs.hearing.graph import _build_family
from app.graphs.hearing.runtime import validate_hearing_recovery_state
from app.graphs.hearing.state import (
    MAX_HEARING_PROPOSAL_BYTES,
    HearingCommandBindingV1,
    HearingGraphInvocation,
    HearingScopeBindingV1,
    new_hearing_graph_state,
    request_hash,
)


TARGET_E2E_EXECUTION_LANE = "TARGET_E2E_CANDIDATE"
TARGET_E2E_HEARING_PROPOSAL_SCHEMA = "target-e2e-hearing-proposal.v1"
TARGET_E2E_HEARING_SOURCE_SCHEMA = "target-e2e-room-proposal-source.v2"
_TARGET_RUNTIME_BINDING_METADATA_KEY = "hearing_target_e2e_runtime_binding_sha256"
_ACTIVATION_ID = re.compile(r"^p9act\.v1\.[0-9a-f]{32}$")
_IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")


class HearingTargetE2EStoredPayload(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)

    proposal_id: str = Field(pattern=_IDENTIFIER.pattern)
    payload_schema_version: str = Field(pattern=_IDENTIFIER.pattern)
    payload_ref: str = Field(
        min_length=1,
        max_length=512,
        pattern=r"^urn:target-e2e:proposal:",
    )
    payload_hash: str = Field(pattern=_SHA256.pattern)
    size_bytes: int = Field(ge=1, le=MAX_HEARING_PROPOSAL_BYTES)


class HearingTargetE2EProposal(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)

    schema_version: Literal["target-e2e-hearing-proposal.v1"] = (
        TARGET_E2E_HEARING_PROPOSAL_SCHEMA
    )
    proposal_id: str = Field(pattern=_IDENTIFIER.pattern)
    command_id: str = Field(pattern=_IDENTIFIER.pattern)
    logical_run_id: str = Field(pattern=_IDENTIFIER.pattern)
    attempt_id: str = Field(pattern=_IDENTIFIER.pattern)
    payload_schema_version: str = Field(pattern=_IDENTIFIER.pattern)
    payload_ref: str = Field(
        min_length=1,
        max_length=512,
        pattern=r"^urn:target-e2e:proposal:",
    )
    payload_hash: str = Field(pattern=_SHA256.pattern)
    terminal_class: Literal["NEEDS_INPUT", "COMPLETED", "NEEDS_REVIEW"]
    formal_authority: Literal[False] = False


class HearingTargetE2EProposalSource(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)

    schema_version: Literal["target-e2e-room-proposal-source.v2"] = (
        TARGET_E2E_HEARING_SOURCE_SCHEMA
    )
    room_type: Literal["HEARING"] = "HEARING"
    proposal: HearingTargetE2EProposal

    @property
    def proposal_hash(self) -> str:
        return canonical_sha256(self.proposal.model_dump(mode="json"))


@dataclass(frozen=True, slots=True)
class HearingTargetE2ELoadedInvocation:
    operation: HearingOperation
    request: BaseModel
    invocation: HearingGraphInvocation
    snapshot_uri: str
    snapshot_hash: str
    event_uri: str | None = None
    event_hash: str | None = None
    shared_barrier_receipt_hash: str | None = None


class HearingTargetE2EInvocationProvider(Protocol):
    async def load(
        self,
        execution: GatewayExecution,
    ) -> HearingTargetE2ELoadedInvocation: ...


class HearingTargetE2EPayloadStore(Protocol):
    async def put(
        self,
        *,
        execution: GatewayExecution,
        operation: HearingOperation,
        proposal_id: str,
        payload_schema_version: str,
        payload: bytes,
        payload_hash: str,
        checkpoint_ns: str,
        checkpoint_id: str,
        cognitive_revision: int,
    ) -> HearingTargetE2EStoredPayload: ...


@dataclass(frozen=True, slots=True)
class HearingTargetE2EFamilyRegistration:
    identity: HearingGraphIdentity
    operation_bindings: Mapping[HearingOperation, HearingTargetE2EOperationBinding]
    execution_lane: Literal["TARGET_E2E_CANDIDATE"] = TARGET_E2E_EXECUTION_LANE
    proposal_schema_version: Literal["target-e2e-hearing-proposal.v1"] = (
        TARGET_E2E_HEARING_PROPOSAL_SCHEMA
    )
    graph_output_authority: Literal["PROPOSAL_ONLY"] = "PROPOSAL_ONLY"
    external_effects_allowed: Literal[False] = False

def _family_registration(identity: HearingGraphIdentity) -> HearingTargetE2EFamilyRegistration:
    return HearingTargetE2EFamilyRegistration(
        identity=identity,
        operation_bindings=MappingProxyType(
            {
                operation: HEARING_TARGET_E2E_OPERATION_BINDINGS[operation]
                for operation in identity.operations
            }
        ),
    )


TARGET_E2E_HEARING_FAMILY_REGISTRY: Mapping[
    tuple[str, str, str], HearingTargetE2EFamilyRegistration
] = MappingProxyType(
    {
        (
            identity.graph_key,
            identity.graph_version,
            identity.checkpoint_schema_version,
        ): _family_registration(identity)
        for identity in HEARING_GRAPH_IDENTITIES.values()
    }
)


if (
    len(TARGET_E2E_HEARING_FAMILY_REGISTRY) != 4
    or {
        operation
        for registration in TARGET_E2E_HEARING_FAMILY_REGISTRY.values()
        for operation in registration.operation_bindings
    }
    != set(HearingOperation)
):
    raise RuntimeError("target-E2E Hearing registry must bind four explicit families")


def target_e2e_hearing_family_registrations() -> Mapping[
    tuple[str, str, str], HearingTargetE2EFamilyRegistration
]:
    """Return the closed internal families used by the explicit HEARING provider."""

    return TARGET_E2E_HEARING_FAMILY_REGISTRY


@dataclass(frozen=True, slots=True)
class HearingTargetE2EExecutionContext:
    execution: GatewayExecution
    activation_id: str
    room_fencing_token: int
    graph_lease_fencing_token: int
    command_hash: str
    command_envelope_hash: str
    execution_provider: str
    execution_model: str
    registry_binding_hash: str
    code_build_id: str

    @classmethod
    def from_gateway_execution(
        cls,
        execution: GatewayExecution,
    ) -> HearingTargetE2EExecutionContext:
        if type(execution) is not GatewayExecution:
            raise HearingGraphContractError("HEARING_TARGET_GATEWAY_EXECUTION_REQUIRED")
        admission = execution.admission
        command = getattr(admission, "command", None)
        thread = getattr(admission, "thread", None)
        command_binding = getattr(admission, "binding", None)
        registry_record = getattr(admission, "registry", None)
        registry_binding = getattr(registry_record, "binding", None)
        authority = getattr(admission, "candidate_authority", None)
        fence = execution.fence
        if (
            not isinstance(command, RoomGraphCommand)
            or command.room_type != "HEARING"
            or not isinstance(thread, ThreadIdentity)
            or not isinstance(fence, GraphFenceContext)
            or authority is None
            or command_binding is None
            or registry_binding is None
            or _enum_value(getattr(registry_record, "state", None))
            != "ACTIVE_CANDIDATE"
            or getattr(registry_record, "loadable", None) is not True
            or _enum_value(getattr(command_binding, "execution_lane", None))
            != TARGET_E2E_EXECUTION_LANE
            or _enum_value(getattr(fence, "execution_lane", None))
            != TARGET_E2E_EXECUTION_LANE
        ):
            raise HearingGraphContractError("HEARING_TARGET_GATEWAY_CONTEXT_INVALID")

        activation_id = getattr(command_binding, "activation_id", None)
        room_fencing_token = getattr(command_binding, "room_fencing_token", None)
        command_hash = getattr(command_binding, "command_hash", None)
        command_envelope_hash = getattr(command_binding, "command_envelope_hash", None)
        execution_provider = getattr(fence, "execution_provider", None)
        execution_model = getattr(fence, "execution_model", None)
        registry_binding_hash = getattr(registry_binding, "binding_hash", None)
        code_build_id = getattr(registry_binding, "code_build_id", None)
        authority_activation_id = getattr(authority, "activation_id", None)
        authority_context = getattr(authority, "context", None)
        allowed_rooms = getattr(authority_context, "allowedRoomTypes", None)
        invocation = command.invocation_context
        if (
            not isinstance(activation_id, str)
            or _ACTIVATION_ID.fullmatch(activation_id) is None
            or authority_activation_id != activation_id
            or getattr(fence, "activation_id", None) != activation_id
            or getattr(fence, "room_fencing_token", None) != room_fencing_token
            or getattr(fence, "command_hash", None) != command_hash
            or getattr(fence, "command_envelope_hash", None) != command_envelope_hash
            or not isinstance(allowed_rooms, (tuple, list))
            or "HEARING" not in allowed_rooms
            or isinstance(room_fencing_token, bool)
            or not isinstance(room_fencing_token, int)
            or room_fencing_token < 1
            or not isinstance(command_hash, str)
            or _SHA256.fullmatch(command_hash) is None
            or not hmac.compare_digest(
                command_hash,
                canonical_sha256(command.model_dump(mode="json", exclude_none=True)),
            )
            or not isinstance(command_envelope_hash, str)
            or _SHA256.fullmatch(command_envelope_hash) is None
            or not isinstance(execution_provider, str)
            or not 1 <= len(execution_provider) <= 64
            or not execution_provider.strip()
            or not isinstance(execution_model, str)
            or not 1 <= len(execution_model) <= 128
            or not execution_model.strip()
            or not isinstance(registry_binding_hash, str)
            or _SHA256.fullmatch(registry_binding_hash) is None
            or getattr(fence, "binding_hash", None) != registry_binding_hash
            or not isinstance(code_build_id, str)
            or _IDENTIFIER.fullmatch(code_build_id) is None
            or getattr(fence, "code_build_id", None) != code_build_id
            or isinstance(fence.fencing_token, bool)
            or not isinstance(fence.fencing_token, int)
            or fence.fencing_token < 1
            or (
                command.graph_key,
                command.graph_version,
                command.checkpoint_schema_version,
                command.schema_version,
                invocation.prompt_profile_id,
                invocation.model_profile_id,
                invocation.output_schema_version,
                invocation.policy_version,
                invocation.guardrail_version,
            )
            != (
                getattr(registry_binding, "graph_key", None),
                getattr(registry_binding, "graph_version", None),
                getattr(registry_binding, "checkpoint_schema_version", None),
                getattr(registry_binding, "command_schema_version", None),
                getattr(registry_binding, "prompt_version", None),
                getattr(registry_binding, "model_profile_id", None),
                getattr(registry_binding, "output_schema_version", None),
                getattr(registry_binding, "policy_version", None),
                getattr(registry_binding, "guardrail_version", None),
            )
            or getattr(registry_binding, "result_schema_version", None)
            != "room-graph-result.v1"
            or not isinstance(getattr(registry_binding, "tool_policy_version", None), str)
            or invocation.tool_capabilities
        ):
            raise HearingGraphContractError("HEARING_TARGET_AUTHORITY_BINDING_MISMATCH")
        if (
            fence.command_id != command.command_id
            or fence.request_hash != command.request_hash
            or fence.room_epoch != command.room_epoch
            or fence.graph_key != command.graph_key
            or fence.graph_version != command.graph_version
            or fence.checkpoint_schema_version != command.checkpoint_schema_version
            or thread.thread_id != command.thread_id
            or thread.tenant_surrogate != command.tenant_surrogate
            or thread.case_id != command.case_id
            or thread.room_type.value != command.room_type
            or thread.room_epoch != command.room_epoch
            or thread.graph_key != command.graph_key
            or thread.graph_version != command.graph_version
            or thread.checkpoint_schema_version != command.checkpoint_schema_version
        ):
            raise HearingGraphContractError("HEARING_TARGET_FENCE_BINDING_MISMATCH")
        references = [
            command.domain_snapshot_ref,
            *([command.event_ref] if command.event_ref is not None else []),
        ]
        if any(not reference.uri.startswith("urn:target-e2e:") for reference in references):
            raise HearingGraphContractError("HEARING_TARGET_INPUT_AUTHORITY_MISMATCH")
        return cls(
            execution=execution,
            activation_id=activation_id,
            room_fencing_token=room_fencing_token,
            graph_lease_fencing_token=fence.fencing_token,
            command_hash=command_hash,
            command_envelope_hash=command_envelope_hash,
            execution_provider=execution_provider,
            execution_model=execution_model,
            registry_binding_hash=registry_binding_hash,
            code_build_id=code_build_id,
        )

    def require_operation(
        self,
        operation: HearingOperation,
        request: BaseModel,
    ) -> HearingTargetE2EFamilyRegistration:
        identity = HEARING_OPERATION_IDENTITIES[operation]
        key = (
            identity.graph_key,
            identity.graph_version,
            identity.checkpoint_schema_version,
        )
        registration = TARGET_E2E_HEARING_FAMILY_REGISTRY.get(key)
        if registration is None:
            raise HearingGraphContractError("HEARING_TARGET_GRAPH_FAMILY_UNAVAILABLE")
        operation_binding = registration.operation_bindings.get(operation)
        command = self.execution.admission.command
        invocation = command.invocation_context
        request_stage = _enum_value(getattr(request, "stage_code", None))
        if (
            operation_binding is None
            or command.stage_code != operation_binding.command_stage_code
            or request_stage != operation_binding.request_stage_code
            or command.case_id != getattr(request, "case_id", None)
            or command.stage_sequence != getattr(request, "stage_sequence", None)
            or getattr(request, "flow_schema_version", None) != "hearing_flow.v2"
            or invocation.tool_capabilities != EMPTY_HEARING_TOOL_POLICY
        ):
            raise HearingGraphContractError("HEARING_TARGET_OPERATION_BINDING_MISMATCH")
        return registration


@dataclass(frozen=True, slots=True)
class HearingTargetE2ERuntimeBundle:
    graph: Any
    identity: HearingGraphIdentity
    context: HearingTargetE2EExecutionContext
    operation: HearingOperation
    request: BaseModel
    invocation: HearingGraphInvocation
    runtime_binding_sha256: str
    command_binding: HearingCommandBindingV1
    scope_binding: HearingScopeBindingV1
    recursion_limit: int

    async def astart(self) -> dict[str, Any]:
        if await self._checkpoint_values() is not None:
            raise HearingGraphContractError("HEARING_TARGET_RUNTIME_ALREADY_STARTED")
        state = new_hearing_graph_state(
            identity=self.identity,
            operation=self.operation,
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
            raise HearingGraphContractError("HEARING_TARGET_RECOVERY_CHECKPOINT_REQUIRED")
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
        values = await self._checkpoint_values()
        if values is not None and values.get("status") == "PROPOSED":
            self._validate_state(values)
            return dict(values)
        return await self.astart() if values is None else await self.aresume()

    async def astream(self) -> AsyncIterator[Any]:
        values = await self._checkpoint_values()
        if values is not None and values.get("status") == "PROPOSED":
            self._validate_state(values)
            return
        if values is None:
            graph_input: Mapping[str, Any] | None = new_hearing_graph_state(
                identity=self.identity,
                operation=self.operation,
                request=self.request,
                command_binding=self.command_binding,
                scope_binding=self.scope_binding,
            )
        else:
            self._validate_state(values)
            graph_input = None
        source = self.graph.astream(
            graph_input,
            self._config(),
            context=self.invocation,
            durability="sync",
            stream_mode=["messages", "updates"],
        )
        close = getattr(source, "aclose", None)
        if not callable(close):
            raise HearingGraphContractError("HEARING_TARGET_GRAPH_STREAM_NOT_CLOSABLE")
        try:
            async for candidate in source:
                yield candidate
        finally:
            await cast(Callable[[], Awaitable[None]], close)()

    async def completed_state(self) -> dict[str, Any]:
        values = await self._checkpoint_values()
        if values is None or values.get("status") != "PROPOSED":
            raise HearingGraphContractError("HEARING_TARGET_GRAPH_STREAM_INCOMPLETE")
        self._validate_state(values)
        return dict(values)

    async def terminal_checkpoint(self) -> tuple[str, str, int]:
        snapshot = await self.graph.aget_state(self._config())
        values = getattr(snapshot, "values", None)
        config = getattr(snapshot, "config", None)
        metadata = getattr(snapshot, "metadata", None)
        if (
            not isinstance(values, Mapping)
            or not isinstance(config, Mapping)
            or getattr(snapshot, "next", None) != ()
            or getattr(snapshot, "tasks", None) != ()
            or getattr(snapshot, "interrupts", None) != ()
            or not isinstance(metadata, Mapping)
            or metadata.get(_TARGET_RUNTIME_BINDING_METADATA_KEY)
            != self.runtime_binding_sha256
        ):
            raise HearingGraphContractError(
                "HEARING_TARGET_TERMINAL_CHECKPOINT_INVALID"
            )
        self._validate_state(values)
        configurable = config.get("configurable") or {}
        checkpoint_ns = configurable.get("checkpoint_ns", "")
        checkpoint_id = configurable.get("checkpoint_id")
        revision = values.get("cognitive_revision")
        if (
            configurable.get("thread_id") != self.command_binding["thread_id"]
            or configurable.get(FENCE_CONTEXT_KEY) != self.context.execution.fence
            or not isinstance(checkpoint_ns, str)
            or len(checkpoint_ns) > 128
            or not isinstance(checkpoint_id, str)
            or not checkpoint_id
            or len(checkpoint_id) > 128
            or isinstance(revision, bool)
            or not isinstance(revision, int)
            or revision < 1
        ):
            raise HearingGraphContractError(
                "HEARING_TARGET_TERMINAL_CHECKPOINT_INVALID"
            )
        return checkpoint_ns, checkpoint_id, revision

    def _config(self) -> dict[str, Any]:
        return dict(
            bind_fence_context(
                {
                    "configurable": {"thread_id": self.command_binding["thread_id"]},
                    "metadata": {
                        _TARGET_RUNTIME_BINDING_METADATA_KEY: (
                            self.runtime_binding_sha256
                        ),
                    },
                    "max_concurrency": 8,
                    "recursion_limit": self.recursion_limit,
                },
                self.context.execution.fence,
            )
        )

    async def _checkpoint_values(self) -> Mapping[str, Any] | None:
        snapshot = await self.graph.aget_state(self._config())
        if not snapshot.values:
            return None
        metadata = snapshot.metadata
        if not isinstance(metadata, Mapping) or metadata.get(
            _TARGET_RUNTIME_BINDING_METADATA_KEY
        ) != self.runtime_binding_sha256:
            raise HearingGraphContractError(
                "HEARING_TARGET_RECOVERY_RUNTIME_BINDING_MISMATCH"
            )
        return cast(Mapping[str, Any], snapshot.values)

    def _validate_state(self, state: Mapping[str, Any]) -> None:
        validate_hearing_recovery_state(
            state,
            identity=self.identity,
            operation=self.operation,
            request=self.request,
            command_binding=self.command_binding,
            scope_binding=self.scope_binding,
        )


def build_target_e2e_hearing_runtime_bundle(
    *,
    execution: GatewayExecution,
    operation: HearingOperation,
    request: BaseModel,
    invocation: HearingGraphInvocation,
    checkpointer: FencedPostgresSaver,
    shared_barrier_receipt_hash: str | None = None,
    recursion_limit: int = 64,
) -> HearingTargetE2ERuntimeBundle:
    if not isinstance(checkpointer, FencedPostgresSaver):
        raise HearingGraphContractError("HEARING_TARGET_FENCED_CHECKPOINTER_REQUIRED")
    if (
        not isinstance(request, BaseModel)
        or invocation.request is not request
        or not callable(invocation.execute)
    ):
        raise HearingGraphContractError("HEARING_TARGET_INVOCATION_CONTEXT_INVALID")
    if (
        isinstance(recursion_limit, bool)
        or not isinstance(recursion_limit, int)
        or recursion_limit < 16
        or recursion_limit > 256
    ):
        raise HearingGraphContractError("HEARING_TARGET_RECURSION_LIMIT_INVALID")

    context = HearingTargetE2EExecutionContext.from_gateway_execution(execution)
    registration = context.require_operation(operation, request)
    command = execution.admission.command
    thread = execution.admission.thread
    fence = execution.fence
    if thread.shared_session:
        if (
            not isinstance(shared_barrier_receipt_hash, str)
            or _SHA256.fullmatch(shared_barrier_receipt_hash) is None
        ):
            raise HearingGraphContractError("HEARING_TARGET_SHARED_BARRIER_REQUIRED")
    elif shared_barrier_receipt_hash is not None:
        raise HearingGraphContractError("HEARING_TARGET_PRIVATE_BARRIER_FORBIDDEN")

    operation_key = (
        f"hearing.agent:{command.tenant_surrogate}:{command.case_id}:"
        f"{command.room_epoch}:{command.stage_sequence}:{operation.value}:"
        f"{command.request_hash}"
    )
    if len(operation_key) > 512:
        raise HearingGraphContractError("HEARING_TARGET_OPERATION_KEY_TOO_LONG")
    command_binding: HearingCommandBindingV1 = {
        "schema_version": "hearing-command-binding.v1",
        "command_id": command.command_id,
        "operation_key": operation_key,
        "command_request_hash": command.request_hash,
        "thread_id": command.thread_id,
        "tenant_surrogate": command.tenant_surrogate,
        "room_epoch": command.room_epoch,
        "process_revision": command.process_revision,
        "java_room_fencing_token": context.room_fencing_token,
        "graph_lease_owner_id": fence.owner_id,
        "graph_lease_fencing_token": fence.fencing_token,
    }
    references = [
        command.domain_snapshot_ref,
        *([command.event_ref] if command.event_ref is not None else []),
    ]
    scope_binding: HearingScopeBindingV1 = {
        "schema_version": "hearing-scope-binding.v1",
        "state_scope": "SHARED" if thread.shared_session else "ACTOR_PRIVATE",
        "actor_scope_hash": thread.actor_scope_hash,
        "authorized_artifact_refs": [
            {
                "artifact_id": reference.artifact_id,
                "schema_version": reference.schema_version,
                "uri": reference.uri,
                "sha256": reference.sha256,
            }
            for reference in references
        ],
        "shared_barrier_receipt_hash": shared_barrier_receipt_hash,
    }
    runtime_binding_sha256 = canonical_sha256(
        {
            "schema_version": "hearing-target-e2e-runtime-binding.v1",
            "execution_lane": TARGET_E2E_EXECUTION_LANE,
            "activation_id": context.activation_id,
            "room_fencing_token": context.room_fencing_token,
            "graph_lease_fencing_token": context.graph_lease_fencing_token,
            "command_hash": context.command_hash,
            "command_envelope_hash": context.command_envelope_hash,
            "registry_binding_hash": context.registry_binding_hash,
            "code_build_id": context.code_build_id,
            "graph_identity": registration.identity.identity,
            "operation": operation.value,
            "input_hash": request_hash(request),
            "command_binding": command_binding,
            "scope_binding": scope_binding,
        }
    )
    graph = _build_family(registration.identity).compile(checkpointer=checkpointer)
    if graph.checkpointer is not checkpointer:
        raise HearingGraphContractError("HEARING_TARGET_CHECKPOINTER_BINDING_INVALID")
    return HearingTargetE2ERuntimeBundle(
        graph=graph,
        identity=registration.identity,
        context=context,
        operation=operation,
        request=request,
        invocation=invocation,
        runtime_binding_sha256=runtime_binding_sha256,
        command_binding=command_binding,
        scope_binding=scope_binding,
        recursion_limit=recursion_limit,
    )


@dataclass(frozen=True, slots=True)
class HearingTargetE2EProposalMaterial:
    source: HearingTargetE2EProposalSource
    proposal_hash: str
    payload: HearingTargetE2EStoredPayload
    checkpoint_ns: str
    checkpoint_id: str
    cognitive_revision: int
    runtime_binding_sha256: str

    @property
    def formal_sink_eligible(self) -> Literal[False]:
        return False


@dataclass(frozen=True, slots=True)
class _HearingTargetE2EExecutionPlan:
    context: HearingTargetE2EExecutionContext
    loaded: HearingTargetE2ELoadedInvocation
    registration: HearingTargetE2EFamilyRegistration
    bundle: HearingTargetE2ERuntimeBundle


class HearingTargetE2ERuntimeAdapter:
    """Execute a verified candidate Hearing command and emit only a proposal source."""

    room_type = RoomType.HEARING

    def __init__(
        self,
        *,
        checkpointer: FencedPostgresSaver,
        invocation_provider: HearingTargetE2EInvocationProvider,
        payload_store: HearingTargetE2EPayloadStore,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        if not isinstance(checkpointer, FencedPostgresSaver):
            raise HearingGraphContractError("HEARING_TARGET_FENCED_CHECKPOINTER_REQUIRED")
        if not callable(getattr(invocation_provider, "load", None)):
            raise HearingGraphContractError("HEARING_TARGET_PROVIDER_REQUIRED")
        if not callable(getattr(payload_store, "put", None)):
            raise HearingGraphContractError("HEARING_TARGET_PROPOSAL_STORE_REQUIRED")
        self._checkpointer = checkpointer
        self._invocation_provider = invocation_provider
        self._payload_store = payload_store
        self._clock = clock or (lambda: datetime.now(timezone.utc))

    async def stream(
        self,
        execution: GatewayExecution,
    ) -> AsyncIterator[AgentStreamEvent]:
        sequence = 0
        yield self._event(
            execution,
            sequence_no=sequence,
            event_type="attempt_started",
            payload=AgentStreamPayload(node="hearing_target_e2e"),
        )
        sequence += 1
        plan = await self._prepare_execution(execution)
        operation_binding = plan.registration.operation_bindings[plan.loaded.operation]
        visible_deltas: list[str] = []
        source = plan.bundle.astream()
        close = getattr(source, "aclose", None)
        if not callable(close):
            raise HearingGraphContractError("HEARING_TARGET_GRAPH_STREAM_NOT_CLOSABLE")
        try:
            async for candidate in source:
                for payload in self._visible_payloads(candidate, operation_binding):
                    visible_deltas.append(cast(str, payload.delta))
                    yield self._event(
                        execution,
                        sequence_no=sequence,
                        event_type="visible_delta",
                        payload=payload,
                    )
                    sequence += 1
        finally:
            await cast(Callable[[], Awaitable[None]], close)()
        state = await plan.bundle.completed_state()
        self._require_visible_terminal(state, visible_deltas)
        material = await self._proposal_material(execution, plan, state)
        result = await self._commit_terminal(execution, material)
        yield self._event(
            execution,
            sequence_no=sequence,
            event_type="final",
            payload=AgentStreamPayload(
                final_result_ref=result.result_ref,
                final_result_hash=result.result_hash,
            ),
        )

    async def _commit_terminal(
        self,
        execution: GatewayExecution,
        material: HearingTargetE2EProposalMaterial,
    ) -> Any:
        command = execution.admission.command
        invocation = command.invocation_context
        materializer = TerminalResultMaterializer(
            thread_id=execution.fence.thread_id,
            request_hash=command.request_hash,
            draft=CompletedDraft(status="COMPLETED"),
            target_proposal_source=TargetE2ERoomProposalSource.model_validate(
                material.source.model_dump(mode="json")
            ),
            bindings=ResultBindings(
                command_id=command.command_id,
                logical_run_id=command.logical_run_id,
                attempt_id=command.attempt_id,
                graph_key=command.graph_key,
                graph_version=command.graph_version,
                checkpoint_id=material.checkpoint_id,
                cognitive_revision=material.cognitive_revision,
                public_event_proposals=(),
                artifact_operations=(
                    ArtifactOperation(
                        operation="PROPOSE_CREATE",
                        artifact=ArtifactPointer(
                            artifact_id=material.source.proposal.proposal_id,
                            schema_version=material.payload.payload_schema_version,
                            uri=material.payload.payload_ref,
                            sha256=material.payload.payload_hash,
                        ),
                    ),
                ),
                usage=Usage(input_tokens=0, output_tokens=0, total_tokens=0),
                execution_metadata=ExecutionMetadata(
                    prompt_version=invocation.prompt_profile_id,
                    model_profile_id=invocation.model_profile_id,
                    schema_version=invocation.output_schema_version,
                    policy_version=invocation.policy_version,
                    guardrail_version=invocation.guardrail_version,
                ),
            ),
        )
        result = materializer.materialize(
            material.checkpoint_ns,
            material.checkpoint_id,
            fence=execution.fence,
        )
        config = bind_fence_context(
            {
                "configurable": {
                    "thread_id": execution.fence.thread_id,
                    "checkpoint_ns": material.checkpoint_ns,
                    "checkpoint_id": material.checkpoint_id,
                },
                "metadata": {
                    _TARGET_RUNTIME_BINDING_METADATA_KEY: (
                        material.runtime_binding_sha256
                    )
                },
                "max_concurrency": 8,
                "recursion_limit": 64,
            },
            execution.fence,
        )
        await self._checkpointer.acommit_external_terminal(
            config,
            ExternalTerminalCommit(
                result=result,
                cognitive_revision=material.cognitive_revision,
            ),
        )
        return result

    async def execute(
        self,
        execution: GatewayExecution,
    ) -> HearingTargetE2EProposalMaterial:
        plan = await self._prepare_execution(execution)
        state = await plan.bundle.arun()
        return await self._proposal_material(execution, plan, state)

    async def _prepare_execution(
        self,
        execution: GatewayExecution,
    ) -> _HearingTargetE2EExecutionPlan:
        context = HearingTargetE2EExecutionContext.from_gateway_execution(execution)
        loaded = await self._invocation_provider.load(execution)
        _require_loaded_invocation(context, loaded)
        operation = loaded.operation
        request = loaded.request
        registration = context.require_operation(operation, request)
        bundle = build_target_e2e_hearing_runtime_bundle(
            execution=execution,
            operation=operation,
            request=request,
            invocation=loaded.invocation,
            checkpointer=self._checkpointer,
            shared_barrier_receipt_hash=loaded.shared_barrier_receipt_hash,
        )
        return _HearingTargetE2EExecutionPlan(
            context=context,
            loaded=loaded,
            registration=registration,
            bundle=bundle,
        )

    async def _proposal_material(
        self,
        execution: GatewayExecution,
        plan: _HearingTargetE2EExecutionPlan,
        state: Mapping[str, Any],
    ) -> HearingTargetE2EProposalMaterial:
        context = plan.context
        loaded = plan.loaded
        registration = plan.registration
        bundle = plan.bundle
        operation = loaded.operation
        checkpoint_ns, checkpoint_id, checkpoint_revision = (
            await bundle.terminal_checkpoint()
        )
        payload = state.get("proposal")
        revision = state.get("cognitive_revision")
        operation_binding = registration.operation_bindings[operation]
        if (
            not isinstance(payload, Mapping)
            or payload.get("schema_version") != operation_binding.result_schema_version
            or isinstance(revision, bool)
            or not isinstance(revision, int)
            or revision < 1
            or revision != checkpoint_revision
        ):
            raise HearingGraphContractError("HEARING_TARGET_PROPOSAL_BINDING_INVALID")
        payload_document = dict(payload)
        payload_bytes = canonicalize(payload_document)
        if not payload_bytes or len(payload_bytes) > MAX_HEARING_PROPOSAL_BYTES:
            raise HearingGraphContractError("HEARING_TARGET_PROPOSAL_SIZE_INVALID")
        payload_hash = canonical_sha256(payload_document)
        command = execution.admission.command
        proposal_id_hash = canonical_sha256(
            {
                "schema_version": "target-e2e-hearing-proposal-id.v1",
                "activation_id": context.activation_id,
                "command_id": command.command_id,
                "operation": operation.value,
                "hearing_graph_identity": registration.identity.identity,
                "hearing_graph_version": registration.identity.graph_version,
                "hearing_checkpoint_schema_version": (
                    registration.identity.checkpoint_schema_version
                ),
                "payload_hash": payload_hash,
            }
        )
        proposal_id = f"proposal-hearing-{proposal_id_hash[:32]}"
        stored = await self._payload_store.put(
            execution=execution,
            operation=operation,
            proposal_id=proposal_id,
            payload_schema_version=operation_binding.result_schema_version,
            payload=payload_bytes,
            payload_hash=payload_hash,
            checkpoint_ns=checkpoint_ns,
            checkpoint_id=checkpoint_id,
            cognitive_revision=revision,
        )
        if not isinstance(stored, HearingTargetE2EStoredPayload) or (
            stored.proposal_id,
            stored.payload_schema_version,
            stored.payload_hash,
            stored.size_bytes,
        ) != (
            proposal_id,
            operation_binding.result_schema_version,
            payload_hash,
            len(payload_bytes),
        ):
            raise HearingGraphContractError("HEARING_TARGET_STORED_PROPOSAL_MISMATCH")
        proposal = HearingTargetE2EProposal(
            proposal_id=proposal_id,
            command_id=command.command_id,
            logical_run_id=command.logical_run_id,
            attempt_id=command.attempt_id,
            payload_schema_version=stored.payload_schema_version,
            payload_ref=stored.payload_ref,
            payload_hash=stored.payload_hash,
            terminal_class="COMPLETED",
            formal_authority=False,
        )
        source = HearingTargetE2EProposalSource(proposal=proposal)
        return HearingTargetE2EProposalMaterial(
            source=source,
            proposal_hash=source.proposal_hash,
            payload=stored,
            checkpoint_ns=checkpoint_ns,
            checkpoint_id=checkpoint_id,
            cognitive_revision=revision,
            runtime_binding_sha256=bundle.runtime_binding_sha256,
        )

    @staticmethod
    def _visible_payloads(
        candidate: Any,
        operation_binding: HearingTargetE2EOperationBinding,
    ) -> tuple[AgentStreamPayload, ...]:
        if not isinstance(candidate, tuple) or len(candidate) != 2:
            raise HearingGraphContractError("HEARING_TARGET_STREAM_EVENT_INVALID")
        mode, value = candidate
        if mode == "updates":
            return ()
        if mode != "messages" or not isinstance(value, tuple) or len(value) != 2:
            raise HearingGraphContractError("HEARING_TARGET_STREAM_EVENT_INVALID")
        chunk, metadata = value
        if not isinstance(chunk, AIMessageChunk):
            return ()
        governed = governed_events_from_chunk(chunk)
        if not governed:
            return ()
        if not isinstance(metadata, Mapping):
            raise HearingGraphContractError("HEARING_TARGET_STREAM_METADATA_INVALID")
        public_node = operation_binding.model_nodes[-1]
        payloads: list[AgentStreamPayload] = []
        for event in governed:
            delta = event.get("delta")
            if (
                event.get("schema_version") != "governed-model-event.v1"
                or event.get("event_type") != "visible_delta"
                or event.get("node_name") != public_node
                or metadata.get("langgraph_node") != public_node
                or event.get("field") != "public_message"
                or not isinstance(delta, str)
                or not delta
            ):
                raise HearingGraphContractError("HEARING_TARGET_VISIBLE_DELTA_INVALID")
            payloads.append(
                AgentStreamPayload(
                    node=public_node,
                    field="public_message",
                    delta=delta,
                )
            )
        return tuple(payloads)

    @staticmethod
    def _require_visible_terminal(
        state: Mapping[str, Any],
        visible_deltas: list[str],
    ) -> None:
        if not visible_deltas:
            return
        proposal = state.get("proposal")
        if (
            not isinstance(proposal, Mapping)
            or proposal.get("public_message") != "".join(visible_deltas)
        ):
            raise HearingGraphContractError("HEARING_TARGET_VISIBLE_TERMINAL_MISMATCH")

    def _event(
        self,
        execution: GatewayExecution,
        *,
        sequence_no: int,
        event_type: Literal["attempt_started", "visible_delta", "final"],
        payload: AgentStreamPayload,
    ) -> AgentStreamEvent:
        occurred_at = self._clock()
        if not isinstance(occurred_at, datetime) or occurred_at.utcoffset() is None:
            raise HearingGraphContractError("HEARING_TARGET_CLOCK_INVALID")
        command = execution.admission.command
        return AgentStreamEvent(
            schema_version="agent-stream.v3",
            run_id=command.logical_run_id,
            attempt_id=command.attempt_id,
            sequence_no=sequence_no,
            event_type=event_type,
            audience=command.actor_scope.audience,
            occurred_at=occurred_at,
            payload=payload,
        )


def build_target_e2e_hearing_provider(
    *,
    checkpointer: FencedPostgresSaver,
    invocation_provider: HearingTargetE2EInvocationProvider,
    payload_store: HearingTargetE2EPayloadStore,
    clock: Callable[[], datetime] | None = None,
) -> HearingTargetE2ERuntimeAdapter:
    """Build the single HEARING provider imported by the all-room composite."""

    return HearingTargetE2ERuntimeAdapter(
        checkpointer=checkpointer,
        invocation_provider=invocation_provider,
        payload_store=payload_store,
        clock=clock,
    )


def _enum_value(value: object) -> object:
    return getattr(value, "value", value)


def _require_loaded_invocation(
    context: HearingTargetE2EExecutionContext,
    loaded: HearingTargetE2ELoadedInvocation,
) -> None:
    if not isinstance(loaded, HearingTargetE2ELoadedInvocation):
        raise HearingGraphContractError("HEARING_TARGET_LOADED_INVOCATION_INVALID")
    command = context.execution.admission.command
    event = command.event_ref
    invocation = loaded.invocation
    if (
        not isinstance(loaded.operation, HearingOperation)
        or not isinstance(loaded.request, BaseModel)
        or not isinstance(invocation, HearingGraphInvocation)
        or invocation.request is not loaded.request
        or not callable(invocation.execute)
        or loaded.snapshot_uri != command.domain_snapshot_ref.uri
        or loaded.snapshot_hash != command.domain_snapshot_ref.sha256
        or loaded.event_uri != (event.uri if event is not None else None)
        or loaded.event_hash != (event.sha256 if event is not None else None)
    ):
        raise HearingGraphContractError("HEARING_TARGET_LOADED_INVOCATION_MISMATCH")
    context.require_operation(loaded.operation, loaded.request)


__all__ = [
    "HearingTargetE2EExecutionContext",
    "HearingTargetE2EFamilyRegistration",
    "HearingTargetE2EInvocationProvider",
    "HearingTargetE2ELoadedInvocation",
    "HearingTargetE2EPayloadStore",
    "HearingTargetE2EProposal",
    "HearingTargetE2EProposalMaterial",
    "HearingTargetE2EProposalSource",
    "HearingTargetE2ERuntimeAdapter",
    "HearingTargetE2ERuntimeBundle",
    "HearingTargetE2EStoredPayload",
    "TARGET_E2E_HEARING_FAMILY_REGISTRY",
    "build_target_e2e_hearing_provider",
    "build_target_e2e_hearing_runtime_bundle",
    "target_e2e_hearing_family_registrations",
]
