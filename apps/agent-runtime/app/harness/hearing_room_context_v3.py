"""Ordered, single-source business context for Hearing intake and evidence officers."""

from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
from typing import Any, Mapping

from app.contracts.v1.codec import canonical_sha256
from app.graphs.hearing.errors import HearingLcelContractError


HEARING_ROOM_CONTEXT_V3_SCHEMA = "hearing_room_context.v3"
HEARING_ROOM_CONTEXT_V3_NODES = frozenset(
    {
        "hearing_evidence_requests",
        "hearing_evidence_file_assessment",
        "hearing_evidence_synthesis",
    }
)

_NODE_BINDINGS: dict[str, tuple[str, str]] = {
    "hearing_intake_questions": ("INTAKE_OFFICER", "INTAKE_QUESTIONS"),
    "hearing_intake_synthesis": ("INTAKE_OFFICER", "INTAKE_SYNTHESIS"),
    "hearing_evidence_requests": ("EVIDENCE_CLERK", "EVIDENCE_REQUESTS"),
    "hearing_evidence_file_assessment": (
        "EVIDENCE_CLERK",
        "EVIDENCE_SYNTHESIS",
    ),
    "hearing_evidence_synthesis": ("EVIDENCE_CLERK", "EVIDENCE_SYNTHESIS"),
}

_PUBLIC_OUTPUT_ORDERS: dict[str, list[str]] = {
    "hearing_intake_questions": ["public_message", "questions"],
    "hearing_intake_synthesis": [
        "public_message",
        "case_fact_matrix_delta",
        "issue_mappings",
    ],
    "hearing_evidence_requests": ["public_message", "requests"],
    "hearing_evidence_synthesis": [
        "public_message",
        "evidence_summary",
        "evidence_gaps",
    ],
}

_GOALS = {
    "hearing_intake_questions": (
        "从冻结双方案情矩阵识别共享争议点，并为双方生成视角化自然语言陈述提示"
    ),
    "hearing_intake_synthesis": (
        "把双方庭审陈述语义绑定到共享争议点，形成案情矩阵增量与完整中立综合"
    ),
    "hearing_evidence_requests": (
        "根据冻结案情矩阵与庭前证据覆盖状态生成事实绑定的最小补证请求"
    ),
    "hearing_evidence_file_assessment": (
        "只核验当前一份庭审补充材料，并将其绑定到已存在的事实"
    ),
    "hearing_evidence_synthesis": (
        "在逐文件核验完成后，依据合并证据矩阵生成全量证据综合与缺口"
    ),
}


@dataclass(frozen=True, slots=True)
class AssembledHearingRoomContextV3:
    node_name: str
    agent_role: str
    stage_mode: str
    payload: dict[str, Any]
    source_authority_hash: str


def assemble_hearing_room_context_v3(
    node_name: str,
    source: Mapping[str, Any],
) -> AssembledHearingRoomContextV3:
    """Project an already-typed Hearing request into one ordered prompt envelope."""

    if node_name not in HEARING_ROOM_CONTEXT_V3_NODES:
        raise HearingLcelContractError("HEARING_CONTEXT_NODE_UNSUPPORTED")
    if not isinstance(source, Mapping):
        raise HearingLcelContractError("HEARING_CONTEXT_SOURCE_INVALID")

    source_copy = deepcopy(dict(source))
    source_hash = _canonical_hash(source_copy)
    agent_role, stage_mode = _NODE_BINDINGS[node_name]
    flow = _flow_authority(node_name, source_copy)
    if flow["stage_code"] != stage_mode:
        raise HearingLcelContractError("HEARING_CONTEXT_STAGE_BINDING_INVALID")

    payload: dict[str, Any] = {
        "context_header": {
            "schema_version": HEARING_ROOM_CONTEXT_V3_SCHEMA,
            "node_name": node_name,
            "agent_role": agent_role,
            "stage_mode": stage_mode,
            "context_scope": "SHARED_HEARING",
            "context_coverage": "FULL",
            "source_contract": "hearing_flow.v2",
            "source_authority_hash": source_hash,
        },
        "stage_contract": _stage_contract(node_name, agent_role, stage_mode),
        "authority_scope": flow,
    }
    _append_node_context(payload, node_name, source_copy, flow)
    payload["output_contract"] = _output_contract(node_name)
    return AssembledHearingRoomContextV3(
        node_name=node_name,
        agent_role=agent_role,
        stage_mode=stage_mode,
        payload=payload,
        source_authority_hash=source_hash,
    )


def _flow_authority(node_name: str, source: dict[str, Any]) -> dict[str, Any]:
    container_name = (
        "flow" if node_name == "hearing_evidence_file_assessment" else "request"
    )
    container = _mapping(source.get(container_name), f"{container_name} authority")
    flow = {
        "flow_schema_version": container.get("flow_schema_version"),
        "case_id": container.get("case_id"),
        "workflow_id": container.get("workflow_id"),
        "stage_code": _enum_value(container.get("stage_code")),
        "stage_sequence": container.get("stage_sequence"),
        "stage_deadline_at": container.get("stage_deadline_at"),
        "source_refs": deepcopy(container.get("source_refs", [])),
    }
    if (
        flow["flow_schema_version"] != "hearing_flow.v2"
        or not _non_empty_text(flow["case_id"])
        or not _non_empty_text(flow["workflow_id"])
        or not isinstance(flow["stage_sequence"], int)
        or isinstance(flow["stage_sequence"], bool)
        or flow["stage_sequence"] < 1
        or not isinstance(flow["source_refs"], list)
        or any(not _non_empty_text(value) for value in flow["source_refs"])
    ):
        raise HearingLcelContractError("HEARING_CONTEXT_AUTHORITY_INVALID")
    return flow


def _append_node_context(
    payload: dict[str, Any],
    node_name: str,
    source: dict[str, Any],
    flow: dict[str, Any],
) -> None:
    if node_name == "hearing_intake_questions":
        request = _mapping(source.get("request"), "intake questions request")
        matrix = _case_matrix(request, flow)
        max_questions = request.get("max_questions")
        if (
            not isinstance(max_questions, int)
            or isinstance(max_questions, bool)
            or max_questions < 1
            or max_questions > 5
        ):
            raise HearingLcelContractError("HEARING_CONTEXT_QUESTION_BUDGET_INVALID")
        payload["frozen_case_matrix"] = matrix
        payload["question_generation_policy"] = {
            "max_questions": max_questions,
            "target_roles": ["USER", "MERCHANT"],
            "fact_scope": "EXISTING_FACT_IDS_ONLY",
            "question_style": "SHARED_ISSUE_WITH_ROLE_PERSPECTIVES",
        }
        return

    if node_name == "hearing_intake_synthesis":
        request = _mapping(source.get("request"), "intake synthesis request")
        payload["frozen_case_matrix"] = _case_matrix(request, flow)
        payload["shared_issue_catalog"] = _list(
            source.get("intake_issues"), "intake issue catalog"
        )
        payload["party_statement_catalog"] = _list(
            source.get("party_statements"), "party statement catalog"
        )
        existing = _list(source.get("existing_fact_keys"), "existing fact key catalog")
        if len(existing) != len(set(existing)) or any(
            not isinstance(value, str) or not value.startswith("FACT_")
            for value in existing
        ):
            raise HearingLcelContractError("HEARING_CONTEXT_FACT_CATALOG_INVALID")
        payload["existing_fact_keys"] = existing
        return

    if node_name == "hearing_evidence_requests":
        request = _mapping(source.get("request"), "evidence requests request")
        case_matrix = _case_matrix(request, flow)
        dossier = _mapping(request.get("evidence_dossier"), "frozen evidence dossier")
        matrix = _mapping(dossier.get("fact_evidence_matrix"), "frozen evidence matrix")
        if (
            dossier.get("dossier_status") != "FROZEN"
            or matrix.get("matrix_status") != "FROZEN"
        ):
            raise HearingLcelContractError("HEARING_CONTEXT_EVIDENCE_NOT_FROZEN")
        (
            payload["m2_fact_catalog"],
            payload["fact_evidence_coverage_catalog"],
            payload["uncovered_fact_catalog"],
        ) = _evidence_request_fact_catalogs(case_matrix, matrix)
        return

    if node_name == "hearing_evidence_file_assessment":
        matrix = _mapping(source.get("case_fact_matrix"), "case fact matrix")
        _require_matrix_case(matrix, flow)
        evidence_file = _mapping(source.get("evidence_file"), "current evidence file")
        evidence_id = evidence_file.get("evidence_id")
        if not _non_empty_text(evidence_id):
            raise HearingLcelContractError("HEARING_CONTEXT_EVIDENCE_ID_INVALID")
        role = source.get("participant_role")
        batch_id = source.get("batch_id")
        if role not in {"USER", "MERCHANT"} or not _non_empty_text(batch_id):
            raise HearingLcelContractError("HEARING_CONTEXT_EVIDENCE_SCOPE_INVALID")
        requests = _list(source.get("requests"), "evidence request catalog")
        request_ids = _list(source.get("request_ids", []), "evidence request ids")
        if len(request_ids) != len(set(request_ids)) or any(
            not _non_empty_text(value) for value in request_ids
        ):
            raise HearingLcelContractError("HEARING_CONTEXT_REQUEST_SCOPE_INVALID")
        requests_by_id = {
            value.get("request_id"): value
            for value in requests
            if isinstance(value, dict) and _non_empty_text(value.get("request_id"))
        }
        if len(requests_by_id) != len(requests) or not set(request_ids).issubset(
            requests_by_id
        ):
            raise HearingLcelContractError("HEARING_CONTEXT_REQUEST_SCOPE_INVALID")
        payload["frozen_case_matrix"] = deepcopy(matrix)
        payload["prior_evidence_matrix"] = deepcopy(
            source.get("prior_fact_evidence_matrix")
        )
        payload["targeted_evidence_requests"] = [
            deepcopy(requests_by_id[request_id]) for request_id in request_ids
        ]
        payload["current_evidence_item"] = {
            "participant_role": role,
            "batch_id": batch_id,
            "evidence_file": deepcopy(evidence_file),
        }
        return

    request = _mapping(source.get("request"), "evidence synthesis request")
    payload["frozen_case_matrix"] = _case_matrix(request, flow)
    payload["targeted_evidence_requests"] = deepcopy(
        _list(request.get("requests", []), "evidence request catalog")
    )
    batches = _list(request.get("party_batches"), "party evidence batches")
    payload["party_evidence_batch_catalog"] = _batch_catalog(batches)
    payload["prior_evidence_matrix"] = deepcopy(
        request.get("prior_fact_evidence_matrix")
    )
    assessments = _list(
        source.get("evidence_assessments"), "evidence assessment catalog"
    )
    evidence_ids = [
        evidence.get("evidence_id")
        for batch in batches
        if isinstance(batch, dict)
        for evidence in batch.get("evidence", [])
        if isinstance(evidence, dict)
    ]
    assessment_ids = [
        value.get("evidence_id") for value in assessments if isinstance(value, dict)
    ]
    if (
        len(evidence_ids) != len(set(evidence_ids))
        or len(assessment_ids) != len(assessments)
        or sorted(evidence_ids) != sorted(assessment_ids)
    ):
        raise HearingLcelContractError("HEARING_CONTEXT_ASSESSMENT_BINDING_INVALID")
    payload["evidence_assessment_catalog"] = assessments
    payload["merged_evidence_matrix"] = deepcopy(
        _mapping(source.get("merged_fact_evidence_matrix"), "merged evidence matrix")
    )


def _stage_contract(node_name: str, agent_role: str, stage_mode: str) -> dict[str, Any]:
    return {
        "schema_version": "hearing_stage_context_contract.v3",
        "stage_mode": stage_mode,
        "agent_role": agent_role,
        "goal": _GOALS[node_name],
        "authority_rule": "USE_ONLY_THIS_ORDERED_CONTEXT",
        "public_text_rule": (
            "PUBLIC_MESSAGE_FIRST" if node_name in _PUBLIC_OUTPUT_ORDERS else "INTERNAL_ONLY"
        ),
    }


def _output_contract(node_name: str) -> dict[str, Any]:
    order = _PUBLIC_OUTPUT_ORDERS.get(
        node_name,
        ["fact_links", "summary", "requires_human_review"],
    )
    return {
        "schema_version": "hearing_model_output_contract.v3",
        "structured_output_only": True,
        "public_text_property": (
            "public_message" if node_name in _PUBLIC_OUTPUT_ORDERS else None
        ),
        "public_text_first": node_name in _PUBLIC_OUTPUT_ORDERS,
        "property_order": list(order),
        "server_owned_identifiers": True,
    }


def _case_matrix(request: dict[str, Any], flow: dict[str, Any]) -> dict[str, Any]:
    matrix = _mapping(request.get("case_fact_matrix"), "case fact matrix")
    _require_matrix_case(matrix, flow)
    return deepcopy(matrix)


def _evidence_request_fact_catalogs(
    case_matrix: dict[str, Any],
    evidence_matrix: dict[str, Any],
) -> tuple[dict[str, Any], list[dict[str, Any]], list[dict[str, Any]]]:
    eligible_facts: list[dict[str, Any]] = []
    for raw_fact in _list(case_matrix.get("fact_rows", []), "M2 fact catalog"):
        fact = _mapping(raw_fact, "M2 fact catalog item")
        if not _evidence_request_eligible(fact):
            continue
        fact.pop("evidence_coverage_status", None)
        eligible_facts.append(fact)
    coverage_by_fact: dict[str, dict[str, Any]] = {}
    for raw_coverage in _list(
        evidence_matrix.get("fact_coverage", []),
        "fact evidence coverage catalog",
    ):
        coverage = _mapping(raw_coverage, "fact evidence coverage item")
        fact_id = coverage.get("fact_id")
        coverage_by_fact[fact_id] = {
            "fact_id": fact_id,
            "evidence_ids": _list(
                coverage.get("evidence_ids", []),
                "fact evidence ids",
            ),
            "coverage_status": coverage.get("coverage_status"),
        }

    fact_coverage: list[dict[str, Any]] = []
    uncovered_facts: list[dict[str, Any]] = []
    for fact in eligible_facts:
        fact_id = fact.get("fact_id")
        coverage = coverage_by_fact.get(fact_id)
        if coverage is None:
            uncovered_facts.append(
                {
                    "fact_id": fact_id,
                    "uncovered_reason": "MISSING_FROM_FROZEN_E1",
                }
            )
            continue
        fact_coverage.append(coverage)
        if not _fact_has_reusable_evidence_coverage(coverage):
            uncovered_facts.append(
                {
                    "fact_id": fact_id,
                    "uncovered_reason": coverage["coverage_status"],
                }
            )

    return (
        {
            "matrix_id": case_matrix.get("matrix_id"),
            "matrix_version": case_matrix.get("matrix_version"),
            "content_hash": case_matrix.get("content_hash"),
            "fact_scope_rule": "REQUIRES_RESOLUTION_OR_HEARING_CLARIFICATION",
            "facts": eligible_facts,
        },
        fact_coverage,
        uncovered_facts,
    )


def _evidence_request_eligible(fact: dict[str, Any]) -> bool:
    origin = fact.get("origin")
    introduced_stage = (
        origin.get("introduced_stage") if isinstance(origin, Mapping) else None
    )
    return fact.get("requires_resolution") is True or introduced_stage == (
        "HEARING_CLARIFICATION"
    )


def _fact_has_reusable_evidence_coverage(coverage: dict[str, Any]) -> bool:
    return bool(coverage["evidence_ids"]) or coverage["coverage_status"] in {
        "COVERED_BY_SUBMITTED_EVIDENCE",
        "COVERED_BY_FROZEN_DOSSIER",
    }


def _require_matrix_case(matrix: dict[str, Any], flow: dict[str, Any]) -> None:
    if (
        matrix.get("schema_version") != "case_fact_matrix.v2"
        or matrix.get("case_id") != flow["case_id"]
    ):
        raise HearingLcelContractError("HEARING_CONTEXT_CASE_MATRIX_BINDING_INVALID")


def _batch_catalog(batches: list[Any]) -> list[dict[str, Any]]:
    catalog: list[dict[str, Any]] = []
    roles: list[str] = []
    for raw_batch in batches:
        batch = _mapping(raw_batch, "party evidence batch")
        role = batch.get("participant_role")
        if role not in {"USER", "MERCHANT"}:
            raise HearingLcelContractError("HEARING_CONTEXT_PARTY_BATCH_INVALID")
        roles.append(role)
        projected = deepcopy(batch)
        evidence_items = _list(projected.get("evidence", []), "party batch evidence")
        projected["evidence"] = []
        for raw_evidence in evidence_items:
            evidence = _mapping(raw_evidence, "party batch evidence item")
            evidence.pop("parsed_text", None)
            projected["evidence"].append(evidence)
        catalog.append(projected)
    if sorted(roles) != ["MERCHANT", "USER"]:
        raise HearingLcelContractError("HEARING_CONTEXT_PARTY_BATCH_INVALID")
    return catalog


def _mapping(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, Mapping):
        raise HearingLcelContractError(
            "HEARING_CONTEXT_SOURCE_INVALID", f"{label} must be an object"
        )
    return deepcopy(dict(value))


def _list(value: Any, label: str) -> list[Any]:
    if not isinstance(value, list):
        raise HearingLcelContractError(
            "HEARING_CONTEXT_SOURCE_INVALID", f"{label} must be an array"
        )
    return deepcopy(value)


def _canonical_hash(value: dict[str, Any]) -> str:
    try:
        return canonical_sha256(value)
    except (TypeError, ValueError) as error:
        raise HearingLcelContractError("HEARING_CONTEXT_SOURCE_INVALID") from error


def _enum_value(value: Any) -> Any:
    return getattr(value, "value", value)


def _non_empty_text(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


__all__ = [
    "AssembledHearingRoomContextV3",
    "HEARING_ROOM_CONTEXT_V3_NODES",
    "HEARING_ROOM_CONTEXT_V3_SCHEMA",
    "assemble_hearing_room_context_v3",
]
