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
    node_name: str
    sections: tuple[PromptSection, ...]
    display_only_section_names: tuple[str, ...]
    configuration_profile_key: str
    configuration_source: str = "code"

    def prompt_sections(self) -> list[PromptSection]:
        return [section for section in self.sections if section.prompt_included]


def build_context_pack(
    node_name: str,
    sources: Mapping[str, Any],
    *,
    actor_role: str | None = None,
) -> ContextPack:
    contract = context_contract(node_name)
    sections: list[PromptSection] = []
    display_only: list[str] = []
    for spec in contract.sections:
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


def _section_content(
    name: str,
    value: Any,
    *,
    actor_role: str | None,
) -> str:
    if value is None:
        return ""
    normalized = _normalize_section_value(name, value, actor_role=actor_role)
    if isinstance(normalized, str):
        return normalized
    return json.dumps(normalized, ensure_ascii=False, separators=(",", ":"))


def _normalize_section_value(
    name: str,
    value: Any,
    *,
    actor_role: str | None,
) -> Any:
    if name == "current_turn":
        return _normalize_current_turn(value, actor_role=actor_role)
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


def _restore_raw_statement_fields(
    localized: dict[str, Any],
    original: Mapping[str, Any],
) -> None:
    raw_keys = (
        "raw_statement",
        "user_original_statement",
        "merchant_original_statement",
        "latest_party_message",
        "quote",
    )
    for key in raw_keys:
        if key in original:
            localized[key] = original[key]
