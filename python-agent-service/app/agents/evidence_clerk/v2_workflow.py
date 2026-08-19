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
from app.harness.evidence_room_context_v2 import (
    AssembledEvidenceRoomContextV2,
    assemble_evidence_room_context_v2,
)
from app.harness.invocation_context import AgentInvocationContext
from app.harness.model_runner import HarnessStreamCompleted, HarnessStreamDelta
from app.schemas import EvidenceTurnRequest
from app.streaming import current_stream_observer
from app.agents.evidence_clerk.v2_contracts import (
    CommittedEvidenceFrameV2,
    EvidenceFrameObjectV2,
    EvidenceMaterialReviewStreamV2,
    EvidenceRoomOpeningStreamV2,
    EvidenceTextFollowupStreamV2,
    EvidenceTurnResultV2,
    EvidenceTurnStreamV2,
    leading_evidence_frame_header_v2,
)
from app.agents.evidence_clerk.v2_policy import EvidenceV2PublicOutputPolicy


@dataclass(frozen=True)
class EvidenceV2Generation:
    value: EvidenceTurnResultV2
    usage: Mapping[str, int]


class EvidenceTurnWorkflowV2:
    """Generate one frame stream; no semantic rewrite or second model call."""

    protocol_version = "evidence-turn-result.v2"

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
        output_type = _authority_bound_output_type(
            _output_type_for_mode(mode),
            assembled,
        )
        context_pack = build_context_pack(
            "evidence_turn",
            {"evidence_room_context_v2": assembled.payload},
        )
        agent_context = AgentInvocationContext.model_validate(
            request.agent_context.model_dump(mode="python")
        )
        loaded_assets = None
        if self._asset_loader is not None:
            loaded_assets = await _load_assets(self._asset_loader, assembled)
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
        _validate_v2_frames(stream, assembled)
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


def _output_type_for_mode(mode: str) -> type[EvidenceTurnStreamV2]:
    if mode == "ROOM_OPENING":
        return EvidenceRoomOpeningStreamV2
    if mode == "MATERIAL_REVIEW":
        return EvidenceMaterialReviewStreamV2
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
            "candidate_fact_ids",
            "target_fact_ids",
            "remaining_core_fact_ids",
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
        if _bind_schema_enum(
            schema,
            "fact_id",
            fact_ids,
            array_items=False,
        ) < 1:
            raise GraphContractError("EVIDENCE_V2_PROVIDER_SCHEMA_BINDING_INVALID")
        if source_unit_ids and _bind_schema_enum(
            schema,
            "source_unit_id",
            source_unit_ids,
            array_items=False,
        ) < 1:
            raise GraphContractError("EVIDENCE_V2_PROVIDER_SCHEMA_BINDING_INVALID")

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


def _validate_v2_frames(
    stream: EvidenceTurnStreamV2,
    assembled: AssembledEvidenceRoomContextV2,
) -> None:
    mode = assembled.payload["turn_contract"]["turn_mode"]
    attachment_ids = tuple(assembled.base.raw_envelope.current_event.attachment_refs)
    leading_header = leading_evidence_frame_header_v2(
        mode,
        attachment_ids=attachment_ids,
    )
    headers = [leading_header, *(frame.header for frame in stream.frames)]
    types = [header.frame_type for header in headers]
    if not headers:
        raise GraphContractError("EVIDENCE_V2_FRAME_STREAM_EMPTY")
    allowed = set(assembled.payload["turn_contract"]["allowed_frame_types"])
    if any(frame_type not in allowed for frame_type in types):
        raise GraphContractError("EVIDENCE_V2_FRAME_TYPE_NOT_ALLOWED")
    if headers[-1].frame_type != "ROOM_READINESS":
        raise GraphContractError("EVIDENCE_V2_READINESS_NOT_LAST")
    fact_ids = {item["fact_id"] for item in assembled.base.working_set.allowed_fact_targets}
    source_units = {item["source_unit_id"]: item for item in assembled.source_units}
    attachment_set = set(attachment_ids)
    if len(attachment_set) != len(attachment_ids):
        raise GraphContractError("EVIDENCE_V2_ATTACHMENT_SCOPE_DUPLICATED")

    if mode == "ROOM_OPENING":
        request_count = types.count("EVIDENCE_REQUEST")
        if (
            len(types) != request_count + 3
            or types[0] != "ROOM_WELCOME"
            or types[1] != "OPENING_ORIENTATION"
            or request_count not in {2, 3}
            or types[-1] != "ROOM_READINESS"
            or any(frame_type != "EVIDENCE_REQUEST" for frame_type in types[2:-1])
        ):
            raise GraphContractError("EVIDENCE_V2_OPENING_FRAME_ORDER_INVALID")
        if any(fact_id not in fact_ids for fact_id in headers[1].focus_fact_ids):
            raise GraphContractError("EVIDENCE_V2_FACT_ID_OUT_OF_SCOPE")
    elif mode == "MATERIAL_REVIEW":
        if types[0] != "MATERIAL_RECEIPT":
            raise GraphContractError("EVIDENCE_V2_MATERIAL_RECEIPT_REQUIRED")
        assessment_headers = [
            header for header in headers if header.frame_type == "EVIDENCE_ASSESSMENT"
        ]
        if tuple(headers[0].evidence_ids) != attachment_ids:
            raise GraphContractError("EVIDENCE_V2_MATERIAL_RECEIPT_SCOPE_INVALID")
        if any(fact_id not in fact_ids for fact_id in headers[0].focus_fact_ids):
            raise GraphContractError("EVIDENCE_V2_FACT_ID_OUT_OF_SCOPE")
        if [header.evidence_id for header in assessment_headers] != list(attachment_ids):
            raise GraphContractError("EVIDENCE_V2_ASSESSMENT_CARDINALITY_INVALID")
        if len(assessment_headers) != len(attachment_ids):
            raise GraphContractError("EVIDENCE_V2_ASSESSMENT_CARDINALITY_INVALID")
        first_assessment = types.index("EVIDENCE_ASSESSMENT")
        assessment_end = first_assessment + len(attachment_ids)
        if types[first_assessment:assessment_end] != [
            "EVIDENCE_ASSESSMENT"
        ] * len(attachment_ids):
            raise GraphContractError("EVIDENCE_V2_MATERIAL_FRAME_ORDER_INVALID")
        if any(frame_type != "EVIDENCE_OBSERVATION" for frame_type in types[1:first_assessment]):
            raise GraphContractError("EVIDENCE_V2_MATERIAL_FRAME_ORDER_INVALID")
        tail = types[assessment_end:-1]
        seen_review = False
        for frame_type in tail:
            if frame_type == "HUMAN_REVIEW_TASK":
                seen_review = True
            elif frame_type == "EVIDENCE_REQUEST" and seen_review:
                raise GraphContractError("EVIDENCE_V2_MATERIAL_FRAME_ORDER_INVALID")
            elif frame_type != "EVIDENCE_REQUEST":
                raise GraphContractError("EVIDENCE_V2_MATERIAL_FRAME_ORDER_INVALID")
    elif mode == "TEXT_FOLLOWUP":
        if types[0] != "TEXT_FOLLOWUP_REPLY":
            raise GraphContractError("EVIDENCE_V2_TEXT_REPLY_REQUIRED")
        if any(
            frame_type in {"EVIDENCE_OBSERVATION", "EVIDENCE_ASSESSMENT", "HUMAN_REVIEW_TASK"}
            for frame_type in types
        ):
            raise GraphContractError("EVIDENCE_V2_TEXT_MODE_CONTAINS_MATERIAL_FRAME")

    observation_slots: set[str] = set()
    observation_evidence: dict[str, str] = {}
    assessment_slots: set[tuple[str, str]] = set()
    request_slots: set[str] = set()
    for header in headers:
        if header.frame_type == "EVIDENCE_OBSERVATION":
            if header.observation_slot in observation_slots:
                raise GraphContractError("EVIDENCE_V2_OBSERVATION_SLOT_DUPLICATED")
            observation_slots.add(str(header.observation_slot))
            source = source_units.get(str(header.source_unit_id))
            if source is None or source["evidence_id"] not in attachment_set:
                raise GraphContractError("EVIDENCE_V2_SOURCE_UNIT_OUT_OF_SCOPE")
            observation_evidence[str(header.observation_slot)] = source["evidence_id"]
            if any(binding.fact_id not in fact_ids for binding in header.fact_bindings):
                raise GraphContractError("EVIDENCE_V2_FACT_ID_OUT_OF_SCOPE")
            if any(fact_id not in fact_ids for fact_id in header.candidate_fact_ids):
                raise GraphContractError("EVIDENCE_V2_FACT_ID_OUT_OF_SCOPE")
        elif header.frame_type == "EVIDENCE_ASSESSMENT":
            if header.evidence_id not in attachment_set:
                raise GraphContractError("EVIDENCE_V2_ASSESSMENT_OUT_OF_SCOPE")
            for slot in header.observation_slots:
                if slot not in observation_slots:
                    raise GraphContractError("EVIDENCE_V2_ASSESSMENT_SLOT_UNKNOWN")
                if observation_evidence.get(slot) != header.evidence_id:
                    raise GraphContractError("EVIDENCE_V2_ASSESSMENT_SLOT_OUT_OF_SCOPE")
                pair = (str(header.evidence_id), str(slot))
                if pair in assessment_slots:
                    raise GraphContractError("EVIDENCE_V2_ASSESSMENT_SLOT_DUPLICATED")
                assessment_slots.add(pair)
        elif header.frame_type == "EVIDENCE_REQUEST":
            if header.request_slot in request_slots:
                raise GraphContractError("EVIDENCE_V2_REQUEST_SLOT_DUPLICATED")
            request_slots.add(str(header.request_slot))
            if any(fact_id not in fact_ids for fact_id in header.target_fact_ids):
                raise GraphContractError("EVIDENCE_V2_REQUEST_FACT_OUT_OF_SCOPE")
        elif header.frame_type == "HUMAN_REVIEW_TASK":
            if header.evidence_id not in attachment_set:
                raise GraphContractError("EVIDENCE_V2_REVIEW_TASK_OUT_OF_SCOPE")
    if mode == "MATERIAL_REVIEW" and {
        slot for _, slot in assessment_slots
    } != observation_slots:
        raise GraphContractError("EVIDENCE_V2_ASSESSMENT_SLOT_COVERAGE_INVALID")


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
    review_tasks: list[dict[str, Any]] = []
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
        if text is not None:
            public_parts.append(text)
        if header.frame_type == "EVIDENCE_OBSERVATION":
            observations.append(header_doc)
        elif header.frame_type == "EVIDENCE_ASSESSMENT":
            assessments.append(header_doc)
        elif header.frame_type == "EVIDENCE_REQUEST":
            requests.append(header_doc)
        elif header.frame_type == "HUMAN_REVIEW_TASK":
            review_tasks.append(header_doc)
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
        human_review_tasks=review_tasks,
        room_readiness=readiness,
    )


__all__ = ["EvidenceTurnWorkflowV2", "EvidenceV2Generation"]
