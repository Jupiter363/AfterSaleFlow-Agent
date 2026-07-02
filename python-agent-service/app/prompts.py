from __future__ import annotations

import json
from pathlib import Path
from typing import Any


class PromptRepository:
    NODE_FILES = {
        "intake_analyze": "intake_analyze.md",
        "evaluation_analyze": "evaluation_analyze.md",
        "issue_framing_node": "issue_framing_node.md",
        "evidence_gap_request_node": "evidence_gap_request_node.md",
        "party_liaison_node": "party_liaison_node.md",
        "evidence_cross_check_node": "evidence_cross_check_node.md",
        "rule_application_node": "rule_application_node.md",
        "adjudication_draft_node": "adjudication_draft_node.md",
        "evidence_critic": "evidence_critic.md",
        "rule_critic": "rule_critic.md",
        "risk_critic": "risk_critic.md",
        "remedy_critic": "remedy_critic.md",
        "fairness_critic": "fairness_critic.md",
        "review_copilot": "review_copilot.md",
    }

    def __init__(self, prompt_dir: Path | None = None) -> None:
        self._prompt_dir = prompt_dir or Path(__file__).with_name("prompts")

    def render(
        self,
        node_name: str,
        case_data: dict[str, Any],
        output_schema: dict[str, Any],
    ) -> tuple[str, str]:
        filename = self.NODE_FILES.get(node_name)
        if filename is None:
            raise KeyError(f"unknown prompt node: {node_name}")
        system_prompt = (self._prompt_dir / filename).read_text(encoding="utf-8").strip()
        user_prompt = (
            "<untrusted_case_data>\n"
            + json.dumps(case_data, ensure_ascii=False, separators=(",", ":"))
            + "\n</untrusted_case_data>\n"
            + "<required_output_schema>\n"
            + json.dumps(output_schema, ensure_ascii=False, separators=(",", ":"))
            + "\n</required_output_schema>"
        )
        return system_prompt, user_prompt
