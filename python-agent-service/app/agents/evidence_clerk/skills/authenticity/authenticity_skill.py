from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable

from app.harness.evidence_context_assembler import EvidenceTurnWorkingSet
from app.schemas import (
    EvidenceAuthenticityFlag,
    EvidenceTurnQuestion,
    EvidenceVerificationSuggestion,
)


@dataclass(frozen=True)
class EvidenceAuthenticityDraft:
    evidence_requests: list[EvidenceTurnQuestion]
    verification_suggestions: list[EvidenceVerificationSuggestion]
    authenticity_flags: list[EvidenceAuthenticityFlag]
    confidence: float


class EvidenceAuthenticitySkill:
    """Deterministic guardrail skill for evidence-only questions.

    The LLM is allowed to reason and phrase the clerk answer, but this skill
    keeps the room anchored on evidence provenance, readability, timestamps,
    integrity, and relevance. It never decides liability or remedy.
    """

    def draft(self, request: EvidenceTurnWorkingSet) -> EvidenceAuthenticityDraft:
        questions: list[EvidenceTurnQuestion] = []
        suggestions: list[EvidenceVerificationSuggestion] = []
        flags: list[EvidenceAuthenticityFlag] = []

        if not request.available_evidence:
            questions.append(
                EvidenceTurnQuestion(
                    question_id="QUESTION_EVIDENCE_SOURCE",
                    target_evidence_id=None,
                    question="请先上传或说明与案情直接相关的原始证据，并补充证据来源、形成时间和想证明的事实。",
                    reason="证据室只核验证据真伪、来源、完整性和关联性；当前还没有可核验材料。",
                )
            )
            return EvidenceAuthenticityDraft(
                evidence_requests=questions,
                verification_suggestions=[],
                authenticity_flags=[
                    EvidenceAuthenticityFlag(
                        evidence_id=None,
                        flag_type="NO_EVIDENCE_AVAILABLE",
                        description="当前轮次没有可核验的证据材料。",
                        severity="MEDIUM",
                    )
                ],
                confidence=0.35,
            )

        confidences: list[float] = []
        for item in request.available_evidence:
            evidence_text = " ".join(
                part
                for part in (
                    item.content,
                    item.parsed_text or "",
                    item.agent_summary or "",
                    item.parser_warning or "",
                )
                if part
            )
            weak_points = _weak_points(item.parser_warning, evidence_text)
            question = _question_for(
                item.evidence_id,
                weak_points,
                include_time_confirmation=item.source_type in {"USER", "MERCHANT"},
            )
            questions.append(question)

            confidence = _confidence(item.parser_warning, evidence_text)
            confidences.append(confidence)
            suggestions.append(
                EvidenceVerificationSuggestion(
                    evidence_id=item.evidence_id,
                    suggestion=(
                        "核对原始来源、形成时间、文件完整性、OCR/图片可读性，"
                        "并确认它与接待室案情中的争议事实是否直接相关。"
                    ),
                    confidence_score=confidence,
                )
            )
            flags.extend(_flags_for(item.evidence_id, weak_points))

        return EvidenceAuthenticityDraft(
            evidence_requests=questions[:10],
            verification_suggestions=suggestions[:100],
            authenticity_flags=flags[:20],
            confidence=round(sum(confidences) / max(len(confidences), 1), 2),
        )


def _weak_points(parser_warning: str | None, evidence_text: str) -> set[str]:
    normalized = evidence_text.casefold()
    points: set[str] = set()
    if parser_warning:
        points.add("OCR_READABILITY")
    if not any(token in normalized for token in ("时间", "date", "签收", "发货", "上传", "形成")):
        points.add("TIME_ANCHOR")
    if not any(token in normalized for token in ("原图", "原件", "来源", "source", "监控", "物流", "质检")):
        points.add("SOURCE_PROVENANCE")
    if not any(token in normalized for token in ("订单", "售后", "物流", "划痕", "破损", "签收", "退款")):
        points.add("CASE_RELEVANCE")
    return points


def _question_for(
    evidence_id: str,
    weak_points: set[str],
    *,
    include_time_confirmation: bool = False,
) -> EvidenceTurnQuestion:
    if not weak_points and not include_time_confirmation:
        return EvidenceTurnQuestion(
            question_id=f"QUESTION_{evidence_id}_CONFIRM_ORIGINAL",
            target_evidence_id=evidence_id,
            question="这份证据目前可进入初步核对。请确认是否能保留原始文件、来源路径和形成时间，方便后续复核。",
            reason="证据已经具备初步可读性，仍需锁定原始来源和完整性。",
        )
    needs = {
        "OCR_READABILITY": "OCR/图片可读性，以及更清晰的原图/原文件或可复制文本",
        "TIME_ANCHOR": "证据形成时间或截图/拍摄时间",
        "SOURCE_PROVENANCE": "原始来源、导出路径或平台/物流/质检记录来源",
        "CASE_RELEVANCE": "这份证据想证明的具体争议事实",
    }
    requested_points = set(weak_points)
    if include_time_confirmation:
        requested_points.add("TIME_ANCHOR")
    need_text = "、".join(needs[item] for item in sorted(requested_points))
    return EvidenceTurnQuestion(
        question_id=f"QUESTION_{evidence_id}_AUTHENTICITY",
        target_evidence_id=evidence_id,
        question=f"请围绕证据 {evidence_id} 补充：{need_text}。",
        reason="当前只核验证据真伪、完整性、可读性和与案情的关联性。",
    )


def _flags_for(
    evidence_id: str,
    weak_points: Iterable[str],
) -> list[EvidenceAuthenticityFlag]:
    labels = {
        "OCR_READABILITY": ("OCR_READABILITY", "OCR 或图片可读性不足，需要更清晰材料。", "MEDIUM"),
        "TIME_ANCHOR": ("TIME_ANCHOR_MISSING", "缺少明确形成时间，后续难以判断证据时序。", "MEDIUM"),
        "SOURCE_PROVENANCE": ("SOURCE_PROVENANCE_MISSING", "缺少原始来源或导出路径，真实性需要补强。", "MEDIUM"),
        "CASE_RELEVANCE": ("CASE_RELEVANCE_WEAK", "尚未说明该证据与核心争议事实的直接关系。", "LOW"),
    }
    return [
        EvidenceAuthenticityFlag(
            evidence_id=evidence_id,
            flag_type=labels[point][0],
            description=labels[point][1],
            severity=labels[point][2],  # type: ignore[arg-type]
        )
        for point in weak_points
        if point in labels
    ]


def _confidence(parser_warning: str | None, evidence_text: str) -> float:
    score = 0.72
    if parser_warning:
        score -= 0.16
    if len(evidence_text.strip()) < 30:
        score -= 0.12
    if any(token in evidence_text for token in ("原件", "原图", "监控", "质检", "物流")):
        score += 0.08
    return min(0.95, max(0.2, round(score, 2)))
