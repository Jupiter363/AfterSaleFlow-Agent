from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.schemas import EvidenceTurnRequest, IntakeTurnRequest


def _agent_context(
    *,
    case_id: str = "CASE_context_contract",
    room_type: str = "INTAKE",
    actor_id: str = "USER_local_1",
    actor_role: str = "USER",
    agent_key: str = "DISPUTE_INTAKE_OFFICER",
    agent_session_id: str = "SESSION_context_user",
    prompt_profile_id: str = "DISPUTE_INTAKE_OFFICER:USER:v1",
) -> dict[str, object]:
    access_session_id = f"ACCESS_{actor_id}"
    return {
        "tenant_id": "default",
        "case_id": case_id,
        "room_type": room_type,
        "actor_id": actor_id,
        "actor_role": actor_role,
        "access_session_id": access_session_id,
        "permission_level": "PARTY_USER" if actor_role == "USER" else "PARTY_MERCHANT",
        "permission_scopes": [],
        "agent_key": agent_key,
        "agent_invocation_id": "INVOCATION_context_1",
        "agent_session_id": agent_session_id,
        "conversation_scope": (
            f"default:{case_id}:{room_type}:{actor_id}:{actor_role}:"
            f"{agent_key}:{prompt_profile_id}:{access_session_id}"
        ),
        "scope_type": (
            "INTAKE_INITIATOR_PRIVATE"
            if room_type == "INTAKE"
            else "EVIDENCE_PARTY_PRIVATE"
        ),
        "allowed_actor_ids": [actor_id],
        "allowed_actor_roles": [actor_role],
        "prompt_profile_id": prompt_profile_id,
        "memory_policy_id": "MEMORY_POLICY_TEST_V1",
    }


def _intake_payload() -> dict[str, object]:
    case_id = "CASE_context_contract"
    return {
        "case_id": case_id,
        "room_type": "INTAKE",
        "turn_source": "LOBBY_SEED",
        "lobby_seed": {
            "initiator_role": "USER",
            "raw_text": "Package signed but not received.",
        },
        "current_user_message": None,
        "latest_scroll_snapshot": None,
        "recent_turns": [],
        "agent_context": _agent_context(case_id=case_id),
    }


def _evidence_payload() -> dict[str, object]:
    case_id = "CASE_context_evidence"
    return {
        "case_id": case_id,
        "room_type": "EVIDENCE",
        "turn_source": "PARTY_MESSAGE",
        "actor_role": "USER",
        "actor_id": "USER_local_1",
        "current_party_message": {
            "message_id": "MESSAGE_context_evidence",
            "role": "USER",
            "text": "I uploaded a signature screenshot.",
        },
        "case_intake_dossier": {},
        "available_evidence": [],
        "recent_turns": [],
        "agent_context": _agent_context(
            case_id=case_id,
            room_type="EVIDENCE",
            actor_id="USER_local_1",
            actor_role="USER",
            agent_key="EVIDENCE_CLERK",
            agent_session_id="SESSION_context_evidence_user",
            prompt_profile_id="EVIDENCE_CLERK:USER:v1",
        ),
    }


def test_intake_turn_request_requires_agent_context() -> None:
    payload = _intake_payload()
    payload.pop("agent_context")

    with pytest.raises(ValidationError) as failure:
        IntakeTurnRequest.model_validate(payload)

    assert "agent_context" in str(failure.value)


def test_evidence_turn_request_requires_agent_context() -> None:
    payload = _evidence_payload()
    payload.pop("agent_context")

    with pytest.raises(ValidationError) as failure:
        EvidenceTurnRequest.model_validate(payload)

    assert "agent_context" in str(failure.value)


def test_evidence_turn_request_rejects_actor_id_mismatch() -> None:
    payload = _evidence_payload()
    payload["actor_id"] = "USER_other"

    with pytest.raises(ValidationError) as failure:
        EvidenceTurnRequest.model_validate(payload)

    assert "actor_id must match agent_context.actor_id" in str(failure.value)


def test_evidence_turn_request_rejects_actor_role_mismatch() -> None:
    payload = _evidence_payload()
    payload["actor_role"] = "MERCHANT"

    with pytest.raises(ValidationError) as failure:
        EvidenceTurnRequest.model_validate(payload)

    assert "actor_role must match agent_context.actor_role" in str(failure.value)


def test_request_rejects_case_id_and_room_type_mismatch() -> None:
    payload = _intake_payload()
    payload["agent_context"] = {
        **payload["agent_context"],  # type: ignore[arg-type]
        "case_id": "CASE_context_other",
    }

    with pytest.raises(ValidationError) as failure:
        IntakeTurnRequest.model_validate(payload)

    assert "case_id must match agent_context.case_id" in str(failure.value)

    evidence_payload = _evidence_payload()
    evidence_payload["agent_context"] = {
        **evidence_payload["agent_context"],  # type: ignore[arg-type]
        "room_type": "INTAKE",
        "scope_type": "INTAKE_INITIATOR_PRIVATE",
    }
    with pytest.raises(ValidationError) as evidence_failure:
        EvidenceTurnRequest.model_validate(evidence_payload)

    assert "room_type must match agent_context.room_type" in str(evidence_failure.value)


def test_agent_context_rejects_blank_agent_session_id() -> None:
    payload = _intake_payload()
    payload["agent_context"] = {
        **payload["agent_context"],  # type: ignore[arg-type]
        "agent_session_id": "   ",
    }

    with pytest.raises(ValidationError) as failure:
        IntakeTurnRequest.model_validate(payload)

    assert "agent_session_id must not be blank" in str(failure.value)
