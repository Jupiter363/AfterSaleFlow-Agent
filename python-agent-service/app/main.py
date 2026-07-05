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

from app.agents.critics import build_default_critics
from app.agents.deliberation_panel import DeliberationPanel
from app.agents.dispute_intake_officer import DisputeIntakeOfficer
from app.agents.evaluation_agent import EvaluationAgent
from app.agents.evidence_clerk import EvidenceClerk
from app.agents.model_roles import ModelCriticEvaluator, ModelReviewAnswerer
from app.agents.presiding_judge import PresidingJudge
from app.agents.review_copilot import ReviewCopilot
from app.business.api.final_agents import FinalAgentServices
from app.config import Settings, get_settings
from app.harness.guardrails import GuardrailViolation
from app.harness.model_runner import HarnessModelRunner
from app.harness.validation import CitationValidationError
from app.llm import AgentServiceUnavailable, LiteLlmProxyClient
from app.llm import AgentOutputSchemaError
from app.intake import IntakeWorkflow
from app.intake_turn import IntakeTurnWorkflow
from app.evaluation import EvaluationWorkflow
from app.harness.prompt_composer import PromptRepository
from app.schemas import (
    HearingAnalysisResult,
    HearingAnalyzeRequest,
    HearingStageRequest,
    HearingStageResult,
    DeliberationReport,
    DeliberationRequest,
    DisputeIntakeRequest,
    DisputeIntakeResult,
    EvidenceBuildRequest,
    EvidenceDossierResult,
    EvaluationAnalysisResult,
    EvaluationAnalyzeRequest,
    IntakeAnalysisOutput,
    IntakeAnalyzeRequest,
    IntakeTurnRequest,
    IntakeTurnResult,
    ReviewCopilotAnswer,
    ReviewCopilotRequest,
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
    intake_turn_workflow: IntakeTurnWorkflow | None = None,
    evaluation_workflow: EvaluationWorkflow | None = None,
    final_agent_services: FinalAgentServices | None = None,
) -> FastAPI:
    if (
        evaluation_workflow is None
        and intake_turn_workflow is not None
        and hasattr(intake_turn_workflow, "analyze")
        and not hasattr(intake_turn_workflow, "run")
    ):
        evaluation_workflow = intake_turn_workflow  # type: ignore[assignment]
        intake_turn_workflow = None
    resolved = settings or get_settings()
    hearing_workflow = workflow or _build_workflow(resolved)
    resolved_intake_workflow = intake_workflow or _build_intake_workflow(resolved)
    resolved_intake_turn_workflow = intake_turn_workflow or _build_intake_turn_workflow(
        resolved
    )
    resolved_evaluation_workflow = evaluation_workflow or _build_evaluation_workflow(
        resolved
    )
    final_services = final_agent_services or _build_final_agent_services(
        resolved,
        hearing_workflow,
        resolved_intake_workflow,
        resolved_evaluation_workflow,
    )
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

    @app.exception_handler(PermissionError)
    async def permission_error(request: Request, exception: PermissionError):
        return _error_response(
            request,
            403,
            "AGENT_PERMISSION_DENIED",
            str(exception),
            {},
        )

    @app.exception_handler(CitationValidationError)
    @app.exception_handler(GuardrailViolation)
    async def governance_error(request: Request, exception: Exception):
        return _error_response(
            request,
            422,
            "AGENT_GOVERNANCE_REJECTED",
            str(exception),
            {},
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

    @app.post(
        "/internal/agents/intake/turn",
        response_model=IntakeTurnResult,
    )
    async def intake_turn(
        payload: IntakeTurnRequest,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
    ) -> IntakeTurnResult:
        _authorize(x_service_secret, resolved.python_agent_service_secret)
        return await run_in_threadpool(resolved_intake_turn_workflow.run, payload)

    @app.post(
        "/internal/agents/evidence/build",
        response_model=EvidenceDossierResult,
    )
    async def final_evidence(
        payload: EvidenceBuildRequest,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
    ) -> EvidenceDossierResult:
        _authorize(x_service_secret, resolved.python_agent_service_secret)
        return await run_in_threadpool(final_services.evidence.build, payload)

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

    @app.post(
        "/internal/agents/deliberation/run",
        response_model=DeliberationReport,
    )
    async def final_deliberation(
        payload: DeliberationRequest,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
    ) -> DeliberationReport:
        _authorize(x_service_secret, resolved.python_agent_service_secret)
        return await run_in_threadpool(final_services.deliberation.run, payload)

    @app.post(
        "/internal/agents/review-copilot/query",
        response_model=ReviewCopilotAnswer,
    )
    async def final_review_copilot(
        payload: ReviewCopilotRequest,
        x_service_secret: str = Header(alias=SERVICE_SECRET_HEADER),
    ) -> ReviewCopilotAnswer:
        _authorize(x_service_secret, resolved.python_agent_service_secret)
        return await run_in_threadpool(
            final_services.review_copilot.query,
            payload,
        )

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
        "/internal/agents/legacy/intake/analyze",
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


def _build_intake_turn_workflow(settings: Settings) -> IntakeTurnWorkflow:
    llm = LiteLlmProxyClient(
        settings.litellm_base_url,
        settings.litellm_model,
        settings.litellm_master_key,
        settings.llm_timeout_seconds,
    )
    return IntakeTurnWorkflow(
        model_runner=HarnessModelRunner(
            llm=llm,
            prompts=PromptRepository(),
        )
    )


def _build_evaluation_workflow(settings: Settings) -> EvaluationWorkflow:
    llm = LiteLlmProxyClient(
        settings.litellm_base_url,
        settings.litellm_model,
        settings.litellm_master_key,
        settings.llm_timeout_seconds,
    )
    return EvaluationWorkflow(
        llm,
        PromptRepository(),
        _build_tracer(settings),
        settings.evaluation_prompt_version,
    )


def _build_final_agent_services(
    settings: Settings,
    hearing_workflow: HearingWorkflow,
    intake_workflow: IntakeWorkflow,
    evaluation_workflow: EvaluationWorkflow,
) -> FinalAgentServices:
    llm = LiteLlmProxyClient(
        settings.litellm_base_url,
        settings.litellm_model,
        settings.litellm_master_key,
        settings.llm_timeout_seconds,
    )
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


def _build_tracer(settings: Settings):
    if not settings.langfuse_enabled:
        return NoOpAgentTracer()
    return LangfuseAgentTracer(
        settings.langfuse_public_key,
        settings.langfuse_secret_key,
        settings.langfuse_host,
    )


def _trace_context(
    request: Request,
    *,
    case_id: str,
    workflow_id: str,
    user_id: str | None,
    role: str,
    prompt_version: str,
) -> AgentTraceContext:
    return AgentTraceContext(
        trace_id=request.state.trace_id,
        request_id=request.state.request_id,
        case_id=case_id,
        workflow_id=workflow_id,
        user_id=user_id,
        role=role,
        prompt_version=prompt_version,
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
