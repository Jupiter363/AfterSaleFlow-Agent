from __future__ import annotations

from typing import Annotated, Any, Literal

from pydantic import (
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
    StringConstraints,
    model_validator,
)


Identifier = Annotated[
    str,
    StringConstraints(
        min_length=1,
        max_length=128,
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$",
    ),
]
Sha256 = Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{64}$")]
ThreadId = Annotated[
    str,
    StringConstraints(pattern=r"^grt\.v1\.[0-9a-f]{32}$"),
]
Audience = Literal["USER", "MERCHANT"]


class StrictIntakeModel(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class SnapshotMessage(StrictIntakeModel):
    message_id: Identifier
    role: Literal["HUMAN", "AI"]
    audience: Audience
    sequence: int = Field(ge=0)
    text: str = Field(max_length=8192)
    source_hash: Sha256


class IntakeDomainSnapshot(StrictIntakeModel):
    schema_version: Literal["intake-domain-snapshot.v2"]
    snapshot_id: Identifier
    tenant_surrogate: Identifier
    case_id: Identifier
    room_type: Literal["INTAKE"]
    room_epoch: int = Field(ge=0)
    thread_id: ThreadId
    actor_scope_hash: Sha256
    agent_session_id: Identifier
    domain_revision: int = Field(ge=0)
    room_revision: int = Field(ge=0)
    projection_revision: int = Field(ge=0)
    visibility: Literal["PRIVATE"]
    source_refs: tuple[Identifier, ...] = Field(min_length=1, max_length=128)
    initial_case_facts: dict[str, Any] = Field(max_length=64)
    shareable_projection: dict[str, Any] = Field(max_length=64)
    own_messages: tuple[SnapshotMessage, ...] = Field(max_length=6)
    current_dossier: dict[str, Any] = Field(max_length=64)
    created_at: AwareDatetime
    snapshot_hash: Sha256

    @model_validator(mode="after")
    def unique_source_refs(self) -> IntakeDomainSnapshot:
        if len(self.source_refs) != len(set(self.source_refs)):
            raise ValueError("source_refs must be unique")
        return self


class IntakeTurnEvent(StrictIntakeModel):
    schema_version: Literal["intake-turn-event.v2"]
    event_id: Identifier
    message_id: Identifier
    tenant_surrogate: Identifier
    case_id: Identifier
    room_type: Literal["INTAKE"]
    room_epoch: int = Field(ge=0)
    thread_id: ThreadId
    actor_scope_hash: Sha256
    agent_session_id: Identifier
    sequence_no: int = Field(ge=1)
    domain_revision: int = Field(ge=0)
    audience: Audience
    source_type: Literal["INITIAL_FORM", "ROOM_MESSAGE", "FORMAL_EVENT"]
    text: str = Field(min_length=1, max_length=8192)
    source_refs: tuple[Identifier, ...] = Field(min_length=1, max_length=32)
    occurred_at: AwareDatetime
    event_hash: Sha256

    @model_validator(mode="after")
    def unique_source_refs(self) -> IntakeTurnEvent:
        if len(self.source_refs) != len(set(self.source_refs)):
            raise ValueError("source_refs must be unique")
        return self


class DossierPatch(StrictIntakeModel):
    schema_version: Literal["intake-dossier.v2"] | None = None
    case_story: dict[str, Any] | None = Field(default=None, max_length=64)
    references: dict[str, Any] | None = Field(default=None, max_length=64)
    party_positions: dict[str, Any] | None = Field(default=None, max_length=64)
    dispute_focus: dict[str, Any] | None = Field(default=None, max_length=64)
    requested_resolution: dict[str, Any] | None = Field(default=None, max_length=64)
    claim_resolution: dict[str, Any] | None = Field(default=None, max_length=64)
    respondent_attitude: dict[str, Any] | None = Field(default=None, max_length=64)
    dispute_core_state: dict[str, Any] | None = Field(default=None, max_length=64)
    risk_assessment: dict[str, Any] | None = Field(default=None, max_length=64)
    missing_information: dict[str, Any] | None = Field(default=None, max_length=64)
    intake_quality: dict[str, Any] | None = Field(default=None, max_length=64)
    admission: dict[str, Any] | None = Field(default=None, max_length=64)
    case_fact_matrix: dict[str, Any] | None = Field(default=None, max_length=64)
    unilateral_case_matrix: dict[str, Any] | None = Field(default=None, max_length=64)


class ProposalProfileVersions(StrictIntakeModel):
    graph_version: Identifier
    checkpoint_schema_version: Identifier
    prompt_version: Identifier
    model_profile_id: Identifier
    output_schema_version: Literal["intake-turn-proposal.v2"]
    policy_version: Identifier
    guardrail_version: Identifier
    tool_policy_version: Identifier


class IntakeTurnProposal(StrictIntakeModel):
    schema_version: Literal["intake-turn-proposal.v2"]
    command_id: Identifier
    logical_run_id: Identifier
    attempt_id: Identifier
    case_id: Identifier
    room_epoch: int = Field(ge=0)
    thread_id: ThreadId
    actor_scope_hash: Sha256
    agent_session_id: Identifier
    cognitive_revision: int = Field(ge=1)
    source_snapshot_hash: Sha256
    source_event_hash: Sha256 | None = None
    room_utterance: str = Field(min_length=1, max_length=20000)
    dossier_patch: DossierPatch
    matrix_patch: dict[str, Any] | None = Field(default=None, max_length=64)
    readiness: Literal["INCOMPLETE", "READY_TO_CONFIRM", "NEEDS_REVIEW"]
    missing_fields: tuple[Identifier, ...] = Field(max_length=30)
    recommendation: Literal["ACCEPTED", "NEED_MORE_INFO", "NOT_ADMISSIBLE"]
    knowledge_answer_mode: Literal["NONE", "STUB"]
    confidence: float = Field(ge=0, le=1)
    profile_versions: ProposalProfileVersions
    proposal_hash: Sha256

    @model_validator(mode="after")
    def unique_missing_fields(self) -> IntakeTurnProposal:
        if len(self.missing_fields) != len(set(self.missing_fields)):
            raise ValueError("missing_fields must be unique")
        return self
