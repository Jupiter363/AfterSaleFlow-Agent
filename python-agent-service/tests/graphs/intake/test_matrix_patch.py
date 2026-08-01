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
    matrix = {
        "schema_version": "case_fact_matrix.v2",
        "case_id": case_id,
        "matrix_id": "CASE_MATRIX_0123456789ABCDEF0123",
        "matrix_version": 1,
        "matrix_kind": "INITIATOR_FROZEN",
        "parent_ref": None,
        "content_hash": "0" * 64,
        "party_map": {"initiator_role": "USER", "respondent_role": "MERCHANT"},
        "source_refs": ["MESSAGE_P4_USER_1"],
        "case_overview": {
            "neutral_summary": "A damage dispute.",
            "core_conflict": "Whether damage existed at delivery.",
            "summary_source_fact_ids": ["FACT_DAMAGE"],
        },
        "claims": {
            "initiator_claim": {
                "initiator_role": "USER",
                "requested_resolution": "REFUND",
                "reason_summary": "The order allegedly arrived damaged.",
                "position_summary": "The initiator requests a refund.",
                "source_refs": ["MESSAGE_P4_USER_1"],
            },
            "respondent_reported_by_initiator": None,
            "respondent_direct": None,
            "claim_conflict": None,
        },
        "fact_rows": [
            {
                "fact_id": "FACT_DAMAGE",
                "category": "PRODUCT_STATE",
                "fact_target": "Whether the order arrived damaged.",
                "materiality": "CORE",
                "origin": {
                    "introduced_stage": "INITIATOR_INTAKE",
                    "source_refs": ["MESSAGE_P4_USER_1"],
                },
                "positions": {
                    "USER": {
                        "stance": "CONFIRM",
                        "position_summary": "The order arrived damaged.",
                        "asserted_value": "damaged",
                        "source_type": "DIRECT_PARTY_STATEMENT",
                        "source_refs": ["MESSAGE_P4_USER_1"],
                    },
                    "MERCHANT": {
                        "stance": "NOT_ADDRESSED",
                        "position_summary": "No direct respondent position is recorded.",
                        "asserted_value": None,
                        "source_type": "NO_DIRECT_POSITION",
                        "source_refs": [],
                    },
                },
                "party_alignment": {
                    "status": "NOT_COMPUTED",
                    "agreed_statement": None,
                    "conflict_summary": None,
                },
                "requires_resolution": None,
                "truth_status": "NOT_EVALUATED",
                "evidence_coverage_status": "PENDING_EVIDENCE_REVIEW",
            },
        ],
        "fact_relationships": [],
        "generation_ref": {
            "actor_role": "USER",
            "source_stage": "INITIATOR_INTAKE",
            "latest_source_ref": "MESSAGE_P4_USER_1",
            "source_context_hash": "b" * 64,
        },
        "fact_indexes": {
            "not_computed_fact_ids": ["FACT_DAMAGE"],
            "agreed_fact_ids": [],
            "partially_agreed_fact_ids": [],
            "contested_fact_ids": [],
            "one_sided_fact_ids": [],
            "unresolved_fact_ids": [],
            "core_fact_ids": ["FACT_DAMAGE"],
            "requires_resolution_fact_ids": [],
        },
    }
    matrix["content_hash"] = canonical_sha256_omitting(matrix, "content_hash")
    return matrix


def _import_state(bindings, version_pins, snapshot):
    graph = compile_intake_v2_graph(
        intake_lcel=_create_test_only_intake_cognition(deterministic_message_fallback)
    )
    return graph.invoke(
        new_intake_graph_state(bindings=bindings, version_pins=version_pins),
        context=IntakeTurnContext("SNAPSHOT", snapshot),
    )


def _respondent_snapshot(bindings, snapshot):
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
    return respondent_bindings, respondent_snapshot


def _respondent_state(bindings, version_pins, snapshot):
    respondent_bindings, respondent_snapshot = _respondent_snapshot(bindings, snapshot)
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


def test_provider_matrix_patch_is_canonicalized_to_the_top_level_envelope() -> None:
    cognition = _cognition(_unilateral_patch())
    nested_patch = cognition.pop("matrix_patch")
    cognition["dossier_patch"]["matrix_patch"] = nested_patch

    parsed = IntakeCognitionDraft.model_validate(cognition)

    assert parsed.matrix_patch is not None
    assert parsed.matrix_patch.schema_version == "unilateral_case_matrix.draft.v1"
    assert "matrix_patch" not in parsed.dossier_patch.model_dump(
        mode="json",
        exclude_none=True,
        exclude_unset=True,
    )


def test_conflicting_matrix_patch_envelope_locations_are_rejected() -> None:
    cognition = _cognition(_unilateral_patch())
    cognition["dossier_patch"]["matrix_patch"] = _unilateral_patch()

    with pytest.raises(ValidationError, match="both envelope locations"):
        IntakeCognitionDraft.model_validate(cognition)


def test_provider_null_dossier_branches_are_treated_as_omitted() -> None:
    cognition = _cognition(_unilateral_patch())
    cognition["dossier_patch"]["case_story"] = {"summary": "商品未收到"}
    cognition["dossier_patch"].update(
        schema_version=None,
        references=None,
        claim_resolution=None,
        respondent_attitude=None,
        dispute_core_state=None,
        admission=None,
    )

    parsed = IntakeCognitionDraft.model_validate(cognition)
    dossier = parsed.dossier_patch.model_dump(
        mode="json",
        exclude_none=True,
        exclude_unset=True,
    )

    assert dossier == {"case_story": {"summary": "商品未收到"}}
    assert parsed.matrix_patch is not None


def test_unknown_null_dossier_key_is_not_canonicalized_away() -> None:
    cognition = _cognition(_unilateral_patch())
    cognition["dossier_patch"]["case_fact_matrix"] = None

    with pytest.raises(ValidationError, match="Extra inputs are not permitted"):
        IntakeCognitionDraft.model_validate(cognition)


def test_incomplete_provider_acceptance_is_normalized_conservatively() -> None:
    cognition = _cognition(_unilateral_patch())
    cognition["recommendation"] = "ACCEPTED"

    parsed = IntakeCognitionDraft.model_validate(cognition)

    assert parsed.readiness == "INCOMPLETE"
    assert parsed.missing_fields == ("supporting_evidence",)
    assert parsed.recommendation == "NEED_MORE_INFO"


@pytest.mark.parametrize(
    ("readiness", "missing_fields", "recommendation", "expected_readiness", "expected_recommendation"),
    [
        ("INCOMPLETE", [], "ACCEPTED", "INCOMPLETE", "NEED_MORE_INFO"),
        (
            "READY_TO_CONFIRM",
            ["supporting_evidence"],
            "ACCEPTED",
            "INCOMPLETE",
            "NEED_MORE_INFO",
        ),
        (
            "READY_TO_CONFIRM",
            [],
            "NEED_MORE_INFO",
            "INCOMPLETE",
            "NEED_MORE_INFO",
        ),
        (
            "INCOMPLETE",
            [],
            "NOT_ADMISSIBLE",
            "NEEDS_REVIEW",
            "NOT_ADMISSIBLE",
        ),
    ],
)
def test_provider_readiness_pairs_are_normalized_without_auto_admission(
    readiness: str,
    missing_fields: list[str],
    recommendation: str,
    expected_readiness: str,
    expected_recommendation: str,
) -> None:
    cognition = _cognition(_unilateral_patch())
    cognition["readiness"] = readiness
    cognition["missing_fields"] = missing_fields
    cognition["recommendation"] = recommendation

    parsed = IntakeCognitionDraft.model_validate(cognition)

    assert parsed.readiness == expected_readiness
    assert parsed.recommendation == expected_recommendation


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("matrix_id", "CASE_MATRIX_MODEL_DERIVED"),
        ("matrix_version", 2),
        ("matrix_kind", "BILATERAL_FROZEN"),
        ("generation_ref", {"actor_role": "USER"}),
        ("parent_ref", {"matrix_id": "CASE_MATRIX_PARENT"}),
        ("party_map", {"initiator_role": "USER", "respondent_role": "MERCHANT"}),
        ("fact_indexes", {"core_fact_ids": ["FACT_DAMAGE"]}),
        ("schema_version", "case_fact_matrix.v2"),
    ],
)
def test_dossier_patch_recursively_rejects_matrix_authority_metadata(
    field,
    value,
) -> None:
    cognition = _cognition(_unilateral_patch())
    cognition["matrix_patch"] = None
    cognition["dossier_patch"]["case_story"] = {"nested": {field: value}}

    with pytest.raises(ValidationError, match="dossier matrix authority field"):
        IntakeCognitionDraft.model_validate(cognition)


def test_dossier_patch_keeps_general_fact_and_source_references() -> None:
    cognition = _cognition(_unilateral_patch())
    cognition["matrix_patch"] = None
    cognition["dossier_patch"]["case_story"] = {
        "fact_id": "FACT_DAMAGE",
        "source_refs": ["MESSAGE_P4_USER_1"],
        "content_hash": "a" * 64,
    }

    parsed = IntakeCognitionDraft.model_validate(cognition)

    assert parsed.dossier_patch.case_story == cognition["dossier_patch"]["case_story"]


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


def test_respondent_unlock_rejects_stale_embedded_matrix_hash(
    bindings,
    version_pins,
    snapshot,
) -> None:
    respondent_bindings, respondent_snapshot = _respondent_snapshot(bindings, snapshot)
    respondent_snapshot["current_dossier"]["case_fact_matrix"]["fact_rows"][0]["fact_target"] = (
        "A stale-hash mutation."
    )
    respondent_snapshot["snapshot_hash"] = canonical_sha256_omitting(
        respondent_snapshot,
        "snapshot_hash",
    )

    with pytest.raises(IntakeGraphContractError, match="INTAKE_MATRIX_CURRENT_INVALID"):
        _import_state(respondent_bindings, version_pins, respondent_snapshot)


@pytest.mark.parametrize(
    "mutation",
    [
        lambda matrix: matrix.update(matrix_id="CASE_MATRIX_SYNTHETIC_1"),
        lambda matrix: matrix.update(matrix_version=2),
        lambda matrix: matrix["generation_ref"].update(latest_source_ref="MESSAGE_UNKNOWN"),
        lambda matrix: matrix["fact_indexes"].update(core_fact_ids=[]),
        lambda matrix: matrix["fact_rows"][0]["positions"]["MERCHANT"].update(
            position_summary="A fabricated respondent position."
        ),
    ],
)
def test_respondent_unlock_rejects_rehashed_non_java_frozen_shape(
    bindings,
    version_pins,
    snapshot,
    mutation,
) -> None:
    respondent_bindings, respondent_snapshot = _respondent_snapshot(bindings, snapshot)
    matrix = respondent_snapshot["current_dossier"]["case_fact_matrix"]
    mutation(matrix)
    matrix["content_hash"] = canonical_sha256_omitting(matrix, "content_hash")
    respondent_snapshot["snapshot_hash"] = canonical_sha256_omitting(
        respondent_snapshot,
        "snapshot_hash",
    )

    with pytest.raises(IntakeGraphContractError, match="INTAKE_MATRIX_CURRENT_INVALID"):
        _import_state(respondent_bindings, version_pins, respondent_snapshot)


def test_respondent_delta_must_carry_every_frozen_fact(
    bindings,
    version_pins,
    snapshot,
) -> None:
    respondent_bindings, respondent_snapshot = _respondent_snapshot(bindings, snapshot)
    matrix = respondent_snapshot["current_dossier"]["case_fact_matrix"]
    second = copy.deepcopy(matrix["fact_rows"][0])
    second.update(
        fact_id="FACT_DELIVERY",
        category="LOGISTICS",
        fact_target="Whether delivery completed at the agreed address.",
        materiality="SUPPORTING",
    )
    second["positions"]["USER"].update(
        position_summary="The initiator disputes successful delivery.",
        asserted_value="not delivered",
    )
    matrix["fact_rows"].append(second)
    matrix["fact_indexes"]["not_computed_fact_ids"].append("FACT_DELIVERY")
    matrix["content_hash"] = canonical_sha256_omitting(matrix, "content_hash")
    respondent_snapshot["snapshot_hash"] = canonical_sha256_omitting(
        respondent_snapshot,
        "snapshot_hash",
    )
    state = _import_state(respondent_bindings, version_pins, respondent_snapshot)

    with pytest.raises(
        IntakeGraphContractError,
        match="INTAKE_MATRIX_FACT_MEMBERSHIP_INVALID",
    ):
        validate_matrix_patch(state, _delta_patch())


def test_respondent_fact_membership_excludes_unilateral_projection(
    bindings,
    version_pins,
    snapshot,
) -> None:
    respondent_bindings, respondent_snapshot = _respondent_snapshot(bindings, snapshot)
    respondent_snapshot["current_dossier"]["unilateral_case_matrix"] = {
        "fact_rows": [
            {
                "fact_id": "FACT_EXTRA",
                "category": "ORDER",
                "fact_target": "A fact absent from the frozen matrix.",
                "materiality": "CORE",
            }
        ]
    }
    respondent_snapshot["snapshot_hash"] = canonical_sha256_omitting(
        respondent_snapshot,
        "snapshot_hash",
    )
    state = _import_state(respondent_bindings, version_pins, respondent_snapshot)
    patch = _delta_patch()
    patch["fact_rows"][0].update(
        fact_key="FACT_EXTRA",
        category="ORDER",
        fact_target="A fact absent from the frozen matrix.",
    )
    patch["summary_source_fact_keys"] = ["FACT_EXTRA"]

    with pytest.raises(IntakeGraphContractError, match="INTAKE_MATRIX_FACT_UNKNOWN"):
        validate_matrix_patch(state, patch)


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


@pytest.mark.parametrize(
    "source_scope",
    ["CURRENT_SOURCE", "PREVIOUS_AND_CURRENT_SOURCE", "PREVIOUS_MATRIX"],
)
def test_delta_rejects_materiality_rebind_for_every_source_scope(
    bindings,
    version_pins,
    snapshot,
    source_scope,
) -> None:
    _, _, state = _respondent_state(bindings, version_pins, snapshot)
    patch = _delta_patch()
    patch["fact_rows"][0].update(
        materiality="SUPPORTING",
        source_scope=source_scope,
    )
    if source_scope == "PREVIOUS_MATRIX":
        patch["fact_rows"][0].update(
            stance="NOT_ADDRESSED",
            position_summary="The respondent has not addressed this fact.",
            asserted_value=None,
        )

    with pytest.raises(IntakeGraphContractError, match="INTAKE_MATRIX_FACT_REBOUND"):
        validate_matrix_patch(state, patch)


def test_delta_allows_new_fact_with_mixed_scope_under_current_source_authority(
    bindings,
    version_pins,
    snapshot,
) -> None:
    _, _, state = _respondent_state(bindings, version_pins, snapshot)
    patch = _delta_patch()
    new_row = {
        "fact_key": "NEW_DISPATCH_CONDITION",
        "category": "PRODUCT_STATE",
        "fact_target": "Whether the item was undamaged when dispatched.",
        "materiality": "SUPPORTING",
        "stance": "CONFIRM",
        "position_summary": "The respondent reports an undamaged dispatch condition.",
        "asserted_value": "undamaged at dispatch",
        "source_scope": "PREVIOUS_AND_CURRENT_SOURCE",
    }
    patch["fact_rows"].append(new_row)
    patch["summary_source_fact_keys"].append(new_row["fact_key"])

    current_source = state.get("last_event_ref") or state.get("initial_snapshot_ref")
    assert isinstance(current_source, str)
    assert "source_refs" not in new_row
    validate_matrix_patch(state, patch)
    assert new_row["source_scope"] == "PREVIOUS_AND_CURRENT_SOURCE"

    missing_current_source = copy.deepcopy(state)
    missing_current_source["last_event_ref"] = None
    missing_current_source["initial_snapshot_ref"] = None
    with pytest.raises(IntakeGraphContractError, match="INTAKE_MATRIX_CURRENT_SOURCE_MISSING"):
        validate_matrix_patch(missing_current_source, patch)


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
