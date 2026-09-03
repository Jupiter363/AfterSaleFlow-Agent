# 文件作用：自动化测试文件，验证 test_parsers 相关模块的行为、契约或页面布局。

from pathlib import Path
from types import SimpleNamespace

from app.models import ParsedDocument
from app.parsers import DocumentParser, PaddleOcrEngine


class FakePaddle:
    # 所属模块：Python 支撑模块 > test_parsers；函数角色：类/闭包内部方法。
    # 具体功能：`extract` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`ParsedDocument`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `ParsedDocument`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def extract(self, content: bytes) -> ParsedDocument:
        return ParsedDocument(text="图片文字", metadata={"engine": "paddleocr"})


class FakeMarkItDown:
    # 所属模块：Python 支撑模块 > test_parsers；函数角色：类/闭包内部方法。
    # 具体功能：`extract` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`ParsedDocument`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `ParsedDocument`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def extract(self, content: bytes, suffix: str) -> ParsedDocument:
        return ParsedDocument(
            text=f"document:{suffix}", metadata={"engine": "markitdown"}
        )


# 所属模块：Python 支撑模块 > test_parsers；函数角色：回归测试用例。
# 具体功能：`test_images_route_to_paddleocr` 验证本阶段状态在固定案例中的输出、边界和失败行为；关键协作调用：`DocumentParser`、`parser.parse`、`FakePaddle`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `DocumentParser`、`parser.parse`、`FakePaddle`、`FakeMarkItDown`。
# 系统意义：固定“Python 支撑模块 > test_parsers”的可观察契约，防止后续重构改变业务结果。
def test_images_route_to_paddleocr() -> None:
    parser = DocumentParser(FakePaddle(), FakeMarkItDown())

    result = parser.parse(b"png", "image/png", "proof.png")

    assert result.text == "图片文字"
    assert result.metadata["engine"] == "paddleocr"


# 所属模块：Python 支撑模块 > test_parsers；函数角色：回归测试用例。
# 具体功能：`test_pdf_word_and_excel_route_to_markitdown` 验证本阶段状态在固定案例中的输出、边界和失败行为；关键协作调用：`DocumentParser`、`FakePaddle`、`FakeMarkItDown`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `DocumentParser`、`FakePaddle`、`FakeMarkItDown`、`parser.parse`。
# 系统意义：固定“Python 支撑模块 > test_parsers”的可观察契约，防止后续重构改变业务结果。
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


# 所属模块：Python 支撑模块 > test_parsers；函数角色：回归测试用例。
# 具体功能：`test_plain_text_is_decoded_without_external_engine` 验证展示文本在固定案例中的输出、边界和失败行为；关键协作调用：`DocumentParser`、`parser.parse`、`FakePaddle`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `DocumentParser`、`parser.parse`、`FakePaddle`、`FakeMarkItDown`。
# 系统意义：固定“Python 支撑模块 > test_parsers”的可观察契约，防止后续重构改变业务结果。
def test_plain_text_is_decoded_without_external_engine() -> None:
    parser = DocumentParser(FakePaddle(), FakeMarkItDown())

    result = parser.parse("物流轨迹".encode(), "text/plain", "tracking.txt")

    assert result.text == "物流轨迹"
    assert result.metadata["engine"] == "plain-text"


# 所属模块：Python 支撑模块 > test_parsers；函数角色：回归测试用例。
# 具体功能：`test_markdown_is_decoded_without_external_engine` 验证本阶段状态在固定案例中的输出、边界和失败行为；关键协作调用：`DocumentParser`、`parser.parse`、`FakePaddle`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `DocumentParser`、`parser.parse`、`FakePaddle`、`FakeMarkItDown`。
# 系统意义：固定“Python 支撑模块 > test_parsers”的可观察契约，防止后续重构改变业务结果。
def test_markdown_is_decoded_without_external_engine() -> None:
    parser = DocumentParser(FakePaddle(), FakeMarkItDown())

    result = parser.parse(
        "# 证据说明\n签收后发现表盘划痕。".encode(),
        "text/markdown",
        "statement.md",
    )

    assert "表盘划痕" in result.text
    assert result.metadata["engine"] == "markdown-text"


# 所属模块：Python 支撑模块 > test_parsers；函数角色：回归测试用例。
# 具体功能：`test_paddleocr_disables_mkldnn_for_cpu_compatibility` 验证本阶段状态在固定案例中的输出、边界和失败行为；关键协作调用：`monkeypatch.setitem`、`_get_engine`、`captured.update`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `monkeypatch.setitem`、`_get_engine`、`captured.update`、`object`。
# 系统意义：固定“Python 支撑模块 > test_parsers”的可观察契约，防止后续重构改变业务结果。
def test_paddleocr_disables_mkldnn_for_cpu_compatibility(monkeypatch) -> None:
    captured: dict[str, object] = {}

    # 所属模块：Python 支撑模块 > test_parsers；函数角色：类/闭包内部方法。
    # 具体功能：`fake_paddle_ocr` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`captured.update`、`object`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `captured.update`、`object`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
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
