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

from app.config import Settings, get_settings
from app.llm import AgentServiceUnavailable, LiteLlmProxyClient
from app.llm import AgentOutputSchemaError
from app.intake import IntakeWorkflow
from app.prompts import PromptRepository
from app.schemas import (
    HearingAnalysisResult,
    HearingAnalyzeRequest,
    IntakeAnalysisOutput,
    IntakeAnalyzeRequest,
)
from app.tracing import (
    AgentTraceContext,
    LangfuseAgentTracer,
    NoOpAgentTracer,
)
from app.workflow import HearingWorkflow


TRACE_HEADER = "X-Trace-Id"
REQUEST_HEADER = "X-Request-Id"
SERVICE_SECRET_HEADER = "X-Service-Secret"
SAFE_CORRELATION_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$")


def create_app(
    settings: Settings | None = None,
    workflow: HearingWorkflow | None = None,
    intake_workflow: IntakeWorkflow | None = None,
) -> FastAPI:
    resolved = settings or get_settings()
    hearing_workflow = workflow or _build_workflow(resolved)
    resolved_intake_workflow = intake_workflow or _build_intake_workflow(resolved)
    app = FastAPI(title="Python Agent Service", version="1.0.0")

    @app.middleware("http")
    async def correlation_middleware(request: Request, call_next):
        trace_id = _correlation_id(
            request.headers.get(TRACE_HEADER), "TRACE_"
        )
        request_id = _correlation_id(
            request.headers.get(REQUEST_HEADER), "REQ_"
        )
        request.state.trace_id = trace_id
        request.state.request_id = request_id
        response = await call_next(request)
        response.headers[TRACE_HEADER] = trace_id
        response.headers[REQUEST_HEADER] = request_id
        return response

    @app.exception_handler(RequestValidationError)
    async def validation_error(request: Request, exception: RequestValidationError):
        return _error_response(
            request,
            422,
            "INVALID_ARGUMENT",
            "request validation failed",
            {"errors": jsonable_encoder(exception.errors())},
        )

    @app.exception_handler(HTTPException)
    async def http_error(request: Request, exception: HTTPException):
        return _error_response(
            request,
            exception.status_code,
            "UNAUTHORIZED" if exception.status_code == 401 else "INVALID_ARGUMENT",
            str(exception.detail),
            {},
        )

    @app.exception_handler(AgentServiceUnavailable)
    async def upstream_error(request: Request, exception: AgentServiceUnavailable):
        return _error_response(
            request,
            503,
            "AGENT_SERVICE_UNAVAILABLE",
            "agent model service unavailable",
            {},
        )

    @app.exception_handler(AgentOutputSchemaError)
    async def schema_error(request: Request, exception: AgentOutputSchemaError):
        return _error_response(
            request,
            502,
            "AGENT_OUTPUT_SCHEMA_INVALID",
            "agent returned invalid structured output",
            {"node_name": exception.node_name},
        )

    @app.exception_handler(Exception)
    async def unexpected_error(request: Request, exception: Exception):
        return _error_response(
            request,
            500,
            "INTERNAL_ERROR",
            "internal service error",
            {},
        )

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "UP", "service": "python-agent-service"}

    @app.post(
        "/agent-api/v1/hearings/analyze",
        response_model=HearingAnalysisResult,
    )
    async def analyze_hearing(
        payload: HearingAnalyzeRequest,
        request: Request,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
        x_role: str = Header(default="SYSTEM", alias="X-Role"),
    ) -> HearingAnalysisResult:
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

    @app.post(
        "/agent-api/v1/intake/analyze",
        response_model=IntakeAnalysisOutput,
    )
    async def analyze_intake(
        payload: IntakeAnalyzeRequest,
        request: Request,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
        x_role: str = Header(default="SYSTEM", alias="X-Role"),
    ) -> IntakeAnalysisOutput:
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

    return app


def _build_workflow(settings: Settings) -> HearingWorkflow:
    llm = LiteLlmProxyClient(
        settings.litellm_base_url,
        settings.litellm_model,
        settings.litellm_master_key,
        settings.llm_timeout_seconds,
    )
    tracer = _build_tracer(settings)
    return HearingWorkflow(
        llm,
        PromptRepository(),
        tracer,
        settings.litellm_model,
        settings.prompt_version,
    )


def _build_intake_workflow(settings: Settings) -> IntakeWorkflow:
    llm = LiteLlmProxyClient(
        settings.litellm_base_url,
        settings.litellm_model,
        settings.litellm_master_key,
        settings.llm_timeout_seconds,
    )
    tracer = _build_tracer(settings)
    return IntakeWorkflow(
        llm,
        PromptRepository(),
        tracer,
        settings.litellm_model,
    )


def _build_tracer(settings: Settings):
    if not settings.langfuse_enabled:
        return NoOpAgentTracer()
    return LangfuseAgentTracer(
        settings.langfuse_public_key,
        settings.langfuse_secret_key,
        settings.langfuse_host,
    )


def _authorize(actual: str, expected: str) -> None:
    if not compare_digest(actual.encode(), expected.encode()):
        raise HTTPException(status_code=401, detail="invalid service credential")


def _correlation_id(value: str | None, prefix: str) -> str:
    if value and SAFE_CORRELATION_ID.fullmatch(value):
        return value
    return f"{prefix}{uuid4().hex}"


def _error_response(
    request: Request,
    status_code: int,
    code: str,
    message: str,
    details: dict[str, Any],
) -> JSONResponse:
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
