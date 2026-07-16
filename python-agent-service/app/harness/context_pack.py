# 文件作用：把各 Agent 节点的原始上下文源按白名单合同打包，并区分模型可见段与仅供界面/审计展示的段。

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, Mapping

from app.harness.context_window import PromptSection
from app.harness.localization_policy import localize_context_tree
from app.harness.narrative_policy import (
    apply_platform_narrative_tree,
    rewrite_platform_narrative,
)
from app.harness.prompt_contracts import context_contract


@dataclass(frozen=True)
class ContextPack:
    """一个节点最终可用的上下文包。

    sections 是 PromptSection 元组；display_only_section_names 记录“不进模型、只展示”的段。
    configuration_profile_key/source 用来追踪这套上下文合同来自哪里。
    """

    node_name: str
    sections: tuple[PromptSection, ...]
    display_only_section_names: tuple[str, ...]
    configuration_profile_key: str
    configuration_source: str = "code"

    # 所属模块：Agent Harness > 节点上下文合同 > 模型可见段投影。
    # 具体功能：`prompt_sections` 从完整 ContextPack 中只选 `prompt_included=True` 的段，保留原顺序、优先级和信任标签。
    # 上下游：上游是 `build_context_pack` 产出的完整上下文包；下游是 `HarnessModelRunner` 的 Token 裁剪器，而 display-only 段仍留在包内供 UI/审计读取。
    # 系统意义：长期记忆预览等“系统知道但模型不应看到”的数据不会因调用方误用整包而进入 Prompt。
    def prompt_sections(self) -> list[PromptSection]:
        """只返回允许进入模型 prompt 的 section。"""

        return [section for section in self.sections if section.prompt_included]


# 所属模块：Agent Harness > 节点上下文合同 > ContextPack 唯一构造入口。
# 具体功能：`build_context_pack` 以 node_name 查询白名单合同，只读取合同声明的 source；同时执行必填检查、内容规范化并复制优先级/信任级别/可见性元数据。
# 上下游：上游是接待、证据、庭审工作流准备的命名 `sources`；下游是 `_section_content` 规范化及 `ContextWindowManager` 的预算裁剪。
# 系统意义：调用方即使持有完整案件对象，也不能绕过合同把任意字段塞给 LLM；新增上下文必须显式修改合同并接受审查。
def build_context_pack(
    node_name: str,
    sources: Mapping[str, Any],
    *,
    actor_role: str | None = None,
) -> ContextPack:
    """根据节点的 context contract，从多个 sources 中构造 ContextPack。

    context_contract(node_name) 定义每个节点需要哪些 section、是否必填、优先级和信任等级。
    这里不会让调用方随意把所有上下文塞进模型，而是按合同筛选。
    """

    contract = context_contract(node_name)
    sections: list[PromptSection] = []
    display_only: list[str] = []
    # 只遍历合同，不遍历 sources：sources 中多出的键因此天然被忽略，这是 allowlist（白名单）设计。
    for spec in contract.sections:
        # optional section 缺失时直接跳过；required section 缺失则立刻报错。
        if spec.name not in sources and not spec.required:
            continue
        raw_value = sources.get(spec.name)
        if spec.required and (spec.name not in sources or raw_value in (None, "")):
            raise ValueError(f"required context section {spec.name} is missing")
        content = _section_content(spec.name, raw_value, actor_role=actor_role)
        section = PromptSection(
            name=spec.name,
            content=content,
            priority=spec.priority,
            required=spec.required,
            trust_level=spec.trust_level,
            prompt_included=spec.prompt_included,
        )
        sections.append(section)
        if not spec.prompt_included:
            display_only.append(spec.name)
    return ContextPack(
        node_name=node_name,
        sections=tuple(sections),
        display_only_section_names=tuple(display_only),
        configuration_profile_key=contract.configuration_profile_key,
        configuration_source=contract.configuration_source,
    )


# 所属模块：Agent Harness > 节点上下文合同 > 单段内容序列化。
# 具体功能：`_section_content` 先按段名执行语义规范化；字符串原样保存，dict/list 则编码成紧凑 UTF-8 JSON，供统一的 PromptSection 接口承载。
# 上下游：上游是 `build_context_pack` 对每个合同项的处理；下游是 `_normalize_section_value` 和后续 `_structured_section_content` 的 JSON 还原。
# 系统意义：统一成字符串便于精确计算长度，但 JSON 结构没有丢失；`ensure_ascii=False` 也避免中文变成更长的转义序列而扭曲预算。
def _section_content(
    name: str,
    value: Any,
    *,
    actor_role: str | None,
) -> str:
    """把一个 section 的值规范化成字符串，供 PromptSection 保存。"""

    if value is None:
        return ""
    normalized = _normalize_section_value(name, value, actor_role=actor_role)
    if isinstance(normalized, str):
        return normalized
    return json.dumps(normalized, ensure_ascii=False, separators=(",", ":"))


# 所属模块：Agent Harness > 节点上下文合同 > 分段语义规范化策略。
# 具体功能：`_normalize_section_value` 按 section 身份决定三种路径：机器标识/原文直接保留、当前发言生成“原文+平台转述”双轨、其他展示文本做业务码本地化与第三人称改写。
# 上下游：上游是 `_section_content`；下游是 `_normalize_current_turn`、`localize_context_tree` 和 `apply_platform_narrative_tree`。
# 系统意义：ID、证据原文和会话内容不能被文案替换破坏引用；平台摘要又不能把一方的“我”误写成平台确认事实。
def _normalize_section_value(
    name: str,
    value: Any,
    *,
    actor_role: str | None,
) -> Any:
    """按 section 类型决定是否本地化、是否保留原文、是否改写平台叙事。"""

    if name in {
        "case_identity",
        "initial_case_facts",
        "recent_dialogue_messages",
        "current_user_message",
        "previous_case_detail",
        "party_visible_evidence_catalog",
        "private_conversation_window",
        "evidence_matrix_snapshot",
        "fact_targets",
        "multimodal_observation",
        "internal_audit_context",
        "frozen_courtroom_dossier",
        "hearing_memory",
        "reviewed_proposal",
        "review_focus_signal",
    }:
        # 这些段已在上游按权限和结构整理完成；保留枚举、机器 ID、证据原文及会话内消息，
        # 否则本地化替换可能让 fact_id/evidence_id 失去可验证性。
        return value
    if name == "current_turn":
        return _normalize_current_turn(value, actor_role=actor_role)
    if name == "initiator_statement_transcript":
        # 这是 Java 已过滤的单方审计原文，必须逐字保留，便于回看“当事人实际说了什么”。
        return value
    if name == "execution_tool_intentions":
        return value
    localized = localize_context_tree(value)
    if name in {
        "canonical_case_dossier",
        "latest_canvas_snapshot",
        "intake_initial_form",
    }:
        return apply_platform_narrative_tree(localized, actor_role=actor_role)
    return localized


# 所属模块：Agent Harness > 节点上下文合同 > 当前发言双轨表达。
# 具体功能：`_normalize_current_turn` 无论输入是纯文本还是字典，都产出可追溯的 `raw_statement` 与第三人称 `platform_statement`；字典中的事件 ID、角色和附件信息继续保留。
# 上下游：上游是 current_turn 合同段；下游是提示词中的本轮事实理解，以及 `_restore_raw_statement_fields` 对审计原文字段的回填。
# 系统意义：模型可使用中立平台表述推理，同时后续审核仍能对照原话，避免“改写后的摘要”冒充当事人原始陈述。
def _normalize_current_turn(value: Any, *, actor_role: str | None) -> Any:
    if not isinstance(value, dict):
        text = str(value or "")
        return {
            "raw_statement": text,
            "platform_statement": localize_context_tree(
                rewrite_platform_narrative(
                    text,
                    actor_role=actor_role,
                )
            ),
        }
    # `dict(value)` 创建浅拷贝，下面增加字段时不会改动调用方持有的原始请求字典。
    normalized = dict(value)
    role = str(normalized.get("role") or normalized.get("actor_role") or actor_role or "")
    text = str(normalized.get("text") or normalized.get("raw_text") or "")
    if text:
        normalized["raw_statement"] = text
        normalized["platform_statement"] = rewrite_platform_narrative(
            text,
            actor_role=role or actor_role,
        )
    localized = localize_context_tree(normalized)
    _restore_raw_statement_fields(localized, normalized)
    return localized


# 所属模块：Agent Harness > 节点上下文合同 > 原始陈述防改写回填。
# 具体功能：`_restore_raw_statement_fields` 在整棵树完成本地化后，把约定的原话、引语和最新消息字段从 original 覆盖回 localized；函数直接修改传入字典且不返回值。
# 上下游：上游是 `_normalize_current_turn`；下游是最终 current_turn Prompt 段和审计追溯链路。
# 系统意义：本地化只应处理平台展示语，绝不能改变证据引用或当事人原话；显式字段白名单让该边界可检查。
def _restore_raw_statement_fields(
    localized: dict[str, Any],
    original: Mapping[str, Any],
) -> None:
    """恢复原始陈述字段。

    localize_context_tree 可能会处理树上的文本，但原始当事人陈述需要保留原样，
    以便后续审计能追溯“用户到底说了什么”。
    """

    raw_keys = (
        "raw_statement",
        "original_statement",
        "user_original_statement",
        "merchant_original_statement",
        "latest_party_message",
        "quote",
    )
    for key in raw_keys:
        if key in original:
            localized[key] = original[key]
