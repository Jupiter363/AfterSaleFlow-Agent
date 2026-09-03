# 文件作用：自动化测试文件，验证 test_simulated_imports 相关模块的行为、契约或页面布局。

from types import SimpleNamespace

import pytest
from pydantic import ValidationError

from app.business.simulated_imports import SimulatedExternalImportWorkflow
from app.schemas import SimulatedExternalImportRequest


class DeterministicImportRunner:
    def invoke_structured(self, **kwargs):
        request = SimulatedExternalImportRequest.model_validate(kwargs["case_data"])
        value = SimulatedExternalImportWorkflow()._deterministic_result(request)
        return SimpleNamespace(value=value)


# 所属模块：Python 支撑模块 > test_simulated_imports；函数角色：回归测试用例。
# 具体功能：`test_simulated_import_batch_id_changes_external_references` 验证业务引用号在固定案例中的输出、边界和失败行为；关键协作调用：`SimulatedExternalImportWorkflow`、`workflow.generate`、`first_refs.isdisjoint`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `SimulatedExternalImportWorkflow`、`workflow.generate`、`first_refs.isdisjoint`、`SimulatedExternalImportRequest`。
# 系统意义：固定“Python 支撑模块 > test_simulated_imports”的可观察契约，防止后续重构改变业务结果。
def test_simulated_import_batch_id_changes_external_references() -> None:
    workflow = SimulatedExternalImportWorkflow(model_runner=DeterministicImportRunner())

    first = workflow.generate(
        SimulatedExternalImportRequest(
            count=1,
            scenario="watch-after-sale-dispute",
            risk_level_hint="MEDIUM",
            initiator_role_hint="USER",
            current_actor_id="user-local",
            counterparty_actor_id="merchant-local",
            simulation_batch_id="external-import-batch-001",
        )
    )
    second = workflow.generate(
        SimulatedExternalImportRequest(
            count=1,
            scenario="watch-after-sale-dispute",
            risk_level_hint="MEDIUM",
            initiator_role_hint="USER",
            current_actor_id="user-local",
            counterparty_actor_id="merchant-local",
            simulation_batch_id="external-import-batch-002",
        )
    )

    first_refs = {item.external_case_reference for item in first.items}
    second_refs = {item.external_case_reference for item in second.items}

    assert len(first_refs) == 1
    assert len(second_refs) == 1
    assert first_refs.isdisjoint(second_refs)


# 所属模块：Python 支撑模块 > test_simulated_imports；函数角色：回归测试用例。
# 具体功能：`test_simulated_import_generates_real_dispute_copy_without_visible_simulation_labels` 验证本阶段状态在固定案例中的输出、边界和失败行为；关键协作调用：`SimulatedExternalImportWorkflow`、`workflow.generate`、`SimulatedExternalImportRequest`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `SimulatedExternalImportWorkflow`、`workflow.generate`、`SimulatedExternalImportRequest`。
# 系统意义：固定“Python 支撑模块 > test_simulated_imports”的可观察契约，防止后续重构改变业务结果。
def test_simulated_import_generates_real_dispute_copy_without_visible_simulation_labels() -> None:
    workflow = SimulatedExternalImportWorkflow(model_runner=DeterministicImportRunner())

    result = workflow.generate(
        SimulatedExternalImportRequest(
            count=1,
            scenario="手表售后争议",
            risk_level_hint="MEDIUM",
            initiator_role_hint="MERCHANT",
            current_actor_id="merchant-local",
            counterparty_actor_id="user-local",
            simulation_batch_id="external-import-batch-real-copy",
        )
    )

    assert len(result.items) == 1
    for item in result.items:
        visible_copy = f"{item.title} {item.description} {item.order_reference}"
        assert "模拟" not in visible_copy
        assert "SIM" not in item.order_reference
        assert any(
            keyword in visible_copy
            for keyword in ["签收", "退款", "手表", "售后", "履约", "检测"]
        )


# 所属模块：Python 支撑模块 > test_simulated_imports；函数角色：回归测试用例。
# 具体功能：`test_simulated_import_request_rejects_more_than_one_item` 验证本阶段状态在固定案例中的输出、边界和失败行为；关键协作调用：`pytest.raises`、`SimulatedExternalImportRequest`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `pytest.raises`、`SimulatedExternalImportRequest`。
# 系统意义：固定“Python 支撑模块 > test_simulated_imports”的可观察契约，防止后续重构改变业务结果。
def test_simulated_import_request_rejects_more_than_one_item() -> None:
    with pytest.raises(ValidationError):
        SimulatedExternalImportRequest(
            count=2,
            scenario="watch-after-sale-dispute",
            risk_level_hint="MEDIUM",
            initiator_role_hint="USER",
            current_actor_id="user-local",
            counterparty_actor_id="merchant-local",
        )


# 所属模块：Python 支撑模块 > test_simulated_imports；函数角色：回归测试用例。
# 具体功能：`test_simulated_import_request_requires_fixed_parties_in_initiator_order` 验证本阶段状态在固定案例中的输出、边界和失败行为；关键协作调用：`pytest.raises`、`SimulatedExternalImportRequest`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `pytest.raises`、`SimulatedExternalImportRequest`。
# 系统意义：固定“Python 支撑模块 > test_simulated_imports”的可观察契约，防止后续重构改变业务结果。
def test_simulated_import_request_requires_fixed_parties_in_initiator_order() -> None:
    with pytest.raises(ValidationError):
        SimulatedExternalImportRequest(
            count=1,
            scenario="watch-after-sale-dispute",
            risk_level_hint="MEDIUM",
            initiator_role_hint="USER",
            current_actor_id="merchant-local",
            counterparty_actor_id="user-local",
        )
