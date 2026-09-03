# 文件作用：Python Agent 服务代码文件，承载售后争议智能体的 API、配置、模型调用或业务流程。

"""Case detail dossier skill package for the dispute intake officer."""

from app.agents.dispute_intake_officer.skills.dossier.dossier_skill import (
    CaseDetailDossierSkill,
    DossierRenderResult,
)

__all__ = ["CaseDetailDossierSkill", "DossierRenderResult"]
