# 文件作用：OCR 解析服务代码文件，负责证据文件解析、外部调用、数据模型或接口处理。

from __future__ import annotations

import json
import tempfile
from pathlib import Path
from typing import Protocol

from app.models import ParsedDocument


class ExtractionEngine(Protocol):
    # 所属模块：Python 支撑模块 > parsers；函数角色：类/闭包内部方法。
    # 具体功能：`extract` 围绕本阶段状态计算该函数独立负责的业务派生值。
    # 上下游：上游为 本文件的 `DocumentParser.parse`；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def extract(self, content: bytes) -> ParsedDocument: ...


class DocumentExtractionEngine(Protocol):
    # 所属模块：Python 支撑模块 > parsers；函数角色：类/闭包内部方法。
    # 具体功能：`extract` 围绕本阶段状态计算该函数独立负责的业务派生值。
    # 上下游：上游为 本文件的 `DocumentParser.parse`；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def extract(self, content: bytes, suffix: str) -> ParsedDocument: ...


class DocumentParser:
    IMAGE_TYPES = {"image/png", "image/jpeg"}
    DOCUMENT_TYPES = {
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    }

    # 所属模块：Python 支撑模块 > parsers；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖。
    # 上下游：上游为 相邻模块输入；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def __init__(
        self,
        paddle_engine: ExtractionEngine,
        markitdown_engine: DocumentExtractionEngine,
    ) -> None:
        self._paddle = paddle_engine
        self._markitdown = markitdown_engine

    # 所属模块：Python 支撑模块 > parsers；函数角色：类/闭包内部方法。
    # 具体功能：`parse` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`ValueError`、`ParsedDocument`、`suffix.lower`。
    # 上下游：上游为 相邻模块输入；下游为 本文件的 `extract`。
    # 系统意义：失败显式映射为 `ValueError`，避免错误状态被当成成功结果。
    def parse(self, content: bytes, content_type: str, filename: str) -> ParsedDocument:
        if content_type in self.IMAGE_TYPES:
            return self._paddle.extract(content)
        if content_type in self.DOCUMENT_TYPES:
            return self._markitdown.extract(content, Path(filename).suffix.lower())
        if content_type in {"text/plain", "text/markdown"}:
            engine = "markdown-text" if content_type == "text/markdown" else "plain-text"
            return ParsedDocument(
                text=content.decode("utf-8-sig"),
                metadata={"engine": engine},
            )
        raise ValueError("unsupported content type")


class PaddleOcrEngine:
    # 所属模块：Python 支撑模块 > parsers；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖。
    # 上下游：上游为 相邻模块输入；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def __init__(self) -> None:
        self._engine = None

    # 所属模块：Python 支撑模块 > parsers；函数角色：类/闭包内部方法。
    # 具体功能：`extract` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`tempfile.NamedTemporaryFile`、`temporary.write`、`temporary.flush`。
    # 上下游：上游为 本文件的 `DocumentParser.parse`；下游为 本文件的 `_get_engine`、`_result_payload`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def extract(self, content: bytes) -> ParsedDocument:
        with tempfile.NamedTemporaryFile(suffix=".png") as temporary:
            temporary.write(content)
            temporary.flush()
            engine = self._get_engine()
            results = engine.predict(input=temporary.name)
            texts: list[str] = []
            scores: list[float] = []
            for result in results:
                payload = self._result_payload(result)
                result_data = payload.get("res", payload)
                texts.extend(result_data.get("rec_texts", []))
                scores.extend(result_data.get("rec_scores", []))
            return ParsedDocument(
                text="\n".join(texts),
                metadata={
                    "engine": "paddleocr",
                    "line_count": len(texts),
                    "average_confidence": (
                        sum(float(score) for score in scores) / len(scores)
                        if scores
                        else None
                    ),
                },
            )

    # 所属模块：Python 支撑模块 > parsers；函数角色：类/闭包内部方法。
    # 具体功能：`_get_engine` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`PaddleOCR`。
    # 上下游：上游为 本文件的 `PaddleOcrEngine.extract`、`MarkItDownEngine.extract`；下游为 协作调用 `PaddleOCR`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def _get_engine(self):
        if self._engine is None:
            from paddleocr import PaddleOCR

            self._engine = PaddleOCR(
                lang="ch",
                enable_mkldnn=False,
                use_doc_orientation_classify=False,
                use_doc_unwarping=False,
                use_textline_orientation=False,
            )
        return self._engine

    # 所属模块：Python 支撑模块 > parsers；函数角色：类/闭包内部方法。
    # 具体功能：`_result_payload` 读取并按案件、角色或会话范围筛选阶段结果；关键协作调用：`callable`、`ValueError`、`payload`。
    # 上下游：上游为 本文件的 `PaddleOcrEngine.extract`；下游为 协作调用 `callable`、`ValueError`、`payload`、`json.loads`。
    # 系统意义：失败显式映射为 `ValueError`，避免错误状态被当成成功结果。
    @staticmethod
    def _result_payload(result) -> dict:
        payload = getattr(result, "json", result)
        if callable(payload):
            payload = payload()
        if isinstance(payload, str):
            return json.loads(payload)
        if isinstance(payload, dict):
            return payload
        raise ValueError("unsupported PaddleOCR result")


class MarkItDownEngine:
    # 所属模块：Python 支撑模块 > parsers；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖。
    # 上下游：上游为 相邻模块输入；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def __init__(self) -> None:
        self._engine = None

    # 所属模块：Python 支撑模块 > parsers；函数角色：类/闭包内部方法。
    # 具体功能：`extract` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`tempfile.NamedTemporaryFile`、`temporary.write`、`temporary.flush`。
    # 上下游：上游为 本文件的 `DocumentParser.parse`；下游为 本文件的 `_get_engine`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def extract(self, content: bytes, suffix: str) -> ParsedDocument:
        with tempfile.NamedTemporaryFile(suffix=suffix) as temporary:
            temporary.write(content)
            temporary.flush()
            result = self._get_engine().convert(temporary.name)
            return ParsedDocument(
                text=result.text_content,
                metadata={"engine": "markitdown", "suffix": suffix},
            )

    # 所属模块：Python 支撑模块 > parsers；函数角色：类/闭包内部方法。
    # 具体功能：`_get_engine` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`MarkItDown`。
    # 上下游：上游为 本文件的 `PaddleOcrEngine.extract`、`MarkItDownEngine.extract`；下游为 协作调用 `MarkItDown`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def _get_engine(self):
        if self._engine is None:
            from markitdown import MarkItDown

            self._engine = MarkItDown(enable_plugins=False)
        return self._engine
