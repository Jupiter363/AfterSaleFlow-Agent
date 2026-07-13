# 文件作用：自动化测试文件，验证 test_final_temporal_workflows 相关模块的行为、契约或页面布局。

from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
JAVA = (
    ROOT
    / "java-api-service"
    / "src"
    / "main"
    / "java"
    / "com"
    / "example"
    / "dispute"
)
TEMPORAL = JAVA / "workflow" / "temporal"


# 所属模块：跨服务契约测试 > test_final_temporal_workflows；函数角色：模块公开业务函数。
# 具体功能：`read` 读取并按案件、角色或会话范围筛选被测业务场景；关键协作调用：`path.read_text`。
# 上下游：上游为 本文件的 `test_all_five_final_workflow_types_and_implementations_exist`、`test_new_disputes_start_final_type_and_worker_keeps_legacy_for_replay`、`test_workflow_code_only_owns_deterministic_control`；下游为 协作调用 `path.read_text`。
# 系统意义：该函数在系统中的业务边界是：只锁定公共契约，不锁死内部实现。
def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


# 所属模块：跨服务契约测试 > test_final_temporal_workflows；函数角色：回归测试用例。
# 具体功能：`test_all_five_final_workflow_types_and_implementations_exist` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`is_file`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_final_temporal_workflows”的可观察契约，防止后续重构改变业务结果。
def test_all_five_final_workflow_types_and_implementations_exist() -> None:
    names = (
        "FulfillmentDisputeWorkflow",
        "DisputeHearingWorkflow",
        "DeliberationPanelWorkflow",
        "HumanReviewWorkflow",
        "ExecutionWorkflow",
    )
    for name in names:
        assert (TEMPORAL / f"{name}.java").is_file()
        assert (TEMPORAL / f"{name}Impl.java").is_file()
        assert "@WorkflowInterface" in read(TEMPORAL / f"{name}.java")


# 所属模块：跨服务契约测试 > test_final_temporal_workflows；函数角色：回归测试用例。
# 具体功能：`test_new_disputes_start_final_type_and_worker_keeps_legacy_for_replay` 验证被测业务场景在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_final_temporal_workflows”的可观察契约，防止后续重构改变业务结果。
def test_new_disputes_start_final_type_and_worker_keeps_legacy_for_replay() -> None:
    application = read(
        JAVA / "workflow" / "application" / "WorkflowApplicationService.java"
    )
    worker = read(
        JAVA / "workflow" / "config" / "TemporalWorkerConfiguration.java"
    )
    assert "FulfillmentDisputeWorkflow.class" in application
    assert "WorkflowExecutionAlreadyStarted" in application
    assert "new FulfillmentDisputeCommand(" in application
    for implementation in (
        "FulfillmentDisputeWorkflowImpl.class",
        "DisputeHearingWorkflowImpl.class",
        "DeliberationPanelWorkflowImpl.class",
        "HumanReviewWorkflowImpl.class",
        "ExecutionWorkflowImpl.class",
    ):
        assert implementation in worker
    assert "CaseFulfillmentDisputeWorkflowImpl.class" in worker


# 所属模块：跨服务契约测试 > test_final_temporal_workflows；函数角色：回归测试用例。
# 具体功能：`test_workflow_code_only_owns_deterministic_control` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`join`、`TEMPORAL.glob`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_final_temporal_workflows”的可观察契约，防止后续重构改变业务结果。
def test_workflow_code_only_owns_deterministic_control() -> None:
    implementations = "\n".join(
        read(path) for path in TEMPORAL.glob("*WorkflowImpl.java")
    )
    for forbidden in (
        "RestClient",
        "HttpClient",
        "EntityManager",
        "Repository",
        "JdbcTemplate",
        "LiteLLM",
    ):
        assert forbidden not in implementations
    assert "Workflow.newActivityStub" in implementations
    assert "Workflow.newChildWorkflowStub" in implementations
