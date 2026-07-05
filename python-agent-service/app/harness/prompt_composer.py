from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class PromptTemplateRef:
    agent_key: str
    filename: str


class PromptComposer:
    """Composes common harness fragments with agent-owned prompt templates."""

    NODE_TEMPLATES: dict[str, PromptTemplateRef] = {
        "intake_analyze": PromptTemplateRef(
            "dispute_intake_officer",
            "intake_analyze.md",
        ),
        "evaluation_analyze": PromptTemplateRef(
            "evaluation_agent",
            "evaluation_analyze.md",
        ),
        "issue_framing_node": PromptTemplateRef(
            "presiding_judge",
            "issue_framing_node.md",
        ),
        "evidence_gap_request_node": PromptTemplateRef(
            "presiding_judge",
            "evidence_gap_request_node.md",
        ),
        "party_liaison_node": PromptTemplateRef(
            "presiding_judge",
            "party_liaison_node.md",
        ),
        "evidence_cross_check_node": PromptTemplateRef(
            "presiding_judge",
            "evidence_cross_check_node.md",
        ),
        "rule_application_node": PromptTemplateRef(
            "presiding_judge",
            "rule_application_node.md",
        ),
        "adjudication_draft_node": PromptTemplateRef(
            "presiding_judge",
            "adjudication_draft_node.md",
        ),
        "evidence_critic": PromptTemplateRef(
            "deliberation_panel",
            "evidence_critic.md",
        ),
        "rule_critic": PromptTemplateRef(
            "deliberation_panel",
            "rule_critic.md",
        ),
        "risk_critic": PromptTemplateRef(
            "deliberation_panel",
            "risk_critic.md",
        ),
        "remedy_critic": PromptTemplateRef(
            "deliberation_panel",
            "remedy_critic.md",
        ),
        "fairness_critic": PromptTemplateRef(
            "deliberation_panel",
            "fairness_critic.md",
        ),
        "review_copilot": PromptTemplateRef(
            "review_copilot",
            "review_copilot.md",
        ),
    }

    COMMON_FRAGMENT_FILES: tuple[str, ...] = (
        "safety_boundary.md",
        "json_output_rules.md",
    )

    def __init__(
        self,
        *,
        app_root: Path | None = None,
        harness_prompt_dir: Path | None = None,
        agent_prompt_root: Path | None = None,
    ) -> None:
        self._app_root = app_root or Path(__file__).resolve().parents[1]
        self._harness_prompt_dir = (
            harness_prompt_dir
            or self._app_root / "harness" / "prompts"
        )
        self._agent_prompt_root = (
            agent_prompt_root
            or self._app_root / "agents" / "prompts"
        )

    def render(
        self,
        node_name: str,
        case_data: dict[str, Any],
        output_schema: dict[str, Any],
    ) -> tuple[str, str]:
        system_prompt = self.render_system_prompt(node_name)
        user_prompt = self.render_user_prompt(case_data, output_schema)
        return system_prompt, user_prompt

    def render_system_prompt(self, node_name: str) -> str:
        fragments = [
            self._read_required(self._harness_prompt_dir / filename)
            for filename in self.COMMON_FRAGMENT_FILES
        ]
        fragments.append(self._read_required(self._absolute_template_path(node_name)))
        return "\n\n".join(fragment.strip() for fragment in fragments if fragment.strip())

    def render_user_prompt(
        self,
        case_data: dict[str, Any],
        output_schema: dict[str, Any],
    ) -> str:
        return (
            "<untrusted_case_data>\n"
            + json.dumps(case_data, ensure_ascii=False, separators=(",", ":"))
            + "\n</untrusted_case_data>\n"
            + "<required_output_schema>\n"
            + json.dumps(output_schema, ensure_ascii=False, separators=(",", ":"))
            + "\n</required_output_schema>"
        )

    def template_path(self, node_name: str) -> Path:
        return self._absolute_template_path(node_name).relative_to(self._app_root.parent)

    def _absolute_template_path(self, node_name: str) -> Path:
        ref = self.NODE_TEMPLATES.get(node_name)
        if ref is None:
            raise KeyError(f"unknown prompt node: {node_name}")
        return self._agent_prompt_root / ref.agent_key / ref.filename

    @staticmethod
    def _read_required(path: Path) -> str:
        try:
            return path.read_text(encoding="utf-8").strip()
        except FileNotFoundError as exception:
            raise FileNotFoundError(f"prompt template not found: {path}") from exception


class PromptRepository(PromptComposer):
    """Backward-compatible name for the shared prompt composer."""
