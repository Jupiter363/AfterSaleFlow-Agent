"""Evidence-clerk context assembly at the Python Harness boundary."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from pydantic import BaseModel, ConfigDict, Field

from app.harness.invocation_context import AgentInvocationContext
from app.harness.memory import MemeoMemoryAssembler
from app.schemas import (
    EvidenceContextEnvelopeV1,
    EvidenceTurnEvidenceItem,
    EvidenceTurnRequest,
)


MAX_PROMPT_EVIDENCE_ITEMS = 20
MAX_EVIDENCE_PREVIEW_CHARS = 3_000
MAX_MODEL_TEXT_CHARS = 20_000
MAX_CASE_SUMMARY_CHARS = 4_000


class EvidenceTurnWorkingSet(BaseModel):
    """Normalized material used by deterministic evidence-clerk guardrails."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    case_id: str
    room_type: str
    turn_source: str
    actor_role: str
    actor_id: str
    current_event: dict[str, Any]
    case_intake_dossier: dict[str, Any]
    available_evidence: tuple[EvidenceTurnEvidenceItem, ...] = Field(default_factory=tuple)


@dataclass(frozen=True)
class AssembledEvidenceContext:
    """The only model-facing view of one trusted evidence turn envelope."""

    working_set: EvidenceTurnWorkingSet
    context_sources: dict[str, Any]
    memory_frame: dict[str, Any]
    case_data: dict[str, Any]
    agent_context: AgentInvocationContext
    raw_envelope: EvidenceContextEnvelopeV1


class EvidenceContextAssembler:
    """Convert a trusted Java envelope into bounded Harness context sections.

    Java owns authorization, visibility filtering and durable snapshots. This
    assembler owns model-facing naming, deterministic fallbacks, memory
    construction and prompt context shape. It does not assess relevance or
    decide whether evidence is admissible.
    """

    def assemble(self, request: EvidenceTurnRequest) -> AssembledEvidenceContext:
        envelope = request.context_envelope
        case_snapshot = envelope.case_snapshot
        current_event = envelope.current_event
        room_policy = envelope.room_policy
        turn_source = current_event.event_type
        canonical_dossier = _canonical_case_dossier(envelope)
        current_turn = _current_turn(envelope, turn_source=turn_source)
        visible_evidence = _actor_visible_evidence(envelope)
        recent_turns = [
            _memory_turn(turn)
            for turn in envelope.private_conversation.recent_turns
        ]
        memory_frame = MemeoMemoryAssembler().assemble(
            recent_turns,
            expected_agent_session_id=envelope.private_conversation.agent_session_id,
            expected_conversation_scope=envelope.private_conversation.conversation_scope,
            strict_scope=True,
        ).model_dump(mode="json")
        actor = envelope.actor_snapshot
        working_evidence = tuple(
            _working_evidence(item) for item in envelope.visible_evidence
        )
        working_set = EvidenceTurnWorkingSet(
            case_id=case_snapshot.case_id,
            room_type=room_policy.room_type,
            turn_source=turn_source,
            actor_role=actor.actor_role,
            actor_id=actor.actor_id,
            current_event=current_turn,
            case_intake_dossier=canonical_dossier,
            available_evidence=working_evidence,
        )
        context_sources = {
            "current_turn": current_turn,
            "case_identity": {
                "case_id": case_snapshot.case_id,
                "case_version": case_snapshot.case_version,
                "room_type": room_policy.room_type,
                "actor_role": actor.actor_role,
                "initiator_role": actor.initiator_role,
                "context_captured_at": envelope.captured_at,
            },
            "canonical_case_dossier": canonical_dossier,
            "actor_private_memory": str(memory_frame.get("prompt_memory") or ""),
            "compressed_summary": str(
                memory_frame.get("compressed_summary") or ""
            ),
            "actor_visible_evidence": visible_evidence,
            "room_deadline": room_policy.model_dump(mode="json"),
        }
        case_data = {
            "case_id": case_snapshot.case_id,
            "case_version": case_snapshot.case_version,
            "room_type": room_policy.room_type,
            "turn_source": turn_source,
            "event_id": envelope.current_event.event_id,
            "actor_role": actor.actor_role,
            "agent_key": request.agent_context.agent_key,
            "prompt_profile_id": actor.prompt_profile_id,
        }
        return AssembledEvidenceContext(
            working_set=working_set,
            context_sources=context_sources,
            memory_frame=memory_frame,
            case_data=case_data,
            agent_context=request.agent_context,
            raw_envelope=envelope,
        )


def _canonical_case_dossier(
    envelope: EvidenceContextEnvelopeV1,
) -> dict[str, Any]:
    snapshot = envelope.case_snapshot
    dossier_snapshot = envelope.intake_dossier_snapshot
    dossier = dict(dossier_snapshot.payload) if dossier_snapshot is not None else {}
    case_story = dossier.get("case_story")
    if isinstance(case_story, dict):
        case_story = dict(case_story)
        summary = case_story.get("one_sentence_summary")
        if isinstance(summary, str):
            case_story["one_sentence_summary"] = summary[:MAX_CASE_SUMMARY_CHARS]
            case_story["summary_truncated"] = len(summary) > MAX_CASE_SUMMARY_CHARS
            case_story["summary_char_count"] = len(summary)
        dossier["case_story"] = case_story
    else:
        dossier["case_story"] = {
            "title": snapshot.title,
            "one_sentence_summary": snapshot.description[:MAX_CASE_SUMMARY_CHARS],
            "summary_truncated": len(snapshot.description) > MAX_CASE_SUMMARY_CHARS,
            "summary_char_count": len(snapshot.description),
        }
    dossier.setdefault(
        "dispute_focus",
        {
            "core_issue": snapshot.dispute_type or snapshot.case_type,
            "facts_to_verify": [],
        },
    )
    snapshot_view = snapshot.model_dump(mode="json")
    snapshot_view["description"] = snapshot.description[:MAX_MODEL_TEXT_CHARS]
    snapshot_view["description_truncated"] = (
        len(snapshot.description) > MAX_MODEL_TEXT_CHARS
    )
    snapshot_view["description_char_count"] = len(snapshot.description)
    dossier["case_snapshot"] = snapshot_view
    dossier["intake_dossier_provenance"] = {
        "dossier_id": dossier_snapshot.dossier_id if dossier_snapshot else None,
        "schema_version": (
            dossier_snapshot.schema_version if dossier_snapshot else None
        ),
        "version": dossier_snapshot.dossier_version if dossier_snapshot else None,
        "source_turn_no": (
            dossier_snapshot.source_turn_no if dossier_snapshot else None
        ),
        "quality_score": (
            dossier_snapshot.quality_score if dossier_snapshot else None
        ),
        "ready_for_next_step": (
            dossier_snapshot.ready_for_next_step if dossier_snapshot else False
        ),
        "admission_recommendation": (
            dossier_snapshot.admission_recommendation if dossier_snapshot else None
        ),
        "updated_at": dossier_snapshot.updated_at if dossier_snapshot else None,
        "available": dossier_snapshot is not None,
    }
    return dossier


def _current_turn(
    envelope: EvidenceContextEnvelopeV1,
    *,
    turn_source: str,
) -> dict[str, Any]:
    event = envelope.current_event
    raw_text = event.text or ""
    return {
        "turn_source": turn_source,
        "event_id": event.event_id,
        "event_type": event.event_type,
        "message_id": event.event_id,
        "message_type": event.message_type,
        "role": event.actor_role,
        "actor_id": event.actor_id,
        "text": raw_text[:MAX_MODEL_TEXT_CHARS],
        "text_truncated": len(raw_text) > MAX_MODEL_TEXT_CHARS,
        "text_char_count": len(raw_text),
        "attachment_refs": list(event.attachment_refs),
        "turn_no": event.turn_no,
        "occurred_at": event.occurred_at,
    }


def _memory_turn(turn: Any) -> dict[str, Any]:
    value = turn.model_dump(mode="json")
    for field in ("answer_content", "agent_response"):
        text = value.get(field)
        if isinstance(text, str):
            value[field] = text[:MAX_MODEL_TEXT_CHARS]
    return value


def _visible_evidence(item: Any) -> dict[str, Any]:
    raw_preview, parse_notice = _content_preview(
        item.parsed_text,
        parse_status=item.parse_status,
    )
    content_preview = raw_preview[:MAX_EVIDENCE_PREVIEW_CHARS]
    return {
        "evidence_id": item.evidence_id,
        "evidence_type": item.evidence_type,
        "source_type": item.source_type,
        "submitted_by_role": item.submitted_by_role,
        "original_filename": item.original_filename,
        "content_type": item.content_type,
        "file_size": item.file_size,
        "has_file_hash": bool(item.file_hash),
        "content_preview": content_preview,
        "preview_truncated": len(raw_preview) > MAX_EVIDENCE_PREVIEW_CHARS,
        "content_char_count": len(item.parsed_text or ""),
        "parse_notice": parse_notice,
        "parse_status": item.parse_status,
        "visibility": item.visibility,
        "desensitized": item.desensitized,
        "occurred_at": item.occurred_at,
        "submitted_at": item.submitted_at,
        "submission_status": item.submission_status,
    }


def _actor_visible_evidence(envelope: EvidenceContextEnvelopeV1) -> dict[str, Any]:
    items_by_id = {item.evidence_id: item for item in envelope.visible_evidence}
    selected: list[Any] = []
    selected_ids: set[str] = set()
    for evidence_id in envelope.current_event.attachment_refs:
        item = items_by_id[evidence_id]
        if evidence_id not in selected_ids:
            selected.append(item)
            selected_ids.add(evidence_id)
    remaining = [
        item
        for item in envelope.visible_evidence
        if item.evidence_id not in selected_ids
    ]
    remaining.sort(
        key=lambda item: (
            item.submitted_at or item.created_at,
            item.evidence_id,
        ),
        reverse=True,
    )
    selected.extend(remaining)
    included = selected[:MAX_PROMPT_EVIDENCE_ITEMS]
    return {
        "source_count": len(envelope.visible_evidence),
        "included_count": len(included),
        "truncated": len(included) < len(envelope.visible_evidence),
        "selection_policy": "CURRENT_EVENT_ATTACHMENTS_THEN_MOST_RECENT_V1",
        "items": [_visible_evidence(item) for item in included],
    }


def _working_evidence(item: Any) -> EvidenceTurnEvidenceItem:
    raw_preview, parse_notice = _content_preview(
        item.parsed_text,
        parse_status=item.parse_status,
    )
    return EvidenceTurnEvidenceItem(
        evidence_id=item.evidence_id,
        evidence_type=item.evidence_type,
        source_type=item.source_type,
        content=raw_preview[:MAX_EVIDENCE_PREVIEW_CHARS],
        parsed_text=None,
        occurred_at=item.occurred_at,
        submitted_by_role=item.submitted_by_role,
        visibility=item.visibility,
        content_url=item.content_url,
        parse_status=item.parse_status,
        original_filename=item.original_filename,
        parser_warning=parse_notice,
        redacted=item.desensitized,
    )


def _content_preview(value: str | None, *, parse_status: str) -> tuple[str, str | None]:
    if value is not None and value.strip():
        return value, None
    normalized_status = parse_status.strip().upper()
    if normalized_status in {"PENDING", "PROCESSING", "PARSING"}:
        notice = "证据内容正在解析，当前仅可核对文件元数据。"
        return notice, notice
    if normalized_status in {"FAILED", "ERROR", "PARSE_FAILED"}:
        notice = "证据内容解析失败，当前仅可核对文件元数据。"
        return notice, notice
    notice = "未提供可解析文本，当前仅可核对文件元数据。"
    return notice, notice
