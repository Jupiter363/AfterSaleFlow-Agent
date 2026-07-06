from __future__ import annotations

from pathlib import Path
from types import SimpleNamespace

from fastapi.testclient import TestClient

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
    return {
        "case_id": case_id,
        "room_type": "EVIDENCE",
        "actor_role": "USER",
        "actor_id": "USER_local_1",
        "current_party_message": {
            "message_id": "MESSAGE_evidence_turn",
            "role": "USER",
            "text": "我上传了签收页截图，但图片有点糊，想证明我没有收到包裹。",
        },
        "case_intake_dossier": {
            "schema_version": "intake_case_detail.v1",
            "case_story": {
                "one_sentence_summary": "物流显示签收，但用户称未收到包裹。"
            },
            "dispute_focus": {
                "core_issue": "SIGNED_NOT_RECEIVED",
                "facts_to_verify": ["签收底单", "物流轨迹", "收件人身份"],
            },
        },
        "available_evidence": [
            {
                "evidence_id": "EVIDENCE_signature_photo",
                "evidence_type": "IMAGE",
                "source_type": "USER",
                "content": "签收页截图，文字较模糊。",
                "parsed_text": "疑似签收时间 2026-07-01 10:30",
                "agent_summary": "用户提供的签收页截图。",
                "occurred_at": "2026-07-01T10:30:00+08:00",
                "related_claim_ids": ["CLAIM_receipt"],
                "parser_warning": "OCR 置信度较低，部分文字不可读。",
            }
        ],
        "recent_turns": [
            {
                "turn_no": 1,
                "actor_id": "USER_local_1",
                "answer_role": "USER",
                "answer_content": "user evidence memory asks about signature proof",
                "agent_role": None,
                "agent_response": None,
                "scroll_snapshot": {},
                "agent_session_id": agent_context["agent_session_id"],
                "conversation_scope": agent_context["conversation_scope"],
            }
        ],
        "agent_context": agent_context,
    }


def _java_evidence_turn_command_payload() -> dict[str, object]:
    payload = _evidence_turn_payload()
    payload.update(
        {
            "turn_source": "PARTY_MESSAGE",
            "actor_id": "USER_local_1",
            "current_party_message": {
                "message_id": "EVIDENCE_TURN_2",
                "message_type": "PARTY_EVIDENCE_REFERENCE",
                "role": "USER",
                "text": "我补充这张签收页截图，请帮我看还缺什么证据说明。",
                "attachment_refs": ["EVIDENCE_signature_photo"],
            },
            "available_evidence": [
                {
                    "evidence_id": "EVIDENCE_signature_photo",
                    "evidence_type": "IMAGE",
                    "source_type": "USER",
                    "content": "签收页截图，OCR 显示疑似 2026-07-01 10:30 签收。",
                    "parsed_text": "签收时间 2026-07-01 10:30，签收人字段不清晰。",
                    "occurred_at": "2026-07-01T10:30:00+08:00",
                    "submitted_by_role": "USER",
                    "visibility": "PARTIES",
                    "content_url": (
                        "/api/disputes/CASE_evidence_turn_llm/evidence/"
                        "EVIDENCE_signature_photo/content"
                    ),
                    "redacted": False,
                    "parse_status": "PARSED",
                    "original_filename": "signature-page.jpg",
                }
            ],
        }
    )
    return payload


def _java_evidence_opening_command_payload() -> dict[str, object]:
    payload = _java_evidence_turn_command_payload()
    payload.update(
        {
            "turn_source": "ROOM_OPENING",
            "current_party_message": {
                "message_id": "EVIDENCE_OPENING_1",
                "message_type": "AGENT_MESSAGE",
                "role": "USER",
                "text": "请根据接待室收敛案情，向当前一方提出首轮证据补充与真实性核验问题。",
                "attachment_refs": [],
            },
            "case_intake_dossier": {
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
            },
            "available_evidence": [],
            "recent_turns": [],
        }
    )
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
        context_sections,
        agent_context=None,
        prompt_profile_id=None,
    ):
        self.calls.append(
            {
                "node_name": node_name,
                "case_data": case_data,
                "output_type": output_type,
                "context_sections": context_sections,
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
        context_sections,
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


def test_evidence_turn_workflow_uses_harness_node_with_memory_dossier_and_evidence_context() -> None:
    from app.agents.evidence_clerk.workflow import EvidenceTurnWorkflow
    from app.schemas import EvidenceTurnRequest

    runner = FakeEvidenceRunner()
    workflow = EvidenceTurnWorkflow(model_runner=runner)

    result = workflow.run(EvidenceTurnRequest.model_validate(_evidence_turn_payload()))

    assert runner.calls[0]["node_name"] == "evidence_turn"
    assert runner.calls[0]["output_type"].__name__ == "EvidenceTurnLlmOutput"
    section_by_name = {
        section.name: section.content
        for section in runner.calls[0]["context_sections"]  # type: ignore[index]
    }
    assert {"memeo_memory", "case_intake_dossier", "available_evidence"} <= set(
        section_by_name
    )
    assert "user evidence memory asks about signature proof" in section_by_name["memeo_memory"]
    assert "SIGNED_NOT_RECEIVED" in section_by_name["case_intake_dossier"]
    assert "EVIDENCE_signature_photo" in section_by_name["available_evidence"]
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
    assert runner.calls[0]["case_data"]["current_party_message"]["message_id"] == (
        "EVIDENCE_OPENING_1"
    )


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


def test_evidence_opening_fallback_localizes_internal_dispute_codes() -> None:
    from app.agents.evidence_clerk.workflow import EvidenceTurnWorkflow
    from app.schemas import EvidenceTurnRequest

    payload = _java_evidence_opening_command_payload()
    payload["case_intake_dossier"]["case_story"][
        "one_sentence_summary"
    ] = "物流系统显示包裹已签收，但用户反馈本人没有收到。"
    payload["case_intake_dossier"]["dispute_focus"] = {
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
    payload["case_intake_dossier"]["case_story"][
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
    assert "可读" in combined_questions or "OCR" in combined_questions
    assert "关联" in result.room_utterance
    assert "责任在" not in result.room_utterance
    assert "应当退款" not in result.room_utterance
    assert "最终判定" not in result.room_utterance
    assert "核对" in combined_suggestions
    assert result.authenticity_flags
    assert result.non_final is True
    assert result.liability_determined is False
    assert result.remedy_recommended is False


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
