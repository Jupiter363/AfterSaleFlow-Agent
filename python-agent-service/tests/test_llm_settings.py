# 文件作用：自动化测试文件，验证 test_llm_settings 相关模块的行为、契约或页面布局。

from app.config import Settings
from app.main import _build_evaluation_workflow


# 所属模块：Python 支撑模块 > test_llm_settings；函数角色：回归测试用例。
# 具体功能：`test_settings_resolve_qwen_only_through_litellm` 读取并按案件、角色或会话范围筛选结构化模型调用；关键协作调用：`Settings`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `Settings`。
# 系统意义：固定“Python 支撑模块 > test_llm_settings”的可观察契约，防止后续重构改变业务结果。
def test_settings_resolve_qwen_only_through_litellm() -> None:
    settings = Settings(
        litellm_base_url="http://litellm-proxy:4000",
        litellm_model="qwen3.7-plus",
        litellm_master_key="test-litellm-master-key",
        langfuse_public_key="pk-test-key",
        langfuse_secret_key="sk-test-secret",
        java_service_secret="test-java-service-secret",
        python_agent_service_secret="test-agent-service-secret",
        langfuse_enabled=False,
    )

    assert settings.resolved_llm_base_url == "http://litellm-proxy:4000"
    assert settings.resolved_llm_model == "qwen3.7-plus"
    assert settings.resolved_llm_api_key == "test-litellm-master-key"


# 所属模块：Python 支撑模块 > test_llm_settings；函数角色：回归测试用例。
# 具体功能：`test_default_evaluation_workflow_builder_returns_workflow` 注册节点、条件边和结束条件，固定本阶段状态的执行顺序；关键协作调用：`Settings`、`_build_evaluation_workflow`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `Settings`、`_build_evaluation_workflow`。
# 系统意义：固定“Python 支撑模块 > test_llm_settings”的可观察契约，防止后续重构改变业务结果。
def test_default_evaluation_workflow_builder_returns_workflow() -> None:
    settings = Settings(
        litellm_master_key="test-litellm-master-key",
        langfuse_public_key="pk-test-key",
        langfuse_secret_key="sk-test-secret",
        java_service_secret="test-java-service-secret",
        python_agent_service_secret="test-agent-service-secret",
        langfuse_enabled=False,
    )

    workflow = _build_evaluation_workflow(settings)

    assert workflow is not None
