# 文件作用：Python Agent 服务代码文件，承载售后争议智能体的 API、配置、模型调用或业务流程。

"""Evidence Clerk: versioned organization without liability reasoning."""

from __future__ import annotations

from collections import defaultdict
import hashlib

from app.agents.profiles import final_agent_profiles
from app.schemas import (
    ClaimIssueEvidenceLink,
    DossierEvidenceItem,
    EvidenceBuildRequest,
    EvidenceCatalogEntry,
    EvidenceDossierResult,
    EvidenceTimelineEvent,
    EvidenceVerificationAssessment,
)


class EvidenceClerk:
    """Build a lossless dossier shell from trusted evidence records.

    Parsing and summarization may be added by governed tools later. The clerk
    deliberately keeps original references and never fills gaps by inference.
    """

    # 所属模块：证据室 Agent > 证据数据契约；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖；关键协作调用：`final_agent_profiles`。
    # 上下游：上游为 Java 按参与方权限筛选的证据、事实目标、私有会话；下游为 协作调用 `final_agent_profiles`。
    # 系统意义：该函数在系统中的业务边界是：只核验证据，不定责；模型不得引用本轮不可见材料。
    def __init__(self) -> None:
        self.profile = final_agent_profiles()["evidence_clerk"]

    # 所属模块：证据室 Agent > 证据数据契约；函数角色：类/闭包内部方法。
    # 具体功能：`build` 把上游材料组装为本阶段可消费的本阶段状态；关键协作调用：`EvidenceDossierResult`、`EvidenceCatalogEntry`、`ClaimIssueEvidenceLink`。
    # 上下游：上游为 Java 按参与方权限筛选的证据、事实目标、私有会话；下游为 本文件的 `_duplicate_groups`、`_potential_conflicts`、`_verification_assessment`。
    # 系统意义：该函数在系统中的业务边界是：只核验证据，不定责；模型不得引用本轮不可见材料。
    def build(self, request: EvidenceBuildRequest) -> EvidenceDossierResult:
        catalog = [
            EvidenceCatalogEntry(
                evidence_id=item.evidence_id,
                evidence_type=item.evidence_type,
                source_type=item.source_type,
                original_ref=item.evidence_id,
                original_content=item.content,
                parsed_text=item.parsed_text,
                agent_summary=item.agent_summary,
                is_party_statement=item.evidence_type == "PARTY_STATEMENT",
            )
            for item in request.evidence
        ]
        matrix = [
            ClaimIssueEvidenceLink(
                claim_id=claim.claim_id,
                evidence_refs=[
                    item.evidence_id
                    for item in request.evidence
                    if claim.claim_id in item.related_claim_ids
                ],
            )
            for claim in request.party_claims
        ]
        timeline = [
            EvidenceTimelineEvent(
                event_id=f"TIMELINE_{index}",
                occurred_at=item.occurred_at,
                description=(
                    item.agent_summary
                    or item.parsed_text
                    or item.content
                ),
                source_refs=[item.evidence_id],
            )
            for index, item in enumerate(request.evidence, start=1)
            if item.occurred_at is not None
        ]
        duplicate_groups = _duplicate_groups(request)
        parser_warnings = [
            f"{item.evidence_id}: {item.parser_warning}"
            for item in request.evidence
            if item.parser_warning
        ]
        gaps = [
            f"No evidence linked to claim {link.claim_id}"
            for link in matrix
            if not link.evidence_refs
        ]
        conflicts = _potential_conflicts(request)
        evidence_refs = [item.evidence_id for item in request.evidence]
        conflicting_types = {
            item.evidence_type
            for item in request.evidence
            if any(
                conflict.startswith(f"{item.evidence_type} ")
                for conflict in conflicts
            )
        }
        verification_recommendations = [
            _verification_assessment(item, conflicting_types)
            for item in request.evidence
        ]
        visibility_warnings = [
            item.visibility_warning
            for item in verification_recommendations
            if item.visibility_warning is not None
        ]
        return EvidenceDossierResult(
            case_id=request.case_id,
            dossier_version=(request.current_dossier_version or 0) + 1,
            party_claims=request.party_claims,
            timeline=timeline,
            evidence_catalog=catalog,
            claim_issue_evidence_matrix=matrix,
            conflicts=conflicts,
            gaps=gaps,
            duplicate_groups=duplicate_groups,
            parser_warnings=parser_warnings,
            source_citations=evidence_refs,
            deterministic_evidence_refs=evidence_refs,
            verification_recommendations=verification_recommendations,
            visibility_warnings=visibility_warnings,
        )


# 所属模块：证据室 Agent > 证据数据契约；函数角色：模块私有业务函数。
# 具体功能：`_duplicate_groups` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`defaultdict`、`join`、`hexdigest`。
# 上下游：上游为 本文件的 `EvidenceClerk.build`；下游为 协作调用 `defaultdict`、`join`、`hexdigest`、`append`。
# 系统意义：该函数在系统中的业务边界是：只核验证据，不定责；模型不得引用本轮不可见材料。
def _duplicate_groups(
    request: EvidenceBuildRequest,
) -> list[list[str]]:
    grouped: dict[str, list[str]] = defaultdict(list)
    for item in request.evidence:
        normalized = " ".join(item.content.casefold().split())
        digest = hashlib.sha256(normalized.encode("utf-8")).hexdigest()
        grouped[digest].append(item.evidence_id)
    return [
        evidence_ids
        for evidence_ids in grouped.values()
        if len(evidence_ids) > 1
    ]


# 所属模块：证据室 Agent > 证据数据契约；函数角色：模块私有业务函数。
# 具体功能：`_potential_conflicts` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`defaultdict`、`join`、`add`。
# 上下游：上游为 本文件的 `EvidenceClerk.build`；下游为 协作调用 `defaultdict`、`join`、`add`、`split`。
# 系统意义：该函数在系统中的业务边界是：只核验证据，不定责；模型不得引用本轮不可见材料。
def _potential_conflicts(
    request: EvidenceBuildRequest,
) -> list[str]:
    assertions: dict[str, set[str]] = defaultdict(set)
    for item in request.evidence:
        if item.evidence_type == "PARTY_STATEMENT":
            continue
        normalized = " ".join(item.content.casefold().split())
        assertions[item.evidence_type].add(normalized)
    return [
        f"{evidence_type} evidence contains distinct source assertions"
        for evidence_type, values in assertions.items()
        if len(values) > 1
    ]


# 所属模块：证据室 Agent > 证据数据契约；函数角色：模块私有业务函数。
# 具体功能：`_verification_assessment` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`EvidenceVerificationAssessment`。
# 上下游：上游为 本文件的 `EvidenceClerk.build`；下游为 协作调用 `EvidenceVerificationAssessment`。
# 系统意义：该函数在系统中的业务边界是：只核验证据，不定责；模型不得引用本轮不可见材料。
def _verification_assessment(
    item: DossierEvidenceItem,
    conflicting_types: set[str],
) -> EvidenceVerificationAssessment:
    evidence_id = item.evidence_id
    visibility_warning = None
    if item.source_type in {"USER", "MERCHANT"}:
        visibility_warning = (
            f"{evidence_id} may become visible to the opposing party "
            "under the evidence-room policy."
        )
    if item.parser_warning:
        recommendation = "NEEDS_HUMAN_REVIEW"
        reasons = [item.parser_warning]
    elif item.evidence_type in conflicting_types:
        recommendation = "SUSPICIOUS"
        reasons = ["Conflicts with another assertion of the same evidence type."]
    elif item.source_type == "PLATFORM":
        recommendation = "VERIFIED"
        reasons = ["Reference and source provenance are platform-controlled."]
    else:
        recommendation = "PLAUSIBLE"
        reasons = ["The submitted content is internally usable but not authenticated."]
    return EvidenceVerificationAssessment(
        evidence_ref=evidence_id,
        recommendation=recommendation,
        reasons=reasons,
        visibility_warning=visibility_warning,
    )


__all__ = ["EvidenceClerk"]
