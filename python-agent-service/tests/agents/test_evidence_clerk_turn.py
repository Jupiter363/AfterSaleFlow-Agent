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


def _evidence_turn_payload() -> dict[str, object]:
    return {
        "case_id": "CASE_evidence_turn_llm",
        "room_type": "EVIDENCE",
        "actor_role": "USER",
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
                "actor_id": "merchant-local",
                "answer_role": "MERCHANT",
                "answer_content": "商家要求核对签收底单和物流证明。",
                "agent_role": None,
                "agent_response": None,
                "scroll_snapshot": {},
            }
        ],
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


class FakeEvidenceRunner:
    def __init__(self) -> None:
        self.calls: list[dict[str, object]] = []

    def invoke_structured(self, *, node_name, case_data, output_type, context_sections):
        self.calls.append(
            {
                "node_name": node_name,
                "case_data": case_data,
                "output_type": output_type,
                "context_sections": context_sections,
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
    assert "商家要求核对签收底单" in section_by_name["memeo_memory"]
    assert "SIGNED_NOT_RECEIVED" in section_by_name["case_intake_dossier"]
    assert "EVIDENCE_signature_photo" in section_by_name["available_evidence"]
    assert result.non_final is True
    assert result.liability_determined is False
    assert result.remedy_recommended is False
    assert result.evidence_requests[0].target_evidence_id == "EVIDENCE_signature_photo"
    assert result.verification_suggestions[0].evidence_id == "EVIDENCE_signature_photo"
    assert 0 <= result.verification_suggestions[0].confidence_score <= 1


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
