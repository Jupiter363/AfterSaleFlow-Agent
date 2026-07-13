# 文件作用：自动化测试文件，验证 test_phase8_agent_contract 相关模块的行为、契约或页面布局。

from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SERVICE = ROOT / "python-agent-service"
APP = SERVICE / "app"


# 所属模块：跨服务契约测试 > test_phase8_agent_contract；函数角色：回归测试用例。
# 具体功能：`test_python_agent_dependencies_and_container_are_formal` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`read_text`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `read_text`。
# 系统意义：固定“跨服务契约测试 > test_phase8_agent_contract”的可观察契约，防止后续重构改变业务结果。
def test_python_agent_dependencies_and_container_are_formal() -> None:
    requirements = (SERVICE / "requirements.txt").read_text(encoding="utf-8")
    assert "langgraph==1.2.6" in requirements
    assert "langfuse==4.11.0" in requirements
    assert "fastapi==0.138.0" in requirements

    dockerfile = (SERVICE / "Dockerfile").read_text(encoding="utf-8")
    assert "USER app" in dockerfile
    assert "HEALTHCHECK" in dockerfile
    assert "app.main:create_app" in dockerfile


# 所属模块：跨服务契约测试 > test_phase8_agent_contract；函数角色：回归测试用例。
# 具体功能：`test_all_c1_to_c6_nodes_and_conditional_gap_path_exist` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`read_text`、`is_file`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `read_text`、`is_file`。
# 系统意义：固定“跨服务契约测试 > test_phase8_agent_contract”的可观察契约，防止后续重构改变业务结果。
def test_all_c1_to_c6_nodes_and_conditional_gap_path_exist() -> None:
    graph = (APP / "graph.py").read_text(encoding="utf-8")
    for node in (
        "issue_framing_node",
        "evidence_gap_request_node",
        "party_liaison_node",
        "evidence_cross_check_node",
        "rule_application_node",
        "adjudication_draft_node",
    ):
        assert node in graph
        assert (APP / "prompts" / f"{node}.md").is_file()
    assert "add_conditional_edges" in graph
    assert "requires_supplemental_evidence" in graph


# 所属模块：跨服务契约测试 > test_phase8_agent_contract；函数角色：回归测试用例。
# 具体功能：`test_agent_output_is_non_final_and_forced_to_human_review` 验证人工复核信息在固定案例中的输出、边界和失败行为；关键协作调用：`read_text`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `read_text`。
# 系统意义：固定“跨服务契约测试 > test_phase8_agent_contract”的可观察契约，防止后续重构改变业务结果。
def test_agent_output_is_non_final_and_forced_to_human_review() -> None:
    schemas = (APP / "schemas" / "models.py").read_text(encoding="utf-8")
    assert 'requires_human_review: Literal[True] = True' in schemas
    assert 'is_final_decision: Literal[False] = False' in schemas
    assert 'draft_status: Literal["PENDING_HUMAN_REVIEW"]' in schemas

    workflow = (APP / "workflow.py").read_text(encoding="utf-8")
    assert "MANUAL_REVIEW_REQUIRED" in workflow
    assert "AGENT_OUTPUT_SCHEMA_INVALID" in workflow


# 所属模块：跨服务契约测试 > test_phase8_agent_contract；函数角色：回归测试用例。
# 具体功能：`test_agent_has_no_execution_tool_and_masks_trace_inputs` 验证履约执行动作在固定案例中的输出、边界和失败行为；关键协作调用：`join`、`read_text`、`path.read_text`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `join`、`read_text`、`path.read_text`、`APP.glob`。
# 系统意义：固定“跨服务契约测试 > test_phase8_agent_contract”的可观察契约，防止后续重构改变业务结果。
def test_agent_has_no_execution_tool_and_masks_trace_inputs() -> None:
    production_python = "\n".join(
        path.read_text(encoding="utf-8")
        for path in APP.glob("*.py")
    )
    assert "ToolExecutor" not in production_python
    assert "refund.create" not in production_python
    assert "close_case" not in production_python
    assert "redacted_trace_input" in production_python
    assert "sha256" in (APP / "tracing.py").read_text(encoding="utf-8")


# 所属模块：跨服务契约测试 > test_phase8_agent_contract；函数角色：回归测试用例。
# 具体功能：`test_agent_api_and_langfuse_headless_keys_are_wired` 验证被测业务场景在固定案例中的输出、边界和失败行为；关键协作调用：`read_text`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 协作调用 `read_text`。
# 系统意义：固定“跨服务契约测试 > test_phase8_agent_contract”的可观察契约，防止后续重构改变业务结果。
def test_agent_api_and_langfuse_headless_keys_are_wired() -> None:
    main = (APP / "main.py").read_text(encoding="utf-8")
    assert "/internal/agents/intake/analyze" in main
    assert "/internal/agents/hearing/run-stage" in main
    assert "X-Service-Secret" in main

    compose = (ROOT / "docker-compose.yml").read_text(encoding="utf-8")
    assert "LANGFUSE_INIT_PROJECT_PUBLIC_KEY: ${LANGFUSE_PUBLIC_KEY}" in compose
    assert "LANGFUSE_INIT_PROJECT_SECRET_KEY: ${LANGFUSE_SECRET_KEY}" in compose
    assert "PROMPT_VERSION: hearing-v1" in compose
