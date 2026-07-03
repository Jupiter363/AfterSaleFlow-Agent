from __future__ import annotations

import hmac
import re
from datetime import datetime, timezone
from typing import Any
from uuid import uuid4

from fastapi import (
    BackgroundTasks,
    Depends,
    FastAPI,
    Header,
    HTTPException,
    Request,
    status,
)
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.clients import CompositeResultSink, MinioObjectStorage
from app.models import ParseTaskCreate, ParseTaskView
from app.parsers import DocumentParser, MarkItDownEngine, PaddleOcrEngine
from app.service import ParseTaskService, SqliteTaskStore


def create_app(
    service: ParseTaskService | None = None,
    service_secret: str | None = None,
) -> FastAPI:
    if service is None or service_secret is None:
        from app.config import get_settings

        settings = get_settings()
        service_secret = settings.ocr_service_secret
        service = service or ParseTaskService(
            store=SqliteTaskStore(f"{settings.ocr_temp_dir}/parse-tasks.db"),
            storage=MinioObjectStorage(
                settings.minio_endpoint,
                settings.minio_root_user,
                settings.minio_root_password,
            ),
            parser=DocumentParser(PaddleOcrEngine(), MarkItDownEngine()),
            sink=CompositeResultSink(
                settings.java_api_service_url,
                settings.elasticsearch_url,
                settings.ocr_service_secret,
            ),
        )
    app = FastAPI(title="OCR Parser Service", version="v1")
    app.state.task_service = service

    @app.middleware("http")
    async def correlation_middleware(request: Request, call_next):
        request.state.trace_id = correlation_id(
            request.headers.get("X-Trace-Id"), "TRACE_"
        )
        request.state.request_id = correlation_id(
            request.headers.get("X-Request-Id"), "REQ_"
        )
        response = await call_next(request)
        response.headers["X-Trace-Id"] = request.state.trace_id
        response.headers["X-Request-Id"] = request.state.request_id
        return response

    def authenticate(
        x_service_secret: str | None = Header(default=None),
    ) -> None:
        if x_service_secret is None or not hmac.compare_digest(
            x_service_secret, service_secret
        ):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="service authentication required",
            )

    @app.exception_handler(HTTPException)
    async def http_error_handler(request: Request, exception: HTTPException) -> JSONResponse:
        code = (
            "UNAUTHORIZED"
            if exception.status_code == status.HTTP_401_UNAUTHORIZED
            else "NOT_FOUND"
        )
        return JSONResponse(
            status_code=exception.status_code,
            content={
                "success": False,
                "code": code,
                "message": str(exception.detail),
                "request_id": request.state.request_id,
                "trace_id": request.state.trace_id,
                "timestamp": datetime.now(timezone.utc).isoformat(),
            },
        )

    @app.exception_handler(RequestValidationError)
    async def validation_error_handler(
        request: Request, _: RequestValidationError
    ) -> JSONResponse:
        return JSONResponse(
            status_code=422,
            content={
                "success": False,
                "code": "INVALID_ARGUMENT",
                "message": "request validation failed",
                "request_id": request.state.request_id,
                "trace_id": request.state.trace_id,
                "timestamp": datetime.now(timezone.utc).isoformat(),
            },
        )

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "UP", "service": "ocr-parser-service"}

    @app.post(
        "/internal/evidence/parse-tasks",
        status_code=status.HTTP_202_ACCEPTED,
        dependencies=[Depends(authenticate)],
    )
    def create_task(
        request_body: Request,
        request: ParseTaskCreate,
        background_tasks: BackgroundTasks,
    ) -> dict[str, Any]:
        task = app.state.task_service.create(request)
        background_tasks.add_task(app.state.task_service.execute, task.task_id)
        return success(task, request_body)

    @app.get(
        "/internal/evidence/parse-tasks/{task_id}",
        dependencies=[Depends(authenticate)],
    )
    def get_task(task_id: str, request: Request) -> dict[str, Any]:
        try:
            task: ParseTaskView = app.state.task_service.get(task_id)
        except KeyError as exception:
            raise HTTPException(status_code=404, detail="parse task not found") from exception
        return success(task, request)

    return app


def success(data: Any, request: Request) -> dict[str, Any]:
    return {
        "success": True,
        "code": "SUCCESS",
        "message": "success",
        "data": data,
        "request_id": request.state.request_id,
        "trace_id": request.state.trace_id,
        "timestamp": datetime.now(timezone.utc),
    }


def correlation_id(candidate: str | None, prefix: str) -> str:
    if candidate and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", candidate):
        return candidate
    return f"{prefix}{uuid4().hex}"
