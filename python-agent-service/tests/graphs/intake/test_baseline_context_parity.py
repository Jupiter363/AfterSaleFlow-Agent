from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime, timezone
from types import SimpleNamespace
from typing import Any, cast

import pytest
from langchain_core.messages import BaseMessage, HumanMessage, SystemMessage

import app.graph_runtime.intake_binding as intake_binding
from app.agents.dispute_intake_officer.schemas import IntakeCaseDetailLlmOutput
from app.graphs.intake.baseline import (
    BASELINE_INTAKE_NODE_NAME,
    _target_missing_field_identifiers,
    adapt_intake_baseline_output,
    build_intake_baseline_memory_summary,
    build_intake_baseline_request,
    prepare_intake_baseline_invocation,
)
from app.contracts.v1.codec import canonical_sha256
from app.graphs.intake.lcel import build_intake_model_node
from app.graphs.intake.state import IntakeGraphStateV2, new_intake_graph_state
from app.harness.invocation_context import AgentInvocationContext
from app.harness.model_runner import (
    PreparedHarnessInvocation,
    prepare_baseline_prompt_authority,
)
from app.harness.prompt_composer import PromptRepository
from app.llm import GovernedProviderRequest, LiteLlmProxyClient
from app.model_runtime.profiles import ModelInvocationPolicy, ModelProfile
from app.model_runtime.transports import ModelTransportRequest


_CASE_ID = "CASE_BASELINE_PARITY_1"
_MODEL_PROFILE_ID = "intake-model.baseline-parity.v1"
_MODEL = "baseline-parity-model"
_BASELINE_NODE = BASELINE_INTAKE_NODE_NAME
_OUTPUT_SCHEMA_VERSION = "intake-case-detail.v1"
_POLICY_VERSION = "intake-policy.baseline-parity.v1"
_GUARDRAIL_VERSION = "intake-guardrail.baseline-parity.v1"
_DEADLINE = datetime(2099, 1, 1, tzinfo=timezone.utc)
_SNAPSHOT_HASH = "a" * 64
_EVENT_HASH = "b" * 64


@dataclass(frozen=True, slots=True)
class _AuthoritativeTurn:
    """One authority record projected into both baseline and Target inputs."""

    name: str
    audience: str
    initial_case_facts: dict[str, Any] | None
    previous_case_detail: dict[str, Any]
    messages: tuple[dict[str, Any], ...]


def _message(
    *,
    message_id: str,
    role: str,
    audience: str,
    sequence: int,
    content: str,
) -> dict[str, Any]:
    return {
        "message_id": message_id,
        "role": role,
        "audience": audience,
        "sequence": sequence,
        "content": content,
        "source_hash": _EVENT_HASH,
    }


_USER_FIRST = _AuthoritativeTurn(
    name="user-first-digital-human-turn",
    audience="USER",
    initial_case_facts={
        "form_source": "EXTERNAL_IMPORT",
        "form_description": "订单页面承诺次日达，实际延迟五天且影响预定使用安排。",
        "order_reference": "ORDER_BASELINE_PARITY_1",
        "after_sales_reference": "AFTER_BASELINE_PARITY_1",
        "logistics_reference": "LOG_BASELINE_PARITY_1",
        "initiator_role": "USER",
        "requested_outcome_hint": "REFUND",
    },
    previous_case_detail={},
    messages=(),
)

_USER_SECOND = _AuthoritativeTurn(
    name="user-second-turn",
    audience="USER",
    initial_case_facts=None,
    previous_case_detail={
        "schema_version": "intake_case_detail.v1",
        "case_story": {
            "title": "订单延迟送达争议",
            "one_sentence_summary": "用户称订单延迟五天送达并影响预定使用安排。",
        },
        "missing_information": {
            "missing_fields": ["current_product_state"],
            "next_questions": ["商品当前是否已经签收？"],
        },
    },
    messages=(
        _message(
            message_id="MESSAGE_AI_USER_1",
            role="AI",
            audience="USER",
            sequence=1,
            content="请问商品当前是否已经签收？",
        ),
        _message(
            message_id="MESSAGE_USER_2",
            role="HUMAN",
            audience="USER",
            sequence=2,
            content="已经签收，但延迟导致原定活动无法使用。",
        ),
    ),
)

_MERCHANT_TURN = _AuthoritativeTurn(
    name="merchant-response-turn",
    audience="MERCHANT",
    initial_case_facts=None,
    previous_case_detail={
        "schema_version": "intake_case_detail.v1",
        "case_story": {
            "title": "订单延迟送达争议",
            "one_sentence_summary": "用户主张订单延迟五天送达并影响预定使用安排。",
        },
        "case_fact_matrix": {
            "schema_version": "case_fact_matrix.v2",
            "fact_rows": [
                {
                    "fact_id": "FACT_DELIVERY_TIME",
                    "fact_key": "FACT_DELIVERY_TIME",
                    "category": "LOGISTICS",
                    "materiality": "CORE",
                    "fact_target": "订单是否较承诺时间延迟五天送达",
                }
            ],
        },
    },
    messages=(
        _message(
            message_id="MESSAGE_AI_MERCHANT_1",
            role="AI",
            audience="MERCHANT",
            sequence=1,
            content="请说明商家对订单送达时间的记录。",
        ),
        _message(
            message_id="MESSAGE_MERCHANT_2",
            role="HUMAN",
            audience="MERCHANT",
            sequence=2,
            content="系统记录显示订单确实晚于承诺时间五天送达。",
        ),
    ),
)


class _NoCallTransport:
    def generate(self, request: ModelTransportRequest):
        del request
        raise AssertionError("prompt parity must not call a model")

    async def agenerate(self, request: ModelTransportRequest):
        del request
        raise AssertionError("prompt parity must not call a model")

    def stream(self, request: ModelTransportRequest):
        del request
        raise AssertionError("prompt parity must not call a model")
        yield

    async def astream(self, request: ModelTransportRequest):
        del request
        raise AssertionError("prompt parity must not call a model")
        yield


def _agent_context(turn: _AuthoritativeTurn) -> AgentInvocationContext:
    role = turn.audience
    actor_id = "USER_BASELINE_PARITY_1" if role == "USER" else "MERCHANT_BASELINE_PARITY_1"
    return AgentInvocationContext.model_validate(
        {
            "tenant_id": "default",
            "case_id": _CASE_ID,
            "room_type": "INTAKE",
            "actor_id": actor_id,
            "actor_role": role,
            "access_session_id": f"ACCESS_BASELINE_PARITY_{role}",
            "permission_level": "PARTY_USER" if role == "USER" else "PARTY_MERCHANT",
            "permission_scopes": [],
            "agent_key": "DISPUTE_INTAKE_OFFICER",
            "agent_invocation_id": f"INVOCATION_BASELINE_PARITY_{role}",
            "agent_session_id": f"SESSION_BASELINE_PARITY_{role}",
            "conversation_scope": f"default:{_CASE_ID}:INTAKE:{actor_id}",
            "scope_type": "INTAKE_PARTY_PRIVATE",
            "allowed_actor_ids": [actor_id],
            "allowed_actor_roles": [role],
            "prompt_profile_id": f"DISPUTE_INTAKE_OFFICER:{role}:v1",
            "memory_policy_id": "INTAKE_MEMORY_BASELINE_PARITY_V1",
            "model_profile_id": _MODEL_PROFILE_ID,
            "output_schema_version": _OUTPUT_SCHEMA_VERSION,
            "policy_version": _POLICY_VERSION,
            "guardrail_version": _GUARDRAIL_VERSION,
            "tool_capabilities": [],
            "retry_budget": {
                "provider_attempts_remaining": 2,
                "activity_attempts_remaining": 0,
                "repairs_remaining": 1,
            },
            "deadline_at": _DEADLINE,
        }
    )


def _capture_target_runtime_controls(
    monkeypatch: pytest.MonkeyPatch,
    turn: _AuthoritativeTurn,
) -> tuple[ModelProfile, ModelInvocationPolicy]:
    context = _agent_context(turn)
    command = SimpleNamespace(
        invocation_context=SimpleNamespace(
            model_profile_id=context.model_profile_id,
            prompt_profile_id=context.prompt_profile_id,
            output_schema_version=context.output_schema_version,
            policy_version=context.policy_version,
            guardrail_version=context.guardrail_version,
        ),
        attempt_id=context.agent_invocation_id,
        deadline_at=context.deadline_at,
        retry_budget=SimpleNamespace(
            provider_attempts_remaining=2,
            repairs_remaining=1,
        ),
        traceparent=None,
    )
    execution = SimpleNamespace(
        admission=SimpleNamespace(
            registry=SimpleNamespace(
                binding=SimpleNamespace(tool_policy_version=intake_binding.INTAKE_TOOL_POLICY)
            )
        )
    )
    captured: dict[str, Any] = {}

    monkeypatch.setattr(
        intake_binding,
        "_execution_command_and_record",
        lambda _execution: (command, object()),
    )
    monkeypatch.setattr(
        intake_binding,
        "build_intake_baseline_agent_context",
        lambda _execution: context,
    )

    def capture_bundle(**kwargs: Any) -> object:
        captured.update(kwargs)
        return object()

    monkeypatch.setattr(intake_binding, "build_intake_runtime_bundle", capture_bundle)
    intake_binding.build_governed_intake_runtime(
        execution=cast(Any, execution),
        transport=cast(Any, _NoCallTransport()),
        provider="litellm",
        model=_MODEL,
        checkpointer=cast(Any, object()),
    )
    return cast(ModelProfile, captured["profile"]), cast(
        ModelInvocationPolicy, captured["policy"]
    )


def _target_state(
    turn: _AuthoritativeTurn,
    *,
    profile: ModelProfile,
    policy: ModelInvocationPolicy,
) -> IntakeGraphStateV2:
    state = new_intake_graph_state(
        bindings={
            "schema_version": "intake-graph-bindings.v2",
            "private": {
                "schema_version": "intake-private-binding.v1",
                "tenant_surrogate": "tenant-baseline-parity",
                "case_id": _CASE_ID,
                "room_type": "INTAKE",
                "room_epoch": 1,
                "actor_scope_hash": "c" * 64,
                "thread_id": "grt.v1.baselineparity0000000000000001",
                "agent_session_id": f"SESSION_BASELINE_PARITY_{turn.audience}",
                "audience": cast(Any, turn.audience),
            },
            "command": {
                "schema_version": "intake-command-binding.v1",
                "command_id": f"COMMAND_BASELINE_PARITY_{turn.audience}",
                "logical_run_id": f"RUN_BASELINE_PARITY_{turn.audience}",
                "attempt_id": policy.invocation_id,
            },
        },
        version_pins={
            "schema_version": "graph-version-pins.v1",
            "graph_key": "intake.v2",
            "graph_version": "2.0.0",
            "checkpoint_schema_version": "intake-checkpoint.v2",
            "state_schema_version": "intake-graph-state.v2",
            "prompt_version": policy.prompt_version,
            "model_profile_id": profile.profile_id,
            "output_schema_version": policy.output_schema_version,
            "policy_version": policy.policy_version,
            "guardrail_version": policy.guardrail_version,
            "tool_policy_version": intake_binding.INTAKE_TOOL_POLICY,
        },
    )
    state["messages"] = {
        cast(str, message["message_id"]): cast(Any, dict(message))
        for message in turn.messages
    }
    state["dossier_draft"] = cast(Any, dict(turn.previous_case_detail))
    state["memory_summary"] = json.dumps(
        {"authorized_initial_case_facts": turn.initial_case_facts or {}},
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    state["initial_snapshot_ref"] = "SNAPSHOT_BASELINE_PARITY_1"
    state["initial_snapshot_hash"] = _SNAPSHOT_HASH
    state["last_event_ref"] = cast(Any, None)
    state["last_event_hash"] = cast(Any, None)
    if turn.messages:
        state["last_event_ref"] = "EVENT_BASELINE_PARITY_1"
        state["last_event_hash"] = _EVENT_HASH
    return state


def _baseline_and_target_requests(
    monkeypatch: pytest.MonkeyPatch,
    turn: _AuthoritativeTurn,
) -> tuple[
    PreparedHarnessInvocation,
    ModelTransportRequest,
    ModelProfile,
    ModelInvocationPolicy,
]:
    context = _agent_context(turn)
    profile, policy = _capture_target_runtime_controls(monkeypatch, turn)
    authority = prepare_baseline_prompt_authority(
        prompts=PromptRepository(),
        node_name=_BASELINE_NODE,
        agent_context=context,
        prompt_profile_id=context.prompt_profile_id,
    )
    built = build_intake_model_node(
        transport=cast(Any, _NoCallTransport()),
        profile=profile,
        policy=policy,
        agent_context=context,
        trusted_system_prompt=authority.system_prompt,
    )
    state = _target_state(turn, profile=profile, policy=policy)
    baseline = prepare_intake_baseline_invocation(
        state,
        agent_context=context,
    )
    assert authority.system_prompt == baseline.system_prompt
    prompt_input = built.lens.invoke(state)
    prompt_value = built.prompt.invoke(prompt_input)
    request = built.model._request(  # noqa: SLF001 - contract probes the provider boundary.
        prompt_value.messages,
        stop=None,
        overrides={},
    )
    return baseline, request, profile, policy


def _baseline_output(
    *,
    summary: str,
    blocking_gaps: list[str] | None = None,
    missing_fields: list[str] | None = None,
) -> IntakeCaseDetailLlmOutput:
    case_detail: dict[str, Any] = {
        "case_story": {"one_sentence_summary": summary},
        "intake_quality": {"score": 45},
    }
    if blocking_gaps is not None:
        case_detail["missing_information"] = {
            "blocking_gaps": blocking_gaps,
        }
    return IntakeCaseDetailLlmOutput.model_validate(
        {
            "room_utterance": "Please provide the remaining case details.",
            "case_detail": case_detail,
            "unilateral_case_matrix": {
                "schema_version": "unilateral_case_matrix.draft.v1",
                "fact_rows": [
                    {
                        "fact_key": "NEW_BASELINE_GAP",
                        "category": "OTHER",
                        "fact_target": "The intake still has unresolved gaps.",
                        "materiality": "CORE",
                        "position_summary": "The user reports unresolved details.",
                        "asserted_value": "Unresolved details remain.",
                        "source_scope": "CURRENT_SOURCE",
                    }
                ],
                "summary_source_fact_keys": ["NEW_BASELINE_GAP"],
            },
            "admission_recommendation": "NEED_MORE_INFO",
            "missing_fields": missing_fields or [],
            "knowledge_answer_mode": "NONE",
            "confidence": 0.7,
        }
    )


def _message_signature(messages: tuple[BaseMessage, ...]) -> tuple[tuple[str, str], ...]:
    return tuple((type(message).__name__, message.type) for message in messages)


def _content_mismatches(
    expected: tuple[BaseMessage, ...],
    actual: tuple[BaseMessage, ...],
) -> list[str]:
    labels = ("system", "human")
    return [
        label
        for label, expected_message, actual_message in zip(
            labels, expected, actual, strict=True
        )
        if actual_message.content != expected_message.content
    ]


@pytest.mark.parametrize(
    "turn",
    (_USER_FIRST, _USER_SECOND, _MERCHANT_TURN),
    ids=lambda turn: turn.name,
)
def test_target_intake_messages_are_byte_for_byte_baseline_context_pack_parity(
    monkeypatch: pytest.MonkeyPatch,
    turn: _AuthoritativeTurn,
) -> None:
    baseline, target, _, _ = _baseline_and_target_requests(monkeypatch, turn)

    assert baseline.output_type is IntakeCaseDetailLlmOutput
    assert baseline.system_prompt == baseline.messages[0].content
    assert baseline.user_prompt == baseline.messages[1].content
    assert _message_signature(baseline.messages) == (
        (SystemMessage.__name__, "system"),
        (HumanMessage.__name__, "human"),
    )
    assert _message_signature(target.messages) == _message_signature(baseline.messages)
    assert _content_mismatches(baseline.messages, target.messages) == []


def test_baseline_request_keeps_the_form_out_of_the_participant_transcript(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    profile, policy = _capture_target_runtime_controls(monkeypatch, _USER_FIRST)
    state = _target_state(_USER_FIRST, profile=profile, policy=policy)
    state["messages"] = {
        "MESSAGE_FORM_1": cast(
            Any,
            _message(
                message_id="MESSAGE_FORM_1",
                role="HUMAN",
                audience="USER",
                sequence=1,
                content=_USER_FIRST.initial_case_facts["form_description"],
            ),
        )
    }

    request = build_intake_baseline_request(
        state,
        agent_context=_agent_context(_USER_FIRST),
    )

    assert request.current_user_message is None
    assert request.initiator_statement_transcript == []


def test_baseline_request_preserves_every_ordered_participant_answer(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    profile, policy = _capture_target_runtime_controls(monkeypatch, _USER_SECOND)
    state = _target_state(_USER_SECOND, profile=profile, policy=policy)
    state["messages"] = {
        "MESSAGE_USER_9": cast(
            Any,
            _message(
                message_id="MESSAGE_USER_9",
                role="HUMAN",
                audience="USER",
                sequence=9,
                content="The ninth participant answer.",
            ),
        )
    }
    state["memory_summary"] = build_intake_baseline_memory_summary(
        {},
        initiator_statement_transcript=[
            {
                "message_id": f"INTAKE_TURN_{turn_no}",
                "role": "USER",
                "text": f"Participant answer {turn_no}.",
            }
            for turn_no in range(2, 9)
        ],
    )

    request = build_intake_baseline_request(
        state,
        agent_context=_agent_context(_USER_SECOND),
    )

    assert [message.message_id for message in request.initiator_statement_transcript] == [
        *(f"INTAKE_TURN_{turn_no}" for turn_no in range(2, 9)),
        "INTAKE_TURN_9",
    ]
    assert [message.role for message in request.initiator_statement_transcript] == [
        "USER"
    ] * 8


def test_baseline_adapter_projects_display_gap_identifiers_stably_across_event_reflow(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    order_number = "\u8ba2\u5355\u53f7"
    supplementary_materials = "\u76f8\u5173\u8865\u5145\u6750\u6599"
    existing_identifier = "known_missing_field"
    profile, policy = _capture_target_runtime_controls(monkeypatch, _USER_FIRST)
    opening_state = _target_state(_USER_FIRST, profile=profile, policy=policy)
    opening_output = _baseline_output(
        summary="The opening snapshot still lacks some details.",
        blocking_gaps=[order_number, supplementary_materials, order_number],
        missing_fields=[existing_identifier, existing_identifier],
    )

    opening = adapt_intake_baseline_output(
        opening_state,
        agent_context=_agent_context(_USER_FIRST),
        output=opening_output,
    )
    opening_retry = adapt_intake_baseline_output(
        opening_state,
        agent_context=_agent_context(_USER_FIRST),
        output=opening_output,
    )
    expected_opening_missing = (
        existing_identifier,
        f"MISSING_{canonical_sha256(order_number)}",
        f"MISSING_{canonical_sha256(supplementary_materials)}",
    )

    assert opening.missing_fields == expected_opening_missing
    assert opening_retry.missing_fields == expected_opening_missing
    assert opening.readiness == opening_retry.readiness == "INCOMPLETE"
    visible_gaps = opening.dossier_patch.missing_information["blocking_gaps"]
    assert visible_gaps
    assert all(not gap.startswith("MISSING_") for gap in visible_gaps)
    assert all(any(ord(character) > 127 for character in gap) for gap in visible_gaps)

    event_state = _target_state(_USER_FIRST, profile=profile, policy=policy)
    event_state["dossier_draft"] = cast(
        Any,
        opening.dossier_patch.model_dump(mode="json", exclude_none=True),
    )
    event_state["messages"] = {
        "MESSAGE_USER_2": cast(
            Any,
            _message(
                message_id="MESSAGE_USER_2",
                role="HUMAN",
                audience="USER",
                sequence=2,
                content="I am following up on the previous intake gaps.",
            ),
        )
    }
    event_state["last_event_ref"] = "EVENT_BASELINE_PARITY_2"
    event_state["last_event_hash"] = _EVENT_HASH

    event = adapt_intake_baseline_output(
        event_state,
        agent_context=_agent_context(_USER_FIRST),
        output=_baseline_output(
            summary="The second event retains the previously unresolved gaps.",
        ),
    )

    expected_event_missing = tuple(
        dict.fromkeys(f"MISSING_{canonical_sha256(gap)}" for gap in visible_gaps)
    )
    assert event.missing_fields == expected_event_missing
    assert set(event.missing_fields) == set(expected_opening_missing[1:])
    assert event.readiness == opening.readiness == "INCOMPLETE"
    assert event.dossier_patch.missing_information["blocking_gaps"] == list(
        dict.fromkeys(visible_gaps)
    )


def test_baseline_missing_identifier_adapter_preserves_a_legal_hash_collision() -> None:
    display_label = "\u8ba2\u5355\u53f7"
    reserved_identifier = f"MISSING_{canonical_sha256(display_label)}"

    adapted = _target_missing_field_identifiers(
        [reserved_identifier, display_label, display_label]
    )

    assert adapted[0] == reserved_identifier
    assert len(adapted) == 2
    assert adapted[1].startswith("MISSING_")
    assert adapted[1] != reserved_identifier
    assert len(adapted[1]) <= 128
    assert _target_missing_field_identifiers(
        [reserved_identifier, display_label, display_label]
    ) == adapted


def _provider_body(
    client: LiteLlmProxyClient,
    request: ModelTransportRequest,
) -> dict[str, Any]:
    return client._completion_request_body(  # noqa: SLF001 - verifies the real wire contract.
        node_name=request.node_name,
        output_type=request.output_type,
        system_prompt=cast(str, request.messages[0].content),
        user_prompt=cast(str, request.messages[1].content),
        user_content_parts=list(request.user_content_parts),
        json_mode=True,
        governed_request=request.governed_request,
    )


def _baseline_wire_request(
    baseline: PreparedHarnessInvocation,
    *,
    profile: ModelProfile,
    policy: ModelInvocationPolicy,
) -> ModelTransportRequest:
    return ModelTransportRequest(
        node_name=_BASELINE_NODE,
        messages=baseline.messages,
        output_type=baseline.output_type,
        governed_request=GovernedProviderRequest(
            provider=profile.provider,
            model=profile.model,
            temperature=profile.temperature,
            max_output_tokens=profile.max_output_tokens,
            response_format="STRICT_JSON_SCHEMA",
            tool_allowlist=profile.tool_allowlist,
            deadline_at=policy.deadline_at,
            provider_attempts_remaining=policy.provider_attempts_remaining,
            repairs_remaining=policy.repairs_remaining,
            traceparent=policy.traceparent,
        ),
    )


def test_target_intake_model_contract_matches_baseline_schema_budget_and_thinking(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    baseline, target, profile, policy = _baseline_and_target_requests(
        monkeypatch,
        _USER_FIRST,
    )
    client = LiteLlmProxyClient(
        base_url="http://model.invalid/v1",
        model=_MODEL,
        api_key="test-only",
    )
    baseline_max_tokens = client.governed_max_output_tokens(_BASELINE_NODE)
    baseline_profile = profile.model_copy(
        update={
            "temperature": 0.0,
            "max_output_tokens": baseline_max_tokens,
            "tool_allowlist": (),
        }
    )
    baseline_request = _baseline_wire_request(
        baseline,
        profile=baseline_profile,
        policy=policy,
    )
    baseline_body = _provider_body(client, baseline_request)
    target_body = _provider_body(client, target)

    parity = {
        "profile_id": profile.profile_id == baseline_profile.profile_id,
        "provider": profile.provider == baseline_profile.provider,
        "model": profile.model == baseline_profile.model,
        "temperature": profile.temperature == baseline_profile.temperature,
        "max_output_tokens": profile.max_output_tokens
        == baseline_profile.max_output_tokens,
        "response_format": profile.response_format
        == baseline_profile.response_format,
        "tool_allowlist": profile.tool_allowlist == baseline_profile.tool_allowlist,
        "provider_attempts": profile.max_provider_attempts
        == baseline_profile.max_provider_attempts,
        "output_type": target.output_type is baseline.output_type,
        "output_schema": target_body["response_format"]["json_schema"]["schema"]
        == baseline_body["response_format"]["json_schema"]["schema"],
        "thinking_disabled": target_body["enable_thinking"]
        is baseline_body["enable_thinking"]
        is False,
    }
    assert parity == {key: True for key in parity}
