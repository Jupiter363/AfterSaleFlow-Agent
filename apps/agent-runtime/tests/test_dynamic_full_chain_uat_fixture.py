from __future__ import annotations

import importlib.util
import sys
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest


def _load_runner() -> Any:
    path = (
        Path(__file__).resolve().parents[3]
        / ".local-dev"
        / "run-dynamic-five-round-uat.py"
    )
    module_name = "_dynamic_full_chain_uat_fixture_contract"
    spec = importlib.util.spec_from_file_location(module_name, path)
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    return module


def _catalog_item(runner: Any, evidence_id: str, user_id: str) -> dict[str, Any]:
    fixture = runner.ACTIVE_FIXTURE["evidence"]
    return {
        "evidence_id": evidence_id,
        "evidence_type": fixture["evidence_type"],
        "submitted_by_role": "USER",
        "submitted_by_id": user_id,
        "visibility": fixture["visibility"],
        "source_type": "USER_UPLOAD",
        "original_filename": fixture["filename"],
        "submission_status": "SUBMITTED",
        "claimed_fact": fixture["claimed_fact"],
        "truth_attested": True,
    }


def test_canonical_fixture_permits_only_user_evidence_upload() -> None:
    runner = _load_runner()

    assert runner.CANONICAL_EVIDENCE_SUBMITTERS == ("USER",)
    merchant = SimpleNamespace(actor_role="MERCHANT")
    with pytest.raises(runner.uat_module.uat.UatFailure) as failure:
        runner.upload_canonical_evidence(merchant, "merchant_fixture_upload")

    assert (failure.value.stage, failure.value.check) == (
        "merchant_fixture_upload",
        "canonical_evidence_submitter",
    )


def test_canonical_catalog_guard_accepts_exactly_one_fixture_evidence(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runner = _load_runner()
    context = SimpleNamespace(
        case_id="CASE_CANONICAL",
        user_id="canonical-user",
        actor_role="USER",
    )
    evidence_id = "EVIDENCE_CANONICAL"
    item = _catalog_item(runner, evidence_id, context.user_id)

    monkeypatch.setattr(
        runner.uat_module.uat,
        "request_json",
        lambda *_args, **_kwargs: (200, {"data": {"items": [item]}}),
    )

    assert (
        runner.assert_single_canonical_evidence(
            context, "single_fixture_catalog", evidence_id
        )
        == item
    )


def test_canonical_catalog_guard_rejects_duplicate_fixture_evidence(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runner = _load_runner()
    context = SimpleNamespace(
        case_id="CASE_CANONICAL",
        user_id="canonical-user",
        actor_role="USER",
    )
    evidence_id = "EVIDENCE_CANONICAL"
    item = _catalog_item(runner, evidence_id, context.user_id)
    duplicate = dict(item, evidence_id="EVIDENCE_CANONICAL_DUPLICATE")
    monkeypatch.setattr(
        runner.uat_module.uat,
        "request_json",
        lambda *_args, **_kwargs: (
            200,
            {"data": {"items": [item, duplicate]}},
        ),
    )

    with pytest.raises(runner.uat_module.uat.UatFailure) as failure:
        runner.assert_single_canonical_evidence(
            context, "duplicate_fixture_catalog", evidence_id
        )

    assert (failure.value.stage, failure.value.check) == (
        "duplicate_fixture_catalog",
        "single_fixture_evidence",
    )
