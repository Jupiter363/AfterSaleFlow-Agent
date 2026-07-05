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
            normalized_items.append(
                item.model_copy(
                    update={
                        "source_system": item.source_system or "LLM_SIMULATED_OMS",
                        "external_case_reference": self._reference(request, index),
                        "user_id": user_id,
                        "merchant_id": merchant_id,
                        "initiator_role": request.initiator_role_hint,
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
        items = [
            SimulatedExternalDispute(
                source_system="LLM_SIMULATED_OMS",
                external_case_reference=self._reference(request, index),
                order_reference=f"ORDER-SIM-{self._suffix(request, index)}",
                after_sales_reference=f"AFTER-SIM-{self._suffix(request, index)}",
                logistics_reference=f"LOG-SIM-{self._suffix(request, index)}",
                user_id=user_id,
                merchant_id=merchant_id,
                initiator_role=request.initiator_role_hint,
                dispute_type=self._dispute_type(request.scenario),
                title=f"{request.scenario}｜外部导入模拟 {index}",
                description=(
                    f"外部系统导入了一笔{request.scenario}。"
                    f"发起方为{request.initiator_role_hint}，"
                    "接待官需要先进行单方事实梳理，再决定是否进入证据室。"
                ),
                risk_level=risk,
            )
            for index in range(1, request.count + 1)
        ]
        return SimulatedExternalImportResult(items=items)

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
        return f"SIM-{cls._suffix(request, index)}"

    @staticmethod
    def _suffix(request: SimulatedExternalImportRequest, index: int) -> str:
        raw = (
            f"{request.scenario}|{request.initiator_role_hint}|"
            f"{request.current_actor_id}|{request.counterparty_actor_id}|"
            f"{request.simulation_batch_id or 'default-batch'}|{index}"
        )
        return hashlib.sha1(raw.encode("utf-8")).hexdigest()[:12].upper()
