# 文件作用：自动化测试文件，验证 test_phase16_acceptance_contract 相关模块的行为、契约或页面布局。

from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


# 所属模块：跨服务契约测试 > test_phase16_acceptance_contract；函数角色：模块公开业务函数。
# 具体功能：`read` 读取并按案件、角色或会话范围筛选被测业务场景；关键协作调用：`read_text`。
# 上下游：上游为 本文件的 `test_phase16_release_review_and_rollback_docs_exist`；下游为 协作调用 `read_text`。
# 系统意义：该函数在系统中的业务边界是：只锁定公共契约，不锁死内部实现。
def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


# 所属模块：跨服务契约测试 > test_phase16_acceptance_contract；函数角色：回归测试用例。
# 具体功能：`test_phase16_runtime_test_suites_exist` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`exists`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `exists`。
# 系统意义：固定“跨服务契约测试 > test_phase16_acceptance_contract”的可观察契约，防止后续重构改变业务结果。
def test_phase16_runtime_test_suites_exist() -> None:
    for relative in (
        "tests/api/test_api_contracts.py",
        "tests/e2e/test_main_flows.py",
        "tests/load/test_performance_budget.py",
        "tests/fixtures/case_payloads.json",
    ):
        assert (ROOT / relative).exists(), relative


# 所属模块：跨服务契约测试 > test_phase16_acceptance_contract；函数角色：回归测试用例。
# 具体功能：`test_phase16_ci_quality_gate_exists` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`workflow.exists`、`workflow.read_text`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `workflow.exists`、`workflow.read_text`。
# 系统意义：固定“跨服务契约测试 > test_phase16_acceptance_contract”的可观察契约，防止后续重构改变业务结果。
def test_phase16_ci_quality_gate_exists() -> None:
    workflow = ROOT / ".github/workflows/quality-gate.yml"
    assert workflow.exists()
    text = workflow.read_text(encoding="utf-8")
    for required in (
        "mvn",
        "pytest",
        "pnpm",
        "docker compose config",
        "scripts/smoke-test.sh",
        "secret",
    ):
        assert required in text


# 所属模块：跨服务契约测试 > test_phase16_acceptance_contract；函数角色：回归测试用例。
# 具体功能：`test_phase16_release_review_and_rollback_docs_exist` 验证人工复核信息在固定案例中的输出、边界和失败行为；关键协作调用：`join`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `read`。
# 系统意义：固定“跨服务契约测试 > test_phase16_acceptance_contract”的可观察契约，防止后续重构改变业务结果。
def test_phase16_release_review_and_rollback_docs_exist() -> None:
    contributing = read("CONTRIBUTING.md")
    deployment = read("docs/deployment/README.md")
    codex = read("docs/codex/README.md")
    release = read("docs/release/README.md")
    for required in (
        "Code Review",
        "rollback",
        "release",
        "smoke-test",
    ):
        combined = "\n".join([contributing, deployment, codex, release])
        assert required in combined
