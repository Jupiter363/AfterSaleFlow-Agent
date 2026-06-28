from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "java-api-service" / "src" / "main" / "java" / "com" / "example" / "dispute"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


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
    assert "CASE_CLOSURE" in workflow
    assert '"WORKFLOW_EXECUTE:" + caseId' in activities


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
