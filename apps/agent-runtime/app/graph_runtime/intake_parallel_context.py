"""Deterministic projection of frozen Intake ingress into the three-Frame model view."""

from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
from typing import Any, Mapping, Sequence, cast

from app.contracts.v1.codec import canonical_sha256
from app.contracts.v1.models import RoomGraphCommand
from app.graph_runtime.errors import GraphContractError
from app.graph_runtime.gateway import GatewayExecution
from app.graph_runtime.identity import ThreadIdentity
from app.graph_runtime.intake_parallel_runtime import require_parallel_intake_execution
from app.graphs.intake.contracts import IntakeDomainSnapshot, IntakeTurnEvent, PartyIntakeState
from app.graphs.intake.parallel_contracts import (
    IntakeAuthorityRefV1,
    IntakeCaseRefV1,
    IntakeFrameInstructionPackV1,
    IntakeFrameModelInputV2,
    IntakeModelContextViewV1,
    IntakeParallelContextEnvelopeV1,
    IntakeSourceEventRefV1,
    LitigationCapacity,
    PartyRole,
    build_frame_model_inputs,
    build_parallel_context_envelope,
)
from app.graphs.intake.state import IntakeTurnContext


_DOSSIER_PROJECTION_FIELDS = (
    "case_story",
    "references",
    "party_positions",
    "dispute_focus",
    "requested_resolution",
    "claim_resolution",
    "respondent_attitude",
    "dispute_core_state",
    "risk_assessment",
)
_MATRIX_PROJECTION_FIELDS = (
    "matrix_version",
    "matrix_kind",
    "party_map",
    "case_overview",
    "claims",
    "fact_rows",
    "fact_relationships",
    "fact_indexes",
)
_SERVER_ONLY_KEYS = frozenset(
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


@dataclass(frozen=True, slots=True)
class ParallelTurnModelMaterial:
    context_envelope: IntakeParallelContextEnvelopeV1
    model_context: IntakeModelContextViewV1
    frame_inputs: tuple[
        IntakeFrameModelInputV2,
        IntakeFrameModelInputV2,
        IntakeFrameModelInputV2,
    ]


def build_parallel_turn_model_material(
    execution: GatewayExecution,
    *,
    snapshot_context: IntakeTurnContext,
    event_context: IntakeTurnContext,
    instruction_packs: Sequence[IntakeFrameInstructionPackV1],
) -> ParallelTurnModelMaterial:
    """Build one self-hashed common view from exact decoded snapshot/event authority."""

    require_parallel_intake_execution(execution)
    return build_parallel_turn_model_material_from_command(
        execution.admission.command,
        thread=execution.admission.thread,
        room_fencing_token=execution.fence.room_fencing_token,
        snapshot_context=snapshot_context,
        event_context=event_context,
        instruction_packs=instruction_packs,
    )


def build_parallel_turn_model_material_from_command(
    command: RoomGraphCommand,
    *,
    thread: ThreadIdentity,
    room_fencing_token: int,
    snapshot_context: IntakeTurnContext,
    event_context: IntakeTurnContext,
    instruction_packs: Sequence[IntakeFrameInstructionPackV1],
) -> ParallelTurnModelMaterial:
    if (
        isinstance(room_fencing_token, bool)
        or not isinstance(room_fencing_token, int)
        or room_fencing_token < 1
    ):
        raise GraphContractError("parallel Intake room fencing authority is invalid")
    if snapshot_context.ingress_kind != "SNAPSHOT" or event_context.ingress_kind != "EVENT":
        raise GraphContractError("parallel Intake requires exact snapshot and event ingress")
    try:
        snapshot = IntakeDomainSnapshot.model_validate(snapshot_context.ingress_payload)
        event = IntakeTurnEvent.model_validate(event_context.ingress_payload)
    except ValueError as error:
        raise GraphContractError("parallel Intake ingress schema is invalid") from error

    room_id = command.room_id
    if room_id is None:
        raise GraphContractError("parallel Intake room authority is absent")
    identity = thread
    snapshot_ref = command.domain_snapshot_ref
    event_ref = command.event_ref
    if event_ref is None:
        raise GraphContractError("parallel Intake event reference is absent")
    expected_common = (
        command.tenant_surrogate,
        command.case_id,
        command.room_type,
        command.room_epoch,
        command.thread_id,
        identity.actor_scope_hash,
        identity.agent_session_id,
    )
    if (
        (
            snapshot.tenant_surrogate,
            snapshot.case_id,
            snapshot.room_type,
            snapshot.room_epoch,
            snapshot.thread_id,
            snapshot.actor_scope_hash,
            snapshot.agent_session_id,
        )
        != expected_common
        or (
            event.tenant_surrogate,
            event.case_id,
            event.room_type,
            event.room_epoch,
            event.thread_id,
            event.actor_scope_hash,
            event.agent_session_id,
        )
        != expected_common
        or snapshot.snapshot_hash != snapshot_ref.sha256
        or event.event_hash != event_ref.sha256
        or snapshot_ref.schema_version != "intake-domain-snapshot.v2"
        or event_ref.schema_version != "intake-turn-event.v2"
        or event.source_type != "ROOM_MESSAGE"
        or event.audience != command.actor_scope.audience
    ):
        raise GraphContractError("parallel Intake ingress differs from command authority")

    dossier = snapshot.current_dossier
    try:
        party_state = PartyIntakeState.model_validate(dossier.get("party_intake_state"))
    except ValueError as error:
        raise GraphContractError("parallel Intake party state is invalid") from error
    matrix = dossier.get("case_fact_matrix")
    if not isinstance(matrix, dict):
        raise GraphContractError("parallel Intake frozen matrix is absent")
    party_map = matrix.get("party_map")
    if not isinstance(party_map, dict) or set(party_map) != {
        "initiator_role",
        "respondent_role",
    }:
        raise GraphContractError("parallel Intake party map is invalid")
    initiator = _party_role(party_map.get("initiator_role"), "initiator")
    respondent = _party_role(party_map.get("respondent_role"), "respondent")
    if initiator == respondent:
        raise GraphContractError("parallel Intake party map aliases both capacities")
    actor_role = cast(PartyRole, command.actor_scope.actor_role)
    if actor_role not in {initiator, respondent}:
        raise GraphContractError("parallel Intake actor has no litigation capacity")
    capacity: LitigationCapacity = (
        "INITIATOR" if actor_role == initiator else "RESPONDENT"
    )
    actor_entry = party_state.USER if actor_role == "USER" else party_state.MERCHANT
    quality = deepcopy(actor_entry["intake_quality"])
    missing = actor_entry["missing_information"]
    handoff = actor_entry["handoff_notes"]
    persisted_phase = handoff["remark_status"]
    action = _action_for_phase(persisted_phase)

    previous_state = {
        "revision": snapshot.domain_revision,
        "persisted_phase": persisted_phase,
        "quality": quality,
        "dossier_projection": _dossier_projection(dossier),
    }
    questions = _authorized_questions(
        missing.get("next_questions"),
        capacity=capacity,
    )
    matrix_projection = _matrix_projection(matrix)
    formal_fact_keys = _formal_fact_keys(matrix_projection)
    model_context = IntakeModelContextViewV1.seal(
        {
            "contract_version": "intake.model-context-view.v1",
            "turn_route": {
                "source_type": "ROOM_MESSAGE",
                "execution_profile": "PARALLEL_FRAMES",
            },
            "source_capacity": {
                "business_role": actor_role,
                "litigation_capacity": capacity,
                "writable_partition": (
                    "INITIATOR_ONLY" if capacity == "INITIATOR" else "RESPONDENT_ONLY"
                ),
            },
            "previous_state": previous_state,
            "current_action_binding": {
                "action": action,
                "derived_from_phase": persisted_phase,
                "phase_source_sha256": canonical_sha256(previous_state),
            },
            "authorized_question_slots": questions,
            "frozen_case_matrix": {
                "version": _matrix_version(matrix),
                "sha256": canonical_sha256(matrix_projection),
                "payload": matrix_projection,
            },
            "fact_key_authority": {
                "existing_fact_keys": formal_fact_keys,
                "new_fact_key_prefix": _new_fact_key_prefix(event.event_hash),
            },
            "recent_dialogue_messages": _party_messages(
                snapshot,
                initiator=initiator,
            ),
            "current_user_message": {
                "source_sequence": event.sequence_no,
                "source_role": actor_role,
                "source_capacity": capacity,
                "text": event.text,
                "text_sha256": canonical_sha256(event.text),
            },
        }
    )
    envelope = build_parallel_context_envelope(
        case_ref=IntakeCaseRefV1.model_validate(
            {
                "tenant_id": command.tenant_surrogate,
                "case_id": command.case_id,
                "thread_id": command.thread_id,
                "room_id": room_id,
                "room_epoch": command.room_epoch,
                "fence_token": str(room_fencing_token),
            }
        ),
        source_event=IntakeSourceEventRefV1.model_validate(
            {
                "message_id": event.message_id,
                "logical_sequence": event.sequence_no,
                "actor_id": command.actor_scope.actor_id,
                "actor_role": actor_role,
                "payload_sha256": event.event_hash,
            }
        ),
        authority=IntakeAuthorityRefV1.model_validate(
            {
                "initiator_role": initiator,
                "respondent_role": respondent,
                "authority_snapshot_ref": snapshot_ref.uri,
                "authority_snapshot_sha256": snapshot_ref.sha256,
            }
        ),
        previous_state_ref=f"{snapshot_ref.uri}#parallel-previous-state",
        previous_state_sha256=canonical_sha256(previous_state),
        model_context_view=model_context,
    )
    return ParallelTurnModelMaterial(
        context_envelope=envelope,
        model_context=model_context,
        frame_inputs=build_frame_model_inputs(
            context_envelope=envelope,
            common_model_context=model_context,
            instruction_packs=instruction_packs,
        ),
    )


def _party_role(value: Any, field: str) -> PartyRole:
    if value not in {"USER", "MERCHANT"}:
        raise GraphContractError(f"parallel Intake {field} role is invalid")
    return cast(PartyRole, value)


def _action_for_phase(value: Any) -> str:
    actions = {
        "NOT_READY": "ASK_SUBSTANTIVE",
        "READY_PENDING_REMARK_INVITE": "INVITE_OPTIONAL_REMARK",
        "WAITING_FOR_REMARK": "ACK_REMARK",
        "HAS_REMARKS": "ACK_REMARK",
        "NO_EXTRA_REMARKS": "ACK_NO_REMARK",
    }
    try:
        return actions[value]
    except (KeyError, TypeError) as error:
        raise GraphContractError("parallel Intake persisted phase is invalid") from error


def _authorized_questions(
    value: Any,
    *,
    capacity: LitigationCapacity,
) -> list[dict[str, Any]]:
    if not isinstance(value, list) or len(value) > 8:
        raise GraphContractError("parallel Intake question authority is invalid")
    result: list[dict[str, Any]] = []
    seen: set[str] = set()
    for question in value:
        if not isinstance(question, str) or not question.strip() or question != question.strip():
            raise GraphContractError("parallel Intake question authority is invalid")
        if question in seen:
            continue
        seen.add(question)
        question_hash = canonical_sha256(
            {"capacity": capacity, "canonical_text": question}
        )
        result.append(
            {
                "question_id": f"Q_{question_hash[:24]}",
                "target_capacity": capacity,
                "source": "PREVIOUS_PERSISTED_STATE",
                "canonical_text": question,
                "canonical_text_sha256": canonical_sha256(question),
            }
        )
    return result


def _matrix_version(matrix: Mapping[str, Any]) -> int:
    value = matrix.get("matrix_version")
    if isinstance(value, bool) or not isinstance(value, int) or value < 1:
        raise GraphContractError("parallel Intake matrix version is invalid")
    return value


def _matrix_projection(matrix: Mapping[str, Any]) -> dict[str, Any]:
    projected = {
        field: deepcopy(matrix[field])
        for field in _MATRIX_PROJECTION_FIELDS
        if field in matrix
    }
    required = {"matrix_version", "party_map", "fact_rows"}
    if not required <= set(projected):
        raise GraphContractError("parallel Intake matrix projection is incomplete")
    return cast(dict[str, Any], _provider_safe(projected))


def _formal_fact_keys(matrix: Mapping[str, Any]) -> tuple[str, ...]:
    rows = matrix.get("fact_rows")
    if not isinstance(rows, list) or len(rows) > 200:
        raise GraphContractError("parallel Intake frozen fact rows are invalid")
    keys: list[str] = []
    for row in rows:
        if not isinstance(row, Mapping):
            raise GraphContractError("parallel Intake frozen fact row is invalid")
        fact_id = row.get("fact_id")
        if (
            not isinstance(fact_id, str)
            or not fact_id.startswith("FACT_")
            or len(fact_id) > 128
        ):
            raise GraphContractError("parallel Intake frozen fact id is invalid")
        keys.append(fact_id)
    if len(keys) != len(set(keys)):
        raise GraphContractError("parallel Intake frozen fact ids are not unique")
    return tuple(keys)


def _new_fact_key_prefix(source_event_sha256: str) -> str:
    if len(source_event_sha256) != 64 or any(
        character not in "0123456789abcdef" for character in source_event_sha256
    ):
        raise GraphContractError("parallel Intake source event hash is invalid")
    return f"NEW_{source_event_sha256[:24].upper()}_"


def _dossier_projection(dossier: Mapping[str, Any]) -> dict[str, Any]:
    projected = {
        field: deepcopy(dossier[field])
        for field in _DOSSIER_PROJECTION_FIELDS
        if field in dossier
    }
    return cast(dict[str, Any], _provider_safe(projected))


def _provider_safe(value: Any) -> Any:
    if isinstance(value, Mapping):
        result: dict[str, Any] = {}
        for key, child in value.items():
            normalized = str(key).strip().lower().replace("-", "_")
            if normalized in _SERVER_ONLY_KEYS or normalized.startswith(
                ("server_only_", "private_authority_", "internal_authority_")
            ):
                continue
            result[str(key)] = _provider_safe(child)
        return result
    if isinstance(value, list):
        return [_provider_safe(child) for child in value]
    return deepcopy(value)


def _party_messages(
    snapshot: IntakeDomainSnapshot,
    *,
    initiator: PartyRole,
) -> list[dict[str, Any]]:
    messages: list[dict[str, Any]] = []
    for message in snapshot.own_messages:
        if message.role != "HUMAN" or not message.text:
            continue
        role = cast(PartyRole, message.audience)
        capacity: LitigationCapacity = (
            "INITIATOR" if role == initiator else "RESPONDENT"
        )
        messages.append(
            {
                "sequence": message.sequence,
                "speaker_role": role,
                "speaker_capacity": capacity,
                "text": message.text,
                "source_sha256": canonical_sha256(message.text),
            }
        )
    return messages


__all__ = [
    "ParallelTurnModelMaterial",
    "build_parallel_turn_model_material",
]
