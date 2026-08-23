"""Ordered V4 business context for the Hearing intake officer.

The assembler deliberately projects one frozen M1 authority. Evidence material,
transport credentials and audit-only matrix fields never enter this prompt envelope.
"""

from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
from typing import Any, Mapping

from app.contracts.v1.codec import canonical_sha256
from app.graphs.hearing.errors import HearingLcelContractError


HEARING_INTAKE_CONTEXT_V4_SCHEMA = "hearing_intake_context.v4"
HEARING_INTAKE_CONTEXT_V4_NODES = frozenset(
    {"hearing_intake_questions", "hearing_intake_synthesis"}
)
HEARING_INTAKE_CONTEXT_V4_MAX_ESTIMATED_TOKENS = 20_000

_NODE_MODES = {
    "hearing_intake_questions": "QUESTION_GENERATION",
    "hearing_intake_synthesis": "ANSWER_SYNTHESIS",
}


@dataclass(frozen=True, slots=True)
class AssembledHearingIntakeContextV4:
    node_name: str
    stage_mode: str
    payload: dict[str, Any]
    source_authority_hash: str


def assemble_hearing_intake_context_v4(
    node_name: str,
    source: Mapping[str, Any],
) -> AssembledHearingIntakeContextV4:
    """Project a typed V4 request into the single ordered prompt authority."""

    if node_name not in HEARING_INTAKE_CONTEXT_V4_NODES:
        raise HearingLcelContractError("HEARING_INTAKE_V4_CONTEXT_NODE_UNSUPPORTED")
    if not isinstance(source, Mapping):
        raise HearingLcelContractError("HEARING_INTAKE_V4_CONTEXT_SOURCE_INVALID")

    source_copy = deepcopy(dict(source))
    request = _mapping(source_copy.get("request"), "request")
    mode = _NODE_MODES[node_name]
    expected_stage = (
        "INTAKE_QUESTIONS" if mode == "QUESTION_GENERATION" else "INTAKE_SYNTHESIS"
    )
    authority_scope = _authority_scope(request)
    if authority_scope["stage_code"] != expected_stage:
        raise HearingLcelContractError("HEARING_INTAKE_V4_STAGE_BINDING_INVALID")

    source_hash = _canonical_hash(source_copy)
    matrix_projection = _matrix_projection(request, authority_scope)
    payload: dict[str, Any] = {
        "context_header": {
            "schema_version": HEARING_INTAKE_CONTEXT_V4_SCHEMA,
            "node_name": node_name,
            "agent_role": "INTAKE_OFFICER",
            "stage_mode": mode,
            "context_scope": "SHARED_HEARING",
            "source_contract": "hearing_flow.v2",
            "source_authority_hash": source_hash,
        },
        "mode_contract": _mode_contract(mode),
        "authority_scope": authority_scope,
        "frozen_case_matrix_projection": matrix_projection,
    }
    if mode == "QUESTION_GENERATION":
        slots = _list(request.get("question_slots"), "question_slots")
        _continuous_slots(slots, "question_slot_id", "QUESTION_SLOT", 5)
        payload["question_slot_catalog"] = slots
        payload["question_policy"] = {
            "minimum_questions": 1,
            "maximum_questions": len(slots),
            "target_roles": ["USER", "MERCHANT"],
            "fact_scope": "M1_FACT_IDS_ONLY",
            "issue_policy": "ONE_BASELINE_ISSUE_PER_USED_SLOT",
            "public_frame_policy": "ONE_FRAME_PER_USED_SLOT_IN_SLOT_ORDER",
        }
    else:
        question_set = _mapping(request.get("question_set"), "question_set")
        questions = _list(question_set.get("questions"), "formal questions")
        if not questions:
            raise HearingLcelContractError("HEARING_INTAKE_V4_ISSUE_CATALOG_EMPTY")
        payload["formal_issue_catalog"] = [
            {
                "issue_id": question.get("issue_id"),
                "issue_version": question.get("issue_version"),
                "issue_state_hash": question.get("issue_state_hash"),
                "issue_statement": _mapping(
                    question.get("issue_baseline"), "issue baseline"
                ).get("issue_statement"),
                "source_fact_ids": deepcopy(
                    _mapping(question.get("issue_baseline"), "issue baseline").get(
                        "source_fact_ids"
                    )
                ),
                "effective_party_positions": deepcopy(
                    _mapping(question.get("issue_baseline"), "issue baseline").get(
                        "effective_party_positions"
                    )
                ),
                "alignment": deepcopy(
                    _mapping(question.get("issue_baseline"), "issue baseline").get(
                        "alignment"
                    )
                ),
            }
            for question in questions
        ]
        payload["formal_question_catalog"] = {
            "question_set_id": question_set.get("question_set_id"),
            "question_set_hash": question_set.get("question_set_hash"),
            "formal_issue_catalog_hash": question_set.get(
                "formal_issue_catalog_hash"
            ),
            "questions": [
                {
                    "question_slot_id": question.get("question_slot_id"),
                    "question_id": question.get("question_id"),
                    "issue_id": question.get("issue_id"),
                    "fact_ids": deepcopy(question.get("fact_ids")),
                    "question_text": question.get("question_text"),
                    "party_prompts": deepcopy(question.get("party_prompts")),
                }
                for question in questions
            ],
        }
        bundles = _list(
            request.get("party_answer_bundles"), "party_answer_bundles"
        )
        if [bundle.get("participant_role") for bundle in bundles] != [
            "USER",
            "MERCHANT",
        ]:
            raise HearingLcelContractError("HEARING_INTAKE_V4_BUNDLE_ORDER_INVALID")
        payload["party_answer_bundle_catalog"] = bundles
        issue_slots = _list(request.get("new_issue_slots"), "new_issue_slots")
        fact_slots = _list(request.get("new_fact_slots"), "new_fact_slots")
        _continuous_scalar_slots(issue_slots, "NEW_ISSUE_SLOT", 5)
        _continuous_scalar_slots(fact_slots, "NEW_FACT_SLOT", 20)
        payload["transition_slot_catalog"] = {
            "new_issue_slots": issue_slots,
            "new_fact_slots": fact_slots,
        }
        payload["binding_authority_catalog"] = _binding_authority_catalog(
            questions=questions,
            bundles=bundles,
            matrix_projection=matrix_projection,
            issue_slots=issue_slots,
            fact_slots=fact_slots,
        )
        payload["matrix_transition_contract"] = {
            "old_issue_policy": "REBIND_EVERY_FORMAL_ISSUE_FROM_CURRENT_ANSWERS",
            "new_issue_policy": "USE_ONLY_PREALLOCATED_SLOTS",
            "matrix_effect_policy": "EVERY_EFFECT_MUST_REFERENCE_A_CURRENT_ISSUE",
            "old_fact_identity_policy": "IMMUTABLE",
            "old_position_policy": "NO_CARRY_FORWARD_AS_CURRENT_AUTHORITY",
        }
    payload["output_contract"] = _output_contract(mode)
    return AssembledHearingIntakeContextV4(
        node_name=node_name,
        stage_mode=mode,
        payload=payload,
        source_authority_hash=source_hash,
    )


def _authority_scope(request: dict[str, Any]) -> dict[str, Any]:
    value = {
        "flow_schema_version": request.get("flow_schema_version"),
        "context_schema_version": request.get("context_schema_version"),
        "case_id": request.get("case_id"),
        "workflow_id": request.get("workflow_id"),
        "stage_code": _enum_value(request.get("stage_code")),
        "stage_sequence": request.get("stage_sequence"),
        "stage_deadline_at": request.get("stage_deadline_at"),
        "source_refs": deepcopy(request.get("source_refs", [])),
        "prelude_authority_hash": request.get("prelude_authority_hash"),
    }
    if (
        value["flow_schema_version"] != "hearing_flow.v2"
        or value["context_schema_version"] != HEARING_INTAKE_CONTEXT_V4_SCHEMA
        or not _non_empty_text(value["case_id"])
        or not _non_empty_text(value["workflow_id"])
        or isinstance(value["stage_sequence"], bool)
        or not isinstance(value["stage_sequence"], int)
        or value["stage_sequence"] < 1
        or not _sha256(value["prelude_authority_hash"])
        or not isinstance(value["source_refs"], list)
        or any(not _non_empty_text(item) for item in value["source_refs"])
    ):
        raise HearingLcelContractError("HEARING_INTAKE_V4_AUTHORITY_INVALID")
    return value


def _matrix_projection(
    request: dict[str, Any], authority_scope: dict[str, Any]
) -> dict[str, Any]:
    matrix = _mapping(request.get("case_fact_matrix"), "case_fact_matrix")
    if (
        matrix.get("schema_version") != "case_fact_matrix.v2"
        or matrix.get("case_id") != authority_scope["case_id"]
        or not _non_empty_text(matrix.get("matrix_id"))
        or isinstance(matrix.get("matrix_version"), bool)
        or not isinstance(matrix.get("matrix_version"), int)
        or matrix.get("matrix_version", 0) < 1
        or not _sha256(matrix.get("content_hash"))
    ):
        raise HearingLcelContractError("HEARING_INTAKE_V4_MATRIX_BINDING_INVALID")
    projected_claims = deepcopy(_mapping(matrix.get("claims"), "claims"))
    _drop_nested_source_refs(projected_claims)
    projected_rows: list[dict[str, Any]] = []
    for raw_row in _list(matrix.get("fact_rows"), "fact_rows"):
        row = _mapping(raw_row, "fact row")
        projected_rows.append(
            {
                "fact_id": row.get("fact_id"),
                "category": _enum_value(row.get("category")),
                "fact_target": row.get("fact_target"),
                "materiality": _enum_value(row.get("materiality")),
                "positions": _without_source_refs(row.get("positions")),
                "party_alignment": deepcopy(row.get("party_alignment")),
                "requires_resolution": row.get("requires_resolution"),
                "truth_status": _enum_value(row.get("truth_status")),
                "evidence_coverage_status": _enum_value(
                    row.get("evidence_coverage_status")
                ),
            }
        )
    projection: dict[str, Any] = {
        "projection_schema_version": "hearing_case_matrix_projection.v4",
        "source_matrix_id": matrix["matrix_id"],
        "source_matrix_version": matrix["matrix_version"],
        "source_matrix_hash": matrix["content_hash"],
        "projection_hash": "0" * 64,
        "party_map": deepcopy(matrix.get("party_map")),
        "case_overview": deepcopy(matrix.get("case_overview")),
        "claims": projected_claims,
        "fact_rows": projected_rows,
    }
    projection["projection_hash"] = _hash_without(projection, "projection_hash")
    return projection


def _binding_authority_catalog(
    *,
    questions: list[Any],
    bundles: list[Any],
    matrix_projection: dict[str, Any],
    issue_slots: list[Any],
    fact_slots: list[Any],
) -> dict[str, Any]:
    """Expose one compact copy-only catalog for every model-owned reference field."""

    issue_ids = [_mapping(item, "formal question").get("issue_id") for item in questions]
    existing_fact_ids = [
        _mapping(item, "projected fact row").get("fact_id")
        for item in _list(matrix_projection.get("fact_rows"), "projected fact rows")
    ]
    if (
        any(not _non_empty_text(value) for value in [*issue_ids, *existing_fact_ids])
        or len(issue_ids) != len(set(issue_ids))
        or len(existing_fact_ids) != len(set(existing_fact_ids))
    ):
        raise HearingLcelContractError("HEARING_INTAKE_V4_BINDING_CATALOG_INVALID")

    bundle_by_role = {
        _mapping(bundle, "answer bundle").get("participant_role"): _mapping(
            bundle, "answer bundle"
        )
        for bundle in bundles
    }
    answer_bindings: list[dict[str, Any]] = []
    for raw_question in questions:
        question = _mapping(raw_question, "formal question")
        issue_id = question.get("issue_id")
        role_bindings: dict[str, Any] = {}
        for role in ("USER", "MERCHANT"):
            bundle = bundle_by_role.get(role)
            if bundle is None:
                raise HearingLcelContractError(
                    "HEARING_INTAKE_V4_BINDING_CATALOG_INVALID"
                )
            units = [
                _mapping(unit, "answer unit")
                for unit in _list(bundle.get("answer_units"), "answer units")
                if _mapping(unit, "answer unit").get("issue_id") == issue_id
            ]
            if len(units) != 1:
                raise HearingLcelContractError(
                    "HEARING_INTAKE_V4_BINDING_CATALOG_INVALID"
                )
            role_bindings[role] = {
                "answer_bundle_id": bundle.get("answer_bundle_id"),
                "answer_unit_id": units[0].get("answer_unit_id"),
            }
        answer_bindings.append(
            {
                "issue_id": issue_id,
                "question_id": question.get("question_id"),
                "source_fact_ids": deepcopy(question.get("fact_ids")),
                "role_bindings": role_bindings,
            }
        )

    return {
        "binding_policy": "COPY_EXACT_VALUE_FROM_THIS_CATALOG_ONLY",
        "formal_issue_ids": issue_ids,
        "answer_binding_catalog": answer_bindings,
        "existing_fact_ids": existing_fact_ids,
        "authorized_new_issue_slots": deepcopy(issue_slots),
        "authorized_new_fact_slots": deepcopy(fact_slots),
        "allowed_issue_refs": [*issue_ids, *deepcopy(issue_slots)],
        "allowed_fact_refs": [*existing_fact_ids, *deepcopy(fact_slots)],
    }


def _mode_contract(mode: str) -> dict[str, Any]:
    return {
        "schema_version": "hearing_intake_mode_contract.v4",
        "mode": mode,
        "model_call_count": 1,
        "business_semantics_authority": "MODEL_OUTPUT",
        "backend_validation_scope": [
            "SCHEMA",
            "IDENTIFIERS",
            "ROLES",
            "AUTHORITY",
            "ORDER",
            "COVERAGE",
            "BUDGETS",
            "HASHES",
        ],
        "semantic_post_validation": False,
    }


def _output_contract(mode: str) -> dict[str, Any]:
    if mode == "QUESTION_GENERATION":
        order = [
            "lead_public_text",
            "schema_version",
            "frames",
            "question_bindings",
        ]
        schema = "hearing_intake_question_stream.v5"
    else:
        order = [
            "lead_public_text",
            "schema_version",
            "frames",
            "issue_rebindings",
            "new_issue_proposals",
            "matrix_effects",
            "matrix_summary",
        ]
        schema = "hearing_intake_answer_stream.v5"
    return {
        "schema_version": "hearing_intake_model_output_contract.v5",
        "model_schema_version": schema,
        "structured_output_only": True,
        "property_order": order,
        "public_delta_fields": ["lead_public_text", "frames.public_text"],
        "frame_contract": {
            "array_field": "frames",
            "property_order": ["header", "public_text"],
            "header_must_complete_before_public_text": True,
            "persist_after_frame_close": True,
        },
        "server_owned_identifiers": True,
    }


def _continuous_slots(
    values: list[Any], field: str, prefix: str, maximum: int
) -> None:
    if not values or len(values) > maximum:
        raise HearingLcelContractError("HEARING_INTAKE_V4_SLOT_CATALOG_INVALID")
    actual = [_mapping(value, "slot").get(field) for value in values]
    expected = [f"{prefix}_{index:02d}" for index in range(1, len(values) + 1)]
    if actual != expected:
        raise HearingLcelContractError("HEARING_INTAKE_V4_SLOT_CATALOG_INVALID")


def _continuous_scalar_slots(values: list[Any], prefix: str, maximum: int) -> None:
    if not values or len(values) > maximum:
        raise HearingLcelContractError("HEARING_INTAKE_V4_SLOT_CATALOG_INVALID")
    expected = [f"{prefix}_{index:02d}" for index in range(1, len(values) + 1)]
    if values != expected:
        raise HearingLcelContractError("HEARING_INTAKE_V4_SLOT_CATALOG_INVALID")


def _drop_nested_source_refs(value: Any) -> None:
    if isinstance(value, dict):
        value.pop("source_refs", None)
        for child in value.values():
            _drop_nested_source_refs(child)
    elif isinstance(value, list):
        for child in value:
            _drop_nested_source_refs(child)


def _without_source_refs(value: Any) -> Any:
    result = deepcopy(value)
    _drop_nested_source_refs(result)
    return result


def _mapping(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, Mapping):
        raise HearingLcelContractError(
            "HEARING_INTAKE_V4_CONTEXT_SOURCE_INVALID", f"{label} must be an object"
        )
    return deepcopy(dict(value))


def _list(value: Any, label: str) -> list[Any]:
    if not isinstance(value, list):
        raise HearingLcelContractError(
            "HEARING_INTAKE_V4_CONTEXT_SOURCE_INVALID", f"{label} must be an array"
        )
    return deepcopy(value)


def _canonical_hash(value: Any) -> str:
    try:
        return canonical_sha256(value)
    except (TypeError, ValueError) as error:
        raise HearingLcelContractError(
            "HEARING_INTAKE_V4_CONTEXT_SOURCE_INVALID"
        ) from error


def _hash_without(value: dict[str, Any], field: str) -> str:
    payload = deepcopy(value)
    payload.pop(field, None)
    return _canonical_hash(payload)


def _enum_value(value: Any) -> Any:
    return getattr(value, "value", value)


def _non_empty_text(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _sha256(value: Any) -> bool:
    return (
        isinstance(value, str)
        and len(value) == 64
        and all(character in "0123456789abcdef" for character in value)
    )


__all__ = [
    "AssembledHearingIntakeContextV4",
    "HEARING_INTAKE_CONTEXT_V4_MAX_ESTIMATED_TOKENS",
    "HEARING_INTAKE_CONTEXT_V4_NODES",
    "HEARING_INTAKE_CONTEXT_V4_SCHEMA",
    "assemble_hearing_intake_context_v4",
]
