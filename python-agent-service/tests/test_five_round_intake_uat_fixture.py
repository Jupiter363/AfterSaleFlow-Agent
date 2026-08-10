from __future__ import annotations

import importlib.util
import sys
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest


REPLACEMENT_PURCHASE_FACT = (
    "该同类替代购买已经完成，人民币270元价款已经实际支付"
)
PRODUCT_CONDITION_FACT = "本案商品本身不存在质量或功能问题"
STATEMENT_ORDER_ANCHORS = (
    "争议标的是本案订单中的商品和加急配送服务",
    "本方诉求合计人民币300元",
    "双方无争议的是实际签收日晚于承诺日五天",
    "订单确认页对应承诺期限",
)


def _load_five_round_uat_contract() -> Any:
    script = (
        Path(__file__).resolve().parents[2]
        / ".local-dev"
        / "five-round-intake-api-uat.py"
    )
    module_name = "_five_round_intake_uat_fixture_contract"
    spec = importlib.util.spec_from_file_location(module_name, script)
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    return module


@pytest.mark.parametrize("rounds_per_party", range(2, 6))
def test_initiator_fixture_closes_semantic_facts_for_every_supported_round_count(
    monkeypatch: pytest.MonkeyPatch,
    rounds_per_party: int,
) -> None:
    contract = _load_five_round_uat_contract()
    captured_texts: list[str] = []
    idempotency_keys: list[str] = []

    def request_json(
        _context: object,
        stage: str,
        method: str,
        path: str,
        *,
        payload: dict[str, object] | None = None,
        extra_headers: dict[str, str] | None = None,
    ) -> tuple[int, dict[str, object]]:
        assert method == "POST"
        assert path == "/api/disputes/CASE_OFFLINE/rooms/INTAKE/messages"
        assert payload is not None
        assert payload["message_type"] == "PARTY_TEXT"
        assert payload["attachment_refs"] == []
        text = payload["text"]
        assert isinstance(text, str)
        captured_texts.append(text)
        assert extra_headers is not None
        idempotency_keys.append(extra_headers["Idempotency-Key"])
        return 201, {
            "data": {
                "message_type": "PARTY_TEXT",
                "sender_role": "USER",
                "agent_run_id": f"target-intake-run:offline:{stage}",
            }
        }

    monkeypatch.setattr(contract.uat, "request_json", request_json)
    context = SimpleNamespace(case_id="CASE_OFFLINE", actor_role="USER")

    run_ids = [
        contract.post_party_text(
            context,
            f"initiator_message_{ordinal}",
            ordinal,
            rounds_per_party,
        )
        for ordinal in range(2, rounds_per_party + 1)
    ]

    assert len(captured_texts) == rounds_per_party - 1
    assert len(run_ids) == len(set(run_ids)) == rounds_per_party - 1
    assert len(idempotency_keys) == len(set(idempotency_keys)) == rounds_per_party - 1
    assert all(
        key.startswith("five-round-message-") for key in idempotency_keys
    )

    assembled = "\n".join(captured_texts)
    assert {
        REPLACEMENT_PURCHASE_FACT: assembled.count(REPLACEMENT_PURCHASE_FACT),
        PRODUCT_CONDITION_FACT: assembled.count(PRODUCT_CONDITION_FACT),
    } == {
        REPLACEMENT_PURCHASE_FACT: 1,
        PRODUCT_CONDITION_FACT: 1,
    }
    assert [
        line for captured_text in captured_texts for line in captured_text.splitlines()
    ] == list(contract.PARTY_STATEMENTS["USER"])
    assert [assembled.index(anchor) for anchor in STATEMENT_ORDER_ANCHORS] == sorted(
        assembled.index(anchor) for anchor in STATEMENT_ORDER_ANCHORS
    )
    assert all(
        not text.endswith(contract.SUBMISSION_CONFIRMATION_SUFFIX)
        for text in captured_texts[:-1]
    )
    assert captured_texts[-1].endswith(contract.SUBMISSION_CONFIRMATION_SUFFIX)
    assert assembled.count(contract.SUBMISSION_CONFIRMATION_SUFFIX) == 1


def test_resume_waiting_party_uses_existing_case_without_import_or_committed_replay(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    contract = _load_five_round_uat_contract()
    for name in contract.RESUME_ENV_NAMES:
        monkeypatch.delenv(name, raising=False)
    assert contract.load_resume_configuration(2) is None

    monkeypatch.setenv(
        "UAT_RESUME_BOUNDARY",
        "INITIATOR_WAITING_PARTY_BEFORE_RESPONDENT",
    )
    with pytest.raises(contract.uat.UatFailure) as partial_failure:
        contract.load_resume_configuration(2)
    assert (
        partial_failure.value.stage,
        partial_failure.value.check,
    ) == ("configuration", "resume_configuration_complete")

    continuation_text = "A genuinely new continuation fact."
    resume_environment = {
        "UAT_INTAKE_ROUNDS_PER_PARTY": "2",
        "UAT_STOP_AFTER_EVIDENCE_OPENING": "true",
        "UAT_RESUME_BOUNDARY": "INITIATOR_WAITING_PARTY_BEFORE_RESPONDENT",
        "UAT_RESUME_CASE_ID": "CASE_RESUME_OFFLINE",
        "UAT_RESUME_INITIATOR_ID": "resume-user",
        "UAT_RESUME_RESPONDENT_ID": "resume-merchant",
        "UAT_RESUME_OPERATION_ID": "resume-operation-1",
        "UAT_RESUME_EXPECTED_PHASE": "WAITING_PARTY",
        "UAT_RESUME_EXPECTED_DOSSIER_VERSION": "2",
        "UAT_RESUME_EXPECTED_SOURCE_TURN": "2",
        "UAT_RESUME_EXPECTED_MATRIX_ID": "matrix-resume-2",
        "UAT_RESUME_EXPECTED_MATRIX_VERSION": "2",
        "UAT_RESUME_EXPECTED_MATRIX_HASH": "a" * 64,
        "UAT_RESUME_CONTINUATION_TEXT": continuation_text,
    }
    for name, value in resume_environment.items():
        monkeypatch.setenv(name, value)

    monkeypatch.setenv(
        "UAT_RESUME_CONTINUATION_TEXT",
        contract.PARTY_STATEMENTS["USER"][0],
    )
    with pytest.raises(contract.uat.UatFailure) as duplicate_fixture_failure:
        contract.load_resume_configuration(2)
    assert (
        duplicate_fixture_failure.value.stage,
        duplicate_fixture_failure.value.check,
    ) == ("configuration", "resume_continuation_text")
    monkeypatch.setenv("UAT_RESUME_CONTINUATION_TEXT", continuation_text)

    monkeypatch.setattr(
        contract.uat,
        "load_configuration",
        lambda: ("http://127.0.0.1:8081", 900.0),
    )

    def reject_unowned_flow(*_args: object, **_kwargs: object) -> Any:
        raise AssertionError("resume must not enter a fresh or downstream flow")

    monkeypatch.setattr(contract.uat, "import_user_case", reject_unowned_flow)
    monkeypatch.setattr(contract.uat, "ensure_opening", reject_unowned_flow)
    monkeypatch.setattr(
        contract.intake_opening_uat,
        "prepare_intake_infrastructure",
        reject_unowned_flow,
    )
    monkeypatch.setattr(contract, "post_party_text", reject_unowned_flow)
    monkeypatch.setattr(contract, "post_confirmation", reject_unowned_flow)
    monkeypatch.setattr(contract, "post_evidence_opening", reject_unowned_flow)

    baseline_matrix = {
        "schema_version": "case_fact_matrix.v2",
        "matrix_id": "matrix-resume-2",
        "matrix_version": 2,
        "content_hash": "a" * 64,
        "matrix_kind": "INITIATOR_FROZEN",
        "parent_ref": {
            "matrix_id": "matrix-resume-1",
            "matrix_version": 1,
            "content_hash": "b" * 64,
        },
        "party_map": {
            "initiator_role": "USER",
            "respondent_role": "MERCHANT",
        },
    }
    status_data = {
        "current_actor_role": "USER",
        "current_actor_completed": False,
        "can_use_intake": True,
        "can_enter_evidence": False,
        "process_projection": {
            "projection_state": "CURRENT",
            "writer_mode": "TEMPORAL",
            "command_admission_state": "READY",
            "room_phase": "WAITING_PARTY",
        },
    }
    case_data = {
        "id": "CASE_RESUME_OFFLINE",
        "user_id": "resume-user",
        "merchant_id": "resume-merchant",
        "initiator_role": "USER",
        "initiator_id": "resume-user",
        "respondent_role": "MERCHANT",
        "respondent_id": "drifted-merchant",
        "current_room": "INTAKE",
        "case_status": "INTAKE_PENDING",
    }
    memory_data = {
        "case_intake_dossier": {
            "case_id": "CASE_RESUME_OFFLINE",
            "room_type": "INTAKE",
            "dossier_version": 2,
            "source_turn_no": 2,
            "ready_for_next_step": False,
            "admission_recommendation": "NEED_MORE_INFO",
            "dossier": {"case_fact_matrix": baseline_matrix},
        }
    }
    intake_messages = [
        {
            "id": "message-old-formal-1",
            "case_id": "CASE_RESUME_OFFLINE",
            "room_id": "room-intake-offline",
            "sequence_no": 1,
            "message_type": "AGENT_MESSAGE",
            "sender_role": "INTAKE_OFFICER",
            "sender_id": "intake-agent",
            "agent_run_id": "run-old-1",
            "message_text": "Prior formal opening.",
        },
        {
            "id": "message-old-party-2",
            "case_id": "CASE_RESUME_OFFLINE",
            "room_id": "room-intake-offline",
            "sequence_no": 2,
            "message_type": "PARTY_TEXT",
            "sender_role": "USER",
            "sender_id": "resume-user",
            "agent_run_id": "run-old-2",
            "message_text": contract.PARTY_STATEMENTS["USER"][0],
        },
        {
            "id": "message-old-formal-2",
            "case_id": "CASE_RESUME_OFFLINE",
            "room_id": "room-intake-offline",
            "sequence_no": 3,
            "message_type": "AGENT_MESSAGE",
            "sender_role": "INTAKE_OFFICER",
            "sender_id": "intake-agent",
            "agent_run_id": "run-old-2",
            "message_text": "Prior formal follow-up.",
        },
    ]
    continuation_response: dict[str, object] = {
        "id": "message-new-3",
        "case_id": "CASE_RESUME_OFFLINE",
        "room_id": "room-intake-offline",
        "message_type": "PARTY_TEXT",
        "sender_role": "USER",
        "sender_id": "resume-user",
        "agent_run_id": "run-new-3",
        "message_text": continuation_text,
        "attachment_refs": [],
    }
    request_log: list[
        tuple[
            str,
            str,
            str,
            dict[str, object] | None,
            dict[str, str] | None,
        ]
    ] = []

    def request_json(
        context: object,
        stage: str,
        method: str,
        path: str,
        *,
        payload: dict[str, object] | None = None,
        extra_headers: dict[str, str] | None = None,
    ) -> tuple[int, dict[str, object]]:
        assert getattr(context, "case_id") == "CASE_RESUME_OFFLINE"
        assert getattr(context, "user_id") == "resume-user"
        assert getattr(context, "merchant_id") == "resume-merchant"
        assert getattr(context, "actor_role") == "USER"
        request_log.append((stage, method, path, payload, extra_headers))
        if path.endswith("/intake/status"):
            assert method == "GET"
            return 200, {"data": status_data}
        if path.endswith("/rooms/INTAKE/turn-memory/latest"):
            assert method == "GET"
            return 200, {"data": memory_data}
        if path.endswith("/rooms/INTAKE/messages") and method == "GET":
            return 200, {"data": intake_messages}
        if path == "/api/disputes/CASE_RESUME_OFFLINE":
            assert method == "GET"
            return 200, {"data": case_data}
        if path in {"/api/agent-runs/run-old-1", "/api/agent-runs/run-old-2"}:
            assert method == "GET"
            return 200, {
                "data": {
                    "run_id": path.rsplit("/", 1)[-1],
                    "status": "COMPLETED",
                }
            }
        if path.endswith("/rooms/INTAKE/messages") and method == "POST":
            assert payload == {
                "message_type": "PARTY_TEXT",
                "text": continuation_text,
                "attachment_refs": [],
            }
            assert extra_headers == {
                "Idempotency-Key": (
                    "five-round-resume-resume-operation-1"
                    "-initiator-continuation"
                )
            }
            return 201, {"data": dict(continuation_response)}
        raise AssertionError((stage, method, path))

    monkeypatch.setattr(contract.uat, "request_json", request_json)
    observed_runs: list[tuple[str, str]] = []

    def observe_agent_run(_context: object, stage: str, run_id: str) -> None:
        observed_runs.append((stage, run_id))

    monkeypatch.setattr(contract.uat, "observe_agent_run", observe_agent_run)
    formal_calls: list[tuple[str, str, int, int, object]] = []

    def wait_for_formal_turn(
        _context: object,
        stage: str,
        run_id: str,
        *,
        expected_version: int | None,
        expected_dossier_version: int,
        previous: object,
        allow_carry_forward: bool = False,
    ) -> object:
        formal_calls.append(
            (
                stage,
                run_id,
                expected_version or -1,
                expected_dossier_version,
                previous,
            )
        )
        assert allow_carry_forward is False
        assert isinstance(previous, contract.MatrixState)
        return contract.MatrixState(
            matrix_id="matrix-resume-3",
            version=3,
            content_hash="c" * 64,
            kind="INITIATOR_FROZEN",
            parent_matrix_id=previous.matrix_id,
            parent_version=previous.version,
            parent_content_hash=previous.content_hash,
        )

    monkeypatch.setattr(contract, "wait_for_formal_turn", wait_for_formal_turn)
    semantic_calls: list[tuple[str, int, int]] = []

    def require_semantic_ready(
        _context: object,
        stage: str,
        *,
        expected_dossier_version: int,
        expected_source_turn_no: int,
    ) -> None:
        semantic_calls.append(
            (stage, expected_dossier_version, expected_source_turn_no)
        )

    monkeypatch.setattr(contract, "require_semantic_ready", require_semantic_ready)

    actor_drift_stages: list[str] = []
    with pytest.raises(contract.uat.UatFailure) as actor_drift_failure:
        contract.execute(
            SimpleNamespace(
                values={},
                measure=lambda stage, operation: (
                    actor_drift_stages.append(stage), operation()
                )[1],
            )
        )
    assert (
        actor_drift_failure.value.stage,
        actor_drift_failure.value.check,
    ) == ("resume_preflight", "case_respondent_authority")
    assert [(method, path) for _, method, path, _, _ in request_log] == [
        ("GET", "/api/disputes/CASE_RESUME_OFFLINE/intake/status"),
        (
            "GET",
            "/api/disputes/CASE_RESUME_OFFLINE/rooms/INTAKE/turn-memory/latest",
        ),
        ("GET", "/api/disputes/CASE_RESUME_OFFLINE/rooms/INTAKE/messages"),
        ("GET", "/api/disputes/CASE_RESUME_OFFLINE"),
    ]
    assert actor_drift_stages == ["resume_preflight"]

    case_data["respondent_id"] = "resume-merchant"
    request_log.clear()
    committed_fixture_text = intake_messages[1]["message_text"]
    intake_messages[1]["message_text"] = continuation_text
    with pytest.raises(contract.uat.UatFailure) as ambiguous_retry_failure:
        contract.execute(
            SimpleNamespace(
                values={},
                measure=lambda _stage, operation: operation(),
            )
        )
    assert (
        ambiguous_retry_failure.value.stage,
        ambiguous_retry_failure.value.check,
    ) == ("resume_preflight", "ambiguous_continuation_retry_forbidden")
    assert all(method == "GET" for _, method, _, _, _ in request_log)
    intake_messages[1]["message_text"] = committed_fixture_text
    request_log.clear()

    original_message_type = intake_messages[0]["message_type"]
    intake_messages[0]["message_type"] = "UNKNOWN_MESSAGE"
    with pytest.raises(contract.uat.UatFailure) as unknown_inventory_failure:
        contract.execute(
            SimpleNamespace(
                values={},
                measure=lambda _stage, operation: operation(),
            )
        )
    assert (
        unknown_inventory_failure.value.stage,
        unknown_inventory_failure.value.check,
    ) == ("resume_preflight", "message_type")
    intake_messages[0]["message_type"] = original_message_type
    request_log.clear()

    unknown_formal = dict(intake_messages[0])
    unknown_formal["id"] = "message-unknown-formal"
    unknown_formal["sequence_no"] = 4
    unknown_formal["agent_run_id"] = "run-unknown"
    intake_messages.append(unknown_formal)
    with pytest.raises(contract.uat.UatFailure) as unknown_formal_failure:
        contract.execute(
            SimpleNamespace(
                values={},
                measure=lambda _stage, operation: operation(),
            )
        )
    assert (
        unknown_formal_failure.value.stage,
        unknown_formal_failure.value.check,
    ) == ("resume_preflight", "committed_run_count")
    intake_messages.pop()
    request_log.clear()

    duplicate_formal = dict(intake_messages[0])
    duplicate_formal["id"] = "message-duplicate-formal-1"
    duplicate_formal["sequence_no"] = 4
    intake_messages.append(duplicate_formal)
    with pytest.raises(contract.uat.UatFailure) as duplicate_formal_failure:
        contract.execute(
            SimpleNamespace(
                values={},
                measure=lambda _stage, operation: operation(),
            )
        )
    assert (
        duplicate_formal_failure.value.stage,
        duplicate_formal_failure.value.check,
    ) == ("resume_preflight", "formal_message_count")
    intake_messages.pop()
    request_log.clear()

    for field, invalid_value, expected_check in (
        ("case_id", "CASE_WRONG", "continuation_case_authority"),
        ("room_id", "room-wrong", "continuation_room_authority"),
    ):
        original_value = continuation_response[field]
        continuation_response[field] = invalid_value
        with pytest.raises(contract.uat.UatFailure) as response_authority_failure:
            contract.execute(
                SimpleNamespace(
                    values={},
                    measure=lambda _stage, operation: operation(),
                )
            )
        assert (
            response_authority_failure.value.stage,
            response_authority_failure.value.check,
        ) == ("resume_initiator_continuation", expected_check)
        continuation_response[field] = original_value
        request_log.clear()

    continuation_response["sender_type"] = "AGENT"
    with pytest.raises(contract.uat.UatFailure) as sender_type_failure:
        contract.execute(
            SimpleNamespace(
                values={},
                measure=lambda _stage, operation: operation(),
            )
        )
    assert (
        sender_type_failure.value.stage,
        sender_type_failure.value.check,
    ) == ("resume_initiator_continuation", "sender_authority")
    continuation_response.pop("sender_type")
    request_log.clear()

    for field, expected_check in (
        ("message_text", "message_text"),
        ("attachment_refs", "attachment_refs"),
    ):
        original_value = continuation_response.pop(field)
        with pytest.raises(contract.uat.UatFailure) as missing_response_failure:
            contract.execute(
                SimpleNamespace(
                    values={},
                    measure=lambda _stage, operation: operation(),
                )
            )
        assert (
            missing_response_failure.value.stage,
            missing_response_failure.value.check,
        ) == ("resume_initiator_continuation", expected_check)
        continuation_response[field] = original_value
        request_log.clear()

    assert observed_runs == []
    assert formal_calls == []
    assert semantic_calls == []

    timing_stages: list[str] = []

    def measure(stage: str, operation: Any) -> Any:
        timing_stages.append(stage)
        return operation()

    timings = SimpleNamespace(values={}, measure=measure)
    summary = contract.execute(timings)

    assert summary == {
        "case_id": "CASE_RESUME_OFFLINE",
        "configured_intake_rounds_per_party": 2,
        "resumed_existing_case": True,
        "resume_boundary": "INITIATOR_WAITING_PARTY_BEFORE_RESPONDENT",
        "resume_new_message_id": "message-new-3",
        "resume_new_agent_run_id": "run-new-3",
        "resume_baseline_agent_runs": 2,
        "initiator_rounds": 3,
        "final_room": "INTAKE",
        "final_matrix_version": 3,
        "stopped_after": "INITIATOR_READY_TO_CONFIRM",
    }
    assert timing_stages == [
        "resume_preflight",
        "resume_initiator_continuation",
        "resume_initiator_stream",
        "resume_initiator_formal",
        "resume_initiator_semantic_readiness",
    ]
    assert [(method, path) for _, method, path, _, _ in request_log] == [
        ("GET", "/api/disputes/CASE_RESUME_OFFLINE/intake/status"),
        (
            "GET",
            "/api/disputes/CASE_RESUME_OFFLINE/rooms/INTAKE/turn-memory/latest",
        ),
        ("GET", "/api/disputes/CASE_RESUME_OFFLINE/rooms/INTAKE/messages"),
        ("GET", "/api/disputes/CASE_RESUME_OFFLINE"),
        ("GET", "/api/agent-runs/run-old-1"),
        ("GET", "/api/agent-runs/run-old-2"),
        ("POST", "/api/disputes/CASE_RESUME_OFFLINE/rooms/INTAKE/messages"),
    ]
    assert observed_runs == [("resume_initiator_stream", "run-new-3")]
    assert len(formal_calls) == 1
    formal_stage, formal_run, matrix_version, dossier_version, previous = formal_calls[0]
    assert (
        formal_stage,
        formal_run,
        matrix_version,
        dossier_version,
        previous.matrix_id,
        previous.version,
        previous.content_hash,
    ) == (
        "resume_initiator_formal",
        "run-new-3",
        3,
        3,
        "matrix-resume-2",
        2,
        "a" * 64,
    )
    assert semantic_calls == [("resume_initiator_semantic_readiness", 3, 3)]
