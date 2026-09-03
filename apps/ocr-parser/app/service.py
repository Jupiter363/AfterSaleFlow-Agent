# 文件作用：OCR 解析服务代码文件，负责证据文件解析、外部调用、数据模型或接口处理。

from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
import sqlite3
from threading import RLock
from typing import Protocol
from uuid import uuid4

from app.models import ParseTaskCreate, ParseTaskView, ParsedDocument, TaskRecord


class TaskStore(Protocol):
    # 所属模块：Python 支撑模块 > service；函数角色：类/闭包内部方法。
    # 具体功能：`save` 把本阶段状态写入或合并到可追溯的阶段状态。
    # 上下游：上游为 本文件的 `ParseTaskService.create`、`ParseTaskService.execute`；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def save(self, record: TaskRecord) -> None: ...

    # 所属模块：Python 支撑模块 > service；函数角色：类/闭包内部方法。
    # 具体功能：`get` 围绕本阶段状态计算该函数独立负责的业务派生值。
    # 上下游：上游为 本文件的 `ParseTaskService.execute`；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def get(self, task_id: str) -> TaskRecord | None: ...


class ObjectStorage(Protocol):
    # 所属模块：Python 支撑模块 > service；函数角色：类/闭包内部方法。
    # 具体功能：`download` 读取并按案件、角色或会话范围筛选本阶段状态。
    # 上下游：上游为 本文件的 `ParseTaskService.execute`；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def download(self, bucket: str, object_key: str) -> bytes: ...


class Parser(Protocol):
    # 所属模块：Python 支撑模块 > service；函数角色：类/闭包内部方法。
    # 具体功能：`parse` 围绕本阶段状态计算该函数独立负责的业务派生值。
    # 上下游：上游为 本文件的 `ParseTaskService.execute`；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def parse(self, content: bytes, content_type: str, filename: str) -> ParsedDocument: ...


class ResultSink(Protocol):
    # 所属模块：Python 支撑模块 > service；函数角色：类/闭包内部方法。
    # 具体功能：`publish_success` 围绕本阶段状态计算该函数独立负责的业务派生值。
    # 上下游：上游为 本文件的 `ParseTaskService.execute`；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def publish_success(
        self, request: ParseTaskCreate, document: ParsedDocument
    ) -> None: ...

    # 所属模块：Python 支撑模块 > service；函数角色：类/闭包内部方法。
    # 具体功能：`publish_failure` 围绕本阶段状态计算该函数独立负责的业务派生值。
    # 上下游：上游为 相邻模块输入；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def publish_failure(self, request: ParseTaskCreate, error_code: str) -> None: ...


class InMemoryTaskStore:
    # 所属模块：Python 支撑模块 > service；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖；关键协作调用：`RLock`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `RLock`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def __init__(self) -> None:
        self._records: dict[str, TaskRecord] = {}
        self._lock = RLock()

    # 所属模块：Python 支撑模块 > service；函数角色：类/闭包内部方法。
    # 具体功能：`save` 把本阶段状态写入或合并到可追溯的阶段状态；关键协作调用：`record.model_copy`。
    # 上下游：上游为 本文件的 `ParseTaskService.create`、`ParseTaskService.execute`；下游为 协作调用 `record.model_copy`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def save(self, record: TaskRecord) -> None:
        with self._lock:
            self._records[record.view.task_id] = record.model_copy(deep=True)

    # 所属模块：Python 支撑模块 > service；函数角色：类/闭包内部方法。
    # 具体功能：`get` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`self._records.get`、`record.model_copy`。
    # 上下游：上游为 本文件的 `ParseTaskService.execute`；下游为 协作调用 `self._records.get`、`record.model_copy`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def get(self, task_id: str) -> TaskRecord | None:
        with self._lock:
            record = self._records.get(task_id)
            return record.model_copy(deep=True) if record else None


class SqliteTaskStore:
    # 所属模块：Python 支撑模块 > service；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖；关键协作调用：`Path`、`path.parent.mkdir`、`RLock`。
    # 上下游：上游为 相邻模块输入；下游为 本文件的 `_connect`、`execute`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
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

    # 所属模块：Python 支撑模块 > service；函数角色：类/闭包内部方法。
    # 具体功能：`save` 把本阶段状态写入或合并到可追溯的阶段状态；关键协作调用：`record.model_dump_json`、`record.view.updated_at.isoformat`。
    # 上下游：上游为 本文件的 `ParseTaskService.create`、`ParseTaskService.execute`；下游为 本文件的 `_connect`、`execute`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
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

    # 所属模块：Python 支撑模块 > service；函数角色：类/闭包内部方法。
    # 具体功能：`get` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`fetchone`、`TaskRecord.model_validate_json`。
    # 上下游：上游为 本文件的 `ParseTaskService.execute`；下游为 本文件的 `_connect`、`execute`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def get(self, task_id: str) -> TaskRecord | None:
        with self._lock, self._connect() as connection:
            row = connection.execute(
                "select payload from parse_task where task_id = ?", (task_id,)
            ).fetchone()
        return TaskRecord.model_validate_json(row[0]) if row else None

    # 所属模块：Python 支撑模块 > service；函数角色：类/闭包内部方法。
    # 具体功能：`_connect` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`sqlite3.connect`。
    # 上下游：上游为 本文件的 `SqliteTaskStore.__init__`、`SqliteTaskStore.save`、`SqliteTaskStore.get`；下游为 协作调用 `sqlite3.connect`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def _connect(self) -> sqlite3.Connection:
        return sqlite3.connect(self._database_path)


class ParseTaskService:
    # 所属模块：Python 支撑模块 > service；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖。
    # 上下游：上游为 相邻模块输入；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
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

    # 所属模块：Python 支撑模块 > service；函数角色：类/闭包内部方法。
    # 具体功能：`create` 把上游材料组装为本阶段可消费的本阶段状态；关键协作调用：`ParseTaskView`、`TaskRecord`、`uuid4`。
    # 上下游：上游为 相邻模块输入；下游为 本文件的 `save`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def create(self, request: ParseTaskCreate) -> ParseTaskView:
        view = ParseTaskView(
            task_id=f"OCR_{uuid4().hex}",
            evidence_id=request.evidence_id,
            case_id=request.case_id,
            status="PENDING",
        )
        self._store.save(TaskRecord(view=view, request=request))
        return view

    # 所属模块：Python 支撑模块 > service；函数角色：类/闭包内部方法。
    # 具体功能：`get` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`self._store.get`、`KeyError`。
    # 上下游：上游为 本文件的 `ParseTaskService.execute`；下游为 协作调用 `self._store.get`、`KeyError`。
    # 系统意义：失败显式映射为 `KeyError`，避免错误状态被当成成功结果。
    def get(self, task_id: str) -> ParseTaskView:
        record = self._store.get(task_id)
        if record is None:
            raise KeyError(task_id)
        return record.view

    # 所属模块：Python 支撑模块 > service；函数角色：类/闭包内部方法。
    # 具体功能：`execute` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`datetime.now`、`record.request.object_key.rsplit`。
    # 上下游：上游为 本文件的 `SqliteTaskStore.__init__`、`SqliteTaskStore.save`、`SqliteTaskStore.get`；下游为 本文件的 `get`、`save`、`download`、`parse`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
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
