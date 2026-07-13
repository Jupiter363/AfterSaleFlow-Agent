# 文件作用：自动化测试文件，验证 test_phase7_router_contract 相关模块的行为、契约或页面布局。

from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "java-api-service" / "src" / "main"


# 所属模块：跨服务契约测试 > test_phase7_router_contract；函数角色：回归测试用例。
# 具体功能：`test_phase7_modules_and_route_api_exist` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`read_text`、`is_dir`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `read_text`、`is_dir`。
# 系统意义：固定“跨服务契约测试 > test_phase7_router_contract”的可观察契约，防止后续重构改变业务结果。
def test_phase7_modules_and_route_api_exist() -> None:
    java_root = JAVA / "java" / "com" / "example" / "dispute"
    for module in ("router", "policy"):
        assert (java_root / module).is_dir()

    controller = (
        java_root / "router" / "api" / "RouterController.java"
    ).read_text(encoding="utf-8")
    assert '/api/disputes/{caseId}/route' in controller
    assert 'RequestHeader("Idempotency-Key")' in controller


# 所属模块：跨服务契约测试 > test_phase7_router_contract；函数角色：回归测试用例。
# 具体功能：`test_route_enum_matches_the_three_formal_paths_exactly` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`read_text`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `read_text`。
# 系统意义：固定“跨服务契约测试 > test_phase7_router_contract”的可观察契约，防止后续重构改变业务结果。
def test_route_enum_matches_the_three_formal_paths_exactly() -> None:
    route_enum = (
        JAVA
        / "java"
        / "com"
        / "example"
        / "dispute"
        / "domain"
        / "model"
        / "RouteType.java"
    ).read_text(encoding="utf-8")
    for route in (
        "TRANSFERRED",
        "SIMPLE_HEARING",
        "FULL_HEARING",
    ):
        assert route in route_enum
    assert "HUMAN_REVIEW" not in route_enum


# 所属模块：跨服务契约测试 > test_phase7_router_contract；函数角色：回归测试用例。
# 具体功能：`test_route_and_conclusion_are_persistent_and_forced_downstream` 把Agent 流事件写入或合并到可追溯的阶段状态；关键协作调用：`read_text`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `read_text`。
# 系统意义：固定“跨服务契约测试 > test_phase7_router_contract”的可观察契约，防止后续重构改变业务结果。
def test_route_and_conclusion_are_persistent_and_forced_downstream() -> None:
    migration = (
        JAVA / "resources" / "db" / "migration" / "V006__init_router_flow_tables.sql"
    ).read_text(encoding="utf-8")
    assert "create table route_decision" in migration
    assert "create table flow_conclusion" in migration
    assert "requires_remedy_planning boolean not null default true" in migration
    assert "requires_human_review boolean not null default true" in migration
    assert "check (requires_remedy_planning and requires_human_review)" in migration
    assert "uq_route_decision_case unique (case_id)" in migration


# 所属模块：跨服务契约测试 > test_phase7_router_contract；函数角色：回归测试用例。
# 具体功能：`test_router_does_not_close_cases_or_execute_actions` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`read_text`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `read_text`。
# 系统意义：固定“跨服务契约测试 > test_phase7_router_contract”的可观察契约，防止后续重构改变业务结果。
def test_router_does_not_close_cases_or_execute_actions() -> None:
    service = (
        JAVA
        / "java"
        / "com"
        / "example"
        / "dispute"
        / "router"
        / "application"
        / "RouterApplicationService.java"
    ).read_text(encoding="utf-8")
    assert "CaseStatus.CLOSED" not in service
    assert "ActionRecord" not in service
    assert "ToolExecutor" not in service
