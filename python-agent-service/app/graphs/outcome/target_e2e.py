"""Target-E2E REVIEW executor backed by the private Outcome LangGraph.

The module consumes only authority already established by the shared Graph gateway. It
does not accept an activation token and it cannot write Domain state or invoke tools.
"""

from __future__ import annotations

from collections.abc import Callable, Mapping
from dataclasses import dataclass
from typing import Any, Literal, Protocol, cast

from langchain_core.runnables import RunnableConfig
from pydantic import BaseModel, ConfigDict, Field

from app.agents.review_copilot import ReviewCopilot
from app.contracts.v1.codec import canonical_sha256, canonicalize
from app.contracts.v1.models import RoomGraphCommand
from app.graph_runtime.checkpoint import FencedPostgresSaver, bind_fence_context
from app.graph_runtime.gateway import GatewayExecution
from app.graph_runtime.persistence_models import GraphFenceContext
from app.graph_runtime.registry import VersionBinding
from app.graph_runtime.target_e2e_composite import (
    TARGET_E2E_CHECKPOINT_SCHEMA_VERSION,
    TARGET_E2E_GRAPH_KEY,
    TARGET_E2E_GRAPH_VERSION,
    TARGET_E2E_OUTPUT_SCHEMA_VERSION,
)
from app.graphs.outcome.contracts import OUTCOME_REVIEW_IDENTITY
from app.graphs.outcome.errors import OutcomeReviewContractError
from app.graphs.outcome.graph import compile_outcome_review_v1_graph
from app.graphs.outcome.state import (
    OutcomeReviewInvocation,
    OutcomeReviewPrivateCommand,
    OutcomeReviewProjection,
    answer_hash,
    new_outcome_review_state,
    validate_outcome_review_recovery_state,
)
from app.schemas import ReviewCopilotAnswer, ReviewCopilotRequest, ReviewStatement

TARGET_E2E_EXECUTION_LANE = "TARGET_E2E_CANDIDATE"
TARGET_E2E_REVIEW_ROOM_TYPE = "REVIEW"
_RUNTIME_BINDING_METADATA_KEY = "outcome_target_e2e_runtime_binding_sha256"


class OutcomeTargetE2EProposalPayload(BaseModel):
    """Private advisory payload stored outside the Graph checkpoint."""

    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)

    schema_version: Literal["outcome-review-proposal.v1"] = "outcome-review-proposal.v1"
    review_task_id: str = Field(min_length=1, max_length=128)
    packet_id: str = Field(min_length=1, max_length=128)
    advisory_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    citation_refs: tuple[str, ...] = Field(max_length=256)
    answer: ReviewCopilotAnswer
    formal_sink_eligible: Literal[False] = False
    formal_authority: Literal[False] = False
    external_effects_enabled: Literal[False] = False
    tools_enabled: Literal[False] = False


class OutcomeTargetE2EProposal(BaseModel):
    """Exact REVIEW member of target-e2e-room-proposal-source.v1."""

    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)

    schema_version: Literal["target-e2e-review-proposal.v1"] = "target-e2e-review-proposal.v1"
    proposal_id: str = Field(
        min_length=1,
        max_length=128,
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$",
    )
    command_id: str = Field(min_length=1, max_length=128)
    logical_run_id: str = Field(min_length=1, max_length=128)
    attempt_id: str = Field(min_length=1, max_length=128)
    payload_schema_version: Literal["outcome-review-proposal.v1"] = "outcome-review-proposal.v1"
    payload_ref: str = Field(
        min_length=1,
        max_length=512,
        pattern=r"^urn:target-e2e:proposal:",
    )
    payload_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    terminal_class: Literal["NEEDS_REVIEW"] = "NEEDS_REVIEW"
    formal_authority: Literal[False] = False


class OutcomeTargetE2EProposalSource(BaseModel):
    """Canonical source whose ``proposal`` member is the proposal-hash preimage."""

    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)

    schema_version: Literal["target-e2e-room-proposal-source.v1"] = (
        "target-e2e-room-proposal-source.v1"
    )
    room_type: Literal["REVIEW"] = "REVIEW"
    proposal: OutcomeTargetE2EProposal

    @property
    def proposal_hash(self) -> str:
        return canonical_sha256(self.proposal.model_dump(mode="json"))


@dataclass(frozen=True, slots=True)
class StoredOutcomeTargetE2EProposal:
    artifact_id: str
    schema_version: str
    uri: str
    sha256: str
    size_bytes: int

    def __post_init__(self) -> None:
        if (
            not self.artifact_id
            or len(self.artifact_id) > 128
            or self.schema_version != OUTCOME_REVIEW_IDENTITY.proposal_payload_schema_version
            or not self.uri.startswith("urn:target-e2e:proposal:review:")
            or len(self.uri) > 512
            or len(self.sha256) != 64
            or any(character not in "0123456789abcdef" for character in self.sha256)
            or self.size_bytes < 1
            or self.size_bytes > 32_768
        ):
            raise OutcomeReviewContractError("OUTCOME_TARGET_E2E_STORED_PROPOSAL_INVALID")


@dataclass(frozen=True, slots=True)
class LoadedOutcomeTargetE2EInvocation:
    command: OutcomeReviewPrivateCommand
    request: ReviewCopilotRequest
    reviewer_actor_hash: str
    answerer: Callable[[ReviewCopilotRequest], ReviewCopilotAnswer]
    validate_answer: Callable[[ReviewCopilotRequest, ReviewCopilotAnswer], ReviewCopilotAnswer]
    snapshot_uri: str
    snapshot_hash: str
    event_uri: str | None
    event_hash: str | None


class OutcomeTargetE2EInvocationProvider(Protocol):
    async def load(
        self,
        execution: GatewayExecution,
    ) -> LoadedOutcomeTargetE2EInvocation: ...


class OutcomeTargetE2EProposalStore(Protocol):
    async def put(
        self,
        execution: GatewayExecution,
        *,
        proposal_id: str,
        payload: bytes,
        payload_hash: str,
        checkpoint_ns: str,
        checkpoint_id: str,
        cognitive_revision: int,
    ) -> StoredOutcomeTargetE2EProposal: ...


@dataclass(frozen=True, slots=True)
class OutcomeTargetE2EExecutionContext:
    """Candidate authority projected from one shared-gateway execution."""

    execution: GatewayExecution
    activation_id: str
    room_fencing_token: int
    graph_lease_fencing_token: int
    command_hash: str
    command_envelope_hash: str
    registry_binding_hash: str
    code_build_id: str

    @classmethod
    def from_gateway_execution(
        cls,
        execution: GatewayExecution,
    ) -> OutcomeTargetE2EExecutionContext:
        if type(execution) is not GatewayExecution:
            raise OutcomeReviewContractError("OUTCOME_TARGET_E2E_GATEWAY_EXECUTION_REQUIRED")
        admission = execution.admission
        candidate_authority = getattr(admission, "candidate_authority", None)
        binding = admission.binding
        registry = admission.registry.binding
        command = admission.command
        fence = execution.fence
        lane = _wire_value(getattr(binding, "execution_lane", None))
        fence_lane = _wire_value(getattr(fence, "execution_lane", None))
        activation_id = getattr(binding, "activation_id", None)
        room_fencing_token = getattr(binding, "room_fencing_token", None)
        command_hash = getattr(binding, "command_hash", None)
        envelope_hash = getattr(binding, "command_envelope_hash", None)
        if (
            candidate_authority is None
            or lane != TARGET_E2E_EXECUTION_LANE
            or fence_lane != TARGET_E2E_EXECUTION_LANE
            or getattr(candidate_authority, "activation_id", None) != activation_id
            or getattr(fence, "activation_id", None) != activation_id
            or getattr(fence, "room_fencing_token", None) != room_fencing_token
            or getattr(fence, "command_hash", None) != command_hash
            or getattr(fence, "command_envelope_hash", None) != envelope_hash
        ):
            raise OutcomeReviewContractError("OUTCOME_TARGET_E2E_GATEWAY_AUTHORITY_REQUIRED")
        _require_gateway_command_binding(command, fence, registry)
        if (
            not isinstance(activation_id, str)
            or not activation_id.startswith("p9act.v1.")
            or len(activation_id) != 41
            or any(character not in "0123456789abcdef" for character in activation_id[9:])
            or not _positive_int(room_fencing_token)
            or not _sha256(command_hash)
            or not _sha256(envelope_hash)
            or not _positive_int(fence.fencing_token)
        ):
            raise OutcomeReviewContractError("OUTCOME_TARGET_E2E_GATEWAY_AUTHORITY_INVALID")
        return cls(
            execution=execution,
            activation_id=activation_id,
            room_fencing_token=room_fencing_token,
            graph_lease_fencing_token=fence.fencing_token,
            command_hash=command_hash,
            command_envelope_hash=envelope_hash,
            registry_binding_hash=registry.binding_hash,
            code_build_id=registry.code_build_id,
        )


@dataclass(frozen=True, slots=True)
class OutcomeTargetE2EExecutionResult:
    payload: OutcomeTargetE2EProposalPayload
    payload_bytes: bytes
    source: OutcomeTargetE2EProposalSource
    checkpoint_ns: str
    checkpoint_id: str
    cognitive_revision: int

    @property
    def payload_hash(self) -> str:
        return self.source.proposal.payload_hash

    @property
    def proposal_hash(self) -> str:
        return self.source.proposal_hash

    @property
    def formal_sink_eligible(self) -> Literal[False]:
        return False


@dataclass(frozen=True, slots=True)
class OutcomeTargetE2EExecutorRegistration:
    """Process-local exact binding; the shared registry must import it explicitly."""

    binding: VersionBinding
    executor: CompiledOutcomeTargetE2EExecutor
    provider: str
    model: str

    def __post_init__(self) -> None:
        require_exact_outcome_target_e2e_registry_binding(self.binding)
        if not self.provider or len(self.provider) > 64 or not self.model or len(self.model) > 128:
            raise OutcomeReviewContractError("OUTCOME_TARGET_E2E_PROVIDER_BINDING_INVALID")


class CompiledOutcomeTargetE2EExecutor:
    """Run the no-tool graph and publish only an immutable advisory proposal."""

    def __init__(
        self,
        *,
        saver: FencedPostgresSaver,
        invocation_provider: OutcomeTargetE2EInvocationProvider,
        proposal_store: OutcomeTargetE2EProposalStore,
    ) -> None:
        if not isinstance(saver, FencedPostgresSaver):
            raise OutcomeReviewContractError("OUTCOME_TARGET_E2E_FENCED_SAVER_REQUIRED")
        if not callable(getattr(invocation_provider, "load", None)) or not callable(
            getattr(proposal_store, "put", None)
        ):
            raise OutcomeReviewContractError("OUTCOME_TARGET_E2E_PORTS_INCOMPLETE")
        self._saver = saver
        self._invocation_provider = invocation_provider
        self._proposal_store = proposal_store

    async def execute(self, execution: GatewayExecution) -> OutcomeTargetE2EExecutionResult:
        authority = OutcomeTargetE2EExecutionContext.from_gateway_execution(execution)
        loaded = await self._invocation_provider.load(execution)
        _require_loaded_invocation(authority, loaded)
        invocation = OutcomeReviewInvocation(
            request=loaded.request,
            reviewer_actor_hash=loaded.reviewer_actor_hash,
            answerer=loaded.answerer,
            validate_answer=loaded.validate_answer,
        )
        graph = compile_outcome_review_v1_graph(checkpointer=self._saver)
        if graph.checkpointer is not self._saver:
            raise OutcomeReviewContractError("OUTCOME_TARGET_E2E_CHECKPOINTER_BINDING_INVALID")
        runtime_binding_hash = canonical_sha256(
            {
                "schema_version": "outcome-target-e2e-runtime-binding.v1",
                "execution_lane": TARGET_E2E_EXECUTION_LANE,
                "activation_id": authority.activation_id,
                "room_fencing_token": authority.room_fencing_token,
                "graph_lease_fencing_token": authority.graph_lease_fencing_token,
                "command_hash": authority.command_hash,
                "command_envelope_hash": authority.command_envelope_hash,
                "registry_binding_hash": authority.registry_binding_hash,
                "code_build_id": authority.code_build_id,
                "private_state": new_outcome_review_state(
                    command=loaded.command,
                    request=loaded.request,
                ),
            }
        )
        config = _runtime_config(execution.fence, runtime_binding_hash)
        snapshot = await graph.aget_state(config)
        if snapshot.values:
            _require_recovery_snapshot(
                snapshot,
                runtime_binding_hash=runtime_binding_hash,
                loaded=loaded,
            )
            if snapshot.values.get("status") == "PROPOSED":
                projection = _reconstruct_projection(snapshot.values, invocation, loaded.command)
                final_snapshot = snapshot
            else:
                result = await graph.ainvoke(
                    None,
                    config,
                    context=invocation,
                    durability="sync",
                )
                projection = _projection(result)
                final_snapshot = await graph.aget_state(config)
        else:
            result = await graph.ainvoke(
                new_outcome_review_state(command=loaded.command, request=loaded.request),
                config,
                context=invocation,
                durability="sync",
            )
            projection = _projection(result)
            final_snapshot = await graph.aget_state(config)
        checkpoint_ns, checkpoint_id, revision = _terminal_checkpoint(final_snapshot, execution)
        payload = _proposal_payload(projection)
        payload_bytes = canonicalize(payload.model_dump(mode="json"))
        payload_hash = canonical_sha256(payload.model_dump(mode="json"))
        proposal_identity_hash = canonical_sha256(
            {
                "schema_version": "outcome-target-e2e-proposal-identity.v1",
                "command_id": execution.admission.command.command_id,
                "logical_run_id": execution.admission.command.logical_run_id,
                "attempt_id": execution.admission.command.attempt_id,
                "payload_hash": payload_hash,
            }
        )
        proposal_id = f"proposal-review-{proposal_identity_hash[:32]}"
        stored = await self._proposal_store.put(
            execution,
            proposal_id=proposal_id,
            payload=payload_bytes,
            payload_hash=payload_hash,
            checkpoint_ns=checkpoint_ns,
            checkpoint_id=checkpoint_id,
            cognitive_revision=revision,
        )
        if (
            stored.artifact_id != proposal_id
            or stored.sha256 != payload_hash
            or stored.size_bytes != len(payload_bytes)
        ):
            raise OutcomeReviewContractError("OUTCOME_TARGET_E2E_STORE_BINDING_MISMATCH")
        source = build_outcome_target_e2e_proposal_source(
            command=execution.admission.command,
            proposal_id=proposal_id,
            payload_ref=stored.uri,
            payload_hash=payload_hash,
        )
        return OutcomeTargetE2EExecutionResult(
            payload=payload,
            payload_bytes=payload_bytes,
            source=source,
            checkpoint_ns=checkpoint_ns,
            checkpoint_id=checkpoint_id,
            cognitive_revision=revision,
        )


class DeterministicOutcomeTargetE2EModel:
    """Fixture-only model used to make isolated target-E2E hashes repeatable."""

    provider = "deterministic-fixture"
    model = "p9-review-fixture-v1"

    def __call__(self, request: ReviewCopilotRequest) -> ReviewCopilotAnswer:
        refs = sorted(
            {
                *request.available_fact_refs,
                *request.available_rule_refs,
                *request.available_draft_refs,
                *request.available_deliberation_refs,
            }
        )
        return ReviewCopilotAnswer(
            answer="Synthetic target-E2E review remains advisory and requires human review.",
            statements=[
                ReviewStatement(
                    kind="SUGGESTION",
                    text="Review the frozen packet and its authorized references.",
                    refs=refs,
                )
            ],
            fact_refs=sorted(request.available_fact_refs),
            rule_refs=sorted(request.available_rule_refs),
            draft_refs=sorted(request.available_draft_refs),
            deliberation_refs=sorted(request.available_deliberation_refs),
            uncertainties=["No formal decision is produced by the review graph."],
            suggested_review_focus=["Confirm the frozen packet before deciding."],
        )


def build_outcome_target_e2e_registration(
    *,
    binding: VersionBinding,
    saver: FencedPostgresSaver,
    invocation_provider: OutcomeTargetE2EInvocationProvider,
    proposal_store: OutcomeTargetE2EProposalStore,
    provider: str,
    model: str,
) -> OutcomeTargetE2EExecutorRegistration:
    return OutcomeTargetE2EExecutorRegistration(
        binding=binding,
        executor=CompiledOutcomeTargetE2EExecutor(
            saver=saver,
            invocation_provider=invocation_provider,
            proposal_store=proposal_store,
        ),
        provider=provider,
        model=model,
    )


def build_outcome_target_e2e_proposal_source(
    *,
    command: RoomGraphCommand,
    proposal_id: str,
    payload_ref: str,
    payload_hash: str,
) -> OutcomeTargetE2EProposalSource:
    if command.room_type != TARGET_E2E_REVIEW_ROOM_TYPE:
        raise OutcomeReviewContractError("OUTCOME_TARGET_E2E_REVIEW_COMMAND_REQUIRED")
    return OutcomeTargetE2EProposalSource(
        proposal=OutcomeTargetE2EProposal(
            proposal_id=proposal_id,
            command_id=command.command_id,
            logical_run_id=command.logical_run_id,
            attempt_id=command.attempt_id,
            payload_ref=payload_ref,
            payload_hash=payload_hash,
        )
    )


def require_exact_outcome_target_e2e_registry_binding(binding: VersionBinding) -> None:
    expected = {
        "graph_key": TARGET_E2E_GRAPH_KEY,
        "graph_version": TARGET_E2E_GRAPH_VERSION,
        "checkpoint_schema_version": TARGET_E2E_CHECKPOINT_SCHEMA_VERSION,
        "output_schema_version": TARGET_E2E_OUTPUT_SCHEMA_VERSION,
    }
    if type(binding) is not VersionBinding or any(
        getattr(binding, field, None) != value for field, value in expected.items()
    ):
        raise OutcomeReviewContractError("OUTCOME_TARGET_E2E_REGISTRY_BINDING_MISMATCH")


def _require_gateway_command_binding(
    command: RoomGraphCommand,
    fence: GraphFenceContext,
    registry: VersionBinding,
) -> None:
    invocation = command.invocation_context
    require_exact_outcome_target_e2e_registry_binding(registry)
    if (
        type(command) is not RoomGraphCommand
        or type(fence) is not GraphFenceContext
        or command.room_type != TARGET_E2E_REVIEW_ROOM_TYPE
        or command.graph_key != TARGET_E2E_GRAPH_KEY
        or command.graph_version != TARGET_E2E_GRAPH_VERSION
        or command.checkpoint_schema_version != TARGET_E2E_CHECKPOINT_SCHEMA_VERSION
        or command.schema_version != registry.command_schema_version
        or invocation.prompt_profile_id != registry.prompt_version
        or invocation.model_profile_id != registry.model_profile_id
        or invocation.output_schema_version != TARGET_E2E_OUTPUT_SCHEMA_VERSION
        or invocation.policy_version != registry.policy_version
        or invocation.guardrail_version != registry.guardrail_version
        or invocation.tool_capabilities
        or fence.thread_id != command.thread_id
        or fence.command_id != command.command_id
        or fence.request_hash != command.request_hash
        or fence.room_epoch != command.room_epoch
        or fence.graph_key != command.graph_key
        or fence.graph_version != command.graph_version
        or fence.checkpoint_schema_version != command.checkpoint_schema_version
        or getattr(fence, "binding_hash", None) != registry.binding_hash
        or getattr(fence, "code_build_id", None) != registry.code_build_id
    ):
        raise OutcomeReviewContractError("OUTCOME_TARGET_E2E_COMMAND_BINDING_MISMATCH")


def _require_loaded_invocation(
    authority: OutcomeTargetE2EExecutionContext,
    loaded: LoadedOutcomeTargetE2EInvocation,
) -> None:
    if not isinstance(loaded, LoadedOutcomeTargetE2EInvocation):
        raise OutcomeReviewContractError("OUTCOME_TARGET_E2E_LOADED_INPUT_MISMATCH")
    execution = authority.execution
    graph_command = execution.admission.command
    private = loaded.command
    event = graph_command.event_ref
    actor_hash = getattr(execution.admission.thread, "actor_scope_hash", None)
    if (
        not _sha256(actor_hash)
        or loaded.snapshot_uri != graph_command.domain_snapshot_ref.uri
        or loaded.snapshot_hash != graph_command.domain_snapshot_ref.sha256
        or loaded.event_uri != (event.uri if event is not None else None)
        or loaded.event_hash != (event.sha256 if event is not None else None)
        or private.thread_id != graph_command.thread_id
        or private.tenant_surrogate != graph_command.tenant_surrogate
        or private.case_id != graph_command.case_id
        or private.room_epoch != graph_command.room_epoch
        or private.process_revision != graph_command.process_revision
        or private.fencing_token != authority.room_fencing_token
        or private.reviewer_actor_hash != actor_hash
        or loaded.reviewer_actor_hash != actor_hash
        or private.frozen_packet_ref != graph_command.domain_snapshot_ref.uri
        or private.event_hash != (event.sha256 if event is not None else None)
    ):
        raise OutcomeReviewContractError("OUTCOME_TARGET_E2E_LOADED_INPUT_MISMATCH")
    new_outcome_review_state(command=private, request=loaded.request)


def _runtime_config(fence: GraphFenceContext, binding_hash: str) -> RunnableConfig:
    return bind_fence_context(
        {
            "configurable": {"thread_id": fence.thread_id, "checkpoint_ns": ""},
            "metadata": {_RUNTIME_BINDING_METADATA_KEY: binding_hash},
            "max_concurrency": 1,
            "recursion_limit": 12,
        },
        fence,
    )


def _require_recovery_snapshot(
    snapshot: Any,
    *,
    runtime_binding_hash: str,
    loaded: LoadedOutcomeTargetE2EInvocation,
) -> None:
    metadata = getattr(snapshot, "metadata", None)
    values = getattr(snapshot, "values", None)
    if (
        not isinstance(metadata, Mapping)
        or metadata.get(_RUNTIME_BINDING_METADATA_KEY) != runtime_binding_hash
        or not isinstance(values, dict)
    ):
        raise OutcomeReviewContractError("OUTCOME_TARGET_E2E_RECOVERY_BINDING_MISMATCH")
    validate_outcome_review_recovery_state(
        values,
        command=loaded.command,
        request=loaded.request,
    )


def _projection(state: Mapping[str, Any]) -> OutcomeReviewProjection:
    value = state.get("projection")
    if not isinstance(value, Mapping):
        raise OutcomeReviewContractError("OUTCOME_TARGET_E2E_PROJECTION_MISSING")
    projection = OutcomeReviewProjection.model_validate(value)
    _require_proposal_only_projection(projection)
    return projection


def _reconstruct_projection(
    state: Mapping[str, Any],
    invocation: OutcomeReviewInvocation,
    command: OutcomeReviewPrivateCommand,
) -> OutcomeReviewProjection:
    answer = invocation.validate_answer(invocation.request, invocation.answerer(invocation.request))
    advisory_hash = answer_hash(answer)
    citations = sorted(
        {
            *answer.fact_refs,
            *answer.rule_refs,
            *answer.draft_refs,
            *answer.deliberation_refs,
            *(ref for statement in answer.statements for ref in statement.refs),
        }
    )
    projection = OutcomeReviewProjection(
        command_id=command.command_id,
        review_task_id=command.review_task_id,
        packet_id=command.packet_id,
        frozen_packet_ref=command.frozen_packet_ref,
        frozen_packet_hash=command.frozen_packet_hash,
        frozen_packet_version=command.frozen_packet_version,
        action_hash=command.action_hash,
        review_task_status=command.review_task_status,
        review_deadline=command.review_deadline,
        room_epoch=command.room_epoch,
        process_revision=command.process_revision,
        fencing_token=command.fencing_token,
        advisory_hash=advisory_hash,
        citation_refs=citations,
        answer=answer,
    )
    if (
        state.get("advisory_hash") != advisory_hash
        or state.get("citation_refs") != citations
        or state.get("result_hash") != canonical_sha256(projection.model_dump(mode="json"))
    ):
        raise OutcomeReviewContractError("OUTCOME_TARGET_E2E_DETERMINISTIC_REPLAY_MISMATCH")
    _require_proposal_only_projection(projection)
    return projection


def _proposal_payload(projection: OutcomeReviewProjection) -> OutcomeTargetE2EProposalPayload:
    _require_proposal_only_projection(projection)
    return OutcomeTargetE2EProposalPayload(
        review_task_id=projection.review_task_id,
        packet_id=projection.packet_id,
        advisory_hash=projection.advisory_hash,
        citation_refs=tuple(projection.citation_refs),
        answer=projection.answer,
    )


def _require_proposal_only_projection(projection: OutcomeReviewProjection) -> None:
    if (
        projection.approval_performed
        or projection.execution_triggered
        or projection.is_final_decision
    ):
        raise OutcomeReviewContractError("OUTCOME_TARGET_E2E_FORMAL_AUTHORITY_FORBIDDEN")


def _terminal_checkpoint(
    snapshot: Any,
    execution: GatewayExecution,
) -> tuple[str, str, int]:
    values = getattr(snapshot, "values", None)
    config = getattr(snapshot, "config", None)
    if (
        not isinstance(values, Mapping)
        or values.get("status") != "PROPOSED"
        or not isinstance(config, Mapping)
        or getattr(snapshot, "next", None)
        or getattr(snapshot, "tasks", None)
        or getattr(snapshot, "interrupts", None)
    ):
        raise OutcomeReviewContractError("OUTCOME_TARGET_E2E_CHECKPOINT_NOT_TERMINAL")
    configurable = config.get("configurable") or {}
    checkpoint_ns = configurable.get("checkpoint_ns", "")
    checkpoint_id = configurable.get("checkpoint_id")
    if (
        configurable.get("thread_id") != execution.fence.thread_id
        or not isinstance(checkpoint_ns, str)
        or len(checkpoint_ns) > 128
        or not isinstance(checkpoint_id, str)
        or not checkpoint_id
        or len(checkpoint_id) > 128
    ):
        raise OutcomeReviewContractError("OUTCOME_TARGET_E2E_CHECKPOINT_BINDING_INVALID")
    return checkpoint_ns, checkpoint_id, cast(int, values["cognitive_revision"])


def deterministic_outcome_target_e2e_invocation(
    *,
    command: OutcomeReviewPrivateCommand,
    request: ReviewCopilotRequest,
    snapshot_uri: str,
    snapshot_hash: str,
    event_uri: str | None,
    event_hash: str | None,
) -> LoadedOutcomeTargetE2EInvocation:
    model = DeterministicOutcomeTargetE2EModel()
    copilot = ReviewCopilot(model)
    return LoadedOutcomeTargetE2EInvocation(
        command=command,
        request=request,
        reviewer_actor_hash=command.reviewer_actor_hash,
        answerer=model,
        validate_answer=copilot.validate_answer,
        snapshot_uri=snapshot_uri,
        snapshot_hash=snapshot_hash,
        event_uri=event_uri,
        event_hash=event_hash,
    )


def _wire_value(value: Any) -> Any:
    return getattr(value, "value", value)


def _positive_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value >= 1


def _sha256(value: Any) -> bool:
    return (
        isinstance(value, str)
        and len(value) == 64
        and all(character in "0123456789abcdef" for character in value)
    )


__all__ = [
    "CompiledOutcomeTargetE2EExecutor",
    "DeterministicOutcomeTargetE2EModel",
    "LoadedOutcomeTargetE2EInvocation",
    "OutcomeTargetE2EExecutionContext",
    "OutcomeTargetE2EExecutionResult",
    "OutcomeTargetE2EExecutorRegistration",
    "OutcomeTargetE2EInvocationProvider",
    "OutcomeTargetE2EProposal",
    "OutcomeTargetE2EProposalPayload",
    "OutcomeTargetE2EProposalSource",
    "OutcomeTargetE2EProposalStore",
    "StoredOutcomeTargetE2EProposal",
    "build_outcome_target_e2e_proposal_source",
    "build_outcome_target_e2e_registration",
    "deterministic_outcome_target_e2e_invocation",
    "require_exact_outcome_target_e2e_registry_binding",
]
