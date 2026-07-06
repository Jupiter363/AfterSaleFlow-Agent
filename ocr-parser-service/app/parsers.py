from __future__ import annotations

import json
import tempfile
from pathlib import Path
from typing import Protocol

from app.models import ParsedDocument


class ExtractionEngine(Protocol):
    def extract(self, content: bytes) -> ParsedDocument: ...


class DocumentExtractionEngine(Protocol):
    def extract(self, content: bytes, suffix: str) -> ParsedDocument: ...


class DocumentParser:
    IMAGE_TYPES = {"image/png", "image/jpeg"}
    DOCUMENT_TYPES = {
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    }

    def __init__(
        self,
        paddle_engine: ExtractionEngine,
        markitdown_engine: DocumentExtractionEngine,
    ) -> None:
        self._paddle = paddle_engine
        self._markitdown = markitdown_engine

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
    def __init__(self) -> None:
        self._engine = None

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
    def __init__(self) -> None:
        self._engine = None

    def extract(self, content: bytes, suffix: str) -> ParsedDocument:
        with tempfile.NamedTemporaryFile(suffix=suffix) as temporary:
            temporary.write(content)
            temporary.flush()
            result = self._get_engine().convert(temporary.name)
            return ParsedDocument(
                text=result.text_content,
                metadata={"engine": "markitdown", "suffix": suffix},
            )

    def _get_engine(self):
        if self._engine is None:
            from markitdown import MarkItDown

            self._engine = MarkItDown(enable_plugins=False)
        return self._engine
