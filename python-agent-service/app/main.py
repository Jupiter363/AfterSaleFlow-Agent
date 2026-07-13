# 文件作用：Python Agent 服务代码文件，承载售后争议智能体的 API、配置、模型调用或业务流程。

"""售后纠纷 Agent 服务 —— FastAPI 应用入口。

本文件是整个 Python Agent 服务的核心入口点，采用**应用工厂模式**创建并配置 FastAPI 应用。

主要职责
--------
1. **应用工厂** —— 通过 ``create_app()`` 组装所有业务组件（工作流、代理、LLM 客户端）。
2. **路由注册** —— 暴露 HTTP API 端点供 Java 服务调用，支持同步和流式（NDJSON）双接口。
3. **中间件配置** —— 实现全链路追踪（trace_id / request_id），记录请求生命周期。
4. **异常处理** —— 统一捕获各类异常，返回标准化的错误响应格式。
5. **认证授权** —— 基于 ``X-Service-Secret`` 请求头验证服务间调用的合法性。
6. **依赖注入** —— 所有工作流均可外部传入，便于单元测试和灵活定制。
7. **组件装配** —— 构建 LLM 客户端、提示词仓库、追踪器等基础设施。

架构定位
--------
作为售后纠纷系统的网关和编排中心，将底层的 LangGraph 工作流逻辑封装成标准 RESTful API，
覆盖以下业务流程：

- 争议案件受理与分析 (Dispute Intake)
- 证据收集与卷宗构建 (Evidence Clerk)
- 庭审阶段管理 (Presiding Judge)
- 多方合议评审 (Deliberation Panel)
- 案件评估分析 (Evaluation Agent)

技术栈
------
- FastAPI — Web 框架
- LangGraph — AI Agent 工作流引擎
- Pydantic — 数据验证与序列化
- Langfuse — LLM 可观测性追踪（可选）
- LiteLLM — 多模型代理层

调用关系
--------
Java API Service → Python Agent Service (本文件) → LangGraph Workflows → LLM Proxy
"""

from __future__ import annotations

from hmac import compare_digest
import re
from typing import Any
from uuid import uuid4

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

# ---- 代理 & 工作流 ----
from app.agents.critics import build_default_critics
from app.agents.deliberation_panel import DeliberationPanel
from app.agents.unified_jury_review import UnifiedJuryReviewAgent
from app.agents.dispute_intake_officer import DisputeIntakeOfficer
from app.agents.evaluation_agent import EvaluationAgent
from app.agents.evidence_clerk import EvidenceClerk
from app.agents.evidence_clerk.workflow import EvidenceTurnWorkflow
from app.agents.model_roles import ModelCriticEvaluator, ModelReviewAnswerer
from app.agents.presiding_judge import PresidingJudge
from app.agents.presiding_judge.round_workflow import HearingRoundTurnWorkflow
from app.agents.review_copilot import ReviewCopilot
from app.business.api.final_agents import FinalAgentServices
from app.business.simulated_imports import SimulatedExternalImportWorkflow

# ---- 配置 & 基础设施 ----
from app.config import Settings, get_settings
from app.harness.guardrails import GuardrailViolation
from app.harness.evidence_asset_loader import EvidenceAssetLoader
from app.harness.model_runner import HarnessModelRunner
from app.harness.validation import CitationValidationError
from app.llm import AgentServiceUnavailable, LiteLlmProxyClient
from app.llm import AgentOutputSchemaError

# ---- 工作流 ----
from app.intake import IntakeWorkflow
from app.intake_turn import IntakeTurnWorkflow
from app.evaluation import EvaluationWorkflow
from app.harness.prompt_composer import PromptRepository

# ---- 请求 / 响应 Schema ----
from app.schemas import (
    HearingAnalysisResult,
    HearingAnalyzeRequest,
    HearingStageRequest,
    HearingStageResult,
    HearingRoundTurnRequest,
    HearingRoundTurnResult,
    DeliberationReport,
    DeliberationRequest,
    DisputeIntakeRequest,
    DisputeIntakeResult,
    EvidenceBuildRequest,
    EvidenceDossierResult,
    EvidenceTurnRequest,
    EvidenceTurnResult,
    EvaluationAnalysisResult,
    EvaluationAnalyzeRequest,
    IntakeAnalysisOutput,
    IntakeAnalyzeRequest,
    IntakeTurnRequest,
    IntakeTurnResult,
    ReviewCopilotAnswer,
    ReviewCopilotRequest,
    SimulatedExternalImportRequest,
    SimulatedExternalImportResult,
)

# ---- 流式传输 & 追踪 ----
from app.streaming import (
    AGENT_RUN_HEADER,
    resolve_agent_run_id,
    workflow_ndjson_response,
)
from app.tracing import (
    AgentTraceContext,
    LangfuseAgentTracer,
    NoOpAgentTracer,
)
from app.workflow import HearingWorkflow


# ==================== 常量定义 ====================

# HTTP 请求头：用于全链路追踪和服务间认证
TRACE_HEADER = "X-Trace-Id"              # 追踪 ID，用于分布式追踪
REQUEST_HEADER = "X-Request-Id"          # 请求 ID，用于日志关联和去重
SERVICE_SECRET_HEADER = "X-Service-Secret"  # 服务密钥，用于服务间认证

# 关联 ID 安全格式：字母/数字开头，后续可含 _.-:，总长 1-128
SAFE_CORRELATION_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$")


# 所属模块：Python Agent 服务边界 > main；函数角色：模块公开业务函数。
# 具体功能：`create_app` 应用工厂：创建并配置 FastAPI 实例；关键协作调用：`FastAPI`、`app.middleware`、`app.exception_handler`；返回/更新字段：`status`、`service`、`model_status`。
# 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_build_llm_client`、`_build_workflow`、`_build_intake_workflow`、`_build_intake_turn_workflow`。
# 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
def create_app(
    settings: Settings | None = None,
    workflow: HearingWorkflow | None = None,
    intake_workflow: IntakeWorkflow | None = None,
    intake_turn_workflow: IntakeTurnWorkflow | None = None,
    evidence_turn_workflow: EvidenceTurnWorkflow | None = None,
    evaluation_workflow: EvaluationWorkflow | None = None,
    final_agent_services: FinalAgentServices | None = None,
    simulated_import_workflow: SimulatedExternalImportWorkflow | None = None,
    hearing_round_turn_workflow: HearingRoundTurnWorkflow | None = None,
) -> FastAPI:
    """应用工厂：创建并配置 FastAPI 实例。

    采用依赖注入模式，所有工作流和配置均可外部传入，便于：
    - 单元测试时注入 Mock 对象
    - 不同环境使用不同配置
    - 灵活替换工作流实现

    Parameters
    ----------
    settings : Settings | None
        应用配置（LLM 连接信息、服务密钥等），默认从环境变量加载。
    workflow : HearingWorkflow | None
        庭审工作流实例，负责 C1-C6 完整庭审流程。
    intake_workflow : IntakeWorkflow | None
        争议受理工作流，负责案件初始分析。
    intake_turn_workflow : IntakeTurnWorkflow | None
        受理轮转工作流，处理多轮对话。
    evidence_turn_workflow : EvidenceTurnWorkflow | None
        证据轮转工作流，管理证据提交。
    evaluation_workflow : EvaluationWorkflow | None
        评估工作流，用于离线评估。
    final_agent_services : FinalAgentServices | None
        组合的代理服务集合，封装所有业务代理。
    simulated_import_workflow : SimulatedExternalImportWorkflow | None
        模拟导入工作流，生成测试数据。
    hearing_round_turn_workflow : HearingRoundTurnWorkflow | None
        庭审轮转工作流，处理庭审多轮交互。

    Returns
    -------
    FastAPI
        配置完成的应用实例，包含所有路由、中间件和异常处理器。
    """
    # 兼容性处理：若 evaluation_workflow 未传入但 intake_turn_workflow 存在旧版接口，
    # 则将其复用为 evaluation_workflow（历史遗留逻辑）
    if (
        evaluation_workflow is None
        and intake_turn_workflow is not None
        and hasattr(intake_turn_workflow, "analyze")
        and not hasattr(intake_turn_workflow, "run")
    ):
        evaluation_workflow = intake_turn_workflow  # type: ignore[assignment]
        intake_turn_workflow = None

    # 解析配置：优先使用传入的配置，否则从环境变量加载
    resolved = settings or get_settings()

    # 构建各工作流实例：如果未传入则自动构建默认实现
    hearing_workflow = workflow or _build_workflow(resolved)
    resolved_intake_workflow = intake_workflow or _build_intake_workflow(resolved)
    resolved_intake_turn_workflow = intake_turn_workflow or _build_intake_turn_workflow(
        resolved
    )
    resolved_evidence_turn_workflow = evidence_turn_workflow or _build_evidence_turn_workflow(
        resolved
    )
    resolved_evaluation_workflow = evaluation_workflow or _build_evaluation_workflow(
        resolved
    )
    resolved_simulated_import_workflow = (
        simulated_import_workflow or _build_simulated_import_workflow(resolved)
    )
    resolved_hearing_round_turn_workflow = (
        hearing_round_turn_workflow or _build_hearing_round_turn_workflow(resolved)
    )

    # 构建 LLM 健康检查客户端
    resolved_model_health_client = _build_llm_client(resolved)

    # 构建最终代理服务集合：组合所有业务代理
    final_services = final_agent_services or _build_final_agent_services(
        resolved,
        hearing_workflow,
        resolved_intake_workflow,
        resolved_evaluation_workflow,
    )

    # 创建 FastAPI 应用实例
    app = FastAPI(title="Python Agent Service", version="1.0.0")

    # ==================== 中间件 ====================

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`correlation_middleware` 关联 ID 追踪中间件；关键协作调用：`app.middleware`、`request.headers.get`、`call_next`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_correlation_id`。
    # 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
    @app.middleware("http")
    async def correlation_middleware(request: Request, call_next):
        """关联 ID 追踪中间件。

        为每个请求生成或提取 trace_id / request_id，实现全链路可观测性：
        1. 从请求头提取已有的追踪 ID
        2. 不存在则自动生成带前缀的 UUID
        3. 存储到 request.state 供后续处理使用
        4. 将 ID 注入响应头，形成完整的追踪链
        """
        # 提取或生成追踪 ID
        trace_id = _correlation_id(
            request.headers.get(TRACE_HEADER), "TRACE_"
        )
        # 提取或生成请求 ID
        request_id = _correlation_id(
            request.headers.get(REQUEST_HEADER), "REQ_"
        )
        # 存储到请求状态，供后续处理和异常处理使用
        request.state.trace_id = trace_id
        request.state.request_id = request_id
        response = await call_next(request)
        # 将追踪 ID 注入响应头
        response.headers[TRACE_HEADER] = trace_id
        response.headers[REQUEST_HEADER] = request_id
        return response

    # ==================== 全局异常处理器 ====================

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`validation_error` 请求验证错误 —— Pydantic 模型验证失败时触发（422）；关键协作调用：`app.exception_handler`、`jsonable_encoder`、`exception.errors`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_error_response`。
    # 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
    @app.exception_handler(RequestValidationError)
    async def validation_error(request: Request, exception: RequestValidationError):
        """请求验证错误 —— Pydantic 模型验证失败时触发（422）。"""
        return _error_response(
            request,
            422,
            "INVALID_ARGUMENT",
            "request validation failed",
            {"errors": jsonable_encoder(exception.errors())},
        )

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`http_error` HTTP 异常 —— 手动抛出的 HTTPException（认证失败 401 / 其他）；关键协作调用：`app.exception_handler`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_error_response`。
    # 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
    @app.exception_handler(HTTPException)
    async def http_error(request: Request, exception: HTTPException):
        """HTTP 异常 —— 手动抛出的 HTTPException（认证失败 401 / 其他）。"""
        return _error_response(
            request,
            exception.status_code,
            "UNAUTHORIZED" if exception.status_code == 401 else "INVALID_ARGUMENT",
            str(exception.detail),
            {},
        )

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`upstream_error` LLM 服务不可用 —— LiteLLM 代理连接失败或超时（503）；关键协作调用：`app.exception_handler`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_error_response`。
    # 系统意义：提供实时反馈，同时阻止未校验完整结果或内部推理经流通道泄露。
    @app.exception_handler(AgentServiceUnavailable)
    async def upstream_error(request: Request, exception: AgentServiceUnavailable):
        """LLM 服务不可用 —— LiteLLM 代理连接失败或超时（503）。"""
        return _error_response(
            request,
            503,
            "AGENT_SERVICE_UNAVAILABLE",
            "agent model service unavailable",
            {},
        )

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`schema_error` Agent 输出 schema 验证失败 —— LLM 返回的结构化输出不符合预期（502）；关键协作调用：`app.exception_handler`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_error_response`。
    # 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
    @app.exception_handler(AgentOutputSchemaError)
    async def schema_error(request: Request, exception: AgentOutputSchemaError):
        """Agent 输出 schema 验证失败 —— LLM 返回的结构化输出不符合预期（502）。"""
        return _error_response(
            request,
            502,
            "AGENT_OUTPUT_SCHEMA_INVALID",
            "agent returned invalid structured output",
            {"node_name": exception.node_name},
        )

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`permission_error` 权限拒绝 —— Agent 内部逻辑抛出 PermissionError（403）；关键协作调用：`app.exception_handler`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_error_response`。
    # 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
    @app.exception_handler(PermissionError)
    async def permission_error(request: Request, exception: PermissionError):
        """权限拒绝 —— Agent 内部逻辑抛出 PermissionError（403）。"""
        return _error_response(
            request,
            403,
            "AGENT_PERMISSION_DENIED",
            str(exception),
            {},
        )

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`governance_error` 治理规则违反 —— 引用验证失败或安全护栏触发（422）；关键协作调用：`app.exception_handler`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_error_response`。
    # 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
    @app.exception_handler(CitationValidationError)
    @app.exception_handler(GuardrailViolation)
    async def governance_error(request: Request, exception: Exception):
        """治理规则违反 —— 引用验证失败或安全护栏触发（422）。"""
        return _error_response(
            request,
            422,
            "AGENT_GOVERNANCE_REJECTED",
            str(exception),
            {},
        )

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`unexpected_error` 未预期异常（兜底）—— 捕获所有未处理的异常，防止服务崩溃（500）；关键协作调用：`app.exception_handler`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_error_response`。
    # 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
    @app.exception_handler(Exception)
    async def unexpected_error(request: Request, exception: Exception):
        """未预期异常（兜底）—— 捕获所有未处理的异常，防止服务崩溃（500）。"""
        return _error_response(
            request,
            500,
            "INTERNAL_ERROR",
            "internal service error",
            {},
        )

    # ==================== 健康检查端点 ====================

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`health` 基础健康检查；关键协作调用：`app.get`；返回/更新字段：`status`、`service`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 协作调用 `app.get`。
    # 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
    @app.get("/health")
    def health() -> dict[str, str]:
        """基础健康检查。"""
        return {"status": "UP", "service": "python-agent-service"}

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`model_health` LLM 模型连接健康检查（在线程池中执行，避免阻塞事件循环）；关键协作调用：`app.get`、`run_in_threadpool`；返回/更新字段：`status`、`service`、`model_status`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 协作调用 `app.get`、`run_in_threadpool`。
    # 系统意义：把不确定模型能力限制在确定性系统边界内：鉴权、追踪、异常映射必须完整；不泄露内部推理。
    @app.get("/health/model")
    async def model_health() -> dict[str, Any]:
        """LLM 模型连接健康检查（在线程池中执行，避免阻塞事件循环）。"""
        result = await run_in_threadpool(resolved_model_health_client.check_available)
        return {
            "status": "UP",
            "service": "python-agent-service",
            "model_status": "CONNECTED",
            **result,
        }

    # ==================== 同步路由 —— 争议受理 (Intake) ====================

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`final_intake` 争议案件受理分析；关键协作调用：`app.post`、`Header`、`run_in_threadpool`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_authorize`、`_trace_context`。
    # 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
    @app.post(
        "/internal/agents/intake/analyze",
        response_model=DisputeIntakeResult,
    )
    async def final_intake(
        payload: DisputeIntakeRequest,
        request: Request,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
        x_role: str = Header(default="SYSTEM", alias="X-Role"),
        x_case_state: str = Header(default="SUBMITTED", alias="X-Case-State"),
    ) -> DisputeIntakeResult:
        """争议案件受理分析。"""
        _authorize(x_service_secret, resolved.python_agent_service_secret)
        context = _trace_context(
            request,
            case_id=f"CASE_PENDING_{request.state.request_id[-32:]}",
            workflow_id=f"INTAKE_{request.state.request_id[-32:]}",
            user_id=None,
            role=x_role,
            prompt_version=resolved.prompt_version,
        )
        return await run_in_threadpool(
            final_services.intake.analyze,
            payload,
            context,
            case_state=x_case_state,
        )

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`intake_turn` 争议受理多轮对话处理；关键协作调用：`app.post`、`Header`、`run_in_threadpool`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_authorize`。
    # 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
    @app.post(
        "/internal/agents/intake/turn",
        response_model=IntakeTurnResult,
    )
    async def intake_turn(
        payload: IntakeTurnRequest,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
    ) -> IntakeTurnResult:
        """争议受理多轮对话处理。"""
        _authorize(x_service_secret, resolved.python_agent_service_secret)
        return await run_in_threadpool(resolved_intake_turn_workflow.run, payload)

    # ==================== 同步路由 —— 证据 (Evidence) ====================

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`evidence_turn` 证据轮转处理；关键协作调用：`app.post`、`Header`、`run_in_threadpool`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_authorize`。
    # 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
    @app.post(
        "/internal/agents/evidence/turn",
        response_model=EvidenceTurnResult,
    )
    async def evidence_turn(
        payload: EvidenceTurnRequest,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
    ) -> EvidenceTurnResult:
        """证据轮转处理。"""
        _authorize(x_service_secret, resolved.python_agent_service_secret)
        return await run_in_threadpool(resolved_evidence_turn_workflow.run, payload)

    # ==================== 同步路由 —— 外部导入模拟 ====================

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`simulate_external_import` 模拟外部数据导入（测试用）；关键协作调用：`app.post`、`Header`、`run_in_threadpool`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_authorize`。
    # 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
    @app.post(
        "/internal/agents/external-import/simulate",
        response_model=SimulatedExternalImportResult,
    )
    async def simulate_external_import(
        payload: SimulatedExternalImportRequest,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
    ) -> SimulatedExternalImportResult:
        """模拟外部数据导入（测试用）。"""
        _authorize(x_service_secret, resolved.python_agent_service_secret)
        return await run_in_threadpool(
            resolved_simulated_import_workflow.generate,
            payload,
        )

    # ==================== 同步路由 —— 证据卷宗构建 ====================

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`final_evidence` 证据卷宗构建；关键协作调用：`app.post`、`Header`、`run_in_threadpool`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_authorize`。
    # 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
    @app.post(
        "/internal/agents/evidence/build",
        response_model=EvidenceDossierResult,
    )
    async def final_evidence(
        payload: EvidenceBuildRequest,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
    ) -> EvidenceDossierResult:
        """证据卷宗构建。"""
        _authorize(x_service_secret, resolved.python_agent_service_secret)
        return await run_in_threadpool(final_services.evidence.build, payload)

    # ==================== 同步路由 —— 庭审 (Hearing) ====================

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`final_hearing` 运行庭审阶段（C1-C6）；关键协作调用：`app.post`、`Header`、`run_in_threadpool`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_authorize`、`_trace_context`。
    # 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
    @app.post(
        "/internal/agents/hearing/run-stage",
        response_model=HearingStageResult,
    )
    async def final_hearing(
        payload: HearingStageRequest,
        request: Request,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
        x_role: str = Header(default="SYSTEM", alias="X-Role"),
        x_case_state: str = Header(
            default="FULL_HEARING",
            alias="X-Case-State",
        ),
    ) -> HearingStageResult:
        """运行庭审阶段（C1-C6）。"""
        _authorize(x_service_secret, resolved.python_agent_service_secret)
        context = _trace_context(
            request,
            case_id=payload.case_id,
            workflow_id=payload.workflow_id,
            user_id=payload.user_id,
            role=x_role,
            prompt_version=resolved.prompt_version,
        )
        return await run_in_threadpool(
            final_services.hearing.run_stage,
            payload,
            context,
            case_state=x_case_state,
        )

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`hearing_round_turn` 庭审多轮交互处理；关键协作调用：`app.post`、`Header`、`run_in_threadpool`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_authorize`。
    # 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
    @app.post(
        "/internal/agents/hearing/round-turn",
        response_model=HearingRoundTurnResult,
    )
    async def hearing_round_turn(
        payload: HearingRoundTurnRequest,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
    ) -> HearingRoundTurnResult:
        """庭审多轮交互处理。"""
        _authorize(x_service_secret, resolved.python_agent_service_secret)
        return await run_in_threadpool(
            resolved_hearing_round_turn_workflow.run,
            payload,
        )

    # ==================== 同步路由 —— 合议 (Deliberation) ====================

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`final_deliberation` 运行多方合议；关键协作调用：`app.post`、`Header`、`run_in_threadpool`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_authorize`。
    # 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
    @app.post(
        "/internal/agents/deliberation/run",
        response_model=DeliberationReport,
    )
    async def final_deliberation(
        payload: DeliberationRequest,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
    ) -> DeliberationReport:
        """运行多方合议。"""
        _authorize(x_service_secret, resolved.python_agent_service_secret)
        return await run_in_threadpool(final_services.deliberation.run, payload)

    # ==================== 同步路由 —— 评审助手 (Review Copilot) ====================

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`final_review_copilot` 评审助手查询；关键协作调用：`app.post`、`Header`、`run_in_threadpool`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_authorize`。
    # 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
    @app.post(
        "/internal/agents/review-copilot/query",
        response_model=ReviewCopilotAnswer,
    )
    async def final_review_copilot(
        payload: ReviewCopilotRequest,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
    ) -> ReviewCopilotAnswer:
        """评审助手查询。"""
        _authorize(x_service_secret, resolved.python_agent_service_secret)
        return await run_in_threadpool(
            final_services.review_copilot.query,
            payload,
        )

    # ==================== 同步路由 —— 评估 (Evaluation) ====================

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`final_evaluation` 案件评估分析（离线模式）；关键协作调用：`app.post`、`Header`、`run_in_threadpool`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_authorize`、`_trace_context`。
    # 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
    @app.post(
        "/internal/agents/evaluation/analyze",
        response_model=EvaluationAnalysisResult,
    )
    async def final_evaluation(
        payload: EvaluationAnalyzeRequest,
        request: Request,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
        x_role: str = Header(default="SYSTEM", alias="X-Role"),
    ) -> EvaluationAnalysisResult:
        """案件评估分析（离线模式）。"""
        _authorize(x_service_secret, resolved.python_agent_service_secret)
        context = _trace_context(
            request,
            case_id=payload.case_id,
            workflow_id=f"EVALUATION_{payload.case_id}",
            user_id=None,
            role=x_role,
            prompt_version=resolved.evaluation_prompt_version,
        )
        return await run_in_threadpool(
            final_services.evaluation.analyze,
            payload,
            context,
            offline=True,
        )

    # ==================== 同步路由 —— 遗留接口 (Legacy) ====================
    # 保留旧版本的同步接口，用于向后兼容，在 Java 迁移窗口期内仍可使用。

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`analyze_hearing` [遗留] 庭审全量分析 —— 一次性执行 C1-C6 所有阶段；关键协作调用：`app.post`、`Header`、`AgentTraceContext`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_authorize`。
    # 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
    @app.post(
        "/internal/agents/legacy/hearing/analyze",
        response_model=HearingAnalysisResult,
    )
    async def analyze_hearing(
        payload: HearingAnalyzeRequest,
        request: Request,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
        x_role: str = Header(default="SYSTEM", alias="X-Role"),
    ) -> HearingAnalysisResult:
        """[遗留] 庭审全量分析 —— 一次性执行 C1-C6 所有阶段。"""
        _authorize(x_service_secret, resolved.python_agent_service_secret)
        context = AgentTraceContext(
            trace_id=request.state.trace_id,
            request_id=request.state.request_id,
            case_id=payload.case_id,
            workflow_id=payload.workflow_id,
            user_id=payload.user_id,
            role=x_role,
            prompt_version=resolved.prompt_version,
        )
        return await run_in_threadpool(hearing_workflow.analyze, payload, context)

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`analyze_intake` [遗留] 争议受理全量分析；关键协作调用：`app.post`、`Header`、`AgentTraceContext`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_authorize`。
    # 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
    @app.post(
        "/internal/agents/legacy/intake/analyze",
        response_model=IntakeAnalysisOutput,
    )
    async def analyze_intake(
        payload: IntakeAnalyzeRequest,
        request: Request,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
        x_role: str = Header(default="SYSTEM", alias="X-Role"),
    ) -> IntakeAnalysisOutput:
        """[遗留] 争议受理全量分析。"""
        _authorize(x_service_secret, resolved.python_agent_service_secret)
        context = AgentTraceContext(
            trace_id=request.state.trace_id,
            request_id=request.state.request_id,
            case_id=f"CASE_PENDING_{request.state.request_id[-32:]}",
            workflow_id=f"INTAKE_{request.state.request_id[-32:]}",
            user_id=payload.user_id,
            role=x_role,
            prompt_version=resolved.prompt_version,
        )
        return await run_in_threadpool(
            resolved_intake_workflow.analyze, payload, context
        )

    # ==================== 流式路由 —— NDJSON 传输 ====================
    # 流式路由与同步路由共享相同的请求/响应契约，
    # 区别仅在于传输方式：认证后的 Java 调用方接收 agent_stream.v1 NDJSON 事件，
    # 并将审批后的增量数据转发给浏览器。同步路由在 Java 迁移窗口期内仍保留。

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`final_intake_stream` [流式] 争议案件受理分析；关键协作调用：`app.post`、`Header`、`workflow_ndjson_response`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_authorize`、`_trace_context`。
    # 系统意义：提供实时反馈，同时阻止未校验完整结果或内部推理经流通道泄露。
    @app.post("/internal/agents/intake/analyze/stream")
    async def final_intake_stream(
        payload: DisputeIntakeRequest,
        request: Request,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
        x_role: str = Header(default="SYSTEM", alias="X-Role"),
        x_case_state: str = Header(default="SUBMITTED", alias="X-Case-State"),
        x_agent_run_id: str | None = Header(default=None, alias=AGENT_RUN_HEADER),
    ):
        """[流式] 争议案件受理分析。"""
        _authorize(x_service_secret, resolved.python_agent_service_secret)
        context = _trace_context(
            request,
            case_id=f"CASE_PENDING_{request.state.request_id[-32:]}",
            workflow_id=f"INTAKE_{request.state.request_id[-32:]}",
            user_id=None,
            role=x_role,
            prompt_version=resolved.prompt_version,
        )
        return workflow_ndjson_response(
            operation="intake_analyze",
            run_id=resolve_agent_run_id(x_agent_run_id),
            invoke=lambda: final_services.intake.analyze(
                payload,
                context,
                case_state=x_case_state,
            ),
        )

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`intake_turn_stream` [流式] 争议受理多轮对话；关键协作调用：`app.post`、`Header`、`workflow_ndjson_response`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_authorize`。
    # 系统意义：提供实时反馈，同时阻止未校验完整结果或内部推理经流通道泄露。
    @app.post("/internal/agents/intake/turn/stream")
    async def intake_turn_stream(
        payload: IntakeTurnRequest,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
        x_agent_run_id: str | None = Header(default=None, alias=AGENT_RUN_HEADER),
    ):
        """[流式] 争议受理多轮对话。"""
        _authorize(x_service_secret, resolved.python_agent_service_secret)
        return workflow_ndjson_response(
            operation="intake_turn",
            run_id=resolve_agent_run_id(x_agent_run_id),
            invoke=lambda: resolved_intake_turn_workflow.run(payload),
        )

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`evidence_turn_stream` [流式] 证据轮转；关键协作调用：`app.post`、`Header`、`workflow_ndjson_response`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_authorize`。
    # 系统意义：提供实时反馈，同时阻止未校验完整结果或内部推理经流通道泄露。
    @app.post("/internal/agents/evidence/turn/stream")
    async def evidence_turn_stream(
        payload: EvidenceTurnRequest,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
        x_agent_run_id: str | None = Header(default=None, alias=AGENT_RUN_HEADER),
    ):
        """[流式] 证据轮转。"""
        _authorize(x_service_secret, resolved.python_agent_service_secret)
        return workflow_ndjson_response(
            operation="evidence_turn",
            run_id=resolve_agent_run_id(x_agent_run_id),
            invoke=lambda: resolved_evidence_turn_workflow.run(payload),
        )

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`simulate_external_import_stream` [流式] 模拟外部数据导入；关键协作调用：`app.post`、`Header`、`workflow_ndjson_response`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_authorize`。
    # 系统意义：提供实时反馈，同时阻止未校验完整结果或内部推理经流通道泄露。
    @app.post("/internal/agents/external-import/simulate/stream")
    async def simulate_external_import_stream(
        payload: SimulatedExternalImportRequest,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
        x_agent_run_id: str | None = Header(default=None, alias=AGENT_RUN_HEADER),
    ):
        """[流式] 模拟外部数据导入。"""
        _authorize(x_service_secret, resolved.python_agent_service_secret)
        return workflow_ndjson_response(
            operation="external_import",
            run_id=resolve_agent_run_id(x_agent_run_id),
            invoke=lambda: resolved_simulated_import_workflow.generate(payload),
        )

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`final_evidence_stream` [流式] 证据卷宗构建；关键协作调用：`app.post`、`Header`、`workflow_ndjson_response`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_authorize`。
    # 系统意义：提供实时反馈，同时阻止未校验完整结果或内部推理经流通道泄露。
    @app.post("/internal/agents/evidence/build/stream")
    async def final_evidence_stream(
        payload: EvidenceBuildRequest,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
        x_agent_run_id: str | None = Header(default=None, alias=AGENT_RUN_HEADER),
    ):
        """[流式] 证据卷宗构建。"""
        _authorize(x_service_secret, resolved.python_agent_service_secret)
        return workflow_ndjson_response(
            operation="evidence_build",
            run_id=resolve_agent_run_id(x_agent_run_id),
            invoke=lambda: final_services.evidence.build(payload),
        )

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`final_hearing_stream` [流式] 运行庭审阶段；关键协作调用：`app.post`、`Header`、`workflow_ndjson_response`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_authorize`、`_trace_context`。
    # 系统意义：提供实时反馈，同时阻止未校验完整结果或内部推理经流通道泄露。
    @app.post("/internal/agents/hearing/run-stage/stream")
    async def final_hearing_stream(
        payload: HearingStageRequest,
        request: Request,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
        x_role: str = Header(default="SYSTEM", alias="X-Role"),
        x_case_state: str = Header(
            default="FULL_HEARING",
            alias="X-Case-State",
        ),
        x_agent_run_id: str | None = Header(default=None, alias=AGENT_RUN_HEADER),
    ):
        """[流式] 运行庭审阶段。"""
        _authorize(x_service_secret, resolved.python_agent_service_secret)
        context = _trace_context(
            request,
            case_id=payload.case_id,
            workflow_id=payload.workflow_id,
            user_id=payload.user_id,
            role=x_role,
            prompt_version=resolved.prompt_version,
        )
        return workflow_ndjson_response(
            operation="hearing_stage",
            run_id=resolve_agent_run_id(x_agent_run_id),
            invoke=lambda: final_services.hearing.run_stage(
                payload,
                context,
                case_state=x_case_state,
            ),
        )

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`hearing_round_turn_stream` [流式] 庭审多轮交互；关键协作调用：`app.post`、`Header`、`workflow_ndjson_response`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_authorize`。
    # 系统意义：提供实时反馈，同时阻止未校验完整结果或内部推理经流通道泄露。
    @app.post("/internal/agents/hearing/round-turn/stream")
    async def hearing_round_turn_stream(
        payload: HearingRoundTurnRequest,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
        x_agent_run_id: str | None = Header(default=None, alias=AGENT_RUN_HEADER),
    ):
        """[流式] 庭审多轮交互。"""
        _authorize(x_service_secret, resolved.python_agent_service_secret)
        return workflow_ndjson_response(
            operation="hearing_round_turn",
            run_id=resolve_agent_run_id(x_agent_run_id),
            invoke=lambda: resolved_hearing_round_turn_workflow.run(payload),
        )

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`final_deliberation_stream` [流式] 多方合议；关键协作调用：`app.post`、`Header`、`workflow_ndjson_response`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_authorize`。
    # 系统意义：提供实时反馈，同时阻止未校验完整结果或内部推理经流通道泄露。
    @app.post("/internal/agents/deliberation/run/stream")
    async def final_deliberation_stream(
        payload: DeliberationRequest,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
        x_agent_run_id: str | None = Header(default=None, alias=AGENT_RUN_HEADER),
    ):
        """[流式] 多方合议。"""
        _authorize(x_service_secret, resolved.python_agent_service_secret)
        return workflow_ndjson_response(
            operation="deliberation",
            run_id=resolve_agent_run_id(x_agent_run_id),
            invoke=lambda: final_services.deliberation.run(payload),
        )

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`final_review_copilot_stream` [流式] 评审助手查询；关键协作调用：`app.post`、`Header`、`workflow_ndjson_response`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_authorize`。
    # 系统意义：提供实时反馈，同时阻止未校验完整结果或内部推理经流通道泄露。
    @app.post("/internal/agents/review-copilot/query/stream")
    async def final_review_copilot_stream(
        payload: ReviewCopilotRequest,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
        x_agent_run_id: str | None = Header(default=None, alias=AGENT_RUN_HEADER),
    ):
        """[流式] 评审助手查询。"""
        _authorize(x_service_secret, resolved.python_agent_service_secret)
        return workflow_ndjson_response(
            operation="review_copilot",
            run_id=resolve_agent_run_id(x_agent_run_id),
            invoke=lambda: final_services.review_copilot.query(payload),
        )

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`final_evaluation_stream` [流式] 案件评估分析；关键协作调用：`app.post`、`Header`、`workflow_ndjson_response`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_authorize`、`_trace_context`。
    # 系统意义：提供实时反馈，同时阻止未校验完整结果或内部推理经流通道泄露。
    @app.post("/internal/agents/evaluation/analyze/stream")
    async def final_evaluation_stream(
        payload: EvaluationAnalyzeRequest,
        request: Request,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
        x_role: str = Header(default="SYSTEM", alias="X-Role"),
        x_agent_run_id: str | None = Header(default=None, alias=AGENT_RUN_HEADER),
    ):
        """[流式] 案件评估分析。"""
        _authorize(x_service_secret, resolved.python_agent_service_secret)
        context = _trace_context(
            request,
            case_id=payload.case_id,
            workflow_id=f"EVALUATION_{payload.case_id}",
            user_id=None,
            role=x_role,
            prompt_version=resolved.evaluation_prompt_version,
        )
        return workflow_ndjson_response(
            operation="evaluation",
            run_id=resolve_agent_run_id(x_agent_run_id),
            invoke=lambda: final_services.evaluation.analyze(
                payload,
                context,
                offline=True,
            ),
        )

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`analyze_hearing_stream` [流式][遗留] 庭审全量分析；关键协作调用：`app.post`、`Header`、`AgentTraceContext`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_authorize`。
    # 系统意义：提供实时反馈，同时阻止未校验完整结果或内部推理经流通道泄露。
    @app.post("/internal/agents/legacy/hearing/analyze/stream")
    async def analyze_hearing_stream(
        payload: HearingAnalyzeRequest,
        request: Request,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
        x_role: str = Header(default="SYSTEM", alias="X-Role"),
        x_agent_run_id: str | None = Header(default=None, alias=AGENT_RUN_HEADER),
    ):
        """[流式][遗留] 庭审全量分析。"""
        _authorize(x_service_secret, resolved.python_agent_service_secret)
        context = AgentTraceContext(
            trace_id=request.state.trace_id,
            request_id=request.state.request_id,
            case_id=payload.case_id,
            workflow_id=payload.workflow_id,
            user_id=payload.user_id,
            role=x_role,
            prompt_version=resolved.prompt_version,
        )
        return workflow_ndjson_response(
            operation="hearing_analysis",
            run_id=resolve_agent_run_id(x_agent_run_id),
            invoke=lambda: hearing_workflow.analyze(payload, context),
        )

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`analyze_intake_stream` [流式][遗留] 争议受理全量分析；关键协作调用：`app.post`、`Header`、`AgentTraceContext`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_authorize`。
    # 系统意义：提供实时反馈，同时阻止未校验完整结果或内部推理经流通道泄露。
    @app.post("/internal/agents/legacy/intake/analyze/stream")
    async def analyze_intake_stream(
        payload: IntakeAnalyzeRequest,
        request: Request,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
        x_role: str = Header(default="SYSTEM", alias="X-Role"),
        x_agent_run_id: str | None = Header(default=None, alias=AGENT_RUN_HEADER),
    ):
        """[流式][遗留] 争议受理全量分析。"""
        _authorize(x_service_secret, resolved.python_agent_service_secret)
        context = AgentTraceContext(
            trace_id=request.state.trace_id,
            request_id=request.state.request_id,
            case_id=f"CASE_PENDING_{request.state.request_id[-32:]}",
            workflow_id=f"INTAKE_{request.state.request_id[-32:]}",
            user_id=payload.user_id,
            role=x_role,
            prompt_version=resolved.prompt_version,
        )
        return workflow_ndjson_response(
            operation="intake_analysis",
            run_id=resolve_agent_run_id(x_agent_run_id),
            invoke=lambda: resolved_intake_workflow.analyze(payload, context),
        )

    return app


# ==================== 工作流构建函数 ====================
# 每个函数负责构建一个独立的工作流实例，统一通过 Settings 获取配置。


# 所属模块：Python Agent 服务边界 > main；函数角色：模块私有业务函数。
# 具体功能：`_build_workflow` 构建庭审工作流（C1-C6 完整流程）；关键协作调用：`HearingWorkflow`、`PromptRepository`。
# 上下游：上游为 本文件的 `create_app`；下游为 本文件的 `_build_llm_client`、`_build_tracer`。
# 系统意义：定义可恢复、可追踪的阶段顺序，使案件重试仍沿相同业务路径推进。
def _build_workflow(settings: Settings) -> HearingWorkflow:
    """构建庭审工作流（C1-C6 完整流程）。"""
    llm = _build_llm_client(settings)
    tracer = _build_tracer(settings)
    return HearingWorkflow(
        llm,
        PromptRepository(),
        tracer,
        settings.resolved_llm_model,
        settings.prompt_version,
    )


# 所属模块：Python Agent 服务边界 > main；函数角色：模块私有业务函数。
# 具体功能：`_build_intake_workflow` 构建争议受理工作流；关键协作调用：`IntakeWorkflow`、`PromptRepository`。
# 上下游：上游为 本文件的 `create_app`；下游为 本文件的 `_build_llm_client`、`_build_tracer`。
# 系统意义：定义可恢复、可追踪的阶段顺序，使案件重试仍沿相同业务路径推进。
def _build_intake_workflow(settings: Settings) -> IntakeWorkflow:
    """构建争议受理工作流。"""
    llm = _build_llm_client(settings)
    tracer = _build_tracer(settings)
    return IntakeWorkflow(
        llm,
        PromptRepository(),
        tracer,
        settings.resolved_llm_model,
    )


# 所属模块：Python Agent 服务边界 > main；函数角色：模块私有业务函数。
# 具体功能：`_build_intake_turn_workflow` 构建受理轮转工作流（基于 HarnessModelRunner）；关键协作调用：`IntakeTurnWorkflow`、`HarnessModelRunner`、`PromptRepository`。
# 上下游：上游为 本文件的 `create_app`；下游为 本文件的 `_build_llm_client`。
# 系统意义：定义可恢复、可追踪的阶段顺序，使案件重试仍沿相同业务路径推进。
def _build_intake_turn_workflow(settings: Settings) -> IntakeTurnWorkflow:
    """构建受理轮转工作流（基于 HarnessModelRunner）。"""
    llm = _build_llm_client(settings)
    return IntakeTurnWorkflow(
        model_runner=HarnessModelRunner(
            llm=llm,
            prompts=PromptRepository(),
        )
    )


# 所属模块：Python Agent 服务边界 > main；函数角色：模块私有业务函数。
# 具体功能：`_build_evidence_turn_workflow` 构建证据轮转工作流（含外部资产加载器）；关键协作调用：`EvidenceTurnWorkflow`、`HarnessModelRunner`、`EvidenceAssetLoader`。
# 上下游：上游为 本文件的 `create_app`；下游为 本文件的 `_build_llm_client`。
# 系统意义：定义可恢复、可追踪的阶段顺序，使案件重试仍沿相同业务路径推进。
def _build_evidence_turn_workflow(settings: Settings) -> EvidenceTurnWorkflow:
    """构建证据轮转工作流（含外部资产加载器）。"""
    llm = _build_llm_client(settings)
    return EvidenceTurnWorkflow(
        model_runner=HarnessModelRunner(
            llm=llm,
            prompts=PromptRepository(),
        ),
        asset_loader=EvidenceAssetLoader(
            java_api_service_url=settings.java_api_service_url,
            java_service_secret=settings.java_service_secret,
        ),
    )


# 所属模块：Python Agent 服务边界 > main；函数角色：模块私有业务函数。
# 具体功能：`_build_evaluation_workflow` 构建评估工作流；关键协作调用：`EvaluationWorkflow`、`PromptRepository`。
# 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_build_llm_client`、`_build_tracer`。
# 系统意义：定义可恢复、可追踪的阶段顺序，使案件重试仍沿相同业务路径推进。
def _build_evaluation_workflow(settings: Settings) -> EvaluationWorkflow:
    """构建评估工作流。"""
    llm = _build_llm_client(settings)
    return EvaluationWorkflow(
        llm,
        PromptRepository(),
        _build_tracer(settings),
        settings.evaluation_prompt_version,
    )


# 所属模块：Python Agent 服务边界 > main；函数角色：模块私有业务函数。
# 具体功能：`_build_simulated_import_workflow` 构建模拟外部导入工作流（测试用）；关键协作调用：`SimulatedExternalImportWorkflow`、`HarnessModelRunner`、`PromptRepository`。
# 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_build_llm_client`。
# 系统意义：定义可恢复、可追踪的阶段顺序，使案件重试仍沿相同业务路径推进。
def _build_simulated_import_workflow(
    settings: Settings,
) -> SimulatedExternalImportWorkflow:
    """构建模拟外部导入工作流（测试用）。"""
    llm = _build_llm_client(settings)
    return SimulatedExternalImportWorkflow(
        model_runner=HarnessModelRunner(
            llm=llm,
            prompts=PromptRepository(),
        )
    )


# 所属模块：Python Agent 服务边界 > main；函数角色：模块私有业务函数。
# 具体功能：`_build_hearing_round_turn_workflow` 构建庭审轮转工作流；关键协作调用：`HearingRoundTurnWorkflow`、`HarnessModelRunner`、`PromptRepository`。
# 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_build_llm_client`。
# 系统意义：定义可恢复、可追踪的阶段顺序，使案件重试仍沿相同业务路径推进。
def _build_hearing_round_turn_workflow(settings: Settings) -> HearingRoundTurnWorkflow:
    """构建庭审轮转工作流。"""
    llm = _build_llm_client(settings)
    model_runner = HarnessModelRunner(
        llm=llm,
        prompts=PromptRepository(),
    )
    return HearingRoundTurnWorkflow(
        model_runner=model_runner,
        jury_review_agent=UnifiedJuryReviewAgent(model_runner),
    )


# 所属模块：Python Agent 服务边界 > main；函数角色：模块私有业务函数。
# 具体功能：`_build_final_agent_services` 构建最终代理服务集合：组合所有业务代理（受理、证据、庭审、合议、评审、评估）；关键协作调用：`PromptRepository`、`ModelCriticEvaluator`、`FinalAgentServices`。
# 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `_build_llm_client`。
# 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
def _build_final_agent_services(
    settings: Settings,
    hearing_workflow: HearingWorkflow,
    intake_workflow: IntakeWorkflow,
    evaluation_workflow: EvaluationWorkflow,
) -> FinalAgentServices:
    """构建最终代理服务集合：组合所有业务代理（受理、证据、庭审、合议、评审、评估）。"""
    llm = _build_llm_client(settings)
    prompts = PromptRepository()
    critic_evaluator = ModelCriticEvaluator(llm, prompts)
    return FinalAgentServices(
        intake=DisputeIntakeOfficer(intake_workflow),
        evidence=EvidenceClerk(),
        hearing=PresidingJudge(hearing_workflow),
        deliberation=DeliberationPanel(
            build_default_critics(critic_evaluator)
        ),
        review_copilot=ReviewCopilot(ModelReviewAnswerer(llm, prompts)),
        evaluation=EvaluationAgent(evaluation_workflow),
    )


# ==================== 基础设施构建函数 ====================


# 所属模块：Python Agent 服务边界 > main；函数角色：模块私有业务函数。
# 具体功能：`_build_llm_client` 构建 LiteLLM 代理客户端（连接 LLM 代理层）；关键协作调用：`LiteLlmProxyClient`。
# 上下游：上游为 本文件的 `create_app`、`_build_workflow`、`_build_intake_workflow`、`_build_intake_turn_workflow`；下游为 协作调用 `LiteLlmProxyClient`。
# 系统意义：把不确定模型能力限制在确定性系统边界内：鉴权、追踪、异常映射必须完整；不泄露内部推理。
def _build_llm_client(settings: Settings) -> LiteLlmProxyClient:
    """构建 LiteLLM 代理客户端（连接 LLM 代理层）。"""
    return LiteLlmProxyClient(
        settings.resolved_llm_base_url,
        settings.resolved_llm_model,
        settings.resolved_llm_api_key,
        settings.llm_timeout_seconds,
    )


# 所属模块：Python Agent 服务边界 > main；函数角色：模块私有业务函数。
# 具体功能：`_build_tracer` 构建追踪器：启用 Langfuse 时返回真实追踪器，否则返回空实现；关键协作调用：`LangfuseAgentTracer`、`NoOpAgentTracer`。
# 上下游：上游为 本文件的 `_build_workflow`、`_build_intake_workflow`、`_build_evaluation_workflow`；下游为 协作调用 `LangfuseAgentTracer`、`NoOpAgentTracer`。
# 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
def _build_tracer(settings: Settings):
    """构建追踪器：启用 Langfuse 时返回真实追踪器，否则返回空实现。"""
    if not settings.langfuse_enabled:
        return NoOpAgentTracer()
    return LangfuseAgentTracer(
        settings.langfuse_public_key,
        settings.langfuse_secret_key,
        settings.langfuse_host,
    )


# ==================== 辅助工具函数 ====================


# 所属模块：Python Agent 服务边界 > main；函数角色：模块私有业务函数。
# 具体功能：`_trace_context` 从请求状态中提取追踪上下文（trace_id / request_id 来自中间件）；关键协作调用：`AgentTraceContext`。
# 上下游：上游为 本文件的 `create_app.final_intake`、`create_app.final_hearing`、`create_app.final_evaluation`、`create_app.final_intake_stream`；下游为 协作调用 `AgentTraceContext`。
# 系统意义：控制隐私、Token 和会话隔离：鉴权、追踪、异常映射必须完整；不泄露内部推理。
def _trace_context(
    request: Request,
    *,
    case_id: str,
    workflow_id: str,
    user_id: str | None,
    role: str,
    prompt_version: str,
) -> AgentTraceContext:
    """从请求状态中提取追踪上下文（trace_id / request_id 来自中间件）。"""
    return AgentTraceContext(
        trace_id=request.state.trace_id,
        request_id=request.state.request_id,
        case_id=case_id,
        workflow_id=workflow_id,
        user_id=user_id,
        role=role,
        prompt_version=prompt_version,
    )


# 所属模块：Python Agent 服务边界 > main；函数角色：模块私有业务函数。
# 具体功能：`_authorize` 服务间认证：使用 hmac.compare_digest 进行安全的字符串比较，防止时序攻击；关键协作调用：`compare_digest`、`HTTPException`、`actual.encode`。
# 上下游：上游为 本文件的 `create_app.final_intake`、`create_app.intake_turn`、`create_app.evidence_turn`、`create_app.simulate_external_import`；下游为 协作调用 `compare_digest`、`HTTPException`、`actual.encode`、`expected.encode`。
# 系统意义：失败显式映射为 `HTTPException`，避免错误状态被当成成功结果。
def _authorize(actual: str, expected: str) -> None:
    """服务间认证：使用 hmac.compare_digest 进行安全的字符串比较，防止时序攻击。"""
    if not compare_digest(actual.encode(), expected.encode()):
        raise HTTPException(status_code=401, detail="invalid service credential")


# 所属模块：Python Agent 服务边界 > main；函数角色：模块私有业务函数。
# 具体功能：`_correlation_id` 关联 ID 生成：若传入值合法则直接使用，否则生成带前缀的 UUID；关键协作调用：`SAFE_CORRELATION_ID.fullmatch`、`uuid4`。
# 上下游：上游为 本文件的 `create_app.correlation_middleware`；下游为 协作调用 `SAFE_CORRELATION_ID.fullmatch`、`uuid4`。
# 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
def _correlation_id(value: str | None, prefix: str) -> str:
    """关联 ID 生成：若传入值合法则直接使用，否则生成带前缀的 UUID。"""
    if value and SAFE_CORRELATION_ID.fullmatch(value):
        return value
    return f"{prefix}{uuid4().hex}"


# 所属模块：Python Agent 服务边界 > main；函数角色：模块私有业务函数。
# 具体功能：`_error_response` 统一错误响应格式：包含 success/code/message/details/request_id/trace_id；关键协作调用：`JSONResponse`。
# 上下游：上游为 本文件的 `create_app.validation_error`、`create_app.http_error`、`create_app.upstream_error`、`create_app.schema_error`；下游为 协作调用 `JSONResponse`。
# 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
def _error_response(
    request: Request,
    status_code: int,
    code: str,
    message: str,
    details: dict[str, Any],
) -> JSONResponse:
    """统一错误响应格式：包含 success/code/message/details/request_id/trace_id。"""
    return JSONResponse(
        status_code=status_code,
        content={
            "success": False,
            "code": code,
            "message": message,
            "details": details,
            "request_id": getattr(request.state, "request_id", ""),
            "trace_id": getattr(request.state, "trace_id", ""),
        },
    )
