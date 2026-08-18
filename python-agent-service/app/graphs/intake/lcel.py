from __future__ import annotations

import hashlib
import json
import re
from collections.abc import AsyncIterator, Callable, Iterator, Mapping
from copy import deepcopy
from dataclasses import dataclass
from inspect import getattr_static
from typing import Any, Literal, cast
from weakref import WeakKeyDictionary

from langchain_core.messages import AIMessage, AIMessageChunk, message_chunk_to_message
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
from app.agents.dispute_intake_officer.case_fact_matrix import (
    case_fact_matrix_content_hash,
    finalize_case_fact_matrix,
    respondent_opening_carry_delta,
)
from app.graph_runtime.state_lens import StateLens
from app.agents.dispute_intake_officer.schemas import (
    IntakeCaseDetailLlmOutput,
    IntakeFreshFormOpeningLlmOutput,
    IntakeInitiatorRoomLlmOutputV3,
    IntakeRespondentRoomLlmOutputV3,
    IntakeRemarkAcknowledgementLlmOutput,
    IntakeRespondentOpeningLlmOutput,
    intake_case_detail_output_type,
    materialize_intake_case_detail_output,
)
from app.agents.dispute_intake_officer.skills.dossier.dossier_skill import (
    DIRECT_RESPONDENT_CONFIDENCE,
    DIRECT_RESPONDENT_SOURCE,
    RESPONDENT_AUTHORED_CURRENT_MESSAGE,
    SUBJECTIVE_RESPONDENT_CONFIDENCE_NOTE,
    SUBJECTIVE_RESPONDENT_SOURCE,
    _reported_attitude_position,
    attributed_reported_respondent_attitude,
    detect_direct_respondent_attitude,
)
from app.harness.prompt_composer import PromptComposer
from app.graphs.intake.baseline import (
    BASELINE_INTAKE_NODE_NAME,
    adapt_intake_baseline_output,
    build_intake_baseline_request,
    intake_baseline_authorized_fact_ids,
    intake_request_actor_is_exactly_not_ready,
    normalize_model_matrix_fact_key_payload,
    prepare_intake_baseline_invocation,
)
from app.graphs.intake.contracts import (
    CaseFactMatrixDeltaV2,
    MODEL_CONTROLLED_FORBIDDEN_FIELDS,
    IntakeCognitionDraft,
    UnilateralCaseMatrixDraftV1,
)
from app.graphs.intake.errors import IntakeGraphContractError
from app.graphs.intake.state import IntakeGraphStateV2, merge_intake_dossier
from app.graphs.intake.validators import (
    MATRIX_AUTHORITY_RECORD_KEY,
    build_baseline_pending_case_detail,
    next_intake_cognitive_revision,
    rebind_matrix_successor_handoff_partition,
    rebind_respondent_opening_handoff_partition,
    unwrap_verified_baseline_previous_case_detail,
    validated_respondent_opening_frozen_context,
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
from app.schemas.case_fact_matrix import (
    CaseFactMatrixDeltaV2 as FormalCaseFactMatrixDeltaV2,
    CaseFactMatrixV2 as FormalCaseFactMatrixV2,
)
from app.schemas.final_agents import IntakeTurnRequest
from app.schemas.intake_case_matrix import (
    UnilateralCaseMatrixDraftV1 as FormalUnilateralCaseMatrixDraftV1,
)
from app.harness.context_window import ContextWindowManager
from app.harness.invocation_context import AgentInvocationContext
from app.harness.prompt_composer import PromptRepository
from app.llm import AgentOutputSchemaError
from app.streaming import TARGET_INTAKE_REPLY_FIRST_VISIBLE_FIELDS


# Backward-compatible generic constant for static diagnostics only.  Production
# runtime messages include the exact trusted Agent context and role profile and are
# prepared by ``prepare_intake_baseline_invocation`` below.
INTAKE_SYSTEM_PROMPT = PromptComposer().render_system_prompt(BASELINE_INTAKE_NODE_NAME)

_TARGET_INTAKE_VISIBLE_FIELDS = TARGET_INTAKE_REPLY_FIRST_VISIBLE_FIELDS
_FRESH_FORM_OPENING_VISIBLE_FIELDS = tuple(
    spec
    for spec in _TARGET_INTAKE_VISIBLE_FIELDS
    if spec.field
    in {
        "room_utterance",
        "ordered_sections",
        "case_detail.case_story.one_sentence_summary",
        "case_detail.case_story",
    }
)
_RESPONDENT_OPENING_VISIBLE_FIELDS = tuple(
    spec for spec in _TARGET_INTAKE_VISIBLE_FIELDS if spec.field == "room_utterance"
)
_PARTY_INTAKE_GOVERNANCE_FIELDS = (
    "intake_quality",
    "missing_information",
    "handoff_notes",
    "admission",
    "party_intake_state",
)
_IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_NO_TOOLS_POLICY_VERSION = "no-tools.v1"
INTAKE_ACTION_GATE_KEY_PREFIX = "intake-action-gate:v1:"
INTAKE_ACTION_GATE_SCHEMA_VERSION = "intake-action-gate.v1"
INTAKE_ACTION_GATE_KIND = "INTAKE_ACTION_GATE"
_INTAKE_CONVERSATION_ACTIONS = frozenset(
    {
        "ASK_SUBSTANTIVE",
        "INVITE_OPTIONAL_REMARK",
        "ACK_REMARK",
        "ACK_NO_REMARK",
    }
)
INTAKE_ACTION_GATE_ACTION_STATUSES = {
    "ASK_SUBSTANTIVE": frozenset({"NOT_READY"}),
    "INVITE_OPTIONAL_REMARK": frozenset({"WAITING_FOR_REMARK"}),
    "ACK_REMARK": frozenset({"HAS_REMARKS"}),
    # A participant may say "no further remarks" after one or more remarks.
    # That Agent turn is acknowledged while the existing HAS_REMARKS authority
    # remains unchanged and append-only.
    "ACK_NO_REMARK": frozenset({"NO_EXTRA_REMARKS", "HAS_REMARKS"}),
}
_ABSENT_RESPONDENT_ATTITUDES = frozenset(
    {"UNKNOWN", "PLATFORM_UNKNOWN", "NOT_RESPONDED", "NOT_ADDRESSED"}
)
_SUBSTANTIVE_RESPONDENT_ATTITUDES = frozenset(
    {
        "AGREE",
        "PARTIALLY_AGREE",
        "DISAGREE",
        "ALTERNATIVE_PROPOSED",
        "NEED_MORE_INFO",
    }
)


def _respondent_attitude_discriminator(attitude: Mapping[str, Any]) -> str | None:
    """Return the sole attitude discriminator, rejecting ambiguous envelopes."""

    has_attitude = "attitude" in attitude
    has_status = "status" in attitude
    if has_attitude == has_status:
        return None
    proposed = attitude["attitude"] if has_attitude else attitude["status"]
    return proposed if isinstance(proposed, str) and proposed else None


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
    system_prompt: str
    human_prompt: str


class _OptionalBaselineContextStateLens(StateLens[IntakeGraphStateV2, IntakePromptInput]):
    """Expose the private baseline context without breaking older checkpoints."""

    def _select(self, state: IntakeGraphStateV2) -> IntakePromptInput:
        if "baseline_previous_case_detail" in state and "result_json" in state:
            return super()._select(state)
        # StateLens deliberately fails closed for undeclared missing fields.  The
        # context field is NotRequired for historical checkpoints, however, so
        # provide a non-durable null sentinel only to this prompt projection.
        # ``_previous_case_detail`` falls back to the legacy public dossier for
        # that sentinel; it is never persisted or exposed in the prompt output.
        scoped_state = dict(state)
        scoped_state.setdefault("baseline_previous_case_detail", None)
        # Historical/opening states legitimately predate a terminal proposal.
        # Keep this prompt-only sentinel outside durable graph state; an actual
        # envelope still requires a real, matching committed result on unwrap.
        scoped_state.setdefault("result_json", None)
        return super()._select(cast(IntakeGraphStateV2, scoped_state))


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


class IntakeRouteModelRunnable(
    Runnable[IntakeGraphStateV2, Mapping[str, Any]]
):
    """Select a sealed provider-output contract from validated route authority."""

    def __init__(
        self,
        *,
        agent_context: AgentInvocationContext,
        respondent_substantive_lens: StateLens[
            IntakeGraphStateV2, IntakePromptInput
        ],
        respondent_substantive_prompt: ChatPromptTemplate,
        respondent_substantive_model: GovernedChatModel,
        default_flow: Runnable[IntakeGraphStateV2, Mapping[str, Any]],
        fresh_form_opening_flow: Runnable[IntakeGraphStateV2, Mapping[str, Any]],
        remark_acknowledgement_flow: Runnable[
            IntakeGraphStateV2, Mapping[str, Any]
        ],
        respondent_substantive_flow: Runnable[
            IntakeGraphStateV2, Mapping[str, Any]
        ],
        respondent_opening_flow: Runnable[IntakeGraphStateV2, Mapping[str, Any]],
    ) -> None:
        self.name = "intake_lcel.route_model"
        self._agent_context = agent_context
        self._respondent_substantive_lens = respondent_substantive_lens
        self._respondent_substantive_prompt = respondent_substantive_prompt
        self._respondent_substantive_model = respondent_substantive_model
        self._default_flow = default_flow
        self._fresh_form_opening_flow = fresh_form_opening_flow
        self._remark_acknowledgement_flow = remark_acknowledgement_flow
        self._respondent_substantive_flow = respondent_substantive_flow
        self._respondent_opening_flow = respondent_opening_flow

    def invoke(
        self,
        input: IntakeGraphStateV2,
        config: RunnableConfig | None = None,
        **kwargs: Any,
    ) -> Mapping[str, Any]:
        if kwargs:
            raise IntakeGraphContractError("INTAKE_LCEL_OVERRIDES_FORBIDDEN")
        return self._select_flow(input).invoke(input, config=config)

    async def ainvoke(
        self,
        input: IntakeGraphStateV2,
        config: RunnableConfig | None = None,
        **kwargs: Any,
    ) -> Mapping[str, Any]:
        if kwargs:
            raise IntakeGraphContractError("INTAKE_LCEL_OVERRIDES_FORBIDDEN")
        return await self._select_flow(input).ainvoke(input, config=config)

    def stream(
        self,
        input: IntakeGraphStateV2,
        config: RunnableConfig | None = None,
        **kwargs: Any,
    ) -> Iterator[Mapping[str, Any]]:
        if kwargs:
            raise IntakeGraphContractError("INTAKE_LCEL_OVERRIDES_FORBIDDEN")
        yield from self._select_flow(input).stream(input, config=config)

    async def astream(
        self,
        input: IntakeGraphStateV2,
        config: RunnableConfig | None = None,
        **kwargs: Any,
    ) -> AsyncIterator[Mapping[str, Any]]:
        if kwargs:
            raise IntakeGraphContractError("INTAKE_LCEL_OVERRIDES_FORBIDDEN")
        async for chunk in self._select_flow(input).astream(input, config=config):
            yield chunk

    def _select_flow(
        self,
        state: IntakeGraphStateV2,
    ) -> Runnable[IntakeGraphStateV2, Mapping[str, Any]]:
        route = state.get("route")
        if route == "respondent_opening":
            validated_respondent_opening_frozen_context(state)
            return self._respondent_opening_flow
        if route in {"initialize", "message"}:
            request = build_intake_baseline_request(
                state,
                agent_context=self._agent_context,
            )
            output_type = intake_case_detail_output_type(request)
            if (
                output_type is IntakeInitiatorRoomLlmOutputV3
                and request.initial_case_facts is not None
                and request.current_user_message is None
            ):
                return self._fresh_form_opening_flow
            if output_type is IntakeRemarkAcknowledgementLlmOutput:
                return self._remark_acknowledgement_flow
            if issubclass(output_type, IntakeRespondentRoomLlmOutputV3):
                return self._respondent_substantive_flow_for(output_type)
            return self._default_flow
        raise IntakeGraphContractError("INTAKE_LCEL_ROUTE_INVALID")

    def _respondent_substantive_flow_for(
        self,
        output_type: type[IntakeRespondentRoomLlmOutputV3],
    ) -> Runnable[IntakeGraphStateV2, Mapping[str, Any]]:
        """Bind the respondent flow to this turn's frozen cross-party claim schema.

        Frozen claim values are request authority, so their Pydantic model is built per
        exact request.  A shared model instance cannot be mutated safely because two
        respondent turns may run concurrently with different claim literals.
        """

        if output_type is IntakeRespondentRoomLlmOutputV3:
            return self._respondent_substantive_flow
        template = self._respondent_substantive_model
        dynamic_model = GovernedChatModel(
            transport=template._transport,
            output_type=output_type,
            profile=template.profile,
            policy=template.policy,
            visible_fields=template._visible_fields,
            user_content_parts=template._user_content_parts,
            cancellation_probe=template._cancelled,
            clock=template._clock,
        )
        dynamic_parser = PydanticOutputParser(pydantic_object=output_type)
        parsed_generation = RunnableParallel(
            message=RunnablePassthrough(),
            draft=dynamic_parser,
        )
        return (
            self._respondent_substantive_lens
            | self._respondent_substantive_prompt
            | dynamic_model
            | parsed_generation
        )


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
    elif type(runnable) is IntakeRouteModelRunnable:
        route_model = cast(IntakeRouteModelRunnable, runnable)
        yield from _iter_runnable_nodes(route_model._default_flow)
        yield from _iter_runnable_nodes(route_model._fresh_form_opening_flow)
        yield from _iter_runnable_nodes(route_model._remark_acknowledgement_flow)
        yield from _iter_runnable_nodes(route_model._respondent_substantive_flow)
        yield from _iter_runnable_nodes(route_model._respondent_opening_flow)


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
    if type(runnable) is IntakeRouteModelRunnable:
        route_model = cast(IntakeRouteModelRunnable, runnable)
        return _RunnableStructureSeal(
            runnable=runnable,
            sequence_middle=None,
            parallel_steps=None,
            children=(
                ("default_flow", _seal_runnable_structure(route_model._default_flow)),
                (
                    "fresh_form_opening_flow",
                    _seal_runnable_structure(route_model._fresh_form_opening_flow),
                ),
                (
                    "remark_acknowledgement_flow",
                    _seal_runnable_structure(
                        route_model._remark_acknowledgement_flow
                    ),
                ),
                (
                    "respondent_substantive_flow",
                    _seal_runnable_structure(
                        route_model._respondent_substantive_flow
                    ),
                ),
                (
                    "respondent_opening_flow",
                    _seal_runnable_structure(route_model._respondent_opening_flow),
                ),
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
    if type(value) is IntakeRouteModelRunnable:
        route_model = cast(IntakeRouteModelRunnable, value)
        current_flows = {
            "default_flow": route_model._default_flow,
            "fresh_form_opening_flow": route_model._fresh_form_opening_flow,
            "remark_acknowledgement_flow": (
                route_model._remark_acknowledgement_flow
            ),
            "respondent_substantive_flow": (
                route_model._respondent_substantive_flow
            ),
            "respondent_opening_flow": route_model._respondent_opening_flow,
        }
        return tuple(current_flows) == tuple(key for key, _ in seal.children) and all(
            _matches_runnable_structure(current_flows[key], child)
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
            if output is None:
                output = chunk
                continue
            try:
                output = output + chunk
            except TypeError as error:
                raise IntakeGraphContractError(
                    "INTAKE_LCEL_STREAM_ACCUMULATION_INVALID"
                ) from error
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
    parser: PydanticOutputParser[IntakeInitiatorRoomLlmOutputV3]
    fresh_form_opening_model: GovernedChatModel
    fresh_form_opening_parser: PydanticOutputParser[
        IntakeInitiatorRoomLlmOutputV3
    ]
    remark_acknowledgement_model: GovernedChatModel
    remark_acknowledgement_parser: PydanticOutputParser[
        IntakeRemarkAcknowledgementLlmOutput
    ]
    respondent_substantive_model: GovernedChatModel
    respondent_substantive_parser: PydanticOutputParser[
        IntakeRespondentRoomLlmOutputV3
    ]
    respondent_opening_model: GovernedChatModel
    respondent_opening_parser: PydanticOutputParser[IntakeRespondentOpeningLlmOutput]
    preflight: Runnable[IntakeGraphStateV2, IntakeGraphStateV2]
    model_router: IntakeRouteModelRunnable
    model_flow: RunnableSequence
    routed_model_flow: Runnable[IntakeGraphStateV2, Mapping[str, Any]]
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
        route = state.get("route")
        has_snapshot = state.get("initial_snapshot_hash") is not None
        has_event = state.get("last_event_hash") is not None
        # A snapshot-only opening has no participant event cursor.  Its durable
        # source identity is the imported snapshot hash (also used by the
        # response-message ID), while all subsequent model turns remain bound
        # to a participant event.
        if not (
            has_snapshot
            and (
                (route == "initialize" and not has_event)
                or (route in {"message", "respondent_opening"} and has_event)
            )
        ):
            raise IntakeGraphContractError("INTAKE_LCEL_ROUTE_INVALID")
        # An imported formal M0 can authorize only an actual participant room
        # statement.  The SNAPSHOT and BOOTSTRAP INITIAL_FORM openings have no
        # current HUMAN message, so fail before the lens/prompt/model boundary
        # rather than silently deriving a successor from form-only context.
        formal_without_current_human = (
            _opening_imported_formal_matrix_without_current_party_message(state)
        )
        if route == "respondent_opening":
            validated_respondent_opening_frozen_context(state)
            if not formal_without_current_human:
                raise IntakeGraphContractError("INTAKE_RESPONDENT_OPENING_AUTHORITY_INVALID")
        elif formal_without_current_human:
            raise IntakeGraphContractError("INTAKE_BASELINE_OPENING_FORMAL_MATRIX_UNSUPPORTED")
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
        if route == "initialize":
            # StateLens deliberately scopes both event fields so a regular turn
            # can bind its current participant message by hash.  They are
            # optional at the durable-state boundary, however, so supply only
            # pipeline-local nulls for the snapshot opening rather than
            # mutating the graph state or inventing a participant message.
            return cast(
                IntakeGraphStateV2,
                {
                    **state,
                    "last_event_ref": None,
                    "last_event_hash": None,
                },
            )
        return state


def _opening_imported_formal_matrix_without_current_party_message(
    state: Mapping[str, Any],
) -> bool:
    """Whether this form-only opening carries an imported formal M0 authority."""

    dossier = state.get("dossier_draft")
    if not isinstance(dossier, Mapping) or not isinstance(dossier.get("case_fact_matrix"), Mapping):
        return False
    messages = state.get("messages")
    if not isinstance(messages, Mapping):
        return False
    event_hash = state.get("last_event_hash")
    return not any(
        isinstance(message, Mapping)
        and message.get("role") == "HUMAN"
        and message.get("source_hash") == event_hash
        for message in messages.values()
    )


class IntakeGuardrailRunnable(Runnable[Mapping[str, Any], Mapping[str, Any]]):
    def __init__(
        self,
        *,
        profile: ModelProfile,
        policy: ModelInvocationPolicy,
        agent_context: AgentInvocationContext,
    ) -> None:
        self.name = "intake_lcel.guardrail"
        self._profile = profile
        self._policy = policy
        self._agent_context = agent_context

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
        state, message, draft = _generation_parts(
            value,
            agent_context=self._agent_context,
        )
        _validated_model_metadata(message, profile=self._profile, policy=self._policy)
        _validate_business_output(
            state,
            draft,
            agent_context=self._agent_context,
            room_utterance_is_baseline_finalized=True,
        )
        return value


class IntakePatchProjectorRunnable(Runnable[Mapping[str, Any], dict[str, Any]]):
    def __init__(
        self,
        *,
        profile: ModelProfile,
        policy: ModelInvocationPolicy,
        agent_context: AgentInvocationContext,
    ) -> None:
        self.name = "intake_lcel.patch"
        self._profile = profile
        self._policy = policy
        self._agent_context = agent_context

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
        (
            state,
            message,
            draft,
            baseline_formal_matrix,
            materialized_public_dossier,
            matrix_derivation_request_base,
        ) = _generation_parts_with_baseline_context(
            value,
            agent_context=self._agent_context,
        )
        draft_json = draft.model_dump(
            mode="json",
            exclude_none=True,
            exclude_unset=True,
        )
        output_hash = canonical_sha256(draft_json)
        action_gate = (
            None
            if state.get("route") == "respondent_opening"
            else _intake_action_gate(state, draft)
        )
        baseline_pending_case_detail = build_baseline_pending_case_detail(
            state,
            terminal_draft=draft_json,
            formal_matrix=baseline_formal_matrix,
            public_dossier=materialized_public_dossier,
            matrix_derivation_request_base=matrix_derivation_request_base,
            execution_receipt_invocation_id=self._policy.invocation_id,
            execution_receipt_node_name=self._policy.node_name,
            execution_receipt_output_hash=output_hash,
        )
        usage = _validated_model_metadata(
            message,
            profile=self._profile,
            policy=self._policy,
        )
        response_message_id = _intake_response_message_id(state, output_hash)
        patch = {
            "cognitive_revision": next_intake_cognitive_revision(state),
            "terminal_draft": draft_json,
            "baseline_pending_case_detail": baseline_pending_case_detail,
            "messages": {
                response_message_id: {
                    "message_id": response_message_id,
                    "role": "AI",
                    "audience": state["bindings"]["private"]["audience"],
                    "content": draft.room_utterance,
                    "sequence": state.get("last_event_sequence", 0),
                    "source_hash": output_hash,
                }
            },
            "execution_receipts": {
                self._policy.invocation_id: {
                    "invocation_id": self._policy.invocation_id,
                    "node_name": self._policy.node_name,
                    "output_hash": output_hash,
                }
            },
            "usage_by_invocation": {self._policy.invocation_id: usage},
        }
        validated = validate_cognition_patch(
            state,
            patch,
            require_baseline_pending_context=True,
        )
        if action_gate is not None:
            validated["node_results"] = {
                INTAKE_ACTION_GATE_KEY_PREFIX + action_gate["source_turn_hash"]: action_gate
            }
        return validated


def _intake_response_message_id(
    state: Mapping[str, Any],
    output_hash: str,
) -> str:
    """Return a retry-stable ID that cannot collide across distinct turns."""

    source_turn_hash = state.get("last_event_hash") or state.get("initial_snapshot_hash")
    if (
        not isinstance(source_turn_hash, str)
        or not _SHA256.fullmatch(source_turn_hash)
        or not _SHA256.fullmatch(output_hash)
    ):
        raise IntakeGraphContractError("INTAKE_LCEL_SOURCE_TURN_INVALID")
    return (
        "INTAKE_AI_"
        + canonical_sha256(
            {
                "source_turn_hash": source_turn_hash,
                "output_hash": output_hash,
            }
        )[:32]
    )


def _intake_action_gate(
    state: Mapping[str, Any],
    draft: IntakeCognitionDraft,
) -> dict[str, Any]:
    """Bind the reducer-approved visible reply to this exact authorized turn."""

    source_turn_hash = state.get("last_event_hash") or state.get(
        "initial_snapshot_hash"
    )
    action = _conversation_action(draft)
    reducer_status = _actor_remark_status(state, draft)
    if (
        not isinstance(source_turn_hash, str)
        or _SHA256.fullmatch(source_turn_hash) is None
        or reducer_status not in INTAKE_ACTION_GATE_ACTION_STATUSES[action]
    ):
        raise IntakeGraphContractError("INTAKE_ACTION_GATE_BINDING_INVALID")
    room_sha256 = hashlib.sha256(draft.room_utterance.encode("utf-8")).hexdigest()
    gate = {
        "schema_version": INTAKE_ACTION_GATE_SCHEMA_VERSION,
        "kind": INTAKE_ACTION_GATE_KIND,
        "conversation_action": action,
        "reducer_status": reducer_status,
        "room_utterance_sha256": room_sha256,
        "source_turn_hash": source_turn_hash,
    }
    return gate


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
    model_output_type: type[IntakeInitiatorRoomLlmOutputV3]
    parser: PydanticOutputParser[IntakeInitiatorRoomLlmOutputV3]
    parser_pydantic_object: type[IntakeInitiatorRoomLlmOutputV3]
    parser_diff: bool
    fresh_form_opening_model: GovernedChatModel
    fresh_form_opening_model_profile: ModelProfile
    fresh_form_opening_model_policy: ModelInvocationPolicy
    fresh_form_opening_model_profile_snapshot: ModelProfile
    fresh_form_opening_model_policy_snapshot: ModelInvocationPolicy
    fresh_form_opening_model_output_type: type[IntakeInitiatorRoomLlmOutputV3]
    fresh_form_opening_parser: PydanticOutputParser[
        IntakeInitiatorRoomLlmOutputV3
    ]
    fresh_form_opening_parser_pydantic_object: type[
        IntakeInitiatorRoomLlmOutputV3
    ]
    fresh_form_opening_parser_diff: bool
    remark_acknowledgement_model: GovernedChatModel
    remark_acknowledgement_model_profile: ModelProfile
    remark_acknowledgement_model_policy: ModelInvocationPolicy
    remark_acknowledgement_model_profile_snapshot: ModelProfile
    remark_acknowledgement_model_policy_snapshot: ModelInvocationPolicy
    remark_acknowledgement_model_output_type: type[
        IntakeRemarkAcknowledgementLlmOutput
    ]
    remark_acknowledgement_parser: PydanticOutputParser[
        IntakeRemarkAcknowledgementLlmOutput
    ]
    remark_acknowledgement_parser_pydantic_object: type[
        IntakeRemarkAcknowledgementLlmOutput
    ]
    remark_acknowledgement_parser_diff: bool
    respondent_substantive_model: GovernedChatModel
    respondent_substantive_model_profile: ModelProfile
    respondent_substantive_model_policy: ModelInvocationPolicy
    respondent_substantive_model_profile_snapshot: ModelProfile
    respondent_substantive_model_policy_snapshot: ModelInvocationPolicy
    respondent_substantive_model_output_type: type[
        IntakeRespondentRoomLlmOutputV3
    ]
    respondent_substantive_parser: PydanticOutputParser[
        IntakeRespondentRoomLlmOutputV3
    ]
    respondent_substantive_parser_pydantic_object: type[
        IntakeRespondentRoomLlmOutputV3
    ]
    respondent_substantive_parser_diff: bool
    respondent_opening_model: GovernedChatModel
    respondent_opening_model_profile: ModelProfile
    respondent_opening_model_policy: ModelInvocationPolicy
    respondent_opening_model_profile_snapshot: ModelProfile
    respondent_opening_model_policy_snapshot: ModelInvocationPolicy
    respondent_opening_model_output_type: type[IntakeRespondentOpeningLlmOutput]
    respondent_opening_parser: PydanticOutputParser[IntakeRespondentOpeningLlmOutput]
    respondent_opening_parser_pydantic_object: type[IntakeRespondentOpeningLlmOutput]
    respondent_opening_parser_diff: bool
    model_router: IntakeRouteModelRunnable
    model_router_name: str
    model_router_agent_context: AgentInvocationContext
    model_router_agent_context_snapshot: AgentInvocationContext
    model_router_respondent_substantive_lens: StateLens[
        IntakeGraphStateV2, IntakePromptInput
    ]
    model_router_respondent_substantive_prompt: ChatPromptTemplate
    model_router_respondent_substantive_model: GovernedChatModel
    model_router_default_flow: Runnable[IntakeGraphStateV2, Mapping[str, Any]]
    model_router_fresh_form_opening_flow: Runnable[
        IntakeGraphStateV2, Mapping[str, Any]
    ]
    model_router_remark_acknowledgement_flow: Runnable[
        IntakeGraphStateV2, Mapping[str, Any]
    ]
    model_router_respondent_substantive_flow: Runnable[
        IntakeGraphStateV2, Mapping[str, Any]
    ]
    model_router_respondent_opening_flow: Runnable[
        IntakeGraphStateV2, Mapping[str, Any]
    ]
    preflight: IntakeModelPreflightRunnable
    guardrail: IntakeGuardrailRunnable
    patch_projector: IntakePatchProjectorRunnable
    profile: ModelProfile
    policy: ModelInvocationPolicy
    profile_snapshot: ModelProfile
    policy_snapshot: ModelInvocationPolicy
    agent_context: AgentInvocationContext
    agent_context_snapshot: AgentInvocationContext
    behavior_methods: tuple[_BehaviorMethodSeal, ...]
    behavior_attributes: tuple[_BehaviorAttributeSeal, ...]
    model_transport: ModelTransport
    model_clock: Callable[[], Any]
    model_cancelled: Callable[[], bool]
    model_user_content_parts: tuple[dict[str, Any], ...]
    model_user_content_parts_snapshot: tuple[dict[str, Any], ...]
    model_visible_fields: tuple[Any, ...]
    model_visible_field_names: frozenset[str]
    fresh_form_opening_model_clock: Callable[[], Any]
    fresh_form_opening_model_cancelled: Callable[[], bool]
    fresh_form_opening_model_user_content_parts: tuple[dict[str, Any], ...]
    fresh_form_opening_model_user_content_parts_snapshot: tuple[
        dict[str, Any], ...
    ]
    fresh_form_opening_model_visible_fields: tuple[Any, ...]
    fresh_form_opening_model_visible_field_names: frozenset[str]
    remark_acknowledgement_model_clock: Callable[[], Any]
    remark_acknowledgement_model_cancelled: Callable[[], bool]
    remark_acknowledgement_model_user_content_parts: tuple[dict[str, Any], ...]
    remark_acknowledgement_model_user_content_parts_snapshot: tuple[
        dict[str, Any], ...
    ]
    remark_acknowledgement_model_visible_fields: tuple[Any, ...]
    remark_acknowledgement_model_visible_field_names: frozenset[str]
    respondent_substantive_model_clock: Callable[[], Any]
    respondent_substantive_model_cancelled: Callable[[], bool]
    respondent_substantive_model_user_content_parts: tuple[dict[str, Any], ...]
    respondent_substantive_model_user_content_parts_snapshot: tuple[
        dict[str, Any], ...
    ]
    respondent_substantive_model_visible_fields: tuple[Any, ...]
    respondent_substantive_model_visible_field_names: frozenset[str]
    respondent_opening_model_clock: Callable[[], Any]
    respondent_opening_model_cancelled: Callable[[], bool]
    respondent_opening_model_user_content_parts: tuple[dict[str, Any], ...]
    respondent_opening_model_user_content_parts_snapshot: tuple[dict[str, Any], ...]
    respondent_opening_model_visible_fields: tuple[Any, ...]
    respondent_opening_model_visible_field_names: frozenset[str]


def _seal_intake_components(
    *,
    lens: StateLens[IntakeGraphStateV2, IntakePromptInput],
    prompt: ChatPromptTemplate,
    model: GovernedChatModel,
    parser: PydanticOutputParser[IntakeInitiatorRoomLlmOutputV3],
    fresh_form_opening_model: GovernedChatModel,
    fresh_form_opening_parser: PydanticOutputParser[
        IntakeInitiatorRoomLlmOutputV3
    ],
    remark_acknowledgement_model: GovernedChatModel,
    remark_acknowledgement_parser: PydanticOutputParser[
        IntakeRemarkAcknowledgementLlmOutput
    ],
    respondent_substantive_model: GovernedChatModel,
    respondent_substantive_parser: PydanticOutputParser[
        IntakeRespondentRoomLlmOutputV3
    ],
    respondent_opening_model: GovernedChatModel,
    respondent_opening_parser: PydanticOutputParser[
        IntakeRespondentOpeningLlmOutput
    ],
    model_router: IntakeRouteModelRunnable,
    preflight: IntakeModelPreflightRunnable,
    guardrail: IntakeGuardrailRunnable,
    patch_projector: IntakePatchProjectorRunnable,
    profile: ModelProfile,
    policy: ModelInvocationPolicy,
    agent_context: AgentInvocationContext,
    pipeline: RunnableSequence,
) -> _IntakeComponentSeal:
    model_behavior_methods = (
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
    )
    parser_behavior_methods = (
        "parse",
        "aparse",
        "parse_result",
        "aparse_result",
        "_parse_obj",
        "_parser_exception",
        "_transform",
        "_atransform",
        "get_name",
    )
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
        (model, model_behavior_methods),
        (fresh_form_opening_model, model_behavior_methods),
        (remark_acknowledgement_model, model_behavior_methods),
        (respondent_substantive_model, model_behavior_methods),
        (respondent_opening_model, model_behavior_methods),
        (parser, parser_behavior_methods),
        (fresh_form_opening_parser, parser_behavior_methods),
        (remark_acknowledgement_parser, parser_behavior_methods),
        (respondent_substantive_parser, parser_behavior_methods),
        (respondent_opening_parser, parser_behavior_methods),
        (model_router, ("_select_flow", "_respondent_substantive_flow_for")),
        (model._transport, ("generate", "agenerate", "stream", "astream")),
        (fresh_form_opening_model._transport, ("generate", "agenerate", "stream", "astream")),
        (
            remark_acknowledgement_model._transport,
            ("generate", "agenerate", "stream", "astream"),
        ),
        (
            respondent_substantive_model._transport,
            ("generate", "agenerate", "stream", "astream"),
        ),
        (respondent_opening_model._transport, ("generate", "agenerate", "stream", "astream")),
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
        fresh_form_opening_model=fresh_form_opening_model,
        fresh_form_opening_model_profile=fresh_form_opening_model.profile,
        fresh_form_opening_model_policy=fresh_form_opening_model.policy,
        fresh_form_opening_model_profile_snapshot=deepcopy(
            fresh_form_opening_model.profile
        ),
        fresh_form_opening_model_policy_snapshot=deepcopy(
            fresh_form_opening_model.policy
        ),
        fresh_form_opening_model_output_type=(
            fresh_form_opening_model._output_type
        ),
        fresh_form_opening_parser=fresh_form_opening_parser,
        fresh_form_opening_parser_pydantic_object=(
            fresh_form_opening_parser.pydantic_object
        ),
        fresh_form_opening_parser_diff=fresh_form_opening_parser.diff,
        remark_acknowledgement_model=remark_acknowledgement_model,
        remark_acknowledgement_model_profile=remark_acknowledgement_model.profile,
        remark_acknowledgement_model_policy=remark_acknowledgement_model.policy,
        remark_acknowledgement_model_profile_snapshot=deepcopy(
            remark_acknowledgement_model.profile
        ),
        remark_acknowledgement_model_policy_snapshot=deepcopy(
            remark_acknowledgement_model.policy
        ),
        remark_acknowledgement_model_output_type=(
            remark_acknowledgement_model._output_type
        ),
        remark_acknowledgement_parser=remark_acknowledgement_parser,
        remark_acknowledgement_parser_pydantic_object=(
            remark_acknowledgement_parser.pydantic_object
        ),
        remark_acknowledgement_parser_diff=remark_acknowledgement_parser.diff,
        respondent_substantive_model=respondent_substantive_model,
        respondent_substantive_model_profile=respondent_substantive_model.profile,
        respondent_substantive_model_policy=respondent_substantive_model.policy,
        respondent_substantive_model_profile_snapshot=deepcopy(
            respondent_substantive_model.profile
        ),
        respondent_substantive_model_policy_snapshot=deepcopy(
            respondent_substantive_model.policy
        ),
        respondent_substantive_model_output_type=(
            respondent_substantive_model._output_type
        ),
        respondent_substantive_parser=respondent_substantive_parser,
        respondent_substantive_parser_pydantic_object=(
            respondent_substantive_parser.pydantic_object
        ),
        respondent_substantive_parser_diff=respondent_substantive_parser.diff,
        respondent_opening_model=respondent_opening_model,
        respondent_opening_model_profile=respondent_opening_model.profile,
        respondent_opening_model_policy=respondent_opening_model.policy,
        respondent_opening_model_profile_snapshot=deepcopy(
            respondent_opening_model.profile
        ),
        respondent_opening_model_policy_snapshot=deepcopy(
            respondent_opening_model.policy
        ),
        respondent_opening_model_output_type=respondent_opening_model._output_type,
        respondent_opening_parser=respondent_opening_parser,
        respondent_opening_parser_pydantic_object=(
            respondent_opening_parser.pydantic_object
        ),
        respondent_opening_parser_diff=respondent_opening_parser.diff,
        model_router=model_router,
        model_router_name=model_router.name,
        model_router_agent_context=model_router._agent_context,
        model_router_agent_context_snapshot=deepcopy(model_router._agent_context),
        model_router_respondent_substantive_lens=(
            model_router._respondent_substantive_lens
        ),
        model_router_respondent_substantive_prompt=(
            model_router._respondent_substantive_prompt
        ),
        model_router_respondent_substantive_model=(
            model_router._respondent_substantive_model
        ),
        model_router_default_flow=model_router._default_flow,
        model_router_fresh_form_opening_flow=(
            model_router._fresh_form_opening_flow
        ),
        model_router_remark_acknowledgement_flow=(
            model_router._remark_acknowledgement_flow
        ),
        model_router_respondent_substantive_flow=(
            model_router._respondent_substantive_flow
        ),
        model_router_respondent_opening_flow=(
            model_router._respondent_opening_flow
        ),
        preflight=preflight,
        guardrail=guardrail,
        patch_projector=patch_projector,
        profile=profile,
        policy=policy,
        profile_snapshot=deepcopy(profile),
        policy_snapshot=deepcopy(policy),
        agent_context=agent_context,
        agent_context_snapshot=deepcopy(agent_context),
        behavior_methods=_seal_behavior_methods(pipeline, explicit_methods),
        behavior_attributes=_seal_behavior_attributes(pipeline),
        model_transport=model._transport,
        model_clock=model._clock,
        model_cancelled=model._cancelled,
        model_user_content_parts=model._user_content_parts,
        model_user_content_parts_snapshot=deepcopy(model._user_content_parts),
        model_visible_fields=model._visible_fields,
        model_visible_field_names=model._visible_field_names,
        fresh_form_opening_model_clock=fresh_form_opening_model._clock,
        fresh_form_opening_model_cancelled=fresh_form_opening_model._cancelled,
        fresh_form_opening_model_user_content_parts=(
            fresh_form_opening_model._user_content_parts
        ),
        fresh_form_opening_model_user_content_parts_snapshot=deepcopy(
            fresh_form_opening_model._user_content_parts
        ),
        fresh_form_opening_model_visible_fields=(
            fresh_form_opening_model._visible_fields
        ),
        fresh_form_opening_model_visible_field_names=(
            fresh_form_opening_model._visible_field_names
        ),
        remark_acknowledgement_model_clock=remark_acknowledgement_model._clock,
        remark_acknowledgement_model_cancelled=(
            remark_acknowledgement_model._cancelled
        ),
        remark_acknowledgement_model_user_content_parts=(
            remark_acknowledgement_model._user_content_parts
        ),
        remark_acknowledgement_model_user_content_parts_snapshot=deepcopy(
            remark_acknowledgement_model._user_content_parts
        ),
        remark_acknowledgement_model_visible_fields=(
            remark_acknowledgement_model._visible_fields
        ),
        remark_acknowledgement_model_visible_field_names=(
            remark_acknowledgement_model._visible_field_names
        ),
        respondent_substantive_model_clock=respondent_substantive_model._clock,
        respondent_substantive_model_cancelled=(
            respondent_substantive_model._cancelled
        ),
        respondent_substantive_model_user_content_parts=(
            respondent_substantive_model._user_content_parts
        ),
        respondent_substantive_model_user_content_parts_snapshot=deepcopy(
            respondent_substantive_model._user_content_parts
        ),
        respondent_substantive_model_visible_fields=(
            respondent_substantive_model._visible_fields
        ),
        respondent_substantive_model_visible_field_names=(
            respondent_substantive_model._visible_field_names
        ),
        respondent_opening_model_clock=respondent_opening_model._clock,
        respondent_opening_model_cancelled=respondent_opening_model._cancelled,
        respondent_opening_model_user_content_parts=(
            respondent_opening_model._user_content_parts
        ),
        respondent_opening_model_user_content_parts_snapshot=deepcopy(
            respondent_opening_model._user_content_parts
        ),
        respondent_opening_model_visible_fields=(
            respondent_opening_model._visible_fields
        ),
        respondent_opening_model_visible_field_names=(
            respondent_opening_model._visible_field_names
        ),
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

    fresh_form_opening_model = seal.fresh_form_opening_model
    if (
        fresh_form_opening_model.profile
        is not seal.fresh_form_opening_model_profile
        or fresh_form_opening_model.policy
        is not seal.fresh_form_opening_model_policy
        or fresh_form_opening_model._output_type
        is not seal.fresh_form_opening_model_output_type
        or fresh_form_opening_model.profile
        != seal.fresh_form_opening_model_profile_snapshot
        or fresh_form_opening_model.policy
        != seal.fresh_form_opening_model_policy_snapshot
        or fresh_form_opening_model._transport is not seal.model_transport
        or fresh_form_opening_model._clock
        is not seal.fresh_form_opening_model_clock
        or fresh_form_opening_model._cancelled
        is not seal.fresh_form_opening_model_cancelled
        or fresh_form_opening_model._user_content_parts
        is not seal.fresh_form_opening_model_user_content_parts
        or fresh_form_opening_model._user_content_parts
        != seal.fresh_form_opening_model_user_content_parts_snapshot
        or fresh_form_opening_model._visible_fields
        is not seal.fresh_form_opening_model_visible_fields
        or fresh_form_opening_model._visible_field_names
        is not seal.fresh_form_opening_model_visible_field_names
    ):
        return False

    remark_acknowledgement_model = seal.remark_acknowledgement_model
    if (
        remark_acknowledgement_model.profile
        is not seal.remark_acknowledgement_model_profile
        or remark_acknowledgement_model.policy
        is not seal.remark_acknowledgement_model_policy
        or remark_acknowledgement_model._output_type
        is not seal.remark_acknowledgement_model_output_type
        or remark_acknowledgement_model.profile
        != seal.remark_acknowledgement_model_profile_snapshot
        or remark_acknowledgement_model.policy
        != seal.remark_acknowledgement_model_policy_snapshot
        or remark_acknowledgement_model._transport is not seal.model_transport
        or remark_acknowledgement_model._clock
        is not seal.remark_acknowledgement_model_clock
        or remark_acknowledgement_model._cancelled
        is not seal.remark_acknowledgement_model_cancelled
        or remark_acknowledgement_model._user_content_parts
        is not seal.remark_acknowledgement_model_user_content_parts
        or remark_acknowledgement_model._user_content_parts
        != seal.remark_acknowledgement_model_user_content_parts_snapshot
        or remark_acknowledgement_model._visible_fields
        is not seal.remark_acknowledgement_model_visible_fields
        or remark_acknowledgement_model._visible_field_names
        is not seal.remark_acknowledgement_model_visible_field_names
    ):
        return False

    respondent_substantive_model = seal.respondent_substantive_model
    if (
        respondent_substantive_model.profile
        is not seal.respondent_substantive_model_profile
        or respondent_substantive_model.policy
        is not seal.respondent_substantive_model_policy
        or respondent_substantive_model._output_type
        is not seal.respondent_substantive_model_output_type
        or respondent_substantive_model.profile
        != seal.respondent_substantive_model_profile_snapshot
        or respondent_substantive_model.policy
        != seal.respondent_substantive_model_policy_snapshot
        or respondent_substantive_model._transport is not seal.model_transport
        or respondent_substantive_model._clock
        is not seal.respondent_substantive_model_clock
        or respondent_substantive_model._cancelled
        is not seal.respondent_substantive_model_cancelled
        or respondent_substantive_model._user_content_parts
        is not seal.respondent_substantive_model_user_content_parts
        or respondent_substantive_model._user_content_parts
        != seal.respondent_substantive_model_user_content_parts_snapshot
        or respondent_substantive_model._visible_fields
        is not seal.respondent_substantive_model_visible_fields
        or respondent_substantive_model._visible_field_names
        is not seal.respondent_substantive_model_visible_field_names
    ):
        return False

    respondent_opening_model = seal.respondent_opening_model
    if (
        respondent_opening_model.profile is not seal.respondent_opening_model_profile
        or respondent_opening_model.policy is not seal.respondent_opening_model_policy
        or respondent_opening_model._output_type
        is not seal.respondent_opening_model_output_type
        or respondent_opening_model.profile
        != seal.respondent_opening_model_profile_snapshot
        or respondent_opening_model.policy
        != seal.respondent_opening_model_policy_snapshot
        or respondent_opening_model._transport is not seal.model_transport
        or respondent_opening_model._clock
        is not seal.respondent_opening_model_clock
        or respondent_opening_model._cancelled
        is not seal.respondent_opening_model_cancelled
        or respondent_opening_model._user_content_parts
        is not seal.respondent_opening_model_user_content_parts
        or respondent_opening_model._user_content_parts
        != seal.respondent_opening_model_user_content_parts_snapshot
        or respondent_opening_model._visible_fields
        is not seal.respondent_opening_model_visible_fields
        or respondent_opening_model._visible_field_names
        is not seal.respondent_opening_model_visible_field_names
    ):
        return False

    parser = seal.parser
    if (
        parser.pydantic_object is not seal.parser_pydantic_object
        or parser.diff is not seal.parser_diff
    ):
        return False

    fresh_form_opening_parser = seal.fresh_form_opening_parser
    if (
        fresh_form_opening_parser.pydantic_object
        is not seal.fresh_form_opening_parser_pydantic_object
        or fresh_form_opening_parser.diff
        is not seal.fresh_form_opening_parser_diff
    ):
        return False

    remark_acknowledgement_parser = seal.remark_acknowledgement_parser
    if (
        remark_acknowledgement_parser.pydantic_object
        is not seal.remark_acknowledgement_parser_pydantic_object
        or remark_acknowledgement_parser.diff
        is not seal.remark_acknowledgement_parser_diff
    ):
        return False

    respondent_substantive_parser = seal.respondent_substantive_parser
    if (
        respondent_substantive_parser.pydantic_object
        is not seal.respondent_substantive_parser_pydantic_object
        or respondent_substantive_parser.diff
        is not seal.respondent_substantive_parser_diff
    ):
        return False

    respondent_opening_parser = seal.respondent_opening_parser
    if (
        respondent_opening_parser.pydantic_object
        is not seal.respondent_opening_parser_pydantic_object
        or respondent_opening_parser.diff
        is not seal.respondent_opening_parser_diff
    ):
        return False

    model_router = seal.model_router
    if (
        model_router.name != seal.model_router_name
        or model_router._agent_context is not seal.model_router_agent_context
        or model_router._agent_context != seal.model_router_agent_context_snapshot
        or model_router._respondent_substantive_lens
        is not seal.model_router_respondent_substantive_lens
        or model_router._respondent_substantive_prompt
        is not seal.model_router_respondent_substantive_prompt
        or model_router._respondent_substantive_model
        is not seal.model_router_respondent_substantive_model
        or model_router._default_flow is not seal.model_router_default_flow
        or model_router._fresh_form_opening_flow
        is not seal.model_router_fresh_form_opening_flow
        or model_router._remark_acknowledgement_flow
        is not seal.model_router_remark_acknowledgement_flow
        or model_router._respondent_substantive_flow
        is not seal.model_router_respondent_substantive_flow
        or model_router._respondent_opening_flow
        is not seal.model_router_respondent_opening_flow
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
            and seal.guardrail._agent_context is seal.agent_context
            and seal.guardrail._agent_context == seal.agent_context_snapshot
            and seal.patch_projector._profile is seal.profile
            and seal.patch_projector._policy is seal.policy
            and seal.patch_projector._profile == seal.profile_snapshot
            and seal.patch_projector._policy == seal.policy_snapshot
            and seal.patch_projector._agent_context is seal.agent_context
            and seal.patch_projector._agent_context == seal.agent_context_snapshot
        )
    )


def build_intake_model_node(
    *,
    transport: ModelTransport,
    profile: ModelProfile,
    policy: ModelInvocationPolicy,
    agent_context: AgentInvocationContext,
    trusted_system_prompt: str,
    _test_hook: _IntakeModelTestHook | None = None,
) -> BuiltIntakeModelNode:
    if policy.node_name != BASELINE_INTAKE_NODE_NAME:
        raise IntakeGraphContractError("INTAKE_LCEL_NODE_BINDING_INVALID")
    if type(agent_context) is not AgentInvocationContext:
        raise IntakeGraphContractError("INTAKE_LCEL_AGENT_CONTEXT_INVALID")
    if system_prompt_sha256(trusted_system_prompt) != policy.trusted_system_sha256:
        raise IntakeGraphContractError("INTAKE_LCEL_SYSTEM_PROMPT_MISMATCH")
    if profile.tool_allowlist:
        raise IntakeGraphContractError("INTAKE_LCEL_TOOLS_FORBIDDEN")
    if not _IDENTIFIER.fullmatch(policy.invocation_id):
        raise IntakeGraphContractError("INTAKE_LCEL_INVOCATION_ID_INVALID")

    prompts = PromptRepository()
    context_window = ContextWindowManager()

    def select_baseline_prompt(state: Mapping[str, Any]) -> Mapping[str, Any]:
        return _select_intake_prompt(
            state,
            agent_context=agent_context,
            prompts=prompts,
            context_window=context_window,
            trusted_system_prompt=trusted_system_prompt,
        )

    lens: StateLens[IntakeGraphStateV2, IntakePromptInput] = _OptionalBaselineContextStateLens(
        name="intake_lcel.state_lens",
        source_fields=(
            "bindings",
            "version_pins",
            "messages",
            "memory_summary",
            "dossier_draft",
            "baseline_previous_case_detail",
            "route",
            "initial_snapshot_ref",
            "initial_snapshot_hash",
            "initial_domain_revision",
            "last_event_ref",
            "last_event_hash",
            "last_event_sequence",
            "node_results",
            "execution_receipts",
            "result_json",
        ),
        selector=select_baseline_prompt,
        output_type=IntakePromptInput,
    )
    prompt = ChatPromptTemplate.from_messages(
        [
            ("system", "{system_prompt}"),
            ("human", "{human_prompt}"),
        ]
    )
    model = GovernedChatModel(
        transport=transport,
        output_type=IntakeInitiatorRoomLlmOutputV3,
        profile=profile,
        policy=policy,
        visible_fields=_TARGET_INTAKE_VISIBLE_FIELDS,
    )
    parser = PydanticOutputParser(pydantic_object=IntakeInitiatorRoomLlmOutputV3)
    fresh_form_opening_profile = deepcopy(profile)
    fresh_form_opening_policy = deepcopy(policy)
    fresh_form_opening_model = GovernedChatModel(
        transport=transport,
        output_type=IntakeInitiatorRoomLlmOutputV3,
        profile=fresh_form_opening_profile,
        policy=fresh_form_opening_policy,
        visible_fields=_FRESH_FORM_OPENING_VISIBLE_FIELDS,
    )
    fresh_form_opening_parser = PydanticOutputParser(
        pydantic_object=IntakeInitiatorRoomLlmOutputV3
    )
    remark_acknowledgement_profile = deepcopy(profile)
    remark_acknowledgement_policy = deepcopy(policy)
    remark_acknowledgement_model = GovernedChatModel(
        transport=transport,
        output_type=IntakeRemarkAcknowledgementLlmOutput,
        profile=remark_acknowledgement_profile,
        policy=remark_acknowledgement_policy,
        visible_fields=_RESPONDENT_OPENING_VISIBLE_FIELDS,
    )
    remark_acknowledgement_parser = PydanticOutputParser(
        pydantic_object=IntakeRemarkAcknowledgementLlmOutput
    )
    respondent_substantive_profile = deepcopy(profile)
    respondent_substantive_policy = deepcopy(policy)
    respondent_substantive_model = GovernedChatModel(
        transport=transport,
        output_type=IntakeRespondentRoomLlmOutputV3,
        profile=respondent_substantive_profile,
        policy=respondent_substantive_policy,
        visible_fields=_TARGET_INTAKE_VISIBLE_FIELDS,
    )
    respondent_substantive_parser = PydanticOutputParser(
        pydantic_object=IntakeRespondentRoomLlmOutputV3
    )
    if (
        len(_RESPONDENT_OPENING_VISIBLE_FIELDS) != 1
        or _RESPONDENT_OPENING_VISIBLE_FIELDS[0].field != "room_utterance"
    ):
        raise IntakeGraphContractError(
            "INTAKE_RESPONDENT_OPENING_OUTPUT_CONTRACT_INVALID"
        )
    respondent_opening_profile = deepcopy(profile)
    respondent_opening_policy = deepcopy(policy)
    respondent_opening_model = GovernedChatModel(
        transport=transport,
        output_type=IntakeRespondentOpeningLlmOutput,
        profile=respondent_opening_profile,
        policy=respondent_opening_policy,
        visible_fields=_RESPONDENT_OPENING_VISIBLE_FIELDS,
    )
    respondent_opening_parser = PydanticOutputParser(
        pydantic_object=IntakeRespondentOpeningLlmOutput
    )
    preflight = IntakeModelPreflightRunnable(profile=profile, policy=policy)
    parsed_generation = RunnableParallel(
        message=RunnablePassthrough(),
        draft=parser,
    )
    default_model_flow = lens | prompt | model | parsed_generation
    fresh_form_opening_parsed_generation = RunnableParallel(
        message=RunnablePassthrough(),
        draft=fresh_form_opening_parser,
    )
    fresh_form_opening_model_flow = (
        lens
        | prompt
        | fresh_form_opening_model
        | fresh_form_opening_parsed_generation
    )
    remark_acknowledgement_parsed_generation = RunnableParallel(
        message=RunnablePassthrough(),
        draft=remark_acknowledgement_parser,
    )
    remark_acknowledgement_model_flow = (
        lens
        | prompt
        | remark_acknowledgement_model
        | remark_acknowledgement_parsed_generation
    )
    respondent_substantive_parsed_generation = RunnableParallel(
        message=RunnablePassthrough(),
        draft=respondent_substantive_parser,
    )
    respondent_substantive_model_flow = (
        lens
        | prompt
        | respondent_substantive_model
        | respondent_substantive_parsed_generation
    )
    respondent_opening_parsed_generation = RunnableParallel(
        message=RunnablePassthrough(),
        draft=respondent_opening_parser,
    )
    respondent_opening_model_flow = (
        lens
        | prompt
        | respondent_opening_model
        | respondent_opening_parsed_generation
    )
    model_router = IntakeRouteModelRunnable(
        agent_context=agent_context,
        respondent_substantive_lens=lens,
        respondent_substantive_prompt=prompt,
        respondent_substantive_model=respondent_substantive_model,
        default_flow=cast(
            Runnable[IntakeGraphStateV2, Mapping[str, Any]],
            default_model_flow,
        ),
        fresh_form_opening_flow=cast(
            Runnable[IntakeGraphStateV2, Mapping[str, Any]],
            fresh_form_opening_model_flow,
        ),
        remark_acknowledgement_flow=cast(
            Runnable[IntakeGraphStateV2, Mapping[str, Any]],
            remark_acknowledgement_model_flow,
        ),
        respondent_substantive_flow=cast(
            Runnable[IntakeGraphStateV2, Mapping[str, Any]],
            respondent_substantive_model_flow,
        ),
        respondent_opening_flow=cast(
            Runnable[IntakeGraphStateV2, Mapping[str, Any]],
            respondent_opening_model_flow,
        ),
    )
    routed_model_flow = cast(
        Runnable[IntakeGraphStateV2, Mapping[str, Any]],
        model_router,
    )
    state_and_generation = RunnableParallel(
        state=RunnablePassthrough(),
        generation=routed_model_flow,
    )
    guardrail = IntakeGuardrailRunnable(
        profile=profile,
        policy=policy,
        agent_context=agent_context,
    )
    patch_projector = IntakePatchProjectorRunnable(
        profile=profile,
        policy=policy,
        agent_context=agent_context,
    )
    pipeline = cast(
        RunnableSequence,
        preflight | state_and_generation | guardrail | patch_projector,
    )
    component_seal = _seal_intake_components(
        lens=lens,
        prompt=prompt,
        model=model,
        parser=parser,
        fresh_form_opening_model=fresh_form_opening_model,
        fresh_form_opening_parser=fresh_form_opening_parser,
        remark_acknowledgement_model=remark_acknowledgement_model,
        remark_acknowledgement_parser=remark_acknowledgement_parser,
        respondent_substantive_model=respondent_substantive_model,
        respondent_substantive_parser=respondent_substantive_parser,
        respondent_opening_model=respondent_opening_model,
        respondent_opening_parser=respondent_opening_parser,
        model_router=model_router,
        preflight=preflight,
        guardrail=guardrail,
        patch_projector=patch_projector,
        profile=profile,
        policy=policy,
        agent_context=agent_context,
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
        fresh_form_opening_model=fresh_form_opening_model,
        fresh_form_opening_parser=fresh_form_opening_parser,
        remark_acknowledgement_model=remark_acknowledgement_model,
        remark_acknowledgement_parser=remark_acknowledgement_parser,
        respondent_substantive_model=respondent_substantive_model,
        respondent_substantive_parser=respondent_substantive_parser,
        respondent_opening_model=respondent_opening_model,
        respondent_opening_parser=respondent_opening_parser,
        preflight=preflight,
        model_router=model_router,
        model_flow=cast(RunnableSequence, default_model_flow),
        routed_model_flow=routed_model_flow,
        guardrail=guardrail,
        patch_projector=patch_projector,
        runnable=cast(Runnable[IntakeGraphStateV2, dict[str, Any]], runnable),
    )


def _select_intake_prompt(
    state: Mapping[str, Any],
    *,
    agent_context: AgentInvocationContext,
    prompts: PromptRepository,
    context_window: ContextWindowManager,
    trusted_system_prompt: str,
) -> Mapping[str, Any]:
    prepared = prepare_intake_baseline_invocation(
        state,
        agent_context=agent_context,
        prompts=prompts,
        context_window=context_window,
    )
    if prepared.system_prompt != trusted_system_prompt:
        raise IntakeGraphContractError("INTAKE_LCEL_SYSTEM_PROMPT_MISMATCH")
    return {
        "system_prompt": prepared.system_prompt,
        "human_prompt": prepared.user_prompt,
    }


def _generation_parts(
    value: Mapping[str, Any],
    *,
    agent_context: AgentInvocationContext,
) -> tuple[IntakeGraphStateV2, AIMessage, IntakeCognitionDraft]:
    state, message, _, draft = _adapt_and_normalize_generation_parts(
        value,
        agent_context=agent_context,
    )
    return state, message, draft


def _completed_generation_message(value: object) -> AIMessage:
    """Normalize only LangChain's explicit terminal chunk to an AIMessage."""

    if isinstance(value, AIMessage):
        return value
    if not isinstance(value, AIMessageChunk) or value.chunk_position != "last":
        raise IntakeGraphContractError("INTAKE_LCEL_GENERATION_INVALID")
    converted = message_chunk_to_message(value)
    if not isinstance(converted, AIMessage):
        raise IntakeGraphContractError("INTAKE_LCEL_GENERATION_INVALID")
    return converted


def _generation_parts_with_baseline_context(
    value: Mapping[str, Any],
    *,
    agent_context: AgentInvocationContext,
) -> tuple[
    IntakeGraphStateV2,
    AIMessage,
    IntakeCognitionDraft,
    dict[str, Any],
    dict[str, Any],
    dict[str, Any],
]:
    """Produce the normalized draft, its public materialization, and formal matrix."""

    typed_state, message, adapted, normalized = _adapt_and_normalize_generation_parts(
        value,
        agent_context=agent_context,
    )
    if normalized.matrix_patch != adapted.matrix_patch:
        # The baseline adapter has already normalized model fact keys before
        # finalizing the retained formal matrix.  A second, divergent Target
        # rewrite would sever the terminal draft from that matrix provenance.
        raise IntakeGraphContractError("INTAKE_BASELINE_MATRIX_NORMALIZATION_DIVERGENCE")
    # Formal matrix finalization accepts only the same already-governed Target
    # patch that the cognition/proposal contract will later persist.
    validate_matrix_patch(
        typed_state,
        (
            normalized.matrix_patch.model_dump(mode="json", exclude_none=True)
            if normalized.matrix_patch is not None
            else None
        ),
    )
    (
        baseline_formal_matrix,
        materialized_public_dossier,
        matrix_derivation_request_base,
    ) = _post_normalizer_formal_matrix(
        typed_state,
        agent_context=agent_context,
        draft=normalized,
    )
    if typed_state.get("route") == "respondent_opening":
        opening_authority = validated_respondent_opening_frozen_context(typed_state)
        materialized_public_dossier = (
            rebind_respondent_opening_handoff_partition(
                materialized_public_dossier,
                authority_dossier=opening_authority,
                successor_matrix=baseline_formal_matrix,
            )
        )
        opening_request = build_intake_baseline_request(
            typed_state,
            agent_context=agent_context,
        )
        opening_patch = respondent_opening_carry_delta(
            request=opening_request,
        ).model_dump(mode="json", exclude_none=True)
        validate_matrix_patch(typed_state, opening_patch)
        normalized_payload = normalized.model_dump(
            mode="json",
            exclude_none=True,
            exclude_unset=True,
        )
        rebound_partition = materialized_public_dossier.get(
            "handoff_remark_partition"
        )
        if rebound_partition is not None:
            dossier_patch = normalized_payload.get("dossier_patch")
            if not isinstance(dossier_patch, dict):
                raise IntakeGraphContractError(
                    "INTAKE_RESPONDENT_OPENING_HANDOFF_REMARK_INVALID"
                )
            dossier_patch["handoff_remark_partition"] = deepcopy(
                rebound_partition
            )
        normalized_payload["matrix_patch"] = opening_patch
        normalized = IntakeCognitionDraft.model_validate(normalized_payload)
    return (
        typed_state,
        message,
        normalized,
        baseline_formal_matrix,
        materialized_public_dossier,
        matrix_derivation_request_base,
    )


def _adapt_and_normalize_generation_parts(
    value: Mapping[str, Any],
    *,
    agent_context: AgentInvocationContext,
) -> tuple[
    IntakeGraphStateV2,
    AIMessage,
    IntakeCognitionDraft,
    IntakeCognitionDraft,
]:
    """Adapt a governed model result and apply Target-only normalizers."""

    state = value.get("state")
    generation = value.get("generation")
    if not isinstance(state, dict) or not isinstance(generation, Mapping):
        raise IntakeGraphContractError("INTAKE_LCEL_GENERATION_INVALID")
    message = _completed_generation_message(generation.get("message"))
    draft = generation.get("draft")
    typed_state = cast(IntakeGraphStateV2, state)
    fresh_form_request: IntakeTurnRequest | None = None
    handoff_request: IntakeTurnRequest | None = None
    ordered_room_v3 = False
    if typed_state.get("route") == "respondent_opening":
        if not isinstance(draft, IntakeRespondentOpeningLlmOutput):
            raise IntakeGraphContractError("INTAKE_LCEL_GENERATION_INVALID")
        adapted = _governed_respondent_opening_draft(
            typed_state,
            agent_context=agent_context,
            draft=draft,
        )
        return typed_state, message, adapted, adapted
    if isinstance(
        draft,
        (IntakeInitiatorRoomLlmOutputV3, IntakeRespondentRoomLlmOutputV3),
    ):
        request = build_intake_baseline_request(
            typed_state,
            agent_context=agent_context,
        )
        ordered_room_v3 = True
        if request.initial_case_facts is not None and request.current_user_message is None:
            fresh_form_request = request
        try:
            draft = materialize_intake_case_detail_output(request, draft)
        except (TypeError, ValueError) as error:
            raise IntakeGraphContractError(
                "INTAKE_ORDERED_ROOM_OUTPUT_INVALID"
            ) from error
    if isinstance(draft, IntakeFreshFormOpeningLlmOutput):
        request = build_intake_baseline_request(
            typed_state,
            agent_context=agent_context,
        )
        if (
            request.turn_source == "FORM_SUBMISSION"
            and intake_case_detail_output_type(request)
            is IntakeFreshFormOpeningLlmOutput
        ):
            fresh_form_request = request
        try:
            draft = materialize_intake_case_detail_output(request, draft)
        except (TypeError, ValueError) as error:
            raise IntakeGraphContractError(
                "INTAKE_FRESH_FORM_OPENING_AUTHORITY_INVALID"
            ) from error
    if isinstance(draft, IntakeRemarkAcknowledgementLlmOutput):
        request = build_intake_baseline_request(
            typed_state,
            agent_context=agent_context,
        )
        try:
            draft = materialize_intake_case_detail_output(request, draft)
        except (TypeError, ValueError) as error:
            raise IntakeGraphContractError(
                "INTAKE_REMARK_ACKNOWLEDGEMENT_AUTHORITY_INVALID"
            ) from error
        handoff_request = request
    if not isinstance(draft, IntakeCaseDetailLlmOutput):
        raise IntakeGraphContractError("INTAKE_LCEL_GENERATION_INVALID")
    try:
        adapted = adapt_intake_baseline_output(
            typed_state,
            agent_context=agent_context,
            output=draft,
        )
    except AgentOutputSchemaError as error:
        raise IntakeGraphContractError(error.safe_code) from None
    normalized = _normalize_model_matrix_fact_keys(typed_state, adapted)
    if not ordered_room_v3:
        normalized = _normalize_model_respondent_attitude(
            typed_state,
            normalized,
            fresh_form_request=fresh_form_request,
            handoff_request=handoff_request,
        )
        normalized = _normalize_model_dispute_core_state(typed_state, normalized)
    return (
        typed_state,
        message,
        adapted,
        normalized,
    )


def _governed_respondent_opening_draft(
    state: IntakeGraphStateV2,
    *,
    agent_context: AgentInvocationContext,
    draft: IntakeRespondentOpeningLlmOutput,
) -> IntakeCognitionDraft:
    """Govern one opening without admitting provider dossier or matrix authority."""

    frozen_context = validated_respondent_opening_frozen_context(state)
    request = build_intake_baseline_request(state, agent_context=agent_context)
    case_story = frozen_context.get("case_story")
    trusted_summary = (
        str(case_story.get("one_sentence_summary") or "").strip()
        if isinstance(case_story, Mapping)
        else ""
    )
    if not trusted_summary:
        trusted_summary = "RESPONDENT_OPENING"
    try:
        sanitized_output = IntakeCaseDetailLlmOutput.model_validate(
            {
                "conversation_action": "ASK_SUBSTANTIVE",
                "room_utterance": draft.room_utterance,
                "case_detail": {
                    "case_story": {"one_sentence_summary": trusted_summary}
                },
                "case_matrix_delta": respondent_opening_carry_delta(request=request),
                "confidence": draft.confidence,
            }
        )
        governed = adapt_intake_baseline_output(
            state,
            agent_context=agent_context,
            output=sanitized_output,
        )
    except AgentOutputSchemaError as error:
        raise IntakeGraphContractError(error.safe_code) from None
    except ValueError as error:
        raise IntakeGraphContractError(
            "INTAKE_RESPONDENT_OPENING_GOVERNANCE_INVALID"
        ) from error

    governed_patch = governed.dossier_patch.model_dump(
        mode="json",
        exclude_none=True,
        exclude_unset=True,
    )
    if any(field not in governed_patch for field in _PARTY_INTAKE_GOVERNANCE_FIELDS):
        raise IntakeGraphContractError(
            "INTAKE_RESPONDENT_OPENING_GOVERNANCE_INVALID"
        )
    phase_patch = {
        field: deepcopy(governed_patch[field])
        for field in _PARTY_INTAKE_GOVERNANCE_FIELDS
    }
    return IntakeCognitionDraft.model_validate(
        {
            "conversation_action": "ASK_SUBSTANTIVE",
            "room_utterance": draft.room_utterance,
            "dossier_patch": phase_patch,
            "matrix_patch": None,
            "readiness": "INCOMPLETE",
            "missing_fields": [],
            "recommendation": "NEED_MORE_INFO",
            "knowledge_answer_mode": "NONE",
            "confidence": draft.confidence,
        }
    )


def _post_normalizer_formal_matrix(
    state: IntakeGraphStateV2,
    *,
    agent_context: AgentInvocationContext,
    draft: IntakeCognitionDraft,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    """Finalize formal authority from the same post-normalizer public dossier.

    The initial baseline projection is needed to construct the Target draft, but
    its full scroll snapshot can contain model text later removed by Target
    boundary normalizers.  Recompute only the established case-fact matrix from
    the normalized public dossier and normalized delta.  Do not re-run the
    DossierSkill: that would reintroduce its earlier evidence follow-ups.
    """

    patch = draft.dossier_patch.model_dump(mode="json", exclude_none=True, exclude_unset=True)
    materialized = merge_intake_dossier(state["dossier_draft"], patch)
    matrix_patch = draft.matrix_patch
    action = _conversation_action(draft)
    request = build_intake_baseline_request(state, agent_context=agent_context)
    combined_substantive_no_remark = bool(
        action == "ACK_NO_REMARK"
        and intake_request_actor_is_exactly_not_ready(request)
    )
    if action in {"ACK_REMARK", "ACK_NO_REMARK"} and not combined_substantive_no_remark:
        if matrix_patch is not None:
            raise IntakeGraphContractError("INTAKE_REMARK_MATRIX_PATCH_FORBIDDEN")
        previous = _verified_previous_case_detail(state)
        frozen_matrix = previous.get("case_fact_matrix")
        frozen_partition = previous.get("handoff_remark_partition")
        materialized_partition = materialized.get("handoff_remark_partition")
        if (
            not isinstance(frozen_matrix, Mapping)
            or not isinstance(frozen_partition, Mapping)
            or not isinstance(materialized_partition, Mapping)
            or any(
                materialized_partition.get(partition_field)
                != frozen_partition.get(partition_field)
                for partition_field in (
                    "schema_version",
                    "case_fact_matrix_id",
                    "case_fact_matrix_version",
                    "case_fact_matrix_hash",
                )
            )
            or materialized_partition.get("case_fact_matrix_id")
            != frozen_matrix.get("matrix_id")
            or materialized_partition.get("case_fact_matrix_version")
            != frozen_matrix.get("matrix_version")
            or materialized_partition.get("case_fact_matrix_hash")
            != frozen_matrix.get("content_hash")
        ):
            raise IntakeGraphContractError("INTAKE_REMARK_FROZEN_MATRIX_CONFLICT")
        request_base = request.model_dump(mode="json")
        request_base["previous_case_detail"] = None
        return deepcopy(dict(frozen_matrix)), materialized, request_base
    if combined_substantive_no_remark and matrix_patch is None:
        raise IntakeGraphContractError(
            "INTAKE_SUBSTANTIVE_NO_REMARK_MATRIX_REQUIRED"
        )
    if matrix_patch is None:
        delta = None
    else:
        matrix_payload = matrix_patch.model_dump(mode="json", exclude_none=True)
        try:
            if matrix_payload.get("schema_version") == "case_fact_matrix.delta.v2":
                delta = FormalCaseFactMatrixDeltaV2.model_validate(matrix_payload)
            elif matrix_payload.get("schema_version") == "unilateral_case_matrix.draft.v1":
                delta = FormalUnilateralCaseMatrixDraftV1.model_validate(matrix_payload)
            else:
                raise IntakeGraphContractError("INTAKE_BASELINE_FORMAL_MATRIX_INVALID")
        except ValueError as error:
            raise IntakeGraphContractError("INTAKE_BASELINE_FORMAL_MATRIX_INVALID") from error
    try:
        matrix = finalize_case_fact_matrix(
            request=request,
            case_detail=deepcopy(materialized),
            delta=delta,
        )
    except AgentOutputSchemaError as error:
        raise IntakeGraphContractError(error.safe_code) from None
    except ValueError as error:
        raise IntakeGraphContractError("INTAKE_BASELINE_FORMAL_MATRIX_INVALID") from error
    request_base = request.model_dump(mode="json")
    # The only retained previous authority is the separate, hash-bound M0/Mn
    # input in the pending capsule.  Do not duplicate a full prior dossier in
    # the derivation request base.
    request_base["previous_case_detail"] = None
    return matrix.model_dump(mode="json"), materialized, request_base


def _normalize_model_respondent_attitude(
    state: IntakeGraphStateV2,
    draft: IntakeCognitionDraft,
    *,
    fresh_form_request: IntakeTurnRequest | None = None,
    handoff_request: IntakeTurnRequest | None = None,
) -> IntakeCognitionDraft:
    """Keep respondent silence out of the unilateral claim projection.

    The baseline dossier treats silence and platform uncertainty as absence of a
    reportable respondent position.  Some providers nevertheless materialize an
    ``UNKNOWN`` placeholder branch.  Remove only those bounded absence aliases
    when the authorized turn carries no structured respondent statement.  A
    respondent turn, or an initiator patch that already attributes a statement to
    the respondent, must instead fail closed so a real position cannot disappear
    behind an absence marker.
    """

    attitude = draft.dossier_patch.respondent_attitude
    if handoff_request is not None:
        return _require_exact_handoff_inherited_respondent_attitude(
            state,
            draft,
            request=handoff_request,
        )
    if attitude is None:
        return draft
    if fresh_form_request is not None:
        return _require_deterministic_initial_form_respondent_attitude(
            state,
            draft,
            request=fresh_form_request,
        )
    grounded = _grounded_respondent_attitude(state, draft=draft)
    prior = _prior_authoritative_respondent_attitude(state)
    prior_is_substantive = _validate_prior_respondent_attitude_authority(state, prior)
    proposed = _respondent_attitude_discriminator(attitude)
    if proposed is None:
        raise IntakeGraphContractError("INTAKE_RESPONDENT_ATTITUDE_INVALID")
    if proposed in _ABSENT_RESPONDENT_ATTITUDES:
        if grounded is not None or prior_is_substantive:
            raise IntakeGraphContractError("INTAKE_RESPONDENT_ATTITUDE_SOURCE_CONFLICT")
        return _without_respondent_attitude_patch(draft)
    if proposed not in _SUBSTANTIVE_RESPONDENT_ATTITUDES:
        raise IntakeGraphContractError("INTAKE_RESPONDENT_ATTITUDE_INVALID")
    if grounded is None:
        if (
            prior_is_substantive
            and prior is not None
            and canonicalize(attitude) == canonicalize(prior)
        ):
            return _without_respondent_attitude_patch(draft)
        raise IntakeGraphContractError("INTAKE_RESPONDENT_ATTITUDE_SOURCE_MISSING")
    if proposed != grounded.attitude:
        raise IntakeGraphContractError("INTAKE_RESPONDENT_ATTITUDE_SOURCE_CONFLICT")
    if grounded.current_message_id is not None:
        return _pin_model_direct_respondent_attitude_authority(
            draft,
            respondent_role=grounded.respondent_role,
            grounded_attitude=grounded.attitude,
            grounded_position=grounded.position,
            grounded_alternative_proposal=grounded.alternative_proposal,
            grounded_confidence=grounded.confidence,
            current_message_id=grounded.current_message_id,
        )
    return _pin_model_respondent_attitude_position(draft, grounded.position)


def _require_exact_handoff_inherited_respondent_attitude(
    state: IntakeGraphStateV2,
    draft: IntakeCognitionDraft,
    *,
    request: IntakeTurnRequest,
) -> IntakeCognitionDraft:
    """Carry only the exact frozen attitude; never ground it to remark text."""

    if (
        intake_case_detail_output_type(request)
        is not IntakeRemarkAcknowledgementLlmOutput
        or draft.conversation_action not in {"ACK_REMARK", "ACK_NO_REMARK"}
        or draft.matrix_patch is not None
    ):
        raise IntakeGraphContractError(
            "INTAKE_RESPONDENT_ATTITUDE_SOURCE_AUTHORITY_INVALID"
        )

    attitude = draft.dossier_patch.respondent_attitude
    prior = _prior_authoritative_respondent_attitude(state)
    if attitude is None and prior is None:
        return draft
    if (
        not isinstance(attitude, Mapping)
        or prior is None
        or not _validate_prior_respondent_attitude_authority(state, prior)
        or canonicalize(attitude) != canonicalize(prior)
    ):
        raise IntakeGraphContractError(
            "INTAKE_RESPONDENT_ATTITUDE_SOURCE_AUTHORITY_INVALID"
        )

    previous = request.previous_case_detail
    request_prior = (
        previous.get("respondent_attitude")
        if isinstance(previous, Mapping)
        else None
    )
    matrix_payload = (
        previous.get("case_fact_matrix")
        if isinstance(previous, Mapping)
        else None
    )
    if (
        not isinstance(request_prior, Mapping)
        or canonicalize(request_prior) != canonicalize(prior)
        or not isinstance(matrix_payload, Mapping)
    ):
        raise IntakeGraphContractError(
            "INTAKE_RESPONDENT_ATTITUDE_SOURCE_AUTHORITY_INVALID"
        )
    raw_matrix = deepcopy(dict(matrix_payload))
    try:
        matrix = FormalCaseFactMatrixV2.model_validate(raw_matrix)
    except ValueError as error:
        raise IntakeGraphContractError(
            "INTAKE_RESPONDENT_ATTITUDE_SOURCE_AUTHORITY_INVALID"
        ) from error
    if matrix.content_hash != case_fact_matrix_content_hash(raw_matrix):
        raise IntakeGraphContractError(
            "INTAKE_RESPONDENT_ATTITUDE_SOURCE_AUTHORITY_INVALID"
        )

    if prior.get("source") == DIRECT_RESPONDENT_SOURCE:
        direct = matrix.claims.respondent_direct
        grounding = prior.get("grounding")
        message_id = (
            grounding.get("message_id")
            if isinstance(grounding, Mapping)
            else None
        )
        if (
            direct is None
            or direct.respondent_role != prior.get("respondent_role")
            or direct.attitude != prior.get("attitude")
            or direct.position_summary != prior.get("position")
            or direct.alternative_proposal != prior.get("alternative_proposal")
            or not isinstance(message_id, str)
            or message_id not in direct.source_refs
        ):
            raise IntakeGraphContractError(
                "INTAKE_RESPONDENT_ATTITUDE_SOURCE_AUTHORITY_INVALID"
            )
    return _without_respondent_attitude_patch(draft)


def _require_deterministic_initial_form_respondent_attitude(
    state: IntakeGraphStateV2,
    draft: IntakeCognitionDraft,
    *,
    request: IntakeTurnRequest,
) -> IntakeCognitionDraft:
    """Retain only the reducer's exact, form-grounded subjective report."""

    if (
        request.turn_source != "FORM_SUBMISSION"
        or intake_case_detail_output_type(request)
        is not IntakeFreshFormOpeningLlmOutput
    ):
        raise IntakeGraphContractError(
            "INTAKE_RESPONDENT_ATTITUDE_SOURCE_AUTHORITY_INVALID"
        )
    private = state.get("bindings", {}).get("private", {})
    authority = state.get("node_results", {}).get(MATRIX_AUTHORITY_RECORD_KEY)
    initial = request.initial_case_facts
    initiator_role = getattr(initial, "initiator_role", None)
    if (
        not isinstance(authority, Mapping)
        or authority.get("schema_version") != "intake-matrix-authority.v1"
        or authority.get("kind") != "MATRIX_AUTHORITY"
        or authority.get("actor_role") not in {"USER", "MERCHANT"}
        or authority.get("initiator_role") not in {"USER", "MERCHANT"}
        or authority.get("actor_role") != authority.get("initiator_role")
        or authority.get("actor_role") != private.get("audience")
        or authority.get("actor_role") != request.agent_context.actor_role
        or authority.get("initiator_role") != initiator_role
    ):
        raise IntakeGraphContractError(
            "INTAKE_RESPONDENT_ATTITUDE_SOURCE_AUTHORITY_INVALID"
        )

    description = str(getattr(initial, "form_description", None) or "").strip()
    attributed_position = _reported_attitude_position(description, initiator_role)
    reported_attitude = attributed_reported_respondent_attitude(
        description,
        initiator_role,
    )
    if not description or not attributed_position or reported_attitude is None:
        return _without_respondent_attitude_patch(draft)

    attitude = draft.dossier_patch.respondent_attitude
    expected_fields = {
        "respondent_role",
        "attitude",
        "position",
        "source",
        "confidence",
        "confidence_note",
        "grounding",
    }
    expected_respondent_role = "MERCHANT" if initiator_role == "USER" else "USER"
    confidence = attitude.get("confidence") if isinstance(attitude, Mapping) else None
    grounding = attitude.get("grounding") if isinstance(attitude, Mapping) else None
    if (
        not isinstance(attitude, Mapping)
        or set(attitude) != expected_fields
        or attitude.get("respondent_role") != expected_respondent_role
        or attitude.get("attitude") not in _SUBSTANTIVE_RESPONDENT_ATTITUDES
        or not isinstance(attitude.get("position"), str)
        or attitude.get("position") != attributed_position
        or attitude.get("source") != SUBJECTIVE_RESPONDENT_SOURCE
        or isinstance(confidence, bool)
        or not isinstance(confidence, int | float)
        or not 0 <= confidence <= 1
        or attitude.get("confidence_note")
        != SUBJECTIVE_RESPONDENT_CONFIDENCE_NOTE
        or not isinstance(grounding, Mapping)
        or dict(grounding) != {"source": "INITIAL_FORM", "message_id": ""}
    ):
        raise IntakeGraphContractError(
            "INTAKE_RESPONDENT_ATTITUDE_SOURCE_AUTHORITY_INVALID"
        )
    return draft


def _prior_authoritative_respondent_attitude(
    state: IntakeGraphStateV2,
) -> dict[str, Any] | None:
    previous_context = state.get("baseline_previous_case_detail")
    if previous_context is None:
        previous = state.get("dossier_draft")
    elif (
        isinstance(previous_context, Mapping)
        and previous_context.get("schema_version") == "intake-baseline-context.v1"
    ):
        previous = unwrap_verified_baseline_previous_case_detail(state)
    else:
        previous = previous_context
    if not isinstance(previous, Mapping):
        return None
    attitude = previous.get("respondent_attitude")
    return deepcopy(dict(attitude)) if isinstance(attitude, Mapping) else None


def _verified_previous_case_detail(
    state: Mapping[str, Any],
) -> dict[str, Any]:
    """Return only a verified envelope snapshot or validated legacy snapshot.

    New checkpoints use the hash-bound baseline envelope.  Legacy checkpoints
    retain their already validated scroll snapshot directly; supporting that
    read-only shape is necessary for a safe first post-deployment remark turn.
    """

    previous_context = state.get("baseline_previous_case_detail")
    if (
        isinstance(previous_context, Mapping)
        and previous_context.get("schema_version") == "intake-baseline-context.v1"
    ):
        return unwrap_verified_baseline_previous_case_detail(state)
    if not isinstance(previous_context, Mapping):
        raise IntakeGraphContractError("INTAKE_BASELINE_CONTEXT_INVALID")
    return deepcopy(dict(previous_context))


def _validate_prior_respondent_attitude_authority(
    state: IntakeGraphStateV2,
    prior: Mapping[str, Any] | None,
) -> bool:
    if prior is None:
        return False
    discriminator = _respondent_attitude_discriminator(prior)
    if discriminator in _ABSENT_RESPONDENT_ATTITUDES:
        return False
    authority = state.get("node_results", {}).get(MATRIX_AUTHORITY_RECORD_KEY)
    initiator_role = authority.get("initiator_role") if isinstance(authority, Mapping) else None
    expected_respondent_role = (
        "MERCHANT"
        if initiator_role == "USER"
        else "USER"
        if initiator_role == "MERCHANT"
        else None
    )
    confidence = prior.get("confidence")
    position = prior.get("position")
    source = prior.get("source")
    grounding = prior.get("grounding")
    if (
        expected_respondent_role is None
        or prior.get("respondent_role") != expected_respondent_role
        or "attitude" not in prior
        or "status" in prior
        or discriminator not in _SUBSTANTIVE_RESPONDENT_ATTITUDES
        or not isinstance(position, str)
        or not position.strip()
        or source not in {SUBJECTIVE_RESPONDENT_SOURCE, DIRECT_RESPONDENT_SOURCE}
        or isinstance(confidence, bool)
        or not isinstance(confidence, int | float)
        or not 0 <= confidence <= 1
        or not isinstance(grounding, Mapping)
        or not {"source", "message_id"} <= set(grounding)
    ):
        raise IntakeGraphContractError("INTAKE_RESPONDENT_ATTITUDE_SOURCE_AUTHORITY_INVALID")
    grounding_source = grounding.get("source")
    message_id = grounding.get("message_id")
    if source == SUBJECTIVE_RESPONDENT_SOURCE:
        valid_grounding = (
            grounding_source == "INITIAL_FORM" and message_id == ""
        ) or (
            grounding_source == "PARTICIPANT_MESSAGE"
            and isinstance(message_id, str)
            and _IDENTIFIER.fullmatch(message_id) is not None
        )
        if (
            prior.get("confidence_note") != SUBJECTIVE_RESPONDENT_CONFIDENCE_NOTE
            or not valid_grounding
        ):
            raise IntakeGraphContractError(
                "INTAKE_RESPONDENT_ATTITUDE_SOURCE_AUTHORITY_INVALID"
            )
        return True
    if (
        grounding_source != "RESPONDENT_PARTICIPANT_MESSAGE"
        or not isinstance(message_id, str)
        or _IDENTIFIER.fullmatch(message_id) is None
    ):
        raise IntakeGraphContractError("INTAKE_RESPONDENT_ATTITUDE_SOURCE_AUTHORITY_INVALID")
    return True


def _without_respondent_attitude_patch(
    draft: IntakeCognitionDraft,
) -> IntakeCognitionDraft:
    normalized = draft.model_dump(mode="json", exclude_none=True, exclude_unset=True)
    dossier_patch = normalized.get("dossier_patch")
    if not isinstance(dossier_patch, dict):
        raise IntakeGraphContractError("INTAKE_LCEL_DOSSIER_PATCH_INVALID")
    dossier_patch.pop("respondent_attitude", None)
    return IntakeCognitionDraft.model_validate(normalized)


def _normalized_intake_room_utterance(value: str) -> str:
    """Preserve the prompt-owned Intake reply without semantic rewriting."""

    return value


def _normalize_model_dispute_core_state(
    state: IntakeGraphStateV2,
    draft: IntakeCognitionDraft,
) -> IntakeCognitionDraft:
    """Project provider field variants onto the baseline dispute-core contract.

    The model-facing dossier branch remains intentionally open for incremental
    patches, while Java formalization requires the baseline canonical fields.
    Normalize only from the current patch or already-authorized dossier state so
    a provider alias cannot turn a successful first attempt into a non-retryable
    cross-service contract failure.
    """

    if draft.dossier_patch.dispute_core_state is None:
        return draft

    normalized = draft.model_dump(mode="json", exclude_none=True, exclude_unset=True)
    dossier_patch = normalized.get("dossier_patch")
    if not isinstance(dossier_patch, dict):
        raise IntakeGraphContractError("INTAKE_LCEL_DOSSIER_PATCH_INVALID")
    proposed_core = dossier_patch.get("dispute_core_state")
    if not isinstance(proposed_core, dict):
        raise IntakeGraphContractError("INTAKE_DISPUTE_CORE_STATE_INVALID")

    current_dossier = state.get("dossier_draft", {})
    current_core = _object_branch(current_dossier, "dispute_core_state")
    proposed_focus = _object_branch(dossier_patch, "dispute_focus")
    current_focus = _object_branch(current_dossier, "dispute_focus")
    proposed_story = _object_branch(dossier_patch, "case_story")
    current_story = _object_branch(current_dossier, "case_story")
    proposed_missing = _object_branch(dossier_patch, "missing_information")
    current_missing = _object_branch(current_dossier, "missing_information")

    core_conflict = (
        _first_nonblank_field(proposed_core, "core_conflict", "core_issue")
        or _first_nonblank_field(current_core, "core_conflict", "core_issue")
        or _first_nonblank_field(proposed_focus, "core_conflict", "core_issue")
        or _first_nonblank_field(proposed_story, "one_sentence_summary", "summary")
        or _first_nonblank_field(current_focus, "core_conflict", "core_issue")
        or _first_nonblank_field(current_story, "one_sentence_summary", "summary")
    )
    if core_conflict is None:
        # The branch is optional model commentary, not formal matrix authority.
        # If it cannot be grounded to a canonical conflict, discard it and let
        # the Java formalizer derive the baseline from authorized case facts.
        dossier_patch.pop("dispute_core_state", None)
        return IntakeCognitionDraft.model_validate(normalized)

    facts_in_dispute = _coalesced_string_list(
        (
            (proposed_core, ("facts_in_dispute", "fact_disputes", "factual_disputes")),
            (current_core, ("facts_in_dispute", "fact_disputes", "factual_disputes")),
            (proposed_focus, ("facts_in_dispute", "focus_points")),
            (current_focus, ("facts_in_dispute", "focus_points")),
        ),
        limit=50,
    )
    next_verification_focus = _coalesced_string_list(
        (
            (proposed_core, ("next_verification_focus", "verification_focus")),
            (current_core, ("next_verification_focus", "verification_focus")),
            (proposed_focus, ("next_verification_focus", "facts_to_verify")),
            (
                proposed_missing,
                (
                    "next_verification_focus",
                    "blocking_gaps",
                    "missing_fields",
                    "missing_facts",
                ),
            ),
            (current_focus, ("next_verification_focus", "facts_to_verify")),
            (
                current_missing,
                (
                    "next_verification_focus",
                    "blocking_gaps",
                    "missing_fields",
                    "missing_facts",
                ),
            ),
        ),
        limit=20,
    )

    canonical_core: dict[str, Any] = {
        "core_conflict": core_conflict,
        "facts_in_dispute": facts_in_dispute,
        "next_verification_focus": next_verification_focus,
    }
    conflict_type = _first_nonblank_field(proposed_core, "conflict_type") or _first_nonblank_field(
        current_core, "conflict_type"
    )
    if conflict_type is not None:
        canonical_core["conflict_type"] = conflict_type
    dossier_patch["dispute_core_state"] = canonical_core
    return IntakeCognitionDraft.model_validate(normalized)


def _object_branch(owner: Mapping[str, Any], field: str) -> Mapping[str, Any]:
    value = owner.get(field)
    return value if isinstance(value, Mapping) else {}


def _first_nonblank_field(owner: Mapping[str, Any], *fields: str) -> str | None:
    for field in fields:
        value = owner.get(field)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return None


def _first_string_list(
    owner: Mapping[str, Any],
    *fields: str,
    limit: int,
) -> list[str] | None:
    for field in fields:
        if field not in owner:
            continue
        value = owner[field]
        if value is None:
            continue
        if not isinstance(value, list):
            continue
        if not value:
            # Model output has no authority to clear an already canonical list.
            # Treat an explicit empty collection as an absent candidate and keep
            # searching the current dossier and grounded fallback branches.
            continue
        normalized: list[str] = []
        seen: set[str] = set()
        for item in value:
            if not isinstance(item, str) or not item.strip():
                normalized = []
                break
            text = item.strip()
            if text not in seen:
                normalized.append(text)
                seen.add(text)
        if len(normalized) > limit or (value and not normalized):
            continue
        return normalized
    return None


def _coalesced_string_list(
    candidates: tuple[tuple[Mapping[str, Any], tuple[str, ...]], ...],
    *,
    limit: int,
) -> list[str]:
    for owner, fields in candidates:
        value = _first_string_list(owner, *fields, limit=limit)
        if value is not None:
            return value
    return []


@dataclass(frozen=True, slots=True)
class _AuthorizedTurnSource:
    message_id: str | None
    text: str


@dataclass(frozen=True, slots=True)
class _GroundedRespondentAttitude:
    attitude: str
    position: str
    alternative_proposal: str | None
    confidence: float
    respondent_role: str
    current_message_id: str | None


def _grounded_respondent_attitude(
    state: IntakeGraphStateV2,
    *,
    draft: IntakeCognitionDraft,
) -> _GroundedRespondentAttitude | None:
    private = state.get("bindings", {}).get("private", {})
    authority = state.get("node_results", {}).get(MATRIX_AUTHORITY_RECORD_KEY)
    if (
        not isinstance(authority, Mapping)
        or authority.get("schema_version") != "intake-matrix-authority.v1"
        or authority.get("kind") != "MATRIX_AUTHORITY"
        or authority.get("actor_role") not in {"USER", "MERCHANT"}
        or authority.get("initiator_role") not in {"USER", "MERCHANT"}
        or authority.get("actor_role") != private.get("audience")
    ):
        raise IntakeGraphContractError("INTAKE_RESPONDENT_ATTITUDE_SOURCE_AUTHORITY_INVALID")
    actor_role = authority["actor_role"]
    initiator_role = authority["initiator_role"]
    source = _current_authorized_turn_source(
        state,
        actor_role,
        allow_initial_form=actor_role == initiator_role,
    )
    if source is None:
        return None
    if actor_role != initiator_role:
        if source.message_id is None:
            raise IntakeGraphContractError("INTAKE_RESPONDENT_ATTITUDE_SOURCE_AUTHORITY_INVALID")
        matrix_patch = draft.matrix_patch
        if not isinstance(matrix_patch, CaseFactMatrixDeltaV2):
            raise IntakeGraphContractError(
                "INTAKE_RESPONDENT_ATTITUDE_SOURCE_AUTHORITY_INVALID"
            )
        if not any(
            row.source_scope in {"CURRENT_SOURCE", "PREVIOUS_AND_CURRENT_SOURCE"}
            and row.stance != "NOT_ADDRESSED"
            for row in matrix_patch.fact_rows
        ):
            raise IntakeGraphContractError(
                "INTAKE_RESPONDENT_ATTITUDE_SOURCE_AUTHORITY_INVALID"
            )
        claim = matrix_patch.respondent_claim
        if claim is None:
            raise IntakeGraphContractError(
                "INTAKE_RESPONDENT_ATTITUDE_SOURCE_AUTHORITY_INVALID"
            )
        detection = detect_direct_respondent_attitude(
            source.text,
            source_authority=RESPONDENT_AUTHORED_CURRENT_MESSAGE,
            respondent_role=actor_role,
        )
        if detection.state == "UNRESOLVED":
            raise IntakeGraphContractError("INTAKE_RESPONDENT_ATTITUDE_SOURCE_UNRESOLVED")
        if claim.attitude == "NOT_ADDRESSED":
            if detection.state == "SUBSTANTIVE":
                raise IntakeGraphContractError(
                    "INTAKE_RESPONDENT_ATTITUDE_SOURCE_UNRESOLVED"
                )
            return None
        if claim.attitude not in _SUBSTANTIVE_RESPONDENT_ATTITUDES:
            raise IntakeGraphContractError(
                "INTAKE_RESPONDENT_ATTITUDE_SOURCE_UNRESOLVED"
            )
        if detection.state == "SUBSTANTIVE":
            candidate = detection.candidate
            if (
                not isinstance(candidate, Mapping)
                or candidate.get("attitude") != claim.attitude
            ):
                raise IntakeGraphContractError(
                    "INTAKE_RESPONDENT_ATTITUDE_SOURCE_UNRESOLVED"
                )
        return _GroundedRespondentAttitude(
            attitude=claim.attitude,
            position=claim.position_summary,
            alternative_proposal=claim.alternative_proposal,
            confidence=DIRECT_RESPONDENT_CONFIDENCE,
            respondent_role=actor_role,
            current_message_id=source.message_id,
        )
    reported = attributed_reported_respondent_attitude(source.text, initiator_role)
    grounded = _grounded_attitude_value(
        reported,
        fallback_position=source.text,
    )
    if grounded is None:
        return None
    return _GroundedRespondentAttitude(
        attitude=grounded[0],
        position=grounded[1],
        alternative_proposal=None,
        confidence=grounded[2],
        respondent_role=("MERCHANT" if initiator_role == "USER" else "USER"),
        current_message_id=None,
    )


def _current_authorized_turn_source(
    state: IntakeGraphStateV2,
    actor_role: str,
    *,
    allow_initial_form: bool,
) -> _AuthorizedTurnSource | None:
    messages = state.get("messages", {})
    if not isinstance(messages, Mapping):
        raise IntakeGraphContractError("INTAKE_RESPONDENT_ATTITUDE_SOURCE_AUTHORITY_INVALID")
    human_messages: list[tuple[int, str, str]] = []
    for key, message in messages.items():
        if (
            not isinstance(message, Mapping)
            or message.get("role") != "HUMAN"
            or message.get("audience") != actor_role
        ):
            continue
        message_id = message.get("message_id")
        content = message.get("content")
        sequence = message.get("sequence")
        if (
            not isinstance(key, str)
            or not isinstance(message_id, str)
            or key != message_id
            or _IDENTIFIER.fullmatch(message_id) is None
            or not isinstance(content, str)
            or not content.strip()
            or isinstance(sequence, bool)
            or not isinstance(sequence, int)
            or sequence < 0
        ):
            raise IntakeGraphContractError("INTAKE_RESPONDENT_ATTITUDE_SOURCE_AUTHORITY_INVALID")
        human_messages.append((sequence, message_id, content.strip()))
    if human_messages:
        latest_sequence = max(message[0] for message in human_messages)
        current = [message for message in human_messages if message[0] == latest_sequence]
        if len(current) != 1:
            raise IntakeGraphContractError("INTAKE_RESPONDENT_ATTITUDE_SOURCE_AUTHORITY_INVALID")
        _, message_id, text = current[0]
        return _AuthorizedTurnSource(message_id=message_id, text=text)
    if not allow_initial_form:
        return None
    summary = state.get("memory_summary", "")
    if not isinstance(summary, str) or not summary:
        return None
    try:
        memory = json.loads(summary)
    except (TypeError, json.JSONDecodeError):
        return None
    initial = memory.get("authorized_initial_case_facts") if isinstance(memory, dict) else None
    description = initial.get("form_description") if isinstance(initial, Mapping) else None
    if not isinstance(description, str) or not description.strip():
        return None
    return _AuthorizedTurnSource(message_id=None, text=description.strip())


def _grounded_attitude_value(
    reported: Mapping[str, Any] | None,
    *,
    fallback_position: str,
) -> tuple[str, str, float] | None:
    if not isinstance(reported, Mapping):
        return None
    attitude = reported.get("attitude")
    if attitude not in _SUBSTANTIVE_RESPONDENT_ATTITUDES:
        return None
    position = reported.get("position")
    confidence = reported.get("confidence")
    if (
        isinstance(confidence, bool)
        or not isinstance(confidence, int | float)
        or not 0 <= confidence <= 1
    ):
        return None
    return (
        cast(str, attitude),
        position.strip() if isinstance(position, str) and position.strip() else fallback_position,
        float(confidence),
    )


def _pin_model_respondent_attitude_position(
    draft: IntakeCognitionDraft,
    grounded_position: str,
) -> IntakeCognitionDraft:
    normalized = draft.model_dump(mode="json", exclude_none=True, exclude_unset=True)
    dossier_patch = normalized.get("dossier_patch")
    attitude = dossier_patch.get("respondent_attitude") if isinstance(dossier_patch, dict) else None
    if not isinstance(attitude, dict):
        raise IntakeGraphContractError("INTAKE_RESPONDENT_ATTITUDE_INVALID")
    position_field = next(
        (
            field
            for field in ("position_summary", "position", "note")
            if isinstance(attitude.get(field), str) and attitude[field].strip()
        ),
        None,
    )
    if position_field is None:
        raise IntakeGraphContractError("INTAKE_RESPONDENT_ATTITUDE_INVALID")
    attitude[position_field] = grounded_position
    return IntakeCognitionDraft.model_validate(normalized)


def _pin_model_direct_respondent_attitude_authority(
    draft: IntakeCognitionDraft,
    *,
    respondent_role: str,
    grounded_attitude: str,
    grounded_position: str,
    grounded_alternative_proposal: str | None,
    grounded_confidence: float,
    current_message_id: str,
) -> IntakeCognitionDraft:
    if grounded_confidence != DIRECT_RESPONDENT_CONFIDENCE:
        raise IntakeGraphContractError("INTAKE_RESPONDENT_ATTITUDE_SOURCE_UNRESOLVED")
    normalized = draft.model_dump(mode="json", exclude_none=True, exclude_unset=True)
    dossier_patch = normalized.get("dossier_patch")
    attitude = dossier_patch.get("respondent_attitude") if isinstance(dossier_patch, dict) else None
    if not isinstance(attitude, dict):
        raise IntakeGraphContractError("INTAKE_RESPONDENT_ATTITUDE_INVALID")
    if not any(
        isinstance(attitude.get(field), str) and attitude[field].strip()
        for field in ("position_summary", "position", "note")
    ):
        raise IntakeGraphContractError("INTAKE_RESPONDENT_ATTITUDE_INVALID")
    canonical: dict[str, Any] = {
        "respondent_role": respondent_role,
        "attitude": grounded_attitude,
        "position": grounded_position,
        "confidence": grounded_confidence,
        "source": DIRECT_RESPONDENT_SOURCE,
        "grounding": {
            "source": "RESPONDENT_PARTICIPANT_MESSAGE",
            "message_id": current_message_id,
        },
    }
    if grounded_alternative_proposal is not None:
        canonical["alternative_proposal"] = grounded_alternative_proposal
    if "confidence" in attitude:
        confidence = attitude["confidence"]
        if (
            isinstance(confidence, bool)
            or not isinstance(confidence, int | float)
            or not 0 <= confidence <= 1
        ):
            raise IntakeGraphContractError("INTAKE_RESPONDENT_ATTITUDE_INVALID")
    dossier_patch["respondent_attitude"] = canonical
    return IntakeCognitionDraft.model_validate(normalized)


def _normalize_model_matrix_fact_keys(
    state: IntakeGraphStateV2,
    draft: IntakeCognitionDraft,
) -> IntakeCognitionDraft:
    """Demote model-minted formal-looking fact keys to proposal-local keys.

    A model has no authority to mint a stable ``FACT_*`` identifier.  Providers
    nevertheless sometimes use that prefix for a genuinely new unilateral fact,
    even when the prompt requires ``NEW_*``.  Preserve every FACT key that is
    already visible in the authorized semantic baseline context, but
    deterministically rewrite an unknown one to the proposal-local namespace
    before the normal matrix policy validates fingerprints, sources, membership,
    and summary references.  Any ambiguous collision remains a hard contract
    failure.
    """

    matrix_patch = draft.matrix_patch
    if not isinstance(
        matrix_patch,
        (UnilateralCaseMatrixDraftV1, CaseFactMatrixDeltaV2),
    ):
        return draft

    matrix_payload = matrix_patch.model_dump(mode="json", exclude_none=True)
    normalized_patch = normalize_model_matrix_fact_key_payload(
        matrix_payload,
        authorized_fact_ids=intake_baseline_authorized_fact_ids(state),
    )
    if normalized_patch == matrix_payload:
        return draft

    normalized = draft.model_dump(mode="json", exclude_none=True, exclude_unset=True)
    normalized["matrix_patch"] = normalized_patch
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
    *,
    agent_context: AgentInvocationContext,
    room_utterance_is_baseline_finalized: bool = False,
) -> None:
    output = draft.model_dump(mode="json", exclude_none=True, exclude_unset=True)
    catalog = _source_catalog(state)
    existing_fact_ids = intake_baseline_authorized_fact_ids(state)
    previous_context = state.get("baseline_previous_case_detail")
    previous_public = merge_intake_dossier(state["dossier_draft"], {})
    validation_authority = (
        _verified_previous_case_detail(state)
        if previous_context is not None
        else previous_public
    )
    _validate_output_tree(
        _business_output_guard_view(validation_authority, output),
        audience=state["bindings"]["private"]["audience"],
        source_catalog=catalog,
        existing_fact_ids=existing_fact_ids,
        inherited_refs=frozenset(),
    )
    validate_matrix_patch(state, output.get("matrix_patch"))
    patch = output.get("dossier_patch", {})
    if not isinstance(patch, dict):
        raise IntakeGraphContractError("INTAKE_LCEL_DOSSIER_PATCH_INVALID")
    merged = merge_intake_dossier(state["dossier_draft"], patch)
    isolated_patch = patch
    if (
        output.get("matrix_patch") is not None
        and previous_public.get("handoff_remark_partition") is not None
    ):
        successor_matrix, _, _ = _post_normalizer_formal_matrix(
            state,
            agent_context=agent_context,
            draft=draft,
        )
        previous_context = state.get("baseline_previous_case_detail")
        authority_dossier = (
            _verified_previous_case_detail(state)
            if previous_context is not None
            else state["dossier_draft"]
        )
        previous_public = rebind_matrix_successor_handoff_partition(
            previous_public,
            authority_dossier=authority_dossier,
            successor_matrix=successor_matrix,
        )
        isolated_patch = deepcopy(patch)
        isolated_patch.pop("handoff_remark_partition", None)
    validate_dossier_transition(previous_public, merged)
    validate_dossier_transition({}, isolated_patch)

    readiness = draft.readiness
    recommendation = draft.recommendation
    if (readiness == "READY_TO_CONFIRM") != (recommendation == "ACCEPTED") or (
        recommendation == "NOT_ADMISSIBLE" and readiness != "NEEDS_REVIEW"
    ):
        raise IntakeGraphContractError("INTAKE_LCEL_READINESS_PRECONDITION_FAILED")

    # Respondent opening owns only the opening text.  Its deterministic M0 carry
    # deliberately predates the optional-remark action protocol.
    if state.get("route") == "respondent_opening":
        return
    action = _conversation_action(draft)
    reducer_status = _actor_remark_status(state, draft)
    expected_statuses = INTAKE_ACTION_GATE_ACTION_STATUSES[action]
    if reducer_status not in expected_statuses:
        raise IntakeGraphContractError("INTAKE_CONVERSATION_ACTION_REDUCER_CONFLICT")

    questions = _actor_next_questions(state, draft)
    if action == "ASK_SUBSTANTIVE":
        if readiness != "INCOMPLETE" or not questions:
            raise IntakeGraphContractError("INTAKE_CONVERSATION_ACTION_TEXT_CONFLICT")
        return
    if readiness != "READY_TO_CONFIRM" or questions:
        raise IntakeGraphContractError("INTAKE_CONVERSATION_ACTION_TEXT_CONFLICT")


def _business_output_guard_view(
    authority_dossier: Mapping[str, Any],
    output: Mapping[str, Any],
) -> dict[str, Any]:
    """Hide only an exact trusted formal-confirmation carry from model-field scanning.

    The baseline adapter may copy Java's frozen handoff source into the normalized
    draft.  ``command_id`` remains forbidden everywhere else: a source is removed
    only from this disposable validation view when its canonical identity, role,
    status, and partition schema exactly match the authoritative prior dossier.
    The original output is retained for the existing dossier/matrix transition
    checks below this guard.
    """

    validation_view = deepcopy(dict(output))
    authority_partition = authority_dossier.get("handoff_remark_partition")
    dossier_patch = validation_view.get("dossier_patch")
    output_partition = (
        dossier_patch.get("handoff_remark_partition")
        if isinstance(dossier_patch, Mapping)
        else None
    )
    if (
        not isinstance(authority_partition, Mapping)
        or not isinstance(output_partition, Mapping)
        or authority_partition.get("schema_version") != "handoff_remark_partition.v1"
        or output_partition.get("schema_version")
        != authority_partition.get("schema_version")
    ):
        return validation_view

    authority_parties = authority_partition.get("parties")
    output_parties = output_partition.get("parties")
    if not isinstance(authority_parties, Mapping) or not isinstance(
        output_parties,
        Mapping,
    ):
        return validation_view

    for role in ("USER", "MERCHANT"):
        authority_party = authority_parties.get(role)
        output_party = output_parties.get(role)
        if not isinstance(authority_party, Mapping) or not isinstance(
            output_party,
            dict,
        ):
            continue
        authority_source = authority_party.get("source")
        output_source = output_party.get("source")
        if (
            authority_party.get("party_role") == role
            and output_party.get("party_role") == role
            and output_party.get("remark_status")
            == authority_party.get("remark_status")
            and _is_canonical_formal_confirmation_source(authority_source)
            and isinstance(output_source, Mapping)
            and dict(output_source) == dict(authority_source)
        ):
            output_party.pop("source")
    return validation_view


def _is_canonical_formal_confirmation_source(value: Any) -> bool:
    return bool(
        isinstance(value, Mapping)
        and set(value) == {"source_kind", "command_id", "request_hash"}
        and value.get("source_kind") == "FORMAL_CONFIRMATION"
        and isinstance(value.get("command_id"), str)
        and _IDENTIFIER.fullmatch(value["command_id"])
        and isinstance(value.get("request_hash"), str)
        and _SHA256.fullmatch(value["request_hash"])
    )


def _conversation_action(draft: IntakeCognitionDraft) -> str:
    action = getattr(draft, "conversation_action", None)
    if action not in _INTAKE_CONVERSATION_ACTIONS:
        raise IntakeGraphContractError("INTAKE_CONVERSATION_ACTION_INVALID")
    return cast(str, action)


def _actor_intake_entry(
    state: Mapping[str, Any],
    draft: IntakeCognitionDraft,
) -> Mapping[str, Any]:
    actor = state.get("bindings", {}).get("private", {}).get("audience")
    party_state = draft.dossier_patch.party_intake_state
    if actor not in {"USER", "MERCHANT"} or party_state is None:
        raise IntakeGraphContractError("INTAKE_CONVERSATION_ACTION_REDUCER_CONFLICT")
    entry = getattr(party_state, cast(str, actor), None)
    if not isinstance(entry, Mapping):
        raise IntakeGraphContractError("INTAKE_CONVERSATION_ACTION_REDUCER_CONFLICT")
    return entry


def _actor_remark_status(
    state: Mapping[str, Any],
    draft: IntakeCognitionDraft,
) -> str:
    entry = _actor_intake_entry(state, draft)
    notes = entry.get("handoff_notes")
    status = notes.get("remark_status") if isinstance(notes, Mapping) else None
    partition = draft.dossier_patch.handoff_remark_partition
    actor = state.get("bindings", {}).get("private", {}).get("audience")
    partition_party = (
        getattr(partition.parties, cast(str, actor), None)
        if partition is not None and actor in {"USER", "MERCHANT"}
        else None
    )
    if not isinstance(status, str):
        raise IntakeGraphContractError("INTAKE_CONVERSATION_ACTION_REDUCER_CONFLICT")
    if partition_party is None:
        if status == "NOT_READY":
            return status
        raise IntakeGraphContractError("INTAKE_CONVERSATION_ACTION_REDUCER_CONFLICT")
    if partition_party.remark_status != status:
        raise IntakeGraphContractError("INTAKE_CONVERSATION_ACTION_REDUCER_CONFLICT")
    return status


def _actor_next_questions(
    state: Mapping[str, Any],
    draft: IntakeCognitionDraft,
) -> tuple[str, ...]:
    entry = _actor_intake_entry(state, draft)
    missing = entry.get("missing_information")
    questions = missing.get("next_questions") if isinstance(missing, Mapping) else None
    if not isinstance(questions, list) or any(
        not isinstance(question, str) or not question.strip() for question in questions
    ):
        raise IntakeGraphContractError("INTAKE_CONVERSATION_ACTION_REDUCER_CONFLICT")
    return tuple(questions)


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
            # The deterministic baseline represents form-only respondent
            # grounding with this exact two-field sentinel.  It is provenance
            # metadata, not a reference to a participant message.  Do not
            # generalize this exemption to empty IDs in other envelopes.
            if (
                key == "message_id"
                and candidate == ""
                and set(value) == {"source", "message_id"}
                and value.get("source") == "INITIAL_FORM"
            ):
                continue
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

    node_results = state.get("node_results")
    if isinstance(node_results, Mapping):
        for record_key, record in node_results.items():
            if _is_trusted_initial_form_source_record(record_key, record, node_results, state):
                register(record["stable_id"], record["content_hash"])

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


def _is_trusted_initial_form_source_record(
    record_key: Any,
    record: Any,
    node_results: Mapping[Any, Any],
    state: Mapping[str, Any],
) -> bool:
    """Accept only the initial-form source receipt paired with its event receipt."""

    last_event_sequence = state.get("last_event_sequence")
    last_event_ref = state.get("last_event_ref")
    last_event_hash = state.get("last_event_hash")
    if (
        not isinstance(last_event_sequence, int)
        or isinstance(last_event_sequence, bool)
        or last_event_sequence != 1
        or not isinstance(last_event_ref, str)
        or _IDENTIFIER.fullmatch(last_event_ref) is None
        or not isinstance(last_event_hash, str)
        or _SHA256.fullmatch(last_event_hash) is None
    ):
        return False

    bindings = state.get("bindings")
    private = bindings.get("private") if isinstance(bindings, Mapping) else None
    case_id = private.get("case_id") if isinstance(private, Mapping) else None
    if not isinstance(case_id, str):
        return False

    if (
        not isinstance(record, Mapping)
        or record.get("kind") != "INITIAL_FORM_SOURCE"
        or record.get("source_type") != "INITIAL_FORM"
        or not isinstance(record.get("sequence"), int)
        or isinstance(record.get("sequence"), bool)
        or record.get("sequence") != 1
        or not isinstance(record.get("stable_id"), str)
        or _IDENTIFIER.fullmatch(record["stable_id"]) is None
        or not isinstance(record.get("content_hash"), str)
        or _SHA256.fullmatch(record["content_hash"]) is None
    ):
        return False

    stable_id = record["stable_id"]
    content_hash = record["content_hash"]
    if (
        stable_id != f"INTAKE_FORM_{case_id}"
        or record_key != _stable_node_result_key("message", stable_id)
        or content_hash != last_event_hash
    ):
        return False
    event = node_results.get(_stable_node_result_key("event", last_event_ref))
    return (
        isinstance(event, Mapping)
        and event.get("kind") == "EVENT"
        and event.get("stable_id") == last_event_ref
        and event.get("message_id") == stable_id
        and event.get("content_hash") == content_hash
        and event.get("source_type") == record["source_type"]
        and isinstance(event.get("sequence"), int)
        and not isinstance(event.get("sequence"), bool)
        and event.get("sequence") == record["sequence"]
        and event.get("source_refs") == [stable_id]
    )


def _stable_node_result_key(kind: str, stable_id: str) -> str:
    digest = hashlib.sha256(stable_id.encode("utf-8")).hexdigest()
    return f"{kind}:{digest}"


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


def _canonical_text(value: Any) -> str:
    try:
        return canonicalize(value).decode("utf-8")
    except (TypeError, ValueError, UnicodeDecodeError) as error:
        raise IntakeGraphContractError("INTAKE_LCEL_PROMPT_VALUE_INVALID") from error


__all__ = [
    "BuiltIntakeModelNode",
    "INTAKE_ACTION_GATE_ACTION_STATUSES",
    "INTAKE_ACTION_GATE_KEY_PREFIX",
    "INTAKE_ACTION_GATE_KIND",
    "INTAKE_ACTION_GATE_SCHEMA_VERSION",
    "INTAKE_SYSTEM_PROMPT",
    "IntakePromptInput",
    "build_intake_model_node",
]
