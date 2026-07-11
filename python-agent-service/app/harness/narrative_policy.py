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


def _subject_for_role(actor_role: str | None) -> str:
    return "商家" if str(actor_role or "").upper() == "MERCHANT" else "用户"


def _role_from_key(key: str, fallback: str | None) -> str | None:
    if key.startswith("merchant_"):
        return "MERCHANT"
    if key.startswith("user_") or key.startswith("buyer_"):
        return "USER"
    return fallback
