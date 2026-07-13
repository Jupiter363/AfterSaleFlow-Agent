# 文件作用：自动化测试文件，验证 test_context_pack 相关模块的行为、契约或页面布局。

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
    # 所属模块：Agent Harness > test_context_pack；函数角色：对象依赖初始化。
    # 具体功能：`__init__` 注入并保存处理本阶段状态需要的客户端、配置或策略依赖。
    # 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 受 Token、权限、Schema、审计约束的模型输入或结果。
    # 系统意义：该函数在系统中的业务边界是：隔离参与方会话；不可信案件文本不能升级为系统指令。
    def __init__(self) -> None:
        self.calls: list[dict[str, object]] = []

    # 所属模块：Agent Harness > test_context_pack；函数角色：类/闭包内部方法。
    # 具体功能：`generate` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`self.calls.append`、`StructuredGeneration`、`output_type`。
    # 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 协作调用 `self.calls.append`、`StructuredGeneration`、`output_type`。
    # 系统意义：该函数在系统中的业务边界是：隔离参与方会话；不可信案件文本不能升级为系统指令。
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


# 所属模块：Agent Harness > test_context_pack；函数角色：回归测试用例。
# 具体功能：`test_prompt_contract_declares_standard_sections_and_configuration_center_slot` 验证模型提示词在固定案例中的输出、边界和失败行为；关键协作调用：`context_contract`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 协作调用 `context_contract`。
# 系统意义：固定“Agent Harness > test_context_pack”的可观察契约，防止后续重构改变业务结果。
def test_prompt_contract_declares_standard_sections_and_configuration_center_slot() -> None:
    contract = context_contract("evidence_turn")

    specs = {spec.name: spec for spec in contract.sections}

    assert contract.node_name == "evidence_turn"
    assert specs["current_turn"].priority == 100
    assert specs["current_turn"].required is True
    assert specs["canonical_case_dossier"].priority == 96
    assert specs["fact_targets"].priority == 95
    assert specs["fact_targets"].trust_level == "intake_dossier_allowlist"
    assert specs["private_conversation_window"].trust_level == (
        "current_actor_session_only"
    )
    assert specs["party_visible_evidence_catalog"].trust_level == (
        "java_authorized_harness_bounded"
    )
    assert specs["multimodal_observation"].trust_level == (
        "harness_asset_loader"
    )
    assert specs["long_term_memory_preview"].prompt_included is False
    assert contract.configuration_source == "code"
    assert contract.configuration_profile_key == "EVIDENCE_CLERK_CONTEXT_PACK_V2"


# 所属模块：Agent Harness > test_context_pack；函数角色：回归测试用例。
# 具体功能：`test_context_pack_rejects_missing_required_sections` 验证案件与会话上下文在固定案例中的输出、边界和失败行为；关键协作调用：`build_context_pack`、`AssertionError`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 协作调用 `build_context_pack`、`AssertionError`。
# 系统意义：固定“Agent Harness > test_context_pack”的可观察契约，防止后续重构改变业务结果。
def test_context_pack_rejects_missing_required_sections() -> None:
    try:
        build_context_pack("evidence_turn", {})
    except ValueError as error:
        assert "required context section current_turn is missing" in str(error)
    else:
        raise AssertionError("expected missing required section to fail")


# 所属模块：Agent Harness > test_context_pack；函数角色：回归测试用例。
# 具体功能：`test_localization_policy_maps_internal_codes_and_field_names` 验证平台规则在固定案例中的输出、边界和失败行为；关键协作调用：`localize_internal_text`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 协作调用 `localize_internal_text`。
# 系统意义：固定“Agent Harness > test_context_pack”的可观察契约，防止后续重构改变业务结果。
def test_localization_policy_maps_internal_codes_and_field_names() -> None:
    text = localize_internal_text(
        "SIGNED_NOT_RECEIVED still lacks user_statement and logistics_reference.",
    )

    assert text == (
        "物流显示签收但用户称未收到包裹 still lacks 用户原始陈述 and 物流单号."
    )


# 所属模块：Agent Harness > test_context_pack；函数角色：回归测试用例。
# 具体功能：`test_localization_policy_does_not_translate_identifiers_or_references` 验证平台规则在固定案例中的输出、边界和失败行为；关键协作调用：`build_context_pack`、`json.loads`、`pack.prompt_sections`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 协作调用 `build_context_pack`、`json.loads`、`pack.prompt_sections`。
# 系统意义：固定“Agent Harness > test_context_pack”的可观察契约，防止后续重构改变业务结果。
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
            "party_visible_evidence_catalog": [
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
    assert sections["party_visible_evidence_catalog"][0]["evidence_id"] == "EVIDENCE_USER_SIGNATURE_1"
    assert sections["party_visible_evidence_catalog"][0]["source_type"] == "USER"
    assert sections["party_visible_evidence_catalog"][0]["content"] == (
        "SIGNED_NOT_RECEIVED 相关签收截图"
    )


# 所属模块：Agent Harness > test_context_pack；函数角色：回归测试用例。
# 具体功能：`test_narrative_policy_rewrites_first_person_to_platform_third_person` 验证平台规则在固定案例中的输出、边界和失败行为；关键协作调用：`rewrite_platform_narrative`、`text.startswith`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 协作调用 `rewrite_platform_narrative`、`text.startswith`。
# 系统意义：固定“Agent Harness > test_context_pack”的可观察契约，防止后续重构改变业务结果。
def test_narrative_policy_rewrites_first_person_to_platform_third_person() -> None:
    text = rewrite_platform_narrative(
        "物流显示签收，但我没有收到包裹，希望核验签收记录并退款。",
        actor_role="USER",
    )

    assert text.startswith("用户称")
    assert "本人没有收到包裹" in text
    assert "我没有" not in text


# 所属模块：Agent Harness > test_context_pack；函数角色：回归测试用例。
# 具体功能：`test_context_pack_builds_localized_sections_and_keeps_raw_statement_for_traceability` 把上游材料组装为本阶段可消费的案件与会话上下文；关键协作调用：`build_context_pack`、`json.loads`、`pack.prompt_sections`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 协作调用 `build_context_pack`、`json.loads`、`pack.prompt_sections`。
# 系统意义：固定“Agent Harness > test_context_pack”的可观察契约，防止后续重构改变业务结果。
def test_context_pack_builds_localized_sections_and_keeps_raw_statement_for_traceability() -> None:
    pack = build_context_pack(
        "evidence_turn",
        {
            "current_turn": {
                "role": "USER",
                "text": "物流显示签收，但我没有收到包裹，希望核验签收记录并退款。",
                "turn_source": "PARTY_MESSAGE",
            },
            "case_identity": {"case_id": "CASE_CONTEXT_1"},
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


# 所属模块：Agent Harness > test_context_pack；函数角色：回归测试用例。
# 具体功能：`test_context_pack_preserves_raw_statement_even_when_it_contains_internal_codes` 验证案件与会话上下文在固定案例中的输出、边界和失败行为；关键协作调用：`build_context_pack`、`json.loads`、`pack.prompt_sections`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 协作调用 `build_context_pack`、`json.loads`、`pack.prompt_sections`。
# 系统意义：固定“Agent Harness > test_context_pack”的可观察契约，防止后续重构改变业务结果。
def test_context_pack_preserves_raw_statement_even_when_it_contains_internal_codes() -> None:
    raw_text = "系统备注：SIGNED_NOT_RECEIVED，但我仍希望补充物流截图。"

    pack = build_context_pack(
        "evidence_turn",
        {
            "current_turn": {
                "role": "USER",
                "text": raw_text,
            },
            "case_identity": {"case_id": "CASE_CONTEXT_2"},
        },
    )

    section_by_name = {section.name: section for section in pack.prompt_sections()}
    current_turn = json.loads(section_by_name["current_turn"].content)

    assert current_turn["raw_statement"] == raw_text
    assert "SIGNED_NOT_RECEIVED" not in current_turn["platform_statement"]
    assert "物流显示签收但用户称未收到包裹" in current_turn["platform_statement"]


# 所属模块：Agent Harness > test_context_pack；函数角色：回归测试用例。
# 具体功能：`test_model_runner_accepts_context_pack_and_excludes_display_only_sections` 把模型状态转换为稳定的接口、提示词或页面表达；关键协作调用：`RecordingContextPackLlm`、`HarnessModelRunner`、`build_context_pack`。
# 上下游：上游为 Java 可信快照、调用身份、上下文合同、角色模板；下游为 协作调用 `RecordingContextPackLlm`、`HarnessModelRunner`、`build_context_pack`、`runner.invoke_structured`。
# 系统意义：固定“Agent Harness > test_context_pack”的可观察契约，防止后续重构改变业务结果。
def test_model_runner_accepts_context_pack_and_excludes_display_only_sections() -> None:
    from app.harness.model_runner import HarnessModelRunner
    from app.harness.prompt_composer import PromptRepository

    llm = RecordingContextPackLlm()
    runner = HarnessModelRunner(llm=llm, prompts=PromptRepository())
    pack = build_context_pack(
        "evidence_turn",
            {
                "current_turn": {"role": "USER", "text": "我上传了签收截图。"},
                "case_identity": {"case_id": "CASE_CONTEXT_3"},
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
