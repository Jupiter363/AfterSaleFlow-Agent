from __future__ import annotations

import re
from collections.abc import AsyncIterator, Callable, Iterator, Mapping
from copy import deepcopy
from dataclasses import dataclass
from inspect import getattr_static
from typing import Any, Literal, cast
from weakref import WeakKeyDictionary

from langchain_core.messages import AIMessage, SystemMessage
from langchain_core.output_parsers import PydanticOutputParser
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.runnables import (
    Runnable,
    RunnableConfig,
    RunnableParallel,
    RunnablePassthrough,
    RunnableSequence,
)
from typing_extensions import TypedDict

from app.contracts.v1.codec import canonical_sha256, canonicalize
from app.graph_runtime.state_lens import StateLens
from app.harness.prompt_composer import PromptComposer
from app.graphs.intake.contracts import (
    MODEL_CONTROLLED_FORBIDDEN_FIELDS,
    IntakeCognitionDraft,
    UnilateralCaseMatrixDraftV1,
)
from app.graphs.intake.errors import IntakeGraphContractError
from app.graphs.intake.state import IntakeGraphStateV2
from app.graphs.intake.validators import (
    validate_cognition_patch,
    validate_dossier_transition,
    validate_matrix_patch,
    validate_state,
)
from app.model_runtime.governed_chat_model import GovernedChatModel
from app.model_runtime.profiles import (
    ModelInvocationPolicy,
    ModelProfile,
    system_prompt_sha256,
)
from app.model_runtime.transports import ModelTransport
from app.streaming import VisibleFieldSpec


_BASELINE_INTAKE_SYSTEM_PROMPT = PromptComposer().render_system_prompt("intake_turn_case_detail")

_TARGET_INTAKE_SCHEMA_SUPPLEMENT = """Target intake.v2 contract adapter.
Apply all baseline business rules above. The authorized input envelope exposes participant turns in
authorized_messages_json, the initial form fields inside
bounded_memory_summary.authorized_initial_case_facts, the current projection in
authorized_dossier_json, and immutable identifiers in immutable_source_catalog_json. Use no other
case source.

Return exactly the configured IntakeCognitionDraft JSON. Map the baseline case_detail patch to
dossier_patch and place it only there. Do not emit baseline-only case_detail, ready_for_next_step,
handoff_notes, or case_matrix_delta fields.

Map the baseline unified-matrix semantics to the internal matrix_patch proposal: an initiator emits
unilateral_case_matrix.draft.v1 and a respondent against the frozen initiator matrix emits
case_fact_matrix.delta.v2. Both remain semantic deltas only. Java validates actor/source authority
and deterministically materializes the one formal unified bilateral case matrix; never emit the
formal case_fact_matrix.v2 or its IDs, versions, hashes, alignments, or authority fields."""

# Loaded once from packaged repository assets.  The exact composed bytes are then pinned by
# trusted_system_sha256, so request data cannot select or mutate the production prompt.
INTAKE_SYSTEM_PROMPT = (
    _BASELINE_INTAKE_SYSTEM_PROMPT.strip() + "\n\n" + _TARGET_INTAKE_SCHEMA_SUPPLEMENT.strip()
)

_TARGET_INTAKE_VISIBLE_FIELDS = (
    # The full JSON string closes before the remaining dossier branches.  Keeping it
    # atomic lets the executor validate the complete question before it becomes
    # visible, without delaying the first reply until the full graph is terminal.
    VisibleFieldSpec("room_utterance", "room_utterance", "json_value"),
    VisibleFieldSpec("case_story", "case_detail.case_story", "json_value"),
    VisibleFieldSpec("references", "case_detail.references", "json_value"),
    VisibleFieldSpec("party_positions", "case_detail.party_positions", "json_value"),
    VisibleFieldSpec("dispute_focus", "case_detail.dispute_focus", "json_value"),
    VisibleFieldSpec("requested_resolution", "case_detail.requested_resolution", "json_value"),
    VisibleFieldSpec("claim_resolution", "case_detail.claim_resolution", "json_value"),
    VisibleFieldSpec("respondent_attitude", "case_detail.respondent_attitude", "json_value"),
    VisibleFieldSpec("dispute_core_state", "case_detail.dispute_core_state", "json_value"),
    VisibleFieldSpec("risk_assessment", "case_detail.risk_assessment", "json_value"),
    VisibleFieldSpec("missing_information", "case_detail.missing_information", "json_value"),
    VisibleFieldSpec("intake_quality", "case_detail.intake_quality", "json_value"),
    VisibleFieldSpec("admission", "case_detail.admission", "json_value"),
)

_EVIDENCE_MATERIAL_IDENTIFIER = re.compile(
    r"(?:evidence|proof|screenshot|screen_shot|photo|picture|video|chat_(?:record|log)|"
    r"logistics_(?:voucher|proof|receipt)|tracking_(?:receipt|proof)|attachment|document)",
    re.IGNORECASE,
)
_EVIDENCE_MATERIAL_TEXT = (
    "证据",
    "凭证",
    "截图",
    "照片",
    "图片",
    "视频",
    "聊天记录",
    "物流单",
    "运单",
    "附件",
)
_EVIDENCE_REQUEST_ZH = re.compile(
    r"(?:请|请您|烦请|麻烦|需要|还需|必须|能否|是否|可否)"
    r"[^。！？!?]{0,32}(?:证据|凭证|截图|照片|图片|视频|聊天记录|物流单|运单|附件|材料)"
    r"|(?:证据|凭证|截图|照片|图片|视频|聊天记录|物流单|运单|附件|材料)"
    r"[^。！？!?]{0,16}(?:请补充|请提供|请上传|需提交|需要出示|请发送|请附上)",
)
_EVIDENCE_REQUEST_EN = re.compile(
    r"(?:please|kindly|must|can you|could you|need (?:you|the party)?\s*to|required to)"
    r"[^.!?]{0,64}(?:evidence|proof|screenshot|photo|picture|video|chat (?:record|log)|"
    r"logistics (?:voucher|receipt)|tracking (?:receipt|proof)|document|attachment)"
    r"|(?:evidence|proof|screenshot|photo|picture|video|chat (?:record|log)|document|attachment)"
    r"[^.!?]{0,32}(?:please provide|please upload|must submit|need to send|required to attach)",
    re.IGNORECASE,
)

_HUMAN_PROMPT = """Authorized audience: {audience}
<authorized_messages_json>{messages_json}</authorized_messages_json>
<bounded_memory_summary>{memory_summary}</bounded_memory_summary>
<authorized_dossier_json>{dossier_json}</authorized_dossier_json>
<immutable_source_catalog_json>{source_refs_json}</immutable_source_catalog_json>
<trusted_version_ids_json>{version_ids_json}</trusted_version_ids_json>"""

_IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_NO_TOOLS_POLICY_VERSION = "no-tools.v1"
_VETTED_INTAKE_RUNNABLE_TOKEN = object()
_INTERNAL_OUTPUT_FIELDS = frozenset(
    {
        "actor_id",
        "actor_scope",
        "actor_scope_hash",
        "agent_session_id",
        "access_session_id",
        "thread_id",
        "tenant_surrogate",
        "command_id",
        "logical_run_id",
        "attempt_id",
        "capabilities",
        "system_prompt",
        "model_profile",
        "model_profile_id",
        "prompt_profile",
        "prompt_version",
    }
)


class IntakePromptInput(TypedDict):
    audience: str
    messages_json: str
    memory_summary: str
    dossier_json: str
    source_refs_json: str
    version_ids_json: str


_IntakeModelTestPhase = Literal["before_model", "after_model_before_checkpoint"]
_IntakeModelTestHook = Callable[[_IntakeModelTestPhase], None]
_EXECUTION_METHOD_NAMES = (
    "invoke",
    "ainvoke",
    "batch",
    "abatch",
    "stream",
    "astream",
    "transform",
    "atransform",
)
_RUNNABLE_CONFIG_METHOD_NAMES = (
    "_call_with_config",
    "_acall_with_config",
    "_transform_stream_with_config",
    "_atransform_stream_with_config",
)
_PASSTHROUGH_BEHAVIOR_ATTRIBUTE_NAMES = ("func", "afunc", "input_type")
_VETTED_WRAPPER_METHOD_NAMES = _EXECUTION_METHOD_NAMES + (
    "__getattribute__",
    "_is_sealed",
    "_before_execution",
    "_after_execution",
    "_run_test_hook",
    "_require_sealed",
)


@dataclass(frozen=True, slots=True)
class _BehaviorMethodSeal:
    owner: Any
    name: str
    implementation: Any


@dataclass(frozen=True, slots=True)
class _BehaviorAttributeSeal:
    owner: Any
    name: str
    value: Any
    snapshot: Any


def _iter_runnable_nodes(runnable: Runnable) -> Iterator[Runnable]:
    yield runnable
    if type(runnable) is RunnableSequence:
        sequence = cast(RunnableSequence, runnable)
        yield from _iter_runnable_nodes(sequence.first)
        for step in sequence.middle:
            yield from _iter_runnable_nodes(step)
        yield from _iter_runnable_nodes(sequence.last)
    elif type(runnable) is RunnableParallel:
        parallel = cast(RunnableParallel, runnable)
        for step in parallel.steps__.values():
            yield from _iter_runnable_nodes(step)


def _seal_behavior_methods(
    pipeline: RunnableSequence,
    explicit_methods: tuple[tuple[Any, tuple[str, ...]], ...],
) -> tuple[_BehaviorMethodSeal, ...]:
    targets = tuple(
        (node, _EXECUTION_METHOD_NAMES + _RUNNABLE_CONFIG_METHOD_NAMES)
        for node in _iter_runnable_nodes(pipeline)
    )
    targets += explicit_methods
    seals: list[_BehaviorMethodSeal] = []
    seen: set[tuple[int, str]] = set()
    for owner, names in targets:
        for name in names:
            key = (id(owner), name)
            if key in seen:
                continue
            seen.add(key)
            try:
                implementation = getattr_static(owner, name)
            except AttributeError as error:
                raise IntakeGraphContractError("INTAKE_LCEL_COMPONENT_SEAL_INVALID") from error
            seals.append(
                _BehaviorMethodSeal(
                    owner=owner,
                    name=name,
                    implementation=implementation,
                )
            )
    return tuple(seals)


def _seal_behavior_attributes(
    pipeline: RunnableSequence,
) -> tuple[_BehaviorAttributeSeal, ...]:
    seals: list[_BehaviorAttributeSeal] = []
    for node in _iter_runnable_nodes(pipeline):
        if type(node) is not RunnablePassthrough:
            continue
        for name in _PASSTHROUGH_BEHAVIOR_ATTRIBUTE_NAMES:
            try:
                value = getattr_static(node, name)
            except AttributeError as error:
                raise IntakeGraphContractError("INTAKE_LCEL_COMPONENT_SEAL_INVALID") from error
            seals.append(
                _BehaviorAttributeSeal(
                    owner=node,
                    name=name,
                    value=value,
                    snapshot=deepcopy(value),
                )
            )
    return tuple(seals)


def _matches_behavior_methods(seals: tuple[_BehaviorMethodSeal, ...]) -> bool:
    for seal in seals:
        try:
            implementation = getattr_static(seal.owner, seal.name)
        except AttributeError:
            return False
        if implementation is not seal.implementation:
            return False
    return True


def _matches_behavior_attributes(seals: tuple[_BehaviorAttributeSeal, ...]) -> bool:
    for seal in seals:
        try:
            value = getattr_static(seal.owner, seal.name)
        except AttributeError:
            return False
        if value is not seal.value or value != seal.snapshot:
            return False
    return True


@dataclass(frozen=True, slots=True)
class _RunnableStructureSeal:
    runnable: Runnable
    sequence_middle: list[Runnable] | None
    parallel_steps: dict[str, Runnable] | None
    children: tuple[tuple[str, _RunnableStructureSeal], ...]


def _seal_runnable_structure(runnable: Runnable) -> _RunnableStructureSeal:
    if type(runnable) is RunnableSequence:
        sequence = cast(RunnableSequence, runnable)
        children = (
            ("first", _seal_runnable_structure(sequence.first)),
            *tuple(
                (f"middle:{index}", _seal_runnable_structure(step))
                for index, step in enumerate(sequence.middle)
            ),
            ("last", _seal_runnable_structure(sequence.last)),
        )
        return _RunnableStructureSeal(
            runnable=runnable,
            sequence_middle=sequence.middle,
            parallel_steps=None,
            children=children,
        )
    if type(runnable) is RunnableParallel:
        parallel = cast(RunnableParallel, runnable)
        return _RunnableStructureSeal(
            runnable=runnable,
            sequence_middle=None,
            parallel_steps=parallel.steps__,
            children=tuple(
                (key, _seal_runnable_structure(step)) for key, step in parallel.steps__.items()
            ),
        )
    return _RunnableStructureSeal(
        runnable=runnable,
        sequence_middle=None,
        parallel_steps=None,
        children=(),
    )


def _matches_runnable_structure(value: Runnable, seal: _RunnableStructureSeal) -> bool:
    if value is not seal.runnable or type(value) is not type(seal.runnable):
        return False
    if type(value) is RunnableSequence:
        sequence = cast(RunnableSequence, value)
        if sequence.middle is not seal.sequence_middle:
            return False
        expected_steps = [child.runnable for _, child in seal.children]
        current_steps = [sequence.first, *sequence.middle, sequence.last]
        return len(current_steps) == len(expected_steps) and all(
            _matches_runnable_structure(current, child)
            for current, (_, child) in zip(current_steps, seal.children)
        )
    if type(value) is RunnableParallel:
        parallel = cast(RunnableParallel, value)
        if parallel.steps__ is not seal.parallel_steps:
            return False
        if tuple(parallel.steps__) != tuple(key for key, _ in seal.children):
            return False
        return all(
            _matches_runnable_structure(parallel.steps__[key], child)
            for key, child in seal.children
        )
    return not seal.children


class _VettedIntakeModelRunnable(Runnable[IntakeGraphStateV2, dict[str, Any]]):
    def __getattribute__(self, name: str) -> Any:
        if name in _EXECUTION_METHOD_NAMES and not _is_vetted_intake_model_runnable(self):
            raise IntakeGraphContractError("INTAKE_LCEL_RUNNABLE_NOT_VETTED")
        return super().__getattribute__(name)

    def __init__(
        self,
        pipeline: RunnableSequence,
        component_seal: _IntakeComponentSeal,
        _token: object,
        name: str | None = None,
        _test_hook: _IntakeModelTestHook | None = None,
    ) -> None:
        if _token is not _VETTED_INTAKE_RUNNABLE_TOKEN:
            raise IntakeGraphContractError("INTAKE_LCEL_RUNNABLE_NOT_VETTED")
        if _test_hook is not None and not callable(_test_hook):
            raise IntakeGraphContractError("INTAKE_LCEL_TEST_HOOK_INVALID")
        self.name = name
        self._vetted_token = _token
        self._pipeline = pipeline
        self._structure_seal = _seal_runnable_structure(pipeline)
        self._component_seal = component_seal
        self._test_hook = _test_hook

    def invoke(
        self,
        input: Any,
        config: RunnableConfig | None = None,
        **kwargs: Any,
    ) -> Any:
        _VettedIntakeModelRunnable._before_execution(self)
        output = self._pipeline.invoke(input, config=config, **kwargs)
        _VettedIntakeModelRunnable._after_execution(self)
        return output

    async def ainvoke(
        self,
        input: Any,
        config: RunnableConfig | None = None,
        **kwargs: Any,
    ) -> Any:
        _VettedIntakeModelRunnable._before_execution(self)
        output: Any | None = None
        async for chunk in self._pipeline.astream(input, config=config, **kwargs):
            _VettedIntakeModelRunnable._require_sealed(self)
            output = chunk
        if output is None:
            raise IntakeGraphContractError("INTAKE_LCEL_STREAM_EMPTY")
        _VettedIntakeModelRunnable._after_execution(self)
        return output

    def batch(
        self,
        inputs: list[IntakeGraphStateV2],
        config: RunnableConfig | list[RunnableConfig] | None = None,
        *,
        return_exceptions: bool = False,
        **kwargs: Any,
    ) -> list[dict[str, Any]]:
        _VettedIntakeModelRunnable._before_execution(self)
        output = self._pipeline.batch(
            inputs,
            config=config,
            return_exceptions=return_exceptions,
            **kwargs,
        )
        _VettedIntakeModelRunnable._after_execution(self)
        return output

    async def abatch(
        self,
        inputs: list[IntakeGraphStateV2],
        config: RunnableConfig | list[RunnableConfig] | None = None,
        *,
        return_exceptions: bool = False,
        **kwargs: Any,
    ) -> list[dict[str, Any]]:
        _VettedIntakeModelRunnable._before_execution(self)
        output = await self._pipeline.abatch(
            inputs,
            config=config,
            return_exceptions=return_exceptions,
            **kwargs,
        )
        _VettedIntakeModelRunnable._after_execution(self)
        return output

    def stream(
        self,
        input: IntakeGraphStateV2,
        config: RunnableConfig | None = None,
        **kwargs: Any,
    ) -> Iterator[dict[str, Any]]:
        _VettedIntakeModelRunnable._before_execution(self)
        for chunk in self._pipeline.stream(input, config=config, **kwargs):
            _VettedIntakeModelRunnable._require_sealed(self)
            yield chunk
        _VettedIntakeModelRunnable._after_execution(self)

    async def astream(
        self,
        input: IntakeGraphStateV2,
        config: RunnableConfig | None = None,
        **kwargs: Any,
    ) -> AsyncIterator[dict[str, Any]]:
        _VettedIntakeModelRunnable._before_execution(self)
        async for chunk in self._pipeline.astream(input, config=config, **kwargs):
            _VettedIntakeModelRunnable._require_sealed(self)
            yield chunk
        _VettedIntakeModelRunnable._after_execution(self)

    def transform(
        self,
        input: Iterator[IntakeGraphStateV2],
        config: RunnableConfig | None = None,
        **kwargs: Any,
    ) -> Iterator[dict[str, Any]]:
        _VettedIntakeModelRunnable._before_execution(self)
        for chunk in self._pipeline.transform(input, config=config, **kwargs):
            _VettedIntakeModelRunnable._require_sealed(self)
            yield chunk
        _VettedIntakeModelRunnable._after_execution(self)

    async def atransform(
        self,
        input: AsyncIterator[IntakeGraphStateV2],
        config: RunnableConfig | None = None,
        **kwargs: Any,
    ) -> AsyncIterator[dict[str, Any]]:
        _VettedIntakeModelRunnable._before_execution(self)
        async for chunk in self._pipeline.atransform(input, config=config, **kwargs):
            _VettedIntakeModelRunnable._require_sealed(self)
            yield chunk
        _VettedIntakeModelRunnable._after_execution(self)

    def _is_sealed(self) -> bool:
        return _is_vetted_intake_model_runnable(self)

    def _before_execution(self) -> None:
        _VettedIntakeModelRunnable._require_sealed(self)
        _VettedIntakeModelRunnable._run_test_hook(self, "before_model")
        _VettedIntakeModelRunnable._require_sealed(self)

    def _after_execution(self) -> None:
        _VettedIntakeModelRunnable._run_test_hook(self, "after_model_before_checkpoint")
        _VettedIntakeModelRunnable._require_sealed(self)

    def _run_test_hook(self, phase: _IntakeModelTestPhase) -> None:
        if self._test_hook is not None:
            self._test_hook(phase)

    def _require_sealed(self) -> None:
        if not _is_vetted_intake_model_runnable(self):
            raise IntakeGraphContractError("INTAKE_LCEL_RUNNABLE_NOT_VETTED")


@dataclass(frozen=True, slots=True)
class _VettedIntakeRunnableRegistration:
    pipeline: RunnableSequence
    structure_seal: _RunnableStructureSeal
    component_seal: _IntakeComponentSeal
    test_hook: _IntakeModelTestHook | None
    behavior_methods: tuple[tuple[str, Any], ...]


_VETTED_INTAKE_RUNNABLES: WeakKeyDictionary[
    _VettedIntakeModelRunnable,
    _VettedIntakeRunnableRegistration,
] = WeakKeyDictionary()


def _register_vetted_intake_model_runnable(
    runnable: _VettedIntakeModelRunnable,
) -> None:
    try:
        behavior_methods = tuple(
            (name, getattr_static(runnable, name)) for name in _VETTED_WRAPPER_METHOD_NAMES
        )
    except AttributeError as error:
        raise IntakeGraphContractError("INTAKE_LCEL_COMPONENT_SEAL_INVALID") from error
    _VETTED_INTAKE_RUNNABLES[runnable] = _VettedIntakeRunnableRegistration(
        pipeline=runnable._pipeline,
        structure_seal=runnable._structure_seal,
        component_seal=runnable._component_seal,
        test_hook=runnable._test_hook,
        behavior_methods=behavior_methods,
    )


def _create_vetted_intake_model_runnable(
    pipeline: RunnableSequence,
    component_seal: _IntakeComponentSeal,
    name: str | None = None,
    test_hook: _IntakeModelTestHook | None = None,
) -> _VettedIntakeModelRunnable:
    runnable = _VettedIntakeModelRunnable(
        pipeline,
        component_seal,
        name=name,
        _token=_VETTED_INTAKE_RUNNABLE_TOKEN,
        _test_hook=test_hook,
    )
    _register_vetted_intake_model_runnable(runnable)
    return runnable


def _is_vetted_intake_model_runnable(value: Any) -> bool:
    if type(value) is not _VettedIntakeModelRunnable:
        return False
    registration = _VETTED_INTAKE_RUNNABLES.get(value)
    if registration is None:
        return False
    try:
        if (
            getattr_static(value, "_vetted_token") is not _VETTED_INTAKE_RUNNABLE_TOKEN
            or getattr_static(value, "_pipeline") is not registration.pipeline
            or getattr_static(value, "_structure_seal") is not registration.structure_seal
            or getattr_static(value, "_component_seal") is not registration.component_seal
            or getattr_static(value, "_test_hook") is not registration.test_hook
        ):
            return False
        for name, implementation in registration.behavior_methods:
            if getattr_static(value, name) is not implementation:
                return False
    except AttributeError:
        return False
    return _matches_runnable_structure(
        registration.pipeline,
        registration.structure_seal,
    ) and _matches_intake_component_seal(registration.component_seal)


def _invoke_vetted_intake_model_runnable(
    value: Any,
    input: IntakeGraphStateV2,
    config: RunnableConfig | None = None,
    **kwargs: Any,
) -> dict[str, Any]:
    if not _is_vetted_intake_model_runnable(value):
        raise IntakeGraphContractError("INTAKE_LCEL_RUNNABLE_NOT_VETTED")
    runnable = cast(_VettedIntakeModelRunnable, value)
    return cast(
        dict[str, Any],
        _VettedIntakeModelRunnable.invoke(runnable, input, config=config, **kwargs),
    )


async def _ainvoke_vetted_intake_model_runnable(
    value: Any,
    input: IntakeGraphStateV2,
    config: RunnableConfig | None = None,
    **kwargs: Any,
) -> dict[str, Any]:
    if not _is_vetted_intake_model_runnable(value):
        raise IntakeGraphContractError("INTAKE_LCEL_RUNNABLE_NOT_VETTED")
    runnable = cast(_VettedIntakeModelRunnable, value)
    return cast(
        dict[str, Any],
        await _VettedIntakeModelRunnable.ainvoke(runnable, input, config=config, **kwargs),
    )


@dataclass(frozen=True, slots=True)
class BuiltIntakeModelNode:
    lens: StateLens[IntakeGraphStateV2, IntakePromptInput]
    prompt: ChatPromptTemplate
    model: GovernedChatModel
    parser: PydanticOutputParser[IntakeCognitionDraft]
    preflight: Runnable[IntakeGraphStateV2, IntakeGraphStateV2]
    model_flow: Runnable[IntakeGraphStateV2, Mapping[str, Any]]
    guardrail: Runnable[Mapping[str, Any], Mapping[str, Any]]
    patch_projector: Runnable[Mapping[str, Any], dict[str, Any]]
    runnable: Runnable[IntakeGraphStateV2, dict[str, Any]]


class IntakeModelPreflightRunnable(Runnable[IntakeGraphStateV2, IntakeGraphStateV2]):
    def __init__(
        self,
        *,
        profile: ModelProfile,
        policy: ModelInvocationPolicy,
    ) -> None:
        self.name = "intake_lcel.preflight"
        self._profile = profile
        self._policy = policy

    def invoke(
        self,
        input: IntakeGraphStateV2,
        config: RunnableConfig | None = None,
        **kwargs: Any,
    ) -> IntakeGraphStateV2:
        if kwargs:
            raise IntakeGraphContractError("INTAKE_LCEL_OVERRIDES_FORBIDDEN")
        return self._call_with_config(
            self._validate,
            input,
            config,
            run_type="chain",
            serialized={"name": self.name, "type": "intake_model_preflight"},
        )

    async def ainvoke(
        self,
        input: IntakeGraphStateV2,
        config: RunnableConfig | None = None,
        **kwargs: Any,
    ) -> IntakeGraphStateV2:
        if kwargs:
            raise IntakeGraphContractError("INTAKE_LCEL_OVERRIDES_FORBIDDEN")

        async def apply(state: IntakeGraphStateV2) -> IntakeGraphStateV2:
            return self._validate(state)

        return await self._acall_with_config(
            apply,
            input,
            config,
            run_type="chain",
            serialized={"name": self.name, "type": "intake_model_preflight"},
        )

    def _validate(self, state: IntakeGraphStateV2) -> IntakeGraphStateV2:
        validate_state(state)
        if (
            state.get("route") != "message"
            or state.get("initial_snapshot_hash") is None
            or state.get("last_event_hash") is None
        ):
            raise IntakeGraphContractError("INTAKE_LCEL_ROUTE_INVALID")
        pins = state["version_pins"]
        expected = {
            "model_profile_id": self._profile.profile_id,
            "prompt_version": self._policy.prompt_version,
            "output_schema_version": self._policy.output_schema_version,
            "policy_version": self._policy.policy_version,
            "guardrail_version": self._policy.guardrail_version,
            "tool_policy_version": _NO_TOOLS_POLICY_VERSION,
        }
        if any(pins.get(key) != value for key, value in expected.items()):
            raise IntakeGraphContractError("INTAKE_LCEL_VERSION_PIN_MISMATCH")
        return state


class IntakeGuardrailRunnable(Runnable[Mapping[str, Any], Mapping[str, Any]]):
    def __init__(
        self,
        *,
        profile: ModelProfile,
        policy: ModelInvocationPolicy,
    ) -> None:
        self.name = "intake_lcel.guardrail"
        self._profile = profile
        self._policy = policy

    def invoke(
        self,
        input: Mapping[str, Any],
        config: RunnableConfig | None = None,
        **kwargs: Any,
    ) -> Mapping[str, Any]:
        if kwargs:
            raise IntakeGraphContractError("INTAKE_LCEL_OVERRIDES_FORBIDDEN")
        return self._call_with_config(
            self._guard,
            input,
            config,
            run_type="chain",
            serialized={"name": self.name, "type": "intake_business_guardrail"},
        )

    async def ainvoke(
        self,
        input: Mapping[str, Any],
        config: RunnableConfig | None = None,
        **kwargs: Any,
    ) -> Mapping[str, Any]:
        if kwargs:
            raise IntakeGraphContractError("INTAKE_LCEL_OVERRIDES_FORBIDDEN")

        async def apply(value: Mapping[str, Any]) -> Mapping[str, Any]:
            return self._guard(value)

        return await self._acall_with_config(
            apply,
            input,
            config,
            run_type="chain",
            serialized={"name": self.name, "type": "intake_business_guardrail"},
        )

    def _guard(self, value: Mapping[str, Any]) -> Mapping[str, Any]:
        state, message, draft = _generation_parts(value)
        _validated_model_metadata(message, profile=self._profile, policy=self._policy)
        _validate_business_output(state, draft)
        return value


class IntakePatchProjectorRunnable(Runnable[Mapping[str, Any], dict[str, Any]]):
    def __init__(
        self,
        *,
        profile: ModelProfile,
        policy: ModelInvocationPolicy,
    ) -> None:
        self.name = "intake_lcel.patch"
        self._profile = profile
        self._policy = policy

    def invoke(
        self,
        input: Mapping[str, Any],
        config: RunnableConfig | None = None,
        **kwargs: Any,
    ) -> dict[str, Any]:
        if kwargs:
            raise IntakeGraphContractError("INTAKE_LCEL_OVERRIDES_FORBIDDEN")
        return self._call_with_config(
            self._project,
            input,
            config,
            run_type="chain",
            serialized={"name": self.name, "type": "intake_state_patch_projector"},
        )

    async def ainvoke(
        self,
        input: Mapping[str, Any],
        config: RunnableConfig | None = None,
        **kwargs: Any,
    ) -> dict[str, Any]:
        if kwargs:
            raise IntakeGraphContractError("INTAKE_LCEL_OVERRIDES_FORBIDDEN")

        async def apply(value: Mapping[str, Any]) -> dict[str, Any]:
            return self._project(value)

        return await self._acall_with_config(
            apply,
            input,
            config,
            run_type="chain",
            serialized={"name": self.name, "type": "intake_state_patch_projector"},
        )

    def _project(self, value: Mapping[str, Any]) -> dict[str, Any]:
        state, message, draft = _generation_parts(value)
        draft_json = draft.model_dump(
            mode="json",
            exclude_none=True,
            exclude_unset=True,
        )
        usage = _validated_model_metadata(
            message,
            profile=self._profile,
            policy=self._policy,
        )
        output_hash = canonical_sha256(draft_json)
        patch = {
            "cognitive_revision": state["cognitive_revision"] + 1,
            "terminal_draft": draft_json,
            "execution_receipts": {
                self._policy.invocation_id: {
                    "invocation_id": self._policy.invocation_id,
                    "node_name": self._policy.node_name,
                    "output_hash": output_hash,
                }
            },
            "usage_by_invocation": {self._policy.invocation_id: usage},
        }
        return validate_cognition_patch(state, patch)


@dataclass(frozen=True, slots=True)
class _IntakeComponentSeal:
    lens: StateLens[IntakeGraphStateV2, IntakePromptInput]
    lens_name: str
    lens_source_fields: tuple[str, ...]
    lens_selector: Callable[[Mapping[str, Any]], Mapping[str, Any]]
    lens_adapter: Any
    prompt: ChatPromptTemplate
    prompt_messages: list[Any]
    prompt_messages_snapshot: list[Any]
    prompt_input_variables: tuple[str, ...]
    prompt_optional_variables: tuple[str, ...]
    prompt_input_types: dict[str, Any]
    prompt_partial_variables: dict[str, Any]
    prompt_output_parser: Any
    prompt_validate_template: bool
    model: GovernedChatModel
    model_profile: ModelProfile
    model_policy: ModelInvocationPolicy
    model_output_type: type[IntakeCognitionDraft]
    parser: PydanticOutputParser[IntakeCognitionDraft]
    parser_pydantic_object: type[IntakeCognitionDraft]
    parser_diff: bool
    preflight: IntakeModelPreflightRunnable
    guardrail: IntakeGuardrailRunnable
    patch_projector: IntakePatchProjectorRunnable
    profile: ModelProfile
    policy: ModelInvocationPolicy
    profile_snapshot: ModelProfile
    policy_snapshot: ModelInvocationPolicy
    behavior_methods: tuple[_BehaviorMethodSeal, ...]
    behavior_attributes: tuple[_BehaviorAttributeSeal, ...]
    model_transport: ModelTransport
    model_clock: Callable[[], Any]
    model_cancelled: Callable[[], bool]
    model_user_content_parts: tuple[dict[str, Any], ...]
    model_user_content_parts_snapshot: tuple[dict[str, Any], ...]
    model_visible_fields: tuple[Any, ...]
    model_visible_field_names: frozenset[str]


def _seal_intake_components(
    *,
    lens: StateLens[IntakeGraphStateV2, IntakePromptInput],
    prompt: ChatPromptTemplate,
    model: GovernedChatModel,
    parser: PydanticOutputParser[IntakeCognitionDraft],
    preflight: IntakeModelPreflightRunnable,
    guardrail: IntakeGuardrailRunnable,
    patch_projector: IntakePatchProjectorRunnable,
    profile: ModelProfile,
    policy: ModelInvocationPolicy,
    pipeline: RunnableSequence,
) -> _IntakeComponentSeal:
    explicit_methods = (
        (lens, ("_select", "_aselect")),
        (
            prompt,
            (
                "_validate_input",
                "_merge_partial_and_user_variables",
                "_format_prompt_with_error_handling",
                "_aformat_prompt_with_error_handling",
                "format_prompt",
                "aformat_prompt",
                "format_messages",
                "aformat_messages",
                "get_name",
            ),
        ),
        (preflight, ("_validate",)),
        (guardrail, ("_guard",)),
        (patch_projector, ("_project",)),
        (
            model,
            (
                "_generate",
                "_agenerate",
                "_stream",
                "_astream",
                "_generate_with_retry",
                "_agenerate_with_retry",
                "_request",
                "_sync_generate",
                "_sync_stream",
                "_sync_call",
                "_validated_result",
                "_validated_visible_delta",
                "_message",
                "_visible_chunk",
                "_final_chunk",
                "_metadata",
                "_attempts_allowed",
                "_guard",
                "_remaining_seconds",
                "_retry_possible",
                "_sync_backoff",
                "_async_backoff",
                "generate_prompt",
                "agenerate_prompt",
                "generate",
                "agenerate",
                "_generate_with_cache",
                "_agenerate_with_cache",
                "_should_stream",
                "_get_invocation_params",
                "_convert_input",
            ),
        ),
        (
            parser,
            (
                "parse",
                "aparse",
                "parse_result",
                "aparse_result",
                "_parse_obj",
                "_parser_exception",
                "_transform",
                "_atransform",
                "get_name",
            ),
        ),
        (model._transport, ("generate", "agenerate", "stream", "astream")),
    )
    return _IntakeComponentSeal(
        lens=lens,
        lens_name=lens.name,
        lens_source_fields=lens.source_fields,
        lens_selector=lens._selector,
        lens_adapter=lens._adapter,
        prompt=prompt,
        prompt_messages=prompt.messages,
        prompt_messages_snapshot=deepcopy(prompt.messages),
        prompt_input_variables=tuple(prompt.input_variables),
        prompt_optional_variables=tuple(prompt.optional_variables),
        prompt_input_types=deepcopy(prompt.input_types),
        prompt_partial_variables=deepcopy(prompt.partial_variables),
        prompt_output_parser=prompt.output_parser,
        prompt_validate_template=prompt.validate_template,
        model=model,
        model_profile=model.profile,
        model_policy=model.policy,
        model_output_type=model._output_type,
        parser=parser,
        parser_pydantic_object=parser.pydantic_object,
        parser_diff=parser.diff,
        preflight=preflight,
        guardrail=guardrail,
        patch_projector=patch_projector,
        profile=profile,
        policy=policy,
        profile_snapshot=deepcopy(profile),
        policy_snapshot=deepcopy(policy),
        behavior_methods=_seal_behavior_methods(pipeline, explicit_methods),
        behavior_attributes=_seal_behavior_attributes(pipeline),
        model_transport=model._transport,
        model_clock=model._clock,
        model_cancelled=model._cancelled,
        model_user_content_parts=model._user_content_parts,
        model_user_content_parts_snapshot=deepcopy(model._user_content_parts),
        model_visible_fields=model._visible_fields,
        model_visible_field_names=model._visible_field_names,
    )


def _matches_intake_component_seal(seal: _IntakeComponentSeal) -> bool:
    lens = seal.lens
    if (
        lens.name != seal.lens_name
        or lens.source_fields != seal.lens_source_fields
        or lens._selector is not seal.lens_selector
        or lens._adapter is not seal.lens_adapter
    ):
        return False

    prompt = seal.prompt
    if (
        prompt.messages is not seal.prompt_messages
        or prompt.messages != seal.prompt_messages_snapshot
        or tuple(prompt.input_variables) != seal.prompt_input_variables
        or tuple(prompt.optional_variables) != seal.prompt_optional_variables
        or prompt.input_types != seal.prompt_input_types
        or prompt.partial_variables != seal.prompt_partial_variables
        or prompt.output_parser is not seal.prompt_output_parser
        or prompt.validate_template is not seal.prompt_validate_template
    ):
        return False

    model = seal.model
    if (
        model.profile is not seal.model_profile
        or model.policy is not seal.model_policy
        or model._output_type is not seal.model_output_type
        or model.profile != seal.profile_snapshot
        or model.policy != seal.policy_snapshot
        or model._transport is not seal.model_transport
        or model._clock is not seal.model_clock
        or model._cancelled is not seal.model_cancelled
        or model._user_content_parts is not seal.model_user_content_parts
        or model._user_content_parts != seal.model_user_content_parts_snapshot
        or model._visible_fields is not seal.model_visible_fields
        or model._visible_field_names is not seal.model_visible_field_names
    ):
        return False

    parser = seal.parser
    if (
        parser.pydantic_object is not seal.parser_pydantic_object
        or parser.diff is not seal.parser_diff
    ):
        return False

    return (
        _matches_behavior_methods(seal.behavior_methods)
        and _matches_behavior_attributes(seal.behavior_attributes)
        and (
            seal.preflight._profile is seal.profile
            and seal.preflight._policy is seal.policy
            and seal.preflight._profile == seal.profile_snapshot
            and seal.preflight._policy == seal.policy_snapshot
            and seal.guardrail._profile is seal.profile
            and seal.guardrail._policy is seal.policy
            and seal.guardrail._profile == seal.profile_snapshot
            and seal.guardrail._policy == seal.policy_snapshot
            and seal.patch_projector._profile is seal.profile
            and seal.patch_projector._policy is seal.policy
            and seal.patch_projector._profile == seal.profile_snapshot
            and seal.patch_projector._policy == seal.policy_snapshot
        )
    )


def build_intake_model_node(
    *,
    transport: ModelTransport,
    profile: ModelProfile,
    policy: ModelInvocationPolicy,
    trusted_system_prompt: str = INTAKE_SYSTEM_PROMPT,
    _test_hook: _IntakeModelTestHook | None = None,
) -> BuiltIntakeModelNode:
    if policy.node_name != "intake_lcel":
        raise IntakeGraphContractError("INTAKE_LCEL_NODE_BINDING_INVALID")
    if system_prompt_sha256(trusted_system_prompt) != policy.trusted_system_sha256:
        raise IntakeGraphContractError("INTAKE_LCEL_SYSTEM_PROMPT_MISMATCH")
    if profile.tool_allowlist:
        raise IntakeGraphContractError("INTAKE_LCEL_TOOLS_FORBIDDEN")
    if not _IDENTIFIER.fullmatch(policy.invocation_id):
        raise IntakeGraphContractError("INTAKE_LCEL_INVOCATION_ID_INVALID")

    lens: StateLens[IntakeGraphStateV2, IntakePromptInput] = StateLens(
        name="intake_lcel.state_lens",
        source_fields=(
            "bindings",
            "version_pins",
            "messages",
            "memory_summary",
            "dossier_draft",
            "initial_snapshot_ref",
            "initial_snapshot_hash",
            "last_event_ref",
            "last_event_hash",
        ),
        selector=_select_intake_prompt,
        output_type=IntakePromptInput,
    )
    prompt = ChatPromptTemplate.from_messages(
        [
            SystemMessage(content=trusted_system_prompt),
            ("human", _HUMAN_PROMPT),
        ]
    )
    model = GovernedChatModel(
        transport=transport,
        output_type=IntakeCognitionDraft,
        profile=profile,
        policy=policy,
        visible_fields=_TARGET_INTAKE_VISIBLE_FIELDS,
    )
    parser = PydanticOutputParser(pydantic_object=IntakeCognitionDraft)
    preflight = IntakeModelPreflightRunnable(profile=profile, policy=policy)
    parsed_generation = RunnableParallel(
        message=RunnablePassthrough(),
        draft=parser,
    )
    model_flow = lens | prompt | model | parsed_generation
    state_and_generation = RunnableParallel(
        state=RunnablePassthrough(),
        generation=model_flow,
    )
    guardrail = IntakeGuardrailRunnable(profile=profile, policy=policy)
    patch_projector = IntakePatchProjectorRunnable(profile=profile, policy=policy)
    pipeline = cast(
        RunnableSequence,
        preflight | state_and_generation | guardrail | patch_projector,
    )
    component_seal = _seal_intake_components(
        lens=lens,
        prompt=prompt,
        model=model,
        parser=parser,
        preflight=preflight,
        guardrail=guardrail,
        patch_projector=patch_projector,
        profile=profile,
        policy=policy,
        pipeline=pipeline,
    )
    runnable = _create_vetted_intake_model_runnable(
        pipeline,
        component_seal,
        name="intake_lcel.vetted",
        test_hook=_test_hook,
    )
    return BuiltIntakeModelNode(
        lens=lens,
        prompt=prompt,
        model=model,
        parser=parser,
        preflight=preflight,
        model_flow=cast(Runnable[IntakeGraphStateV2, Mapping[str, Any]], model_flow),
        guardrail=guardrail,
        patch_projector=patch_projector,
        runnable=cast(Runnable[IntakeGraphStateV2, dict[str, Any]], runnable),
    )


def _select_intake_prompt(state: Mapping[str, Any]) -> Mapping[str, Any]:
    bindings = cast(Mapping[str, Any], state["bindings"])
    private = cast(Mapping[str, Any], bindings["private"])
    messages = cast(Mapping[str, Mapping[str, Any]], state["messages"])
    ordered_messages = sorted(
        messages.values(),
        key=lambda item: (cast(int, item["sequence"]), cast(str, item["message_id"])),
    )
    if len(ordered_messages) > 6:
        raise IntakeGraphContractError("INTAKE_LCEL_MESSAGE_WINDOW_TOO_LARGE")
    projected_messages = [
        {
            "message_id": message["message_id"],
            "role": message["role"],
            "audience": message["audience"],
            "sequence": message["sequence"],
            "content": message["content"],
        }
        for message in ordered_messages
    ]
    source_catalog = [
        {
            "source_ref": source_ref,
            **({"source_hash": source_hash} if source_hash is not None else {}),
        }
        for source_ref, source_hash in sorted(_source_catalog(state).items())
    ]
    pins = cast(Mapping[str, Any], state["version_pins"])
    version_ids = {
        key: pins[key]
        for key in (
            "graph_version",
            "checkpoint_schema_version",
            "state_schema_version",
            "prompt_version",
            "model_profile_id",
            "output_schema_version",
            "policy_version",
            "guardrail_version",
            "tool_policy_version",
        )
    }
    return {
        "audience": private["audience"],
        "messages_json": _canonical_text(projected_messages),
        "memory_summary": state["memory_summary"],
        "dossier_json": _canonical_text(state["dossier_draft"]),
        "source_refs_json": _canonical_text(source_catalog),
        "version_ids_json": _canonical_text(version_ids),
    }


def _generation_parts(
    value: Mapping[str, Any],
) -> tuple[IntakeGraphStateV2, AIMessage, IntakeCognitionDraft]:
    state = value.get("state")
    generation = value.get("generation")
    if not isinstance(state, dict) or not isinstance(generation, Mapping):
        raise IntakeGraphContractError("INTAKE_LCEL_GENERATION_INVALID")
    message = generation.get("message")
    draft = generation.get("draft")
    if not isinstance(message, AIMessage) or not isinstance(draft, IntakeCognitionDraft):
        raise IntakeGraphContractError("INTAKE_LCEL_GENERATION_INVALID")
    typed_state = cast(IntakeGraphStateV2, state)
    return typed_state, message, _normalize_model_matrix_fact_keys(typed_state, draft)


def _normalize_model_matrix_fact_keys(
    state: IntakeGraphStateV2,
    draft: IntakeCognitionDraft,
) -> IntakeCognitionDraft:
    """Demote model-minted formal-looking fact keys to proposal-local keys.

    A model has no authority to mint a stable ``FACT_*`` identifier.  Providers
    nevertheless sometimes use that prefix for a genuinely new unilateral fact,
    even when the prompt requires ``NEW_*``.  Preserve every FACT key that is
    already visible in the authorized dossier, but deterministically rewrite an
    unknown one to the proposal-local namespace before the normal matrix policy
    validates fingerprints, sources, membership, and summary references.  Any
    ambiguous collision remains a hard contract failure.
    """

    matrix_patch = draft.matrix_patch
    if not isinstance(matrix_patch, UnilateralCaseMatrixDraftV1):
        return draft

    existing_fact_ids = _fact_ids(state.get("dossier_draft", {}))
    proposed_keys = {row.fact_key for row in matrix_patch.fact_rows}
    replacements: dict[str, str] = {}
    for row in matrix_patch.fact_rows:
        fact_key = row.fact_key
        if not fact_key.startswith("FACT_") or fact_key in existing_fact_ids:
            continue
        replacement = f"NEW_{fact_key.removeprefix('FACT_')}"
        if replacement in proposed_keys or replacement in replacements.values():
            raise IntakeGraphContractError("INTAKE_MATRIX_FACT_ID_CONFLICT")
        replacements[fact_key] = replacement

    if not replacements:
        return draft

    normalized = draft.model_dump(mode="json", exclude_none=True, exclude_unset=True)
    normalized_patch = normalized["matrix_patch"]
    if not isinstance(normalized_patch, dict):
        raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_INVALID")
    rows = normalized_patch.get("fact_rows")
    summary_keys = normalized_patch.get("summary_source_fact_keys")
    if not isinstance(rows, list) or not isinstance(summary_keys, list):
        raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_INVALID")
    for row in rows:
        if not isinstance(row, dict):
            raise IntakeGraphContractError("INTAKE_MATRIX_PATCH_INVALID")
        fact_key = row.get("fact_key")
        if isinstance(fact_key, str) and fact_key in replacements:
            row["fact_key"] = replacements[fact_key]
    normalized_patch["summary_source_fact_keys"] = [
        replacements.get(fact_key, fact_key) for fact_key in summary_keys
    ]
    return IntakeCognitionDraft.model_validate(normalized)


def _validated_model_metadata(
    message: AIMessage,
    *,
    profile: ModelProfile,
    policy: ModelInvocationPolicy,
) -> dict[str, int]:
    metadata = message.response_metadata
    if not isinstance(metadata, dict):
        raise IntakeGraphContractError("INTAKE_LCEL_MODEL_METADATA_INVALID")
    expected = {
        "model_profile_id": profile.profile_id,
        "model": profile.model,
        "prompt_version": policy.prompt_version,
        "output_schema_version": policy.output_schema_version,
        "policy_version": policy.policy_version,
        "guardrail_version": policy.guardrail_version,
        "tool_allowlist": [],
    }
    if any(metadata.get(key) != expected_value for key, expected_value in expected.items()):
        raise IntakeGraphContractError("INTAKE_LCEL_MODEL_METADATA_INVALID")
    usage = metadata.get("token_usage")
    if not isinstance(usage, dict) or set(usage) != {"input", "output", "total"}:
        raise IntakeGraphContractError("INTAKE_LCEL_USAGE_INVALID")
    values = tuple(usage[key] for key in ("input", "output", "total"))
    if any(isinstance(item, bool) or not isinstance(item, int) or item < 0 for item in values):
        raise IntakeGraphContractError("INTAKE_LCEL_USAGE_INVALID")
    input_tokens, output_tokens, total_tokens = cast(tuple[int, int, int], values)
    if total_tokens < input_tokens + output_tokens:
        raise IntakeGraphContractError("INTAKE_LCEL_USAGE_INVALID")
    return {
        "input_tokens": input_tokens,
        "output_tokens": output_tokens,
        "total_tokens": total_tokens,
    }


def _validate_business_output(
    state: IntakeGraphStateV2,
    draft: IntakeCognitionDraft,
) -> None:
    output = draft.model_dump(mode="json", exclude_none=True, exclude_unset=True)
    _validate_no_evidence_collection(draft, output)
    catalog = _source_catalog(state)
    existing_fact_ids = _fact_ids(state["dossier_draft"])
    _validate_output_tree(
        output,
        audience=state["bindings"]["private"]["audience"],
        source_catalog=catalog,
        existing_fact_ids=existing_fact_ids,
        inherited_refs=frozenset(),
    )
    validate_matrix_patch(state, output.get("matrix_patch"))
    patch = output.get("dossier_patch", {})
    if not isinstance(patch, dict):
        raise IntakeGraphContractError("INTAKE_LCEL_DOSSIER_PATCH_INVALID")
    merged = _merge_object(state["dossier_draft"], patch)
    validate_dossier_transition(state["dossier_draft"], merged)
    validate_dossier_transition({}, patch)

    readiness = draft.readiness
    recommendation = draft.recommendation
    if (readiness == "READY_TO_CONFIRM") != (recommendation == "ACCEPTED") or (
        recommendation == "NOT_ADMISSIBLE" and readiness != "NEEDS_REVIEW"
    ):
        raise IntakeGraphContractError("INTAKE_LCEL_READINESS_PRECONDITION_FAILED")


def _validate_no_evidence_collection(
    draft: IntakeCognitionDraft,
    output: Mapping[str, Any],
) -> None:
    if _contains_forbidden_evidence_request(draft.room_utterance):
        raise IntakeGraphContractError("INTAKE_EVIDENCE_REQUEST_FORBIDDEN")
    if any(_is_evidence_material_gap(field) for field in draft.missing_fields):
        raise IntakeGraphContractError("INTAKE_EVIDENCE_MISSING_FIELD_FORBIDDEN")
    dossier_patch = output.get("dossier_patch")
    if isinstance(dossier_patch, Mapping):
        for text in _nested_strings(dossier_patch):
            if _contains_forbidden_evidence_request(text):
                raise IntakeGraphContractError("INTAKE_EVIDENCE_REQUEST_FORBIDDEN")
        missing_information = dossier_patch.get("missing_information")
        if isinstance(missing_information, Mapping) and any(
            _is_evidence_material_gap(text) for text in _nested_strings(missing_information)
        ):
            raise IntakeGraphContractError("INTAKE_EVIDENCE_MISSING_FIELD_FORBIDDEN")


def _is_evidence_material_gap(value: str) -> bool:
    normalized = value.strip().replace("-", "_").replace(" ", "_")
    return bool(_EVIDENCE_MATERIAL_IDENTIFIER.search(normalized)) or any(
        term in value for term in _EVIDENCE_MATERIAL_TEXT
    )


def _contains_forbidden_evidence_request(value: str) -> bool:
    if _EVIDENCE_REQUEST_ZH.search(value) or _EVIDENCE_REQUEST_EN.search(value):
        return True
    return ("?" in value or "？" in value) and _is_evidence_material_gap(value)


def _nested_strings(value: Any) -> Iterator[str]:
    if isinstance(value, str):
        yield value
    elif isinstance(value, Mapping):
        for child in value.values():
            yield from _nested_strings(child)
    elif isinstance(value, list | tuple):
        for child in value:
            yield from _nested_strings(child)


def _validate_output_tree(
    value: Any,
    *,
    audience: str,
    source_catalog: Mapping[str, str | None],
    existing_fact_ids: frozenset[str],
    inherited_refs: frozenset[str],
) -> None:
    if isinstance(value, Mapping):
        keys = set(value)
        if keys & (MODEL_CONTROLLED_FORBIDDEN_FIELDS | _INTERNAL_OUTPUT_FIELDS):
            raise IntakeGraphContractError("INTAKE_LCEL_INTERNAL_FIELD_FORBIDDEN")
        if "audience" in value and value["audience"] != audience:
            raise IntakeGraphContractError("INTAKE_LCEL_ACTOR_ISOLATION_VIOLATION")
        local_refs = _output_source_refs(value)
        for source_ref in local_refs:
            if source_ref not in source_catalog:
                raise IntakeGraphContractError("INTAKE_LCEL_SOURCE_REF_UNAUTHORIZED")
        effective_refs = inherited_refs | frozenset(local_refs)
        hashes = [value[key] for key in ("source_hash", "sha256", "content_hash") if key in value]
        allowed_hashes = {
            source_catalog[source_ref]
            for source_ref in effective_refs
            if source_catalog.get(source_ref) is not None
        }
        all_hashes = {item for item in source_catalog.values() if item is not None}
        for source_hash in hashes:
            if (
                not isinstance(source_hash, str)
                or not _SHA256.fullmatch(source_hash)
                or source_hash not in (allowed_hashes or all_hashes)
            ):
                raise IntakeGraphContractError("INTAKE_LCEL_SOURCE_HASH_MISMATCH")
        fact_id = value.get("fact_id")
        if fact_id is not None:
            if not isinstance(fact_id, str) or not _IDENTIFIER.fullmatch(fact_id):
                raise IntakeGraphContractError("INTAKE_LCEL_FACT_ID_INVALID")
            if fact_id not in existing_fact_ids and not effective_refs:
                raise IntakeGraphContractError("INTAKE_LCEL_FACT_SOURCE_REQUIRED")
        for child in value.values():
            _validate_output_tree(
                child,
                audience=audience,
                source_catalog=source_catalog,
                existing_fact_ids=existing_fact_ids,
                inherited_refs=effective_refs,
            )
    elif isinstance(value, list | tuple):
        for child in value:
            _validate_output_tree(
                child,
                audience=audience,
                source_catalog=source_catalog,
                existing_fact_ids=existing_fact_ids,
                inherited_refs=inherited_refs,
            )


def _output_source_refs(value: Mapping[str, Any]) -> set[str]:
    refs: set[str] = set()
    for key in ("source_id", "source_ref", "message_id"):
        candidate = value.get(key)
        if isinstance(candidate, str):
            refs.add(candidate)
    for key in ("source_refs", "source_ids"):
        candidates = value.get(key)
        if isinstance(candidates, list | tuple):
            if not all(isinstance(item, str) for item in candidates):
                raise IntakeGraphContractError("INTAKE_LCEL_SOURCE_REF_INVALID")
            refs.update(cast(list[str] | tuple[str, ...], candidates))
    return refs


def _source_catalog(state: Mapping[str, Any]) -> dict[str, str | None]:
    catalog: dict[str, str | None] = {}

    def register(source_ref: Any, source_hash: Any = None) -> None:
        if not isinstance(source_ref, str) or not _IDENTIFIER.fullmatch(source_ref):
            raise IntakeGraphContractError("INTAKE_LCEL_SOURCE_CATALOG_INVALID")
        if source_hash is not None and (
            not isinstance(source_hash, str) or not _SHA256.fullmatch(source_hash)
        ):
            raise IntakeGraphContractError("INTAKE_LCEL_SOURCE_CATALOG_INVALID")
        existing = catalog.get(source_ref)
        if existing is not None and source_hash is not None and existing != source_hash:
            raise IntakeGraphContractError("INTAKE_LCEL_SOURCE_CATALOG_CONFLICT")
        if source_ref not in catalog or source_hash is not None:
            catalog[source_ref] = source_hash

    register(state.get("initial_snapshot_ref"), state.get("initial_snapshot_hash"))
    if state.get("last_event_ref") is not None:
        register(state.get("last_event_ref"), state.get("last_event_hash"))
    messages = state.get("messages")
    if not isinstance(messages, Mapping):
        raise IntakeGraphContractError("INTAKE_LCEL_SOURCE_CATALOG_INVALID")
    for message in messages.values():
        if not isinstance(message, Mapping):
            raise IntakeGraphContractError("INTAKE_LCEL_SOURCE_CATALOG_INVALID")
        register(message.get("message_id"), message.get("source_hash"))

    def visit(candidate: Any) -> None:
        if isinstance(candidate, Mapping):
            refs = _output_source_refs(candidate)
            bound_hash = next(
                (
                    candidate[key]
                    for key in ("source_hash", "sha256", "content_hash")
                    if isinstance(candidate.get(key), str)
                ),
                None,
            )
            for source_ref in refs:
                register(source_ref, bound_hash if len(refs) == 1 else None)
            for child in candidate.values():
                visit(child)
        elif isinstance(candidate, list | tuple):
            for child in candidate:
                visit(child)

    visit(state.get("dossier_draft", {}))
    return catalog


def _fact_ids(value: Any) -> frozenset[str]:
    ids: set[str] = set()

    def visit(candidate: Any) -> None:
        if isinstance(candidate, Mapping):
            fact_id = candidate.get("fact_id")
            if isinstance(fact_id, str):
                ids.add(fact_id)
            for child in candidate.values():
                visit(child)
        elif isinstance(candidate, list | tuple):
            for child in candidate:
                visit(child)

    visit(value)
    return frozenset(ids)


def _merge_object(left: Mapping[str, Any], right: Mapping[str, Any]) -> dict[str, Any]:
    merged = deepcopy(dict(left))
    for key in sorted(right):
        incoming = right[key]
        existing = merged.get(key)
        if isinstance(existing, dict) and isinstance(incoming, Mapping):
            merged[key] = _merge_object(existing, incoming)
        else:
            merged[key] = deepcopy(incoming)
    return merged


def _canonical_text(value: Any) -> str:
    try:
        return canonicalize(value).decode("utf-8")
    except (TypeError, ValueError, UnicodeDecodeError) as error:
        raise IntakeGraphContractError("INTAKE_LCEL_PROMPT_VALUE_INVALID") from error


__all__ = [
    "BuiltIntakeModelNode",
    "INTAKE_SYSTEM_PROMPT",
    "IntakePromptInput",
    "build_intake_model_node",
]
