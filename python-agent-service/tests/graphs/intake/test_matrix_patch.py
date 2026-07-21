from __future__ import annotations

import copy

import pytest
from pydantic import ValidationError

from app.contracts.v1.codec import canonical_sha256_omitting
from app.graphs.intake.contracts import IntakeCognitionDraft
from app.graphs.intake.errors import IntakeGraphContractError
from app.graphs.intake.graph import (
    _create_test_only_intake_cognition,
    compile_intake_v2_graph,
)
from app.graphs.intake.nodes import deterministic_message_fallback
from app.graphs.intake.state import IntakeTurnContext, new_intake_graph_state
from app.graphs.intake.validators import (
    MATRIX_AUTHORITY_RECORD_KEY,
    validate_matrix_patch,
)


def _unilateral_patch() -> dict:
    return {
        "schema_version": "unilateral_case_matrix.draft.v1",
        "fact_rows": [
            {
                "fact_key": "NEW_DAMAGE",
                "category": "PRODUCT_STATE",
                "fact_target": "Whether the order arrived damaged.",
                "materiality": "CORE",
                "position_summary": "The current actor reports visible damage.",
                "asserted_value": "damaged",
                "source_scope": "CURRENT_SOURCE",
            }
        ],
        "summary_source_fact_keys": ["NEW_DAMAGE"],
    }


def _delta_patch() -> dict:
    return {
        "schema_version": "case_fact_matrix.delta.v2",
        "fact_rows": [
            {
                "fact_key": "FACT_DAMAGE",
                "category": "PRODUCT_STATE",
                "fact_target": "Whether the order arrived damaged.",
                "materiality": "CORE",
                "stance": "DENY",
                "position_summary": "The current actor disputes the reported damage.",
                "asserted_value": "undamaged at dispatch",
                "source_scope": "CURRENT_SOURCE",
                "conflict_summary": "The parties disagree about the item condition.",
            }
        ],
        "summary_source_fact_keys": ["FACT_DAMAGE"],
        "respondent_claim": {
            "attitude": "DISAGREE",
            "position_summary": "The current actor disputes the refund request.",
        },
    }


def _cognition(matrix_patch: dict) -> dict:
    return {
        "room_utterance": "The structured position was recorded.",
        "dossier_patch": {"schema_version": "intake-dossier.v2"},
        "matrix_patch": matrix_patch,
        "readiness": "INCOMPLETE",
        "missing_fields": ["supporting_evidence"],
        "recommendation": "NEED_MORE_INFO",
        "knowledge_answer_mode": "NONE",
        "confidence": 0.75,
    }


def _formal_initiator_matrix(case_id: str) -> dict:
    return {
        "schema_version": "case_fact_matrix.v2",
        "case_id": case_id,
        "matrix_id": "CASE_MATRIX_SYNTHETIC_1",
        "matrix_version": 1,
        "matrix_kind": "INITIATOR_FROZEN",
        "parent_ref": None,
        "content_hash": "a" * 64,
        "party_map": {"initiator_role": "USER", "respondent_role": "MERCHANT"},
        "source_refs": ["MESSAGE_P4_USER_1"],
        "case_overview": {
            "neutral_summary": "A damage dispute.",
            "core_conflict": "Whether damage existed at delivery.",
            "summary_source_fact_ids": ["FACT_DAMAGE"],
        },
        "claims": {},
        "fact_rows": [
            {
                "fact_id": "FACT_DAMAGE",
                "category": "PRODUCT_STATE",
                "fact_target": "Whether the order arrived damaged.",
                "materiality": "CORE",
                "positions": {
                    "USER": {
                        "stance": "CONFIRM",
                        "position_summary": "The order arrived damaged.",
                        "asserted_value": "damaged",
                    },
                    "MERCHANT": {
                        "stance": "NOT_ADDRESSED",
                        "position_summary": "The respondent has not addressed this fact.",
                        "asserted_value": None,
                    },
                },
            }
        ],
        "generation_ref": {
            "actor_role": "USER",
            "source_stage": "INITIATOR_INTAKE",
            "latest_source_ref": "MESSAGE_P4_USER_1",
            "source_context_hash": "b" * 64,
        },
        "fact_indexes": {},
    }


def _import_state(bindings, version_pins, snapshot):
    graph = compile_intake_v2_graph(
        intake_lcel=_create_test_only_intake_cognition(deterministic_message_fallback)
    )
    return graph.invoke(
        new_intake_graph_state(bindings=bindings, version_pins=version_pins),
        context=IntakeTurnContext("SNAPSHOT", snapshot),
    )


def _respondent_state(bindings, version_pins, snapshot):
    respondent_bindings = copy.deepcopy(bindings)
    respondent_bindings["private"]["audience"] = "MERCHANT"
    respondent_snapshot = copy.deepcopy(snapshot)
    respondent_snapshot["own_messages"][0]["audience"] = "MERCHANT"
    respondent_snapshot["current_dossier"]["case_fact_matrix"] = _formal_initiator_matrix(
        respondent_snapshot["case_id"]
    )
    respondent_snapshot["snapshot_hash"] = canonical_sha256_omitting(
        respondent_snapshot,
        "snapshot_hash",
    )
    return (
        respondent_bindings,
        respondent_snapshot,
        _import_state(
            respondent_bindings,
            version_pins,
            respondent_snapshot,
        ),
    )


def test_contract_union_requires_explicit_delta_stance() -> None:
    parsed = IntakeCognitionDraft.model_validate(_cognition(_delta_patch()))
    assert parsed.matrix_patch is not None
    assert parsed.matrix_patch.schema_version == "case_fact_matrix.delta.v2"

    missing_stance = _cognition(_delta_patch())
    missing_stance["matrix_patch"]["fact_rows"][0].pop("stance")
    with pytest.raises(ValidationError, match="stance"):
        IntakeCognitionDraft.model_validate(missing_stance)


def test_initiator_can_only_propose_unilateral_matrix(
    bindings,
    version_pins,
    snapshot,
) -> None:
    state = _import_state(bindings, version_pins, snapshot)

    validate_matrix_patch(state, _unilateral_patch())
    with pytest.raises(IntakeGraphContractError, match="INTAKE_MATRIX_PATCH_UNAUTHORIZED"):
        validate_matrix_patch(state, _delta_patch())


def test_unlocked_respondent_can_only_propose_delta(
    bindings,
    version_pins,
    snapshot,
) -> None:
    _, _, state = _respondent_state(bindings, version_pins, snapshot)

    validate_matrix_patch(state, _delta_patch())
    with pytest.raises(IntakeGraphContractError, match="INTAKE_MATRIX_PATCH_UNAUTHORIZED"):
        validate_matrix_patch(state, _unilateral_patch())


def test_respondent_delta_fails_closed_without_locked_initiator_matrix(
    bindings,
    version_pins,
    snapshot,
) -> None:
    respondent_bindings = copy.deepcopy(bindings)
    respondent_bindings["private"]["audience"] = "MERCHANT"
    snapshot["own_messages"][0]["audience"] = "MERCHANT"
    snapshot["snapshot_hash"] = canonical_sha256_omitting(snapshot, "snapshot_hash")
    state = _import_state(respondent_bindings, version_pins, snapshot)

    with pytest.raises(IntakeGraphContractError, match="INTAKE_MATRIX_PATCH_UNAUTHORIZED"):
        validate_matrix_patch(state, _delta_patch())


@pytest.mark.parametrize(
    "mutation",
    [
        lambda patch: patch["fact_rows"][0].pop("stance"),
        lambda patch: patch["fact_rows"][0].update(
            fact_key="NEW_DAMAGE",
            stance="NOT_ADDRESSED",
            asserted_value=None,
            source_scope="PREVIOUS_MATRIX",
        ),
        lambda patch: patch.update(summary_source_fact_keys=["FACT_UNKNOWN"]),
        lambda patch: patch.update(matrix_version=1),
    ],
)
def test_delta_shape_and_formal_authority_fields_fail_closed(
    bindings,
    version_pins,
    snapshot,
    mutation,
) -> None:
    _, _, state = _respondent_state(bindings, version_pins, snapshot)
    patch = _delta_patch()
    mutation(patch)

    with pytest.raises(IntakeGraphContractError, match="INTAKE_MATRIX_PATCH_INVALID"):
        validate_matrix_patch(state, patch)


@pytest.mark.parametrize(
    ("mutation", "error_code"),
    [
        (
            lambda patch: (
                patch["fact_rows"][0].update(fact_key="FACT_UNKNOWN"),
                patch.update(summary_source_fact_keys=["FACT_UNKNOWN"]),
            ),
            "INTAKE_MATRIX_FACT_UNKNOWN",
        ),
        (
            lambda patch: patch["fact_rows"][0].update(fact_target="A rebound target."),
            "INTAKE_MATRIX_FACT_REBOUND",
        ),
        (
            lambda patch: (
                patch["fact_rows"][0].update(fact_key="NEW_DAMAGE"),
                patch.update(summary_source_fact_keys=["NEW_DAMAGE"]),
            ),
            "INTAKE_MATRIX_FACT_REBOUND",
        ),
    ],
)
def test_delta_rejects_unknown_or_rebound_fact_identity(
    bindings,
    version_pins,
    snapshot,
    mutation,
    error_code,
) -> None:
    _, _, state = _respondent_state(bindings, version_pins, snapshot)
    patch = _delta_patch()
    mutation(patch)

    with pytest.raises(IntakeGraphContractError, match=error_code):
        validate_matrix_patch(state, patch)


def test_not_addressed_only_carries_a_prior_fact_without_asserted_value(
    bindings,
    version_pins,
    snapshot,
) -> None:
    _, _, state = _respondent_state(bindings, version_pins, snapshot)
    patch = _delta_patch()
    patch["fact_rows"][0].update(
        stance="NOT_ADDRESSED",
        position_summary="The respondent has not addressed this fact.",
        asserted_value=None,
        source_scope="PREVIOUS_MATRIX",
    )

    validate_matrix_patch(state, patch)


def test_tampered_checkpoint_authority_fails_closed(
    bindings,
    version_pins,
    snapshot,
) -> None:
    state = _import_state(bindings, version_pins, snapshot)
    state["node_results"][MATRIX_AUTHORITY_RECORD_KEY]["proposal_mode"] = "RESPONDENT_DELTA"

    with pytest.raises(IntakeGraphContractError, match="INTAKE_MATRIX_PATCH_UNAUTHORIZED"):
        validate_matrix_patch(state, _unilateral_patch())


def test_respondent_delta_projects_and_replays_deterministically(
    bindings,
    version_pins,
    snapshot,
    event,
) -> None:
    respondent_bindings, respondent_snapshot, _ = _respondent_state(
        bindings,
        version_pins,
        snapshot,
    )
    patch = _delta_patch()

    def cognition(state, runtime):
        del runtime
        return {
            "cognitive_revision": state["cognitive_revision"] + 1,
            "terminal_draft": _cognition(copy.deepcopy(patch)),
        }

    graph = compile_intake_v2_graph(intake_lcel=_create_test_only_intake_cognition(cognition))
    initialized = graph.invoke(
        new_intake_graph_state(bindings=respondent_bindings, version_pins=version_pins),
        context=IntakeTurnContext("SNAPSHOT", respondent_snapshot),
    )
    initialized["bindings"]["command"].update(
        command_id="COMMAND_P4_MERCHANT_2",
        logical_run_id="RUN_P4_MERCHANT_2",
        attempt_id="ATTEMPT_P4_MERCHANT_2_1",
    )
    respondent_event = copy.deepcopy(event)
    respondent_event["audience"] = "MERCHANT"
    respondent_event["event_hash"] = canonical_sha256_omitting(
        respondent_event,
        "event_hash",
    )
    first = graph.invoke(
        initialized,
        context=IntakeTurnContext("EVENT", respondent_event),
    )
    replay = graph.invoke(
        first,
        context=IntakeTurnContext("EVENT", copy.deepcopy(respondent_event)),
    )

    assert first["result_json"]["matrix_patch"] == patch
    assert replay["result_json"] == first["result_json"]
    assert replay["cognitive_revision"] == first["cognitive_revision"]
