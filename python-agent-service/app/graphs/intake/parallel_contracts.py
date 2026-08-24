from __future__ import annotations

from collections.abc import Mapping, Sequence
from copy import deepcopy
from typing import Annotated, Any, ClassVar, Literal, Self

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

from app.contracts.v1.codec import canonical_sha256


Identifier = Annotated[
    str,
    StringConstraints(
        min_length=1,
        max_length=128,
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$",
    ),
]
Sha256 = Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{64}$")]
PromptProfileId = Annotated[
    str,
    StringConstraints(
        min_length=1,
        max_length=128,
        pattern=r"^[a-z0-9][a-z0-9._:-]{0,127}$",
    ),
]

PartyRole = Literal["USER", "MERCHANT"]
LitigationCapacity = Literal["INITIATOR", "RESPONDENT"]
WritablePartition = Literal["INITIATOR_ONLY", "RESPONDENT_ONLY"]
ParallelFrameType = Literal[
    "DIALOGUE_FRAME",
    "DOSSIER_FRAME",
    "QUALITY_FRAME",
]
IntakeTurnSourceType = Literal[
    "INITIAL_FORM",
    "ROOM_MESSAGE",
    "FORMAL_EVENT",
    "RESPONDENT_OPENING",
]
IntakeTurnExecutionProfile = Literal[
    "OPENING_DIALOGUE_ONLY",
    "PARALLEL_FRAMES",
    "DETERMINISTIC_TRANSITION",
]
PersistedIntakePhase = Literal[
    "NOT_READY",
    "READY_PENDING_REMARK_INVITE",
    "WAITING_FOR_REMARK",
    "HAS_REMARKS",
    "NO_EXTRA_REMARKS",
]
ConversationAction = Literal[
    "ASK_SUBSTANTIVE",
    "INVITE_OPTIONAL_REMARK",
    "ACK_REMARK",
    "ACK_NO_REMARK",
]


FRAME_TYPES: tuple[ParallelFrameType, ...] = (
    "DIALOGUE_FRAME",
    "DOSSIER_FRAME",
    "QUALITY_FRAME",
)

FRAME_PROMPT_PROFILE: Mapping[ParallelFrameType, PromptProfileId] = {
    "DIALOGUE_FRAME": "intake_turn_dialogue_frame",
    "DOSSIER_FRAME": "intake_turn_dossier_frame",
    "QUALITY_FRAME": "intake_turn_quality_frame",
}

FRAME_OUTPUT_SCHEMA: Mapping[ParallelFrameType, Identifier] = {
    "DIALOGUE_FRAME": "intake-dialogue-frame.v1",
    "DOSSIER_FRAME": "intake-dossier-frame.v1",
    "QUALITY_FRAME": "intake-quality-frame.v1",
}

FRAME_ALLOWED_OUTPUT_FIELDS: Mapping[ParallelFrameType, tuple[Identifier, ...]] = {
    "DIALOGUE_FRAME": ("public_projection_items", "dialogue"),
    "DOSSIER_FRAME": ("public_projection_items", "dossier_delta"),
    "QUALITY_FRAME": ("public_projection_items", "quality"),
}

FRAME_FORBIDDEN_OUTPUT_FIELDS: Mapping[ParallelFrameType, tuple[Identifier, ...]] = {
    "DIALOGUE_FRAME": ("dossier_delta", "quality"),
    "DOSSIER_FRAME": ("dialogue", "quality"),
    "QUALITY_FRAME": ("dialogue", "dossier_delta"),
}

_SERVER_ONLY_PROVIDER_KEYS = frozenset(
    {
        "tenant_id",
        "tenant_surrogate",
        "case_id",
        "actor_id",
        "thread_id",
        "room_id",
        "room_epoch",
        "fence_token",
        "fencing_token",
        "message_id",
        "event_id",
        "logical_sequence",
        "command_id",
        "logical_run_id",
        "attempt_id",
        "agent_run_id",
        "agent_run_attempt_id",
        "registration_id",
        "event_binding_id",
        "binding_generation",
        "authority_version",
        "actor_scope_hash",
        "agent_session_id",
        "stream_session_id",
        "authority_snapshot_ref",
        "authority_snapshot_sha256",
        "previous_state_ref",
        "previous_state_sha256",
        "checkpoint",
        "checkpoint_ref",
        "context_envelope_sha256",
        "credentials",
        "credential",
        "api_key",
        "access_token",
        "refresh_token",
        "authorization_header",
        "private_key",
        "client_secret",
        "hidden_reasoning",
        "chain_of_thought",
    }
)

_SERVER_ONLY_PROVIDER_PREFIXES = (
    "server_only_",
    "private_authority_",
    "internal_authority_",
)


class StrictParallelModel(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class _SelfHashedParallelModel(StrictParallelModel):
    _hash_field: ClassVar[str]

    @classmethod
    def seal(cls, value: Mapping[str, Any]) -> Self:
        payload = _json_compatible(deepcopy(dict(value)))
        if cls._hash_field in payload:
            raise ValueError(f"{cls._hash_field} must be computed by the service")
        payload[cls._hash_field] = canonical_sha256(payload)
        return cls.model_validate(payload)

    @model_validator(mode="after")
    def validate_self_hash(self) -> Self:
        payload = self.model_dump(mode="json")
        supplied = payload.pop(self._hash_field)
        if canonical_sha256(payload) != supplied:
            raise ValueError(f"{self._hash_field} does not bind canonical content")
        return self


class IntakeCaseRefV1(StrictParallelModel):
    tenant_id: Identifier
    case_id: Identifier
    thread_id: Identifier
    room_id: Identifier
    room_epoch: int = Field(ge=0)
    fence_token: Identifier


class IntakeSourceEventRefV1(StrictParallelModel):
    message_id: Identifier
    logical_sequence: int = Field(ge=1)
    actor_id: Identifier
    actor_role: PartyRole
    payload_sha256: Sha256


class IntakeAuthorityRefV1(StrictParallelModel):
    initiator_role: PartyRole
    respondent_role: PartyRole
    authority_snapshot_ref: Annotated[
        str,
        StringConstraints(min_length=1, max_length=1024),
    ]
    authority_snapshot_sha256: Sha256

    @model_validator(mode="after")
    def validate_distinct_roles(self) -> IntakeAuthorityRefV1:
        if self.initiator_role == self.respondent_role:
            raise ValueError("initiator_role and respondent_role must differ")
        return self


class IntakeParallelContextEnvelopeV1(_SelfHashedParallelModel):
    _hash_field = "context_envelope_sha256"

    contract_version: Literal["intake.parallel-context-envelope.v1"]
    case_ref: IntakeCaseRefV1
    source_event: IntakeSourceEventRefV1
    authority: IntakeAuthorityRefV1
    previous_state_ref: Annotated[
        str,
        StringConstraints(min_length=1, max_length=1024),
    ]
    previous_state_sha256: Sha256
    model_context_view_sha256: Sha256
    context_envelope_sha256: Sha256


class IntakeSourceCapacityV1(StrictParallelModel):
    business_role: PartyRole
    litigation_capacity: LitigationCapacity
    writable_partition: WritablePartition

    @model_validator(mode="after")
    def validate_partition(self) -> IntakeSourceCapacityV1:
        expected = (
            "INITIATOR_ONLY"
            if self.litigation_capacity == "INITIATOR"
            else "RESPONDENT_ONLY"
        )
        if self.writable_partition != expected:
            raise ValueError("writable_partition does not match litigation_capacity")
        return self


class IntakePreviousStateViewV1(StrictParallelModel):
    revision: int = Field(ge=0)
    persisted_phase: PersistedIntakePhase
    quality: dict[str, Any]
    dossier_projection: dict[str, Any]


class IntakeTurnRouteV1(StrictParallelModel):
    source_type: IntakeTurnSourceType
    execution_profile: IntakeTurnExecutionProfile

    @model_validator(mode="after")
    def validate_authoritative_route(self) -> IntakeTurnRouteV1:
        expected = {
            "INITIAL_FORM": "OPENING_DIALOGUE_ONLY",
            "RESPONDENT_OPENING": "OPENING_DIALOGUE_ONLY",
            "ROOM_MESSAGE": "PARALLEL_FRAMES",
            "FORMAL_EVENT": "DETERMINISTIC_TRANSITION",
        }[self.source_type]
        if self.execution_profile != expected:
            raise ValueError("execution_profile does not match authoritative source_type")
        return self


class IntakeActionBindingV1(StrictParallelModel):
    action: ConversationAction
    derived_from_phase: PersistedIntakePhase
    phase_source_sha256: Sha256

    @model_validator(mode="after")
    def validate_phase_action(self) -> IntakeActionBindingV1:
        if (
            self.derived_from_phase == "NOT_READY"
            and self.action != "ASK_SUBSTANTIVE"
        ):
            raise ValueError("NOT_READY must bind ASK_SUBSTANTIVE")
        if (
            self.derived_from_phase == "READY_PENDING_REMARK_INVITE"
            and self.action != "INVITE_OPTIONAL_REMARK"
        ):
            raise ValueError(
                "READY_PENDING_REMARK_INVITE must bind INVITE_OPTIONAL_REMARK"
            )
        if self.derived_from_phase == "WAITING_FOR_REMARK" and self.action not in {
            "ACK_REMARK",
            "ACK_NO_REMARK",
        }:
            raise ValueError("WAITING_FOR_REMARK must bind a remark acknowledgement")
        return self


class IntakeAuthorizedQuestionSlotV1(StrictParallelModel):
    question_id: Identifier
    target_capacity: LitigationCapacity
    source: Literal["PREVIOUS_PERSISTED_STATE"]
    canonical_text: Annotated[
        str,
        StringConstraints(min_length=1, max_length=1000),
    ]
    canonical_text_sha256: Sha256

    @model_validator(mode="after")
    def validate_text_hash(self) -> IntakeAuthorizedQuestionSlotV1:
        if canonical_sha256(self.canonical_text) != self.canonical_text_sha256:
            raise ValueError("canonical_text_sha256 does not bind canonical_text")
        return self


class IntakeFrozenMatrixViewV1(StrictParallelModel):
    version: int = Field(ge=0)
    sha256: Sha256
    payload: dict[str, Any]

    @model_validator(mode="after")
    def validate_payload_hash(self) -> IntakeFrozenMatrixViewV1:
        if canonical_sha256(self.payload) != self.sha256:
            raise ValueError("frozen matrix sha256 does not bind payload")
        return self


class IntakeDialogueMessageViewV1(StrictParallelModel):
    sequence: int = Field(ge=0)
    speaker_role: PartyRole
    speaker_capacity: LitigationCapacity
    text: Annotated[str, StringConstraints(min_length=1, max_length=8192)]
    source_sha256: Sha256

    @model_validator(mode="after")
    def validate_source_hash(self) -> IntakeDialogueMessageViewV1:
        if canonical_sha256(self.text) != self.source_sha256:
            raise ValueError("dialogue source_sha256 does not bind text")
        return self


class IntakeCurrentMessageViewV1(StrictParallelModel):
    source_sequence: int = Field(ge=1)
    source_role: PartyRole
    source_capacity: LitigationCapacity
    text: Annotated[str, StringConstraints(min_length=1, max_length=8192)]
    text_sha256: Sha256

    @model_validator(mode="after")
    def validate_payload_hash(self) -> IntakeCurrentMessageViewV1:
        if canonical_sha256(self.text) != self.text_sha256:
            raise ValueError("current message text_sha256 does not bind text")
        return self


class IntakeModelContextViewV1(_SelfHashedParallelModel):
    _hash_field = "model_context_view_sha256"

    contract_version: Literal["intake.model-context-view.v1"]
    turn_route: IntakeTurnRouteV1
    source_capacity: IntakeSourceCapacityV1
    previous_state: IntakePreviousStateViewV1
    current_action_binding: IntakeActionBindingV1
    authorized_question_slots: tuple[IntakeAuthorizedQuestionSlotV1, ...] = Field(
        max_length=8
    )
    frozen_case_matrix: IntakeFrozenMatrixViewV1
    recent_dialogue_messages: tuple[IntakeDialogueMessageViewV1, ...] = Field(
        max_length=6
    )
    current_user_message: IntakeCurrentMessageViewV1
    model_context_view_sha256: Sha256

    @model_validator(mode="after")
    def validate_provider_boundary(self) -> IntakeModelContextViewV1:
        _reject_provider_forbidden_keys(self.model_dump(mode="json"))
        if self.turn_route.execution_profile != "PARALLEL_FRAMES":
            raise ValueError("parallel Frame context only accepts ROOM_MESSAGE turns")
        if (
            self.source_capacity.business_role
            != self.current_user_message.source_role
            or self.source_capacity.litigation_capacity
            != self.current_user_message.source_capacity
        ):
            raise ValueError("current message source does not match source_capacity")
        if (
            self.current_action_binding.derived_from_phase
            != self.previous_state.persisted_phase
        ):
            raise ValueError("current action phase does not match previous persisted phase")
        if self.current_action_binding.phase_source_sha256 != canonical_sha256(
            self.previous_state.model_dump(mode="json")
        ):
            raise ValueError("phase_source_sha256 does not bind previous_state")
        question_ids = [slot.question_id for slot in self.authorized_question_slots]
        if len(question_ids) != len(set(question_ids)):
            raise ValueError("authorized question ids must be unique")
        if any(
            slot.target_capacity != self.source_capacity.litigation_capacity
            for slot in self.authorized_question_slots
        ):
            raise ValueError("authorized question slot targets a foreign capacity")
        return self


def build_parallel_context_envelope(
    *,
    case_ref: IntakeCaseRefV1,
    source_event: IntakeSourceEventRefV1,
    authority: IntakeAuthorityRefV1,
    previous_state_ref: str,
    previous_state_sha256: Sha256,
    model_context_view: IntakeModelContextViewV1,
) -> IntakeParallelContextEnvelopeV1:
    return IntakeParallelContextEnvelopeV1.seal(
        {
            "contract_version": "intake.parallel-context-envelope.v1",
            "case_ref": case_ref.model_dump(mode="json"),
            "source_event": source_event.model_dump(mode="json"),
            "authority": authority.model_dump(mode="json"),
            "previous_state_ref": previous_state_ref,
            "previous_state_sha256": previous_state_sha256,
            "model_context_view_sha256": model_context_view.model_context_view_sha256,
        }
    )


def require_envelope_model_context_binding(
    envelope: IntakeParallelContextEnvelopeV1,
    model_context_view: IntakeModelContextViewV1,
) -> None:
    if (
        envelope.model_context_view_sha256
        != model_context_view.model_context_view_sha256
    ):
        raise ValueError("context envelope does not bind model context view")


class IntakeFrameInstructionPackV1(_SelfHashedParallelModel):
    _hash_field = "instruction_pack_sha256"

    contract_version: Literal["intake.frame-instruction-pack.v1"]
    frame_type: ParallelFrameType
    prompt_profile_id: PromptProfileId
    output_schema_id: Identifier
    common_authority_prompt_sha256: Sha256
    frame_prompt_sha256: Sha256
    allowed_output_fields: tuple[Identifier, ...] = Field(min_length=2, max_length=8)
    forbidden_output_fields: tuple[Identifier, ...] = Field(min_length=2, max_length=8)
    instruction_pack_sha256: Sha256

    @model_validator(mode="after")
    def validate_registry_binding(self) -> IntakeFrameInstructionPackV1:
        if self.prompt_profile_id != FRAME_PROMPT_PROFILE[self.frame_type]:
            raise ValueError("prompt_profile_id does not match frame_type")
        if self.output_schema_id != FRAME_OUTPUT_SCHEMA[self.frame_type]:
            raise ValueError("output_schema_id does not match frame_type")
        if self.allowed_output_fields != FRAME_ALLOWED_OUTPUT_FIELDS[self.frame_type]:
            raise ValueError("allowed_output_fields do not match frame_type")
        if self.forbidden_output_fields != FRAME_FORBIDDEN_OUTPUT_FIELDS[self.frame_type]:
            raise ValueError("forbidden_output_fields do not match frame_type")
        if set(self.allowed_output_fields) & set(self.forbidden_output_fields):
            raise ValueError("allowed and forbidden output fields overlap")
        return self


class IntakeFrameModelInputV1(_SelfHashedParallelModel):
    _hash_field = "frame_model_input_sha256"

    contract_version: Literal["intake.frame-model-input.v1"]
    frame_type: ParallelFrameType
    common_model_context: IntakeModelContextViewV1
    instruction_pack: IntakeFrameInstructionPackV1
    frame_model_input_sha256: Sha256

    @model_validator(mode="after")
    def validate_frame_binding(self) -> IntakeFrameModelInputV1:
        if self.instruction_pack.frame_type != self.frame_type:
            raise ValueError("instruction pack belongs to a different frame")
        return self


def build_instruction_pack(
    *,
    frame_type: ParallelFrameType,
    common_authority_prompt: str,
    frame_prompt: str,
) -> IntakeFrameInstructionPackV1:
    return IntakeFrameInstructionPackV1.seal(
        {
            "contract_version": "intake.frame-instruction-pack.v1",
            "frame_type": frame_type,
            "prompt_profile_id": FRAME_PROMPT_PROFILE[frame_type],
            "output_schema_id": FRAME_OUTPUT_SCHEMA[frame_type],
            "common_authority_prompt_sha256": canonical_sha256(
                common_authority_prompt
            ),
            "frame_prompt_sha256": canonical_sha256(frame_prompt),
            "allowed_output_fields": FRAME_ALLOWED_OUTPUT_FIELDS[frame_type],
            "forbidden_output_fields": FRAME_FORBIDDEN_OUTPUT_FIELDS[frame_type],
        }
    )


def build_frame_model_inputs(
    *,
    context_envelope: IntakeParallelContextEnvelopeV1,
    common_model_context: IntakeModelContextViewV1,
    instruction_packs: Sequence[IntakeFrameInstructionPackV1],
) -> tuple[IntakeFrameModelInputV1, IntakeFrameModelInputV1, IntakeFrameModelInputV1]:
    require_envelope_model_context_binding(context_envelope, common_model_context)
    by_type = {pack.frame_type: pack for pack in instruction_packs}
    if set(by_type) != set(FRAME_TYPES) or len(instruction_packs) != len(FRAME_TYPES):
        raise ValueError("exactly one instruction pack per frame type is required")
    inputs = tuple(
        IntakeFrameModelInputV1.seal(
            {
                "contract_version": "intake.frame-model-input.v1",
                "frame_type": frame_type,
                "common_model_context": common_model_context.model_dump(mode="json"),
                "instruction_pack": by_type[frame_type].model_dump(mode="json"),
            }
        )
        for frame_type in FRAME_TYPES
    )
    return inputs  # type: ignore[return-value]


def _reject_provider_forbidden_keys(value: Any, *, path: str = "$") -> None:
    if isinstance(value, Mapping):
        for key, child in value.items():
            normalized_key = str(key).strip().lower().replace("-", "_")
            if normalized_key in _SERVER_ONLY_PROVIDER_KEYS or normalized_key.startswith(
                _SERVER_ONLY_PROVIDER_PREFIXES
            ):
                raise ValueError(f"provider context contains forbidden key at {path}.{key}")
            _reject_provider_forbidden_keys(child, path=f"{path}.{key}")
    elif isinstance(value, (list, tuple)):
        for index, child in enumerate(value):
            _reject_provider_forbidden_keys(child, path=f"{path}[{index}]")


def _json_compatible(value: Any) -> Any:
    if isinstance(value, BaseModel):
        return value.model_dump(mode="json")
    if isinstance(value, Mapping):
        return {str(key): _json_compatible(child) for key, child in value.items()}
    if isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)):
        return [_json_compatible(child) for child in value]
    return value
