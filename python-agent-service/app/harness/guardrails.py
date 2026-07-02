"""Guardrails for untrusted evidence and non-final Agent output."""

from __future__ import annotations

import re


class GuardrailViolation(ValueError):
    def __init__(self, risk_flags: tuple[str, ...]) -> None:
        self.risk_flags = risk_flags
        super().__init__(", ".join(risk_flags))


class GuardrailChecker:
    """Detects authority escalation before model input/output is committed."""

    _input_patterns = (
        re.compile(r"ignore\s+(all\s+)?previous\s+instructions", re.I),
        re.compile(r"system\s+prompt", re.I),
        re.compile(r"(refund|reship|review)\.execute", re.I),
        re.compile(r"review\.approve", re.I),
    )
    _final_output_patterns = (
        re.compile(r"\bfinal\s+(decision|ruling|judgment)\b", re.I),
        re.compile(r"\bmust\s+(refund|reship|reject|close)\b", re.I),
        re.compile(r"\bapproved\s+for\s+execution\b", re.I),
    )

    def assert_safe_input(self, text: str) -> None:
        flags = []
        if any(pattern.search(text) for pattern in self._input_patterns):
            flags.append("PROMPT_INJECTION")
        if flags:
            raise GuardrailViolation(tuple(flags))

    def assert_safe_output(self, text: str) -> None:
        flags = []
        if any(
            pattern.search(text) for pattern in self._final_output_patterns
        ):
            flags.append("FINAL_DECISION_CLAIM")
        if flags:
            raise GuardrailViolation(tuple(flags))
