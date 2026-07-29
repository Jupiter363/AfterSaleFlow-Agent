from __future__ import annotations

from pathlib import Path

import pytest
from pydantic import ValidationError

from app.config import GraphTargetE2EBindingSettings, Settings


BASE = {
    "litellm_master_key": "test-litellm-master-key",
    "langfuse_public_key": "test-public-key",
    "langfuse_secret_key": "test-secret-key",
    "java_service_secret": "test-java-service-secret",
    "python_agent_service_secret": "test-python-service-secret",
}
GRAPH_GENERATION = {
    "graph_expected_environment_generation": "graphenv-test-001",
    "graph_expected_restore_verification_hash": "a" * 64,
}


def settings(**overrides) -> Settings:
    return Settings(**{**BASE, **overrides})


def test_graph_runtime_is_disabled_without_opening_or_requiring_dependencies() -> None:
    configured = settings()

    assert configured.graph_gateway_mode == "DISABLED"
    assert configured.graph_database_dsn is None
    assert configured.graph_jwks_url is None


def test_shadow_requires_isolated_runtime_dsn_and_jwks() -> None:
    with pytest.raises(ValidationError, match="graph_database_dsn"):
        settings(graph_gateway_mode="SHADOW")

    with pytest.raises(ValidationError, match="graph_jwks_url"):
        settings(
            graph_gateway_mode="SHADOW",
            graph_database_dsn=(
                "postgresql://graph_runtime:secret@postgresql:5432/dispute_graph"
            ),
        )

    with pytest.raises(ValidationError, match="graph_expected_environment_generation"):
        settings(
            graph_gateway_mode="SHADOW",
            graph_database_dsn="postgresql://graph_runtime:secret@postgresql:5432/dispute_graph",
            graph_jwks_url="http://java-api-service:8080/.well-known/graph-jwks.json",
        )

    with pytest.raises(ValidationError, match="graph_expected_restore_verification_hash"):
        settings(
            graph_gateway_mode="SHADOW",
            graph_database_dsn="postgresql://graph_runtime:secret@postgresql:5432/dispute_graph",
            graph_jwks_url="http://java-api-service:8080/.well-known/graph-jwks.json",
            graph_expected_environment_generation="graphenv-test-001",
        )

    configured = settings(
        graph_gateway_mode="SHADOW",
        graph_database_dsn="postgresql://graph_runtime:secret@postgresql:5432/dispute_graph",
        graph_jwks_url="http://java-api-service:8080/.well-known/graph-jwks.json",
        **GRAPH_GENERATION,
    )
    assert configured.graph_database_dsn is not None
    assert "graph_runtime:secret" not in repr(configured)
    assert configured.graph_database_dsn.get_secret_value().startswith("postgresql://")


@pytest.mark.parametrize(
    "dsn",
    [
        "postgresql://graph_owner:secret@postgresql:5432/dispute_graph",
        "postgresql://graph_migrator:secret@postgresql:5432/dispute_graph",
        "postgresql://graph_runtime:secret@postgresql:5432/dispute_system",
        "mysql://graph_runtime:secret@postgresql:5432/dispute_graph",
        "postgresql://graph_runtime:secret@postgresql:5432/dispute_graph?options=-csearch_path%3Dpublic",
        "postgresql://graph_runtime:secret@postgresql:5432/dispute_graph#public",
    ],
)
def test_shadow_rejects_owner_migrator_domain_and_search_path_dsn(dsn: str) -> None:
    with pytest.raises(ValidationError, match="graph_database_dsn"):
        settings(
            graph_gateway_mode="SHADOW",
            graph_database_dsn=dsn,
            graph_jwks_url="http://java-api-service:8080/.well-known/graph-jwks.json",
            **GRAPH_GENERATION,
        )


@pytest.mark.parametrize(
    "overrides",
    [
        {"graph_gateway_mode": "FORMAL"},
        {"graph_database_schema": "public"},
        {"graph_database_user": "Graph-Runtime"},
        {"graph_pool_min_size": 17, "graph_pool_max_size": 16},
        {"graph_pool_max_waiting": 15, "graph_pool_max_size": 16},
        {"app_env": "production", "graph_pool_min_size": 0},
        {"graph_expected_spiffe_id": "java-api-service"},
    ],
)
def test_graph_configuration_rejects_formal_mode_and_unsafe_bounds(overrides) -> None:
    with pytest.raises(ValidationError):
        settings(**overrides)


def _target_binding() -> GraphTargetE2EBindingSettings:
    return GraphTargetE2EBindingSettings(
        graph_key="all-rooms.target-e2e.v1",
        graph_version="target-e2e-graph.2026-07-27.1",
        checkpoint_schema_version="target-e2e-checkpoint.v1",
        state_schema_version="target-e2e-room-state.v1",
        state_schema_hash="b" * 64,
        command_schema_version="room-graph-command.v1",
        result_schema_version="room-graph-result.v1",
        agent_profile_id="dispute-intake-officer.v1",
        prompt_version="intake.user.v1",
        model_profile_id="qwen3.7-plus.structured.v1",
        output_schema_version="target-e2e-room-proposal-source.v1",
        policy_version="intake-policy.v1",
        guardrail_version="intake-guardrail.v1",
        tool_policy_version="tools.none.v1",
        binding_hash="c" * 64,
        code_build_id="candidate-build-1",
        allowed_room_types=("INTAKE", "EVIDENCE", "HEARING", "REVIEW"),
        allowed_stage_codes=("INTAKE_OPEN",),
    )


def _target_settings(**overrides) -> Settings:
    values = {
        "graph_gateway_mode": "TARGET_E2E_CANDIDATE",
        "graph_database_dsn": (
            "postgresql://graph_runtime:secret@postgresql:5432/dispute_graph"
        ),
        "graph_jwks_url": "http://java-api-service:8080/.well-known/graph-jwks.json",
        "graph_expected_environment_generation": "7",
        "graph_expected_restore_verification_hash": "a" * 64,
        "graph_target_e2e_isolated": True,
        "target_e2e_activation_manifest_hash": "e" * 64,
        "graph_target_e2e_bindings": (_target_binding(),),
        "graph_target_e2e_runtime_context": {
            "schemaVersion": "graph-target-e2e-runtime-context.v1",
            "executionLane": "TARGET_E2E_CANDIDATE",
            "activationId": f"p9act.v1.{'1' * 32}",
            "activationManifestHash": "e" * 64,
            "environmentId": "target-e2e-local",
            "environmentGeneration": 7,
            "candidateSha": "d" * 40,
            "issuedAt": "2026-07-27T10:00:00Z",
            "expiresAt": "2026-07-27T11:00:00Z",
            "runNonce": "runtime-projection-nonce-0123456789abcdef",
            "tenantSurrogate": "tenant-p9-isolated",
            "caseScope": {
                "mode": "EXPLICIT_CASE_IDS",
                "allowedCaseIds": ["case-p9-001"],
            },
            "allowedRoomTypes": ["INTAKE"],
            "composeProject": "p9_target_e2e",
            "temporalNamespace": "target-e2e-p9",
            "buildBindings": {
                "caseBuildId": "case-build-1",
                "controlBuildId": "control-build-1",
                "agentBuildId": "agent-build-1",
            },
            "imageDigests": {
                "javaApi": f"sha256:{'1' * 64}",
                "temporalControlWorker": f"sha256:{'2' * 64}",
                "temporalAgentWorker": f"sha256:{'3' * 64}",
                "pythonAgent": f"sha256:{'4' * 64}",
                "frontend": f"sha256:{'5' * 64}",
            },
            "databaseIdentities": {
                "domain": {
                    "service": "domain-db",
                    "database": "isolated_domain",
                    "schema": "domain_runtime",
                    "expectedUser": "java_domain_runtime",
                },
                "graph": {
                    "service": "postgresql",
                    "database": "dispute_graph",
                    "schema": "graph_runtime",
                    "runtimeUser": "graph_runtime",
                    "environmentGeneration": 7,
                    "restoreVerificationHash": "a" * 64,
                },
            },
            "trustedSigningKeyIds": ["java-command-key-1"],
            "perCommandManifestAllowed": False,
        },
    }
    values.update(overrides)
    return settings(**values)


def test_target_e2e_mode_is_explicit_isolated_and_fully_bound() -> None:
    configured = _target_settings()

    assert configured.graph_gateway_mode == "TARGET_E2E_CANDIDATE"
    assert configured.graph_target_e2e_isolated is True
    assert configured.graph_target_e2e_runtime_context is not None

    with pytest.raises(ValidationError, match="restricted"):
        _target_settings(app_env="production")
    with pytest.raises(ValidationError, match="isolated non-secret runtime context"):
        _target_settings(graph_target_e2e_isolated=False)
    with pytest.raises(ValidationError, match="exact activation manifest hash"):
        _target_settings(target_e2e_activation_manifest_hash=None)
    with pytest.raises(ValidationError, match="cannot relabel SHADOW"):
        _target_settings(graph_shadow_bindings=(_target_binding(),))
    with pytest.raises(ValidationError, match="Graph DB settings"):
        _target_settings(graph_expected_environment_generation="8")


def test_checkpoint_recovery_barrier_is_target_only_and_uses_the_fixed_mount() -> None:
    with pytest.raises(ValidationError, match="directory requires"):
        _target_settings(
            graph_target_e2e_checkpoint_barrier_directory=(
                "/run/target-e2e/python/recovery-barrier"
            )
        )
    with pytest.raises(ValidationError, match="isolated target-E2E mount"):
        _target_settings(
            graph_target_e2e_checkpoint_barrier_enabled=True,
            graph_target_e2e_checkpoint_barrier_directory=(
                "/run/target-e2e/python/recovery-barrier"
            ),
        )
    with pytest.raises(ValidationError, match="TARGET_E2E_CANDIDATE"):
        settings(graph_target_e2e_checkpoint_barrier_enabled=True)

    configured = _target_settings(
        app_env="target-e2e",
        graph_target_e2e_checkpoint_barrier_enabled=True,
        graph_target_e2e_checkpoint_barrier_directory=(
            "/run/target-e2e/python/recovery-barrier"
        ),
    )
    assert configured.graph_target_e2e_checkpoint_barrier_enabled is True
    assert configured.graph_target_e2e_checkpoint_barrier_directory == Path(
        "/run/target-e2e/python/recovery-barrier"
    )
