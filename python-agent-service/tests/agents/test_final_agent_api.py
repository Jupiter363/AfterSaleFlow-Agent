# 文件作用：自动化测试文件，验证 test_final_agent_api 相关模块的行为、契约或页面布局。

from __future__ import annotations

from fastapi.testclient import TestClient

from app.api.final_agents import FinalAgentServices
from app.config import Settings
from app.main import create_app
from app.schemas import (
    AdjudicationDraft,
    AdjudicationDraftOutput,
    CriticReport,
    CriticSeverity,
    CriticStatus,
    CriticType,
    DeliberationReport,
    DisputeIntakeResult,
    EvaluationAnalysisResult,
    EvaluationMetricScores,
    EvidenceDossierResult,
    HearingAnalysisResult,
    HearingStageResult,
    IntakeAnalysisOutput,
    ReviewCopilotAnswer,
)


class _LegacyWorkflow:
    # 所属模块：Agent 角色能力 > test_final_agent_api；函数角色：类/闭包内部方法。
    # 具体功能：`analyze` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`HearingAnalysisResult`、`AdjudicationDraftOutput`、`AdjudicationDraft`。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 协作调用 `HearingAnalysisResult`、`AdjudicationDraftOutput`、`AdjudicationDraft`。
    # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
    def analyze(self, request, _context):
        return HearingAnalysisResult(
            case_id=request.case_id,
            workflow_id=request.workflow_id,
            workflow_status="COMPLETED",
            executed_nodes=["adjudication_draft_node"],
            adjudication_draft=AdjudicationDraftOutput(
                draft=AdjudicationDraft(
                    recommended_outcome="REQUEST_MORE_EVIDENCE",
                    reasoning_summary="More evidence is needed.",
                    issue_findings=[],
                    confidence=0.7,
                    risk_level="HIGH",
                    review_focus=["Check handover proof."],
                )
            ),
            prompt_version="hearing-v1",
            model="test-model",
        )


class _LegacyIntake:
    # 所属模块：Agent 角色能力 > test_final_agent_api；函数角色：类/闭包内部方法。
    # 具体功能：`analyze` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`IntakeAnalysisOutput`。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 协作调用 `IntakeAnalysisOutput`。
    # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
    def analyze(self, request, _context):
        return IntakeAnalysisOutput(
            case_type="DISPUTE",
            dispute_type="SIGNED_NOT_RECEIVED",
            risk_level="HIGH",
            potential_dispute=True,
            missing_slots=[],
            title="Delivery dispute",
            normalized_description=request.description,
        )


class _LegacyEvaluation:
    # 所属模块：Agent 角色能力 > test_final_agent_api；函数角色：类/闭包内部方法。
    # 具体功能：`analyze` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`EvaluationAnalysisResult`、`EvaluationMetricScores`。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 协作调用 `EvaluationAnalysisResult`、`EvaluationMetricScores`。
    # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
    def analyze(self, request, _context):
        return EvaluationAnalysisResult(
            case_id=request.case_id,
            evaluation_status="COMPLETED",
            metric_scores=EvaluationMetricScores(
                draft_approval_rate=1,
                reviewer_modification_rate=0,
                evidence_quality_score=0.8,
                policy_coverage_score=0.8,
                execution_quality_score=0.8,
                process_quality_score=0.8,
                overall_quality_score=0.8,
            ),
            findings=[],
            rule_gap_suggestions=[],
            improvement_suggestions=[],
            evaluator_model="test-model",
            prompt_version="evaluation-v1",
            latency_ms=1,
            token_usage=1,
        )


class _IntakeAgent:
    # 所属模块：Agent 角色能力 > test_final_agent_api；函数角色：类/闭包内部方法。
    # 具体功能：`analyze` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`DisputeIntakeResult`。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 协作调用 `DisputeIntakeResult`。
    # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
    def analyze(self, request, context, *, case_state):
        return DisputeIntakeResult(
            admissible=True,
            admission_recommendation="ACCEPTED",
            dispute_type="SIGNED_NOT_RECEIVED",
            initiator_role=request.initiator_role,
            order_reference=request.order_reference,
            after_sales_reference=request.after_sales_reference,
            logistics_reference=request.logistics_reference,
            party_claims=[
                {
                    "party": request.initiator_role,
                    "claim_text": request.raw_text,
                    "source_ref": request.submission_id,
                }
            ],
            requested_outcome="UNKNOWN",
            confidence=0.8,
            next_step="BUILD_DOSSIER",
            room_utterance="The dispute intake recommendation is ready.",
        )


class _EvidenceAgent:
    # 所属模块：Agent 角色能力 > test_final_agent_api；函数角色：类/闭包内部方法。
    # 具体功能：`build` 把上游材料组装为本阶段可消费的本阶段状态；关键协作调用：`EvidenceDossierResult`。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 协作调用 `EvidenceDossierResult`。
    # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
    def build(self, request):
        return EvidenceDossierResult(
            case_id=request.case_id,
            dossier_version=1,
        )


class _JudgeAgent:
    # 所属模块：Agent 角色能力 > test_final_agent_api；函数角色：类/闭包内部方法。
    # 具体功能：`run_stage` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`HearingStageResult`。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 协作调用 `HearingStageResult`。
    # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
    def run_stage(self, request, context, *, case_state):
        return HearingStageResult(
            case_id=request.case_id,
            workflow_id=request.workflow_id,
            stage=request.stage,
            dossier_version=request.dossier_version,
            output={"neutral_summary": "Receipt is disputed.", "issues": []},
            output_schema="IssueFramingOutput",
            prompt_version="hearing-v1",
            model="test-model",
        )


class _PanelAgent:
    # 所属模块：Agent 角色能力 > test_final_agent_api；函数角色：类/闭包内部方法。
    # 具体功能：`run` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`DeliberationReport`、`CriticReport`。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 协作调用 `DeliberationReport`、`CriticReport`。
    # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
    def run(self, request):
        fingerprint = "a" * 64
        return DeliberationReport(
            deliberation_id="DELIBERATION_api",
            panel_result="NO_MAJOR_OBJECTION",
            frozen_input_fingerprint=fingerprint,
            critic_reports=[
                CriticReport(
                    critic=critic,
                    scope=critic.name,
                    status=CriticStatus.COMPLETED,
                    severity=CriticSeverity.NONE,
                    frozen_input_fingerprint=fingerprint,
                )
                for critic in CriticType
            ],
            revision_required=False,
        )


class _CopilotAgent:
    # 所属模块：Agent 角色能力 > test_final_agent_api；函数角色：类/闭包内部方法。
    # 具体功能：`query` 读取并按案件、角色或会话范围筛选本阶段状态；关键协作调用：`ReviewCopilotAnswer`。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 协作调用 `ReviewCopilotAnswer`。
    # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
    def query(self, request):
        return ReviewCopilotAnswer(
            answer="The current packet shows an unresolved evidence gap.",
            statements=[
                {
                    "kind": "FACT",
                    "text": "The current packet contains an evidence gap.",
                    "refs": request.available_fact_refs,
                }
            ],
            fact_refs=request.available_fact_refs,
        )


class _EvaluationAgent:
    # 所属模块：Agent 角色能力 > test_final_agent_api；函数角色：类/闭包内部方法。
    # 具体功能：`analyze` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`analyze`、`_LegacyEvaluation`。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 协作调用 `analyze`、`_LegacyEvaluation`。
    # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
    def analyze(self, request, context, *, offline):
        return _LegacyEvaluation().analyze(request, context)


# 所属模块：Agent 角色能力 > test_final_agent_api；函数角色：模块私有业务函数。
# 具体功能：`_settings` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`Settings`。
# 上下游：上游为 本文件的 `_client`；下游为 协作调用 `Settings`。
# 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
def _settings() -> Settings:
    return Settings(
        litellm_master_key="test-litellm-master-key",
        langfuse_public_key="pk-test-key",
        langfuse_secret_key="sk-test-secret",
        java_service_secret="test-java-service-secret",
        python_agent_service_secret="test-agent-service-secret",
        langfuse_enabled=False,
    )


# 所属模块：Agent 角色能力 > test_final_agent_api；函数角色：模块私有业务函数。
# 具体功能：`_client` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`_LegacyWorkflow`、`_LegacyIntake`、`_LegacyEvaluation`。
# 上下游：上游为 本文件的 `test_all_final_internal_agent_routes_are_authenticated`、`test_final_internal_agent_routes_return_strict_non_final_outputs`；下游为 本文件的 `_settings`。
# 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
def _client() -> TestClient:
    legacy_hearing = _LegacyWorkflow()
    legacy_intake = _LegacyIntake()
    legacy_evaluation = _LegacyEvaluation()
    services = FinalAgentServices(
        intake=_IntakeAgent(),
        evidence=_EvidenceAgent(),
        hearing=_JudgeAgent(),
        deliberation=_PanelAgent(),
        review_copilot=_CopilotAgent(),
        evaluation=_EvaluationAgent(),
    )
    return TestClient(
        create_app(
            _settings(),
            legacy_hearing,
            legacy_intake,
            legacy_evaluation,
            final_agent_services=services,
        )
    )


# 所属模块：Agent 角色能力 > test_final_agent_api；函数角色：模块私有业务函数。
# 具体功能：`_headers` 围绕本阶段状态计算该函数独立负责的业务派生值；返回/更新字段：`X-Service-Secret`。
# 上下游：上游为 本文件的 `test_final_internal_agent_routes_return_strict_non_final_outputs`；下游为 返回/更新 `X-Service-Secret`。
# 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
def _headers() -> dict[str, str]:
    return {"X-Service-Secret": "test-agent-service-secret"}


# 所属模块：Agent 角色能力 > test_final_agent_api；函数角色：回归测试用例。
# 具体功能：`test_all_final_internal_agent_routes_are_authenticated` 验证本阶段状态在固定案例中的输出、边界和失败行为；关键协作调用：`client.post`、`route.path.startswith`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_client`。
# 系统意义：固定“Agent 角色能力 > test_final_agent_api”的可观察契约，防止后续重构改变业务结果。
def test_all_final_internal_agent_routes_are_authenticated() -> None:
    client = _client()
    paths = {
        route.path
        for route in client.app.routes
        if route.path.startswith("/internal/agents/")
        and "/legacy/" not in route.path
    }
    assert paths == {
        "/internal/agents/intake/analyze",
        "/internal/agents/intake/turn",
        "/internal/agents/evidence/build",
        "/internal/agents/evidence/turn",
            "/internal/agents/hearing/run-stage",
            "/internal/agents/hearing/round-turn",
            "/internal/agents/deliberation/run",
        "/internal/agents/review-copilot/query",
        "/internal/agents/evaluation/analyze",
        "/internal/agents/external-import/simulate",
    }

    unauthorized = client.post(
        "/internal/agents/evidence/build",
        json={
            "case_id": "CASE_api",
            "case_version": 1,
            "submission_version": 1,
        },
        headers={"X-Service-Secret": "wrong-secret"},
    )
    assert unauthorized.status_code == 401


# 所属模块：Agent 角色能力 > test_final_agent_api；函数角色：回归测试用例。
# 具体功能：`test_final_internal_agent_routes_return_strict_non_final_outputs` 验证结构化输出在固定案例中的输出、边界和失败行为；关键协作调用：`client.post`、`intake.json`、`evidence.json`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_client`、`_headers`。
# 系统意义：固定“Agent 角色能力 > test_final_agent_api”的可观察契约，防止后续重构改变业务结果。
def test_final_internal_agent_routes_return_strict_non_final_outputs() -> None:
    client = _client()
    intake = client.post(
        "/internal/agents/intake/analyze",
        json={
            "submission_id": "SUBMISSION_api",
            "initiator_role": "USER",
            "order_reference": "ORDER_api",
            "raw_text": "Marked delivered but not received.",
            "channel": "WEB",
        },
        headers=_headers(),
    )
    evidence = client.post(
        "/internal/agents/evidence/build",
        json={
            "case_id": "CASE_api",
            "case_version": 1,
            "submission_version": 1,
        },
        headers=_headers(),
    )
    hearing = client.post(
        "/internal/agents/hearing/run-stage",
        json={
            "case_id": "CASE_api",
            "workflow_id": "WORKFLOW_api",
            "stage": "C1_ISSUE_FRAMING",
            "dossier_version": 1,
            "claims": [
                {
                    "claim_id": "CLAIM_api",
                    "party_type": "USER",
                    "statement": "Parcel not received.",
                }
            ],
        },
        headers=_headers(),
    )
    panel = client.post(
        "/internal/agents/deliberation/run",
        json={
            "frozen_input": {
                "case_id": "CASE_api",
                "case_snapshot_version": 1,
                "dossier_version": 1,
                "adjudication_draft_version": 1,
                "rule_version": "RULE_1",
                "frozen_dossier_snapshot": {},
                "frozen_draft_snapshot": {},
                "frozen_at_event_sequence": 1,
            },
            "trigger_reasons": ["HIGH_RISK"],
        },
        headers=_headers(),
    )
    copilot = client.post(
        "/internal/agents/review-copilot/query",
        json={
            "review_id": "REVIEW_api",
            "case_id": "CASE_api",
            "review_packet_version": 1,
            "reviewer_role": "PLATFORM_REVIEWER",
            "question": "Why is more evidence needed?",
            "available_fact_refs": ["EVIDENCE_api"],
        },
        headers=_headers(),
    )
    evaluation = client.post(
        "/internal/agents/evaluation/analyze",
        json={
            "case_id": "CASE_api",
            "case_status": "CLOSED",
            "route_type": "FULL_HEARING",
            "risk_level": "HIGH",
            "approval_decision": "APPROVE",
            "adjudication_draft": {},
            "approved_plan": {},
            "action_records": [],
            "evidence_summary": {},
            "policy_summary": {},
        },
        headers=_headers(),
    )

    assert all(
        response.status_code == 200
        for response in [intake, evidence, hearing, panel, copilot, evaluation]
    )
    assert intake.json()["admission_recommendation"] == "ACCEPTED"
    assert evidence.json()["liability_determined"] is False
    assert hearing.json()["non_final"] is True
    assert panel.json()["approval_performed"] is False
    assert copilot.json()["execution_triggered"] is False
    assert evaluation.json()["online_case_mutated"] is False
