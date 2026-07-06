from __future__ import annotations

from app.agents.presiding_judge.round_workflow import HearingRoundTurnWorkflow
from app.harness.prompt_composer import PromptRepository
from app.schemas import HearingRoundTurnRequest, HearingRoundTurnResult


class FakeRoundModelRunner:
    def __init__(self) -> None:
        self.calls: list[dict[str, object]] = []

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

        class Generation:
            value = output_type(
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

        return Generation()


def request() -> HearingRoundTurnRequest:
    return HearingRoundTurnRequest(
        case_id="CASE_COURT",
        workflow_id="WORKFLOW_COURT",
        order_id="ORDER_COURT",
        after_sale_id="AS_COURT",
        logistics_id="LOG_COURT",
        dispute_type="SIGNED_NOT_RECEIVED",
        title="物流显示签收但用户称未收到",
        case_description="用户称物流显示已签收但本人未收到包裹，商家称已正常发货并有签收记录。",
        risk_level="HIGH",
        round_no=1,
        dossier_version=2,
        final_round=False,
        round_status="COMPLETED",
        round_summary_json='{"trigger":"BOTH_PARTIES_SUBMITTED"}',
        party_submissions=[
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
    )


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
