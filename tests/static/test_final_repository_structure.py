# 文件作用：自动化测试文件，验证 test_final_repository_structure 相关模块的行为、契约或页面布局。

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
PYTHON = ROOT / "python-agent-service" / "app"
FRONTEND = ROOT / "frontend" / "src"


# 所属模块：跨服务契约测试 > test_final_repository_structure；函数角色：回归测试用例。
# 具体功能：`test_final_java_bounded_contexts_are_explicit` 验证案件与会话上下文在固定案例中的输出、边界和失败行为；关键协作调用：`is_dir`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `is_dir`。
# 系统意义：固定“跨服务契约测试 > test_final_repository_structure”的可观察契约，防止后续重构改变业务结果。
def test_final_java_bounded_contexts_are_explicit() -> None:
    expected = {
        "casecore",
        "intake",
        "evidence",
        "routing",
        "hearing",
        "deliberation",
        "remedy",
        "review",
        "execution",
        "evaluation",
        "workflow",
        "platform",
    }

    missing = sorted(name for name in expected if not (JAVA / name).is_dir())

    assert missing == []


# 所属模块：跨服务契约测试 > test_final_repository_structure；函数角色：回归测试用例。
# 具体功能：`test_agent_service_has_governed_runtime_structure` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`is_dir`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `is_dir`。
# 系统意义：固定“跨服务契约测试 > test_final_repository_structure”的可观察契约，防止后续重构改变业务结果。
def test_agent_service_has_governed_runtime_structure() -> None:
    expected = {
        "api",
        "agents",
        "agents/prompts",
        "harness",
        "harness/prompts",
        "schemas",
        "skills",
    }

    missing = sorted(name for name in expected if not (PYTHON / name).is_dir())

    assert missing == []


# 所属模块：跨服务契约测试 > test_final_repository_structure；函数角色：回归测试用例。
# 具体功能：`test_frontend_is_partitioned_by_final_workspace_responsibility` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`is_dir`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `is_dir`。
# 系统意义：固定“跨服务契约测试 > test_final_repository_structure”的可观察契约，防止后续重构改变业务结果。
def test_frontend_is_partitioned_by_final_workspace_responsibility() -> None:
    expected = {
        "api",
        "components/agent",
        "components/evidence",
        "components/hearing",
        "components/review",
        "components/shared",
        "router",
        "schemas",
        "stores",
        "views/disputes",
        "views/reviews",
    }

    missing = sorted(name for name in expected if not (FRONTEND / name).is_dir())

    assert missing == []


# 所属模块：跨服务契约测试 > test_final_repository_structure；函数角色：回归测试用例。
# 具体功能：`test_repository_documentation_names_the_final_product_and_boundaries` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`read_text`、`readme.startswith`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `read_text`、`readme.startswith`。
# 系统意义：固定“跨服务契约测试 > test_final_repository_structure”的可观察契约，防止后续重构改变业务结果。
def test_repository_documentation_names_the_final_product_and_boundaries() -> None:
    readme = (ROOT / "README.md").read_text(encoding="utf-8")
    module_map = (
        ROOT / "docs" / "architecture" / "final-module-map.md"
    ).read_text(encoding="utf-8")

    assert readme.startswith("# AI Native 履约争端审理系统")
    assert "Agent Runtime Harness" in readme
    assert "Platform Human Review" in readme
    assert "Tool Executor" in readme
    assert "业务事实" in module_map
    assert "禁止依赖" in module_map
