from __future__ import annotations

import copy
import hashlib
import json
from datetime import datetime
from pathlib import Path
from typing import Any

import jsonschema
import rfc8785
import yaml


ROOT = Path(__file__).resolve().parents[2]
CONTRACT_ROOT = ROOT / "contracts/agent-platform/target-e2e/v1"
SCHEMA_PATH = CONTRACT_ROOT / "target-e2e-activation-manifest.schema.json"
POLICY_PATH = CONTRACT_ROOT / "activation-validation-policy.v1.json"
CONTEXT_PATH = CONTRACT_ROOT / "fixtures/runtime/isolated-preproduction-context.json"
VALID_ROOT = CONTRACT_ROOT / "fixtures/valid"
INVALID_PATH = CONTRACT_ROOT / "fixtures/invalid/activation-invalid-cases.json"
GOLDEN_PATH = CONTRACT_ROOT / "fixtures/canonical/activation-canonical-golden.json"
PLAN_PATH = ROOT / "plans/phase-9-target-architecture-e2e-execution.md"
BATCH_PATH = ROOT / "plans/phase-9-target-architecture-e2e-test-batches.yaml"
ADR_PATH = ROOT / "docs/architecture/adr/0017-target-architecture-preproduction-e2e.md"
PACK_PATH = ROOT / "docs/runbooks/temporal-first/phase-9-p9.0-contract-pack.md"


def _json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    assert isinstance(value, dict)
    return value


def _instant(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def _canonical_hash(value: object) -> str:
    return hashlib.sha256(rfc8785.dumps(value)).hexdigest()


def _without(value: dict[str, Any], field: str) -> dict[str, Any]:
    result = copy.deepcopy(value)
    result.pop(field)
    return result


def _set_pointer(document: dict[str, Any], pointer: str, value: object) -> None:
    parts = [part.replace("~1", "/").replace("~0", "~") for part in pointer.split("/")[1:]]
    target: Any = document
    for part in parts[:-1]:
        target = target[part]
    target[parts[-1]] = value


def _semantic_errors(manifest: dict[str, Any], context: dict[str, Any]) -> set[str]:
    errors: set[str] = set()
    issued_at = _instant(manifest["issuedAt"])
    expires_at = _instant(manifest["expiresAt"])
    now = _instant(context["validationTime"])

    if issued_at > now:
        errors.add("MANIFEST_ISSUED_IN_FUTURE")
    if expires_at <= issued_at:
        errors.add("MANIFEST_EXPIRY_ORDER_INVALID")
    if (expires_at - issued_at).total_seconds() > 7200:
        errors.add("MANIFEST_LIFETIME_EXCEEDED")
    if expires_at <= now:
        errors.add("MANIFEST_EXPIRED")
    for grant in context["registeredGrants"]:
        same_activation = grant["activationId"] == manifest["activationId"]
        same_nonce = grant["nonce"] == manifest["nonce"]
        if same_activation or same_nonce:
            exact_attach = grant == {
                "environmentId": manifest["environmentId"],
                "environmentGeneration": manifest["environmentGeneration"],
                "activationId": manifest["activationId"],
                "nonce": manifest["nonce"],
                "manifestHash": manifest["manifestHash"],
            }
            if not exact_attach:
                errors.add("NONCE_REPLAY")

    scalar_bindings = {
        "contractVersion": ("expectedContractVersion", "CONTRACT_VERSION_MISMATCH"),
        "executionLane": ("expectedExecutionLane", "EXECUTION_LANE_MISMATCH"),
        "environmentId": ("environmentId", "ENVIRONMENT_ID_MISMATCH"),
        "environmentGeneration": (
            "environmentGeneration",
            "ENVIRONMENT_GENERATION_MISMATCH",
        ),
        "candidateSha": ("candidateSha", "CANDIDATE_SHA_MISMATCH"),
        "tenantSurrogate": ("tenantSurrogate", "TENANT_SCOPE_MISMATCH"),
        "temporalNamespace": (
            "temporalNamespace",
            "TEMPORAL_NAMESPACE_MISMATCH",
        ),
    }
    for manifest_key, (context_key, code) in scalar_bindings.items():
        if manifest[manifest_key] != context[context_key]:
            errors.add(code)

    for manifest_key, code in {
        "buildBindings": "BUILD_BINDING_MISMATCH",
        "graphBinding": "GRAPH_BINDING_MISMATCH",
        "imageDigests": "IMAGE_DIGEST_MISMATCH",
        "databaseIdentities": "DATABASE_IDENTITY_MISMATCH",
    }.items():
        if manifest[manifest_key] != context[manifest_key]:
            errors.add(code)

    if context["requestedRoomType"] not in manifest["allowedRoomTypes"]:
        errors.add("ROOM_SCOPE_MISMATCH")
    scope = manifest["caseScope"]
    if scope["mode"] == "EXPLICIT_CASE_IDS":
        if context["requestedCaseId"] not in scope["allowedCaseIds"]:
            errors.add("CASE_SCOPE_MISMATCH")
    else:
        if scope["fixtureSetId"] != context["requestedSyntheticFixtureSetId"]:
            errors.add("CASE_SCOPE_MISMATCH")
        if not context["requestedSyntheticCaseId"].startswith(scope["caseIdPrefix"]):
            errors.add("CASE_SCOPE_MISMATCH")
        if context["reservedSyntheticCaseCount"] >= scope["maxCases"]:
            errors.add("CASE_SCOPE_MISMATCH")

    graph_without_hash = _without(manifest["graphBinding"], "bindingHash")
    if _canonical_hash(graph_without_hash) != manifest["graphBinding"]["bindingHash"]:
        errors.add("GRAPH_BINDING_HASH_MISMATCH")
    if _canonical_hash(_without(manifest, "manifestHash")) != manifest["manifestHash"]:
        errors.add("MANIFEST_HASH_MISMATCH")

    domain = manifest["databaseIdentities"]["domain"]
    graph = manifest["databaseIdentities"]["graph"]
    if domain == graph:
        errors.add("DATABASE_IDENTITY_COLLISION")

    if manifest["authority"] != {
        "environmentClass": "ISOLATED_PREPRODUCTION",
        "graphOutputAuthority": "PROPOSAL_ONLY",
        "graphDomainCredentialsPresent": False,
        "graphDomainWriteAllowed": False,
        "formalWriter": "JAVA_FINALIZER_ONLY",
        "javaDomainCommitAllowed": True,
        "externalEffectsAllowed": False,
        "productionTrafficAllowed": False,
        "productionPromotionAuthority": False,
        "migrationPromotionAuthority": False,
    }:
        errors.add("AUTHORITY_CEILING_VIOLATION")
    if manifest["productionDefaults"] != {
        "formalCaseSelector": "LEGACY",
        "targetE2EActivation": "DISABLED",
    }:
        errors.add("PRODUCTION_DEFAULT_VIOLATION")
    return errors


def test_activation_schema_and_both_scope_examples_are_valid() -> None:
    schema = _json(SCHEMA_PATH)
    validator_class = jsonschema.validators.validator_for(schema)
    validator_class.check_schema(schema)
    validator = validator_class(schema, format_checker=jsonschema.FormatChecker())
    context = _json(CONTEXT_PATH)

    fixtures = sorted(VALID_ROOT.glob("target-e2e-activation-*-valid.json"))
    assert [path.name for path in fixtures] == [
        "target-e2e-activation-allowlist-valid.json",
        "target-e2e-activation-synthetic-valid.json",
    ]
    for fixture_path in fixtures:
        fixture = _json(fixture_path)
        assert not list(validator.iter_errors(fixture)), fixture_path.name
        assert not _semantic_errors(fixture, context), fixture_path.name

    assert _json(fixtures[0])["caseScope"]["mode"] == "EXPLICIT_CASE_IDS"
    synthetic = _json(fixtures[1])["caseScope"]
    assert synthetic["mode"] == "ISOLATED_SYNTHETIC_NEW_CASES"
    assert synthetic["caseIdPrefix"] == "CASE_P9_SYNTHETIC_"
    assert 1 <= synthetic["maxCases"] <= 16


def test_canonical_hashes_and_non_secret_jws_golden_are_frozen() -> None:
    fixture = _json(VALID_ROOT / "target-e2e-activation-allowlist-valid.json")
    golden = _json(GOLDEN_PATH)
    assert _canonical_hash(_without(fixture, "manifestHash")) == fixture["manifestHash"]
    assert golden["manifestHash"] == fixture["manifestHash"]
    assert golden["selfHashOmitFields"] == ["manifestHash"]
    assert golden["activationJwsProtectedHeader"] == {
        "alg": "ES256",
        "kid": "p9-java-activation-key-01",
        "typ": "target-e2e-activation+jwt",
    }
    assert golden["signatureFixtureStatus"] == (
        "STRUCTURAL_GOLDEN_ONLY_NO_PRIVATE_KEY_OR_VALID_SIGNATURE"
    )

    graph = fixture["graphBinding"]
    assert _canonical_hash(_without(graph, "bindingHash")) == graph["bindingHash"]


def test_invalid_fixture_matrix_rejects_every_required_failure_mode() -> None:
    invalid = _json(INVALID_PATH)
    base = _json((INVALID_PATH.parent / invalid["baseFixture"]).resolve())
    base_context = _json(CONTEXT_PATH)
    observed_ids: set[str] = set()
    observed_rejections: set[str] = set()

    for case in invalid["cases"]:
        fixture_base = base
        if "baseFixture" in case:
            fixture_base = _json((INVALID_PATH.parent / case["baseFixture"]).resolve())
        fixture = copy.deepcopy(fixture_base)
        context = copy.deepcopy(base_context)
        _set_pointer(fixture, case["pointer"], case["value"])
        if "registeredGrantManifestHash" in case:
            context["registeredGrants"].append(
                {
                    "environmentId": fixture["environmentId"],
                    "environmentGeneration": fixture["environmentGeneration"],
                    "activationId": fixture["activationId"],
                    "nonce": fixture["nonce"],
                    "manifestHash": case["registeredGrantManifestHash"],
                }
            )
        if "reservedSyntheticCaseCount" in case:
            context["reservedSyntheticCaseCount"] = case["reservedSyntheticCaseCount"]
        errors = _semantic_errors(fixture, context)
        assert case["expectedRejection"] in errors, (case["id"], errors)
        observed_ids.add(case["id"])
        observed_rejections.add(case["expectedRejection"])

    assert {
        "expired",
        "lifetime-over-two-hours",
        "future-issued-at",
        "replayed-nonce",
        "wrong-environment",
        "wrong-candidate-sha",
        "wrong-lane",
        "wrong-contract-version",
        "wrong-case-build",
        "wrong-control-build",
        "wrong-agent-build",
        "wrong-image-digest",
        "wrong-tenant",
        "wrong-room",
        "wrong-case",
        "wrong-graph-key",
        "wrong-graph-version",
        "wrong-checkpoint-version",
        "wrong-graph-binding-hash",
        "wrong-graph-code-build",
        "wrong-synthetic-case-prefix",
        "synthetic-case-capacity-exhausted",
    } <= observed_ids
    assert {
        "MANIFEST_EXPIRED",
        "MANIFEST_LIFETIME_EXCEEDED",
        "MANIFEST_ISSUED_IN_FUTURE",
        "NONCE_REPLAY",
        "ENVIRONMENT_ID_MISMATCH",
        "ENVIRONMENT_GENERATION_MISMATCH",
        "CANDIDATE_SHA_MISMATCH",
        "EXECUTION_LANE_MISMATCH",
        "CONTRACT_VERSION_MISMATCH",
        "BUILD_BINDING_MISMATCH",
        "IMAGE_DIGEST_MISMATCH",
        "TENANT_SCOPE_MISMATCH",
        "ROOM_SCOPE_MISMATCH",
        "CASE_SCOPE_MISMATCH",
        "GRAPH_BINDING_MISMATCH",
        "TEMPORAL_NAMESPACE_MISMATCH",
        "DATABASE_IDENTITY_MISMATCH",
    } <= observed_rejections


def test_identical_ha_replica_attach_is_idempotent_but_rebinding_is_replay() -> None:
    manifest = _json(VALID_ROOT / "target-e2e-activation-allowlist-valid.json")
    context = _json(CONTEXT_PATH)
    registered = {
        "environmentId": manifest["environmentId"],
        "environmentGeneration": manifest["environmentGeneration"],
        "activationId": manifest["activationId"],
        "nonce": manifest["nonce"],
        "manifestHash": manifest["manifestHash"],
    }
    context["registeredGrants"] = [registered]
    assert not _semantic_errors(manifest, context)

    conflicting = copy.deepcopy(context)
    conflicting["registeredGrants"][0]["environmentGeneration"] += 1
    assert "NONCE_REPLAY" in _semantic_errors(manifest, conflicting)


def test_policy_freezes_transport_replay_authority_and_exact_bindings() -> None:
    policy = _json(POLICY_PATH)
    assert policy["contractVersion"] == "target-e2e-activation.v1"
    assert policy["executionLane"] == "TARGET_E2E_CANDIDATE"
    assert policy["timePolicy"] == {
        "maximumLifetimeSeconds": 7200,
        "futureIssuedAtToleranceSeconds": 0,
        "expiresAtMustBeAfterIssuedAt": True,
        "expiredManifestAllowed": False,
    }
    assert policy["replayPolicy"]["operation"] == "ATOMIC_REGISTER_OR_ATTACH_IDENTICAL"
    assert policy["replayPolicy"]["identity"] == [
        "environmentId",
        "environmentGeneration",
        "activationId",
        "nonce",
        "manifestHash",
    ]
    assert policy["replayPolicy"]["uniqueGrantKeys"] == ["activationId", "nonce"]
    assert policy["replayPolicy"]["identicalReplicaAttachResult"] == "ATTACHED_EXISTING"
    assert policy["replayPolicy"]["identicalReplicaCreatesSecondConsumptionRow"] is False
    assert policy["newCaseReservationPolicy"] == {
        "mode": "ISOLATED_SYNTHETIC_NEW_CASES",
        "operation": "ATOMIC_RESERVE_CASE_SLOT_BEFORE_FIRST_EPOCH_SELECTION",
        "identity": [
            "environmentId",
            "environmentGeneration",
            "activationId",
            "generatedCaseId",
        ],
        "maximumCases": 16,
        "prefixWildcardAllowed": False,
        "existingCaseIdReservationResult": "IDEMPOTENT_ONLY_FOR_SAME_ACTIVATION_SLOT",
        "wrongPrefixOrExhaustedCapacityResult": "REJECT",
    }
    transport = policy["transport"]
    assert transport["scope"] == "DEPLOYMENT_STARTUP_ONLY"
    assert transport["bootstrapHeader"] == "X-AfterSaleFlow-Target-E2E-Activation"
    assert transport["graphEndpointHeaderAllowed"] is False
    assert transport["perCommandManifestAllowed"] is False
    assert transport["reusableBearerAllowed"] is False

    rules = {rule["id"]: rule for rule in policy["bindingRules"]}
    assert set(rules) == {
        "CONTRACT_VERSION",
        "EXECUTION_LANE",
        "ENVIRONMENT_ID",
        "ENVIRONMENT_GENERATION",
        "CANDIDATE_SHA",
        "TENANT",
        "CASE",
        "ROOM",
        "BUILDS",
        "GRAPH",
        "IMAGES",
        "TEMPORAL_NAMESPACE",
        "DATABASE_IDENTITIES",
    }
    assert policy["failurePolicy"] == {
        "default": "FAIL_CLOSED_BEFORE_WORKER_ADMISSION",
        "partialActivationAllowed": False,
        "fallbackToLegacyInsideEpochAllowed": False,
        "mixedManifestOrDeploymentBindingsAllowed": False,
    }
    command = policy["commandBinding"]
    assert command["commandEnvelopeHash"] == (
        "SHA256_RFC8785_WRAPPER_OMITTING_ONLY_COMMAND_ENVELOPE_HASH"
    )
    assert command["jwsClaimAdditions"] == [
        "execution_lane",
        "activation_id",
        "command_hash",
        "command_envelope_hash",
    ]
    finalization = policy["resultAndFinalizationBinding"]
    assert finalization["resultHash"].startswith("EQUALS_ROOM_GRAPH_RESULT_OUTPUT_HASH")
    assert finalization["receiptHash"] == (
        "SHA256_RFC8785_FINALIZATION_RECEIPT_OMITTING_ONLY_RECEIPT_HASH"
    )
    assert "isolated_domain_db_binding_hash" in finalization["requiredReceiptBindings"]


def test_additive_graph_envelopes_preserve_v1_and_bind_lane_activation_hash() -> None:
    schemas = {
        path.name: _json(path)
        for path in CONTRACT_ROOT.glob("target-e2e-*.schema.json")
    }
    assert set(schemas) == {
        "target-e2e-activation-manifest.schema.json",
        "target-e2e-finalization-receipt.schema.json",
        "target-e2e-graph-command-envelope.schema.json",
        "target-e2e-graph-result-envelope.schema.json",
    }
    for schema in schemas.values():
        jsonschema.validators.validator_for(schema).check_schema(schema)

    command = schemas["target-e2e-graph-command-envelope.schema.json"]
    assert command["required"] == [
        "schema_version",
        "execution_lane",
        "activation_id",
        "command_hash",
        "command_envelope_hash",
        "command",
    ]
    assert command["properties"]["command"]["$ref"] == (
        "../../v1/room-graph-command.schema.json"
    )
    assert command["x-jws"]["protectedHeaderTyp"] == (
        "target-e2e-graph-command+jwt"
    )
    assert command["x-jws"]["requiredClaims"] == [
        "execution_lane",
        "activation_id",
        "command_hash",
        "command_envelope_hash",
    ]
    assert command["x-self-hash"] == {
        "algorithm": "SHA-256",
        "field": "command_envelope_hash",
        "preimage": "RFC8785_OMIT_TOP_LEVEL_FIELD",
        "omitFields": ["command_envelope_hash"],
    }

    result = schemas["target-e2e-graph-result-envelope.schema.json"]
    assert result["properties"]["graph_output_authority"]["const"] == "PROPOSAL_ONLY"
    assert result["x-result-hash"]["rule"] == "EQUALS_NESTED_RESULT_OUTPUT_HASH"
    assert result["x-result-hash"]["nestedOutputHashPreimage"] == (
        "RFC8785_FULL_ROOM_GRAPH_RESULT_V1_OMITTING_ONLY_OUTPUT_HASH"
    )
    assert result["x-self-hash"]["omitFields"] == ["result_envelope_hash"]
    assert {
        "command_hash",
        "command_envelope_hash",
        "result_hash",
        "proposal_hash",
        "result_envelope_hash",
    } <= set(result["required"])

    receipt = schemas["target-e2e-finalization-receipt.schema.json"]
    assert receipt["properties"]["formal_writer"]["const"] == "JAVA_FINALIZER_ONLY"
    assert receipt["x-self-hash"]["omitFields"] == ["receipt_hash"]
    assert {
        "tenant_surrogate",
        "case_id",
        "room_type",
        "room_epoch",
        "fencing_token",
        "process_revision",
        "stage_sequence",
        "logical_run_id",
        "attempt_id",
        "command_hash",
        "command_envelope_hash",
        "graph_key",
        "graph_version",
        "checkpoint_schema_version",
        "checkpoint_id",
        "result_hash",
        "proposal_hash",
        "result_envelope_hash",
        "agent_run_manifest_id",
        "agent_run_manifest_hash",
        "isolated_domain_db_binding_hash",
        "committed_at",
        "receipt_hash",
    } <= set(receipt["required"])


def test_plan_has_exactly_five_slices_gates_drain_and_unified_db_assertions() -> None:
    plan = PLAN_PATH.read_text(encoding="utf-8")
    assert plan.count("### P9-S") == 5
    for slice_id in ("P9-S1", "P9-S2", "P9-S3", "P9-S4", "P9-S5"):
        assert slice_id in plan
    for marker in (
        "## P9.0 Entry Gate",
        "## Unified Isolated Checkpoint",
        "## Required Database Assertions",
        "## Rollback And Drain",
        "TARGET_E2E_CANDIDATE",
        "JAVA_FINALIZER_ONLY",
        "production_checkpoint: PENDING_EXTERNAL",
        "promotion_gate: PENDING",
    ):
        assert marker in plan

    batches = yaml.safe_load(BATCH_PATH.read_text(encoding="utf-8"))
    assert batches["phase"] == 9
    assert batches["gate"]["status"] == "NOT_RUN"
    assert batches["gate"]["runtime_activation"] == "BLOCKED"
    assert list(batches["slices"]) == ["P9-S1", "P9-S2", "P9-S3", "P9-S4", "P9-S5"]
    assert list(batches["batches"]) == [
        "batch_0_contract",
        "batch_1_foundation_focused",
        "batch_2_room_verticals_focused",
        "batch_3_recovery_focused",
        "batch_4_unified_isolated_e2e",
    ]
    assertions = batches["database_assertions"]
    assert set(assertions) == {
        "activation_control",
        "domain",
        "graph",
        "privileges",
        "temporal",
    }
    assert "ALL_TARGET_EPOCHS_TEMPORAL_READY_OR_TERMINAL_WITH_NON_NULL_WORKFLOW_RUN_AND_BUILD_IDS" in assertions["domain"]
    assert "ZERO_LEGACY_WORKER_RUNS_FOR_TARGET_CASE" in assertions["domain"]
    assert "EVERY_ROW_BINDS_TARGET_E2E_LANE_AND_ACTIVATION_ID" in assertions["graph"]


def test_adr_and_contract_pack_separate_engineering_from_production() -> None:
    adr = ADR_PATH.read_text(encoding="utf-8")
    pack = PACK_PATH.read_text(encoding="utf-8")
    for text in (adr, pack):
        assert "Graph" in text and "proposal" in text.lower()
        assert "Java Finalizer" in text
        assert "LEGACY" in text and "DISABLED" in text
        assert "PENDING_EXTERNAL" in text
        assert "PENDING_PROMOTION" in text
        assert "production" in text.lower()
    assert "Production authorization: NONE" in adr
    assert "P9.0: NOT_RUN" in pack
    assert "runtime_activation: BLOCKED" in pack
    assert "X-AfterSaleFlow-Target-E2E-Activation" in pack


def test_examples_contain_no_secret_bearing_members() -> None:
    forbidden_keys = {
        "password",
        "secret",
        "token",
        "privateKey",
        "private_key",
        "connectionString",
        "jdbcUrl",
        "databaseUrl",
        "compactJws",
        "signature",
    }

    def visit(value: object) -> None:
        if isinstance(value, dict):
            assert not (set(value) & forbidden_keys)
            for nested in value.values():
                visit(nested)
        elif isinstance(value, list):
            for nested in value:
                visit(nested)

    for path in CONTRACT_ROOT.glob("fixtures/**/*.json"):
        visit(_json(path))
