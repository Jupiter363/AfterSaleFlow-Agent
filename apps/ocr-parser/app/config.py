# 文件作用：OCR 解析服务代码文件，负责证据文件解析、外部调用、数据模型或接口处理。

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


# 所属模块：Python 支撑模块 > config；函数角色：模块公开业务函数。
# 具体功能：`get_settings` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`Settings`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `Settings`。
# 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
@lru_cache
def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]
