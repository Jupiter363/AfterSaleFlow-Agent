"""Phase 8 candidate tooling package.

Submodules are intentionally loaded only when explicitly imported so trusted
stdlib-only checkpoint tools do not inherit optional evidence dependencies.
"""

from typing import Any


__all__ = [
    "ENGINEERING_LOCAL",
    "EXTERNAL_SIGNED",
    "EvidenceValidationError",
    "seal_evidence",
    "validate_evidence",
]
_EVIDENCE_EXPORTS = frozenset(__all__)


def __getattr__(name: str) -> Any:
    if name not in _EVIDENCE_EXPORTS:
        raise AttributeError(name)
    from . import evidence_schema

    return vars(evidence_schema)[name]
