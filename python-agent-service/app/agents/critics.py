# 文件作用：Python Agent 服务代码文件，承载售后争议智能体的 API、配置、模型调用或业务流程。

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


# 所属模块：合议评审 Agent；函数角色：模块公开业务函数。
# 具体功能：`frozen_input_fingerprint` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`value.model_dump_json`、`hexdigest`、`hashlib.sha256`。
# 上下游：上游为 本文件的 `CriticAgent.review`；下游为 协作调用 `value.model_dump_json`、`hexdigest`、`hashlib.sha256`、`canonical.encode`。
# 系统意义：该函数在系统中的业务边界是：只提出风险和分歧，不修改正式裁判。
def frozen_input_fingerprint(value: FrozenDeliberationInput) -> str:
    canonical = value.model_dump_json(exclude_none=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


class CriticAgent:
    """Validate one critic output against the exact frozen panel input."""

    # 所属模块：合议评审 Agent；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖；关键协作调用：`final_agent_profiles`。
    # 上下游：上游为 法官草案、证据分析、规则适用；下游为 协作调用 `final_agent_profiles`。
    # 系统意义：该函数在系统中的业务边界是：只提出风险和分歧，不修改正式裁判。
    def __init__(
        self,
        critic_type: CriticType,
        evaluator: CriticEvaluator,
    ) -> None:
        self.critic_type = critic_type
        self.profile = final_agent_profiles()[_PROFILE[critic_type]]
        self._evaluator = evaluator

    # 所属模块：合议评审 Agent；函数角色：类/闭包内部方法。
    # 具体功能：`review` 围绕人工复核信息计算该函数独立负责的业务派生值；关键协作调用：`self._evaluator`、`CriticReport`、`frozen_input.model_copy`。
    # 上下游：上游为 法官草案、证据分析、规则适用；下游为 本文件的 `frozen_input_fingerprint`、`_failure`。
    # 系统意义：该函数在系统中的业务边界是：只提出风险和分歧，不修改正式裁判。
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

    # 所属模块：合议评审 Agent；函数角色：类/闭包内部方法。
    # 具体功能：`_failure` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`CriticReport`。
    # 上下游：上游为 本文件的 `CriticAgent.review`；下游为 协作调用 `CriticReport`。
    # 系统意义：该函数在系统中的业务边界是：只提出风险和分歧，不修改正式裁判。
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


# 所属模块：合议评审 Agent；函数角色：模块公开业务函数。
# 具体功能：`build_default_critics` 把上游材料组装为本阶段可消费的合议质疑结果；关键协作调用：`CriticAgent`。
# 上下游：上游为 法官草案、证据分析、规则适用；下游为 协作调用 `CriticAgent`。
# 系统意义：该函数在系统中的业务边界是：只提出风险和分歧，不修改正式裁判。
def build_default_critics(
    evaluator: CriticEvaluator,
) -> list[CriticAgent]:
    return [CriticAgent(critic_type, evaluator) for critic_type in CriticType]
