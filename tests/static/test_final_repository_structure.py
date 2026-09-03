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
        "components/avatar",
        "components/common",
        "components/notification",
        "components/room",
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
    architecture = (
        ROOT / "docs" / "architecture" / "temporal-first-agent-platform.md"
    ).read_text(encoding="utf-8")
    normalized_readme = " ".join(readme.split())

    assert readme.startswith('<div align="center">')
    assert "# AfterSaleFlow-Agent" in readme
    assert "AI Native 履约争端审理协作平台" in normalized_readme
    assert "Python 认知运行时" in normalized_readme
    assert "最终裁决由平台人工终审确认" in normalized_readme
    assert "Tool Executor 不接受“模型建议执行”作为授权" in normalized_readme
    assert "Java and PostgreSQL are the authoritative domain ledger" in architecture
    assert "No state field has two authoritative writers" in architecture
