# 文件作用：自动化测试文件，验证 container_document_smoke 相关模块的行为、契约或页面布局。

"""Container smoke test for the real MarkItDown document dependencies."""

from io import BytesIO
from pathlib import Path
from tempfile import TemporaryDirectory
from zipfile import ZIP_DEFLATED, ZipFile

from openpyxl import Workbook

from app.parsers import MarkItDownEngine


# 所属模块：Python 支撑模块 > container_document_smoke；函数角色：模块公开业务函数。
# 具体功能：`make_docx` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`BytesIO`、`output.getvalue`、`ZipFile`。
# 上下游：上游为 本文件的 `main`；下游为 协作调用 `BytesIO`、`output.getvalue`、`ZipFile`、`archive.writestr`。
# 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
def make_docx(text: str) -> bytes:
    output = BytesIO()
    with ZipFile(output, "w", ZIP_DEFLATED) as archive:
        archive.writestr(
            "[Content_Types].xml",
            """<?xml version="1.0" encoding="UTF-8"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            </Types>""",
        )
        archive.writestr(
            "_rels/.rels",
            """<?xml version="1.0" encoding="UTF-8"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            </Relationships>""",
        )
        archive.writestr(
            "word/document.xml",
            f"""<?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body><w:p><w:r><w:t>{text}</w:t></w:r></w:p></w:body>
            </w:document>""",
        )
    return output.getvalue()


# 所属模块：Python 支撑模块 > container_document_smoke；函数角色：模块公开业务函数。
# 具体功能：`make_xlsx` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`BytesIO`、`Workbook`、`workbook.save`。
# 上下游：上游为 本文件的 `main`；下游为 协作调用 `BytesIO`、`Workbook`、`workbook.save`、`output.getvalue`。
# 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
def make_xlsx(text: str) -> bytes:
    output = BytesIO()
    workbook = Workbook()
    workbook.active["A1"] = text
    workbook.save(output)
    return output.getvalue()


# 所属模块：Python 支撑模块 > container_document_smoke；函数角色：模块公开业务函数。
# 具体功能：`make_pdf` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`encode`、`output.extend`、`offsets.append`。
# 上下游：上游为 本文件的 `main`；下游为 协作调用 `encode`、`output.extend`、`offsets.append`。
# 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
def make_pdf(text: str) -> bytes:
    stream = f"BT /F1 18 Tf 72 720 Td ({text}) Tj ET".encode("ascii")
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
        b"/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        b"<< /Length " + str(len(stream)).encode("ascii") + b" >>\nstream\n"
        + stream
        + b"\nendstream",
    ]
    output = bytearray(b"%PDF-1.4\n")
    offsets = [0]
    for index, item in enumerate(objects, start=1):
        offsets.append(len(output))
        output.extend(f"{index} 0 obj\n".encode("ascii"))
        output.extend(item)
        output.extend(b"\nendobj\n")
    xref = len(output)
    output.extend(f"xref\n0 {len(objects) + 1}\n".encode("ascii"))
    output.extend(b"0000000000 65535 f \n")
    for offset in offsets[1:]:
        output.extend(f"{offset:010d} 00000 n \n".encode("ascii"))
    output.extend(
        f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\n"
        f"startxref\n{xref}\n%%EOF\n".encode("ascii")
    )
    return bytes(output)


# 所属模块：Python 支撑模块 > container_document_smoke；函数角色：模块公开业务函数。
# 具体功能：`main` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`MarkItDownEngine`、`TemporaryDirectory`、`fixtures.items`。
# 上下游：上游为 相邻模块输入；下游为 本文件的 `make_docx`、`make_xlsx`、`make_pdf`。
# 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
def main() -> None:
    engine = MarkItDownEngine()
    fixtures = {
        ".docx": (make_docx("DOCX_DELIVERED"), "DOCX_DELIVERED"),
        ".xlsx": (make_xlsx("XLSX_SIGNED"), "XLSX_SIGNED"),
        ".pdf": (make_pdf("PDF_RECEIVED"), "PDF_RECEIVED"),
    }
    with TemporaryDirectory() as directory:
        for suffix, (content, expected) in fixtures.items():
            Path(directory, f"fixture{suffix}").write_bytes(content)
            parsed = engine.extract(content, suffix)
            normalized_text = parsed.text.replace("\\_", "_")
            assert expected in normalized_text, (suffix, parsed.text)
            print(f"{suffix}=ok")


if __name__ == "__main__":
    main()
