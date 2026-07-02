"""Evidence Clerk: versioned organization without liability reasoning."""

from __future__ import annotations

from collections import defaultdict
import hashlib

from app.agents.profiles import final_agent_profiles
from app.schemas import (
    ClaimIssueEvidenceLink,
    EvidenceBuildRequest,
    EvidenceCatalogEntry,
    EvidenceDossierResult,
    EvidenceTimelineEvent,
)


class EvidenceClerk:
    """Build a lossless dossier shell from trusted evidence records.

    Parsing and summarization may be added by governed tools later. The clerk
    deliberately keeps original references and never fills gaps by inference.
    """

    def __init__(self) -> None:
        self.profile = final_agent_profiles()["evidence_clerk"]

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
        )


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
