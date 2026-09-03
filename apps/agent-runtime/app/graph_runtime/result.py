from __future__ import annotations

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, TypeAdapter

from app.contracts.v1.codec import canonical_sha256_omitting
from app.contracts.v1.models import (
    ArtifactOperation,
    ContractError,
    EventProposal,
    ExecutionMetadata,
    NeedsInput,
    NeedsReview,
    RoomGraphResult,
    Usage,
)


class _FrozenDraft(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class CompletedDraft(_FrozenDraft):
    status: Literal["COMPLETED"]


class NeedsInputDraft(_FrozenDraft):
    status: Literal["NEEDS_INPUT"]
    needs_input: NeedsInput


class NeedsReviewDraft(_FrozenDraft):
    status: Literal["NEEDS_REVIEW"]
    needs_review: NeedsReview


class FailedDraft(_FrozenDraft):
    status: Literal["FAILED"]
    error: ContractError


TerminalDraft = Annotated[
    CompletedDraft | NeedsInputDraft | NeedsReviewDraft | FailedDraft,
    Field(discriminator="status"),
]
TERMINAL_DRAFT_ADAPTER = TypeAdapter(TerminalDraft)


class ResultBindings(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    schema_version: Literal["room-graph-result.v1"] = "room-graph-result.v1"
    command_id: str
    logical_run_id: str
    attempt_id: str
    graph_key: str
    graph_version: str
    checkpoint_id: str
    cognitive_revision: int = Field(ge=0)
    public_event_proposals: tuple[EventProposal, ...] = Field(max_length=100)
    artifact_operations: tuple[ArtifactOperation, ...] = Field(max_length=100)
    usage: Usage
    execution_metadata: ExecutionMetadata


def project_room_graph_result(
    draft: TerminalDraft | dict[str, object],
    bindings: ResultBindings,
) -> RoomGraphResult:
    terminal = TERMINAL_DRAFT_ADAPTER.validate_python(draft)
    payload = bindings.model_dump(mode="json", exclude_none=True)
    payload.update(terminal.model_dump(mode="json", exclude_none=True))
    payload["output_hash"] = "0" * 64
    payload["output_hash"] = canonical_sha256_omitting(payload, "output_hash")
    return RoomGraphResult.model_validate(payload)
