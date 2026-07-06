import pytest
from pydantic import ValidationError

from app.config import Settings
from app.main import _build_evaluation_workflow


def test_settings_resolve_deepseek_direct_client_from_default_llm_env() -> None:
    settings = Settings(
        litellm_base_url="http://litellm-proxy:4000",
        litellm_model="proxy-model",
        litellm_master_key="test-litellm-master-key",
        default_llm_provider="deepseek",
        default_llm_model="deepseek-v4-flash",
        default_llm_api_base="https://api.deepseek.com",
        deepseek_api_key="test-deepseek-api-key",
        langfuse_public_key="pk-test-key",
        langfuse_secret_key="sk-test-secret",
        python_agent_service_secret="test-agent-service-secret",
        langfuse_enabled=False,
    )

    assert settings.resolved_llm_base_url == "https://api.deepseek.com"
    assert settings.resolved_llm_model == "deepseek-v4-flash"
    assert settings.resolved_llm_api_key == "test-deepseek-api-key"


def test_deepseek_direct_mode_requires_deepseek_api_key() -> None:
    with pytest.raises(ValidationError):
        Settings(
            litellm_base_url="http://litellm-proxy:4000",
            litellm_model="proxy-model",
            litellm_master_key="test-litellm-master-key",
            default_llm_provider="deepseek",
            default_llm_model="deepseek-v4-flash",
            default_llm_api_base="https://api.deepseek.com",
            deepseek_api_key=None,
            langfuse_public_key="pk-test-key",
            langfuse_secret_key="sk-test-secret",
            python_agent_service_secret="test-agent-service-secret",
            langfuse_enabled=False,
        )


def test_default_evaluation_workflow_builder_returns_workflow() -> None:
    settings = Settings(
        litellm_master_key="test-litellm-master-key",
        langfuse_public_key="pk-test-key",
        langfuse_secret_key="sk-test-secret",
        python_agent_service_secret="test-agent-service-secret",
        langfuse_enabled=False,
    )

    workflow = _build_evaluation_workflow(settings)

    assert workflow is not None
