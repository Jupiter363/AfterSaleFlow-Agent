# 文件作用：自动化测试文件，验证 test_api 相关模块的行为、契约或页面布局。

from fastapi.testclient import TestClient

from app.config import Settings
from app.schemas import (
    AdjudicationDraft,
    AdjudicationDraftOutput,
    HearingAnalysisResult,
    EvaluationAnalysisResult,
    EvaluationFinding,
    EvaluationMetricScores,
    IntakeAnalysisOutput,
    HearingRoundTurnResult,
    SimulatedExternalImportResult,
    SimulatedExternalDispute,
)
from app.main import create_app


class FakeWorkflow:
    # 所属模块：Python 支撑模块 > test_api；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖。
    # 上下游：上游为 相邻模块输入；下游为 结构化调用结果。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def __init__(self) -> None:
        self.context = None

    # 所属模块：Python 支撑模块 > test_api；函数角色：类/闭包内部方法。
    # 具体功能：`analyze` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`HearingAnalysisResult`、`AdjudicationDraftOutput`、`AdjudicationDraft`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `HearingAnalysisResult`、`AdjudicationDraftOutput`、`AdjudicationDraft`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def analyze(self, request, context):
        self.context = context
        return HearingAnalysisResult(
            case_id=request.case_id,
            workflow_id=request.workflow_id,
            workflow_status="MANUAL_REVIEW_REQUIRED",
            executed_nodes=[],
            adjudication_draft=AdjudicationDraftOutput(
                draft=AdjudicationDraft(
                    recommended_outcome="UNDETERMINED",
                    reasoning_summary="Human analysis is required.",
                    issue_findings=[],
                    confidence=0,
                    risk_level="HIGH",
                    review_focus=["Review case"],
                )
            ),
            manual_review_reasons=["TEST"],
            prompt_version="hearing-v1",
            model="test-model",
        )


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


class FakeHearingRoundTurnWorkflow:
    # 所属模块：Python 支撑模块 > test_api；函数角色：类/闭包内部方法。
    # 具体功能：`run` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`HearingRoundTurnResult`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `HearingRoundTurnResult`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def run(self, request):
        return HearingRoundTurnResult(
            speaker_role="JUDGE",
            message_text="第一轮事实陈述已封存。请进入第二轮定向说明。",
            round_summary="双方围绕签收未收到进行第一轮陈述。",
            questions_for_user=["请补充签收现场情况。"],
            questions_for_merchant=["请补充物流交接记录。"],
            court_event_type="JUDGE_NEXT_QUESTIONS_READY",
            round_no=request.round_no,
            next_round_no=request.round_no + 1,
            final_draft_required=False,
            prompt_version="hearing-round-turn-v1",
            model="fake-round-model",
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
# 上下游：上游为 本文件的 `test_hearing_api_requires_secret_and_propagates_correlation_ids`、`test_hearing_api_accepts_courtroom_bootstrap_context_from_java`、`test_intake_api_matches_the_java_client_raw_response_contract`、`test_evaluation_api_only_accepts_authenticated_closed_case_snapshots`；下游为 协作调用 `Settings`。
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


# 所属模块：Python 支撑模块 > test_api；函数角色：模块公开业务函数。
# 具体功能：`request_payload` 读取并按案件、角色或会话范围筛选本阶段状态；返回/更新字段：`case_id`、`workflow_id`、`user_id`、`claims`。
# 上下游：上游为 本文件的 `test_hearing_api_requires_secret_and_propagates_correlation_ids`、`test_hearing_api_accepts_courtroom_bootstrap_context_from_java`；下游为 返回/更新 `case_id`、`workflow_id`、`user_id`、`claims`。
# 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
def request_payload() -> dict:
    return {
        "case_id": "CASE_api",
        "workflow_id": "WORKFLOW_api",
        "user_id": "USER_api",
        "claims": [
            {
                "claim_id": "CLAIM_api",
                "party_type": "USER",
                "statement": "The parcel was not received.",
            }
        ],
        "evidence": [],
        "policy_candidates": [],
    }


# 所属模块：Python 支撑模块 > test_api；函数角色：回归测试用例。
# 具体功能：`test_hearing_api_requires_secret_and_propagates_correlation_ids` 验证庭审材料在固定案例中的输出、边界和失败行为；关键协作调用：`FakeWorkflow`、`TestClient`、`client.post`。
# 上下游：上游为 相邻模块输入；下游为 本文件的 `settings`、`request_payload`。
# 系统意义：固定“Python 支撑模块 > test_api”的可观察契约，防止后续重构改变业务结果。
def test_hearing_api_requires_secret_and_propagates_correlation_ids() -> None:
    workflow = FakeWorkflow()
    client = TestClient(create_app(settings(), workflow))

    unauthorized = client.post(
        "/internal/agents/legacy/hearing/analyze",
        json=request_payload(),
        headers={"X-Service-Secret": "wrong-secret"},
    )
    assert unauthorized.status_code == 401

    response = client.post(
        "/internal/agents/legacy/hearing/analyze",
        json=request_payload(),
        headers={
            "X-Service-Secret": "test-agent-service-secret",
            "X-Trace-Id": "TRACE_api",
            "X-Request-Id": "REQ_api",
            "X-Role": "SYSTEM",
        },
    )

    assert response.status_code == 200
    assert response.json()["adjudication_draft"]["draft"]["is_final_decision"] is False
    assert response.headers["X-Trace-Id"] == "TRACE_api"
    assert response.headers["X-Request-Id"] == "REQ_api"
    assert workflow.context.trace_id == "TRACE_api"


# 所属模块：Python 支撑模块 > test_api；函数角色：回归测试用例。
# 具体功能：`test_hearing_api_accepts_courtroom_bootstrap_context_from_java` 验证案件与会话上下文在固定案例中的输出、边界和失败行为；关键协作调用：`FakeWorkflow`、`TestClient`、`client.post`。
# 上下游：上游为 相邻模块输入；下游为 本文件的 `request_payload`、`settings`。
# 系统意义：固定“Python 支撑模块 > test_api”的可观察契约，防止后续重构改变业务结果。
def test_hearing_api_accepts_courtroom_bootstrap_context_from_java() -> None:
    workflow = FakeWorkflow()
    client = TestClient(create_app(settings(), workflow))
    payload = request_payload()
    payload["hearing_context"] = {
        "completed_statement_rounds": 3,
        "max_statement_rounds": 3,
        "final_convergence": True,
        "must_produce_final_plan": True,
        "allow_supplemental_request": False,
        "courtroom_context": {
            "schema_version": "hearing_bootstrap_dossier.v1",
            "case_id": "CASE_api",
            "intake_dossier": {
                "case_story": "物流记录显示包裹已签收，但用户称本人未收到商品。"
            },
            "evidence_dossier": {
                "fact_evidence_matrix": [
                    {
                        "fact_id": "FACT_SIGNED",
                        "fact": "物流显示已签收",
                        "evidence_strength": "MEDIUM",
                    }
                ]
            },
            "courtroom_opening_messages": [
                {
                    "sender_role": "INTAKE_OFFICER",
                    "message_text": "案情接待官已宣读案情卷宗。",
                }
            ],
        },
        "sealed_rounds": [
            {
                "round_no": 3,
                "round_status": "COMPLETED",
                "summary_json": "{\"trigger\":\"BOTH_PARTIES_SUBMITTED\"}",
                "party_submissions": [
                    {
                        "participant_role": "USER",
                        "submission_source": "PARTY_ACTION",
                        "submission_json": "{\"statement\":\"用户坚持称本人未收到包裹。\"}",
                    }
                ],
            }
        ],
    }

    response = client.post(
        "/internal/agents/legacy/hearing/analyze",
        json=payload,
        headers={"X-Service-Secret": "test-agent-service-secret"},
    )

    assert response.status_code == 200
    assert response.json()["adjudication_draft"]["draft"]["is_final_decision"] is False


# 所属模块：Python 支撑模块 > test_api；函数角色：回归测试用例。
# 具体功能：`test_intake_api_matches_the_java_client_raw_response_contract` 验证接待信息在固定案例中的输出、边界和失败行为；关键协作调用：`TestClient`、`client.post`、`create_app`。
# 上下游：上游为 相邻模块输入；下游为 本文件的 `settings`。
# 系统意义：固定“Python 支撑模块 > test_api”的可观察契约，防止后续重构改变业务结果。
def test_intake_api_matches_the_java_client_raw_response_contract() -> None:
    client = TestClient(
        create_app(settings(), FakeWorkflow(), FakeIntakeWorkflow())
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
            FakeWorkflow(),
            FakeIntakeWorkflow(),
            FakeEvaluationWorkflow(),
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
            FakeWorkflow(),
            FakeIntakeWorkflow(),
            FakeEvaluationWorkflow(),
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


# 所属模块：Python 支撑模块 > test_api；函数角色：回归测试用例。
# 具体功能：`test_hearing_round_turn_api_matches_java_court_adapter_contract` 验证庭审轮次在固定案例中的输出、边界和失败行为；关键协作调用：`TestClient`、`client.post`、`create_app`。
# 上下游：上游为 相邻模块输入；下游为 本文件的 `settings`。
# 系统意义：固定“Python 支撑模块 > test_api”的可观察契约，防止后续重构改变业务结果。
def test_hearing_round_turn_api_matches_java_court_adapter_contract() -> None:
    client = TestClient(
        create_app(
            settings(),
            FakeWorkflow(),
            FakeIntakeWorkflow(),
            FakeEvaluationWorkflow(),
            hearing_round_turn_workflow=FakeHearingRoundTurnWorkflow(),
        )
    )

    response = client.post(
        "/internal/agents/hearing/round-turn",
        json={
            "case_id": "CASE_COURT",
            "workflow_id": "WORKFLOW_COURT",
            "order_id": "ORDER_COURT",
            "after_sale_id": "AS_COURT",
            "logistics_id": "LOG_COURT",
            "dispute_type": "SIGNED_NOT_RECEIVED",
            "title": "物流显示签收但用户称未收到",
            "case_description": "用户称物流显示已签收但本人未收到包裹。",
            "risk_level": "HIGH",
            "round_no": 1,
            "dossier_version": 2,
            "final_round": False,
            "round_status": "COMPLETED",
            "round_summary_json": "{\"trigger\":\"BOTH_PARTIES_SUBMITTED\"}",
            "party_submissions": [
                {
                    "participant_role": "USER",
                    "participant_id": "user-local",
                    "submission_source": "PARTY_ACTION",
                    "submission_json": "{\"statement\":\"用户陈述\"}",
                }
            ],
        },
        headers={
            "X-Service-Secret": "test-agent-service-secret",
            "X-Trace-Id": "TRACE_round",
            "X-Request-Id": "REQ_round",
        },
    )

    assert response.status_code == 200
    assert response.json()["speaker_role"] == "JUDGE"
    assert response.json()["court_event_type"] == "JUDGE_NEXT_QUESTIONS_READY"
    assert response.json()["next_round_no"] == 2
    assert response.headers["X-Trace-Id"] == "TRACE_round"
