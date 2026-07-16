# 文件作用：自动化测试文件，验证 test_final_agents 相关模块的行为、契约或页面布局。

from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.agents.critics import CriticAgent, build_default_critics
from app.agents.deliberation_panel import DeliberationPanel
from app.agents.dispute_intake_officer import DisputeIntakeOfficer
from app.agents.evaluation_agent import EvaluationAgent
from app.agents.evidence_clerk import EvidenceClerk
from app.agents.profiles import final_agent_profiles
from app.agents.review_copilot import ReviewCopilot
from app.harness.guardrails import GuardrailViolation
from app.harness.validation import CitationValidationError
from app.schemas import (
    CriticDraft,
    CriticSeverity,
    CriticType,
    DeliberationRequest,
    DossierEvidenceItem,
    DisputeIntakeRequest,
    EvaluationAnalysisResult,
    EvaluationFinding,
    EvaluationMetricScores,
    EvidenceBuildRequest,
    FrozenDeliberationInput,
    ReviewCopilotAnswer,
    ReviewCopilotRequest,
    ReviewStatement,
)


# 所属模块：Agent 角色能力 > test_final_agents；函数角色：回归测试用例。
# 具体功能：`test_room_agent_contract_exposes_final_intake_fields` 验证接待结果的稳定公共字段。
# 上下游：上游为受治理的案件上下文和角色提示词；下游为接待 API。
# 系统意义：固定“Agent 角色能力 > test_final_agents”的可观察契约，防止后续重构改变业务结果。
def test_room_agent_contract_exposes_final_intake_fields() -> None:
    from app.schemas import DisputeIntakeResult

    assert set(DisputeIntakeResult.model_fields) >= {
        "admissible",
        "initiator_role",
        "order_reference",
        "after_sales_reference",
        "logistics_reference",
        "party_claims",
        "requested_outcome",
        "initial_risk_signals",
        "admission_recommendation",
        "room_utterance",
    }

# 所属模块：Agent 角色能力 > test_final_agents；函数角色：回归测试用例。
# 具体功能：`test_final_profiles_are_default_deny_and_cannot_approve_or_execute` 验证本阶段状态在固定案例中的输出、边界和失败行为；关键协作调用：`final_agent_profiles`、`profiles.values`、`profile.authorizes_tool`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 协作调用 `final_agent_profiles`、`profiles.values`、`profile.authorizes_tool`。
# 系统意义：固定“Agent 角色能力 > test_final_agents”的可观察契约，防止后续重构改变业务结果。
def test_final_profiles_are_default_deny_and_cannot_approve_or_execute() -> None:
    profiles = final_agent_profiles()

    assert set(profiles) == {
        "dispute_intake_officer",
        "evidence_clerk",
        "presiding_judge",
        "evidence_critic",
        "rule_critic",
        "risk_critic",
        "remedy_critic",
        "fairness_critic",
        "review_copilot",
        "evaluation_agent",
    }
    for profile in profiles.values():
        assert not profile.authorizes_tool("review.approve")
        assert not profile.authorizes_tool("refund.execute")
        assert not profile.authorizes_tool("case.close")


# 所属模块：Agent 角色能力 > test_final_agents；函数角色：回归测试用例。
# 具体功能：`test_dispute_intake_officer_reuses_intake_analysis_without_deciding` 验证接待信息在固定案例中的输出、边界和失败行为；关键协作调用：`DisputeIntakeOfficer`、`IntakeWorkflow`、`DisputeIntakeRequest`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `analyze`。
# 系统意义：固定“Agent 角色能力 > test_final_agents”的可观察契约，防止后续重构改变业务结果。
def test_dispute_intake_officer_reuses_intake_analysis_without_deciding() -> None:
    class IntakeWorkflow:
        # 所属模块：Agent 角色能力 > test_final_agents；函数角色：类/闭包内部方法。
        # 具体功能：`analyze` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`IntakeAnalysisOutput`。
        # 上下游：上游为 本文件的 `test_dispute_intake_officer_reuses_intake_analysis_without_deciding`、`test_presiding_judge_only_runs_in_hearing_states_and_stays_non_final`、`test_evaluation_agent_is_closed_case_offline_only`；下游为 协作调用 `IntakeAnalysisOutput`。
        # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
        def analyze(self, request, _context):
            from app.schemas import IntakeAnalysisOutput

            return IntakeAnalysisOutput(
                case_type="DISPUTE",
                dispute_type="SIGNED_NOT_RECEIVED",
                risk_level="HIGH",
                potential_dispute=True,
                missing_slots=[],
                title="Signed but not received",
                normalized_description=request.description,
            )

    officer = DisputeIntakeOfficer(IntakeWorkflow())
    result = officer.analyze(
        DisputeIntakeRequest(
            submission_id="SUBMISSION_1",
            initiator_role="USER",
            raw_text=(
                "The parcel is marked delivered but was not received. "
                "I request a refund."
            ),
            order_reference="ORDER_1",
            channel="WEB",
        ),
        object(),
        case_state="SUBMITTED",
    )

    assert result.is_potential_dispute is True
    assert result.admissibility_recommendation == "ACCEPTED"
    assert result.initiator == "USER"
    assert result.claims[0].source_ref == "SUBMISSION_1"
    assert result.requested_remedy == "REFUND"
    assert result.liability_determined is False

    class NonDisputeWorkflow:
        # 所属模块：Agent 角色能力 > test_final_agents；函数角色：类/闭包内部方法。
        # 具体功能：`analyze` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`IntakeAnalysisOutput`。
        # 上下游：上游为 本文件的 `test_dispute_intake_officer_reuses_intake_analysis_without_deciding`、`test_presiding_judge_only_runs_in_hearing_states_and_stays_non_final`、`test_evaluation_agent_is_closed_case_offline_only`；下游为 协作调用 `IntakeAnalysisOutput`。
        # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
        def analyze(self, request, _context):
            from app.schemas import IntakeAnalysisOutput

            return IntakeAnalysisOutput(
                case_type="INQUIRY",
                risk_level="LOW",
                potential_dispute=False,
                missing_slots=[],
                title="Order status inquiry",
                normalized_description=request.description,
            )

    transferred = DisputeIntakeOfficer(NonDisputeWorkflow()).analyze(
        DisputeIntakeRequest(
            submission_id="SUBMISSION_2",
            initiator_role="USER",
            raw_text="Where can I see the tracking page?",
            order_reference="ORDER_2",
            channel="WEB",
        ),
        object(),
        case_state="SUBMITTED",
    )
    assert transferred.admissibility_recommendation == "NOT_ADMISSIBLE"
    assert transferred.next_step == "TRANSFER"

    with pytest.raises(PermissionError):
        officer.analyze(
            DisputeIntakeRequest(
                submission_id="SUBMISSION_1",
                initiator_role="USER",
                raw_text="The parcel was not received.",
                order_reference="ORDER_1",
                channel="WEB",
            ),
            object(),
            case_state="CLOSED",
        )


# 所属模块：Agent 角色能力 > test_final_agents；函数角色：回归测试用例。
# 具体功能：`test_evidence_clerk_versions_and_preserves_evidence_without_deciding` 验证当前可见证据在固定案例中的输出、边界和失败行为；关键协作调用：`EvidenceClerk`、`EvidenceBuildRequest`、`clerk.build`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 协作调用 `EvidenceClerk`、`EvidenceBuildRequest`、`clerk.build`、`DossierEvidenceItem`。
# 系统意义：固定“Agent 角色能力 > test_final_agents”的可观察契约，防止后续重构改变业务结果。
def test_evidence_clerk_versions_and_preserves_evidence_without_deciding() -> None:
    clerk = EvidenceClerk()
    request = EvidenceBuildRequest(
        case_id="CASE_evidence",
        case_version=2,
        submission_version=3,
        current_dossier_version=4,
        party_claims=[
            {
                "claim_id": "CLAIM_receipt",
                "party_type": "USER",
                "statement": "The parcel was not received.",
            },
            {
                "claim_id": "CLAIM_damage",
                "party_type": "USER",
                "statement": "The parcel was damaged.",
            },
        ],
        evidence=[
            DossierEvidenceItem(
                evidence_id="EVIDENCE_tracking",
                evidence_type="LOGISTICS",
                source_type="PLATFORM",
                content="Carrier scan says delivered at 10:30.",
                parsed_text="Delivered scan: 10:30",
                agent_summary="Carrier recorded a delivery scan.",
                occurred_at="2026-07-01T10:30:00+08:00",
                related_claim_ids=["CLAIM_receipt"],
            ),
            DossierEvidenceItem(
                evidence_id="EVIDENCE_tracking_copy",
                evidence_type="LOGISTICS",
                source_type="PLATFORM",
                content="Carrier scan says delivered at 10:30.",
                related_claim_ids=["CLAIM_receipt"],
            ),
            DossierEvidenceItem(
                evidence_id="EVIDENCE_statement",
                evidence_type="PARTY_STATEMENT",
                source_type="USER",
                content="The parcel was not received.",
                related_claim_ids=["CLAIM_receipt"],
                parser_warning="Image quality prevented OCR verification.",
            ),
            DossierEvidenceItem(
                evidence_id="EVIDENCE_tracking_conflict",
                evidence_type="LOGISTICS",
                source_type="PLATFORM",
                content="Carrier later marked the delivery scan as cancelled.",
                related_claim_ids=["CLAIM_receipt"],
            ),
        ],
    )

    result = clerk.build(request)

    assert result.dossier_version == 5
    assert [item.original_ref for item in result.evidence_catalog] == [
        "EVIDENCE_tracking",
        "EVIDENCE_tracking_copy",
        "EVIDENCE_statement",
        "EVIDENCE_tracking_conflict",
    ]
    assert result.source_citations == [
        "EVIDENCE_tracking",
        "EVIDENCE_tracking_copy",
        "EVIDENCE_statement",
        "EVIDENCE_tracking_conflict",
    ]
    assert result.timeline[0].source_refs == ["EVIDENCE_tracking"]
    assert result.duplicate_groups == [
        ["EVIDENCE_tracking", "EVIDENCE_tracking_copy"]
    ]
    assert result.parser_warnings == [
        "EVIDENCE_statement: Image quality prevented OCR verification."
    ]
    assert result.claim_issue_evidence_matrix[0].evidence_refs == [
        "EVIDENCE_tracking",
        "EVIDENCE_tracking_copy",
        "EVIDENCE_statement",
        "EVIDENCE_tracking_conflict",
    ]
    assert result.conflicts == [
        "LOGISTICS evidence contains distinct source assertions"
    ]
    assert result.gaps == ["No evidence linked to claim CLAIM_damage"]
    assert result.liability_determined is False
    assert result.remedy_recommended is False
    assert {
        item.recommendation for item in result.verification_recommendations
    } <= {
        "VERIFIED",
        "PLAUSIBLE",
        "SUSPICIOUS",
        "REJECTED",
        "NEEDS_HUMAN_REVIEW",
    }
    assert result.authenticity_guaranteed is False
    assert "not an authenticity guarantee" in result.authenticity_disclaimer
    assert result.deterministic_evidence_refs == result.source_citations
    assert result.visibility_warnings


# 所属模块：Agent 角色能力 > test_final_agents；函数角色：模块私有业务函数。
# 具体功能：`_frozen_input` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`FrozenDeliberationInput`。
# 上下游：上游为 本文件的 `test_all_critics_receive_the_same_frozen_input_and_blocker_is_preserved`、`test_panel_contract_rejects_unfrozen_room_messages_or_newer_evidence`、`test_failed_or_timed_out_critic_requires_human_review`、`test_critic_rejects_output_for_another_frozen_snapshot`；下游为 协作调用 `FrozenDeliberationInput`。
# 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
def _frozen_input() -> FrozenDeliberationInput:
    return FrozenDeliberationInput(
        case_id="CASE_panel",
        case_snapshot_version=7,
        dossier_version=4,
        adjudication_draft_version=2,
        rule_version="RULE_2026_01",
        remedy_plan_candidate_version=1,
        frozen_dossier_snapshot={"evidence_refs": ["EVIDENCE_tracking"]},
        frozen_draft_snapshot={
            "recommended_outcome": "REQUEST_MORE_EVIDENCE"
        },
        frozen_at_event_sequence=17,
    )


# 所属模块：Agent 角色能力 > test_final_agents；函数角色：回归测试用例。
# 具体功能：`test_all_critics_receive_the_same_frozen_input_and_blocker_is_preserved` 验证合议质疑结果在固定案例中的输出、边界和失败行为；关键协作调用：`build_default_critics`、`run`、`fingerprints.append`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_frozen_input`。
# 系统意义：固定“Agent 角色能力 > test_final_agents”的可观察契约，防止后续重构改变业务结果。
def test_all_critics_receive_the_same_frozen_input_and_blocker_is_preserved() -> None:
    fingerprints: list[str] = []

    # 所属模块：Agent 角色能力 > test_final_agents；函数角色：类/闭包内部方法。
    # 具体功能：`evaluator` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`fingerprints.append`、`CriticDraft`。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 协作调用 `fingerprints.append`、`CriticDraft`。
    # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
    def evaluator(
        critic_type: CriticType,
        frozen_input: FrozenDeliberationInput,
        fingerprint: str,
    ) -> CriticDraft:
        fingerprints.append(fingerprint)
        severity = (
            CriticSeverity.BLOCKER
            if critic_type is CriticType.EVIDENCE
            else CriticSeverity.NONE
        )
        return CriticDraft(
            severity=severity,
            findings=(
                ["The delivery conflict remains unresolved."]
                if severity is CriticSeverity.BLOCKER
                else []
            ),
            blocking_issues=(
                ["EVIDENCE_CONFLICT"]
                if severity is CriticSeverity.BLOCKER
                else []
            ),
            recommended_revision=(
                "REQUEST_MORE_EVIDENCE"
                if severity is CriticSeverity.BLOCKER
                else None
            ),
        )

    critics = build_default_critics(evaluator)
    report = DeliberationPanel(critics).run(
        DeliberationRequest(
            frozen_input=_frozen_input(),
            trigger_reasons=["EVIDENCE_CONFLICT"],
        )
    )

    assert len(set(fingerprints)) == 1
    assert report.panel_result == "REVISION_REQUIRED"
    assert report.revision_required is True
    assert report.major_risks == ["EVIDENCE_CONFLICT"]
    assert report.critic_reports[0].severity is CriticSeverity.BLOCKER
    assert report.trigger_reasons == ["EVIDENCE_CONFLICT"]
    assert report.approval_performed is False
    assert report.execution_triggered is False


# 所属模块：Agent 角色能力 > test_final_agents；函数角色：回归测试用例。
# 具体功能：`test_panel_contract_rejects_unfrozen_room_messages_or_newer_evidence` 验证当前可见证据在固定案例中的输出、边界和失败行为；关键协作调用：`model_dump`、`pytest.raises`、`FrozenDeliberationInput.model_validate`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_frozen_input`。
# 系统意义：固定“Agent 角色能力 > test_final_agents”的可观察契约，防止后续重构改变业务结果。
def test_panel_contract_rejects_unfrozen_room_messages_or_newer_evidence() -> None:
    payload = _frozen_input().model_dump(mode="json")
    payload["room_messages"] = [{"sequence": 18, "content": "new assertion"}]
    payload["evidence_after_dossier_version"] = ["EVIDENCE_late"]

    with pytest.raises(ValidationError):
        FrozenDeliberationInput.model_validate(payload)


# 所属模块：Agent 角色能力 > test_final_agents；函数角色：回归测试用例。
# 具体功能：`test_failed_or_timed_out_critic_requires_human_review` 验证人工复核信息在固定案例中的输出、边界和失败行为；关键协作调用：`run`、`next`、`CriticDraft`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_frozen_input`。
# 系统意义：固定“Agent 角色能力 > test_final_agents”的可观察契约，防止后续重构改变业务结果。
def test_failed_or_timed_out_critic_requires_human_review() -> None:
    frozen_input = _frozen_input()

    # 所属模块：Agent 角色能力 > test_final_agents；函数角色：类/闭包内部方法。
    # 具体功能：`evaluator` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`CriticDraft`、`TimeoutError`。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 协作调用 `CriticDraft`、`TimeoutError`。
    # 系统意义：失败显式映射为 `TimeoutError`，避免错误状态被当成成功结果。
    def evaluator(
        critic_type: CriticType,
        _frozen_input: FrozenDeliberationInput,
        _fingerprint: str,
    ) -> CriticDraft:
        if critic_type is CriticType.RULE:
            raise TimeoutError("critic deadline exceeded")
        return CriticDraft(severity=CriticSeverity.NONE)

    report = DeliberationPanel(build_default_critics(evaluator)).run(
        DeliberationRequest(
            frozen_input=frozen_input,
            trigger_reasons=["CRITIC_REVIEW_REQUIRED"],
        )
    )

    rule_report = next(
        item for item in report.critic_reports if item.critic is CriticType.RULE
    )
    assert rule_report.status == "TIMED_OUT"
    assert report.panel_result == "MANUAL_REVIEW_REQUIRED"
    assert "RULE_CRITIC_UNAVAILABLE" in report.major_risks


# 所属模块：Agent 角色能力 > test_final_agents；函数角色：回归测试用例。
# 具体功能：`test_critic_rejects_output_for_another_frozen_snapshot` 验证合议质疑结果在固定案例中的输出、边界和失败行为；关键协作调用：`CriticAgent`、`critic.review`、`CriticDraft`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `_frozen_input`。
# 系统意义：固定“Agent 角色能力 > test_final_agents”的可观察契约，防止后续重构改变业务结果。
def test_critic_rejects_output_for_another_frozen_snapshot() -> None:
    # 所属模块：Agent 角色能力 > test_final_agents；函数角色：类/闭包内部方法。
    # 具体功能：`wrong_snapshot` 围绕冻结快照计算该函数独立负责的业务派生值；关键协作调用：`CriticDraft`。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 协作调用 `CriticDraft`。
    # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
    def wrong_snapshot(
        _critic_type: CriticType,
        _frozen_input: FrozenDeliberationInput,
        _fingerprint: str,
    ) -> CriticDraft:
        return CriticDraft(
            severity=CriticSeverity.NONE,
            frozen_input_fingerprint="wrong",
        )

    critic = CriticAgent(CriticType.EVIDENCE, wrong_snapshot)
    report = critic.review(_frozen_input())

    assert report.status == "FAILED"
    assert report.blocking_issues == ["FROZEN_INPUT_MISMATCH"]


# 所属模块：Agent 角色能力 > test_final_agents；函数角色：回归测试用例。
# 具体功能：`test_review_copilot_validates_frozen_refs_and_cannot_issue_a_decision` 校验人工复核信息的 Schema、权限和阶段约束，拒绝越权或不一致数据；关键协作调用：`ReviewCopilotAnswer`、`ReviewCopilot`、`ReviewCopilotRequest`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 协作调用 `ReviewCopilotAnswer`、`ReviewCopilot`、`ReviewCopilotRequest`、`copilot.query`。
# 系统意义：固定“Agent 角色能力 > test_final_agents”的可观察契约，防止后续重构改变业务结果。
def test_review_copilot_validates_frozen_refs_and_cannot_issue_a_decision() -> None:
    safe_answer = ReviewCopilotAnswer(
        answer="The draft relies on the carrier scan, while receipt remains disputed.",
        fact_refs=["EVIDENCE_tracking"],
        rule_refs=["RULE_2026_01"],
        draft_refs=["DRAFT_2"],
        deliberation_refs=["DELIBERATION_1"],
        uncertainties=["Actual receipt is unresolved."],
        suggested_review_focus=["Check proof of handover."],
        statements=[
            ReviewStatement(
                kind="FACT",
                text="The carrier recorded a delivery scan.",
                refs=["EVIDENCE_tracking"],
            ),
            ReviewStatement(
                kind="INFERENCE",
                text="The scan alone may not prove handover.",
                refs=["EVIDENCE_tracking", "RULE_2026_01"],
            ),
            ReviewStatement(
                kind="SUGGESTION",
                text="The reviewer can inspect proof of handover.",
                refs=["DRAFT_2"],
            ),
        ],
    )
    copilot = ReviewCopilot(lambda _request: safe_answer)
    request = ReviewCopilotRequest(
        review_id="REVIEW_1",
        case_id="CASE_review",
        review_packet_version=2,
        reviewer_role="PLATFORM_REVIEWER",
        question="Why is more evidence recommended?",
        available_fact_refs=["EVIDENCE_tracking"],
        available_rule_refs=["RULE_2026_01"],
        available_draft_refs=["DRAFT_2"],
        available_deliberation_refs=["DELIBERATION_1"],
    )

    answer = copilot.query(request)

    assert answer.approval_performed is False
    assert answer.execution_triggered is False
    assert [item.kind for item in answer.statements] == [
        "FACT",
        "INFERENCE",
        "SUGGESTION",
    ]

    bad_refs = safe_answer.model_copy(
        update={"fact_refs": ["EVIDENCE_not_in_packet"]}
    )
    with pytest.raises(CitationValidationError):
        ReviewCopilot(lambda _request: bad_refs).query(request)

    unsafe = safe_answer.model_copy(
        update={"answer": "This is the final decision: refund immediately."}
    )
    with pytest.raises(GuardrailViolation):
        ReviewCopilot(lambda _request: unsafe).query(request)


class _FakeEvaluationWorkflow:
    # 所属模块：Agent 角色能力 > test_final_agents；函数角色：类/闭包内部方法。
    # 具体功能：`analyze` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`EvaluationAnalysisResult`、`EvaluationMetricScores`、`EvaluationFinding`。
    # 上下游：上游为 本文件的 `test_dispute_intake_officer_reuses_intake_analysis_without_deciding`、`test_presiding_judge_only_runs_in_hearing_states_and_stays_non_final`、`test_evaluation_agent_is_closed_case_offline_only`；下游为 协作调用 `EvaluationAnalysisResult`、`EvaluationMetricScores`、`EvaluationFinding`。
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
            findings=[
                EvaluationFinding(
                    category="PROCESS_GAP",
                    severity="LOW",
                    summary="Test finding.",
                    supporting_references=[],
                )
            ],
            rule_gap_suggestions=[],
            improvement_suggestions=[],
            evaluator_model="test-model",
            prompt_version="evaluation-v1",
            latency_ms=1,
            token_usage=1,
        )


# 所属模块：Agent 角色能力 > test_final_agents；函数角色：回归测试用例。
# 具体功能：`test_evaluation_agent_is_closed_case_offline_only` 验证本阶段状态在固定案例中的输出、边界和失败行为；关键协作调用：`EvaluationAgent`、`_FakeEvaluationWorkflow`、`pytest.raises`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `analyze`。
# 系统意义：固定“Agent 角色能力 > test_final_agents”的可观察契约，防止后续重构改变业务结果。
def test_evaluation_agent_is_closed_case_offline_only() -> None:
    agent = EvaluationAgent(_FakeEvaluationWorkflow())
    closed_request = type(
        "Request",
        (),
        {"case_id": "CASE_closed", "case_status": "CLOSED"},
    )()

    with pytest.raises(PermissionError):
        agent.analyze(closed_request, object(), offline=False)

    online_request = type(
        "Request",
        (),
        {"case_id": "CASE_open", "case_status": "IN_REVIEW"},
    )()
    with pytest.raises(PermissionError):
        agent.analyze(online_request, object(), offline=True)

    result = agent.analyze(closed_request, object(), offline=True)
    assert result.online_case_mutated is False
    assert result.automatic_changes_applied is False
