# 文件作用：自动化测试文件，验证 test_clients 相关模块的行为、契约或页面布局。

import httpx

from app.clients import CompositeResultSink
from app.models import ParseTaskCreate, ParsedDocument


class RecordingResponse:
    # 所属模块：Python 支撑模块 > test_clients；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖。
    # 上下游：上游为 相邻模块输入；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def __init__(self, url: str = "", fail: bool = False) -> None:
        self._url = url
        self._fail = fail

    # 所属模块：Python 支撑模块 > test_clients；函数角色：类/闭包内部方法。
    # 具体功能：`raise_for_status` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`httpx.HTTPStatusError`、`httpx.Request`、`httpx.Response`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `httpx.HTTPStatusError`、`httpx.Request`、`httpx.Response`。
    # 系统意义：失败显式映射为 `httpx.HTTPStatusError`，避免错误状态被当成成功结果。
    def raise_for_status(self) -> None:
        if self._fail:
            raise httpx.HTTPStatusError(
                "elasticsearch unavailable",
                request=httpx.Request("PUT", self._url),
                response=httpx.Response(503),
            )
        return None


class RecordingHttpClient:
    posts: list[tuple[str, dict]] = []
    puts: list[tuple[str, dict]] = []

    # 所属模块：Python 支撑模块 > test_clients；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖。
    # 上下游：上游为 相邻模块输入；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def __init__(self, timeout: float) -> None:
        self.timeout = timeout

    # 所属模块：Python 支撑模块 > test_clients；函数角色：类/闭包内部方法。
    # 具体功能：`__enter__` 围绕本阶段状态计算该函数独立负责的业务派生值。
    # 上下游：上游为 相邻模块输入；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def __enter__(self) -> "RecordingHttpClient":
        return self

    # 所属模块：Python 支撑模块 > test_clients；函数角色：类/闭包内部方法。
    # 具体功能：`__exit__` 围绕本阶段状态计算该函数独立负责的业务派生值。
    # 上下游：上游为 相邻模块输入；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def __exit__(self, exc_type, exc, traceback) -> None:
        return None

    # 所属模块：Python 支撑模块 > test_clients；函数角色：类/闭包内部方法。
    # 具体功能：`post` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`self.posts.append`、`RecordingResponse`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `self.posts.append`、`RecordingResponse`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def post(self, url: str, **kwargs) -> RecordingResponse:
        self.posts.append((url, kwargs))
        return RecordingResponse()

    # 所属模块：Python 支撑模块 > test_clients；函数角色：类/闭包内部方法。
    # 具体功能：`put` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`self.puts.append`、`RecordingResponse`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `self.puts.append`、`RecordingResponse`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def put(self, url: str, **kwargs) -> RecordingResponse:
        self.puts.append((url, kwargs))
        return RecordingResponse()


class SearchFailureHttpClient(RecordingHttpClient):
    # 所属模块：Python 支撑模块 > test_clients；函数角色：类/闭包内部方法。
    # 具体功能：`put` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`self.puts.append`、`RecordingResponse`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `self.puts.append`、`RecordingResponse`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def put(self, url: str, **kwargs) -> RecordingResponse:
        self.puts.append((url, kwargs))
        return RecordingResponse(url, fail=True)


# 所属模块：Python 支撑模块 > test_clients；函数角色：回归测试用例。
# 具体功能：`test_result_sink_prefers_task_callback_url` 验证阶段结果在固定案例中的输出、边界和失败行为；关键协作调用：`CompositeResultSink`、`ParseTaskCreate`、`sink.publish_success`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `CompositeResultSink`、`ParseTaskCreate`、`sink.publish_success`、`ParsedDocument`。
# 系统意义：固定“Python 支撑模块 > test_clients”的可观察契约，防止后续重构改变业务结果。
def test_result_sink_prefers_task_callback_url(monkeypatch) -> None:
    RecordingHttpClient.posts = []
    RecordingHttpClient.puts = []
    monkeypatch.setattr("app.clients.httpx.Client", RecordingHttpClient)
    sink = CompositeResultSink(
        "http://java-api-service:8080",
        "http://elasticsearch:9200",
        "ocr-secret",
    )
    request = ParseTaskCreate(
        evidence_id="EVIDENCE_callback",
        case_id="CASE_callback",
        bucket="evidence-original",
        object_key="CASE_callback/EVIDENCE_callback/proof.md",
        content_type="text/markdown",
        callback_url=(
            "http://host.docker.internal:8081/internal/evidence/"
            "EVIDENCE_callback/parse-result"
        ),
    )

    sink.publish_success(
        request, ParsedDocument(text="证据解析文本", metadata={"engine": "markdown-text"})
    )

    assert RecordingHttpClient.posts[0][0] == (
        "http://host.docker.internal:8081/internal/evidence/"
        "EVIDENCE_callback/parse-result"
    )
    assert RecordingHttpClient.posts[0][1]["json"]["status"] == "SUCCEEDED"
    assert RecordingHttpClient.puts[0][0] == (
        "http://elasticsearch:9200/evidence_index/_doc/EVIDENCE_callback"
    )


# 所属模块：Python 支撑模块 > test_clients；函数角色：回归测试用例。
# 具体功能：`test_result_sink_keeps_success_callback_when_search_indexing_fails` 验证阶段结果在固定案例中的输出、边界和失败行为；关键协作调用：`CompositeResultSink`、`ParseTaskCreate`、`sink.publish_success`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `CompositeResultSink`、`ParseTaskCreate`、`sink.publish_success`、`ParsedDocument`。
# 系统意义：固定“Python 支撑模块 > test_clients”的可观察契约，防止后续重构改变业务结果。
def test_result_sink_keeps_success_callback_when_search_indexing_fails(
    monkeypatch,
) -> None:
    SearchFailureHttpClient.posts = []
    SearchFailureHttpClient.puts = []
    monkeypatch.setattr("app.clients.httpx.Client", SearchFailureHttpClient)
    sink = CompositeResultSink(
        "http://java-api-service:8080",
        "http://elasticsearch:9200",
        "ocr-secret",
    )
    request = ParseTaskCreate(
        evidence_id="EVIDENCE_search_down",
        case_id="CASE_search_down",
        bucket="evidence-original",
        object_key="CASE_search_down/EVIDENCE_search_down/proof.png",
        content_type="image/png",
        callback_url=(
            "http://java-api-service:8080/internal/evidence/"
            "EVIDENCE_search_down/parse-result"
        ),
    )

    sink.publish_success(
        request,
        ParsedDocument(text="签收证明解析文本", metadata={"engine": "fake-ocr"}),
    )

    java_statuses = [
        kwargs["json"]["status"]
        for url, kwargs in SearchFailureHttpClient.posts
        if url.endswith("/internal/evidence/EVIDENCE_search_down/parse-result")
    ]
    assert java_statuses == ["SUCCEEDED"]
