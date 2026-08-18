# 文件作用：自动化测试文件，验证 test_streaming 相关模块的行为、契约或页面布局。

from __future__ import annotations

import asyncio
import copy
import hashlib
import importlib.util
import json
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from threading import Event, Lock
from types import SimpleNamespace

import httpx
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from langchain_core.messages import HumanMessage, SystemMessage
from pydantic import BaseModel, ConfigDict

from app.agents.evidence_clerk.public_reply import (
    EVIDENCE_CANONICAL_OPENING,
    EvidencePublicOutputMismatch,
    EvidencePublicOutputPolicy,
    EvidencePublicOutputPolicyError,
    guard_evidence_public_reply,
)
from app.agents.dispute_intake_officer.schemas import INTAKE_ROOM_SECTION_KINDS
from app.llm import (
    AgentOutputSchemaError,
    GovernedProviderRequest,
    LiteLlmProxyClient,
    StructuredStreamCompleted,
    StructuredStreamDelta,
)
from app.model_runtime.transports import (
    ModelTransportOutputError,
    ModelTransportRequest,
    StructuredClientTransport,
)
from app.streaming import (
    AgentStreamLimitExceeded,
    AgentStreamObserver,
    AgentStreamProjectionError,
    IncrementalVisibleJsonProjector,
    STREAM_EVENT_MAX_DELTA_CHARS,
    STREAM_EVENT_QUEUE_MAXSIZE,
    STREAM_MAX_MODEL_DOCUMENT_CHARS,
    STREAM_MAX_VISIBLE_OUTPUT_CHARS,
    StreamUsageEvent,
    TARGET_INTAKE_REPLY_FIRST_VISIBLE_FIELDS,
    VISIBLE_FIELD_REGISTRY,
    VisibleFieldSpec,
    current_stream_observer,
    workflow_ndjson_response,
)


class _Reply(BaseModel):
    model_config = ConfigDict(extra="forbid")

    room_utterance: str
    internal_note: str = ""


def _governed_transport_ndjson(
    provider_documents: list[dict[str, object]],
    *,
    attempts: int,
    repairs: int,
) -> tuple[list[dict[str, object]], list[dict[str, object]], str]:
    calls: list[dict[str, object]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(json.loads(request.content))
        return httpx.Response(200, json=provider_documents[len(calls) - 1])

    mock = httpx.MockTransport(handler)
    client = LiteLlmProxyClient(
        "http://litellm.test",
        "governed-model",
        "secret",
        transport=mock,
        async_transport=mock,
    )
    transport = StructuredClientTransport(client)
    request = ModelTransportRequest(
        node_name="intake_turn_case_detail",
        messages=(SystemMessage("system"), HumanMessage("user")),
        output_type=_Reply,
        governed_request=GovernedProviderRequest(
            provider="litellm",
            model="governed-model",
            temperature=0.2,
            max_output_tokens=1_024,
            response_format="STRICT_JSON_SCHEMA",
            tool_allowlist=(),
            deadline_at=datetime.now(timezone.utc) + timedelta(minutes=1),
            provider_attempts_remaining=attempts,
            repairs_remaining=repairs,
            traceparent=(
                "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"
            ),
        ),
    )
    app = FastAPI()

    def invoke() -> None:
        asyncio.run(transport.agenerate(request))

    @app.post("/stream")
    async def stream():
        return workflow_ndjson_response(
            operation="intake_turn_case_detail",
            run_id="AGENT_RUN_transport_classification",
            invoke=invoke,
        )

    with TestClient(app) as test_client:
        response = test_client.post("/stream")
    events = [json.loads(line) for line in response.text.splitlines() if line]
    return calls, events, response.text


# 所属模块：Python 支撑模块 > test_streaming；函数角色：模块私有业务函数。
# 具体功能：`_sse` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`join`、`lines.append`、`json.dumps`。
# 上下游：上游为 本文件的 `test_real_provider_stream_projects_answer_and_ignores_reasoning_channel`、`test_real_provider_stream_projects_answer_and_ignores_reasoning_channel.handler`、`test_invalid_streamed_schema_fails_closed_without_second_model_call`、`test_invalid_streamed_schema_fails_closed_without_second_model_call.handler`；下游为 协作调用 `join`、`lines.append`、`json.dumps`。
# 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
def _sse(*payloads: dict[str, object] | str) -> str:
    lines: list[str] = []
    for payload in payloads:
        if isinstance(payload, str):
            lines.append(f"data: {payload}\n\n")
        else:
            lines.append(
                "data: "
                + json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
                + "\n\n"
            )
    return "".join(lines)


# 所属模块：Python 支撑模块 > test_streaming；函数角色：回归测试用例。
# 具体功能：`test_incremental_projector_only_emits_new_decoded_string_prefix` 验证本阶段状态在固定案例中的输出、边界和失败行为；关键协作调用：`IncrementalVisibleJsonProjector`、`projector.feed`、`VisibleFieldSpec`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `IncrementalVisibleJsonProjector`、`projector.feed`、`VisibleFieldSpec`。
# 系统意义：固定“Python 支撑模块 > test_streaming”的可观察契约，防止后续重构改变业务结果。
def test_incremental_projector_only_emits_new_decoded_string_prefix() -> None:
    projector = IncrementalVisibleJsonProjector(
        (VisibleFieldSpec("room_utterance", "room_utterance"),)
    )

    assert projector.feed('{"internal_note":"不要展示","room_utterance":"你') == [
        ("room_utterance", "你")
    ]
    assert projector.feed('好\\n第\\u4e8c') == [
        ("room_utterance", "好\n第二")
    ]
    assert projector.feed('行","other":"ignored"}') == [
        ("room_utterance", "行")
    ]


def test_evidence_projector_uses_exact_paths_and_rejects_duplicate_path_keys() -> None:
    private_object = "PRIVATE_NESTED_EVIDENCE_TEXT"
    private_array = "PRIVATE_ARRAY_EVIDENCE_TEXT"
    root_text = "我会先核验本轮材料与案情的关联。"
    projector = IncrementalVisibleJsonProjector(
        (VisibleFieldSpec("room_utterance", "room_utterance"),)
    )

    projected = projector.feed(
        json.dumps(
            {
                "internal_handoff": {"room_utterance": private_object},
                "items": [{"room_utterance": private_array}],
                "room_utterance": root_text,
            },
            ensure_ascii=False,
            separators=(",", ":"),
        )
    )

    assert projected == [("room_utterance", root_text)]
    assert private_object not in "".join(delta for _, delta in projected)
    assert private_array not in "".join(delta for _, delta in projected)

    for duplicate_value in (root_text, "LATER_DUPLICATE_MUST_NOT_PROJECT"):
        duplicate = IncrementalVisibleJsonProjector(
            (VisibleFieldSpec("room_utterance", "room_utterance"),)
        )
        assert duplicate.feed('{"room_utterance":"' + root_text + '"') == [
            ("room_utterance", root_text)
        ]
        with pytest.raises(AgentStreamProjectionError):
            duplicate.feed(',"room_utterance":"' + duplicate_value + '"}')

    nested = IncrementalVisibleJsonProjector(
        (VisibleFieldSpec("room_utterance", "envelope.room_utterance"),)
    )
    assert nested.feed(
        '{"envelope":{"room_utterance":"' + root_text + '"}'
    ) == [("envelope.room_utterance", root_text)]
    with pytest.raises(AgentStreamProjectionError):
        nested.feed(
            ',"envelope":{"room_utterance":"LATER_NESTED_DUPLICATE"}}'
        )


def test_evidence_public_output_policy_is_chunk_invariant_private_and_fail_closed() -> None:
    safe_sentence = EVIDENCE_CANONICAL_OPENING
    independently_safe_sentence = "我会先核验签收材料与冻结事实矩阵的关联。"
    risky_sentence = "不判断责任但实际由商家承担。"
    raw_source = safe_sentence + risky_sentence
    guarded_final = guard_evidence_public_reply(raw_source)
    private_reasoning = "PRIVATE_EVIDENCE_REASONING_MUST_NEVER_BE_PUBLIC"
    private_json = '{"internal_handoff":"PRIVATE_EVIDENCE_JSON"}'

    for split_points in (
        (1,),
        (len(safe_sentence) // 2,),
        (1, len(safe_sentence) - 1),
    ):
        split_policy = EvidencePublicOutputPolicy()
        assert split_policy.begin(
            operation="evidence_turn",
            node_name="evidence_turn",
            field_name="room_utterance",
        ) == (EVIDENCE_CANONICAL_OPENING,)
        assert split_policy.begin(
            operation="evidence_turn",
            node_name="evidence_turn",
            field_name="room_utterance",
        ) == ()
        boundaries = (0, *split_points, len(safe_sentence))
        released: list[str] = []
        for start, end in zip(boundaries, boundaries[1:]):
            released.extend(
                split_policy.accept(
                    operation="evidence_turn",
                    node_name="evidence_turn",
                    field_name="room_utterance",
                    delta=safe_sentence[start:end],
                )
            )
        assert released == []
        assert split_policy.source_observed is True
        assert split_policy.visible_text == EVIDENCE_CANONICAL_OPENING

    split_policy = EvidencePublicOutputPolicy()
    assert split_policy.begin(
        operation="evidence_turn",
        node_name="evidence_turn",
        field_name="room_utterance",
    ) == (EVIDENCE_CANONICAL_OPENING,)
    released = []
    for delta in (
        independently_safe_sentence[:7],
        independently_safe_sentence[7:],
    ):
        released.extend(
            split_policy.accept(
                operation="evidence_turn",
                node_name="evidence_turn",
                field_name="room_utterance",
                delta=delta,
            )
        )
    assert released == [independently_safe_sentence]

    for unsafe_sentence in (
        "我会先核验本轮材料，确认与案情的关联。",
        "我会先核验本轮材料；确认与案情的关联。",
        "我会先核验本轮材料：确认与案情的关联。",
        "本案责任由商家承担。",
        "商家存在过错。",
        "用户应当承担损失。",
        "应当退款退货并赔付补偿。",
        "现有证据支持退款请求。",
        "应当驳回商家主张。",
        "现已判定用户胜诉。",
        "裁决结果为商家败诉。",
        "该材料属于欺诈造假。",
        risky_sentence,
        "尚未认定过错。",
        "不支持退款。",
        "没有发现造假。",
        '{"room_utterance":"机器语法不得公开"}',
    ):
        unsafe_policy = EvidencePublicOutputPolicy()
        assert unsafe_policy.begin(
            operation="evidence_turn",
            node_name="evidence_turn",
            field_name="room_utterance",
        ) == (EVIDENCE_CANONICAL_OPENING,)
        assert unsafe_policy.accept(
            operation="evidence_turn",
            node_name="evidence_turn",
            field_name="room_utterance",
            delta=unsafe_sentence,
        ) == ()

    guarded_suffix = (
        "相关事项仍需后续程序核验。"
        "本轮只做证据核验，不判断责任或最终方案。"
    )
    for risky_prose in (
        "Merchant must refund the user.",
        "商家需把钱退给用户。",
        "平台尚未认定商家有过错，也不支持退款。",
        "The evidence file appears decisive.",
        '{"decision":"refund"}',
        "case/CASE_123/result.json",
        "FACT_123 supports CLAIM_456.",
    ):
        guarded = guard_evidence_public_reply(safe_sentence + risky_prose)
        assert guarded.startswith(safe_sentence)
        assert guarded.removeprefix(safe_sentence) == guarded_suffix
        assert risky_prose not in guarded
        assert guard_evidence_public_reply(guarded) == guarded

    sticky_policy = EvidencePublicOutputPolicy()
    assert sticky_policy.begin(
        operation="evidence_turn",
        node_name="evidence_turn",
        field_name="room_utterance",
    ) == (EVIDENCE_CANONICAL_OPENING,)
    assert sticky_policy.accept(
        operation="evidence_turn",
        node_name="evidence_turn",
        field_name="room_utterance",
        delta=risky_sentence,
    ) == ()
    assert sticky_policy.accept(
        operation="evidence_turn",
        node_name="evidence_turn",
        field_name="room_utterance",
        delta=safe_sentence,
    ) == ()

    events = []
    policy = EvidencePublicOutputPolicy()
    observer = AgentStreamObserver(
        operation="evidence_turn",
        run_id="AGENT_RUN_EVIDENCE_PUBLIC_POLICY",
        publish=events.append,
        public_output_policy=policy,
    )
    assert policy.allows_node("evidence_turn", "evidence_turn") is True
    assert policy.allows_node("evidence_turn", "internal_handoff") is False
    observer.begin_public_output("evidence_turn", "room_utterance")
    observer.begin_public_output("evidence_turn", "room_utterance")

    for node_name, field_name, delta in (
        ("evidence_turn", "reasoning_content", private_reasoning),
        ("evidence_turn", "internal_handoff", private_json),
        ("evidence_turn", "envelope.room_utterance", private_json),
        ("other_node", "room_utterance", private_json),
    ):
        with pytest.raises(EvidencePublicOutputPolicyError):
            policy.accept(
                operation="evidence_turn",
                node_name=node_name,
                field_name=field_name,
                delta=delta,
            )
        with pytest.raises(EvidencePublicOutputPolicyError):
            EvidencePublicOutputPolicy().begin(
                operation="evidence_turn",
                node_name=node_name,
                field_name=field_name,
            )
    assert [event.delta for event in events] == [EVIDENCE_CANONICAL_OPENING]
    observer.visible_delta("evidence_turn", "room_utterance", safe_sentence[:5])
    assert [event.delta for event in events] == [EVIDENCE_CANONICAL_OPENING]
    observer.visible_delta("evidence_turn", "room_utterance", safe_sentence[5:])
    assert [event.delta for event in events] == [EVIDENCE_CANONICAL_OPENING]
    assert policy.source_observed is True
    assert policy.visible_text == safe_sentence

    observer.visible_delta(
        "evidence_turn",
        "room_utterance",
        risky_sentence[:-2],
    )
    assert [event.delta for event in events] == [EVIDENCE_CANONICAL_OPENING]
    observer.visible_delta(
        "evidence_turn",
        "room_utterance",
        risky_sentence[-2:],
    )
    assert [event.delta for event in events] == [EVIDENCE_CANONICAL_OPENING]
    assert all(event.node_name == "evidence_turn" for event in events)
    assert all(event.field == "room_utterance" for event in events)
    assert private_reasoning not in "".join(event.delta for event in events)
    assert private_json not in "".join(event.delta for event in events)
    observer.finalize_public_output(
        "evidence_turn",
        "room_utterance",
        guarded_final,
    )
    assert "".join(event.delta for event in events) == guarded_final
    assert guarded_final != raw_source
    assert guarded_final.startswith(safe_sentence)
    assert risky_sentence not in guarded_final

    withheld_events = []
    withheld_observer = AgentStreamObserver(
        operation="evidence_turn",
        run_id="AGENT_RUN_EVIDENCE_ALL_WITHHELD",
        publish=withheld_events.append,
        public_output_policy=EvidencePublicOutputPolicy(),
    )
    withheld_observer.begin_public_output("evidence_turn", "room_utterance")
    withheld_observer.visible_delta(
        "evidence_turn",
        "room_utterance",
        risky_sentence,
    )
    withheld_final = guard_evidence_public_reply(risky_sentence)
    withheld_observer.finalize_public_output(
        "evidence_turn",
        "room_utterance",
        withheld_final,
    )
    assert "".join(event.delta for event in withheld_events) == withheld_final
    assert risky_sentence not in withheld_final
    assert withheld_final.count(EVIDENCE_CANONICAL_OPENING) == 1

    no_source_events = []
    no_source_observer = AgentStreamObserver(
        operation="evidence_turn",
        run_id="AGENT_RUN_EVIDENCE_NO_SOURCE",
        publish=no_source_events.append,
        public_output_policy=EvidencePublicOutputPolicy(),
    )
    no_source_observer.begin_public_output("evidence_turn", "room_utterance")
    with pytest.raises(EvidencePublicOutputMismatch):
        no_source_observer.finalize_public_output(
            "evidence_turn",
            "room_utterance",
            guard_evidence_public_reply(safe_sentence),
        )
    assert [event.delta for event in no_source_events] == [
        EVIDENCE_CANONICAL_OPENING
    ]

    mismatch_events = []
    mismatch_observer = AgentStreamObserver(
        operation="evidence_turn",
        run_id="AGENT_RUN_EVIDENCE_FINAL_MISMATCH",
        publish=mismatch_events.append,
        public_output_policy=EvidencePublicOutputPolicy(),
    )
    mismatch_observer.begin_public_output("evidence_turn", "room_utterance")
    mismatch_observer.visible_delta(
        "evidence_turn",
        "room_utterance",
        safe_sentence,
    )
    with pytest.raises(EvidencePublicOutputMismatch):
        mismatch_observer.finalize_public_output(
            "evidence_turn",
            "room_utterance",
            guard_evidence_public_reply("我会先核验其他材料与案情的关联。"),
        )
    assert [event.delta for event in mismatch_events] == [
        EVIDENCE_CANONICAL_OPENING
    ]

    replay_events = []
    replay_policy = EvidencePublicOutputPolicy()
    replay_observer = AgentStreamObserver(
        operation="evidence_turn",
        run_id="AGENT_RUN_EVIDENCE_CANONICAL_REPLAY",
        publish=replay_events.append,
        public_output_policy=replay_policy,
    )
    replay_observer.finalize_public_output(
        "evidence_turn",
        "room_utterance",
        guard_evidence_public_reply(safe_sentence),
        allow_canonical_fallback=True,
    )
    assert replay_policy.source_observed is False
    assert [event.delta for event in replay_events] == [
        guard_evidence_public_reply(safe_sentence)
    ]
    assert replay_events[0].delta.count(EVIDENCE_CANONICAL_OPENING) == 1


def test_evidence_legacy_bootstrap_precedes_invoke_and_deduplicates_model_prefix() -> (
    None
):
    async def scenario() -> None:
        invoke_started = Event()
        release_invoke = Event()
        invoke_calls = 0
        risky_sentence = "不判断责任但实际由商家承担。"
        raw_source = EVIDENCE_CANONICAL_OPENING + risky_sentence
        guarded_final = guard_evidence_public_reply(raw_source)

        def invoke() -> SimpleNamespace:
            nonlocal invoke_calls
            invoke_calls += 1
            observer = current_stream_observer()
            assert observer is not None
            assert observer.finalized_public_output == EVIDENCE_CANONICAL_OPENING
            invoke_started.set()
            assert release_invoke.wait(timeout=1.0)
            observer.visible_delta(
                "evidence_turn",
                "room_utterance",
                EVIDENCE_CANONICAL_OPENING[:8],
            )
            observer.visible_delta(
                "evidence_turn",
                "room_utterance",
                EVIDENCE_CANONICAL_OPENING[8:] + risky_sentence,
            )
            observer.usage(
                node_name="evidence_turn",
                model="bootstrap-test-model",
                latency_ms=1,
                token_usage={"input": 3, "output": 2, "total": 5},
            )
            return SimpleNamespace(room_utterance=guarded_final)

        response = workflow_ndjson_response(
            operation="evidence_turn",
            run_id="AGENT_RUN_EVIDENCE_BOOTSTRAP_ORDER",
            invoke=invoke,
            public_output_policy=EvidencePublicOutputPolicy(),
            finalized_visible=lambda result: result.room_utterance,
            finalized_visible_node="evidence_turn",
            finalized_visible_field="room_utterance",
        )
        iterator = response.body_iterator
        events = [json.loads((await anext(iterator)).strip())]
        assert events[0]["type"] == "start"
        assert await asyncio.to_thread(invoke_started.wait, 1.0)

        bootstrap = json.loads((await anext(iterator)).strip())
        events.append(bootstrap)
        assert bootstrap["type"] == "visible_delta"
        assert bootstrap["delta"] == EVIDENCE_CANONICAL_OPENING
        assert invoke_calls == 1

        release_invoke.set()
        events.extend(
            [json.loads(item.strip()) async for item in iterator]
        )
        event_types = [event["type"] for event in events]
        assert event_types[:2] == ["start", "visible_delta"]
        assert event_types[-2:] == ["usage", "final"]
        assert event_types.count("usage") == 1
        visible_text = "".join(
            event["delta"] for event in events if event["type"] == "visible_delta"
        )
        assert visible_text == guarded_final
        assert events[-1]["response"]["room_utterance"] == guarded_final
        assert visible_text.count(EVIDENCE_CANONICAL_OPENING) == 1
        assert risky_sentence not in json.dumps(events, ensure_ascii=False)
        assert invoke_calls == 1

    asyncio.run(scenario())


def test_evidence_stream_error_reports_only_already_safe_visible_output() -> None:
    private_failure = "PRIVATE_EVIDENCE_WORKFLOW_FAILURE"
    app = FastAPI()

    def invoke() -> None:
        observer = current_stream_observer()
        assert observer is not None
        assert observer.finalized_public_output == EVIDENCE_CANONICAL_OPENING
        raise ValueError(private_failure)

    @app.post("/evidence-stream")
    async def evidence_stream():
        return workflow_ndjson_response(
            operation="evidence_turn",
            run_id="AGENT_RUN_EVIDENCE_SAFE_PREFIX_FAILURE",
            invoke=invoke,
            public_output_policy=EvidencePublicOutputPolicy(),
            finalized_visible=lambda result: result.room_utterance,
            finalized_visible_node="evidence_turn",
            finalized_visible_field="room_utterance",
        )

    with TestClient(app) as client:
        response = client.post("/evidence-stream")

    events = [json.loads(line) for line in response.text.splitlines() if line]
    assert [event["type"] for event in events] == [
        "start",
        "visible_delta",
        "error",
    ]
    assert events[1]["node_name"] == "evidence_turn"
    assert events[1]["field"] == "room_utterance"
    assert events[1]["delta"] == EVIDENCE_CANONICAL_OPENING
    assert events[-1]["visible_output_emitted"] is True
    assert private_failure not in response.text
    assert not {"usage", "final"} & {event["type"] for event in events}


def test_evidence_fresh_stream_requires_a_proven_safe_source_before_usage() -> None:
    risky_sentence = "不判断责任但实际由商家承担。"
    cases = (
        ("no-source", None, EVIDENCE_CANONICAL_OPENING, False),
        ("all-withheld", risky_sentence, risky_sentence, True),
    )

    for case_name, source_text, raw_final, succeeds in cases:
        app = FastAPI()

        def invoke(
            source_text: str | None = source_text,
            raw_final: str = raw_final,
        ) -> SimpleNamespace:
            observer = current_stream_observer()
            assert observer is not None
            if source_text is not None:
                observer.visible_delta(
                    "evidence_turn",
                    "room_utterance",
                    source_text,
                )
            observer.usage(
                node_name="evidence_turn",
                model="fresh-evidence-test-model",
                latency_ms=1,
                token_usage={"input": 3, "output": 2, "total": 5},
            )
            return SimpleNamespace(
                room_utterance=guard_evidence_public_reply(raw_final)
            )

        @app.post("/evidence-stream")
        async def evidence_stream():
            return workflow_ndjson_response(
                operation="evidence_turn",
                run_id=f"AGENT_RUN_EVIDENCE_{case_name}",
                invoke=invoke,
                public_output_policy=EvidencePublicOutputPolicy(),
                finalized_visible=lambda result: result.room_utterance,
                finalized_visible_node="evidence_turn",
                finalized_visible_field="room_utterance",
            )

        with TestClient(app) as client:
            response = client.post("/evidence-stream")

        events = [json.loads(line) for line in response.text.splitlines() if line]
        event_types = [event["type"] for event in events]
        assert event_types[:2] == ["start", "visible_delta"]
        assert events[1]["delta"] == EVIDENCE_CANONICAL_OPENING
        if not succeeds:
            assert event_types == ["start", "visible_delta", "error"]
            assert events[-1]["visible_output_emitted"] is True
            assert not {"usage", "final"} & set(event_types)
            continue
        assert event_types[-2:] == ["usage", "final"]
        assert event_types.count("usage") == 1
        visible_text = "".join(
            event["delta"] for event in events if event["type"] == "visible_delta"
        )
        guarded_final = guard_evidence_public_reply(raw_final)
        assert visible_text == guarded_final
        assert events[-1]["response"]["room_utterance"] == guarded_final
        assert guarded_final.count(EVIDENCE_CANONICAL_OPENING) == 1
        if case_name == "all-withheld":
            assert risky_sentence not in response.text


def test_incremental_projector_streams_completed_case_detail_sections() -> None:
    projector = IncrementalVisibleJsonProjector(
        (
            VisibleFieldSpec(
                "case_story",
                "case_detail.case_story",
                "json_value",
            ),
        )
    )

    assert projector.feed('{"case_detail":{"case_story":{"title":"安装') == []
    assert projector.feed('收费","one_sentence_summary":"用户要求退款"}') == [
        (
            "case_detail.case_story",
            '{"title":"安装收费","one_sentence_summary":"用户要求退款"}',
        )
    ]
    assert projector.feed(',"risk_assessment":{}}}') == []


def test_incremental_projector_streams_only_complete_array_objects_in_source_order() -> None:
    spec = VisibleFieldSpec(
        "public_observations",
        "public_observations",
        "json_array_items",
        max_array_items=2,
        max_array_item_bytes=128,
    )
    chunks = (
        '{"public_observations":[{"evidence_id":"EVIDENCE_1","source_quote":"退款',
        '20元"}',
        ',{"evidence_id":"EVIDENCE_2","source_quote":"工单未生成"}',
        ']}',
    )

    projector = IncrementalVisibleJsonProjector((spec,))
    replay = IncrementalVisibleJsonProjector((spec,))
    emitted = [projector.feed(chunk) for chunk in chunks]
    replayed = [replay.feed(chunk) for chunk in chunks]

    assert emitted == replayed
    assert emitted[0] == []
    assert emitted[1] == [
        (
            "public_observations",
            '{"evidence_id":"EVIDENCE_1","source_quote":"退款20元"}',
        )
    ]
    assert emitted[2] == [
        (
            "public_observations",
            '{"evidence_id":"EVIDENCE_2","source_quote":"工单未生成"}',
        )
    ]
    assert emitted[3] == []

    over_limit = IncrementalVisibleJsonProjector((spec,))
    with pytest.raises(AgentStreamLimitExceeded):
        over_limit.feed(
            '{"public_observations":[{"item":1},{"item":2},{"item":3}]}'
        )
    non_object = IncrementalVisibleJsonProjector((spec,))
    with pytest.raises(AgentStreamProjectionError):
        non_object.feed('{"public_observations":["not-an-object"]}')


def test_evidence_v2_frame_projector_releases_public_text_before_frame_or_document_close() -> None:
    spec = VisibleFieldSpec(
        "frames",
        "frames",
        "json_frame_tuples",
        max_array_items=4,
        max_array_item_bytes=2_048,
        max_array_bytes=8_192,
    )
    chunks = (
        '{"schema_version":"evidence_turn_stream.v2","frames":'
        '[[{"frame_sequence":1,"frame_type":"ROOM_WELCOME"},"欢迎',
        "进入证据",
        '室。"],',
        '[{"frame_sequence":2,"frame_type":"HUMAN_REVIEW_TASK",'
        '"evidence_id":"EVIDENCE_1","observation_slots":[],"trigger_code":'
        '"SOURCE_CHAIN","review_target":"原件","review_instruction":"核对原件",'
        '"priority":"MEDIUM"},null]',
        ']}'
    )

    projector = IncrementalVisibleJsonProjector((spec,))
    replay = IncrementalVisibleJsonProjector((spec,))
    emitted = [projector.feed(chunk) for chunk in chunks]
    replayed = [replay.feed(chunk) for chunk in chunks]

    assert emitted == replayed
    assert [json.loads(delta)["kind"] for _, delta in emitted[0]] == [
        "frame_start",
        "public_text_delta",
    ]
    assert json.loads(emitted[0][1][1])["delta"] == "欢迎"
    assert json.loads(emitted[1][0][1]) == {
        "kind": "public_text_delta",
        "frame_sequence": 1,
        "delta": "进入证据",
    }
    assert [json.loads(delta)["kind"] for _, delta in emitted[2]] == [
        "public_text_delta",
        "frame_end",
    ]
    assert json.loads(emitted[2][0][1])["delta"] == "室。"
    assert [json.loads(delta)["kind"] for _, delta in emitted[3]] == [
        "frame_start",
        "frame_end",
    ]
    assert emitted[4] == []


def test_evidence_v2_public_policy_keeps_model_text_and_frame_identity() -> None:
    from app.agents.evidence_clerk.v2_policy import EvidenceV2PublicOutputPolicy

    policy = EvidenceV2PublicOutputPolicy()
    assert policy.begin(
        operation="evidence_turn",
        node_name="evidence_turn",
        field_name="frames",
    ) == ()
    header = {
        "frame_sequence": 1,
        "frame_type": "ROOM_WELCOME",
    }
    assert policy.project_event(
        operation="evidence_turn",
        node_name="evidence_turn",
        field_name="frames",
        delta=json.dumps(
            {"kind": "frame_start", "frame_sequence": 1, "header": header},
            ensure_ascii=False,
            separators=(",", ":"),
        ),
    )[0][0] == "frame.1.header"
    literal = "该文本包含退款、责任等模型原话，不由后端改写。"
    projected = policy.project_event(
        operation="evidence_turn",
        node_name="evidence_turn",
        field_name="frames",
        delta=json.dumps(
            {"kind": "public_text_delta", "frame_sequence": 1, "delta": literal},
            ensure_ascii=False,
            separators=(",", ":"),
        ),
    )
    assert projected == (("frame.1.public_text", literal),)
    policy.project_event(
        operation="evidence_turn",
        node_name="evidence_turn",
        field_name="frames",
        delta='{"kind":"frame_end","frame_sequence":1}',
    )
    assert policy.visible_text == literal
    policy.finalize(
        operation="evidence_turn",
        node_name="evidence_turn",
        field_name="frames",
        final_text=literal,
    )


def test_target_intake_projector_streams_room_utterance_before_case_detail() -> None:
    projector = IncrementalVisibleJsonProjector(
        TARGET_INTAKE_REPLY_FIRST_VISIBLE_FIELDS
    )

    assert projector.feed(
        '{"case_detail":{"case_story":{"title":"商品无法开机"}}'
    ) == []
    first = projector.feed(',"room_utterance":"您好，')
    second = projector.feed('请先确认订单问题。"')

    assert first == [("room_utterance", "您好，")]
    assert second == [
        ("room_utterance", "请先确认订单问题。"),
        ("case_detail.case_story.title", "商品无法开机"),
        ("case_detail.case_story", '{"title":"商品无法开机"}'),
    ]
    assert [field for field, _ in first + second] == [
        "room_utterance",
        "room_utterance",
        "case_detail.case_story.title",
        "case_detail.case_story",
    ]


def test_target_intake_v3_streams_each_ordered_card_before_terminal_json() -> None:
    projector = IncrementalVisibleJsonProjector(
        TARGET_INTAKE_REPLY_FIRST_VISIBLE_FIELDS
    )

    events = projector.feed('{"room_utterance":"已记录本轮')
    assert events == [("room_utterance", "已记录本轮")]
    events += projector.feed('补充。","ordered_sections":[')
    assert events[-1] == ("room_utterance", "补充。")

    projected_sections: list[dict[str, object]] = []
    for sequence, kind in enumerate(INTAKE_ROOM_SECTION_KINDS, start=1):
        prefix = "" if sequence == 1 else ","
        item = {
            "sequence": sequence,
            "kind": kind,
            "value": {"marker": f"SECTION_{sequence}"},
        }
        section_events = projector.feed(
            prefix
            + json.dumps(item, ensure_ascii=False, separators=(",", ":"))
        )
        assert len(section_events) == 1
        assert section_events[0][0] == "ordered_sections"
        projected_sections.append(json.loads(section_events[0][1]))

    assert projector.feed("]}") == []
    assert [section["kind"] for section in projected_sections] == list(
        INTAKE_ROOM_SECTION_KINDS
    )
    assert projected_sections[-1]["kind"] == "TURN_EVALUATION"


def test_target_intake_projector_preserves_unicode_before_opening_case_detail() -> None:
    projector = IncrementalVisibleJsonProjector(
        TARGET_INTAKE_REPLY_FIRST_VISIBLE_FIELDS
    )

    assert projector.feed('{"room_utterance":"你\\uD83D') == [
        ("room_utterance", "你")
    ]
    assert projector.feed('\\uDE00好"') == [("room_utterance", "😀好")]
    assert projector.feed(
        ',"case_detail":{"case_story":{"one_sentence_summary":"已记录"'
    ) == [
        ("case_detail.case_story.one_sentence_summary", "已记录")
    ]


def test_target_intake_projector_hides_unapproved_fields() -> None:
    unapproved = IncrementalVisibleJsonProjector(
        TARGET_INTAKE_REPLY_FIRST_VISIBLE_FIELDS
    )
    assert unapproved.feed(
        '{"private_reasoning":"must-not-project","room_utterance":"请说明订单情况"'
    ) == [
        ("room_utterance", "请说明订单情况")
    ]

    nested_unapproved = IncrementalVisibleJsonProjector(
        TARGET_INTAKE_REPLY_FIRST_VISIBLE_FIELDS
    )
    assert nested_unapproved.feed('{"room_utterance":"请说明订单情况"') == [
        ("room_utterance", "请说明订单情况")
    ]
    assert nested_unapproved.feed(
        ',"case_detail":{"private":{"title":"must-not-project"'
    ) == []


def test_legacy_intake_registry_is_independent_from_target_reply_first_fields() -> None:
    legacy_fields = VISIBLE_FIELD_REGISTRY["intake_turn"]["intake_turn_case_detail"]
    legacy_signature = tuple(
        (spec.property_name, spec.field, spec.value_mode)
        for spec in legacy_fields
    )

    assert legacy_fields is not TARGET_INTAKE_REPLY_FIRST_VISIBLE_FIELDS
    assert all(spec.field != "room_utterance" for spec in legacy_fields)
    assert all(
        spec.requires_completed_root_property is None for spec in legacy_fields
    )

    target_with_extra_field = TARGET_INTAKE_REPLY_FIRST_VISIBLE_FIELDS + (
        VisibleFieldSpec(
            "target_only",
            "case_detail.target_only",
            requires_completed_root_property="room_utterance",
        ),
    )
    assert target_with_extra_field[-1] not in legacy_fields
    assert tuple(
        (spec.property_name, spec.field, spec.value_mode)
        for spec in legacy_fields
    ) == legacy_signature

    projector = IncrementalVisibleJsonProjector(legacy_fields)
    assert projector.feed('{"room_utterance":"model draft"') == []


# 所属模块：Python 支撑模块 > test_streaming；函数角色：回归测试用例。
# 具体功能：`test_real_provider_stream_projects_answer_and_ignores_reasoning_channel` 按协议增量产生或消费Agent 流事件，维持顺序、限额和取消语义；关键协作调用：`LiteLlmProxyClient`、`join`、`next`。
# 上下游：上游为 相邻模块输入；下游为 本文件的 `_sse`。
# 系统意义：固定“Python 支撑模块 > test_streaming”的可观察契约，防止后续重构改变业务结果。
def test_real_provider_stream_projects_answer_and_ignores_reasoning_channel() -> None:
    requests: list[httpx.Request] = []

    # 所属模块：Python 支撑模块 > test_streaming；函数角色：类/闭包内部方法。
    # 具体功能：`handler` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`requests.append`、`httpx.Response`。
    # 上下游：上游为 相邻模块输入；下游为 本文件的 `_sse`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        body = _sse(
            {
                "model": "qwen3.7-plus",
                "choices": [
                    {"delta": {"reasoning_content": "不得出现在公开流中"}}
                ],
            },
            {
                "model": "qwen3.7-plus",
                "choices": [
                    {"delta": {"content": '{"room_utterance":"你'}}
                ],
            },
            {
                "model": "qwen3.7-plus",
                "choices": [
                    {
                        "delta": {
                            "content": '好","internal_note":"内部说明"}'
                        },
                        "finish_reason": "stop",
                    }
                ],
            },
            {
                "model": "qwen3.7-plus",
                "choices": [],
                "usage": {
                    "prompt_tokens": 12,
                    "completion_tokens": 8,
                    "total_tokens": 20,
                },
            },
            "[DONE]",
        )
        return httpx.Response(
            200,
            headers={"content-type": "text/event-stream"},
            text=body,
        )

    client = LiteLlmProxyClient(
        "http://litellm.test",
        "qwen3.7-plus",
        "secret",
        transport=httpx.MockTransport(handler),
    )
    updates = list(
        client.generate_stream(
            node_name="intake_turn_case_detail",
            system_prompt="system",
            user_prompt="user",
            output_type=_Reply,
            visible_fields=(
                VisibleFieldSpec("room_utterance", "room_utterance"),
            ),
        )
    )

    visible = "".join(
        update.delta
        for update in updates
        if isinstance(update, StructuredStreamDelta)
    )
    completed = next(
        update
        for update in updates
        if isinstance(update, StructuredStreamCompleted)
    )
    assert visible == "你好"
    assert "不得出现在公开流中" not in visible
    assert completed.generation.value.room_utterance == "你好"
    assert completed.generation.token_usage == {
        "input": 12,
        "output": 8,
        "total": 20,
    }
    assert len(requests) == 1
    sent = json.loads(requests[0].content)
    assert sent["stream"] is True
    assert sent["stream_options"] == {"include_usage": True}


def test_evidence_litellm_stream_projects_root_before_done_once() -> None:
    safe_sentence = "我会先核验本轮材料与案情的关联。"
    private_object = "PRIVATE_NESTED_PROVIDER_TEXT"
    private_array = "PRIVATE_ARRAY_PROVIDER_TEXT"
    requests: list[httpx.Request] = []

    class EvidenceProviderReply(BaseModel):
        model_config = ConfigDict(extra="forbid")

        room_utterance: str
        internal_handoff: dict[str, str]
        items: list[dict[str, str]]

    class ChunkedSseStream(httpx.SyncByteStream):
        def __init__(self) -> None:
            self.terminal_chunk_requested = False

        def __iter__(self):
            yield _sse(
                {
                    "model": "qwen3.7-plus",
                    "choices": [
                        {"delta": {"reasoning_content": "PRIVATE_PROVIDER_REASONING"}}
                    ],
                },
                {
                    "model": "qwen3.7-plus",
                    "choices": [
                        {
                            "delta": {
                                "content": (
                                    '{"internal_handoff":{"room_utterance":"'
                                    + private_object
                                    + '"},"items":[{"room_utterance":"'
                                    + private_array
                                    + '"}],"room_utterance":"'
                                    + safe_sentence[:8]
                                )
                            }
                        }
                    ],
                },
            ).encode("utf-8")
            yield _sse(
                {
                    "model": "qwen3.7-plus",
                    "choices": [
                        {
                            "delta": {"content": safe_sentence[8:] + '"}'},
                            "finish_reason": "stop",
                        }
                    ],
                }
            ).encode("utf-8")
            self.terminal_chunk_requested = True
            yield _sse(
                {
                    "model": "qwen3.7-plus",
                    "choices": [],
                    "usage": {
                        "prompt_tokens": 13,
                        "completion_tokens": 9,
                        "total_tokens": 22,
                    },
                },
                "[DONE]",
            ).encode("utf-8")

    stream = ChunkedSseStream()

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(
            200,
            headers={"content-type": "text/event-stream"},
            stream=stream,
        )

    client = LiteLlmProxyClient(
        "http://litellm.test",
        "qwen3.7-plus",
        "secret",
        transport=httpx.MockTransport(handler),
    )
    updates = client.generate_stream(
        node_name="evidence_turn",
        system_prompt="system",
        user_prompt="user",
        output_type=EvidenceProviderReply,
        visible_fields=(VisibleFieldSpec("room_utterance", "room_utterance"),),
    )

    public_events = []
    public_observer = AgentStreamObserver(
        operation="evidence_turn",
        run_id="AGENT_RUN_EVIDENCE_REAL_PROVIDER",
        publish=public_events.append,
        public_output_policy=EvidencePublicOutputPolicy(),
    )
    first = next(updates)
    assert isinstance(first, StructuredStreamDelta)
    assert first.field == "room_utterance"
    assert first.delta
    assert stream.terminal_chunk_requested is False
    public_observer.visible_delta("evidence_turn", first.field, first.delta)
    all_updates = [first]
    while not public_events:
        update = next(updates)
        all_updates.append(update)
        if isinstance(update, StructuredStreamDelta):
            public_observer.visible_delta(
                "evidence_turn",
                update.field,
                update.delta,
            )
    assert stream.terminal_chunk_requested is False
    for update in updates:
        all_updates.append(update)
        if isinstance(update, StructuredStreamDelta):
            public_observer.visible_delta(
                "evidence_turn",
                update.field,
                update.delta,
            )
    visible = "".join(
        update.delta
        for update in all_updates
        if isinstance(update, StructuredStreamDelta)
    )
    completed = [
        update
        for update in all_updates
        if isinstance(update, StructuredStreamCompleted)
    ]
    guarded_final = guard_evidence_public_reply(
        completed[0].generation.value.room_utterance
    )
    public_observer.finalize_public_output(
        "evidence_turn",
        "room_utterance",
        guarded_final,
    )
    public_visible = "".join(event.delta for event in public_events)

    assert stream.terminal_chunk_requested is True
    assert visible == safe_sentence
    assert public_visible == guarded_final
    assert private_object not in visible
    assert private_array not in visible
    assert len(completed) == 1
    assert completed[0].generation.token_usage == {
        "input": 13,
        "output": 9,
        "total": 22,
    }
    assert len(requests) == 1


@pytest.mark.asyncio
async def test_evidence_native_async_runner_streams_safe_root_before_done_once() -> None:
    from contextlib import suppress

    from app.harness.model_runner import HarnessModelRunner
    from app.harness.prompt_composer import PromptRepository
    from app.streaming import bind_stream_observer

    safe_sentence = "我会先核验本轮材料与案情的关联。"
    risky_sentence = "平台尚未认定商家有过错，也不支持退款。"
    private_object = "PRIVATE_NESTED_ASYNC_PROVIDER_TEXT"
    private_array = "PRIVATE_ARRAY_ASYNC_PROVIDER_TEXT"
    raw_room_utterance = safe_sentence + risky_sentence
    requests: list[httpx.Request] = []

    class NativeAsyncEvidenceReply(BaseModel):
        model_config = ConfigDict(extra="forbid")

        room_utterance: str
        internal_handoff: dict[str, str]
        items: list[dict[str, str]]

    class GatedAsyncSseStream(httpx.AsyncByteStream):
        def __init__(self) -> None:
            self.waiting_before_done = asyncio.Event()
            self.release_done = asyncio.Event()
            self.done_emitted = False

        async def __aiter__(self):
            yield _sse(
                {
                    "model": "qwen3.7-plus",
                    "choices": [
                        {
                            "delta": {
                                "reasoning_content": "PRIVATE_ASYNC_PROVIDER_REASONING"
                            }
                        }
                    ],
                },
                {
                    "model": "qwen3.7-plus",
                    "choices": [
                        {
                            "delta": {
                                "content": (
                                    '{"internal_handoff":{"room_utterance":"'
                                    + private_object
                                    + '"},"items":[{"room_utterance":"'
                                    + private_array
                                    + '"}],"room_utterance":"'
                                    + safe_sentence
                                )
                            }
                        }
                    ],
                },
            ).encode("utf-8")
            self.waiting_before_done.set()
            await self.release_done.wait()
            self.done_emitted = True
            yield _sse(
                {
                    "model": "qwen3.7-plus",
                    "choices": [
                        {
                            "delta": {"content": risky_sentence + '"}'},
                            "finish_reason": "stop",
                        }
                    ],
                },
                {
                    "model": "qwen3.7-plus",
                    "choices": [],
                    "usage": {
                        "prompt_tokens": 17,
                        "completion_tokens": 11,
                        "total_tokens": 28,
                    },
                },
                "[DONE]",
            ).encode("utf-8")

        async def aclose(self) -> None:
            return None

    stream = GatedAsyncSseStream()

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(
            200,
            headers={"content-type": "text/event-stream"},
            stream=stream,
        )

    client = LiteLlmProxyClient(
        "http://litellm.test",
        "qwen3.7-plus",
        "secret",
        async_transport=httpx.MockTransport(handler),
    )
    runner = HarnessModelRunner(llm=client, prompts=PromptRepository())
    public_events = []
    observer = AgentStreamObserver(
        operation="evidence_turn",
        run_id="AGENT_RUN_EVIDENCE_NATIVE_ASYNC",
        publish=public_events.append,
        public_output_policy=EvidencePublicOutputPolicy(),
    )
    invocation = None
    try:
        with bind_stream_observer(observer):
            invocation = asyncio.create_task(
                runner.ainvoke_structured(
                    node_name="evidence_turn",
                    case_data={"case_id": "CASE_EVIDENCE_NATIVE_ASYNC"},
                    output_type=NativeAsyncEvidenceReply,
                )
            )
            await asyncio.wait_for(stream.waiting_before_done.wait(), timeout=1.0)
            await asyncio.sleep(0)
            early_visible = "".join(
                event.delta
                for event in public_events
                if getattr(event, "type", None) == "visible_delta"
            )
            assert early_visible == safe_sentence
            assert stream.done_emitted is False
            assert invocation.done() is False
            assert private_object not in early_visible
            assert private_array not in early_visible

            stream.release_done.set()
            generation = await asyncio.wait_for(invocation, timeout=1.0)
            guarded_final = guard_evidence_public_reply(
                generation.value.room_utterance
            )
            observer.finalize_public_output(
                "evidence_turn",
                "room_utterance",
                guarded_final,
            )
            observer.flush_deferred_usage()
    finally:
        stream.release_done.set()
        if invocation is not None and not invocation.done():
            invocation.cancel()
            with suppress(asyncio.CancelledError):
                await invocation
        await client.aclose()

    visible = "".join(
        event.delta
        for event in public_events
        if getattr(event, "type", None) == "visible_delta"
    )
    usage_events = [
        event for event in public_events if getattr(event, "type", None) == "usage"
    ]
    assert len(requests) == 1
    assert json.loads(requests[0].content)["stream"] is True
    assert stream.done_emitted is True
    assert generation.value.room_utterance == raw_room_utterance
    assert visible == guarded_final
    assert risky_sentence not in visible
    assert private_object not in visible
    assert private_array not in visible
    assert len(usage_events) == 1
    assert usage_events[0].token_usage == {
        "input": 17,
        "output": 11,
        "total": 28,
    }


# 所属模块：Python 支撑模块 > test_streaming；函数角色：回归测试用例。
# 具体功能：`test_invalid_streamed_schema_fails_closed_without_second_model_call` 按协议增量产生或消费模型状态，维持顺序、限额和取消语义；关键协作调用：`LiteLlmProxyClient`、`httpx.Response`、`pytest.raises`。
# 上下游：上游为 相邻模块输入；下游为 本文件的 `_sse`。
# 系统意义：固定“Python 支撑模块 > test_streaming”的可观察契约，防止后续重构改变业务结果。
def test_invalid_streamed_schema_fails_closed_without_second_model_call() -> None:
    request_count = 0

    # 所属模块：Python 支撑模块 > test_streaming；函数角色：类/闭包内部方法。
    # 具体功能：`handler` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`httpx.Response`。
    # 上下游：上游为 相邻模块输入；下游为 本文件的 `_sse`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    def handler(_: httpx.Request) -> httpx.Response:
        nonlocal request_count
        request_count += 1
        return httpx.Response(
            200,
            headers={"content-type": "text/event-stream"},
            text=_sse(
                {
                    "choices": [
                        {
                            "delta": {"content": '{"room_utterance":12}'},
                            "finish_reason": "stop",
                        }
                    ]
                },
                "[DONE]",
            ),
        )

    client = LiteLlmProxyClient(
        "http://litellm.test",
        "qwen3.7-plus",
        "secret",
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(AgentOutputSchemaError):
        list(
            client.generate_stream(
                node_name="intake_turn_case_detail",
                system_prompt="system",
                user_prompt="user",
                output_type=_Reply,
                visible_fields=(
                    VisibleFieldSpec("room_utterance", "room_utterance"),
                ),
            )
        )

    assert request_count == 1


# 所属模块：Python 支撑模块 > test_streaming；函数角色：回归测试用例。
# 具体功能：`test_ndjson_endpoint_uses_one_versioned_terminal_contract` 验证本阶段状态在固定案例中的输出、边界和失败行为；关键协作调用：`FastAPI`、`app.post`、`workflow_ndjson_response`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `FastAPI`、`app.post`、`workflow_ndjson_response`、`TestClient`。
# 系统意义：固定“Python 支撑模块 > test_streaming”的可观察契约，防止后续重构改变业务结果。
def test_ndjson_endpoint_uses_one_versioned_terminal_contract() -> None:
    app = FastAPI()

    # 所属模块：Python 支撑模块 > test_streaming；函数角色：类/闭包内部方法。
    # 具体功能：`stream` 按协议增量产生或消费Agent 流事件，维持顺序、限额和取消语义；关键协作调用：`app.post`、`workflow_ndjson_response`。
    # 上下游：上游为 相邻模块输入；下游为 协作调用 `app.post`、`workflow_ndjson_response`。
    # 系统意义：提供实时反馈，同时阻止未校验完整结果或内部推理经流通道泄露。
    @app.post("/stream")
    async def stream():
        return workflow_ndjson_response(
            operation="test_operation",
            run_id="AGENT_RUN_test",
            invoke=lambda: {"answer": "完成"},
        )

    with TestClient(app) as client:
        response = client.post("/stream")

    assert response.status_code == 200
    assert response.headers["x-agent-run-id"] == "AGENT_RUN_test"
    events = [json.loads(line) for line in response.text.splitlines() if line]
    assert [event["type"] for event in events] == ["start", "final"]
    assert [event["sequence"] for event in events] == [0, 1]
    assert all(event["schema_version"] == "agent_stream.v1" for event in events)
    assert events[-1]["response"] == {"answer": "完成"}


def test_legacy_evidence_opening_accepts_raw_authority_hash_and_streams_guarded_result() -> None:
    from app.agents.evidence_clerk.workflow import EvidenceTurnWorkflow
    from app.config import Settings
    from app.main import create_app

    case_id = "CASE_legacy_evidence_opening"
    actor_id = "USER_legacy_evidence_opening"
    access_session_id = "ACCESS_legacy_evidence_opening"
    agent_session_id = "AGENT_SESSION_legacy_evidence_opening"
    prompt_profile_id = "EVIDENCE_CLERK:USER:v1"
    memory_policy_id = "MEMEO_DEFAULT"
    conversation_scope = (
        f"default:{case_id}:EVIDENCE:{actor_id}:USER:EVIDENCE_CLERK:"
        f"{prompt_profile_id}:{access_session_id}"
    )
    user_source = "MESSAGE_USER_INTAKE"
    merchant_source = "MESSAGE_MERCHANT_INTAKE"
    fact_id = "FACT_DELIVERY_RECEIPT"
    raw_matrix: dict[str, object] = {
        "schema_version": "case_fact_matrix.v2",
        "case_id": case_id,
        "matrix_id": "CASE_MATRIX_LEGACY_EVIDENCE_OPENING",
        "matrix_version": 9,
        "matrix_kind": "BILATERAL_FROZEN",
        "parent_ref": None,
        "party_map": {
            "initiator_role": "USER",
            "respondent_role": "MERCHANT",
        },
        "source_refs": [user_source, merchant_source],
        "case_overview": {
            "neutral_summary": "双方对包裹是否由用户本人签收存在争议。",
            "core_conflict": "包裹是否由用户本人签收。",
            "summary_source_fact_ids": [fact_id],
        },
        "claims": {
            "initiator_claim": {
                "initiator_role": "USER",
                "requested_resolution": "REFUND",
                # Fixed Java/JCS vector: RFC 8785 emits 299 for this floating
                # JSON value, while ordinary json.dumps emits 299.0.
                "requested_amount": 299.0,
                "requested_items": "蓝牙耳机",
                "reason_summary": "用户称未收到包裹。",
                "position_summary": "用户请求退款。",
                "source_refs": [user_source],
            },
            "respondent_reported_by_initiator": None,
            "respondent_direct": {
                "respondent_role": "MERCHANT",
                "attitude": "DISAGREE",
                "position_summary": "商家称物流记录显示包裹已签收。",
                "alternative_proposal": None,
                "source_type": "RESPONDENT_DIRECT_INTAKE",
                "source_refs": [merchant_source],
            },
            "claim_conflict": "双方对签收事实存在争议。",
        },
        "fact_rows": [
            {
                "fact_id": fact_id,
                "category": "LOGISTICS",
                "fact_target": "包裹是否由用户本人签收",
                "materiality": "CORE",
                "origin": {
                    "introduced_stage": "INITIATOR_INTAKE",
                    "source_refs": [user_source],
                },
                "positions": {
                    "USER": {
                        "stance": "DENY",
                        "position_summary": "用户否认本人或同住人员签收。",
                        "asserted_value": "未签收",
                        "source_type": "DIRECT_PARTY_STATEMENT",
                        "source_refs": [user_source],
                    },
                    "MERCHANT": {
                        "stance": "CONFIRM",
                        "position_summary": "商家称物流记录显示已签收。",
                        "asserted_value": "已签收",
                        "source_type": "DIRECT_PARTY_STATEMENT",
                        "source_refs": [merchant_source],
                    },
                },
                "party_alignment": {
                    "status": "CONTESTED",
                    "agreed_statement": None,
                    "conflict_summary": "双方对是否签收存在直接分歧。",
                },
                "requires_resolution": True,
                "truth_status": "NOT_EVALUATED",
                "evidence_coverage_status": "PENDING_EVIDENCE_REVIEW",
            }
        ],
        "fact_relationships": [],
        "generation_ref": {
            "actor_role": "MERCHANT",
            "source_stage": "RESPONDENT_INTAKE",
            "latest_source_ref": merchant_source,
            "source_context_hash": "b" * 64,
        },
        "fact_indexes": {
            "not_computed_fact_ids": [],
            "agreed_fact_ids": [],
            "partially_agreed_fact_ids": [],
            "contested_fact_ids": [fact_id],
            "one_sided_fact_ids": [],
            "unresolved_fact_ids": [],
            "core_fact_ids": [fact_id],
            "requires_resolution_fact_ids": [fact_id],
        },
    }

    expected_jcs_hash = (
        "00d3a6d7de86516205e26e883bd53c15b7a7c447137227a59dd44efdf27fc7d3"
    )
    ordinary_json_hash = hashlib.sha256(
        json.dumps(
            raw_matrix,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()
    assert ordinary_json_hash == (
        "ccedfba8ab23142f5b44140ea4b4745de698bc8a01c2a0012568786e31b5fe52"
    )
    assert ordinary_json_hash != expected_jcs_hash
    raw_matrix["content_hash"] = expected_jcs_hash

    payload = {
        "context_envelope": {
            "schema_version": "evidence_context_envelope.v1",
            "captured_at": "2026-08-07T17:21:44+08:00",
            "case_snapshot": {
                "case_id": case_id,
                "case_version": 12,
                "case_status": "EVIDENCE_OPEN",
                "case_type": "AFTER_SALE_DISPUTE",
                "dispute_type": "SIGNED_NOT_RECEIVED",
                "title": "签收未收到争议",
                "description": "物流记录显示签收，但用户称本人未收到包裹。",
                "risk_level": "MEDIUM",
                "route_type": "NORMAL_HEARING",
                "order_id": "ORDER_legacy_evidence_opening",
                "after_sale_id": None,
                "logistics_id": "LOGISTICS_legacy_evidence_opening",
                "source_type": "LOCAL",
                "initiator_role": "USER",
                "source_system": None,
                "external_case_ref": None,
                "current_room": "EVIDENCE",
                "current_deadline_at": "2026-08-08T17:21:44+08:00",
            },
            "intake_dossier_snapshot": {
                "dossier_id": "DOSSIER_legacy_evidence_opening",
                "schema_version": "intake-dossier.v2",
                "dossier_version": 3,
                "source_turn_no": 10,
                "quality_score": 90,
                "ready_for_next_step": True,
                "admission_recommendation": "ACCEPTED",
                "updated_at": "2026-08-07T17:21:40+08:00",
                "payload": {
                    "schema_version": "intake-dossier.v2",
                    "case_fact_matrix": raw_matrix,
                },
            },
            "actor_snapshot": {
                "actor_id": actor_id,
                "actor_role": "USER",
                "initiator_role": "USER",
                "access_session_id": access_session_id,
                "agent_session_id": agent_session_id,
                "conversation_scope": conversation_scope,
                "prompt_profile_id": prompt_profile_id,
                "memory_policy_id": memory_policy_id,
            },
            "current_event": {
                "event_id": "EVIDENCE_OPENING_1",
                "event_type": "ROOM_OPENING",
                "message_type": "AGENT_MESSAGE",
                "actor_id": actor_id,
                "actor_role": "USER",
                "text": None,
                "attachment_refs": [],
                "turn_no": 1,
                "occurred_at": "2026-08-07T17:21:44+08:00",
            },
            "visible_evidence": [],
            "private_conversation": {
                "agent_session_id": agent_session_id,
                "conversation_scope": conversation_scope,
                "source_count": 0,
                "truncated": False,
                "recent_turns": [],
            },
            "room_policy": {
                "room_id": "ROOM_legacy_evidence_opening",
                "room_type": "EVIDENCE",
                "room_status": "OPEN",
                "current_deadline_at": "2026-08-08T17:21:44+08:00",
                "initiator_role": "USER",
                "initiator_evidence_required": True,
            },
        },
        "agent_context": {
            "tenant_id": "default",
            "case_id": case_id,
            "room_type": "EVIDENCE",
            "actor_id": actor_id,
            "actor_role": "USER",
            "access_session_id": access_session_id,
            "permission_level": "PARTY_USER",
            "permission_scopes": ["EVIDENCE_SUBMIT"],
            "agent_key": "EVIDENCE_CLERK",
            "agent_invocation_id": "AGENT_INVOCATION_legacy_evidence_opening",
            "agent_session_id": agent_session_id,
            "conversation_scope": conversation_scope,
            "scope_type": "EVIDENCE_PARTY_PRIVATE",
            "allowed_actor_ids": [actor_id],
            "allowed_actor_roles": ["USER"],
            "prompt_profile_id": prompt_profile_id,
            "memory_policy_id": memory_policy_id,
        },
    }

    class FormalOpeningRunner:
        def __init__(self) -> None:
            self.calls: list[dict[str, object]] = []

        def invoke_structured(self, **kwargs):
            self.calls.append(kwargs)
            observer = current_stream_observer()
            assert observer is not None
            observer.visible_delta(
                "evidence_turn",
                "room_utterance",
                "RAW_MODEL_PRIVATE_DELTA",
            )
            observer.usage(
                node_name="evidence_turn",
                model="stub-formal-evidence-model",
                latency_ms=12,
                token_usage={
                    "input_tokens": 7,
                    "output_tokens": 3,
                    "total_tokens": 10,
                },
            )
            return SimpleNamespace(
                value=kwargs["output_type"](
                    room_utterance="请围绕冻结事实补充与签收交接有关的证据。",
                    evidence_requests=[],
                    verification_suggestions=[],
                    authenticity_flags=[],
                    evidence_assessments=[],
                    fact_matrix_patch=[],
                    human_review_tasks=[],
                    internal_handoff={
                        "evidence_change_summary": "本轮尚无新增证据。",
                        "matrix_change_summary": "事实证据矩阵保持不变。",
                        "remaining_conflicts": ["包裹是否由用户本人签收。"],
                        "uncovered_fact_ids": [fact_id],
                        "human_review_evidence_ids": [],
                        "judge_attention_points": [],
                    },
                    confidence=0.5,
                )
            )

    runner = FormalOpeningRunner()
    app = create_app(
        Settings(
            litellm_master_key="test-litellm-master-key",
            langfuse_public_key="pk-test-key",
            langfuse_secret_key="sk-test-secret",
            java_service_secret="test-java-service-secret",
            python_agent_service_secret="test-agent-service-secret",
            langfuse_enabled=False,
        ),
        evidence_turn_workflow=EvidenceTurnWorkflow(model_runner=runner),
    )
    headers = {
        "X-Service-Secret": "test-agent-service-secret",
        "X-Agent-Run-Id": "AGENT_RUN_legacy_evidence_opening",
    }
    with TestClient(app) as client:
        response = client.post(
            "/internal/agents/evidence/turn/stream",
            headers=headers,
            json=payload,
        )
        tampered_payload = copy.deepcopy(payload)
        tampered_payload["context_envelope"]["intake_dossier_snapshot"]["payload"][
            "case_fact_matrix"
        ]["case_overview"]["neutral_summary"] = "被篡改的权威矩阵摘要。"
        tampered = client.post(
            "/internal/agents/evidence/turn/stream",
            headers={**headers, "X-Agent-Run-Id": "AGENT_RUN_tampered_evidence_opening"},
            json=tampered_payload,
        )

    assert response.status_code == 200
    events = [json.loads(line) for line in response.text.splitlines() if line]
    assert [event["type"] for event in events] == [
        "start",
        "visible_delta",
        "usage",
        "final",
    ]
    guarded_utterance = events[-1]["response"]["room_utterance"]
    assert events[1]["node_name"] == "evidence_turn"
    assert events[1]["field"] == "room_utterance"
    assert events[1]["delta"] == guarded_utterance
    assert "请围绕冻结事实" in guarded_utterance
    assert events[2]["token_usage"] == {
        "input_tokens": 7,
        "output_tokens": 3,
        "total_tokens": 10,
    }
    assert "RAW_MODEL_PRIVATE_DELTA" not in response.text
    assert len(runner.calls) == 1
    assert runner.calls[0]["case_data"]["task_mode"] == "ROOM_OPENING"

    tampered_events = [
        json.loads(line) for line in tampered.text.splitlines() if line
    ]
    assert [event["type"] for event in tampered_events] == ["start", "error"]
    assert tampered_events[-1]["code"] == "INTERNAL_ERROR"
    assert tampered_events[-1]["message"] == "internal service error"
    assert "content hash" not in tampered.text
    assert len(runner.calls) == 1


def test_ndjson_maps_transport_output_error_to_public_schema_contract() -> None:
    app = FastAPI()

    def fail_with_wrapped_schema_error() -> None:
        try:
            raise AgentOutputSchemaError(
                "hearing_intake_synthesis",
                "private schema validation details",
            )
        except AgentOutputSchemaError as error:
            raise ModelTransportOutputError("private transport details") from error

    @app.post("/stream")
    async def stream():
        return workflow_ndjson_response(
            operation="hearing_intake_synthesis",
            run_id="AGENT_RUN_transport_output_error",
            invoke=fail_with_wrapped_schema_error,
        )

    with TestClient(app) as client:
        response = client.post("/stream")

    events = [json.loads(line) for line in response.text.splitlines() if line]
    assert [event["type"] for event in events] == ["start", "error"]
    assert events[-1] == {
        "schema_version": "agent_stream.v1",
        "type": "error",
        "run_id": "AGENT_RUN_transport_output_error",
        "sequence": 1,
        "timestamp": events[-1]["timestamp"],
        "code": "AGENT_OUTPUT_SCHEMA_INVALID",
        "message": "agent returned invalid structured output",
        "retryable": False,
        "visible_output_emitted": False,
        "node_name": "hearing_intake_synthesis",
    }
    assert "private schema validation details" not in response.text
    assert "private transport details" not in response.text
    assert "INTERNAL_ERROR" not in response.text


def test_ndjson_preserves_exhausted_schema_repair_classification() -> None:
    first_sentinel = "PRIVATE_INVALID_FIRST_OUTPUT"
    second_sentinel = "PRIVATE_INVALID_SECOND_OUTPUT"
    provider_documents = [
        {
            "model": "governed-model",
            "choices": [
                {
                    "message": {
                        "content": json.dumps({"unexpected": first_sentinel})
                    }
                }
            ],
            "usage": {
                "prompt_tokens": 7,
                "completion_tokens": 3,
                "total_tokens": 10,
            },
        },
        {
            "model": "governed-model",
            "choices": [
                {
                    "message": {
                        "content": json.dumps({"unexpected": second_sentinel})
                    }
                }
            ],
            "usage": {
                "prompt_tokens": 9,
                "completion_tokens": 4,
                "total_tokens": 13,
            },
        },
    ]

    calls, events, response_text = _governed_transport_ndjson(
        provider_documents,
        attempts=2,
        repairs=1,
    )

    assert len(calls) == 2
    assert [event["type"] for event in events] == ["start", "error"]
    assert events[-1]["code"] == "AGENT_OUTPUT_SCHEMA_REPAIR_EXHAUSTED"
    assert events[-1]["message"] == "agent returned invalid structured output"
    assert events[-1]["retryable"] is False
    assert events[-1]["visible_output_emitted"] is False
    assert events[-1]["node_name"] == "intake_turn_case_detail"
    assert not {"visible_delta", "usage", "final"} & {
        event["type"] for event in events
    }
    assert first_sentinel not in response_text
    assert second_sentinel not in response_text


def test_ndjson_preserves_governed_provider_contract_classification() -> None:
    private_sentinel = "PRIVATE_PROVIDER_CONTRACT_DETAILS"
    provider_document = {
        "model": "governed-model",
        "choices": [
            {
                "message": {
                    "content": json.dumps(
                        {
                            "room_utterance": "validated public reply",
                            "internal_note": private_sentinel,
                        }
                    )
                }
            }
        ],
    }

    calls, events, response_text = _governed_transport_ndjson(
        [provider_document],
        attempts=2,
        repairs=1,
    )

    assert len(calls) == 1
    assert [event["type"] for event in events] == ["start", "error"]
    assert events[-1]["code"] == "AGENT_PROVIDER_CONTRACT_INVALID"
    assert events[-1]["message"] == (
        "agent provider returned an invalid governed response"
    )
    assert events[-1]["retryable"] is False
    assert events[-1]["visible_output_emitted"] is False
    assert events[-1]["node_name"] == "intake_turn_case_detail"
    assert private_sentinel not in response_text


def test_ndjson_keeps_initial_schema_failure_generic_without_repair_budget() -> None:
    private_sentinel = "PRIVATE_INITIAL_INVALID_OUTPUT"
    provider_document = {
        "model": "governed-model",
        "choices": [
            {"message": {"content": json.dumps({"unexpected": private_sentinel})}}
        ],
        "usage": {
            "prompt_tokens": 7,
            "completion_tokens": 3,
            "total_tokens": 10,
        },
    }

    calls, events, response_text = _governed_transport_ndjson(
        [provider_document],
        attempts=1,
        repairs=0,
    )

    assert len(calls) == 1
    assert [event["type"] for event in events] == ["start", "error"]
    assert events[-1]["code"] == "AGENT_OUTPUT_SCHEMA_INVALID"
    assert events[-1]["message"] == "agent returned invalid structured output"
    assert events[-1]["retryable"] is False
    assert events[-1]["visible_output_emitted"] is False
    assert events[-1]["node_name"] == "intake_turn_case_detail"
    assert private_sentinel not in response_text


# 所属模块：Python 支撑模块 > test_streaming；函数角色：回归测试用例。
# 具体功能：`test_observer_splits_large_deltas_and_rejects_unbounded_visible_output` 按协议增量产生或消费结构化输出，维持顺序、限额和取消语义；关键协作调用：`AgentStreamObserver`、`observer.visible_delta`、`join`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `AgentStreamObserver`、`observer.visible_delta`、`join`、`pytest.raises`。
# 系统意义：固定“Python 支撑模块 > test_streaming”的可观察契约，防止后续重构改变业务结果。
def test_observer_splits_large_deltas_and_rejects_unbounded_visible_output() -> None:
    published = []
    observer = AgentStreamObserver(
        operation="intake_turn",
        run_id="AGENT_RUN_limits",
        publish=published.append,
    )
    large_delta = "x" * (STREAM_EVENT_MAX_DELTA_CHARS * 2 + 7)

    observer.visible_delta(
        "intake_turn_case_detail",
        "room_utterance",
        large_delta,
    )

    assert "".join(event.delta for event in published) == large_delta
    assert len(published) == 3
    assert all(
        len(event.delta) <= STREAM_EVENT_MAX_DELTA_CHARS for event in published
    )
    with pytest.raises(AgentStreamLimitExceeded):
        observer.visible_delta(
            "intake_turn_case_detail",
            "room_utterance",
            "y" * (STREAM_MAX_VISIBLE_OUTPUT_CHARS - len(large_delta) + 1),
        )


def test_observer_reorders_events_constructed_by_parallel_model_threads() -> None:
    published = []
    observer = AgentStreamObserver(
        operation="hearing_evidence_synthesis",
        run_id="AGENT_RUN_parallel_usage",
        publish=published.append,
    )
    first = StreamUsageEvent(
        **observer._base_fields(),
        node_name="hearing_evidence_file_assessment",
        model="test-model",
        latency_ms=10,
        token_usage={"total_tokens": 10},
    )
    second = StreamUsageEvent(
        **observer._base_fields(),
        node_name="hearing_evidence_file_assessment",
        model="test-model",
        latency_ms=20,
        token_usage={"total_tokens": 20},
    )

    observer._emit(second)
    assert published == []

    observer._emit(first)
    assert [event.sequence for event in published] == [0, 1]


# 所属模块：Python 支撑模块 > test_streaming；函数角色：回归测试用例。
# 具体功能：`test_projector_rejects_model_document_over_hard_limit` 验证模型状态在固定案例中的输出、边界和失败行为；关键协作调用：`IncrementalVisibleJsonProjector`、`pytest.raises`、`projector.feed`。
# 上下游：上游为 相邻模块输入；下游为 协作调用 `IncrementalVisibleJsonProjector`、`pytest.raises`、`projector.feed`。
# 系统意义：固定“Python 支撑模块 > test_streaming”的可观察契约，防止后续重构改变业务结果。
def test_projector_rejects_model_document_over_hard_limit() -> None:
    projector = IncrementalVisibleJsonProjector(())

    with pytest.raises(AgentStreamLimitExceeded):
        projector.feed("x" * (STREAM_MAX_MODEL_DOCUMENT_CHARS + 1))


# 所属模块：Python 支撑模块 > test_streaming；函数角色：回归测试用例。
# 具体功能：`test_ndjson_slow_consumer_applies_bounded_backpressure` 验证本阶段状态在固定案例中的输出、边界和失败行为；关键协作调用：`asyncio.run`、`Lock`、`workflow_ndjson_response`；返回/更新字段：`answer`。
# 上下游：上游为 相邻模块输入；下游为 本文件的 `scenario`。
# 系统意义：固定“Python 支撑模块 > test_streaming”的可观察契约，防止后续重构改变业务结果。
def test_ndjson_slow_consumer_applies_bounded_backpressure() -> None:
    # 所属模块：Python 支撑模块 > test_streaming；函数角色：类/闭包内部方法。
    # 具体功能：`scenario` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`Lock`、`workflow_ndjson_response`、`json.loads`；返回/更新字段：`answer`。
    # 上下游：上游为 本文件的 `test_ndjson_slow_consumer_applies_bounded_backpressure`、`test_ndjson_disconnect_cancels_a_blocked_publisher`；下游为 协作调用 `Lock`、`workflow_ndjson_response`、`json.loads`、`current_stream_observer`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    async def scenario() -> None:
        produced = 0
        produced_lock = Lock()
        delta_count = STREAM_EVENT_QUEUE_MAXSIZE * 2

        # 所属模块：Python 支撑模块 > test_streaming；函数角色：类/闭包内部方法。
        # 具体功能：`invoke` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`current_stream_observer`、`observer.visible_delta`；返回/更新字段：`answer`。
        # 上下游：上游为 相邻模块输入；下游为 协作调用 `current_stream_observer`、`observer.visible_delta`。
        # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
        def invoke() -> dict[str, str]:
            nonlocal produced
            observer = current_stream_observer()
            assert observer is not None
            for _ in range(delta_count):
                observer.visible_delta(
                    "intake_turn_case_detail",
                    "room_utterance",
                    "x",
                )
                with produced_lock:
                    produced += 1
            return {"answer": "done"}

        response = workflow_ndjson_response(
            operation="intake_turn",
            run_id="AGENT_RUN_backpressure",
            invoke=invoke,
        )
        iterator = response.body_iterator
        start = json.loads((await anext(iterator)).strip())
        assert start["type"] == "start"

        for _ in range(500):
            with produced_lock:
                current_produced = produced
            if current_produced == STREAM_EVENT_QUEUE_MAXSIZE:
                break
            await asyncio.sleep(0.01)
        assert current_produced == STREAM_EVENT_QUEUE_MAXSIZE

        # With consumption paused, the producer cannot enqueue beyond the
        # fixed queue capacity regardless of how many deltas it wants to emit.
        await asyncio.sleep(0.1)
        with produced_lock:
            assert produced == STREAM_EVENT_QUEUE_MAXSIZE

        types = [start["type"]]
        async for line in iterator:
            types.append(json.loads(line.strip())["type"])
        assert types.count("visible_delta") == delta_count
        assert types[-1] == "final"

    asyncio.run(scenario())


# 所属模块：Python 支撑模块 > test_streaming；函数角色：回归测试用例。
# 具体功能：`test_ndjson_disconnect_cancels_a_blocked_publisher` 验证本阶段状态在固定案例中的输出、边界和失败行为；关键协作调用：`asyncio.run`、`Event`、`Lock`。
# 上下游：上游为 相邻模块输入；下游为 本文件的 `scenario`。
# 系统意义：固定“Python 支撑模块 > test_streaming”的可观察契约，防止后续重构改变业务结果。
def test_ndjson_disconnect_cancels_a_blocked_publisher() -> None:
    # 所属模块：Python 支撑模块 > test_streaming；函数角色：类/闭包内部方法。
    # 具体功能：`scenario` 围绕本阶段状态计算该函数独立负责的业务派生值；关键协作调用：`Event`、`Lock`、`workflow_ndjson_response`。
    # 上下游：上游为 本文件的 `test_ndjson_slow_consumer_applies_bounded_backpressure`、`test_ndjson_disconnect_cancels_a_blocked_publisher`；下游为 协作调用 `Event`、`Lock`、`workflow_ndjson_response`、`current_stream_observer`。
    # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
    async def scenario() -> None:
        finished = Event()
        published = 0
        published_lock = Lock()

        # 所属模块：Python 支撑模块 > test_streaming；函数角色：类/闭包内部方法。
        # 具体功能：`invoke` 驱动本阶段状态对应的业务步骤并返回阶段结果；关键协作调用：`current_stream_observer`、`observer.visible_delta`。
        # 上下游：上游为 相邻模块输入；下游为 协作调用 `current_stream_observer`、`observer.visible_delta`。
        # 系统意义：该函数在系统中的业务边界是：接口稳定、错误显式、不绕过权限审计。
        def invoke() -> None:
            nonlocal published
            observer = current_stream_observer()
            assert observer is not None
            try:
                while True:
                    observer.visible_delta(
                        "intake_turn_case_detail",
                        "room_utterance",
                        "x",
                    )
                    with published_lock:
                        published += 1
            finally:
                finished.set()

        response = workflow_ndjson_response(
            operation="intake_turn",
            run_id="AGENT_RUN_cancel",
            invoke=invoke,
        )
        iterator = response.body_iterator
        assert json.loads((await anext(iterator)).strip())["type"] == "start"

        for _ in range(500):
            with published_lock:
                current_published = published
            if current_published == STREAM_EVENT_QUEUE_MAXSIZE:
                break
            await asyncio.sleep(0.01)
        assert current_published == STREAM_EVENT_QUEUE_MAXSIZE

        await iterator.aclose()
        assert await asyncio.to_thread(finished.wait, 1.0)
        with published_lock:
            count_after_disconnect = published
        await asyncio.sleep(0.1)
        with published_lock:
            assert published == count_after_disconnect
        with pytest.raises(StopAsyncIteration):
            await anext(iterator)

    asyncio.run(scenario())


def _load_two_turn_uat_contract(module_name: str) -> object:
    script = Path(__file__).resolve().parents[2] / ".local-dev" / "two-turn-api-uat.py"
    spec = importlib.util.spec_from_file_location(module_name, script)
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    return module


class _OfflineSseHeaders:
    @staticmethod
    def get_content_type() -> str:
        return "text/event-stream"


class _OfflineSseResponse:
    status = 200
    headers = _OfflineSseHeaders()

    def __init__(self, events: list[dict[str, object]]) -> None:
        body = "".join(
            f"data: {json.dumps(event, separators=(',', ':'))}\n\n" for event in events
        )
        self._lines = body.encode("utf-8").splitlines(keepends=True)

    def __enter__(self) -> _OfflineSseResponse:
        return self

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> None:
        return None

    def __iter__(self):
        return iter(self._lines)


class _OfflineSseOpener:
    def __init__(self, events: list[dict[str, object]]) -> None:
        self._events = events
        self.calls: list[tuple[str, str, float]] = []

    def open(self, request: object, *, timeout: float) -> _OfflineSseResponse:
        method = request.get_method()
        url = request.full_url
        self.calls.append((method, url, timeout))
        return _OfflineSseResponse(self._events)


def _offline_uat_event(
    run_id: str,
    attempt_no: int,
    attempt_id: str,
    sequence: int,
    type_name: str,
    **payload: object,
) -> dict[str, object]:
    return {
        "run_id": run_id,
        "attempt_no": attempt_no,
        "attempt_id": attempt_id,
        "sequence": sequence,
        "cursor": f"v2:{attempt_id}:{sequence}",
        "type": type_name,
        **payload,
    }


def _offline_retry_trace(*, terminal_type: str) -> tuple[list[dict[str, object]], int]:
    run_id = "target-intake-run:offline-retry-contract"
    first_attempt = "target-intake-attempt:offline-retry-contract:1"
    second_attempt = "target-intake-attempt:offline-retry-contract:2"
    events = [
        _offline_uat_event(run_id, 1, first_attempt, 0, "attempt_started"),
        _offline_uat_event(
            run_id,
            1,
            first_attempt,
            1,
            "visible_delta",
            field="room_utterance",
            delta="safe-first-attempt",
        ),
        _offline_uat_event(
            run_id,
            1,
            first_attempt,
            2,
            "visible_delta",
            field="case_detail.issue",
            delta="safe-detail",
        ),
        _offline_uat_event(
            run_id,
            1,
            first_attempt,
            3,
            "usage",
            token_usage={"input_tokens": 1, "output_tokens": 1, "total_tokens": 2},
        ),
        _offline_uat_event(
            run_id,
            1,
            first_attempt,
            4,
            "attempt_aborted",
            code="GRAPH_EXECUTION_LEASE_LOST",
        ),
        _offline_uat_event(run_id, 2, second_attempt, 0, "attempt_started"),
        _offline_uat_event(
            run_id,
            2,
            second_attempt,
            1,
            "attempt_reset",
            reset_attempt_id=first_attempt,
        ),
        _offline_uat_event(
            run_id,
            2,
            second_attempt,
            2,
            "visible_delta",
            field="room_utterance",
            delta="safe-winning-attempt",
        ),
        _offline_uat_event(
            run_id,
            2,
            second_attempt,
            3,
            "visible_delta",
            field="case_detail.resolution",
            delta="safe-resolution",
        ),
        _offline_uat_event(
            run_id,
            2,
            second_attempt,
            4,
            "usage",
            token_usage={"input_tokens": 2, "output_tokens": 2, "total_tokens": 4},
        ),
    ]
    terminal_payload: dict[str, object]
    if terminal_type == "final":
        terminal_payload = {
            "response": {
                "final_result_ref": "urn:test:graph-result:offline-retry-contract",
                "final_result_hash": "f" * 64,
            }
        }
    else:
        terminal_payload = {
            "code": "GRAPH_STREAM_INTERNAL_ERROR",
            "retryable": False,
        }
    events.append(
        _offline_uat_event(
            run_id,
            2,
            second_attempt,
            5,
            terminal_type,
            **terminal_payload,
        )
    )
    return events, 5


def _offline_uat_context(
    module: object,
    monkeypatch: pytest.MonkeyPatch,
    events: list[dict[str, object]],
    first_attempt_size: int,
    *,
    replay_events: list[dict[str, object]] | None = None,
) -> tuple[object, _OfflineSseOpener, list[tuple[str, str]]]:
    opener = _OfflineSseOpener(events)
    replay_calls: list[tuple[str, str]] = []
    durable_events = replay_events if replay_events is not None else events
    replay_pages = (
        durable_events[:first_attempt_size],
        durable_events[first_attempt_size:],
    )

    def request_json(
        context: object,
        stage: str,
        method: str,
        path: str,
        **kwargs: object,
    ) -> tuple[int, dict[str, object]]:
        del context, stage, kwargs
        replay_calls.append((method, path))
        page_index = len(replay_calls) - 1
        assert page_index < len(replay_pages)
        return 200, {"data": replay_pages[page_index]}

    monkeypatch.setattr(module, "request_json", request_json)
    context = module.UatContext(
        base_url="http://offline.test",
        deadline=module.Deadline(timeout_seconds=300.0),
        user_id="offline-user",
        merchant_id="offline-merchant",
        opener=opener,
    )
    return context, opener, replay_calls


def test_uat_observer_keeps_attempt_abort_local_and_accepts_winning_retry(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    module = _load_two_turn_uat_contract("_two_turn_uat_retry_success")
    events, first_attempt_size = _offline_retry_trace(terminal_type="final")
    context, opener, replay_calls = _offline_uat_context(
        module, monkeypatch, events, first_attempt_size
    )

    module.observe_agent_run(context, "offline_retry_success", events[0]["run_id"])

    assert len(opener.calls) == 1
    assert opener.calls[0][0] == "GET"
    assert [method for method, _ in replay_calls] == ["GET", "GET"]
    assert "after_sequence=-1" in replay_calls[0][1]
    assert "after_cursor=" in replay_calls[1][1]


def test_uat_observer_reports_retry_terminal_error_instead_of_missing_final(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    module = _load_two_turn_uat_contract("_two_turn_uat_retry_error")
    events, first_attempt_size = _offline_retry_trace(terminal_type="error")
    context, opener, replay_calls = _offline_uat_context(
        module, monkeypatch, events, first_attempt_size
    )

    with pytest.raises(module.UatFailure) as failure:
        module.observe_agent_run(context, "offline_retry_error", events[0]["run_id"])

    assert failure.value.stage == "offline_retry_error"
    assert failure.value.check == "agent_run_error"
    assert len(opener.calls) == 1
    assert opener.calls[0][0] == "GET"
    assert [method for method, _ in replay_calls] == ["GET", "GET"]


@pytest.mark.parametrize(
    ("mutation", "expected_check"),
    (
        ("missing_abort", "attempt_order"),
        ("wrong_attempt_identity", "attempt_id"),
        ("wrong_reset_source", "reset_attempt_id"),
    ),
)
def test_uat_retry_trace_rejects_malformed_attempt_order_or_identity(
    mutation: str,
    expected_check: str,
) -> None:
    module = _load_two_turn_uat_contract(f"_two_turn_uat_malformed_{mutation}")
    events, _ = _offline_retry_trace(terminal_type="final")
    if mutation == "missing_abort":
        del events[4]
    elif mutation == "wrong_attempt_identity":
        events[7]["attempt_id"] = events[1]["attempt_id"]
    else:
        events[6]["reset_attempt_id"] = "target-intake-attempt:foreign:1"

    with pytest.raises(module.UatFailure) as failure:
        module.validate_event_trace(events, "offline_retry_malformed", events[0]["run_id"])

    assert failure.value.stage == "offline_retry_malformed"
    assert failure.value.check == expected_check


@pytest.mark.parametrize(
    ("missing_type", "expected_check"),
    (("visible_delta", "room_utterance"), ("usage", "usage")),
)
def test_uat_retry_success_requires_visible_and_usage_from_winning_attempt(
    missing_type: str,
    expected_check: str,
) -> None:
    module = _load_two_turn_uat_contract(
        f"_two_turn_uat_missing_winning_{missing_type}"
    )
    events, _ = _offline_retry_trace(terminal_type="final")
    winning_attempt_id = events[-1]["attempt_id"]
    events = [
        event
        for event in events
        if not (
            event["attempt_id"] == winning_attempt_id
            and event["type"] == missing_type
        )
    ]
    winning_sequence = 0
    for event in events:
        if event["attempt_id"] != winning_attempt_id:
            continue
        event["sequence"] = winning_sequence
        event["cursor"] = f"v2:{winning_attempt_id}:{winning_sequence}"
        winning_sequence += 1

    with pytest.raises(module.UatFailure) as failure:
        module.validate_event_trace(
            events,
            "offline_retry_missing_winning_output",
            events[0]["run_id"],
        )

    assert failure.value.stage == "offline_retry_missing_winning_output"
    assert failure.value.check == expected_check


@pytest.mark.parametrize("drift", ("usage", "final_identity"))
def test_uat_observer_rejects_exact_replay_usage_or_final_identity_drift(
    monkeypatch: pytest.MonkeyPatch,
    drift: str,
) -> None:
    module = _load_two_turn_uat_contract(f"_two_turn_uat_replay_drift_{drift}")
    live_events, first_attempt_size = _offline_retry_trace(terminal_type="final")
    replay_events = copy.deepcopy(live_events)
    if drift == "usage":
        winning_attempt_id = replay_events[-1]["attempt_id"]
        replay_usage = next(
            event
            for event in replay_events
            if event["attempt_id"] == winning_attempt_id and event["type"] == "usage"
        )
        replay_usage["token_usage"] = {
            "input_tokens": 2,
            "output_tokens": 3,
            "total_tokens": 5,
        }
    else:
        replay_events[-1]["response"] = {
            "final_result_ref": "urn:test:graph-result:replay-drift",
            "final_result_hash": "e" * 64,
        }
    context, opener, replay_calls = _offline_uat_context(
        module,
        monkeypatch,
        live_events,
        first_attempt_size,
        replay_events=replay_events,
    )

    with pytest.raises(module.UatFailure) as failure:
        module.observe_agent_run(
            context,
            "offline_retry_replay_drift",
            live_events[0]["run_id"],
        )

    assert failure.value.stage == "offline_retry_replay_drift"
    assert failure.value.check == "sse_replay_consistency"
    assert len(opener.calls) == 1
    assert opener.calls[0][0] == "GET"
    assert [method for method, _ in replay_calls] == ["GET", "GET"]
