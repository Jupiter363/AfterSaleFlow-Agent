"""Ordered, single-source business context for the Evidence room v2 contract."""

from __future__ import annotations

import copy
from dataclasses import dataclass
import re
from typing import Any

from app.contracts.v1.codec import canonical_sha256
from app.graph_runtime.errors import GraphContractError
from app.harness.evidence_context_assembler import (
    AssembledEvidenceContext,
    EvidenceContextAssembler,
)
from app.schemas import EvidenceTurnRequest


MAX_SOURCE_UNITS = 64
MAX_SOURCE_UNIT_CHARS = 12_000
MAX_CURRENT_BATCH_ITEMS = 20
_PARAGRAPH_SPLIT = re.compile(r"\n\s*\n")
_SHA256_HEX = re.compile(r"^[0-9a-f]{64}$")


@dataclass(frozen=True)
class AssembledEvidenceRoomContextV2:
    base: AssembledEvidenceContext
    payload: dict[str, Any]
    source_units: tuple[dict[str, Any], ...]


def assemble_evidence_room_context_v2(
    request: EvidenceTurnRequest,
) -> AssembledEvidenceRoomContextV2:
    """Build the exact prompt order without repeating dossier or source text.

    ``EvidenceContextAssembler`` remains the permission/visibility boundary.  This
    layer only projects its already-authorized result into the frozen v2 order;
    it never re-reads a case or expands an actor's visibility.
    """

    base = EvidenceContextAssembler().assemble(request)
    envelope = base.raw_envelope
    actor = envelope.actor_snapshot
    event = envelope.current_event
    room_epoch = (
        envelope.frozen_submission.evidence_room_epoch
        if envelope.frozen_submission is not None
        else 0
    )
    mode = _turn_mode(base.working_set.task_mode, event)
    source_units = _source_unit_catalog(envelope, event.attachment_refs)
    matrix = _frozen_matrix(base)
    current_batch = _current_batch(envelope, event.attachment_refs)
    payload: dict[str, Any] = {
        # Deliberate insertion order is part of the context contract.
        "context_header": {
            "schema_version": "evidence_room_context.v2",
            "matrix_revision": _matrix_revision(matrix),
            "evidence_state_revision": _evidence_state_revision(base),
            "room_epoch": room_epoch,
            "context_coverage": "FULL",
        },
        "turn_contract": _turn_contract(mode, len(event.attachment_refs)),
        "authority_scope": {
            "case_id": envelope.case_snapshot.case_id,
            "room_type": envelope.room_policy.room_type,
            "room_epoch": room_epoch,
            "actor_id": actor.actor_id,
            "actor_role": actor.actor_role,
            "initiator_role": actor.initiator_role,
            "current_event_id": event.event_id,
            "current_batch_id": event.event_id,
            "attachment_refs": list(event.attachment_refs),
            "visible_attachment_refs": [item["evidence_id"] for item in current_batch],
        },
        "frozen_case_matrix": matrix,
        "current_evidence_batch": current_batch,
        "source_unit_catalog": {
            "schema_version": "evidence_source_unit_catalog.v2",
            "segmenter_version": "evidence-source-segmenter.v2",
            "normalization_version": "unicode-newline-normalization.v1",
            "source_authority_hash": _source_authority_hash(envelope, event.attachment_refs),
            "items": list(source_units),
        },
        "accepted_evidence_graph": {
            "matrix_snapshot": base.working_set.evidence_matrix_snapshot,
            "visible_assessments": _accepted_assessments(base),
        },
        "remaining_verification_requirements": _remaining_requirements(base),
        "private_actor_memory": _private_memory(base),
        "output_contract": {
            "schema_version": "evidence_turn_stream.v3",
            "frame_authority_schema": "evidence-turn-frame.v3",
            "lead_public_text_property": "lead_public_text",
            "lead_frame_type": (
                None if mode == "REENTRY_REPLAY" else _leading_frame_type(mode)
            ),
            "frame_wire": '{"header":{...},"public_text":string|null}',
            "frame_property_order": ["header", "public_text"],
            "remaining_frame_sequence_start": 2,
            "allowed_frame_types": _allowed_frame_types(mode),
            "frame_order": _frame_order(mode),
            "max_public_text_chars": 100_000,
            "max_frames": 128,
        },
    }
    return AssembledEvidenceRoomContextV2(
        base=base,
        payload=payload,
        source_units=source_units,
    )


def finalize_evidence_room_sources_v2(
    assembled: AssembledEvidenceRoomContextV2,
    asset_manifest: dict[str, Any] | None,
) -> AssembledEvidenceRoomContextV2:
    """Bind source authority to pixels that the loader actually delivered.

    Parsed text is known while the base context is assembled, but image pixels
    are known only after the privacy, MIME, size and hash gates run.  This
    second deterministic projection closes that timing gap before the provider
    schema and public stream policy are configured.
    """

    mode = str(assembled.payload["turn_contract"]["turn_mode"])
    if mode != "MATERIAL_REVIEW":
        return assembled

    attachment_refs = tuple(
        str(value)
        for value in assembled.base.raw_envelope.current_event.attachment_refs
    )
    visual_units = _visual_source_unit_catalog(asset_manifest, attachment_refs)
    combined_units = tuple(
        [*visual_units, *assembled.source_units][:MAX_SOURCE_UNITS]
    )
    allow_observation = bool(combined_units)

    payload = copy.deepcopy(assembled.payload)
    source_catalog = payload["source_unit_catalog"]
    source_catalog["items"] = copy.deepcopy(list(combined_units))
    source_catalog["source_authority_hash"] = canonical_sha256(
        {
            "base_source_authority_hash": source_catalog["source_authority_hash"],
            "source_units": [
                {
                    "source_unit_id": item["source_unit_id"],
                    "evidence_id": item["evidence_id"],
                    "basis": item["basis"],
                    "authority": item["authority"],
                }
                for item in combined_units
            ],
        }
    )

    turn_contract = payload["turn_contract"]
    output_contract = payload["output_contract"]
    turn_contract["allow_observation"] = allow_observation
    if allow_observation:
        frame_order = (
            "MATERIAL_RECEIPT -> OBSERVATION* -> "
            "ASSESSMENT(exactly one per attachment) -> REQUEST* -> ROOM_READINESS"
        )
    else:
        frame_order = (
            "MATERIAL_RECEIPT -> ASSESSMENT(exactly one per attachment) -> "
            "REQUEST* -> ROOM_READINESS"
        )
        turn_contract["allowed_frame_types"] = [
            value
            for value in turn_contract["allowed_frame_types"]
            if value != "EVIDENCE_OBSERVATION"
        ]
        output_contract["allowed_frame_types"] = [
            value
            for value in output_contract["allowed_frame_types"]
            if value != "EVIDENCE_OBSERVATION"
        ]
    turn_contract["frame_order"] = frame_order
    output_contract["frame_order"] = frame_order

    visual_by_id = {
        str(item["evidence_id"]): item
        for item in (asset_manifest or {}).get("items", [])
        if isinstance(item, dict) and isinstance(item.get("evidence_id"), str)
    }
    for item in payload["current_evidence_batch"]:
        descriptor = visual_by_id.get(str(item["evidence_id"]))
        if descriptor is None:
            continue
        item["visual_input_status"] = descriptor.get("visual_input_status")
        item["inspected_modalities"] = list(
            descriptor.get("inspected_modalities") or []
        )

    return AssembledEvidenceRoomContextV2(
        base=assembled.base,
        payload=payload,
        source_units=combined_units,
    )


def _turn_mode(task_mode: str, event: Any) -> str:
    if task_mode in {"ROOM_OPENING", "REENTRY_REPLAY"}:
        return "ROOM_OPENING" if task_mode == "ROOM_OPENING" else "REENTRY_REPLAY"
    if event.attachment_refs:
        return "MATERIAL_REVIEW"
    return "TEXT_FOLLOWUP"


def _turn_contract(mode: str, attachment_count: int) -> dict[str, Any]:
    if mode == "ROOM_OPENING":
        return {
            "turn_mode": mode,
            "goal": "根据冻结案情矩阵生成欢迎、案情梳理和针对性证据问询",
            "allowed_frame_types": [
                "ROOM_WELCOME",
                "OPENING_ORIENTATION",
                "EVIDENCE_REQUEST",
                "ROOM_READINESS",
            ],
            "frame_order": "ROOM_WELCOME -> OPENING_ORIENTATION -> EVIDENCE_REQUEST(2..3) -> ROOM_READINESS",
            "min_requests": 2,
            "max_requests": 3,
            "allow_observation": False,
            "allow_assessment": False,
            "attachment_count": attachment_count,
        }
    if mode == "MATERIAL_REVIEW":
        return {
            "turn_mode": mode,
            "goal": "核验当前附件与冻结案情矩阵的关联、来源和真实性风险",
            "allowed_frame_types": [
                "MATERIAL_RECEIPT",
                "EVIDENCE_OBSERVATION",
                "EVIDENCE_ASSESSMENT",
                "EVIDENCE_REQUEST",
                "ROOM_READINESS",
            ],
            "frame_order": "MATERIAL_RECEIPT -> OBSERVATION* -> ASSESSMENT(exactly one per attachment) -> REQUEST* -> ROOM_READINESS",
            "min_requests": 0,
            "max_requests": 3,
            "allow_observation": True,
            "allow_assessment": True,
            "attachment_count": attachment_count,
        }
    if mode == "TEXT_FOLLOWUP":
        return {
            "turn_mode": mode,
            "goal": "回应当事人补充说明并提出最小必要追问",
            "allowed_frame_types": [
                "TEXT_FOLLOWUP_REPLY",
                "EVIDENCE_REQUEST",
                "ROOM_READINESS",
            ],
            "frame_order": "TEXT_FOLLOWUP_REPLY -> REQUEST* -> ROOM_READINESS",
            "min_requests": 0,
            "max_requests": 3,
            "allow_observation": False,
            "allow_assessment": False,
            "attachment_count": attachment_count,
        }
    return {
        "turn_mode": mode,
        "goal": "只重放已正式提交的证据室帧",
        "allowed_frame_types": [],
        "frame_order": "COMMITTED_SNAPSHOT_ONLY",
        "min_requests": 0,
        "max_requests": 0,
        "allow_observation": False,
        "allow_assessment": False,
        "attachment_count": attachment_count,
    }


def _allowed_frame_types(mode: str) -> list[str]:
    return list(_turn_contract(mode, 0)["allowed_frame_types"])


def _frame_order(mode: str) -> str:
    return str(_turn_contract(mode, 0)["frame_order"])


def _leading_frame_type(mode: str) -> str:
    leading = {
        "ROOM_OPENING": "ROOM_WELCOME",
        "MATERIAL_REVIEW": "MATERIAL_RECEIPT",
        "TEXT_FOLLOWUP": "TEXT_FOLLOWUP_REPLY",
    }.get(mode)
    if leading is None:
        raise GraphContractError("EVIDENCE_V2_TURN_MODE_INVALID")
    return leading


def _frozen_matrix(base: AssembledEvidenceContext) -> dict[str, Any]:
    dossier = base.working_set.case_intake_dossier
    matrix = dossier.get("case_fact_matrix") or dossier.get("fact_matrix")
    if isinstance(matrix, dict) and matrix.get("schema_version"):
        if matrix.get("schema_version") == "case_fact_matrix.v2":
            return _model_matrix_projection(matrix)
        return matrix
    return {
        "schema_version": "case_fact_matrix.v2",
        "matrix_kind": "BILATERAL_FROZEN",
        "facts": list(base.working_set.allowed_fact_targets),
        "content_hash": canonical_sha256(base.working_set.allowed_fact_targets),
    }


def _model_matrix_projection(matrix: dict[str, Any]) -> dict[str, Any]:
    """Keep frozen business semantics while omitting server-only audit material."""

    claims = matrix["claims"]
    fact_rows = matrix["fact_rows"]
    relationships = matrix.get("fact_relationships", [])
    return {
        "schema_version": "evidence_case_matrix_context.v2",
        "source_schema_version": matrix["schema_version"],
        "matrix_version": matrix["matrix_version"],
        "matrix_kind": matrix["matrix_kind"],
        "party_map": copy.deepcopy(matrix["party_map"]),
        "case_overview": copy.deepcopy(matrix["case_overview"]),
        "claims": {
            name: (
                {
                    key: copy.deepcopy(value)
                    for key, value in claim.items()
                    if key != "source_refs"
                }
                if isinstance(claim, dict)
                else claim
            )
            for name, claim in claims.items()
        },
        "fact_rows": [_model_fact_row(row) for row in fact_rows],
        "fact_relationships": [
            {
                key: copy.deepcopy(value)
                for key, value in relationship.items()
                if key != "source_refs"
            }
            for relationship in relationships
        ],
    }


def _model_fact_row(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "fact_id": row["fact_id"],
        "category": row["category"],
        "fact_target": row["fact_target"],
        "materiality": row["materiality"],
        "introduced_stage": row["origin"]["introduced_stage"],
        "positions": {
            role: {
                key: copy.deepcopy(value)
                for key, value in row["positions"][role].items()
                if key != "source_refs"
            }
            for role in ("USER", "MERCHANT")
        },
        "party_alignment": copy.deepcopy(row["party_alignment"]),
        "requires_resolution": row["requires_resolution"],
        "truth_status": row["truth_status"],
        "evidence_coverage_status": row["evidence_coverage_status"],
    }


def _matrix_revision(matrix: dict[str, Any]) -> int:
    value = matrix.get("matrix_version", matrix.get("revision", 0))
    return int(value) if isinstance(value, int) and value >= 0 else 0


def _evidence_state_revision(base: AssembledEvidenceContext) -> int:
    value = base.working_set.evidence_matrix_snapshot.get("version", 0)
    return int(value) if isinstance(value, int) and value >= 0 else 0


def _current_batch(envelope: Any, attachment_refs: Any) -> list[dict[str, Any]]:
    by_id = {item.evidence_id: item for item in envelope.visible_evidence}
    result: list[dict[str, Any]] = []
    for evidence_id in attachment_refs:
        item = by_id.get(evidence_id)
        if item is None:
            continue
        metadata = item.metadata if isinstance(item.metadata, dict) else {}
        result.append(
            {
                "evidence_id": item.evidence_id,
                "evidence_type": item.evidence_type,
                "source_type": item.source_type,
                "content_type": item.content_type,
                "file_size": item.file_size,
                "submitted_by_role": item.submitted_by_role,
                "parse_status": item.parse_status,
                "file_hash_present": bool(item.file_hash),
                "claimed_fact": metadata.get("claimed_fact"),
                "truth_attested": metadata.get("truth_attested"),
                "attestation_scope": list(metadata.get("attestation_scope") or []),
            }
        )
    return result[:MAX_CURRENT_BATCH_ITEMS]


def _source_unit_catalog(envelope: Any, attachment_refs: Any) -> tuple[dict[str, Any], ...]:
    authorities = {item.evidence_id: item for item in envelope.evidence_content_authorities}
    units: list[dict[str, Any]] = []
    for evidence_id in attachment_refs:
        authority = authorities.get(evidence_id)
        if authority is None or authority.status != "SUCCEEDED" or not authority.parsed_text:
            continue
        text = _normalize_source_text(authority.parsed_text)
        paragraphs = [part for part in _PARAGRAPH_SPLIT.split(text) if part]
        if not paragraphs:
            paragraphs = [text]
        byte_cursor = 0
        for part_index, paragraph in enumerate(paragraphs, start=1):
            if len(units) >= MAX_SOURCE_UNITS:
                return tuple(units)
            content = paragraph[:MAX_SOURCE_UNIT_CHARS]
            start_byte = len(text[:byte_cursor].encode("utf-8"))
            end_byte = start_byte + len(content.encode("utf-8"))
            identity = {
                "evidence_id": evidence_id,
                "parsed_content_sha256": authority.parsed_content_sha256,
                "part_index": part_index,
                "start_byte": start_byte,
                "end_byte": end_byte,
                "segmenter_version": "evidence-source-segmenter.v2",
                "normalization_version": "unicode-newline-normalization.v1",
            }
            units.append(
                {
                    "source_unit_id": "ESRC_" + canonical_sha256(identity)[:24].upper(),
                    "evidence_id": evidence_id,
                    "basis": "PARSED_TEXT",
                    "content": content,
                    "authority": {
                        "parsed_content_sha256": authority.parsed_content_sha256,
                        "start_byte": start_byte,
                        "end_byte": end_byte,
                        "coverage": "FULL" if len(content) == len(paragraph) else "PARTIAL",
                    },
                }
            )
            byte_cursor += len(paragraph) + 2
    return tuple(units)


def _visual_source_unit_catalog(
    asset_manifest: dict[str, Any] | None,
    attachment_refs: tuple[str, ...],
) -> tuple[dict[str, Any], ...]:
    if not asset_manifest:
        return ()
    manifest_items = asset_manifest.get("items")
    if not isinstance(manifest_items, list):
        raise GraphContractError("EVIDENCE_V2_ASSET_MANIFEST_INVALID")
    attachment_scope = set(attachment_refs)
    units: list[dict[str, Any]] = []
    seen_evidence_ids: set[str] = set()
    for descriptor in manifest_items:
        if not isinstance(descriptor, dict):
            raise GraphContractError("EVIDENCE_V2_ASSET_MANIFEST_INVALID")
        if descriptor.get("visual_input_status") != "LOADED":
            continue
        evidence_id = descriptor.get("evidence_id")
        actual_sha256 = descriptor.get("actual_sha256")
        modalities = descriptor.get("inspected_modalities") or []
        content_type = descriptor.get("content_type")
        if (
            not isinstance(evidence_id, str)
            or evidence_id not in attachment_scope
            or evidence_id in seen_evidence_ids
            or not isinstance(actual_sha256, str)
            or _SHA256_HEX.fullmatch(actual_sha256) is None
            or not isinstance(content_type, str)
            or "IMAGE_PIXELS" not in modalities
        ):
            raise GraphContractError("EVIDENCE_V2_ASSET_MANIFEST_INVALID")
        seen_evidence_ids.add(evidence_id)
        identity = {
            "evidence_id": evidence_id,
            "content_sha256": actual_sha256,
            "content_type": content_type,
            "basis": "IMAGE_PIXELS",
            "source_projection_version": "evidence-image-source.v1",
        }
        units.append(
            {
                "source_unit_id": "ESRC_" + canonical_sha256(identity)[:24].upper(),
                "evidence_id": evidence_id,
                "basis": "IMAGE_PIXELS",
                "content": (
                    "该来源单元对应已通过授权、格式与哈希校验并实际载入模型的"
                    "完整图片像素；只能依据画面可见内容形成观察。"
                ),
                "authority": {
                    "content_sha256": actual_sha256,
                    "content_type": content_type,
                    "coverage": "FULL",
                    "source_projection_version": "evidence-image-source.v1",
                },
            }
        )
    return tuple(units)


def _normalize_source_text(value: str) -> str:
    return value.replace("\r\n", "\n").replace("\r", "\n")


def _source_authority_hash(envelope: Any, attachment_refs: Any) -> str:
    authorities = [
        item.model_dump(mode="json")
        for item in envelope.evidence_content_authorities
        if item.evidence_id in attachment_refs
    ]
    return canonical_sha256({"attachment_refs": list(attachment_refs), "authorities": authorities})


def _accepted_assessments(base: AssembledEvidenceContext) -> list[dict[str, Any]]:
    value = base.working_set.evidence_matrix_snapshot.get("assessments", [])
    return list(value) if isinstance(value, list) else []


def _remaining_requirements(base: AssembledEvidenceContext) -> dict[str, Any]:
    facts = list(base.working_set.allowed_fact_targets)
    covered = {
        str(item.get("fact_id"))
        for item in base.working_set.evidence_matrix_snapshot.get("links", [])
        if isinstance(item, dict)
    }
    return {
        "uncovered_fact_ids": [item["fact_id"] for item in facts if item["fact_id"] not in covered],
        "evidence_gap_plan": base.working_set.evidence_matrix_snapshot.get("gap_plan", []),
    }


def _private_memory(base: AssembledEvidenceContext) -> dict[str, Any]:
    memory = dict(base.memory_frame)
    # The frozen matrix and full evidence bodies have dedicated sections.  Do not
    # duplicate them in the actor memory window.
    for key in ("evidence_matrix_snapshot", "fact_matrix_patch", "internal_handoff"):
        memory.pop(key, None)
    return memory


__all__ = [
    "AssembledEvidenceRoomContextV2",
    "assemble_evidence_room_context_v2",
    "finalize_evidence_room_sources_v2",
]
