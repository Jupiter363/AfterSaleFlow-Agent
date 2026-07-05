from __future__ import annotations

import operator
from typing import Annotated, Any

from langgraph.graph import END, START, StateGraph
from typing_extensions import NotRequired, TypedDict

from app.harness.memory import MemeoMemoryAssembler
from app.schemas import IntakeTurnRequest, IntakeTurnResult


class IntakeTurnGraphState(TypedDict):
    request: dict[str, Any]
    executed_nodes: Annotated[list[str], operator.add]
    source_text: str
    actor_role: str
    knowledge_query_intent: bool
    knowledge_answer_mode: str
    memory_frame: dict[str, Any]
    dossier_patch: NotRequired[dict[str, Any]]
    room_utterance: NotRequired[str]
    scroll_snapshot: NotRequired[dict[str, Any]]
    canvas_operations: NotRequired[list[dict[str, Any]]]
    admission_recommendation: NotRequired[str]
    missing_fields: NotRequired[list[str]]
    confidence: NotRequired[float]


class IntakeTurnWorkflow:
    """LangGraph workflow for one dispute-intake room turn.

    The workflow is intentionally deterministic for now: it does not spend LLM
    tokens, but it implements the production contract that Java and the
    frontend depend on. Later we can replace the reasoning node with a model
    node and the knowledge node with RAG/MCP without changing the API shape.
    """

    def __init__(self) -> None:
        self._graph = build_intake_turn_graph()

    def run(self, request: IntakeTurnRequest) -> IntakeTurnResult:
        initial_state: IntakeTurnGraphState = {
            "request": request.model_dump(mode="json"),
            "executed_nodes": [],
            "source_text": "",
            "actor_role": "USER",
            "knowledge_query_intent": False,
            "knowledge_answer_mode": "NONE",
            "memory_frame": {},
        }
        result = self._graph.invoke(initial_state)
        return IntakeTurnResult(
            room_utterance=result["room_utterance"],
            dossier_patch=result["dossier_patch"],
            scroll_snapshot=result["scroll_snapshot"],
            canvas_operations=result["canvas_operations"],
            memory_frame=result["memory_frame"],
            admission_recommendation=result["admission_recommendation"],
            missing_fields=result["missing_fields"],
            knowledge_query_intent=result["knowledge_query_intent"],
            knowledge_answer_mode=result["knowledge_answer_mode"],
            confidence=result["confidence"],
        )


def build_intake_turn_graph():
    builder = StateGraph(IntakeTurnGraphState)
    builder.add_node("load_context", _load_context)
    builder.add_node("classify_intent", _classify_intent)
    builder.add_node("intake_reasoning", _intake_reasoning)
    builder.add_node("knowledge_qa_stub", _knowledge_qa_stub)
    builder.add_node("dossier_canvas", _dossier_canvas)
    builder.add_node("validate_output", _validate_output)
    builder.add_edge(START, "load_context")
    builder.add_edge("load_context", "classify_intent")
    builder.add_edge("classify_intent", "intake_reasoning")
    builder.add_edge("intake_reasoning", "knowledge_qa_stub")
    builder.add_edge("knowledge_qa_stub", "dossier_canvas")
    builder.add_edge("dossier_canvas", "validate_output")
    builder.add_edge("validate_output", END)
    return builder.compile()


def _load_context(state: IntakeTurnGraphState) -> dict[str, Any]:
    request = state["request"]
    current = request.get("current_user_message") or {}
    seed = request["lobby_seed"]
    source_text = str(current.get("text") or seed.get("raw_text") or "")
    actor_role = str(current.get("role") or seed.get("initiator_role") or "USER")
    memory_frame = MemeoMemoryAssembler().assemble(
        request.get("recent_turns") or []
    ).model_dump(mode="json")
    return {
        "source_text": source_text,
        "actor_role": actor_role,
        "memory_frame": memory_frame,
        "executed_nodes": ["load_context"],
    }


def _classify_intent(state: IntakeTurnGraphState) -> dict[str, Any]:
    text = _normalized(state["source_text"])
    knowledge_terms = (
        "规则",
        "时效",
        "多久",
        "流程",
        "怎么处理",
        "标准",
        "赔付",
        "判断",
        "平台规定",
    )
    return {
        "knowledge_query_intent": any(term in text for term in knowledge_terms),
        "executed_nodes": ["classify_intent"],
    }


def _intake_reasoning(state: IntakeTurnGraphState) -> dict[str, Any]:
    request = state["request"]
    seed = request["lobby_seed"]
    source_text = state["source_text"]
    normalized = _normalized(source_text)
    actor_role = state["actor_role"]
    outcome = _requested_outcome(source_text, seed.get("requested_outcome_hint"))
    missing_fields = _missing_fields(
        normalized,
        seed,
        request.get("latest_scroll_snapshot") or {},
        state["memory_frame"].get("prompt_memory", ""),
    )
    recommendation = "ACCEPTED" if _can_accept(seed, missing_fields) else "NEED_MORE_INFO"
    risk_signals = _risk_signals(normalized)

    claim_key = "merchant" if actor_role == "MERCHANT" else "user"
    dossier_patch: dict[str, Any] = {
        "initiator_role": seed.get("initiator_role") or actor_role or "USER",
        "party_claims": {claim_key: source_text},
        "requested_outcome": outcome,
        "missing_initial_fields": missing_fields,
        "initial_risk_signals": risk_signals,
    }
    for key in (
        "order_reference",
        "after_sales_reference",
        "logistics_reference",
    ):
        if seed.get(key):
            dossier_patch[key] = seed[key]

    if request.get("turn_source") == "LOBBY_SEED":
        prefix = "我已收到大厅表单，并先把订单引用、售后引用、物流引用和你的核心诉求整理成右侧卷轴。"
    else:
        prefix = "我已把你这一轮补充写入右侧卷轴，并会沿用上一轮已确认的信息继续追问。"
    if _mentions_logistics_dispute(normalized):
        prefix += "我看到这是物流/签收相关问题。"

    if missing_fields:
        question = _question_for_missing(missing_fields)
        utterance = f"{prefix} 目前还差一点关键材料：{question}"
    else:
        utterance = f"{prefix} 当前信息已经足以形成受理建议，请你确认右侧卷轴是否准确。"

    return {
        "dossier_patch": dossier_patch,
        "room_utterance": utterance,
        "admission_recommendation": recommendation,
        "missing_fields": missing_fields,
        "confidence": 0.82 if recommendation == "ACCEPTED" else 0.7,
        "executed_nodes": ["intake_reasoning"],
    }


def _knowledge_qa_stub(state: IntakeTurnGraphState) -> dict[str, Any]:
    if not state["knowledge_query_intent"]:
        return {
            "knowledge_answer_mode": "NONE",
            "executed_nodes": ["knowledge_qa_stub"],
        }
    return {
        "knowledge_answer_mode": "STUB",
        "room_utterance": (
            state["room_utterance"]
            + " 关于处理规则和时效，我先按平台流程说明：接待阶段只形成受理建议，"
            "不会直接裁决责任；证据书记官会收集双方证据，AI 法官会在庭审阶段形成裁决草案，"
            "最终仍需要平台审核。真实知识库插件后续接入。"
        ),
        "executed_nodes": ["knowledge_qa_stub"],
    }


def _dossier_canvas(state: IntakeTurnGraphState) -> dict[str, Any]:
    request = state["request"]
    previous = request.get("latest_scroll_snapshot") or {}
    previous_cards = _cards_by_key(previous)
    patch = state["dossier_patch"]
    cards: list[dict[str, Any]] = []
    operations: list[dict[str, Any]] = []
    field_labels = {
        "order_reference": "订单引用",
        "after_sales_reference": "售后引用",
        "logistics_reference": "物流引用",
        "requested_outcome": "期望结果",
        "initiator_role": "发起身份",
        "user_claim": "用户主张",
        "merchant_claim": "商家主张",
    }
    field_values = {
        "order_reference": patch.get("order_reference"),
        "after_sales_reference": patch.get("after_sales_reference"),
        "logistics_reference": patch.get("logistics_reference"),
        "requested_outcome": patch.get("requested_outcome"),
        "initiator_role": patch.get("initiator_role"),
        "user_claim": (patch.get("party_claims") or {}).get("user"),
        "merchant_claim": (patch.get("party_claims") or {}).get("merchant"),
    }

    for key, label in field_labels.items():
        value = field_values.get(key)
        previous_card = previous_cards.get(key)
        if not value and previous_card:
            cards.append({**previous_card, "status": "confirmed"})
            continue
        if not value:
            continue
        prior_value = previous_card.get("value") if previous_card else None
        status = "confirmed" if prior_value == value else "updated"
        cards.append(
            {
                "key": key,
                "label": label,
                "value": value,
                "status": status,
            }
        )
        if prior_value != value:
            operations.append(
                {
                    "type": "UPSERT_CARD",
                    "target_key": key,
                    "animation": "ink-write" if key.endswith("_reference") else "glow",
                    "value": value,
                }
            )

    for key, previous_card in previous_cards.items():
        if key not in field_labels:
            cards.append({**previous_card, "status": "confirmed"})

    stamps = _merge_stamps(
        previous.get("stamps", []),
        [
            {"type": "risk", "text": signal, "level": "MEDIUM"}
            for signal in patch.get("initial_risk_signals", [])
        ],
    )
    next_questions = (
        [_question_for_missing(state["missing_fields"])]
        if state["missing_fields"]
        else []
    )
    operations.extend(
        {
            "type": "ADD_STAMP",
            "target_key": "risk_signals",
            "animation": "stamp",
            "value": stamp["text"],
        }
        for stamp in stamps
        if not _stamp_exists(previous.get("stamps", []), stamp.get("text"))
    )
    return {
        "scroll_snapshot": {
            "cards": cards,
            "stamps": stamps,
            "next_questions": next_questions,
            "admission_recommendation": state["admission_recommendation"],
        },
        "canvas_operations": operations,
        "executed_nodes": ["dossier_canvas"],
    }


def _validate_output(state: IntakeTurnGraphState) -> dict[str, Any]:
    cards = state.get("scroll_snapshot", {}).get("cards", [])
    if not cards:
        return {
            "scroll_snapshot": {
                "cards": [
                    {
                        "key": "intake_note",
                        "label": "接待记录",
                        "value": state["source_text"],
                        "status": "updated",
                    }
                ],
                "stamps": [],
                "next_questions": [],
                "admission_recommendation": state["admission_recommendation"],
            },
            "canvas_operations": [
                {
                    "type": "UPSERT_CARD",
                    "target_key": "intake_note",
                    "animation": "ink-write",
                    "value": state["source_text"],
                }
            ],
            "executed_nodes": ["validate_output"],
        }
    if not state.get("canvas_operations"):
        first_card = cards[0]
        return {
            "canvas_operations": [
                {
                    "type": "UPSERT_CARD",
                    "target_key": first_card["key"],
                    "animation": "ink-write",
                    "value": first_card["value"],
                }
            ],
            "executed_nodes": ["validate_output"],
        }
    return {"executed_nodes": ["validate_output"]}


def _cards_by_key(snapshot: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {
        str(card.get("key")): dict(card)
        for card in snapshot.get("cards", [])
        if isinstance(card, dict) and card.get("key")
    }


def _normalized(text: str) -> str:
    return text.casefold()


def _requested_outcome(text: str, hint: str | None) -> str:
    if hint in {"REFUND", "REPLACEMENT", "RETURN", "REJECT_REFUND", "OTHER"}:
        return hint
    normalized = _normalized(text)
    if any(term in normalized for term in ("退款", "退钱", "退回钱款", "refund")):
        return "REFUND"
    if any(term in normalized for term in ("补发", "重发", "reship")):
        return "REPLACEMENT"
    if any(term in normalized for term in ("换货", "replacement", "replace")):
        return "REPLACEMENT"
    if any(term in normalized for term in ("退货", "return")):
        return "RETURN"
    return "UNKNOWN"


def _missing_fields(
    text: str,
    seed: dict[str, Any],
    latest_scroll_snapshot: dict[str, Any],
    prompt_memory: str,
) -> list[str]:
    missing = []
    previous_cards = _cards_by_key(latest_scroll_snapshot)
    memory_text = _normalized(
        " ".join(
            [
                *(_claim_card_fragments(latest_scroll_snapshot)),
                prompt_memory,
            ]
        )
    )
    combined_text = f"{text} {memory_text}"
    if not seed.get("logistics_reference") and "logistics_reference" not in previous_cards:
        missing.append("LOGISTICS_REFERENCE")
    if _mentions_logistics_dispute(combined_text) and not _has_delivery_proof(
        combined_text
    ):
        missing.append("DELIVERY_PROOF")
    if (
        not _has_merchant_communication(combined_text)
        and "merchant_claim" not in previous_cards
    ):
        missing.append("MERCHANT_COMMUNICATION")
    return missing[:4]


def _claim_card_fragments(latest_scroll_snapshot: dict[str, Any]) -> list[str]:
    fragments: list[str] = []
    for card in (latest_scroll_snapshot or {}).get("cards", []):
        if not isinstance(card, dict):
            continue
        if card.get("key") in {"user_claim", "merchant_claim"} and card.get("value"):
            fragments.append(str(card["value"]))
    return fragments


def _can_accept(seed: dict[str, Any], missing_fields: list[str]) -> bool:
    return bool(seed.get("order_reference")) and not missing_fields


def _risk_signals(text: str) -> list[str]:
    signals = []
    if _mentions_logistics_dispute(text):
        signals.append("SIGNED_NOT_RECEIVED")
    if any(term in text for term in ("拒绝", "失联", "无法联系")):
        signals.append("COUNTERPARTY_REFUSAL")
    if any(term in text for term in ("高价", "贵重", "超过1000", "上千")):
        signals.append("HIGH_VALUE_CASE")
    return signals


def _mentions_logistics_dispute(text: str) -> bool:
    return any(term in text for term in ("签收", "物流", "未收到", "没收到", "快递"))


def _has_delivery_proof(text: str) -> bool:
    return any(
        term in text
        for term in ("截图", "凭证", "取件", "快递柜", "门口", "证明", "监控", "签收底单")
    )


def _has_merchant_communication(text: str) -> bool:
    return any(term in text for term in ("商家", "客服", "沟通", "回复", "协商"))


def _question_for_missing(missing_fields: list[str]) -> str:
    questions = {
        "LOGISTICS_REFERENCE": "请补充物流单号或平台可识别的物流引用。",
        "DELIVERY_PROOF": "请补充物流签收截图、取件记录、快递柜记录或能说明未收到的凭证。",
        "MERCHANT_COMMUNICATION": "请说明是否已经联系过商家，以及商家的回复。",
    }
    return "；".join(questions.get(field, field) for field in missing_fields)


def _merge_stamps(
    previous: list[Any],
    current: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    merged: list[dict[str, Any]] = []
    seen: set[str] = set()
    for stamp in [*previous, *current]:
        if not isinstance(stamp, dict):
            continue
        text = str(stamp.get("text") or stamp.get("value") or "")
        if not text or text in seen:
            continue
        seen.add(text)
        merged.append(
            {
                "type": stamp.get("type") or "risk",
                "text": text,
                "level": stamp.get("level") or "MEDIUM",
            }
        )
    return merged


def _stamp_exists(stamps: list[Any], text: str | None) -> bool:
    if not text:
        return False
    return any(isinstance(stamp, dict) and stamp.get("text") == text for stamp in stamps)
