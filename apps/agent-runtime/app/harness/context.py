# 文件作用：按 AgentProfile 的案件阶段与数据域权限装配旧版上下文片段，并在固定 Token 预算内确定性取舍。

"""带权限校验的确定性上下文装配器；主要供基于 AgentProfile 的 Harness 链路使用。"""

from __future__ import annotations

import json
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field

from app.harness.profile import AgentProfile


class ContextAuthorityError(ValueError):
    """请求的案件阶段、上下文范围或必需来源超出 AgentProfile 时抛出。"""


class ContextTokenBudgetError(ValueError):
    """必需上下文无法放进调用方声明的 Token 预算时抛出。"""


class ContextFragment(BaseModel):
    """可追溯来源和版本的上下文片段，content 才是实际业务正文。"""

    model_config = ConfigDict(extra="forbid", frozen=True)

    source_type: str = Field(min_length=1, max_length=64)
    source_id: str = Field(min_length=1, max_length=128)
    source_version: str = Field(min_length=1, max_length=64)
    captured_at: datetime
    access_scope: str = Field(min_length=1, max_length=64)
    content: str = Field(min_length=1, max_length=100_000)
    priority: int = Field(default=50, ge=0, le=100)

    # 所属模块：Agent Harness > Profile 授权上下文 > 单片段成本核算。
    # 具体功能：`estimated_tokens` 把正文连同来源、版本、权限域等审计字段序列化，再用“约 4 字符/Token”估算整片段成本。
    # 上下游：上游是 `ContextAssembler.assemble` 对每个已授权片段的预算检查；下游是片段保留、丢弃或必需片段超限异常。
    # 系统意义：不能只计算正文而漏掉元数据；稳定的近似算法让同一输入在重试时得到同一上下文选择结果。
    def estimated_tokens(self) -> int:
        # model_dump(mode="json") 将 datetime 等 Pydantic 字段转成可 JSON 序列化的基础类型。
        serialized = json.dumps(
            self.model_dump(mode="json"),
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        # `//` 是向下取整除法；先加 3 等价于对 len / 4 向上取整，且非空片段至少计 1 Token。
        return max(1, (len(serialized) + 3) // 4)


class AssembledContext(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    fragments: tuple[ContextFragment, ...]
    estimated_tokens: int = Field(ge=0)
    omitted_source_ids: tuple[str, ...]


class ContextAssembler:
    """先执行 AgentProfile 权限检查，再在预算内选择最重要的上下文片段。"""

    # 所属模块：Agent Harness > Profile 授权上下文 > 权限校验与预算选择入口。
    # 具体功能：`assemble` 依次校验预算、案件阶段、每个片段的 access_scope 和必需来源，再按“必需优先、业务优先级降序”装配上下文。
    # 上下游：上游是 Agent 配置中心给出的 `AgentProfile` 与各数据适配器生成的 `ContextFragment`；下游是模型调用所用 `AssembledContext` 或显式权限/预算异常。
    # 系统意义：权限校验先于 Token 取舍，保证越权片段不会因“后来可能被裁掉”而被容忍；必需材料放不下时失败关闭而非静默生成残缺结论。
    def assemble(
        self,
        profile: AgentProfile,
        case_state: str,
        fragments: list[ContextFragment],
        max_tokens: int,
        *,
        required_source_ids: set[str] | None = None,
    ) -> AssembledContext:
        # 第一层边界：调用方必须给出正预算，且当前 Agent 必须能在该案件阶段运行。
        if max_tokens < 1:
            raise ContextTokenBudgetError("max_tokens must be positive")
        if not profile.authorizes_case_state(case_state):
            raise ContextAuthorityError(
                f"{profile.agent_id} cannot run in case state {case_state}"
            )
        # 第二层边界：列表里任何一个越权片段都会使整次装配失败，避免调用者混入敏感数据。
        for fragment in fragments:
            if not profile.authorizes_context(fragment.access_scope):
                raise ContextAuthorityError(
                    f"{profile.agent_id} cannot access scope "
                    f"{fragment.access_scope}"
                )

        # `or set()` 把 None 规范成空集合；集合差集可一次找出缺失的必需来源 ID。
        required = required_source_ids or set()
        by_id = {fragment.source_id: fragment for fragment in fragments}
        missing = required - by_id.keys()
        if missing:
            raise ContextAuthorityError(
                f"required context sources are missing: {sorted(missing)}"
            )

        # Python 会按 tuple 从左到右排序：False 在 True 前，因此必需来源排最前；负号让高 priority 排在前面。
        # source_id/version 作为最后排序键，使相同输入跨重试和跨进程仍得到稳定顺序。
        ordered = sorted(
            fragments,
            key=lambda item: (
                item.source_id not in required,
                -item.priority,
                item.source_id,
                item.source_version,
            ),
        )
        selected: list[ContextFragment] = []
        omitted: list[str] = []
        used = 0
        # 这是一个贪心选择：按业务优先级逐段装入，不拆分片段，避免截断后破坏 JSON 或证据语义。
        for fragment in ordered:
            cost = fragment.estimated_tokens()
            if used + cost <= max_tokens:
                selected.append(fragment)
                used += cost
                continue
            if fragment.source_id in required:
                raise ContextTokenBudgetError(
                    f"required source {fragment.source_id} exceeds token budget"
                )
            omitted.append(fragment.source_id)

        return AssembledContext(
            fragments=tuple(selected),
            estimated_tokens=used,
            omitted_source_ids=tuple(omitted),
        )
