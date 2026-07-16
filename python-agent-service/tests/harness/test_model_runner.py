# 文件作用：自动化测试文件，验证 test_model_runner 相关模块的行为、契约或页面布局。

from __future__ import annotations

from pydantic import BaseModel

from app.harness.context_window import ContextWindowManager, PromptSection
from app.harness.model_runner import HarnessModelRunner
from app.harness.prompt_composer import PromptRepository
from app.llm import StructuredGeneration


class RunnerOutput(BaseModel):
    answer: str


class RecordingLlm:
    # 所属模块：Agent Harness > test_model_runner；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖。
    # 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 受 Token、权限、Schema、审计约束的模型输入或结果。
    # 系统意义：该函数在系统中的业务边界是：隔离参与方会话；不可信案件文本不能升级为系统指令。
    def __init__(self) -> None:
        self.calls: list[dict[str, object]] = []

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
    ):
        self.calls.append(
            {
                "node_name": node_name,
                "system_prompt": system_prompt,
                "user_prompt": user_prompt,
                "output_type": output_type,
                "user_content_parts": user_content_parts,
            }
        )
        return StructuredGeneration(
            value=output_type(answer="智能接待回复"),
            model="fake-model",
            latency_ms=12,
            token_usage={"input": 30, "output": 5, "total": 35},
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
    agent_context = {
        "agent_key": "EVIDENCE_CLERK",
        "actor_id": "USER_local_1",
        "actor_role": "USER",
        "agent_session_id": "SESSION_evidence_user",
        "scope_type": "EVIDENCE_PARTY_PRIVATE",
        "allowed_actor_ids": ["USER_local_1"],
        "prompt_profile_id": "EVIDENCE_CLERK:USER:v1",
        "case_id": "CASE_model_runner",
        "room_type": "EVIDENCE",
        "agent_invocation_id": "INVOCATION_model_runner",
    }

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


# 所属模块：Agent Harness > test_model_runner；函数角色：回归测试用例。
# 具体功能：`test_model_runner_forwards_multimodal_parts_only_to_llm_transport` 验证结构化模型调用在固定案例中的输出、边界和失败行为；关键协作调用：`RecordingLlm`、`HarnessModelRunner`、`runner.invoke_structured`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 协作调用 `RecordingLlm`、`HarnessModelRunner`、`runner.invoke_structured`、`PromptRepository`。
# 系统意义：固定“Agent Harness > test_model_runner”的可观察契约，防止后续重构改变业务结果。
def test_model_runner_forwards_multimodal_parts_only_to_llm_transport() -> None:
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

    runner.invoke_structured(
        node_name="evidence_turn",
        case_data={"case_id": "CASE_multimodal"},
        output_type=RunnerOutput,
        multimodal_parts=parts,
    )

    assert llm.calls[0]["user_content_parts"] == parts
    assert "data:image" not in str(llm.calls[0]["user_prompt"])


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
