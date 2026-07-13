# 文件作用：Python Agent 服务代码文件，承载售后争议智能体的 API、配置、模型调用或业务流程。

from functools import lru_cache
from typing import ClassVar

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """服务配置模型。

    BaseSettings 来自 pydantic-settings：字段会自动从环境变量和 .env 文件读取。
    例如 litellm_base_url 可由 LITELLM_BASE_URL 覆盖。
    """

    QWEN_MODEL: ClassVar[str] = "qwen3.7-plus"

    model_config = SettingsConfigDict(
        # .env 是本地开发配置文件；生产环境通常由容器环境变量注入。
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
        env_ignore_empty=True,
    )

    app_env: str = "local"
    litellm_base_url: str = "http://litellm-proxy:4000"
    litellm_model: str = QWEN_MODEL
    litellm_master_key: str = Field(min_length=16)
    # Field(...) 可以声明校验规则；这里要求超时 >0 且 <=300 秒。
    llm_timeout_seconds: float = Field(default=120.0, gt=0, le=300)
    langfuse_host: str = "http://langfuse:3000"
    langfuse_public_key: str = Field(min_length=8)
    langfuse_secret_key: str = Field(min_length=8)
    langfuse_enabled: bool = True
    java_api_service_url: str = "http://java-api-service:8080"
    java_service_secret: str = Field(min_length=16)
    python_agent_service_secret: str = Field(min_length=16)
    prompt_version: str = "hearing-v1"
    evaluation_prompt_version: str = "evaluation-v1"
    enable_sensitive_log_masking: bool = True

    # 所属模块：Python 支撑模块 > config；函数角色：只读派生属性。
    # 具体功能：`resolved_llm_base_url` 当前实际使用的 LLM 网关地址。保留 resolved_* 命名便于未来兼容多配置来源。
    # 上下游：上游为 相邻模块输入；下游为 结构化调用结果。
    # 系统意义：把不确定模型能力限制在确定性系统边界内：接口稳定、错误显式、不绕过权限审计。
    @property
    def resolved_llm_base_url(self) -> str:
        """当前实际使用的 LLM 网关地址。保留 resolved_* 命名便于未来兼容多配置来源。"""

        return self.litellm_base_url

    # 所属模块：Python 支撑模块 > config；函数角色：只读派生属性。
    # 具体功能：`resolved_llm_model` 读取并按案件、角色或会话范围筛选结构化模型调用。
    # 上下游：上游为 相邻模块输入；下游为 结构化调用结果。
    # 系统意义：把不确定模型能力限制在确定性系统边界内：接口稳定、错误显式、不绕过权限审计。
    @property
    def resolved_llm_model(self) -> str:
        return self.litellm_model

    # 所属模块：Python 支撑模块 > config；函数角色：只读派生属性。
    # 具体功能：`resolved_llm_api_key` 读取并按案件、角色或会话范围筛选结构化模型调用。
    # 上下游：上游为 相邻模块输入；下游为 结构化调用结果。
    # 系统意义：把不确定模型能力限制在确定性系统边界内：接口稳定、错误显式、不绕过权限审计。
    @property
    def resolved_llm_api_key(self) -> str:
        return self.litellm_master_key


# 所属模块：Python 支撑模块 > config；函数角色：模块公开业务函数。
# 具体功能：`get_settings` 读取配置并缓存；关键协作调用：`Settings`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `Settings`。
# 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
@lru_cache
def get_settings() -> Settings:
    """读取配置并缓存。

    @lru_cache 是装饰器：第一次调用会创建 Settings，后续调用直接复用，
    避免每个请求都重复解析 .env。
    """

    return Settings()  # type: ignore[call-arg]
