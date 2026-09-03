# 文件作用：Python Agent 服务代码文件，承载售后争议智能体的 API、配置、模型调用或业务流程。

"""Authenticity and relevance checks for evidence-room dialogue."""

from app.agents.evidence_clerk.skills.authenticity.authenticity_skill import (
    EvidenceAuthenticitySkill,
)

__all__ = ["EvidenceAuthenticitySkill"]
