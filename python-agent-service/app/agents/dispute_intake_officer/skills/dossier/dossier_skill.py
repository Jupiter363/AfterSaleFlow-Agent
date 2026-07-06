from __future__ import annotations

import copy
import re
from dataclasses import dataclass
from typing import Any

from app.schemas import IntakeTurnRequest


ORDER_REFERENCE_RE = re.compile(r"\b(?:ORDER|ORD|订单)[-_]?[A-Za-z0-9]{3,40}\b", re.IGNORECASE)
AFTER_SALES_REFERENCE_RE = re.compile(
    r"\b(?:AS|AFTERSALE|售后)[-_]?[A-Za-z0-9]{3,40}\b",
    re.IGNORECASE,
)
LOGISTICS_REFERENCE_RE = re.compile(
    r"\b(?:SF|EMS|JD|JT|YTO|ZTO|STO|YD|YZ|DBL|HTKY|LOG|TRACK)[-_]?[A-Za-z0-9]{5,40}\b",
    re.IGNORECASE,
)

FIELD_DISPLAY_LABELS = {
    "ORDER_REFERENCE": "订单号",
    "AFTER_SALES_REFERENCE": "售后单号",
    "LOGISTICS_REFERENCE": "物流单号",
    "order_reference_confirmation": "订单号核对",
    "after_sales_reference_confirmation": "售后单号核对",
    "logistics_reference_confirmation": "物流单号核对",
    "product_issue_details": "故障细节",
    "product_quality_details": "商品质量细节",
    "user_statement": "用户原始陈述",
    "merchant_statement": "商家原始陈述",
    "merchant_requested_outcome": "商家期望处理方案",
    "requested_outcome": "期望处理结果",
    "evidence_attachments": "证据材料",
    "buyer_evidence": "买家证据材料",
    "user_evidence": "用户证据材料",
    "merchant_evidence": "商家证据材料",
    "merchant_outbound_photos": "商家发货前照片",
    "merchant_outbound_records": "商家发货前记录",
    "merchant_quality_inspection": "商家质检记录",
    "buyer_photos": "买家照片",
    "user_photos": "用户照片",
    "unboxing_video": "开箱视频",
    "opening_video": "开箱视频",
    "delivery_record": "物流派送记录",
    "proof_of_delivery": "签收凭证",
}


@dataclass(frozen=True)
class DossierRenderResult:
    dossier_patch: dict[str, Any]
    scroll_snapshot: dict[str, Any]
    canvas_operations: list[dict[str, Any]]
    admission_recommendation: str
    missing_fields: list[str]
    confidence: float


class CaseDetailDossierSkill:
    """Render the right-side case-detail board for the intake room.

    The LLM may draft the board and score it, but this skill performs the
    deterministic merge and readiness gate. It prevents invented references
    from opening the next room.
    """

    schema_version = "intake_case_detail.v1"
    readiness_threshold = 80

    def render(
        self,
        *,
        request: IntakeTurnRequest,
        room_utterance: str,
        llm_case_detail: dict[str, Any] | None,
        llm_dossier_patch: dict[str, Any] | None,
        llm_scroll_snapshot: dict[str, Any] | None,
        llm_canvas_operations: list[dict[str, Any]],
        llm_admission_recommendation: str,
        llm_missing_fields: list[str],
        llm_confidence: float,
    ) -> DossierRenderResult:
        if llm_case_detail is None and llm_scroll_snapshot:
            return self._legacy_passthrough(
                llm_dossier_patch=llm_dossier_patch,
                llm_scroll_snapshot=llm_scroll_snapshot,
                llm_canvas_operations=llm_canvas_operations,
                llm_admission_recommendation=llm_admission_recommendation,
                llm_missing_fields=llm_missing_fields,
                llm_confidence=llm_confidence,
            )

        previous = request.latest_scroll_snapshot or {}
        previous_waiting_for_remark = (
            _handoff_remark_status(previous) == "WAITING_FOR_REMARK"
            if isinstance(previous, dict)
            else False
        )
        detail = self._default_case_detail(request)
        detail = _deep_merge(detail, previous if _is_case_detail(previous) else {})
        detail = _deep_merge(detail, llm_case_detail or {})
        detail["schema_version"] = self.schema_version

        trusted_refs = self._trusted_references(request)
        detail["references"] = {
            "order_reference": trusted_refs.get("order_reference") or "",
            "after_sales_reference": trusted_refs.get("after_sales_reference") or "",
            "logistics_reference": trusted_refs.get("logistics_reference") or "",
        }

        llm_missing_from_detail = _list_values(
            _ensure_dict(detail, "missing_information").get("blocking_gaps")
        )
        missing = self._hard_missing_fields(trusted_refs)
        missing.extend(field for field in llm_missing_fields if field not in missing)
        missing.extend(field for field in llm_missing_from_detail if field not in missing)
        score = _clamp_score(
            detail.get("intake_quality", {}).get("score")
            if isinstance(detail.get("intake_quality"), dict)
            else None
        )
        quality = _ensure_dict(detail, "intake_quality")
        quality["score"] = score
        quality["threshold"] = self.readiness_threshold
        quality["ready_for_next_step"] = bool(score >= self.readiness_threshold and not missing)
        if missing:
            quality["improvement_reason"] = "仍缺少可信的" + "、".join(
                _human_missing_fields(missing)
            )
        else:
            quality["improvement_reason"] = _humanize_internal_tokens(
                str(quality.get("improvement_reason") or "")
            )

        missing_info = _ensure_dict(detail, "missing_information")
        missing_info["blocking_gaps"] = _human_missing_fields(missing)
        if quality["ready_for_next_step"]:
            missing_info.setdefault("next_questions", [])
            _ensure_handoff_notes(detail)
        elif not missing_info.get("next_questions"):
            missing_info["next_questions"] = [_question_for_missing(missing)]
        _record_handoff_remark_if_needed(
            detail,
            request,
            previous_waiting_for_remark=previous_waiting_for_remark,
        )

        admission = _ensure_dict(detail, "admission")
        if quality["ready_for_next_step"]:
            admission["recommendation"] = "ACCEPTED"
        elif llm_admission_recommendation == "NOT_ADMISSIBLE":
            admission["recommendation"] = "NOT_ADMISSIBLE"
        else:
            admission["recommendation"] = "NEED_MORE_INFO"
        admission["confidence"] = _clamp_confidence(
            admission.get("confidence", llm_confidence)
        )

        operations = [
            {
                "type": "UPSERT_CASE_DETAIL",
                "target_key": "case_detail",
                "animation": "ink-write",
                "value": detail,
            },
            {
                "type": "SET_QUALITY_SCORE",
                "target_key": "intake_quality",
                "animation": "score-rise",
                "value": score,
            },
        ]
        return DossierRenderResult(
            dossier_patch={
                "case_detail": detail,
                "room_utterance_source": room_utterance,
            },
            scroll_snapshot=detail,
            canvas_operations=operations,
            admission_recommendation=str(admission["recommendation"]),
            missing_fields=missing,
            confidence=float(admission["confidence"]),
        )

    def _legacy_passthrough(
        self,
        *,
        llm_dossier_patch: dict[str, Any] | None,
        llm_scroll_snapshot: dict[str, Any],
        llm_canvas_operations: list[dict[str, Any]],
        llm_admission_recommendation: str,
        llm_missing_fields: list[str],
        llm_confidence: float,
    ) -> DossierRenderResult:
        return DossierRenderResult(
            dossier_patch=llm_dossier_patch or {},
            scroll_snapshot=llm_scroll_snapshot,
            canvas_operations=llm_canvas_operations,
            admission_recommendation=llm_admission_recommendation,
            missing_fields=llm_missing_fields,
            confidence=llm_confidence,
        )

    def _trusted_references(self, request: IntakeTurnRequest) -> dict[str, str]:
        current_text = (request.current_user_message.text if request.current_user_message else "")
        raw_text = request.lobby_seed.raw_text or ""
        previous = request.latest_scroll_snapshot or {}
        previous_refs = previous.get("references") if isinstance(previous, dict) else {}
        if not isinstance(previous_refs, dict):
            previous_refs = {}
        source_text = f"{raw_text}\n{current_text}"
        return {
            "order_reference": (
                request.lobby_seed.order_reference
                or str(previous_refs.get("order_reference") or "")
                or _first_match(ORDER_REFERENCE_RE, source_text)
            ),
            "after_sales_reference": (
                request.lobby_seed.after_sales_reference
                or str(previous_refs.get("after_sales_reference") or "")
                or _first_match(AFTER_SALES_REFERENCE_RE, source_text)
            ),
            "logistics_reference": (
                request.lobby_seed.logistics_reference
                or str(previous_refs.get("logistics_reference") or "")
                or _first_match(LOGISTICS_REFERENCE_RE, source_text)
            ),
        }

    @staticmethod
    def _hard_missing_fields(trusted_refs: dict[str, str]) -> list[str]:
        missing: list[str] = []
        if not trusted_refs.get("order_reference"):
            missing.append("ORDER_REFERENCE")
        if not trusted_refs.get("logistics_reference"):
            missing.append("LOGISTICS_REFERENCE")
        return missing

    def _default_case_detail(self, request: IntakeTurnRequest) -> dict[str, Any]:
        current_text = request.current_user_message.text if request.current_user_message else ""
        source_text = current_text or request.lobby_seed.raw_text
        return {
            "schema_version": self.schema_version,
            "case_story": {
                "title": "待梳理履约争议",
                "one_sentence_summary": source_text,
                "event_timeline": [
                    {
                        "time_hint": "接待室当前轮次",
                        "event": source_text,
                        "source": request.turn_source,
                    }
                ],
            },
            "references": {
                "order_reference": "",
                "after_sales_reference": "",
                "logistics_reference": "",
            },
            "party_positions": {
                "user_claim": source_text if request.lobby_seed.initiator_role == "USER" else "",
                "merchant_claim": source_text if request.lobby_seed.initiator_role == "MERCHANT" else "",
                "platform_observation": "",
            },
            "dispute_focus": {
                "core_issue": "UNKNOWN",
                "key_conflicts": [],
                "facts_to_verify": [],
            },
            "requested_resolution": {
                "requested_outcome": request.lobby_seed.requested_outcome_hint or "UNKNOWN",
                "expected_resolution_text": "",
            },
            "risk_assessment": {
                "case_grade": "LOW",
                "risk_signals": [],
                "reasoning": "",
            },
            "missing_information": {
                "blocking_gaps": [],
                "nice_to_have_gaps": [],
                "next_questions": [],
            },
            "intake_quality": {
                "score": 0,
                "threshold": self.readiness_threshold,
                "ready_for_next_step": False,
                "score_breakdown": {
                    "references": 0,
                    "event_story": 0,
                    "party_positions": 0,
                    "requested_resolution": 0,
                    "risk_and_conflicts": 0,
                    "next_action_clarity": 0,
                },
                "improvement_reason": "等待接待官完成案件详情整理。",
            },
            "admission": {
                "recommendation": "NEED_MORE_INFO",
                "reasoning": "",
                "confidence": 0.0,
            },
            "handoff_notes": {
                "remark_status": "NOT_READY",
                "latest_remark": "",
                "remarks": [],
                "instruction": "案件详情达标后，接待官会询问是否有备注需要交接给证据书记官。",
            },
        }


def _deep_merge(base: dict[str, Any], patch: dict[str, Any]) -> dict[str, Any]:
    merged = copy.deepcopy(base)
    for key, value in patch.items():
        if isinstance(value, dict) and isinstance(merged.get(key), dict):
            merged[key] = _deep_merge(merged[key], value)
        elif value is not None:
            if value == "" and merged.get(key):
                continue
            merged[key] = copy.deepcopy(value)
    return merged


def _is_case_detail(value: dict[str, Any]) -> bool:
    return value.get("schema_version") == CaseDetailDossierSkill.schema_version


def _case_detail_ready(value: dict[str, Any]) -> bool:
    if not _is_case_detail(value):
        return False
    quality = value.get("intake_quality")
    return isinstance(quality, dict) and quality.get("ready_for_next_step") is True


def _handoff_remark_status(value: dict[str, Any]) -> str:
    notes = value.get("handoff_notes")
    if not isinstance(notes, dict):
        return ""
    return str(notes.get("remark_status") or "")


def _ensure_handoff_notes(detail: dict[str, Any]) -> dict[str, Any]:
    notes = _ensure_dict(detail, "handoff_notes")
    if not notes.get("remark_status") or notes.get("remark_status") == "NOT_READY":
        notes["remark_status"] = "WAITING_FOR_REMARK"
    notes.setdefault("latest_remark", "")
    remarks = notes.get("remarks")
    if not isinstance(remarks, list):
        notes["remarks"] = []
    notes.setdefault("instruction", "如有备注，将随案件详情提交给证据书记官。")
    return notes


def _record_handoff_remark_if_needed(
    detail: dict[str, Any],
    request: IntakeTurnRequest,
    *,
    previous_waiting_for_remark: bool,
) -> None:
    current = request.current_user_message
    if not previous_waiting_for_remark or current is None or not current.text.strip():
        return

    notes = _ensure_handoff_notes(detail)
    text = current.text.strip()
    if _is_no_extra_remark(text):
        notes["remark_status"] = "NO_EXTRA_REMARKS"
        notes["latest_remark"] = "无额外备注。"
        return

    notes["remark_status"] = "HAS_REMARKS"
    notes["latest_remark"] = text
    remarks = notes["remarks"]
    source_message_id = current.message_id
    if not any(
        isinstance(item, dict) and item.get("source_message_id") == source_message_id
        for item in remarks
    ):
        remarks.append(
            {
                "role": current.role,
                "text": text,
                "source_message_id": source_message_id,
                "turn_source": request.turn_source,
            }
        )


def _is_no_extra_remark(text: str) -> bool:
    normalized = re.sub(r"\s+", "", text or "").casefold()
    return normalized in {
        "没有",
        "无",
        "没有补充",
        "无补充",
        "没有备注",
        "无备注",
        "不用备注",
        "no",
        "nothingelse",
        "noadditionalnotes",
    }


def _ensure_dict(container: dict[str, Any], key: str) -> dict[str, Any]:
    value = container.get(key)
    if not isinstance(value, dict):
        value = {}
        container[key] = value
    return value


def _first_match(pattern: re.Pattern[str], text: str) -> str:
    match = pattern.search(text or "")
    return match.group(0).upper() if match else ""


def _clamp_score(value: Any) -> int:
    try:
        number = int(value)
    except (TypeError, ValueError):
        return 0
    return max(0, min(100, number))


def _clamp_confidence(value: Any) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return 0.0
    return max(0.0, min(1.0, number))


def _human_field_label(field: str) -> str:
    if field in FIELD_DISPLAY_LABELS:
        return FIELD_DISPLAY_LABELS[field]
    normalized = str(field or "").strip()
    if normalized in FIELD_DISPLAY_LABELS:
        return FIELD_DISPLAY_LABELS[normalized]
    lower = normalized.lower()
    if lower in FIELD_DISPLAY_LABELS:
        return FIELD_DISPLAY_LABELS[lower]
    if re.search(r"[A-Za-z_]{3,}", normalized):
        return "相关补充材料"
    return normalized or "相关补充材料"


def _human_missing_fields(missing: list[str]) -> list[str]:
    return [_human_field_label(field) for field in missing]


def _humanize_internal_tokens(text: str) -> str:
    output = text
    for token, label in sorted(
        FIELD_DISPLAY_LABELS.items(), key=lambda item: len(item[0]), reverse=True
    ):
        output = output.replace(token, label)
    return output


def _list_values(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, list):
        return [str(item) for item in value if str(item or "").strip()]
    if isinstance(value, str) and value.strip():
        return [value.strip()]
    return []


def _question_for_missing(missing: list[str]) -> str:
    questions = {
        "ORDER_REFERENCE": "请补充订单号或平台可识别的订单引用。",
        "LOGISTICS_REFERENCE": "请补充物流单号或平台可识别的物流引用。",
    }
    return " ".join(
        questions.get(field, f"请补充{_human_field_label(field)}。")
        for field in missing
    )
