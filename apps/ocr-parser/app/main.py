# 文件作用：OCR 解析服务代码文件，负责证据文件解析、外部调用、数据模型或接口处理。

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


# 所属模块：Python Agent 服务边界 > main；函数角色：模块公开业务函数。
# 具体功能：`create_app` 把上游材料组装为本阶段可消费的本阶段状态；关键协作调用：`FastAPI`、`app.middleware`、`app.exception_handler`；返回/更新字段：`status`、`service`。
# 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `correlation_id`、`success`。
# 系统意义：失败显式映射为 `HTTPException`，避免错误状态被当成成功结果。
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

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`correlation_middleware` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`app.middleware`、`request.headers.get`、`call_next`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `correlation_id`。
    # 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
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

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`authenticate` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`Header`、`HTTPException`、`hmac.compare_digest`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 协作调用 `Header`、`HTTPException`、`hmac.compare_digest`。
    # 系统意义：失败显式映射为 `HTTPException`，避免错误状态被当成成功结果。
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

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`http_error_handler` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`app.exception_handler`、`JSONResponse`、`isoformat`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 协作调用 `app.exception_handler`、`JSONResponse`、`isoformat`、`datetime.now`。
    # 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
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

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`validation_error_handler` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`app.exception_handler`、`JSONResponse`、`isoformat`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 协作调用 `app.exception_handler`、`JSONResponse`、`isoformat`、`datetime.now`。
    # 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
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

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`health` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`app.get`；返回/更新字段：`status`、`service`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 协作调用 `app.get`。
    # 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "UP", "service": "ocr-parser-service"}

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`create_task` 把上游材料组装为本阶段可消费的本阶段状态；关键协作调用：`app.post`、`app.state.task_service.create`、`background_tasks.add_task`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `success`。
    # 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
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

    # 所属模块：Python Agent 服务边界 > main；函数角色：类/闭包内部方法。
    # 具体功能：`get_task` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`app.get`、`app.state.task_service.get`、`HTTPException`。
    # 上下游：上游为 Java 内部鉴权 HTTP 请求、关联 ID；下游为 本文件的 `success`。
    # 系统意义：失败显式映射为 `HTTPException`，避免错误状态被当成成功结果。
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


# 所属模块：Python Agent 服务边界 > main；函数角色：模块公开业务函数。
# 具体功能：`success` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`datetime.now`；返回/更新字段：`success`、`code`、`message`、`data`。
# 上下游：上游为 本文件的 `create_app`、`create_app.create_task`、`create_app.get_task`；下游为 协作调用 `datetime.now`。
# 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
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


# 所属模块：Python Agent 服务边界 > main；函数角色：模块公开业务函数。
# 具体功能：`correlation_id` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`re.fullmatch`、`uuid4`。
# 上下游：上游为 本文件的 `create_app`、`create_app.correlation_middleware`；下游为 协作调用 `re.fullmatch`、`uuid4`。
# 系统意义：该函数在系统中的业务边界是：鉴权、追踪、异常映射必须完整；不泄露内部推理。
def correlation_id(candidate: str | None, prefix: str) -> str:
    if candidate and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", candidate):
        return candidate
    return f"{prefix}{uuid4().hex}"
