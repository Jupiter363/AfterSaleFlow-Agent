"""Deterministic reducer for the unified intake case fact matrix."""

from __future__ import annotations

import hashlib
import json
import re
from typing import Any, NoReturn

from app.llm import AgentOutputSchemaError
from app.schemas.intake_case_matrix import (
    FactStance,
    MatrixPartyMap,
    UnilateralCaseMatrixDraftV1,
    UnilateralCaseMatrixV1,
)
from app.schemas.case_fact_matrix import (
    CaseAlignmentStatus,
    CaseFactMatrixDeltaV2,
    CaseFactMatrixV2,
    CaseMatrixKind,
    CaseMatrixSourceScope,
)
from app.schemas.final_agents import IntakeTurnRequest


SUBJECTIVE_RESPONDENT_SOURCE = "发起方单方陈述（主观）"
_SUBSTANTIVE = {FactStance.CONFIRM, FactStance.DENY, FactStance.PARTIAL}


def finalize_case_fact_matrix(
    *,
    request: IntakeTurnRequest,
    case_detail: dict[str, Any],
    delta: CaseFactMatrixDeltaV2 | UnilateralCaseMatrixDraftV1 | None,
) -> CaseFactMatrixV2:
    previous = _previous_matrix(request)
    current_ref, current_text = _current_source(request)
    initiator_role = _initiator_role(request, case_detail, previous)
    respondent_role = "MERCHANT" if initiator_role == "USER" else "USER"
    actor_role = _matrix_actor_role(request, initiator_role)
    if actor_role == respondent_role and previous is None:
        _schema_error("respondent intake requires an initiator matrix")

    resolved_delta = _as_v2_delta(
        delta,
        _case_summary(case_detail, request),
        previous=previous,
        actor_role=actor_role,
    )
    previous_rows = {
        row.fact_id: row for row in (previous.fact_rows if previous is not None else [])
    }
    previous_fingerprints = {
        _fact_fingerprint(row.category, row.fact_target): row.fact_id
        for row in previous_rows.values()
    }
    previous_ids_by_fingerprint: dict[str, list[str]] = {}
    for row in previous_rows.values():
        previous_ids_by_fingerprint.setdefault(
            _fact_fingerprint(row.category, row.fact_target), []
        ).append(row.fact_id)
    referenced_previous: set[str] = set()
    resolved_ids: dict[str, str] = {}
    corrected_fact_keys: dict[str, str] = {}
    rows: list[dict[str, Any]] = []
    seen: set[str] = set()
    matrix_kind = (
        CaseMatrixKind.BILATERAL_FROZEN
        if actor_role == respondent_role
        else CaseMatrixKind.INITIATOR_FROZEN
    )

    for item in resolved_delta.fact_rows:
        previous_row = None
        if item.fact_key.startswith("FACT_"):
            fact_id = item.fact_key
            previous_row = previous_rows.get(fact_id)
            if previous_row is None:
                matches = previous_ids_by_fingerprint.get(
                    _fact_fingerprint(item.category, item.fact_target), []
                )
                if len(matches) != 1:
                    if matches:
                        _schema_error(
                            "case matrix delta cannot uniquely resolve unknown fact "
                            f"{item.fact_key}"
                        )
                    _schema_error(
                        f"case matrix delta references unknown fact {item.fact_key}"
                    )
                fact_id = matches[0]
                previous_row = previous_rows[fact_id]
                corrected_fact_keys[item.fact_key] = fact_id
            referenced_previous.add(fact_id)
            if _fact_fingerprint(previous_row.category, previous_row.fact_target) != _fact_fingerprint(
                item.category, item.fact_target
            ):
                _schema_error(
                    f"existing fact {item.fact_key} cannot change category or fact_target"
                )
        else:
            fingerprint = _fact_fingerprint(item.category, item.fact_target)
            reused = previous_fingerprints.get(fingerprint)
            if reused is not None:
                fact_id = reused
                previous_row = previous_rows[reused]
                referenced_previous.add(reused)
            else:
                fact_id = _stable_fact_id(request.case_id, item.category, item.fact_target)
        if fact_id in seen:
            _schema_error(f"case matrix delta resolves duplicate fact {fact_id}")
        seen.add(fact_id)
        resolved_ids[item.fact_key] = fact_id
        rows.append(
            _reduce_fact_row(
                item=item,
                fact_id=fact_id,
                previous_row=previous_row,
                current_ref=current_ref,
                actor_role=actor_role,
                matrix_kind=matrix_kind,
            )
        )

    missing_previous = set(previous_rows) - referenced_previous
    if missing_previous:
        _schema_error(
            "case matrix delta must carry every prior fact; missing="
            + str(sorted(missing_previous))
        )
    summary_ids = _deduplicate(
        [resolved_ids[key] for key in resolved_delta.summary_source_fact_keys]
    )
    if not summary_ids:
        _schema_error("case overview requires at least one fact reference")

    claims = _claims(
        request=request,
        case_detail=case_detail,
        previous=previous,
        delta=resolved_delta,
        initiator_role=initiator_role,
        respondent_role=respondent_role,
        actor_role=actor_role,
        current_ref=current_ref,
    )
    summary = _case_summary(case_detail, request)
    core_conflict = _core_conflict(case_detail, summary)
    relationships = (
        [item.model_dump(mode="json") for item in previous.fact_relationships]
        if previous is not None
        else []
    )
    all_source_refs = _deduplicate(
        [
            current_ref,
            *claims["initiator_claim"]["source_refs"],
            *(
                claims["respondent_reported_by_initiator"].get("source_refs", [])
                if claims["respondent_reported_by_initiator"] is not None
                else []
            ),
            *(
                claims["respondent_direct"].get("source_refs", [])
                if claims["respondent_direct"] is not None
                else []
            ),
            *[ref for row in rows for ref in row["origin"]["source_refs"]],
            *[
                ref
                for row in rows
                for position in row["positions"].values()
                for ref in position["source_refs"]
            ],
        ]
    )[:256]
    matrix_version = (previous.matrix_version + 1) if previous is not None else 1
    delta_for_hash = resolved_delta.model_dump(mode="json")
    if corrected_fact_keys:
        for row in delta_for_hash["fact_rows"]:
            row["fact_key"] = corrected_fact_keys.get(row["fact_key"], row["fact_key"])
        delta_for_hash["summary_source_fact_keys"] = [
            corrected_fact_keys.get(key, key)
            for key in delta_for_hash["summary_source_fact_keys"]
        ]
    source_context_hash = _hash_json(
        {
            "case_id": request.case_id,
            "parent_hash": previous.content_hash if previous is not None else None,
            "actor_role": actor_role,
            "current_source_ref": current_ref,
            "current_source_text": current_text,
            "delta": delta_for_hash,
        }
    )
    matrix_without_hash = {
        "schema_version": "case_fact_matrix.v2",
        "case_id": request.case_id,
        "matrix_id": "CASE_MATRIX_"
        + _digest(request.case_id, str(matrix_version), current_ref)[:20].upper(),
        "matrix_version": matrix_version,
        "matrix_kind": matrix_kind,
        "parent_ref": (
            {
                "matrix_id": previous.matrix_id,
                "matrix_version": previous.matrix_version,
                "content_hash": previous.content_hash,
            }
            if previous is not None
            else None
        ),
        "party_map": MatrixPartyMap(
            initiator_role=initiator_role, respondent_role=respondent_role
        ).model_dump(mode="json"),
        "source_refs": all_source_refs,
        "case_overview": {
            "neutral_summary": summary,
            "core_conflict": core_conflict,
            "summary_source_fact_ids": summary_ids,
        },
        "claims": claims,
        "fact_rows": rows,
        "fact_relationships": relationships,
        "generation_ref": {
            "actor_role": actor_role,
            "source_stage": (
                "RESPONDENT_INTAKE"
                if actor_role == respondent_role
                else "INITIATOR_INTAKE"
            ),
            "latest_source_ref": current_ref,
            "source_context_hash": source_context_hash,
        },
        "fact_indexes": _fact_indexes(rows),
    }
    return _with_hash(matrix_without_hash)


def _reduce_fact_row(
    *,
    item: Any,
    fact_id: str,
    previous_row: Any | None,
    current_ref: str,
    actor_role: str,
    matrix_kind: CaseMatrixKind,
) -> dict[str, Any]:
    if previous_row is None and item.source_scope != CaseMatrixSourceScope.CURRENT_SOURCE:
        _schema_error(f"new fact {item.fact_key} must cite CURRENT_SOURCE")
    positions = (
        previous_row.positions.model_dump(mode="json")
        if previous_row is not None
        else {
            "USER": _not_addressed_position(),
            "MERCHANT": _not_addressed_position(),
        }
    )
    previous_position = positions[actor_role]
    if item.stance == FactStance.NOT_ADDRESSED:
        if previous_row is None:
            _schema_error("a new fact cannot be NOT_ADDRESSED by its source party")
        if item.source_scope != CaseMatrixSourceScope.PREVIOUS_MATRIX:
            _schema_error("NOT_ADDRESSED delta must preserve the previous position")
    else:
        prior_refs = list(previous_position.get("source_refs") or [])
        refs = list(prior_refs)
        if item.source_scope in {
            CaseMatrixSourceScope.CURRENT_SOURCE,
            CaseMatrixSourceScope.PREVIOUS_AND_CURRENT_SOURCE,
        }:
            refs.append(current_ref)
        if not refs:
            _schema_error(f"fact {fact_id} current position has no source")
        positions[actor_role] = {
            "stance": item.stance,
            "position_summary": item.position_summary,
            "asserted_value": item.asserted_value,
            "source_type": "DIRECT_PARTY_STATEMENT",
            "source_refs": _deduplicate(refs)[:50],
        }

    prior_origin_refs = (
        list(previous_row.origin.source_refs) if previous_row is not None else []
    )
    origin_refs = list(prior_origin_refs)
    if item.source_scope in {
        CaseMatrixSourceScope.CURRENT_SOURCE,
        CaseMatrixSourceScope.PREVIOUS_AND_CURRENT_SOURCE,
    }:
        origin_refs.append(current_ref)
    if not origin_refs:
        _schema_error(f"fact {fact_id} has no origin source")
    introduced_stage = (
        previous_row.origin.introduced_stage
        if previous_row is not None
        else (
            "RESPONDENT_INTAKE"
            if matrix_kind == CaseMatrixKind.BILATERAL_FROZEN
            else "INITIATOR_INTAKE"
        )
    )
    alignment = _alignment(
        positions,
        fact_target=item.fact_target,
        agreed_statement=item.agreed_statement,
        conflict_summary=item.conflict_summary,
        compute=matrix_kind != CaseMatrixKind.INITIATOR_FROZEN,
    )
    status = CaseAlignmentStatus(alignment["status"])
    return {
        "fact_id": fact_id,
        "category": item.category,
        "fact_target": item.fact_target,
        "materiality": item.materiality,
        "origin": {
            "introduced_stage": introduced_stage,
            "source_refs": _deduplicate(origin_refs)[:50],
        },
        "positions": positions,
        "party_alignment": alignment,
        "requires_resolution": (
            None
            if status == CaseAlignmentStatus.NOT_COMPUTED
            else status != CaseAlignmentStatus.AGREED
        ),
        "truth_status": "NOT_EVALUATED",
    }


def _alignment(
    positions: dict[str, Any],
    *,
    fact_target: str,
    agreed_statement: str | None,
    conflict_summary: str | None,
    compute: bool,
) -> dict[str, Any]:
    if not compute:
        return {
            "status": "NOT_COMPUTED",
            "agreed_statement": None,
            "conflict_summary": None,
        }
    user = FactStance(positions["USER"]["stance"])
    merchant = FactStance(positions["MERCHANT"]["stance"])
    user_value = positions["USER"].get("asserted_value")
    merchant_value = positions["MERCHANT"].get("asserted_value")
    status = _alignment_status(
        user,
        merchant,
        user_value=user_value,
        merchant_value=merchant_value,
        has_shared_scope=bool(agreed_statement and conflict_summary),
    )
    if status == CaseAlignmentStatus.AGREED:
        return {
            "status": status,
            "agreed_statement": agreed_statement or fact_target,
            "conflict_summary": None,
        }
    if status == CaseAlignmentStatus.PARTIALLY_AGREED:
        return {
            "status": status,
            "agreed_statement": agreed_statement,
            "conflict_summary": conflict_summary,
        }
    return {
        "status": status,
        "agreed_statement": None,
        "conflict_summary": conflict_summary or "双方对该事实尚未形成一致陈述。",
    }


def _alignment_status(
    user: FactStance,
    merchant: FactStance,
    *,
    user_value: str | None,
    merchant_value: str | None,
    has_shared_scope: bool,
) -> CaseAlignmentStatus:
    if user not in _SUBSTANTIVE and merchant not in _SUBSTANTIVE:
        return CaseAlignmentStatus.UNRESOLVED
    if user not in _SUBSTANTIVE or merchant not in _SUBSTANTIVE:
        return CaseAlignmentStatus.ONE_SIDED
    same_value = bool(_normalize_value(user_value)) and _normalize_value(
        user_value
    ) == _normalize_value(merchant_value)
    if user == merchant and user in {FactStance.CONFIRM, FactStance.DENY}:
        return CaseAlignmentStatus.AGREED if same_value else CaseAlignmentStatus.CONTESTED
    if FactStance.DENY in {user, merchant}:
        return CaseAlignmentStatus.CONTESTED
    if FactStance.PARTIAL in {user, merchant}:
        return (
            CaseAlignmentStatus.PARTIALLY_AGREED
            if has_shared_scope
            else CaseAlignmentStatus.CONTESTED
        )
    return CaseAlignmentStatus.CONTESTED


def _claims(
    *,
    request: IntakeTurnRequest,
    case_detail: dict[str, Any],
    previous: CaseFactMatrixV2 | None,
    delta: CaseFactMatrixDeltaV2,
    initiator_role: str,
    respondent_role: str,
    actor_role: str,
    current_ref: str,
) -> dict[str, Any]:
    claim = _mapping(case_detail.get("claim_resolution"))
    summary = _case_summary(case_detail, request)
    material = {
        "initiator_role": initiator_role,
        "requested_resolution": str(
            claim.get("requested_resolution")
            or _initial_claim_value(request, "requested_resolution")
            or "UNKNOWN"
        ).strip(),
        "requested_amount": _number_or_none(claim.get("requested_amount")),
        "requested_items": _optional_text(claim.get("requested_items")),
        "reason_summary": _optional_text(claim.get("request_reason"))
        or _optional_text(claim.get("reason_summary"))
        or summary,
        "position_summary": _optional_text(claim.get("normalized_statement"))
        or _optional_text(claim.get("position_summary"))
        or _optional_text(claim.get("request_reason"))
        or summary,
    }
    prior_claim = previous.claims.initiator_claim if previous is not None else None
    claim_refs = list(prior_claim.source_refs) if prior_claim is not None else []
    if actor_role == initiator_role and (
        prior_claim is None
        or {
            key: getattr(prior_claim, key)
            for key in material
        }
        != material
    ):
        claim_refs.append(current_ref)
    if not claim_refs:
        claim_refs.append(current_ref)

    reported = _reported_position(
        case_detail, previous, respondent_role, current_ref, actor_role == initiator_role
    )
    direct = (
        previous.claims.respondent_direct.model_dump(mode="json")
        if previous is not None and previous.claims.respondent_direct is not None
        else None
    )
    respondent_claim = (
        delta.respondent_claim.model_dump(mode="json")
        if delta.respondent_claim is not None
        else None
    )
    if actor_role == respondent_role and respondent_claim is None and direct is None:
        respondent_claim = _fallback_respondent_claim(case_detail, request)
    if actor_role == respondent_role and respondent_claim is not None:
        direct = {
            "respondent_role": respondent_role,
            **respondent_claim,
            "source_type": "RESPONDENT_DIRECT_INTAKE",
            "source_refs": _deduplicate(
                [*(direct.get("source_refs", []) if direct else []), current_ref]
            )[:50],
        }
    return {
        "initiator_claim": {
            **material,
            "source_refs": _deduplicate(claim_refs)[:50],
        },
        "respondent_reported_by_initiator": reported,
        "respondent_direct": direct,
        "claim_conflict": (
            _core_conflict(case_detail, summary) if direct is not None else None
        ),
    }


def _fallback_respondent_claim(
    case_detail: dict[str, Any], request: IntakeTurnRequest
) -> dict[str, Any] | None:
    attitude = _mapping(case_detail.get("respondent_attitude"))
    attitude_code = str(attitude.get("attitude") or "").strip().upper()
    attitude_source = _optional_text(attitude.get("source"))
    position = _optional_text(attitude.get("position")) or _optional_text(
        attitude.get("position_summary")
    )
    supported = {
        "AGREE",
        "PARTIALLY_AGREE",
        "DISAGREE",
        "ALTERNATIVE_PROPOSED",
        "NEED_MORE_INFO",
    }
    if (
        attitude_source != SUBJECTIVE_RESPONDENT_SOURCE
        and attitude_code in supported
        and position
    ):
        return {
            "attitude": attitude_code,
            "position_summary": position,
            "alternative_proposal": _optional_text(
                attitude.get("alternative_proposal")
            ),
        }

    current = request.current_user_message
    text = current.text.strip() if current is not None else ""
    if not text:
        return None
    if re.search(r"部分(?:接受|同意|认可)", text):
        code = "PARTIALLY_AGREE"
        summary = "被发起方明确表示部分接受发起方诉求。"
    elif re.search(r"不(?:接受|同意|认可)|拒绝|诉求不合理", text):
        code = "DISAGREE"
        summary = "被发起方明确表示不接受发起方诉求。"
    elif re.search(r"(?:接受|同意|认可)(?:发起方)?(?:的)?(?:处理)?诉求", text):
        code = "AGREE"
        summary = "被发起方明确表示接受发起方诉求。"
    else:
        return None
    return {
        "attitude": code,
        "position_summary": summary,
        "alternative_proposal": None,
    }


def _reported_position(
    case_detail: dict[str, Any],
    previous: CaseFactMatrixV2 | None,
    respondent_role: str,
    current_ref: str,
    current_is_initiator: bool,
) -> dict[str, Any] | None:
    prior = (
        previous.claims.respondent_reported_by_initiator
        if previous is not None
        else None
    )
    attitude = _mapping(case_detail.get("respondent_attitude"))
    source = _optional_text(attitude.get("source"))
    code = str(attitude.get("attitude") or attitude.get("status") or "").strip()
    position = _optional_text(attitude.get("position")) or _optional_text(
        attitude.get("summary")
    )
    if source != SUBJECTIVE_RESPONDENT_SOURCE or code in {
        "",
        "NOT_RESPONDED",
        "PLATFORM_UNKNOWN",
    } or not position:
        return prior.model_dump(mode="json") if prior is not None else None
    refs = list(prior.source_refs) if prior is not None else []
    if current_is_initiator:
        refs.append(current_ref)
    if not refs:
        refs.append(current_ref)
    return {
        "respondent_role": respondent_role,
        "attitude": code,
        "position_summary": position,
        "source_type": "INITIATOR_SUBJECTIVE_REPORT",
        "source_refs": _deduplicate(refs)[:50],
    }


def _previous_matrix(request: IntakeTurnRequest) -> CaseFactMatrixV2 | None:
    detail = request.previous_case_detail
    if not isinstance(detail, dict):
        return None
    candidate = detail.get("case_fact_matrix")
    if isinstance(candidate, dict):
        try:
            matrix = CaseFactMatrixV2.model_validate(candidate)
        except ValueError as failure:
            _schema_error(f"previous case_fact_matrix.v2 is invalid: {failure}")
        if matrix.content_hash != _hash_without_content_hash(matrix.model_dump(mode="json")):
            _schema_error("previous case_fact_matrix.v2 content hash is invalid")
        return matrix
    legacy = detail.get("unilateral_case_matrix")
    if isinstance(legacy, dict):
        try:
            return _upgrade_unilateral(UnilateralCaseMatrixV1.model_validate(legacy))
        except ValueError as failure:
            _schema_error(f"previous unilateral_case_matrix.v1 is invalid: {failure}")
    return None


def _upgrade_unilateral(source: UnilateralCaseMatrixV1) -> CaseFactMatrixV2:
    initiator = source.party_map.initiator_role
    rows: list[dict[str, Any]] = []
    for row in source.fact_rows:
        positions = {
            "USER": _not_addressed_position(),
            "MERCHANT": _not_addressed_position(),
        }
        positions[initiator] = {
            **row.initiator_position.model_dump(mode="json"),
            "source_type": "DIRECT_PARTY_STATEMENT",
        }
        rows.append(
            {
                "fact_id": row.fact_id,
                "category": row.category,
                "fact_target": row.fact_target,
                "materiality": row.materiality,
                "origin": {
                    "introduced_stage": "INITIATOR_INTAKE",
                    "source_refs": row.origin.source_refs,
                },
                "positions": positions,
                "party_alignment": {
                    "status": "NOT_COMPUTED",
                    "agreed_statement": None,
                    "conflict_summary": None,
                },
                "requires_resolution": None,
                "truth_status": "NOT_EVALUATED",
            }
        )
    refs = list(source.source_binding.source_refs)
    without_hash = {
        "schema_version": "case_fact_matrix.v2",
        "case_id": source.source_binding.case_id,
        "matrix_id": "CASE_MATRIX_" + source.content_hash[:20].upper(),
        "matrix_version": source.matrix_version,
        "matrix_kind": "INITIATOR_FROZEN",
        "parent_ref": None,
        "party_map": source.party_map.model_dump(mode="json"),
        "source_refs": refs,
        "case_overview": {
            "neutral_summary": source.case_summary,
            "core_conflict": source.dispute_core_state.core_conflict,
            "summary_source_fact_ids": source.summary_source_fact_ids,
        },
        "claims": {
            "initiator_claim": source.claim_resolution.model_dump(mode="json"),
            "respondent_reported_by_initiator": (
                source.reported_respondent_attitude.model_dump(mode="json")
                if source.reported_respondent_attitude is not None
                else None
            ),
            "respondent_direct": None,
            "claim_conflict": None,
        },
        "fact_rows": rows,
        "fact_relationships": [],
        "generation_ref": {
            "actor_role": initiator,
            "source_stage": "INITIATOR_INTAKE",
            "latest_source_ref": source.source_binding.latest_source_ref,
            "source_context_hash": source.source_binding.source_context_hash,
        },
        "fact_indexes": _fact_indexes(rows),
    }
    return _with_hash(without_hash)


def _as_v2_delta(
    value: CaseFactMatrixDeltaV2 | UnilateralCaseMatrixDraftV1 | None,
    summary: str,
    *,
    previous: CaseFactMatrixV2 | None,
    actor_role: str,
) -> CaseFactMatrixDeltaV2:
    if isinstance(value, CaseFactMatrixDeltaV2):
        return value
    if isinstance(value, UnilateralCaseMatrixDraftV1):
        return CaseFactMatrixDeltaV2.model_validate(
            {
                "fact_rows": [
                    {
                        **row.model_dump(mode="json"),
                        "stance": "CONFIRM",
                    }
                    for row in value.fact_rows
                ],
                "summary_source_fact_keys": value.summary_source_fact_keys,
            }
        )
    if previous is not None:
        carry_rows: list[dict[str, Any]] = []
        for row in previous.fact_rows:
            position = row.positions.for_role(actor_role)
            if position.stance == FactStance.NOT_ADDRESSED:
                _schema_error(
                    "a missing matrix delta cannot invent the current party's position for "
                    + row.fact_id
                )
            carry_rows.append(
                {
                    "fact_key": row.fact_id,
                    "category": row.category,
                    "fact_target": row.fact_target,
                    "materiality": row.materiality,
                    "stance": position.stance,
                    "position_summary": position.position_summary,
                    "asserted_value": position.asserted_value,
                    "source_scope": "PREVIOUS_MATRIX",
                    "agreed_statement": row.party_alignment.agreed_statement,
                    "conflict_summary": row.party_alignment.conflict_summary,
                }
            )
        respondent_claim = None
        if previous.claims.respondent_direct is not None:
            direct = previous.claims.respondent_direct
            respondent_claim = {
                "attitude": direct.attitude,
                "position_summary": direct.position_summary,
                "alternative_proposal": direct.alternative_proposal,
            }
        return CaseFactMatrixDeltaV2.model_validate(
            {
                "fact_rows": carry_rows,
                "summary_source_fact_keys": previous.case_overview.summary_source_fact_ids,
                "respondent_claim": respondent_claim,
            }
        )
    return CaseFactMatrixDeltaV2.model_validate(
        {
            "fact_rows": [
                {
                    "fact_key": "NEW_CASE_SUMMARY",
                    "category": "OTHER",
                    "fact_target": summary,
                    "materiality": "CORE",
                    "stance": "CONFIRM",
                    "position_summary": summary,
                    "asserted_value": summary,
                    "source_scope": "CURRENT_SOURCE",
                }
            ],
            "summary_source_fact_keys": ["NEW_CASE_SUMMARY"],
        }
    )


def _fact_indexes(rows: list[dict[str, Any]]) -> dict[str, list[str]]:
    indexes = {
        "not_computed_fact_ids": [],
        "agreed_fact_ids": [],
        "partially_agreed_fact_ids": [],
        "contested_fact_ids": [],
        "one_sided_fact_ids": [],
        "unresolved_fact_ids": [],
        "core_fact_ids": [],
        "requires_resolution_fact_ids": [],
    }
    status_key = {
        "NOT_COMPUTED": "not_computed_fact_ids",
        "AGREED": "agreed_fact_ids",
        "PARTIALLY_AGREED": "partially_agreed_fact_ids",
        "CONTESTED": "contested_fact_ids",
        "ONE_SIDED": "one_sided_fact_ids",
        "UNRESOLVED": "unresolved_fact_ids",
    }
    for row in rows:
        indexes[status_key[str(row["party_alignment"]["status"])]] .append(row["fact_id"])
        if str(row["materiality"]) == "CORE":
            indexes["core_fact_ids"].append(row["fact_id"])
        if row["requires_resolution"] is True:
            indexes["requires_resolution_fact_ids"].append(row["fact_id"])
    return indexes


def _with_hash(value: dict[str, Any]) -> CaseFactMatrixV2:
    provisional = CaseFactMatrixV2.model_validate({**value, "content_hash": "0" * 64})
    material = provisional.model_dump(mode="json")
    material.pop("content_hash")
    return CaseFactMatrixV2.model_validate(
        {**material, "content_hash": _hash_json(material)}
    )


def _hash_without_content_hash(value: dict[str, Any]) -> str:
    material = dict(value)
    material.pop("content_hash", None)
    return _hash_json(material)


def _current_source(request: IntakeTurnRequest) -> tuple[str, str]:
    if request.current_user_message is not None:
        return request.current_user_message.message_id, request.current_user_message.text
    initial = request.initial_case_facts
    if initial is None:
        _schema_error("intake matrix requires a current source")
    return f"INTAKE_FORM_{request.case_id}", initial.form_description


def _matrix_actor_role(request: IntakeTurnRequest, initiator_role: str) -> str:
    role = str(request.agent_context.actor_role).upper()
    return role if role in {"USER", "MERCHANT"} else initiator_role


def _initiator_role(
    request: IntakeTurnRequest,
    case_detail: dict[str, Any],
    previous: CaseFactMatrixV2 | None,
) -> str:
    if previous is not None:
        return previous.party_map.initiator_role
    initial = request.initial_case_facts
    claim = _mapping(case_detail.get("claim_resolution"))
    role = str(
        (initial.initiator_role if initial is not None else "")
        or claim.get("initiator_role")
        or request.agent_context.actor_role
    ).upper()
    if role not in {"USER", "MERCHANT"}:
        _schema_error("matrix initiator_role must be USER or MERCHANT")
    return role


def _case_summary(case_detail: dict[str, Any], request: IntakeTurnRequest) -> str:
    story = _mapping(case_detail.get("case_story"))
    summary = _optional_text(story.get("one_sentence_summary"))
    if summary:
        return summary
    if request.current_user_message is not None:
        return request.current_user_message.text
    if request.initial_case_facts is not None:
        return request.initial_case_facts.form_description
    _schema_error("case matrix requires a case summary")


def _core_conflict(case_detail: dict[str, Any], fallback: str) -> str:
    core = _mapping(case_detail.get("dispute_core_state"))
    focus = _mapping(case_detail.get("dispute_focus"))
    return (
        _optional_text(core.get("core_conflict"))
        or _optional_text(focus.get("core_issue"))
        or fallback
    )


def _initial_claim_value(request: IntakeTurnRequest, field: str) -> Any:
    initial = request.initial_case_facts
    seed = initial.claim_resolution_seed if initial is not None else None
    return getattr(seed, field, None) if seed is not None else None


def _not_addressed_position() -> dict[str, Any]:
    return {
        "stance": "NOT_ADDRESSED",
        "position_summary": "该方尚未直接陈述。",
        "asserted_value": None,
        "source_type": "NO_DIRECT_POSITION",
        "source_refs": [],
    }


def _stable_fact_id(case_id: str, category: Any, target: str) -> str:
    return "FACT_INTAKE_" + _digest(case_id, str(category), _normalize(target))[:20].upper()


def _fact_fingerprint(category: Any, target: str) -> str:
    return f"{category}:{_normalize(target)}"


def _normalize(value: str) -> str:
    return re.sub(r"\s+", "", str(value or "")).casefold()


def _normalize_value(value: str | None) -> str:
    return re.sub(r"[\W_]+", "", str(value or ""), flags=re.UNICODE).casefold()


def _mapping(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _optional_text(value: Any) -> str | None:
    text = str(value or "").strip()
    return text or None


def _number_or_none(value: Any) -> float | None:
    return float(value) if isinstance(value, (int, float)) else None


def _deduplicate(values: list[Any]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        text = str(value or "").strip()
        if text and text not in seen:
            seen.add(text)
            result.append(text)
    return result


def _digest(*parts: str) -> str:
    return hashlib.sha256("\x1f".join(parts).encode("utf-8")).hexdigest()


def _hash_json(value: Any) -> str:
    return hashlib.sha256(
        json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()


def _schema_error(message: str) -> NoReturn:
    raise AgentOutputSchemaError("intake_turn_case_detail", message)
