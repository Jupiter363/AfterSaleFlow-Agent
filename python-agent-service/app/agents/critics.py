"""Five narrowly scoped deliberation critics."""

from __future__ import annotations

from collections.abc import Callable
import hashlib

from app.agents.profiles import final_agent_profiles
from app.schemas import (
    CriticDraft,
    CriticReport,
    CriticSeverity,
    CriticStatus,
    CriticType,
    FrozenDeliberationInput,
)


CriticEvaluator = Callable[
    [CriticType, FrozenDeliberationInput, str],
    CriticDraft,
]

_SCOPE = {
    CriticType.EVIDENCE: "EVIDENCE",
    CriticType.RULE: "RULE",
    CriticType.RISK: "RISK",
    CriticType.REMEDY: "REMEDY",
    CriticType.FAIRNESS: "FAIRNESS",
}

_PROFILE = {
    CriticType.EVIDENCE: "evidence_critic",
    CriticType.RULE: "rule_critic",
    CriticType.RISK: "risk_critic",
    CriticType.REMEDY: "remedy_critic",
    CriticType.FAIRNESS: "fairness_critic",
}


def frozen_input_fingerprint(value: FrozenDeliberationInput) -> str:
    canonical = value.model_dump_json(exclude_none=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


class CriticAgent:
    """Validate one critic output against the exact frozen panel input."""

    def __init__(
        self,
        critic_type: CriticType,
        evaluator: CriticEvaluator,
    ) -> None:
        self.critic_type = critic_type
        self.profile = final_agent_profiles()[_PROFILE[critic_type]]
        self._evaluator = evaluator

    def review(self, frozen_input: FrozenDeliberationInput) -> CriticReport:
        fingerprint = frozen_input_fingerprint(frozen_input)
        try:
            draft = self._evaluator(
                self.critic_type,
                frozen_input.model_copy(deep=True),
                fingerprint,
            )
            if (
                draft.frozen_input_fingerprint is not None
                and draft.frozen_input_fingerprint != fingerprint
            ):
                return self._failure(
                    fingerprint,
                    CriticStatus.FAILED,
                    "FROZEN_INPUT_MISMATCH",
                )
            return CriticReport(
                critic=self.critic_type,
                scope=_SCOPE[self.critic_type],
                status=CriticStatus.COMPLETED,
                severity=draft.severity,
                findings=draft.findings,
                blocking_issues=draft.blocking_issues,
                recommended_revision=draft.recommended_revision,
                frozen_input_fingerprint=fingerprint,
            )
        except TimeoutError:
            return self._failure(
                fingerprint,
                CriticStatus.TIMED_OUT,
                f"{self.critic_type.value}_TIMEOUT",
            )
        except Exception:
            return self._failure(
                fingerprint,
                CriticStatus.FAILED,
                f"{self.critic_type.value}_FAILED",
            )

    def _failure(
        self,
        fingerprint: str,
        status: CriticStatus,
        reason: str,
    ) -> CriticReport:
        return CriticReport(
            critic=self.critic_type,
            scope=_SCOPE[self.critic_type],
            status=status,
            severity=CriticSeverity.BLOCKER,
            blocking_issues=[
                (
                    "FROZEN_INPUT_MISMATCH"
                    if reason == "FROZEN_INPUT_MISMATCH"
                    else f"{self.critic_type.name}_CRITIC_UNAVAILABLE"
                )
            ],
            frozen_input_fingerprint=fingerprint,
            failure_reason=reason,
        )


def build_default_critics(
    evaluator: CriticEvaluator,
) -> list[CriticAgent]:
    return [CriticAgent(critic_type, evaluator) for critic_type in CriticType]
