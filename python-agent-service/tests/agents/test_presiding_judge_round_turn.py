# 文件作用：自动化测试文件，验证 test_presiding_judge_round_turn 相关模块的行为、契约或页面布局。

from __future__ import annotations

from app.agents.presiding_judge.round_workflow import HearingRoundTurnWorkflow
from app.harness.prompt_composer import PromptRepository
from app.schemas import (
    HearingRoundTurnRequest,
    HearingRoundTurnResult,
    JuryReviewDimension,
    JuryReviewFinding,
    JuryReviewReport,
)


class FakeRoundModelRunner:
    # 所属模块：Agent 角色能力 > test_presiding_judge_round_turn；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 符合 Schema 的角色分析结果。
    # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
    def __init__(self, *, generated: HearingRoundTurnResult | None = None) -> None:
        self.calls: list[dict[str, object]] = []
        self.generated = generated

    # 所属模块：Agent 角色能力 > test_presiding_judge_round_turn；函数角色：类/闭包内部方法。
    # 具体功能：`invoke_structured` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`self.calls.append`、`Generation`、`output_type`。
    # 上下游：上游为 受治理的案件上下文和角色提示词；下游为 协作调用 `self.calls.append`、`Generation`、`output_type`。
    # 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
    def invoke_structured(
        self,
        *,
        node_name,
        case_data,
        output_type,
        context_pack=None,
        agent_context=None,
        prompt_profile_id=None,
        **_,
    ):
        self.calls.append(
            {
                "node_name": node_name,
                "case_data": case_data,
                "context_pack": context_pack,
                "agent_context": agent_context,
                "prompt_profile_id": prompt_profile_id,
            }
        )

        if node_name == "jury_review":
            generated = JuryReviewReport(
                public_message="本次 V1 已完成六维复核，法官形成 V2 时应逐项回应签收核验风险。",
                reviewed_proposal=case_data["reviewed_proposal"],
                summary="统一评审员已完成六项非最终复核。",
                risk_level="MEDIUM",
                confidence_score=82,
                findings=[
                    JuryReviewFinding(
                        dimension=dimension,
                        severity="LOW",
                        assessment=f"已复核{dimension.value}。",
                        basis=["基于冻结庭审材料。"],
                    )
                    for dimension in JuryReviewDimension
                ],
                recommendations=["法官草案应逐项回应第三轮复核方向。"],
                review_notes="评审意见只补充风险和遗漏，不构成最终裁决。",
            )
        else:
            generated = self.generated or output_type(
            speaker_role="JUDGE",
            message_text="第一轮事实陈述已封存。第二轮请用户补充签收现场情况，请商家补充物流交接记录。",
            round_summary="双方第一轮围绕物流显示签收但用户称未收到包裹展开陈述。",
            questions_for_user=["请补充签收现场、快递柜或驿站沟通记录。"],
            questions_for_merchant=["请补充物流交接、签收凭证和发货履约记录。"],
            court_event_type="JUDGE_NEXT_QUESTIONS_READY",
            round_no=1,
            next_round_no=2,
            final_draft_required=False,
            prompt_version="hearing-round-turn-v1",
            model="fake-round-model",
            )

        class Generation:
            value = generated
            model = "fake-round-model"

        return Generation()


# 所属模块：Agent 角色能力 > test_presiding_judge_round_turn；函数角色：模块公开业务函数。
# 具体功能：`request` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`base.update`、`HearingRoundTurnRequest`。
# 上下游：上游为 本文件的 `test_round_turn_workflow_uses_context_pack_and_returns_judge_message`、`test_round_turn_context_can_include_execution_tool_intentions_without_backend_names`、`test_open_round_is_guarded_as_judge_opening_instead_of_sealed_turn`、`test_final_round_is_guarded_to_final_draft_path`；下游为 协作调用 `base.update`、`HearingRoundTurnRequest`。
# 系统意义：该函数在系统中的业务边界是：服从角色权限、上下文范围和非最终结论边界。
def request(**overrides) -> HearingRoundTurnRequest:
    base = {
        "case_id": "CASE_COURT",
        "workflow_id": "WORKFLOW_COURT",
        "order_id": "ORDER_COURT",
        "after_sale_id": "AS_COURT",
        "logistics_id": "LOG_COURT",
        "dispute_type": "SIGNED_NOT_RECEIVED",
        "title": "物流显示签收但用户称未收到包裹",
        "case_description": "用户称物流显示已签收但本人未收到包裹，商家称已正常发货并有签收记录。",
        "risk_level": "HIGH",
        "round_no": 1,
        "dossier_version": 2,
        "final_round": False,
        "round_status": "COMPLETED",
        "round_summary_json": '{"trigger":"BOTH_PARTIES_SUBMITTED"}',
        "courtroom_context": {
            "schema_version": "hearing_bootstrap_dossier.v1",
            "intake_dossier": {
                "case_story": "用户称物流显示已签收但本人未收到包裹。",
                "disputed_facts": [
                    {
                        "fact": "用户是否实际收到商品",
                        "user_position": "用户称未收到",
                        "merchant_position": "商家称物流已签收",
                    }
                ],
            },
            "evidence_dossier": {
                "fact_evidence_matrix": [
                    {
                        "fact": "物流显示已签收",
                        "supporting_evidence": ["EVIDENCE_LOGISTICS"],
                        "evidence_strength": "MEDIUM",
                    }
                ],
                "overall_confidence_score": 76,
            },
            "jury_a2a_notes": [
                {
                    "message_type": "JURY_SILENT_NOTE",
                    "payload": {"judge_attention": ["签收人身份仍需关注"]},
                }
            ],
        },
        "party_submissions": [
            {
                "participant_role": "USER",
                "participant_id": "user-local",
                "submission_source": "PARTY_ACTION",
                "submission_json": '{"statement":"用户称物流显示签收但本人未收到包裹。"}',
            },
            {
                "participant_role": "MERCHANT",
                "participant_id": "merchant-local",
                "submission_source": "PARTY_ACTION",
                "submission_json": '{"statement":"商家称已正常发货并有物流签收记录。"}',
            },
        ],
    }
    base.update(overrides)
    return HearingRoundTurnRequest(**base)


# 所属模块：Agent 角色能力 > test_presiding_judge_round_turn；函数角色：回归测试用例。
# 具体功能：`test_hearing_round_turn_prompt_is_registered_with_harness_fragments` 验证模型提示词在固定案例中的输出、边界和失败行为；关键协作调用：`render`、`HearingRoundTurnResult.model_json_schema`、`system_prompt.lower`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 协作调用 `render`、`HearingRoundTurnResult.model_json_schema`、`system_prompt.lower`、`PromptRepository`。
# 系统意义：固定“Agent 角色能力 > test_presiding_judge_round_turn”的可观察契约，防止后续重构改变业务结果。
def test_hearing_round_turn_prompt_is_registered_with_harness_fragments() -> None:
    system_prompt, user_prompt = PromptRepository().render(
        "hearing_round_turn",
        {"case_id": "CASE_COURT"},
        HearingRoundTurnResult.model_json_schema(),
    )

    assert "Common AI Native harness safety boundary" in system_prompt
    assert "Harness business code localization rules" in system_prompt
    assert "Harness case narration rules" in system_prompt
    assert "presiding judge" in system_prompt.lower()
    assert "三轮结构化庭审" in system_prompt
    assert "JUDGE_OPENING_READY" in system_prompt
    assert "庭审刚打开" in system_prompt
    assert "方案确认轮" in system_prompt
    assert "双方一致" in system_prompt
    assert "不是和解协议" in system_prompt
    assert "review_focus_signal" in system_prompt
    assert "不直接采纳" in system_prompt
    assert "<untrusted_case_data>" in user_prompt


# 所属模块：Agent 角色能力 > test_presiding_judge_round_turn；函数角色：回归测试用例。
# 具体功能：`test_round_turn_workflow_uses_context_pack_and_returns_judge_message` 验证案件与会话上下文在固定案例中的输出、边界和失败行为；关键协作调用：`FakeRoundModelRunner`、`run`、`next`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `request`。
# 系统意义：固定“Agent 角色能力 > test_presiding_judge_round_turn”的可观察契约，防止后续重构改变业务结果。
def test_round_turn_workflow_uses_context_pack_and_returns_judge_message() -> None:
    runner = FakeRoundModelRunner()

    result = HearingRoundTurnWorkflow(model_runner=runner).run(request())

    assert result.speaker_role == "JUDGE"
    assert result.round_no == 1
    assert result.next_round_no == 2
    assert result.final_draft_required is False
    assert "第一轮事实陈述已封存" in result.message_text
    assert runner.calls[0]["node_name"] == "hearing_round_turn"
    context_pack = runner.calls[0]["context_pack"]
    assert context_pack is not None
    assert context_pack.node_name == "hearing_round_turn"
    canonical = next(
        section for section in context_pack.sections if section.name == "canonical_case_dossier"
    )
    evidence = next(
        section for section in context_pack.sections if section.name == "actor_visible_evidence"
    )
    round_policy = next(
        section for section in context_pack.sections if section.name == "round_control_policy"
    )
    jury_notes = next(
        section for section in context_pack.sections if section.name == "jury_a2a_notes"
    )
    assert "用户称物流显示已签收但本人未收到包裹" in canonical.content
    assert "fact_evidence_matrix" in evidence.content
    assert "EVIDENCE_LOGISTICS" in evidence.content
    assert "签收人身份仍需关注" in jury_notes.content
    assert "方案确认轮" in round_policy.content
    assert "双方一致" in round_policy.content
    assert "不是和解协议" in round_policy.content


# 所属模块：Agent 角色能力 > test_presiding_judge_round_turn；函数角色：回归测试用例。
# 具体功能：`test_round_turn_context_can_include_execution_tool_intentions_without_backend_names` 验证案件与会话上下文在固定案例中的输出、边界和失败行为；关键协作调用：`FakeRoundModelRunner`、`run`、`next`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `request`。
# 系统意义：固定“Agent 角色能力 > test_presiding_judge_round_turn”的可观察契约，防止后续重构改变业务结果。
def test_round_turn_context_can_include_execution_tool_intentions_without_backend_names() -> None:
    runner = FakeRoundModelRunner()

    HearingRoundTurnWorkflow(model_runner=runner).run(
        request(
            courtroom_context={
                "execution_tool_declarations": [
                    {
                        "action_type": "REFUND",
                        "tool_name": "after_sale_tool",
                        "operation": "refund",
                        "display_name": "模拟退款",
                        "description": "仅在平台审核通过后模拟退款动作，不直接调用真实支付下游。",
                        "risk_level": "HIGH",
                        "simulated": True,
                        "requires_approved_plan": True,
                    }
                ]
            }
        )
    )

    context_pack = runner.calls[0]["context_pack"]
    execution_tools = next(
        section
        for section in context_pack.sections
        if section.name == "execution_tool_intentions"
    )

    assert execution_tools.trust_level == "java_tool_catalog"
    assert "ONLY_PROPOSE_EXECUTION_INTENT" in execution_tools.content
    assert "REFUND" in execution_tools.content
    assert "不得直接执行" in execution_tools.content
    assert "tool_name" not in execution_tools.content
    assert "after_sale_tool" not in execution_tools.content
    assert "operation" not in execution_tools.content


# 所属模块：Agent 角色能力 > test_presiding_judge_round_turn；函数角色：回归测试用例。
# 具体功能：`test_open_round_is_guarded_as_judge_opening_instead_of_sealed_turn` 校验庭审轮次的 Schema、权限和阶段约束，拒绝越权或不一致数据；关键协作调用：`FakeRoundModelRunner`、`run`、`next`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `request`。
# 系统意义：固定“Agent 角色能力 > test_presiding_judge_round_turn”的可观察契约，防止后续重构改变业务结果。
def test_open_round_is_guarded_as_judge_opening_instead_of_sealed_turn() -> None:
    runner = FakeRoundModelRunner(
        generated=HearingRoundTurnResult(
            speaker_role="JUDGE",
            message_text="第一轮陈述已封存，下一轮继续补充。",
            round_summary="模型误把刚打开的庭审轮次当成已封存轮次。",
            court_event_type="JUDGE_NEXT_QUESTIONS_READY",
            round_no=1,
            next_round_no=2,
            final_draft_required=False,
            prompt_version="hearing-round-turn-v1",
            model="fake-round-model",
        )
    )

    result = HearingRoundTurnWorkflow(model_runner=runner).run(
        request(
            round_status="OPEN",
            round_summary_json="{}",
            party_submissions=[],
            courtroom_context={
                "courtroom_opening_messages": [
                    {
                        "sender_role": "JUDGE",
                        "content": "小法庭现在开庭。根据接待室卷宗和证据证明矩阵，请双方围绕签收争议说明。",
                    }
                ]
            },
        )
    )

    assert result.court_event_type == "JUDGE_OPENING_READY"
    assert result.round_no == 1
    assert result.next_round_no == 1
    assert result.final_draft_required is False
    assert "开庭" in result.message_text
    assert "证据证明矩阵" in result.message_text
    assert "已封存" not in result.message_text
    assert "已入卷" not in result.message_text
    context_pack = runner.calls[0]["context_pack"]
    current_turn = next(
        section for section in context_pack.sections if section.name == "current_turn"
    )
    assert "ROUND_OPENED" in current_turn.content


# 所属模块：Agent 角色能力 > test_presiding_judge_round_turn；函数角色：回归测试用例。
# 具体功能：`test_final_round_is_guarded_to_final_draft_path` 校验庭审轮次的 Schema、权限和阶段约束，拒绝越权或不一致数据；关键协作调用：`FakeRoundModelRunner`、`run`、`HearingRoundTurnResult`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `request`。
# 系统意义：固定“Agent 角色能力 > test_presiding_judge_round_turn”的可观察契约，防止后续重构改变业务结果。
def test_final_round_is_guarded_to_final_draft_path() -> None:
    proposal = (
        "非最终拟处理方案 V1：在人工核验签收人身份和投递位置后，"
        "若无法证明用户本人收货，建议支持退款；否则维持履约完成判断"
    )
    runner = FakeRoundModelRunner(
        generated=HearingRoundTurnResult(
            speaker_role="JUDGE",
            message_text=(
                f"第三轮陈述已封存。本庭提出{proposal}。"
                "该非最终方案现在交由统一 AI 评审员复核。"
            ),
            round_summary="第三轮已封存，法官形成评审前拟处理方案 V1。",
            court_event_type="FINAL_DRAFT_REQUIRED",
            round_no=3,
            next_round_no=None,
            final_draft_required=True,
            final_proposed_resolution=proposal,
            prompt_version="hearing-round-turn-v1",
            model="fake-round-model",
        )
    )

    result = HearingRoundTurnWorkflow(model_runner=runner).run(
        request(round_no=3, final_round=True)
    )

    assert result.court_event_type == "FINAL_DRAFT_REQUIRED"
    assert result.round_no == 3
    assert result.next_round_no is None
    assert result.final_draft_required is True
    assert result.questions_for_user == []
    assert result.questions_for_merchant == []
    assert proposal in result.message_text
    assert result.final_proposed_resolution == proposal
    assert "评审" in result.message_text
    assert "平台审核员" not in result.message_text
    assert "终审" not in result.message_text
    assert result.jury_review_report is not None
    assert result.jury_review_report.reviewed_proposal == proposal
    assert {item.dimension for item in result.jury_review_report.findings} == set(
        JuryReviewDimension
    )


# 所属模块：Agent 角色能力 > test_presiding_judge_round_turn；函数角色：回归测试用例。
# 具体功能：`test_final_round_party_opinions_become_review_focus_signal` 验证庭审轮次在固定案例中的输出、边界和失败行为；关键协作调用：`FakeRoundModelRunner`、`run`、`HearingRoundTurnResult`。
# 上下游：上游为 受治理的案件上下文和角色提示词；下游为 本文件的 `request`。
# 系统意义：固定“Agent 角色能力 > test_presiding_judge_round_turn”的可观察契约，防止后续重构改变业务结果。
def test_final_round_party_opinions_become_review_focus_signal() -> None:
    proposal = "非最终拟处理方案 V1：建议在人工复核签收证据后决定是否支持退款"
    runner = FakeRoundModelRunner(
        generated=HearingRoundTurnResult(
            speaker_role="JUDGE",
            message_text=(
                f"第三轮陈述已封存。本庭提出{proposal}。"
                "该非最终方案现在交由统一 AI 评审员复核。"
            ),
            round_summary="第三轮双方围绕拟处理方向表达确认和异议。",
            court_event_type="FINAL_DRAFT_REQUIRED",
            round_no=3,
            next_round_no=None,
            final_draft_required=True,
            final_proposed_resolution=proposal,
            prompt_version="hearing-round-turn-v1",
            model="fake-round-model",
        )
    )

    result = HearingRoundTurnWorkflow(model_runner=runner).run(
        request(
            round_no=3,
            final_round=True,
            party_submissions=[
                {
                    "participant_role": "USER",
                    "participant_id": "user-local",
                    "submission_source": "PARTY_ACTION",
                    "submission_json": '{"statement":"我认可退款方向，但担心签收人身份还没有核验清楚。"}',
                },
                {
                    "participant_role": "MERCHANT",
                    "participant_id": "merchant-local",
                    "submission_source": "PARTY_ACTION",
                    "submission_json": '{"statement":"不同意退款，物流签收记录已经足以证明商家完成履约。"}',
                },
            ],
        )
    )

    assert result.final_draft_required is True
    assert result.review_focus_signal == [
        "用户认可退款方向，但要求复核签收人身份是否已核验清楚。",
        "商家不同意退款，主张物流签收记录足以证明已履约。",
    ]
    assert proposal in result.message_text
    assert "直接采纳用户意见" not in result.message_text
