from __future__ import annotations

from app.agents.presiding_judge.round_workflow import HearingRoundTurnWorkflow
from app.harness.prompt_composer import PromptRepository
from app.schemas import HearingRoundTurnRequest, HearingRoundTurnResult


class FakeRoundModelRunner:
    def __init__(self, *, generated: HearingRoundTurnResult | None = None) -> None:
        self.calls: list[dict[str, object]] = []
        self.generated = generated

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
    assert "<untrusted_case_data>" in user_prompt


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


def test_final_round_is_guarded_to_final_draft_path() -> None:
    runner = FakeRoundModelRunner(
        generated=HearingRoundTurnResult(
            speaker_role="JUDGE",
            message_text="第三轮之后还需要双方继续补充更多材料。",
            round_summary="模型误把第三轮当成中间轮。",
            questions_for_user=["继续补充。"],
            questions_for_merchant=["继续补充。"],
            court_event_type="JUDGE_NEXT_QUESTIONS_READY",
            round_no=3,
            next_round_no=None,
            final_draft_required=False,
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
    assert "非最终裁决草案" in result.message_text
    assert "后续确认" in result.message_text
    assert "平台审核员" not in result.message_text
    assert "终审" not in result.message_text
