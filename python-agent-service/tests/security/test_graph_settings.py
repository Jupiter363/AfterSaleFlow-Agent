from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.config import Settings


BASE = {
    "litellm_master_key": "test-litellm-master-key",
    "langfuse_public_key": "test-public-key",
    "langfuse_secret_key": "test-secret-key",
    "java_service_secret": "test-java-service-secret",
    "python_agent_service_secret": "test-python-service-secret",
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

    configured = settings(
        graph_gateway_mode="SHADOW",
        graph_database_dsn="postgresql://graph_runtime:secret@postgresql:5432/dispute_graph",
        graph_jwks_url="http://java-api-service:8080/.well-known/graph-jwks.json",
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
