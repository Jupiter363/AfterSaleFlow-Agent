"""Deterministic governance for Intake room utterances."""

from __future__ import annotations

from typing import Any


def phase_safe_room_utterance(
    room_utterance: str,
    snapshot: dict[str, Any],
) -> str:
    """Keep first-readiness output in substantive Q&A rather than submit/remark UX."""

    handoff = snapshot.get("handoff_notes")
    status = (
        str(handoff.get("remark_status") or "")
        if isinstance(handoff, dict)
        else ""
    )
    if status != "READY_PENDING_REMARK_INVITE":
        return room_utterance

    process_markers = (
        "备注",
        "提交",
        "下一步",
        "证据书记官",
        "handoff",
        "submit",
        "next step",
    )
    missing = snapshot.get("missing_information")
    questions = missing.get("next_questions") if isinstance(missing, dict) else None
    question = next(
        (
            str(item).strip()
            for item in questions
            if isinstance(item, str) and item.strip()
        ),
        "请继续补充本案仍需核实的具体事实或经过？",
    ) if isinstance(questions, list) else "请继续补充本案仍需核实的具体事实或经过？"
    if any(marker in question.casefold() for marker in process_markers):
        question = "请继续补充本案仍需核实的具体事实或经过"
    cut_positions = [
        position
        for marker in ("？", "?")
        if (position := question.find(marker)) >= 0
    ]
    if cut_positions:
        question = question[: min(cut_positions)]
    question = question.strip().rstrip("。！？?!")
    if not question:
        question = "请继续补充本案仍需核实的具体事实或经过"
    return f"{question}？"
