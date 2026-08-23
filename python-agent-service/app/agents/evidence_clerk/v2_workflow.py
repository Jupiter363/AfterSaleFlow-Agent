"""One-call Evidence room v2 workflow and deterministic frame materializer."""

from __future__ import annotations

from collections.abc import AsyncIterator, Mapping
from copy import deepcopy
from dataclasses import dataclass
import hashlib
from typing import Any

from app.contracts.v1.codec import canonical_sha256
from app.graph_runtime.errors import GraphContractError
from app.harness.context_pack import build_context_pack
from app.harness.evidence_asset_loader import validated_evidence_asset_manifest
from app.harness.evidence_room_context_v2 import (
    AssembledEvidenceRoomContextV2,
    assemble_evidence_room_context_v2,
    finalize_evidence_room_sources_v2,
)
from app.harness.invocation_context import AgentInvocationContext
from app.harness.model_runner import HarnessStreamCompleted, HarnessStreamDelta
from app.schemas import EvidenceTurnRequest
from app.streaming import current_stream_observer
from app.agents.evidence_clerk.v2_contracts import (
    CommittedEvidenceFrameV2,
    EvidenceFrameObjectV2,
    EvidenceMaterialReviewNoObservationStreamV2,
    EvidenceMaterialReviewStreamV2,
    EvidenceRoomOpeningStreamV2,
    EvidenceTextFollowupStreamV2,
    EvidenceTurnResultV2,
    EvidenceTurnStreamV2,
    leading_evidence_frame_header_v2,
)
from app.agents.evidence_clerk.v2_policy import (
    EvidenceV2PublicOutputPolicy,
    bind_assessment_observation_slots,
    bind_room_readiness_fact_ids,
)


@dataclass(frozen=True)
class EvidenceV2Generation:
    value: EvidenceTurnResultV2
    usage: Mapping[str, int]


class EvidenceTurnWorkflowV2:
    """Generate one frame stream; no semantic rewrite or second model call."""

    protocol_version = "evidence-turn-result.v3"

    def __init__(self, *, model_runner: Any, asset_loader: Any | None = None) -> None:
        self._model_runner = model_runner
        self._asset_loader = asset_loader

    def run(self, request: EvidenceTurnRequest) -> EvidenceTurnResultV2:
        """Synchronous API compatibility for the non-streaming internal route."""

        import asyncio

        return asyncio.run(self.arun(request))

    async def arun(self, request: EvidenceTurnRequest) -> EvidenceTurnResultV2:
        assembled = assemble_evidence_room_context_v2(request)
        mode = assembled.payload["turn_contract"]["turn_mode"]
        if mode == "REENTRY_REPLAY":
            raise GraphContractError("EVIDENCE_V2_REENTRY_REQUIRES_DURABLE_REPLAY")
        loaded_assets = None
        asset_manifest = None
        if self._asset_loader is not None:
            loaded_assets = await _load_assets(self._asset_loader, assembled)
            asset_manifest = validated_evidence_asset_manifest(loaded_assets)
        assembled = finalize_evidence_room_sources_v2(assembled, asset_manifest)
        output_type = _authority_bound_output_type(
            _output_type_for_mode(
                mode,
                allow_observation=bool(assembled.source_units),
            ),
            assembled,
        )
        context_pack = build_context_pack(
            "evidence_turn",
            {"evidence_room_context_v2": assembled.payload},
        )
        agent_context = AgentInvocationContext.model_validate(
            request.agent_context.model_dump(mode="python")
        )
        observer = current_stream_observer()
        if observer is not None:
            policy = observer.public_output_policy
            if isinstance(policy, EvidenceV2PublicOutputPolicy):
                policy.configure(assembled)
        visible_fields = (
            observer.visible_fields_for("evidence_turn") if observer is not None else ()
        )
        stream_runner = getattr(self._model_runner, "ainvoke_structured_stream", None)
        if not callable(stream_runner):
            raise GraphContractError("EVIDENCE_V2_STREAMING_RUNNER_REQUIRED")
        generation = await _consume_stream(
            stream_runner(
                node_name="evidence_turn",
                case_data={
                    "case_id": assembled.base.working_set.case_id,
                    "room_type": "EVIDENCE",
                    "turn_mode": mode,
                    "agent_key": "EVIDENCE_CLERK",
                },
                output_type=output_type,
                visible_fields=visible_fields,
                context_pack=context_pack,
                agent_context=agent_context,
                prompt_profile_id=agent_context.prompt_profile_id,
                evidence_assets=loaded_assets,
            ),
            observer=observer,
        )
        stream = EvidenceTurnStreamV2.model_validate(
            generation.value.model_dump(mode="json")
        )
        stream = _bind_v2_transport_sequences(stream)
        stream = _bind_v2_room_readiness_fact_ids(stream, assembled)
        _validate_v2_authority(stream, assembled)
        stream = _bind_v2_assessment_observation_slots(stream, assembled)
        return _materialize_result(stream, assembled, request)


async def _consume_stream(
    updates: AsyncIterator[HarnessStreamDelta | HarnessStreamCompleted[Any]],
    *,
    observer: Any | None,
) -> Any:
    """Forward governed deltas immediately and retain only the final value."""

    generation: Any | None = None
    async for update in updates:
        if isinstance(update, HarnessStreamDelta):
            if observer is not None:
                observer.visible_delta("evidence_turn", update.field, update.delta)
            continue
        if not isinstance(update, HarnessStreamCompleted):
            raise GraphContractError("EVIDENCE_V2_STREAM_UPDATE_INVALID")
        if generation is not None:
            raise GraphContractError("EVIDENCE_V2_STREAM_COMPLETION_DUPLICATED")
        generation = update.generation
    if generation is None:
        raise GraphContractError("EVIDENCE_V2_STREAM_COMPLETION_MISSING")
    return generation


async def _load_assets(loader: Any, assembled: AssembledEvidenceRoomContextV2) -> Any:
    """Load only the already-authorized envelope assets in a worker thread."""

    import asyncio

    return await asyncio.to_thread(loader.load, assembled.base.raw_envelope)


def _output_type_for_mode(
    mode: str,
    *,
    allow_observation: bool = True,
) -> type[EvidenceTurnStreamV2]:
    if mode == "ROOM_OPENING":
        return EvidenceRoomOpeningStreamV2
    if mode == "MATERIAL_REVIEW":
        return (
            EvidenceMaterialReviewStreamV2
            if allow_observation
            else EvidenceMaterialReviewNoObservationStreamV2
        )
    if mode == "TEXT_FOLLOWUP":
        return EvidenceTextFollowupStreamV2
    raise GraphContractError("EVIDENCE_V2_TURN_MODE_INVALID")


def _authority_bound_output_type(
    output_type: type[EvidenceTurnStreamV2],
    assembled: AssembledEvidenceRoomContextV2,
) -> type[EvidenceTurnStreamV2]:
    """Narrow provider-visible identifier fields to this frozen invocation."""

    schema = deepcopy(output_type.model_json_schema())
    mode = str(assembled.payload["turn_contract"]["turn_mode"])
    fact_ids = _unique_authority_ids(
        item["fact_id"]
        for item in assembled.base.working_set.allowed_fact_targets
    )
    attachment_ids = _unique_authority_ids(
        assembled.base.raw_envelope.current_event.attachment_refs
    )
    source_unit_ids = _unique_authority_ids(
        item["source_unit_id"] for item in assembled.source_units
    )
    if not fact_ids:
        raise GraphContractError("EVIDENCE_V2_FACT_AUTHORITY_EMPTY")

    required_fact_arrays = {
        "ROOM_OPENING": ("focus_fact_ids", "target_fact_ids", "remaining_core_fact_ids"),
        "MATERIAL_REVIEW": (
            (
                "candidate_fact_ids",
                "target_fact_ids",
                "remaining_core_fact_ids",
            )
            if source_unit_ids
            else ("target_fact_ids", "remaining_core_fact_ids")
        ),
        "TEXT_FOLLOWUP": ("target_fact_ids", "remaining_core_fact_ids"),
    }.get(mode)
    if required_fact_arrays is None:
        raise GraphContractError("EVIDENCE_V2_TURN_MODE_INVALID")
    for property_name in required_fact_arrays:
        if _bind_schema_enum(
            schema,
            property_name,
            fact_ids,
            array_items=True,
        ) < 1:
            raise GraphContractError("EVIDENCE_V2_PROVIDER_SCHEMA_BINDING_INVALID")
    if mode == "MATERIAL_REVIEW":
        if not attachment_ids:
            raise GraphContractError("EVIDENCE_V2_ATTACHMENT_AUTHORITY_EMPTY")
        if _bind_schema_enum(
            schema,
            "evidence_id",
            attachment_ids,
            array_items=False,
        ) < 1:
            raise GraphContractError("EVIDENCE_V2_PROVIDER_SCHEMA_BINDING_INVALID")
        if source_unit_ids:
            if _bind_schema_enum(
                schema,
                "fact_id",
                fact_ids,
                array_items=False,
            ) < 1:
                raise GraphContractError("EVIDENCE_V2_PROVIDER_SCHEMA_BINDING_INVALID")
            if _bind_schema_enum(
                schema,
                "source_unit_id",
                source_unit_ids,
                array_items=False,
            ) < 1:
                raise GraphContractError("EVIDENCE_V2_PROVIDER_SCHEMA_BINDING_INVALID")

    _strip_schema_titles(schema)

    bound_schema = deepcopy(schema)

    class AuthorityBoundEvidenceTurnStream(output_type):
        @classmethod
        def model_json_schema(cls, *args: Any, **kwargs: Any) -> dict[str, Any]:
            del cls, args, kwargs
            return deepcopy(bound_schema)

    AuthorityBoundEvidenceTurnStream.__name__ = output_type.__name__
    AuthorityBoundEvidenceTurnStream.__qualname__ = output_type.__qualname__
    return AuthorityBoundEvidenceTurnStream


def _unique_authority_ids(values: Any) -> tuple[str, ...]:
    unique: dict[str, None] = {}
    for value in values:
        identifier = str(value)
        if not identifier:
            raise GraphContractError("EVIDENCE_V2_PROVIDER_SCHEMA_BINDING_INVALID")
        unique.setdefault(identifier, None)
    return tuple(unique)


def _bind_schema_enum(
    value: Any,
    property_name: str,
    allowed_values: tuple[str, ...],
    *,
    array_items: bool,
) -> int:
    """Bind every matching output-schema property without guessing def names."""

    bound = 0
    if isinstance(value, dict):
        properties = value.get("properties")
        if isinstance(properties, dict):
            property_schema = properties.get(property_name)
            if isinstance(property_schema, dict):
                target = property_schema.get("items") if array_items else property_schema
                if isinstance(target, dict):
                    target["enum"] = list(allowed_values)
                    bound += 1
        for nested in value.values():
            bound += _bind_schema_enum(
                nested,
                property_name,
                allowed_values,
                array_items=array_items,
            )
    elif isinstance(value, list):
        for nested in value:
            bound += _bind_schema_enum(
                nested,
                property_name,
                allowed_values,
                array_items=array_items,
            )
    return bound


def _strip_schema_titles(value: Any) -> None:
    """Remove generated display labels without weakening provider constraints."""

    if isinstance(value, dict):
        value.pop("title", None)
        for nested in value.values():
            _strip_schema_titles(nested)
    elif isinstance(value, list):
        for nested in value:
            _strip_schema_titles(nested)


def _validate_v2_authority(
    stream: EvidenceTurnStreamV2,
    assembled: AssembledEvidenceRoomContextV2,
) -> None:
    """Enforce only immutable ID, attachment and actor authority.

    Frame order, cardinality, budgets and semantic quality belong to the
    provider Scheme/prompt contract.  This post-provider boundary must not
    reinterpret or repair model output.
    """

    attachment_ids = tuple(assembled.base.raw_envelope.current_event.attachment_refs)
    fact_ids = {item["fact_id"] for item in assembled.base.working_set.allowed_fact_targets}
    source_units = {item["source_unit_id"]: item for item in assembled.source_units}
    attachment_set = set(attachment_ids)
    actor_role = str(assembled.payload["authority_scope"]["actor_role"])
    current_batch = {
        str(item["evidence_id"]): item
        for item in assembled.payload["current_evidence_batch"]
    }
    if attachment_set != set(current_batch):
        raise GraphContractError("EVIDENCE_V2_ATTACHMENT_SCOPE_INVALID")
    if any(str(item.get("submitted_by_role")) != actor_role for item in current_batch.values()):
        raise GraphContractError("EVIDENCE_V2_ATTACHMENT_ROLE_AUTHORITY_INVALID")

    headers = [frame.header for frame in stream.frames]
    for header in headers:
        if header.frame_type == "OPENING_ORIENTATION":
            if any(fact_id not in fact_ids for fact_id in header.focus_fact_ids):
                raise GraphContractError("EVIDENCE_V2_FACT_ID_OUT_OF_SCOPE")
        if header.frame_type == "EVIDENCE_OBSERVATION":
            source_id = str(header.source_unit_id or "")
            source = source_units.get(source_id) if source_id else None
            if source_id and (source is None or source["evidence_id"] not in attachment_set):
                raise GraphContractError("EVIDENCE_V2_SOURCE_UNIT_OUT_OF_SCOPE")
            if any(
                binding.fact_id is not None and binding.fact_id not in fact_ids
                for binding in header.fact_bindings
            ):
                raise GraphContractError("EVIDENCE_V2_FACT_ID_OUT_OF_SCOPE")
            if any(fact_id not in fact_ids for fact_id in header.candidate_fact_ids):
                raise GraphContractError("EVIDENCE_V2_FACT_ID_OUT_OF_SCOPE")
        elif header.frame_type == "EVIDENCE_ASSESSMENT":
            if header.evidence_id is not None and header.evidence_id not in attachment_set:
                raise GraphContractError("EVIDENCE_V2_ASSESSMENT_OUT_OF_SCOPE")
        elif header.frame_type == "EVIDENCE_REQUEST":
            if any(fact_id not in fact_ids for fact_id in header.target_fact_ids):
                raise GraphContractError("EVIDENCE_V2_REQUEST_FACT_OUT_OF_SCOPE")
        elif header.frame_type == "ROOM_READINESS":
            if any(fact_id not in fact_ids for fact_id in header.remaining_core_fact_ids):
                raise GraphContractError("EVIDENCE_V2_FACT_ID_OUT_OF_SCOPE")


def _bind_v2_room_readiness_fact_ids(
    stream: EvidenceTurnStreamV2,
    assembled: AssembledEvidenceRoomContextV2,
) -> EvidenceTurnStreamV2:
    """Apply the live frozen-readiness projection to the terminal object."""

    allowed_fact_ids = tuple(
        item["fact_id"]
        for item in assembled.base.working_set.allowed_fact_targets
    )
    return stream.model_copy(
        update={
            "frames": [
                frame.model_copy(
                    update={
                        "header": bind_room_readiness_fact_ids(
                            frame.header,
                            allowed_fact_ids,
                        )
                    }
                )
                for frame in stream.frames
            ]
        }
    )


def _bind_v2_transport_sequences(stream: EvidenceTurnStreamV2) -> EvidenceTurnStreamV2:
    """Replace model sequence guesses with deterministic transport order."""

    frames = []
    for sequence, frame in enumerate(stream.frames, start=2):
        frames.append(
            frame.model_copy(
                update={
                    "header": frame.header.model_copy(
                        update={"frame_sequence": sequence}
                    )
                }
            )
        )
    return stream.model_copy(update={"frames": frames})


def _bind_v2_assessment_observation_slots(
    stream: EvidenceTurnStreamV2,
    assembled: AssembledEvidenceRoomContextV2,
) -> EvidenceTurnStreamV2:
    """Project immutable source-unit ownership into assessment transport bindings."""

    source_units = {
        str(item["source_unit_id"]): str(item["evidence_id"])
        for item in assembled.source_units
    }
    observation_evidence: list[tuple[str, str]] = []
    frames = []
    for frame in stream.frames:
        header = frame.header
        if header.frame_type == "EVIDENCE_OBSERVATION":
            source_id = str(header.source_unit_id or "")
            slot = str(header.observation_slot or "")
            evidence_id = source_units.get(source_id)
            if slot and evidence_id is not None:
                observation_evidence.append((slot, evidence_id))
        elif header.frame_type == "EVIDENCE_ASSESSMENT":
            header = bind_assessment_observation_slots(
                header,
                observation_evidence,
            )
        frames.append(frame.model_copy(update={"header": header}))
    return stream.model_copy(update={"frames": frames})


def _materialize_result(
    stream: EvidenceTurnStreamV2,
    assembled: AssembledEvidenceRoomContextV2,
    request: EvidenceTurnRequest,
) -> EvidenceTurnResultV2:
    run_key = request.agent_context.agent_invocation_id
    committed: list[CommittedEvidenceFrameV2] = []
    public_parts: list[str] = []
    observations: list[dict[str, Any]] = []
    assessments: list[dict[str, Any]] = []
    requests: list[dict[str, Any]] = []
    readiness: dict[str, Any] = {}
    mode = assembled.payload["turn_contract"]["turn_mode"]
    leading_frame = EvidenceFrameObjectV2(
        header=leading_evidence_frame_header_v2(
            mode,
            attachment_ids=tuple(
                assembled.base.raw_envelope.current_event.attachment_refs
            ),
        ),
        public_text=stream.lead_public_text,
    )
    for frame in (leading_frame, *stream.frames):
        header = frame.header
        text = frame.public_text
        header_doc = header.model_dump(
            mode="json",
            exclude_none=True,
            exclude_defaults=True,
        )
        header_hash = canonical_sha256(header_doc)
        text_value = text or ""
        text_hash = hashlib.sha256(text_value.encode("utf-8")).hexdigest()
        frame_id = "FRAME_" + canonical_sha256(
            {
                "run": run_key,
                "sequence": header.frame_sequence,
                "header": header_doc,
                "text": text_value,
            }
        )[:24].upper()
        frame_document = {
            "frame_id": frame_id,
            "frame_sequence": header.frame_sequence,
            "frame_type": header.frame_type,
            "header": header_doc,
            "header_sha256": header_hash,
            "public_text": text,
            "public_text_sha256": text_hash,
            "public_text_length": len(text_value),
        }
        committed.append(
            CommittedEvidenceFrameV2(
                **frame_document,
                frame_sha256=canonical_sha256(frame_document),
            )
        )
        if text:
            public_parts.append(text)
        if header.frame_type == "EVIDENCE_OBSERVATION":
            observations.append(header_doc)
        elif header.frame_type == "EVIDENCE_ASSESSMENT":
            assessments.append(header_doc)
        elif header.frame_type == "EVIDENCE_REQUEST":
            requests.append(header_doc)
        elif header.frame_type == "ROOM_READINESS":
            readiness = header_doc
    manifest = [item.model_dump(mode="json") for item in committed]
    manifest_hash = canonical_sha256(manifest)
    return EvidenceTurnResultV2(
        frame_manifest=committed,
        frame_manifest_sha256=manifest_hash,
        room_utterance="\n\n".join(public_parts),
        referenced_evidence_ids=list(request.context_envelope.current_event.attachment_refs),
        observation_graph=observations,
        evidence_assessments=assessments,
        evidence_requests=requests,
        room_readiness=readiness,
    )


__all__ = ["EvidenceTurnWorkflowV2", "EvidenceV2Generation"]
