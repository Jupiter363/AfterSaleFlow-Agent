"""Production-Runtime room adapters over immutable object references.

The shared Graph gateway has already authenticated the command before these
adapters run.  They deliberately have no Java client and no Domain database
dependency: every room input is an immutable object referenced by the command
and every proposal is written through the same object-store capability.
"""

from __future__ import annotations

from collections.abc import AsyncIterator, Mapping
from copy import deepcopy
from dataclasses import dataclass, replace
from datetime import datetime, timezone
import hashlib
import json
from typing import Any, Protocol, cast

from app.contracts.v1.codec import canonicalize
from app.contracts.v1.models import (
    AgentStreamEvent,
    AgentStreamPayload,
    ArtifactOperation,
    ArtifactPointer,
    ExecutionMetadata,
    SnapshotRef,
    Usage,
)
from app.graph_runtime.checkpoint import (
    ExternalTerminalCommit,
    FencedPostgresSaver,
    TerminalResultMaterializer,
    bind_fence_context,
)
from app.graph_runtime.errors import GraphContractError
from app.graph_runtime.evidence_turn_executor import (
    CompiledEvidenceTurnExecutor,
    EvidenceTurnWorkflowPort,
)
from app.graph_runtime.gateway import GatewayExecution
from app.graph_runtime.gateway import GatewayAdmission
from app.graph_runtime.identity import RoomType, ThreadRecord
from app.graph_runtime.intake_binding import INTAKE_OUTPUT_SCHEMA
from app.graph_runtime.intake_executor import CompiledIntakeGraphShadowExecutor
from app.graph_runtime.persistence_models import GraphGatewayMode
from app.graph_runtime.registry import RegistryRecord
from app.graph_runtime.result import NeedsReviewDraft, ResultBindings
from app.graph_runtime.production_runtime import ProductionRoomProposalSource
from app.graph_runtime.production_runtime_composite import ProductionRoomProvider
from app.graphs.evidence.contracts import EvidenceGraphContractError
from app.graphs.evidence.lcel import TargetEvidenceAsset, TargetEvidenceAssetLoader
from app.graphs.evidence.lcel import EvidenceAssessmentDraft
from app.graphs.evidence.production_runtime import (
    TargetEvidenceManifestLoader,
    TargetEvidenceProposalStore,
)
from app.graphs.hearing.contracts import HearingOperation
from app.graphs.hearing.production_runtime import (
    HearingProductionInvocationProvider,
    HearingProductionLoadedInvocation,
    HearingProductionPayloadStore,
    HearingProductionStoredPayload,
    build_production_runtime_hearing_provider,
)
from app.graphs.outcome.state import OutcomeReviewPrivateCommand
from app.graphs.outcome.production_runtime import (
    CompiledOutcomeProductionExecutor,
    LoadedOutcomeProductionInvocation,
    OutcomeProductionInvocationProvider,
    OutcomeProductionProposalStore,
    StoredOutcomeProductionProposal,
    deterministic_outcome_production_runtime_invocation,
)
from app.graphs.outcome.errors import OutcomeReviewContractError
from app.schemas import ReviewCopilotRequest


class ProductionImmutableObjectStore(Protocol):
    """The only non-Graph-DB data-plane capability granted to room providers."""

    async def load(self, reference: SnapshotRef) -> bytes: ...

    async def put(
        self,
        *,
        execution: GatewayExecution,
        proposal_id: str,
        schema_version: str,
        payload: bytes,
        payload_hash: str,
        checkpoint_ns: str,
        checkpoint_id: str,
        cognitive_revision: int,
    ) -> str: ...


class ProductionImmutableObjectStoreFactory(Protocol):
    """Mint a per-command object capability after gateway admission.

    A shared provider must never retain a mutable "current execution".  The
    returned store is instead bound to one admitted command by the Java
    exchange, so a URI cannot be replayed under another room or attempt.
    """

    def for_execution(self, execution: GatewayExecution) -> ProductionImmutableObjectStore: ...

    async def put_content_addressed(
        self,
        *,
        proposal_id: str,
        schema_version: str,
        payload: bytes,
        payload_hash: str,
    ) -> str: ...


class ProductionHearingInvocationDecoder(Protocol):
    """Decode one Java-published immutable Hearing invocation document."""

    def decode(
        self,
        *,
        execution: GatewayExecution,
        snapshot_payload: bytes,
        event_payload: bytes | None,
    ) -> HearingProductionLoadedInvocation: ...


class ProductionIntakeExchange(Protocol):
    """Production-only exchange with all-room authority, never SHADOW's ``intake.v2`` DTO."""

    async def load(self, execution: GatewayExecution) -> Any: ...

    async def put(self, execution: GatewayExecution, **kwargs: Any) -> Any: ...


@dataclass(frozen=True, slots=True)
class ProductionSpecializedRoomDependencies:
    """Explicit lifecycle handoff required to run the non-Intake room graphs."""

    object_store: ProductionImmutableObjectStore | None
    evidence_workflow: EvidenceTurnWorkflowPort
    hearing_decoder: ProductionHearingInvocationDecoder
    object_store_factory: ProductionImmutableObjectStoreFactory | None = None

    def store_for_execution(self, execution: GatewayExecution) -> ProductionImmutableObjectStore:
        if self.object_store_factory is not None:
            store = self.object_store_factory.for_execution(execution)
        else:
            store = self.object_store
        if not callable(getattr(store, "load", None)) or not callable(
            getattr(store, "put", None)
        ) or not callable(getattr(store, "put_content_addressed", None)):
            raise GraphContractError("PRODUCTION_RUNTIME_SCOPED_OBJECT_STORE_REQUIRED")
        return store


class ProductionIntakeProvider:
    """Run the governed Intake graph under an internal state projection.

    The outer command remains the frozen all-room candidate binding.  Only the
    private Intake state machine receives an internal ``intake.v2`` projection
    so its state/proposal schema stays ``intake-turn-proposal.v2``.  Terminal
    result and proposal-source materialization continue to use the original
    candidate command and fence.
    """

    room_type = RoomType.INTAKE

    def __init__(
        self,
        *,
        saver: FencedPostgresSaver,
        transport: Any,
        provider: str,
        model: str,
        exchange: ProductionIntakeExchange,
    ) -> None:
        if not callable(getattr(exchange, "load", None)) or not callable(
            getattr(exchange, "put", None)
        ):
            raise GraphContractError("PRODUCTION_RUNTIME_INTAKE_EXCHANGE_REQUIRED")
        self._executor = CompiledIntakeGraphShadowExecutor(
            saver=saver,
            transport=transport,
            provider=provider,
            model=model,
            input_loader=exchange,
            proposal_store=exchange,
            runtime_execution_projector=_project_target_intake_execution,
        )

    def stream(self, execution: GatewayExecution) -> AsyncIterator[AgentStreamEvent]:
        _require_target_intake_command(execution)
        return self._executor.stream(execution)


def build_production_runtime_intake_provider(
    *,
    saver: FencedPostgresSaver,
    transport: Any,
    provider: str,
    model: str,
    exchange: ProductionIntakeExchange,
) -> ProductionIntakeProvider:
    return ProductionIntakeProvider(
        saver=saver,
        transport=transport,
        provider=provider,
        model=model,
        exchange=exchange,
    )


def _require_target_intake_command(execution: GatewayExecution) -> None:
    command = execution.admission.command
    if (
        execution.fence.execution_lane is not GraphGatewayMode.PRODUCTION
        or execution.admission.candidate_authority is None
        or command.room_type != "INTAKE"
        or command.graph_key != "all-rooms.production-runtime.v2"
        or command.graph_version != "production-runtime-graph.2026-08-18.3"
        or command.checkpoint_schema_version != "production-runtime-checkpoint.v2"
        or command.invocation_context.output_schema_version
        != "production-runtime-room-proposal-source.v2"
        or execution.admission.binding.execution_lane
        is not GraphGatewayMode.PRODUCTION
        or execution.admission.registry.binding.graph_key != command.graph_key
        or execution.admission.registry.binding.graph_version != command.graph_version
        or execution.admission.registry.binding.checkpoint_schema_version
        != command.checkpoint_schema_version
        or execution.admission.registry.binding.output_schema_version
        != command.invocation_context.output_schema_version
    ):
        raise GraphContractError("PRODUCTION_RUNTIME_INTAKE_OUTER_BINDING_REQUIRED")


def _project_target_intake_execution(execution: GatewayExecution) -> GatewayExecution:
    """Produce an in-memory-only private graph projection; never persist it as authority."""

    _require_target_intake_command(execution)
    command = execution.admission.command
    invocation = command.invocation_context.model_copy(
        update={"output_schema_version": INTAKE_OUTPUT_SCHEMA}
    )
    internal_command = command.model_copy(
        update={"graph_key": "intake.v2", "invocation_context": invocation}
    )
    internal_identity = replace(
        execution.admission.thread,
        graph_key="intake.v2",
    )
    record = execution.thread_record
    if not isinstance(record, ThreadRecord) or record.identity != execution.admission.thread:
        raise GraphContractError("PRODUCTION_RUNTIME_INTAKE_THREAD_RECORD_REQUIRED")
    internal_record = replace(record, identity=internal_identity)
    outer_binding = execution.admission.registry.binding
    internal_binding = replace(
        outer_binding,
        graph_key="intake.v2",
        state_schema_version="intake-graph-state.v2",
        output_schema_version=INTAKE_OUTPUT_SCHEMA,
        tool_policy_version="no-tools.v1",
    )
    internal_registry = RegistryRecord(
        binding=internal_binding,
        state=execution.admission.registry.state,
        loadable=execution.admission.registry.loadable,
        revision=execution.admission.registry.revision,
    )
    return replace(
        execution,
        admission=GatewayAdmission(
            command=internal_command,
            binding=execution.admission.binding,
            thread=internal_identity,
            registry=internal_registry,
            record=execution.admission.record,
            action=execution.admission.action,
            created=execution.admission.created,
            candidate_authority=execution.admission.candidate_authority,
        ),
        thread_record=internal_record,
    )


class ProductionObjectEvidenceManifestLoader(TargetEvidenceManifestLoader):
    def __init__(self, store: ProductionImmutableObjectStore) -> None:
        self._store = store

    async def load(self, snapshot_ref: Mapping[str, Any]) -> bytes:
        try:
            reference = SnapshotRef.model_validate(dict(snapshot_ref))
        except ValueError as error:
            raise EvidenceGraphContractError("EVIDENCE_TARGET_SNAPSHOT_REF_REQUIRED") from error
        return await _load_exact(self._store, reference)


class ProductionObjectEvidenceAssetLoader(TargetEvidenceAssetLoader):
    """Load a bounded parsed-evidence object approved by the signed manifest."""

    def __init__(self, store: ProductionImmutableObjectStore) -> None:
        self._store = store

    async def load(self, item: Mapping[str, Any]) -> TargetEvidenceAsset:
        if not isinstance(item, Mapping):
            raise EvidenceGraphContractError("EVIDENCE_TARGET_ASSET_INVALID")
        uri = item.get("parse_ref")
        digest = item.get("parse_hash")
        if not isinstance(uri, str) or not isinstance(digest, str):
            raise EvidenceGraphContractError("EVIDENCE_TARGET_ASSET_INVALID")
        # The signed manifest supplies a parse hash but no parse size.  The
        # target object document is intentionally capped here; it is not a
        # Domain lookup and cannot resolve any alternate URI.
        reference = SnapshotRef(
            artifact_id=f"parse.{item.get('evidence_id', '')}",
            schema_version="production-runtime-evidence-asset.v1",
            uri=uri,
            sha256=digest,
            size_bytes=131_072,
        )
        payload = await _load_exact(self._store, reference, allow_smaller=True)
        document = _object_document(payload, "EVIDENCE_TARGET_ASSET_DOCUMENT_INVALID")
        if document.get("schema_version") != "production-runtime-evidence-asset.v1":
            raise EvidenceGraphContractError("EVIDENCE_TARGET_ASSET_DOCUMENT_INVALID")
        content = document.get("content")
        source_refs = document.get("source_refs")
        modalities = document.get("inspected_modalities")
        receipt_ref = document.get("receipt_ref")
        receipt_hash = document.get("receipt_hash")
        if not (
            isinstance(content, str)
            and isinstance(source_refs, list)
            and isinstance(modalities, list)
            and isinstance(receipt_ref, str)
            and isinstance(receipt_hash, str)
        ):
            raise EvidenceGraphContractError("EVIDENCE_TARGET_ASSET_DOCUMENT_INVALID")
        return TargetEvidenceAsset(
            content=content,
            source_refs=tuple(source_refs),
            inspected_modalities=tuple(modalities),
            receipt_ref=receipt_ref,
            receipt_hash=receipt_hash,
        )


class ProductionObjectEvidenceProposalStore(TargetEvidenceProposalStore):
    def __init__(self, store: ProductionImmutableObjectStore) -> None:
        self._store = store

    async def put(self, *, payload: bytes, payload_hash: str, media_type: str) -> str:
        if media_type != "application/json" or hashlib.sha256(payload).hexdigest() != payload_hash:
            raise EvidenceGraphContractError("EVIDENCE_TARGET_PROPOSAL_STORE_INVALID")
        return await self._store.put_content_addressed(
            proposal_id=f"proposal.evidence.{payload_hash[:32]}",
            schema_version="evidence-terminal-proposal.v2",
            payload=payload,
            payload_hash=payload_hash,
        )


class ProductionObjectHearingInvocationProvider(HearingProductionInvocationProvider):
    def __init__(
        self,
        store: ProductionImmutableObjectStore,
        decoder: ProductionHearingInvocationDecoder,
    ) -> None:
        self._store = store
        self._decoder = decoder

    async def load(self, execution: GatewayExecution) -> HearingProductionLoadedInvocation:
        command = execution.admission.command
        snapshot = await _load_exact(self._store, command.domain_snapshot_ref)
        event = (
            await _load_exact(self._store, command.event_ref)
            if command.event_ref is not None
            else None
        )
        loaded = self._decoder.decode(
            execution=execution,
            snapshot_payload=snapshot,
            event_payload=event,
        )
        if not isinstance(loaded, HearingProductionLoadedInvocation):
            raise GraphContractError("PRODUCTION_RUNTIME_HEARING_INVOCATION_DOCUMENT_INVALID")
        return loaded


class ProductionObjectHearingProposalStore(HearingProductionPayloadStore):
    def __init__(self, store: ProductionImmutableObjectStore) -> None:
        self._store = store

    async def put(self, **kwargs: Any) -> HearingProductionStoredPayload:
        execution = kwargs.get("execution")
        operation = kwargs.get("operation")
        proposal_id = kwargs.get("proposal_id")
        schema_version = kwargs.get("payload_schema_version")
        payload = kwargs.get("payload")
        payload_hash = kwargs.get("payload_hash")
        checkpoint_ns = kwargs.get("checkpoint_ns")
        checkpoint_id = kwargs.get("checkpoint_id")
        revision = kwargs.get("cognitive_revision")
        if not (
            isinstance(execution, GatewayExecution)
            and isinstance(operation, HearingOperation)
            and isinstance(proposal_id, str)
            and isinstance(schema_version, str)
            and isinstance(payload, bytes)
            and isinstance(payload_hash, str)
            and isinstance(checkpoint_ns, str)
            and isinstance(checkpoint_id, str)
            and isinstance(revision, int)
            and hashlib.sha256(payload).hexdigest() == payload_hash
        ):
            raise GraphContractError("PRODUCTION_RUNTIME_HEARING_PROPOSAL_STORE_INVALID")
        uri = await self._store.put(
            execution=execution,
            proposal_id=proposal_id,
            schema_version=schema_version,
            payload=payload,
            payload_hash=payload_hash,
            checkpoint_ns=checkpoint_ns,
            checkpoint_id=checkpoint_id,
            cognitive_revision=revision,
        )
        return HearingProductionStoredPayload(
            proposal_id=proposal_id,
            payload_schema_version=schema_version,
            payload_ref=uri,
            payload_hash=payload_hash,
            size_bytes=len(payload),
        )


class ProductionObjectOutcomeInvocationProvider(OutcomeProductionInvocationProvider):
    """Decode a private REVIEW invocation from the immutable snapshot object."""

    def __init__(self, store: ProductionImmutableObjectStore) -> None:
        self._store = store

    async def load(self, execution: GatewayExecution) -> LoadedOutcomeProductionInvocation:
        command = execution.admission.command
        payload = await _load_exact(self._store, command.domain_snapshot_ref)
        document = _object_document(payload, "PRODUCTION_RUNTIME_REVIEW_INVOCATION_DOCUMENT_INVALID")
        if document.get("schema_version") != "production-runtime-review-invocation.v1":
            raise OutcomeReviewContractError("PRODUCTION_RUNTIME_REVIEW_INVOCATION_DOCUMENT_REQUIRED")
        try:
            private_command = OutcomeReviewPrivateCommand.model_validate(
                document["private_command"]
            )
            request = ReviewCopilotRequest.model_validate(document["request"])
        except (KeyError, ValueError, TypeError) as error:
            raise OutcomeReviewContractError(
                "PRODUCTION_RUNTIME_REVIEW_INVOCATION_DOCUMENT_INVALID"
            ) from error
        event = command.event_ref
        return deterministic_outcome_production_runtime_invocation(
            command=private_command,
            request=request,
            snapshot_uri=command.domain_snapshot_ref.uri,
            snapshot_hash=command.domain_snapshot_ref.sha256,
            event_uri=event.uri if event is not None else None,
            event_hash=event.sha256 if event is not None else None,
        )


class ProductionObjectOutcomeProposalStore(OutcomeProductionProposalStore):
    def __init__(self, store: ProductionImmutableObjectStore) -> None:
        self._store = store

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
    ) -> StoredOutcomeProductionProposal:
        if hashlib.sha256(payload).hexdigest() != payload_hash:
            raise OutcomeReviewContractError("OUTCOME_PRODUCTION_RUNTIME_STORE_BINDING_MISMATCH")
        uri = await self._store.put(
            execution=execution,
            proposal_id=proposal_id,
            schema_version="outcome-review-proposal.v1",
            payload=payload,
            payload_hash=payload_hash,
            checkpoint_ns=checkpoint_ns,
            checkpoint_id=checkpoint_id,
            cognitive_revision=cognitive_revision,
        )
        return StoredOutcomeProductionProposal(
            artifact_id=proposal_id,
            schema_version="outcome-review-proposal.v1",
            uri=uri,
            sha256=payload_hash,
            size_bytes=len(payload),
        )


class ProductionOutcomeGraphProvider:
    """Stream the compiled REVIEW graph and bind its proposal source at terminal commit."""

    room_type = RoomType.REVIEW

    def __init__(self, *, saver: FencedPostgresSaver, store: ProductionImmutableObjectStore) -> None:
        self._saver = saver
        self._executor = CompiledOutcomeProductionExecutor(
            saver=saver,
            invocation_provider=ProductionObjectOutcomeInvocationProvider(store),
            proposal_store=ProductionObjectOutcomeProposalStore(store),
        )

    def stream(self, execution: GatewayExecution) -> AsyncIterator[AgentStreamEvent]:
        async def events() -> AsyncIterator[AgentStreamEvent]:
            command = execution.admission.command
            yield _event(execution, 0, "attempt_started", "outcome_production_runtime")
            material = await self._executor.execute(execution)
            source = ProductionRoomProposalSource.model_validate(
                material.source.model_dump(mode="json")
            )
            proposal = source.proposal
            artifact_operation = ArtifactOperation(
                operation="PROPOSE_PATCH",
                artifact=ArtifactPointer(
                    artifact_id=proposal.proposal_id,
                    schema_version=proposal.payload_schema_version,
                    uri=proposal.payload_ref,
                    sha256=proposal.payload_hash,
                ),
            )
            bindings = ResultBindings(
                command_id=command.command_id,
                logical_run_id=command.logical_run_id,
                attempt_id=command.attempt_id,
                graph_key=command.graph_key,
                graph_version=command.graph_version,
                checkpoint_id=material.checkpoint_id,
                cognitive_revision=material.cognitive_revision,
                public_event_proposals=(),
                artifact_operations=(artifact_operation,),
                usage=Usage(input_tokens=0, output_tokens=0, total_tokens=0),
                execution_metadata=ExecutionMetadata(
                    prompt_version=command.invocation_context.prompt_profile_id,
                    model_profile_id=command.invocation_context.model_profile_id,
                    schema_version=command.invocation_context.output_schema_version,
                    policy_version=command.invocation_context.policy_version,
                    guardrail_version=command.invocation_context.guardrail_version,
                ),
            )
            result = TerminalResultMaterializer(
                thread_id=execution.fence.thread_id,
                request_hash=command.request_hash,
                draft=NeedsReviewDraft(
                    status="NEEDS_REVIEW",
                    needs_review={
                        "reason_code": "REVIEW_ADVISORY_REQUIRED",
                        "risk_level": "MEDIUM",
                    },
                ),
                bindings=bindings,
                target_proposal_source=source,
            ).materialize(material.checkpoint_ns, material.checkpoint_id, fence=execution.fence)
            await self._saver.acommit_external_terminal(
                bind_fence_context(
                    {
                        "configurable": {
                            "thread_id": execution.fence.thread_id,
                            "checkpoint_ns": material.checkpoint_ns,
                            "checkpoint_id": material.checkpoint_id,
                        }
                    },
                    execution.fence,
                ),
                ExternalTerminalCommit(
                    result=result,
                    cognitive_revision=material.cognitive_revision,
                ),
            )
            yield _event(execution, 1, "final", None, result.result_ref, result.result_hash)

        return events()


class _ExecutionScopedEvidenceProvider:
    room_type = RoomType.EVIDENCE

    def __init__(
        self,
        *,
        saver: FencedPostgresSaver,
        dependencies: ProductionSpecializedRoomDependencies,
    ) -> None:
        self._dependencies = dependencies
        self._executor = CompiledEvidenceTurnExecutor(
            saver=saver,
            workflow=dependencies.evidence_workflow,
        )

    def stream(self, execution: GatewayExecution) -> AsyncIterator[AgentStreamEvent]:
        store = self._dependencies.store_for_execution(execution)
        return self._executor.stream(execution, store=store)


class _ExecutionScopedHearingProvider:
    room_type = RoomType.HEARING

    def __init__(
        self,
        *,
        saver: FencedPostgresSaver,
        dependencies: ProductionSpecializedRoomDependencies,
    ) -> None:
        self._saver = saver
        self._dependencies = dependencies

    def stream(self, execution: GatewayExecution) -> AsyncIterator[AgentStreamEvent]:
        store = self._dependencies.store_for_execution(execution)
        return build_production_runtime_hearing_provider(
            checkpointer=self._saver,
            invocation_provider=ProductionObjectHearingInvocationProvider(
                store,
                self._dependencies.hearing_decoder,
            ),
            payload_store=ProductionObjectHearingProposalStore(store),
        ).stream(execution)


class _ExecutionScopedOutcomeProvider:
    room_type = RoomType.REVIEW

    def __init__(
        self,
        *,
        saver: FencedPostgresSaver,
        dependencies: ProductionSpecializedRoomDependencies,
    ) -> None:
        self._saver = saver
        self._dependencies = dependencies

    def stream(self, execution: GatewayExecution) -> AsyncIterator[AgentStreamEvent]:
        return ProductionOutcomeGraphProvider(
            saver=self._saver,
            store=self._dependencies.store_for_execution(execution),
        ).stream(execution)


def build_production_runtime_specialized_room_providers(
    *,
    saver: FencedPostgresSaver,
    bulkhead: Any,
    dependencies: ProductionSpecializedRoomDependencies,
) -> tuple[ProductionRoomProvider, ProductionRoomProvider, ProductionRoomProvider]:
    """Build exact specialized providers; caller owns trusted lifecycle dependencies."""

    return (
        _ExecutionScopedEvidenceProvider(
            saver=saver,
            dependencies=dependencies,
        ),
        _ExecutionScopedHearingProvider(saver=saver, dependencies=dependencies),
        _ExecutionScopedOutcomeProvider(saver=saver, dependencies=dependencies),
    )


def _deterministic_evidence_model() -> Any:
    """A no-egress LCEL model used only by the signed synthetic candidate lane."""

    from langchain_core.runnables import RunnableLambda

    draft = EvidenceAssessmentDraft(
        assessment_status="NEEDS_REVIEW",
        authenticity_score=0.0,
        authenticity_reason_codes=(),
        relevance_score=0.0,
        relevance_reason_codes=(),
        completeness_score=0.0,
        confidence=0.0,
        candidate_fact_links=(),
        limitations=("FIXTURE_MODEL_ONLY",),
        review_reasons=("FIXTURE_REVIEW_REQUIRED",),
    )
    payload = canonicalize(draft.model_dump(mode="json")).decode("utf-8")
    return RunnableLambda(lambda _prompt: payload)


async def _load_exact(
    store: ProductionImmutableObjectStore,
    reference: SnapshotRef,
    *,
    allow_smaller: bool = False,
) -> bytes:
    payload = await store.load(reference)
    if not isinstance(payload, bytes) or not payload:
        raise GraphContractError("PRODUCTION_RUNTIME_OBJECT_LOAD_INVALID")
    if len(payload) > reference.size_bytes or (
        not allow_smaller and len(payload) != reference.size_bytes
    ) or hashlib.sha256(payload).hexdigest() != reference.sha256:
        raise GraphContractError("PRODUCTION_RUNTIME_OBJECT_LOAD_BINDING_MISMATCH")
    return payload


def _object_document(payload: bytes, code: str) -> dict[str, Any]:
    try:
        value = json.loads(payload)
    except (TypeError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise GraphContractError(code) from error
    if not isinstance(value, dict) or canonicalize(value) != payload:
        raise GraphContractError(code)
    return cast(dict[str, Any], deepcopy(value))


def _event(
    execution: GatewayExecution,
    sequence_no: int,
    event_type: str,
    node: str | None = None,
    result_ref: str | None = None,
    result_hash: str | None = None,
) -> AgentStreamEvent:
    command = execution.admission.command
    return AgentStreamEvent(
            schema_version="agent-stream.v3",
        run_id=command.logical_run_id,
        attempt_id=command.attempt_id,
        sequence_no=sequence_no,
        event_type=event_type,
        audience=command.actor_scope.audience,
        occurred_at=datetime.now(timezone.utc),
        payload=AgentStreamPayload(
            node=node,
            final_result_ref=result_ref,
            final_result_hash=result_hash,
        ),
    )


__all__ = [
    "ProductionImmutableObjectStore",
    "ProductionHearingInvocationDecoder",
    "ProductionObjectEvidenceAssetLoader",
    "ProductionObjectEvidenceManifestLoader",
    "ProductionObjectEvidenceProposalStore",
    "ProductionObjectHearingInvocationProvider",
    "ProductionObjectHearingProposalStore",
    "ProductionObjectOutcomeInvocationProvider",
    "ProductionObjectOutcomeProposalStore",
    "ProductionOutcomeGraphProvider",
    "ProductionSpecializedRoomDependencies",
    "ProductionImmutableObjectStoreFactory",
    "build_production_runtime_specialized_room_providers",
]
