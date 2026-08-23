"""Deterministic reducer for the unified intake case fact matrix."""

from __future__ import annotations

import hashlib
import json
import re
from collections.abc import Mapping
from typing import Any, NoReturn

from app.contracts.case_fact_matrix_hash import (
    case_fact_matrix_content_hash,
    validate_case_fact_matrix_content_hash,
)
from app.graphs.intake.contracts import RESPONDENT_OPENING_MARKER
from app.llm import AgentOutputSchemaError
from app.schemas.intake_case_matrix import (
    FactStance,
    MatrixPartyMap,
    UnilateralCaseMatrixDraftV1,
    UnilateralCaseMatrixV1,
)
from app.schemas.case_fact_matrix import (
    CaseAlignmentStatus,
    CaseFactDeltaRowV2,
    CaseFactMatrixDeltaV2,
    CaseFactMatrixV2,
    CaseMatrixKind,
    CaseMatrixSourceScope,
)
from app.schemas.final_agents import IntakeTurnRequest


SUBJECTIVE_RESPONDENT_SOURCE = "发起方单方陈述（主观）"
DIRECT_RESPONDENT_SOURCE = "被发起方接待室直接陈述"
_SUBSTANTIVE = {FactStance.CONFIRM, FactStance.DENY, FactStance.PARTIAL}


def respondent_opening_carry_delta(
    *,
    request: IntakeTurnRequest,
) -> CaseFactMatrixDeltaV2:
    """Derive the only authority-neutral delta allowed for respondent opening."""

    if request.turn_source != RESPONDENT_OPENING_MARKER:
        _schema_error(
            "respondent opening carry requires the opening control source",
            safe_code="INTAKE_MATRIX_MISSING_DELTA_CARRY_INVALID",
        )
    _current_source(request)
    previous = _previous_matrix(request)
    if previous is None:
        _schema_error(
            "respondent opening requires an initiator matrix",
            safe_code="INTAKE_MATRIX_INITIATOR_MATRIX_MISSING",
        )
    initiator_role = previous.party_map.initiator_role
    respondent_role = "MERCHANT" if initiator_role == "USER" else "USER"
    actor_role = _matrix_actor_role(request, initiator_role)
    if (
        actor_role != respondent_role
        or previous.matrix_kind != CaseMatrixKind.INITIATOR_FROZEN
    ):
        _schema_error(
            "respondent opening requires an unchanged initiator matrix",
            safe_code="INTAKE_MATRIX_MISSING_DELTA_CARRY_INVALID",
        )
    return _respondent_opening_carry_delta(previous, actor_role=actor_role)


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
    respondent_opening = request.turn_source == RESPONDENT_OPENING_MARKER
    if actor_role == respondent_role and previous is None:
        _schema_error(
            "respondent intake requires an initiator matrix",
            safe_code="INTAKE_MATRIX_INITIATOR_MATRIX_MISSING",
        )

    if respondent_opening:
        expected_opening_delta = respondent_opening_carry_delta(request=request)
        if delta is not None and delta.model_dump(
            mode="json"
        ) != expected_opening_delta.model_dump(mode="json"):
            _schema_error(
                "respondent opening delta conflicts with the authoritative carry",
                safe_code="INTAKE_MATRIX_MISSING_DELTA_CARRY_INVALID",
            )
        resolved_delta = expected_opening_delta
    else:
        resolved_delta = _as_v2_delta(
            delta,
            _case_summary(case_detail, request),
            previous=previous,
            actor_role=actor_role,
        )
    previous_rows = {
        row.fact_id: row for row in (previous.fact_rows if previous is not None else [])
    }
    previous_ids_by_fingerprint: dict[str, list[str]] = {}
    for row in previous_rows.values():
        previous_ids_by_fingerprint.setdefault(
            _fact_fingerprint(row.category, row.fact_target), []
        ).append(row.fact_id)
    explicit_previous_bindings = _explicit_previous_fact_bindings(
        resolved_delta.fact_rows,
        previous_rows=previous_rows,
        previous_ids_by_fingerprint=previous_ids_by_fingerprint,
    )
    new_previous_bindings, genuinely_new_groups = _new_fact_resolution_plan(
        resolved_delta.fact_rows,
        previous_ids_by_fingerprint=previous_ids_by_fingerprint,
        explicitly_bound_previous_ids=set(explicit_previous_bindings.values()),
    )
    for fingerprint, items in genuinely_new_groups.items():
        if len(items) > 1 and _fact_collision_is_conflicting(items):
            _schema_error(
                "new matrix facts share an identity but carry conflicting positions: "
                + fingerprint,
                safe_code="INTAKE_MATRIX_FACT_DUPLICATE",
            )
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
            fact_id = explicit_previous_bindings[item.fact_key]
            previous_row = previous_rows[fact_id]
            if fact_id != item.fact_key:
                corrected_fact_keys[item.fact_key] = fact_id
            referenced_previous.add(fact_id)
            if _fact_fingerprint(previous_row.category, previous_row.fact_target) != _fact_fingerprint(
                item.category, item.fact_target
            ):
                _schema_error(
                    f"existing fact {item.fact_key} cannot change category or fact_target",
                    safe_code="INTAKE_MATRIX_FACT_BINDING_MUTATED",
                )
        else:
            fingerprint = _fact_fingerprint(item.category, item.fact_target)
            reused = new_previous_bindings.get(item.fact_key)
            if reused is not None:
                fact_id = reused
                previous_row = previous_rows[reused]
                referenced_previous.add(reused)
            else:
                collision_items = genuinely_new_groups[fingerprint]
                fact_id = _stable_fact_id(
                    request.case_id,
                    item.category,
                    item.fact_target,
                    discriminator=(
                        _fact_collision_digest(item)
                        if previous_ids_by_fingerprint.get(fingerprint)
                        or len(collision_items) > 1
                        else None
                    ),
                )
        if fact_id in seen:
            _schema_error(
                f"case matrix delta resolves duplicate fact {fact_id}",
                safe_code="INTAKE_MATRIX_FACT_DUPLICATE",
            )
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

    # The Provider emits only the current authenticated party's rows.  Prior
    # facts omitted by that party are carried by the server as NOT_ADDRESSED;
    # the model never has to reproduce the other party's position to preserve a
    # complete frozen matrix.
    missing_previous = set(previous_rows) - referenced_previous
    for fact_id in previous_rows:
        if fact_id not in missing_previous:
            continue
        previous_row = previous_rows[fact_id]
        carry = CaseFactDeltaRowV2.model_validate(
            {
                "fact_key": fact_id,
                "category": previous_row.category,
                "fact_target": previous_row.fact_target,
                "materiality": previous_row.materiality,
                "stance": "NOT_ADDRESSED",
                "position_summary": "当前方未就该事实直接陈述。",
                "asserted_value": None,
                "source_scope": "PREVIOUS_MATRIX",
                "agreed_statement": previous_row.party_alignment.agreed_statement,
                "conflict_summary": previous_row.party_alignment.conflict_summary,
            }
        )
        seen.add(fact_id)
        rows.append(
            _reduce_fact_row(
                item=carry,
                fact_id=fact_id,
                previous_row=previous_row,
                current_ref=current_ref,
                actor_role=actor_role,
                matrix_kind=matrix_kind,
            )
        )
    if previous_rows:
        previous_order = {fact_id: index for index, fact_id in enumerate(previous_rows)}
        rows.sort(
            key=lambda row: (
                0,
                previous_order[row["fact_id"]],
            )
            if row["fact_id"] in previous_order
            else (1, 0)
        )
    summary_ids = _deduplicate(
        [resolved_ids[key] for key in resolved_delta.summary_source_fact_keys]
    )
    if not summary_ids:
        _schema_error(
            "case overview requires at least one fact reference",
            safe_code="INTAKE_MATRIX_OVERVIEW_FACTS_MISSING",
        )

    if respondent_opening:
        if previous is None:  # Kept explicit for static narrowing and fail-closed safety.
            _schema_error(
                "respondent opening requires an initiator matrix",
                safe_code="INTAKE_MATRIX_INITIATOR_MATRIX_MISSING",
            )
        claims = previous.claims.model_dump(mode="json")
        summary = previous.case_overview.neutral_summary
        core_conflict = previous.case_overview.core_conflict
    else:
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
    if previous_row is None and item.source_scope == CaseMatrixSourceScope.PREVIOUS_MATRIX:
        _schema_error(
            f"new fact {item.fact_key} cannot cite PREVIOUS_MATRIX",
            safe_code="INTAKE_MATRIX_NEW_FACT_PREVIOUS_SCOPE_INVALID",
        )
    if previous_row is not None and item.materiality != previous_row.materiality:
        _schema_error(
            f"existing fact {fact_id} cannot change materiality",
            safe_code="INTAKE_MATRIX_FACT_MATERIALITY_MUTATED",
        )
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
            _schema_error(
                "a new fact cannot be NOT_ADDRESSED by its source party",
                safe_code="INTAKE_MATRIX_NEW_FACT_NOT_ADDRESSED",
            )
        if item.source_scope != CaseMatrixSourceScope.PREVIOUS_MATRIX:
            _schema_error(
                "NOT_ADDRESSED delta must preserve the previous position",
                safe_code="INTAKE_MATRIX_NOT_ADDRESSED_SCOPE_INVALID",
            )
    else:
        prior_refs = list(previous_position.get("source_refs") or [])
        refs = list(prior_refs)
        if item.source_scope in {
            CaseMatrixSourceScope.CURRENT_SOURCE,
            CaseMatrixSourceScope.PREVIOUS_AND_CURRENT_SOURCE,
        }:
            refs.append(current_ref)
        if not refs:
            _schema_error(
                f"fact {fact_id} current position has no source",
                safe_code="INTAKE_MATRIX_POSITION_SOURCE_MISSING",
            )
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
        _schema_error(
            f"fact {fact_id} has no origin source",
            safe_code="INTAKE_MATRIX_ORIGIN_SOURCE_MISSING",
        )
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
        "evidence_coverage_status": "PENDING_EVIDENCE_REVIEW",
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

    # Actor-local viewpoint ownership: an initiator-authored turn never creates
    # or carries a position that purports to describe the respondent.  The real
    # respondent position is added only by the authenticated respondent turn.
    reported = None
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
    if (
        respondent_claim is not None
        and respondent_claim.get("attitude") == "NOT_ADDRESSED"
    ):
        respondent_claim = None
    if actor_role == respondent_role and respondent_claim is not None:
        direct_candidate = {
            "respondent_role": respondent_role,
            **respondent_claim,
            "source_type": "RESPONDENT_DIRECT_INTAKE",
        }
        if direct is not None and {
            key: value for key, value in direct.items() if key != "source_refs"
        } == direct_candidate:
            pass
        else:
            if not _attitude_grounding_matches_source(
                request,
                case_detail,
                current_ref=current_ref,
                expected_dossier_source=DIRECT_RESPONDENT_SOURCE,
                expected_message_source="RESPONDENT_PARTICIPANT_MESSAGE",
            ):
                _schema_error(
                    "changed respondent direct claim is not bound to the current source",
                    safe_code="INTAKE_MATRIX_DIRECT_CLAIM_SOURCE_MISSING",
                )
            direct = {
                **direct_candidate,
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
def _reported_position(
    request: IntakeTurnRequest,
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
    candidate = {
        "respondent_role": respondent_role,
        "attitude": code,
        "position_summary": position,
        "source_type": "INITIATOR_SUBJECTIVE_REPORT",
    }
    if prior is not None:
        prior_payload = prior.model_dump(mode="json")
        if {
            key: value for key, value in prior_payload.items() if key != "source_refs"
        } == candidate:
            return prior_payload
    elif _is_legacy_dossier_attitude_exact_carry(
        request,
        attitude,
        respondent_role=respondent_role,
    ):
        # A pre-matrix checkpoint can carry a valid historical dossier branch but
        # has no formal source_refs to preserve.  Keep it dossier-only until a
        # future turn supplies a fresh, current-source-bound update.
        return None
    if not current_is_initiator or not _attitude_grounding_matches_source(
        request,
        case_detail,
        current_ref=current_ref,
        expected_dossier_source=SUBJECTIVE_RESPONDENT_SOURCE,
        expected_message_source="PARTICIPANT_MESSAGE",
        allow_initial_form=True,
    ):
        _schema_error(
            "changed reported respondent position is not bound to the current source",
            safe_code="INTAKE_MATRIX_REPORTED_CLAIM_SOURCE_MISSING",
        )
    refs = list(prior.source_refs) if prior is not None else []
    if current_is_initiator:
        refs.append(current_ref)
    if not refs:
        refs.append(current_ref)
    return {
        **candidate,
        "source_refs": _deduplicate(refs)[:50],
    }


def _is_legacy_dossier_attitude_exact_carry(
    request: IntakeTurnRequest,
    current: Mapping[str, Any],
    *,
    respondent_role: str,
) -> bool:
    previous_detail = request.previous_case_detail
    if not isinstance(previous_detail, dict):
        return False
    previous = previous_detail.get("respondent_attitude")
    if not isinstance(previous, dict) or not _canonical_json_equal(previous, current):
        return False
    if (
        previous.get("respondent_role") != respondent_role
        or _optional_text(previous.get("source")) != SUBJECTIVE_RESPONDENT_SOURCE
        or "attitude" not in previous
        or "status" in previous
        or str(previous.get("attitude") or "").strip()
        not in {
            "AGREE",
            "PARTIALLY_AGREE",
            "DISAGREE",
            "ALTERNATIVE_PROPOSED",
            "NEED_MORE_INFO",
        }
        or _optional_text(previous.get("position")) is None
    ):
        return False
    confidence = previous.get("confidence")
    if (
        isinstance(confidence, bool)
        or not isinstance(confidence, int | float)
        or not 0 <= confidence <= 1
        or _optional_text(previous.get("confidence_note")) is None
    ):
        return False
    grounding = _mapping(previous.get("grounding"))
    if not {"source", "message_id"} <= set(grounding):
        return False
    grounding_source = grounding.get("source")
    message_id = grounding.get("message_id")
    return (
        grounding_source == "INITIAL_FORM" and message_id == ""
    ) or (
        grounding_source == "PARTICIPANT_MESSAGE"
        and isinstance(message_id, str)
        and bool(message_id)
    )


def _attitude_grounding_matches_source(
    request: IntakeTurnRequest,
    case_detail: dict[str, Any],
    *,
    current_ref: str,
    expected_dossier_source: str,
    expected_message_source: str,
    allow_initial_form: bool = False,
) -> bool:
    attitude = _mapping(case_detail.get("respondent_attitude"))
    if _optional_text(attitude.get("source")) != expected_dossier_source:
        return False
    grounding = _mapping(attitude.get("grounding"))
    if not {"source", "message_id"} <= set(grounding):
        return False
    current = request.current_user_message
    if current is not None:
        return (
            grounding.get("source") == expected_message_source
            and grounding.get("message_id") == current.message_id
            and current_ref == current.message_id
        )
    return (
        allow_initial_form
        and request.initial_case_facts is not None
        and grounding.get("source") == "INITIAL_FORM"
        and grounding.get("message_id") == ""
        and current_ref == f"INTAKE_FORM_{request.case_id}"
    )


def _canonical_json_equal(left: Mapping[str, Any], right: Mapping[str, Any]) -> bool:
    return json.dumps(
        left,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ) == json.dumps(
        right,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def _previous_matrix(request: IntakeTurnRequest) -> CaseFactMatrixV2 | None:
    detail = request.previous_case_detail
    if not isinstance(detail, dict):
        return None
    candidate = detail.get("case_fact_matrix")
    if isinstance(candidate, dict):
        try:
            matrix = CaseFactMatrixV2.model_validate(candidate)
        except ValueError as failure:
            _schema_error(
                f"previous case_fact_matrix.v2 is invalid: {failure}",
                safe_code="INTAKE_MATRIX_PREVIOUS_SCHEMA_INVALID",
            )
        if not validate_case_fact_matrix_content_hash(candidate):
            _schema_error(
                "previous case_fact_matrix.v2 content hash is invalid",
                safe_code="INTAKE_MATRIX_PREVIOUS_HASH_INVALID",
            )
        return matrix
    legacy = detail.get("unilateral_case_matrix")
    if isinstance(legacy, dict):
        try:
            return _upgrade_unilateral(UnilateralCaseMatrixV1.model_validate(legacy))
        except ValueError as failure:
            _schema_error(
                f"previous unilateral_case_matrix.v1 is invalid: {failure}",
                safe_code="INTAKE_MATRIX_PREVIOUS_LEGACY_SCHEMA_INVALID",
            )
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
                "evidence_coverage_status": "PENDING_EVIDENCE_REVIEW",
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
                    + row.fact_id,
                    safe_code="INTAKE_MATRIX_MISSING_DELTA_CARRY_INVALID",
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


def _respondent_opening_carry_delta(
    previous: CaseFactMatrixV2,
    *,
    actor_role: str,
) -> CaseFactMatrixDeltaV2:
    """Carry M0 without treating the room-opening control event as testimony."""

    carry_rows: list[dict[str, Any]] = []
    for row in previous.fact_rows:
        position = row.positions.for_role(actor_role)
        if position.model_dump(mode="json") != _not_addressed_position():
            _schema_error(
                "respondent opening cannot carry a substantive respondent position",
                safe_code="INTAKE_MATRIX_MISSING_DELTA_CARRY_INVALID",
            )
        carry_rows.append(
            {
                "fact_key": row.fact_id,
                "category": row.category,
                "fact_target": row.fact_target,
                "materiality": row.materiality,
                "stance": "NOT_ADDRESSED",
                "position_summary": position.position_summary,
                "asserted_value": None,
                "source_scope": "PREVIOUS_MATRIX",
                "agreed_statement": row.party_alignment.agreed_statement,
                "conflict_summary": row.party_alignment.conflict_summary,
            }
        )
    return CaseFactMatrixDeltaV2.model_validate(
        {
            "fact_rows": carry_rows,
            "summary_source_fact_keys": previous.case_overview.summary_source_fact_ids,
            "respondent_claim": None,
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
        {**material, "content_hash": case_fact_matrix_content_hash(material)}
    )


def _current_source(request: IntakeTurnRequest) -> tuple[str, str]:
    if request.turn_source == RESPONDENT_OPENING_MARKER:
        source_ref = request.respondent_opening_source_ref
        if source_ref is None:
            _schema_error(
                "respondent opening source is missing",
                safe_code="INTAKE_MATRIX_CURRENT_SOURCE_MISSING",
            )
        return source_ref, RESPONDENT_OPENING_MARKER
    if request.current_user_message is not None:
        return request.current_user_message.message_id, request.current_user_message.text
    initial = request.initial_case_facts
    if initial is None:
        _schema_error(
            "intake matrix requires a current source",
            safe_code="INTAKE_MATRIX_CURRENT_SOURCE_MISSING",
        )
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
        _schema_error(
            "matrix initiator_role must be USER or MERCHANT",
            safe_code="INTAKE_MATRIX_INITIATOR_ROLE_INVALID",
        )
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
    _schema_error(
        "case matrix requires a case summary",
        safe_code="INTAKE_MATRIX_CASE_SUMMARY_MISSING",
    )


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


def _stable_fact_id(
    case_id: str,
    category: Any,
    target: str,
    *,
    discriminator: str | None = None,
) -> str:
    parts = [case_id, str(category), _normalize(target)]
    if discriminator is not None:
        parts.append(discriminator)
    return "FACT_INTAKE_" + _digest(*parts)[:20].upper()


def _explicit_previous_fact_bindings(
    rows: Any,
    *,
    previous_rows: Mapping[str, Any],
    previous_ids_by_fingerprint: Mapping[str, Any],
) -> dict[str, str]:
    """Resolve every explicit FACT key before considering NEW compatibility binds."""

    bindings: dict[str, str] = {}
    for row in rows:
        fact_key = _fact_delta_value(row, "fact_key")
        if not isinstance(fact_key, str) or not fact_key.startswith("FACT_"):
            continue
        if fact_key in previous_rows:
            bindings[fact_key] = fact_key
            continue
        fingerprint = _fact_fingerprint(
            _fact_delta_value(row, "category"),
            str(_fact_delta_value(row, "fact_target") or ""),
        )
        matches = previous_ids_by_fingerprint.get(fingerprint, [])
        if len(matches) != 1:
            if matches:
                _schema_error(
                    "case matrix delta cannot uniquely resolve unknown fact "
                    f"{fact_key}",
                    safe_code="INTAKE_MATRIX_FACT_AMBIGUOUS",
                )
            _schema_error(
                f"case matrix delta references unknown fact {fact_key}",
                safe_code="INTAKE_MATRIX_FACT_UNKNOWN",
            )
        bindings[fact_key] = matches[0]
    return bindings


def _new_fact_resolution_plan(
    rows: Any,
    *,
    previous_ids_by_fingerprint: Mapping[str, Any],
    explicitly_bound_previous_ids: set[str],
) -> tuple[dict[str, str], dict[str, tuple[Any, ...]]]:
    """Bind NEW rows only to unclaimed history, otherwise preserve NEW identity.

    ``category + fact_target`` remains the compatibility key for carrying a
    frozen fact when a provider emits one recoverable NEW key.  Explicit FACT
    rows reserve their historical identities first.  A NEW row sharing that
    broad target is therefore a distinct proposal and receives a deterministic
    collision identity.  Planning the whole delta up front makes this invariant
    independent of provider row order.
    """

    grouped: dict[str, list[Any]] = {}
    for row in rows:
        fact_key = _fact_delta_value(row, "fact_key")
        if not isinstance(fact_key, str) or not fact_key.startswith("NEW_"):
            continue
        fingerprint = _fact_fingerprint(
            _fact_delta_value(row, "category"),
            str(_fact_delta_value(row, "fact_target") or ""),
        )
        grouped.setdefault(fingerprint, []).append(row)

    reused: dict[str, str] = {}
    genuinely_new: dict[str, tuple[Any, ...]] = {}
    for fingerprint, grouped_items in grouped.items():
        items = tuple(grouped_items)
        available_previous_ids = [
            fact_id
            for fact_id in previous_ids_by_fingerprint.get(fingerprint, [])
            if fact_id not in explicitly_bound_previous_ids
        ]
        if not available_previous_ids:
            genuinely_new[fingerprint] = items
            continue
        if len(available_previous_ids) == 1 and len(items) == 1:
            fact_key = _fact_delta_value(items[0], "fact_key")
            if not isinstance(fact_key, str):  # Pydantic guarantees this boundary.
                _schema_error(
                    "new matrix fact key is invalid",
                    safe_code="INTAKE_MATRIX_FACT_UNKNOWN",
                )
            reused[fact_key] = available_previous_ids[0]
            continue
        _schema_error(
            "case matrix delta cannot uniquely bind new facts to existing facts "
            + fingerprint,
            safe_code="INTAKE_MATRIX_FACT_AMBIGUOUS",
        )
    return reused, genuinely_new


def _fact_delta_value(row: Any, name: str) -> Any:
    if isinstance(row, Mapping):
        return row.get(name)
    return getattr(row, name, None)


def _fact_collision_signature(row: Any) -> tuple[str, ...]:
    """Return the proposal-local semantic identity, excluding its temporary key."""

    def value(name: str) -> Any:
        if isinstance(row, Mapping):
            return row.get(name)
        return getattr(row, name, None)

    return (
        _normalize(str(value("materiality") or "")),
        _normalize(str(value("stance") or "")),
        _normalize(str(value("position_summary") or "")),
        _normalize_value(value("asserted_value")),
        _normalize(str(value("source_scope") or "")),
        _normalize(str(value("agreed_statement") or "")),
        _normalize(str(value("conflict_summary") or "")),
    )


def _fact_collision_digest(row: Any) -> str:
    return _hash_json(_fact_collision_signature(row))


def _fact_collision_is_conflicting(rows: tuple[Any, ...]) -> bool:
    """Reject explicit contradictory positions instead of splitting them."""

    def value(row: Any, name: str) -> Any:
        if isinstance(row, Mapping):
            return row.get(name)
        return getattr(row, name, None)

    stances = {
        str(value(row, "stance"))
        for row in rows
        if str(value(row, "stance"))
        not in {"", "UNKNOWN", "NOT_ADDRESSED"}
    }
    if len(stances) > 1:
        return True
    asserted_values = {
        _normalize_value(value(row, "asserted_value"))
        for row in rows
        if _normalize_value(value(row, "asserted_value"))
    }
    return len(asserted_values) > 1


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


def _schema_error(message: str, *, safe_code: str) -> NoReturn:
    raise AgentOutputSchemaError(
        "intake_turn_case_detail",
        message,
        safe_code=safe_code,
    )
