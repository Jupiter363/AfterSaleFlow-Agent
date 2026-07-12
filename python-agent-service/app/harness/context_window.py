from __future__ import annotations

import json
from dataclasses import dataclass


@dataclass(frozen=True)
class PromptSection:
    name: str
    content: str
    priority: int = 50
    required: bool = False
    trust_level: str = "untrusted"
    prompt_included: bool = True

    def estimated_tokens(self) -> int:
        if not self.content or not self.prompt_included:
            return 0
        return max(1, (len(self.name) + len(self.content) + 3) // 4)


@dataclass(frozen=True)
class AssembledPromptContext:
    sections: tuple[PromptSection, ...]
    estimated_tokens: int
    omitted_section_names: tuple[str, ...]

    def as_prompt_payload(self) -> dict[str, object]:
        return {
            "sections": [
                {
                    "name": section.name,
                    "content": _structured_section_content(section.content),
                    "estimated_tokens": section.estimated_tokens(),
                    "priority": section.priority,
                    "required": section.required,
                    "trust_level": section.trust_level,
                }
                for section in self.sections
            ],
            "estimated_tokens": self.estimated_tokens,
            "omitted_section_names": list(self.omitted_section_names),
        }


def _structured_section_content(content: str) -> object:
    """Keep JSON context as JSON instead of embedding escaped JSON strings."""

    try:
        return json.loads(content)
    except (TypeError, json.JSONDecodeError):
        return content


class ContextWindowManager:
    """Selects prompt context sections by priority under a token budget."""

    def __init__(self, default_max_input_tokens: int = 32_000) -> None:
        if default_max_input_tokens < 1:
            raise ValueError("default_max_input_tokens must be positive")
        self._default_max_input_tokens = default_max_input_tokens

    def assemble(
        self,
        sections: list[PromptSection],
        *,
        max_input_tokens: int | None = None,
    ) -> AssembledPromptContext:
        budget = max_input_tokens or self._default_max_input_tokens
        if budget < 1:
            raise ValueError("max_input_tokens must be positive")

        selected: list[PromptSection] = []
        omitted: list[str] = []
        used = 0
        ordered = sorted(
            [
                section
                for section in sections
                if section.content and section.prompt_included
            ],
            key=lambda section: (
                not section.required,
                -section.priority,
                section.name,
            ),
        )
        for section in ordered:
            cost = section.estimated_tokens()
            if used + cost <= budget:
                selected.append(section)
                used += cost
                continue
            if section.required:
                raise ValueError(
                    f"required context section {section.name} exceeds token budget"
                )
            omitted.append(section.name)

        return AssembledPromptContext(
            sections=tuple(selected),
            estimated_tokens=used,
            omitted_section_names=tuple(omitted),
        )
