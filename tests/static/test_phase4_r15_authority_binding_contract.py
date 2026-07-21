from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path

import jsonschema
import pytest
import rfc8785
import yaml


ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "plans/phase-4-r15-authority-binding-contract.yaml"
SCHEMA = ROOT / "plans/phase-4-r15-authority-binding-contract.schema.json"
RUNBOOK = (
    ROOT / "docs/runbooks/temporal-first/phase-4-p4-r1.5-authority-binding-contract.md"
)
PHASE4_PLAN = ROOT / "plans/phase-4-intake-pilot-execution.md"
PHASE4_BATCHES = ROOT / "plans/phase-4-intake-pilot-test-batches.yaml"
INTAKE_CONTRACT_ROOT = ROOT / "contracts/agent-platform/intake/v2"
SAFE_INTEGER_MAX = 9_007_199_254_740_991
R15_STATIC_TEST = "tests/static/test_phase4_r15_authority_binding_contract.py"

PARTY_ROUTE_KEY = [
    "authority_id",
    "epoch_id",
    "tenant_surrogate",
    "case_id",
    "room_type",
    "room_epoch",
    "fencing_token",
    "access_session_id",
    "registration_id",
    "thread_id",
    "actor_id",
    "actor_role",
    "actor_scope_hash",
    "agent_session_id",
]
PAYLOAD_ROUTE_KEY = [
    "payload_authority_id",
    "epoch_id",
    "party_authority_id",
    "access_session_id",
    "registration_id",
    "tenant_surrogate",
    "case_id",
    "room_type",
    "room_epoch",
    "fencing_token",
    "thread_id",
    "actor_scope_hash",
    "agent_session_id",
    "command_id",
]
PUT_RECEIPT_FIELDS = [
    "schema_version",
    "receipt_id",
    "put_idempotency_key",
    "command_id",
    "tenant_surrogate",
    "case_id",
    "registration_id",
    "actor_id",
    "access_session_id",
    "source_kind",
    "artifact_id",
    "payload_schema_version",
    "object_uri",
    "object_version",
    "content_sha256",
    "size_bytes",
    "stored_at_epoch_micros",
    "receipt_hash",
]


def load_manifest() -> dict:
    return yaml.safe_load(MANIFEST.read_text(encoding="utf-8"))


def load_schema() -> dict:
    return json.loads(SCHEMA.read_text(encoding="utf-8"))


def manifest_validator() -> jsonschema.Draft202012Validator:
    return jsonschema.Draft202012Validator(load_schema())


def load_wire_schema(filename: str) -> dict:
    return json.loads((INTAKE_CONTRACT_ROOT / filename).read_text(encoding="utf-8"))


def wire_validator(filename: str) -> jsonschema.Draft202012Validator:
    schema = load_wire_schema(filename)
    jsonschema.Draft202012Validator.check_schema(schema)
    return jsonschema.Draft202012Validator(
        schema,
        format_checker=jsonschema.FormatChecker(),
    )


def load_fixture(kind: str, filename: str) -> dict:
    path = INTAKE_CONTRACT_ROOT / "fixtures" / kind / filename
    return json.loads(path.read_text(encoding="utf-8"))


def foreign_key(table: dict, referenced_table: str) -> dict:
    return next(
        item
        for item in table["foreign_keys"]
        if item["references"]["table"] == referenced_table
    )


def test_r15_manifest_validates_against_its_schema() -> None:
    schema = load_schema()
    jsonschema.Draft202012Validator.check_schema(schema)
    manifest_validator().validate(load_manifest())


@pytest.mark.parametrize(
    ("path", "bad_value", "remove"),
    [
        (("execution_matrix", "DISABLED", "INERT_EXTERNAL_EVENT"), "allowed", False),
        (("selection_rules", "current_writer_mode"), "TEMPORAL", False),
        (
            ("creation_order", "epoch_authority", "transaction_order"),
            [
                "persist_bootstrap_outbox",
                "lock_access_agent_registration_rows",
                "persist_epoch_selection",
                "persist_initiator_party_authority",
                "persist_respondent_party_authority",
                "assert_exact_two_parties",
            ],
            False,
        ),
        (("compatibility", "old_worker_retirement_gate"), None, True),
        (
            ("event_rules", "source_hash_recomputed_from"),
            "case_timeline_event.id",
            False,
        ),
        (
            (
                "source_and_route_authority",
                "registration_agent_session_must_belong_to_access_session",
            ),
            False,
            False,
        ),
    ],
    ids=[
        "disabled-allows-inert",
        "selection-temporal",
        "bootstrap-outbox-visible-first",
        "v1-drain-gate-removed",
        "event-hash-from-event-id",
        "registration-session-membership-disabled",
    ],
)
def test_r15_schema_rejects_security_and_ordering_drift(
    path: tuple[str, ...], bad_value: object, remove: bool
) -> None:
    contract = copy.deepcopy(load_manifest())
    parent = contract
    for key in path[:-1]:
        parent = parent[key]
    if remove:
        del parent[path[-1]]
    else:
        parent[path[-1]] = bad_value

    with pytest.raises(jsonschema.ValidationError):
        manifest_validator().validate(contract)


def test_r15_schema_rejects_payload_source_kind_drift() -> None:
    contract = copy.deepcopy(load_manifest())
    contract["payload_contract"]["source_kind_enum"].append("CLIENT_REFERENCE")

    with pytest.raises(jsonschema.ValidationError):
        jsonschema.Draft202012Validator(load_schema()).validate(contract)


def test_r15_schema_rejects_payload_row_shape_drift() -> None:
    contract = copy.deepcopy(load_manifest())
    row_shape = contract["authority_tables"]["case_intake_command_payload_authority"][
        "source_kind_row_shape"
    ]
    row_shape["SERVER_MINTED_HUMAN_INPUT"]["null_columns"] = []

    with pytest.raises(jsonschema.ValidationError):
        jsonschema.Draft202012Validator(load_schema()).validate(contract)


def test_r15_schema_rejects_profile_formula_drift() -> None:
    contract = copy.deepcopy(load_manifest())
    contract["selection_rules"]["agent_session_profile"][
        "prompt_profile_id_formula"
    ] = "{agent_key}:{actor_role}:{prompt_version}"

    with pytest.raises(jsonschema.ValidationError):
        jsonschema.Draft202012Validator(load_schema()).validate(contract)


def test_r15_schema_rejects_command_fk_without_request_hash() -> None:
    contract = copy.deepcopy(load_manifest())
    command = contract["authority_tables"]["case_intake_command_authority"]
    command_fk = foreign_key(command, "case_command")
    command_fk["columns"].remove("request_hash")
    command_fk["references"]["columns"].remove("request_hash")

    with pytest.raises(jsonschema.ValidationError):
        jsonschema.Draft202012Validator(load_schema()).validate(contract)


@pytest.mark.parametrize(
    ("schema_filename", "fixture_filename"),
    [
        (
            "intake-human-input-command.schema.json",
            "intake-human-input-command-valid.json",
        ),
        (
            "intake-branch-command.schema.json",
            "intake-branch-command-valid.json",
        ),
        (
            "intake-branch-command.schema.json",
            "intake-branch-command-cancel-empty-valid.json",
        ),
        (
            "intake-command-payload-put-receipt.schema.json",
            "intake-command-payload-put-receipt-valid.json",
        ),
    ],
)
def test_r15_formal_wire_schemas_accept_positive_fixtures(
    schema_filename: str, fixture_filename: str
) -> None:
    wire_validator(schema_filename).validate(load_fixture("valid", fixture_filename))


@pytest.mark.parametrize(
    ("schema_filename", "fixture_filename"),
    [
        (
            "intake-human-input-command.schema.json",
            "intake-human-input-command-extra-authority.json",
        ),
        (
            "intake-branch-command.schema.json",
            "intake-branch-command-respondent-cancel.json",
        ),
        (
            "intake-command-payload-put-receipt.schema.json",
            "intake-command-payload-put-receipt-branch-oversize.json",
        ),
        (
            "intake-command-payload-put-receipt.schema.json",
            "intake-command-payload-put-receipt-unsafe-epoch-micros.json",
        ),
    ],
)
def test_r15_formal_wire_schemas_reject_negative_fixtures(
    schema_filename: str, fixture_filename: str
) -> None:
    with pytest.raises(jsonschema.ValidationError):
        wire_validator(schema_filename).validate(
            load_fixture("invalid", fixture_filename)
        )


def test_r15_put_receipt_schema_rejects_invalid_object_uri() -> None:
    receipt = load_fixture("valid", "intake-command-payload-put-receipt-valid.json")
    receipt["object_uri"] = "not an immutable object URI"

    with pytest.raises(jsonschema.ValidationError):
        wire_validator("intake-command-payload-put-receipt.schema.json").validate(
            receipt
        )


def test_r15_wire_schema_integer_fields_are_jcs_safe() -> None:
    expected_integer_paths = {
        "intake-human-input-command.schema.json": {
            ("properties", "room_epoch"),
            ("properties", "occurred_at_epoch_micros"),
        },
        "intake-branch-command.schema.json": set(),
        "intake-command-payload-put-receipt.schema.json": {
            ("properties", "size_bytes"),
            ("properties", "stored_at_epoch_micros"),
        },
    }

    for schema_filename, expected_paths in expected_integer_paths.items():
        pending: list[tuple[tuple[str, ...], object]] = [
            ((), load_wire_schema(schema_filename))
        ]
        integer_nodes: dict[tuple[str, ...], dict] = {}
        while pending:
            path, node = pending.pop()
            if isinstance(node, dict):
                if node.get("type") == "integer":
                    integer_nodes[path] = node
                pending.extend(((*path, key), value) for key, value in node.items())
            elif isinstance(node, list):
                pending.extend(
                    ((*path, str(index)), value) for index, value in enumerate(node)
                )

        assert set(integer_nodes) == expected_paths
        for node in integer_nodes.values():
            assert node["minimum"] >= -SAFE_INTEGER_MAX
            assert node["maximum"] <= SAFE_INTEGER_MAX


def test_r15_formal_schema_manifest_links_are_exact() -> None:
    formal_schemas = load_manifest()["payload_contract"]["formal_schemas"]
    assert formal_schemas == {
        "intake-human-input-command.v1": {
            "file": (
                "contracts/agent-platform/intake/v2/"
                "intake-human-input-command.schema.json"
            ),
            "draft": "DRAFT_2020_12",
            "additional_properties": False,
            "maximum_bytes": 32768,
        },
        "intake-branch-command.v1": {
            "file": (
                "contracts/agent-platform/intake/v2/intake-branch-command.schema.json"
            ),
            "draft": "DRAFT_2020_12",
            "additional_properties": False,
            "maximum_bytes": 16384,
        },
        "intake-command-payload-put-receipt.v1": {
            "file": (
                "contracts/agent-platform/intake/v2/"
                "intake-command-payload-put-receipt.schema.json"
            ),
            "draft": "DRAFT_2020_12",
            "additional_properties": False,
            "maximum_bytes": 16384,
            "self_hash": "receipt_hash",
        },
    }
    for contract in formal_schemas.values():
        schema_path = ROOT / contract["file"]
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
        assert schema["$schema"] == "https://json-schema.org/draft/2020-12/schema"
        assert schema["additionalProperties"] is contract["additional_properties"]
        assert schema["x-max-encoded-bytes"] == contract["maximum_bytes"]


def test_r15_contract_is_frozen_and_keeps_all_runtime_gates_closed() -> None:
    contract = load_manifest()

    assert contract["schema_version"] == "phase-4-r15-authority-binding-contract.v1"
    assert contract["contract_id"] == "P4-R1.5"
    assert contract["status"] == "FROZEN_FOR_IMPLEMENTATION"
    gate = contract["runtime_gate"]
    assert gate["allowed_modes"] == ["DISABLED", "SIGNED_SYNTHETIC_SHADOW"]
    assert gate["real_case_shadow_allowed"] is False
    assert gate["temporal_intake_allocation_allowed"] is False
    assert gate["formal_sink_allowed"] is False
    assert gate["required_defaults"] == {
        "graph_runtime": "DISABLED",
        "new_epoch_mode": "LEGACY",
        "non_legacy_epoch_allocation_enabled": False,
        "temporal_writer_enabled": False,
    }
    assert contract["execution_matrix"]["SIGNED_SYNTHETIC_SHADOW"] == {
        "INERT_EXTERNAL_EVENT": "allowed",
        "ACTIVITY_ORCHESTRATED": "forbidden_until_P4_E1",
    }
    assert all(
        value.startswith("forbidden")
        for value in contract["execution_matrix"]["TEMPORAL"].values()
    )


def test_r15_migration_identity_preserves_phase6_and_outcome_reservations() -> None:
    assert load_manifest()["migration_contract"] == {
        "file": "V043_1__intake_authority_bindings.sql",
        "predecessor": "V043__intake_graph_bindings.sql",
        "mode": "EXPAND_ONLY",
        "reserved_versions_untouched": {
            "V044": "phase_6_hearing_temporal_projection",
            "V045": "outcome_execution_compensation",
        },
        "implementation_present_in_this_gate": False,
    }


def test_r15_all_four_table_column_maps_are_exact_and_complete() -> None:
    tables = load_manifest()["authority_tables"]
    column_maps = {name: table["columns"] for name, table in tables.items()}

    assert set(column_maps) == {
        "case_intake_epoch_selection_binding",
        "case_intake_epoch_party_authority",
        "case_intake_command_payload_authority",
        "case_intake_command_authority",
    }
    assert all(
        set(column_contract)
        == {
            "sql_type",
            "length",
            "nullable",
            "default",
            "checks",
        }
        for columns in column_maps.values()
        for column_contract in columns.values()
    )
    canonical_hash = hashlib.sha256(rfc8785.dumps(column_maps)).hexdigest()
    assert (
        canonical_hash
        == "689081c325608a810eee87083f5376b7bcdaaed784aaca45d5b7bd6858e12ab7"
    )


def test_r15_long_object_uri_is_not_in_any_primary_unique_or_candidate_key() -> None:
    for table_name, table in load_manifest()["authority_tables"].items():
        keys = [table["primary_key"], *table["unique_keys"], *table["candidate_keys"]]
        for key in keys:
            assert "object_uri" not in key, (
                f"object_uri is indexed by {table_name}: {key}"
            )
            for column_name in key:
                assert table["columns"][column_name]["length"] != 1024


def test_r15_selection_relation_freezes_exact_epoch_key_and_profile_pins() -> None:
    tables = load_manifest()["authority_tables"]
    assert set(tables) == {
        "case_intake_epoch_selection_binding",
        "case_intake_epoch_party_authority",
        "case_intake_command_payload_authority",
        "case_intake_command_authority",
    }
    assert all(table["immutable"] is True for table in tables.values())

    selection = tables["case_intake_epoch_selection_binding"]
    exact_epoch = [
        "epoch_id",
        "tenant_surrogate",
        "case_id",
        "room_type",
        "room_epoch",
        "fencing_token",
    ]
    assert selection["primary_key"] == ["epoch_id"]
    assert selection["unique_keys"] == [exact_epoch]
    epoch_fk = foreign_key(selection, "case_room_epoch")
    assert epoch_fk["columns"] == exact_epoch
    assert epoch_fk["references"]["columns"] == [
        "id",
        "tenant_surrogate",
        "case_id",
        "room_type",
        "room_epoch",
        "fencing_token",
    ]
    assert epoch_fk["references"]["candidate_key_added_by_V043_1"] is True
    assert {
        "selection_hash",
        "prompt_version",
        "model_profile_id",
        "agent_key",
        "agent_session_profile_version",
        "memory_policy_id",
    } <= set(selection["required_pins"])


def test_r15_party_authority_directly_binds_exact_access_session_scope() -> None:
    party = load_manifest()["authority_tables"]["case_intake_epoch_party_authority"]
    access_fk = foreign_key(party, "case_access_session")

    assert access_fk == {
        "columns": [
            "access_session_id",
            "session_tenant_id",
            "session_case_id",
            "actor_id",
            "actor_role",
            "permission_level",
        ],
        "references": {
            "table": "case_access_session",
            "columns": [
                "id",
                "tenant_id",
                "case_id",
                "actor_id",
                "actor_role",
                "permission_level",
            ],
            "candidate_key_added_by_V043_1": True,
        },
    }
    assert party["scope_checks"] == {
        "party_enum": ["INITIATOR", "RESPONDENT"],
        "session_tenant_id_equals_tenant_surrogate": True,
        "session_case_id_equals_case_id": True,
        "room_type": "INTAKE",
        "permission_level_by_actor_role": {
            "USER": "PARTY_USER",
            "MERCHANT": "PARTY_MERCHANT",
        },
    }


def test_r15_party_authority_binds_exact_agent_session_scope_and_profiles() -> None:
    party = load_manifest()["authority_tables"]["case_intake_epoch_party_authority"]
    session_fk = foreign_key(party, "agent_conversation_session")

    assert session_fk["columns"] == [
        "agent_session_id",
        "session_tenant_id",
        "session_case_id",
        "room_type",
        "access_session_id",
        "actor_id",
        "actor_role",
        "agent_key",
        "prompt_profile_id",
        "memory_policy_id",
    ]
    assert session_fk["references"] == {
        "table": "agent_conversation_session",
        "columns": [
            "id",
            "tenant_id",
            "case_id",
            "room_type",
            "access_session_id",
            "actor_id",
            "actor_role",
            "agent_key",
            "prompt_profile_id",
            "memory_policy_id",
        ],
        "candidate_key_added_by_V043_1": True,
    }
    assert {
        "session_tenant_id",
        "session_case_id",
        "room_type",
        "access_session_id",
        "actor_id",
        "actor_role",
        "agent_key",
        "prompt_version",
        "prompt_profile_id",
        "agent_session_profile_version",
        "memory_policy_id",
    } <= set(party["required_pins"])


def test_r15_party_authority_binds_exact_epoch_and_registration() -> None:
    party = load_manifest()["authority_tables"]["case_intake_epoch_party_authority"]
    assert party["primary_key"] == ["authority_id"]
    assert party["unique_keys"] == [["epoch_id", "party"], PARTY_ROUTE_KEY]

    epoch_fk = foreign_key(party, "case_intake_epoch_selection_binding")
    assert epoch_fk["columns"] == [
        "epoch_id",
        "tenant_surrogate",
        "case_id",
        "room_type",
        "room_epoch",
        "fencing_token",
    ]
    assert epoch_fk["columns"] == epoch_fk["references"]["columns"]

    registration_fk = foreign_key(party, "case_intake_graph_thread_binding")
    assert registration_fk["references"]["candidate_key_added_by_V043_1"] is True
    assert registration_fk["columns"] == registration_fk["references"]["columns"]
    assert {
        "registration_id",
        "tenant_surrogate",
        "case_id",
        "room_epoch",
        "thread_id",
        "actor_id",
        "actor_role",
        "agent_session_id",
        "registration_hash",
    } <= set(registration_fk["columns"])


def test_r15_active_status_is_transactional_and_never_a_foreign_key_pin() -> None:
    party = load_manifest()["authority_tables"]["case_intake_epoch_party_authority"]
    assert party["transactional_status_checks"] == {
        "epoch_binding_transaction": {
            "case_access_session": "ACTIVE",
            "agent_conversation_session": "ACTIVE",
            "case_intake_graph_thread_binding": "REGISTERED",
        },
        "acceptance_transaction": {
            "case_access_session": "ACTIVE",
            "agent_conversation_session": "ACTIVE",
            "case_intake_graph_thread_binding": "REGISTERED",
        },
        "start_read_transaction": {
            "case_access_session": "ACTIVE",
            "agent_conversation_session": "ACTIVE",
            "case_intake_graph_thread_binding": "REGISTERED",
        },
        "status_columns_in_foreign_keys": False,
        "reason": "ACTIVE_is_mutable_and_must_not_be_part_of_an_immutable_candidate_key",
    }
    fk_columns = {column for fk in party["foreign_keys"] for column in fk["columns"]}
    referenced_columns = {
        column for fk in party["foreign_keys"] for column in fk["references"]["columns"]
    }
    assert "status" not in fk_columns | referenced_columns


def test_r15_epoch_binding_asserts_both_parties_before_bootstrap_delivery() -> None:
    party = load_manifest()["authority_tables"]["case_intake_epoch_party_authority"]
    assert party["bootstrap_cardinality_assertion"] == {
        "required_parties": ["INITIATOR", "RESPONDENT"],
        "exact_row_count": 2,
        "asserted_in": "epoch_binding_transaction",
        "unique_epoch_party_semantics": "at_most_one_row_per_party_only",
        "bootstrap_outbox_deliverable_after_assertion_only": True,
    }


def test_r15_agent_session_profile_uses_exact_versioned_hash_registry() -> None:
    profile = load_manifest()["selection_rules"]["agent_session_profile"]
    assert profile == {
        "agent_key": "DISPUTE_INTAKE_OFFICER",
        "agent_session_profile_version": "agent-session-profile.v1",
        "prompt_profile_id_column": "agent_conversation_session.prompt_profile_id",
        "prompt_profile_id_formula": (
            "asp.v1.{lowercase_sha256_of_rfc8785_utf8_canonical_hash_input}"
        ),
        "prompt_profile_id_prefix": "asp.v1.",
        "prompt_profile_id_encoded_length": 71,
        "prompt_profile_id_storage_length": 128,
        "canonicalization": "RFC_8785",
        "canonical_encoding": "UTF_8",
        "hash": "SHA_256",
        "canonical_hash_input": [
            "agent_key",
            "actor_role",
            "prompt_version",
            "agent_session_profile_version",
        ],
        "registry": {
            "authority": "JAVA_OWNED_IMMUTABLE_VERSIONED_REGISTRY",
            "lookup": "exact_prompt_profile_id_and_canonical_hash_input",
            "mutable_default_forbidden": True,
        },
        "memory_policy_id": "GRAPH_PRIVATE_NO_MEMORY_FRAME_V1",
        "registration_prompt_version_must_equal_selection": True,
        "registration_model_profile_must_equal_selection": True,
    }


@pytest.mark.parametrize("actor_role", ["USER", "MERCHANT"])
def test_r15_agent_session_profile_hash_id_fits_varchar_128_at_boundaries(
    actor_role: str,
) -> None:
    profile = load_manifest()["selection_rules"]["agent_session_profile"]
    canonical_input = {
        "agent_key": "A" * 128,
        "actor_role": actor_role,
        "prompt_version": "P" * 128,
        "agent_session_profile_version": profile["agent_session_profile_version"],
    }
    canonical_bytes = json.dumps(
        canonical_input, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    profile_id = (
        profile["prompt_profile_id_prefix"]
        + hashlib.sha256(canonical_bytes).hexdigest()
    )

    assert len(profile_id) == profile["prompt_profile_id_encoded_length"] == 71
    assert len(profile_id) < profile["prompt_profile_id_storage_length"] == 128
    assert profile_id.startswith("asp.v1.")


def test_r15_payload_source_kind_and_command_schema_matrix_are_closed() -> None:
    payload = load_manifest()["payload_contract"]
    assert payload["source_kind_enum"] == [
        "EXISTING_PRIVATE_EVENT",
        "SERVER_MINTED_HUMAN_INPUT",
        "SERVER_CANONICAL_BRANCH",
    ]
    assert payload["command_schema_matrix"] == [
        {
            "command_type": "INTAKE_MESSAGE",
            "source_kind": "EXISTING_PRIVATE_EVENT",
            "schema_version": "intake-turn-event.v2",
            "maximum_bytes": 32768,
            "execution_disposition": "INERT_EXTERNAL_EVENT",
            "current_gate": "allowed_only_for_signed_synthetic_shadow",
        },
        {
            "command_type": "INTAKE_MESSAGE",
            "source_kind": "SERVER_MINTED_HUMAN_INPUT",
            "schema_version": "intake-human-input-command.v1",
            "maximum_bytes": 32768,
            "execution_disposition": "ACTIVITY_ORCHESTRATED",
            "current_gate": "forbidden_until_P4_E1",
        },
        {
            "command_type": "INTAKE_CONFIRM",
            "source_kind": "SERVER_CANONICAL_BRANCH",
            "schema_version": "intake-branch-command.v1",
            "maximum_bytes": 16384,
            "execution_disposition": "ACTIVITY_ORCHESTRATED",
            "current_gate": "forbidden_until_P4_E1",
        },
        {
            "command_type": "INTAKE_CANCEL",
            "source_kind": "SERVER_CANONICAL_BRANCH",
            "schema_version": "intake-branch-command.v1",
            "maximum_bytes": 16384,
            "execution_disposition": "ACTIVITY_ORCHESTRATED",
            "current_gate": "forbidden_until_P4_E1",
        },
    ]


def test_r15_payload_source_kind_row_shapes_are_database_checked() -> None:
    shape = load_manifest()["authority_tables"][
        "case_intake_command_payload_authority"
    ]["source_kind_row_shape"]
    assert shape["database_check_constraint_required"] is True
    assert shape["common_non_null_columns"] == [
        "payload_authority_id",
        "command_id",
        "epoch_id",
        "party_authority_id",
        "access_session_id",
        "registration_id",
        "tenant_surrogate",
        "case_id",
        "room_type",
        "room_epoch",
        "fencing_token",
        "thread_id",
        "actor_id",
        "actor_role",
        "actor_scope_hash",
        "agent_session_id",
        "source_kind",
        "artifact_id",
        "schema_version",
        "object_uri",
        "object_version",
        "content_sha256",
        "size_bytes",
    ]
    existing = shape["EXISTING_PRIVATE_EVENT"]
    assert existing["required_values"] == {
        "source_kind": "EXISTING_PRIVATE_EVENT",
        "schema_version": "intake-turn-event.v2",
        "size_bytes_minimum": 1,
        "size_bytes_maximum": 32768,
    }
    assert existing["event_composite_non_null_columns"] == [
        "existing_event_binding_id",
        "registration_id",
    ]
    receipt_columns = [
        "put_receipt_schema_version",
        "put_receipt_id",
        "put_idempotency_key",
        "put_receipt_stored_at_epoch_micros",
        "put_receipt_hash",
    ]
    assert existing["null_columns"] == receipt_columns
    assert shape["SERVER_MINTED_HUMAN_INPUT"] == {
        "required_values": {
            "source_kind": "SERVER_MINTED_HUMAN_INPUT",
            "schema_version": "intake-human-input-command.v1",
            "put_receipt_schema_version": "intake-command-payload-put-receipt.v1",
            "size_bytes_minimum": 1,
            "size_bytes_maximum": 32768,
        },
        "non_null_columns": receipt_columns,
        "null_columns": ["existing_event_binding_id"],
    }
    assert shape["SERVER_CANONICAL_BRANCH"] == {
        "required_values": {
            "source_kind": "SERVER_CANONICAL_BRANCH",
            "schema_version": "intake-branch-command.v1",
            "put_receipt_schema_version": "intake-command-payload-put-receipt.v1",
            "size_bytes_minimum": 1,
            "size_bytes_maximum": 16384,
        },
        "non_null_columns": receipt_columns,
        "null_columns": ["existing_event_binding_id"],
    }


def test_r15_existing_private_event_has_exact_v043_binding_and_route_fk() -> None:
    contract = load_manifest()
    payload = contract["authority_tables"]["case_intake_command_payload_authority"]
    event_fk = foreign_key(payload, "case_intake_snapshot_binding")

    assert event_fk["columns"] == ["existing_event_binding_id", "registration_id"]
    assert event_fk["references"] == {
        "table": "case_intake_snapshot_binding",
        "columns": ["binding_id", "thread_registration_id"],
        "candidate_key_added_by_V043_1": True,
    }
    event = contract["payload_contract"]["existing_private_event"]
    assert event["required_v043_table"] == "case_intake_snapshot_binding"
    assert event["required_binding_type"] == "EVENT"
    assert event["required_schema_version"] == "intake-turn-event.v2"
    assert event["composite_foreign_key_required"] is True
    assert event["composite_foreign_key_columns"] == [
        "existing_event_binding_id",
        "registration_id",
    ]
    assert event["V043_candidate_key"] == ["binding_id", "thread_registration_id"]
    assert event["artifact_and_route_verification"] == (
        "V043_1_constraint_trigger_exact_column_comparison"
    )
    assert event["exact_route_registration_required"] is True


def test_r15_existing_event_constraint_trigger_compares_every_artifact_and_route_pin() -> (
    None
):
    payload = load_manifest()["authority_tables"][
        "case_intake_command_payload_authority"
    ]
    assertion = payload["existing_private_event_assertion"]

    assert assertion["mechanism"] == "V043_1_DEFERRABLE_CONSTRAINT_TRIGGER"
    assert assertion["timing"] == "AFTER_INSERT"
    assert assertion["deferrable"] is True
    assert assertion["initially"] == "IMMEDIATE"
    assert assertion["compact_foreign_key"] == [
        "existing_event_binding_id",
        "registration_id",
    ]
    assert assertion["v043_candidate_key"] == [
        "binding_id",
        "thread_registration_id",
    ]
    assert assertion["v043_required_constants"] == {
        "binding_type": "EVENT",
        "schema_version": "intake-turn-event.v2",
        "room_type": "INTAKE",
        "visibility": "PRIVATE",
        "initialization_marker": False,
    }
    assert assertion["exact_column_comparison"] == {
        "tenant_surrogate": "tenant_surrogate",
        "case_id": "case_id",
        "room_type": "room_type",
        "room_epoch": "room_epoch",
        "fencing_token": "fencing_token",
        "thread_id": "thread_id",
        "actor_scope_hash": "actor_scope_hash",
        "agent_session_id": "agent_session_id",
        "actor_audience": "actor_role",
        "schema_version": "schema_version",
        "artifact_id": "artifact_id",
        "object_uri": "object_uri",
        "object_version": "object_version",
        "content_sha256": "content_sha256",
        "size_bytes": "size_bytes",
    }
    assert assertion["functional_dependencies"] == [
        "payload_authority_id_determines_immutable_payload_row",
        "party_authority_id_determines_exact_registration_route",
        "binding_id_and_thread_registration_id_determine_immutable_V043_event_row",
    ]
    assert assertion["concurrency_safety"] == [
        "compact_FK_waits_for_referenced_V043_insert_commit",
        "V043_binding_is_update_delete_truncate_immutable",
        "constraint_trigger_reads_one_visible_row_by_compact_candidate_key",
        "payload_authority_is_insert_only",
    ]
    assert assertion["indexed_columns_forbidden"] == ["object_uri"]


def test_r15_server_minted_human_input_is_put_before_db_with_exact_receipt() -> None:
    human = load_manifest()["payload_contract"]["server_minted_human_input"]
    assert human["authority"] == "JAVA_SERVER"
    assert human["canonicalization"] == "RFC_8785"
    assert human["canonical_encoding"] == "UTF_8"
    assert human["maximum_bytes"] == 32768
    assert human["immutable_put_order"] == "before_database_transaction"
    assert human["receipt_schema_version"] == ("intake-command-payload-put-receipt.v1")
    assert human["provenance_receipt_required"] is True
    assert human["provenance_receipt_exact_fields"] == PUT_RECEIPT_FIELDS
    assert human["database_failure_result"] == "no_deliverable_command_or_outbox"


@pytest.mark.parametrize(
    "source", ["server_minted_human_input", "server_canonical_branch"]
)
def test_r15_server_minted_payload_orphan_cleanup_is_idempotent(
    source: str,
) -> None:
    cleanup = load_manifest()["payload_contract"][source]["orphan_cleanup"]
    assert cleanup == {
        "idempotency_key": (
            "intake.payload.orphan-cleanup:{tenant_surrogate}:"
            "{artifact_id}:{object_version}"
        ),
        "eligibility": (
            "terminally_abandoned_put_and_exact_object_version_has_no_committed_"
            "payload_authority"
        ),
        "serialized_with_put_key": True,
        "acceptance_retry_reuses_receipt_before_abandonment": True,
        "retry_after_terminal_abandonment": "forbidden",
        "cleanup_tombstone": (
            "durable_put_key_content_hash_object_version_and_terminal_reason"
        ),
        "tombstone_reuse_policy": (
            "same_put_key_is_terminal_and_cannot_create_another_object"
        ),
        "repeat_result": "ALREADY_ABSENT_OR_DELETED",
        "committed_authority_object_deletion_forbidden": True,
    }


@pytest.mark.parametrize(
    "source", ["server_minted_human_input", "server_canonical_branch"]
)
def test_r15_server_minted_payload_put_retry_key_is_deterministic(
    source: str,
) -> None:
    protocol = load_manifest()["payload_contract"][source]
    assert protocol["put_idempotency_key_formula"] == (
        "iput.v1.{lowercase_sha256_of_rfc8785_utf8_put_key_input}"
    )
    assert protocol["put_idempotency_key_input"] == [
        "tenant_surrogate",
        "case_id",
        "command_id",
        "source_kind",
    ]
    assert protocol["put_idempotency_key_encoded_length"] == 72
    assert protocol["put_key_content_hash_binding"] == (
        "content_sha256_first_write_wins"
    )
    assert protocol["same_key_same_hash"] == (
        "return_same_immutable_object_version_and_receipt"
    )
    assert protocol["same_key_different_hash"] == "conflict_without_new_object"
    assert protocol["authority_receipt_snapshot"] == ("required_and_rfc8785_recomputed")


def test_r15_server_canonical_branch_is_closed_and_bounded() -> None:
    branch = load_manifest()["payload_contract"]["server_canonical_branch"]
    assert branch["authority"] == "JAVA_SERVER"
    assert branch["raw_client_payload_ref_allowed"] is False
    assert branch["schema_version"] == "intake-branch-command.v1"
    assert branch["canonicalization"] == "RFC_8785"
    assert branch["maximum_bytes"] == 16384
    assert branch["immutable_put_order"] == "before_database_transaction"
    assert branch["receipt_schema_version"] == ("intake-command-payload-put-receipt.v1")
    assert branch["provenance_receipt_required"] is True
    assert branch["provenance_receipt_exact_fields"] == PUT_RECEIPT_FIELDS
    assert branch["database_failure_result"] == "no_deliverable_command_or_outbox"
    assert branch["additional_properties"] is False
    assert branch["bounded_fields"] == {
        "command_id": 128,
        "dispute_type": 128,
        "confirmation_note": 2000,
        "cancellation_reason": 2000,
    }
    assert branch["operation_matrix"] == {
        "INTAKE_CONFIRM_INITIATOR": ["INITIATOR_ACCEPT", "INITIATOR_REJECT"],
        "INTAKE_CONFIRM_RESPONDENT": ["RESPONDENT_CONFIRM"],
        "INTAKE_CANCEL_INITIATOR": ["CANCEL"],
    }
    assert branch["respondent_cancel_allowed"] is False


def test_r15_put_receipt_snapshot_is_persisted_and_rfc8785_recomputed() -> None:
    snapshot = load_manifest()["payload_contract"]["put_receipt_snapshot"]
    assert snapshot["applies_to"] == [
        "SERVER_MINTED_HUMAN_INPUT",
        "SERVER_CANONICAL_BRANCH",
    ]
    assert snapshot["authority_columns"] == [
        "put_receipt_schema_version",
        "put_receipt_id",
        "put_idempotency_key",
        "command_id",
        "put_receipt_stored_at_epoch_micros",
        "put_receipt_hash",
    ]
    assert snapshot["authority_column_constraints"] == {
        "put_receipt_schema_version": "intake-command-payload-put-receipt.v1",
        "put_receipt_id": "identifier_max_128",
        "put_idempotency_key": "iput_v1_plus_64_lowercase_hex",
        "command_id": "identifier_max_128",
        "put_receipt_stored_at_epoch_micros": ("integer_0_to_9007199254740991"),
        "put_receipt_hash": "lowercase_sha256",
    }
    assert snapshot["exact_field_mapping"] == {
        "schema_version": "put_receipt_schema_version",
        "receipt_id": "put_receipt_id",
        "put_idempotency_key": "put_idempotency_key",
        "command_id": "command_id",
        "tenant_surrogate": "tenant_surrogate",
        "case_id": "case_id",
        "registration_id": "registration_id",
        "actor_id": "actor_id",
        "access_session_id": "access_session_id",
        "source_kind": "source_kind",
        "artifact_id": "artifact_id",
        "payload_schema_version": "schema_version",
        "object_uri": "object_uri",
        "object_version": "object_version",
        "content_sha256": "content_sha256",
        "size_bytes": "size_bytes",
        "stored_at_epoch_micros": "put_receipt_stored_at_epoch_micros",
        "receipt_hash": "put_receipt_hash",
    }
    assert snapshot["receipt_hash_input_fields"] == PUT_RECEIPT_FIELDS[:-1]
    assert snapshot["receipt_hash_recomputation"] == (
        "SHA_256_of_RFC_8785_UTF_8_authority_snapshot"
    )
    assert snapshot["persisted_snapshot_immutable"] is True


def test_r15_fixture_jcs_hash_size_put_key_and_receipt_self_hash_are_linked() -> None:
    human = load_fixture("valid", "intake-human-input-command-valid.json")
    receipt = load_fixture("valid", "intake-command-payload-put-receipt-valid.json")

    canonical_payload = rfc8785.dumps(human)
    assert len(canonical_payload) == receipt["size_bytes"] == 455
    assert hashlib.sha256(canonical_payload).hexdigest() == receipt["content_sha256"]
    assert receipt["content_sha256"] == (
        "8ffcd36dc1bdd92adbb24300ec62e225cceb1d0b655ad4937454626f518acdba"
    )
    assert receipt["command_id"] == human["command_id"]
    assert receipt["payload_schema_version"] == human["schema_version"]

    put_key_input = {
        key: receipt[key]
        for key in ["tenant_surrogate", "case_id", "command_id", "source_kind"]
    }
    computed_put_key = (
        "iput.v1." + hashlib.sha256(rfc8785.dumps(put_key_input)).hexdigest()
    )
    assert computed_put_key == receipt["put_idempotency_key"]
    assert computed_put_key == (
        "iput.v1.ed1a01a9d77a5b404ec6f60116fe76247e1f761455c9ee2d355182a6c9955cc4"
    )

    receipt_hash_input = {
        key: receipt[key] for key in PUT_RECEIPT_FIELDS if key != "receipt_hash"
    }
    computed_receipt_hash = hashlib.sha256(
        rfc8785.dumps(receipt_hash_input)
    ).hexdigest()
    assert computed_receipt_hash == receipt["receipt_hash"]
    assert computed_receipt_hash == (
        "230ed06c2b9a30ce5f5bebb4679cc709c53e3dd3e16224749bd06b8b1c6b72db"
    )


def test_r15_empty_cancellation_reason_preserves_current_cancel_contract() -> None:
    cancel = load_fixture("valid", "intake-branch-command-cancel-empty-valid.json")
    assert cancel["cancellation_reason"] == ""
    wire_validator("intake-branch-command.schema.json").validate(cancel)


def test_r15_case_command_ref_compares_only_its_four_wire_fields() -> None:
    comparison = load_manifest()["payload_contract"]["case_command_ref_payload_ref"]
    assert comparison["exact_field_mapping"] == {
        "schema_version": "schema_version",
        "uri": "object_uri",
        "sha256": "content_sha256",
        "size_bytes": "size_bytes",
    }
    assert comparison["fields_not_present_in_case_command_ref"] == [
        "artifact_id",
        "object_version",
    ]
    assert comparison["artifact_and_object_version_proof"] == {
        "EXISTING_PRIVATE_EVENT": (
            "compact_V043_binding_FK_plus_V043_1_exact_assertion"
        ),
        "SERVER_MINTED_HUMAN_INPUT": "exact_immutable_put_provenance_receipt",
        "SERVER_CANONICAL_BRANCH": (
            "exact_server_canonicalization_and_immutable_put_receipt"
        ),
    }


def test_r15_payload_and_command_relations_are_one_to_one_and_atomic() -> None:
    contract = load_manifest()
    tables = contract["authority_tables"]
    payload = tables["case_intake_command_payload_authority"]
    command = tables["case_intake_command_authority"]

    assert payload["primary_key"] == ["payload_authority_id"]
    assert payload["unique_keys"] == [
        ["tenant_surrogate", "artifact_id"],
        PAYLOAD_ROUTE_KEY,
    ]
    assert command["primary_key"] == ["case_command_id"]
    assert command["unique_keys"] == [
        ["tenant_surrogate", "command_id"],
        [
            "case_command_id",
            "tenant_surrogate",
            "case_id",
            "command_id",
            "request_hash",
        ],
        ["payload_authority_id"],
    ]
    command_fk = foreign_key(command, "case_command")
    assert command_fk == {
        "columns": [
            "case_command_id",
            "tenant_surrogate",
            "case_id",
            "command_id",
            "request_hash",
        ],
        "references": {
            "table": "case_command",
            "columns": [
                "id",
                "tenant_surrogate",
                "case_id",
                "command_id",
                "request_hash",
            ],
            "candidate_key_added_by_V043_1": True,
        },
    }
    assert {"case_command_sequence", "command_type", "request_hash"} <= set(
        command["required_pins"]
    )
    assert (
        foreign_key(command, "case_intake_command_payload_authority")["columns"]
        == PAYLOAD_ROUTE_KEY
    )
    assert (
        command["transaction_boundary"]
        == "same_as_payload_authority_case_command_and_command_outbox"
    )
    assert set(contract["creation_order"]) == {
        "epoch_authority",
        "command_authority",
    }


def test_r15_case_party_and_route_authorities_are_server_owned() -> None:
    contract = load_manifest()

    assert contract["party_resolution"] == {
        "authority_source": "fulfillment_dispute_case",
        "INITIATOR": ["initiator_id", "initiator_role"],
        "RESPONDENT": ["respondent_id", "respondent_role"],
        "exact_actor_id_and_role_match_required": True,
        "exactly_one_party_match_required": True,
        "role_only_mapping_forbidden": True,
        "required_bridge_command_source_fields": [
            "actor_id",
            "actor_role",
            "party",
            "actor_scope_hash",
        ],
        "required_tests": ["user_initiated_case", "merchant_initiated_case"],
    }

    authority = contract["source_and_route_authority"]
    assert authority["human_source_authority"] == (
        "authenticated_actor_plus_active_server_resolved_access_session"
    )
    assert authority["agent_session_source_claimed"] is False
    assert authority["route_target"] == "immutable_epoch_party_authority"
    assert authority["route_target_client_selectable"] is False
    assert authority["payload"]["raw_client_uri_or_hash_is_authority"] is False
    assert authority["payload"]["case_command_payload_ref_must_equal_authority"] is True


def test_r15_creation_order_keeps_authority_before_outbox_visibility() -> None:
    order = load_manifest()["creation_order"]
    assert order == {
        "epoch_authority": {
            "transaction_order": [
                "lock_access_agent_registration_rows",
                "persist_epoch_selection",
                "persist_initiator_party_authority",
                "persist_respondent_party_authority",
                "assert_exact_two_parties",
                "persist_bootstrap_outbox",
            ],
            "bootstrap_outbox_delivery_position": "after_transaction_commit",
        },
        "command_authority": {
            "pre_transaction_order": [
                "canonicalize_and_hash_payload",
                "idempotent_immutable_put_when_server_minted",
                "verify_put_receipt_or_V043_event_binding",
            ],
            "transaction_order": [
                "lock_access_agent_registration_rows",
                "persist_case_command",
                "persist_payload_authority",
                "persist_command_authority",
                "persist_command_outbox",
            ],
            "command_outbox_delivery_position": "after_transaction_commit",
        },
    }


def test_r15_revocation_lock_order_and_conflict_matrix_are_closed() -> None:
    protocol = load_manifest()["revocation_lock_protocol"]
    assert protocol["fixed_row_order"] == [
        "case_access_session",
        "agent_conversation_session",
        "case_intake_graph_thread_binding",
    ]
    assert protocol["intra_table_row_order"] == {
        "case_access_session": "id_ASC",
        "agent_conversation_session": "id_ASC",
        "case_intake_graph_thread_binding": "registration_id_ASC",
    }
    assert protocol["acceptance"] == {
        "lock_mode": "FOR_SHARE",
        "status_checks_after_all_locks": {
            "case_access_session": "ACTIVE",
            "agent_conversation_session": "ACTIVE",
            "case_intake_graph_thread_binding": "REGISTERED",
        },
        "holds_locks_until": "transaction_commit_or_rollback",
    }
    assert protocol["revocation_writers"] == {
        "lock_mode": "FOR_UPDATE",
        "must_use_fixed_row_order": True,
        "update_after_all_locks": True,
        "holds_locks_until": "transaction_commit_or_rollback",
    }
    assert protocol["postgresql_conflict_matrix"] == {
        "FOR_SHARE_conflicts_with": ["FOR_UPDATE", "FOR_NO_KEY_UPDATE"],
        "FOR_UPDATE_conflicts_with": [
            "FOR_SHARE",
            "FOR_KEY_SHARE",
            "FOR_NO_KEY_UPDATE",
            "FOR_UPDATE",
        ],
        "application_revocation_mode": "FOR_UPDATE",
    }
    assert protocol["linearization"] == {
        "winner": "first_lock_owner_to_commit",
        "revoke_commits_first": (
            "acceptance_waits_rechecks_status_and_rejects_without_command_or_outbox"
        ),
        "acceptance_commits_first": (
            "later_revocation_does_not_invalidate_accepted_inert_snapshot"
        ),
        "deadlock_prevention": (
            "every_bootstrap_acceptance_and_revocation_writer_uses_table_and_"
            "intra_table_order"
        ),
    }
    assert protocol["activity_read"] == {
        "isolation": "REPEATABLE_READ",
        "row_locks": "none",
        "accepted_inert_replay_reopens_authorization": False,
    }
    assert protocol["required_race_tests"] == [
        "access_revoke_vs_accept_lock_order",
        "agent_session_revoke_vs_accept_lock_order",
        "registration_retire_vs_accept_lock_order",
        "accept_commit_then_revoke_inert_replay",
        "revoke_commit_then_accept_rejected",
    ]


def test_r15_read_linearization_is_replay_stable_and_fail_closed() -> None:
    read_port = load_manifest()["read_port"]

    assert read_port["transaction"] == {
        "read_only": True,
        "isolation": "REPEATABLE_READ",
        "lock_rows": False,
    }
    assert read_port["candidate_policy"] == "exactly_one_or_fail_closed"
    assert {
        "findFirst",
        "latest_created_at",
        "latest_registered_at",
        "distinct_then_pick",
        "actor_role_only",
        "browser_supplied_thread_or_session",
    } == set(read_port["forbidden_selection"])
    assert read_port["command_acceptance"] == {
        "authenticated_actor_required": True,
        "access_session_resolved_server_side": True,
        "payload_provenance_verified_server_side": True,
        "command_authority_written_with_command_and_outbox": "atomically",
        "replay_requires_existing_payload_and_authority_match": True,
    }
    assert read_port["command_authority_exact_comparison"] == {
        "database_composite_fk_fields": [
            "case_command_id",
            "tenant_surrogate",
            "case_id",
            "command_id",
            "request_hash",
        ],
        "authority_snapshot_fields": [
            "case_command_sequence",
            "command_type",
            "room_type",
            "room_epoch",
            "actor_id",
            "actor_role",
            "payload_authority_id",
            "accepted_room_revision",
            "execution_disposition",
        ],
        "case_command_payload_ref_fields_compared_in_transaction": [
            "payload_schema_version",
            "payload_uri",
            "payload_sha256",
            "payload_size_bytes",
        ],
        "payload_uri_in_composite_btree_key": False,
        "request_hash_semantics": (
            "canonical_case_command_ref_binds_actor_and_payload_ref"
        ),
    }
    assert read_port["linearization"] == {
        "start": "current_registered_registration_and_active_sessions_required",
        "revoke_before_accept": "reject_without_command_or_outbox",
        "accepted_inert_command": (
            "immutable_acceptance_snapshot_survives_later_revocation"
        ),
        "committed_event_replay": (
            "use_committed_receipt_without_reopening_current_authorization"
        ),
        "activity_orchestrated_revocation": (
            "blocked_until_P4_E1_terminal_disposition_protocol"
        ),
    }


def test_r15_event_worker_and_history_boundaries_are_exact() -> None:
    contract = load_manifest()
    event = contract["event_rules"]
    assert event["turn_event"] == {
        "agent_run_ref": "required",
        "graph_execution_ref": "required",
        "graph_thread_must_equal_registration_thread": True,
        "operation_result_hash_semantics": "finalization_receipt_hash",
        "graph_result_hash_source": "committed_manifest_output",
    }
    assert event["branch_event"] == {
        "agent_run_ref": "forbidden",
        "graph_execution_ref": "forbidden",
    }

    assembly = contract["worker_assembly"]
    assert assembly["CASE_CONTROL"] == {
        "workflow_types": ["CaseProcessWorkflowImpl"],
        "required_activities": ["IntakeChildBridgeActivitiesV2Adapter"],
        "bridge_activity_cardinality": "exactly_one",
    }
    assert set(assembly["ROOM_CONTROL"]["required_workflow_types"]) == {
        "RoomControlWorkflowImpl",
        "IntakeRoomWorkflowImpl",
    }
    assert set(assembly["forbidden_runtime_types"]) == {
        "IntakeRoomActivitiesAdapter",
        "IntakeFormalCommitPort",
        "IntakeFormalBranchCommitPort",
        "IntakeGraphResultFinalizer",
    }

    compatibility = contract["compatibility"]
    assert compatibility["version_marker"] == "typed-intake-bridge-authority-v1"
    assert compatibility["v2_activity_names"] == [
        "BindIntakeChildStartV2",
        "BindIntakeChildCommandV2",
        "BindIntakeChildDomainEventV2",
    ]
    assert compatibility["completed_v1_activity_result"] == "replay_from_history"
    assert compatibility["scheduled_uncompleted_v1_activity"] == (
        "remain_on_pinned_old_worker_build_until_drained"
    )
    assert compatibility["ambiguous_v1_backfill"] == "forbidden"
    assert compatibility["missing_v2_authority_binding"] == "fail_closed"


def test_r15_exit_gate_and_phase4_links_cover_every_review_finding() -> None:
    required = set(
        load_manifest()["implementation_gate"]["required_before_P4_D1_or_P4_E1"]
    )
    assert {
        "contract_schema_and_exact_static_tests",
        "table_column_type_length_null_default_check_map_tests",
        "migration_pk_uk_fk_and_immutability_tests",
        "atomic_epoch_authority_before_bootstrap_test",
        "exact_two_party_bootstrap_cardinality_test",
        "atomic_command_payload_authority_and_outbox_test",
        "payload_source_kind_command_schema_matrix_tests",
        "formal_payload_schema_positive_negative_fixture_tests",
        "payload_source_kind_row_shape_check_tests",
        "existing_private_event_exact_V043_fk_test",
        "compact_V043_fk_and_constraint_trigger_exact_comparison_tests",
        "immutable_put_receipt_and_orphan_cleanup_tests",
        "put_receipt_snapshot_recomputation_test",
        "put_receipt_epoch_micros_precision_test",
        "deterministic_put_retry_conflict_and_cleanup_tests",
        "canonical_branch_payload_boundaries",
        "command_payload_authority_one_to_one_test",
        "case_command_ref_four_field_comparison_test",
        "agent_session_profile_hash_boundary_tests",
        "user_and_merchant_initiated_party_tests",
        "payload_cross_session_rejection_test",
        "read_port_unit_and_postgresql_tests",
        "revoke_before_and_after_acceptance_races",
        "revocation_lock_conflict_matrix_race_tests",
        "commit_before_event_delivery_loss_test",
        "repeatable_read_concurrency_test",
        "v1_completed_and_uncompleted_history_compatibility_tests",
        "worker_registration_context_smoke",
        "no_formal_sink_gate",
        "typed_child_replay_tests",
        "independent_review_pass",
    } <= required

    runbook = RUNBOOK.read_text(encoding="utf-8")
    plan = PHASE4_PLAN.read_text(encoding="utf-8")
    assert MANIFEST.name in runbook
    assert SCHEMA.name in runbook
    assert "Mutable `ACTIVE` status" in plan
    assert "same-transaction acceptance/start check" in plan
    assert (
        "CaseCommandRef.payloadRef` contains only schema version, URI, SHA-256"
        in runbook
    )
    assert "UNIQUE(payload_authority_id)" in runbook
    assert RUNBOOK.name in plan
    assert "P4-R1.5 Authority-Binding Gate" in plan


def test_r15_phase4_task_dag_and_batches_enforce_the_gate() -> None:
    schedule = yaml.safe_load(PHASE4_BATCHES.read_text(encoding="utf-8"))
    assert "P4-R1.5" in schedule["agents"]["R"]["tasks"]
    assert schedule["gate"]["authority_binding_gate"] == {
        "gate_id": "P4-R1.5",
        "status": "FROZEN_FOR_IMPLEMENTATION",
        "manifest": "plans/phase-4-r15-authority-binding-contract.yaml",
        "schema": "plans/phase-4-r15-authority-binding-contract.schema.json",
        "static_gate": R15_STATIC_TEST,
        "required_before_tasks": ["P4-R1", "P4-D1", "P4-E1"],
        "runtime_effect": "none",
    }
    for task_name in ["P4-R1", "P4-D1", "P4-E1"]:
        assert "P4-R1.5" in schedule["task_contracts"][task_name]["depends_on"]

    batches = schedule["batches"]
    for batch_name in ["P4-BATCH-1", "P4-BATCH-2"]:
        batch = batches[batch_name]
        assert "P4-R1.5" in batch["requires_tasks"]
        assert R15_STATIC_TEST in batch["static_tests"]
        assert any(
            R15_STATIC_TEST in command["command"]
            for command in batch["source_commands"]
        )

    checkpoint = batches["P4-BATCH-3"]
    assert "P4-R1.5" in checkpoint["requires_tasks"]
    assert "P4_R1_5_authority_binding_gate_pass" in checkpoint["requires"]
    static_command = next(
        command
        for command in checkpoint["source_commands"]
        if command["id"] == "static_phase_4"
    )
    assert R15_STATIC_TEST in static_command["command"]
