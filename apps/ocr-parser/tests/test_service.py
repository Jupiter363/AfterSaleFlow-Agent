# 文件作用：自动化测试文件，验证 test_service 相关模块的行为、契约或页面布局。

from app.models import ParseTaskCreate, ParsedDocument
from app.service import InMemoryTaskStore, ParseTaskService, SqliteTaskStore


class BrokenStorage:
    # 所属模块：Python 支撑模块 > test_service；函数角色：类/闭包内部方法。
    # 具体功能：`download` 读取并按案件、角色或会话范围筛选本阶段状态；关键协作调用：`RuntimeError`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `RuntimeError`。
    # 系统意义：失败显式映射为 `RuntimeError`，避免错误状态被当成成功结果。
    def download(self, bucket: str, object_key: str) -> bytes:
        raise RuntimeError("minio unavailable")


class UnusedParser:
    # 所属模块：Python 支撑模块 > test_service；函数角色：类/闭包内部方法。
    # 具体功能：`parse` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`AssertionError`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `AssertionError`。
    # 系统意义：失败显式映射为 `AssertionError`，避免错误状态被当成成功结果。
    def parse(self, content: bytes, content_type: str, filename: str) -> ParsedDocument:
        raise AssertionError("parser must not run")


class RecordingSink:
    # 所属模块：Python 支撑模块 > test_service；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖。
    # 上下游：上游为 相邻模块输入；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def __init__(self) -> None:
        self.failure: str | None = None

    # 所属模块：Python 支撑模块 > test_service；函数角色：类/闭包内部方法。
    # 具体功能：`publish_success` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`AssertionError`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `AssertionError`。
    # 系统意义：失败显式映射为 `AssertionError`，避免错误状态被当成成功结果。
    def publish_success(
        self, request: ParseTaskCreate, document: ParsedDocument
    ) -> None:
        raise AssertionError("success must not be published")

    # 所属模块：Python 支撑模块 > test_service；函数角色：类/闭包内部方法。
    # 具体功能：`publish_failure` 围绕本阶段状态计算该函数独立负责的业务派生值。
    # 上下游：上游为 相邻模块输入；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def publish_failure(self, request: ParseTaskCreate, error_code: str) -> None:
        self.failure = error_code


# 所属模块：Python 支撑模块 > test_service；函数角色：回归测试用例。
# 具体功能：`test_failure_is_queryable_and_published` 读取并按案件、角色或会话范围筛选本阶段状态；关键协作调用：`InMemoryTaskStore`、`RecordingSink`、`ParseTaskService`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `InMemoryTaskStore`、`RecordingSink`、`ParseTaskService`、`ParseTaskCreate`。
# 系统意义：固定“Python 支撑模块 > test_service”的可观察契约，防止后续重构改变业务结果。
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


# 所属模块：Python 支撑模块 > test_service；函数角色：回归测试用例。
# 具体功能：`test_sqlite_store_survives_reinitialization` 把本阶段状态写入或合并到可追溯的阶段状态；关键协作调用：`ParseTaskCreate`、`ParseTaskService`、`service.create`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `ParseTaskCreate`、`ParseTaskService`、`service.create`、`get`。
# 系统意义：固定“Python 支撑模块 > test_service”的可观察契约，防止后续重构改变业务结果。
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
