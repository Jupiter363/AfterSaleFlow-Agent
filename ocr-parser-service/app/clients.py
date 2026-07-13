# 文件作用：OCR 解析服务代码文件，负责证据文件解析、外部调用、数据模型或接口处理。

from __future__ import annotations

import logging
from urllib.parse import urlparse

import httpx

from app.models import ParseTaskCreate, ParsedDocument

LOGGER = logging.getLogger(__name__)


class MinioObjectStorage:
    # 所属模块：Python 支撑模块 > clients；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖；关键协作调用：`urlparse`、`Minio`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `urlparse`、`Minio`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def __init__(self, endpoint: str, access_key: str, secret_key: str) -> None:
        from minio import Minio

        parsed = urlparse(endpoint)
        self._client = Minio(
            parsed.netloc,
            access_key=access_key,
            secret_key=secret_key,
            secure=parsed.scheme == "https",
        )

    # 所属模块：Python 支撑模块 > clients；函数角色：类/闭包内部方法。
    # 具体功能：`download` 读取并按案件、角色或会话范围筛选本阶段状态；关键协作调用：`self._client.get_object`、`response.read`、`response.close`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `self._client.get_object`、`response.read`、`response.close`、`response.release_conn`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def download(self, bucket: str, object_key: str) -> bytes:
        response = self._client.get_object(bucket, object_key)
        try:
            return response.read()
        finally:
            response.close()
            response.release_conn()


class CompositeResultSink:
    # 所属模块：Python 支撑模块 > clients；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖；关键协作调用：`java_api_url.rstrip`、`elasticsearch_url.rstrip`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `java_api_url.rstrip`、`elasticsearch_url.rstrip`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def __init__(
        self,
        java_api_url: str,
        elasticsearch_url: str,
        service_secret: str,
    ) -> None:
        self._java_api_url = java_api_url.rstrip("/")
        self._elasticsearch_url = elasticsearch_url.rstrip("/")
        self._headers = {
            "X-Service-Secret": service_secret,
            "X-Service-Identity": "ocr-parser-service",
        }

    # 所属模块：Python 支撑模块 > clients；函数角色：类/闭包内部方法。
    # 具体功能：`publish_success` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`httpx.Client`、`raise_for_status`、`client.post`。
    # 上下游：上游为 相邻模块输入；下游为 本文件的 `_parse_result_url`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def publish_success(
        self, request: ParseTaskCreate, document: ParsedDocument
    ) -> None:
        callback_url = self._parse_result_url(request)
        payload = {
            "status": "SUCCEEDED",
            "text": document.text,
            "metadata": document.metadata,
        }
        with httpx.Client(timeout=15.0) as client:
            client.post(
                callback_url,
                headers=self._headers,
                json=payload,
            ).raise_for_status()
            try:
                client.put(
                    f"{self._elasticsearch_url}/evidence_index/_doc/"
                    f"{request.evidence_id}",
                    json={
                        "evidence_id": request.evidence_id,
                        "case_id": request.case_id,
                        "content_type": request.content_type,
                        "parsed_text": document.text,
                        "parse_status": "SUCCEEDED",
                        "extraction": document.metadata,
                    },
                ).raise_for_status()
            except httpx.HTTPError as exception:
                LOGGER.warning(
                    "Evidence parsed text indexing deferred: evidence_id=%s, error_type=%s",
                    request.evidence_id,
                    exception.__class__.__name__,
                )

    # 所属模块：Python 支撑模块 > clients；函数角色：类/闭包内部方法。
    # 具体功能：`publish_failure` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`httpx.Client`、`raise_for_status`、`client.post`。
    # 上下游：上游为 相邻模块输入；下游为 本文件的 `_parse_result_url`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def publish_failure(self, request: ParseTaskCreate, error_code: str) -> None:
        callback_url = self._parse_result_url(request)
        with httpx.Client(timeout=15.0) as client:
            client.post(
                callback_url,
                headers=self._headers,
                json={"status": "FAILED", "error_code": error_code},
            ).raise_for_status()

    # 所属模块：Python 支撑模块 > clients；函数角色：类/闭包内部方法。
    # 具体功能：`_parse_result_url` 围绕阶段结果计算该函数独立负责的业务派生值。
    # 上下游：上游为 本文件的 `CompositeResultSink.publish_success`、`CompositeResultSink.publish_failure`；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def _parse_result_url(self, request: ParseTaskCreate) -> str:
        if request.callback_url:
            return request.callback_url
        return (
            f"{self._java_api_url}/internal/evidence/"
            f"{request.evidence_id}/parse-result"
        )
