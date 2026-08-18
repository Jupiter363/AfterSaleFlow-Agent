from __future__ import annotations

import asyncio
import hashlib
import json
import sys
from pathlib import Path

import pytest

from app.agents.evidence_clerk.v2_contracts import EvidenceRoomOpeningStreamV2
from app.agents.evidence_clerk.v2_policy import EvidenceV2PublicOutputPolicy
from app.agents.evidence_clerk.v2_workflow import EvidenceTurnWorkflowV2
from app.harness.evidence_room_context_v2 import assemble_evidence_room_context_v2
from app.harness.model_runner import (
    HarnessGeneration,
    HarnessStreamCompleted,
    HarnessStreamDelta,
)
from app.graph_runtime.evidence_turn_executor import _EvidencePreviewBridge
from app.graph_runtime.errors import GraphContractError
from app.graph_runtime.executor import TargetE2ESpecializedRoomProviderFactory
from app.graph_runtime.production_bindings import _build_target_e2e_evidence_workflow
from app.schemas import EvidenceTurnRequest
from app.streaming import AgentStreamObserver, bind_stream_observer

sys.path.insert(0, str(Path(__file__).parent))
from test_evidence_clerk_turn import _java_evidence_opening_command_payload  # noqa: E402


def _opening_frames(request: EvidenceTurnRequest) -> list[list[object]]:
    facts = [
        item["fact_id"]
        for item in assemble_evidence_room_context_v2(request).base.working_set.allowed_fact_targets
    ]
    headers = [
        {"frame_sequence": 1, "frame_type": "ROOM_WELCOME"},
        {
            "frame_sequence": 2,
            "frame_type": "OPENING_ORIENTATION",
            "focus_fact_ids": [facts[0]],
        },
        {
            "frame_sequence": 3,
            "frame_type": "EVIDENCE_REQUEST",
            "request_slot": "REQ_1",
            "target_fact_ids": [facts[1]],
            "requested_material_kind": "ORDER_RECORD",
            "priority": "HIGH",
            "reason": "核验订单记录",
        },
        {
            "frame_sequence": 4,
            "frame_type": "EVIDENCE_REQUEST",
            "request_slot": "REQ_2",
            "target_fact_ids": [facts[2]],
            "requested_material_kind": "LOGISTICS_RECORD",
            "priority": "HIGH",
            "reason": "核验物流记录",
        },
        {
            "frame_sequence": 5,
            "frame_type": "ROOM_READINESS",
            "core_fact_coverage": "NONE",
            "source_chain_coverage": "NONE",
            "time_integrity_coverage": "UNKNOWN",
            "human_review_status": "NONE",
            "overall_readiness": "NOT_READY",
            "remaining_core_fact_ids": facts,
        },
    ]
    return [[header, f"本案第{header['frame_sequence']}帧。"] for header in headers]


def _event(kind: str, sequence: int, **values: object) -> str:
    return json.dumps(
        {"kind": kind, "frame_sequence": sequence, **values},
        ensure_ascii=False,
        separators=(",", ":"),
    )


class _StreamingRunner:
    def __init__(self, stream: EvidenceRoomOpeningStreamV2) -> None:
        self.stream = stream
        self.calls = 0
        self.first_frame_sent = asyncio.Event()
        self.release = asyncio.Event()

    async def ainvoke_structured_stream(self, **_: object):
        self.calls += 1
        for frame in self.stream.frames:
            header = frame.header.model_dump(
                mode="json",
                exclude_none=True,
                exclude_defaults=True,
            )
            sequence = header["frame_sequence"]
            yield HarnessStreamDelta(
                kind="visible_delta",
                field="frames",
                delta=_event("frame_start", sequence, header=header),
            )
            assert frame.public_text is not None
            yield HarnessStreamDelta(
                kind="visible_delta",
                field="frames",
                delta=_event("public_text_delta", sequence, delta=frame.public_text),
            )
            if sequence == 1:
                self.first_frame_sent.set()
                await self.release.wait()
            yield HarnessStreamDelta(
                kind="visible_delta",
                field="frames",
                delta=_event("frame_end", sequence),
            )
        yield HarnessStreamCompleted(
            kind="completed",
            generation=HarnessGeneration(
                value=self.stream,
                model="v2-test-model",
                latency_ms=1,
                token_usage={"input": 1, "output": 1, "total": 2},
                context=None,
                messages=(),
            ),
        )


@pytest.mark.asyncio
async def test_v2_workflow_releases_first_frame_before_terminal_json_and_is_single_call() -> None:
    request = EvidenceTurnRequest.model_validate(_java_evidence_opening_command_payload())
    frames = _opening_frames(request)
    stream = EvidenceRoomOpeningStreamV2.model_validate({"frames": frames})
    runner = _StreamingRunner(stream)
    policy = EvidenceV2PublicOutputPolicy()
    events: list[object] = []
    observer = AgentStreamObserver(
        operation="evidence_turn",
        run_id="V2_WORKFLOW_STREAM_TEST",
        publish=events.append,
        public_output_policy=policy,
    )
    observer.begin_public_output("evidence_turn", "frames")

    with bind_stream_observer(observer):
        task = asyncio.create_task(EvidenceTurnWorkflowV2(model_runner=runner).arun(request))
        await asyncio.wait_for(runner.first_frame_sent.wait(), timeout=1)
        await asyncio.sleep(0)
        assert not task.done()
        assert any(
            getattr(event, "field", None) == "frame.1.public_text"
            for event in events
        )
        runner.release.set()
        result = await asyncio.wait_for(task, timeout=1)

    observer.finalize_public_output("evidence_turn", "frames", result.room_utterance)
    assert runner.calls == 1
    assert result.room_utterance == "\n\n".join(frame[1] for frame in frames)
    assert result.frame_manifest_sha256


@pytest.mark.asyncio
async def test_v2_workflow_rejects_out_of_scope_focus_fact_before_public_text() -> None:
    request = EvidenceTurnRequest.model_validate(_java_evidence_opening_command_payload())
    frames = _opening_frames(request)
    frames[1][0]["focus_fact_ids"] = ["FACT_NOT_IN_FROZEN_MATRIX"]
    stream = EvidenceRoomOpeningStreamV2.model_validate({"frames": frames})
    runner = _StreamingRunner(stream)
    policy = EvidenceV2PublicOutputPolicy()
    events: list[object] = []
    observer = AgentStreamObserver(
        operation="evidence_turn",
        run_id="V2_WORKFLOW_SCOPE_TEST",
        publish=events.append,
        public_output_policy=policy,
    )
    observer.begin_public_output("evidence_turn", "frames")

    with bind_stream_observer(observer):
        runner.release.set()
        task = asyncio.create_task(EvidenceTurnWorkflowV2(model_runner=runner).arun(request))
        with pytest.raises(Exception, match="FACT_ID_OUT_OF_SCOPE"):
            await asyncio.wait_for(task, timeout=1)
    assert not any(
        getattr(event, "field", None) == "frame.2.public_text" for event in events
    )


@pytest.mark.asyncio
async def test_v2_executor_bridge_preserves_provider_deltas_and_commits_one_frame() -> None:
    request = EvidenceTurnRequest.model_validate(_java_evidence_opening_command_payload())
    assembled = assemble_evidence_room_context_v2(request)
    policy = EvidenceV2PublicOutputPolicy()
    policy.configure(assembled)
    bridge = _EvidencePreviewBridge(
        provider_request=request,
        command_id="COMMAND_EVIDENCE_V3_TEST",
        attempt_id="ATTEMPT_EVIDENCE_V3_TEST",
        policy=policy,
    )
    observer = AgentStreamObserver(
        operation="evidence_turn",
        run_id="RUN_EVIDENCE_V3_TEST",
        publish=bridge.publish,
        public_output_policy=policy,
    )
    bridge.bind(observer)
    observer.begin_public_output("evidence_turn", "frames")
    header = {"frame_sequence": 1, "frame_type": "ROOM_WELCOME"}

    observer.visible_delta(
        "evidence_turn",
        "frames",
        _event("frame_start", 1, header=header),
    )
    observer.visible_delta(
        "evidence_turn",
        "frames",
        _event("public_text_delta", 1, delta="欢迎"),
    )
    observer.visible_delta(
        "evidence_turn",
        "frames",
        _event("public_text_delta", 1, delta="进入证据室"),
    )
    observer.visible_delta(
        "evidence_turn",
        "frames",
        _event("frame_end", 1),
    )

    events = list(bridge.pending)
    assert [event.event_type for event in events] == [
        "public_frame_start",
        "public_text_delta",
        "public_text_delta",
        "public_frame_committed",
    ]
    frame_id = events[0].payload.frame_id
    assert frame_id and all(event.payload.frame_id == frame_id for event in events)
    assert [events[1].payload.delta_index, events[2].payload.delta_index] == [0, 1]
    assert [events[1].payload.delta, events[2].payload.delta] == [
        "欢迎",
        "进入证据室",
    ]
    assert events[3].payload.durable_cursor == "v3:ATTEMPT_EVIDENCE_V3_TEST:FRAME:1"
    assert events[3].payload.public_text_sha256 == hashlib.sha256(
        "欢迎进入证据室".encode("utf-8")
    ).hexdigest()
    assert events[3].payload.public_text_chars == len("欢迎进入证据室")


def test_target_e2e_binding_is_exactly_the_v2_evidence_workflow() -> None:
    from types import SimpleNamespace

    from app.llm import LiteLlmProxyClient

    settings = SimpleNamespace(
        java_api_service_url="http://127.0.0.1:8080",
        java_service_secret="test-secret",
    )
    workflow = _build_target_e2e_evidence_workflow(
        settings=settings,
        structured_client=LiteLlmProxyClient(
            "http://127.0.0.1:1",
            "test-model",
            "test-key",
            1.0,
        ),
    )
    assert workflow.protocol_version == "evidence-turn-result.v2"
    assert callable(workflow.run)
    assert callable(workflow.arun)

    factory = TargetE2ESpecializedRoomProviderFactory(
        security_runtime=object(),
        room_exchange=object(),
    )

    class RetiredWorkflow:
        def run(self, request):
            del request

        async def arun(self, request):
            del request

    with pytest.raises(
        GraphContractError,
        match="TARGET_E2E_FORMAL_EVIDENCE_WORKFLOW_REQUIRED",
    ):
        factory.with_evidence_workflow(RetiredWorkflow())
