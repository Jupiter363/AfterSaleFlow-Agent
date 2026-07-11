from __future__ import annotations

import json
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.config import Settings
from app.harness.prompt_composer import PromptRepository
from app.main import create_app


def _settings() -> Settings:
    return Settings(
        litellm_master_key="test-litellm-master-key",
        langfuse_public_key="pk-test-key",
        langfuse_secret_key="sk-test-secret",
        python_agent_service_secret="test-agent-service-secret",
        langfuse_enabled=False,
    )


def _headers() -> dict[str, str]:
    return {"X-Service-Secret": "test-agent-service-secret"}


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
    }
    envelope["visible_evidence"] = []
    envelope["private_conversation"]["source_count"] = 0
    envelope["private_conversation"]["recent_turns"] = []
    return payload


class FakeEvidenceRunner:
    def __init__(self) -> None:
        self.calls: list[dict[str, object]] = []

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
                confidence=0.64,
            )
        )


class GenericOpeningRunner:
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
                confidence=0.31,
            )
        )


class MaximalOpeningRunner:
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
                confidence=0.8,
            )
        )


def test_evidence_turn_workflow_uses_harness_node_with_memory_dossier_and_evidence_context() -> None:
    from app.agents.evidence_clerk.workflow import EvidenceTurnWorkflow
    from app.schemas import EvidenceTurnRequest

    runner = FakeEvidenceRunner()
    workflow = EvidenceTurnWorkflow(model_runner=runner)

    result = workflow.run(EvidenceTurnRequest.model_validate(_evidence_turn_payload()))

    assert runner.calls[0]["node_name"] == "evidence_turn"
    assert runner.calls[0]["output_type"].__name__ == "EvidenceTurnLlmOutput"
    context_pack = runner.calls[0]["context_pack"]
    assert context_pack.configuration_profile_key == "EVIDENCE_CLERK_CONTEXT_PACK_V1"
    section_by_name = {
        section.name: section.content
        for section in runner.calls[0]["context_sections"]  # type: ignore[index]
    }
    assert {
        "current_turn",
        "actor_private_memory",
        "canonical_case_dossier",
        "actor_visible_evidence",
    } <= set(section_by_name)
    assert (
        "user evidence memory asks about signature proof"
        in section_by_name["actor_private_memory"]
    )
    current_turn = json.loads(section_by_name["current_turn"])
    assert current_turn["raw_statement"] == (
        "我上传了签收页截图，但图片有点糊，想证明我没有收到包裹。"
    )
    assert current_turn["platform_statement"].startswith("用户称")
    assert "我上传" not in current_turn["platform_statement"]
    assert "物流显示签收但用户称未收到包裹" in section_by_name["canonical_case_dossier"]
    assert "SIGNED_NOT_RECEIVED" not in section_by_name["canonical_case_dossier"]
    actor_visible_evidence = json.loads(section_by_name["actor_visible_evidence"])
    assert actor_visible_evidence["items"][0]["evidence_id"] == (
        "EVIDENCE_signature_photo"
    )
    assert actor_visible_evidence["items"][0]["source_type"] == "用户"
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


def test_evidence_opening_turn_passes_source_to_llm_context() -> None:
    from app.agents.evidence_clerk.workflow import EvidenceTurnWorkflow
    from app.schemas import EvidenceTurnRequest

    runner = FakeEvidenceRunner()
    workflow = EvidenceTurnWorkflow(model_runner=runner)

    workflow.run(EvidenceTurnRequest.model_validate(_java_evidence_opening_command_payload()))

    assert runner.calls[0]["case_data"]["turn_source"] == "ROOM_OPENING"
    assert runner.calls[0]["case_data"]["event_id"] == "EVIDENCE_OPENING_1"


def test_evidence_opening_fallback_asks_dossier_specific_evidence_questions() -> None:
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


def test_evidence_opening_fallback_states_initiator_evidence_gate_without_deciding() -> None:
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


def test_evidence_opening_fallback_localizes_internal_dispute_codes() -> None:
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


def test_evidence_opening_fallback_normalizes_summary_punctuation() -> None:
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


def test_evidence_opening_replaces_generic_llm_welcome_with_dossier_questions() -> None:
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


def test_evidence_opening_coerce_bounds_merged_model_and_baseline_output() -> None:
    from app.agents.evidence_clerk.workflow import EvidenceTurnWorkflow
    from app.schemas import EvidenceTurnRequest

    workflow = EvidenceTurnWorkflow(model_runner=MaximalOpeningRunner())

    result = workflow.run(
        EvidenceTurnRequest.model_validate(_java_evidence_opening_command_payload())
    )

    assert len(result.evidence_requests) <= 10
    assert len(result.authenticity_flags) <= 20


def test_evidence_turn_fallback_asks_authenticity_and_relevance_questions_without_deciding() -> None:
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


def test_evidence_context_assembler_preserves_null_parsed_text_and_adds_notice() -> None:
    from app.harness.evidence_context_assembler import EvidenceContextAssembler
    from app.schemas import EvidenceTurnRequest

    payload = _evidence_turn_payload()
    evidence = payload["context_envelope"]["visible_evidence"][0]
    evidence["parsed_text"] = None
    evidence["parse_status"] = "PROCESSING"

    assembled = EvidenceContextAssembler().assemble(
        EvidenceTurnRequest.model_validate(payload)
    )

    evidence_context = assembled.context_sources["actor_visible_evidence"]
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
    evidence_context = assembled.context_sources["actor_visible_evidence"]

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


def test_evidence_fallback_bounds_deterministic_output_above_one_hundred_items() -> None:
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


def test_evidence_context_assembler_uses_raw_case_when_intake_dossier_is_missing() -> None:
    from app.harness.evidence_context_assembler import EvidenceContextAssembler
    from app.schemas import EvidenceTurnRequest

    payload = _evidence_turn_payload()
    payload["context_envelope"]["intake_dossier_snapshot"] = None
    payload["context_envelope"]["case_snapshot"]["description"] = "案情描述" * 10_000

    assembled = EvidenceContextAssembler().assemble(
        EvidenceTurnRequest.model_validate(payload)
    )
    dossier = assembled.context_sources["canonical_case_dossier"]

    assert len(dossier["case_story"]["one_sentence_summary"]) == 4_000
    assert dossier["case_story"]["summary_truncated"] is True
    assert dossier["case_story"]["summary_char_count"] == 40_000
    assert dossier["case_snapshot"]["case_version"] == 7
    assert dossier["intake_dossier_provenance"]["available"] is False
    assert len(assembled.raw_envelope.case_snapshot.description) == 40_000
    assert assembled.context_sources["room_deadline"][
        "initiator_evidence_required"
    ] is True


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


def test_evidence_turn_api_requires_service_secret_and_returns_fallback_payload() -> None:
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

    assert response.status_code == 200
    payload = response.json()
    assert payload["non_final"] is True
    assert payload["evidence_requests"]
    assert payload["verification_suggestions"][0]["evidence_id"] == (
        "EVIDENCE_signature_photo"
    )
    assert payload["memory_frame"]["short_term_rounds"][0]["turn_no"] == 1


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


def test_evidence_turn_endpoint_accepts_java_command_payload_without_degrading() -> None:
    from app.agents.evidence_clerk.workflow import EvidenceTurnWorkflow

    client = TestClient(
        create_app(
            _settings(),
            evidence_turn_workflow=EvidenceTurnWorkflow(),
        )
    )

    response = client.post(
        "/internal/agents/evidence/turn",
        headers=_headers(),
        json=_java_evidence_turn_command_payload(),
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
    assert payload["memory_frame"].get("agent_degraded") is not True
    assert payload["liability_determined"] is False
    assert payload["remedy_recommended"] is False
    assert "责任在" not in utterance
    assert "应当退款" not in utterance
    assert "最终判定" not in utterance
    assert "来源" in combined_questions
    assert "完整" in combined_suggestions
    assert "关联" in utterance + combined_questions + combined_suggestions


def test_prompt_repository_registers_evidence_turn_prompt() -> None:
    assert PromptRepository().template_path("evidence_turn") == Path(
        "app/agents/prompts/evidence_clerk/evidence_turn.md"
    )


def test_evidence_turn_prompt_treats_initiator_evidence_as_admission_gate() -> None:
    prompt = Path(
        "app/agents/prompts/evidence_clerk/evidence_turn.md"
    ).read_text(encoding="utf-8")

    assert "发起争议方须至少正式提交 1 份相关证据后才能完成举证" in prompt
    assert "另一方可补充材料，或等待举证时效结束" in prompt
    assert "受理门槛" in prompt
    assert "不是证据强弱或责任评价" in prompt
    assert "不得宣称无证据、证据不足仍可开庭或进入小法庭" in prompt
    assert "证据不足也不阻止进入小法庭" not in prompt
    assert "只做证据核验" in prompt
    assert "不判断责任" in prompt
