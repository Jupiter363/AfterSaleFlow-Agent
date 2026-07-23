from __future__ import annotations

import ast
import hashlib
import re
import subprocess
from pathlib import Path
from typing import Any

import yaml


ROOT = Path(__file__).resolve().parents[2]
MATRIX_PATH = ROOT / "contracts/agent-platform/hearing/v2/compatibility-matrix.yaml"
ADR_PATH = ROOT / "docs/architecture/adr/0015-phase-6-hearing-engineering-exception.md"
CHECKPOINT_PATH = ROOT / "docs/runbooks/temporal-first/phase-5-engineering-checkpoint.md"
PYTHON_SCHEMA_PATH = ROOT / "python-agent-service/app/schemas/hearing_flow.py"
JAVA_STAGE_PATH = (
    ROOT
    / "java-api-service/src/main/java/com/example/dispute/hearing/domain/HearingFlowStage.java"
)
V037_PATH = (
    ROOT
    / "java-api-service/src/main/resources/db/migration/"
    "V037__key_hearing_party_actions_by_participant_id.sql"
)

EXPECTED_STAGES = [
    "COURT_PREPARING",
    "CASE_INTRODUCTION",
    "EVIDENCE_INTRODUCTION",
    "INTAKE_QUESTIONS_GENERATING",
    "PARTY_ANSWERS_OPEN",
    "INTAKE_SYNTHESIZING",
    "EVIDENCE_REQUESTS_GENERATING",
    "PARTY_EVIDENCE_OPEN",
    "EVIDENCE_SYNTHESIZING",
    "DOSSIER_FREEZING",
    "JUDGE_V1_GENERATING",
    "JURY_REVIEWING",
    "JUDGE_V2_GENERATING",
    "HUMAN_REVIEW_OPEN",
    "CLOSED",
]

EXPECTED_OPERATIONS = {
    "HEARING_INTAKE_QUESTIONS": (
        "INTAKE_QUESTIONS_GENERATING",
        "INTAKE_QUESTIONS",
        "HearingIntakeQuestionsRequest",
        "HearingIntakeQuestionsResult",
        "hearing_intake_questions.v1",
    ),
    "HEARING_INTAKE_SYNTHESIS": (
        "INTAKE_SYNTHESIZING",
        "INTAKE_SYNTHESIS",
        "HearingIntakeSynthesisRequest",
        "HearingIntakeSynthesisResult",
        "hearing_intake_synthesis.v1",
    ),
    "HEARING_EVIDENCE_REQUESTS": (
        "EVIDENCE_REQUESTS_GENERATING",
        "EVIDENCE_REQUESTS",
        "HearingEvidenceRequestsRequest",
        "HearingEvidenceRequestsResult",
        "hearing_evidence_requests.v1",
    ),
    "HEARING_EVIDENCE_SYNTHESIS": (
        "EVIDENCE_SYNTHESIZING",
        "EVIDENCE_SYNTHESIS",
        "HearingEvidenceSynthesisRequest",
        "HearingEvidenceSynthesisResult",
        "hearing_evidence_synthesis.v1",
    ),
    "HEARING_JUDGE_V1": (
        "JUDGE_V1_GENERATING",
        "JUDGE_V1",
        "HearingJudgeV1Request",
        "HearingJudgeV1Result",
        "hearing_judge_v1.v1",
    ),
    "HEARING_JURY_REVIEW": (
        "JURY_REVIEWING",
        "JURY_REVIEW",
        "HearingJuryReviewRequest",
        "HearingJuryReviewResult",
        "hearing_jury_review.v1",
    ),
    "HEARING_JUDGE_V2": (
        "JUDGE_V2_GENERATING",
        "JUDGE_V2",
        "HearingJudgeV2Request",
        "HearingJudgeV2Result",
        "hearing_judge_v2.v1",
    ),
}


def _matrix() -> dict[str, Any]:
    value = yaml.safe_load(MATRIX_PATH.read_text(encoding="utf-8"))
    assert isinstance(value, dict)
    return value


def _git_blob(revision: str, path: str) -> bytes:
    completed = subprocess.run(
        ["git", "show", f"{revision}:{path}"],
        cwd=ROOT,
        check=True,
        capture_output=True,
    )
    return completed.stdout


def _extract_yaml_block(markdown: str, heading: str) -> dict[str, Any]:
    match = re.search(
        rf"(?ms)^## {re.escape(heading)}\s*$.*?^```yaml\s*$\n(.*?)^```\s*$",
        markdown,
    )
    assert match is not None, f"missing structured YAML block under {heading}"
    value = yaml.safe_load(match.group(1))
    assert isinstance(value, dict)
    return value


def _checkpoint_gate(markdown: str) -> dict[str, str]:
    blocks = re.findall(r"(?ms)^```text\s*$\n(.*?)^```\s*$", markdown)
    records: list[dict[str, str]] = []
    for block in blocks:
        record: dict[str, str] = {}
        for line in block.splitlines():
            if ":" not in line:
                continue
            key, value = line.split(":", 1)
            record[key.strip()] = value.strip()
        if "next_phase_permission" in record:
            records.append(record)
    assert len(records) == 1
    return records[0]


def _class_nodes(tree: ast.Module) -> dict[str, ast.ClassDef]:
    return {node.name: node for node in tree.body if isinstance(node, ast.ClassDef)}


def _annotated_literal_string(class_node: ast.ClassDef, field: str) -> str:
    assignment = next(
        node
        for node in class_node.body
        if isinstance(node, ast.AnnAssign)
        and isinstance(node.target, ast.Name)
        and node.target.id == field
    )
    annotation = assignment.annotation
    assert isinstance(annotation, ast.Subscript)
    assert isinstance(annotation.value, ast.Name) and annotation.value.id == "Literal"
    values = annotation.slice.elts if isinstance(annotation.slice, ast.Tuple) else [annotation.slice]
    strings = [item.value for item in values if isinstance(item, ast.Constant)]
    assert len(strings) == 1 and isinstance(strings[0], str)
    return strings[0]


def _request_stage_code(class_node: ast.ClassDef) -> str:
    assignment = next(
        node
        for node in class_node.body
        if isinstance(node, ast.AnnAssign)
        and isinstance(node.target, ast.Name)
        and node.target.id == "stage_code"
    )
    literal = assignment.annotation
    assert isinstance(literal, ast.Subscript)
    value = literal.slice
    assert isinstance(value, ast.Attribute)
    assert isinstance(value.value, ast.Name) and value.value.id == "HearingFlowStageCode"
    return value.attr


def test_source_snapshot_hashes_are_exact_raw_git_blob_pins() -> None:
    matrix = _matrix()
    base = matrix["accepted_base"]
    pins = matrix["source_snapshot"]["files"]
    assert matrix["source_snapshot"]["hash_scope"] == "raw_git_blob_at_accepted_base"
    assert len(pins) == 5
    for pin in pins:
        accepted_blob = _git_blob(base, pin["path"])
        current_blob = _git_blob("HEAD", pin["path"])
        assert hashlib.sha256(accepted_blob).hexdigest() == pin["sha256"]
        assert current_blob == accepted_blob, f"unrecorded source drift: {pin['path']}"

    checkpoint = matrix["source_snapshot"]["accepted_checkpoint"]
    checkpoint_blob = _git_blob(base, checkpoint["path"])
    assert hashlib.sha256(checkpoint_blob).hexdigest() == checkpoint["sha256"]
    assert _git_blob("HEAD", checkpoint["path"]) == checkpoint_blob


def test_fixed_stage_machine_matches_java_and_has_only_two_deadlines() -> None:
    matrix = _matrix()
    stages = matrix["stages"]
    assert [item["sequence"] for item in stages] == list(range(1, 16))
    assert [item["code"] for item in stages] == EXPECTED_STAGES
    assert [item["code"] for item in stages if item["deadline"] == "SHARED_ABSOLUTE"] == [
        "PARTY_ANSWERS_OPEN",
        "PARTY_EVIDENCE_OPEN",
    ]
    assert matrix["flow_identity"] == {
        "schema_version": "hearing_flow.v2",
        "selector": "LEGACY",
        "state_machine": "FIXED_15_STAGE_V2",
        "generic_three_round_fallback": "forbidden",
        "legacy_active_flow_writer_transfer": "forbidden",
    }

    java = JAVA_STAGE_PATH.read_text(encoding="utf-8")
    enum_body = re.search(r"enum HearingFlowStage\s*\{(.*?);\s*\n\s*private", java, re.S)
    assert enum_body is not None
    java_stages = re.findall(r"^\s*([A-Z][A-Z0-9_]+)\((true|false)\)", enum_body.group(1), re.M)
    assert [name for name, _ in java_stages] == EXPECTED_STAGES
    assert [name for name, deadline in java_stages if deadline == "true"] == [
        "PARTY_ANSWERS_OPEN",
        "PARTY_EVIDENCE_OPEN",
    ]


def test_seven_cognitive_operations_pin_python_requests_and_results() -> None:
    matrix = _matrix()
    operations = matrix["cognitive_operations"]
    actual = {
        item["java_operation"]: (
            item["stage"],
            item["python_stage_code"],
            item["request_model"],
            item["result_model"],
            item["result_schema"],
        )
        for item in operations
    }
    assert actual == EXPECTED_OPERATIONS
    assert len({item["endpoint"] for item in operations}) == 7
    assert len({item["stage"] for item in operations}) == 7

    tree = ast.parse(PYTHON_SCHEMA_PATH.read_text(encoding="utf-8"))
    classes = _class_nodes(tree)
    for _, stage_code, request_name, result_name, result_schema in actual.values():
        assert request_name in classes and result_name in classes
        assert _request_stage_code(classes[request_name]) == stage_code
        assert _annotated_literal_string(classes[result_name], "schema_version") == result_schema

    stage_enum = classes["HearingFlowStageCode"]
    enum_values = {
        node.value.value
        for node in stage_enum.body
        if isinstance(node, ast.Assign)
        and isinstance(node.value, ast.Constant)
        and isinstance(node.value.value, str)
    }
    assert enum_values == {value[1] for value in EXPECTED_OPERATIONS.values()}


def test_limits_identity_hash_and_parent_chain_are_closed() -> None:
    matrix = _matrix()
    assert matrix["limits"] == {
        "questions_per_set": {"min": 1, "max": 5},
        "target_roles_per_question": {"min": 1, "max": 2},
        "evidence_requests_per_set": {"min": 0, "max": 10},
        "evidence_ids_per_party_batch": {"min": 0, "max": 50, "unique": True},
        "evidence_ids_across_two_party_batches": {"min": 0, "max": 100, "unique": True},
        "party_terminal_submissions_per_wait": {"exactly": 2},
        "policy_rules_per_dossier": {
            "min": 1,
            "max": 100,
            "unique_by": ["rule_code", "rule_version"],
        },
        "jury_findings": {"exactly": 6, "unique_by": "dimension"},
        "fact_rows_per_delta": {"max": 200},
        "fact_evidence_links_per_matrix": {
            "max": 2000,
            "unique_by": ["fact_id", "evidence_id"],
        },
    }
    identity = matrix["participant_identity"]
    assert identity["terminal_action_uniqueness"] == [
        "stage_id",
        "action_type",
        "participant_id",
    ]
    assert identity["synthesis_participant_count"] == 2
    assert identity["synthesis_participant_ids_distinct"] is True
    assert identity["derive_participant_id_from_role"] == "forbidden"

    sql = V037_PATH.read_text(encoding="utf-8")
    unique_index = re.search(
        r"create unique index uq_hearing_flow_action_party\s+"
        r"on hearing_flow_action\(([^)]+)\)",
        sql,
        re.I,
    )
    assert unique_index is not None
    assert [part.strip() for part in unique_index.group(1).split(",")] == [
        "stage_id",
        "action_type",
        "participant_id",
    ]

    hash_contract = matrix["hash_contract"]
    assert hash_contract["digest"] == "SHA_256"
    assert hash_contract["preimage"] == {
        "object_copy": True,
        "omit_exactly_named_top_level_hash_field": True,
        "key_order": "lexicographic_sorted_keys",
        "separators": ["COMMA", "COLON"],
        "whitespace": "none_outside_json_strings",
        "string_encoding": "UTF_8",
        "ensure_ascii": False,
        "implementation_profile": "PYTHON_JSON_DUMPS_SORT_KEYS_COMPACT_UTF8",
        "implicit_rfc8785": False,
    }
    assert set(hash_contract["named_hash_fields"].values()) == {
        "content_hash",
        "proposal_hash",
        "review_hash",
        "judge_v2_hash",
    }
    chain = matrix["id_hash_parent_chain"]
    assert chain["substitution"] == "reject_at_every_boundary"
    assert chain["jury_bypass"] == "forbidden"
    assert chain["v2_generation_count"] == 1


def test_v037_read_compatibility_is_separate_from_identity_complete_emission() -> None:
    compatibility = _matrix()["v037_compatibility"]
    assert compatibility["historical_reads"] == {
        "accepted_answer_schemas": [
            "hearing_answer_bundle.v1",
            "hearing_party_statement.v1",
        ],
        "historical_hearing_answer_bundle_rewrite": "forbidden",
        "nested_participant_id_promotion_for_python_read": "allowed",
    }
    emissions = compatibility["canonical_new_emissions"]
    assert emissions["participant_id_required"] is True
    assert emissions["preferred_answer_schema"] == "hearing_party_statement.v1"
    assert emissions["accepted_compatibility_emission_schema"] == "hearing_answer_bundle.v1"
    assert emissions["identity_key"] == "participant_id"


def test_authority_and_replay_boundaries_fail_closed() -> None:
    matrix = _matrix()
    authority = matrix["authority_boundaries"]
    assert authority["java"]["authority"] == "FORMAL_BUSINESS_TRUTH_AND_ONLY_FORMAL_WRITER"
    assert authority["temporal"]["authority"] == (
        "FUTURE_PROCESS_ORDER_WAITS_DEADLINES_RETRIES_FAILURE_AND_HANDOFF"
    )
    assert authority["temporal"]["current_formal_allocation"] == "forbidden"
    assert authority["graph"]["authority"] == "PRIVATE_COGNITION_ONLY"
    assert authority["graph"]["formal_sink"] == "forbidden"
    assert authority["lcel"]["authority"] == (
        "TYPED_MODEL_INVOCATION_AND_CLOSED_PARSING_ONLY"
    )
    assert authority["lcel"]["stage_or_writer_authority"] == "forbidden"

    rollback = matrix["version_replay_and_rollback"]
    assert rollback["mid_flight_writer_transfer"] == "forbidden"
    assert rollback["captured_history_replay_required_before_promotion"] is True
    assert rollback["recovery_epoch_and_fence"] == (
        "strictly_higher_with_explicit_recovery_record"
    )
    arbitration = matrix["deadlines_and_arbitration"]
    assert arbitration["same_temporal_timestamp"] == "deterministic_workflow_event_order"
    assert arbitration["committed_java_action_order"] == "monotonic_case_event_sequence"
    assert arbitration["duplicate_or_late_signal"] == "return_persisted_terminal_state"


def test_adr_and_matrix_cannot_exceed_the_accepted_phase5_checkpoint() -> None:
    matrix = _matrix()
    checkpoint = _checkpoint_gate(CHECKPOINT_PATH.read_text(encoding="utf-8"))
    adr_text = ADR_PATH.read_text(encoding="utf-8")
    adr_gate = _extract_yaml_block(adr_text, "Gate Record")
    metadata = dict(
        re.findall(r"(?m)^- (Status|Date|Scope|Approval):\s*(.+?)\s*$", adr_text)
    )

    assert metadata["Status"] == "ACCEPTED FOR ENGINEERING ONLY"
    assert checkpoint["engineering_checkpoint"] == "PASS"
    assert checkpoint["promotion_gate"] == "PENDING"
    assert checkpoint["next_phase_permission"] == "PHASE_6_ENGINEERING_ONLY"
    assert checkpoint["MIG-004"] == "PENDING_PROMOTION"
    assert checkpoint["MIG-005"] == "PENDING_PROMOTION"

    gate = matrix["gate_state"]
    assert adr_gate["engineering_checkpoint"] == gate["upstream_engineering_checkpoint"]
    assert adr_gate["promotion_gate"] == gate["upstream_promotion_gate"]
    assert adr_gate["next_phase_permission"] == gate["permission"]
    assert adr_gate["p6_0_entry_gate"] == gate["p6_0_entry_gate"]
    assert adr_gate["product_implementation"] == "BLOCKED"
    assert gate["product_implementation_allowed"] is False
    assert adr_gate["MIG-004"] == gate["migrations"]["MIG-004"] == checkpoint["MIG-004"]
    assert adr_gate["MIG-005"] == gate["migrations"]["MIG-005"] == checkpoint["MIG-005"]
    assert adr_gate["MIG-006"] == gate["migrations"]["MIG-006"]
    assert all(value.startswith("PENDING") for value in gate["migrations"].values())
    assert gate["allowed_runtime_modes"] == [
        "DISABLED",
        "JAVA_SIGNED_SYNTHETIC_SHADOW",
    ]
    assert adr_gate["allowed_runtime_modes"] == gate["allowed_runtime_modes"]
    for forbidden_grant in (
        "real_case_or_party_data_allowed",
        "real_shadow_allowed",
        "formal_graph_sink_allowed",
        "temporal_hearing_allocation_allowed",
        "v044_implementation_allowed",
        "canary_allowed",
        "promotion_allowed",
    ):
        assert gate[forbidden_grant] is False
