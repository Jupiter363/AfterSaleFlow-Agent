from __future__ import annotations

import hashlib
from typing import Any

from app.schemas import (
    SimulatedExternalDispute,
    SimulatedExternalImportRequest,
    SimulatedExternalImportResult,
)


class SimulatedExternalImportWorkflow:
    """Generates external-adapter import DTOs without writing business data."""

    def __init__(self, model_runner: Any | None = None) -> None:
        self._model_runner = model_runner

    def generate(
        self,
        request: SimulatedExternalImportRequest,
    ) -> SimulatedExternalImportResult:
        if self._model_runner is None:
            return self._deterministic_result(request)
        try:
            generation = self._model_runner.invoke_structured(
                node_name="external_import_simulator",
                case_data=request.model_dump(mode="json"),
                output_type=SimulatedExternalImportResult,
                max_input_tokens=1800,
            )
            return self._normalize(generation.value, request)
        except Exception:
            return self._deterministic_result(request)

    def _normalize(
        self,
        result: SimulatedExternalImportResult,
        request: SimulatedExternalImportRequest,
    ) -> SimulatedExternalImportResult:
        normalized_items: list[SimulatedExternalDispute] = []
        for index, item in enumerate(result.items[: request.count], start=1):
            user_id, merchant_id = self._party_ids(request)
            template = self._realistic_case(request, index)
            normalized_items.append(
                item.model_copy(
                    update={
                        "source_system": item.source_system or "LLM_SIMULATED_OMS",
                        "external_case_reference": self._reference(request, index),
                        "order_reference": self._visible_identifier(
                            item.order_reference,
                            template.order_reference,
                        ),
                        "after_sales_reference": self._visible_identifier(
                            item.after_sales_reference,
                            template.after_sales_reference,
                        ),
                        "logistics_reference": self._visible_identifier(
                            item.logistics_reference,
                            template.logistics_reference,
                        ),
                        "user_id": user_id,
                        "merchant_id": merchant_id,
                        "initiator_role": request.initiator_role_hint,
                        "dispute_type": item.dispute_type
                        or template.dispute_type,
                        "title": self._visible_copy(item.title, template.title),
                        "description": self._visible_copy(
                            item.description,
                            template.description,
                        ),
                        "risk_level": item.risk_level
                        or request.risk_level_hint
                        or "MEDIUM",
                    }
                )
            )
        if not normalized_items:
            return self._deterministic_result(request)
        return SimulatedExternalImportResult(items=normalized_items)

    def _deterministic_result(
        self,
        request: SimulatedExternalImportRequest,
    ) -> SimulatedExternalImportResult:
        user_id, merchant_id = self._party_ids(request)
        risk = request.risk_level_hint or "MEDIUM"
        items = []
        for index in range(1, request.count + 1):
            template = self._realistic_case(request, index)
            items.append(
                SimulatedExternalDispute(
                    source_system="LLM_SIMULATED_OMS",
                    external_case_reference=self._reference(request, index),
                    order_reference=template.order_reference,
                    after_sales_reference=template.after_sales_reference,
                    logistics_reference=template.logistics_reference,
                    user_id=user_id,
                    merchant_id=merchant_id,
                    initiator_role=request.initiator_role_hint,
                    dispute_type=template.dispute_type,
                    title=template.title,
                    description=template.description,
                    risk_level=risk,
                )
            )
        return SimulatedExternalImportResult(items=items)

    def _realistic_case(
        self,
        request: SimulatedExternalImportRequest,
        index: int,
    ) -> SimulatedExternalDispute:
        suffix = self._suffix(request, index)
        user_id, merchant_id = self._party_ids(request)
        order_reference = f"ORDER-20260706-{suffix[:4]}"
        after_sales_reference = f"AS-20260706-{suffix[4:8]}"
        logistics_reference = f"SF-20260706-{suffix[8:12]}"
        title, description, dispute_type = self._realistic_story(
            request.scenario,
            request.initiator_role_hint,
            index,
        )
        return SimulatedExternalDispute(
            source_system="LLM_SIMULATED_OMS",
            external_case_reference=self._reference(request, index),
            order_reference=order_reference,
            after_sales_reference=after_sales_reference,
            logistics_reference=logistics_reference,
            user_id=user_id,
            merchant_id=merchant_id,
            initiator_role=request.initiator_role_hint,
            dispute_type=dispute_type,
            title=title,
            description=description,
            risk_level=request.risk_level_hint or "MEDIUM",
        )

    @classmethod
    def _realistic_story(
        cls,
        scenario: str,
        initiator_role: str,
        index: int,
    ) -> tuple[str, str, str]:
        normalized = scenario.lower()
        party = "商家" if initiator_role == "MERCHANT" else "用户"
        if "签收" in scenario or "未收到" in scenario:
            stories = [
                (
                    "签收未收到包裹争议",
                    f"{party}反馈物流显示已签收，但收货方表示未实际收到商品；双方对签收凭证、派送位置和后续退款责任存在分歧，需要平台先梳理订单、物流节点和诉求。",
                    "SIGNED_NOT_RECEIVED",
                ),
                (
                    "代收点签收责任争议",
                    f"{party}称包裹被记录为代收点签收，但取件凭证与实际领取信息不一致，希望平台核实物流履约链路并判断是否进入举证阶段。",
                    "SIGNED_NOT_RECEIVED",
                ),
            ]
        elif "退款" in scenario:
            stories = [
                (
                    "退款履约时效争议",
                    f"{party}表示退货或售后条件已经满足，但退款节点迟迟未完成；当前争议集中在退款承诺、平台规则时效和商家处理记录。",
                    "REFUND_FULFILLMENT",
                ),
                (
                    "售后退款金额争议",
                    f"{party}对售后退款金额存在异议，认为扣减依据、运费承担和商品状态说明不充分，需要接待官先整理双方待举证事项。",
                    "REFUND_FULFILLMENT",
                ),
            ]
        elif "手表" in scenario or "故障" in scenario or "质量" in scenario or "watch" in normalized:
            stories = [
                (
                    "手表维修检测结论不一致",
                    f"{party}发起售后争议，称手表维修检测结论与提交前的故障表现不一致；当前焦点是故障成因、维修责任和后续处理方案。",
                    "QUALITY_DISPUTE",
                ),
                (
                    "高价值手表售后责任争议",
                    f"{party}认为手表售后过程中的检测照片、维修记录和使用说明存在冲突，要求平台核实是否构成履约争端并明确下一步举证要求。",
                    "QUALITY_DISPUTE",
                ),
                (
                    "维修寄回后功能异常争议",
                    f"{party}反馈手表寄回后仍存在走时异常，双方对是否属于原故障延续或二次损坏存在分歧，需要先完成案情接待和风险分级。",
                    "QUALITY_DISPUTE",
                ),
            ]
        else:
            stories = [
                (
                    "履约承诺与实际处理不一致",
                    f"{party}反馈订单售后处理结果与前期沟通承诺不一致，当前需要核对订单、售后记录、物流节点和期望处理结果。",
                    "FULFILLMENT_CONFLICT",
                ),
                (
                    "售后处理路径争议",
                    f"{party}认为当前售后处理路径无法解决履约问题，希望平台确认是否受理为争议并开放后续证据提交流程。",
                    "FULFILLMENT_CONFLICT",
                ),
            ]
        return stories[(index - 1) % len(stories)]

    @staticmethod
    def _party_ids(request: SimulatedExternalImportRequest) -> tuple[str, str]:
        if request.initiator_role_hint == "MERCHANT":
            return request.counterparty_actor_id, request.current_actor_id
        return request.current_actor_id, request.counterparty_actor_id

    @staticmethod
    def _dispute_type(scenario: str) -> str:
        text = scenario.lower()
        if "手表" in scenario or "故障" in scenario or "质量" in scenario:
            return "QUALITY_DISPUTE"
        if "签收" in scenario or "未收到" in scenario:
            return "SIGNED_NOT_RECEIVED"
        if "退款" in scenario:
            return "REFUND_FULFILLMENT"
        return "FULFILLMENT_CONFLICT"

    @classmethod
    def _reference(
        cls,
        request: SimulatedExternalImportRequest,
        index: int,
    ) -> str:
        return f"EXT-{cls._suffix(request, index)}"

    @classmethod
    def _visible_identifier(cls, value: str | None, fallback: str | None) -> str | None:
        if not value:
            return fallback
        return fallback if cls._has_visible_simulation_marker(value) else value

    @classmethod
    def _visible_copy(cls, value: str | None, fallback: str) -> str:
        if not value or cls._has_visible_simulation_marker(value):
            return fallback
        return value

    @staticmethod
    def _has_visible_simulation_marker(value: str) -> bool:
        normalized = value.upper()
        return "模拟" in value or "測試" in value or "测试" in value or "SIM" in normalized

    @staticmethod
    def _suffix(request: SimulatedExternalImportRequest, index: int) -> str:
        raw = (
            f"{request.scenario}|{request.initiator_role_hint}|"
            f"{request.current_actor_id}|{request.counterparty_actor_id}|"
            f"{request.simulation_batch_id or 'default-batch'}|{index}"
        )
        return hashlib.sha1(raw.encode("utf-8")).hexdigest()[:12].upper()
