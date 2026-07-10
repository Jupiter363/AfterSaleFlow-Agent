import pytest
from pydantic import ValidationError

from app.business.simulated_imports import SimulatedExternalImportWorkflow
from app.schemas import SimulatedExternalImportRequest


def test_simulated_import_batch_id_changes_external_references() -> None:
    workflow = SimulatedExternalImportWorkflow()

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


def test_simulated_import_generates_real_dispute_copy_without_visible_simulation_labels() -> None:
    workflow = SimulatedExternalImportWorkflow()

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
