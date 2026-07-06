from functools import lru_cache

from pydantic import Field
from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    app_env: str = "local"
    litellm_base_url: str = "http://litellm-proxy:4000"
    litellm_model: str = "deepseek-v4-flash"
    litellm_master_key: str = Field(min_length=16)
    default_llm_provider: str = "litellm"
    default_llm_model: str | None = None
    default_llm_api_base: str | None = None
    deepseek_api_key: str | None = Field(default=None, min_length=8)
    llm_timeout_seconds: float = Field(default=120.0, gt=0, le=300)
    langfuse_host: str = "http://langfuse:3000"
    langfuse_public_key: str = Field(min_length=8)
    langfuse_secret_key: str = Field(min_length=8)
    langfuse_enabled: bool = True
    java_api_service_url: str = "http://java-api-service:8080"
    python_agent_service_secret: str = Field(min_length=16)
    prompt_version: str = "hearing-v1"
    evaluation_prompt_version: str = "evaluation-v1"
    enable_sensitive_log_masking: bool = True

    @model_validator(mode="after")
    def require_provider_specific_credentials(self) -> "Settings":
        if self._uses_deepseek_direct() and not self.deepseek_api_key:
            raise ValueError(
                "DEEPSEEK_API_KEY is required when DEFAULT_LLM_PROVIDER=deepseek"
            )
        return self

    @property
    def resolved_llm_base_url(self) -> str:
        if self._uses_deepseek_direct():
            return self.default_llm_api_base or "https://api.deepseek.com"
        return self.litellm_base_url

    @property
    def resolved_llm_model(self) -> str:
        if self._uses_deepseek_direct():
            return self.default_llm_model or self.litellm_model
        return self.litellm_model

    @property
    def resolved_llm_api_key(self) -> str:
        if self._uses_deepseek_direct():
            return self.deepseek_api_key or ""
        return self.litellm_master_key

    def _uses_deepseek_direct(self) -> bool:
        return self.default_llm_provider.strip().lower() == "deepseek"


@lru_cache
def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]
