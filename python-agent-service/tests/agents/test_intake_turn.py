from __future__ import annotations

from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app


def _settings() -> Settings:
    return Settings(
        litellm_master_key="test-litellm-master-key",
        langfuse_public_key="pk-test-key",
        langfuse_secret_key="sk-test-secret",
        python_agent_service_secret="test-agent-service-secret",
        langfuse_enabled=False,
    )


def _client() -> TestClient:
    return TestClient(create_app(_settings()))


def _headers() -> dict[str, str]:
    return {
        "X-Service-Secret": "test-agent-service-secret",
        "X-Role": "SYSTEM",
    }


def test_lobby_seed_turn_generates_readable_first_question_and_scroll_snapshot() -> None:
    client = _client()

    response = client.post(
        "/internal/agents/intake/turn",
        headers=_headers(),
        json={
            "case_id": "CASE_intake_turn_seed",
            "room_type": "INTAKE",
            "turn_source": "LOBBY_SEED",
            "lobby_seed": {
                "order_reference": "ORDER_123",
                "after_sales_reference": "AS_456",
                "logistics_reference": "SF_789",
                "initiator_role": "USER",
                "raw_text": "物流显示签收，但我没有收到包裹，希望退款。",
                "requested_outcome_hint": "REFUND",
            },
            "current_user_message": None,
            "latest_scroll_snapshot": None,
            "recent_turns": [],
        },
    )

    assert response.status_code == 200
    payload = response.json()
    assert payload["admission_recommendation"] == "NEED_MORE_INFO"
    assert "我已收到大厅表单" in payload["room_utterance"]
    assert "物流" in payload["room_utterance"]
    assert "凭证" in payload["room_utterance"]
    assert payload["dossier_patch"]["order_reference"] == "ORDER_123"
    assert payload["dossier_patch"]["requested_outcome"] == "REFUND"
    assert payload["dossier_patch"]["party_claims"]["user"].startswith("物流显示签收")
    assert [card["key"] for card in payload["scroll_snapshot"]["cards"]] == [
        "order_reference",
        "after_sales_reference",
        "logistics_reference",
        "requested_outcome",
        "initiator_role",
        "user_claim",
    ]
    assert {
        operation["target_key"] for operation in payload["canvas_operations"]
    } >= {"order_reference", "requested_outcome", "user_claim"}
    assert payload["knowledge_query_intent"] is False


def test_user_message_turn_merges_previous_scroll_snapshot_as_memory() -> None:
    client = _client()

    response = client.post(
        "/internal/agents/intake/turn",
        headers=_headers(),
        json={
            "case_id": "CASE_intake_turn_memory",
            "room_type": "INTAKE",
            "turn_source": "USER_MESSAGE",
            "lobby_seed": {
                "order_reference": "ORDER_123",
                "after_sales_reference": "AS_456",
                "initiator_role": "USER",
                "raw_text": "物流显示签收，但我没有收到包裹。",
            },
            "current_user_message": {
                "message_id": "MESSAGE_123",
                "role": "USER",
                "text": "我补充：快递柜没有取件码，商家客服让我找平台处理，我希望退款。",
            },
            "latest_scroll_snapshot": {
                "cards": [
                    {
                        "key": "requested_outcome",
                        "label": "期望结果",
                        "value": "REPLACEMENT",
                        "status": "confirmed",
                    },
                    {
                        "key": "merchant_claim",
                        "label": "商家主张",
                        "value": "商家认为物流已签收。",
                        "status": "confirmed",
                    },
                ],
                "stamps": [{"type": "risk", "text": "SIGNED_NOT_RECEIVED", "level": "MEDIUM"}],
                "next_questions": [],
            },
            "recent_turns": [],
        },
    )

    assert response.status_code == 200
    payload = response.json()
    cards = {card["key"]: card for card in payload["scroll_snapshot"]["cards"]}
    assert cards["requested_outcome"]["value"] == "REFUND"
    assert cards["requested_outcome"]["status"] == "updated"
    assert cards["merchant_claim"]["value"] == "商家认为物流已签收。"
    assert cards["merchant_claim"]["status"] == "confirmed"
    assert cards["user_claim"]["value"].startswith("我补充")
    assert "MERCHANT_COMMUNICATION" not in payload["missing_fields"]
    assert any(
        operation["target_key"] == "requested_outcome"
        and operation["type"] == "UPSERT_CARD"
        for operation in payload["canvas_operations"]
    )


def test_user_message_turn_does_not_repeat_delivery_proof_when_memory_already_has_it() -> None:
    client = _client()

    response = client.post(
        "/internal/agents/intake/turn",
        headers=_headers(),
        json={
            "case_id": "CASE_intake_turn_no_repeat_proof",
            "room_type": "INTAKE",
            "turn_source": "USER_MESSAGE",
            "lobby_seed": {
                "order_reference": "ORDER_123",
                "after_sales_reference": "AS_456",
                "logistics_reference": "LOG_789",
                "initiator_role": "USER",
                "raw_text": "物流显示签收，但我没有收到包裹，希望退款。",
                "requested_outcome_hint": "REFUND",
            },
            "current_user_message": {
                "message_id": "MESSAGE_merchant_contact",
                "role": "USER",
                "text": "我联系过商家，商家回复说物流已签收不支持退款；快递说需要商家发起核查。",
            },
            "latest_scroll_snapshot": {
                "cards": [
                    {
                        "key": "user_claim",
                        "label": "用户主张",
                        "value": "我没有收到包裹，快递柜没有取件码，物业也没有代收记录，可以提供快递柜截图和物业证明。",
                        "status": "confirmed",
                    }
                ],
                "stamps": [{"type": "risk", "text": "SIGNED_NOT_RECEIVED", "level": "MEDIUM"}],
                "next_questions": ["请说明是否联系过商家，以及商家的回复。"],
                "admission_recommendation": "NEED_MORE_INFO",
            },
            "recent_turns": [
                {
                    "turn_no": 2,
                    "actor_id": "user-local",
                    "answer_role": "USER",
                    "answer_content": "补充：快递柜没有取件码，物业也没有代收记录，我可以提供快递柜截图和物业证明。",
                    "agent_role": None,
                    "agent_response": None,
                    "scroll_snapshot": {},
                }
            ],
        },
    )

    assert response.status_code == 200
    payload = response.json()
    assert "DELIVERY_PROOF" not in payload["missing_fields"]
    assert "补充物流签收截图" not in payload["room_utterance"]


def test_intake_turn_response_exposes_memeo_memory_frame_for_ui() -> None:
    client = _client()
    recent_turns = []
    for turn_no in range(1, 8):
        recent_turns.append(
            {
                "turn_no": turn_no,
                "actor_id": "user-local",
                "answer_role": "USER",
                "answer_content": f"user memory round {turn_no}",
                "agent_role": None,
                "agent_response": None,
                "scroll_snapshot": {},
            }
        )
        recent_turns.append(
            {
                "turn_no": turn_no,
                "actor_id": "dispute-intake-officer",
                "answer_role": None,
                "answer_content": None,
                "agent_role": "DISPUTE_INTAKE_OFFICER",
                "agent_response": f"agent memory round {turn_no}",
                "scroll_snapshot": {},
            }
        )

    response = client.post(
        "/internal/agents/intake/turn",
        headers=_headers(),
        json={
            "case_id": "CASE_intake_turn_memory_frame",
            "room_type": "INTAKE",
            "turn_source": "USER_MESSAGE",
            "lobby_seed": {
                "order_reference": "ORDER_123",
                "initiator_role": "USER",
                "raw_text": "Package signed but not received.",
            },
            "current_user_message": {
                "message_id": "MESSAGE_memory_frame",
                "role": "USER",
                "text": "I contacted the merchant and want a refund.",
            },
            "latest_scroll_snapshot": None,
            "recent_turns": recent_turns,
        },
    )

    assert response.status_code == 200
    payload = response.json()
    memory_frame = payload["memory_frame"]
    assert memory_frame["memory_modes"] == {
        "short_term_enabled": True,
        "summary_enabled": True,
        "long_term_enabled": False,
        "short_term_round_limit": 5,
        "summary_window_round_limit": 10,
        "compressed_token_limit": 200,
    }
    assert [item["turn_no"] for item in memory_frame["short_term_rounds"]] == [
        3,
        4,
        5,
        6,
        7,
    ]
    assert "user memory round 2" not in memory_frame["prompt_memory"]
    assert "user memory round 7" in memory_frame["prompt_memory"]
    assert memory_frame["long_term_slots"] == []


def test_process_question_marks_knowledge_query_intent_without_real_rag() -> None:
    client = _client()

    response = client.post(
        "/internal/agents/intake/turn",
        headers=_headers(),
        json={
            "case_id": "CASE_intake_turn_qa",
            "room_type": "INTAKE",
            "turn_source": "USER_MESSAGE",
            "lobby_seed": {
                "initiator_role": "USER",
                "raw_text": "我想咨询处理时效。",
            },
            "current_user_message": {
                "message_id": "MESSAGE_qa",
                "role": "USER",
                "text": "这种签收未收到的平台规则一般多久处理？",
            },
            "latest_scroll_snapshot": None,
            "recent_turns": [],
        },
    )

    assert response.status_code == 200
    payload = response.json()
    assert payload["knowledge_query_intent"] is True
    assert payload["knowledge_answer_mode"] == "STUB"
    assert "知识库插件" in payload["room_utterance"]
    assert "先按平台流程说明" in payload["room_utterance"]


def test_intake_turn_route_requires_service_secret() -> None:
    client = _client()

    response = client.post(
        "/internal/agents/intake/turn",
        headers={"X-Service-Secret": "wrong-secret"},
        json={
            "case_id": "CASE_intake_turn_auth",
            "room_type": "INTAKE",
            "turn_source": "LOBBY_SEED",
            "lobby_seed": {
                "initiator_role": "USER",
                "raw_text": "我要发起争议。",
            },
            "current_user_message": None,
            "latest_scroll_snapshot": None,
            "recent_turns": [],
        },
    )

    assert response.status_code == 401
