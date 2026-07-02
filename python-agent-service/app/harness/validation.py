"""Structured Agent output and source-reference validation."""

from __future__ import annotations

from typing import Generic, TypeVar

from pydantic import BaseModel


OutputT = TypeVar("OutputT", bound=BaseModel)


class CitationValidationError(ValueError):
    """Raised when an Agent cites evidence or rules outside frozen input."""


class StructuredOutputValidator(Generic[OutputT]):
    """Validates schema before checking references against frozen context."""

    def __init__(
        self,
        output_model: type[OutputT],
        *,
        available_evidence_refs: set[str] | None = None,
        available_rule_refs: set[str] | None = None,
    ) -> None:
        self._output_model = output_model
        self._evidence_refs = available_evidence_refs or set()
        self._rule_refs = available_rule_refs or set()

    def validate(self, raw: object) -> OutputT:
        output = self._output_model.model_validate(raw)
        data = output.model_dump()
        self._validate_refs(
            "evidence",
            set(data.get("evidence_refs", [])),
            self._evidence_refs,
        )
        self._validate_refs(
            "rule",
            set(data.get("rule_refs", [])),
            self._rule_refs,
        )
        return output

    @staticmethod
    def _validate_refs(
        kind: str, actual: set[str], available: set[str]
    ) -> None:
        unknown = actual - available
        if unknown:
            raise CitationValidationError(
                f"unknown {kind} references: {sorted(unknown)}"
            )
