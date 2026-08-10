"""State-to-baseline adapters for the durable Intake graph.

The model-facing contract in this module is deliberately the established Intake
Harness contract.  Target-only proposal fields are derived only after the strict
baseline response has been parsed and validated.
"""

from __future__ import annotations

import json
import re
from collections.abc import Mapping
from copy import deepcopy
from typing import Any, cast

from app.agents.dispute_intake_officer.schemas import IntakeCaseDetailLlmOutput
from app.agents.dispute_intake_officer.workflow import (
    build_intake_turn_context_pack,
    finalize_intake_projected_output,
    project_intake_case_detail_output,
)
from app.contracts.v1.codec import canonical_sha256, canonicalize
from app.graphs.intake.contracts import IntakeCognitionDraft, RESPONDENT_OPENING_MARKER
from app.graphs.intake.errors import IntakeGraphContractError
from app.harness.context_window import ContextWindowManager
from app.harness.invocation_context import AgentInvocationContext
from app.harness.model_runner import (
    PreparedHarnessInvocation,
    prepare_baseline_invocation,
)
from app.harness.prompt_composer import PromptRepository
from app.schemas import IntakeInitialCaseFacts, IntakeTurnRequest
from app.schemas.final_agents import IntakeTurnMessage


BASELINE_INTAKE_NODE_NAME = "intake_turn_case_detail"
_BASELINE_CONTEXT_ENVELOPE_SCHEMA = "intake-baseline-context.v1"

_MEMORY_INITIAL_FACTS_KEY = "authorized_initial_case_facts"
_MEMORY_TRANSCRIPT_KEY = "initiator_statement_transcript"
_MEMORY_KEYS = frozenset(
    {
        _MEMORY_INITIAL_FACTS_KEY,
        _MEMORY_TRANSCRIPT_KEY,
    }
)

_TARGET_IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_MISSING_FIELD_IDENTIFIER_PREFIX = "MISSING_"


def build_intake_baseline_memory_summary(
    initial_case_facts: Mapping[str, Any],
    *,
    initiator_statement_transcript: list[Mapping[str, Any]] | None = None,
) -> str:
    """Serialize the durable, model-local Intake context cache.

    This preserves the complete same-party transcript outside the six-message
    dialogue window while retaining the original form-facts envelope.
    """

    try:
        facts = IntakeInitialCaseFacts.model_validate(deepcopy(dict(initial_case_facts)))
        transcript = [
            IntakeTurnMessage.model_validate(deepcopy(dict(message)))
            for message in initiator_statement_transcript or []
        ]
    except (TypeError, ValueError) as error:
        raise IntakeGraphContractError("INTAKE_BASELINE_MEMORY_INVALID") from error
    if len({message.message_id for message in transcript}) != len(transcript):
        raise IntakeGraphContractError("INTAKE_BASELINE_TRANSCRIPT_INVALID")

    payload: dict[str, Any] = {
        _MEMORY_INITIAL_FACTS_KEY: facts.model_dump(mode="json", exclude_none=True),
        _MEMORY_TRANSCRIPT_KEY: [message.model_dump(mode="json") for message in transcript],
    }
    return canonicalize(payload).decode("utf-8")


def append_intake_baseline_statement(
    memory_summary: str,
    *,
    turn_no: int,
    actor_role: str,
    text: str,
) -> str:
    """Append one participant answer using the legacy Java transcript identity."""

    if isinstance(turn_no, bool) or not isinstance(turn_no, int) or turn_no < 1:
        raise IntakeGraphContractError("INTAKE_BASELINE_TRANSCRIPT_INVALID")
    facts, transcript = read_intake_baseline_memory_summary(memory_summary)
    try:
        statement = IntakeTurnMessage.model_validate(
            {
                "message_id": f"INTAKE_TURN_{turn_no}",
                "role": actor_role,
                "text": text,
            }
        )
    except ValueError as error:
        raise IntakeGraphContractError("INTAKE_BASELINE_TRANSCRIPT_INVALID") from error

    statement_json = statement.model_dump(mode="json")
    for previous in transcript:
        if previous["message_id"] != statement.message_id:
            continue
        if previous != statement_json:
            raise IntakeGraphContractError("INTAKE_BASELINE_TRANSCRIPT_REBINDING")
        return memory_summary
    return build_intake_baseline_memory_summary(
        facts,
        initiator_statement_transcript=[*transcript, statement_json],
    )


def read_intake_baseline_memory_summary(
    memory_summary: str,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """Read both the legacy one-key summary and its durable extensions."""

    if not isinstance(memory_summary, str) or not memory_summary:
        raise IntakeGraphContractError("INTAKE_BASELINE_INITIAL_FACTS_MISSING")
    try:
        envelope = json.loads(memory_summary)
    except (TypeError, ValueError) as error:
        raise IntakeGraphContractError("INTAKE_BASELINE_INITIAL_FACTS_INVALID") from error
    if (
        not isinstance(envelope, dict)
        or _MEMORY_INITIAL_FACTS_KEY not in envelope
        or not set(envelope) <= _MEMORY_KEYS
    ):
        raise IntakeGraphContractError("INTAKE_BASELINE_INITIAL_FACTS_INVALID")
    facts = envelope[_MEMORY_INITIAL_FACTS_KEY]
    transcript = envelope.get(_MEMORY_TRANSCRIPT_KEY, [])
    if not isinstance(facts, dict) or not isinstance(transcript, list):
        raise IntakeGraphContractError("INTAKE_BASELINE_INITIAL_FACTS_INVALID")
    try:
        validated_facts = IntakeInitialCaseFacts.model_validate(deepcopy(facts))
        validated_transcript = [
            IntakeTurnMessage.model_validate(deepcopy(message)) for message in transcript
        ]
    except (TypeError, ValueError) as error:
        raise IntakeGraphContractError("INTAKE_BASELINE_TRANSCRIPT_INVALID") from error
    if len({message.message_id for message in validated_transcript}) != len(validated_transcript):
        raise IntakeGraphContractError("INTAKE_BASELINE_TRANSCRIPT_INVALID")
    return (
        validated_facts.model_dump(mode="json", exclude_none=True),
        [message.model_dump(mode="json") for message in validated_transcript],
    )


def build_intake_baseline_request(
    state: Mapping[str, Any],
    *,
    agent_context: AgentInvocationContext,
) -> IntakeTurnRequest:
    """Project durable graph state into the exact baseline Intake request."""

    if not isinstance(state, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_STATE_INVALID")
    bindings = _mapping(state.get("bindings"), "INTAKE_BASELINE_BINDINGS_INVALID")
    private = _mapping(bindings.get("private"), "INTAKE_BASELINE_BINDINGS_INVALID")
    if (
        private.get("case_id") != agent_context.case_id
        or private.get("room_type") != agent_context.room_type
        or private.get("agent_session_id") != agent_context.agent_session_id
        or private.get("audience") != agent_context.actor_role
    ):
        raise IntakeGraphContractError("INTAKE_BASELINE_AGENT_CONTEXT_MISMATCH")

    initial_facts, transcript = read_intake_baseline_memory_summary(state.get("memory_summary", ""))
    messages = _ordered_messages(state)
    current = _current_party_message(state, messages)
    if state.get("route") == "respondent_opening":
        # Import lazily to keep the validators/baseline module boundary acyclic.
        # The returned context is the exact snapshot-bound M0, not public or
        # command-supplied replacement data.
        from app.graphs.intake.validators import (
            validated_respondent_opening_frozen_context,
        )

        previous_context = validated_respondent_opening_frozen_context(state)
        source_ref = state.get("last_event_ref")
        if transcript or not isinstance(source_ref, str):
            raise IntakeGraphContractError("INTAKE_RESPONDENT_OPENING_CONTEXT_INVALID")
        request = {
            "case_id": agent_context.case_id,
            "room_type": "INTAKE",
            "turn_source": RESPONDENT_OPENING_MARKER,
            "initial_case_facts": None,
            "current_user_message": None,
            "recent_dialogue_messages": [],
            "previous_case_detail": previous_context,
            "respondent_opening_source_ref": source_ref,
            "initiator_statement_transcript": [],
            "agent_context": agent_context,
        }
    elif current is None:
        turn_source = str(initial_facts.get("form_source") or "")
        if turn_source not in {"EXTERNAL_IMPORT", "FORM_SUBMISSION"}:
            raise IntakeGraphContractError("INTAKE_BASELINE_FORM_SOURCE_MISSING")
        request = {
            "case_id": agent_context.case_id,
            "room_type": "INTAKE",
            "turn_source": turn_source,
            "initial_case_facts": initial_facts,
            "current_user_message": None,
            "recent_dialogue_messages": [],
            "previous_case_detail": None,
            # The retained Java baseline sends the opening form only as
            # initial_case_facts; it is not a participant answer transcript.
            "initiator_statement_transcript": [],
            "agent_context": agent_context,
        }
    else:
        transcript = _ensure_current_statement(transcript, current)
        request = {
            "case_id": agent_context.case_id,
            "room_type": "INTAKE",
            "turn_source": "ROOM_MESSAGE",
            "initial_case_facts": None,
            "current_user_message": _baseline_message(current, source="ROOM_MESSAGE"),
            "recent_dialogue_messages": [
                _baseline_message(
                    message,
                    source=("AGENT_RESPONSE" if message.get("role") == "AI" else "ROOM_MESSAGE"),
                )
                for message in messages
                if message is not current
            ][-5:],
            "previous_case_detail": _previous_case_detail(state),
            "initiator_statement_transcript": transcript,
            "agent_context": agent_context,
        }
    try:
        return IntakeTurnRequest.model_validate(request)
    except ValueError as error:
        raise IntakeGraphContractError("INTAKE_BASELINE_REQUEST_INVALID") from error


def prepare_intake_baseline_invocation(
    state: Mapping[str, Any],
    *,
    agent_context: AgentInvocationContext,
    prompts: PromptRepository | None = None,
    context_window: ContextWindowManager | None = None,
) -> PreparedHarnessInvocation:
    """Return the exact baseline System/Human messages for one durable turn."""

    request = build_intake_baseline_request(state, agent_context=agent_context)
    context_pack = build_intake_turn_context_pack(request)
    return prepare_baseline_invocation(
        prompts=prompts or PromptRepository(),
        context_window=context_window or ContextWindowManager(),
        node_name=BASELINE_INTAKE_NODE_NAME,
        case_data={"context_contract": "intake_turn_context.v2"},
        output_type=IntakeCaseDetailLlmOutput,
        context_pack=context_pack,
        agent_context=agent_context,
        prompt_profile_id=agent_context.prompt_profile_id,
    )


def adapt_intake_baseline_output(
    state: Mapping[str, Any],
    *,
    agent_context: AgentInvocationContext,
    output: IntakeCaseDetailLlmOutput,
) -> IntakeCognitionDraft:
    """Map a validated baseline response to the durable Target proposal contract."""

    draft, _ = adapt_intake_baseline_output_with_scroll_snapshot(
        state,
        agent_context=agent_context,
        output=output,
    )
    return draft


def adapt_intake_baseline_output_with_scroll_snapshot(
    state: Mapping[str, Any],
    *,
    agent_context: AgentInvocationContext,
    output: IntakeCaseDetailLlmOutput,
) -> tuple[IntakeCognitionDraft, dict[str, Any]]:
    """Adapt one response and retain its finalized semantic scroll snapshot.

    The public Target proposal deliberately excludes the formal case matrix.
    The returned snapshot is therefore for the graph's private baseline context
    only; it is the exact post-reducer/finalizer ``scroll_snapshot``, never the
    raw model matrix payload.
    """

    request = build_intake_baseline_request(state, agent_context=agent_context)
    output = _normalize_intake_baseline_matrix_fact_keys(state, output)
    output = _canonicalize_intake_baseline_historical_matrix_carry(
        request=request,
        output=output,
    )
    source_text = _baseline_source_text(request)
    projected = finalize_intake_projected_output(
        project_intake_case_detail_output(
            request=request,
            output=output,
            source_text=source_text,
        )
    )
    return _target_draft_and_scroll_snapshot(projected, output)


def _target_draft_and_scroll_snapshot(
    projected: Mapping[str, Any],
    output: IntakeCaseDetailLlmOutput,
) -> tuple[IntakeCognitionDraft, dict[str, Any]]:
    raw_scroll_snapshot = projected.get("scroll_snapshot")
    if not isinstance(raw_scroll_snapshot, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_SCROLL_SNAPSHOT_INVALID")
    scroll_snapshot = deepcopy(dict(raw_scroll_snapshot))
    detail = deepcopy(scroll_snapshot)
    detail.pop("case_fact_matrix", None)
    detail.pop("unilateral_case_matrix", None)
    matrix = output.case_matrix_delta or output.unilateral_case_matrix
    recommendation = str(projected["admission_recommendation"])
    quality = detail.get("intake_quality")
    ready = isinstance(quality, Mapping) and quality.get("ready_for_next_step") is True
    readiness = (
        "READY_TO_CONFIRM"
        if ready
        else "NEEDS_REVIEW"
        if recommendation == "NOT_ADMISSIBLE"
        else "INCOMPLETE"
    )
    return (
        IntakeCognitionDraft.model_validate(
            {
                "room_utterance": projected["room_utterance"],
                "dossier_patch": detail,
                "matrix_patch": (matrix.model_dump(mode="json") if matrix is not None else None),
                "readiness": readiness,
                "missing_fields": _target_missing_field_identifiers(projected["missing_fields"]),
                "recommendation": recommendation,
                "knowledge_answer_mode": projected["knowledge_answer_mode"],
                "confidence": projected["confidence"],
            }
        ),
        scroll_snapshot,
    )


def _baseline_source_text(request: IntakeTurnRequest) -> str:
    if request.current_user_message is not None:
        return request.current_user_message.text
    if request.initial_case_facts is not None:
        return str(request.initial_case_facts.form_description or "")
    return ""


def normalize_model_matrix_fact_key_payload(
    matrix_patch: Mapping[str, Any],
    *,
    authorized_fact_ids: frozenset[str],
) -> dict[str, Any]:
    """Demote unknown model-authored ``FACT_*`` keys before matrix reduction.

    Stable ``FACT_*`` identifiers belong to the authorized dossier.  A model may
    only propose a new fact under ``NEW_*``.  The payload boundary is shared by
    the baseline adapter, which must normalize before its formal reducer, and
    the Target proposal adapter, which rechecks its typed matrix draft.
    """

    normalized = deepcopy(dict(matrix_patch))
    if normalized.get("schema_version") not in {
        "unilateral_case_matrix.draft.v1",
        "case_fact_matrix.delta.v2",
    }:
        raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_INVALID")
    rows = normalized.get("fact_rows")
    summary_keys = normalized.get("summary_source_fact_keys")
    if not isinstance(rows, list) or not isinstance(summary_keys, list):
        raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_INVALID")

    proposed_keys: set[str] = set()
    for row in rows:
        if not isinstance(row, dict):
            raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_INVALID")
        fact_key = row.get("fact_key")
        if not isinstance(fact_key, str):
            raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_INVALID")
        if fact_key in proposed_keys:
            raise IntakeGraphContractError("INTAKE_MATRIX_FACT_ID_CONFLICT")
        proposed_keys.add(fact_key)
    if any(not isinstance(fact_key, str) for fact_key in summary_keys):
        raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_INVALID")

    replacements: dict[str, str] = {}
    removed: set[str] = set()
    for row in rows:
        fact_key = row["fact_key"]
        if not fact_key.startswith("FACT_") or fact_key in authorized_fact_ids:
            continue
        source_scope = row.get("source_scope")
        if source_scope == "PREVIOUS_MATRIX":
            removed.add(fact_key)
            continue
        if source_scope not in {
            "CURRENT_SOURCE",
            "PREVIOUS_AND_CURRENT_SOURCE",
        }:
            raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_INVALID")
        replacement = f"NEW_{fact_key.removeprefix('FACT_')}"
        if replacement in proposed_keys or replacement in replacements.values():
            raise IntakeGraphContractError("INTAKE_MATRIX_FACT_ID_CONFLICT")
        replacements[fact_key] = replacement

    if not replacements and not removed:
        return normalized

    normalized_rows: list[dict[str, Any]] = []
    for row in rows:
        fact_key = row["fact_key"]
        if fact_key in removed:
            continue
        if fact_key in replacements:
            row["fact_key"] = replacements[fact_key]
            if row.get("source_scope") == "PREVIOUS_AND_CURRENT_SOURCE":
                row["source_scope"] = "CURRENT_SOURCE"
        normalized_rows.append(row)
    normalized_summary_keys = [
        replacements.get(fact_key, fact_key)
        for fact_key in summary_keys
        if fact_key not in removed
    ]
    remaining_keys = {row["fact_key"] for row in normalized_rows}
    if (
        not normalized_rows
        or not normalized_summary_keys
        or any(fact_key not in remaining_keys for fact_key in normalized_summary_keys)
    ):
        raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_INVALID")
    normalized["fact_rows"] = normalized_rows
    normalized["summary_source_fact_keys"] = normalized_summary_keys
    return normalized


def _normalize_intake_baseline_matrix_fact_keys(
    state: Mapping[str, Any],
    output: IntakeCaseDetailLlmOutput,
) -> IntakeCaseDetailLlmOutput:
    """Normalize the parsed model matrix before the baseline reducer consumes it."""

    if output.case_matrix_delta is not None:
        matrix_field = "case_matrix_delta"
        matrix = output.case_matrix_delta
    elif output.unilateral_case_matrix is not None:
        matrix_field = "unilateral_case_matrix"
        matrix = output.unilateral_case_matrix
    else:
        raise IntakeGraphContractError("INTAKE_BASELINE_MATRIX_PATCH_INVALID")

    matrix_payload = matrix.model_dump(mode="json", exclude_none=True)
    normalized_matrix = normalize_model_matrix_fact_key_payload(
        matrix_payload,
        authorized_fact_ids=intake_baseline_authorized_fact_ids(state),
    )
    if normalized_matrix == matrix_payload:
        return output

    normalized_output = output.model_dump(mode="json", exclude_none=True)
    normalized_output[matrix_field] = normalized_matrix
    try:
        return IntakeCaseDetailLlmOutput.model_validate(normalized_output)
    except ValueError as error:
        raise IntakeGraphContractError("INTAKE_BASELINE_MATRIX_PATCH_INVALID") from error


def _canonicalize_intake_baseline_historical_matrix_carry(
    *,
    request: IntakeTurnRequest,
    output: IntakeCaseDetailLlmOutput,
) -> IntakeCaseDetailLlmOutput:
    """Restore immutable bindings and actor semantics for exact historical rows."""

    matrix = output.case_matrix_delta
    previous_detail = request.previous_case_detail
    if matrix is None or not isinstance(previous_detail, Mapping):
        return output
    previous_matrix = previous_detail.get("case_fact_matrix")
    if not isinstance(previous_matrix, Mapping):
        return output
    previous_rows = previous_matrix.get("fact_rows")
    if not isinstance(previous_rows, list):
        return output

    previous_by_id: dict[str, Mapping[str, Any]] = {}
    for previous_row in previous_rows:
        if not isinstance(previous_row, Mapping):
            return output
        fact_id = previous_row.get("fact_id")
        if not isinstance(fact_id, str) or fact_id in previous_by_id:
            return output
        previous_by_id[fact_id] = previous_row

    matrix_payload = matrix.model_dump(mode="json", exclude_none=True)
    rows = matrix_payload.get("fact_rows")
    if not isinstance(rows, list):
        return output
    actor_role = request.agent_context.actor_role
    changed = False
    for row in rows:
        if not isinstance(row, dict):
            continue
        previous_row = previous_by_id.get(row.get("fact_key"))
        if previous_row is None:
            continue
        for field in ("category", "fact_target", "materiality"):
            authoritative_value = deepcopy(previous_row.get(field))
            if row.get(field) != authoritative_value:
                row[field] = authoritative_value
                changed = True
        if row.get("source_scope") != "PREVIOUS_MATRIX":
            continue
        positions = previous_row.get("positions")
        previous_position = (
            positions.get(actor_role) if isinstance(positions, Mapping) else None
        )
        if not isinstance(previous_position, Mapping):
            continue
        for field in ("stance", "position_summary", "asserted_value"):
            authoritative_value = deepcopy(previous_position.get(field))
            if row.get(field) != authoritative_value:
                row[field] = authoritative_value
                changed = True

    if not changed:
        return output
    normalized_output = output.model_dump(mode="json", exclude_none=True)
    normalized_output["case_matrix_delta"] = matrix_payload
    try:
        return IntakeCaseDetailLlmOutput.model_validate(normalized_output)
    except ValueError as error:
        raise IntakeGraphContractError("INTAKE_BASELINE_MATRIX_PATCH_INVALID") from error


def _selected_previous_matrix(
    detail: Mapping[str, Any],
) -> tuple[str, Mapping[str, Any]] | None:
    matrix_keys = [
        key
        for key in ("case_fact_matrix", "unilateral_case_matrix")
        if key in detail
    ]
    if len(matrix_keys) > 1:
        raise IntakeGraphContractError("INTAKE_BASELINE_PREVIOUS_DETAIL_INVALID")
    if not matrix_keys:
        return None
    matrix_key = matrix_keys[0]
    matrix = detail.get(matrix_key)
    if not isinstance(matrix, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_PREVIOUS_DETAIL_INVALID")
    _selected_matrix_fact_ids(matrix)
    return matrix_key, matrix


def _selected_matrix_fact_ids(matrix: Mapping[str, Any]) -> frozenset[str]:
    rows = matrix.get("fact_rows")
    if not isinstance(rows, list) or not rows:
        raise IntakeGraphContractError("INTAKE_BASELINE_PREVIOUS_DETAIL_INVALID")
    fact_ids: set[str] = set()
    for row in rows:
        if not isinstance(row, Mapping):
            raise IntakeGraphContractError("INTAKE_BASELINE_PREVIOUS_DETAIL_INVALID")
        fact_id = row.get("fact_id")
        if (
            not isinstance(fact_id, str)
            or not fact_id.startswith("FACT_")
            or fact_id in fact_ids
        ):
            raise IntakeGraphContractError("INTAKE_BASELINE_PREVIOUS_DETAIL_INVALID")
        fact_ids.add(fact_id)
    return frozenset(fact_ids)


def _domain_owned_matrix(
    state: Mapping[str, Any],
) -> tuple[str, Mapping[str, Any]] | None:
    dossier = state.get("dossier_draft")
    if dossier is None:
        return None
    if not isinstance(dossier, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_PREVIOUS_DETAIL_INVALID")
    return _selected_previous_matrix(dossier)


def intake_baseline_authorized_fact_ids(state: Mapping[str, Any]) -> frozenset[str]:
    """Return stable fact IDs from the selected committed previous matrix."""

    detail = _previous_case_detail(state)
    if detail is None:
        return frozenset()
    selected = _selected_previous_matrix(detail)
    if selected is None:
        return frozenset()
    return _selected_matrix_fact_ids(selected[1])


def _target_missing_field_identifiers(missing_fields: Any) -> list[str]:
    """Project baseline display gaps onto unique Target identifiers.

    The retained baseline deliberately keeps human-readable gap labels in its
    dossier.  Target proposal contracts instead require machine identifiers, so
    only this proposal-facing list is adapted.  Legal baseline identifiers stay
    unchanged; every display label receives a canonical hash-derived identifier.
    """

    if not isinstance(missing_fields, list | tuple) or any(
        not isinstance(field, str) for field in missing_fields
    ):
        raise IntakeGraphContractError("INTAKE_BASELINE_MISSING_FIELDS_INVALID")

    # Reserve every source identifier before deriving display-label identifiers.
    # This also prevents a (rare, but valid) source identifier such as a hash
    # output from collapsing a distinct display label.
    reserved = {field for field in missing_fields if _TARGET_IDENTIFIER.fullmatch(field)}
    used: set[str] = set()
    display_identifiers: dict[str, str] = {}
    adapted: list[str] = []

    for field in missing_fields:
        if _TARGET_IDENTIFIER.fullmatch(field):
            identifier = field
        else:
            identifier = display_identifiers.get(field)
            if identifier is None:
                identifier = _missing_field_identifier(field, reserved | used)
                display_identifiers[field] = identifier
        if identifier not in used:
            adapted.append(identifier)
            used.add(identifier)
    return adapted


def _missing_field_identifier(display_label: str, unavailable: set[str]) -> str:
    """Return a retry-stable hash identifier not used by this proposal."""

    identifier = _MISSING_FIELD_IDENTIFIER_PREFIX + canonical_sha256(display_label)
    collision_index = 0
    while identifier in unavailable:
        collision_index += 1
        identifier = _MISSING_FIELD_IDENTIFIER_PREFIX + canonical_sha256(
            {
                "display_label": display_label,
                "collision_index": collision_index,
            }
        )
    return identifier


def _initial_case_facts(state: Mapping[str, Any]) -> dict[str, Any]:
    facts, _ = read_intake_baseline_memory_summary(state.get("memory_summary", ""))
    return facts


def _ordered_messages(state: Mapping[str, Any]) -> list[Mapping[str, Any]]:
    raw = state.get("messages")
    if not isinstance(raw, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_MESSAGES_INVALID")
    messages = list(raw.values())
    if any(not isinstance(message, Mapping) for message in messages):
        raise IntakeGraphContractError("INTAKE_BASELINE_MESSAGES_INVALID")
    return sorted(
        cast(list[Mapping[str, Any]], messages),
        key=lambda message: (
            int(message.get("sequence", -1)),
            0 if message.get("role") == "HUMAN" else 1,
            str(message.get("message_id") or ""),
        ),
    )


def _current_party_message(
    state: Mapping[str, Any],
    messages: list[Mapping[str, Any]],
) -> Mapping[str, Any] | None:
    event_hash = state.get("last_event_hash")
    candidates = [
        message
        for message in messages
        if message.get("role") == "HUMAN" and message.get("source_hash") == event_hash
    ]
    if len(candidates) > 1:
        raise IntakeGraphContractError("INTAKE_BASELINE_CURRENT_MESSAGE_AMBIGUOUS")
    return candidates[0] if candidates else None


def _previous_case_detail(state: Mapping[str, Any]) -> dict[str, Any] | None:
    # New checkpoints retain the full deterministic scroll snapshot separately
    # from the public dossier projection.  Older checkpoints have no such field,
    # so preserve their deterministic dossier fallback for replay compatibility.
    baseline_previous = state.get("baseline_previous_case_detail")
    raw = baseline_previous if baseline_previous is not None else state.get("dossier_draft")
    if isinstance(raw, Mapping) and raw.get("schema_version") == _BASELINE_CONTEXT_ENVELOPE_SCHEMA:
        # Import lazily to avoid the validators module's baseline-memory import
        # cycle.  Prompt construction is a trust boundary too: an internally
        # self-consistent envelope may not be unwrapped for another private
        # case/thread/audience or snapshot lineage.
        from app.graphs.intake.validators import (
            unwrap_verified_baseline_previous_case_detail,
        )

        raw = unwrap_verified_baseline_previous_case_detail(state)
    if raw is None:
        detail: dict[str, Any] = {}
    elif isinstance(raw, Mapping):
        detail = deepcopy(dict(raw))
    else:
        raise IntakeGraphContractError("INTAKE_BASELINE_PREVIOUS_DETAIL_INVALID")
    domain_matrix = _domain_owned_matrix(state)
    if domain_matrix is not None:
        matrix_key, matrix = domain_matrix
        detail.pop("case_fact_matrix", None)
        detail.pop("unilateral_case_matrix", None)
        detail[matrix_key] = deepcopy(dict(matrix))
    return detail or None


def _ensure_current_statement(
    transcript: list[dict[str, Any]],
    current: Mapping[str, Any],
) -> list[dict[str, Any]]:
    statement = _statement_from_message(current)
    for previous in transcript:
        if previous["message_id"] != statement["message_id"]:
            continue
        if previous != statement:
            raise IntakeGraphContractError("INTAKE_BASELINE_TRANSCRIPT_REBINDING")
        return transcript
    return [*transcript, statement]


def _statement_from_message(message: Mapping[str, Any]) -> dict[str, Any]:
    sequence = message.get("sequence")
    if isinstance(sequence, bool) or not isinstance(sequence, int) or sequence < 1:
        raise IntakeGraphContractError("INTAKE_BASELINE_TRANSCRIPT_INVALID")
    try:
        statement = IntakeTurnMessage.model_validate(
            {
                # Java's RoomTurnMemory transcript exposes turn identities as
                # INTAKE_TURN_{turnNo}; the durable event sequence is the
                # corresponding Target-only participant turn cursor.
                "message_id": f"INTAKE_TURN_{sequence}",
                "role": message.get("audience"),
                "text": message.get("content"),
            }
        )
    except ValueError as error:
        raise IntakeGraphContractError("INTAKE_BASELINE_TRANSCRIPT_INVALID") from error
    return statement.model_dump(mode="json")


def _baseline_message(message: Mapping[str, Any], *, source: str) -> dict[str, Any]:
    role = message.get("role")
    audience = message.get("audience")
    return {
        "message_id": message.get("message_id"),
        "sequence_no": message.get("sequence"),
        "role": "AGENT" if role == "AI" else audience,
        "source": source,
        "text": message.get("content"),
    }


def _mapping(value: Any, code: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise IntakeGraphContractError(code)
    return value


__all__ = [
    "BASELINE_INTAKE_NODE_NAME",
    "adapt_intake_baseline_output",
    "adapt_intake_baseline_output_with_scroll_snapshot",
    "append_intake_baseline_statement",
    "build_intake_baseline_memory_summary",
    "build_intake_baseline_request",
    "intake_baseline_authorized_fact_ids",
    "normalize_model_matrix_fact_key_payload",
    "prepare_intake_baseline_invocation",
    "read_intake_baseline_memory_summary",
]
