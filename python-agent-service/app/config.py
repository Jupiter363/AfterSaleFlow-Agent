# 文件作用：Python Agent 服务代码文件，承载售后争议智能体的 API、配置、模型调用或业务流程。

from functools import lru_cache
import re
from typing import ClassVar, Literal, Self
from urllib.parse import parse_qs, unquote, urlsplit

from pydantic import (
    AnyHttpUrl,
    BaseModel,
    ConfigDict,
    Field,
    SecretStr,
    field_validator,
    model_validator,
)
from pydantic_settings import BaseSettings, SettingsConfigDict


class GraphShadowBindingSettings(BaseModel):
    """Deployment-owned metadata for one fixed synthetic SHADOW graph."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    graph_key: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    graph_version: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    checkpoint_schema_version: str = Field(
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"
    )
    state_schema_version: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    state_schema_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    command_schema_version: Literal["room-graph-command.v1"]
    result_schema_version: Literal["room-graph-result.v1"]
    agent_profile_id: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    prompt_version: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    model_profile_id: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    output_schema_version: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    policy_version: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    guardrail_version: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    tool_policy_version: Literal["tools.none.v1"]
    binding_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    code_build_id: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    allowed_room_types: tuple[
        Literal["INTAKE", "EVIDENCE", "HEARING", "REVIEW"], ...
    ] = Field(min_length=1, max_length=4)
    allowed_stage_codes: tuple[str, ...] = Field(min_length=1, max_length=32)

    @field_validator("allowed_stage_codes")
    @classmethod
    def validate_identifiers(cls, values: tuple[str, ...]) -> tuple[str, ...]:
        identifier = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
        if len(values) != len(set(values)) or any(
            identifier.fullmatch(value) is None for value in values
        ):
            raise ValueError("Graph SHADOW identifiers must be unique bounded wire values")
        return values

    @field_validator("allowed_room_types")
    @classmethod
    def validate_room_types(cls, values: tuple[str, ...]) -> tuple[str, ...]:
        if len(values) != len(set(values)):
            raise ValueError("Graph SHADOW room types must be unique")
        return values


class GraphShadowInputSettings(BaseModel):
    """One immutable, deployment-approved synthetic input reference."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    artifact_id: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    schema_version: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    uri: str = Field(min_length=1, max_length=1024)
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    size_bytes: int = Field(ge=0, le=16 * 1024 * 1024)
    visibility: Literal["PRIVATE", "FORMAL"]

    @field_validator("uri")
    @classmethod
    def validate_input_uri(cls, value: str) -> str:
        parsed = urlsplit(value)
        decoded_path = unquote(parsed.path)
        if (
            parsed.scheme not in {"s3", "minio"}
            or not parsed.netloc
            or parsed.username is not None
            or parsed.password is not None
            or parsed.query
            or parsed.fragment
            or not decoded_path.startswith("/")
            or decoded_path.endswith("/")
            or "\\" in decoded_path
            or "//" in decoded_path
            or any(part in {"", ".", ".."} for part in decoded_path.split("/")[1:])
        ):
            raise ValueError("Graph SHADOW inputs must use canonical s3/minio object URIs")
        return value


class GraphShadowThreadSettings(BaseModel):
    """Exact Java-issued synthetic thread identity and its approved input manifest."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    thread_id: str = Field(pattern=r"^grt\.v1\.[0-9a-f]{32}$")
    tenant_surrogate: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    case_id: str = Field(min_length=1, max_length=64)
    room_type: Literal["INTAKE", "EVIDENCE", "HEARING", "REVIEW"]
    room_epoch: int = Field(ge=0)
    actor_id: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    actor_role: Literal["USER", "MERCHANT", "PLATFORM_REVIEWER", "SYSTEM"]
    audience: Literal["USER", "MERCHANT", "PLATFORM_REVIEWER", "SYSTEM"]
    actor_capabilities: tuple[str, ...] = Field(default=(), max_length=32)
    agent_session_id: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    shared_session: bool = False
    graph_key: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    graph_version: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    checkpoint_schema_version: str = Field(
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"
    )
    request_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    allowed_inputs: tuple[GraphShadowInputSettings, ...] = Field(
        min_length=1,
        max_length=64,
    )

    @field_validator("case_id")
    @classmethod
    def validate_case_id(cls, value: str) -> str:
        if "\x00" in value:
            raise ValueError("Graph SHADOW case ID cannot contain NUL")
        return value

    @field_validator("actor_capabilities")
    @classmethod
    def validate_capabilities(cls, values: tuple[str, ...]) -> tuple[str, ...]:
        identifier = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
        if len(values) != len(set(values)) or any(
            identifier.fullmatch(value) is None for value in values
        ):
            raise ValueError("Graph SHADOW capabilities must be unique wire identifiers")
        return values

    @model_validator(mode="after")
    def validate_scope(self) -> Self:
        party_scope = (
            self.actor_role == "USER" and self.audience == "USER"
        ) or (
            self.actor_role == "MERCHANT" and self.audience == "MERCHANT"
        )
        if self.shared_session:
            if (
                self.room_type != "HEARING"
                or self.actor_role != "SYSTEM"
                or self.audience != "SYSTEM"
                or any(item.visibility != "FORMAL" for item in self.allowed_inputs)
            ):
                raise ValueError(
                    "shared SHADOW Hearing threads require SYSTEM scope and formal inputs"
                )
        elif self.room_type in {"INTAKE", "EVIDENCE", "HEARING"}:
            if not party_scope:
                raise ValueError("private SHADOW room threads require an exact party scope")
        elif (
            self.actor_role != "PLATFORM_REVIEWER"
            or self.audience != "PLATFORM_REVIEWER"
        ):
            raise ValueError("SHADOW Review threads require platform reviewer scope")
        input_keys = {
            (item.artifact_id, item.schema_version, item.uri, item.sha256, item.size_bytes)
            for item in self.allowed_inputs
        }
        if len(input_keys) != len(self.allowed_inputs):
            raise ValueError("Graph SHADOW input manifest contains a duplicate reference")
        return self


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
    graph_gateway_mode: Literal["DISABLED", "SHADOW"] = "DISABLED"
    graph_database_dsn: SecretStr | None = None
    graph_database_name: str = "dispute_graph"
    graph_database_user: str = "graph_runtime"
    graph_database_schema: str = "graph_runtime"
    graph_pool_min_size: int = Field(default=2, ge=0, le=64)
    graph_pool_max_size: int = Field(default=16, ge=1, le=64)
    graph_pool_max_waiting: int = Field(default=64, ge=1, le=1024)
    graph_pool_acquire_timeout_seconds: float = Field(default=3.0, gt=0, le=30)
    graph_pool_max_idle_seconds: float = Field(default=300.0, gt=0, le=3600)
    graph_pool_max_lifetime_seconds: float = Field(default=1800.0, gt=0, le=86400)
    graph_readiness_timeout_seconds: float = Field(default=2.0, gt=0, le=30)
    graph_jwks_url: AnyHttpUrl | None = None
    graph_jwks_refresh_seconds: float = Field(default=30.0, ge=5, le=3600)
    graph_jwks_timeout_seconds: float = Field(default=2.0, gt=0, le=30)
    graph_expected_spiffe_id: str = "spiffe://after-sale-flow/java-api-service"
    graph_expected_environment_generation: str | None = Field(
        default=None,
        min_length=1,
        max_length=64,
    )
    graph_expected_restore_verification_hash: str | None = Field(
        default=None,
        pattern=r"^[0-9a-f]{64}$",
    )
    graph_shadow_bindings: tuple[GraphShadowBindingSettings, ...] = Field(
        default=(),
        max_length=16,
    )
    graph_shadow_threads: tuple[GraphShadowThreadSettings, ...] = Field(
        default=(),
        max_length=64,
    )

    @model_validator(mode="after")
    def validate_graph_runtime(self) -> Self:
        identifier = re.compile(r"^[a-z][a-z0-9_]{0,62}$")
        for name in ("graph_database_name", "graph_database_user", "graph_database_schema"):
            if not identifier.fullmatch(getattr(self, name)):
                raise ValueError(f"{name} must be a safe lowercase PostgreSQL identifier")
        if self.graph_database_schema == "public":
            raise ValueError("graph_database_schema cannot use public")
        if self.graph_pool_min_size > self.graph_pool_max_size:
            raise ValueError("graph pool min size cannot exceed max size")
        if self.graph_pool_max_waiting < self.graph_pool_max_size:
            raise ValueError("graph pool max waiting cannot be below max size")
        if self.app_env.lower() not in {"local", "test"} and self.graph_pool_min_size < 1:
            raise ValueError("production graph pool min size must be positive")
        if not self.graph_expected_spiffe_id.startswith("spiffe://"):
            raise ValueError("graph_expected_spiffe_id must be a SPIFFE URI")
        if self.graph_gateway_mode == "SHADOW":
            if self.graph_database_dsn is None:
                raise ValueError("SHADOW graph mode requires graph_database_dsn")
            if self.graph_jwks_url is None:
                raise ValueError("SHADOW graph mode requires graph_jwks_url")
            if self.graph_expected_environment_generation is None:
                raise ValueError(
                    "SHADOW graph mode requires graph_expected_environment_generation"
                )
            if self.graph_expected_restore_verification_hash is None:
                raise ValueError(
                    "SHADOW graph mode requires graph_expected_restore_verification_hash"
                )
            self._validate_graph_runtime_dsn(self.graph_database_dsn.get_secret_value())
        binding_keys = {
            (
                binding.graph_key,
                binding.graph_version,
                binding.checkpoint_schema_version,
            )
            for binding in self.graph_shadow_bindings
        }
        if len(binding_keys) != len(self.graph_shadow_bindings):
            raise ValueError("graph_shadow_bindings contains a duplicate exact version")
        thread_ids = {thread.thread_id for thread in self.graph_shadow_threads}
        if len(thread_ids) != len(self.graph_shadow_threads):
            raise ValueError("graph_shadow_threads contains a duplicate thread ID")
        scope_keys = {
            (
                thread.tenant_surrogate,
                thread.case_id,
                thread.room_type,
                thread.room_epoch,
                thread.actor_id,
                thread.actor_role,
                thread.audience,
                thread.actor_capabilities,
                thread.agent_session_id,
                thread.graph_key,
                thread.graph_version,
                thread.checkpoint_schema_version,
            )
            for thread in self.graph_shadow_threads
        }
        if len(scope_keys) != len(self.graph_shadow_threads):
            raise ValueError("graph_shadow_threads contains a duplicate authority scope")
        bindings_by_key = {
            (
                binding.graph_key,
                binding.graph_version,
                binding.checkpoint_schema_version,
            ): binding
            for binding in self.graph_shadow_bindings
        }
        for thread in self.graph_shadow_threads:
            key = (
                thread.graph_key,
                thread.graph_version,
                thread.checkpoint_schema_version,
            )
            binding = bindings_by_key.get(key)
            if binding is None or thread.room_type not in binding.allowed_room_types:
                raise ValueError(
                    "graph_shadow_threads must reference an allowed exact graph binding"
                )
        return self

    def _validate_graph_runtime_dsn(self, value: str) -> None:
        parsed = urlsplit(value)
        if parsed.scheme not in {"postgres", "postgresql"} or not parsed.hostname:
            raise ValueError("graph_database_dsn must be a PostgreSQL URL")
        if unquote(parsed.username or "") != self.graph_database_user:
            raise ValueError("graph_database_dsn must use the runtime-only Graph role")
        if unquote(parsed.path.removeprefix("/")) != self.graph_database_name:
            raise ValueError("graph_database_dsn must target the isolated Graph database")
        query = parse_qs(parsed.query, keep_blank_values=True)
        if "options" in query or parsed.fragment:
            raise ValueError("graph_database_dsn cannot override search_path or use a fragment")

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
