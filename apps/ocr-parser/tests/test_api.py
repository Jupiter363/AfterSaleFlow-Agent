# 文件作用：自动化测试文件，验证 test_api 相关模块的行为、契约或页面布局。

from fastapi.testclient import TestClient

from app.main import create_app
from app.models import ParseTaskCreate, ParsedDocument
from app.service import InMemoryTaskStore, ParseTaskService


class FakeStorage:
    # 所属模块：Python 支撑模块 > test_api；函数角色：类/闭包内部方法。
    # 具体功能：`download` 读取并按案件、角色或会话范围筛选本阶段状态。
    # 上下游：上游为 相邻模块输入；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def download(self, bucket: str, object_key: str) -> bytes:
        assert bucket == "evidence-original"
        assert object_key == "CASE_1/EVIDENCE_1/proof.png"
        return b"\x89PNG\r\n\x1a\n"


class FakeParser:
    # 所属模块：Python 支撑模块 > test_api；函数角色：类/闭包内部方法。
    # 具体功能：`parse` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`ParsedDocument`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `ParsedDocument`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def parse(self, content: bytes, content_type: str, filename: str) -> ParsedDocument:
        assert content_type == "image/png"
        return ParsedDocument(text="签收证明：本人未签收", metadata={"engine": "fake-ocr"})


class FakeSink:
    # 所属模块：Python 支撑模块 > test_api；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖。
    # 上下游：上游为 相邻模块输入；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def __init__(self) -> None:
        self.results: list[tuple[ParseTaskCreate, ParsedDocument]] = []

    # 所属模块：Python 支撑模块 > test_api；函数角色：类/闭包内部方法。
    # 具体功能：`publish_success` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`self.results.append`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `self.results.append`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def publish_success(
        self, request: ParseTaskCreate, document: ParsedDocument
    ) -> None:
        self.results.append((request, document))

    # 所属模块：Python 支撑模块 > test_api；函数角色：类/闭包内部方法。
    # 具体功能：`publish_failure` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`AssertionError`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `AssertionError`。
    # 系统意义：失败显式映射为 `AssertionError`，避免错误状态被当成成功结果。
    def publish_failure(self, request: ParseTaskCreate, error_code: str) -> None:
        raise AssertionError(f"unexpected parse failure: {error_code}")


# 所属模块：Python 支撑模块 > test_api；函数角色：模块公开业务函数。
# 具体功能：`client_with_fakes` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`FakeSink`、`ParseTaskService`、`create_app`。
# 上下游：上游为 本文件的 `test_health_is_public`、`test_parse_task_requires_service_secret`、`test_create_and_query_parse_task`、`test_invalid_payload_uses_unified_error`；下游为 协作调用 `FakeSink`、`ParseTaskService`、`create_app`、`TestClient`。
# 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
def client_with_fakes() -> tuple[TestClient, FakeSink]:
    sink = FakeSink()
    service = ParseTaskService(
        store=InMemoryTaskStore(),
        storage=FakeStorage(),
        parser=FakeParser(),
        sink=sink,
    )
    app = create_app(service=service, service_secret="test-ocr-secret")
    return TestClient(app), sink


# 所属模块：Python 支撑模块 > test_api；函数角色：回归测试用例。
# 具体功能：`test_health_is_public` 验证本阶段状态在固定案例中的输出、边界和失败行为；关键协作调用：`client.get`、`response.json`。
# 上下游：上游为 相邻模块输入；下游为 本文件的 `client_with_fakes`。
# 系统意义：固定“Python 支撑模块 > test_api”的可观察契约，防止后续重构改变业务结果。
def test_health_is_public() -> None:
    client, _ = client_with_fakes()

    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP", "service": "ocr-parser-service"}


# 所属模块：Python 支撑模块 > test_api；函数角色：回归测试用例。
# 具体功能：`test_parse_task_requires_service_secret` 验证本阶段状态在固定案例中的输出、边界和失败行为；关键协作调用：`client.post`、`response.json`。
# 上下游：上游为 相邻模块输入；下游为 本文件的 `client_with_fakes`。
# 系统意义：固定“Python 支撑模块 > test_api”的可观察契约，防止后续重构改变业务结果。
def test_parse_task_requires_service_secret() -> None:
    client, _ = client_with_fakes()

    response = client.post("/internal/evidence/parse-tasks", json={})

    assert response.status_code == 401
    assert response.json()["code"] == "UNAUTHORIZED"


# 所属模块：Python 支撑模块 > test_api；函数角色：回归测试用例。
# 具体功能：`test_create_and_query_parse_task` 把上游材料组装为本阶段可消费的本阶段状态；关键协作调用：`client.post`、`startswith`、`client.get`。
# 上下游：上游为 相邻模块输入；下游为 本文件的 `client_with_fakes`。
# 系统意义：固定“Python 支撑模块 > test_api”的可观察契约，防止后续重构改变业务结果。
def test_create_and_query_parse_task() -> None:
    client, sink = client_with_fakes()
    payload = {
        "evidence_id": "EVIDENCE_1",
        "case_id": "CASE_1",
        "bucket": "evidence-original",
        "object_key": "CASE_1/EVIDENCE_1/proof.png",
        "content_type": "image/png",
    }

    created = client.post(
        "/internal/evidence/parse-tasks",
        headers={"X-Service-Secret": "test-ocr-secret"},
        json=payload,
    )

    assert created.status_code == 202
    assert created.json()["request_id"].startswith("REQ_")
    assert created.json()["trace_id"].startswith("TRACE_")
    task_id = created.json()["data"]["task_id"]
    queried = client.get(
        f"/internal/evidence/parse-tasks/{task_id}",
        headers={"X-Service-Secret": "test-ocr-secret"},
    )
    assert queried.status_code == 200
    assert queried.json()["data"]["status"] == "SUCCEEDED"
    assert queried.json()["data"]["text"] == "签收证明：本人未签收"
    assert len(sink.results) == 1


# 所属模块：Python 支撑模块 > test_api；函数角色：回归测试用例。
# 具体功能：`test_invalid_payload_uses_unified_error` 读取并按案件、角色或会话范围筛选本阶段状态；关键协作调用：`client.post`、`startswith`、`response.json`。
# 上下游：上游为 相邻模块输入；下游为 本文件的 `client_with_fakes`。
# 系统意义：固定“Python 支撑模块 > test_api”的可观察契约，防止后续重构改变业务结果。
def test_invalid_payload_uses_unified_error() -> None:
    client, _ = client_with_fakes()

    response = client.post(
        "/internal/evidence/parse-tasks",
        headers={"X-Service-Secret": "test-ocr-secret"},
        json={"evidence_id": "invalid"},
    )

    assert response.status_code == 422
    assert response.json()["success"] is False
    assert response.json()["code"] == "INVALID_ARGUMENT"
    assert response.json()["request_id"].startswith("REQ_")
