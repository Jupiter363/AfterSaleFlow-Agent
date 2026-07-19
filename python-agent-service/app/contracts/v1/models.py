from __future__ import annotations

from typing import Annotated, Literal

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
Traceparent = Annotated[
    str,
    StringConstraints(pattern=r"^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$"),
]
ObjectUri = Annotated[
    str,
    StringConstraints(max_length=1024, pattern=r"^(s3|minio|urn):"),
]
GraphThreadId = Annotated[
    str,
    StringConstraints(pattern=r"^grt\.v1\.[0-9a-f]{32}$"),
]
CheckpointNamespace = Annotated[
    str,
    StringConstraints(max_length=128, pattern=r"^[A-Za-z0-9._:-]{0,128}$"),
]
ActorRole = Literal["USER", "MERCHANT", "PLATFORM_REVIEWER", "ADMIN", "SYSTEM"]
Audience = Literal["USER", "MERCHANT", "PLATFORM_REVIEWER", "SYSTEM"]
RoomType = Literal["INTAKE", "EVIDENCE", "HEARING", "REVIEW"]


class StrictContractModel(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class ActorRef(StrictContractModel):
    actor_id: Identifier
    actor_role: ActorRole
    actor_scopes: tuple[Identifier, ...] = Field(min_length=1, max_length=32)


class PayloadRef(StrictContractModel):
    schema_version: Identifier
    uri: ObjectUri
    sha256: Sha256
    size_bytes: int = Field(ge=0, le=1_073_741_824)


CommandType = Literal[
    "CASE_OPEN",
    "INTAKE_MESSAGE",
    "INTAKE_CONFIRM",
    "INTAKE_CANCEL",
    "EVIDENCE_SUBMIT",
    "PARTY_EVIDENCE_COMPLETE",
    "HEARING_STATEMENT",
    "HEARING_EVIDENCE_BATCH",
    "REVIEW_DECISION",
    "EXECUTE_APPROVED_PLAN",
    "CLOSE_CASE",
]


class CaseCommandRef(StrictContractModel):
    schema_version: Literal["case-command-ref.v1"]
    command_id: Identifier
    tenant_surrogate: Identifier
    case_id: Identifier
    case_command_sequence: int = Field(ge=1)
    command_type: CommandType
    room_type: RoomType
    room_epoch: int = Field(ge=0)
    actor_ref: ActorRef
    payload_ref: PayloadRef
    expected_process_revision: int = Field(ge=0)
    occurred_at: AwareDatetime
    deadline_at: AwareDatetime
    traceparent: Traceparent
    request_hash: Sha256


class ParentRef(StrictContractModel):
    artifact_id: Identifier
    content_hash: Sha256


class ArtifactRef(StrictContractModel):
    schema_version: Literal["artifact-ref.v1"]
    artifact_id: Identifier
    artifact_type: Identifier
    content_schema_version: Identifier
    storage_ref: ObjectUri
    content_hash: Sha256
    size_bytes: int = Field(ge=0, le=1_073_741_824)
    parent_refs: tuple[ParentRef, ...] = Field(max_length=32)
    visibility: Literal["PRIVATE", "PARTIES", "PLATFORM", "INTERNAL"]
    created_by_run_id: Identifier
    created_at: AwareDatetime


class ProcessProjection(StrictContractModel):
    schema_version: Literal["process-projection.v1"]
    tenant_surrogate: Identifier
    case_id: Identifier
    workflow_id: Identifier
    workflow_run_id: Identifier
    workflow_build_id: Identifier
    writer_mode: Literal["LEGACY", "SHADOW", "TEMPORAL"]
    macro_phase: Identifier
    room_type: RoomType
    room_phase: Identifier
    room_epoch: int = Field(ge=0)
    process_revision: int = Field(ge=0)
    room_revision: int = Field(ge=0)
    fencing_token: int = Field(ge=0)
    source_event_sequence: int = Field(ge=0)
    pending_state: Literal[
        "NONE",
        "WAITING_PARTY",
        "WAITING_TIMER",
        "AGENT_RUNNING",
        "REVIEW_PENDING",
        "TOOL_RUNNING",
        "FAILED",
    ]
    projected_at: AwareDatetime


class Usage(StrictContractModel):
    input_tokens: int = Field(ge=0)
    output_tokens: int = Field(ge=0)
    total_tokens: int = Field(ge=0)


class AgentStreamPayload(StrictContractModel):
    node: Identifier | None = None
    field: Identifier | None = None
    delta: Annotated[str, StringConstraints(min_length=1, max_length=4096)] | None = None
    usage: Usage | None = None
    reason_code: Identifier | None = None
    reset_attempt_id: Identifier | None = None
    final_result_ref: ObjectUri | None = None
    final_result_hash: Sha256 | None = None
    error_code: Identifier | None = None
    retryable: bool | None = None


StreamEventType = Literal[
    "attempt_started",
    "visible_delta",
    "usage",
    "attempt_aborted",
    "attempt_reset",
    "final",
    "error",
]


class AgentStreamEvent(StrictContractModel):
    schema_version: Literal["agent-stream.v2"]
    run_id: Identifier
    attempt_id: Identifier
    sequence_no: int = Field(ge=0)
    event_type: StreamEventType
    audience: Audience
    occurred_at: AwareDatetime
    payload: AgentStreamPayload

    @model_validator(mode="after")
    def validate_event_payload(self) -> AgentStreamEvent:
        required = {
            "attempt_started": {"node"},
            "visible_delta": {"node", "field", "delta"},
            "usage": {"usage"},
            "attempt_aborted": {"reason_code"},
            "attempt_reset": {"reset_attempt_id", "reason_code"},
            "final": {"final_result_ref", "final_result_hash"},
            "error": {"error_code", "retryable"},
        }[self.event_type]
        present = set(self.payload.model_dump(exclude_none=True))
        missing = required - present
        if missing:
            raise ValueError(f"{self.event_type} payload missing {sorted(missing)}")
        return self


class ActorScope(StrictContractModel):
    actor_id: Identifier
    actor_role: ActorRole
    audience: Audience
    capabilities: tuple[Identifier, ...] = Field(max_length=32)


class SnapshotRef(StrictContractModel):
    artifact_id: Identifier
    schema_version: Identifier
    uri: ObjectUri
    sha256: Sha256
    size_bytes: int = Field(ge=0, le=1_073_741_824)


class InvocationContext(StrictContractModel):
    agent_profile_id: Identifier
    prompt_profile_id: Identifier
    model_profile_id: Identifier
    output_schema_version: Identifier
    policy_version: Identifier
    guardrail_version: Identifier
    tool_capabilities: tuple[Identifier, ...] = Field(max_length=32)
    envelope_key_id: Identifier
    envelope_nonce: Identifier


class RetryBudget(StrictContractModel):
    provider_attempts_remaining: int = Field(ge=0, le=2)
    activity_attempts_remaining: int = Field(ge=0, le=3)
    repairs_remaining: int = Field(ge=0, le=1)


class RoomGraphCommand(StrictContractModel):
    schema_version: Literal["room-graph-command.v1"]
    command_id: Identifier
    logical_run_id: Identifier
    attempt_id: Identifier
    tenant_surrogate: Identifier
    case_id: Identifier
    room_type: RoomType
    room_epoch: int = Field(ge=0)
    graph_key: Identifier
    graph_version: Identifier
    checkpoint_schema_version: Identifier
    thread_id: GraphThreadId
    actor_scope: ActorScope
    process_revision: int = Field(ge=0)
    stage_code: Identifier
    stage_sequence: int = Field(ge=0)
    domain_snapshot_ref: SnapshotRef
    event_ref: SnapshotRef | None = None
    invocation_context: InvocationContext
    retry_budget: RetryBudget
    deadline_at: AwareDatetime
    traceparent: Traceparent
    request_hash: Sha256


class ArtifactPointer(StrictContractModel):
    artifact_id: Identifier
    schema_version: Identifier
    uri: ObjectUri
    sha256: Sha256


class EventProposal(StrictContractModel):
    event_type: Identifier
    audience: Audience
    payload_ref: ObjectUri
    payload_hash: Sha256


class ArtifactOperation(StrictContractModel):
    operation: Literal["PROPOSE_CREATE", "PROPOSE_PATCH"]
    artifact: ArtifactPointer


class NeedsInput(StrictContractModel):
    reason_code: Identifier
    required_actor_scopes: tuple[Identifier, ...] = Field(min_length=1, max_length=8)


class NeedsReview(StrictContractModel):
    reason_code: Identifier
    risk_level: Literal["LOW", "MEDIUM", "HIGH", "CRITICAL"]


class ContractError(StrictContractModel):
    code: Identifier
    retryable: bool


class ExecutionMetadata(StrictContractModel):
    prompt_version: Identifier
    model_profile_id: Identifier
    schema_version: Identifier
    policy_version: Identifier
    guardrail_version: Identifier


class RoomGraphResult(StrictContractModel):
    schema_version: Literal["room-graph-result.v1"]
    command_id: Identifier
    logical_run_id: Identifier
    attempt_id: Identifier
    graph_key: Identifier
    graph_version: Identifier
    checkpoint_id: Identifier
    cognitive_revision: int = Field(ge=0)
    status: Literal["COMPLETED", "NEEDS_INPUT", "NEEDS_REVIEW", "FAILED"]
    public_event_proposals: tuple[EventProposal, ...] = Field(max_length=100)
    artifact_operations: tuple[ArtifactOperation, ...] = Field(max_length=100)
    needs_input: NeedsInput | None = None
    needs_review: NeedsReview | None = None
    error: ContractError | None = None
    output_hash: Sha256
    usage: Usage
    execution_metadata: ExecutionMetadata

    @model_validator(mode="after")
    def validate_terminal_spec(self) -> RoomGraphResult:
        fields = {
            "needs_input": self.needs_input,
            "needs_review": self.needs_review,
            "error": self.error,
        }
        expected = {
            "COMPLETED": None,
            "NEEDS_INPUT": "needs_input",
            "NEEDS_REVIEW": "needs_review",
            "FAILED": "error",
        }[self.status]
        present = {name for name, value in fields.items() if value is not None}
        if expected is None and present:
            raise ValueError("COMPLETED result cannot contain a terminal specification")
        if expected is not None and present != {expected}:
            raise ValueError(f"{self.status} result requires only {expected}")
        return self


class GraphReconcileResponse(StrictContractModel):
    schema_version: Literal["graph-reconcile-response.v1"]
    disposition: Literal["RETURN_CACHED", "RECONCILED_TERMINAL"]
    thread_id: GraphThreadId
    command_id: Identifier
    request_hash: Sha256
    logical_run_id: Identifier
    attempt_id: Identifier
    graph_key: Identifier
    graph_version: Identifier
    checkpoint_schema_version: Identifier
    checkpoint_ns: CheckpointNamespace
    checkpoint_id: Identifier
    result_ref: ObjectUri
    result_hash: Sha256
    registry_binding_hash: Sha256
    tool_policy_version: Identifier
    result: RoomGraphResult

    @model_validator(mode="after")
    def validate_result_binding(self) -> GraphReconcileResponse:
        expected = (
            self.command_id,
            self.logical_run_id,
            self.attempt_id,
            self.graph_key,
            self.graph_version,
            self.checkpoint_id,
            self.result_hash,
        )
        actual = (
            self.result.command_id,
            self.result.logical_run_id,
            self.result.attempt_id,
            self.result.graph_key,
            self.result.graph_version,
            self.result.checkpoint_id,
            self.result.output_hash,
        )
        if actual != expected:
            raise ValueError("reconciliation response conflicts with its nested result")
        return self


class WorkflowRef(StrictContractModel):
    workflow_id: Identifier
    run_id: Identifier
    workflow_type: Identifier
    build_id: Identifier


class AgentRunRef(StrictContractModel):
    logical_run_id: Identifier
    attempt_id: Identifier
    logical_idempotency_key: Identifier


class GraphRef(StrictContractModel):
    graph_key: Identifier
    graph_version: Identifier
    checkpoint_schema_version: Identifier
    checkpoint_id: Identifier
    cognitive_revision: int = Field(ge=0)


class ModelRef(StrictContractModel):
    prompt_version: Identifier
    model_profile_id: Identifier
    provider: Identifier
    model: Identifier
    request_hash: Sha256
    response_hash: Sha256


class ManifestUsage(Usage):
    latency_ms: int = Field(ge=0)


class AgentExecutionManifest(StrictContractModel):
    schema_version: Literal["agent-execution-manifest.v1"]
    manifest_id: Identifier
    tenant_surrogate: Identifier
    case_id: Identifier
    room_epoch: int = Field(ge=0)
    process_revision: int = Field(ge=0)
    fencing_token: int = Field(ge=0)
    workflow: WorkflowRef
    agent_run: AgentRunRef
    graph: GraphRef
    model: ModelRef
    contract_versions: dict[str, Identifier] = Field(min_length=1, max_length=32)
    policy_version: Identifier
    guardrail_version: Identifier
    tool_versions: tuple[Identifier, ...] = Field(max_length=32)
    inputs: tuple[ArtifactPointer, ...] = Field(min_length=1, max_length=128)
    output: ArtifactPointer
    usage: ManifestUsage
    traceparent: Traceparent
    finalized_at: AwareDatetime


MODEL_BY_SCHEMA: dict[str, type[StrictContractModel]] = {
    "case-command-ref.schema.json": CaseCommandRef,
    "room-graph-command.schema.json": RoomGraphCommand,
    "room-graph-result.schema.json": RoomGraphResult,
    "graph-reconcile-response.schema.json": GraphReconcileResponse,
    "artifact-ref.schema.json": ArtifactRef,
    "process-projection.schema.json": ProcessProjection,
    "agent-stream-event.schema.json": AgentStreamEvent,
    "agent-execution-manifest.schema.json": AgentExecutionManifest,
}
