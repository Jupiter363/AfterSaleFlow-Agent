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
    minio_endpoint: str = "http://minio:9000"
    minio_root_user: str
    minio_root_password: str
    java_api_service_url: str = "http://java-api-service:8080"
    ocr_service_secret: str = Field(min_length=16)
    elasticsearch_url: str = "http://elasticsearch:9200"
    ocr_temp_dir: str = "/var/lib/ocr-parser"
    log_level: str = "INFO"
    enable_sensitive_log_masking: bool = True


@lru_cache
def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]
