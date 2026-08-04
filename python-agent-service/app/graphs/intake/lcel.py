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

from langchain_core.messages import AIMessage
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
from app.agents.dispute_intake_officer.case_fact_matrix import finalize_case_fact_matrix
from app.graph_runtime.state_lens import StateLens
from app.agents.dispute_intake_officer.schemas import IntakeCaseDetailLlmOutput
from app.agents.dispute_intake_officer.skills.dossier.dossier_skill import (
    DIRECT_RESPONDENT_SOURCE,
    SUBJECTIVE_RESPONDENT_CONFIDENCE_NOTE,
    SUBJECTIVE_RESPONDENT_SOURCE,
    attributed_reported_respondent_attitude,
    detect_direct_respondent_attitude,
)
from app.harness.prompt_composer import PromptComposer
from app.graphs.intake.baseline import (
    BASELINE_INTAKE_NODE_NAME,
    adapt_intake_baseline_output,
    build_intake_baseline_request,
    intake_baseline_authorized_fact_ids,
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
    unwrap_verified_baseline_previous_case_detail,
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
from app.schemas.case_fact_matrix import CaseFactMatrixDeltaV2 as FormalCaseFactMatrixDeltaV2
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
_IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_NO_TOOLS_POLICY_VERSION = "no-tools.v1"
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
    parser: PydanticOutputParser[IntakeCaseDetailLlmOutput]
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
        route = state.get("route")
        has_snapshot = state.get("initial_snapshot_hash") is not None
        has_event = state.get("last_event_hash") is not None
        # A snapshot-only opening has no participant event cursor.  Its durable
        # source identity is the imported snapshot hash (also used by the
        # response-message ID), while all subsequent model turns remain bound
        # to a participant event.
        if not (
            has_snapshot
            and ((route == "initialize" and not has_event) or (route == "message" and has_event))
        ):
            raise IntakeGraphContractError("INTAKE_LCEL_ROUTE_INVALID")
        # An imported formal M0 can authorize only an actual participant room
        # statement.  The SNAPSHOT and BOOTSTRAP INITIAL_FORM openings have no
        # current HUMAN message, so fail before the lens/prompt/model boundary
        # rather than silently deriving a successor from form-only context.
        if _opening_imported_formal_matrix_without_current_party_message(state):
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
        return validate_cognition_patch(
            state,
            patch,
            require_baseline_pending_context=True,
        )


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
    model_output_type: type[IntakeCaseDetailLlmOutput]
    parser: PydanticOutputParser[IntakeCaseDetailLlmOutput]
    parser_pydantic_object: type[IntakeCaseDetailLlmOutput]
    parser_diff: bool
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


def _seal_intake_components(
    *,
    lens: StateLens[IntakeGraphStateV2, IntakePromptInput],
    prompt: ChatPromptTemplate,
    model: GovernedChatModel,
    parser: PydanticOutputParser[IntakeCaseDetailLlmOutput],
    preflight: IntakeModelPreflightRunnable,
    guardrail: IntakeGuardrailRunnable,
    patch_projector: IntakePatchProjectorRunnable,
    profile: ModelProfile,
    policy: ModelInvocationPolicy,
    agent_context: AgentInvocationContext,
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
        output_type=IntakeCaseDetailLlmOutput,
        profile=profile,
        policy=policy,
        visible_fields=_TARGET_INTAKE_VISIBLE_FIELDS,
    )
    parser = PydanticOutputParser(pydantic_object=IntakeCaseDetailLlmOutput)
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
        preflight=preflight,
        model_flow=cast(Runnable[IntakeGraphStateV2, Mapping[str, Any]], model_flow),
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
    message = generation.get("message")
    draft = generation.get("draft")
    if not isinstance(message, AIMessage) or not isinstance(
        draft,
        IntakeCaseDetailLlmOutput,
    ):
        raise IntakeGraphContractError("INTAKE_LCEL_GENERATION_INVALID")
    typed_state = cast(IntakeGraphStateV2, state)
    adapted = adapt_intake_baseline_output(
        typed_state,
        agent_context=agent_context,
        output=draft,
    )
    normalized = _normalize_model_matrix_fact_keys(typed_state, adapted)
    normalized = _normalize_model_respondent_attitude(typed_state, normalized)
    return (
        typed_state,
        message,
        adapted,
        _normalize_model_dispute_core_state(typed_state, normalized),
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
        request = build_intake_baseline_request(state, agent_context=agent_context)
        matrix = finalize_case_fact_matrix(
            request=request,
            case_detail=deepcopy(materialized),
            delta=delta,
        )
    except (AgentOutputSchemaError, ValueError) as error:
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
    if attitude is None:
        return draft
    grounded = _grounded_respondent_attitude(state)
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
    grounded_attitude, grounded_position = grounded
    if proposed != grounded_attitude:
        raise IntakeGraphContractError("INTAKE_RESPONDENT_ATTITUDE_SOURCE_CONFLICT")
    return _pin_model_respondent_attitude_position(draft, grounded_position)


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


def _grounded_respondent_attitude(
    state: IntakeGraphStateV2,
) -> tuple[str, str] | None:
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
    source_text = _current_authorized_turn_text(
        state,
        actor_role,
        allow_initial_form=actor_role == initiator_role,
    )
    if not source_text:
        return None
    if actor_role != initiator_role:
        detection = detect_direct_respondent_attitude(source_text)
        if detection.state == "UNRESOLVED":
            raise IntakeGraphContractError("INTAKE_RESPONDENT_ATTITUDE_SOURCE_UNRESOLVED")
        if detection.state == "NONE":
            return None
        candidate = detection.candidate
        if not isinstance(candidate, Mapping):
            raise IntakeGraphContractError("INTAKE_RESPONDENT_ATTITUDE_SOURCE_UNRESOLVED")
        attitude = candidate.get("attitude")
        if attitude not in _SUBSTANTIVE_RESPONDENT_ATTITUDES:
            raise IntakeGraphContractError("INTAKE_RESPONDENT_ATTITUDE_SOURCE_UNRESOLVED")
        return cast(str, attitude), source_text
    reported = attributed_reported_respondent_attitude(source_text, initiator_role)
    return _grounded_attitude_value(
        reported,
        fallback_position=source_text,
    )


def _current_authorized_turn_text(
    state: IntakeGraphStateV2,
    actor_role: str,
    *,
    allow_initial_form: bool,
) -> str:
    human_messages = [
        message
        for message in state.get("messages", {}).values()
        if isinstance(message, Mapping)
        and message.get("role") == "HUMAN"
        and message.get("audience") == actor_role
        and isinstance(message.get("content"), str)
        and message["content"].strip()
    ]
    if human_messages:
        current = max(
            human_messages,
            key=lambda message: (message.get("sequence", 0), message.get("message_id", "")),
        )
        return cast(str, current["content"]).strip()
    if not allow_initial_form:
        return ""
    summary = state.get("memory_summary", "")
    if not isinstance(summary, str) or not summary:
        return ""
    try:
        memory = json.loads(summary)
    except (TypeError, json.JSONDecodeError):
        return ""
    initial = memory.get("authorized_initial_case_facts") if isinstance(memory, dict) else None
    description = initial.get("form_description") if isinstance(initial, Mapping) else None
    return description.strip() if isinstance(description, str) else ""


def _grounded_attitude_value(
    reported: Mapping[str, Any] | None,
    *,
    fallback_position: str,
) -> tuple[str, str] | None:
    if not isinstance(reported, Mapping):
        return None
    attitude = reported.get("attitude")
    if attitude not in _SUBSTANTIVE_RESPONDENT_ATTITUDES:
        return None
    position = reported.get("position")
    return (
        cast(str, attitude),
        position.strip() if isinstance(position, str) and position.strip() else fallback_position,
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
    room_utterance_is_baseline_finalized: bool = False,
) -> None:
    output = draft.model_dump(mode="json", exclude_none=True, exclude_unset=True)
    catalog = _source_catalog(state)
    existing_fact_ids = intake_baseline_authorized_fact_ids(state)
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
    merged = merge_intake_dossier(state["dossier_draft"], patch)
    previous_public = merge_intake_dossier(state["dossier_draft"], {})
    validate_dossier_transition(previous_public, merged)
    validate_dossier_transition({}, patch)

    readiness = draft.readiness
    recommendation = draft.recommendation
    if (readiness == "READY_TO_CONFIRM") != (recommendation == "ACCEPTED") or (
        recommendation == "NOT_ADMISSIBLE" and readiness != "NEEDS_REVIEW"
    ):
        raise IntakeGraphContractError("INTAKE_LCEL_READINESS_PRECONDITION_FAILED")


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
    "INTAKE_SYSTEM_PROMPT",
    "IntakePromptInput",
    "build_intake_model_node",
]
