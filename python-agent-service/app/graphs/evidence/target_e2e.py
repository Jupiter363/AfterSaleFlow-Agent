from __future__ import annotations

from collections.abc import AsyncIterator, Callable, Mapping
from copy import deepcopy
from dataclasses import dataclass
from datetime import datetime, timezone
import hmac
from importlib import import_module
import inspect
import re
from typing import Any, Protocol, cast

from langchain_core.runnables import Runnable

from app.contracts.v1.codec import canonical_sha256, canonicalize
from app.contracts.v1.models import (
    AgentStreamEvent,
    AgentStreamPayload,
    ExecutionMetadata,
    Usage,
)
from app.graph_runtime.checkpoint import (
    ExternalTerminalCommit,
    FencedPostgresSaver,
    TerminalResultMaterializer,
)
from app.graph_runtime.errors import GraphContractError
from app.graph_runtime.persistence_models import GraphFenceContext
from app.graph_runtime.postgres_bulkhead import PostgresGraphFanoutBulkhead
from app.graph_runtime.result import ResultBindings
from app.graphs.evidence.contracts import (
    EVIDENCE_STATE_SCHEMA_VERSION,
    TARGET_E2E_CHECKPOINT_SCHEMA_VERSION,
    TARGET_E2E_GRAPH_KEY,
    TARGET_E2E_GRAPH_VERSION,
    TARGET_E2E_OUTPUT_SCHEMA_VERSION,
    TERMINAL_OUTPUT_SCHEMA_VERSION,
    EvidenceAdmissionRequest,
    EvidenceAdmissionVerifier,
    EvidenceGraphContractError,
    JsonObject,
    VerifiedEvidenceAdmission,
    validate_verified_admission,
)
from app.graphs.evidence.lcel import (
    TargetEvidenceAssessmentLCEL,
    TargetEvidenceAssetLoader,
    build_target_evidence_assessment_lcel,
)
from app.graphs.evidence.runtime import (
    EvidenceRuntimeBundle,
    build_evidence_runtime_bundle,
    recover_evidence_runtime_completed_at,
)


TARGET_E2E_EXECUTION_LANE = "TARGET_E2E_CANDIDATE"
TARGET_E2E_EVIDENCE_CHECKPOINT_SCHEMA_VERSION = TARGET_E2E_CHECKPOINT_SCHEMA_VERSION
TARGET_E2E_EVIDENCE_OUTPUT_SCHEMA_VERSION = TARGET_E2E_OUTPUT_SCHEMA_VERSION
TARGET_E2E_EVIDENCE_PROPOSAL_SCHEMA_VERSION = "target-e2e-evidence-proposal.v1"
TARGET_E2E_ROOM_PROPOSAL_SOURCE_SCHEMA_VERSION = "target-e2e-room-proposal-source.v1"
_ACTIVATION_ID = re.compile(r"^p9act\.v1\.[0-9a-f]{32}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")


class TargetEvidenceManifestLoader(Protocol):
    async def load(self, snapshot_ref: Mapping[str, Any]) -> bytes: ...


class TargetEvidenceProposalStore(Protocol):
    async def put(
        self,
        *,
        payload: bytes,
        payload_hash: str,
        media_type: str,
    ) -> str: ...


@dataclass(frozen=True, slots=True)
class TargetEvidenceGatewayContext:
    """Projection of an already-admitted shared gateway execution.

    This adapter never parses or accepts the deployment activation JWS. The shared gateway owns
    that boundary and exposes only its persisted candidate authority and command binding here.
    """

    command: JsonObject
    activation_id: str
    room_fencing_token: int
    command_hash: str
    command_envelope_hash: str
    registry_binding_hash: str
    graph_code_build_id: str
    graph_lease_fence: GraphFenceContext

    @classmethod
    def from_shared_execution(cls, execution: Any) -> TargetEvidenceGatewayContext:
        admission = getattr(execution, "admission", None)
        binding = getattr(admission, "binding", None)
        registry_record = getattr(admission, "registry", None)
        registry = getattr(registry_record, "binding", None)
        authority = getattr(admission, "candidate_authority", None)
        fence = getattr(execution, "fence", None)
        command_value = getattr(admission, "command", None)
        dump = getattr(command_value, "model_dump", None)
        if not callable(dump):
            raise EvidenceGraphContractError("EVIDENCE_TARGET_GATEWAY_CONTEXT_REQUIRED")
        command = cast(JsonObject, dump(mode="json", exclude_none=True))
        lane = _wire_enum(getattr(binding, "execution_lane", None))
        fence_lane = _wire_enum(getattr(fence, "execution_lane", None))
        activation_id = getattr(binding, "activation_id", None)
        authority_activation_id = getattr(authority, "activation_id", None)
        if authority_activation_id is None:
            authority_activation_id = getattr(
                getattr(authority, "context", None),
                "activationId",
                None,
            )
        room_fencing_token = getattr(binding, "room_fencing_token", None)
        command_hash = getattr(binding, "command_hash", None)
        command_envelope_hash = getattr(binding, "command_envelope_hash", None)
        registry_binding_hash = getattr(registry, "binding_hash", None)
        graph_code_build_id = getattr(registry, "code_build_id", None)
        if (
            lane != TARGET_E2E_EXECUTION_LANE
            or fence_lane != TARGET_E2E_EXECUTION_LANE
            or not isinstance(fence, GraphFenceContext)
            or _ACTIVATION_ID.fullmatch(str(activation_id)) is None
            or activation_id != authority_activation_id
            or room_fencing_token != getattr(fence, "room_fencing_token", None)
            or activation_id != getattr(fence, "activation_id", None)
            or command_hash != getattr(fence, "command_hash", None)
            or command_envelope_hash != getattr(fence, "command_envelope_hash", None)
            or registry_binding_hash != getattr(fence, "binding_hash", None)
            or graph_code_build_id != getattr(fence, "code_build_id", None)
            or not _positive_int(room_fencing_token)
            or _SHA256.fullmatch(str(command_hash)) is None
            or _SHA256.fullmatch(str(command_envelope_hash)) is None
            or _SHA256.fullmatch(str(registry_binding_hash)) is None
            or not isinstance(graph_code_build_id, str)
            or not graph_code_build_id
            or getattr(fence, "tenant_surrogate", None) != command.get("tenant_surrogate")
            or getattr(fence, "case_id", None) != command.get("case_id")
            or getattr(fence, "room_type", None) != "EVIDENCE"
        ):
            raise EvidenceGraphContractError("EVIDENCE_TARGET_GATEWAY_CONTEXT_REQUIRED")
        cls._require_exact_command_binding(command, binding=binding, registry=registry, fence=fence)
        if not hmac.compare_digest(cast(str, command_hash), canonical_sha256(command)):
            raise EvidenceGraphContractError("EVIDENCE_TARGET_COMMAND_HASH_MISMATCH")
        return cls(
            command=deepcopy(command),
            activation_id=cast(str, activation_id),
            room_fencing_token=cast(int, room_fencing_token),
            command_hash=cast(str, command_hash),
            command_envelope_hash=cast(str, command_envelope_hash),
            registry_binding_hash=cast(str, registry_binding_hash),
            graph_code_build_id=graph_code_build_id,
            graph_lease_fence=fence,
        )

    @staticmethod
    def _require_exact_command_binding(
        command: JsonObject,
        *,
        binding: Any,
        registry: Any,
        fence: GraphFenceContext,
    ) -> None:
        invocation = command.get("invocation_context")
        if not isinstance(invocation, dict):
            raise EvidenceGraphContractError("EVIDENCE_TARGET_COMMAND_BINDING_INVALID")
        exact = (
            command.get("room_type"),
            command.get("graph_key"),
            command.get("graph_version"),
            command.get("checkpoint_schema_version"),
            invocation.get("output_schema_version"),
        )
        required = (
            "EVIDENCE",
            TARGET_E2E_GRAPH_KEY,
            TARGET_E2E_GRAPH_VERSION,
            TARGET_E2E_EVIDENCE_CHECKPOINT_SCHEMA_VERSION,
            TARGET_E2E_EVIDENCE_OUTPUT_SCHEMA_VERSION,
        )
        persisted = (
            getattr(binding, "graph_key", None),
            getattr(binding, "graph_version", None),
            getattr(binding, "checkpoint_schema_version", None),
        )
        registered = (
            getattr(registry, "graph_key", None),
            getattr(registry, "graph_version", None),
            getattr(registry, "checkpoint_schema_version", None),
            getattr(registry, "output_schema_version", None),
        )
        if (
            exact != required
            or persisted != required[1:4]
            or registered
            != (
                *required[1:4],
                TARGET_E2E_EVIDENCE_OUTPUT_SCHEMA_VERSION,
            )
            or (
                fence.thread_id,
                fence.command_id,
                fence.request_hash,
                fence.room_epoch,
                fence.graph_key,
                fence.graph_version,
                fence.checkpoint_schema_version,
            )
            != (
                command.get("thread_id"),
                command.get("command_id"),
                command.get("request_hash"),
                command.get("room_epoch"),
                *required[1:4],
            )
        ):
            raise EvidenceGraphContractError("EVIDENCE_TARGET_COMMAND_BINDING_INVALID")


@dataclass(frozen=True, slots=True)
class TargetEvidenceExecutionPlan:
    gateway: TargetEvidenceGatewayContext
    admission: VerifiedEvidenceAdmission


@dataclass(frozen=True, slots=True)
class TargetEvidenceExecutionResult:
    proposal_source: Any
    proposal_hash: str
    payload: JsonObject
    payload_hash: str
    payload_ref: str
    terminal_class: str
    cognitive_revision: int


class TargetEvidenceRuntimeAdapter:
    def __init__(self, verifier: EvidenceAdmissionVerifier) -> None:
        if type(verifier) is not EvidenceAdmissionVerifier:
            raise EvidenceGraphContractError("EVIDENCE_VERIFIER_REQUIRED")
        self._verifier = verifier

    def admit(
        self,
        *,
        execution: Any,
        signed_manifest_payload: bytes,
    ) -> TargetEvidenceExecutionPlan:
        gateway = TargetEvidenceGatewayContext.from_shared_execution(execution)
        request = EvidenceAdmissionRequest(
            runtime_mode="SHADOW",
            room_graph_command=gateway.command,
            signed_manifest_payload=signed_manifest_payload,
            registry_output_schema_version=TARGET_E2E_EVIDENCE_OUTPUT_SCHEMA_VERSION,
            graph_lease_fencing_token=gateway.graph_lease_fence.fencing_token,
        )
        admission = self._verifier._verify_target_candidate(request)  # noqa: SLF001
        command, manifest = validate_verified_admission(admission)
        if (
            manifest.get("registration_id") != gateway.activation_id
            or manifest.get("fencing_token") != gateway.room_fencing_token
            or command != gateway.command
        ):
            raise EvidenceGraphContractError("EVIDENCE_TARGET_ACTIVATION_BINDING_MISMATCH")
        return TargetEvidenceExecutionPlan(gateway=gateway, admission=admission)


class TargetEvidenceGraphProvider:
    """Exact Evidence candidate provider; it has no Domain or formal-sink dependency."""

    graph_key = TARGET_E2E_GRAPH_KEY
    graph_version = TARGET_E2E_GRAPH_VERSION
    checkpoint_schema_version = TARGET_E2E_EVIDENCE_CHECKPOINT_SCHEMA_VERSION
    output_schema_version = TARGET_E2E_EVIDENCE_OUTPUT_SCHEMA_VERSION
    state_schema_version = EVIDENCE_STATE_SCHEMA_VERSION
    execution_lane = TARGET_E2E_EXECUTION_LANE

    def __init__(
        self,
        *,
        verifier: EvidenceAdmissionVerifier,
        model: Runnable[Any, Any],
        manifest_loader: TargetEvidenceManifestLoader,
        asset_loader: TargetEvidenceAssetLoader,
        proposal_store: TargetEvidenceProposalStore,
        checkpointer: FencedPostgresSaver,
        bulkhead: PostgresGraphFanoutBulkhead,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        if not callable(getattr(manifest_loader, "load", None)):
            raise EvidenceGraphContractError("EVIDENCE_TARGET_MANIFEST_LOADER_REQUIRED")
        if not callable(getattr(proposal_store, "put", None)):
            raise EvidenceGraphContractError("EVIDENCE_TARGET_PROPOSAL_STORE_REQUIRED")
        self._adapter = TargetEvidenceRuntimeAdapter(verifier)
        self._assessment_lcel = build_target_evidence_assessment_lcel(
            model=model,
            asset_loader=asset_loader,
        )
        self._manifest_loader = manifest_loader
        self._proposal_store = proposal_store
        self._checkpointer = checkpointer
        self._bulkhead = bulkhead
        self._clock = clock or (lambda: datetime.now(timezone.utc))

    @property
    def assessment_lcel(self) -> TargetEvidenceAssessmentLCEL:
        return self._assessment_lcel

    async def stream(self, execution: Any) -> AsyncIterator[AgentStreamEvent]:
        runtime = await self._prepare_runtime(execution)
        yield self._event(
            execution,
            sequence_no=0,
            event_type="attempt_started",
            payload=AgentStreamPayload(node="authorize_registration_and_manifest"),
        )
        state = await runtime.arun()
        payload = runtime.terminal_proposal(state)
        checkpoint_state, config = await runtime.aterminal_checkpoint()
        if checkpoint_state != state:
            raise EvidenceGraphContractError("EVIDENCE_TARGET_CHECKPOINT_STATE_MISMATCH")
        revision = state.get("cognitive_revision")
        if not isinstance(revision, int) or isinstance(revision, bool) or revision < 1:
            raise EvidenceGraphContractError("EVIDENCE_TARGET_REVISION_INVALID")
        await self._checkpointer.avalidate_external_terminal_checkpoint(
            config,
            cognitive_revision=revision,
        )
        prepared = await self._store_proposal(runtime, state, payload)
        result = self._materialize_result(
            execution=execution,
            prepared=prepared,
            state=state,
            config=config,
        )
        await self._checkpointer.acommit_external_terminal(
            config,
            ExternalTerminalCommit(result=result, cognitive_revision=revision),
        )
        usage = _aggregate_usage(state.get("usage_by_invocation"))
        sequence = 1
        if usage.total_tokens:
            yield self._event(
                execution,
                sequence_no=sequence,
                event_type="usage",
                payload=AgentStreamPayload(usage=usage),
            )
            sequence += 1
        yield self._event(
            execution,
            sequence_no=sequence,
            event_type="final",
            payload=AgentStreamPayload(
                final_result_ref=result.result_ref,
                final_result_hash=result.result_hash,
            ),
        )

    async def _prepare_runtime(
        self,
        execution: Any,
    ) -> EvidenceRuntimeBundle:
        command_value = getattr(getattr(execution, "admission", None), "command", None)
        dump = getattr(command_value, "model_dump", None)
        if not callable(dump):
            raise EvidenceGraphContractError("EVIDENCE_TARGET_GATEWAY_CONTEXT_REQUIRED")
        command = cast(JsonObject, dump(mode="json", exclude_none=True))
        snapshot_ref = command.get("domain_snapshot_ref")
        if not isinstance(snapshot_ref, dict):
            raise EvidenceGraphContractError("EVIDENCE_TARGET_SNAPSHOT_REF_REQUIRED")
        manifest_payload = await self._manifest_loader.load(snapshot_ref)
        if not isinstance(manifest_payload, bytes):
            raise EvidenceGraphContractError("EVIDENCE_TARGET_MANIFEST_PAYLOAD_INVALID")
        plan = self._adapter.admit(
            execution=execution,
            signed_manifest_payload=manifest_payload,
        )
        completed_at = await recover_evidence_runtime_completed_at(
            checkpointer=self._checkpointer,
            fence=plan.gateway.graph_lease_fence,
        )
        if completed_at is None:
            completed_at = _rfc3339(self._clock())
        return build_evidence_runtime_bundle(
            item_assessor=self._assessment_lcel.runnable,
            admission=plan.admission,
            completed_at=completed_at,
            checkpointer=self._checkpointer,
            bulkhead=self._bulkhead,
            fence=plan.gateway.graph_lease_fence,
            runtime_mode=TARGET_E2E_EXECUTION_LANE,
        )

    async def _store_proposal(
        self,
        runtime: EvidenceRuntimeBundle,
        state: Mapping[str, Any],
        payload: JsonObject,
    ) -> TargetEvidenceExecutionResult:
        if (
            payload.get("execution_scope") != TARGET_E2E_EXECUTION_LANE
            or payload.get("writer_mode") != "PROPOSAL_ONLY"
            or payload.get("formal_sink_eligible") is not False
        ):
            raise EvidenceGraphContractError("EVIDENCE_TARGET_PROPOSAL_AUTHORITY_INVALID")
        payload_bytes = canonicalize(payload)
        payload_hash = canonical_sha256(payload)
        payload_ref = await self._proposal_store.put(
            payload=payload_bytes,
            payload_hash=payload_hash,
            media_type="application/json",
        )
        _require_proposal_ref(payload_ref)
        terminal_class = (
            "NEEDS_REVIEW" if payload.get("proposed_review_items") else "COMPLETED"
        )
        binding = cast(JsonObject, runtime.admission.room_graph_command)
        proposal: JsonObject = {
            "schema_version": TARGET_E2E_EVIDENCE_PROPOSAL_SCHEMA_VERSION,
            "proposal_id": f"proposal.evidence.{payload_hash[:32]}",
            "command_id": cast(str, binding["command_id"]),
            "logical_run_id": cast(str, binding["logical_run_id"]),
            "attempt_id": cast(str, binding["attempt_id"]),
            "payload_schema_version": TERMINAL_OUTPUT_SCHEMA_VERSION,
            "payload_ref": payload_ref,
            "payload_hash": payload_hash,
            "terminal_class": terminal_class,
            "formal_authority": False,
        }
        source: JsonObject = {
            "schema_version": TARGET_E2E_ROOM_PROPOSAL_SOURCE_SCHEMA_VERSION,
            "room_type": "EVIDENCE",
            "proposal": proposal,
        }
        typed_source = _target_proposal_source(source)
        proposal_hash = cast(str, typed_source.proposal_hash)
        revision = state.get("cognitive_revision")
        if not isinstance(revision, int) or isinstance(revision, bool) or revision < 1:
            raise EvidenceGraphContractError("EVIDENCE_TARGET_REVISION_INVALID")
        return TargetEvidenceExecutionResult(
            proposal_source=typed_source,
            proposal_hash=proposal_hash,
            payload=deepcopy(payload),
            payload_hash=payload_hash,
            payload_ref=payload_ref,
            terminal_class=terminal_class,
            cognitive_revision=revision,
        )

    def _materialize_result(
        self,
        *,
        execution: Any,
        prepared: TargetEvidenceExecutionResult,
        state: Mapping[str, Any],
        config: Mapping[str, Any],
    ) -> Any:
        command = getattr(getattr(execution, "admission", None), "command", None)
        fence = getattr(execution, "fence", None)
        if command is None or not isinstance(fence, GraphFenceContext):
            raise EvidenceGraphContractError("EVIDENCE_TARGET_GATEWAY_CONTEXT_REQUIRED")
        configurable = config.get("configurable")
        if not isinstance(configurable, Mapping):
            raise EvidenceGraphContractError("EVIDENCE_RUNTIME_CHECKPOINT_INVALID")
        checkpoint_ns = configurable.get("checkpoint_ns", "")
        checkpoint_id = configurable.get("checkpoint_id")
        if not isinstance(checkpoint_ns, str) or not isinstance(checkpoint_id, str):
            raise EvidenceGraphContractError("EVIDENCE_RUNTIME_CHECKPOINT_INVALID")
        usage = _aggregate_usage(state.get("usage_by_invocation"))
        draft: dict[str, Any]
        if prepared.terminal_class == "NEEDS_REVIEW":
            draft = {
                "status": "NEEDS_REVIEW",
                "needs_review": {
                    "reason_code": "EVIDENCE_REVIEW_REQUIRED",
                    "risk_level": "MEDIUM",
                },
            }
        else:
            draft = {"status": "COMPLETED"}
        invocation = command.invocation_context
        bindings = ResultBindings(
            command_id=command.command_id,
            logical_run_id=command.logical_run_id,
            attempt_id=command.attempt_id,
            graph_key=command.graph_key,
            graph_version=command.graph_version,
            checkpoint_id=checkpoint_id,
            cognitive_revision=prepared.cognitive_revision,
            public_event_proposals=(),
            artifact_operations=(),
            usage=usage,
            execution_metadata=ExecutionMetadata(
                prompt_version=invocation.prompt_profile_id,
                model_profile_id=invocation.model_profile_id,
                schema_version=invocation.output_schema_version,
                policy_version=invocation.policy_version,
                guardrail_version=invocation.guardrail_version,
            ),
        )
        if "target_proposal_source" not in inspect.signature(
            TerminalResultMaterializer
        ).parameters:
            raise EvidenceGraphContractError("EVIDENCE_TARGET_SHARED_MATERIALIZER_REQUIRED")
        materializer = TerminalResultMaterializer(
            thread_id=fence.thread_id,
            request_hash=command.request_hash,
            draft=draft,
            bindings=bindings,
            target_proposal_source=prepared.proposal_source,
        )
        try:
            return materializer.materialize(checkpoint_ns, checkpoint_id, fence=fence)
        except (TypeError, ValueError, GraphContractError) as error:
            raise EvidenceGraphContractError(
                "EVIDENCE_TARGET_TERMINAL_BINDING_INVALID"
            ) from error

    def _event(
        self,
        execution: Any,
        *,
        sequence_no: int,
        event_type: str,
        payload: AgentStreamPayload,
    ) -> AgentStreamEvent:
        command = execution.admission.command
        return AgentStreamEvent(
            schema_version="agent-stream.v2",
            run_id=command.logical_run_id,
            attempt_id=command.attempt_id,
            sequence_no=sequence_no,
            event_type=event_type,
            audience=command.actor_scope.audience,
            occurred_at=self._clock(),
            payload=payload,
        )


def build_target_evidence_provider(
    *,
    verifier: EvidenceAdmissionVerifier,
    model: Runnable[Any, Any],
    manifest_loader: TargetEvidenceManifestLoader,
    asset_loader: TargetEvidenceAssetLoader,
    proposal_store: TargetEvidenceProposalStore,
    checkpointer: FencedPostgresSaver,
    bulkhead: PostgresGraphFanoutBulkhead,
    clock: Callable[[], datetime] | None = None,
) -> TargetEvidenceGraphProvider:
    """Explicit composite-registry hook; it does not register an outer binding itself."""

    return TargetEvidenceGraphProvider(
        verifier=verifier,
        model=model,
        manifest_loader=manifest_loader,
        asset_loader=asset_loader,
        proposal_store=proposal_store,
        checkpointer=checkpointer,
        bulkhead=bulkhead,
        clock=clock,
    )


def _wire_enum(value: Any) -> str | None:
    candidate = getattr(value, "value", value)
    return candidate if isinstance(candidate, str) else None


def _target_proposal_source(value: JsonObject) -> Any:
    try:
        module = import_module("app.graph_runtime.target_e2e")
        source_type = getattr(module, "TargetE2ERoomProposalSource")
        source = source_type.model_validate(value)
    except (AttributeError, ImportError, TypeError, ValueError) as error:
        raise EvidenceGraphContractError(
            "EVIDENCE_TARGET_SHARED_PROPOSAL_SOURCE_REQUIRED"
        ) from error
    if getattr(source, "room_type", None) != "EVIDENCE":
        raise EvidenceGraphContractError("EVIDENCE_TARGET_PROPOSAL_SOURCE_INVALID")
    return source


def _aggregate_usage(value: Any) -> Usage:
    if not isinstance(value, Mapping):
        raise EvidenceGraphContractError("EVIDENCE_TARGET_USAGE_INVALID")
    totals = {"input_tokens": 0, "output_tokens": 0, "total_tokens": 0}
    for candidate in value.values():
        if not isinstance(candidate, Mapping) or set(candidate) != set(totals):
            raise EvidenceGraphContractError("EVIDENCE_TARGET_USAGE_INVALID")
        for field in totals:
            amount = candidate[field]
            if not isinstance(amount, int) or isinstance(amount, bool) or amount < 0:
                raise EvidenceGraphContractError("EVIDENCE_TARGET_USAGE_INVALID")
            totals[field] += amount
        if candidate["total_tokens"] != (
            candidate["input_tokens"] + candidate["output_tokens"]
        ):
            raise EvidenceGraphContractError("EVIDENCE_TARGET_USAGE_INVALID")
    if totals["total_tokens"] != totals["input_tokens"] + totals["output_tokens"]:
        raise EvidenceGraphContractError("EVIDENCE_TARGET_USAGE_INVALID")
    return Usage(**totals)


def _positive_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value >= 1


def _require_proposal_ref(value: Any) -> None:
    if (
        not isinstance(value, str)
        or not value.startswith("urn:target-e2e:proposal:")
        or len(value) > 512
    ):
        raise EvidenceGraphContractError("EVIDENCE_TARGET_PROPOSAL_REF_INVALID")


def _rfc3339(value: Any) -> str:
    if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
        raise EvidenceGraphContractError("EVIDENCE_TARGET_CLOCK_INVALID")
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


__all__ = [
    "TARGET_E2E_EVIDENCE_CHECKPOINT_SCHEMA_VERSION",
    "TARGET_E2E_EVIDENCE_OUTPUT_SCHEMA_VERSION",
    "TARGET_E2E_EVIDENCE_PROPOSAL_SCHEMA_VERSION",
    "TARGET_E2E_EXECUTION_LANE",
    "TARGET_E2E_ROOM_PROPOSAL_SOURCE_SCHEMA_VERSION",
    "TargetEvidenceExecutionPlan",
    "TargetEvidenceExecutionResult",
    "TargetEvidenceGatewayContext",
    "TargetEvidenceGraphProvider",
    "TargetEvidenceManifestLoader",
    "TargetEvidenceProposalStore",
    "TargetEvidenceRuntimeAdapter",
    "build_target_evidence_provider",
]
