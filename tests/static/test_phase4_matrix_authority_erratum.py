from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path

import jsonschema
import rfc8785


ROOT = Path(__file__).resolve().parents[2]
CONTRACT_ROOT = ROOT / "contracts" / "agent-platform" / "intake" / "v2"
SCHEMA_PATH = CONTRACT_ROOT / "intake-turn-proposal.schema.json"
JAVA_SCHEMA_PATH = (
    ROOT
    / "java-api-service"
    / "src"
    / "main"
    / "resources"
    / "contracts"
    / "agent-platform"
    / "intake"
    / "v2"
    / "intake-turn-proposal.schema.json"
)
VALID_ROOT = CONTRACT_ROOT / "fixtures" / "valid"
INVALID_ROOT = CONTRACT_ROOT / "fixtures" / "invalid"
ERRATUM_PATH = (
    ROOT
    / "docs"
    / "runbooks"
    / "temporal-first"
    / "phase-4-p4.0-matrix-authority-erratum.md"
)
PACK_PATH = (
    ROOT
    / "docs"
    / "runbooks"
    / "temporal-first"
    / "phase-4-p4.0-contract-pack.md"
)
ENTRY_PATH = (
    ROOT
    / "docs"
    / "runbooks"
    / "temporal-first"
    / "phase-4-p4.0-entry-checkpoint.md"
)


def _load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _validator() -> jsonschema.Draft202012Validator:
    schema = _load(SCHEMA_PATH)
    jsonschema.Draft202012Validator.check_schema(schema)
    return jsonschema.Draft202012Validator(schema)


def test_matrix_patch_union_and_dual_schema_copies_are_exact() -> None:
    schema = _load(SCHEMA_PATH)
    assert schema == _load(JAVA_SCHEMA_PATH)
    alternatives = schema["properties"]["matrix_patch"]["oneOf"]
    assert alternatives == [
        {"type": "null"},
        {"$ref": "#/$defs/unilateral_matrix_draft"},
        {"$ref": "#/$defs/case_matrix_delta"},
    ]

    definitions = schema["$defs"]
    unilateral = definitions["unilateral_matrix_draft"]
    assert set(unilateral["properties"]) == {
        "schema_version",
        "fact_rows",
        "summary_source_fact_keys",
    }
    assert unilateral["properties"]["fact_rows"]["maxItems"] == 100

    delta = definitions["case_matrix_delta"]
    assert set(delta["properties"]) == {
        "schema_version",
        "fact_rows",
        "summary_source_fact_keys",
        "respondent_claim",
    }
    assert delta["required"] == [
        "schema_version",
        "fact_rows",
        "summary_source_fact_keys",
    ]
    assert delta["properties"]["fact_rows"]["maxItems"] == 200
    assert delta["properties"]["summary_source_fact_keys"]["maxItems"] == 200
    assert set(definitions["case_fact_delta"]["properties"]) == {
        "fact_key",
        "category",
        "fact_target",
        "materiality",
        "stance",
        "position_summary",
        "asserted_value",
        "source_scope",
        "agreed_statement",
        "conflict_summary",
    }


def test_null_unilateral_and_respondent_delta_fixtures_are_valid_and_hash_bound() -> None:
    validator = _validator()
    for fixture_name in (
        "intake-turn-proposal-valid.json",
        "intake-turn-proposal-unilateral-matrix-valid.json",
        "intake-turn-proposal-respondent-delta-valid.json",
    ):
        proposal = _load(VALID_ROOT / fixture_name)
        validator.validate(proposal)
        expected_hash = proposal.pop("proposal_hash")
        assert hashlib.sha256(rfc8785.dumps(proposal)).hexdigest() == expected_hash
        assert len(rfc8785.dumps({**proposal, "proposal_hash": expected_hash})) <= 65_536


def test_formal_matrix_fields_and_frozen_projection_are_rejected() -> None:
    validator = _validator()
    for fixture_name in (
        "intake-turn-proposal-matrix-derived-authority.json",
        "intake-turn-proposal-matrix-formal-bilateral.json",
        "intake-turn-proposal-dossier-matrix-bypass.json",
    ):
        assert list(validator.iter_errors(_load(INVALID_ROOT / fixture_name))), fixture_name

    unilateral = _load(VALID_ROOT / "intake-turn-proposal-unilateral-matrix-valid.json")
    delta = _load(VALID_ROOT / "intake-turn-proposal-respondent-delta-valid.json")
    for field in (
        "matrix_id",
        "matrix_version",
        "source_binding",
        "source_refs",
        "generation_ref",
        "parent_ref",
        "party_map",
        "content_hash",
        "matrix_kind",
        "fact_indexes",
        "freeze_matrix",
    ):
        candidate = copy.deepcopy(unilateral)
        candidate["matrix_patch"][field] = "MODEL_DERIVED"
        assert list(validator.iter_errors(candidate)), field

    for field in (
        "fact_id",
        "origin",
        "positions",
        "party_alignment",
        "requires_resolution",
        "truth_status",
        "evidence_coverage_status",
    ):
        candidate = copy.deepcopy(delta)
        candidate["matrix_patch"]["fact_rows"][0][field] = "MODEL_DERIVED"
        assert list(validator.iter_errors(candidate)), field


def test_erratum_requires_exact_sha_reauthentication_without_promotion() -> None:
    erratum = ERRATUM_PATH.read_text(encoding="utf-8")
    pack = PACK_PATH.read_text(encoding="utf-8")
    entry = ENTRY_PATH.read_text(encoding="utf-8")

    for document in (erratum, pack, entry):
        assert "case_fact_matrix.delta.v2" in document
        assert "MIG-003" in document
        assert "MIG-004" in document
    assert "BILATERAL_FROZEN" in erratum
    assert "Java remains the sole authority" in erratum
    assert "PENDING_PROMOTION" in erratum
    assert "PENDING_BATCH_0_REAUTHENTICATION" in entry
    assert "implementation_integration: BLOCKED_PENDING_EVIDENCE_COMMIT" in entry
