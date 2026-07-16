# 文件作用：自动化测试文件，验证 test_phase13_closure_evaluation_contract 相关模块的行为、契约或页面布局。

from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "java-api-service" / "src" / "main" / "java" / "com" / "example" / "dispute"
PYTHON = ROOT / "python-agent-service" / "app"


# 所属模块：跨服务契约测试 > test_phase13_closure_evaluation_contract；函数角色：模块公开业务函数。
# 具体功能：`read` 读取并按案件、角色或会话范围筛选被测业务场景；关键协作调用：`path.read_text`。
# 上下游：上游为 本文件的 `test_closure_requires_approved_successful_execution_and_is_audited`、`test_evaluation_trace_persists_report_metrics_failure_and_provenance`、`test_agent_evaluation_runs_outside_database_transactions`、`test_evaluation_agent_is_closed_case_only_and_read_only`；下游为 协作调用 `path.read_text`。
# 系统意义：该函数在系统中的业务边界是：只锁定公共契约，不锁死内部实现。
def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


# 所属模块：跨服务契约测试 > test_phase13_closure_evaluation_contract；函数角色：回归测试用例。
# 具体功能：`test_closure_requires_approved_successful_execution_and_is_audited` 验证履约执行动作在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase13_closure_evaluation_contract”的可观察契约，防止后续重构改变业务结果。
def test_closure_requires_approved_successful_execution_and_is_audited() -> None:
    service = read(JAVA / "evaluation" / "application" / "CaseClosureService.java")
    entity = read(
        JAVA
        / "infrastructure"
        / "persistence"
        / "entity"
        / "FulfillmentCaseEntity.java"
    )
    for contract in (
        "CaseStatus.EXECUTING",
        "ExecutionStatus.SUCCEEDED",
        "latestApproval",
        "validateCompletedExecution",
        "CASE_CLOSED",
        "EVALUATION_STARTED",
    ):
        assert contract in service
    assert "caseStatus = CaseStatus.CLOSED" in entity
    assert "closedAt = OffsetDateTime.now" in entity


# 所属模块：跨服务契约测试 > test_phase13_closure_evaluation_contract；函数角色：回归测试用例。
# 具体功能：`test_evaluation_trace_persists_report_metrics_failure_and_provenance` 把被测业务场景写入或合并到可追溯的阶段状态。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase13_closure_evaluation_contract”的可观察契约，防止后续重构改变业务结果。
def test_evaluation_trace_persists_report_metrics_failure_and_provenance() -> None:
    entity = read(
        JAVA
        / "infrastructure"
        / "persistence"
        / "entity"
        / "EvaluationTraceEntity.java"
    )
    service = read(JAVA / "evaluation" / "application" / "CaseClosureService.java")
    for field in (
        "inputSnapshotJson",
        "metricScoresJson",
        "findingsJson",
        "reportJson",
        "evaluatorModel",
        "promptVersion",
        "latencyMs",
        "tokenUsage",
    ):
        assert field in entity
    assert "EVALUATION_COMPLETED" in service
    assert "EVALUATION_FAILED" in service
    assert "online_case_mutated" in service


# 所属模块：跨服务契约测试 > test_phase13_closure_evaluation_contract；函数角色：回归测试用例。
# 具体功能：`test_agent_evaluation_runs_outside_database_transactions` 验证被测业务场景在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase13_closure_evaluation_contract”的可观察契约，防止后续重构改变业务结果。
def test_agent_evaluation_runs_outside_database_transactions() -> None:
    service = read(JAVA / "evaluation" / "application" / "CaseClosureService.java")
    assert "@Transactional(propagation = Propagation.NOT_SUPPORTED)" in service
    assert "evaluationAgent.analyze(" in service
    assert "transactions.executeWithoutResult(" in service


# 所属模块：跨服务契约测试 > test_phase13_closure_evaluation_contract；函数角色：回归测试用例。
# 具体功能：`test_evaluation_agent_is_closed_case_only_and_read_only` 读取并按案件、角色或会话范围筛选被测业务场景。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase13_closure_evaluation_contract”的可观察契约，防止后续重构改变业务结果。
def test_evaluation_agent_is_closed_case_only_and_read_only() -> None:
    schemas = read(PYTHON / "schemas" / "models.py")
    workflow = read(PYTHON / "evaluation.py")
    prompt = read(
        PYTHON
        / "agents"
        / "prompts"
        / "evaluation_agent"
        / "evaluation_analyze.md"
    )
    main = read(PYTHON / "main.py")
    assert 'case_status: Literal["CLOSED"]' in schemas
    assert "automatic_changes_applied: Literal[False]" in schemas
    assert "online_case_mutated: Literal[False]" in schemas
    assert "draft_approval_rate=1.0" in workflow
    assert "reviewer_modification_rate=" in workflow
    assert "绝不能参与在线案件" in prompt
    assert "不得更改在线案件" in prompt
    assert '"/internal/agents/evaluation/analyze"' in main


# 所属模块：跨服务契约测试 > test_phase13_closure_evaluation_contract；函数角色：回归测试用例。
# 具体功能：`test_closure_and_evaluation_query_apis_are_exposed` 读取并按案件、角色或会话范围筛选被测业务场景。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase13_closure_evaluation_contract”的可观察契约，防止后续重构改变业务结果。
def test_closure_and_evaluation_query_apis_are_exposed() -> None:
    controller = read(JAVA / "evaluation" / "api" / "ClosureController.java")
    assert '"/disputes/{caseId}/close"' in controller
    assert '"/disputes/{caseId}/evaluation"' in controller
    assert '"/reviews/evaluations/metrics"' in controller
    assert '"Idempotency-Key"' in controller
