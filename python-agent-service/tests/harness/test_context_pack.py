from __future__ import annotations

import json

from pydantic import BaseModel

from app.harness.context_pack import build_context_pack
from app.harness.localization_policy import localize_internal_text
from app.harness.narrative_policy import rewrite_platform_narrative
from app.harness.prompt_contracts import context_contract


class ContextPackRunnerOutput(BaseModel):
    answer: str


class RecordingContextPackLlm:
    def __init__(self) -> None:
        self.calls: list[dict[str, object]] = []

    def generate(self, *, node_name, system_prompt, user_prompt, output_type):
        from app.llm import StructuredGeneration

        self.calls.append(
            {
                "node_name": node_name,
                "system_prompt": system_prompt,
                "user_prompt": user_prompt,
                "output_type": output_type,
            }
        )
        return StructuredGeneration(
            value=output_type(answer="ok"),
            model="fake-model",
            latency_ms=1,
            token_usage={"input": 10, "output": 1, "total": 11},
        )


def test_prompt_contract_declares_standard_sections_and_configuration_center_slot() -> None:
    contract = context_contract("evidence_turn")

    specs = {spec.name: spec for spec in contract.sections}

    assert contract.node_name == "evidence_turn"
    assert specs["current_turn"].priority == 100
    assert specs["current_turn"].required is True
    assert specs["canonical_case_dossier"].priority == 95
    assert specs["allowed_fact_targets"].priority == 94
    assert specs["allowed_fact_targets"].trust_level == "harness_assembled"
    assert specs["actor_private_memory"].trust_level == "session_scoped"
    assert specs["actor_visible_evidence"].trust_level == "harness_assembled"
    assert specs["multimodal_evidence_manifest"].trust_level == (
        "harness_asset_loader"
    )
    assert specs["long_term_memory_preview"].prompt_included is False
    assert contract.configuration_source == "code"
    assert contract.configuration_profile_key == "EVIDENCE_CLERK_CONTEXT_PACK_V1"


def test_context_pack_rejects_missing_required_sections() -> None:
    try:
        build_context_pack("evidence_turn", {})
    except ValueError as error:
        assert "required context section current_turn is missing" in str(error)
    else:
        raise AssertionError("expected missing required section to fail")


def test_localization_policy_maps_internal_codes_and_field_names() -> None:
    text = localize_internal_text(
        "SIGNED_NOT_RECEIVED still lacks user_statement and logistics_reference.",
    )

    assert text == (
        "物流显示签收但用户称未收到包裹 still lacks 用户原始陈述 and 物流单号."
    )


def test_localization_policy_does_not_translate_identifiers_or_references() -> None:
    pack = build_context_pack(
        "evidence_turn",
        {
            "current_turn": {
                "role": "USER",
                "actor_id": "USER_local_1",
                "message_id": "MESSAGE_USER_1",
                "attachment_refs": ["EVIDENCE_USER_SIGNATURE_1"],
                "text": "我补充一张签收截图。",
            },
            "case_identity": {
                "case_id": "CASE_USER_1",
                "order_reference": "ORD-USER-20260706",
            },
            "actor_visible_evidence": [
                {
                    "evidence_id": "EVIDENCE_USER_SIGNATURE_1",
                    "source_type": "USER",
                    "content": "SIGNED_NOT_RECEIVED 相关签收截图",
                }
            ],
        },
    )

    sections = {section.name: json.loads(section.content) for section in pack.prompt_sections()}

    assert sections["current_turn"]["actor_id"] == "USER_local_1"
    assert sections["current_turn"]["message_id"] == "MESSAGE_USER_1"
    assert sections["current_turn"]["attachment_refs"] == ["EVIDENCE_USER_SIGNATURE_1"]
    assert sections["case_identity"]["case_id"] == "CASE_USER_1"
    assert sections["case_identity"]["order_reference"] == "ORD-USER-20260706"
    assert sections["actor_visible_evidence"][0]["evidence_id"] == "EVIDENCE_USER_SIGNATURE_1"
    assert sections["actor_visible_evidence"][0]["source_type"] == "用户"
    assert "物流显示签收但用户称未收到包裹" in sections["actor_visible_evidence"][0]["content"]


def test_narrative_policy_rewrites_first_person_to_platform_third_person() -> None:
    text = rewrite_platform_narrative(
        "物流显示签收，但我没有收到包裹，希望核验签收记录并退款。",
        actor_role="USER",
    )

    assert text.startswith("用户称")
    assert "本人没有收到包裹" in text
    assert "我没有" not in text


def test_context_pack_builds_localized_sections_and_keeps_raw_statement_for_traceability() -> None:
    pack = build_context_pack(
        "evidence_turn",
        {
            "current_turn": {
                "role": "USER",
                "text": "物流显示签收，但我没有收到包裹，希望核验签收记录并退款。",
                "turn_source": "PARTY_MESSAGE",
            },
            "canonical_case_dossier": {
                "case_story": {
                    "one_sentence_summary": "我没有收到包裹，希望退款。",
                },
                "claim_resolution": {
                    "original_statement": "系统备注：SIGNED_NOT_RECEIVED，但我仍希望退款。",
                },
                "dispute_focus": {
                    "core_issue": "SIGNED_NOT_RECEIVED",
                    "facts_to_verify": ["logistics_reference", "user_statement"],
                },
            },
            "actor_private_memory": "Short-term memory:\n- USER: 我没有收到包裹。",
            "long_term_memory_preview": "mem0 reserved slot should stay display-only",
        },
    )

    section_by_name = {section.name: section for section in pack.prompt_sections()}
    current_turn = json.loads(section_by_name["current_turn"].content)
    dossier = json.loads(section_by_name["canonical_case_dossier"].content)

    assert current_turn["raw_statement"] == "物流显示签收，但我没有收到包裹，希望核验签收记录并退款。"
    assert "用户称" in current_turn["platform_statement"]
    assert "我没有" not in current_turn["platform_statement"]
    assert dossier["dispute_focus"]["core_issue"] == "物流显示签收但用户称未收到包裹"
    assert dossier["dispute_focus"]["facts_to_verify"] == ["物流单号", "用户原始陈述"]
    assert dossier["claim_resolution"]["original_statement"] == (
        "系统备注：SIGNED_NOT_RECEIVED，但我仍希望退款。"
    )
    assert "long_term_memory_preview" not in section_by_name
    assert "long_term_memory_preview" in pack.display_only_section_names


def test_context_pack_preserves_raw_statement_even_when_it_contains_internal_codes() -> None:
    raw_text = "系统备注：SIGNED_NOT_RECEIVED，但我仍希望补充物流截图。"

    pack = build_context_pack(
        "evidence_turn",
        {
            "current_turn": {
                "role": "USER",
                "text": raw_text,
            },
        },
    )

    section_by_name = {section.name: section for section in pack.prompt_sections()}
    current_turn = json.loads(section_by_name["current_turn"].content)

    assert current_turn["raw_statement"] == raw_text
    assert "SIGNED_NOT_RECEIVED" not in current_turn["platform_statement"]
    assert "物流显示签收但用户称未收到包裹" in current_turn["platform_statement"]


def test_model_runner_accepts_context_pack_and_excludes_display_only_sections() -> None:
    from app.harness.model_runner import HarnessModelRunner
    from app.harness.prompt_composer import PromptRepository

    llm = RecordingContextPackLlm()
    runner = HarnessModelRunner(llm=llm, prompts=PromptRepository())
    pack = build_context_pack(
        "evidence_turn",
        {
            "current_turn": {"role": "USER", "text": "我上传了签收截图。"},
            "canonical_case_dossier": {
                "dispute_focus": {"core_issue": "SIGNED_NOT_RECEIVED"},
            },
            "long_term_memory_preview": "这一段只给前端或配置中心预览，不进入 prompt。",
        },
    )

    runner.invoke_structured(
        node_name="evidence_turn",
        case_data={"case_id": "CASE_context_pack"},
        output_type=ContextPackRunnerOutput,
        context_pack=pack,
    )

    user_prompt = str(llm.calls[0]["user_prompt"])
    assert "harness_context" in user_prompt
    assert "current_turn" in user_prompt
    assert "canonical_case_dossier" in user_prompt
    assert "物流显示签收但用户称未收到包裹" in user_prompt
    assert "这一段只给前端或配置中心预览" not in user_prompt
