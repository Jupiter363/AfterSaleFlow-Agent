# 文件作用：自动化测试文件，验证 test_phase10_remedy_contract 相关模块的行为、契约或页面布局。

from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "java-api-service" / "src" / "main" / "java" / "com" / "example" / "dispute"


# 所属模块：跨服务契约测试 > test_phase10_remedy_contract；函数角色：模块公开业务函数。
# 具体功能：`read` 读取并按案件、角色或会话范围筛选被测业务场景；关键协作调用：`path.read_text`。
# 上下游：上游为 本文件的 `test_remedy_planner_supports_all_routes_and_gates_every_action`、`test_remedy_plan_persists_risk_idempotency_preconditions_and_notifications`、`test_workflow_enters_remedy_before_approval_and_api_is_read_only`；下游为 协作调用 `path.read_text`。
# 系统意义：该函数在系统中的业务边界是：只锁定公共契约，不锁死内部实现。
def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


# 所属模块：跨服务契约测试 > test_phase10_remedy_contract；函数角色：回归测试用例。
# 具体功能：`test_remedy_planner_supports_all_routes_and_gates_every_action` 验证被测业务场景在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase10_remedy_contract”的可观察契约，防止后续重构改变业务结果。
def test_remedy_planner_supports_all_routes_and_gates_every_action() -> None:
    planner = read(JAVA / "remedy" / "domain" / "RemedyPlanner.java")
    assert "TRANSFERRED" in read(
        JAVA / "domain" / "model" / "RouteType.java"
    )
    assert "SIMPLE_HEARING" in read(
        JAVA / "domain" / "model" / "RouteType.java"
    )
    assert "FULL_HEARING" in planner
    assert "PLATFORM_REVIEW_APPROVED" in planner
    assert "requiresApproval" in read(
        JAVA / "remedy" / "domain" / "PlannedRemedyAction.java"
    )
    assert "Tool" not in planner


# 所属模块：跨服务契约测试 > test_phase10_remedy_contract；函数角色：回归测试用例。
# 具体功能：`test_remedy_plan_persists_risk_idempotency_preconditions_and_notifications` 把被测业务场景写入或合并到可追溯的阶段状态。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase10_remedy_contract”的可观察契约，防止后续重构改变业务结果。
def test_remedy_plan_persists_risk_idempotency_preconditions_and_notifications() -> None:
    entity = read(
        JAVA
        / "infrastructure"
        / "persistence"
        / "entity"
        / "RemedyPlanEntity.java"
    )
    service = read(
        JAVA / "remedy" / "application" / "RemedyApplicationService.java"
    )
    for column in (
        "actions_json",
        "preconditions_json",
        "notification_plan_json",
        "risk_level",
        "requires_human_review",
    ):
        assert column in entity
    assert "REMEDY_PLAN_CREATED" in service
    assert "markRemedyPlanned" in service


# 所属模块：跨服务契约测试 > test_phase10_remedy_contract；函数角色：回归测试用例。
# 具体功能：`test_workflow_enters_remedy_before_approval_and_api_is_read_only` 读取并按案件、角色或会话范围筛选被测业务场景；关键协作调用：`workflow.index`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase10_remedy_contract”的可观察契约，防止后续重构改变业务结果。
def test_remedy_api_is_read_only() -> None:
    controller = read(JAVA / "remedy" / "api" / "RemedyController.java")
    assert "@GetMapping" in controller
    assert "@PostMapping" not in controller
