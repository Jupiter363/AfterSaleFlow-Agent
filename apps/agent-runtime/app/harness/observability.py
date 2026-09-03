# 文件作用：定义可关联案件、工作流、Agent/Profile/Prompt/Skill/规则版本的运行事件，并在本地观测器中脱敏敏感元数据。

"""供 trace、审计、成本与评测使用的脱敏 Agent Run 事件。"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class AgentRunEvent(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    trace_id: str
    case_id: str
    workflow_id: str
    run_id: str
    agent_id: str
    profile_version: str
    prompt_version: str
    skill_version: str
    ruleset_version: str
    event_type: str
    occurred_at: datetime
    metadata: dict[str, Any] = Field(default_factory=dict)


class InMemoryRunObserver:
    """测试/本地内存观测器；生产适配器可持久化同一 AgentRunEvent 合同。"""

    _sensitive_markers = (
        "api_key",
        "secret",
        "password",
        "token",
        "credential",
    )

    # 所属模块：Agent Harness > 可观测性 > 本地事件存储初始化。
    # 具体功能：`__init__` 创建按发生顺序保存 AgentRunEvent 的内存列表，不共享类级可变状态。
    # 上下游：上游是测试或本地 Harness 依赖装配；下游是 `record` 追加脱敏事件以及断言/调试读取 events。
    # 系统意义：提供与生产事件合同一致的轻量实现，使审计字段和版本关联在单元测试中也可验证。
    def __init__(self) -> None:
        self.events: list[AgentRunEvent] = []

    # 所属模块：Agent Harness > 可观测性 > 元数据键名脱敏与记录。
    # 具体功能：`record` 对 metadata 键名做大小写无关敏感词检查，将命中值替换为 `[REDACTED]`，再复制不可变事件并追加。
    # 上下游：上游是模型、工具、上下文和输出提交等生命周期事件；下游是本地审计查询/测试，生产可替换为数据库或遥测适配器。
    # 系统意义：API key、secret、password、token、credential 不应因调试元数据进入日志；原事件不被原地修改，避免影响其他 sink。
    def record(self, event: AgentRunEvent) -> None:
        redacted = {
            key: (
                "[REDACTED]"
                if any(marker in key.lower() for marker in self._sensitive_markers)
                else value
            )
            for key, value in event.metadata.items()
        }
        self.events.append(event.model_copy(update={"metadata": redacted}))
