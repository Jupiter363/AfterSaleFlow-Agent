from functools import lru_cache

from pydantic import Field
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
    llm_timeout_seconds: float = Field(default=120.0, gt=0, le=300)
    langfuse_host: str = "http://langfuse:3000"
    langfuse_public_key: str = Field(min_length=8)
    langfuse_secret_key: str = Field(min_length=8)
    langfuse_enabled: bool = True
    java_api_service_url: str = "http://java-api-service:8080"
    python_agent_service_secret: str = Field(min_length=16)
    prompt_version: str = "hearing-v1"
    enable_sensitive_log_masking: bool = True


@lru_cache
def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]
