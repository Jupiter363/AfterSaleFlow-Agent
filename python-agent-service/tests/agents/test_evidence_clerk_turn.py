# 文件作用：自动化测试文件，验证 test_evidence_clerk_turn 相关模块的行为、契约或页面布局。

from __future__ import annotations

import json
import hashlib
import base64
from pathlib import Path
from types import SimpleNamespace

import pytest
import httpx
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.config import Settings
from app.harness.prompt_composer import PromptRepository
from app.main import create_app


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：模块私有业务函数。
# 具体功能：`_settings` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`Settings`。
# 上下游：上游为 本文件的 `test_evidence_turn_api_requires_service_secret_and_fails_without_model`、`test_evidence_turn_api_rejects_legacy_and_mixed_transport_contracts`、`test_evidence_turn_endpoint_accepts_java_command_payload_without_degrading`；下游为 协作调用 `Settings`。
# 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
def _settings() -> Settings:
    return Settings(
        litellm_master_key="test-litellm-master-key",
        langfuse_public_key="pk-test-key",
        langfuse_secret_key="sk-test-secret",
        java_service_secret="test-java-service-secret",
        python_agent_service_secret="test-agent-service-secret",
        langfuse_enabled=False,
    )


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：模块私有业务函数。
# 具体功能：`_headers` 围绕本阶段状态计算该函数独立负责的业务派生值；返回/更新字段：`X-Service-Secret`。
# 上下游：上游为 本文件的 `test_evidence_turn_api_requires_service_secret_and_fails_without_model`、`test_evidence_turn_api_rejects_legacy_and_mixed_transport_contracts`、`test_evidence_turn_endpoint_accepts_java_command_payload_without_degrading`；下游为 返回/更新 `X-Service-Secret`。
# 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
def _headers() -> dict[str, str]:
    return {"X-Service-Secret": "test-agent-service-secret"}


def _unilateral_case_matrix(
    *facts: tuple[str, str, str],
) -> dict[str, object]:
    rows = [
        {
            "fact_id": fact_id,
            "category": "LOGISTICS",
            "fact_target": fact,
            "materiality": materiality,
            "origin": {
                "source_stage": "INTAKE",
                "source_refs": ["INTAKE_FORM_CASE_evidence_turn_llm"],
            },
            "initiator_position": {
                "stance": "CONFIRM",
                "position_summary": f"发起方主张：{fact}",
                "asserted_value": fact,
                "source_refs": ["INTAKE_FORM_CASE_evidence_turn_llm"],
            },
            "truth_status": "NOT_EVALUATED",
        }
        for fact_id, fact, materiality in facts
    ]
    return {
        "schema_version": "unilateral_case_matrix.v1",
        "matrix_version": 2,
        "content_hash": "a" * 64,
        "source_binding": {
            "case_id": "CASE_evidence_turn_llm",
            "source_stage": "INTAKE",
            "source_refs": ["INTAKE_FORM_CASE_evidence_turn_llm"],
            "latest_source_ref": "INTAKE_FORM_CASE_evidence_turn_llm",
            "source_context_hash": "b" * 64,
        },
        "party_map": {"initiator_role": "USER", "respondent_role": "MERCHANT"},
        "case_summary": "物流显示签收，但用户称未收到包裹。",
        "summary_source_fact_ids": [row["fact_id"] for row in rows],
        "claim_resolution": {
            "initiator_role": "USER",
            "requested_resolution": "REFUND",
            "requested_amount": 299.0,
            "requested_items": "蓝牙耳机 1 件",
            "reason_summary": "未实际收到商品。",
            "position_summary": "用户要求核验签收交接并退款。",
            "source_refs": ["INTAKE_FORM_CASE_evidence_turn_llm"],
        },
        "reported_respondent_attitude": None,
        "dispute_core_state": {
            "core_conflict": "物流签收状态与实际收货情况不一致。",
            "facts_in_dispute": [fact for _, fact, _ in facts],
            "next_verification_focus": ["核验签收人和投递位置"],
        },
        "fact_rows": rows,
    }


def _case_fact_matrix_v2(
    case_id: str = "CASE_evidence_turn_llm",
) -> dict[str, object]:
    from app.schemas import CaseFactMatrixV2

    source_user = "MESSAGE_USER_INTAKE"
    source_merchant = "MESSAGE_MERCHANT_INTAKE"
    fact_id = "FACT_SIGNATURE"
    payload = {
        "schema_version": "case_fact_matrix.v2",
        "case_id": case_id,
        "matrix_id": "CASE_MATRIX_EVIDENCE_V2",
        "matrix_version": 2,
        "matrix_kind": "BILATERAL_FROZEN",
        "parent_ref": None,
        "content_hash": "0" * 64,
        "party_map": {
            "initiator_role": "USER",
            "respondent_role": "MERCHANT",
        },
        "source_refs": [source_user, source_merchant],
        "case_overview": {
            "neutral_summary": "双方对包裹是否由用户本人签收存在争议。",
            "core_conflict": "包裹是否由用户本人签收。",
            "summary_source_fact_ids": [fact_id],
        },
        "claims": {
            "initiator_claim": {
                "initiator_role": "USER",
                "requested_resolution": "REFUND",
                "requested_amount": 299,
                "requested_items": "蓝牙耳机",
                "reason_summary": "用户称未收到包裹。",
                "position_summary": "用户请求退款。",
                "source_refs": [source_user],
            },
            "respondent_reported_by_initiator": None,
            "respondent_direct": {
                "respondent_role": "MERCHANT",
                "attitude": "DISAGREE",
                "position_summary": "商家称物流记录显示包裹已签收。",
                "alternative_proposal": None,
                "source_type": "RESPONDENT_DIRECT_INTAKE",
                "source_refs": [source_merchant],
            },
            "claim_conflict": "双方对签收事实存在争议。",
        },
        "fact_rows": [
            {
                "fact_id": fact_id,
                "category": "LOGISTICS",
                "fact_target": "包裹是否由用户本人签收",
                "materiality": "CORE",
                "origin": {
                    "introduced_stage": "INITIATOR_INTAKE",
                    "source_refs": [source_user],
                },
                "positions": {
                    "USER": {
                        "stance": "DENY",
                        "position_summary": "用户否认本人或同住人员签收。",
                        "asserted_value": "未签收",
                        "source_type": "DIRECT_PARTY_STATEMENT",
                        "source_refs": [source_user],
                    },
                    "MERCHANT": {
                        "stance": "CONFIRM",
                        "position_summary": "商家称物流记录显示已签收。",
                        "asserted_value": "已签收",
                        "source_type": "DIRECT_PARTY_STATEMENT",
                        "source_refs": [source_merchant],
                    },
                },
                "party_alignment": {
                    "status": "CONTESTED",
                    "agreed_statement": None,
                    "conflict_summary": "双方对是否签收存在直接分歧。",
                },
                "requires_resolution": True,
                "truth_status": "NOT_EVALUATED",
            }
        ],
        "fact_relationships": [],
        "generation_ref": {
            "actor_role": "MERCHANT",
            "source_stage": "RESPONDENT_INTAKE",
            "latest_source_ref": source_merchant,
            "source_context_hash": "b" * 64,
        },
        "fact_indexes": {
            "not_computed_fact_ids": [],
            "agreed_fact_ids": [],
            "partially_agreed_fact_ids": [],
            "contested_fact_ids": [fact_id],
            "one_sided_fact_ids": [],
            "unresolved_fact_ids": [],
            "core_fact_ids": [fact_id],
            "requires_resolution_fact_ids": [fact_id],
        },
    }
    provisional = CaseFactMatrixV2.model_validate(payload).model_dump(mode="json")
    provisional.pop("content_hash")
    payload["content_hash"] = hashlib.sha256(
        json.dumps(
            provisional,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()
    return CaseFactMatrixV2.model_validate(payload).model_dump(mode="json")


def _rehash_case_fact_matrix(matrix: dict[str, object]) -> None:
    material = dict(matrix)
    material.pop("content_hash", None)
    matrix["content_hash"] = hashlib.sha256(
        json.dumps(
            material,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：模块私有业务函数。
# 具体功能：`_agent_context` 围绕案件与会话上下文计算该函数独立负责的业务派生值；返回/更新字段：`tenant_id`、`case_id`、`room_type`、`actor_id`。
# 上下游：上游为 本文件的 `_evidence_turn_payload`；下游为 返回/更新 `tenant_id`、`case_id`、`room_type`、`actor_id`。
# 系统意义：控制隐私、Token 和会话隔离：服从角色权限、上下文范围和非最终结论边界。
def _agent_context(
    case_id: str,
    *,
    actor_id: str = "USER_local_1",
    actor_role: str = "USER",
) -> dict[str, object]:
    access_session_id = f"ACCESS_{case_id}_{actor_role}"
    prompt_profile_id = f"EVIDENCE_CLERK:{actor_role}:v1"
    agent_session_id = f"SESSION_{case_id}_{actor_role}_evidence"
    return {
        "tenant_id": "default",
        "case_id": case_id,
        "room_type": "EVIDENCE",
        "actor_id": actor_id,
        "actor_role": actor_role,
        "access_session_id": access_session_id,
        "permission_level": "PARTY_USER" if actor_role == "USER" else "PARTY_MERCHANT",
        "permission_scopes": [],
        "agent_key": "EVIDENCE_CLERK",
        "agent_invocation_id": f"INVOCATION_{case_id}",
        "agent_session_id": agent_session_id,
        "conversation_scope": (
            f"default:{case_id}:EVIDENCE:{actor_id}:{actor_role}:"
            f"EVIDENCE_CLERK:{prompt_profile_id}:{access_session_id}"
        ),
        "scope_type": "EVIDENCE_PARTY_PRIVATE",
        "allowed_actor_ids": [actor_id],
        "allowed_actor_roles": [actor_role],
        "prompt_profile_id": prompt_profile_id,
        "memory_policy_id": "MEMORY_POLICY_EVIDENCE_V1",
    }


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：模块私有业务函数。
# 具体功能：`_evidence_turn_payload` 读取并按案件、角色或会话范围筛选当前可见证据；返回/更新字段：`context_envelope`、`agent_context`。
# 上下游：上游为 本文件的 `_java_evidence_turn_command_payload`、`test_evidence_turn_workflow_uses_harness_node_with_memory_dossier_and_evidence_context`、`legacy_evidence_turn_fallback_asks_authenticity_and_relevance_questions_without_deciding`、`legacy_evidence_fallback_bounds_deterministic_output_above_one_hundred_items`；下游为 本文件的 `_agent_context`、`_visible_signature_evidence`。
# 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
def _evidence_turn_payload() -> dict[str, object]:
    case_id = "CASE_evidence_turn_llm"
    agent_context = _agent_context(case_id)
    dossier = {
        "schema_version": "intake_case_detail.v1",
        "case_story": {
            "one_sentence_summary": "物流显示签收，但用户称未收到包裹。"
        },
        "dispute_focus": {
            "core_issue": "SIGNED_NOT_RECEIVED",
            "facts_to_verify": ["签收底单", "物流轨迹", "收件人身份"],
        },
        "unilateral_case_matrix": _unilateral_case_matrix(
            ("FACT_SIGNATURE", "物流系统记录包裹已签收", "CORE"),
            ("FACT_LOGISTICS", "物流轨迹需要核验", "SUPPORTING"),
            ("FACT_RECIPIENT", "收件人称本人及同住人员未收到包裹", "CORE"),
        ),
    }


    return {
        "context_envelope": {
            "schema_version": "evidence_context_envelope.v1",
            "captured_at": "2026-07-11T10:00:00+08:00",
            "case_snapshot": {
                "case_id": case_id,
                "case_version": 7,
                "case_status": "EVIDENCE_IN_PROGRESS",
                "case_type": "AFTER_SALE_DISPUTE",
                "dispute_type": "SIGNED_NOT_RECEIVED",
                "title": "签收未收到争议",
                "description": "物流显示签收，但用户称未收到包裹。",
                "risk_level": "MEDIUM",
                "route_type": "NORMAL_HEARING",
                "order_id": "ORDER_evidence_turn",
                "after_sale_id": None,
                "logistics_id": "LOGISTICS_evidence_turn",
                "source_type": "LOCAL",
                "initiator_role": "USER",
                "source_system": None,
                "external_case_ref": None,
                "current_room": "EVIDENCE",
                "current_deadline_at": "2026-07-11T10:05:00+08:00",
            },
            "intake_dossier_snapshot": {
                "dossier_id": "DOSSIER_evidence_turn",
                "schema_version": "intake_case_detail.v1",
                "dossier_version": 2,
                "source_turn_no": 3,
                "quality_score": 84,
                "ready_for_next_step": True,
                "admission_recommendation": "ACCEPTED",
                "updated_at": "2026-07-11T09:55:00+08:00",
                "payload": dossier,
            },
            "actor_snapshot": {
                "actor_id": "USER_local_1",
                "actor_role": "USER",
                "initiator_role": "USER",
                "access_session_id": agent_context["access_session_id"],
                "agent_session_id": agent_context["agent_session_id"],
                "conversation_scope": agent_context["conversation_scope"],
                "prompt_profile_id": agent_context["prompt_profile_id"],
                "memory_policy_id": agent_context["memory_policy_id"],
            },
            "current_event": {
                "event_id": "MESSAGE_evidence_turn",
                "event_type": "PARTY_MESSAGE",
                "message_type": "PARTY_TEXT",
                "actor_id": "USER_local_1",
                "actor_role": "USER",
                "text": "我上传了签收页截图，但图片有点糊，想证明我没有收到包裹。",
                "attachment_refs": [],
                "turn_no": 2,
                "occurred_at": "2026-07-11T10:00:00+08:00",
            },
            "visible_evidence": [_visible_signature_evidence()],
            "private_conversation": {
                "agent_session_id": agent_context["agent_session_id"],
                "conversation_scope": agent_context["conversation_scope"],
                "source_count": 1,
                "truncated": False,
                "recent_turns": [
                    {
                        "turn_no": 1,
                        "actor_id": "USER_local_1",
                        "answer_role": "USER",
                        "answer_content": (
                            "user evidence memory asks about signature proof"
                        ),
                        "agent_role": None,
                        "agent_response": None,
                        "scroll_snapshot": {},
                        "agent_session_id": agent_context["agent_session_id"],
                        "conversation_scope": agent_context["conversation_scope"],
                    }
                ],
            },
            "room_policy": {
                "room_id": "ROOM_evidence_turn",
                "room_type": "EVIDENCE",
                "room_status": "OPEN",
                "current_deadline_at": "2026-07-11T10:05:00+08:00",
                "initiator_role": "USER",
                "initiator_evidence_required": True,
            },
        },
        "agent_context": agent_context,
    }


def _switch_evidence_actor(
    payload: dict[str, object],
    *,
    actor_id: str,
    actor_role: str,
) -> None:
    envelope = payload["context_envelope"]
    case_id = envelope["case_snapshot"]["case_id"]
    context = _agent_context(case_id, actor_id=actor_id, actor_role=actor_role)
    payload["agent_context"] = context
    envelope["actor_snapshot"].update(
        {
            "actor_id": actor_id,
            "actor_role": actor_role,
            "access_session_id": context["access_session_id"],
            "agent_session_id": context["agent_session_id"],
            "conversation_scope": context["conversation_scope"],
            "prompt_profile_id": context["prompt_profile_id"],
            "memory_policy_id": context["memory_policy_id"],
        }
    )
    envelope["current_event"].update(
        {"actor_id": actor_id, "actor_role": actor_role}
    )
    for item in envelope["visible_evidence"]:
        item["submitted_by_id"] = actor_id
        item["submitted_by_role"] = actor_role
    conversation = envelope["private_conversation"]
    conversation["agent_session_id"] = context["agent_session_id"]
    conversation["conversation_scope"] = context["conversation_scope"]
    for turn in conversation["recent_turns"]:
        turn["actor_id"] = actor_id
        turn["answer_role"] = actor_role
        turn["agent_session_id"] = context["agent_session_id"]
        turn["conversation_scope"] = context["conversation_scope"]


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：模块私有业务函数。
# 具体功能：`_visible_signature_evidence` 围绕当前可见证据计算该函数独立负责的业务派生值；返回/更新字段：`evidence_id`、`dossier_id`、`evidence_type`、`source_type`。
# 上下游：上游为 本文件的 `_evidence_turn_payload`、`test_evidence_context_assembler_prioritizes_current_attachments_and_budgets`、`legacy_evidence_fallback_bounds_deterministic_output_above_one_hundred_items`；下游为 返回/更新 `evidence_id`、`dossier_id`、`evidence_type`、`source_type`。
# 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
def _visible_signature_evidence() -> dict[str, object]:
    return {
        "evidence_id": "EVIDENCE_signature_photo",
        "dossier_id": "DOSSIER_evidence_turn",
        "evidence_type": "IMAGE",
        "source_type": "USER",
        "submitted_by_role": "USER",
        "submitted_by_id": "USER_local_1",
        "original_filename": "signature-page.jpg",
        "content_type": "image/jpeg",
        "file_size": 1024,
        "file_hash": "sha256-signature-photo",
        "parsed_text": "签收时间 2026-07-01 10:30，签收人字段不清晰。",
        "parse_status": "SUCCEEDED",
        "visibility": "PARTIES",
        "desensitized": False,
        "metadata": {},
        "extraction": {},
        "occurred_at": "2026-07-01T10:30:00+08:00",
        "created_at": "2026-07-11T09:58:00+08:00",
        "submitted_at": "2026-07-11T09:59:00+08:00",
        "submission_status": "SUBMITTED",
        "submission_batch_id": "BATCH_signature",
        "content_url": (
            "/api/disputes/CASE_evidence_turn_llm/evidence/"
            "EVIDENCE_signature_photo/content"
        ),
    }


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：模块私有业务函数。
# 具体功能：`_java_evidence_turn_command_payload` 读取并按案件、角色或会话范围筛选当前可见证据；关键协作调用：`event.update`。
# 上下游：上游为 本文件的 `_java_evidence_opening_command_payload`、`test_evidence_turn_workflow_places_asset_manifest_in_context_pack`、`test_evidence_context_assembler_preserves_null_parsed_text_and_adds_notice`、`test_evidence_context_assembler_prioritizes_current_attachments_and_budgets`；下游为 本文件的 `_evidence_turn_payload`。
# 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
def _java_evidence_turn_command_payload() -> dict[str, object]:
    payload = _evidence_turn_payload()
    event = payload["context_envelope"]["current_event"]
    event.update(
        {
            "event_id": "MESSAGE_evidence_turn_2",
            "message_type": "PARTY_EVIDENCE_REFERENCE",
            "text": "",
            "attachment_refs": ["EVIDENCE_signature_photo"],
        }
    )
    return payload


def _hearing_batch_evidence_turn_payload() -> dict[str, object]:
    payload = _java_evidence_turn_command_payload()
    envelope = payload["context_envelope"]
    context = payload["agent_context"]

    second_evidence = _visible_signature_evidence()
    second_evidence.update(
        {
            "evidence_id": "EVIDENCE_logistics_record",
            "evidence_type": "DOCUMENT",
            "original_filename": "logistics-record.pdf",
            "content_type": "application/pdf",
            "file_hash": "sha256-logistics-record",
            "parsed_text": "物流轨迹显示包裹于 2026-07-01 投递至驿站。",
            "occurred_at": "2026-07-01T10:25:00+08:00",
            "created_at": "2026-07-11T09:59:00+08:00",
            "submitted_at": "2026-07-11T10:00:00+08:00",
            "submission_batch_id": "BATCH_hearing_supplement",
            "content_url": (
                "/api/disputes/CASE_evidence_turn_llm/evidence/"
                "EVIDENCE_logistics_record/content"
            ),
        }
    )
    envelope["visible_evidence"].append(second_evidence)
    envelope["current_event"]["attachment_refs"] = [
        "EVIDENCE_signature_photo",
        "EVIDENCE_logistics_record",
    ]
    envelope["current_event"]["text"] = "补充提交签收截图和物流记录。"
    envelope["case_snapshot"]["current_room"] = "HEARING"
    envelope["room_policy"]["room_type"] = "HEARING"
    envelope["room_policy"]["room_id"] = "ROOM_evidence_hearing"

    old_scope = context["conversation_scope"]
    hearing_scope = old_scope.replace(":EVIDENCE:", ":HEARING:")
    context["room_type"] = "HEARING"
    context["conversation_scope"] = hearing_scope
    envelope["actor_snapshot"]["conversation_scope"] = hearing_scope
    envelope["private_conversation"]["conversation_scope"] = hearing_scope
    for turn in envelope["private_conversation"]["recent_turns"]:
        turn["conversation_scope"] = hearing_scope
    return payload


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：模块私有业务函数。
# 具体功能：`_java_evidence_opening_command_payload` 读取并按案件、角色或会话范围筛选当前可见证据。
# 上下游：上游为 本文件的 `test_evidence_opening_turn_passes_source_to_llm_context`、`legacy_evidence_opening_fallback_asks_dossier_specific_evidence_questions`、`legacy_evidence_opening_fallback_states_initiator_evidence_gate_without_deciding`、`legacy_evidence_opening_fallback_localizes_internal_dispute_codes`；下游为 本文件的 `_java_evidence_turn_command_payload`。
# 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
def _java_evidence_opening_command_payload() -> dict[str, object]:
    payload = _java_evidence_turn_command_payload()
    envelope = payload["context_envelope"]
    envelope["current_event"] = {
        "event_id": "EVIDENCE_OPENING_1",
        "event_type": "ROOM_OPENING",
        "message_type": "AGENT_MESSAGE",
        "actor_id": "USER_local_1",
        "actor_role": "USER",
        "text": None,
        "attachment_refs": [],
        "turn_no": 1,
        "occurred_at": "2026-07-11T09:57:00+08:00",
    }
    envelope["intake_dossier_snapshot"]["payload"] = {
        "schema_version": "intake_case_detail.v1",
        "case_story": {
            "one_sentence_summary": (
                "The merchant says the watch was intact before shipment; "
                "the user says the dial was scratched after receipt."
            )
        },
        "dispute_focus": {
            "core_issue": "SCRATCHED_WATCH_AFTER_DELIVERY",
            "facts_to_verify": [
                "factory QC video",
                "unboxing photo original file",
                "logistics handling record",
            ],
        },
        "unilateral_case_matrix": _unilateral_case_matrix(
            ("FACT_QC_VIDEO", "商家称发货前质检画面显示商品完好", "SUPPORTING"),
            ("FACT_UNBOXING", "用户称拆箱后发现表盘划痕", "CORE"),
            ("FACT_HANDLING", "物流运输环节是否造成划痕仍待核验", "CORE"),
        ),
    }
    envelope["visible_evidence"] = []
    envelope["private_conversation"]["source_count"] = 0
    envelope["private_conversation"]["recent_turns"] = []
    return payload


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：模块私有业务函数。
# 具体功能：`_empty_internal_handoff` 围绕本阶段状态计算该函数独立负责的业务派生值；返回/更新字段：`evidence_change_summary`、`matrix_change_summary`、`remaining_conflicts`、`uncovered_fact_ids`。
# 上下游：上游为 本文件的 `FakeEvidenceRunner.invoke_structured`、`CapturingMultimodalRunner.invoke_structured`、`GenericOpeningRunner.invoke_structured`、`MaximalOpeningRunner.invoke_structured`；下游为 返回/更新 `evidence_change_summary`、`matrix_change_summary`、`remaining_conflicts`、`uncovered_fact_ids`。
# 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
def _empty_internal_handoff() -> dict[str, object]:
    return {
        "evidence_change_summary": "本轮没有新增证据评估。",
        "matrix_change_summary": "证据矩阵保持不变。",
        "remaining_conflicts": [],
        "uncovered_fact_ids": [],
        "human_review_evidence_ids": [],
        "judge_attention_points": [],
    }


class FakeEvidenceRunner:
    # 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 符合 Schema 的角色分析结果。
    # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
    def __init__(self) -> None:
        self.calls: list[dict[str, object]] = []

    # 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：类/闭包内部方法。
    # 具体功能：`invoke_structured` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`self.calls.append`、`SimpleNamespace`、`context_pack.prompt_sections`。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_empty_internal_handoff`。
    # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
    def invoke_structured(
        self,
        *,
        node_name,
        case_data,
        output_type,
        context_sections=None,
        context_pack=None,
        agent_context=None,
        prompt_profile_id=None,
    ):
        resolved_sections = (
            context_pack.prompt_sections() if context_pack is not None else context_sections
        )
        self.calls.append(
            {
                "node_name": node_name,
                "case_data": case_data,
                "output_type": output_type,
                "context_sections": resolved_sections,
                "context_pack": context_pack,
                "agent_context": agent_context,
                "prompt_profile_id": prompt_profile_id,
            }
        )
        return SimpleNamespace(
            value=output_type(
                room_utterance=(
                    "我会先核验这份截图的来源、可读性和与签收争议的关联性；"
                    "本轮只核验证据，不裁决责任。"
                ),
                evidence_requests=[
                    {
                        "question_id": "QUESTION_signature_source",
                        "target_evidence_id": "EVIDENCE_signature_photo",
                        "question": "请补充截图的原始来源、截取时间，以及是否能提供更清晰的原图。",
                        "reason": "需要确认来源、时间戳和 OCR 可读性。",
                    }
                ],
                verification_suggestions=[
                    {
                        "evidence_id": "EVIDENCE_signature_photo",
                        "suggestion": "核对原图来源、上传前后是否裁剪，以及签收时间是否与物流轨迹一致。",
                        "confidence_score": 0.64,
                    }
                ],
                authenticity_flags=[
                    {
                        "evidence_id": "EVIDENCE_signature_photo",
                        "flag_type": "OCR_READABILITY",
                        "description": "OCR 提示部分文字不可读，需要更清晰原图。",
                        "severity": "MEDIUM",
                    }
                ],
                internal_handoff=_empty_internal_handoff(),
                confidence=0.64,
            )
        )


class CapturingMultimodalRunner:
    # 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 符合 Schema 的角色分析结果。
    # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
    def __init__(self) -> None:
        self.calls: list[dict[str, object]] = []

    # 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：类/闭包内部方法。
    # 具体功能：`invoke_structured` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`self.calls.append`、`SimpleNamespace`、`output_type`。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_empty_internal_handoff`。
    # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
    def invoke_structured(self, **kwargs):
        self.calls.append(kwargs)
        output_type = kwargs["output_type"]
        return SimpleNamespace(
            value=output_type(
                room_utterance="已结合原图、OCR 和文件元数据完成本轮证据核验。",
                evidence_assessments=[
                    {
                        "evidence_id": "EVIDENCE_signature_photo",
                        "analysis_method": "HYBRID",
                        "inspected_modalities": [
                            "OCR_TEXT",
                            "IMAGE_PIXELS",
                            "FILE_METADATA",
                        ],
                        "authenticity_score": 0.82,
                        "relevance_score": 0.91,
                        "completeness_score": 0.76,
                        "assessment_confidence": 0.8,
                        "source_basis": ["原始图片、OCR 文本和文件元数据。"],
                        "fact_links": [
                            {
                                "fact_id": "FACT_SIGNATURE",
                                "relation": "INCONCLUSIVE",
                                "reason": "图片内容与物流签收记录相关，但不能单独确认收件人身份。",
                                "confidence": 0.72,
                            }
                        ],
                        "supported_fact_ids": [],
                        "unsupported_claims": ["不能仅凭该图片确认实际收件人身份。"],
                        "formation_time_assessment": "图片中的时间信息仍需与平台记录核对。",
                        "recommendation": "PLAUSIBLE",
                        "summary": "图片可读，内容与签收争议相关。",
                    }
                ],
                internal_handoff=_empty_internal_handoff(),
                confidence=0.8,
            )
        )


class GenericOpeningRunner:
    # 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：类/闭包内部方法。
    # 具体功能：`invoke_structured` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`SimpleNamespace`、`output_type`。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_empty_internal_handoff`。
    # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
    def invoke_structured(
        self,
        *,
        node_name,
        case_data,
        output_type,
        context_sections=None,
        context_pack=None,
        agent_context=None,
        prompt_profile_id=None,
    ):
        return SimpleNamespace(
            value=output_type(
                room_utterance="您好！我是您的证据书记官，请上传与本案相关的证据材料。",
                evidence_requests=[],
                verification_suggestions=[],
                authenticity_flags=[],
                internal_handoff=_empty_internal_handoff(),
                confidence=0.31,
            )
        )


class MaximalOpeningRunner:
    # 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：类/闭包内部方法。
    # 具体功能：`invoke_structured` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`SimpleNamespace`、`output_type`。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_empty_internal_handoff`。
    # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
    def invoke_structured(
        self,
        *,
        node_name,
        case_data,
        output_type,
        context_sections=None,
        context_pack=None,
        agent_context=None,
        prompt_profile_id=None,
    ):
        return SimpleNamespace(
            value=output_type(
                room_utterance="请补充证据材料。",
                evidence_requests=[
                    {
                        "question_id": f"QUESTION_MAX_{index}",
                        "target_evidence_id": None,
                        "question": f"第 {index} 项证据问题。",
                        "reason": f"第 {index} 项核验原因。",
                    }
                    for index in range(30)
                ],
                verification_suggestions=[],
                authenticity_flags=[
                    {
                        "evidence_id": None,
                        "flag_type": f"FLAG_MAX_{index}",
                        "description": f"第 {index} 项风险提示。",
                        "severity": "LOW",
                    }
                    for index in range(100)
                ],
                internal_handoff=_empty_internal_handoff(),
                confidence=0.8,
            )
        )


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：回归测试用例。
# 具体功能：`test_evidence_turn_workflow_uses_harness_node_with_memory_dossier_and_evidence_context` 验证案件卷宗在固定案例中的输出、边界和失败行为；关键协作调用：`FakeEvidenceRunner`、`EvidenceTurnWorkflow`、`workflow.run`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_evidence_turn_payload`。
# 系统意义：固定“Agent 角色能力 > test_evidence_clerk_turn”的可观察契约，防止后续重构改变业务结果。
def test_evidence_turn_workflow_uses_harness_node_with_memory_dossier_and_evidence_context() -> None:
    from app.agents.evidence_clerk.workflow import EvidenceTurnWorkflow
    from app.schemas import EvidenceTurnRequest

    runner = FakeEvidenceRunner()
    workflow = EvidenceTurnWorkflow(model_runner=runner)

    result = workflow.run(EvidenceTurnRequest.model_validate(_evidence_turn_payload()))

    assert runner.calls[0]["node_name"] == "evidence_turn"
    assert runner.calls[0]["output_type"].__name__ == "EvidenceTurnLlmOutput"
    context_pack = runner.calls[0]["context_pack"]
    assert context_pack.configuration_profile_key == "EVIDENCE_CLERK_CONTEXT_PACK_V2"
    section_by_name = {
        section.name: section.content
        for section in runner.calls[0]["context_sections"]  # type: ignore[index]
    }
    assert {
        "current_turn",
        "private_conversation_window",
        "canonical_case_dossier",
        "party_visible_evidence_catalog",
    } <= set(section_by_name)
    private_window = json.loads(section_by_name["private_conversation_window"])
    assert (
        "user evidence memory asks about signature proof"
        in private_window["turns"][0]["answer_content"]
    )
    current_turn = json.loads(section_by_name["current_turn"])
    assert current_turn["raw_statement"] == (
        "我上传了签收页截图，但图片有点糊，想证明我没有收到包裹。"
    )
    assert current_turn["platform_statement"].startswith("用户称")
    assert "我上传" not in current_turn["platform_statement"]
    assert "物流显示签收但用户称未收到包裹" in section_by_name["canonical_case_dossier"]
    assert "SIGNED_NOT_RECEIVED" not in section_by_name["canonical_case_dossier"]
    actor_visible_evidence = json.loads(
        section_by_name["party_visible_evidence_catalog"]
    )
    assert actor_visible_evidence["items"][0]["evidence_id"] == (
        "EVIDENCE_signature_photo"
    )
    assert actor_visible_evidence["items"][0]["source_type"] == "USER"
    assert runner.calls[0]["agent_context"]["agent_session_id"] == (
        "SESSION_CASE_evidence_turn_llm_USER_evidence"
    )
    assert runner.calls[0]["prompt_profile_id"] == "EVIDENCE_CLERK:USER:v1"
    assert result.non_final is True
    assert result.liability_determined is False
    assert result.remedy_recommended is False
    assert result.evidence_requests[0].target_evidence_id == "EVIDENCE_signature_photo"
    assert result.verification_suggestions[0].evidence_id == "EVIDENCE_signature_photo"
    assert 0 <= result.verification_suggestions[0].confidence_score <= 1


def test_evidence_turn_workflow_processes_one_complete_hearing_evidence_batch() -> None:
    from app.agents.evidence_clerk.workflow import EvidenceTurnWorkflow
    from app.schemas import EvidenceTurnRequest

    runner = FakeEvidenceRunner()
    request = EvidenceTurnRequest.model_validate(
        _hearing_batch_evidence_turn_payload()
    )

    result = EvidenceTurnWorkflow(model_runner=runner).run(request)

    assert len(runner.calls) == 1
    sections = {
        section.name: json.loads(section.content)
        for section in runner.calls[0]["context_pack"].prompt_sections()
        if section.name in {"current_turn", "party_visible_evidence_catalog"}
    }
    expected_ids = [
        "EVIDENCE_signature_photo",
        "EVIDENCE_logistics_record",
    ]
    assert runner.calls[0]["case_data"]["room_type"] == "HEARING"
    assert sections["current_turn"]["attachment_refs"] == expected_ids
    assert [
        item["evidence_id"]
        for item in sections["party_visible_evidence_catalog"]["items"][:2]
    ] == expected_ids
    assert result.referenced_evidence_ids == expected_ids
    assert [item.evidence_id for item in result.evidence_assessments] == expected_ids


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：回归测试用例。
# 具体功能：`test_evidence_turn_workflow_places_asset_manifest_in_context_pack` 验证当前可见证据在固定案例中的输出、边界和失败行为；关键协作调用：`base64.b64decode`、`hexdigest`、`CapturingMultimodalRunner`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_java_evidence_turn_command_payload`。
# 系统意义：固定“Agent 角色能力 > test_evidence_clerk_turn”的可观察契约，防止后续重构改变业务结果。
def test_evidence_turn_workflow_places_asset_manifest_in_context_pack() -> None:
    from app.agents.evidence_clerk.workflow import EvidenceTurnWorkflow
    from app.harness.evidence_asset_loader import EvidenceAssetLoader
    from app.schemas import EvidenceTurnRequest

    image = base64.b64decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    )
    payload = _java_evidence_turn_command_payload()
    payload["context_envelope"]["current_event"]["attachment_refs"] = [
        "EVIDENCE_signature_photo"
    ]
    evidence = payload["context_envelope"]["visible_evidence"][0]
    evidence["content_type"] = "image/png"
    evidence["file_size"] = len(image)
    evidence["file_hash"] = hashlib.sha256(image).hexdigest()
    evidence["desensitized"] = True
    runner = CapturingMultimodalRunner()
    workflow = EvidenceTurnWorkflow(
        model_runner=runner,
        asset_loader=EvidenceAssetLoader(
            java_api_service_url="http://java-api-service:8080",
            java_service_secret="test-java-service-secret",
            transport=httpx.MockTransport(
                lambda request: httpx.Response(200, content=image)
            ),
        ),
    )

    result = workflow.run(EvidenceTurnRequest.model_validate(payload))

    call = runner.calls[0]
    context_pack = call["context_pack"]
    sections = {item.name: item.content for item in context_pack.prompt_sections()}
    observation = json.loads(sections["multimodal_observation"])
    assert observation["manifest"]["loaded_image_count"] == 1
    assert observation["manifest"]["items"][0]["visual_input_status"] == "LOADED"
    assert call["multimodal_parts"][1]["type"] == "image_url"
    assert result.evidence_assessments[0].analysis_method == "HYBRID"
    assert len(result.fact_matrix_patch) == 1
    assert result.fact_matrix_patch[0].operation == "UPSERT_LINK"
    assert result.fact_matrix_patch[0].fact_id == "FACT_SIGNATURE"
    assert result.fact_matrix_patch[0].evidence_id == "EVIDENCE_signature_photo"
    assert result.fact_matrix_patch[0].relation == "INCONCLUSIVE"


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：回归测试用例。
# 具体功能：`test_evidence_opening_turn_passes_source_to_llm_context` 验证当前可见证据在固定案例中的输出、边界和失败行为；关键协作调用：`FakeEvidenceRunner`、`EvidenceTurnWorkflow`、`workflow.run`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_java_evidence_opening_command_payload`。
# 系统意义：固定“Agent 角色能力 > test_evidence_clerk_turn”的可观察契约，防止后续重构改变业务结果。
def test_evidence_opening_turn_passes_source_to_llm_context() -> None:
    from app.agents.evidence_clerk.workflow import EvidenceTurnWorkflow
    from app.schemas import EvidenceTurnRequest

    runner = FakeEvidenceRunner()
    workflow = EvidenceTurnWorkflow(model_runner=runner)

    workflow.run(EvidenceTurnRequest.model_validate(_java_evidence_opening_command_payload()))

    assert runner.calls[0]["case_data"]["turn_source"] == "ROOM_OPENING"
    assert runner.calls[0]["case_data"]["event_id"] == "EVIDENCE_OPENING_1"


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：模块公开业务函数。
# 具体功能：`legacy_evidence_opening_fallback_asks_dossier_specific_evidence_questions` 在模型或外部依赖不可用时生成保守的案件卷宗降级结果；关键协作调用：`EvidenceTurnWorkflow`、`workflow.run`、`join`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_java_evidence_opening_command_payload`。
# 系统意义：保证依赖故障时案件进入可解释的重试或人工路径，而不是生成伪结论。
def legacy_evidence_opening_fallback_asks_dossier_specific_evidence_questions() -> None:
    from app.agents.evidence_clerk.workflow import EvidenceTurnWorkflow
    from app.schemas import EvidenceTurnRequest

    workflow = EvidenceTurnWorkflow()

    result = workflow.run(
        EvidenceTurnRequest.model_validate(_java_evidence_opening_command_payload())
    )

    combined = " ".join(
        [result.room_utterance]
        + [item.question for item in result.evidence_requests]
        + [item.reason for item in result.evidence_requests]
    )
    assert "factory QC video" in combined
    assert "unboxing photo original file" in combined
    assert "logistics handling record" in combined
    assert "签收后发现手表划痕" in combined
    assert result.non_final is True
    assert result.liability_determined is False
    assert result.remedy_recommended is False


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：模块公开业务函数。
# 具体功能：`legacy_evidence_opening_fallback_states_initiator_evidence_gate_without_deciding` 在模型或外部依赖不可用时生成保守的当前可见证据降级结果；关键协作调用：`EvidenceTurnWorkflow`、`workflow.run`、`EvidenceTurnRequest.model_validate`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_java_evidence_opening_command_payload`。
# 系统意义：保证依赖故障时案件进入可解释的重试或人工路径，而不是生成伪结论。
def legacy_evidence_opening_fallback_states_initiator_evidence_gate_without_deciding() -> None:
    from app.agents.evidence_clerk.workflow import EvidenceTurnWorkflow
    from app.schemas import EvidenceTurnRequest

    workflow = EvidenceTurnWorkflow()

    result = workflow.run(
        EvidenceTurnRequest.model_validate(_java_evidence_opening_command_payload())
    )

    assert (
        "发起争议方须至少正式提交 1 份相关证据后才能完成举证"
        in result.room_utterance
    )
    assert "另一方可补充材料，或等待举证时效结束" in result.room_utterance
    assert "证据不足不会阻止进入小法庭" not in result.room_utterance
    assert result.non_final is True
    assert result.liability_determined is False
    assert result.remedy_recommended is False


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：模块公开业务函数。
# 具体功能：`legacy_evidence_opening_fallback_localizes_internal_dispute_codes` 在模型或外部依赖不可用时生成保守的当前可见证据降级结果；关键协作调用：`EvidenceTurnWorkflow`、`workflow.run`、`join`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_java_evidence_opening_command_payload`。
# 系统意义：保证依赖故障时案件进入可解释的重试或人工路径，而不是生成伪结论。
def legacy_evidence_opening_fallback_localizes_internal_dispute_codes() -> None:
    from app.agents.evidence_clerk.workflow import EvidenceTurnWorkflow
    from app.schemas import EvidenceTurnRequest

    payload = _java_evidence_opening_command_payload()
    dossier = payload["context_envelope"]["intake_dossier_snapshot"]["payload"]
    dossier["case_story"][
        "one_sentence_summary"
    ] = "物流系统显示包裹已签收，但用户反馈本人没有收到。"
    dossier["dispute_focus"] = {
        "core_issue": "SIGNED_NOT_RECEIVED",
        "facts_to_verify": ["物流签收记录", "投递轨迹或快递柜/驿站记录"],
    }
    dossier["unilateral_case_matrix"] = _unilateral_case_matrix(
        ("FACT_SIGNATURE", "物流系统显示包裹已签收", "CORE"),
        ("FACT_DELIVERY_TRACE", "投递轨迹或快递柜、驿站记录仍待核验", "CORE"),
    )
    workflow = EvidenceTurnWorkflow()

    result = workflow.run(EvidenceTurnRequest.model_validate(payload))

    combined = " ".join(
        [result.room_utterance]
        + [item.question for item in result.evidence_requests]
        + [item.reason for item in result.evidence_requests]
        + [item.description for item in result.authenticity_flags]
    )
    assert "SIGNED_NOT_RECEIVED" not in combined
    assert "物流显示签收但用户称未收到包裹" in combined


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：模块公开业务函数。
# 具体功能：`legacy_evidence_opening_fallback_normalizes_summary_punctuation` 在模型或外部依赖不可用时生成保守的当前可见证据降级结果；关键协作调用：`EvidenceTurnWorkflow`、`workflow.run`、`EvidenceTurnRequest.model_validate`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_java_evidence_opening_command_payload`。
# 系统意义：保证依赖故障时案件进入可解释的重试或人工路径，而不是生成伪结论。
def legacy_evidence_opening_fallback_normalizes_summary_punctuation() -> None:
    from app.agents.evidence_clerk.workflow import EvidenceTurnWorkflow
    from app.schemas import EvidenceTurnRequest

    payload = _java_evidence_opening_command_payload()
    dossier = payload["context_envelope"]["intake_dossier_snapshot"]["payload"]
    dossier["case_story"][
        "one_sentence_summary"
    ] = "物流显示签收，但用户称没有收到包裹。"
    workflow = EvidenceTurnWorkflow()

    result = workflow.run(EvidenceTurnRequest.model_validate(payload))

    assert "。。" not in result.room_utterance
    assert "物流显示签收，但用户称没有收到包裹。" in result.room_utterance


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：模块公开业务函数。
# 具体功能：`legacy_evidence_opening_replaces_generic_llm_welcome_with_dossier_questions` 围绕案件卷宗计算该函数独立负责的业务派生值；关键协作调用：`EvidenceTurnWorkflow`、`workflow.run`、`join`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_java_evidence_opening_command_payload`。
# 系统意义：把不确定模型能力限制在确定性系统边界内：服从角色权限、上下文范围和非最终结论边界。
def legacy_evidence_opening_replaces_generic_llm_welcome_with_dossier_questions() -> None:
    from app.agents.evidence_clerk.workflow import EvidenceTurnWorkflow
    from app.schemas import EvidenceTurnRequest

    workflow = EvidenceTurnWorkflow(model_runner=GenericOpeningRunner())

    result = workflow.run(
        EvidenceTurnRequest.model_validate(_java_evidence_opening_command_payload())
    )

    combined = " ".join(
        [result.room_utterance]
        + [item.question for item in result.evidence_requests]
        + [item.reason for item in result.evidence_requests]
    )
    assert "您好！我是您的证据书记官" not in result.room_utterance
    assert "接待室收敛的案情" in result.room_utterance
    assert "factory QC video" in combined
    assert "unboxing photo original file" in combined
    assert "logistics handling record" in combined
    assert "签收后发现手表划痕" in combined


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：模块公开业务函数。
# 具体功能：`legacy_evidence_opening_coerce_bounds_merged_model_and_baseline_output` 把当前可见证据写入或合并到可追溯的阶段状态；关键协作调用：`EvidenceTurnWorkflow`、`workflow.run`、`EvidenceTurnRequest.model_validate`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_java_evidence_opening_command_payload`。
# 系统意义：把不确定模型能力限制在确定性系统边界内：服从角色权限、上下文范围和非最终结论边界。
def legacy_evidence_opening_coerce_bounds_merged_model_and_baseline_output() -> None:
    from app.agents.evidence_clerk.workflow import EvidenceTurnWorkflow
    from app.schemas import EvidenceTurnRequest

    workflow = EvidenceTurnWorkflow(model_runner=MaximalOpeningRunner())

    result = workflow.run(
        EvidenceTurnRequest.model_validate(_java_evidence_opening_command_payload())
    )

    assert len(result.evidence_requests) <= 10
    assert len(result.authenticity_flags) <= 20


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：模块公开业务函数。
# 具体功能：`legacy_evidence_turn_fallback_asks_authenticity_and_relevance_questions_without_deciding` 在模型或外部依赖不可用时生成保守的当前可见证据降级结果；关键协作调用：`EvidenceTurnWorkflow`、`workflow.run`、`join`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_evidence_turn_payload`。
# 系统意义：保证依赖故障时案件进入可解释的重试或人工路径，而不是生成伪结论。
def legacy_evidence_turn_fallback_asks_authenticity_and_relevance_questions_without_deciding() -> None:
    from app.agents.evidence_clerk.workflow import EvidenceTurnWorkflow
    from app.schemas import EvidenceTurnRequest

    workflow = EvidenceTurnWorkflow()

    result = workflow.run(EvidenceTurnRequest.model_validate(_evidence_turn_payload()))

    combined_questions = " ".join(item.question for item in result.evidence_requests)
    combined_suggestions = " ".join(
        item.suggestion for item in result.verification_suggestions
    )
    assert "来源" in combined_questions
    assert "时间" in combined_questions
    assert "关联" in result.room_utterance
    assert "责任在" not in result.room_utterance
    assert "应当退款" not in result.room_utterance
    assert "最终判定" not in result.room_utterance
    assert "核对" in combined_suggestions
    assert result.authenticity_flags
    assert result.non_final is True
    assert result.liability_determined is False
    assert result.remedy_recommended is False


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：回归测试用例。
# 具体功能：`test_evidence_context_assembler_preserves_null_parsed_text_and_adds_notice` 把上游材料组装为本阶段可消费的当前可见证据；关键协作调用：`assemble`、`EvidenceTurnRequest.model_validate`、`EvidenceContextAssembler`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_java_evidence_turn_command_payload`。
# 系统意义：固定“Agent 角色能力 > test_evidence_clerk_turn”的可观察契约，防止后续重构改变业务结果。
def test_evidence_context_assembler_preserves_null_parsed_text_and_adds_notice() -> None:
    from app.harness.evidence_context_assembler import EvidenceContextAssembler
    from app.schemas import EvidenceTurnRequest

    payload = _java_evidence_turn_command_payload()
    evidence = payload["context_envelope"]["visible_evidence"][0]
    evidence["parsed_text"] = None
    evidence["parse_status"] = "PROCESSING"

    assembled = EvidenceContextAssembler().assemble(
        EvidenceTurnRequest.model_validate(payload)
    )

    evidence_context = assembled.context_sources["party_visible_evidence_catalog"]
    model_evidence = evidence_context["items"][0]
    assert "parsed_text" not in model_evidence
    assert model_evidence["content_preview"] == (
        "证据内容正在解析，当前仅可核对文件元数据。"
    )
    assert model_evidence["parse_notice"] == model_evidence["content_preview"]
    assert model_evidence["content_char_count"] == 0
    assert model_evidence["has_file_hash"] is True
    assert "file_hash" not in model_evidence
    assert "submitted_by_id" not in model_evidence
    assert "content_url" not in model_evidence
    assert "metadata" not in model_evidence
    assert "extraction" not in model_evidence
    assert assembled.working_set.available_evidence[0].parsed_text is None
    assert assembled.working_set.available_evidence[0].content == (
        "证据内容正在解析，当前仅可核对文件元数据。"
    )


def test_evidence_context_assembler_exposes_submission_declaration_without_trusting_it() -> None:
    from app.harness.evidence_context_assembler import EvidenceContextAssembler
    from app.schemas import EvidenceTurnRequest

    payload = _java_evidence_turn_command_payload()
    evidence = payload["context_envelope"]["visible_evidence"][0]
    evidence["metadata"] = {
        "claimed_fact": "证明物流截图中的签收人不是本人",
        "truth_attested": True,
        "attestation_version": "EVIDENCE_TRUTH_ATTESTATION_V1",
        "attestation_scope": ["AUTHENTICITY", "CLAIMED_FACT_RELEVANCE"],
        "attestation_role": "USER",
        "attested_by": "USER_local_1",
        "attested_at": "2026-07-11T09:59:00+08:00",
        "party_capacity": "INITIATOR",
        "forgery_consequence_code": (
            "REJECT_INITIATOR_CLAIMS_AND_REPUTATION_PENALTY"
        ),
        "enforcement_gate": "HUMAN_CONFIRMED_FORGERY_REQUIRED",
        "untrusted_extra": "不得透传到模型上下文",
    }

    assembled = EvidenceContextAssembler().assemble(
        EvidenceTurnRequest.model_validate(payload)
    )

    catalog_item = assembled.context_sources["party_visible_evidence_catalog"][
        "items"
    ][0]
    working_item = assembled.working_set.available_evidence[0]
    assert catalog_item["claimed_fact"] == "证明物流截图中的签收人不是本人"
    assert catalog_item["truth_attested"] is True
    assert catalog_item["party_capacity"] == "INITIATOR"
    assert catalog_item["enforcement_gate"] == (
        "HUMAN_CONFIRMED_FORGERY_REQUIRED"
    )
    assert "untrusted_extra" not in catalog_item
    assert working_item.claimed_fact == catalog_item["claimed_fact"]
    assert working_item.truth_attested is True
    assert working_item.attestation_version == "EVIDENCE_TRUTH_ATTESTATION_V1"
    assert working_item.attestation_scope == [
        "AUTHENTICITY",
        "CLAIMED_FACT_RELEVANCE",
    ]


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：回归测试用例。
# 具体功能：`test_evidence_context_assembler_prioritizes_current_attachments_and_budgets` 把上游材料组装为本阶段可消费的当前可见证据；关键协作调用：`assemble`、`items.append`、`EvidenceTurnRequest.model_validate`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_java_evidence_turn_command_payload`、`_visible_signature_evidence`。
# 系统意义：固定“Agent 角色能力 > test_evidence_clerk_turn”的可观察契约，防止后续重构改变业务结果。
def test_evidence_context_assembler_prioritizes_current_attachments_and_budgets() -> None:
    from app.harness.evidence_context_assembler import EvidenceContextAssembler
    from app.schemas import EvidenceTurnRequest

    payload = _java_evidence_turn_command_payload()
    items = []
    for index in range(25):
        item = dict(_visible_signature_evidence())
        item["evidence_id"] = f"EVIDENCE_budget_{index:02d}"
        item["file_hash"] = f"sha256-budget-{index:02d}"
        item["parsed_text"] = "证据解析正文" * 10_000
        item["submitted_at"] = f"2026-07-11T09:{index:02d}:00+08:00"
        items.append(item)
    payload["context_envelope"]["visible_evidence"] = items
    payload["context_envelope"]["current_event"]["attachment_refs"] = [
        "EVIDENCE_budget_00"
    ]

    assembled = EvidenceContextAssembler().assemble(
        EvidenceTurnRequest.model_validate(payload)
    )
    evidence_context = assembled.context_sources["party_visible_evidence_catalog"]

    assert evidence_context["source_count"] == 25
    assert evidence_context["included_count"] == 20
    assert evidence_context["truncated"] is True
    assert evidence_context["items"][0]["evidence_id"] == "EVIDENCE_budget_00"
    assert len(evidence_context["items"][0]["content_preview"]) == 3_000
    assert evidence_context["items"][0]["preview_truncated"] is True
    assert len(assembled.working_set.available_evidence) == 25
    assert assembled.working_set.available_evidence[0].parsed_text is None
    assert len(assembled.working_set.available_evidence[0].content) == 3_000
    assert len(assembled.raw_envelope.visible_evidence[0].parsed_text or "") > 20_000


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：模块公开业务函数。
# 具体功能：`legacy_evidence_fallback_bounds_deterministic_output_above_one_hundred_items` 在模型或外部依赖不可用时生成保守的当前可见证据降级结果；关键协作调用：`EvidenceTurnRequest.model_validate`、`assemble`、`draft`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_evidence_turn_payload`、`_visible_signature_evidence`。
# 系统意义：保证依赖故障时案件进入可解释的重试或人工路径，而不是生成伪结论。
def legacy_evidence_fallback_bounds_deterministic_output_above_one_hundred_items() -> None:
    from app.agents.evidence_clerk.skills.authenticity import EvidenceAuthenticitySkill
    from app.agents.evidence_clerk.workflow import EvidenceTurnWorkflow
    from app.harness.evidence_context_assembler import EvidenceContextAssembler
    from app.schemas import EvidenceTurnRequest

    payload = _evidence_turn_payload()
    items = []
    for index in range(125):
        item = dict(_visible_signature_evidence())
        item["evidence_id"] = f"EVIDENCE_scale_{index:03d}"
        item["file_hash"] = f"sha256-scale-{index:03d}"
        item["parsed_text"] = f"第 {index} 份证据解析文本。"
        item["submitted_at"] = f"2026-07-11T{index // 60:02d}:{index % 60:02d}:00+08:00"
        items.append(item)
    payload["context_envelope"]["visible_evidence"] = items
    request = EvidenceTurnRequest.model_validate(payload)
    assembled = EvidenceContextAssembler().assemble(request)

    draft = EvidenceAuthenticitySkill().draft(assembled.working_set)
    result = EvidenceTurnWorkflow().run(request)

    assert len(draft.evidence_requests) == 10
    assert len(draft.verification_suggestions) == 100
    assert len(draft.authenticity_flags) <= 20
    assert len(result.evidence_requests) <= 10
    assert len(result.verification_suggestions) == 20
    assert len(result.authenticity_flags) <= 20


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：回归测试用例。
# 具体功能：`test_evidence_context_assembler_uses_raw_case_when_intake_dossier_is_missing` 把上游材料组装为本阶段可消费的案件卷宗；关键协作调用：`assemble`、`EvidenceTurnRequest.model_validate`、`EvidenceContextAssembler`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_evidence_turn_payload`。
# 系统意义：固定“Agent 角色能力 > test_evidence_clerk_turn”的可观察契约，防止后续重构改变业务结果。
def test_evidence_context_assembler_rejects_missing_formal_intake_matrix() -> None:
    from app.harness.evidence_context_assembler import EvidenceContextAssembler
    from app.schemas import EvidenceTurnRequest

    payload = _evidence_turn_payload()
    payload["context_envelope"]["intake_dossier_snapshot"] = None
    payload["context_envelope"]["case_snapshot"]["description"] = "案情描述" * 10_000

    with pytest.raises(
        ValueError,
        match="frozen case_fact_matrix.v2",
    ):
        EvidenceContextAssembler().assemble(
            EvidenceTurnRequest.model_validate(payload)
        )


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：回归测试用例。
# 具体功能：`test_evidence_envelope_accepts_short_external_business_references` 验证当前可见证据在固定案例中的输出、边界和失败行为；关键协作调用：`snapshot.update`、`EvidenceTurnRequest.model_validate`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_evidence_turn_payload`。
# 系统意义：固定“Agent 角色能力 > test_evidence_clerk_turn”的可观察契约，防止后续重构改变业务结果。
def test_evidence_envelope_accepts_short_external_business_references() -> None:
    from app.schemas import EvidenceTurnRequest

    payload = _evidence_turn_payload()
    snapshot = payload["context_envelope"]["case_snapshot"]
    snapshot.update(
        {
            "order_id": "O",
            "after_sale_id": "A2",
            "logistics_id": "L",
            "source_system": "X",
            "external_case_ref": "E2",
        }
    )

    request = EvidenceTurnRequest.model_validate(payload)

    assert request.context_envelope.case_snapshot.order_id == "O"
    assert request.context_envelope.case_snapshot.source_system == "X"


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：回归测试用例。
# 具体功能：`test_evidence_envelope_rejects_cross_actor_or_unknown_attachment_evidence` 验证当前可见证据在固定案例中的输出、边界和失败行为；关键协作调用：`pytest.raises`、`EvidenceTurnRequest.model_validate`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_java_evidence_turn_command_payload`。
# 系统意义：固定“Agent 角色能力 > test_evidence_clerk_turn”的可观察契约，防止后续重构改变业务结果。
def test_evidence_envelope_rejects_cross_actor_or_unknown_attachment_evidence() -> None:
    from app.schemas import EvidenceTurnRequest

    wrong_owner = _java_evidence_turn_command_payload()
    wrong_owner["context_envelope"]["visible_evidence"][0][
        "submitted_by_id"
    ] = "USER_other"

    with pytest.raises(ValidationError) as owner_failure:
        EvidenceTurnRequest.model_validate(wrong_owner)

    assert "submitted_by_id must match actor_snapshot.actor_id" in str(
        owner_failure.value
    )

    unknown_attachment = _java_evidence_turn_command_payload()
    unknown_attachment["context_envelope"]["current_event"][
        "attachment_refs"
    ] = ["EVIDENCE_not_visible"]

    with pytest.raises(ValidationError) as attachment_failure:
        EvidenceTurnRequest.model_validate(unknown_attachment)

    assert "attachment_refs must reference visible_evidence" in str(
        attachment_failure.value
    )


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：回归测试用例。
# 具体功能：`test_evidence_turn_api_requires_service_secret_and_fails_without_model` 验证当前可见证据在固定案例中的输出、边界和失败行为；关键协作调用：`TestClient`、`client.post`、`create_app`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_settings`、`_evidence_turn_payload`、`_headers`。
# 系统意义：固定“Agent 角色能力 > test_evidence_clerk_turn”的可观察契约，防止后续重构改变业务结果。
def test_evidence_turn_api_requires_service_secret_and_fails_without_model() -> None:
    from app.agents.evidence_clerk.workflow import EvidenceTurnWorkflow

    client = TestClient(
        create_app(
            _settings(),
            evidence_turn_workflow=EvidenceTurnWorkflow(),
        )
    )

    unauthorized = client.post(
        "/internal/agents/evidence/turn",
        headers={"X-Service-Secret": "wrong-secret"},
        json=_evidence_turn_payload(),
    )
    assert unauthorized.status_code == 401

    response = client.post(
        "/internal/agents/evidence/turn",
        headers=_headers(),
        json=_evidence_turn_payload(),
    )

    assert response.status_code == 503
    assert response.json()["code"] == "AGENT_SERVICE_UNAVAILABLE"


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：回归测试用例。
# 具体功能：`test_evidence_turn_api_rejects_legacy_and_mixed_transport_contracts` 验证当前可见证据在固定案例中的输出、边界和失败行为；关键协作调用：`TestClient`、`client.post`、`create_app`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_evidence_turn_payload`、`_settings`、`_headers`。
# 系统意义：固定“Agent 角色能力 > test_evidence_clerk_turn”的可观察契约，防止后续重构改变业务结果。
def test_evidence_turn_api_rejects_legacy_and_mixed_transport_contracts() -> None:
    from app.agents.evidence_clerk.workflow import EvidenceTurnWorkflow

    client = TestClient(
        create_app(
            _settings(),
            evidence_turn_workflow=EvidenceTurnWorkflow(),
        )
    )
    valid = _evidence_turn_payload()
    legacy = {
        "case_id": "CASE_evidence_turn_llm",
        "room_type": "EVIDENCE",
        "turn_source": "PARTY_MESSAGE",
        "actor_role": "USER",
        "current_party_message": {},
        "agent_context": valid["agent_context"],
    }

    legacy_response = client.post(
        "/internal/agents/evidence/turn",
        headers=_headers(),
        json=legacy,
    )
    assert legacy_response.status_code == 422

    mixed = _evidence_turn_payload()
    mixed["case_id"] = "CASE_evidence_turn_llm"
    mixed_response = client.post(
        "/internal/agents/evidence/turn",
        headers=_headers(),
        json=mixed,
    )
    assert mixed_response.status_code == 422


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：回归测试用例。
# 具体功能：`test_evidence_turn_endpoint_accepts_java_command_payload_without_degrading` 读取并按案件、角色或会话范围筛选当前可见证据；关键协作调用：`TestClient`、`client.post`、`response.json`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_settings`、`_headers`、`_evidence_turn_payload`。
# 系统意义：固定“Agent 角色能力 > test_evidence_clerk_turn”的可观察契约，防止后续重构改变业务结果。
def test_evidence_turn_endpoint_accepts_java_command_payload_without_degrading() -> None:
    from app.agents.evidence_clerk.workflow import EvidenceTurnWorkflow

    client = TestClient(
        create_app(
            _settings(),
            evidence_turn_workflow=EvidenceTurnWorkflow(model_runner=FakeEvidenceRunner()),
        )
    )

    response = client.post(
        "/internal/agents/evidence/turn",
        headers=_headers(),
        json=_evidence_turn_payload(),
    )

    assert response.status_code == 200
    payload = response.json()
    utterance = payload["room_utterance"]
    combined_questions = " ".join(
        item["question"] for item in payload["evidence_requests"]
    )
    combined_suggestions = " ".join(
        item["suggestion"] for item in payload["verification_suggestions"]
    )
    assert any("\u4e00" <= char <= "\u9fff" for char in utterance)
    assert "Evidence clerk is temporarily unavailable" not in utterance
    assert payload["memory_patch"].get("agent_degraded") is not True
    assert payload["liability_determined"] is False
    assert payload["remedy_recommended"] is False
    assert "责任在" not in utterance
    assert "应当退款" not in utterance
    assert "最终判定" not in utterance
    assert "来源" in combined_questions
    assert "裁剪" in combined_suggestions
    assert "关联" in utterance + combined_questions + combined_suggestions


def test_evidence_turn_stream_accepts_one_hearing_batch_without_422() -> None:
    from app.agents.evidence_clerk.workflow import EvidenceTurnWorkflow

    client = TestClient(
        create_app(
            _settings(),
            evidence_turn_workflow=EvidenceTurnWorkflow(
                model_runner=FakeEvidenceRunner()
            ),
        )
    )

    response = client.post(
        "/internal/agents/evidence/turn/stream",
        headers={**_headers(), "X-Agent-Run-Id": "AGENT_RUN_hearing_batch"},
        json=_hearing_batch_evidence_turn_payload(),
    )

    assert response.status_code == 200
    events = [json.loads(line) for line in response.text.splitlines() if line]
    assert not any(event["type"] == "visible_delta" for event in events)
    assert events[-1]["type"] == "final"
    assert events[-1]["response"]["room_utterance"] == (
        "我会先核验这份截图的来源、可读性和与签收争议的关联性；"
        "本轮只核验证据，不裁决责任。"
        "本轮只做证据核验，不判断责任或最终方案。"
    )
    assert events[-1]["response"]["referenced_evidence_ids"] == [
        "EVIDENCE_signature_photo",
        "EVIDENCE_logistics_record",
    ]


def test_evidence_turn_guardrail_failure_never_publishes_raw_room_utterance(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from app.agents.evidence_clerk.assessment_policy import EvidenceAssessmentPolicy
    from app.agents.evidence_clerk.workflow import EvidenceTurnWorkflow
    from app.llm import LiteLlmProxyClient
    from app.schemas import EvidenceTurnRequest
    from app.streaming import AgentStreamObserver, bind_stream_observer

    raw_room_utterance = "模型原始话术不得在业务护栏失败前公开。"
    output = {
        "room_utterance": raw_room_utterance,
        "evidence_assessments": [
            {
                "evidence_id": "EVIDENCE_signature_photo",
                "analysis_method": "TEXT_ONLY",
                "inspected_modalities": ["PARSED_TEXT"],
                "fact_links": [],
                "authenticity_score": 0.9,
                "relevance_score": 0.9,
                "completeness_score": 0.9,
                "assessment_confidence": 0.9,
                "source_basis": ["当前附件解析文本"],
                "supported_fact_ids": [],
                "formation_time_assessment": "形成时间待核对。",
                "recommendation": "PLAUSIBLE",
                "summary": "模型给出高相关性，但没有提供任何事实坐标。",
            }
        ],
        "internal_handoff": _empty_internal_handoff(),
        "confidence": 0.9,
    }

    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "model": "qwen3.7-plus",
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(output, ensure_ascii=False)
                        }
                    }
                ],
                "usage": {
                    "prompt_tokens": 60,
                    "completion_tokens": 40,
                    "total_tokens": 100,
                },
            },
        )

    llm = LiteLlmProxyClient(
        "http://litellm.test",
        "qwen3.7-plus",
        "secret",
        transport=httpx.MockTransport(handler),
    )

    class ClientBackedRunner:
        def invoke_structured(self, **kwargs):
            return llm.generate(
                node_name=kwargs["node_name"],
                system_prompt="system",
                user_prompt="user",
                output_type=kwargs["output_type"],
            )

    published = []
    observer = AgentStreamObserver(
        operation="evidence_turn",
        run_id="AGENT_RUN_GUARDRAIL_FAILURE",
        publish=published.append,
    )
    request = EvidenceTurnRequest.model_validate(_evidence_turn_payload())

    def reject_after_schema_validation(*_args, **_kwargs):
        raise ValueError("business guardrail rejected evidence output")

    monkeypatch.setattr(EvidenceAssessmentPolicy, "apply", reject_after_schema_validation)

    with bind_stream_observer(observer):
        with pytest.raises(
            ValueError,
            match="business guardrail rejected evidence output",
        ):
            EvidenceTurnWorkflow(model_runner=ClientBackedRunner()).run(request)

    assert [event.type for event in published] == ["usage"]
    assert not any(event.type == "visible_delta" for event in published)
    assert all(
        raw_room_utterance not in getattr(event, "delta", "")
        for event in published
    )


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：回归测试用例。
# 具体功能：`test_prompt_repository_registers_evidence_turn_prompt` 验证当前可见证据在固定案例中的输出、边界和失败行为；关键协作调用：`template_path`、`Path`、`PromptRepository`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 协作调用 `template_path`、`Path`、`PromptRepository`。
# 系统意义：固定“Agent 角色能力 > test_evidence_clerk_turn”的可观察契约，防止后续重构改变业务结果。
def test_prompt_repository_registers_evidence_turn_prompt() -> None:
    assert PromptRepository().template_path("evidence_turn") == Path(
        "app/agents/prompts/evidence_clerk/evidence_turn.md"
    )


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：回归测试用例。
# 具体功能：`test_evidence_turn_prompt_treats_initiator_evidence_as_admission_gate` 验证当前可见证据在固定案例中的输出、边界和失败行为；关键协作调用：`read_text`、`Path`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 协作调用 `read_text`、`Path`。
# 系统意义：固定“Agent 角色能力 > test_evidence_clerk_turn”的可观察契约，防止后续重构改变业务结果。
def test_evidence_turn_prompt_treats_initiator_evidence_as_admission_gate() -> None:
    prompt = Path(
        "app/agents/prompts/evidence_clerk/evidence_turn.md"
    ).read_text(encoding="utf-8")

    assert "至少正式提交 1 份相关证据后才能完成举证" in prompt
    assert "受理门槛" in prompt
    assert "不判断责任" in prompt
    assert "ROOM_OPENING" in prompt
    assert "只用简体中文" in prompt
    assert "禁止英文翻译、双语复述" in prompt


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：回归测试用例。
# 具体功能：`test_evidence_asset_loader_fetches_authorized_image_and_builds_data_url` 把上游材料组装为本阶段可消费的当前可见证据；关键协作调用：`base64.b64decode`、`hexdigest`、`load`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_java_evidence_turn_command_payload`。
# 系统意义：固定“Agent 角色能力 > test_evidence_clerk_turn”的可观察契约，防止后续重构改变业务结果。
def test_evidence_asset_loader_fetches_authorized_image_and_builds_data_url() -> None:
    from app.harness.evidence_asset_loader import EvidenceAssetLoader
    from app.schemas import EvidenceTurnRequest

    image = base64.b64decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    )
    payload = _java_evidence_turn_command_payload()
    evidence = payload["context_envelope"]["visible_evidence"][0]
    evidence["content_type"] = "image/png"
    evidence["file_size"] = len(image)
    evidence["file_hash"] = hashlib.sha256(image).hexdigest()
    evidence["desensitized"] = True

    # 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：类/闭包内部方法。
    # 具体功能：`handler` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`request.url.path.endswith`、`httpx.Response`。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 协作调用 `request.url.path.endswith`、`httpx.Response`。
    # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path.endswith(
            "/internal/evidence/CASE_evidence_turn_llm/"
            "EVIDENCE_signature_photo/content"
        )
        assert request.headers["X-Service-Identity"] == "python-agent-service"
        assert request.headers["X-Service-Secret"] == "test-java-service-secret"
        return httpx.Response(200, content=image)

    envelope = EvidenceTurnRequest.model_validate(payload).context_envelope
    loaded = EvidenceAssetLoader(
        java_api_service_url="http://java-api-service:8080",
        java_service_secret="test-java-service-secret",
        transport=httpx.MockTransport(handler),
    ).load(envelope)

    assert loaded.manifest["loaded_image_count"] == 1
    assert loaded.manifest["items"][0]["visual_input_status"] == "LOADED"
    assert loaded.content_parts[1]["type"] == "image_url"
    assert loaded.content_parts[1]["image_url"]["url"].startswith(
        "data:image/png;base64,"
    )


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：回归测试用例。
# 具体功能：`test_evidence_asset_loader_blocks_hash_mismatch_from_model_input` 读取并按案件、角色或会话范围筛选当前可见证据；关键协作调用：`base64.b64decode`、`load`、`EvidenceTurnRequest.model_validate`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_java_evidence_turn_command_payload`。
# 系统意义：固定“Agent 角色能力 > test_evidence_clerk_turn”的可观察契约，防止后续重构改变业务结果。
def test_evidence_asset_loader_blocks_hash_mismatch_from_model_input() -> None:
    from app.harness.evidence_asset_loader import EvidenceAssetLoader
    from app.schemas import EvidenceTurnRequest

    payload = _java_evidence_turn_command_payload()
    evidence = payload["context_envelope"]["visible_evidence"][0]
    evidence["content_type"] = "image/png"
    evidence["file_hash"] = "0" * 64
    evidence["desensitized"] = True
    image = base64.b64decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    )
    envelope = EvidenceTurnRequest.model_validate(payload).context_envelope
    loaded = EvidenceAssetLoader(
        java_api_service_url="http://java-api-service:8080",
        java_service_secret="test-java-service-secret",
        transport=httpx.MockTransport(
            lambda request: httpx.Response(200, content=image)
        ),
    ).load(envelope)

    assert loaded.content_parts == ()
    assert loaded.manifest["items"][0]["visual_input_status"] == "HASH_MISMATCH"


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：回归测试用例。
# 具体功能：`test_evidence_asset_loader_blocks_raw_image_until_privacy_gate_is_enabled` 读取并按案件、角色或会话范围筛选当前可见证据；关键协作调用：`load`、`EvidenceTurnRequest.model_validate`、`EvidenceAssetLoader`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_java_evidence_turn_command_payload`。
# 系统意义：固定“Agent 角色能力 > test_evidence_clerk_turn”的可观察契约，防止后续重构改变业务结果。
def test_evidence_asset_loader_blocks_raw_image_until_privacy_gate_is_enabled() -> None:
    from app.harness.evidence_asset_loader import EvidenceAssetLoader
    from app.schemas import EvidenceTurnRequest

    payload = _java_evidence_turn_command_payload()
    evidence = payload["context_envelope"]["visible_evidence"][0]
    evidence["content_type"] = "image/png"
    evidence["desensitized"] = False
    envelope = EvidenceTurnRequest.model_validate(payload).context_envelope
    loaded = EvidenceAssetLoader(
        java_api_service_url="http://java-api-service:8080",
        java_service_secret="test-java-service-secret",
        transport=httpx.MockTransport(
            lambda request: pytest.fail("raw image must not be fetched")
        ),
    ).load(envelope)

    assert loaded.content_parts == ()
    assert loaded.manifest["items"][0]["visual_input_status"] == (
        "PRIVACY_REVIEW_REQUIRED"
    )


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：回归测试用例。
# 具体功能：`test_evidence_asset_loader_accepts_per_evidence_model_processing_authorization` 读取并按案件、角色或会话范围筛选当前可见证据；关键协作调用：`base64.b64decode`、`hexdigest`、`load`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_java_evidence_turn_command_payload`。
# 系统意义：固定“Agent 角色能力 > test_evidence_clerk_turn”的可观察契约，防止后续重构改变业务结果。
def test_evidence_asset_loader_accepts_per_evidence_model_processing_authorization() -> None:
    from app.harness.evidence_asset_loader import EvidenceAssetLoader
    from app.schemas import EvidenceTurnRequest

    image = base64.b64decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    )
    payload = _java_evidence_turn_command_payload()
    evidence = payload["context_envelope"]["visible_evidence"][0]
    evidence["content_type"] = "image/png"
    evidence["desensitized"] = False
    evidence["file_hash"] = hashlib.sha256(image).hexdigest()
    evidence["metadata"] = {"model_processing_authorized": True}
    envelope = EvidenceTurnRequest.model_validate(payload).context_envelope
    loaded = EvidenceAssetLoader(
        java_api_service_url="http://java-api-service:8080",
        java_service_secret="test-java-service-secret",
        transport=httpx.MockTransport(
            lambda request: httpx.Response(200, content=image)
        ),
    ).load(envelope)

    assert loaded.manifest["loaded_image_count"] == 1
    descriptor = loaded.manifest["items"][0]
    assert descriptor["privacy_basis"] == "EXPLICIT_PARTY_AUTHORIZATION"
    assert descriptor["visual_input_status"] == "LOADED"


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：回归测试用例。
# 具体功能：`test_evidence_asset_loader_blocks_declared_image_with_invalid_magic` 读取并按案件、角色或会话范围筛选当前可见证据；关键协作调用：`hexdigest`、`load`、`EvidenceTurnRequest.model_validate`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_java_evidence_turn_command_payload`。
# 系统意义：固定“Agent 角色能力 > test_evidence_clerk_turn”的可观察契约，防止后续重构改变业务结果。
def test_evidence_asset_loader_blocks_declared_image_with_invalid_magic() -> None:
    from app.harness.evidence_asset_loader import EvidenceAssetLoader
    from app.schemas import EvidenceTurnRequest

    payload = _java_evidence_turn_command_payload()
    evidence = payload["context_envelope"]["visible_evidence"][0]
    evidence["content_type"] = "image/png"
    evidence["desensitized"] = True
    evidence["file_hash"] = hashlib.sha256(b"not-an-image").hexdigest()
    envelope = EvidenceTurnRequest.model_validate(payload).context_envelope
    loaded = EvidenceAssetLoader(
        java_api_service_url="http://java-api-service:8080",
        java_service_secret="test-java-service-secret",
        transport=httpx.MockTransport(
            lambda request: httpx.Response(200, content=b"not-an-image")
        ),
    ).load(envelope)

    assert loaded.content_parts == ()
    assert loaded.manifest["items"][0]["visual_input_status"] == "MIME_MISMATCH"


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：回归测试用例。
# 具体功能：`test_evidence_asset_loader_marks_missing_hash_as_provenance_gap` 读取并按案件、角色或会话范围筛选当前可见证据；关键协作调用：`base64.b64decode`、`load`、`EvidenceTurnRequest.model_validate`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_java_evidence_turn_command_payload`。
# 系统意义：固定“Agent 角色能力 > test_evidence_clerk_turn”的可观察契约，防止后续重构改变业务结果。
def test_evidence_asset_loader_marks_missing_hash_as_provenance_gap() -> None:
    from app.harness.evidence_asset_loader import EvidenceAssetLoader
    from app.schemas import EvidenceTurnRequest

    image = base64.b64decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    )
    payload = _java_evidence_turn_command_payload()
    evidence = payload["context_envelope"]["visible_evidence"][0]
    evidence["content_type"] = "image/png"
    evidence["desensitized"] = True
    evidence["file_hash"] = None
    envelope = EvidenceTurnRequest.model_validate(payload).context_envelope
    loaded = EvidenceAssetLoader(
        java_api_service_url="http://java-api-service:8080",
        java_service_secret="test-java-service-secret",
        transport=httpx.MockTransport(
            lambda request: httpx.Response(200, content=image)
        ),
    ).load(envelope)

    assert loaded.manifest["loaded_image_count"] == 1
    assert loaded.manifest["items"][0]["visual_input_status"] == (
        "LOADED_WITHOUT_HASH"
    )


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：回归测试用例。
# 具体功能：`test_assessment_policy_keeps_irrelevant_image_separate_from_authenticity` 验证平台规则在固定案例中的输出、边界和失败行为；关键协作调用：`assemble`、`EvidenceItemAssessment`、`EvidenceTurnRequest.model_validate`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_java_evidence_turn_command_payload`。
# 系统意义：固定“Agent 角色能力 > test_evidence_clerk_turn”的可观察契约，防止后续重构改变业务结果。
def test_assessment_policy_keeps_irrelevant_image_separate_from_authenticity() -> None:
    from app.agents.evidence_clerk.assessment_policy import EvidenceAssessmentPolicy
    from app.harness.evidence_context_assembler import EvidenceContextAssembler
    from app.schemas import EvidenceItemAssessment, EvidenceTurnRequest

    assembled = EvidenceContextAssembler().assemble(
        EvidenceTurnRequest.model_validate(_java_evidence_turn_command_payload())
    )
    assessment = EvidenceItemAssessment(
        evidence_id="EVIDENCE_signature_photo",
        analysis_method="MULTIMODAL",
        inspected_modalities=["OCR_TEXT", "IMAGE_PIXELS", "FILE_METADATA"],
        fact_links=[],
        authenticity_score=0.88,
        relevance_score=0.08,
        completeness_score=0.82,
        assessment_confidence=0.86,
        source_basis=["原始图片和文件元数据。"],
        supported_fact_ids=[],
        unsupported_claims=["该图片不能支持本案物流签收事实。"],
        formation_time_assessment="图片形成时间尚待核验。",
        findings=[
            {
                "finding_type": "UNRELATED_SCENE",
                "description": "画面内容与物流签收争议没有直接关系。",
            }
        ],
        limitations=[],
        risk_flags=[],
        recommendation="PLAUSIBLE",
        human_review={"required": False},
        summary="图片本身可读，但与当前争议事实关联性很低。",
    )

    result = EvidenceAssessmentPolicy().apply(
        [assessment],
        assembled.working_set,
        {
            "items": [
                {
                    "evidence_id": "EVIDENCE_signature_photo",
                    "visual_input_status": "LOADED",
                    "inspected_modalities": [
                        "OCR_TEXT",
                        "IMAGE_PIXELS",
                        "FILE_METADATA",
                    ],
                }
            ]
        },
    )[0]

    assert result.authenticity_score == 0.88
    assert result.relevance_score == 0.08
    assert result.human_review.required is True
    assert result.recommendation == "NEEDS_HUMAN_REVIEW"
    assert "LOW_RELEVANCE_SCORE" in result.human_review.reason_codes
    assert any(flag.code == "LOW_RELEVANCE" for flag in result.risk_flags)
    assert all(flag.code != "SUSPECTED_FORGERY" for flag in result.risk_flags)


def test_assessment_policy_routes_low_authenticity_to_suspected_forgery_review() -> None:
    from app.agents.evidence_clerk.assessment_policy import EvidenceAssessmentPolicy
    from app.harness.evidence_context_assembler import EvidenceContextAssembler
    from app.schemas import EvidenceItemAssessment, EvidenceTurnRequest

    assembled = EvidenceContextAssembler().assemble(
        EvidenceTurnRequest.model_validate(_java_evidence_turn_command_payload())
    )
    assessment = EvidenceItemAssessment(
        evidence_id="EVIDENCE_signature_photo",
        analysis_method="MULTIMODAL",
        inspected_modalities=["IMAGE_PIXELS", "FILE_METADATA"],
        authenticity_score=0.49,
        relevance_score=0.95,
        completeness_score=0.8,
        assessment_confidence=0.85,
        source_basis=["原始图片和文件元数据。"],
        fact_links=[
            {
                "fact_id": "FACT_SIGNATURE",
                "relation": "SUPPORTS",
                "reason": "图片内容对应物流签收记录。",
                "confidence": 0.76,
            }
        ],
        supported_fact_ids=[],
        unsupported_claims=["当前材料不能完整证明签收人身份。"],
        formation_time_assessment="形成时间仍需核对。",
        recommendation="SUSPICIOUS",
        summary="材料真实性和案情相关性均存在核验缺口。",
    )

    result = EvidenceAssessmentPolicy().apply(
        [assessment],
        assembled.working_set,
        {
            "items": [
                {
                    "evidence_id": "EVIDENCE_signature_photo",
                    "visual_input_status": "LOADED",
                    "inspected_modalities": ["IMAGE_PIXELS", "FILE_METADATA"],
                }
            ]
        },
    )[0]

    assert result.recommendation == "NEEDS_HUMAN_REVIEW"
    assert result.human_review.required is True
    assert "LOW_AUTHENTICITY_SUSPECTED_FORGERY" in (
        result.human_review.reason_codes
    )
    suspected_flag = next(
        flag for flag in result.risk_flags if flag.code == "SUSPECTED_FORGERY"
    )
    assert suspected_flag.description == "疑似造假"


def test_assessment_policy_does_not_label_missing_assessment_as_suspected_forgery() -> None:
    from app.agents.evidence_clerk.assessment_policy import EvidenceAssessmentPolicy
    from app.harness.evidence_context_assembler import EvidenceContextAssembler
    from app.schemas import EvidenceTurnRequest

    assembled = EvidenceContextAssembler().assemble(
        EvidenceTurnRequest.model_validate(_java_evidence_turn_command_payload())
    )
    result = EvidenceAssessmentPolicy().apply(
        [],
        assembled.working_set,
        {"items": []},
    )[0]

    assert all(flag.code != "SUSPECTED_FORGERY" for flag in result.risk_flags)
    assert all(
        reason != "LOW_AUTHENTICITY_SUSPECTED_FORGERY"
        for reason in result.human_review.reason_codes
    )


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：回归测试用例。
# 具体功能：`test_assessment_policy_blocks_fact_ids_outside_intake_dossier_allowlist` 验证案件卷宗在固定案例中的输出、边界和失败行为；关键协作调用：`assemble`、`EvidenceItemAssessment`、`EvidenceTurnRequest.model_validate`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_java_evidence_turn_command_payload`。
# 系统意义：固定“Agent 角色能力 > test_evidence_clerk_turn”的可观察契约，防止后续重构改变业务结果。
def test_assessment_policy_blocks_fact_ids_outside_intake_dossier_allowlist() -> None:
    from app.agents.evidence_clerk.assessment_policy import EvidenceAssessmentPolicy
    from app.harness.evidence_context_assembler import EvidenceContextAssembler
    from app.schemas import EvidenceItemAssessment, EvidenceTurnRequest

    payload = _java_evidence_turn_command_payload()
    payload["context_envelope"]["intake_dossier_snapshot"]["payload"][
        "unilateral_case_matrix"
    ] = _unilateral_case_matrix(
        ("FACT_SIGNED", "物流记录是否足以证明本人收货", "CORE"),
    )
    assembled = EvidenceContextAssembler().assemble(
        EvidenceTurnRequest.model_validate(payload)
    )
    assert assembled.working_set.allowed_fact_targets == (
            {
                "fact_id": "FACT_SIGNED",
                "fact": "物流记录是否足以证明本人收货",
                "category": "LOGISTICS",
                "match_text": "物流记录是否足以证明本人收货",
                "materiality": "CORE",
                "truth_status": "NOT_EVALUATED",
            },
    )
    assessment = EvidenceItemAssessment(
        evidence_id="EVIDENCE_signature_photo",
        analysis_method="HYBRID",
        inspected_modalities=["OCR_TEXT", "IMAGE_PIXELS", "FILE_METADATA"],
        fact_links=[
            {
                "fact_id": "FACT_SIGNED",
                "relation": "SUPPORTS",
                "reason": "图片包含签收记录。",
                "confidence": 0.78,
            },
            {
                "fact_id": "FACT_MODEL_INVENTED",
                "relation": "SUPPORTS",
                "reason": "模型自行创造的事实。",
                "confidence": 0.99,
            },
        ],
        authenticity_score=0.82,
        relevance_score=0.88,
        completeness_score=0.74,
        assessment_confidence=0.8,
        source_basis=["原始图片、OCR 文本和文件元数据。"],
        supported_fact_ids=["FACT_SIGNED", "FACT_MODEL_INVENTED"],
        unsupported_claims=[],
        formation_time_assessment="图片时间需要与物流平台记录交叉核对。",
        recommendation="PLAUSIBLE",
        summary="签收图片与既有待证事实相关。",
    )

    result = EvidenceAssessmentPolicy().apply(
        [assessment],
        assembled.working_set,
        {
            "items": [
                {
                    "evidence_id": "EVIDENCE_signature_photo",
                    "visual_input_status": "LOADED",
                    "inspected_modalities": [
                        "OCR_TEXT",
                        "IMAGE_PIXELS",
                        "FILE_METADATA",
                    ],
                }
            ]
        },
    )[0]

    assert [item.fact_id for item in result.fact_links] == ["FACT_SIGNED"]
    assert result.recommendation == "NEEDS_HUMAN_REVIEW"
    assert "UNKNOWN_FACT_REFERENCE" in result.human_review.reason_codes
    assert any("事实白名单" in item for item in result.limitations)


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：回归测试用例。
# 具体功能：`test_assessment_policy_routes_visual_damage_to_human_review` 验证人工复核信息在固定案例中的输出、边界和失败行为；关键协作调用：`assemble`、`EvidenceItemAssessment`、`EvidenceTurnRequest.model_validate`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_java_evidence_turn_command_payload`。
# 系统意义：固定“Agent 角色能力 > test_evidence_clerk_turn”的可观察契约，防止后续重构改变业务结果。
def test_assessment_policy_routes_visual_damage_to_human_review() -> None:
    from app.agents.evidence_clerk.assessment_policy import EvidenceAssessmentPolicy
    from app.harness.evidence_context_assembler import EvidenceContextAssembler
    from app.schemas import EvidenceItemAssessment, EvidenceTurnRequest

    payload = _java_evidence_turn_command_payload()
    payload["context_envelope"]["intake_dossier_snapshot"]["payload"]["case_story"][
        "one_sentence_summary"
    ] = "用户称商品表面存在细微划痕，需要核验照片。"
    payload["context_envelope"]["intake_dossier_snapshot"]["payload"][
        "unilateral_case_matrix"
    ] = _unilateral_case_matrix(
        ("FACT_DAMAGE", "用户称拆箱后发现商品表面存在细微划痕", "CORE"),
    )
    assembled = EvidenceContextAssembler().assemble(
        EvidenceTurnRequest.model_validate(payload)
    )
    assessment = EvidenceItemAssessment(
        evidence_id="EVIDENCE_signature_photo",
        analysis_method="MULTIMODAL",
        inspected_modalities=["IMAGE_PIXELS"],
        authenticity_score=0.84,
        relevance_score=0.91,
        completeness_score=0.79,
        assessment_confidence=0.83,
        source_basis=["原始图片像素和文件元数据。"],
        fact_links=[
            {
                "fact_id": "FACT_DAMAGE",
                "relation": "INCONCLUSIVE",
                "reason": "图片可显示疑似划痕，但不能单独确认形成时间。",
                "confidence": 0.74,
            }
        ],
        supported_fact_ids=[],
        unsupported_claims=["图片不能单独证明划痕形成时间和责任。"],
        formation_time_assessment="无法仅凭图片确认划痕形成时间。",
        recommendation="PLAUSIBLE",
        summary="画面可见疑似细微划痕。",
    )

    result = EvidenceAssessmentPolicy().apply(
        [assessment],
        assembled.working_set,
        {
            "items": [
                {
                    "evidence_id": "EVIDENCE_signature_photo",
                    "visual_input_status": "LOADED",
                    "inspected_modalities": ["IMAGE_PIXELS", "FILE_METADATA"],
                }
            ]
        },
    )[0]

    assert result.recommendation == "NEEDS_HUMAN_REVIEW"
    assert result.human_review.required is True
    assert "FINE_VISUAL_DAMAGE_REQUIRES_HUMAN" in result.human_review.reason_codes
    assert any("形成时间" in item for item in result.limitations)


def test_assessment_policy_uses_asset_manifest_as_modality_authority() -> None:
    from app.agents.evidence_clerk.assessment_policy import EvidenceAssessmentPolicy
    from app.harness.evidence_context_assembler import EvidenceContextAssembler
    from app.schemas import EvidenceItemAssessment, EvidenceTurnRequest

    assembled = EvidenceContextAssembler().assemble(
        EvidenceTurnRequest.model_validate(_java_evidence_turn_command_payload())
    )
    assessment = EvidenceItemAssessment(
        evidence_id="EVIDENCE_signature_photo",
        analysis_method="TEXT_ONLY",
        inspected_modalities=[],
        authenticity_score=0.8,
        relevance_score=0.1,
        completeness_score=0.8,
        assessment_confidence=0.9,
        source_basis=["图片像素和文件元数据。"],
        supported_fact_ids=[],
        unsupported_claims=["界面截图不能支持物流签收事实。"],
        formation_time_assessment="图片形成时间尚待核验。",
        recommendation="SUSPICIOUS",
        summary="图片是与案件无关的界面截图。",
    )

    result = EvidenceAssessmentPolicy().apply(
        [assessment],
        assembled.working_set,
        {
            "items": [
                {
                    "evidence_id": "EVIDENCE_signature_photo",
                    "visual_input_status": "LOADED",
                    "inspected_modalities": ["IMAGE_PIXELS", "FILE_METADATA"],
                }
            ]
        },
    )[0]

    assert result.analysis_method == "MULTIMODAL"
    assert result.inspected_modalities == ["IMAGE_PIXELS", "FILE_METADATA"]
    assert "VISUAL_NOT_INSPECTED" not in result.human_review.reason_codes


# 所属模块：Agent 角色能力 > test_evidence_clerk_turn；函数角色：回归测试用例。
# 具体功能：`test_assessment_policy_rejects_model_claim_of_visual_inspection_when_not_loaded` 读取并按案件、角色或会话范围筛选模型状态；关键协作调用：`assemble`、`EvidenceItemAssessment`、`EvidenceTurnRequest.model_validate`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_java_evidence_turn_command_payload`。
# 系统意义：固定“Agent 角色能力 > test_evidence_clerk_turn”的可观察契约，防止后续重构改变业务结果。
def test_assessment_policy_rejects_model_claim_of_visual_inspection_when_not_loaded() -> None:
    from app.agents.evidence_clerk.assessment_policy import EvidenceAssessmentPolicy
    from app.harness.evidence_context_assembler import EvidenceContextAssembler
    from app.schemas import EvidenceItemAssessment, EvidenceTurnRequest

    assembled = EvidenceContextAssembler().assemble(
        EvidenceTurnRequest.model_validate(_java_evidence_turn_command_payload())
    )
    assessment = EvidenceItemAssessment(
        evidence_id="EVIDENCE_signature_photo",
        analysis_method="MULTIMODAL",
        inspected_modalities=["IMAGE_PIXELS"],
        authenticity_score=0.99,
        relevance_score=0.95,
        completeness_score=0.96,
        assessment_confidence=0.98,
        source_basis=["模型声称读取图片，但资产加载记录不支持该说法。"],
        fact_links=[
            {
                "fact_id": "FACT_SIGNATURE",
                "relation": "SUPPORTS",
                "reason": "模型声称图片内容对应物流签收记录。",
                "confidence": 0.9,
            }
        ],
        supported_fact_ids=[],
        unsupported_claims=["无法确认图片中的签收内容。"],
        formation_time_assessment="未读取原图，无法核验形成时间。",
        findings=[
            {
                "finding_type": "CLAIMED_VISUAL_FINDING",
                "description": "模型自称看到了签收图片。",
            }
        ],
        recommendation="PLAUSIBLE",
        summary="不可信的模型自报视觉结论。",
    )

    result = EvidenceAssessmentPolicy().apply(
        [assessment],
        assembled.working_set,
        {
            "items": [
                {
                    "evidence_id": "EVIDENCE_signature_photo",
                    "visual_input_status": "FETCH_FAILED",
                    "inspected_modalities": [],
                }
            ]
        },
    )[0]

    assert result.analysis_method == "TEXT_ONLY"
    assert "IMAGE_PIXELS" not in result.inspected_modalities
    assert result.findings == []
    assert result.authenticity_score <= 0.5
    assert result.completeness_score <= 0.5
    assert result.assessment_confidence <= 0.4
    assert result.recommendation == "NEEDS_HUMAN_REVIEW"
    assert result.asset_audit["visual_input_status"] == "FETCH_FAILED"


def test_evidence_envelope_rejects_cross_actor_private_history() -> None:
    from app.schemas import EvidenceTurnRequest

    payload = _evidence_turn_payload()
    turn = payload["context_envelope"]["private_conversation"]["recent_turns"][0]
    turn["actor_id"] = "MERCHANT_other"
    turn["answer_role"] = "MERCHANT"
    turn["answer_content"] = "另一方私聊不得进入当前会话。"

    with pytest.raises(ValidationError, match="actor_id must match"):
        EvidenceTurnRequest.model_validate(payload)

    payload = _evidence_turn_payload()
    turn = payload["context_envelope"]["private_conversation"]["recent_turns"][0]
    turn["agent_role"] = "PRESIDING_JUDGE"
    turn["agent_response"] = "错误角色回复。"
    with pytest.raises(ValidationError, match="agent_role must be EVIDENCE_CLERK"):
        EvidenceTurnRequest.model_validate(payload)


def test_respondent_evidence_context_only_receives_shared_intake_projection() -> None:
    from app.harness.evidence_context_assembler import EvidenceContextAssembler
    from app.schemas import EvidenceTurnRequest

    payload = _evidence_turn_payload()
    dossier = payload["context_envelope"]["intake_dossier_snapshot"]["payload"]
    dossier.pop("unilateral_case_matrix")
    dossier["case_fact_matrix"] = _case_fact_matrix_v2()
    private_secret = "INITIATOR_PRIVATE_TEXT_MUST_NOT_LEAK"
    dossier["claim_resolution"] = {"original_statement": private_secret}
    dossier["handoff_notes"] = {"latest_remark": private_secret}
    dossier["party_positions"] = {"raw_statement": private_secret}
    _switch_evidence_actor(
        payload,
        actor_id="MERCHANT_local_1",
        actor_role="MERCHANT",
    )

    assembled = EvidenceContextAssembler().assemble(
        EvidenceTurnRequest.model_validate(payload)
    )
    canonical = assembled.context_sources["canonical_case_dossier"]
    serialized = json.dumps(canonical, ensure_ascii=False)

    assert private_secret not in serialized
    assert "original_statement" not in serialized
    assert "handoff_notes" not in canonical
    assert canonical["case_fact_matrix"]["schema_version"] == "case_fact_matrix.v2"
    assert "包裹是否由用户本人签收" in serialized


def test_evidence_context_rejects_tampered_case_fact_matrix_v2() -> None:
    from app.harness.evidence_context_assembler import EvidenceContextAssembler
    from app.schemas import EvidenceTurnRequest

    cross_case = _evidence_turn_payload()
    dossier = cross_case["context_envelope"]["intake_dossier_snapshot"]["payload"]
    dossier.pop("unilateral_case_matrix")
    matrix = _case_fact_matrix_v2()
    matrix["case_id"] = "CASE_other_evidence"
    _rehash_case_fact_matrix(matrix)
    dossier["case_fact_matrix"] = matrix
    with pytest.raises(ValueError, match="case_id must match"):
        EvidenceContextAssembler().assemble(
            EvidenceTurnRequest.model_validate(cross_case)
        )

    bad_hash = _evidence_turn_payload()
    dossier = bad_hash["context_envelope"]["intake_dossier_snapshot"]["payload"]
    dossier.pop("unilateral_case_matrix")
    matrix = _case_fact_matrix_v2()
    matrix["content_hash"] = "f" * 64
    dossier["case_fact_matrix"] = matrix
    with pytest.raises(ValueError, match="content hash is invalid"):
        EvidenceContextAssembler().assemble(
            EvidenceTurnRequest.model_validate(bad_hash)
        )

    wrong_party_map = _evidence_turn_payload()
    dossier = wrong_party_map["context_envelope"]["intake_dossier_snapshot"]["payload"]
    dossier.pop("unilateral_case_matrix")
    dossier["case_fact_matrix"] = _case_fact_matrix_v2()
    envelope = wrong_party_map["context_envelope"]
    envelope["case_snapshot"]["initiator_role"] = "MERCHANT"
    envelope["actor_snapshot"]["initiator_role"] = "MERCHANT"
    envelope["room_policy"]["initiator_role"] = "MERCHANT"
    with pytest.raises(ValueError, match="party_map must match"):
        EvidenceContextAssembler().assemble(
            EvidenceTurnRequest.model_validate(wrong_party_map)
        )


def test_legacy_wrapped_evidence_matrix_preserves_old_links_when_merged() -> None:
    from app.agents.evidence_clerk.workflow import _merge_fact_matrix
    from app.harness.evidence_context_assembler import _evidence_matrix_snapshot

    old_link = {
        "fact_id": "FACT_OLD",
        "evidence_id": "EVIDENCE_OLD",
        "relation": "SUPPORTS",
        "reason": "旧关联。",
        "confidence": 0.8,
    }
    normalized = _evidence_matrix_snapshot(
        {
            "evidence_matrix_snapshot": {
                "version": 7,
                "matrix": [old_link],
            }
        }
    )
    merged = _merge_fact_matrix(
        normalized,
        [
            {
                "operation": "UPSERT_LINK",
                "fact_id": "FACT_NEW",
                "evidence_id": "EVIDENCE_NEW",
                "relation": "INCONCLUSIVE",
                "reason": "新关联。",
                "confidence": 0.7,
            }
        ],
    )

    assert normalized["version"] == 7
    assert normalized["matrix"] == [old_link]
    assert merged["version"] == 8
    assert {
        (item["fact_id"], item["evidence_id"])
        for item in merged["links"]
    } == {
        ("FACT_OLD", "EVIDENCE_OLD"),
        ("FACT_NEW", "EVIDENCE_NEW"),
    }


def test_noop_evidence_matrix_patches_do_not_increment_version() -> None:
    from app.agents.evidence_clerk.workflow import _merge_fact_matrix

    old_link = {
        "fact_id": "FACT_OLD",
        "evidence_id": "EVIDENCE_OLD",
        "relation": "SUPPORTS",
        "reason": "旧关联。",
        "confidence": 0.8,
    }
    snapshot = {"version": 7, "matrix": {"links": [old_link]}}
    missing_remove = {
        "operation": "REMOVE_LINK",
        "fact_id": "FACT_MISSING",
        "evidence_id": "EVIDENCE_MISSING",
        "relation": "INCONCLUSIVE",
        "reason": "不存在的关联。",
        "confidence": 0.1,
    }
    same_upsert = {"operation": "UPSERT_LINK", **old_link}

    for patch in (missing_remove, same_upsert):
        result = _merge_fact_matrix(snapshot, [patch])
        assert result["version"] == 7
        assert result["updated"] is False
        assert result["links"] == [old_link]

    changed = _merge_fact_matrix(
        snapshot,
        [{**same_upsert, "relation": "OPPOSES", "reason": "关系发生变化。"}],
    )
    assert changed["version"] == 8
    assert changed["updated"] is True


def test_existing_evidence_links_are_filtered_by_fact_and_visibility() -> None:
    from app.harness.evidence_context_assembler import EvidenceContextAssembler
    from app.schemas import EvidenceTurnRequest

    payload = _evidence_turn_payload()
    dossier = payload["context_envelope"]["intake_dossier_snapshot"]["payload"]
    dossier.pop("unilateral_case_matrix")
    dossier["case_fact_matrix"] = _case_fact_matrix_v2()
    valid_link = {
        "fact_id": "FACT_SIGNATURE",
        "evidence_id": "EVIDENCE_signature_photo",
        "relation": "SUPPORTS",
        "reason": "当前方可见证据与既有事实相关。",
        "confidence": 0.8,
    }
    payload["context_envelope"]["private_conversation"]["recent_turns"][0][
        "scroll_snapshot"
    ] = {
        "evidence_matrix_snapshot": {
            "version": 4,
            "matrix": [
                valid_link,
                {**valid_link, "fact_id": "FACT_UNKNOWN"},
                {**valid_link, "evidence_id": "EVIDENCE_OTHER_PARTY"},
            ],
        }
    }

    assembled = EvidenceContextAssembler().assemble(
        EvidenceTurnRequest.model_validate(payload)
    )
    snapshot = assembled.working_set.evidence_matrix_snapshot

    assert snapshot["version"] == 4
    assert snapshot["matrix"] == [valid_link]
    assert assembled.context_sources["evidence_gap_plan"]["covered_fact_ids"] == [
        "FACT_SIGNATURE"
    ]
