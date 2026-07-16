# 文件作用：自动化测试文件，验证 test_phase9_temporal_contract 相关模块的行为、契约或页面布局。

from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "java-api-service" / "src" / "main" / "java" / "com" / "example" / "dispute"


# 所属模块：跨服务契约测试 > test_phase9_temporal_contract；函数角色：模块公开业务函数。
# 具体功能：`text` 围绕展示文本计算该函数独立负责的业务派生值；关键协作调用：`path.read_text`。
# 上下游：上游为 本文件的 `test_temporal_workflow_owns_wait_signal_timeout_and_retry`、`test_hearing_controller_is_async_and_python_does_not_own_global_state`、`test_hearing_state_records_drafts_and_submissions_are_real_entities`、`test_worker_is_explicitly_enabled_only_in_compose`；下游为 协作调用 `path.read_text`。
# 系统意义：该函数在系统中的业务边界是：只锁定公共契约，不锁死内部实现。
def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


# 所属模块：跨服务契约测试 > test_phase9_temporal_contract；函数角色：回归测试用例。
# 具体功能：`test_temporal_workflow_owns_wait_signal_timeout_and_retry` 验证被测业务场景在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `text`。
# 系统意义：固定“跨服务契约测试 > test_phase9_temporal_contract”的可观察契约，防止后续重构改变业务结果。
def test_temporal_workflow_owns_wait_signal_timeout_and_retry() -> None:
    workflow = text(
        JAVA
        / "workflow"
        / "temporal"
        / "EvidenceWindowWorkflowImpl.java"
    )
    contract = text(
        JAVA
        / "workflow"
        / "temporal"
        / "EvidenceWindowWorkflow.java"
    )
    assert "Workflow.await(" in workflow
    assert "partyCompleted" in contract
    assert "activities.warn" in workflow
    assert "activities.expire" in workflow
    worker = text(JAVA / "workflow" / "config" / "TemporalWorkerConfiguration.java")
    assert "EvidenceWindowWorkflowImpl.class" in worker
    assert "DisputeHearingWorkflowImpl.class" not in worker


# 所属模块：跨服务契约测试 > test_phase9_temporal_contract；函数角色：回归测试用例。
# 具体功能：`test_hearing_controller_is_async_and_python_does_not_own_global_state` 验证庭审材料在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `text`。
# 系统意义：固定“跨服务契约测试 > test_phase9_temporal_contract”的可观察契约，防止后续重构改变业务结果。
def test_hearing_controller_is_async_and_python_does_not_own_global_state() -> None:
    controller = text(
        JAVA / "hearing" / "api" / "HearingFlowController.java"
    )
    runtime = text(
        JAVA
        / "hearing"
        / "application"
        / "HearingFlowRuntimeService.java"
    )
    assert '@PostMapping("/answers")' in controller
    assert '@PostMapping("/evidence-batches")' in controller
    assert ".getResult(" not in controller
    assert "HearingFlowInstanceRepository" in runtime
    assert "AgentRunCoordinator" in runtime
    assert "trialDossierRepository" in runtime
    assert "static" not in runtime.split("class HearingFlowRuntimeService", 1)[1].split("{", 1)[0]


# 所属模块：跨服务契约测试 > test_phase9_temporal_contract；函数角色：回归测试用例。
# 具体功能：`test_hearing_state_records_drafts_and_submissions_are_real_entities` 验证庭审材料在固定案例中的输出、边界和失败行为；关键协作调用：`items`。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `text`。
# 系统意义：固定“跨服务契约测试 > test_phase9_temporal_contract”的可观察契约，防止后续重构改变业务结果。
def test_hearing_state_records_drafts_and_submissions_are_real_entities() -> None:
    for name, table in {
        "HearingFlowInstanceEntity.java": "hearing_flow_instance",
        "HearingFlowStageEntity.java": "hearing_flow_stage",
        "HearingFlowActionEntity.java": "hearing_flow_action",
        "HearingTrialDossierEntity.java": "hearing_trial_dossier",
        "HearingFlowArtifactEntity.java": "hearing_flow_artifact",
    }.items():
        entity = text(
            JAVA / "hearing" / "infrastructure" / "persistence" / "entity" / name
        )
        assert f'@Table(name = "{table}")' in entity
        assert "@Column" in entity


# 所属模块：跨服务契约测试 > test_phase9_temporal_contract；函数角色：回归测试用例。
# 具体功能：`test_worker_is_explicitly_enabled_only_in_compose` 把上游材料组装为本阶段可消费的被测业务场景。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `text`。
# 系统意义：固定“跨服务契约测试 > test_phase9_temporal_contract”的可观察契约，防止后续重构改变业务结果。
def test_worker_enablement_is_explicitly_configurable() -> None:
    config = text(
        JAVA
        / "workflow"
        / "config"
        / "TemporalWorkerConfiguration.java"
    )
    compose = text(ROOT / "docker-compose.yml")
    local = text(
        ROOT
        / "java-api-service"
        / "src"
        / "main"
        / "resources"
        / "application-local.yml"
    )
    assert "@ConditionalOnProperty(" in config
    assert 'havingValue = "true"' in config
    assert 'APP_TEMPORAL_WORKER_ENABLED: "true"' in compose
    assert "${APP_TEMPORAL_WORKER_ENABLED:" in local


# 所属模块：跨服务契约测试 > test_phase9_temporal_contract；函数角色：回归测试用例。
# 具体功能：`test_phase9_public_api_and_service_auth_headers_exist` 验证被测业务场景在固定案例中的输出、边界和失败行为。
# 上下游：上游为 仓库源码、固定夹具、服务契约；下游为 本文件的 `text`。
# 系统意义：固定“跨服务契约测试 > test_phase9_temporal_contract”的可观察契约，防止后续重构改变业务结果。
def test_phase9_public_api_and_service_auth_headers_exist() -> None:
    controller = text(
        JAVA / "hearing" / "api" / "HearingFlowController.java"
    )
    operations = text(
        JAVA
        / "agentstream"
        / "application"
        / "AgentStreamOperationRegistry.java"
    )
    for route in (
        "/answers",
        "/evidence-batches",
        "/complete",
        "/settlements",
        "/settlements/{version}/confirm",
    ):
        assert route in controller
    for route in (
        "/internal/agents/hearing-flow/intake/questions/stream",
        "/internal/agents/hearing-flow/intake/synthesis/stream",
        "/internal/agents/hearing-flow/evidence/requests/stream",
        "/internal/agents/hearing-flow/evidence/synthesis/stream",
        "/internal/agents/hearing-flow/judge/v1/stream",
        "/internal/agents/hearing-flow/jury/review/stream",
        "/internal/agents/hearing-flow/judge/v2/stream",
    ):
        assert route in operations
    assert "HEARING_ROUND" not in operations
    assert "HEARING_ANALYSIS" not in operations
    client = text(
        JAVA / "agentstream" / "infrastructure" / "AgentNdjsonStreamClient.java"
    )
    assert '"X-Role", "SYSTEM"' in client
    assert "TraceIdFilter.TRACE_HEADER" in client
