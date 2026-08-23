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
        target = target[int(part)] if isinstance(target, list) else target[part]
    final = int(parts[-1]) if isinstance(target, list) else parts[-1]
    target[final] = value


def _semantic_errors(manifest: dict[str, Any], context: dict[str, Any]) -> set[str]:
    errors: set[str] = set()
    issued_at = _instant(manifest["issuedAt"])
    expires_at = _instant(manifest["expiresAt"])
    now = _instant(context["validationTime"])

    if issued_at > now:
        errors.add("MANIFEST_ISSUED_IN_FUTURE")
    if expires_at <= issued_at:
        errors.add("MANIFEST_EXPIRY_ORDER_INVALID")
    if (expires_at - issued_at).total_seconds() > 2_592_000:
        errors.add("MANIFEST_LIFETIME_EXCEEDED")
    if expires_at <= now:
        errors.add("MANIFEST_EXPIRED")
    exact_registered_attach = False
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
            else:
                exact_registered_attach = True
    if (
        not exact_registered_attach
        and manifest["environmentGeneration"] <= context["environmentGenerationHighWater"]
    ):
        errors.add("ENVIRONMENT_GENERATION_NOT_MONOTONIC")

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
        fixture_context = context["syntheticFixtureSet"]
        fixture_document = context.get("syntheticFixtureDocument")
        if fixture_document is None:
            fixture_document = _json(CONTRACT_ROOT / fixture_context["path"])
        fixture_hash = _canonical_hash(fixture_document)
        if not (
            fixture_hash == fixture_context["canonicalHash"] == scope["fixtureSetHash"]
            and fixture_document["fixtureSetId"] == scope["fixtureSetId"]
            and fixture_document["caseIdPrefix"] == scope["caseIdPrefix"]
            and fixture_document["maximumCases"] == scope["maxCases"]
            and fixture_document["roomTypes"] == manifest["allowedRoomTypes"]
        ):
            errors.add("SYNTHETIC_FIXTURE_HASH_MISMATCH")
        for reservation in context["registeredGeneratedCases"]:
            if reservation["generatedCaseId"] == context["requestedSyntheticCaseId"]:
                exact_reservation = (
                    reservation["activationId"] == manifest["activationId"]
                    and reservation["slotNumber"] == context["requestedSyntheticSlot"]
                )
                if not exact_reservation:
                    errors.add("GENERATED_CASE_ID_GLOBAL_CONFLICT")

    graph_without_hash = _without(manifest["graphBinding"], "bindingHash")
    if _canonical_hash(graph_without_hash) != manifest["graphBinding"]["bindingHash"]:
        errors.add("GRAPH_BINDING_HASH_MISMATCH")
    if _canonical_hash(_without(manifest, "manifestHash")) != manifest["manifestHash"]:
        errors.add("MANIFEST_HASH_MISMATCH")

    domain = manifest["databaseIdentities"]["domain"]
    graph = manifest["databaseIdentities"]["graph"]
    if (
        domain["clusterIdentity"] == graph["clusterIdentity"]
        or domain["databaseIdentity"] == graph["databaseIdentity"]
    ):
        errors.add("DATABASE_PHYSICAL_ISOLATION_VIOLATION")

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

    synthetic_manifest = _json(fixtures[1])
    idempotent_context = copy.deepcopy(context)
    idempotent_context["registeredGeneratedCases"] = [
        {
            "generatedCaseId": idempotent_context["requestedSyntheticCaseId"],
            "activationId": synthetic_manifest["activationId"],
            "slotNumber": idempotent_context["requestedSyntheticSlot"],
        }
    ]
    assert not _semantic_errors(synthetic_manifest, idempotent_context)


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


def test_target_graph_key_is_compatible_with_frozen_room_graph_command_v1() -> None:
    activation_schema = _json(SCHEMA_PATH)
    target_key = activation_schema["$defs"]["graphBinding"]["properties"]["key"]["const"]
    command_schema = _json(
        ROOT / "contracts/agent-platform/v1/room-graph-command.schema.json"
    )
    graph_key_schema = command_schema["$defs"]["identifier"]

    assert target_key == "all-rooms.target-e2e.v2"
    assert not list(jsonschema.Draft202012Validator(graph_key_schema).iter_errors(target_key))
    assert command_schema["properties"]["graph_key"] == {"$ref": "#/$defs/identifier"}

    manifests = [
        _json(VALID_ROOT / "target-e2e-activation-allowlist-valid.json"),
        _json(VALID_ROOT / "target-e2e-activation-synthetic-valid.json"),
    ]
    assert all(manifest["graphBinding"]["key"] == target_key for manifest in manifests)
    assert _json(CONTEXT_PATH)["graphBinding"]["key"] == target_key
    assert all(
        manifest["productionDefaults"]
        == {"formalCaseSelector": "LEGACY", "targetE2EActivation": "DISABLED"}
        for manifest in manifests
    )

    incompatible_key = "all-rooms" + "/" + "target-e2e.v1"
    assert all(
        incompatible_key not in path.read_text(encoding="utf-8")
        for path in CONTRACT_ROOT.rglob("*")
        if path.is_file()
    )


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
        if "syntheticFixtureDocumentMutation" in case:
            fixture_document = _json(
                CONTRACT_ROOT / context["syntheticFixtureSet"]["path"]
            )
            mutation = case["syntheticFixtureDocumentMutation"]
            _set_pointer(fixture_document, mutation["pointer"], mutation["value"])
            context["syntheticFixtureDocument"] = fixture_document
        if "registeredGeneratedCase" in case:
            context["registeredGeneratedCases"].append(case["registeredGeneratedCase"])
        if "environmentGenerationHighWater" in case:
            context["environmentGenerationHighWater"] = case[
                "environmentGenerationHighWater"
            ]
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
        "wrong-synthetic-fixture-hash",
        "wrong-synthetic-fixture-bytes",
        "cross-activation-generated-case-reuse",
        "concurrent-generated-case-slot-conflict",
        "domain-graph-cluster-collision",
        "domain-graph-database-collision",
        "stale-environment-generation-high-water",
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
        "SYNTHETIC_FIXTURE_HASH_MISMATCH",
        "GENERATED_CASE_ID_GLOBAL_CONFLICT",
        "DATABASE_PHYSICAL_ISOLATION_VIOLATION",
        "ENVIRONMENT_GENERATION_NOT_MONOTONIC",
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
    context["environmentGenerationHighWater"] = manifest["environmentGeneration"]
    assert not _semantic_errors(manifest, context)

    conflicting = copy.deepcopy(context)
    conflicting["registeredGrants"][0]["environmentGeneration"] += 1
    assert "NONCE_REPLAY" in _semantic_errors(manifest, conflicting)


def test_policy_freezes_transport_replay_authority_and_exact_bindings() -> None:
    policy = _json(POLICY_PATH)
    assert policy["contractVersion"] == "target-e2e-activation.v1"
    assert policy["executionLane"] == "TARGET_E2E_CANDIDATE"
    assert policy["timePolicy"] == {
        "maximumLifetimeSeconds": 2_592_000,
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
        "reservationPrimaryKey": ["activationId", "slotNumber"],
        "generatedCaseIdUniqueScope": "GLOBAL_NOT_PARTITIONED_BY_ACTIVATION_OR_ENVIRONMENT",
        "generatedCaseIdTombstone": "DURABLE_NEVER_REASSIGN",
        "tombstoneRetention": "THROUGH_ENVIRONMENT_DECOMMISSION_AUDIT",
        "maximumCases": 16,
        "prefixWildcardAllowed": False,
        "existingCaseIdReservationResult": "IDEMPOTENT_ONLY_FOR_SAME_ACTIVATION_SLOT",
        "crossActivationOrConcurrentDifferentSlotSameIdResult": "REJECT_GENERATED_CASE_ID_GLOBAL_CONFLICT",
        "wrongPrefixFixtureHashOrExhaustedCapacityResult": "REJECT",
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
        "room_fencing_token",
        "command_hash",
        "command_envelope_hash",
    ]
    assert command["roomFenceIsGraphLeaseFence"] is False
    assert command["beforeCheckpointMutation"].startswith(
        "VERIFY_CURRENT_ROOM_FENCING_TOKEN"
    )
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
        "target-e2e-isolated-domain-db-binding.schema.json",
        "target-e2e-room-proposal-source.schema.json",
        "target-e2e-synthetic-fixture-set.schema.json",
    }
    for schema in schemas.values():
        jsonschema.validators.validator_for(schema).check_schema(schema)

    command = schemas["target-e2e-graph-command-envelope.schema.json"]
    assert command["required"] == [
        "schema_version",
        "execution_lane",
        "activation_id",
        "room_fencing_token",
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
        "room_fencing_token",
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
        "room_fencing_token",
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
        "room_fencing_token",
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
    assert receipt["properties"]["domain_commit_status"] == {"const": "COMMITTED"}
    assert receipt["x-replay"]["sameIdentitySameHashes"] == (
        "RETURN_ORIGINAL_COMMITTED_RECEIPT_EXACT_BYTES_AND_HASH"
    )
    assert set(receipt["x-hash-source-bindings"]) == {
        "agent_run_manifest_hash",
        "isolated_domain_db_binding_hash",
        "proposal_hash",
    }


def test_fixture_database_manifest_and_all_room_proposal_hash_sources_are_exact() -> None:
    activation = _json(VALID_ROOT / "target-e2e-activation-synthetic-valid.json")
    context = _json(CONTEXT_PATH)

    fixture_schema = _json(CONTRACT_ROOT / "target-e2e-synthetic-fixture-set.schema.json")
    fixture_document = _json(
        CONTRACT_ROOT / "fixtures/synthetic/p9-synthetic-all-rooms-001.json"
    )
    fixture_validator = jsonschema.Draft202012Validator(fixture_schema)
    assert not list(fixture_validator.iter_errors(fixture_document))
    fixture_hash = _canonical_hash(fixture_document)
    assert fixture_hash == activation["caseScope"]["fixtureSetHash"]
    assert fixture_hash == context["syntheticFixtureSet"]["canonicalHash"]
    assert fixture_document["fixtureSetId"] == activation["caseScope"]["fixtureSetId"]
    assert fixture_document["caseIdPrefix"] == activation["caseScope"]["caseIdPrefix"]
    assert fixture_document["maximumCases"] == activation["caseScope"]["maxCases"]
    assert set(fixture_document["roomTypes"]) == {
        "INTAKE",
        "EVIDENCE",
        "HEARING",
        "REVIEW",
    }

    db_schema = _json(CONTRACT_ROOT / "target-e2e-isolated-domain-db-binding.schema.json")
    db_binding = _json(VALID_ROOT / "target-e2e-isolated-domain-db-binding-valid.json")
    assert not list(jsonschema.Draft202012Validator(db_schema).iter_errors(db_binding))
    assert _canonical_hash(_without(db_binding, "binding_hash")) == db_binding["binding_hash"]
    domain = activation["databaseIdentities"]["domain"]
    assert db_binding["cluster_identity"] == domain["clusterIdentity"]
    assert db_binding["database_identity"] == domain["databaseIdentity"]
    assert db_binding["runtime_principal_identity"] == domain["runtimePrincipalIdentity"]
    graph = activation["databaseIdentities"]["graph"]
    assert domain["clusterIdentity"] != graph["clusterIdentity"]
    assert domain["databaseIdentity"] != graph["databaseIdentity"]

    proposal_schema = _json(CONTRACT_ROOT / "target-e2e-room-proposal-source.schema.json")
    proposal_validator = jsonschema.Draft202012Validator(proposal_schema)
    proposals = sorted(VALID_ROOT.glob("target-e2e-*-proposal-source-valid.json"))
    assert len(proposals) == 4
    proposal_hashes: dict[str, str] = {}
    for path in proposals:
        source = _json(path)
        assert not list(proposal_validator.iter_errors(source)), path.name
        proposal_hashes[source["room_type"]] = _canonical_hash(source["proposal"])
        assert source["proposal"]["formal_authority"] is False
    assert set(proposal_hashes) == {"INTAKE", "EVIDENCE", "HEARING", "REVIEW"}
    assert len(set(proposal_hashes.values())) == 4

    manifest_schema = _json(
        ROOT / "contracts/agent-platform/v1/agent-execution-manifest.schema.json"
    )
    manifest_fixture = _json(
        ROOT
        / "contracts/agent-platform/v1/fixtures/valid/agent-execution-manifest-valid.json"
    )["instance"]
    assert not list(
        jsonschema.Draft202012Validator(manifest_schema).iter_errors(manifest_fixture)
    )
    assert len(_canonical_hash(manifest_fixture)) == 64


def test_generation_high_water_expiry_drain_and_terminal_order_are_frozen() -> None:
    policy = _json(POLICY_PATH)
    generation = policy["environmentGenerationPolicy"]
    assert generation == {
        "scope": "DURABLE_HIGH_WATER_PER_ENVIRONMENT_ID",
        "newRegistrationRule": "environmentGeneration > durableHighWater",
        "update": "ATOMIC_WITH_FIRST_ACTIVATION_REGISTRATION",
        "identicalReplicaAttachAtHighWater": "ALLOWED",
        "sameGenerationDifferentGrant": "REJECT_ENVIRONMENT_GENERATION_CONFLICT",
        "lowerGeneration": "REJECT_ENVIRONMENT_GENERATION_STALE",
        "reuseAfterDrainOrRevoke": "FORBIDDEN",
    }
    drain = policy["expiryAndDrainPolicy"]
    assert drain["lifecycleOrder"] == [
        "REGISTERED",
        "ACTIVE",
        "DRAIN_ONLY",
        "DRAINED",
        "REVOKED_TERMINAL",
    ]
    assert drain["expiryTransition"] == "ACTIVE_TO_DRAIN_ONLY"
    assert drain["newCaseAdmissionAfterExpiry"] is False
    assert drain["newCommandAdmissionAfterExpiry"] is False
    assert drain["identicalReplicaAttachAfterExpiry"] == "DRAIN_ACCEPTED_COMMAND_ONLY"
    assert drain["continuedCommandProof"] == [
        "command_id",
        "command_hash",
        "command_envelope_hash",
        "room_epoch",
        "room_fencing_token",
        "admitted_at_before_expires_at",
    ]
    assert drain["drainedAcceptsOrExecutesWork"] is False
    assert drain["terminalState"] == "REVOKED_TERMINAL"
    assert drain["timestampOrder"] == (
        "expires_at <= drain_only_at <= drained_at < revoked_at"
    )


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
    assert batches["graph_command"]["room_fence_is_graph_lease_fence"] is False
    assert batches["graph_command"]["finalization_receipt_status"] == "COMMITTED"
    assert batches["drain_policy"]["lifecycle"] == [
        "REGISTERED",
        "ACTIVE",
        "DRAIN_ONLY",
        "DRAINED",
        "REVOKED_TERMINAL",
    ]
    assert "DOMAIN_AND_GRAPH_CLUSTER_IDENTITIES_PHYSICALLY_DIFFERENT" in assertions[
        "privileges"
    ]


def test_adr_and_contract_pack_separate_engineering_from_production() -> None:
    adr = ADR_PATH.read_text(encoding="utf-8")
    pack = PACK_PATH.read_text(encoding="utf-8")
    plan = PLAN_PATH.read_text(encoding="utf-8")
    for text in (adr, pack, plan):
        assert "Graph" in text and "proposal" in text.lower()
        assert "Java Finalizer" in text
        assert "LEGACY" in text and "DISABLED" in text
        assert "PENDING_EXTERNAL" in text
        assert "PENDING_PROMOTION" in text
        assert "production" in text.lower()
        for marker in (
            "room_fencing_token",
            "REVOKED_TERMINAL",
            "agent_run_manifest_hash",
            "isolated_domain_db_binding_hash",
            "proposal_hash",
            "ALREADY_COMMITTED",
        ):
            assert marker in text
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


def test_local_source_activation_binds_worktree_without_replacing_graph_identity() -> (
    None
):
    provisioner = (ROOT / ".local-dev" / "provision-local-target.py").read_text(
        encoding="utf-8"
    )
    launcher = (ROOT / ".local-dev" / "launch-source.ps1").read_text(
        encoding="utf-8"
    )

    assert '"compiledWorktreeBinding": compiled_worktree_binding' in provisioner
    assert '"compiled_worktree_binding": compiled_worktree_binding' in provisioner
    assert (
        "target_binding, registry_hash = provision._target_binding(candidate)"
        in provisioner
    )
    assert "provision._target_binding(compiled_worktree_binding)" not in provisioner
    assert "--compiled-worktree-binding $expectedJavaSourceBinding" in launcher
    assert "Local target activation provisioning did not create fresh authority." in launcher
