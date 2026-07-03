from fastapi.testclient import TestClient

from app.main import create_app
from app.models import ParseTaskCreate, ParsedDocument
from app.service import InMemoryTaskStore, ParseTaskService


class FakeStorage:
    def download(self, bucket: str, object_key: str) -> bytes:
        assert bucket == "evidence-original"
        assert object_key == "CASE_1/EVIDENCE_1/proof.png"
        return b"\x89PNG\r\n\x1a\n"


class FakeParser:
    def parse(self, content: bytes, content_type: str, filename: str) -> ParsedDocument:
        assert content_type == "image/png"
        return ParsedDocument(text="签收证明：本人未签收", metadata={"engine": "fake-ocr"})


class FakeSink:
    def __init__(self) -> None:
        self.results: list[tuple[ParseTaskCreate, ParsedDocument]] = []

    def publish_success(
        self, request: ParseTaskCreate, document: ParsedDocument
    ) -> None:
        self.results.append((request, document))

    def publish_failure(self, request: ParseTaskCreate, error_code: str) -> None:
        raise AssertionError(f"unexpected parse failure: {error_code}")


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


def test_health_is_public() -> None:
    client, _ = client_with_fakes()

    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP", "service": "ocr-parser-service"}


def test_parse_task_requires_service_secret() -> None:
    client, _ = client_with_fakes()

    response = client.post("/internal/evidence/parse-tasks", json={})

    assert response.status_code == 401
    assert response.json()["code"] == "UNAUTHORIZED"


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
