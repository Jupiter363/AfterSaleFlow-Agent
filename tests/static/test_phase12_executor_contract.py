# 文件作用：自动化测试文件，验证 test_phase12_executor_contract 相关模块的行为、契约或页面布局。

from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "java-api-service" / "src" / "main" / "java" / "com" / "example" / "dispute"


# 所属模块：跨服务契约测试 > test_phase12_executor_contract；函数角色：模块公开业务函数。
# 具体功能：`read` 读取并按案件、角色或会话范围筛选被测业务场景；关键协作调用：`path.read_text`。
# 上下游：上游为 本文件的 `test_executor_requires_approval_case_state_and_approved_snapshot_membership`、`test_executor_records_idempotent_success_failure_and_retry_boundaries`、`test_tool_call_is_outside_database_transaction_and_workflow_driven`、`test_execution_api_and_simulated_tool_cover_required_actions`；下游为 协作调用 `path.read_text`。
# 系统意义：该函数在系统中的业务边界是：只锁定公共契约，不锁死内部实现。
def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


# 所属模块：跨服务契约测试 > test_phase12_executor_contract；函数角色：回归测试用例。
# 具体功能：`test_executor_requires_approval_case_state_and_approved_snapshot_membership` 验证冻结快照在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase12_executor_contract”的可观察契约，防止后续重构改变业务结果。
def test_executor_requires_approval_case_state_and_approved_snapshot_membership() -> None:
    service = read(JAVA / "executor" / "application" / "ToolExecutorService.java")
    for contract in (
        "EXECUTABLE_DECISIONS",
        "APPROVED_FOR_EXECUTION",
        "approval.getApprovedPlanJson()",
        "action is not contained in the approved remedy plan",
        "notification is not contained in the approved remedy plan",
        "assertCanExecute",
    ):
        assert contract in service
    assert "plan.getActionsJson()" in service
    assert "plan.getNotificationPlanJson()" in service


# 所属模块：跨服务契约测试 > test_phase12_executor_contract；函数角色：回归测试用例。
# 具体功能：`test_executor_records_idempotent_success_failure_and_retry_boundaries` 验证被测业务场景在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase12_executor_contract”的可观察契约，防止后续重构改变业务结果。
def test_executor_records_idempotent_success_failure_and_retry_boundaries() -> None:
    service = read(JAVA / "executor" / "application" / "ToolExecutorService.java")
    entity = read(
        JAVA
        / "infrastructure"
        / "persistence"
        / "entity"
        / "ActionRecordEntity.java"
    )
    repository = read(
        JAVA
        / "infrastructure"
        / "persistence"
        / "repository"
        / "ActionRecordRepository.java"
    )
    for operation in ("running(", "retry(", "succeed(", "fail("):
        assert operation in entity
    assert "findByIdempotencyKeyForUpdate" in repository
    assert "TOOL_EXECUTION_SUCCEEDED" in service
    assert "TOOL_EXECUTION_FAILED" in service
    assert "ExecutionStatus.SUCCEEDED" in service
    redis_lock = read(
        JAVA / "executor" / "application" / "RedisActionExecutionLock.java"
    )
    assert "dispute:lock:" in redis_lock
    assert "setIfAbsent" in redis_lock
    assert "redis.call('get', KEYS[1]) == ARGV[1]" in redis_lock


# 所属模块：跨服务契约测试 > test_phase12_executor_contract；函数角色：回归测试用例。
# 具体功能：`test_tool_call_is_outside_database_transaction_and_workflow_driven` 验证工具意图在固定案例中的输出、边界和失败行为；关键协作调用：`split`、`service.split`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase12_executor_contract”的可观察契约，防止后续重构改变业务结果。
def test_tool_call_is_outside_database_transaction_and_workflow_driven() -> None:
    service = read(JAVA / "executor" / "application" / "ToolExecutorService.java")
    workflow = read(
        JAVA
        / "workflow"
        / "temporal"
        / "CaseFulfillmentDisputeWorkflowImpl.java"
    )
    activities = read(
        JAVA
        / "workflow"
        / "application"
        / "CaseFulfillmentDisputeActivitiesImpl.java"
    )
    assert "@Transactional" not in service.split(
        "public ExecutionBatchView executeApprovedActions", 1
    )[0].split("public class ToolExecutorService", 1)[1]
    assert "ToolExecutionResult result = tool.execute(action)" in service
    assert "transactions.executeWithoutResult" in service
    assert "activities.executeApprovedPlan(input.caseId())" in workflow
    assert "activities.closeCaseAndEvaluate(input.caseId())" in workflow
    assert "EVALUATION_COMPLETE" in workflow
    assert '"WORKFLOW_EXECUTE:" + caseId' in activities


# 所属模块：跨服务契约测试 > test_phase12_executor_contract；函数角色：回归测试用例。
# 具体功能：`test_execution_api_and_simulated_tool_cover_required_actions` 验证履约执行动作在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase12_executor_contract”的可观察契约，防止后续重构改变业务结果。
def test_execution_api_and_simulated_tool_cover_required_actions() -> None:
    controller = read(JAVA / "executor" / "api" / "ExecutionController.java")
    tool = read(JAVA / "tool" / "application" / "SimulatedExecutionTool.java")
    assert '"/execution/execute"' in controller
    assert '"/actions"' in controller
    assert '"Idempotency-Key"' in controller
    for action in (
        "REFUND",
        "RESHIP",
        "CLOSE_AFTER_SALE",
        "REJECT_AFTER_SALE",
        "NOTIFY_USER_AFTER_EXECUTION",
        "NOTIFY_MERCHANT_AFTER_EXECUTION",
        "AUDIT_EXECUTION_RESULT",
    ):
        assert action in tool
