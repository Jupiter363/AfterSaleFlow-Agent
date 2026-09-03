# 文件作用：自动化测试文件，验证 test_api 相关模块的行为、契约或页面布局。

from fastapi.testclient import TestClient

from app.config import Settings
from app.schemas import (
    EvaluationAnalysisResult,
    EvaluationFinding,
    EvaluationMetricScores,
    IntakeAnalysisOutput,
    SimulatedExternalImportResult,
    SimulatedExternalDispute,
)
from app.main import create_app


class FakeIntakeWorkflow:
    # 所属模块：Python 支撑模块 > test_api；函数角色：类/闭包内部方法。
    # 具体功能：`analyze` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`IntakeAnalysisOutput`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `IntakeAnalysisOutput`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def analyze(self, request, context):
        return IntakeAnalysisOutput(
            case_type="DISPUTE",
            dispute_type="NON_RECEIPT",
            risk_level="HIGH",
            potential_dispute=True,
            missing_slots=[],
            title="Delivery dispute",
            normalized_description=request.description,
        )


class FakeEvaluationWorkflow:
    # 所属模块：Python 支撑模块 > test_api；函数角色：类/闭包内部方法。
    # 具体功能：`analyze` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`EvaluationAnalysisResult`、`EvaluationMetricScores`、`EvaluationFinding`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `EvaluationAnalysisResult`、`EvaluationMetricScores`、`EvaluationFinding`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def analyze(self, request, context):
        return EvaluationAnalysisResult(
            case_id=request.case_id,
            evaluation_status="COMPLETED",
            metric_scores=EvaluationMetricScores(
                draft_approval_rate=1.0,
                reviewer_modification_rate=0.0,
                evidence_quality_score=0.8,
                policy_coverage_score=0.7,
                execution_quality_score=1.0,
                process_quality_score=0.9,
                overall_quality_score=0.88,
            ),
            findings=[
                EvaluationFinding(
                    category="POLICY_GAP",
                    severity="LOW",
                    summary="Policy could include another example.",
                    supporting_references=[],
                )
            ],
            rule_gap_suggestions=["Add an example."],
            improvement_suggestions=["Keep evidence structured."],
            automatic_changes_applied=False,
            online_case_mutated=False,
            evaluator_model="test-evaluation-model",
            prompt_version="evaluation-v1",
            latency_ms=5,
            token_usage=12,
        )


class FakeSimulatedImportWorkflow:
    # 所属模块：Python 支撑模块 > test_api；函数角色：类/闭包内部方法。
    # 具体功能：`generate` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`SimulatedExternalImportResult`、`SimulatedExternalDispute`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `SimulatedExternalImportResult`、`SimulatedExternalDispute`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def generate(self, request):
        return SimulatedExternalImportResult(
            items=[
                SimulatedExternalDispute(
                    source_system="LLM_SIMULATED_OMS",
                    external_case_reference="SIM-20260706-001",
                    order_reference="ORDER-SIM-001",
                    after_sales_reference="AFTER-SIM-001",
                    logistics_reference="LOG-SIM-001",
                    user_id="user-local",
                    merchant_id="merchant-local",
                    initiator_role=request.initiator_role_hint,
                    dispute_type="QUALITY_DISPUTE",
                    title="LLM simulated watch dispute",
                    description="The LLM simulated an imported watch after-sales dispute.",
                    risk_level=request.risk_level_hint or "MEDIUM",
                )
            ]
        )


# 所属模块：Python 支撑模块 > test_api；函数角色：模块公开业务函数。
# 具体功能：`settings` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`Settings`。
# 上下游：上游为本文件的 API 测试；下游为 `Settings`。
# 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
def settings() -> Settings:
    return Settings(
        litellm_master_key="test-litellm-master-key",
        langfuse_public_key="pk-test-key",
        langfuse_secret_key="sk-test-secret",
        java_service_secret="test-java-service-secret",
        python_agent_service_secret="test-agent-service-secret",
        langfuse_enabled=False,
    )


# 所属模块：Python 支撑模块 > test_api；函数角色：回归测试用例。
# 具体功能：`test_intake_api_matches_the_java_client_raw_response_contract` 验证接待信息在固定案例中的输出、边界和失败行为；关键协作调用：`TestClient`、`client.post`、`create_app`。
# 上下游：上游为 相邻模块输入；下游为 本文件的 `settings`。
# 系统意义：固定“Python 支撑模块 > test_api”的可观察契约，防止后续重构改变业务结果。
def test_intake_api_matches_the_java_client_raw_response_contract() -> None:
    client = TestClient(
        create_app(settings(), intake_workflow=FakeIntakeWorkflow())
    )

    response = client.post(
        "/internal/agents/legacy/intake/analyze",
        json={
            "order_id": "ORDER_api",
            "after_sale_id": None,
            "user_id": "USER_api",
            "merchant_id": "MERCHANT_api",
            "description": "Tracking says delivered but the user did not receive it.",
            "attachment_ids": [],
            "channel": "WEB",
        },
        headers={"X-Service-Secret": "test-agent-service-secret"},
    )

    assert response.status_code == 200
    assert response.json() == {
        "case_type": "DISPUTE",
        "dispute_type": "NON_RECEIPT",
        "risk_level": "HIGH",
        "potential_dispute": True,
        "missing_slots": [],
        "title": "Delivery dispute",
        "normalized_description": (
            "Tracking says delivered but the user did not receive it."
        ),
    }


# 所属模块：Python 支撑模块 > test_api；函数角色：回归测试用例。
# 具体功能：`test_evaluation_api_only_accepts_authenticated_closed_case_snapshots` 验证冻结快照在固定案例中的输出、边界和失败行为；关键协作调用：`TestClient`、`client.post`、`create_app`。
# 上下游：上游为 相邻模块输入；下游为 本文件的 `settings`。
# 系统意义：固定“Python 支撑模块 > test_api”的可观察契约，防止后续重构改变业务结果。
def test_evaluation_api_only_accepts_authenticated_closed_case_snapshots() -> None:
    client = TestClient(
        create_app(
            settings(),
            intake_workflow=FakeIntakeWorkflow(),
            evaluation_workflow=FakeEvaluationWorkflow(),
        )
    )
    payload = {
        "case_id": "CASE_evaluation",
        "case_status": "CLOSED",
        "route_type": "DISPUTE_HEARING",
        "risk_level": "HIGH",
        "approval_decision": "APPROVE",
        "adjudication_draft": {},
        "approved_plan": {},
        "action_records": [],
        "evidence_summary": {},
        "policy_summary": {},
    }

    unauthorized = client.post(
        "/internal/agents/evaluation/analyze",
        json=payload,
        headers={"X-Service-Secret": "wrong-secret"},
    )
    response = client.post(
        "/internal/agents/evaluation/analyze",
        json=payload,
        headers={"X-Service-Secret": "test-agent-service-secret"},
    )

    assert unauthorized.status_code == 401
    assert response.status_code == 200
    assert response.json()["evaluation_status"] == "COMPLETED"
    assert response.json()["online_case_mutated"] is False


# 所属模块：Python 支撑模块 > test_api；函数角色：回归测试用例。
# 具体功能：`test_simulated_external_import_api_generates_import_dtos` 验证本阶段状态在固定案例中的输出、边界和失败行为；关键协作调用：`TestClient`、`client.post`、`create_app`。
# 上下游：上游为 相邻模块输入；下游为 本文件的 `settings`。
# 系统意义：固定“Python 支撑模块 > test_api”的可观察契约，防止后续重构改变业务结果。
def test_simulated_external_import_api_generates_import_dtos() -> None:
    client = TestClient(
        create_app(
            settings(),
            intake_workflow=FakeIntakeWorkflow(),
            evaluation_workflow=FakeEvaluationWorkflow(),
            simulated_import_workflow=FakeSimulatedImportWorkflow(),
        )
    )

    response = client.post(
        "/internal/agents/external-import/simulate",
        json={
            "count": 1,
            "scenario": "手表售后争议",
            "risk_level_hint": "MEDIUM",
            "initiator_role_hint": "MERCHANT",
            "current_actor_id": "merchant-local",
            "counterparty_actor_id": "user-local",
        },
        headers={"X-Service-Secret": "test-agent-service-secret"},
    )

    assert response.status_code == 200
    assert response.json()["items"][0]["source_system"] == "LLM_SIMULATED_OMS"
    assert response.json()["items"][0]["initiator_role"] == "MERCHANT"
    assert response.json()["items"][0]["user_id"] == "user-local"
    assert response.json()["items"][0]["merchant_id"] == "merchant-local"
