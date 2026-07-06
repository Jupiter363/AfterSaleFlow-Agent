import httpx

from app.clients import CompositeResultSink
from app.models import ParseTaskCreate, ParsedDocument


class RecordingResponse:
    def __init__(self, url: str = "", fail: bool = False) -> None:
        self._url = url
        self._fail = fail

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

    def __init__(self, timeout: float) -> None:
        self.timeout = timeout

    def __enter__(self) -> "RecordingHttpClient":
        return self

    def __exit__(self, exc_type, exc, traceback) -> None:
        return None

    def post(self, url: str, **kwargs) -> RecordingResponse:
        self.posts.append((url, kwargs))
        return RecordingResponse()

    def put(self, url: str, **kwargs) -> RecordingResponse:
        self.puts.append((url, kwargs))
        return RecordingResponse()


class SearchFailureHttpClient(RecordingHttpClient):
    def put(self, url: str, **kwargs) -> RecordingResponse:
        self.puts.append((url, kwargs))
        return RecordingResponse(url, fail=True)


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
