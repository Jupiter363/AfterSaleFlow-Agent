from app.config import Settings
from app.main import _build_evaluation_workflow


def test_settings_resolve_qwen_only_through_litellm() -> None:
    settings = Settings(
        litellm_base_url="http://litellm-proxy:4000",
        litellm_model="qwen3.7-plus",
        litellm_master_key="test-litellm-master-key",
        langfuse_public_key="pk-test-key",
        langfuse_secret_key="sk-test-secret",
        python_agent_service_secret="test-agent-service-secret",
        langfuse_enabled=False,
    )

    assert settings.resolved_llm_base_url == "http://litellm-proxy:4000"
    assert settings.resolved_llm_model == "qwen3.7-plus"
    assert settings.resolved_llm_api_key == "test-litellm-master-key"


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
