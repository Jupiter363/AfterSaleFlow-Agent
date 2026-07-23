from __future__ import annotations

import random
import time
from copy import deepcopy
from dataclasses import replace

import pytest
from langchain_core.runnables import RunnableLambda
from langgraph.checkpoint.memory import InMemorySaver

from app.graphs.evidence.contracts import EvidenceGraphContractError
from app.graphs.evidence.runtime import (
    build_evidence_runtime_bundle,
    extract_evidence_terminal_proposal,
    validate_evidence_recovery_state,
)
from app.graphs.evidence.state import new_evidence_graph_state


COMPLETED_AT = "2026-07-22T12:05:00Z"


def _bundle(*, admission, assessment, saver, completed_at=COMPLETED_AT, mode=None):
    return build_evidence_runtime_bundle(
        item_assessor=RunnableLambda(assessment),
        admission=admission,
        completed_at=completed_at,
        checkpointer=saver,
        runtime_mode=mode or "SIGNED_SYNTHETIC_SHADOW",
    )


def test_random_completion_order_produces_one_stable_terminal_hash(
    admission_factory,
    assessment_factory,
) -> None:
    admission = admission_factory(8)

    def execute(seed: int) -> tuple[str, dict]:
        randomizer = random.Random(seed)
        delays = {
            key: randomizer.random() / 1000
            for key in admission.manifest["ordered_item_keys"]
        }

        def delayed(work_item):
            time.sleep(delays[work_item["item"]["evidence_id"]])
            return assessment_factory(work_item)

        bundle = _bundle(admission=admission, assessment=delayed, saver=InMemorySaver())
        state = bundle.start()
        proposal = bundle.terminal_proposal(state)
        replay = bundle.resume()
        return proposal["proposal_hash"], bundle.terminal_proposal(replay)

    first_hash, replay = execute(7)
    second_hash, _ = execute(91)

    assert first_hash == second_hash == replay["proposal_hash"]
    assert replay["coverage_status"] == "COMPLETE"
    assert replay["formal_sink_eligible"] is False
    assert replay["writer_mode"] == "PROPOSAL_ONLY"


@pytest.mark.parametrize("count", [1, 8, 100])
def test_crash_recovery_resumes_same_manifest_and_replays_identical_proposal(
    admission_factory,
    assessment_factory,
    count: int,
) -> None:
    admission = admission_factory(count)
    target = admission.manifest["ordered_item_keys"][-1]
    saver = InMemorySaver()
    crashed = False

    def crash_once(work_item):
        nonlocal crashed
        if work_item["item"]["evidence_id"] == target and not crashed:
            crashed = True
            raise RuntimeError("synthetic assessment crash")
        return assessment_factory(work_item)

    crashing = _bundle(admission=admission, assessment=crash_once, saver=saver)
    with pytest.raises(RuntimeError, match="synthetic assessment crash"):
        crashing.start()

    recovered = _bundle(admission=admission, assessment=assessment_factory, saver=saver)
    recovered_state = recovered.resume()
    recovered_proposal = recovered.terminal_proposal(recovered_state)

    clean = _bundle(
        admission=admission,
        assessment=assessment_factory,
        saver=InMemorySaver(),
    )
    clean_proposal = clean.terminal_proposal(clean.start())

    assert recovered_proposal == clean_proposal


def test_recovery_rejects_another_graph_lease_fence_on_the_same_java_manifest(
    admission_request_factory,
    admission_verifier_factory,
    assessment_factory,
) -> None:
    request = admission_request_factory(1)
    original = admission_verifier_factory().verify(request)
    saver = InMemorySaver()
    completed = _bundle(
        admission=original,
        assessment=assessment_factory,
        saver=saver,
    )
    completed.start()

    replacement_request = replace(
        request,
        graph_lease_fencing_token=request.graph_lease_fencing_token + 1,
    )
    replacement_admission = admission_verifier_factory().verify(replacement_request)
    replacement = _bundle(
        admission=replacement_admission,
        assessment=assessment_factory,
        saver=saver,
    )

    assert original.manifest["fencing_token"] == replacement_admission.manifest["fencing_token"]
    assert original.graph_lease_fencing_token != (
        replacement_admission.graph_lease_fencing_token
    )
    with pytest.raises(
        EvidenceGraphContractError,
        match="EVIDENCE_RECOVERY_RUNTIME_BINDING_MISMATCH",
    ):
        replacement.resume()


def test_recovery_rejects_changed_runtime_binding_and_formal_authority(
    admission,
    assessment_factory,
) -> None:
    saver = InMemorySaver()
    original = _bundle(admission=admission, assessment=assessment_factory, saver=saver)
    terminal = original.start()
    changed_time = _bundle(
        admission=admission,
        assessment=assessment_factory,
        saver=saver,
        completed_at="2026-07-22T12:06:00Z",
    )
    with pytest.raises(
        EvidenceGraphContractError,
        match="EVIDENCE_RECOVERY_RUNTIME_BINDING_MISMATCH",
    ):
        changed_time.resume()

    poisoned = deepcopy(terminal)
    poisoned["trusted_business_decision"] = True
    with pytest.raises(
        EvidenceGraphContractError,
        match="EVIDENCE_RECOVERY_FORMAL_AUTHORITY_FORBIDDEN",
    ):
        validate_evidence_recovery_state(poisoned, admission=admission)


def test_terminal_extraction_fails_before_exact_manifest_coverage(
    admission_factory,
) -> None:
    admission = admission_factory(8)
    partial = new_evidence_graph_state(admission=admission)

    with pytest.raises(EvidenceGraphContractError, match="EVIDENCE_COMPLETE_COVERAGE_REQUIRED"):
        extract_evidence_terminal_proposal(
            partial,
            admission=admission,
            completed_at=COMPLETED_AT,
        )


@pytest.mark.parametrize(
    ("mode", "code"),
    [
        ("DISABLED", "EVIDENCE_RUNTIME_DISABLED"),
        ("TEMPORAL", "EVIDENCE_RUNTIME_MODE_FORBIDDEN"),
        ("REAL_SHADOW", "EVIDENCE_RUNTIME_MODE_FORBIDDEN"),
    ],
)
def test_runtime_allows_only_java_signed_synthetic_shadow(
    admission,
    assessment_factory,
    mode: str,
    code: str,
) -> None:
    with pytest.raises(EvidenceGraphContractError, match=code):
        _bundle(
            admission=admission,
            assessment=assessment_factory,
            saver=InMemorySaver(),
            mode=mode,
        )
