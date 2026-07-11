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

ORIGINAL_STATEMENT_SEPARATOR = "\n\n"
ORIGINAL_STATEMENT_MISSING = "外部系统未提供发起方原话"
ORIGINAL_STATEMENT_POLICY = "INITIATOR_INPUTS_V1"
SUBJECTIVE_RESPONDENT_SOURCE = "发起方单方陈述（主观）"
SUBJECTIVE_RESPONDENT_CONFIDENCE_NOTE = (
    "仅表示从发起方单方陈述中提取态度的明确度，不代表事实真实性。"
)
RESPONDENT_ATTITUDE_CODES = {
    "NOT_RESPONDED",
    "AGREE",
    "PARTIALLY_AGREE",
    "DISAGREE",
    "ALTERNATIVE_PROPOSED",
    "NEED_MORE_INFO",
    "PLATFORM_UNKNOWN",
}

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
        _enforce_original_statement(detail, request, previous)
        _enforce_respondent_attitude_source(
            detail,
            request.lobby_seed,
            previous,
            llm_case_detail,
        )

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
            "claim_resolution": _default_claim_resolution(
                request.lobby_seed,
                source_text,
            ),
            "respondent_attitude": _default_respondent_attitude(request.lobby_seed),
            "dispute_core_state": _default_dispute_core_state(
                request.lobby_seed,
                source_text,
            ),
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


CLAIM_RESOLUTION_LABELS = {
    "REFUND": "退款",
    "RETURN_REFUND": "退货退款",
    "RESHIP": "补发",
    "REPLACE_OR_REPAIR": "换货或维修",
    "REPLACEMENT": "换货或维修",
    "REPAIR": "换货或维修",
    "COMPENSATION": "赔付",
    "CANCEL_ORDER": "取消订单",
    "VERIFY_OR_EXPLAIN_ONLY": "核验或解释",
    "OTHER": "其他处理",
    "UNKNOWN": "待确认处理",
}


def _default_claim_resolution(lobby_seed: Any, source_text: str) -> dict[str, Any]:
    seed = getattr(lobby_seed, "claim_resolution_seed", None)
    initiator_role = (
        getattr(seed, "initiator_role", None)
        or _party_role_or_default(getattr(lobby_seed, "initiator_role", None))
    )
    requested_resolution = (
        getattr(seed, "requested_resolution", None)
        or getattr(lobby_seed, "requested_outcome_hint", None)
        or "UNKNOWN"
    )
    request_reason = getattr(seed, "request_reason", None) or source_text
    original_statement = _initial_original_statement(lobby_seed, source_text)
    return {
        "initiator_role": initiator_role,
        "requested_resolution": requested_resolution,
        "requested_amount": getattr(seed, "requested_amount", None),
        "requested_items": getattr(seed, "requested_items", None) or "",
        "request_reason": request_reason,
        "original_statement": original_statement,
        "normalized_statement": _normalized_claim_statement(
            initiator_role,
            requested_resolution,
            request_reason,
        ),
    }


def _enforce_original_statement(
    detail: dict[str, Any],
    request: IntakeTurnRequest,
    previous: dict[str, Any],
) -> None:
    """Pin the display statement to the unilateral participant's verbatim inputs.

    The LLM owns the normalized summary, but never the raw statement. A prior
    deterministic snapshot is the accumulator for long conversations; on a
    legacy snapshot, the seed and durable recent turns rebuild the statement.
    """

    claim = _ensure_dict(detail, "claim_resolution")
    transcript_statements = [
        message.text
        for message in request.initiator_statement_transcript
    ]
    if transcript_statements:
        claim["original_statement"] = ORIGINAL_STATEMENT_SEPARATOR.join(
            transcript_statements
        )
        claim["original_statement_provenance"] = {
            "policy": ORIGINAL_STATEMENT_POLICY,
            "last_message_id": request.initiator_statement_transcript[-1].message_id,
            "submission_count": len(transcript_statements),
            "separator": "BLANK_LINE",
            "source": "INITIATOR_STATEMENT_TRANSCRIPT",
        }
        return

    previous_claim = (
        previous.get("claim_resolution") if isinstance(previous, dict) else None
    )
    if not isinstance(previous_claim, dict):
        previous_claim = {}
    previous_provenance = previous_claim.get("original_statement_provenance")
    if not isinstance(previous_provenance, dict):
        previous_provenance = {}

    current = request.current_user_message
    current_message_id = current.message_id if current is not None else ""
    current_text = current.text if current is not None else ""
    prior_is_trusted = (
        previous_provenance.get("policy") == ORIGINAL_STATEMENT_POLICY
        and isinstance(previous_claim.get("original_statement"), str)
        and bool(previous_claim.get("original_statement"))
    )

    if prior_is_trusted:
        statements = [str(previous_claim["original_statement"])]
        last_message_id = str(previous_provenance.get("last_message_id") or "")
        if current_text and current_message_id != last_message_id:
            statements.append(current_text)
        submission_count = _non_negative_int(
            previous_provenance.get("submission_count")
        ) + (1 if len(statements) > 1 else 0)
    else:
        statements = [
            _initial_original_statement(
                request.lobby_seed,
                request.lobby_seed.raw_text,
            )
        ]
        recent_statements = _recent_participant_statements(request.recent_turns)
        statements.extend(recent_statements)
        if current_text and (not recent_statements or recent_statements[-1] != current_text):
            statements.append(current_text)
        submission_count = (
            (0 if statements[0] == ORIGINAL_STATEMENT_MISSING else 1)
            + len(statements)
            - 1
        )

    claim["original_statement"] = ORIGINAL_STATEMENT_SEPARATOR.join(statements)
    claim["original_statement_provenance"] = {
        "policy": ORIGINAL_STATEMENT_POLICY,
        "last_message_id": current_message_id,
        "submission_count": submission_count,
        "separator": "BLANK_LINE",
        "source": "LEGACY_COMPATIBILITY",
    }


def _initial_original_statement(lobby_seed: Any, source_text: str) -> str:
    seed = getattr(lobby_seed, "claim_resolution_seed", None)
    if seed is not None:
        original_statement = getattr(seed, "original_statement", None)
        if isinstance(original_statement, str) and original_statement.strip():
            return original_statement
        return ORIGINAL_STATEMENT_MISSING
    return source_text


def _recent_participant_statements(recent_turns: list[dict[str, object]]) -> list[str]:
    statements: list[str] = []
    for turn in recent_turns:
        if not isinstance(turn, dict):
            continue
        content = turn.get("answer_content")
        if isinstance(content, str) and content.strip():
            statements.append(content)
    return statements


def _non_negative_int(value: Any) -> int:
    try:
        return max(0, int(value))
    except (TypeError, ValueError):
        return 0


def _enforce_respondent_attitude_source(
    detail: dict[str, Any],
    lobby_seed: Any,
    previous: dict[str, Any],
    llm_case_detail: dict[str, Any] | None,
) -> None:
    """Persist only attitudes reported by the initiator in this private room.

    A formal response belongs to a shared or respondent-authored room, not the
    intake room. Legacy snapshots and external seeds with formal provenance are
    therefore discarded instead of being relabelled as a subjective report.
    """

    llm_attitude = _nested_attitude(llm_case_detail)
    previous_attitude = (
        previous.get("respondent_attitude") if isinstance(previous, dict) else None
    )
    if not isinstance(previous_attitude, dict):
        previous_attitude = {}

    candidate = _reported_attitude(llm_attitude)
    if candidate is None:
        candidate = _subjective_attitude(previous_attitude)
    if candidate is None:
        detail["respondent_attitude"] = _default_respondent_attitude(lobby_seed)
        return

    initiator_role = _party_role_or_default(getattr(lobby_seed, "initiator_role", None))
    detail["respondent_attitude"] = {
        "respondent_role": _opposite_party(initiator_role),
        "attitude": candidate["attitude"],
        "position": candidate["position"],
        "source": SUBJECTIVE_RESPONDENT_SOURCE,
        "confidence": _clamp_confidence(candidate.get("confidence", 0.5)),
        "confidence_note": SUBJECTIVE_RESPONDENT_CONFIDENCE_NOTE,
    }


def _nested_attitude(case_detail: dict[str, Any] | None) -> dict[str, Any]:
    if not isinstance(case_detail, dict):
        return {}
    attitude = case_detail.get("respondent_attitude")
    return attitude if isinstance(attitude, dict) else {}


def _reported_attitude(candidate: dict[str, Any]) -> dict[str, Any] | None:
    attitude_code = str(candidate.get("attitude") or "NOT_RESPONDED").upper()
    position = str(candidate.get("position") or "").strip()
    if (
        attitude_code not in RESPONDENT_ATTITUDE_CODES
        or attitude_code == "NOT_RESPONDED"
        or not position
    ):
        return None
    return {
        "attitude": attitude_code,
        "position": position,
        "confidence": candidate.get("confidence", 0.5),
    }


def _subjective_attitude(candidate: dict[str, Any]) -> dict[str, Any] | None:
    if str(candidate.get("source") or "").strip() != SUBJECTIVE_RESPONDENT_SOURCE:
        return None
    return _reported_attitude(candidate)


def _default_respondent_attitude(lobby_seed: Any) -> dict[str, Any]:
    seed = getattr(lobby_seed, "respondent_attitude_seed", None)
    initiator_role = _party_role_or_default(getattr(lobby_seed, "initiator_role", None))
    respondent_role = _opposite_party(initiator_role)
    seed_values = (
        seed.model_dump(mode="python")
        if seed is not None and hasattr(seed, "model_dump")
        else {}
    )
    subjective_seed = _subjective_attitude(seed_values)
    if subjective_seed is not None:
        return {
            "respondent_role": respondent_role,
            "attitude": subjective_seed["attitude"],
            "position": subjective_seed["position"],
            "source": SUBJECTIVE_RESPONDENT_SOURCE,
            "confidence": _clamp_confidence(
                subjective_seed.get("confidence", 0.5)
            ),
            "confidence_note": SUBJECTIVE_RESPONDENT_CONFIDENCE_NOTE,
        }
    return {
        "respondent_role": respondent_role,
        "attitude": "NOT_RESPONDED",
        "position": f"{_role_label(respondent_role)}尚未在接待室表达态度。",
        "source": "尚未回应",
        "confidence": 0.5,
    }


def _default_dispute_core_state(lobby_seed: Any, source_text: str) -> dict[str, Any]:
    claim = _default_claim_resolution(lobby_seed, source_text)
    attitude = _default_respondent_attitude(lobby_seed)
    resolution_label = CLAIM_RESOLUTION_LABELS.get(
        str(claim["requested_resolution"] or "UNKNOWN").upper(),
        "相关处理",
    )
    initiator = _role_label(claim["initiator_role"])
    respondent = _role_label(attitude["respondent_role"])
    if attitude["attitude"] == "NOT_RESPONDED":
        conflict_type = "CLAIM_UNANSWERED"
        core_conflict = f"{initiator}请求{resolution_label}，但{respondent}态度尚待补充。"
    elif attitude["attitude"] == "DISAGREE":
        conflict_type = "CLAIM_REJECTED_WITH_FACT_DISPUTE"
        core_conflict = f"{initiator}请求{resolution_label}，但{respondent}不同意该诉求。"
    else:
        conflict_type = "CLAIM_WITH_EVIDENCE_GAP"
        core_conflict = f"{initiator}请求{resolution_label}，{respondent}回应状态仍需结合证据核验。"
    return {
        "core_conflict": core_conflict,
        "conflict_type": conflict_type,
        "facts_in_dispute": [],
        "next_verification_focus": _verification_focus_for_text(source_text),
    }


def _normalized_claim_statement(
    initiator_role: str,
    requested_resolution: str,
    request_reason: str,
) -> str:
    role = _role_label(initiator_role)
    resolution = CLAIM_RESOLUTION_LABELS.get(
        str(requested_resolution or "UNKNOWN").upper(),
        "相关处理",
    )
    statement = _third_person_text(request_reason, initiator_role)
    if resolution in statement:
        return f"{role}称{statement}"
    return f"{role}称{statement}，并请求{resolution}。"


def _third_person_text(text: str, initiator_role: str) -> str:
    role = _role_label(initiator_role)
    normalized = (text or "").strip("。 ")
    replacements = {
        "我方": role,
        "我们": role,
        "我": role,
        "本人": f"{role}本人",
        "本店": role,
    }
    for source, target in replacements.items():
        normalized = normalized.replace(source, target)
    return normalized or "争议发起方提出处理诉求"


def _verification_focus_for_text(text: str) -> list[str]:
    focus: list[str] = []
    if "签收" in text or "物流" in text:
        focus.extend(["签收人身份", "签收位置", "物流投递轨迹"])
    if "未收到" in text or "没收到" in text:
        focus.append("用户未收货证明")
    return focus


def _party_role_or_default(value: str | None) -> str:
    value = str(value or "").upper()
    if value == "MERCHANT":
        return "MERCHANT"
    return "USER"


def _opposite_party(value: str | None) -> str:
    return "USER" if str(value or "").upper() == "MERCHANT" else "MERCHANT"


def _role_label(value: str | None) -> str:
    return "商家" if str(value or "").upper() == "MERCHANT" else "用户"


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
