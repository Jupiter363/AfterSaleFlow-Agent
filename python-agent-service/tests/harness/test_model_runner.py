# 文件作用：自动化测试文件，验证 test_model_runner 相关模块的行为、契约或页面布局。

from __future__ import annotations

import base64
from dataclasses import replace
from datetime import datetime, timedelta, timezone
import hashlib

import httpx
import pytest
from pydantic import BaseModel

from app.harness.context_window import ContextWindowManager, PromptSection
from app.harness.evidence_asset_loader import (
    EvidenceAssetLoader,
    LoadedEvidenceAssets,
)
from app.harness.invocation_context import AgentInvocationContext
from app.harness.model_runner import (
    HarnessModelRunner,
    HarnessStreamCompleted,
    HarnessStreamDelta,
    HarnessStreamReset,
)
from app.harness.prompt_composer import PromptRepository
from app.llm import (
    StructuredGeneration,
    StructuredStreamCompleted,
    StructuredStreamDelta,
    StructuredStreamReset,
)
from app.streaming import VisibleFieldSpec
from app.schemas import EvidenceContextEnvelopeV1


class RunnerOutput(BaseModel):
    answer: str


class ArbitraryAgentContext(BaseModel):
    agent_session_id: str
    prompt_profile_id: str
    model_profile_id: str
    tool_capabilities: list[str]
    deadline_at: str


def _trusted_agent_context() -> AgentInvocationContext:
    return AgentInvocationContext.model_validate(
        {
            "tenant_id": "default",
            "case_id": "CASE_model_runner",
            "room_type": "EVIDENCE",
            "actor_id": "USER_local_1",
            "actor_role": "USER",
            "access_session_id": "ACCESS_model_runner",
            "permission_level": "PARTY_USER",
            "permission_scopes": [],
            "agent_key": "EVIDENCE_CLERK",
            "agent_invocation_id": "INVOCATION_model_runner",
            "agent_session_id": "SESSION_evidence_user",
            "conversation_scope": "default:CASE_model_runner:EVIDENCE:USER_local_1",
            "scope_type": "EVIDENCE_PARTY_PRIVATE",
            "allowed_actor_ids": ["USER_local_1"],
            "allowed_actor_roles": ["USER"],
            "prompt_profile_id": "EVIDENCE_CLERK:USER:v1",
            "memory_policy_id": "MEMORY_POLICY_TEST_V1",
            "model_profile_id": "model:test:v1",
            "output_schema_version": "evidence:test:v1",
            "policy_version": "policy:test:v1",
            "guardrail_version": "guardrail:test:v1",
            "tool_capabilities": ["evidence.read"],
            "retry_budget": {
                "provider_attempts_remaining": 1,
                "repairs_remaining": 0,
            },
            "deadline_at": datetime.now(timezone.utc) + timedelta(minutes=1),
            "traceparent": (
                "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"
            ),
        }
    )


def _loaded_png_assets() -> LoadedEvidenceAssets:
    image = base64.b64decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    )
    evidence_hash = hashlib.sha256(image).hexdigest()
    envelope = EvidenceContextEnvelopeV1.model_validate(
        {
            "schema_version": "evidence_context_envelope.v1",
            "captured_at": "2026-07-20T04:00:00+08:00",
            "case_snapshot": {
                "case_id": "CASE_multimodal",
                "case_version": 1,
                "case_status": "EVIDENCE_IN_PROGRESS",
                "case_type": "AFTER_SALE_DISPUTE",
                "dispute_type": None,
                "title": "Multimodal evidence",
                "description": "Authorized image input",
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
                "access_session_id": "ACCESS_multimodal",
                "agent_session_id": "SESSION_multimodal",
                "conversation_scope": "default:CASE_multimodal:EVIDENCE:USER_local_1",
                "prompt_profile_id": "EVIDENCE_CLERK:USER:v1",
                "memory_policy_id": "MEMORY_POLICY_TEST_V1",
            },
            "current_event": {
                "event_id": "MESSAGE_multimodal",
                "event_type": "PARTY_MESSAGE",
                "message_type": "PARTY_EVIDENCE_REFERENCE",
                "actor_id": "USER_local_1",
                "actor_role": "USER",
                "text": "Inspect the attached image.",
                "attachment_refs": ["EVIDENCE_image"],
                "turn_no": 1,
                "occurred_at": "2026-07-20T04:00:00+08:00",
            },
            "visible_evidence": [
                {
                    "evidence_id": "EVIDENCE_image",
                    "dossier_id": "DOSSIER_multimodal",
                    "evidence_type": "IMAGE",
                    "source_type": "PARTY_UPLOAD",
                    "submitted_by_role": "USER",
                    "submitted_by_id": "USER_local_1",
                    "original_filename": "proof.png",
                    "content_type": "image/png",
                    "file_size": len(image),
                    "file_hash": evidence_hash,
                    "parsed_text": None,
                    "parse_status": "PARSED",
                    "visibility": "PARTY_PRIVATE",
                    "desensitized": True,
                    "metadata": {},
                    "extraction": {},
                    "occurred_at": None,
                    "created_at": "2026-07-20T04:00:00+08:00",
                    "submitted_at": "2026-07-20T04:00:00+08:00",
                    "submission_status": "SUBMITTED",
                    "submission_batch_id": None,
                    "content_url": "/internal/evidence/EVIDENCE_image/content",
                }
            ],
            "private_conversation": {
                "agent_session_id": "SESSION_multimodal",
                "conversation_scope": "default:CASE_multimodal:EVIDENCE:USER_local_1",
                "source_count": 0,
                "truncated": False,
                "recent_turns": [],
            },
            "room_policy": {
                "room_id": "ROOM_multimodal",
                "room_type": "EVIDENCE",
                "room_status": "OPEN",
                "current_deadline_at": None,
                "initiator_role": "USER",
                "initiator_evidence_required": True,
            },
        }
    )
    return EvidenceAssetLoader(
        java_api_service_url="http://java-api-service:8080",
        java_service_secret="test-java-service-secret",
        transport=httpx.MockTransport(
            lambda request: httpx.Response(200, content=image)
        ),
    ).load(envelope)


class RecordingLlm:
    supports_governed_provider_request = True
    governed_provider = "test-provider"
    governed_model = "fake-model"
    governed_max_provider_attempts = 2

    # 所属模块：Agent Harness > test_model_runner；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖。
    # 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 受 Token、权限、Schema、审计约束的模型输入或结果。
    # 系统意义：该函数在系统中的业务边界是：隔离参与方会话；不可信案件文本不能升级为系统指令。
    def __init__(self) -> None:
        self.calls: list[dict[str, object]] = []

    def governed_max_output_tokens(self, node_name: str) -> int:
        return 4_096 if node_name == "intake_analyze" else 8_192

    # 所属模块：Agent Harness > test_model_runner；函数角色：类/闭包内部方法。
    # 具体功能：`generate` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`self.calls.append`、`StructuredGeneration`、`output_type`。
    # 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 协作调用 `self.calls.append`、`StructuredGeneration`、`output_type`。
    # 系统意义：该函数在系统中的业务边界是：隔离参与方会话；不可信案件文本不能升级为系统指令。
    def generate(
        self,
        *,
        node_name,
        system_prompt,
        user_prompt,
        output_type,
        user_content_parts=None,
        governed_request=None,
    ):
        self.calls.append(
            {
                "node_name": node_name,
                "system_prompt": system_prompt,
                "user_prompt": user_prompt,
                "output_type": output_type,
                "user_content_parts": user_content_parts,
                "governed_request": governed_request,
            }
        )
        return StructuredGeneration(
            value=output_type(answer="智能接待回复"),
            model="fake-model",
            latency_ms=12,
            token_usage={"input": 30, "output": 5, "total": 35},
        )

    def generate_stream(
        self,
        *,
        node_name,
        system_prompt,
        user_prompt,
        output_type,
        visible_fields=(),
        user_content_parts=None,
        governed_request=None,
    ):
        self.calls.append(
            {
                "node_name": node_name,
                "system_prompt": system_prompt,
                "user_prompt": user_prompt,
                "output_type": output_type,
                "visible_fields": visible_fields,
                "user_content_parts": user_content_parts,
                "governed_request": governed_request,
            }
        )
        yield StructuredStreamDelta(
            kind="visible_delta",
            field="answer",
            delta="智能接待回复",
        )
        yield StructuredStreamCompleted(
            kind="completed",
            generation=StructuredGeneration(
                value=output_type(answer="智能接待回复"),
                model="fake-model",
                latency_ms=12,
                token_usage={"input": 30, "output": 5, "total": 35},
            ),
        )


# 所属模块：Agent Harness > test_model_runner；函数角色：回归测试用例。
# 具体功能：`test_model_runner_composes_prompt_with_managed_context_window` 把上游材料组装为本阶段可消费的模型提示词；关键协作调用：`RecordingLlm`、`HarnessModelRunner`、`runner.invoke_structured`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 协作调用 `RecordingLlm`、`HarnessModelRunner`、`runner.invoke_structured`、`PromptRepository`。
# 系统意义：固定“Agent Harness > test_model_runner”的可观察契约，防止后续重构改变业务结果。
def test_model_runner_composes_prompt_with_managed_context_window() -> None:
    llm = RecordingLlm()
    runner = HarnessModelRunner(
        llm=llm,
        prompts=PromptRepository(),
        context_window=ContextWindowManager(default_max_input_tokens=220),
    )

    result = runner.invoke_structured(
        node_name="intake_analyze",
        case_data={"raw_text": "物流显示签收但用户未收到"},
        output_type=RunnerOutput,
        context_sections=[
            PromptSection(
                name="short_term_memory",
                content="最近一轮：用户坚持未收到包裹。",
                priority=90,
                required=True,
            ),
            PromptSection(
                name="low_priority_history",
                content="很早之前的历史。" * 200,
                priority=1,
                required=False,
            ),
        ],
    )

    assert result.value.answer == "智能接待回复"
    assert result.model == "fake-model"
    assert result.context.omitted_section_names == ("low_priority_history",)
    assert len(llm.calls) == 1
    call = llm.calls[0]
    assert call["node_name"] == "intake_analyze"
    assert call["governed_request"].max_output_tokens == 4_096
    assert "人工智能原生编排框架通用安全边界" in str(call["system_prompt"])
    assert "中立争议接待官" in str(call["system_prompt"])
    assert "harness_context" in str(call["user_prompt"])
    assert "最近一轮：用户坚持未收到包裹。" in str(call["user_prompt"])
    assert "很早之前的历史。" not in str(call["user_prompt"])


# 所属模块：Agent Harness > test_model_runner；函数角色：回归测试用例。
# 具体功能：`test_model_runner_passes_prompt_profile_and_trusted_agent_context` 验证模型提示词在固定案例中的输出、边界和失败行为；关键协作调用：`RecordingLlm`、`HarnessModelRunner`、`runner.invoke_structured`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 协作调用 `RecordingLlm`、`HarnessModelRunner`、`runner.invoke_structured`、`PromptRepository`。
# 系统意义：固定“Agent Harness > test_model_runner”的可观察契约，防止后续重构改变业务结果。
def test_model_runner_passes_prompt_profile_and_trusted_agent_context() -> None:
    llm = RecordingLlm()
    runner = HarnessModelRunner(
        llm=llm,
        prompts=PromptRepository(),
    )
    agent_context = _trusted_agent_context()

    runner.invoke_structured(
        node_name="evidence_turn",
        case_data={
            "agent_context": {"actor_id": "MALICIOUS_CASE_DATA"},
            "message_text": "Please inspect this signature screenshot.",
        },
        output_type=RunnerOutput,
        agent_context=agent_context,
    )

    call = llm.calls[0]
    assert "Evidence Clerk" in str(call["system_prompt"])
    assert "SESSION_evidence_user" in str(call["system_prompt"])
    assert "EVIDENCE_CLERK:USER:v1" in str(call["system_prompt"])
    assert "MALICIOUS_CASE_DATA" not in str(call["system_prompt"])
    assert "MALICIOUS_CASE_DATA" in str(call["user_prompt"])
    governed = call["governed_request"]
    assert governed.provider == "test-provider"
    assert governed.model == "fake-model"
    assert governed.tool_allowlist == ("evidence.read",)
    assert governed.provider_attempts_remaining == 1
    assert governed.repairs_remaining == 0
    assert governed.deadline_at == agent_context.deadline_at
    assert governed.traceparent == agent_context.traceparent


@pytest.mark.parametrize(
    "untrusted_context",
    [
        {
            "agent_session_id": "SESSION_attacker",
            "prompt_profile_id": "EVIDENCE_CLERK:MERCHANT:v1",
            "model_profile_id": "model:attacker:v1",
            "tool_capabilities": ["admin.write"],
            "retry_budget": {
                "provider_attempts_remaining": 2,
                "repairs_remaining": 1,
            },
            "deadline_at": "2099-01-01T00:00:00Z",
            "traceparent": (
                "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01"
            ),
        },
        ArbitraryAgentContext(
            agent_session_id="SESSION_attacker",
            prompt_profile_id="EVIDENCE_CLERK:MERCHANT:v1",
            model_profile_id="model:attacker:v1",
            tool_capabilities=["admin.write"],
            deadline_at="2099-01-01T00:00:00Z",
        ),
    ],
)
def test_model_runner_does_not_trust_mapping_or_arbitrary_model_context(
    untrusted_context: object,
) -> None:
    llm = RecordingLlm()
    runner = HarnessModelRunner(llm=llm, prompts=PromptRepository())

    with pytest.raises(TypeError, match="validated AgentInvocationContext"):
        runner.invoke_structured(
            node_name="evidence_turn",
            case_data={"case_id": "CASE_untrusted_context"},
            output_type=RunnerOutput,
            agent_context=untrusted_context,  # type: ignore[arg-type]
        )

    assert llm.calls == []


def test_model_runner_rejects_prompt_profile_override_of_trusted_context() -> None:
    llm = RecordingLlm()
    runner = HarnessModelRunner(llm=llm, prompts=PromptRepository())

    with pytest.raises(ValueError, match="conflicts with trusted agent context"):
        runner.invoke_structured(
            node_name="evidence_turn",
            case_data={"case_id": "CASE_profile_override"},
            output_type=RunnerOutput,
            agent_context=_trusted_agent_context(),
            prompt_profile_id="EVIDENCE_CLERK:MERCHANT:v1",
        )

    assert llm.calls == []


def test_model_runner_rejects_explicit_profile_backed_only_by_mapping() -> None:
    llm = RecordingLlm()
    runner = HarnessModelRunner(llm=llm, prompts=PromptRepository())

    with pytest.raises(TypeError, match="validated AgentInvocationContext"):
        runner.invoke_structured(
            node_name="evidence_turn",
            case_data={"case_id": "CASE_mapping_profile"},
            output_type=RunnerOutput,
            agent_context={  # type: ignore[arg-type]
                "prompt_profile_id": "EVIDENCE_CLERK:MERCHANT:v1"
            },
            prompt_profile_id="EVIDENCE_CLERK:MERCHANT:v1",
        )

    assert llm.calls == []


def test_model_runner_rejects_nonempty_untyped_agent_context_without_profile() -> None:
    llm = RecordingLlm()
    runner = HarnessModelRunner(llm=llm, prompts=PromptRepository())

    with pytest.raises(TypeError, match="validated AgentInvocationContext"):
        runner.invoke_structured(
            node_name="evidence_turn",
            case_data={"case_id": "CASE_untyped_context"},
            output_type=RunnerOutput,
            agent_context={"actor_role": "MERCHANT"},  # type: ignore[arg-type]
        )

    assert llm.calls == []


# 所属模块：Agent Harness > test_model_runner；函数角色：回归测试用例。
# 具体功能：`test_model_runner_rejects_raw_multimodal_parts` 验证 Harness 不再接受调用方自行拼装的图片内容列表。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 协作调用 `RecordingLlm`、`HarnessModelRunner`、`runner.invoke_structured`、`PromptRepository`。
# 系统意义：固定“Agent Harness > test_model_runner”的可观察契约，防止后续重构改变业务结果。
def test_model_runner_rejects_raw_multimodal_parts() -> None:
    llm = RecordingLlm()
    runner = HarnessModelRunner(llm=llm, prompts=PromptRepository())
    parts = [
        {"type": "text", "text": "Evidence EVIDENCE_image follows."},
        {
            "type": "image_url",
            "image_url": {
                "url": "data:image/png;base64,iVBORw0KGgo=",
                "detail": "high",
            },
        },
    ]

    with pytest.raises(TypeError, match="multimodal_parts"):
        runner.invoke_structured(
            node_name="evidence_turn",
            case_data={"case_id": "CASE_multimodal"},
            output_type=RunnerOutput,
            multimodal_parts=parts,  # type: ignore[call-arg]
        )

    assert llm.calls == []


def test_model_runner_accepts_only_loader_issued_evidence_capability() -> None:
    llm = RecordingLlm()
    runner = HarnessModelRunner(llm=llm, prompts=PromptRepository())
    assets = _loaded_png_assets()

    runner.invoke_structured(
        node_name="evidence_turn",
        case_data={"case_id": "CASE_multimodal"},
        output_type=RunnerOutput,
        evidence_assets=assets,
    )

    assert llm.calls[0]["user_content_parts"] == list(assets.content_parts)
    assert "data:image" not in str(llm.calls[0]["user_prompt"])

    forged = object.__new__(LoadedEvidenceAssets)
    with pytest.raises(ValueError, match="capability provenance"):
        runner.invoke_structured(
            node_name="evidence_turn",
            case_data={"case_id": "CASE_multimodal"},
            output_type=RunnerOutput,
            evidence_assets=forged,
        )
    assert len(llm.calls) == 1

    tampered = _loaded_png_assets()
    image_record = getattr(tampered, "_images")[0]
    tampered_payload = b"\x89PNG\r\n\x1a\nforged-pixels"
    object.__setattr__(
        tampered,
        "_images",
        (
            replace(
                image_record,
                data_url=(
                    "data:image/png;base64,"
                    + base64.b64encode(tampered_payload).decode("ascii")
                ),
            ),
        ),
    )
    with pytest.raises(ValueError, match="pixel hash"):
        runner.invoke_structured(
            node_name="evidence_turn",
            case_data={"case_id": "CASE_multimodal"},
            output_type=RunnerOutput,
            evidence_assets=tampered,
        )
    assert len(llm.calls) == 1


def test_model_runner_streams_public_callbacks_and_parses_one_final_document() -> None:
    llm = RecordingLlm()
    runner = HarnessModelRunner(llm=llm, prompts=PromptRepository())

    updates = list(
        runner.invoke_structured_stream(
            node_name="intake_analyze",
            case_data={"raw_text": "用户文本"},
            output_type=RunnerOutput,
            visible_fields=(VisibleFieldSpec("answer", "answer"),),
        )
    )

    assert len(llm.calls) == 1
    assert isinstance(updates[0], HarnessStreamDelta)
    assert updates[0].delta == "智能接待回复"
    assert isinstance(updates[1], HarnessStreamCompleted)
    assert updates[1].generation.value.answer == "智能接待回复"


def test_model_runner_preserves_generation_reset_between_stream_generations() -> None:
    class ResettingRecordingLlm(RecordingLlm):
        def generate_stream(self, **kwargs):
            self.calls.append(dict(kwargs))
            output_type = kwargs["output_type"]
            yield StructuredStreamDelta(
                kind="visible_delta",
                field="answer",
                delta="第一代",
            )
            yield StructuredStreamReset(
                kind="generation_reset",
                generation=2,
                failed_model="qwen3.7-max-2026-06-08",
                failed_latency_ms=8,
                failed_token_usage={"input": 4, "output": 2, "total": 6},
            )
            yield StructuredStreamDelta(
                kind="visible_delta",
                field="answer",
                delta="第二代",
            )
            yield StructuredStreamCompleted(
                kind="completed",
                generation=StructuredGeneration(
                    value=output_type(answer="第二代"),
                    model="fake-model",
                    latency_ms=20,
                    token_usage={"input": 60, "output": 10, "total": 70},
                    provider_attempts_used=1,
                    repairs_used=0,
                ),
            )

    runner = HarnessModelRunner(llm=ResettingRecordingLlm(), prompts=PromptRepository())

    updates = list(
        runner.invoke_structured_stream(
            node_name="intake_analyze",
            case_data={"raw_text": "用户文本"},
            output_type=RunnerOutput,
            visible_fields=(VisibleFieldSpec("answer", "answer"),),
        )
    )

    assert [update.kind for update in updates] == [
        "visible_delta",
        "generation_reset",
        "visible_delta",
        "completed",
    ]
    assert isinstance(updates[1], HarnessStreamReset)
    assert updates[1].generation == 2
    assert updates[1].reason_code == "OUTPUT_SCHEMA_INVALID"
    assert updates[1].failed_model == "qwen3.7-max-2026-06-08"
    assert updates[1].failed_latency_ms == 8
    assert updates[1].failed_token_usage == {"input": 4, "output": 2, "total": 6}
    assert isinstance(updates[-1], HarnessStreamCompleted)
    assert updates[-1].generation.value.answer == "第二代"


@pytest.mark.asyncio
async def test_async_stream_preserves_semantic_validator_and_generation_reset() -> None:
    class AsyncResettingRecordingLlm(RecordingLlm):
        def __init__(self) -> None:
            super().__init__()
            self.bad_generation_rejected = False

        async def agenerate(self, **kwargs):  # pragma: no cover - stream-only proof
            raise AssertionError(kwargs)

        async def agenerate_stream(self, **kwargs):
            self.calls.append(dict(kwargs))
            output_type = kwargs["output_type"]
            with pytest.raises(ValueError):
                output_type(answer="第一代")
            self.bad_generation_rejected = True
            yield StructuredStreamDelta(
                kind="visible_delta", field="answer", delta="第一代"
            )
            yield StructuredStreamReset(
                kind="generation_reset",
                generation=2,
                failed_model="qwen3.7-max-2026-06-08",
                failed_latency_ms=8,
                failed_token_usage={"input": 4, "output": 2, "total": 6},
            )
            yield StructuredStreamDelta(
                kind="visible_delta", field="answer", delta="第二代"
            )
            yield StructuredStreamCompleted(
                kind="completed",
                generation=StructuredGeneration(
                    value=output_type(answer="第二代"),
                    model="fake-model",
                    latency_ms=20,
                    token_usage={"input": 60, "output": 10, "total": 70},
                    provider_attempts_used=2,
                    repairs_used=1,
                ),
            )

    def require_second_generation(value: RunnerOutput) -> RunnerOutput:
        if value.answer != "第二代":
            raise ValueError("semantic contract requires the second generation")
        return value

    llm = AsyncResettingRecordingLlm()
    runner = HarnessModelRunner(llm=llm, prompts=PromptRepository())
    updates = [
        update
        async for update in runner.ainvoke_structured_stream(
            node_name="intake_analyze",
            case_data={"raw_text": "用户文本"},
            output_type=RunnerOutput,
            visible_fields=(VisibleFieldSpec("answer", "answer"),),
            semantic_validator=require_second_generation,
        )
    ]

    assert llm.bad_generation_rejected
    assert [update.kind for update in updates] == [
        "visible_delta",
        "generation_reset",
        "visible_delta",
        "completed",
    ]
    reset = updates[1]
    assert isinstance(reset, HarnessStreamReset)
    assert reset.failed_model == "qwen3.7-max-2026-06-08"
    assert reset.failed_latency_ms == 8
    assert reset.failed_token_usage == {"input": 4, "output": 2, "total": 6}
    assert updates[-1].generation.value.answer == "第二代"


# 所属模块：Agent Harness > test_model_runner；函数角色：回归测试用例。
# 具体功能：`test_context_window_rejects_required_section_that_cannot_fit` 验证案件与会话上下文在固定案例中的输出、边界和失败行为；关键协作调用：`ContextWindowManager`、`manager.assemble`、`AssertionError`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 协作调用 `ContextWindowManager`、`manager.assemble`、`AssertionError`、`PromptSection`。
# 系统意义：固定“Agent Harness > test_model_runner”的可观察契约，防止后续重构改变业务结果。
def test_context_window_rejects_required_section_that_cannot_fit() -> None:
    manager = ContextWindowManager(default_max_input_tokens=10)

    try:
        manager.assemble(
            [
                PromptSection(
                    name="required_context",
                    content="必要上下文" * 100,
                    priority=100,
                    required=True,
                )
            ]
        )
    except ValueError as failure:
        assert "required context section required_context exceeds token budget" in str(
            failure
        )
    else:
        raise AssertionError("required oversized context should fail")
