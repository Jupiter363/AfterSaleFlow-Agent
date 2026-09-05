"""Request-bound citation choices for the read-only review model, not authority."""

from typing import Literal

from pydantic import Field, create_model

from app.schemas import ReviewCopilotAnswer, ReviewCopilotRequest, ReviewStatement


def review_citation_catalog(request: ReviewCopilotRequest) -> dict[str, list[str]]:
    groups = {
        "fact_refs": list(request.available_fact_refs),
        "rule_refs": list(request.available_rule_refs),
        "draft_refs": list(request.available_draft_refs),
        "deliberation_refs": list(request.available_deliberation_refs),
    }
    return {**groups, "statement_refs": sorted({ref for refs in groups.values() for ref in refs})}


def _refs(choices: list[str]):
    if not choices:
        return (list[str], Field(default_factory=list, max_length=0))
    item_type = Literal[tuple(sorted(set(choices)))]
    return (list[item_type], Field(default_factory=list))


def review_answer_type(request: ReviewCopilotRequest) -> type[ReviewCopilotAnswer]:
    catalog = review_citation_catalog(request)
    statement_type = create_model(
        "RequestBoundReviewStatement", __base__=ReviewStatement,
        refs=_refs(catalog["statement_refs"]),
    )
    return create_model(
        "RequestBoundReviewAnswer", __base__=ReviewCopilotAnswer,
        statements=(list[statement_type], Field(min_length=1, max_length=50)),
        **{field: _refs(choices) for field, choices in catalog.items() if field != "statement_refs"},
    )
