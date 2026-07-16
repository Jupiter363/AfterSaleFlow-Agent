# 文件作用：自动化测试文件，验证 test_agent_invocation_context 相关模块的行为、契约或页面布局。

from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.schemas import EvidenceTurnRequest, IntakeTurnRequest


# 所属模块：Agent Harness > test_agent_invocation_context；函数角色：模块私有业务函数。
# 具体功能：`_agent_context` 围绕案件与会话上下文计算该函数独立负责的业务派生值；返回/更新字段：`tenant_id`、`case_id`、`room_type`、`actor_id`。
# 上下游：上游为 本文件的 `_intake_payload`、`_evidence_payload`；下游为 返回/更新 `tenant_id`、`case_id`、`room_type`、`actor_id`。
# 系统意义：控制隐私、Token 和会话隔离：隔离参与方会话；不可信案件文本不能升级为系统指令。
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


# 所属模块：Agent Harness > test_agent_invocation_context；函数角色：模块私有业务函数。
# 具体功能：`_intake_payload` 读取并按案件、角色或会话范围筛选接待信息；返回/更新字段：`case_id`、`room_type`、`turn_source`、`initial_case_facts`。
# 上下游：上游为 本文件的 `test_intake_turn_request_requires_agent_context`、`test_request_rejects_case_id_and_room_type_mismatch`、`test_agent_context_rejects_blank_agent_session_id`；下游为 本文件的 `_agent_context`。
# 系统意义：该函数在系统中的业务边界是：隔离参与方会话；不可信案件文本不能升级为系统指令。
def _intake_payload() -> dict[str, object]:
    case_id = "CASE_context_contract"
    return {
        "case_id": case_id,
        "room_type": "INTAKE",
        "turn_source": "EXTERNAL_IMPORT",
        "initial_case_facts": {
            "initiator_role": "USER",
        },
        "current_user_message": {
            "message_id": "MESSAGE_IMPORTED_INITIAL",
            "sequence_no": 1,
            "role": "USER",
            "source": "EXTERNAL_IMPORT",
            "text": "Package signed but not received.",
        },
        "recent_dialogue_messages": [],
        "previous_case_detail": None,
        "agent_context": _agent_context(case_id=case_id),
    }


# 所属模块：Agent Harness > test_agent_invocation_context；函数角色：模块私有业务函数。
# 具体功能：`_evidence_payload` 读取并按案件、角色或会话范围筛选当前可见证据；返回/更新字段：`context_envelope`、`agent_context`。
# 上下游：上游为 本文件的 `test_evidence_turn_request_requires_agent_context`、`test_evidence_turn_request_rejects_actor_id_mismatch`、`test_evidence_turn_request_rejects_actor_role_mismatch`、`test_request_rejects_case_id_and_room_type_mismatch`；下游为 本文件的 `_agent_context`。
# 系统意义：该函数在系统中的业务边界是：隔离参与方会话；不可信案件文本不能升级为系统指令。
def _evidence_payload() -> dict[str, object]:
    case_id = "CASE_context_evidence"
    agent_context = _agent_context(
        case_id=case_id,
        room_type="EVIDENCE",
        actor_id="USER_local_1",
        actor_role="USER",
        agent_key="EVIDENCE_CLERK",
        agent_session_id="SESSION_context_evidence_user",
        prompt_profile_id="EVIDENCE_CLERK:USER:v1",
    )
    return {
        "context_envelope": {
            "schema_version": "evidence_context_envelope.v1",
            "captured_at": "2026-07-11T10:00:00+08:00",
            "case_snapshot": {
                "case_id": case_id,
                "case_version": 1,
                "case_status": "EVIDENCE_IN_PROGRESS",
                "case_type": "AFTER_SALE_DISPUTE",
                "dispute_type": None,
                "title": "Evidence context test",
                "description": "Evidence context test description",
                "risk_level": "MEDIUM",
                "route_type": None,
                "order_id": None,
                "after_sale_id": None,
                "logistics_id": None,
                "source_type": "LOCAL",
                "initiator_role": "USER",
                "source_system": None,
                "external_case_ref": None,
                "current_room": "EVIDENCE",
                "current_deadline_at": None,
            },
            "intake_dossier_snapshot": None,
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
                "event_id": "MESSAGE_context_evidence",
                "event_type": "PARTY_MESSAGE",
                "message_type": "PARTY_TEXT",
                "actor_id": "USER_local_1",
                "actor_role": "USER",
                "text": "I uploaded a signature screenshot.",
                "attachment_refs": [],
                "turn_no": 1,
                "occurred_at": "2026-07-11T10:00:00+08:00",
            },
            "visible_evidence": [],
            "private_conversation": {
                "agent_session_id": agent_context["agent_session_id"],
                "conversation_scope": agent_context["conversation_scope"],
                "source_count": 0,
                "truncated": False,
                "recent_turns": [],
            },
            "room_policy": {
                "room_id": "ROOM_context_evidence",
                "room_type": "EVIDENCE",
                "room_status": "OPEN",
                "current_deadline_at": None,
                "initiator_role": "USER",
                "initiator_evidence_required": True,
            },
        },
        "agent_context": agent_context,
    }


# 所属模块：Agent Harness > test_agent_invocation_context；函数角色：回归测试用例。
# 具体功能：`test_intake_turn_request_requires_agent_context` 验证案件与会话上下文在固定案例中的输出、边界和失败行为；关键协作调用：`payload.pop`、`pytest.raises`、`IntakeTurnRequest.model_validate`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 本文件的 `_intake_payload`。
# 系统意义：固定“Agent Harness > test_agent_invocation_context”的可观察契约，防止后续重构改变业务结果。
def test_intake_turn_request_requires_agent_context() -> None:
    payload = _intake_payload()
    payload.pop("agent_context")

    with pytest.raises(ValidationError) as failure:
        IntakeTurnRequest.model_validate(payload)

    assert "agent_context" in str(failure.value)


# 所属模块：Agent Harness > test_agent_invocation_context；函数角色：回归测试用例。
# 具体功能：`test_evidence_turn_request_requires_agent_context` 验证当前可见证据在固定案例中的输出、边界和失败行为；关键协作调用：`payload.pop`、`pytest.raises`、`EvidenceTurnRequest.model_validate`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 本文件的 `_evidence_payload`。
# 系统意义：固定“Agent Harness > test_agent_invocation_context”的可观察契约，防止后续重构改变业务结果。
def test_evidence_turn_request_requires_agent_context() -> None:
    payload = _evidence_payload()
    payload.pop("agent_context")

    with pytest.raises(ValidationError) as failure:
        EvidenceTurnRequest.model_validate(payload)

    assert "agent_context" in str(failure.value)


# 所属模块：Agent Harness > test_agent_invocation_context；函数角色：回归测试用例。
# 具体功能：`test_evidence_turn_request_rejects_actor_id_mismatch` 验证当前可见证据在固定案例中的输出、边界和失败行为；关键协作调用：`pytest.raises`、`EvidenceTurnRequest.model_validate`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 本文件的 `_evidence_payload`。
# 系统意义：固定“Agent Harness > test_agent_invocation_context”的可观察契约，防止后续重构改变业务结果。
def test_evidence_turn_request_rejects_actor_id_mismatch() -> None:
    payload = _evidence_payload()
    payload["context_envelope"]["actor_snapshot"]["actor_id"] = "USER_other"

    with pytest.raises(ValidationError) as failure:
        EvidenceTurnRequest.model_validate(payload)

    assert "actor_id must match agent_context.actor_id" in str(failure.value)


# 所属模块：Agent Harness > test_agent_invocation_context；函数角色：回归测试用例。
# 具体功能：`test_evidence_turn_request_rejects_actor_role_mismatch` 验证当前可见证据在固定案例中的输出、边界和失败行为；关键协作调用：`pytest.raises`、`EvidenceTurnRequest.model_validate`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 本文件的 `_evidence_payload`。
# 系统意义：固定“Agent Harness > test_agent_invocation_context”的可观察契约，防止后续重构改变业务结果。
def test_evidence_turn_request_rejects_actor_role_mismatch() -> None:
    payload = _evidence_payload()
    payload["context_envelope"]["actor_snapshot"]["actor_role"] = "MERCHANT"

    with pytest.raises(ValidationError) as failure:
        EvidenceTurnRequest.model_validate(payload)

    assert "actor_role must match agent_context.actor_role" in str(failure.value)


def test_evidence_turn_request_accepts_hearing_evidence_supplement_scope() -> None:
    payload = _evidence_payload()
    envelope = payload["context_envelope"]
    context = payload["agent_context"]
    old_scope = context["conversation_scope"]
    hearing_scope = old_scope.replace(":EVIDENCE:", ":HEARING:")

    envelope["case_snapshot"]["current_room"] = "HEARING"
    envelope["room_policy"]["room_type"] = "HEARING"
    envelope["room_policy"]["room_id"] = "ROOM_context_hearing"
    envelope["actor_snapshot"]["conversation_scope"] = hearing_scope
    envelope["private_conversation"]["conversation_scope"] = hearing_scope
    context["room_type"] = "HEARING"
    context["conversation_scope"] = hearing_scope

    request = EvidenceTurnRequest.model_validate(payload)

    assert request.context_envelope.room_policy.room_type == "HEARING"
    assert request.agent_context.room_type == "HEARING"


# 所属模块：Agent Harness > test_agent_invocation_context；函数角色：回归测试用例。
# 具体功能：`test_request_rejects_case_id_and_room_type_mismatch` 验证本阶段状态在固定案例中的输出、边界和失败行为；关键协作调用：`pytest.raises`、`IntakeTurnRequest.model_validate`、`EvidenceTurnRequest.model_validate`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 本文件的 `_intake_payload`、`_evidence_payload`。
# 系统意义：固定“Agent Harness > test_agent_invocation_context”的可观察契约，防止后续重构改变业务结果。
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
    }
    with pytest.raises(ValidationError) as evidence_failure:
        EvidenceTurnRequest.model_validate(evidence_payload)

    assert "room_policy.room_type must match agent_context.room_type" in str(
        evidence_failure.value
    )


# 所属模块：Agent Harness > test_agent_invocation_context；函数角色：回归测试用例。
# 具体功能：`test_evidence_turn_request_rejects_legacy_and_mixed_payloads` 读取并按案件、角色或会话范围筛选当前可见证据；关键协作调用：`pytest.raises`、`EvidenceTurnRequest.model_validate`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 本文件的 `_evidence_payload`。
# 系统意义：固定“Agent Harness > test_agent_invocation_context”的可观察契约，防止后续重构改变业务结果。
def test_evidence_turn_request_rejects_legacy_and_mixed_payloads() -> None:
    legacy_payload = {
        "case_id": "CASE_context_evidence",
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
        "agent_context": _evidence_payload()["agent_context"],
    }

    with pytest.raises(ValidationError) as legacy_failure:
        EvidenceTurnRequest.model_validate(legacy_payload)

    assert "context_envelope" in str(legacy_failure.value)
    assert "Extra inputs are not permitted" in str(legacy_failure.value)

    mixed_payload = _evidence_payload()
    mixed_payload["case_id"] = "CASE_context_evidence"

    with pytest.raises(ValidationError) as mixed_failure:
        EvidenceTurnRequest.model_validate(mixed_payload)

    assert "case_id" in str(mixed_failure.value)
    assert "Extra inputs are not permitted" in str(mixed_failure.value)


# 所属模块：Agent Harness > test_agent_invocation_context；函数角色：回归测试用例。
# 具体功能：`test_evidence_turn_request_rejects_wrong_envelope_version_and_opening_text` 验证当前可见证据在固定案例中的输出、边界和失败行为；关键协作调用：`update`、`pytest.raises`、`EvidenceTurnRequest.model_validate`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 本文件的 `_evidence_payload`。
# 系统意义：固定“Agent Harness > test_agent_invocation_context”的可观察契约，防止后续重构改变业务结果。
def test_evidence_turn_request_rejects_wrong_envelope_version_and_opening_text() -> None:
    wrong_version = _evidence_payload()
    wrong_version["context_envelope"]["schema_version"] = "evidence_context_envelope.v2"

    with pytest.raises(ValidationError) as version_failure:
        EvidenceTurnRequest.model_validate(wrong_version)

    assert "evidence_context_envelope.v1" in str(version_failure.value)

    invalid_opening = _evidence_payload()
    invalid_opening["context_envelope"]["current_event"].update(
        {
            "event_type": "ROOM_OPENING",
            "message_type": "AGENT_MESSAGE",
            "text": "fake opening text",
        }
    )

    with pytest.raises(ValidationError) as opening_failure:
        EvidenceTurnRequest.model_validate(invalid_opening)

    assert "ROOM_OPENING text must be null" in str(opening_failure.value)


# 所属模块：Agent Harness > test_agent_invocation_context；函数角色：回归测试用例。
# 具体功能：`test_agent_context_rejects_blank_agent_session_id` 验证案件与会话上下文在固定案例中的输出、边界和失败行为；关键协作调用：`pytest.raises`、`IntakeTurnRequest.model_validate`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 本文件的 `_intake_payload`。
# 系统意义：固定“Agent Harness > test_agent_invocation_context”的可观察契约，防止后续重构改变业务结果。
def test_agent_context_rejects_blank_agent_session_id() -> None:
    payload = _intake_payload()
    payload["agent_context"] = {
        **payload["agent_context"],  # type: ignore[arg-type]
        "agent_session_id": "   ",
    }

    with pytest.raises(ValidationError) as failure:
        IntakeTurnRequest.model_validate(payload)

    assert "agent_session_id must not be blank" in str(failure.value)
