# 文件作用：把同步 Agent/LLM 工作流安全桥接为有序 NDJSON 流，负责可见字段白名单投影、背压、断连取消、终态与公开错误协议。

from __future__ import annotations

import asyncio
from collections.abc import Callable, Iterator, Mapping
from concurrent.futures import CancelledError as FutureCancelledError
from concurrent.futures import TimeoutError as FutureTimeoutError
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
from datetime import datetime, timezone
import json
import logging
import re
from threading import Event, Lock
from typing import Any, Literal, Protocol
from uuid import uuid4

from fastapi.encoders import jsonable_encoder
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field, model_validator


LOGGER = logging.getLogger(__name__)


STREAM_SCHEMA_VERSION = "agent_stream.v1"
AGENT_RUN_HEADER = "X-Agent-Run-Id"
SAFE_AGENT_RUN_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$")

# 流式响应跨越“后台同步生产者”和“FastAPI 异步消费者”两个线程/执行域；
# 队列、单事件、可见总输出和模型 JSON 文档都必须有上限，防止慢客户端造成无界内存积压。
STREAM_EVENT_QUEUE_MAXSIZE = 64
STREAM_QUEUE_WAIT_POLL_SECONDS = 0.05
STREAM_EVENT_MAX_DELTA_CHARS = 16 * 1024
STREAM_MAX_VISIBLE_OUTPUT_CHARS = 512 * 1024
STREAM_MAX_MODEL_DOCUMENT_CHARS = 2 * 1024 * 1024
STREAM_MAX_VISIBLE_ARRAY_ITEMS = 64
STREAM_MAX_VISIBLE_ARRAY_ITEM_BYTES = 16 * 1024
STREAM_MAX_VISIBLE_ARRAY_BYTES = 64 * 1024
V2_IDENTIFIER_PATTERN = r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"


class AgentStreamCancelled(RuntimeError):
    """客户端断连后在同步模型链路内传播的协作取消信号。"""


class AgentStreamLimitExceeded(RuntimeError):
    """供应商响应超过任一受治理流式边界时抛出。"""


class AgentStreamProjectionError(RuntimeError):
    """结构化增量不能唯一绑定到声明的公开 JSON 路径。"""


class StreamPublicOutputPolicy(Protocol):
    @property
    def visible_text(self) -> str: ...

    def allows_node(self, operation: str, node_name: str) -> bool: ...

    def begin(
        self,
        *,
        operation: str,
        node_name: str,
        field_name: str,
    ) -> tuple[str, ...]: ...

    def accept(
        self,
        *,
        operation: str,
        node_name: str,
        field_name: str,
        delta: str,
    ) -> tuple[str, ...]: ...

    def finalize(
        self,
        *,
        operation: str,
        node_name: str,
        field_name: str,
        final_text: str,
        allow_canonical_fallback: bool = False,
    ) -> tuple[str, ...]: ...


class _StrictStreamModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class StreamEventBase(_StrictStreamModel):
    schema_version: Literal["agent_stream.v1"] = STREAM_SCHEMA_VERSION
    run_id: str
    sequence: int = Field(ge=0)
    timestamp: str
    type: str


class StreamStartEvent(StreamEventBase):
    type: Literal["start"] = "start"
    operation: str


class StreamVisibleDeltaEvent(StreamEventBase):
    type: Literal["visible_delta"] = "visible_delta"
    node_name: str
    field: str
    delta: str


class StreamUsageEvent(StreamEventBase):
    type: Literal["usage"] = "usage"
    node_name: str
    model: str
    latency_ms: int = Field(ge=0)
    token_usage: dict[str, int]


class StreamFinalEvent(StreamEventBase):
    type: Literal["final"] = "final"
    operation: str
    response: Any


class StreamErrorEvent(StreamEventBase):
    type: Literal["error"] = "error"
    code: str
    message: str
    retryable: bool
    visible_output_emitted: bool
    node_name: str | None = None


AgentStreamEvent = (
    StreamStartEvent
    | StreamVisibleDeltaEvent
    | StreamUsageEvent
    | StreamFinalEvent
    | StreamErrorEvent
)


class StreamV2Usage(_StrictStreamModel):
    input_tokens: int = Field(ge=0)
    output_tokens: int = Field(ge=0)
    total_tokens: int = Field(ge=0)


class StreamV2Payload(_StrictStreamModel):
    node: str | None = Field(default=None, pattern=V2_IDENTIFIER_PATTERN)
    field: str | None = Field(default=None, pattern=V2_IDENTIFIER_PATTERN)
    delta: str | None = Field(default=None, min_length=1, max_length=4096)
    usage: StreamV2Usage | None = None
    reason_code: str | None = Field(default=None, pattern=V2_IDENTIFIER_PATTERN)
    reset_attempt_id: str | None = Field(default=None, pattern=V2_IDENTIFIER_PATTERN)
    final_result_ref: str | None = Field(
        default=None,
        max_length=1024,
        pattern=r"^(?:s3|minio|urn):",
    )
    final_result_hash: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    error_code: str | None = Field(default=None, pattern=V2_IDENTIFIER_PATTERN)
    retryable: bool | None = None


class AgentStreamV2Event(_StrictStreamModel):
    schema_version: Literal["agent-stream.v2"] = "agent-stream.v2"
    run_id: str = Field(pattern=V2_IDENTIFIER_PATTERN)
    attempt_id: str = Field(pattern=V2_IDENTIFIER_PATTERN)
    sequence_no: int = Field(ge=0)
    event_type: Literal[
        "attempt_started",
        "visible_delta",
        "usage",
        "attempt_aborted",
        "attempt_reset",
        "final",
        "error",
    ]
    audience: Literal["USER", "MERCHANT", "PLATFORM_REVIEWER", "SYSTEM"]
    occurred_at: datetime
    payload: StreamV2Payload

    @model_validator(mode="after")
    def validate_event_payload(self) -> AgentStreamV2Event:
        required = {
            "attempt_started": ("node",),
            "visible_delta": ("node", "field", "delta"),
            "usage": ("usage",),
            "attempt_aborted": ("reason_code",),
            "attempt_reset": ("reset_attempt_id", "reason_code"),
            "final": ("final_result_ref", "final_result_hash"),
            "error": ("error_code", "retryable"),
        }[self.event_type]
        missing = [name for name in required if getattr(self.payload, name) is None]
        if missing:
            raise ValueError(f"{self.event_type} is missing payload fields: {missing}")
        present = {
            name
            for name, value in self.payload.model_dump().items()
            if value is not None
        }
        unexpected = present.difference(required)
        if unexpected:
            raise ValueError(
                f"{self.event_type} contains incompatible payload fields: "
                f"{sorted(unexpected)}"
            )
        return self


def adapt_v1_event_to_v2(
    event: AgentStreamEvent,
    *,
    attempt_id: str,
    audience: Literal["USER", "MERCHANT", "PLATFORM_REVIEWER", "SYSTEM"],
    allowed_fields: frozenset[str],
    final_result_ref: str | None = None,
    final_result_hash: str | None = None,
) -> AgentStreamV2Event:
    """Project a validated V1 event into V2 without carrying raw model JSON."""

    common = {
        "run_id": event.run_id,
        "attempt_id": attempt_id,
        "sequence_no": event.sequence,
        "audience": audience,
        "occurred_at": datetime.fromisoformat(event.timestamp.replace("Z", "+00:00")),
    }
    if isinstance(event, StreamStartEvent):
        return AgentStreamV2Event(
            **common,
            event_type="attempt_started",
            payload=StreamV2Payload(node=event.operation),
        )
    if isinstance(event, StreamVisibleDeltaEvent):
        if event.field not in allowed_fields:
            raise ValueError("V1 adapter rejected a non-public field")
        return AgentStreamV2Event(
            **common,
            event_type="visible_delta",
            payload=StreamV2Payload(
                node=event.node_name,
                field=event.field,
                delta=event.delta,
            ),
        )
    if isinstance(event, StreamUsageEvent):
        usage = event.token_usage
        input_tokens = int(usage.get("input_tokens", usage.get("prompt_tokens", 0)))
        output_tokens = int(usage.get("output_tokens", usage.get("completion_tokens", 0)))
        total_tokens = int(usage.get("total_tokens", input_tokens + output_tokens))
        return AgentStreamV2Event(
            **common,
            event_type="usage",
            payload=StreamV2Payload(
                usage=StreamV2Usage(
                    input_tokens=input_tokens,
                    output_tokens=output_tokens,
                    total_tokens=total_tokens,
                )
            ),
        )
    if isinstance(event, StreamErrorEvent):
        return AgentStreamV2Event(
            **common,
            event_type="error",
            payload=StreamV2Payload(
                error_code=event.code,
                retryable=event.retryable,
            ),
        )
    if isinstance(event, StreamFinalEvent):
        if final_result_ref is None or final_result_hash is None:
            raise ValueError("V1 final requires a persisted result reference before V2 projection")
        return AgentStreamV2Event(
            **common,
            event_type="final",
            payload=StreamV2Payload(
                final_result_ref=final_result_ref,
                final_result_hash=final_result_hash,
            ),
        )
    raise TypeError(f"unsupported V1 stream event: {type(event).__name__}")


class V2DeltaCoalescer:
    """Coalesce adjacent public deltas for at most 75 ms or 4 KiB."""

    def __init__(self, *, window_ms: int = 75, max_chars: int = 4096) -> None:
        if not 50 <= window_ms <= 100 or not 1024 <= max_chars <= 4096:
            raise ValueError("coalescer bounds violate the stream v2 contract")
        self._window_ms = window_ms
        self._max_chars = max_chars
        self._pending: AgentStreamV2Event | None = None
        self._pending_since_ms = 0

    def push(self, event: AgentStreamV2Event, *, now_ms: int) -> list[AgentStreamV2Event]:
        if event.event_type != "visible_delta":
            emitted = self.flush()
            emitted.append(event)
            return emitted
        if self._pending is None:
            self._pending = event
            self._pending_since_ms = now_ms
            return []
        current = self._pending
        compatible = (
            current.run_id == event.run_id
            and current.attempt_id == event.attempt_id
            and current.audience == event.audience
            and current.payload.node == event.payload.node
            and current.payload.field == event.payload.field
        )
        combined = (current.payload.delta or "") + (event.payload.delta or "")
        if (
            compatible
            and now_ms - self._pending_since_ms <= self._window_ms
            and len(combined) <= self._max_chars
        ):
            self._pending = current.model_copy(
                update={"payload": current.payload.model_copy(update={"delta": combined})}
            )
            return []
        emitted = self.flush()
        self._pending = event
        self._pending_since_ms = now_ms
        return emitted

    def flush(self) -> list[AgentStreamV2Event]:
        if self._pending is None:
            return []
        pending = self._pending
        self._pending = None
        return [pending]


@dataclass(frozen=True)
class VisibleFieldSpec:
    """允许从模型 JSON 投影到公开流的一个属性。

    string_prefix 会逐字符投影字符串；json_value 只在整个 JSON 值闭合后投影一次，
    适合把右侧展板的独立结构分区按生成顺序安全送到前端；json_array_items
    则只在数组中一个对象完整闭合后逐项投影，不等待整个数组或模型文档结束。
    """

    property_name: str
    field: str
    value_mode: Literal["string_prefix", "json_value", "json_array_items"] = (
        "string_prefix"
    )
    requires_completed_root_property: str | None = None
    max_array_items: int = STREAM_MAX_VISIBLE_ARRAY_ITEMS
    max_array_item_bytes: int = STREAM_MAX_VISIBLE_ARRAY_ITEM_BYTES
    max_array_bytes: int = STREAM_MAX_VISIBLE_ARRAY_BYTES


@dataclass(frozen=True)
class _RootProjectionGate:
    """一个需要先完成前导字符串、再开放后续字段的公开流契约。"""

    leading_spec: VisibleFieldSpec


def _root_projection_gate_for(
    specs: tuple[VisibleFieldSpec, ...],
) -> _RootProjectionGate | None:
    """从显式字段标记构造公开发布门控，错误配置直接拒绝。"""

    dependent_specs = tuple(
        spec for spec in specs if spec.requires_completed_root_property is not None
    )
    if not dependent_specs:
        return None
    gate_names = {
        spec.requires_completed_root_property for spec in dependent_specs
    }
    if len(gate_names) != 1:
        raise ValueError("visible stream fields must use one completed-root gate")
    gate_name = gate_names.pop()
    assert gate_name is not None
    leading_specs = tuple(
        spec
        for spec in specs
        if (
            spec.property_name == gate_name
            and spec.field == gate_name
            and spec.requires_completed_root_property is None
        )
    )
    if len(leading_specs) != 1:
        raise ValueError("visible stream gate requires exactly one root string field")
    leading = leading_specs[0]
    if (
        leading.value_mode != "string_prefix"
        or "." in leading.field
    ):
        raise ValueError("visible stream gate must be a root string prefix")
    if specs[0] is not leading:
        raise ValueError("visible stream gate field must be first in publication order")
    if any(spec is not leading and spec not in dependent_specs for spec in specs):
        raise ValueError("visible stream gate cannot contain an ungated field")
    return _RootProjectionGate(leading_spec=leading)


def _intake_case_detail_field(
    property_name: str,
    field: str,
    value_mode: Literal["string_prefix", "json_value"] = "string_prefix",
) -> VisibleFieldSpec:
    """注册必须在接待话术完成后才公开的右侧卷宗字段。"""

    return VisibleFieldSpec(
        property_name,
        field,
        value_mode,
        requires_completed_root_property="room_utterance",
    )


# Target 图使用独立注册表：它规定公开发布顺序，而不要求模型 JSON 的根键顺序。
# 精确根路径的 room_utterance closing quote 到达后才会开放右侧 case_detail 投影。
TARGET_INTAKE_REPLY_FIRST_VISIBLE_FIELDS: tuple[VisibleFieldSpec, ...] = (
            # 元组顺序决定客户端看到的先后：先接待官话术，后右侧卷宗。
            VisibleFieldSpec(
                "room_utterance",
                "room_utterance",
            ),
            VisibleFieldSpec(
                "ordered_sections",
                "ordered_sections",
                "json_array_items",
                requires_completed_root_property="room_utterance",
                max_array_items=10,
            ),
            _intake_case_detail_field("title", "case_detail.case_story.title"),
            _intake_case_detail_field(
                "one_sentence_summary",
                "case_detail.case_story.one_sentence_summary",
            ),
            _intake_case_detail_field(
                "order_reference",
                "case_detail.references.order_reference",
            ),
            _intake_case_detail_field(
                "after_sales_reference",
                "case_detail.references.after_sales_reference",
            ),
            _intake_case_detail_field(
                "logistics_reference",
                "case_detail.references.logistics_reference",
            ),
            _intake_case_detail_field(
                "user_claim",
                "case_detail.party_positions.user_claim",
            ),
            _intake_case_detail_field(
                "merchant_claim",
                "case_detail.party_positions.merchant_claim",
            ),
            _intake_case_detail_field(
                "initiator_position",
                "case_detail.party_positions.initiator_position",
            ),
            _intake_case_detail_field(
                "platform_observation",
                "case_detail.party_positions.platform_observation",
            ),
            _intake_case_detail_field(
                "normalized_statement",
                "case_detail.claim_resolution.normalized_statement",
            ),
            _intake_case_detail_field(
                "request_reason",
                "case_detail.claim_resolution.request_reason",
            ),
            _intake_case_detail_field(
                "requested_items",
                "case_detail.claim_resolution.requested_items",
            ),
            _intake_case_detail_field(
                "position",
                "case_detail.respondent_attitude.position",
            ),
            _intake_case_detail_field(
                "core_conflict",
                "case_detail.dispute_core_state.core_conflict",
            ),
            _intake_case_detail_field(
                "core_issue",
                "case_detail.dispute_focus.core_issue",
            ),
            _intake_case_detail_field(
                "improvement_reason",
                "case_detail.intake_quality.improvement_reason",
            ),
            _intake_case_detail_field(
                "case_story",
                "case_detail.case_story",
                "json_value",
            ),
            _intake_case_detail_field(
                "references",
                "case_detail.references",
                "json_value",
            ),
            _intake_case_detail_field(
                "party_positions",
                "case_detail.party_positions",
                "json_value",
            ),
            _intake_case_detail_field(
                "claim_resolution",
                "case_detail.claim_resolution",
                "json_value",
            ),
            _intake_case_detail_field(
                "respondent_attitude",
                "case_detail.respondent_attitude",
                "json_value",
            ),
            _intake_case_detail_field(
                "dispute_core_state",
                "case_detail.dispute_core_state",
                "json_value",
            ),
            _intake_case_detail_field(
                "dispute_focus",
                "case_detail.dispute_focus",
                "json_value",
            ),
            _intake_case_detail_field(
                "risk_assessment",
                "case_detail.risk_assessment",
                "json_value",
            ),
            _intake_case_detail_field(
                "missing_information",
                "case_detail.missing_information",
                "json_value",
            ),
            _intake_case_detail_field(
                "intake_quality",
                "case_detail.intake_quality",
                "json_value",
            ),
)


# Legacy Intake 工作流仍只公开经确定性护栏归一化后的终态话术；不能把原始
# room_utterance 放进其注册表，否则会与终态追加语义冲突。这里必须是独立的
# 基线字段清单，不能从 Target tuple 过滤或派生，以免 Target 变更影响 Legacy。
_LEGACY_INTAKE_CASE_DETAIL_VISIBLE_FIELDS: tuple[VisibleFieldSpec, ...] = (
    VisibleFieldSpec("title", "case_detail.case_story.title"),
    VisibleFieldSpec(
        "one_sentence_summary",
        "case_detail.case_story.one_sentence_summary",
    ),
    VisibleFieldSpec("order_reference", "case_detail.references.order_reference"),
    VisibleFieldSpec(
        "after_sales_reference",
        "case_detail.references.after_sales_reference",
    ),
    VisibleFieldSpec(
        "logistics_reference",
        "case_detail.references.logistics_reference",
    ),
    VisibleFieldSpec("user_claim", "case_detail.party_positions.user_claim"),
    VisibleFieldSpec("merchant_claim", "case_detail.party_positions.merchant_claim"),
    VisibleFieldSpec(
        "initiator_position",
        "case_detail.party_positions.initiator_position",
    ),
    VisibleFieldSpec(
        "platform_observation",
        "case_detail.party_positions.platform_observation",
    ),
    VisibleFieldSpec(
        "normalized_statement",
        "case_detail.claim_resolution.normalized_statement",
    ),
    VisibleFieldSpec(
        "request_reason",
        "case_detail.claim_resolution.request_reason",
    ),
    VisibleFieldSpec(
        "requested_items",
        "case_detail.claim_resolution.requested_items",
    ),
    VisibleFieldSpec("position", "case_detail.respondent_attitude.position"),
    VisibleFieldSpec(
        "core_conflict",
        "case_detail.dispute_core_state.core_conflict",
    ),
    VisibleFieldSpec("core_issue", "case_detail.dispute_focus.core_issue"),
    VisibleFieldSpec(
        "improvement_reason",
        "case_detail.intake_quality.improvement_reason",
    ),
    VisibleFieldSpec("case_story", "case_detail.case_story", "json_value"),
    VisibleFieldSpec("references", "case_detail.references", "json_value"),
    VisibleFieldSpec(
        "party_positions",
        "case_detail.party_positions",
        "json_value",
    ),
    VisibleFieldSpec(
        "claim_resolution",
        "case_detail.claim_resolution",
        "json_value",
    ),
    VisibleFieldSpec(
        "respondent_attitude",
        "case_detail.respondent_attitude",
        "json_value",
    ),
    VisibleFieldSpec(
        "dispute_core_state",
        "case_detail.dispute_core_state",
        "json_value",
    ),
    VisibleFieldSpec("dispute_focus", "case_detail.dispute_focus", "json_value"),
    VisibleFieldSpec(
        "risk_assessment",
        "case_detail.risk_assessment",
        "json_value",
    ),
    VisibleFieldSpec(
        "missing_information",
        "case_detail.missing_information",
        "json_value",
    ),
    VisibleFieldSpec("intake_quality", "case_detail.intake_quality", "json_value"),
)


# 注册表默认拒绝：内部推理、矩阵 patch、工具参数、评议过程和私有 A2A 数据没有条目，因此不能流出。
VISIBLE_FIELD_REGISTRY: dict[str, dict[str, tuple[VisibleFieldSpec, ...]]] = {
    "intake_turn": {
        "intake_turn_case_detail": _LEGACY_INTAKE_CASE_DETAIL_VISIBLE_FIELDS,
    },
    "evidence_turn": {
        "evidence_turn": (
            VisibleFieldSpec(
                "public_observations",
                "public_observations",
                "json_array_items",
                max_array_items=12,
            ),
            VisibleFieldSpec("room_utterance", "room_utterance"),
        ),
    },
    "hearing_intake_questions": {
        "hearing_intake_questions": (
            VisibleFieldSpec("public_message", "public_message"),
        ),
    },
    "hearing_intake_synthesis": {
        "hearing_intake_synthesis": (
            VisibleFieldSpec("public_message", "public_message"),
        ),
    },
    "hearing_evidence_requests": {
        "hearing_evidence_requests": (
            VisibleFieldSpec("public_message", "public_message"),
        ),
    },
    "hearing_evidence_synthesis": {
        "hearing_evidence_synthesis": (
            VisibleFieldSpec("public_message", "public_message"),
        ),
    },
    "hearing_judge_v1": {
        "hearing_judge_v1": (
            VisibleFieldSpec("public_message", "public_message"),
        ),
    },
    "hearing_jury_review": {
        "hearing_jury_review": (
            VisibleFieldSpec("public_message", "public_message"),
        ),
    },
    "hearing_judge_v2": {
        "hearing_judge_v2": (
            VisibleFieldSpec("public_message", "public_message"),
        ),
    },
    "review_copilot": {
        "review_copilot": (
            VisibleFieldSpec("answer", "answer"),
        ),
    },
    # 外部导入虽不是聊天回复，title/description 仍是客户端可安全预览的公开进度字段。
    "external_import": {
        "external_import_simulator": (
            VisibleFieldSpec("title", "items.0.title"),
            VisibleFieldSpec("description", "items.0.description"),
        ),
    },
}


class IncrementalVisibleJsonProjector:
    """从不完整模型 JSON 中提取显式白名单字符串字段的只追加前缀。

    完整模型输出在通过 Pydantic 前仍是私有半成品；调用方只喂 delta.content，
    本类不会解析或输出 reasoning_content。
    """

    # 所属模块：Agent 流式协议 > JSON 可见投影 > 投影器状态初始化。
    # 具体功能：`__init__` 固定本节点允许的 VisibleFieldSpec，创建累计 JSON 缓冲，并为每个公开 field 记录已发送字符长度。
    # 上下游：上游是 LLM generate_stream 根据 operation/node 查出的白名单；下游是 `feed` 对连续 delta 只返回尚未发送的后缀。
    # 系统意义：没有 spec 就不会投影任何字段；已发送长度防止每次扫描累计缓冲时重复向前端发送旧文本。
    def __init__(self, specs: tuple[VisibleFieldSpec, ...]) -> None:
        for spec in specs:
            if spec.value_mode != "json_array_items":
                continue
            if (
                isinstance(spec.max_array_items, bool)
                or spec.max_array_items < 1
                or spec.max_array_items > STREAM_MAX_VISIBLE_ARRAY_ITEMS
                or isinstance(spec.max_array_item_bytes, bool)
                or spec.max_array_item_bytes < 1
                or spec.max_array_item_bytes > STREAM_MAX_VISIBLE_ARRAY_ITEM_BYTES
                or isinstance(spec.max_array_bytes, bool)
                or spec.max_array_bytes < 1
                or spec.max_array_bytes > STREAM_MAX_VISIBLE_ARRAY_BYTES
            ):
                raise AgentStreamProjectionError(
                    "visible stream array-item budget is invalid"
                )
        self._specs = specs
        self._root_projection_gate = _root_projection_gate_for(specs)
        self._buffer = ""
        self._emitted_lengths = {spec.field: 0 for spec in specs}
        self._emitted_json_fields: set[str] = set()
        self._emitted_array_item_counts = {spec.field: 0 for spec in specs}

    # 所属模块：Agent 流式协议 > JSON 可见投影 > 增量消费入口。
    # 具体功能：`feed` 在追加 content_delta 前检查完整模型文档上限，对每个精确属性扫描当前可解码字符串前缀，仅返回相对 emitted_length 新增长的 `(field, delta)`。
    # 上下游：上游是 LiteLLM SSE 的 answer content 增量；下游是 StructuredStreamDelta/AgentStreamObserver.visible_delta。
    # 系统意义：公开的是白名单值的只追加前缀，不是任意 JSON token；完整文档过大时在继续保留前立即失败。
    def feed(self, content_delta: str) -> list[tuple[str, str]]:
        """喂入一小段模型输出，返回新发现的可见字段增量。

        content_delta 是模型流式返回的 JSON 字符串片段，可能不完整。
        本函数用轻量 JSON 字符串扫描，只在目标字段的字符串值增长时返回新增部分。
        """

        if not content_delta:
            return []
        if len(content_delta) > STREAM_MAX_MODEL_DOCUMENT_CHARS - len(self._buffer):
            raise AgentStreamLimitExceeded(
                "model structured output exceeds the stream document limit"
            )
        # 字符串缓冲保存从流开始到当前的 JSON 文档，扫描器才能判断字段名、冒号和转义上下文。
        self._buffer += content_delta
        deltas: list[tuple[str, str]] = []
        specs = self._specs
        gated_projection = self._root_projection_gate is not None
        if gated_projection:
            assert self._root_projection_gate is not None
            if not _root_projection_gate_is_open(
                self._buffer,
                self._root_projection_gate,
            ):
                # 在前导话术的 closing quote 到来前，后续卷宗字段即使已被扫描到也
                # 不可见；这样客户端永远先得到左侧接待官文本，再收到右侧展板更新。
                specs = (self._root_projection_gate.leading_spec,)
        for spec in specs:
            if _json_path_has_duplicate_property(self._buffer, spec.field):
                raise AgentStreamProjectionError(
                    "visible stream JSON path is duplicated"
                )
            if spec.value_mode == "json_value":
                if spec.field in self._emitted_json_fields:
                    continue
                value = _find_complete_json_path_value(self._buffer, spec.field)
                if value is None:
                    continue
                self._emitted_json_fields.add(spec.field)
                deltas.append(
                    (
                        spec.field,
                        json.dumps(
                            value,
                            ensure_ascii=False,
                            separators=(",", ":"),
                        ),
                    )
                )
                continue
            if spec.value_mode == "json_array_items":
                values = _find_complete_json_array_object_items(
                    self._buffer,
                    spec.field,
                    max_items=spec.max_array_items,
                    max_item_bytes=spec.max_array_item_bytes,
                    max_array_bytes=spec.max_array_bytes,
                )
                emitted_count = self._emitted_array_item_counts[spec.field]
                if emitted_count > len(values):
                    raise AgentStreamProjectionError(
                        "visible stream array-item projection regressed"
                )
                deltas.extend((spec.field, value) for value in values[emitted_count:])
                self._emitted_array_item_counts[spec.field] = len(values)
                continue
            prefix = _find_json_string_path_prefix(self._buffer, spec.field)
            if prefix is None:
                continue
            emitted_length = self._emitted_lengths[spec.field]
            if len(prefix) <= emitted_length:
                continue
            delta = prefix[emitted_length:]
            self._emitted_lengths[spec.field] = len(prefix)
            if delta:
                deltas.append((spec.field, delta))
        return deltas


class AgentStreamObserver:
    """从同步模型线程向异步 NDJSON 响应发布强类型事件的线程安全观察器。"""

    # 所属模块：Agent 流式协议 > 事件观察器 > 运行状态初始化。
    # 具体功能：`__init__` 固定 operation/run_id/publish 回调，初始化独立的序号锁、可见输出状态锁、累计字符数和线程 Event 取消标志。
    # 上下游：上游是 `workflow_ndjson_response` 为单个 HTTP 请求创建 observer；下游是 start/delta/usage/final/error 事件生成。
    # 系统意义：锁保护跨线程共享计数与状态；每个请求独立 observer，事件序号和取消信号不会串到其他 Agent run。
    def __init__(
        self,
        *,
        operation: str,
        run_id: str,
        publish: Callable[[AgentStreamEvent], None],
        defer_visible_output: bool = False,
        public_output_policy: StreamPublicOutputPolicy | None = None,
    ) -> None:
        if defer_visible_output and public_output_policy is not None:
            raise ValueError("deferred output and a public output policy are exclusive")
        self.operation = operation
        self.run_id = run_id
        self._publish = publish
        self._defer_visible_output = defer_visible_output
        self._public_output_policy = public_output_policy
        self._sequence = 0
        self._sequence_lock = Lock()
        self._publish_lock = Lock()
        self._next_publish_sequence = 0
        self._pending_events: dict[int, AgentStreamEvent] = {}
        self._state_lock = Lock()
        self._visible_output_emitted = False
        self._visible_output_chars = 0
        self._deferred_usage: list[tuple[str, str, int, dict[str, int]]] = []
        self._cancelled = Event()

    # 所属模块：Agent 流式协议 > 事件观察器 > 可见输出状态读取。
    # 具体功能：`visible_output_emitted` 在 state lock 下返回是否至少成功登记过一段公开 delta，避免 worker 与响应线程的数据竞争。
    # 上下游：上游是错误映射和 error event 构造；下游决定服务故障是否可安全从头重试，以及客户端是否应丢弃已展示文本。
    # 系统意义：流出部分文本后自动重试可能造成重复/拼接两次回答，因此错误协议必须准确携带该状态。
    @property
    def visible_output_emitted(self) -> bool:
        with self._state_lock:
            return self._visible_output_emitted

    # 所属模块：Agent 流式协议 > 事件观察器 > 协作取消触发。
    # 具体功能：`cancel` 只设置线程安全 Event，不尝试从异步线程强杀正在执行的 Python/HTTP 代码。
    # 上下游：上游是响应 body 结束/断连或 schedule 检测事件循环关闭；下游是 `_emit`、visible_delta 及 LLM SSE 循环的 `raise_if_cancelled` 检查。
    # 系统意义：取消信号让后台线程在下一安全检查点退出，避免强制终止造成锁、HTTP 连接或业务提交处于不一致状态。
    def cancel(self) -> None:
        self._cancelled.set()

    # 所属模块：Agent 流式协议 > 事件观察器 > 取消检查点。
    # 具体功能：`raise_if_cancelled` 检查 Event，已取消就抛内部 AgentStreamCancelled；未取消无副作用返回。
    # 上下游：上游是每次发布事件及 LiteLLM 每个 SSE 事件；下游由 worker 专门吞掉取消异常，不发送 error/final。
    # 系统意义：客户端主动断连不是业务失败，不应制造误导的错误事件或继续消耗整个模型响应。
    def raise_if_cancelled(self) -> None:
        if self._cancelled.is_set():
            raise AgentStreamCancelled("agent stream consumer disconnected")

    # 所属模块：Agent 流式协议 > 事件观察器 > start 终端协议事件。
    # 具体功能：`start` 用公共 run_id/sequence/timestamp 与 operation 构造并发布每个流的首事件。
    # 上下游：上游是 worker 在调用任何业务代码前；下游是 NDJSON 客户端建立 run 上下文，之后才可能收到 delta/usage/final/error。
    # 系统意义：客户端无需从第一段模型文本猜操作类型；start 也证明 HTTP 流已经进入应用层执行。
    def start(self) -> None:
        self._emit(
            StreamStartEvent(
                **self._base_fields(),
                operation=self.operation,
            )
        )

    # 所属模块：Agent 流式协议 > 事件观察器 > 操作/节点可见字段查询。
    # 具体功能：`visible_fields_for` 先按本次 operation 再按 node_name 精确查注册表；任一级未知都返回空 tuple。
    # 上下游：上游是 LLM `generate` 发现当前 observer；下游是 IncrementalVisibleJsonProjector specs。
    # 系统意义：deny-by-default 防止新节点上线时因忘配流式策略而公开整个输出；必须显式审查后才能添加字段。
    def visible_fields_for(self, node_name: str) -> tuple[VisibleFieldSpec, ...]:
        if self._defer_visible_output:
            return ()
        if self.operation == "evidence_turn":
            policy = self._public_output_policy
            if policy is None or not policy.allows_node(self.operation, node_name):
                return ()
        return VISIBLE_FIELD_REGISTRY.get(self.operation, {}).get(node_name, ())

    def public_output_policy_enabled(self, node_name: str) -> bool:
        policy = self._public_output_policy
        return policy is not None and policy.allows_node(self.operation, node_name)

    # 所属模块：Agent 流式协议 > 事件观察器 > 可见增量限额与发布。
    # 具体功能：`visible_delta` 忽略空串、检查取消，在锁内累计总可见字符并限额，再按 16KiB 切块为多个 StreamVisibleDeltaEvent。
    # 上下游：上游是结构化 JSON projector 产出的字段后缀；下游是有序 NDJSON delta 行。
    # 系统意义：即使供应商一次给出超大 delta，单事件和总输出都受控；只有登记成功的白名单文本会把 visible_output_emitted 置 True。
    def visible_delta(self, node_name: str, field: str, delta: str) -> None:
        """发布一段可以给用户看的模型文本。"""

        if self._public_output_policy is not None:
            for public_delta in self._public_output_policy.accept(
                operation=self.operation,
                node_name=node_name,
                field_name=field,
                delta=delta,
            ):
                self._publish_visible_delta(node_name, field, public_delta)
            return
        if self._defer_visible_output:
            self.raise_if_cancelled()
            return
        self._publish_visible_delta(node_name, field, delta)

    def begin_public_output(self, node_name: str, field_name: str) -> None:
        """Publish an explicit policy-owned prefix before any provider invocation."""

        policy = self._public_output_policy
        if policy is None:
            raise RuntimeError("a public output policy is not enabled")
        for public_delta in policy.begin(
            operation=self.operation,
            node_name=node_name,
            field_name=field_name,
        ):
            self._publish_visible_delta(node_name, field_name, public_delta)

    def finalize_public_output(
        self,
        node_name: str,
        field_name: str,
        final_text: str,
        *,
        allow_canonical_fallback: bool = False,
    ) -> None:
        policy = self._public_output_policy
        if policy is None:
            raise RuntimeError("a public output policy is not enabled")
        for public_delta in policy.finalize(
            operation=self.operation,
            node_name=node_name,
            field_name=field_name,
            final_text=final_text,
            allow_canonical_fallback=allow_canonical_fallback,
        ):
            self._publish_visible_delta(node_name, field_name, public_delta)

    @property
    def finalized_public_output(self) -> str | None:
        policy = self._public_output_policy
        return policy.visible_text if policy is not None else None

    def finalized_visible_delta(self, node_name: str, field: str, delta: str) -> None:
        """Publish text extracted from a successfully validated workflow result."""

        if not self._defer_visible_output:
            raise RuntimeError("finalized visible output mode is not enabled")
        self._publish_visible_delta(node_name, field, delta)

    def _publish_visible_delta(self, node_name: str, field: str, delta: str) -> None:
        if not delta:
            return
        self.raise_if_cancelled()
        with self._state_lock:
            next_total = self._visible_output_chars + len(delta)
            if next_total > STREAM_MAX_VISIBLE_OUTPUT_CHARS:
                raise AgentStreamLimitExceeded(
                    "visible model output exceeds the stream output limit"
                )
            self._visible_output_chars = next_total
            self._visible_output_emitted = True
        for offset in range(0, len(delta), STREAM_EVENT_MAX_DELTA_CHARS):
            self._emit(
                StreamVisibleDeltaEvent(
                    **self._base_fields(),
                    node_name=node_name,
                    field=field,
                    delta=delta[offset : offset + STREAM_EVENT_MAX_DELTA_CHARS],
                )
            )

    # 所属模块：Agent 流式协议 > 事件观察器 > 模型用量事件。
    # 具体功能：`usage` 把负延迟夹到 0、将 Mapping 中用量统一转 int，并发布带 node/model 的 StreamUsageEvent。
    # 上下游：上游是同一次模型 stream 完成后的 StructuredGeneration；下游是客户端成本/性能展示与 run 审计。
    # 系统意义：usage 是观测事件而非最终业务结果；节点维度让多节点完整分析可分别核算，不暴露 Prompt 或隐藏推理正文。
    def usage(
        self,
        *,
        node_name: str,
        model: str,
        latency_ms: int,
        token_usage: Mapping[str, int],
    ) -> None:
        normalized_usage = {key: int(value) for key, value in token_usage.items()}
        normalized_latency_ms = max(0, latency_ms)
        if self._defer_visible_output or self._public_output_policy is not None:
            self.raise_if_cancelled()
            with self._state_lock:
                self._deferred_usage.append(
                    (node_name, model, normalized_latency_ms, normalized_usage)
                )
            return
        self._publish_usage(
            node_name=node_name,
            model=model,
            latency_ms=normalized_latency_ms,
            token_usage=normalized_usage,
        )

    def flush_deferred_usage(self) -> None:
        """Publish buffered audit usage after finalized visible output is known."""

        with self._state_lock:
            deferred_usage = self._deferred_usage
            self._deferred_usage = []
        for node_name, model, latency_ms, token_usage in deferred_usage:
            self._publish_usage(
                node_name=node_name,
                model=model,
                latency_ms=latency_ms,
                token_usage=token_usage,
            )

    def _publish_usage(
        self,
        *,
        node_name: str,
        model: str,
        latency_ms: int,
        token_usage: dict[str, int],
    ) -> None:
        self._emit(
            StreamUsageEvent(
                **self._base_fields(),
                node_name=node_name,
                model=model,
                latency_ms=latency_ms,
                token_usage=token_usage,
            )
        )

    # 所属模块：Agent 流式协议 > 事件观察器 > final 成功终态。
    # 具体功能：`final` 用 FastAPI jsonable_encoder 把 Pydantic/datetime 等响应转成 JSON 友好值，并发布包含完整已验收业务响应的 StreamFinalEvent。
    # 上下游：上游是同步 workflow `invoke()` 正常返回；下游是客户端用 final.response 替换/确认此前预览文本。
    # 系统意义：delta 只是即时预览，final 才是可持久化权威结果；同一流成功时只由 worker 调用一次。
    def final(self, response: Any) -> None:
        self._emit(
            StreamFinalEvent(
                **self._base_fields(),
                operation=self.operation,
                response=jsonable_encoder(response),
            )
        )

    # 所属模块：Agent 流式协议 > 事件观察器 > error 失败终态。
    # 具体功能：`error` 组合公开 code/message/retryable、可见输出状态和可选失败 node_name，发布不含异常堆栈的 StreamErrorEvent。
    # 上下游：上游是 worker 经 `_public_stream_error` 清洗后的异常；下游是客户端停止读取并按 retryable/visible_output_emitted 决定交互。
    # 系统意义：已打开 HTTP 流后不能再改成普通 4xx/5xx JSON，必须用协议内 error 结束且不能泄露内部异常细节。
    def error(
        self,
        *,
        code: str,
        message: str,
        retryable: bool,
        node_name: str | None = None,
    ) -> None:
        self._emit(
            StreamErrorEvent(
                **self._base_fields(),
                code=code,
                message=message,
                retryable=retryable,
                visible_output_emitted=self.visible_output_emitted,
                node_name=node_name,
            )
        )

    # 所属模块：Agent 流式协议 > 事件观察器 > 公共事件字段与原子序号。
    # 具体功能：`_base_fields` 在 sequence lock 下读取并递增序号，再生成 run_id 与 UTC ISO-8601 timestamp。
    # 上下游：上游是所有 start/delta/usage/final/error 构造器；下游是 StreamEventBase 字段。
    # 系统意义：多线程发布也不会出现重复 sequence；客户端可按序检测丢事件，而时间戳统一 UTC 避免时区歧义。
    def _base_fields(self) -> dict[str, Any]:
        with self._sequence_lock:
            sequence = self._sequence
            self._sequence += 1
        fields = {
            "run_id": self.run_id,
            "sequence": sequence,
            "timestamp": datetime.now(timezone.utc).isoformat(),
        }
        return fields

    # 所属模块：Agent 流式协议 > 事件观察器 > 单一发布出口。
    # 具体功能：`_emit` 在调用跨线程 publish 回调前统一执行取消检查，并按 sequence 暂存并发线程抢先到达的事件，只有连续前缀才能依次发布。
    # 上下游：上游是观察器所有事件方法；下游是 workflow_ndjson_response.publish/schedule 的有界队列。
    # 系统意义：取消后不再把事件塞入无人消费的队列，所有事件共享同一背压与断连语义。
    def _emit(self, event: AgentStreamEvent) -> None:
        self.raise_if_cancelled()
        with self._publish_lock:
            self.raise_if_cancelled()
            self._pending_events[event.sequence] = event
            while self._next_publish_sequence in self._pending_events:
                pending = self._pending_events.pop(self._next_publish_sequence)
                self._publish(pending)
                self._next_publish_sequence += 1


_ACTIVE_STREAM_OBSERVER: ContextVar[AgentStreamObserver | None] = ContextVar(
    "active_agent_stream_observer",
    default=None,
)


# 所属模块：Agent 流式协议 > 请求上下文 > 当前 observer 查询。
# 具体功能：`current_stream_observer` 从 ContextVar 读取当前调用链绑定的 observer；非流式请求或绑定范围外返回 None。
# 上下游：上游是 LLM 客户端 `generate`；下游决定同步调用走普通响应还是透明消费真实 provider stream。
# 系统意义：ContextVar 比进程全局变量更适合并发请求，避免一个用户的模型 delta 被发到另一个用户的 HTTP 流。
def current_stream_observer() -> AgentStreamObserver | None:
    """读取当前线程/协程上下文里的流式 observer。

    ContextVar 类似“上下文级全局变量”：同一个进程里不同请求互不污染。
    """

    return _ACTIVE_STREAM_OBSERVER.get()


# 所属模块：Agent 流式协议 > 请求上下文 > observer 生命周期绑定。
# 具体功能：`bind_stream_observer` 用 ContextVar.set 得到恢复 token，在 `yield` 覆盖的 with 范围内生效，并在 finally 中无论成功/异常都 reset 原值。
# 上下游：上游是 worker 包裹整个同步 invoke；下游是调用栈深处的 LLM generate 透明发现 observer。
# 系统意义：绑定严格随单次工作流调用结束，异常也不会把 observer 遗留给线程池后续复用任务。
@contextmanager
def bind_stream_observer(observer: AgentStreamObserver) -> Iterator[None]:
    """在 with 代码块内绑定 observer，退出时自动恢复。

    @contextmanager 是装饰器语法：把一个含 yield 的函数包装成可用于 with 的上下文管理器。
    """

    token = _ACTIVE_STREAM_OBSERVER.set(observer)
    try:
        yield
    finally:
        _ACTIVE_STREAM_OBSERVER.reset(token)


# 所属模块：Agent 流式协议 > 运行标识 > 客户端 ID 验收或生成。
# 具体功能：`resolve_agent_run_id` 仅保留符合 1..128 位安全字符正则的调用方 ID；缺失或非法时生成不可预测 `RUN_<uuid>`。
# 上下游：上游是 API 请求头/参数；下游是所有事件 run_id 与 X-Agent-Run-Id 响应头。
# 系统意义：稳定 run_id 便于 Java/UI/trace 关联，同时拒绝换行、空格和超长值，防止响应头注入与日志污染。
def resolve_agent_run_id(value: str | None) -> str:
    if value and SAFE_AGENT_RUN_ID.fullmatch(value):
        return value
    return f"RUN_{uuid4().hex}"


# 所属模块：Agent 流式协议 > NDJSON 桥接 > 同步工作流响应总入口。
# 具体功能：`workflow_ndjson_response` 创建有界 asyncio.Queue、完成哨兵和断连 Event，把同步 invoke 放到后台线程，将 observer 事件安全调度回当前 event loop，并返回禁缓存/禁代理缓冲的 StreamingResponse。
# 上下游：上游是各 FastAPI 流式端点提供 operation/run_id/同步业务 callable；下游是 start→delta/usage→final 或 error 的 NDJSON 行。
# 系统意义：保留现有同步 LangGraph/LLM 代码的同时提供实时反馈；有界队列产生背压，断连后协作取消，不能让快模型+慢客户端耗尽内存。
def workflow_ndjson_response(
    *,
    operation: str,
    run_id: str,
    invoke: Callable[[], Any],
    finalized_visible: Callable[[Any], str] | None = None,
    finalized_visible_node: str | None = None,
    finalized_visible_field: str | None = None,
    public_output_policy: StreamPublicOutputPolicy | None = None,
) -> StreamingResponse:
    """Run a synchronous governed workflow and expose its events as NDJSON."""

    if finalized_visible is None:
        if finalized_visible_node is not None or finalized_visible_field is not None:
            raise ValueError(
                "finalized visible node and field require an extraction callback"
            )
    elif not finalized_visible_node or not finalized_visible_field:
        raise ValueError(
            "finalized visible output requires non-empty node and field names"
        )
    if public_output_policy is not None and finalized_visible is None:
        raise ValueError("a public output policy requires finalized visible extraction")

    # FastAPI 的响应是异步的，但当前 workflow/LLM 调用是同步阻塞的。
    # 所以这里用“后台线程跑 workflow + asyncio.Queue 传事件”的桥接模式。
    event_queue: asyncio.Queue[AgentStreamEvent | object] = asyncio.Queue(
        maxsize=STREAM_EVENT_QUEUE_MAXSIZE
    )
    completed = object()
    disconnected = Event()

    loop = asyncio.get_running_loop()

    # 所属模块：Agent 流式协议 > NDJSON 桥接 > 后台线程到事件循环调度。
    # 具体功能：`schedule` 用 run_coroutine_threadsafe 提交 queue.put，并以 50ms 轮询 future；队列满时自然等待，期间持续检查断连/loop 关闭并取消挂起 put。
    # 上下游：上游是 observer.publish 及 worker 的完成哨兵；下游是异步 body 的 event_queue.get。
    # 系统意义：asyncio.Queue 不能从普通线程直接 await；该桥既保证线程安全又提供真实背压，不把事件复制到无界中间列表。
    def schedule(item: AgentStreamEvent | object) -> None:
        """从后台线程安全地把事件放进 asyncio.Queue。"""

        if disconnected.is_set() or loop.is_closed():
            raise AgentStreamCancelled("agent stream consumer disconnected")
        put = event_queue.put(item)
        try:
            future = asyncio.run_coroutine_threadsafe(put, loop)
        except RuntimeError:
            put.close()
            raise AgentStreamCancelled("agent stream event loop closed") from None
        while True:
            try:
                future.result(timeout=STREAM_QUEUE_WAIT_POLL_SECONDS)
                return
            except FutureTimeoutError:
                if disconnected.is_set() or loop.is_closed():
                    future.cancel()
                    raise AgentStreamCancelled(
                        "agent stream consumer disconnected"
                    ) from None
            except FutureCancelledError:
                raise AgentStreamCancelled("agent stream event loop closed") from None

    # 所属模块：Agent 流式协议 > NDJSON 桥接 > observer 发布适配器。
    # 具体功能：`publish` 将强类型 AgentStreamEvent 转交 schedule；窄签名满足 AgentStreamObserver 所需 Callable 合同。
    # 上下游：上游是 observer._emit；下游是跨线程有界队列调度。
    # 系统意义：观察器无需依赖 asyncio，传输细节集中在桥接函数内部，便于独立测试事件生成。
    def publish(event: AgentStreamEvent) -> None:
        schedule(event)

    observer = AgentStreamObserver(
        operation=operation,
        run_id=run_id,
        publish=publish,
        defer_visible_output=(
            finalized_visible is not None and public_output_policy is None
        ),
        public_output_policy=public_output_policy,
    )

    # 所属模块：Agent 流式协议 > NDJSON 桥接 > 同步生产者线程入口。
    # 具体功能：`worker` 先发 start，在 observer 绑定范围调用同步 workflow；成功且未断连发 final，已取消静默退出，其他异常映射为公开 error，最后尝试发送完成哨兵。
    # 上下游：上游是 body 通过 asyncio.to_thread 启动；下游是事件队列和同步 LangGraph/LLM 调用栈。
    # 系统意义：异常发生在 HTTP 流打开之后，只能编码成流内终态；客户端断连不再发 final/error，也不把内部 traceback 暴露给前端。
    def worker() -> None:
        """后台线程入口：执行 workflow，并把 start/delta/usage/final/error 发到队列。"""

        try:
            observer.start()
            if public_output_policy is not None:
                assert finalized_visible_node is not None
                assert finalized_visible_field is not None
                observer.begin_public_output(
                    finalized_visible_node,
                    finalized_visible_field,
                )
            with bind_stream_observer(observer):
                result = invoke()
            if not disconnected.is_set():
                if finalized_visible is not None:
                    visible_text = finalized_visible(result)
                    if not isinstance(visible_text, str) or not visible_text:
                        raise ValueError(
                            "finalized visible extraction must return non-empty text"
                        )
                    assert finalized_visible_node is not None
                    assert finalized_visible_field is not None
                    if public_output_policy is not None:
                        observer.finalize_public_output(
                            finalized_visible_node,
                            finalized_visible_field,
                            visible_text,
                        )
                    else:
                        observer.finalized_visible_delta(
                            finalized_visible_node,
                            finalized_visible_field,
                            visible_text,
                        )
                    observer.flush_deferred_usage()
                observer.final(result)
        except AgentStreamCancelled:
            pass
        except Exception as exception:  # HTTP 流已打开，只能编码成协议内 error 事件。
            if not disconnected.is_set():
                if public_output_policy is None:
                    observer.flush_deferred_usage()
                LOGGER.error(
                    "agent stream workflow failed: operation=%s run_id=%s "
                    "error_type=%s error=%s",
                    operation,
                    run_id,
                    type(exception).__name__,
                    exception,
                )
                code, message, retryable, node_name = _public_stream_error(
                    exception,
                    visible_output_emitted=observer.visible_output_emitted,
                )
                observer.error(
                    code=code,
                    message=message,
                    retryable=retryable,
                    node_name=node_name,
                )
        finally:
            if not disconnected.is_set():
                try:
                    schedule(completed)
                except AgentStreamCancelled:
                    pass

    # 所属模块：Agent 流式协议 > NDJSON 桥接 > FastAPI 异步响应体。
    # 具体功能：`body` 用 to_thread 启动 worker，逐项 await 队列；普通事件序列化为一行 JSON+换行，哨兵结束并 await worker；finally 标记断连、触发 observer.cancel 并取消 wrapper task。
    # 上下游：上游是 StreamingResponse 拉取异步生成器；下游是客户端逐行读取 NDJSON。
    # 系统意义：`async def + yield` 不阻塞 event loop；finally 在正常结束、客户端取消或序列化异常时都执行，确保后台生产者尽快停止。
    async def body():
        """StreamingResponse 消费的异步生成器。

        async def + yield 表示异步生成器：前端每读一行 NDJSON，这里就产出一行。
        """

        worker_task = asyncio.create_task(asyncio.to_thread(worker))
        try:
            while True:
                event = await event_queue.get()
                if event is completed:
                    break
                assert isinstance(event, StreamEventBase)
                yield event.model_dump_json(exclude_none=True) + "\n"
            await worker_task
        finally:
            disconnected.set()
            observer.cancel()
            if not worker_task.done():
                # 取消 to_thread 包装任务不能强杀供应商请求；observer Event 会让 worker 在下一个 SSE/发布检查点主动退出。
                worker_task.cancel()

    return StreamingResponse(
        body(),
        media_type="application/x-ndjson",
        headers={
            "Cache-Control": "no-cache, no-transform",
            "X-Accel-Buffering": "no",
            AGENT_RUN_HEADER: run_id,
        },
    )


# 所属模块：Agent 流式协议 > 错误合同 > 内部异常公开映射。
# 具体功能：`_public_stream_error` 将 Schema、服务不可用、流限额、权限及未知异常映射为固定 code/安全 message/retryable/node_name；服务不可用仅在尚未输出可见文本时可重试。
# 上下游：上游是 worker 捕获的任意非取消异常；下游是 observer.error 的 StreamErrorEvent。
# 系统意义：客户端不应看到密钥、URL、堆栈等异常细节；错误类型仍足够决定重试/人工处理，并避免部分输出后自动重放。
def _public_stream_error(
    exception: Exception,
    *,
    visible_output_emitted: bool,
) -> tuple[str, str, bool, str | None]:
    # 局部 import 避免循环依赖：llm.py 顶层本身会导入本文件的 observer。
    from app.llm import AgentOutputSchemaError, AgentServiceUnavailable
    from app.model_runtime.transports import ModelTransportOutputError

    if isinstance(exception, (AgentOutputSchemaError, ModelTransportOutputError)):
        cause = exception.__cause__
        if isinstance(exception, AgentOutputSchemaError):
            safe_code = exception.safe_code
            node_name = exception.node_name
        else:
            safe_code = exception.safe_code
            node_name = exception.node_name
            if isinstance(cause, AgentOutputSchemaError):
                safe_code = safe_code or cause.safe_code
                node_name = node_name or cause.node_name
        public_messages = {
            "AGENT_OUTPUT_SCHEMA_INVALID": "agent returned invalid structured output",
            "AGENT_OUTPUT_SCHEMA_REPAIR_EXHAUSTED": (
                "agent returned invalid structured output"
            ),
            "AGENT_PROVIDER_CONTRACT_INVALID": (
                "agent provider returned an invalid governed response"
            ),
        }
        if safe_code not in public_messages:
            safe_code = "AGENT_OUTPUT_SCHEMA_INVALID"
        return (
            safe_code,
            public_messages[safe_code],
            False,
            node_name,
        )
    if isinstance(exception, AgentServiceUnavailable):
        return (
            "AGENT_SERVICE_UNAVAILABLE",
            "agent model service unavailable",
            not visible_output_emitted,
            None,
        )
    if isinstance(exception, AgentStreamLimitExceeded):
        return (
            "AGENT_OUTPUT_LIMIT_EXCEEDED",
            "agent output exceeded the configured stream limit",
            False,
            None,
        )
    if isinstance(exception, AgentStreamProjectionError):
        return (
            "AGENT_OUTPUT_SCHEMA_INVALID",
            "agent returned invalid structured output",
            False,
            None,
        )
    if isinstance(exception, PermissionError):
        return "AGENT_PERMISSION_DENIED", str(exception), False, None
    return "INTERNAL_ERROR", "internal service error", False, None


def _root_projection_gate_is_open(
    document: str,
    gate: _RootProjectionGate,
) -> bool:
    """只在精确根路径的前导公开字符串闭合后开放后续字段。"""

    return _find_complete_json_string_path(
        document,
        gate.leading_spec.field,
    ) is not None


def _find_json_string_path_prefix(document: str, field: str) -> str | None:
    """在不完整 JSON 中按完整字段路径读取字符串前缀。"""

    start = _find_json_path_value_start(document, field)
    if start is None or start >= len(document) or document[start] != '"':
        return None
    value, _, _ = _decode_json_string(document, start)
    return value


def _find_complete_json_string_path(document: str, field: str) -> str | None:
    """只在精确路径的 JSON 字符串 closing quote 到达后返回完整文本。"""

    start = _find_json_path_value_start(document, field)
    if start is None or start >= len(document) or document[start] != '"':
        return None
    value, _, complete = _decode_json_string(document, start)
    return value if complete else None


def _find_complete_json_path_value(document: str, field: str) -> Any | None:
    """只在完整字段路径的 JSON 值闭合后返回其公开投影。"""

    start = _find_json_path_value_start(document, field)
    if start is None:
        return None
    try:
        value, _ = json.JSONDecoder().raw_decode(document, start)
    except ValueError:
        return None
    return value


def _find_complete_json_array_object_items(
    document: str,
    field: str,
    *,
    max_items: int,
    max_item_bytes: int,
    max_array_bytes: int,
) -> tuple[str, ...]:
    """Return canonical complete object items from one append-only JSON array path."""

    start = _find_json_path_value_start(document, field)
    if start is None:
        return ()
    if start >= len(document):
        return ()
    if document[start] != "[":
        raise AgentStreamProjectionError(
            "visible stream array-item field is not an array"
        )

    decoder = json.JSONDecoder()
    cursor = _skip_whitespace(document, start + 1)
    values: list[str] = []
    array_bytes = 0
    if cursor >= len(document):
        return ()
    if document[cursor] == "]":
        return ()
    while True:
        if len(values) >= max_items:
            raise AgentStreamLimitExceeded(
                "visible stream array-item count exceeds its limit"
            )
        try:
            value, end = decoder.raw_decode(document, cursor)
        except ValueError:
            # The current item is not complete yet and remains private until a
            # later provider chunk closes it.
            return tuple(values)
        if not isinstance(value, dict):
            raise AgentStreamProjectionError(
                "visible stream array item must be an object"
            )
        raw_item = document[cursor:end]
        try:
            raw_item_bytes = len(raw_item.encode("utf-8"))
        except UnicodeEncodeError as error:
            raise AgentStreamProjectionError(
                "visible stream array item has invalid Unicode"
            ) from error
        if raw_item_bytes > max_item_bytes:
            raise AgentStreamLimitExceeded(
                "visible stream array item exceeds its limit"
            )
        array_bytes += raw_item_bytes
        if array_bytes > max_array_bytes:
            raise AgentStreamLimitExceeded(
                "visible stream array exceeds its limit"
            )
        values.append(
            json.dumps(value, ensure_ascii=False, separators=(",", ":"))
        )
        cursor = _skip_whitespace(document, end)
        if cursor >= len(document):
            return tuple(values)
        if document[cursor] == "]":
            return tuple(values)
        if document[cursor] != ",":
            raise AgentStreamProjectionError(
                "visible stream array item is not comma-delimited"
            )
        cursor = _skip_whitespace(document, cursor + 1)
        if cursor >= len(document):
            return tuple(values)


def _find_json_path_value_start(document: str, field: str) -> int | None:
    """定位部分 JSON 文档中精确路径的值起点，避免同名私有字段冒充公开字段。"""

    target_path = tuple(field.split("."))
    status, value = _scan_json_path_value(
        document,
        _skip_whitespace(document, 0),
        (),
        target_path,
    )
    return value if status == "found" else None


def _json_path_has_duplicate_property(document: str, field: str) -> bool:
    """Reject duplicate object keys at every object segment of an exact path."""

    segments = tuple(field.split("."))
    if not segments or any(not segment for segment in segments):
        raise AgentStreamProjectionError("visible stream JSON path is invalid")
    root_start = _skip_whitespace(document, 0)
    for index, segment in enumerate(segments):
        parent_start = (
            root_start
            if index == 0
            else _find_json_path_value_start(document, ".".join(segments[:index]))
        )
        if (
            parent_start is None
            or parent_start >= len(document)
            or document[parent_start] != "{"
        ):
            continue
        starts = _find_json_object_property_value_starts(
            document,
            parent_start,
            segment,
        )
        if len(starts) > 1:
            return True
    return False


def _find_json_object_property_value_starts(
    document: str,
    object_start: int,
    property_name: str,
) -> tuple[int, ...]:
    """Return direct-property value starts visible in one partial JSON object."""

    cursor = _skip_whitespace(document, object_start + 1)
    starts: list[int] = []
    while cursor < len(document) and document[cursor] != "}":
        if document[cursor] != '"':
            return tuple(starts)
        key, cursor, complete = _decode_json_string(document, cursor)
        if not complete:
            return tuple(starts)
        cursor = _skip_whitespace(document, cursor)
        if cursor >= len(document) or document[cursor] != ":":
            return tuple(starts)
        value_start = _skip_whitespace(document, cursor + 1)
        if value_start >= len(document):
            return tuple(starts)
        if key == property_name:
            starts.append(value_start)
        status, value_end = _scan_json_path_value(
            document,
            value_start,
            (),
            None,
        )
        if status != "complete":
            return tuple(starts)
        cursor = _skip_whitespace(document, value_end)
        if cursor >= len(document) or document[cursor] == "}":
            return tuple(starts)
        if document[cursor] != ",":
            return tuple(starts)
        cursor = _skip_whitespace(document, cursor + 1)
    return tuple(starts)


def _scan_json_path_value(
    document: str,
    index: int,
    path: tuple[str, ...],
    target_path: tuple[str, ...] | None,
) -> tuple[Literal["found", "complete", "incomplete"], int]:
    """在一个 JSON 值中寻找精确路径；未闭合前缀只返回 incomplete。"""

    index = _skip_whitespace(document, index)
    if index >= len(document):
        return "incomplete", index
    if target_path is not None and path == target_path:
        return "found", index

    token = document[index]
    if token == "{":
        return _scan_json_object_path(document, index, path, target_path)
    if token == "[":
        return _scan_json_array_path(document, index, path, target_path)
    if token == '"':
        _, end, complete = _decode_json_string(document, index)
        return ("complete", end) if complete else ("incomplete", end)

    cursor = index
    while cursor < len(document) and document[cursor] not in ",]} \t\r\n":
        cursor += 1
    if cursor == index or cursor == len(document):
        return "incomplete", cursor
    return "complete", cursor


def _scan_json_object_path(
    document: str,
    index: int,
    path: tuple[str, ...],
    target_path: tuple[str, ...] | None,
) -> tuple[Literal["found", "complete", "incomplete"], int]:
    cursor = _skip_whitespace(document, index + 1)
    if cursor >= len(document):
        return "incomplete", cursor
    if document[cursor] == "}":
        return "complete", cursor + 1

    while True:
        if cursor >= len(document) or document[cursor] != '"':
            return "incomplete", cursor
        key, cursor, complete = _decode_json_string(document, cursor)
        if not complete:
            return "incomplete", cursor
        cursor = _skip_whitespace(document, cursor)
        if cursor >= len(document) or document[cursor] != ":":
            return "incomplete", cursor
        status, value_end = _scan_json_path_value(
            document,
            cursor + 1,
            path + (key,),
            target_path,
        )
        if status != "complete":
            return status, value_end
        cursor = _skip_whitespace(document, value_end)
        if cursor >= len(document):
            return "incomplete", cursor
        if document[cursor] == "}":
            return "complete", cursor + 1
        if document[cursor] != ",":
            return "incomplete", cursor
        cursor = _skip_whitespace(document, cursor + 1)
        if cursor >= len(document):
            return "incomplete", cursor


def _scan_json_array_path(
    document: str,
    index: int,
    path: tuple[str, ...],
    target_path: tuple[str, ...] | None,
) -> tuple[Literal["found", "complete", "incomplete"], int]:
    cursor = _skip_whitespace(document, index + 1)
    if cursor >= len(document):
        return "incomplete", cursor
    if document[cursor] == "]":
        return "complete", cursor + 1

    item_index = 0
    while True:
        status, value_end = _scan_json_path_value(
            document,
            cursor,
            path + (str(item_index),),
            target_path,
        )
        if status != "complete":
            return status, value_end
        cursor = _skip_whitespace(document, value_end)
        if cursor >= len(document):
            return "incomplete", cursor
        if document[cursor] == "]":
            return "complete", cursor + 1
        if document[cursor] != ",":
            return "incomplete", cursor
        cursor = _skip_whitespace(document, cursor + 1)
        if cursor >= len(document):
            return "incomplete", cursor
        item_index += 1


# 所属模块：Agent 流式协议 > JSON 可见投影 > 目标属性前缀扫描。
# 具体功能：`_find_json_string_property_prefix` 从文档头扫描每个 JSON 字符串，只有解码后紧跟冒号且 key 精确等于 property_name、value 又以引号开始时，才返回当前可解码值前缀。
# 上下游：上游是 projector 累计的不完整 JSON；下游是 emitted-length 差量计算。
# 系统意义：它不是通用 JSON parser，只允许 string 属性公开；对象、数组、数字及不完整 key 不投影，减少半结构化内部数据泄露。
def _find_json_string_property_prefix(
    document: str,
    property_name: str,
) -> str | None:
    """在不完整 JSON 文档中寻找某个字符串属性的当前可解码前缀。

    这个函数不是完整 JSON parser，只服务于流式展示：
    例如模型正在输出 {"room_utterance":"你好..."}，即使右括号还没来，
    也可以把 room_utterance 的已完成字符安全地投影给前端。
    """

    index = 0
    length = len(document)
    while index < length:
        if document[index] != '"':
            index += 1
            continue
        key, end, complete = _decode_json_string(document, index)
        if not complete:
            return None
        cursor = _skip_whitespace(document, end)
        if cursor >= length or document[cursor] != ":":
            index = end
            continue
        cursor = _skip_whitespace(document, cursor + 1)
        if key == property_name:
            if cursor >= length or document[cursor] != '"':
                return None
            value, _, _ = _decode_json_string(document, cursor)
            return value
        index = end
    return None


def _find_complete_json_property_value(
    document: str,
    property_name: str,
) -> Any | None:
    """返回已完整闭合的白名单属性值；不完整值不会提前公开。"""

    decoder = json.JSONDecoder()
    index = 0
    length = len(document)
    while index < length:
        if document[index] != '"':
            index += 1
            continue
        key, end, complete = _decode_json_string(document, index)
        if not complete:
            return None
        cursor = _skip_whitespace(document, end)
        if cursor >= length or document[cursor] != ":":
            index = end
            continue
        cursor = _skip_whitespace(document, cursor + 1)
        if key != property_name:
            index = end
            continue
        if cursor >= length:
            return None
        try:
            value, _ = decoder.raw_decode(document, cursor)
        except ValueError:
            return None
        return value
    return None


# 所属模块：Agent 流式协议 > JSON 可见投影 > JSON 空白游标推进。
# 具体功能：`_skip_whitespace` 从给定 index 跳过 JSON 允许的空格、制表、回车和换行，返回第一个非空白位置或字符串末尾。
# 上下游：上游是属性扫描器完成 key/冒号读取后的 cursor；下游决定下一 token 是否为冒号或字符串引号。
# 系统意义：只移动索引、不分配子串，使频繁增量扫描保持简单；不会跳过注释等非 JSON 内容。
def _skip_whitespace(value: str, index: int) -> int:
    while index < len(value) and value[index] in " \t\r\n":
        index += 1
    return index


# 所属模块：Agent 流式协议 > JSON 可见投影 > 不完整 JSON 字符串解码器。
# 具体功能：`_decode_json_string` 从开引号后逐字符解码普通字符、简单转义、`\uXXXX` 和 UTF-16 surrogate pair，返回 `(已解码前缀, 下一索引, 是否闭合)`；非法/未收齐转义标记 incomplete。
# 上下游：上游是属性扫描器对 key 与 value 的读取；下游是当前可安全公开的字符串前缀。
# 系统意义：不能直接删除反斜杠或用不完整 json.loads；正确处理转义和代理对可避免向前端发错字符、重复字符或半个 Unicode 字符。
def _decode_json_string(value: str, quote_index: int) -> tuple[str, int, bool]:
    output: list[str] = []
    index = quote_index + 1
    while index < len(value):
        character = value[index]
        if character == '"':
            return "".join(output), index + 1, True
        if character != "\\":
            if ord(character) < 0x20:
                return "".join(output), index, False
            output.append(character)
            index += 1
            continue
        if index + 1 >= len(value):
            return "".join(output), len(value), False
        escape = value[index + 1]
        simple = {
            '"': '"',
            "\\": "\\",
            "/": "/",
            "b": "\b",
            "f": "\f",
            "n": "\n",
            "r": "\r",
            "t": "\t",
        }
        if escape in simple:
            output.append(simple[escape])
            index += 2
            continue
        if escape != "u" or index + 6 > len(value):
            return "".join(output), len(value), False
        digits = value[index + 2 : index + 6]
        try:
            codepoint = int(digits, 16)
        except ValueError:
            return "".join(output), index, False
        if 0xD800 <= codepoint <= 0xDBFF:
            if index + 12 > len(value) or value[index + 6 : index + 8] != "\\u":
                return "".join(output), len(value), False
            low_digits = value[index + 8 : index + 12]
            try:
                low = int(low_digits, 16)
            except ValueError:
                return "".join(output), index, False
            if not 0xDC00 <= low <= 0xDFFF:
                return "".join(output), index, False
            combined = 0x10000 + ((codepoint - 0xD800) << 10) + (low - 0xDC00)
            output.append(chr(combined))
            index += 12
            continue
        if 0xDC00 <= codepoint <= 0xDFFF:
            return "".join(output), index, False
        output.append(chr(codepoint))
        index += 6
    return "".join(output), len(value), False


# 所属模块：Agent 流式协议 > 序列化 > 非 FastAPI 公共编码器。
# 具体功能：`encode_stream_event` 将强类型事件转 JSON 模式 dict，删除 None，保留中文并使用紧凑分隔符，最后追加 NDJSON 必需换行。
# 上下游：上游是协议合同测试或其他非 StreamingResponse 消费者；下游是一条可直接写入流/文件的 NDJSON 文本。
# 系统意义：与 body 的一事件一行语义一致，调用者不能绕过 Pydantic 事件模型塞入任意未定义字段。
def encode_stream_event(event: AgentStreamEvent) -> str:
    """供协议测试和非 FastAPI 消费者使用的小型公开编码器。"""

    return json.dumps(
        event.model_dump(mode="json", exclude_none=True),
        ensure_ascii=False,
        separators=(",", ":"),
    ) + "\n"
