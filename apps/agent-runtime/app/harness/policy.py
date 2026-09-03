# 文件作用：把平台政策、Agent 身份、流程任务与不可信案件/用户内容分层渲染，明确 Prompt 指令优先级。

"""Prompt 指令分层：不可信案件数据和用户文本永远位于平台政策之下。"""

from __future__ import annotations

from enum import StrEnum

from pydantic import BaseModel, ConfigDict


class InstructionLayer(StrEnum):
    PLATFORM_POLICY = "PLATFORM_POLICY"
    AGENT_IDENTITY = "AGENT_IDENTITY"
    WORKFLOW = "WORKFLOW"
    TASK = "TASK"
    CASE_DATA = "CASE_DATA"
    USER = "USER"


class InstructionSegment(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    layer: InstructionLayer
    content: str
    trusted_instruction: bool


class InstructionBundle(BaseModel):
    """Ordered instructions plus data boundaries for one Agent call."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    platform_policy: str
    agent_identity: str
    workflow_instruction: str
    task_instruction: str
    case_data: str
    user_instruction: str

    # 所属模块：Agent Harness > Prompt 指令分层 > 规范顺序生成。
    # 具体功能：`layers` 把六个具名字段转换成固定顺序的 InstructionSegment，并只把平台、身份、流程、任务四层标为可信指令。
    # 上下游：上游是调用方构造的 `InstructionBundle`；下游是 `render` 逐层加边界标签。
    # 系统意义：案件描述里即使出现“忽略系统规则”，它仍被标记为 CASE_DATA 而非指令，不能获得与平台政策同等的语义地位。
    def layers(self) -> tuple[InstructionSegment, ...]:
        return (
            InstructionSegment(
                layer=InstructionLayer.PLATFORM_POLICY,
                content=self.platform_policy,
                trusted_instruction=True,
            ),
            InstructionSegment(
                layer=InstructionLayer.AGENT_IDENTITY,
                content=self.agent_identity,
                trusted_instruction=True,
            ),
            InstructionSegment(
                layer=InstructionLayer.WORKFLOW,
                content=self.workflow_instruction,
                trusted_instruction=True,
            ),
            InstructionSegment(
                layer=InstructionLayer.TASK,
                content=self.task_instruction,
                trusted_instruction=True,
            ),
            InstructionSegment(
                layer=InstructionLayer.CASE_DATA,
                content=self.case_data,
                trusted_instruction=False,
            ),
            InstructionSegment(
                layer=InstructionLayer.USER,
                content=self.user_instruction,
                trusted_instruction=False,
            ),
        )

    # 所属模块：Agent Harness > Prompt 指令分层 > 带信任边界的文本渲染。
    # 具体功能：`render` 将可信层放入同名标签，将案件数据和用户内容放入显式 UNTRUSTED 标签，最后按层间双换行拼成单个 Prompt。
    # 上下游：上游是 `layers` 返回的规范顺序；下游是底层 LLM 客户端接收的系统指令文本。
    # 系统意义：标签不能替代服务端权限校验，但能把数据与指令边界清楚传给模型并方便安全审计定位 Prompt 注入来源。
    def render(self) -> str:
        rendered: list[str] = []
        # `is` 用于比较枚举单例身份；两个不可信层使用固定标签，不能由案件内容自定义标签名。
        for segment in self.layers():
            if segment.layer is InstructionLayer.CASE_DATA:
                rendered.append(
                    "<UNTRUSTED_CASE_DATA>\n"
                    + segment.content
                    + "\n</UNTRUSTED_CASE_DATA>"
                )
            elif segment.layer is InstructionLayer.USER:
                rendered.append(
                    "<UNTRUSTED_USER_CONTENT>\n"
                    + segment.content
                    + "\n</UNTRUSTED_USER_CONTENT>"
                )
            else:
                rendered.append(
                    f"<{segment.layer.value}>\n"
                    f"{segment.content}\n"
                    f"</{segment.layer.value}>"
                )
        return "\n\n".join(rendered)
