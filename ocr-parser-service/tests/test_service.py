from app.models import ParseTaskCreate, ParsedDocument
from app.service import InMemoryTaskStore, ParseTaskService, SqliteTaskStore


class BrokenStorage:
    def download(self, bucket: str, object_key: str) -> bytes:
        raise RuntimeError("minio unavailable")


class UnusedParser:
    def parse(self, content: bytes, content_type: str, filename: str) -> ParsedDocument:
        raise AssertionError("parser must not run")


class RecordingSink:
    def __init__(self) -> None:
        self.failure: str | None = None

    def publish_success(
        self, request: ParseTaskCreate, document: ParsedDocument
    ) -> None:
        raise AssertionError("success must not be published")

    def publish_failure(self, request: ParseTaskCreate, error_code: str) -> None:
        self.failure = error_code


def test_failure_is_queryable_and_published() -> None:
    store = InMemoryTaskStore()
    sink = RecordingSink()
    service = ParseTaskService(store, BrokenStorage(), UnusedParser(), sink)
    request = ParseTaskCreate(
        evidence_id="EVIDENCE_failure",
        case_id="CASE_failure",
        bucket="evidence-original",
        object_key="case/failure.png",
        content_type="image/png",
    )

    task = service.create(request)
    service.execute(task.task_id)

    failed = service.get(task.task_id)
    assert failed.status == "FAILED"
    assert failed.error_code == "PARSE_FAILED"
    assert "minio unavailable" not in (failed.error_message or "")
    assert sink.failure == "PARSE_FAILED"


def test_sqlite_store_survives_reinitialization(tmp_path) -> None:
    database_path = tmp_path / "tasks.sqlite3"
    request = ParseTaskCreate(
        evidence_id="EVIDENCE_persistent",
        case_id="CASE_persistent",
        bucket="evidence-original",
        object_key="case/persistent.pdf",
        content_type="application/pdf",
    )
    service = ParseTaskService(
        SqliteTaskStore(str(database_path)),
        BrokenStorage(),
        UnusedParser(),
        RecordingSink(),
    )

    created = service.create(request)

    reloaded = SqliteTaskStore(str(database_path)).get(created.task_id)
    assert reloaded is not None
    assert reloaded.request == request
    assert reloaded.view.status == "PENDING"
