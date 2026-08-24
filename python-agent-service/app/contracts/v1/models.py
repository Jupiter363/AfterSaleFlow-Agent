from __future__ import annotations

from types import MappingProxyType
from typing import Annotated, Any, Final, Literal, Mapping

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

MODEL_INVOCATION_PROVIDER_ATTEMPT_LIMIT: Final[int] = 2
HEARING_EVIDENCE_SYNTHESIS_PROVIDER_ATTEMPT_LIMIT: Final[int] = 202


def command_provider_attempt_limit(
    room_type: str | None,
    stage_code: str | None,
) -> int:
    if room_type == "HEARING" and stage_code == "EVIDENCE_SYNTHESIZING":
        return HEARING_EVIDENCE_SYNTHESIS_PROVIDER_ATTEMPT_LIMIT
    return MODEL_INVOCATION_PROVIDER_ATTEMPT_LIMIT


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
    generation: int | None = Field(default=None, ge=2, le=2)
    field: Identifier | None = None
    delta: Annotated[str, StringConstraints(min_length=1, max_length=4096)] | None = None
    frame_id: Identifier | None = None
    frame_sequence: int | None = Field(default=None, ge=1, le=128)
    frame_type: Identifier | None = None
    public_header: dict[str, Any] | None = None
    delta_index: int | None = Field(default=None, ge=0)
    public_text: Annotated[
        str,
        StringConstraints(max_length=100_000),
    ] | None = None
    durable_cursor: Annotated[
        str,
        StringConstraints(
            min_length=1,
            max_length=256,
            pattern=r"^v3:[A-Za-z0-9][A-Za-z0-9._:-]{0,127}:(?:FRAME:[1-9][0-9]{0,2}|INTERRUPTED:[1-9][0-9]{0,2}|FINAL|ERROR)$",
        ),
    ] | None = None
    header_sha256: Sha256 | None = None
    public_text_sha256: Sha256 | None = None
    frame_sha256: Sha256 | None = None
    public_text_chars: int | None = Field(default=None, ge=0, le=100_000)
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
    "generation_reset",
    "public_frame_start",
    "public_text_delta",
    "active_frame_snapshot",
    "public_frame_committed",
    "public_frame_interrupted",
    "usage",
    "attempt_aborted",
    "attempt_reset",
    "final",
    "error",
]


AGENT_STREAM_PAYLOAD_FIELDS: Final[Mapping[str, frozenset[str]]] = MappingProxyType(
    {
        "attempt_started": frozenset({"node"}),
        "visible_delta": frozenset({"node", "field", "delta"}),
        "generation_reset": frozenset({"node", "generation", "reason_code"}),
        "public_frame_start": frozenset(
            {"frame_id", "frame_sequence", "frame_type", "public_header"}
        ),
        "public_text_delta": frozenset(
            {"frame_id", "frame_sequence", "delta_index", "delta"}
        ),
        "active_frame_snapshot": frozenset(
            {"frame_id", "frame_sequence", "delta_index", "public_text"}
        ),
        "public_frame_committed": frozenset(
            {
                "frame_id",
                "frame_sequence",
                "durable_cursor",
                "header_sha256",
                "public_text_sha256",
                "frame_sha256",
                "public_text_chars",
            }
        ),
        "public_frame_interrupted": frozenset(
            {
                "frame_id",
                "frame_sequence",
                "durable_cursor",
                "reason_code",
                "public_text",
            }
        ),
        "usage": frozenset({"usage"}),
        "attempt_aborted": frozenset({"reason_code"}),
        "attempt_reset": frozenset({"reset_attempt_id", "reason_code"}),
        "final": frozenset({"final_result_ref", "final_result_hash"}),
        "error": frozenset({"error_code", "retryable"}),
    }
)


class AgentStreamEvent(StrictContractModel):
    schema_version: Literal["agent-stream.v3"]
    run_id: Identifier
    attempt_id: Identifier
    sequence_no: int = Field(ge=0)
    event_type: StreamEventType
    audience: Audience
    occurred_at: AwareDatetime
    payload: AgentStreamPayload

    @model_validator(mode="after")
    def validate_event_payload(self) -> AgentStreamEvent:
        required = AGENT_STREAM_PAYLOAD_FIELDS[self.event_type]
        present = set(self.payload.model_dump(exclude_none=True))
        missing = required - present
        if missing:
            raise ValueError(f"{self.event_type} payload missing {sorted(missing)}")
        unexpected = present - required
        if unexpected:
            raise ValueError(
                f"{self.event_type} payload contains incompatible fields "
                f"{sorted(unexpected)}"
            )
        if (
            self.event_type == "generation_reset"
            and self.payload.reason_code != "OUTPUT_SCHEMA_INVALID"
        ):
            raise ValueError("generation_reset reason_code is invalid")
        return self


ParallelFrameType = Literal[
    "DIALOGUE_FRAME",
    "DOSSIER_FRAME",
    "QUALITY_FRAME",
]
ParallelFrameDeliveryClass = Literal[
    "DURABLE_CONTROL",
    "DURABLE_PREVIEW",
    "DURABLE_STAGING",
    "DURABLE_TERMINAL",
]
ParallelFrameValueKind = Literal["TEXT", "JSON_VALUE"]
ParallelStreamEventType = Literal[
    "public_frame_start",
    "public_frame_projection_item",
    "active_frame_snapshot",
    "frame_generation_reset",
    "public_frame_sealed",
    "public_frame_interrupted",
    "usage",
    "final",
    "error",
]


class AgentStreamPayloadV4(StrictContractModel):
    frame_id: Identifier | None = None
    frame_type: ParallelFrameType | None = None
    generation: int | None = Field(default=None, ge=1, le=16)
    frame_set_receipt_id: Identifier | None = None
    projection_registry_version: Identifier | None = None
    delivery_class: ParallelFrameDeliveryClass | None = None
    local_index: int | None = Field(default=None, ge=0, le=255)
    next_local_index: int | None = Field(default=None, ge=0, le=256)
    canonical_item_id: Identifier | None = None
    projection_kind: Identifier | None = None
    projection_path_id: Identifier | None = None
    value_kind: ParallelFrameValueKind | None = None
    canonical_value_json: Annotated[
        str,
        StringConstraints(min_length=1, max_length=8192),
    ] | None = None
    public_text: Annotated[
        str,
        StringConstraints(min_length=1, max_length=8192),
    ] | None = None
    item_sha256: Sha256 | None = None
    frame_revision: int | None = Field(default=None, ge=1, le=1024)
    projection_sha256: Sha256 | None = None
    old_frame_id: Identifier | None = None
    new_frame_id: Identifier | None = None
    old_generation: int | None = Field(default=None, ge=1, le=16)
    new_generation: int | None = Field(default=None, ge=1, le=16)
    reason_code: Identifier | None = None
    frame_receipt_id: Identifier | None = None
    result_sha256: Sha256 | None = None
    public_projection_sha256: Sha256 | None = None
    retryable: bool | None = None
    usage: Usage | None = None
    final_receipt_id: Identifier | None = None
    final_result_hash: Sha256 | None = None
    error_code: Identifier | None = None


AGENT_STREAM_V4_PAYLOAD_FIELDS: Final[Mapping[str, frozenset[str]]] = (
    MappingProxyType(
        {
            "public_frame_start": frozenset(
                {
                    "frame_id",
                    "frame_type",
                    "generation",
                    "frame_set_receipt_id",
                    "projection_registry_version",
                    "delivery_class",
                }
            ),
            "public_frame_projection_item": frozenset(
                {
                    "frame_id",
                    "frame_type",
                    "generation",
                    "local_index",
                    "next_local_index",
                    "canonical_item_id",
                    "projection_kind",
                    "projection_path_id",
                    "value_kind",
                    "item_sha256",
                    "delivery_class",
                }
            ),
            "active_frame_snapshot": frozenset(
                {
                    "frame_id",
                    "frame_type",
                    "generation",
                    "frame_revision",
                    "next_local_index",
                    "projection_sha256",
                    "delivery_class",
                }
            ),
            "frame_generation_reset": frozenset(
                {
                    "old_frame_id",
                    "new_frame_id",
                    "frame_type",
                    "old_generation",
                    "new_generation",
                    "reason_code",
                    "delivery_class",
                }
            ),
            "public_frame_sealed": frozenset(
                {
                    "frame_id",
                    "frame_type",
                    "generation",
                    "frame_receipt_id",
                    "next_local_index",
                    "result_sha256",
                    "public_projection_sha256",
                    "delivery_class",
                }
            ),
            "public_frame_interrupted": frozenset(
                {
                    "frame_id",
                    "frame_type",
                    "generation",
                    "next_local_index",
                    "reason_code",
                    "retryable",
                    "delivery_class",
                }
            ),
            "usage": frozenset(
                {"frame_type", "generation", "usage", "delivery_class"}
            ),
            "final": frozenset(
                {"final_receipt_id", "final_result_hash", "delivery_class"}
            ),
            "error": frozenset(
                {"error_code", "retryable", "delivery_class"}
            ),
        }
    )
)


AGENT_STREAM_V4_DELIVERY_CLASS: Final[Mapping[str, str]] = MappingProxyType(
    {
        "public_frame_start": "DURABLE_CONTROL",
        "public_frame_projection_item": "DURABLE_PREVIEW",
        "active_frame_snapshot": "DURABLE_PREVIEW",
        "frame_generation_reset": "DURABLE_CONTROL",
        "public_frame_sealed": "DURABLE_STAGING",
        "public_frame_interrupted": "DURABLE_CONTROL",
        "usage": "DURABLE_STAGING",
        "final": "DURABLE_TERMINAL",
        "error": "DURABLE_TERMINAL",
    }
)


class AgentStreamEventV4(StrictContractModel):
    schema_version: Literal["agent-stream.v4"]
    run_id: Identifier
    attempt_id: Identifier
    sequence_no: int = Field(ge=0)
    event_type: ParallelStreamEventType
    audience: Audience
    occurred_at: AwareDatetime
    payload: AgentStreamPayloadV4

    @model_validator(mode="after")
    def validate_event_payload(self) -> AgentStreamEventV4:
        required = set(AGENT_STREAM_V4_PAYLOAD_FIELDS[self.event_type])
        if self.event_type == "public_frame_projection_item":
            if self.payload.value_kind == "TEXT":
                required.add("public_text")
            elif self.payload.value_kind == "JSON_VALUE":
                required.add("canonical_value_json")

        present = set(self.payload.model_dump(exclude_none=True))
        missing = required - present
        if missing:
            raise ValueError(f"{self.event_type} payload missing {sorted(missing)}")
        unexpected = present - required
        if unexpected:
            raise ValueError(
                f"{self.event_type} payload contains incompatible fields "
                f"{sorted(unexpected)}"
            )

        expected_delivery = AGENT_STREAM_V4_DELIVERY_CLASS[self.event_type]
        if self.payload.delivery_class != expected_delivery:
            raise ValueError(
                f"{self.event_type} delivery_class must be {expected_delivery}"
            )
        if (
            self.event_type == "public_frame_projection_item"
            and self.payload.next_local_index != self.payload.local_index + 1
        ):
            raise ValueError("next_local_index must equal local_index + 1")
        if (
            self.event_type == "frame_generation_reset"
            and self.payload.new_generation != self.payload.old_generation + 1
        ):
            raise ValueError("new_generation must equal old_generation + 1")
        if (
            self.event_type == "usage"
            and self.payload.usage.total_tokens
            != self.payload.usage.input_tokens + self.payload.usage.output_tokens
        ):
            raise ValueError("usage total_tokens must equal input_tokens + output_tokens")
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
    provider_attempts_remaining: int = Field(
        ge=0,
        le=HEARING_EVIDENCE_SYNTHESIS_PROVIDER_ATTEMPT_LIMIT,
    )
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

    @model_validator(mode="after")
    def bind_aggregate_provider_budget_to_stage(self) -> "RoomGraphCommand":
        if self.retry_budget.provider_attempts_remaining > command_provider_attempt_limit(
            self.room_type,
            self.stage_code,
        ):
            raise ValueError(
                "aggregate provider budget is reserved for Hearing evidence synthesis"
            )
        return self


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
    "agent-stream-event-v4.schema.json": AgentStreamEventV4,
    "agent-execution-manifest.schema.json": AgentExecutionManifest,
}
