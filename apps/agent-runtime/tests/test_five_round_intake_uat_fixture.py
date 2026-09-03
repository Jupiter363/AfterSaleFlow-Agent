from __future__ import annotations

import importlib.util
import inspect
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
        Path(__file__).resolve().parents[3]
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


def test_fresh_party_ids_use_browser_actor_authority(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    contract = _load_five_round_uat_contract()
    monkeypatch.setenv(contract.FRESH_USER_ID_ENV, "user-local")
    monkeypatch.setenv(contract.FRESH_MERCHANT_ID_ENV, "merchant-local")

    assert contract.load_fresh_party_ids() == ("user-local", "merchant-local")


def test_fresh_party_ids_reject_a_partial_override(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    contract = _load_five_round_uat_contract()
    monkeypatch.setenv(contract.FRESH_USER_ID_ENV, "user-local")
    monkeypatch.delenv(contract.FRESH_MERCHANT_ID_ENV, raising=False)

    with pytest.raises(contract.uat.UatFailure) as failure:
        contract.load_fresh_party_ids()

    assert failure.value.stage == "configuration"
    assert failure.value.check == "fresh_party_ids_pair"


def test_two_round_initiator_fixture_closes_declared_handoff_gaps() -> None:
    contract = _load_five_round_uat_contract()

    statement = contract.fixture_party_statement("USER", 2, 2)

    assert "2026年7月12日" in statement
    assert "与本案订单商品同品类、同规格" in statement
    assert "7月12日不可延期的预定用途" in statement
    assert "2026年7月12日09:20保存的物流轨迹截图" in statement
    assert "运输中，预计7月15日送达" in statement
    assert "LOG-20260712-0920" in statement
    assert "2026年7月16日的书面客服聊天记录" in statement
    assert "明确拒绝承担270元替代购买费用" in statement
    assert statement.endswith(contract.SUBMISSION_CONFIRMATION_SUFFIX)


def test_three_round_merchant_fixture_closes_declared_handoff_gaps() -> None:
    contract = _load_five_round_uat_contract()

    statement = contract.fixture_party_statement("MERCHANT", 3, 3)

    assert "WH-20260709-1840" in statement
    assert "LOG-M-20260712-1100" in statement
    assert "两份原始记录均可提交核验" in statement
    assert "未通知本方并给予改派或退款的处理机会" in statement
    assert "必要性、合理性和因果关系尚未证明" in statement
    assert statement.endswith(contract.SUBMISSION_CONFIRMATION_SUFFIX)


def test_synthetic_evidence_upload_source_type_is_actor_exact() -> None:
    contract = _load_five_round_uat_contract()

    assert contract.synthetic_evidence_source_type("USER", "evidence_upload") == (
        "USER_UPLOAD"
    )
    assert contract.synthetic_evidence_source_type(
        "MERCHANT", "respondent_evidence_upload"
    ) == "MERCHANT_UPLOAD"
    with pytest.raises(contract.uat.UatFailure) as failure:
        contract.synthetic_evidence_source_type("EVIDENCE_CLERK", "evidence_upload")

    assert failure.value.stage == "evidence_upload"
    assert failure.value.check == "evidence_actor_role"


def test_parallel_projection_count_matches_each_frame_contract() -> None:
    contract = _load_five_round_uat_contract()

    assert contract.valid_parallel_projection_count("DIALOGUE_FRAME", 1)
    assert not contract.valid_parallel_projection_count("DIALOGUE_FRAME", 0)
    assert contract.valid_parallel_projection_count("DOSSIER_FRAME", 0)
    assert contract.valid_parallel_projection_count("DOSSIER_FRAME", 5)
    assert not contract.valid_parallel_projection_count("DOSSIER_FRAME", 6)
    assert contract.valid_parallel_projection_count("QUALITY_FRAME", 6)
    assert contract.valid_parallel_projection_count("QUALITY_FRAME", 12)
    assert not contract.valid_parallel_projection_count("QUALITY_FRAME", 5)
    assert contract.ParallelIntakeFrameTiming(
        first_projection_seconds=None,
        winning_generation_first_projection_seconds=None,
        sealed_seconds=1.0,
        reset_count=0,
        winning_generation=1,
        projection_items=0,
    ).public_value()["first_projection_seconds"] is None


def test_handoff_formal_turn_carries_exact_matrix_when_dossier_is_ready(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    contract = _load_five_round_uat_contract()
    previous = contract.MatrixState(
        "CASE_MATRIX_INITIATOR",
        2,
        "a" * 64,
        "INITIATOR_FROZEN",
        None,
        None,
        None,
        "WAITING_PARTY",
    )

    def exact_matrix_state(
        matrix: dict[str, object],
        stage: str,
        *,
        expected_version: int | None,
        previous: object,
    ) -> object:
        assert matrix == {"matrixVersion": 2}
        assert stage == "handoff_formal"
        assert expected_version == 2
        assert previous is None
        return contract.MatrixState(
            "CASE_MATRIX_INITIATOR",
            2,
            "a" * 64,
            "INITIATOR_FROZEN",
            None,
            None,
            None,
        )

    monkeypatch.setattr(contract, "matrix_state", exact_matrix_state)

    result = contract.formal_turn_matrix_state(
        {"matrixVersion": 2},
        {"dossierVersion": 3, "readyForNextStep": True},
        "handoff_formal",
        expected_matrix_version=None,
        expected_dossier_version=3,
        previous=previous,
        allow_carry_forward=True,
    )

    assert result == contract.MatrixState(
        "CASE_MATRIX_INITIATOR",
        2,
        "a" * 64,
        "INITIATOR_FROZEN",
        None,
        None,
        None,
    )


def test_handoff_formal_turn_accepts_exact_single_matrix_successor() -> None:
    contract = _load_five_round_uat_contract()
    previous = contract.MatrixState(
        "CASE_MATRIX_PARENT",
        4,
        "a" * 64,
        "BILATERAL_FROZEN",
        "CASE_MATRIX_GRANDPARENT",
        3,
        "b" * 64,
        "WAITING_PARTY",
    )
    matrix = {
        "schemaVersion": "case_fact_matrix.v2",
        "matrixId": "CASE_MATRIX_SUCCESSOR",
        "matrixVersion": 5,
        "contentHash": "c" * 64,
        "matrixKind": "BILATERAL_FROZEN",
        "parentRef": {
            "matrixId": previous.matrix_id,
            "matrixVersion": previous.version,
            "contentHash": previous.content_hash,
        },
        "partyMap": {
            "initiatorRole": "USER",
            "respondentRole": "MERCHANT",
        },
    }

    result = contract.formal_turn_matrix_state(
        matrix,
        {"dossierVersion": 7, "readyForNextStep": True},
        "respondent_handoff_formal",
        expected_matrix_version=None,
        expected_dossier_version=7,
        previous=previous,
        allow_carry_forward=True,
    )

    assert result == contract.MatrixState(
        "CASE_MATRIX_SUCCESSOR",
        5,
        "c" * 64,
        "BILATERAL_FROZEN",
        previous.matrix_id,
        previous.version,
        previous.content_hash,
    )


def test_persisted_matrix_ignores_observed_room_phase(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    contract = _load_five_round_uat_contract()
    matrix = {
        "schemaVersion": "case_fact_matrix.v2",
        "matrixId": "CASE_MATRIX_FINAL",
        "matrixVersion": 5,
        "contentHash": "c" * 64,
        "matrixKind": "BILATERAL_FROZEN",
        "parentRef": {
            "matrixId": "CASE_MATRIX_PARENT",
            "matrixVersion": 4,
            "contentHash": "b" * 64,
        },
        "partyMap": {
            "initiatorRole": "USER",
            "respondentRole": "MERCHANT",
        },
    }
    expected = contract.MatrixState(
        "CASE_MATRIX_FINAL",
        5,
        "c" * 64,
        "BILATERAL_FROZEN",
        "CASE_MATRIX_PARENT",
        4,
        "b" * 64,
        "READY_TO_CONFIRM",
    )

    def request_json(
        _context: object,
        stage: str,
        method: str,
        path: str,
    ) -> tuple[int, dict[str, object]]:
        assert stage == "final_matrix_persisted"
        assert method == "GET"
        assert path.endswith("/rooms/INTAKE/turn-memory/latest")
        return 200, {
            "data": {
                "caseIntakeDossier": {
                    "caseId": "CASE_FINAL",
                    "roomType": "INTAKE",
                    "dossierVersion": 8,
                    "sourceTurnNo": 3,
                    "readyForNextStep": True,
                    "qualityScore": 100,
                    "admissionRecommendation": "ADMIT",
                    "updatedAt": "2026-09-02T00:22:00+08:00",
                    "dossier": {"caseFactMatrix": matrix},
                }
            }
        }

    monkeypatch.setattr(contract.uat, "request_json", request_json)
    context = SimpleNamespace(case_id="CASE_FINAL")

    persisted = contract.wait_for_persisted_matrix(
        context,
        "final_matrix_persisted",
        expected,
        expected_dossier_version=8,
        expected_source_turn_no=3,
    )

    assert persisted == contract.replace(expected, room_phase=None)


def test_final_matrix_validation_uses_post_handoff_source_turn() -> None:
    contract = _load_five_round_uat_contract()
    execute_source = inspect.getsource(contract.execute)
    final_matrix_block = execute_source.split(
        '"final_matrix_persisted",', 1
    )[1].split("baseline_evidence_messages", 1)[0]

    assert "expected_source_turn_no=rounds_per_party + 1" in final_matrix_block


def test_respondent_opening_uses_protocol_aware_stream_observer() -> None:
    contract = _load_five_round_uat_contract()
    execute_source = inspect.getsource(contract.execute)
    respondent_opening = execute_source.split(
        "respondent_run = timings.measure(", 1
    )[1].split("respondent_formal_1", 1)[0]

    assert "observe_intake_agent_run(" in respondent_opening
    assert "uat.observe_agent_run(" not in respondent_opening


@pytest.mark.parametrize(
    ("envelope", "expected"),
    (
        (
            {
                "code": "CASE_STATUS_INVALID",
                "message": "expected process revision is already reserved by an active command",
                "details": {
                    "case_id": "CASE_OFFLINE",
                    "expected_process_revision": 8,
                },
            },
            True,
        ),
        (
            {
                "code": "CASE_STATUS_INVALID",
                "message": "expected process revision is stale",
                "details": {
                    "expected_process_revision": 8,
                    "current_process_revision": 9,
                    "epoch_process_revision": 9,
                },
            },
            True,
        ),
        (
            {
                "code": "CASE_STATUS_INVALID",
                "message": "expected process revision is already reserved by an active command",
                "details": {
                    "case_id": "CASE_OTHER",
                    "expected_process_revision": 8,
                },
            },
            False,
        ),
        (
            {
                "code": "CASE_STATUS_INVALID",
                "message": "command does not target the active room epoch",
                "details": {"expected_process_revision": 8},
            },
            False,
        ),
    ),
)
def test_process_revision_retry_is_limited_to_authoritative_races(
    envelope: dict[str, object], expected: bool
) -> None:
    contract = _load_five_round_uat_contract()

    assert contract.process_revision_retryable(envelope, "CASE_OFFLINE") is expected


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


def test_handoff_bridge_requires_one_visible_persisted_question_before_post(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    contract = _load_five_round_uat_contract()
    question = "请确认以上案情与诉求是否完整，如有遗漏请补充最后一项可核验事实？"
    calls: list[tuple[str, str, object]] = []

    def request_json(
        _context: object,
        _stage: str,
        method: str,
        path: str,
        *,
        payload: dict[str, object] | None = None,
        extra_headers: dict[str, str] | None = None,
    ) -> tuple[int, dict[str, object]]:
        calls.append((method, path, payload))
        if method == "GET":
            return 200, {
                "data": {
                    "agent_response": f"已记录本轮陈述。 {question}",
                    "case_intake_dossier": {
                        "dossier_version": 2,
                        "source_turn_no": 2,
                        "dossier": {
                            "party_intake_state": {
                                "USER": {
                                    "handoff_notes": {
                                        "remark_status": "READY_PENDING_REMARK_INVITE"
                                    },
                                    "missing_information": {
                                        "next_questions": [question]
                                    },
                                }
                            }
                        },
                    },
                }
            }
        assert payload is not None
        assert payload["text"] == contract.HANDOFF_BRIDGE_STATEMENTS["USER"]
        assert extra_headers is not None
        assert extra_headers["Idempotency-Key"].startswith("five-round-handoff-")
        return 201, {
            "data": {
                "message_text": payload["text"],
                "agent_run_id": "target-intake-run:handoff-bridge",
            }
        }

    monkeypatch.setattr(contract.uat, "request_json", request_json)
    context = SimpleNamespace(case_id="CASE_OFFLINE", actor_role="USER")

    run_id = contract.post_handoff_bridge(
        context,
        "initiator_handoff_bridge",
        expected_dossier_version=2,
        expected_source_turn_no=2,
    )

    assert run_id == "target-intake-run:handoff-bridge"
    assert [method for method, _, _ in calls] == ["GET", "POST"]


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


def test_pending_invite_resume_boundary_binds_exact_successful_coordinates(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    contract = _load_five_round_uat_contract()
    for name in (*contract.RESUME_ENV_NAMES, *contract.FAILED_TURN_RESUME_ENV_NAMES):
        monkeypatch.delenv(name, raising=False)
    values = {
        contract.RESUME_BOUNDARY_ENV: contract.INITIATOR_READY_PENDING_REMARK_INVITE,
        contract.RESUME_CASE_ID_ENV: "CASE_PENDING_INVITE",
        contract.RESUME_INITIATOR_ID_ENV: "resume-user",
        contract.RESUME_RESPONDENT_ID_ENV: "resume-merchant",
        contract.RESUME_OPERATION_ID_ENV: "pending-invite-v1",
        contract.RESUME_EXPECTED_PHASE_ENV: "WAITING_PARTY",
        contract.RESUME_EXPECTED_DOSSIER_VERSION_ENV: "3",
        contract.RESUME_EXPECTED_SOURCE_TURN_ENV: "3",
        contract.RESUME_EXPECTED_MATRIX_ID_ENV: "matrix-pending-3",
        contract.RESUME_EXPECTED_MATRIX_VERSION_ENV: "3",
        contract.RESUME_EXPECTED_MATRIX_HASH_ENV: "a" * 64,
        contract.RESUME_CONTINUATION_TEXT_ENV: "Exact optional-remark answer.",
    }
    for name, value in values.items():
        monkeypatch.setenv(name, value)

    configuration = contract.load_resume_configuration(3)

    assert configuration is not None
    assert configuration.boundary == contract.INITIATOR_READY_PENDING_REMARK_INVITE
    assert configuration.expected_dossier_version == 3
    assert configuration.expected_matrix_version == 3
    assert configuration.expected_failed_run_id is None

    baseline = contract.MatrixState(
        "matrix-pending-3",
        3,
        "a" * 64,
        "INITIATOR_FROZEN",
        "matrix-pending-2",
        2,
        "b" * 64,
    )
    monkeypatch.setattr(
        contract,
        "require_resume_preflight",
        lambda *_args, **_kwargs: contract.ResumePreflight(
            matrix=baseline,
            room_id="room-pending",
            message_ids=frozenset({"message-old"}),
            run_ids=frozenset({"run-old"}),
            committed_texts=frozenset(),
        ),
    )
    monkeypatch.setattr(
        contract,
        "post_resume_continuation",
        lambda *_args, **_kwargs: ("message-handoff-4", "run-handoff-4"),
    )
    monkeypatch.setattr(contract, "observe_intake_agent_run", lambda *_a, **_k: None)
    monkeypatch.setattr(contract, "record_parallel_intake_timing", lambda *_a, **_k: None)

    def formal_turn(
        _context: object,
        _stage: str,
        _run_id: str,
        *,
        expected_version: int | None,
        expected_dossier_version: int,
        previous: object,
        allow_carry_forward: bool,
    ) -> object:
        assert expected_version is None
        assert expected_dossier_version == 4
        assert previous == baseline
        assert allow_carry_forward is True
        return contract.replace(baseline, room_phase="READY_TO_CONFIRM")

    monkeypatch.setattr(contract, "wait_for_formal_turn", formal_turn)
    monkeypatch.setattr(
        contract,
        "post_handoff_bridge",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            AssertionError("pending-invite continuation is already the bridge")
        ),
    )
    semantic_authority: list[tuple[int, int]] = []
    monkeypatch.setattr(
        contract,
        "require_semantic_ready",
        lambda _context, _stage, *, expected_dossier_version, expected_source_turn_no: semantic_authority.append(
            (expected_dossier_version, expected_source_turn_no)
        ),
    )
    summary = contract.execute_resume_waiting_party(
        SimpleNamespace(values={}, measure=lambda _stage, operation: operation()),
        SimpleNamespace(case_id=configuration.case_id),
        configuration,
        rounds_per_party=3,
    )

    assert summary["resume_new_agent_run_ids"] == ["run-handoff-4"]
    assert summary["initiator_rounds"] == 3
    assert summary["final_matrix_version"] == 3
    assert semantic_authority == [(4, 4)]


def test_failed_tail_resume_stops_before_next_post_when_formal_turn_is_ready(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    contract = _load_five_round_uat_contract()
    baseline = contract.MatrixState(
        matrix_id="matrix-ready-2",
        version=2,
        content_hash="a" * 64,
        kind="INITIATOR_FROZEN",
        parent_matrix_id="matrix-ready-1",
        parent_version=1,
        parent_content_hash="b" * 64,
    )
    preflight = contract.ResumePreflight(
        matrix=baseline,
        room_id="room-ready",
        message_ids=frozenset({"message-old"}),
        run_ids=frozenset({"run-old"}),
        committed_texts=frozenset(),
    )
    configuration = contract.ResumeConfiguration(
        boundary=contract.INITIATOR_WAITING_PARTY_AFTER_FAILED_TURN,
        case_id="CASE_READY_AFTER_RESUME",
        initiator_id="resume-user",
        respondent_id="resume-merchant",
        operation_id="ready-after-resume-v1",
        expected_phase="WAITING_PARTY",
        expected_dossier_version=2,
        expected_source_turn=2,
        expected_matrix_id=baseline.matrix_id,
        expected_matrix_version=baseline.version,
        expected_matrix_hash=baseline.content_hash,
        continuation_text="New exact continuation.",
        expected_failed_message_id="message-failed",
        expected_failed_run_id="run-failed",
        expected_failed_error_code="INTAKE_PARALLEL_FRAME_BATCH_FAILED",
    )
    monkeypatch.setattr(
        contract,
        "require_resume_preflight",
        lambda *_args, **_kwargs: preflight,
    )
    monkeypatch.setattr(
        contract,
        "post_resume_continuation",
        lambda *_args, **_kwargs: ("message-new-3", "run-new-3"),
    )
    monkeypatch.setattr(
        contract,
        "observe_intake_agent_run",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        contract,
        "record_parallel_intake_timing",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        contract,
        "wait_for_formal_turn",
        lambda *_args, **_kwargs: contract.MatrixState(
            matrix_id="matrix-ready-3",
            version=3,
            content_hash="c" * 64,
            kind="INITIATOR_FROZEN",
            parent_matrix_id=baseline.matrix_id,
            parent_version=baseline.version,
            parent_content_hash=baseline.content_hash,
            room_phase="READY_TO_CONFIRM",
        ),
    )

    def reject_extra_post(*_args: object, **_kwargs: object) -> str:
        raise AssertionError("READY_TO_CONFIRM must stop the next PARTY post")

    monkeypatch.setattr(contract, "post_party_text", reject_extra_post)
    semantic_authority: list[tuple[int, int]] = []
    monkeypatch.setattr(
        contract,
        "require_semantic_ready",
        lambda _context, _stage, *, expected_dossier_version, expected_source_turn_no: (
            semantic_authority.append(
                (expected_dossier_version, expected_source_turn_no)
            )
        ),
    )
    timings = SimpleNamespace(
        values={},
        measure=lambda _stage, operation: operation(),
    )

    summary = contract.execute_resume_waiting_party(
        timings,
        SimpleNamespace(case_id=configuration.case_id),
        configuration,
        rounds_per_party=5,
    )

    assert summary["resume_new_agent_run_ids"] == ["run-new-3"]
    assert summary["initiator_rounds"] == 3
    assert summary["final_matrix_version"] == 3
    assert semantic_authority == [(3, 3)]


def test_mid_round_resume_posts_pending_invite_bridge_before_semantic_readiness(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    contract = _load_five_round_uat_contract()
    baseline = contract.MatrixState(
        matrix_id="matrix-mid-2",
        version=2,
        content_hash="a" * 64,
        kind="INITIATOR_FROZEN",
        parent_matrix_id="matrix-mid-1",
        parent_version=1,
        parent_content_hash="b" * 64,
    )
    configuration = contract.ResumeConfiguration(
        boundary=contract.INITIATOR_WAITING_PARTY_MID_ROUNDS,
        case_id="CASE_MID_BRIDGE",
        initiator_id="resume-user",
        respondent_id="resume-merchant",
        operation_id="mid-bridge-v1",
        expected_phase="WAITING_PARTY",
        expected_dossier_version=2,
        expected_source_turn=2,
        expected_matrix_id=baseline.matrix_id,
        expected_matrix_version=baseline.version,
        expected_matrix_hash=baseline.content_hash,
        continuation_text="Exact missing fact.",
    )
    monkeypatch.setattr(
        contract,
        "require_resume_preflight",
        lambda *_args, **_kwargs: contract.ResumePreflight(
            matrix=baseline,
            room_id="room-mid",
            message_ids=frozenset({"message-old"}),
            run_ids=frozenset({"run-old"}),
            committed_texts=frozenset(),
        ),
    )
    monkeypatch.setattr(
        contract,
        "post_resume_continuation",
        lambda *_args, **_kwargs: ("message-new-3", "run-new-3"),
    )
    monkeypatch.setattr(contract, "observe_intake_agent_run", lambda *_a, **_k: None)
    monkeypatch.setattr(contract, "record_parallel_intake_timing", lambda *_a, **_k: None)
    monkeypatch.setattr(
        contract,
        "post_handoff_bridge",
        lambda _context,
        _stage,
        *,
        expected_dossier_version,
        expected_source_turn_no: (
            "run-handoff-4"
            if (expected_dossier_version, expected_source_turn_no) == (3, 3)
            else (_ for _ in ()).throw(AssertionError("wrong handoff authority"))
        ),
    )

    def formal_turn(
        _context: object,
        stage: str,
        run_id: str,
        **_kwargs: object,
    ) -> object:
        if stage == "resume_initiator_formal":
            assert run_id == "run-new-3"
            return contract.MatrixState(
                "matrix-mid-3",
                3,
                "c" * 64,
                "INITIATOR_FROZEN",
                baseline.matrix_id,
                baseline.version,
                baseline.content_hash,
                "WAITING_PARTY",
            )
        assert stage == "resume_initiator_handoff_formal"
        assert run_id == "run-handoff-4"
        return contract.MatrixState(
            "matrix-mid-3",
            3,
            "c" * 64,
            "INITIATOR_FROZEN",
            baseline.matrix_id,
            baseline.version,
            baseline.content_hash,
            "READY_TO_CONFIRM",
        )

    monkeypatch.setattr(contract, "wait_for_formal_turn", formal_turn)
    semantic_authority: list[tuple[int, int]] = []
    monkeypatch.setattr(
        contract,
        "require_semantic_ready",
        lambda _context, _stage, *, expected_dossier_version, expected_source_turn_no: semantic_authority.append(
            (expected_dossier_version, expected_source_turn_no)
        ),
    )
    summary = contract.execute_resume_waiting_party(
        SimpleNamespace(values={}, measure=lambda _stage, operation: operation()),
        SimpleNamespace(case_id=configuration.case_id),
        configuration,
        rounds_per_party=3,
    )

    assert summary["resume_new_agent_run_ids"] == ["run-new-3", "run-handoff-4"]
    assert summary["initiator_rounds"] == 3
    assert summary["final_matrix_version"] == 3
    assert semantic_authority == [(4, 4)]


def test_failed_tail_resume_binds_exact_terminal_run(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    contract = _load_five_round_uat_contract()
    for name in (*contract.RESUME_ENV_NAMES, *contract.FAILED_TURN_RESUME_ENV_NAMES):
        monkeypatch.delenv(name, raising=False)
    values = {
        contract.RESUME_BOUNDARY_ENV: (
            contract.INITIATOR_WAITING_PARTY_AFTER_FAILED_TURN
        ),
        contract.RESUME_CASE_ID_ENV: "CASE_FAILED_TAIL",
        contract.RESUME_INITIATOR_ID_ENV: "resume-user",
        contract.RESUME_RESPONDENT_ID_ENV: "resume-merchant",
        contract.RESUME_OPERATION_ID_ENV: "failed-tail-op-v1",
        contract.RESUME_EXPECTED_PHASE_ENV: "WAITING_PARTY",
        contract.RESUME_EXPECTED_DOSSIER_VERSION_ENV: "2",
        contract.RESUME_EXPECTED_SOURCE_TURN_ENV: "2",
        contract.RESUME_EXPECTED_MATRIX_ID_ENV: "matrix-resume-2",
        contract.RESUME_EXPECTED_MATRIX_VERSION_ENV: "2",
        contract.RESUME_EXPECTED_MATRIX_HASH_ENV: "a" * 64,
        contract.RESUME_CONTINUATION_TEXT_ENV: "A new fact after the failed turn.",
        contract.RESUME_FAILED_MESSAGE_ID_ENV: "message-failed-3",
        contract.RESUME_FAILED_RUN_ID_ENV: "run-failed-3",
        contract.RESUME_FAILED_ERROR_CODE_ENV: "INTAKE_PARALLEL_FRAME_BATCH_FAILED",
    }
    for name, value in values.items():
        monkeypatch.setenv(name, value)
    configuration = contract.load_resume_configuration(5)
    assert configuration is not None
    assert configuration.expected_failed_run_id == "run-failed-3"

    matrix = {
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
    room_id = "room-intake-failed-tail"
    responses: dict[str, dict[str, object]] = {
        "status": {
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
        },
        "memory": {
            "case_intake_dossier": {
                "case_id": "CASE_FAILED_TAIL",
                "room_type": "INTAKE",
                "dossier_version": 2,
                "source_turn_no": 2,
                "ready_for_next_step": True,
                "admission_recommendation": "ACCEPTED",
                "dossier": {
                    "case_fact_matrix": matrix,
                    "party_intake_state": {
                        "USER": {
                            "handoff_notes": {
                                "remark_status": "READY_PENDING_REMARK_INVITE"
                            }
                        }
                    },
                },
            }
        },
        "case": {
            "id": "CASE_FAILED_TAIL",
            "user_id": "resume-user",
            "merchant_id": "resume-merchant",
            "initiator_role": "USER",
            "initiator_id": "resume-user",
            "respondent_role": "MERCHANT",
            "respondent_id": "resume-merchant",
            "current_room": "INTAKE",
            "case_status": "INTAKE_PENDING",
        },
    }
    messages = [
        {
            "id": "message-formal-1",
            "case_id": "CASE_FAILED_TAIL",
            "room_id": room_id,
            "sequence_no": 1,
            "message_type": "AGENT_MESSAGE",
            "sender_role": "INTAKE_OFFICER",
            "sender_id": "intake-agent",
            "agent_run_id": "run-old-1",
            "message_text": "Opening.",
        },
        {
            "id": "message-party-2",
            "case_id": "CASE_FAILED_TAIL",
            "room_id": room_id,
            "sequence_no": 2,
            "message_type": "PARTY_TEXT",
            "sender_role": "USER",
            "sender_id": "resume-user",
            "agent_run_id": "run-old-2",
            "message_text": "Prior party fact.",
        },
        {
            "id": "message-formal-2",
            "case_id": "CASE_FAILED_TAIL",
            "room_id": room_id,
            "sequence_no": 3,
            "message_type": "AGENT_MESSAGE",
            "sender_role": "INTAKE_OFFICER",
            "sender_id": "intake-agent",
            "agent_run_id": "run-old-2",
            "message_text": "Prior formal response.",
        },
        {
            "id": "message-failed-3",
            "case_id": "CASE_FAILED_TAIL",
            "room_id": room_id,
            "sequence_no": 4,
            "message_type": "PARTY_TEXT",
            "sender_role": "USER",
            "sender_id": "resume-user",
            "agent_run_id": "run-failed-3",
            "message_text": "Failed turn text.",
        },
    ]
    runs: dict[str, dict[str, object]] = {
        "run-old-1": {"run_id": "run-old-1", "status": "COMPLETED"},
        "run-old-2": {"run_id": "run-old-2", "status": "COMPLETED"},
        "run-failed-3": {
            "run_id": "run-failed-3",
            "case_id": "CASE_FAILED_TAIL",
            "room_id": room_id,
            "operation": "INTAKE_MESSAGE",
            "status": "ABORTED",
            "error_code": "INTAKE_PARALLEL_FRAME_BATCH_FAILED",
            "retryable": False,
        },
    }

    def request_json(
        _context: object,
        _stage: str,
        method: str,
        path: str,
        **_kwargs: object,
    ) -> tuple[int, dict[str, object]]:
        assert method == "GET"
        if path.endswith("/intake/status"):
            return 200, {"data": responses["status"]}
        if path.endswith("/turn-memory/latest"):
            return 200, {"data": responses["memory"]}
        if path.endswith("/rooms/INTAKE/messages"):
            return 200, {"data": messages}
        if path == "/api/disputes/CASE_FAILED_TAIL":
            return 200, {"data": responses["case"]}
        if path.startswith("/api/agent-runs/"):
            return 200, {"data": runs[path.rsplit("/", 1)[-1]]}
        raise AssertionError(path)

    monkeypatch.setattr(contract.uat, "request_json", request_json)
    context = SimpleNamespace(
        case_id="CASE_FAILED_TAIL",
        actor_role="USER",
        user_id="resume-user",
        merchant_id="resume-merchant",
    )
    preflight = contract.require_resume_preflight(
        context, "resume_preflight", configuration
    )
    assert preflight.run_ids == frozenset(
        {"run-old-1", "run-old-2", "run-failed-3"}
    )

    runs["run-failed-3"]["error_code"] = "WRONG_ERROR"
    with pytest.raises(contract.uat.UatFailure) as wrong_terminal:
        contract.require_resume_preflight(context, "resume_preflight", configuration)
    assert (wrong_terminal.value.stage, wrong_terminal.value.check) == (
        "resume_preflight",
        "failed_tail_run_terminal",
    )
