from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

import rfc8785
import yaml


ROOT = Path(__file__).resolve().parents[2]
EXECUTION_PLAN = ROOT / "plans/phase-5-evidence-pilot-execution.md"
TEST_BATCHES = ROOT / "plans/phase-5-evidence-pilot-test-batches.yaml"
CONTRACT_PACK = (
    ROOT / "docs/runbooks/temporal-first/phase-5-p5.0-contract-pack.md"
)
BASELINE_INVENTORY = (
    ROOT
    / "docs/runbooks/temporal-first/phase-5-p5.0-baseline-inventory.md"
)
REVIEW_CLOSURE = (
    ROOT / "docs/runbooks/temporal-first/phase-5-p5.0-review-closure.md"
)
PRE_ENTRY_CORRECTION = (
    ROOT
    / "docs/architecture/adr/"
    "0013-phase-5-evidence-pre-entry-contract-correction.md"
)
EVIDENCE_CONTRACT_ROOT = ROOT / "contracts/agent-platform/evidence/v2"
OWNER_BRIEFS = ROOT / "plans/phase-5-owner-briefs.yaml"
ROOM_GRAPH_COMMAND_FIXTURE = (
    ROOT
    / "contracts/agent-platform/v1/fixtures/valid/"
    "room-graph-command-evidence-valid.json"
)
EVIDENCE_MANIFEST_FIXTURE = (
    EVIDENCE_CONTRACT_ROOT
    / "fixtures/valid/evidence-batch-manifest-synthetic-1-valid.json"
)
MIGRATIONS = ROOT / "java-api-service/src/main/resources/db/migration"
EVIDENCE_MIGRATION = "V043_4__evidence_graph_bindings.sql"


def _defines_or_carries_field(value: Any, field: str) -> bool:
    if isinstance(value, dict):
        if field in value:
            return True
        required = value.get("required")
        if isinstance(required, list) and field in required:
            return True
        return any(_defines_or_carries_field(item, field) for item in value.values())
    if isinstance(value, list):
        return any(_defines_or_carries_field(item, field) for item in value)
    return False


def _mapping_values_for_key(value: Any, key: str) -> list[Any]:
    matches: list[Any] = []
    if isinstance(value, dict):
        for item_key, item_value in value.items():
            if item_key == key:
                matches.append(item_value)
            matches.extend(_mapping_values_for_key(item_value, key))
    elif isinstance(value, list):
        for item in value:
            matches.extend(_mapping_values_for_key(item, key))
    return matches


def _owner_briefs() -> dict[str, Any]:
    return yaml.safe_load(OWNER_BRIEFS.read_text(encoding="utf-8"))


def test_phase5_evidence_migration_follows_all_committed_intake_subversions() -> None:
    execution = EXECUTION_PLAN.read_text(encoding="utf-8")
    contract = CONTRACT_PACK.read_text(encoding="utf-8")
    batches = yaml.safe_load(TEST_BATCHES.read_text(encoding="utf-8"))
    owner_c = batches["owners"]["C"]

    assert (MIGRATIONS / "V043_2__intake_shadow_comparisons.sql").is_file()
    assert (MIGRATIONS / "V043_3__intake_signed_synthetic_admission.sql").is_file()
    assert (
        f"java-api-service/src/main/resources/db/migration/{EVIDENCE_MIGRATION}"
        in owner_c["change_routes"]
    )
    assert EVIDENCE_MIGRATION in execution
    assert EVIDENCE_MIGRATION in contract
    assert "V043_2__evidence_graph_bindings.sql" not in execution
    assert "V043_2__evidence_graph_bindings.sql" not in contract
    assert "V043_2__evidence_graph_bindings.sql" not in TEST_BATCHES.read_text(
        encoding="utf-8"
    )


def test_phase5_entry_requires_corrected_manifest_authority_before_batch0() -> None:
    execution = EXECUTION_PLAN.read_text(encoding="utf-8")
    contract = CONTRACT_PACK.read_text(encoding="utf-8")
    adr = PRE_ENTRY_CORRECTION.read_text(encoding="utf-8")

    for document in (execution, contract, adr):
        assert "signature_algorithm=ES256" in document
        assert "JOSE_P1363_BASE64URL" in document
        assert "ASCII_LOWERCASE_HEX_TEXT" in document
        assert "x-signature" in document
        assert "signature_encoding=" not in document
        assert "manifest_hash" in document and "signature" in document
        assert "assessment_output_schema_version=evidence-item-assessment.v1" in document
        assert (
            "terminal_output_schema_version=evidence-batch-proposal.v1" in document
        )
        assert "authorization_proof_ref" in document
        assert "BEFORE_CHECKPOINT_MUTATION" in document

    assert "no `authorization_proof_ref` field" in execution
    assert "`authorization_proof_ref` is forbidden" in contract
    assert "`authorization_proof_ref` is not" in adr
    for path in EVIDENCE_CONTRACT_ROOT.rglob("*.json"):
        payload = json.loads(path.read_text(encoding="utf-8"))
        assert not _defines_or_carries_field(payload, "authorization_proof_ref"), path

    matrix = yaml.safe_load(
        (EVIDENCE_CONTRACT_ROOT / "compatibility-matrix.yaml").read_text(
            encoding="utf-8"
        )
    )
    policy_mentions = _mapping_values_for_key(matrix, "authorization_proof_ref")
    assert all(value == "forbidden" for value in policy_mentions)

    for field in (
        "command_id",
        "logical_run_id",
        "attempt_id",
        "tenant",
        "case",
        "room identity",
        "thread_id",
        "room_epoch",
        "domain_snapshot_ref",
        "graph/checkpoint",
        "invocation/profile",
    ):
        assert field in contract
    normalized_contract = " ".join(contract.split())
    assert "RoomGraphCommand.v1` has no `fencing_token`" in normalized_contract
    assert "current Graph lease fence" in normalized_contract
    assert "tokens are distinct" in normalized_contract
    assert "Java Finalizer revalidates the room fence" in normalized_contract
    assert "not the decoded 32-byte digest" in contract
    assert "finalization receipt does not carry `profile_versions`" in contract
    assert "new exact clean detached SHA" in execution
    assert "full P5-BATCH-0" in execution
    assert "regenerated" in execution and "fixture" in execution
    assert "Python" in execution and "Java" in execution and "parity" in execution


def test_phase5_governance_docs_freeze_transport_and_internal_hash_layers() -> None:
    governance_docs = (
        PRE_ENTRY_CORRECTION,
        EXECUTION_PLAN,
        CONTRACT_PACK,
        BASELINE_INVENTORY,
        REVIEW_CLOSURE,
    )
    required_contract = (
        "snapshot_payload_hash_scope: FULL_RFC8785_CANONICAL_SIGNED_MANIFEST_BYTES",
        "snapshot_payload_size_scope: EXACT_FULL_CANONICAL_SIGNED_MANIFEST_BYTES",
        "snapshot_payload_uri: IMMUTABLE_CONTENT_ADDRESSED_BY_SNAPSHOT_SHA256",
        "internal_manifest_hash_scope: RFC8785_OMIT_MANIFEST_HASH_AND_SIGNATURE",
        "snapshot_and_internal_hashes_interchangeable: false",
        "validation_order: SNAPSHOT_SHA_SIZE_URI -> PARSE_CANONICAL_JSON -> "
        "INTERNAL_MANIFEST_HASH -> JAVA_ES256_SIGNATURE",
        "room_graph_command_output_schema_version: evidence-batch-proposal.v1",
        "graph_registry_output_schema_version: evidence-batch-proposal.v1",
        "item_lcel_parser_output_schema_version: evidence-item-assessment.v1",
        "java_room_fence_source: SIGNED_MANIFEST",
        "graph_lease_fence_source: CURRENT_GRAPH_LEASE",
        "fence_tokens_interchangeable: false",
        "engineering_execution: BLOCKED_PENDING_P5_0_ENTRY_EVIDENCE",
    )

    for path in governance_docs:
        normalized = " ".join(path.read_text(encoding="utf-8").split())
        for contract_line in required_contract:
            assert contract_line in normalized, path
        assert "45d7f087eafe4f50be0d491b3d612446a3e1e94e" in normalized
        assert "P5.0 NOT_RUN" in normalized
        assert "ASCII_LOWERCASE_HEX_TEXT" in normalized
        assert "JOSE_P1363_BASE64URL" in normalized
        assert "authorization_proof_ref" in normalized


def test_phase5_evidence_room_command_binds_full_signed_manifest_payload() -> None:
    command = json.loads(ROOM_GRAPH_COMMAND_FIXTURE.read_text(encoding="utf-8"))[
        "instance"
    ]
    manifest = json.loads(EVIDENCE_MANIFEST_FIXTURE.read_text(encoding="utf-8"))

    actor_scope_hash = hashlib.sha256(rfc8785.dumps(command["actor_scope"])).hexdigest()
    request_preimage = dict(command)
    request_preimage.pop("request_hash")
    request_hash = hashlib.sha256(rfc8785.dumps(request_preimage)).hexdigest()
    full_manifest = rfc8785.dumps(manifest)
    manifest_preimage = dict(manifest)
    manifest_preimage.pop("manifest_hash")
    manifest_preimage.pop("signature")
    manifest_hash = hashlib.sha256(rfc8785.dumps(manifest_preimage)).hexdigest()

    assert actor_scope_hash == manifest["actor_scope_hash"]
    assert request_hash == command["request_hash"]
    assert manifest_hash == manifest["manifest_hash"]
    assert "fencing_token" not in command

    binding = manifest["command_binding"]
    for field in ("command_id", "logical_run_id", "attempt_id"):
        assert command[field] == binding[field]
    for field in ("tenant_surrogate", "case_id", "room_type", "room_epoch", "thread_id"):
        assert command[field] == manifest[field]
    assert command["deadline_at"] == binding["deadline_at"]
    assert command["graph_key"] == "evidence.v2"
    assert command["graph_version"] == manifest["profile_versions"]["graph_version"]
    assert command["checkpoint_schema_version"] == manifest["profile_versions"][
        "checkpoint_schema_version"
    ]
    assert command["invocation_context"]["output_schema_version"] == manifest[
        "profile_versions"
    ]["terminal_output_schema_version"]
    assert manifest["profile_versions"]["terminal_output_schema_version"] == (
        "evidence-batch-proposal.v1"
    )
    assert manifest["profile_versions"]["assessment_output_schema_version"] == (
        "evidence-item-assessment.v1"
    )

    snapshot = command["domain_snapshot_ref"]
    full_payload_hash = hashlib.sha256(full_manifest).hexdigest()
    assert snapshot["artifact_id"] == manifest["manifest_id"]
    assert snapshot["schema_version"] == manifest["schema_version"]
    assert snapshot["sha256"] == full_payload_hash
    assert snapshot["size_bytes"] == len(full_manifest)
    assert snapshot["uri"].endswith(f"/{full_payload_hash}.json")
    assert manifest["signature_algorithm"] == "ES256"
    assert manifest["signature"]


def test_phase5_wave_a_authority_owners_share_corrected_admission_contract() -> None:
    briefs = _owner_briefs()
    assert briefs["document_status"] == "DRAFT_BLOCKED_UNTIL_P5_0_ENTRY_EVIDENCE"
    assert briefs["entry_gate"]["status"] == "BLOCKED"
    assert "P5_0_ENTRY_EVIDENCE_COMMITTED" in briefs["entry_gate"][
        "required_before_dispatch"
    ]

    common_inputs = {
        "docs/architecture/adr/0013-phase-5-evidence-pre-entry-contract-correction.md",
        "contracts/agent-platform/evidence/v2/compatibility-matrix.yaml",
        "contracts/agent-platform/evidence/v2/evidence-batch-manifest.schema.json",
        "contracts/agent-platform/evidence/v2/fixtures/valid/"
        "evidence-batch-manifest-synthetic-1-valid.json",
        "contracts/agent-platform/v1/room-graph-command.schema.json",
        "contracts/agent-platform/v1/fixtures/valid/"
        "room-graph-command-evidence-valid.json",
    }
    validation_order = [
        "VERIFY_ROOM_GRAPH_COMMAND_SCHEMA_AND_REQUEST_HASH",
        "LOAD_EXACT_IMMUTABLE_MANIFEST_URI",
        "VERIFY_FULL_SNAPSHOT_PAYLOAD_SHA256_AND_SIZE",
        "VERIFY_INTERNAL_MANIFEST_RFC8785_SELF_HASH",
        "VERIFY_DIRECT_JAVA_ES256_MANIFEST_SIGNATURE",
        "DERIVE_AND_MATCH_RFC8785_ACTOR_SCOPE_HASH",
        "VERIFY_TRANSPORT_AND_REGISTRY_TERMINAL_OUTPUT_PIN",
        "VERIFY_INTERNAL_ITEM_ASSESSMENT_OUTPUT_PIN",
        "ENFORCE_DISTINCT_JAVA_ROOM_AND_GRAPH_LEASE_FENCES",
    ]
    responsibilities = {
        "P5-A1": "CONSUME_VERIFIED_ADMISSION_BINDING_ONLY",
        "P5-C1": "ISSUE_AND_VERIFY_JAVA_MANIFEST_AUTHORITY",
        "P5-E0": "PROVE_FAIL_CLOSED_SYNTHETIC_NO_SINK_ASSEMBLY",
    }

    for task_id, responsibility in responsibilities.items():
        owner_id = task_id.split("-")[1][0]
        task = briefs["owners"][owner_id]["tasks"][task_id]
        authority = task["authority_contract"]
        assert common_inputs.issubset(task["input_contracts"])
        assert authority["contract_version"] == "ADR_0013_DIRECT_JAVA_ES256_MANIFEST"
        assert authority["responsibility"] == responsibility
        assert authority["manifest_authority"] == "DIRECT_JAVA_ES256_SIGNATURE"
        assert authority["validation_order"] == validation_order
        assert authority["actor_scope_hash_source"] == (
            "RFC8785_SHA256_OF_VERIFIED_ROOM_GRAPH_COMMAND_ACTOR_SCOPE"
        )
        assert authority["assessment_output_schema_version"] == (
            "evidence-item-assessment.v1"
        )
        assert authority["terminal_output_schema_version"] == (
            "evidence-batch-proposal.v1"
        )
        assert authority["room_graph_command_output_schema_version"] == (
            "evidence-batch-proposal.v1"
        )
        assert authority["graph_registry_output_schema_version"] == (
            "evidence-batch-proposal.v1"
        )
        assert authority["item_lcel_parser_output_schema_version"] == (
            "evidence-item-assessment.v1"
        )
        assert authority["java_room_fence_source"] == "SIGNED_MANIFEST"
        assert authority["graph_lease_fence_source"] == "CURRENT_GRAPH_LEASE"
        assert authority["fence_tokens_interchangeable"] is False
        assert authority["authorization_proof_ref"] == "FORBIDDEN"
        assert authority["implementation_gate"] == (
            "P5_0_ENTRY_EVIDENCE_COMMITTED_FOR_NEW_EXACT_SHA"
        )
        contract_gate = next(
            command
            for command in task["t0_commands"]
            if command["id"] == f"{task_id.removeprefix('P5-')}_CONTRACT_GATE"
        )
        assert "tests/static/test_phase5_entry_contract_plan.py" in contract_gate[
            "argv"
        ]
        assert "tests/static/test_phase5_evidence_contracts.py" in contract_gate[
            "argv"
        ]
        assert "tests/static/test_agent_platform_schema_contracts.py" in contract_gate[
            "argv"
        ]

    c1_maven = next(
        command
        for command in briefs["owners"]["C"]["tasks"]["P5-C1"]["t0_commands"]
        if command["id"] == "C1_MAVEN_TEST"
    )
    selector = next(token for token in c1_maven["argv"] if token.startswith("-Dtest="))
    assert "EvidenceV2ContractFixtureTest" in selector
