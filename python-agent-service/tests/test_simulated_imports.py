from app.business.simulated_imports import SimulatedExternalImportWorkflow
from app.schemas import SimulatedExternalImportRequest


def test_simulated_import_batch_id_changes_external_references() -> None:
    workflow = SimulatedExternalImportWorkflow()

    first = workflow.generate(
        SimulatedExternalImportRequest(
            count=2,
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
            count=2,
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

    assert len(first_refs) == 2
    assert len(second_refs) == 2
    assert first_refs.isdisjoint(second_refs)
