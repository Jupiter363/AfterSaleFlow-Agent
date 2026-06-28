from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
import sqlite3
from threading import RLock
from typing import Protocol
from uuid import uuid4

from app.models import ParseTaskCreate, ParseTaskView, ParsedDocument, TaskRecord


class TaskStore(Protocol):
    def save(self, record: TaskRecord) -> None: ...

    def get(self, task_id: str) -> TaskRecord | None: ...


class ObjectStorage(Protocol):
    def download(self, bucket: str, object_key: str) -> bytes: ...


class Parser(Protocol):
    def parse(self, content: bytes, content_type: str, filename: str) -> ParsedDocument: ...


class ResultSink(Protocol):
    def publish_success(
        self, request: ParseTaskCreate, document: ParsedDocument
    ) -> None: ...

    def publish_failure(self, request: ParseTaskCreate, error_code: str) -> None: ...


class InMemoryTaskStore:
    def __init__(self) -> None:
        self._records: dict[str, TaskRecord] = {}
        self._lock = RLock()

    def save(self, record: TaskRecord) -> None:
        with self._lock:
            self._records[record.view.task_id] = record.model_copy(deep=True)

    def get(self, task_id: str) -> TaskRecord | None:
        with self._lock:
            record = self._records.get(task_id)
            return record.model_copy(deep=True) if record else None


class SqliteTaskStore:
    def __init__(self, database_path: str) -> None:
        path = Path(database_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        self._database_path = str(path)
        self._lock = RLock()
        with self._connect() as connection:
            connection.execute(
                """
                create table if not exists parse_task (
                    task_id text primary key,
                    payload text not null,
                    updated_at text not null
                )
                """
            )

    def save(self, record: TaskRecord) -> None:
        with self._lock, self._connect() as connection:
            connection.execute(
                """
                insert into parse_task(task_id, payload, updated_at)
                values (?, ?, ?)
                on conflict(task_id) do update set
                    payload = excluded.payload,
                    updated_at = excluded.updated_at
                """,
                (
                    record.view.task_id,
                    record.model_dump_json(),
                    record.view.updated_at.isoformat(),
                ),
            )

    def get(self, task_id: str) -> TaskRecord | None:
        with self._lock, self._connect() as connection:
            row = connection.execute(
                "select payload from parse_task where task_id = ?", (task_id,)
            ).fetchone()
        return TaskRecord.model_validate_json(row[0]) if row else None

    def _connect(self) -> sqlite3.Connection:
        return sqlite3.connect(self._database_path)


class ParseTaskService:
    def __init__(
        self,
        store: TaskStore,
        storage: ObjectStorage,
        parser: Parser,
        sink: ResultSink,
    ) -> None:
        self._store = store
        self._storage = storage
        self._parser = parser
        self._sink = sink

    def create(self, request: ParseTaskCreate) -> ParseTaskView:
        view = ParseTaskView(
            task_id=f"OCR_{uuid4().hex}",
            evidence_id=request.evidence_id,
            case_id=request.case_id,
            status="PENDING",
        )
        self._store.save(TaskRecord(view=view, request=request))
        return view

    def get(self, task_id: str) -> ParseTaskView:
        record = self._store.get(task_id)
        if record is None:
            raise KeyError(task_id)
        return record.view

    def execute(self, task_id: str) -> None:
        record = self._store.get(task_id)
        if record is None:
            return
        record.view.status = "PROCESSING"
        record.view.updated_at = datetime.now(timezone.utc)
        self._store.save(record)
        try:
            content = self._storage.download(
                record.request.bucket, record.request.object_key
            )
            filename = record.request.object_key.rsplit("/", 1)[-1]
            document = self._parser.parse(
                content, record.request.content_type, filename
            )
            self._sink.publish_success(record.request, document)
            record.view.status = "SUCCEEDED"
            record.view.text = document.text
            record.view.metadata = document.metadata
        except Exception:
            record.view.status = "FAILED"
            record.view.error_code = "PARSE_FAILED"
            record.view.error_message = "evidence parsing failed"
            try:
                self._sink.publish_failure(record.request, "PARSE_FAILED")
            except Exception:
                pass
        record.view.updated_at = datetime.now(timezone.utc)
        self._store.save(record)
