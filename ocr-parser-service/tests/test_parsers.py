from pathlib import Path
from types import SimpleNamespace

from app.models import ParsedDocument
from app.parsers import DocumentParser, PaddleOcrEngine


class FakePaddle:
    def extract(self, content: bytes) -> ParsedDocument:
        return ParsedDocument(text="图片文字", metadata={"engine": "paddleocr"})


class FakeMarkItDown:
    def extract(self, content: bytes, suffix: str) -> ParsedDocument:
        return ParsedDocument(
            text=f"document:{suffix}", metadata={"engine": "markitdown"}
        )


def test_images_route_to_paddleocr() -> None:
    parser = DocumentParser(FakePaddle(), FakeMarkItDown())

    result = parser.parse(b"png", "image/png", "proof.png")

    assert result.text == "图片文字"
    assert result.metadata["engine"] == "paddleocr"


def test_pdf_word_and_excel_route_to_markitdown() -> None:
    parser = DocumentParser(FakePaddle(), FakeMarkItDown())

    for filename, content_type in [
        ("proof.pdf", "application/pdf"),
        (
            "chat.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        ),
        (
            "ledger.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        ),
    ]:
        result = parser.parse(b"document", content_type, filename)
        assert result.text == f"document:{Path(filename).suffix}"


def test_plain_text_is_decoded_without_external_engine() -> None:
    parser = DocumentParser(FakePaddle(), FakeMarkItDown())

    result = parser.parse("物流轨迹".encode(), "text/plain", "tracking.txt")

    assert result.text == "物流轨迹"
    assert result.metadata["engine"] == "plain-text"


def test_paddleocr_disables_mkldnn_for_cpu_compatibility(monkeypatch) -> None:
    captured: dict[str, object] = {}

    def fake_paddle_ocr(**kwargs):
        captured.update(kwargs)
        return object()

    monkeypatch.setitem(
        __import__("sys").modules,
        "paddleocr",
        SimpleNamespace(PaddleOCR=fake_paddle_ocr),
    )

    PaddleOcrEngine()._get_engine()

    assert captured["enable_mkldnn"] is False
