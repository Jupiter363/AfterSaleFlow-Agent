# 文件作用：把平台摘要中的第一人称主张改成带角色来源的第三人称叙事，同时逐字保留当事人原始陈述字段。

from __future__ import annotations

from typing import Any


PLATFORM_NARRATIVE_KEYS = {
    "title",
    "one_sentence_summary",
    "event",
    "user_claim",
    "merchant_claim",
    "platform_observation",
    "core_issue",
    "key_conflicts",
    "facts_to_verify",
    "expected_resolution_text",
    "reasoning",
    "blocking_gaps",
    "nice_to_have_gaps",
    "next_questions",
    "improvement_reason",
}

RAW_STATEMENT_KEYS = {
    "raw_statement",
    "original_statement",
    "user_original_statement",
    "merchant_original_statement",
    "latest_party_message",
    "quote",
}


# 所属模块：Agent Harness > 平台叙事 > 单段第一人称改写。
# 具体功能：`rewrite_platform_narrative` 根据 actor_role 选择“用户/商家”，把我方、我们、本店、我的等第一人称转换成第三人称；发生改写时补上“某方称”。
# 上下游：上游是 ContextPack 的平台摘要字段或 `apply_platform_narrative_tree`；下游是中立的卷宗摘要、Prompt 上下文和展示文案。
# 系统意义：平台转述必须明确“谁主张”，不能把用户/商家自述改写成平台已认定事实；原话另有 raw_statement 保存。
def rewrite_platform_narrative(text: str, *, actor_role: str | None = None) -> str:
    stripped = str(text or "").strip()
    if not stripped:
        return ""
    subject = _subject_for_role(actor_role)
    rewritten = stripped
    rewritten = rewritten.replace("我方", subject)
    rewritten = rewritten.replace("我们", subject)
    rewritten = rewritten.replace("本店", subject)
    rewritten = rewritten.replace("本人", "本人")
    rewritten = rewritten.replace("我的", "其")
    rewritten = rewritten.replace("我", "本人")
    if rewritten != stripped and not rewritten.startswith((subject, "用户称", "商家称")):
        rewritten = f"{subject}称{rewritten}"
    return rewritten


# 所属模块：Agent Harness > 平台叙事 > 结构化树选择性改写。
# 具体功能：`apply_platform_narrative_tree` 递归复制 dict/list，只改 PLATFORM_NARRATIVE_KEYS 中的字符串；RAW_STATEMENT_KEYS 永远直返，并由字段名前缀推导用户/商家角色。
# 上下游：上游是接待卷宗、案件摘要、画布等上下文树；下游是 `_role_from_key`、`rewrite_platform_narrative` 及最终 ContextPack。
# 系统意义：改写范围采用字段白名单，防止证据正文、ID、引用和任意未知字段被全局字符串替换污染。
def apply_platform_narrative_tree(
    value: Any,
    *,
    actor_role: str | None = None,
    current_key: str | None = None,
) -> Any:
    if isinstance(value, dict):
        return {
            key: apply_platform_narrative_tree(
                item,
                actor_role=_role_from_key(key, actor_role),
                current_key=key,
            )
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [
            apply_platform_narrative_tree(
                item,
                actor_role=actor_role,
                current_key=current_key,
            )
            for item in value
        ]
    if isinstance(value, str):
        if current_key in RAW_STATEMENT_KEYS:
            return value
        if current_key in PLATFORM_NARRATIVE_KEYS:
            return rewrite_platform_narrative(value, actor_role=actor_role)
    return value


# 所属模块：Agent Harness > 平台叙事 > 角色到中文主语映射。
# 具体功能：`_subject_for_role` 将大小写不敏感的 MERCHANT 映射为“商家”，其他/缺失角色保守映射为“用户”。
# 上下游：上游是 `rewrite_platform_narrative`；下游是第三人称前缀与代词替换。
# 系统意义：集中映射避免各业务模块自行猜测称谓；角色授权本身仍由 AgentInvocationContext 校验，不由该文案函数决定。
def _subject_for_role(actor_role: str | None) -> str:
    return "商家" if str(actor_role or "").upper() == "MERCHANT" else "用户"


# 所属模块：Agent Harness > 平台叙事 > 字段名前缀角色推导。
# 具体功能：`_role_from_key` 让 merchant_* 子树使用商家主语，user_*/buyer_* 子树使用用户主语；无明确前缀时继承父级 fallback。
# 上下游：上游是 `apply_platform_narrative_tree` 遍历每个字典键；下游是子树递归时的 actor_role。
# 系统意义：同一卷宗可同时包含双方摘要，按字段上下文切换主语可防止把商家主张误标成用户主张。
def _role_from_key(key: str, fallback: str | None) -> str | None:
    if key.startswith("merchant_"):
        return "MERCHANT"
    if key.startswith("user_") or key.startswith("buyer_"):
        return "USER"
    return fallback
