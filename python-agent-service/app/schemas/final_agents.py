# 文件作用：Python Agent 数据契约文件，使用 Pydantic 定义请求、响应和模型输出结构。

"""Strict contracts for the six final AI Native agent roles."""

from __future__ import annotations

from enum import StrEnum
from typing import Annotated, Any, Literal

from pydantic import Field, model_validator

from app.harness.invocation_context import AgentInvocationContext
from app.schemas.models import (
    Confidence,
    EvidenceItem,
    Identifier,
    LongText,
    PartyClaim,
    ShortText,
    StrictModel,
)


# 这个文件定义“最终 Agent API”的严格输入/输出契约。
# 对 Python 新手：
# - class Xxx(StrictModel) 表示定义一个 Pydantic 数据模型；
# - 字段后面的冒号是类型标注，例如 raw_text: LongText；
# - Literal["USER", "MERCHANT"] 表示这个字段只能取这两个字符串之一；
# - Annotated[list[Identifier], Field(max_length=30)] 表示 list 之外还附带 Pydantic 校验规则；
# - default_factory=list 表示每次创建对象时生成一个新的空列表，避免多个实例共享同一个列表。
class DisputeIntakeRequest(StrictModel):
    """争议接待 Agent 的初始分析请求。"""

    submission_id: Identifier
    initiator_role: Literal["USER", "MERCHANT"]
    raw_text: LongText
    order_reference: Identifier | None = None
    after_sales_reference: Identifier | None = None
    logistics_reference: Identifier | None = None
    attachments: Annotated[list[Identifier], Field(max_length=30)] = Field(
        default_factory=list
    )
    channel: Identifier


class IntakeClaim(StrictModel):
    """从自然语言中抽取出的单条当事人主张。"""

    party: Literal["USER", "MERCHANT"]
    claim_text: LongText
    source_ref: Identifier


class DisputeIntakeResult(StrictModel):
    """接待 Agent 的结构化输出。

    liability_determined 和 remedy_promised 被固定为 False，
    表示接待阶段不能判断责任，也不能承诺赔付方案。
    """

    admissible: bool
    initiator_role: Literal["USER", "MERCHANT"]
    order_reference: Identifier | None = None
    after_sales_reference: Identifier | None = None
    logistics_reference: Identifier | None = None
    party_claims: Annotated[list[IntakeClaim], Field(min_length=1, max_length=20)]
    requested_outcome: Literal[
        "REFUND",
        "REPLACEMENT",
        "RETURN",
        "REJECT_REFUND",
        "OTHER",
        "UNKNOWN",
    ]
    initial_risk_signals: list[Identifier] = Field(default_factory=list)
    admission_recommendation: Literal[
        "ACCEPTED",
        "NEED_MORE_INFO",
        "NOT_ADMISSIBLE",
    ]
    dispute_type: Identifier | None = None
    missing_initial_fields: list[Identifier] = Field(default_factory=list)
    confidence: Annotated[float, Field(ge=0, le=1)]
    next_step: Literal[
        "BUILD_DOSSIER",
        "REQUEST_MORE_INFO",
        "TRANSFER",
    ]
    room_utterance: LongText
    liability_determined: Literal[False] = False
    remedy_promised: Literal[False] = False

    # 所属模块：Python Agent 数据契约 > final_agents；函数角色：只读派生属性。
    # 具体功能：`is_potential_dispute` 判断本阶段状态是否满足当前业务分支条件。
    # 上下游：上游为 Java 请求、LangGraph 状态、LLM JSON；下游为 Pydantic 强校验领域对象。
    # 系统意义：该函数在系统中的业务边界是：拒绝多余字段、资源越界、身份错配和非法枚举。
    @property
    def is_potential_dispute(self) -> bool:
        """Compatibility view for callers migrating to ``admissible``."""

        return self.admissible

    # 所属模块：Python Agent 数据契约 > final_agents；函数角色：只读派生属性。
    # 具体功能：`admissibility_recommendation` 围绕本阶段状态计算该函数独立负责的业务派生值。
    # 上下游：上游为 Java 请求、LangGraph 状态、LLM JSON；下游为 Pydantic 强校验领域对象。
    # 系统意义：该函数在系统中的业务边界是：拒绝多余字段、资源越界、身份错配和非法枚举。
    @property
    def admissibility_recommendation(self) -> str:
        return self.admission_recommendation

    # 所属模块：Python Agent 数据契约 > final_agents；函数角色：只读派生属性。
    # 具体功能：`initiator` 围绕本阶段状态计算该函数独立负责的业务派生值。
    # 上下游：上游为 Java 请求、LangGraph 状态、LLM JSON；下游为 Pydantic 强校验领域对象。
    # 系统意义：该函数在系统中的业务边界是：拒绝多余字段、资源越界、身份错配和非法枚举。
    @property
    def initiator(self) -> str:
        return self.initiator_role

    # 所属模块：Python Agent 数据契约 > final_agents；函数角色：只读派生属性。
    # 具体功能：`claims` 围绕当事人主张计算该函数独立负责的业务派生值。
    # 上下游：上游为 Java 请求、LangGraph 状态、LLM JSON；下游为 Pydantic 强校验领域对象。
    # 系统意义：该函数在系统中的业务边界是：拒绝多余字段、资源越界、身份错配和非法枚举。
    @property
    def claims(self) -> list[IntakeClaim]:
        return self.party_claims

    # 所属模块：Python Agent 数据契约 > final_agents；函数角色：只读派生属性。
    # 具体功能：`requested_remedy` 围绕本阶段状态计算该函数独立负责的业务派生值。
    # 上下游：上游为 Java 请求、LangGraph 状态、LLM JSON；下游为 Pydantic 强校验领域对象。
    # 系统意义：该函数在系统中的业务边界是：拒绝多余字段、资源越界、身份错配和非法枚举。
    @property
    def requested_remedy(self) -> str:
        return self.requested_outcome

    # 所属模块：Python Agent 数据契约 > final_agents；函数角色：只读派生属性。
    # 具体功能：`risk_signals` 围绕本阶段状态计算该函数独立负责的业务派生值。
    # 上下游：上游为 Java 请求、LangGraph 状态、LLM JSON；下游为 Pydantic 强校验领域对象。
    # 系统意义：该函数在系统中的业务边界是：拒绝多余字段、资源越界、身份错配和非法枚举。
    @property
    def risk_signals(self) -> list[Identifier]:
        return self.initial_risk_signals


RiskLevelLiteral = Literal["LOW", "MEDIUM", "HIGH", "CRITICAL"]
# RawTransportText / RawReference 是复用型字段约束，避免每个请求重复写长度限制。
RawTransportText = Annotated[
    str,
    Field(min_length=1, max_length=2_000_000),
]
RawReference = Annotated[
    str,
    Field(min_length=1, max_length=128),
]


class SimulatedExternalImportRequest(StrictModel):
    """本地演示/测试用的模拟外部导入请求。"""

    count: Annotated[int, Field(ge=1, le=1)] = 1
    scenario: ShortText = "履约争议订单"
    risk_level_hint: RiskLevelLiteral | None = "MEDIUM"
    initiator_role_hint: Literal["USER", "MERCHANT"]
    current_actor_id: Identifier
    counterparty_actor_id: Identifier
    simulation_batch_id: Identifier | None = None

    # 所属模块：Python Agent 数据契约 > final_agents；函数角色：类/闭包内部方法。
    # 具体功能：`validate_fixed_demo_parties` Pydantic 模型级校验；关键协作调用：`model_validator`、`ValueError`。
    # 上下游：上游为 Java 请求、LangGraph 状态、LLM JSON；下游为 协作调用 `model_validator`、`ValueError`。
    # 系统意义：这是信任边界：拒绝多余字段、资源越界、身份错配和非法枚举。
    @model_validator(mode="after")
    def validate_fixed_demo_parties(self) -> "SimulatedExternalImportRequest":
        """Pydantic 模型级校验。

        @model_validator(mode="after") 表示字段基础校验完成后，再检查多个字段之间的关系。
        这里强制本地演示账号固定成 user-local / merchant-local。
        """

        expected_current = (
            "user-local"
            if self.initiator_role_hint == "USER"
            else "merchant-local"
        )
        expected_counterparty = (
            "merchant-local"
            if self.initiator_role_hint == "USER"
            else "user-local"
        )
        if self.current_actor_id != expected_current:
            raise ValueError(f"current_actor_id must be {expected_current}")
        if self.counterparty_actor_id != expected_counterparty:
            raise ValueError(
                f"counterparty_actor_id must be {expected_counterparty}"
            )
        return self


class SimulatedExternalDispute(StrictModel):
    source_system: Identifier = "LLM_SIMULATED_OMS"
    external_case_reference: Identifier
    order_reference: Identifier
    after_sales_reference: Identifier | None = None
    logistics_reference: Identifier | None = None
    user_id: Identifier
    merchant_id: Identifier
    initiator_role: Literal["USER", "MERCHANT"]
    dispute_type: Identifier
    title: ShortText
    description: LongText
    risk_level: RiskLevelLiteral


class SimulatedExternalImportResult(StrictModel):
    items: Annotated[
        list[SimulatedExternalDispute],
        Field(min_length=1, max_length=1),
    ]


class IntakeClaimResolutionSeed(StrictModel):
    initiator_role: Literal["USER", "MERCHANT"] | None = None
    requested_resolution: Identifier | None = None
    requested_amount: float | None = None
    requested_items: ShortText | None = None
    request_reason: LongText | None = None
    original_statement: LongText | None = None


class IntakeRespondentAttitudeSeed(StrictModel):
    """Optional extraction hint from the initiator's unilateral statement.

    The intake workflow ignores seeds carrying formal/external provenance. A
    non-empty attitude is only eligible when ``source`` is exactly
    ``发起方单方陈述（主观）``.
    """

    respondent_role: Literal["USER", "MERCHANT"] | None = None
    attitude: Identifier | None = None
    position: LongText | None = None
    source: ShortText | None = Field(
        default=None,
        description="仅允许表示发起方单方陈述（主观）的提取来源。",
    )
    confidence: Confidence | None = Field(
        default=None,
        description="仅表示主观态度提取的明确度，不表示真实性。",
    )


class IntakeLobbySeed(StrictModel):
    order_reference: Identifier | None = None
    after_sales_reference: Identifier | None = None
    logistics_reference: Identifier | None = None
    initiator_role: Literal["USER", "MERCHANT", "CUSTOMER_SERVICE", "SYSTEM"] = (
        "USER"
    )
    raw_text: LongText
    requested_outcome_hint: Identifier | None = None
    claim_resolution_seed: IntakeClaimResolutionSeed | None = None
    respondent_attitude_seed: IntakeRespondentAttitudeSeed | None = None


class IntakeTurnMessage(StrictModel):
    message_id: Identifier
    role: Literal["USER", "MERCHANT", "CUSTOMER_SERVICE", "SYSTEM", "AGENT"]
    text: LongText


class IntakeDialogueMessage(StrictModel):
    message_id: Identifier
    sequence_no: Annotated[int, Field(ge=1)]
    role: Literal["USER", "MERCHANT", "CUSTOMER_SERVICE", "SYSTEM", "AGENT"]
    source: Literal[
        "EXTERNAL_IMPORT", "FORM_SUBMISSION", "ROOM_MESSAGE", "AGENT_RESPONSE"
    ]
    text: LongText


class IntakeInitialCaseFacts(StrictModel):
    form_source: Literal["EXTERNAL_IMPORT", "FORM_SUBMISSION"] | None = None
    form_description: LongText | None = None
    order_reference: Identifier | None = None
    after_sales_reference: Identifier | None = None
    logistics_reference: Identifier | None = None
    initiator_role: Literal["USER", "MERCHANT", "CUSTOMER_SERVICE", "SYSTEM"] = (
        "USER"
    )
    requested_outcome_hint: Identifier | None = None
    claim_resolution_seed: IntakeClaimResolutionSeed | None = None
    respondent_attitude_seed: IntakeRespondentAttitudeSeed | None = None


# 所属模块：Python Agent 数据契约 > final_agents；函数角色：模块私有业务函数。
# 具体功能：`_validate_intake_context_tree` 校验案件与会话上下文的 Schema、权限和阶段约束，拒绝越权或不一致数据；关键协作调用：`stack.pop`、`ValueError`、`seen_containers.add`。
# 上下游：上游为 本文件的 `IntakeTurnRequest.enforce_context_resource_limits`；下游为 协作调用 `stack.pop`、`ValueError`、`seen_containers.add`、`current.items`。
# 系统意义：这是信任边界：拒绝多余字段、资源越界、身份错配和非法枚举。
def _validate_intake_context_tree(value: Any, *, field_name: str) -> None:
    """Validate an untrusted JSON-like tree without recursively copying it."""

    max_depth = 12
    max_nodes = 5_000
    max_text_characters = 200_000
    stack: list[tuple[Any, int]] = [(value, 0)]
    seen_containers: set[int] = set()
    node_count = 0
    text_characters = 0

    while stack:
        current, depth = stack.pop()
        node_count += 1
        if node_count > max_nodes:
            raise ValueError(f"{field_name} exceeds {max_nodes} values")
        if depth > max_depth:
            raise ValueError(f"{field_name} exceeds nesting depth {max_depth}")
        if isinstance(current, str):
            text_characters += len(current)
        elif isinstance(current, dict):
            identity = id(current)
            if identity in seen_containers:
                raise ValueError(f"{field_name} must be an acyclic JSON tree")
            seen_containers.add(identity)
            for key, item in current.items():
                text_characters += len(str(key))
                stack.append((item, depth + 1))
        elif isinstance(current, (list, tuple)):
            identity = id(current)
            if identity in seen_containers:
                raise ValueError(f"{field_name} must be an acyclic JSON tree")
            seen_containers.add(identity)
            stack.extend((item, depth + 1) for item in current)
        if text_characters > max_text_characters:
            raise ValueError(
                f"{field_name} exceeds {max_text_characters} text characters"
            )


class IntakeTurnRequest(StrictModel):
    case_id: Annotated[
        str, Field(pattern=r"^CASE_[A-Za-z0-9_]{1,59}$")
    ]
    room_type: Literal["INTAKE"]
    turn_source: Literal["EXTERNAL_IMPORT", "FORM_SUBMISSION", "ROOM_MESSAGE"]
    initial_case_facts: IntakeInitialCaseFacts | None = None
    current_user_message: IntakeDialogueMessage | None = None
    recent_dialogue_messages: Annotated[
        list[IntakeDialogueMessage], Field(max_length=5)
    ] = Field(default_factory=list)
    previous_case_detail: dict[str, object] | None = None
    initiator_statement_transcript: Annotated[
        list[IntakeTurnMessage], Field(max_length=100)
    ] = Field(default_factory=list)
    agent_context: AgentInvocationContext

    # 所属模块：Python Agent 数据契约 > final_agents；函数角色：类/闭包内部方法。
    # 具体功能：`enforce_context_resource_limits` 校验案件与会话上下文的 Schema、权限和阶段约束，拒绝越权或不一致数据；关键协作调用：`model_validator`、`raw.get`、`ValueError`。
    # 上下游：上游为 Java 请求、LangGraph 状态、LLM JSON；下游为 本文件的 `_validate_intake_context_tree`。
    # 系统意义：这是信任边界：拒绝多余字段、资源越界、身份错配和非法枚举。
    @model_validator(mode="before")
    @classmethod
    def enforce_context_resource_limits(cls, raw: Any) -> Any:
        """Reject oversized intake context before Pydantic copies it.

        Intake requests are assembled from persisted room state. Keeping the
        board and verbatim transcript bounded prevents a malformed snapshot or
        an unbounded audit transcript from being copied into every model turn.
        """

        if not isinstance(raw, dict):
            return raw
        transcript = raw.get("initiator_statement_transcript")
        if isinstance(transcript, list) and len(transcript) > 100:
            raise ValueError("initiator_statement_transcript exceeds 100 messages")

        text_characters = 0
        for message in transcript if isinstance(transcript, list) else []:
            if isinstance(message, dict):
                text_characters += len(str(message.get("text") or ""))
        for message in raw.get("recent_dialogue_messages") or []:
            if isinstance(message, dict):
                text_characters += len(str(message.get("text") or ""))
        current = raw.get("current_user_message")
        if isinstance(current, dict):
            text_characters += len(str(current.get("text") or ""))
        initial = raw.get("initial_case_facts")
        if isinstance(initial, dict):
            text_characters += len(str(initial.get("form_description") or ""))
        if text_characters > 200_000:
            raise ValueError("intake textual context exceeds 200000 characters")

        previous = raw.get("previous_case_detail")
        if previous is not None:
            _validate_intake_context_tree(previous, field_name="previous_case_detail")
        return raw

    # 所属模块：Python Agent 数据契约 > final_agents；函数角色：类/闭包内部方法。
    # 具体功能：`enforce_agent_context_scope` 校验案件与会话上下文的 Schema、权限和阶段约束，拒绝越权或不一致数据；关键协作调用：`model_validator`、`ValueError`。
    # 上下游：上游为 Java 请求、LangGraph 状态、LLM JSON；下游为 协作调用 `model_validator`、`ValueError`。
    # 系统意义：这是信任边界：拒绝多余字段、资源越界、身份错配和非法枚举。
    @model_validator(mode="after")
    def enforce_agent_context_scope(self) -> "IntakeTurnRequest":
        if self.case_id != self.agent_context.case_id:
            raise ValueError("case_id must match agent_context.case_id")
        if self.room_type != self.agent_context.room_type:
            raise ValueError("room_type must match agent_context.room_type")
        actor_role = str(self.agent_context.actor_role or "").upper()
        if actor_role not in {"USER", "MERCHANT"}:
            raise ValueError("agent_context.actor_role must be an intake party")
        if self.agent_context.scope_type not in {
            "INTAKE_INITIATOR_PRIVATE",
            "INTAKE_PARTY_PRIVATE",
        }:
            raise ValueError("agent_context.scope_type must be intake party private")
        current = self.current_user_message
        if self.turn_source in {"EXTERNAL_IMPORT", "FORM_SUBMISSION"}:
            if self.initial_case_facts is None:
                raise ValueError("form-opening turns require initial_case_facts")
            if (
                self.initial_case_facts.form_source is not None
                and self.initial_case_facts.form_source != self.turn_source
            ):
                raise ValueError("initial_case_facts.form_source must match turn_source")
            if current is not None:
                raise ValueError(
                    "form-opening turns cannot contain current_user_message"
                )
            if self.recent_dialogue_messages:
                raise ValueError("form-opening turns cannot have dialogue history")
            if self.previous_case_detail:
                raise ValueError("form-opening turns cannot have previous_case_detail")
            return self
        if self.initial_case_facts is not None:
            raise ValueError("ROOM_MESSAGE turns cannot contain initial_case_facts")
        if current is None:
            raise ValueError("ROOM_MESSAGE turns require current_user_message")
        if current.role not in {"USER", "MERCHANT"}:
            raise ValueError("current_user_message must be a party message")
        if current.role != actor_role:
            raise ValueError(
                "current_user_message.role must match agent_context.actor_role"
            )
        if current.source != self.turn_source:
            raise ValueError("current_user_message.source must match turn_source")
        if any(
            message.sequence_no >= current.sequence_no
            for message in self.recent_dialogue_messages
        ):
            raise ValueError(
                "recent_dialogue_messages must contain only messages before the current message"
            )
        if self.recent_dialogue_messages != sorted(
            self.recent_dialogue_messages, key=lambda message: message.sequence_no
        ):
            raise ValueError("recent_dialogue_messages must be ordered by sequence_no")
        for message in self.recent_dialogue_messages:
            if message.role == "AGENT":
                continue
            if message.role not in {"USER", "MERCHANT"}:
                raise ValueError("recent dialogue may contain only AGENT and party messages")
            if message.role != actor_role:
                raise ValueError(
                    "recent party message role must match agent_context.actor_role"
                )
        return self


class IntakeTurnResult(StrictModel):
    room_utterance: LongText
    dossier_patch: dict[str, object]
    scroll_snapshot: dict[str, object]
    canvas_operations: Annotated[list[dict[str, object]], Field(max_length=100)]
    memory_frame: dict[str, object] = Field(default_factory=dict)
    admission_recommendation: Literal[
        "ACCEPTED",
        "NEED_MORE_INFO",
        "NOT_ADMISSIBLE",
    ]
    missing_fields: list[Identifier] = Field(default_factory=list)
    knowledge_query_intent: bool = False
    knowledge_answer_mode: Literal["NONE", "STUB"] = "NONE"
    confidence: Annotated[float, Field(ge=0, le=1)]


class EvidenceTurnMessage(StrictModel):
    message_id: Identifier
    role: Literal["USER", "MERCHANT"]
    message_type: Identifier | None = None
    text: LongText
    attachment_refs: Annotated[list[Identifier], Field(max_length=50)] = Field(
        default_factory=list
    )


class EvidenceCaseSnapshotV1(StrictModel):
    """Raw, trusted case data supplied by the Java business boundary."""

    case_id: Annotated[str, Field(pattern=r"^CASE_[A-Za-z0-9_]{1,59}$")]
    case_version: Annotated[int, Field(ge=0)]
    case_status: Identifier
    case_type: Identifier
    dispute_type: Identifier | None
    title: ShortText
    description: RawTransportText
    risk_level: Identifier
    route_type: Identifier | None
    order_id: RawReference | None
    after_sale_id: RawReference | None
    logistics_id: RawReference | None
    source_type: Identifier
    initiator_role: Literal["USER", "MERCHANT"]
    source_system: RawReference | None
    external_case_ref: RawReference | None
    current_room: Identifier | None
    current_deadline_at: ShortText | None


class EvidenceIntakeDossierSnapshotV1(StrictModel):
    dossier_id: Identifier
    schema_version: Identifier | None
    dossier_version: Annotated[int, Field(ge=1)]
    source_turn_no: Annotated[int, Field(ge=1)]
    quality_score: Annotated[int, Field(ge=0, le=100)]
    ready_for_next_step: bool
    admission_recommendation: Identifier
    updated_at: ShortText
    payload: dict[str, object]


class EvidenceActorSnapshotV1(StrictModel):
    actor_id: Identifier
    actor_role: Literal["USER", "MERCHANT"]
    initiator_role: Literal["USER", "MERCHANT"]
    access_session_id: Identifier
    agent_session_id: Identifier
    conversation_scope: Annotated[str, Field(min_length=10, max_length=512)]
    prompt_profile_id: Identifier
    memory_policy_id: Identifier


class EvidenceCurrentEventV1(StrictModel):
    event_id: Identifier
    event_type: Literal["ROOM_OPENING", "PARTY_MESSAGE"]
    message_type: Literal[
        "AGENT_MESSAGE",
        "PARTY_TEXT",
        "PARTY_EVIDENCE_REFERENCE",
    ]
    actor_id: Identifier
    actor_role: Literal["USER", "MERCHANT"]
    text: Annotated[str, Field(max_length=2_000_000)] | None
    attachment_refs: Annotated[list[Identifier], Field(max_length=50)]
    turn_no: Annotated[int, Field(ge=1)]
    occurred_at: ShortText

    # 所属模块：Python Agent 数据契约 > final_agents；函数角色：类/闭包内部方法。
    # 具体功能：`validate_event_shape` 校验本阶段状态的 Schema、权限和阶段约束，拒绝越权或不一致数据；关键协作调用：`model_validator`、`ValueError`、`self.text.strip`。
    # 上下游：上游为 Java 请求、LangGraph 状态、LLM JSON；下游为 协作调用 `model_validator`、`ValueError`、`self.text.strip`。
    # 系统意义：这是信任边界：拒绝多余字段、资源越界、身份错配和非法枚举。
    @model_validator(mode="after")
    def validate_event_shape(self) -> "EvidenceCurrentEventV1":
        if self.event_type == "ROOM_OPENING":
            if self.message_type != "AGENT_MESSAGE":
                raise ValueError("ROOM_OPENING message_type must be AGENT_MESSAGE")
            if self.text is not None:
                raise ValueError("ROOM_OPENING text must be null")
            if self.attachment_refs:
                raise ValueError("ROOM_OPENING attachment_refs must be empty")
            return self
        if self.message_type not in {"PARTY_TEXT", "PARTY_EVIDENCE_REFERENCE"}:
            raise ValueError(
                "PARTY_MESSAGE message_type must be PARTY_TEXT or "
                "PARTY_EVIDENCE_REFERENCE"
            )
        if not (self.text and self.text.strip()) and not self.attachment_refs:
            raise ValueError("PARTY_MESSAGE requires text or attachment_refs")
        return self


class EvidenceVisibleEvidenceItemV1(StrictModel):
    evidence_id: Identifier
    dossier_id: Identifier
    evidence_type: Identifier
    source_type: Identifier
    submitted_by_role: Literal[
        "USER",
        "MERCHANT",
        "PLATFORM",
        "CUSTOMER_SERVICE",
    ]
    submitted_by_id: Identifier
    original_filename: ShortText | None
    content_type: ShortText | None
    file_size: Annotated[int, Field(ge=0)] | None
    file_hash: ShortText | None
    parsed_text: RawTransportText | None
    parse_status: Identifier
    visibility: Identifier
    desensitized: bool
    metadata: dict[str, object]
    extraction: dict[str, object]
    occurred_at: ShortText | None
    created_at: ShortText
    submitted_at: ShortText | None
    submission_status: Literal["SUBMITTED"]
    submission_batch_id: Identifier | None
    content_url: ShortText


class EvidencePrivateConversationTurnV1(StrictModel):
    turn_no: Annotated[int, Field(ge=0)]
    actor_id: Identifier | None
    answer_role: Identifier | None
    answer_content: RawTransportText | None
    agent_role: Identifier | None
    agent_response: RawTransportText | None
    scroll_snapshot: dict[str, object]
    agent_session_id: Identifier
    conversation_scope: Annotated[str, Field(min_length=10, max_length=512)]


class EvidencePrivateConversationV1(StrictModel):
    agent_session_id: Identifier
    conversation_scope: Annotated[str, Field(min_length=10, max_length=512)]
    source_count: Annotated[int, Field(ge=0)]
    truncated: bool
    recent_turns: Annotated[
        list[EvidencePrivateConversationTurnV1],
        Field(max_length=20),
    ]


class EvidenceRoomPolicyV1(StrictModel):
    room_id: Identifier
    room_type: Literal["EVIDENCE", "HEARING"]
    room_status: Identifier
    current_deadline_at: ShortText | None
    initiator_role: Literal["USER", "MERCHANT"]
    initiator_evidence_required: bool


class EvidenceContextEnvelopeV1(StrictModel):
    """Versioned Java-to-Harness evidence context boundary."""

    schema_version: Literal["evidence_context_envelope.v1"]
    captured_at: ShortText
    case_snapshot: EvidenceCaseSnapshotV1
    intake_dossier_snapshot: EvidenceIntakeDossierSnapshotV1 | None
    actor_snapshot: EvidenceActorSnapshotV1
    current_event: EvidenceCurrentEventV1
    visible_evidence: list[EvidenceVisibleEvidenceItemV1]
    private_conversation: EvidencePrivateConversationV1
    room_policy: EvidenceRoomPolicyV1

    # 所属模块：Python Agent 数据契约 > final_agents；函数角色：类/闭包内部方法。
    # 具体功能：`validate_visible_evidence_scope` 校验当前可见证据的 Schema、权限和阶段约束，拒绝越权或不一致数据；关键协作调用：`model_validator`、`visible_ids.add`、`ValueError`。
    # 上下游：上游为 Java 请求、LangGraph 状态、LLM JSON；下游为 协作调用 `model_validator`、`visible_ids.add`、`ValueError`。
    # 系统意义：这是信任边界：拒绝多余字段、资源越界、身份错配和非法枚举。
    @model_validator(mode="after")
    def validate_visible_evidence_scope(self) -> "EvidenceContextEnvelopeV1":
        actor = self.actor_snapshot
        visible_ids: set[str] = set()
        for item in self.visible_evidence:
            if item.submitted_by_role != actor.actor_role:
                raise ValueError(
                    "visible_evidence submitted_by_role must match "
                    "actor_snapshot.actor_role"
                )
            if item.submitted_by_id != actor.actor_id:
                raise ValueError(
                    "visible_evidence submitted_by_id must match "
                    "actor_snapshot.actor_id"
                )
            if item.evidence_id in visible_ids:
                raise ValueError("visible_evidence evidence_id values must be unique")
            visible_ids.add(item.evidence_id)
        unknown_attachments = set(self.current_event.attachment_refs) - visible_ids
        if unknown_attachments:
            raise ValueError(
                "current_event.attachment_refs must reference visible_evidence"
            )
        return self


class EvidenceTurnEvidenceItem(StrictModel):
    evidence_id: Identifier
    evidence_type: Identifier
    source_type: Identifier
    content: LongText
    parsed_text: LongText | None = None
    agent_summary: LongText | None = None
    occurred_at: ShortText | None = None
    related_claim_ids: Annotated[list[Identifier], Field(max_length=50)] = Field(
        default_factory=list
    )
    parser_warning: ShortText | None = None
    submitted_by_role: Literal["USER", "MERCHANT", "PLATFORM", "CUSTOMER_SERVICE"] | None = None
    visibility: Identifier | None = None
    content_url: ShortText | None = None
    content_type: ShortText | None = None
    parse_status: Identifier | None = None
    original_filename: ShortText | None = None
    redacted: bool = False
    claimed_fact: LongText | None = None
    truth_attested: bool | None = None
    attestation_version: ShortText | None = None
    attestation_scope: Annotated[list[Identifier], Field(max_length=10)] = Field(
        default_factory=list
    )
    attestation_role: Literal[
        "USER",
        "MERCHANT",
        "PLATFORM",
        "CUSTOMER_SERVICE",
    ] | None = None
    attested_by: ShortText | None = None
    attested_at: ShortText | None = None
    party_capacity: Literal["INITIATOR", "RESPONDENT"] | None = None
    forgery_consequence_code: ShortText | None = None
    enforcement_gate: ShortText | None = None


class EvidenceTurnQuestion(StrictModel):
    question_id: Identifier
    target_evidence_id: Identifier | None = None
    question: LongText
    reason: ShortText


class EvidenceVerificationSuggestion(StrictModel):
    evidence_id: Identifier
    suggestion: LongText
    confidence_score: Annotated[
        float,
        Field(
            ge=0,
            le=1,
            description="该证据核验建议的置信度，取值范围为 0.0 至 1.0。",
        ),
    ]


class EvidenceAuthenticityFlag(StrictModel):
    evidence_id: Identifier | None = None
    flag_type: Identifier
    description: ShortText
    severity: Literal["LOW", "MEDIUM", "HIGH"] = "LOW"


class EvidenceFactLink(StrictModel):
    fact_id: Identifier
    relation: Literal["SUPPORTS", "OPPOSES", "INCONCLUSIVE"]
    reason: ShortText
    confidence: Confidence


class EvidenceVisualFinding(StrictModel):
    finding_type: Identifier
    description: ShortText
    visual_region: ShortText | None = None


class EvidenceRiskFlag(StrictModel):
    code: Identifier
    severity: Literal["LOW", "MEDIUM", "HIGH"]
    description: ShortText


class EvidenceHumanReviewSignal(StrictModel):
    required: bool = False
    reason_codes: Annotated[list[Identifier], Field(max_length=20)] = Field(
        default_factory=list
    )
    instructions: Annotated[list[ShortText], Field(max_length=20)] = Field(
        default_factory=list
    )


class EvidenceItemAssessment(StrictModel):
    """针对单份证据的可审计模型评估，不构成真实性保证。"""

    evidence_id: Identifier
    analysis_method: Literal["TEXT_ONLY", "MULTIMODAL", "HYBRID"]
    inspected_modalities: Annotated[list[Identifier], Field(max_length=10)] = Field(
        default_factory=list
    )
    fact_links: Annotated[list[EvidenceFactLink], Field(max_length=50)] = Field(
        default_factory=list,
        description=(
            "事实坐标关联；relevance_score >= 0.50 时至少一项。真实性或完整性"
            "不足时保留 fact_id 并使用 INCONCLUSIVE；低相关时允许为空。"
        ),
    )
    authenticity_score: Confidence
    relevance_score: Confidence
    completeness_score: Confidence
    assessment_confidence: Confidence
    source_basis: Annotated[list[ShortText], Field(min_length=1, max_length=20)]
    supported_fact_ids: Annotated[list[Identifier], Field(max_length=50)] = Field(
        default_factory=list
    )
    unsupported_claims: Annotated[list[ShortText], Field(max_length=30)] = Field(
        default_factory=list
    )
    formation_time_assessment: ShortText
    findings: Annotated[list[EvidenceVisualFinding], Field(max_length=30)] = Field(
        default_factory=list
    )
    limitations: Annotated[list[ShortText], Field(max_length=30)] = Field(
        default_factory=list
    )
    risk_flags: Annotated[list[EvidenceRiskFlag], Field(max_length=30)] = Field(
        default_factory=list
    )
    recommendation: Literal[
        "PLAUSIBLE",
        "SUSPICIOUS",
        "NEEDS_HUMAN_REVIEW",
    ]
    human_review: EvidenceHumanReviewSignal = Field(
        default_factory=EvidenceHumanReviewSignal
    )
    asset_audit: dict[str, object] = Field(default_factory=dict)
    summary: LongText


class EvidenceFactMatrixPatch(StrictModel):
    operation: Literal["UPSERT_LINK", "REMOVE_LINK"]
    fact_id: Identifier
    evidence_id: Identifier
    relation: Literal["SUPPORTS", "OPPOSES", "INCONCLUSIVE"]
    reason: ShortText
    confidence: Confidence


class EvidenceHumanReviewTask(StrictModel):
    evidence_id: Identifier
    reason_codes: Annotated[list[Identifier], Field(min_length=1, max_length=20)]
    review_goal: ShortText
    instructions: Annotated[list[ShortText], Field(min_length=1, max_length=20)]
    priority: Literal["LOW", "MEDIUM", "HIGH"]


class EvidenceInternalHandoff(StrictModel):
    evidence_change_summary: ShortText
    matrix_change_summary: ShortText
    remaining_conflicts: Annotated[list[ShortText], Field(max_length=30)] = Field(
        default_factory=list
    )
    uncovered_fact_ids: Annotated[list[Identifier], Field(max_length=100)] = Field(
        default_factory=list
    )
    human_review_evidence_ids: Annotated[
        list[Identifier], Field(max_length=50)
    ] = Field(default_factory=list)
    judge_attention_points: Annotated[list[ShortText], Field(max_length=30)] = Field(
        default_factory=list
    )


class EvidenceTurnLlmOutput(StrictModel):
    room_utterance: LongText
    evidence_requests: Annotated[
        list[EvidenceTurnQuestion],
        Field(max_length=3),
    ] = Field(default_factory=list)
    verification_suggestions: Annotated[
        list[EvidenceVerificationSuggestion],
        Field(max_length=100),
    ] = Field(default_factory=list)
    authenticity_flags: Annotated[
        list[EvidenceAuthenticityFlag],
        Field(max_length=100),
    ] = Field(default_factory=list)
    evidence_assessments: Annotated[
        list[EvidenceItemAssessment],
        Field(max_length=50),
    ] = Field(default_factory=list)
    fact_matrix_patch: Annotated[
        list[EvidenceFactMatrixPatch],
        Field(max_length=100),
    ] = Field(
        default_factory=list,
        description=(
            "通常为空。UPSERT_LINK 由系统从已验收 fact_links 确定性生成；"
            "模型仅在撤销本轮附件的既有错误关联时输出 REMOVE_LINK。"
        ),
    )
    human_review_tasks: Annotated[
        list[EvidenceHumanReviewTask],
        Field(max_length=50),
    ] = Field(default_factory=list)
    internal_handoff: EvidenceInternalHandoff
    confidence: Confidence


class EvidenceTurnRequest(StrictModel):
    context_envelope: EvidenceContextEnvelopeV1
    agent_context: AgentInvocationContext

    # 所属模块：Python Agent 数据契约 > final_agents；函数角色：类/闭包内部方法。
    # 具体功能：`enforce_agent_context_scope` 校验案件与会话上下文的 Schema、权限和阶段约束，拒绝越权或不一致数据；关键协作调用：`model_validator`。
    # 上下游：上游为 Java 请求、LangGraph 状态、LLM JSON；下游为 本文件的 `_validate_context_envelope_scope`。
    # 系统意义：这是信任边界：拒绝多余字段、资源越界、身份错配和非法枚举。
    @model_validator(mode="after")
    def enforce_agent_context_scope(self) -> "EvidenceTurnRequest":
        self._validate_context_envelope_scope()
        return self

    # 所属模块：Python Agent 数据契约 > final_agents；函数角色：类/闭包内部方法。
    # 具体功能：`_validate_context_envelope_scope` 校验案件与会话上下文的 Schema、权限和阶段约束，拒绝越权或不一致数据；关键协作调用：`ValueError`。
    # 上下游：上游为 本文件的 `EvidenceTurnRequest.enforce_agent_context_scope`；下游为 协作调用 `ValueError`。
    # 系统意义：这是信任边界：拒绝多余字段、资源越界、身份错配和非法枚举。
    def _validate_context_envelope_scope(self) -> None:
        envelope = self.context_envelope
        case_snapshot = envelope.case_snapshot
        actor_snapshot = envelope.actor_snapshot
        current_event = envelope.current_event
        conversation = envelope.private_conversation
        room_policy = envelope.room_policy
        context = self.agent_context

        if context.agent_key != "EVIDENCE_CLERK":
            raise ValueError("agent_context.agent_key must be EVIDENCE_CLERK")
        if context.scope_type != "EVIDENCE_PARTY_PRIVATE":
            raise ValueError(
                "agent_context.scope_type must be EVIDENCE_PARTY_PRIVATE"
            )
        if case_snapshot.case_id != context.case_id:
            raise ValueError(
                "context_envelope.case_snapshot.case_id must match "
                "agent_context.case_id"
            )
        if room_policy.room_type != context.room_type:
            raise ValueError(
                "context_envelope.room_policy.room_type must match "
                "agent_context.room_type"
            )
        if (
            case_snapshot.current_room is not None
            and case_snapshot.current_room != room_policy.room_type
        ):
            raise ValueError(
                "context_envelope.case_snapshot.current_room must match "
                "room_policy.room_type"
            )
        if (
            case_snapshot.current_deadline_at is not None
            and room_policy.current_deadline_at is not None
            and case_snapshot.current_deadline_at != room_policy.current_deadline_at
        ):
            raise ValueError(
                "context_envelope case and room deadline snapshots must match"
            )
        if actor_snapshot.actor_id != context.actor_id:
            raise ValueError(
                "context_envelope.actor_snapshot.actor_id must match agent_context.actor_id"
            )
        if actor_snapshot.actor_role != context.actor_role:
            raise ValueError(
                "context_envelope.actor_snapshot.actor_role must match "
                "agent_context.actor_role"
            )
        if actor_snapshot.actor_id not in context.allowed_actor_ids:
            raise ValueError(
                "context_envelope.actor_snapshot.actor_id must be present in "
                "agent_context.allowed_actor_ids"
            )
        if actor_snapshot.actor_role not in context.allowed_actor_roles:
            raise ValueError(
                "context_envelope.actor_snapshot.actor_role must be present in "
                "agent_context.allowed_actor_roles"
            )
        expected_permission = (
            "PARTY_USER" if actor_snapshot.actor_role == "USER" else "PARTY_MERCHANT"
        )
        if context.permission_level != expected_permission:
            raise ValueError(
                "agent_context.permission_level must match actor_snapshot.actor_role"
            )
        if actor_snapshot.access_session_id != context.access_session_id:
            raise ValueError(
                "context_envelope.actor_snapshot.access_session_id must match "
                "agent_context.access_session_id"
            )
        if actor_snapshot.agent_session_id != context.agent_session_id:
            raise ValueError(
                "context_envelope.actor_snapshot.agent_session_id must match "
                "agent_context.agent_session_id"
            )
        if actor_snapshot.conversation_scope != context.conversation_scope:
            raise ValueError(
                "context_envelope.actor_snapshot.conversation_scope must match "
                "agent_context.conversation_scope"
            )
        if actor_snapshot.prompt_profile_id != context.prompt_profile_id:
            raise ValueError(
                "context_envelope.actor_snapshot.prompt_profile_id must match "
                "agent_context.prompt_profile_id"
            )
        if actor_snapshot.memory_policy_id != context.memory_policy_id:
            raise ValueError(
                "context_envelope.actor_snapshot.memory_policy_id must match "
                "agent_context.memory_policy_id"
            )
        if current_event.actor_id != actor_snapshot.actor_id:
            raise ValueError(
                "context_envelope.current_event.actor_id must match actor_snapshot.actor_id"
            )
        if current_event.actor_role != actor_snapshot.actor_role:
            raise ValueError(
                "context_envelope.current_event.actor_role must match "
                "actor_snapshot.actor_role"
            )
        if actor_snapshot.initiator_role != case_snapshot.initiator_role:
            raise ValueError(
                "context_envelope.actor_snapshot.initiator_role must match "
                "case_snapshot.initiator_role"
            )
        if room_policy.initiator_role != case_snapshot.initiator_role:
            raise ValueError(
                "context_envelope.room_policy.initiator_role must match "
                "case_snapshot.initiator_role"
            )
        if conversation.agent_session_id != actor_snapshot.agent_session_id:
            raise ValueError(
                "context_envelope.private_conversation.agent_session_id must match "
                "actor_snapshot.agent_session_id"
            )
        if conversation.conversation_scope != actor_snapshot.conversation_scope:
            raise ValueError(
                "context_envelope.private_conversation.conversation_scope must match "
                "actor_snapshot.conversation_scope"
            )
        turn_numbers = [turn.turn_no for turn in conversation.recent_turns]
        if turn_numbers != sorted(turn_numbers):
            raise ValueError(
                "context_envelope.private_conversation.recent_turns must be ordered"
            )
        for turn in conversation.recent_turns:
            if turn.agent_session_id != conversation.agent_session_id:
                raise ValueError(
                    "context_envelope.private_conversation.recent_turns agent_session_id "
                    "must match private_conversation.agent_session_id"
                )
            if turn.conversation_scope != conversation.conversation_scope:
                raise ValueError(
                    "context_envelope.private_conversation.recent_turns "
                    "conversation_scope must match private_conversation.conversation_scope"
                )
            has_party_answer = (
                turn.answer_content is not None or turn.answer_role is not None
            )
            if has_party_answer and turn.actor_id != actor_snapshot.actor_id:
                raise ValueError(
                    "context_envelope.private_conversation.recent_turns actor_id "
                    "must match actor_snapshot.actor_id"
                )
            if has_party_answer and turn.answer_role != actor_snapshot.actor_role:
                raise ValueError(
                    "context_envelope.private_conversation.recent_turns answer_role "
                    "must match actor_snapshot.actor_role"
                )
            if turn.agent_role is not None and turn.agent_role != "EVIDENCE_CLERK":
                raise ValueError(
                    "context_envelope.private_conversation.recent_turns agent_role "
                    "must be EVIDENCE_CLERK"
                )
            if turn.agent_response is not None and turn.agent_role != "EVIDENCE_CLERK":
                raise ValueError(
                    "context_envelope.private_conversation.recent_turns agent response "
                    "requires EVIDENCE_CLERK role"
                )


class EvidenceTurnResult(EvidenceTurnLlmOutput):
    # Python 内部仍以 memory_frame 表示本轮工作记忆；跨服务 JSON 使用
    # Java AgentRun 正式合同名 memory_patch，避免流式 final 在别名反序列化前失败。
    memory_frame: dict[str, object] = Field(
        default_factory=dict,
        serialization_alias="memory_patch",
    )
    canvas_operations: Annotated[
        list[dict[str, object]], Field(max_length=100)
    ] = Field(default_factory=list)
    referenced_evidence_ids: Annotated[
        list[Identifier], Field(max_length=50)
    ] = Field(default_factory=list)
    non_final: Literal[True] = True
    liability_determined: Literal[False] = False
    remedy_recommended: Literal[False] = False


class EvidenceBuildRequest(StrictModel):
    case_id: Identifier
    case_version: Annotated[int, Field(ge=1)]
    submission_version: Annotated[int, Field(ge=1)]
    current_dossier_version: Annotated[int, Field(ge=1)] | None = None
    party_claims: Annotated[list[PartyClaim], Field(max_length=50)] = Field(
        default_factory=list
    )
    evidence: Annotated[list["DossierEvidenceItem"], Field(max_length=200)] = Field(
        default_factory=list
    )


class DossierEvidenceItem(EvidenceItem):
    """Original evidence plus separately stored derived representations."""

    parsed_text: LongText | None = None
    agent_summary: LongText | None = None
    occurred_at: ShortText | None = None
    related_claim_ids: Annotated[list[Identifier], Field(max_length=50)] = Field(
        default_factory=list
    )
    parser_warning: ShortText | None = None


class EvidenceCatalogEntry(StrictModel):
    evidence_id: Identifier
    evidence_type: Identifier
    source_type: Identifier
    original_ref: Identifier
    original_content: LongText
    parsed_text: LongText | None = None
    agent_summary: LongText | None = None
    is_party_statement: bool


class EvidenceVerificationRecommendation(StrEnum):
    VERIFIED = "VERIFIED"
    PLAUSIBLE = "PLAUSIBLE"
    SUSPICIOUS = "SUSPICIOUS"
    REJECTED = "REJECTED"
    NEEDS_HUMAN_REVIEW = "NEEDS_HUMAN_REVIEW"


class EvidenceVerificationAssessment(StrictModel):
    evidence_ref: Identifier
    recommendation: EvidenceVerificationRecommendation
    reasons: list[ShortText] = Field(default_factory=list)
    visibility_warning: ShortText | None = None
    authenticity_guaranteed: Literal[False] = False


class ClaimIssueEvidenceLink(StrictModel):
    claim_id: Identifier
    issue_id: Identifier | None = None
    evidence_refs: Annotated[list[Identifier], Field(max_length=100)] = Field(
        default_factory=list
    )


class EvidenceTimelineEvent(StrictModel):
    event_id: Identifier
    occurred_at: ShortText
    description: LongText
    source_refs: Annotated[list[Identifier], Field(min_length=1, max_length=20)]


class EvidenceDossierResult(StrictModel):
    case_id: Identifier
    dossier_version: Annotated[int, Field(ge=1)]
    timeline: list[EvidenceTimelineEvent] = Field(default_factory=list)
    party_claims: list[PartyClaim] = Field(default_factory=list)
    evidence_catalog: list[EvidenceCatalogEntry] = Field(default_factory=list)
    claim_issue_evidence_matrix: list[ClaimIssueEvidenceLink] = Field(
        default_factory=list
    )
    conflicts: list[ShortText] = Field(default_factory=list)
    gaps: list[ShortText] = Field(default_factory=list)
    duplicate_groups: list[list[Identifier]] = Field(default_factory=list)
    parser_warnings: list[ShortText] = Field(default_factory=list)
    policy_candidates: list[Identifier] = Field(default_factory=list)
    source_citations: list[Identifier] = Field(default_factory=list)
    deterministic_evidence_refs: list[Identifier] = Field(default_factory=list)
    verification_recommendations: list[EvidenceVerificationAssessment] = Field(
        default_factory=list
    )
    visibility_warnings: list[ShortText] = Field(default_factory=list)
    authenticity_guaranteed: Literal[False] = False
    authenticity_disclaimer: Literal[
        "Verification is a risk recommendation, not an authenticity guarantee."
    ] = "Verification is a risk recommendation, not an authenticity guarantee."
    liability_determined: Literal[False] = False
    remedy_recommended: Literal[False] = False


class CriticType(StrEnum):
    EVIDENCE = "EVIDENCE_CRITIC"
    RULE = "RULE_CRITIC"
    RISK = "RISK_CRITIC"
    REMEDY = "REMEDY_CRITIC"
    FAIRNESS = "FAIRNESS_CRITIC"


class CriticSeverity(StrEnum):
    NONE = "NONE"
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    BLOCKER = "BLOCKER"


class CriticStatus(StrEnum):
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    TIMED_OUT = "TIMED_OUT"


class FrozenDeliberationInput(StrictModel):
    """One immutable version tuple shared by every panel member."""

    case_id: Identifier
    case_snapshot_version: Annotated[int, Field(ge=1)]
    dossier_version: Annotated[int, Field(ge=1)]
    adjudication_draft_version: Annotated[int, Field(ge=1)]
    rule_version: Identifier
    remedy_plan_candidate_version: Annotated[int, Field(ge=1)] | None = None
    frozen_dossier_snapshot: dict[str, object]
    frozen_draft_snapshot: dict[str, object]
    frozen_at_event_sequence: Annotated[int, Field(ge=0)]


class CriticDraft(StrictModel):
    severity: CriticSeverity
    findings: list[ShortText] = Field(default_factory=list)
    blocking_issues: list[Identifier] = Field(default_factory=list)
    recommended_revision: ShortText | None = None
    frozen_input_fingerprint: str | None = None


class CriticReport(StrictModel):
    critic: CriticType
    scope: Literal["EVIDENCE", "RULE", "RISK", "REMEDY", "FAIRNESS"]
    status: CriticStatus
    severity: CriticSeverity
    findings: list[ShortText] = Field(default_factory=list)
    blocking_issues: list[Identifier] = Field(default_factory=list)
    recommended_revision: ShortText | None = None
    frozen_input_fingerprint: str
    failure_reason: ShortText | None = None
    approval_performed: Literal[False] = False
    execution_triggered: Literal[False] = False
    is_final_decision: Literal[False] = False


class DeliberationRequest(StrictModel):
    frozen_input: FrozenDeliberationInput
    trigger_reasons: Annotated[list[Identifier], Field(min_length=1, max_length=30)]


class DeliberationReport(StrictModel):
    deliberation_id: Identifier
    report_version: Annotated[int, Field(ge=1)] = 1
    panel_result: Literal[
        "NO_MAJOR_OBJECTION",
        "REVISION_REQUIRED",
        "MANUAL_REVIEW_REQUIRED",
    ]
    frozen_input_fingerprint: str
    critic_reports: list[CriticReport]
    trigger_reasons: list[Identifier] = Field(default_factory=list)
    major_risks: list[Identifier] = Field(default_factory=list)
    consensus: list[ShortText] = Field(default_factory=list)
    disagreements: list[ShortText] = Field(default_factory=list)
    recommended_revision: ShortText | None = None
    reviewer_attention: list[ShortText] = Field(default_factory=list)
    revision_required: bool
    approval_performed: Literal[False] = False
    execution_triggered: Literal[False] = False
    is_final_decision: Literal[False] = False


class ReviewCopilotRequest(StrictModel):
    review_id: Identifier
    case_id: Identifier
    review_packet_version: Annotated[int, Field(ge=1)]
    reviewer_role: Literal["PLATFORM_REVIEWER"]
    question: LongText
    available_fact_refs: list[Identifier] = Field(default_factory=list)
    available_rule_refs: list[Identifier] = Field(default_factory=list)
    available_draft_refs: list[Identifier] = Field(default_factory=list)
    available_deliberation_refs: list[Identifier] = Field(default_factory=list)
    frozen_packet: dict[str, object] = Field(default_factory=dict)


class ReviewStatement(StrictModel):
    kind: Literal["FACT", "INFERENCE", "SUGGESTION"]
    text: LongText
    refs: list[Identifier] = Field(default_factory=list)


class ReviewCopilotAnswer(StrictModel):
    answer: LongText
    statements: Annotated[list[ReviewStatement], Field(min_length=1, max_length=50)]
    fact_refs: list[Identifier] = Field(default_factory=list)
    rule_refs: list[Identifier] = Field(default_factory=list)
    draft_refs: list[Identifier] = Field(default_factory=list)
    deliberation_refs: list[Identifier] = Field(default_factory=list)
    uncertainties: list[ShortText] = Field(default_factory=list)
    suggested_review_focus: list[ShortText] = Field(default_factory=list)
    approval_performed: Literal[False] = False
    execution_triggered: Literal[False] = False
    is_final_decision: Literal[False] = False
