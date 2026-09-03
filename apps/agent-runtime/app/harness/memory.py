# 文件作用：隔离 RUN/CASE/EXPERIENCE 三类记忆，校验会话归属，并把近期回合压缩成有 Token 上限的模型记忆帧。

"""受治理 Agent 运行的记忆作用域、经验晋升规则、会话校验与短期压缩。"""

from __future__ import annotations

from enum import StrEnum
from typing import Any, Self

from pydantic import BaseModel, ConfigDict, Field, model_validator


class MemoryScope(StrEnum):
    RUN = "RUN"
    CASE = "CASE"
    EXPERIENCE = "EXPERIENCE"


class MemoryScopeViolation(ValueError):
    """Raised when backend-supplied turn memory crosses an agent session boundary."""


class MemoryEntry(BaseModel):
    """A versioned memory item with explicit promotion approval.

    Case output cannot silently become shared experience. EXPERIENCE entries
    require an offline governance identity so one disputed case never teaches
    the whole system by itself.
    """

    model_config = ConfigDict(extra="forbid", frozen=True)

    scope: MemoryScope
    memory_key: str = Field(min_length=1, max_length=128)
    memory_version: int = Field(ge=1)
    source_refs: tuple[str, ...] = Field(min_length=1)
    content: dict[str, Any]
    approved_for_experience: bool = False
    approved_by: str | None = Field(default=None, min_length=3, max_length=128)

    # 所属模块：Agent Harness > 记忆治理 > CASE 到 EXPERIENCE 晋升校验。
    # 具体功能：`enforce_experience_approval` 要求 EXPERIENCE 条目同时具备 approved_for_experience 与 approved_by；非 EXPERIENCE 条目反而禁止携带经验批准标志。
    # 上下游：上游是记忆写入/加载时构造 MemoryEntry；下游是经验库持久化或普通 RUN/CASE 记忆使用。
    # 系统意义：单个争议案件及其中未经核实的陈述不能自动“教会”全局 Agent；共享经验必须经过离线治理身份批准。
    @model_validator(mode="after")
    def enforce_experience_approval(self) -> Self:
        if self.scope is MemoryScope.EXPERIENCE:
            if not self.approved_for_experience or not self.approved_by:
                raise ValueError(
                    "experience memory requires explicit offline approval"
                )
        elif self.approved_for_experience:
            raise ValueError(
                "only EXPERIENCE memory may be approved for experience"
            )
        return self


class MemeoMemoryConfig(BaseModel):
    """Per-agent memory mode flags, later owned by the Agent configuration center."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    short_term_enabled: bool = True
    summary_enabled: bool = True
    long_term_enabled: bool = False
    short_term_round_limit: int = Field(default=5, ge=0, le=20)
    summary_window_round_limit: int = Field(default=10, ge=1, le=50)
    compressed_token_limit: int = Field(default=200, ge=1, le=2_000)


class MemeoMemoryMessage(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    role: str = Field(min_length=1, max_length=64)
    content: str = Field(min_length=1, max_length=20_000)


class MemeoMemoryRound(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    turn_no: int = Field(ge=1)
    messages: tuple[MemeoMemoryMessage, ...] = Field(default_factory=tuple)


class MemeoLongTermSlot(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    memory_key: str = Field(min_length=1, max_length=128)
    status: str = Field(default="RESERVED", min_length=1, max_length=64)
    content: str = Field(default="", max_length=20_000)
    enabled: bool = False
    prompt_included: bool = False


class MemeoMemoryFrame(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    memory_modes: MemeoMemoryConfig = Field(default_factory=MemeoMemoryConfig)
    short_term_rounds: tuple[MemeoMemoryRound, ...] = Field(default_factory=tuple)
    compression_window_turns: tuple[int, ...] = Field(default_factory=tuple)
    compressed_summary: str = ""
    compressed_summary_estimated_tokens: int = 0
    prompt_memory: str = ""
    prompt_memory_estimated_tokens: int = 0
    long_term_slots: tuple[MemeoLongTermSlot, ...] = Field(default_factory=tuple)


class MemeoMemoryAssembler:
    """Builds the shared short-term memory frame for Agent harness runs.

    The current implementation is deterministic and local: it compresses recent
    room-turn memory into a bounded textual frame. Long-term memory is exposed
    as a slot for UI and future vector retrieval, but is not inserted into the
    prompt by default.
    """

    # 所属模块：Agent Harness > 记忆治理 > 单次调用记忆帧装配。
    # 具体功能：`assemble` 先按 agent_session_id/conversation_scope 校验来路，再按 turn_no 归组，截取短期窗口、压缩摘要窗口，并创建默认不进 Prompt 的长期记忆预览槽。
    # 上下游：上游是 Java 按当前参与方私聊返回的 recent_turns 与 AgentInvocationContext 会话标识；下游是 ContextPack 的短期记忆段、UI 预览和审计估算字段。
    # 系统意义：记忆不是全案聊天全文；它必须同时满足会话隔离、回合上限、Token 上限，长期向量槽在明确启用前也不能自动进入模型。
    def assemble(
        self,
        recent_turns: list[dict[str, Any]],
        *,
        config: MemeoMemoryConfig | None = None,
        long_term_preview: list[dict[str, Any]] | None = None,
        expected_agent_session_id: str | None = None,
        expected_conversation_scope: str | None = None,
        strict_scope: bool = False,
    ) -> MemeoMemoryFrame:
        cfg = config or MemeoMemoryConfig()
        scoped_turns = _validate_recent_turn_scope(
            recent_turns,
            expected_agent_session_id=expected_agent_session_id,
            expected_conversation_scope=expected_conversation_scope,
            strict_scope=strict_scope,
        )
        rounds = _rounds_from_turn_memory(scoped_turns)
        # 切片 `rounds[-N:]` 取最后 N 轮；配置关闭或 N=0 时使用空 tuple，防止误带历史。
        latest_short_term = (
            tuple(rounds[-cfg.short_term_round_limit :])
            if cfg.short_term_enabled and cfg.short_term_round_limit > 0
            else tuple()
        )
        # 只有累计轮数达到 summary_window_round_limit 才生成摘要，避免少量消息被重复放进摘要和短期窗口。
        compression_window = (
            tuple(rounds[-cfg.summary_window_round_limit :])
            if cfg.summary_enabled and len(rounds) >= cfg.summary_window_round_limit
            else tuple()
        )
        summary = (
            _compress_rounds(compression_window, cfg.compressed_token_limit)
            if cfg.summary_enabled
            else ""
        )
        prompt_parts: list[str] = []
        if summary:
            prompt_parts.append(f"Compressed summary:\n{summary}")
        if cfg.short_term_enabled and latest_short_term:
            prompt_parts.append(f"Short-term memory:\n{_render_rounds(latest_short_term)}")
        prompt_memory = "\n\n".join(prompt_parts)

        # 长期槽只暴露元数据/预览给 UI；prompt_included 固定 False，即使配置启用也不在这里直接做向量召回。
        slots = tuple(
            MemeoLongTermSlot(
                memory_key=str(item.get("memory_key") or "MEM0_VECTOR_SLOT"),
                status=str(item.get("status") or "RESERVED"),
                content=str(item.get("content") or ""),
                enabled=cfg.long_term_enabled,
                prompt_included=False,
            )
            for item in (long_term_preview or [])
        )

        return MemeoMemoryFrame(
            memory_modes=cfg,
            short_term_rounds=latest_short_term,
            compression_window_turns=tuple(item.turn_no for item in compression_window),
            compressed_summary=summary,
            compressed_summary_estimated_tokens=_estimate_tokens(summary),
            prompt_memory=prompt_memory,
            prompt_memory_estimated_tokens=_estimate_tokens(prompt_memory),
            long_term_slots=slots,
        )


# 所属模块：Agent Harness > 记忆治理 > 参与方会话归属校验。
# 具体功能：`_validate_recent_turn_scope` 在 strict_scope 下要求每条记忆都是 dict，且 agent_session_id 与可选 conversation_scope 逐条等于本次可信调用上下文。
# 上下游：上游是 `MemeoMemoryAssembler.assemble` 收到的后端 turn memory；下游仅把全部通过的条目交给 `_rounds_from_turn_memory`。
# 系统意义：任何一条跨会话记录都会使整次装配失败，而不是静默混入或只过滤一部分；这可暴露 Java 查询条件错误并阻断用户/商家串话。
def _validate_recent_turn_scope(
    recent_turns: list[dict[str, Any]],
    *,
    expected_agent_session_id: str | None,
    expected_conversation_scope: str | None,
    strict_scope: bool,
) -> list[dict[str, Any]]:
    # 兼容旧调用时可关闭严格模式；核心接待/证据链路应传 strict_scope=True 和可信期望值。
    if not strict_scope:
        return recent_turns
    if not _has_text(expected_agent_session_id):
        raise MemoryScopeViolation("expected_agent_session_id is required in strict scope")

    accepted: list[dict[str, Any]] = []
    for index, item in enumerate(recent_turns):
        if not isinstance(item, dict):
            raise MemoryScopeViolation(f"recent_turns[{index}] must be an object")
        agent_session_id = item.get("agent_session_id")
        if not _has_text(agent_session_id):
            raise MemoryScopeViolation(f"recent_turns[{index}] missing agent_session_id")
        if str(agent_session_id) != expected_agent_session_id:
            raise MemoryScopeViolation(
                "agent_session_id mismatch: "
                f"expected {expected_agent_session_id}, got {agent_session_id}"
            )
        if expected_conversation_scope is not None:
            conversation_scope = item.get("conversation_scope")
            if not _has_text(conversation_scope):
                raise MemoryScopeViolation(f"recent_turns[{index}] missing conversation_scope")
            if str(conversation_scope) != expected_conversation_scope:
                raise MemoryScopeViolation(
                    "conversation_scope mismatch: "
                    f"expected {expected_conversation_scope}, got {conversation_scope}"
                )
        accepted.append(item)
    return accepted


# 所属模块：Agent Harness > 记忆治理 > 会话标识非空判断。
# 具体功能：`_has_text` 只有在值确实是 str 且去除空白后仍有内容时返回 True；数字、None 和空白字符串都视为缺失。
# 上下游：上游是严格会话校验；下游决定是否抛出缺失 session/scope 的 MemoryScopeViolation。
# 系统意义：不把 `str(None)` 等宽松转换当成合法标识，避免无效会话键通过验证。
def _has_text(value: object) -> bool:
    return isinstance(value, str) and bool(value.strip())


# 所属模块：Agent Harness > 记忆治理 > 扁平记录按业务回合归组。
# 具体功能：`_rounds_from_turn_memory` 将后端每条 answer/agent_response 记录按正整数 turn_no 分组，按“当事人回答后 Agent 回复”顺序构造 MemeoMemoryRound，并跳过格式损坏项。
# 上下游：上游是已通过 scope 校验的 recent_turns；下游是短期切片、摘要窗口和 `_render_rounds`。
# 系统意义：模型看到的是有顺序的业务回合而非数据库行；稳定排序避免查询返回顺序变化导致 Prompt 记忆漂移。
def _rounds_from_turn_memory(recent_turns: list[dict[str, Any]]) -> list[MemeoMemoryRound]:
    grouped: dict[int, list[MemeoMemoryMessage]] = {}
    for item in recent_turns:
        if not isinstance(item, dict):
            continue
        try:
            turn_no = int(item.get("turn_no") or 0)
        except (TypeError, ValueError):
            continue
        if turn_no < 1:
            continue
        # setdefault 在 turn_no 第一次出现时创建列表，之后同一回合继续追加到同一列表。
        messages = grouped.setdefault(turn_no, [])
        answer_role = item.get("answer_role")
        answer_content = item.get("answer_content")
        if answer_role and answer_content:
            messages.append(
                MemeoMemoryMessage(role=str(answer_role), content=str(answer_content))
            )
        agent_role = item.get("agent_role")
        agent_response = item.get("agent_response")
        if agent_role and agent_response:
            messages.append(
                MemeoMemoryMessage(role=str(agent_role), content=str(agent_response))
            )
    return [
        MemeoMemoryRound(turn_no=turn_no, messages=tuple(messages))
        for turn_no, messages in sorted(grouped.items())
        if messages
    ]


# 所属模块：Agent Harness > 记忆治理 > 摘要窗口确定性截尾压缩。
# 具体功能：`_compress_rounds` 先渲染完整回合；超限时从最新行向前装入约 token_limit*4 个字符，必要时只截取最新一行并做最终兜底截尾。
# 上下游：上游是达到摘要窗口阈值的 MemeoMemoryRound 元组；下游是 prompt_memory 的 compressed_summary 与 Token 估算。
# 系统意义：这里没有再次调用 LLM“总结”，避免额外成本和摘要幻觉；优先保留最近内容并严格保证近似预算上限。
def _compress_rounds(rounds: tuple[MemeoMemoryRound, ...], token_limit: int) -> str:
    rendered = _render_rounds(rounds)
    if _estimate_tokens(rendered) <= token_limit:
        return rendered
    budget_chars = max(4, token_limit * 4)
    fragments: list[str] = []
    remaining = budget_chars
    # reversed 从最新一行向旧内容遍历，insert(0, ...) 再把被选中的行恢复成正常时间顺序。
    for line in reversed(rendered.splitlines()):
        if remaining <= 0:
            break
        line_with_break = line if not fragments else line + "\n"
        if len(line_with_break) <= remaining:
            fragments.insert(0, line)
            remaining -= len(line_with_break)
            continue
        if not fragments:
            fragments.insert(0, line[:remaining])
        break
    compressed = "\n".join(fragments).strip()
    if _estimate_tokens(compressed) > token_limit:
        compressed = compressed[-budget_chars:]
    return compressed


# 所属模块：Agent Harness > 记忆治理 > 回合文本渲染。
# 具体功能：`_render_rounds` 按 turn_no 输出 Round 标题，再按记录顺序输出 `role: content`，形成供短期记忆和压缩算法共用的稳定格式。
# 上下游：上游是 assemble 的最近回合及 `_compress_rounds` 的摘要窗口；下游是模型可读 prompt_memory 字符串。
# 系统意义：共享渲染器保证完整窗口和压缩窗口语义一致，角色标签明确可减少模型混淆谁说了哪句话。
def _render_rounds(rounds: tuple[MemeoMemoryRound, ...]) -> str:
    lines: list[str] = []
    for round_item in rounds:
        lines.append(f"Round {round_item.turn_no}:")
        for message in round_item.messages:
            lines.append(f"- {message.role}: {message.content}")
    return "\n".join(lines).strip()


# 所属模块：Agent Harness > 记忆治理 > 记忆文本近似 Token 计数。
# 具体功能：`_estimate_tokens` 对空文本返回 0，对非空文本用字符数除以 4 向上取整，和 ContextWindowManager 使用相同保守口径。
# 上下游：上游是摘要裁剪判断与最终 MemoryFrame 指标；下游是 compressed/prompt memory 的预算展示和上限验证。
# 系统意义：统一估算口径可避免记忆模块认为未超限、Prompt 窗口模块却立即删除同一内容的不可解释差异。
def _estimate_tokens(text: str) -> int:
    if not text:
        return 0
    return max(1, (len(text) + 3) // 4)
