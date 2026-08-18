"""Deployment-owned bindings for SHADOW and isolated target-E2E runtimes."""

from __future__ import annotations

from collections.abc import Callable, Iterable, Mapping
import hmac
import re
from typing import Any, cast

from app.api.graph_lifecycle import (
    GraphExecutorKernel,
    GraphRuntimeBindings,
)
from app.api.graph_stream_service import (
    ExactShadowExecutorRegistry,
    ProviderRuntimeBinding,
    ShadowExecutorRegistration,
)
from app.agents.evidence_clerk.workflow import EVIDENCE_TURN_MODEL_NODE_NAME
from app.agents.evidence_clerk.v2_workflow import EvidenceTurnWorkflowV2
from app.agents.hearing_flow import HearingFlowWorkflows
from app.config import (
    GraphShadowBindingSettings,
    GraphShadowInputSettings,
    GraphShadowThreadSettings,
    Settings,
)
from app.contracts.v1.codec import canonical_sha256
from app.contracts.v1.models import (
    ExecutionMetadata,
    RoomGraphCommand,
    SnapshotRef,
    Usage,
)
from app.graph_runtime.compiled_executor import (
    CompiledGraphShadowExecutor,
    CompiledStateGraphPort,
    TerminalResultPlan,
)
from app.graph_runtime.errors import (
    GraphContractError,
    GraphThreadBindingError,
)
from app.graph_runtime.gateway import GatewayExecution
from app.graph_runtime.identity import (
    ActorRole,
    ActorScopeBinding,
    Audience,
    RoomType,
    ThreadIdentity,
    ThreadRecord,
)
from app.graph_runtime.intake_binding import require_exact_intake_binding
from app.graph_runtime.intake_exchange import JavaIntakeExchangeClient
from app.graph_runtime.intake_executor import CompiledIntakeGraphShadowExecutor
from app.graphs.intake.baseline import BASELINE_INTAKE_NODE_NAME
from app.graphs.hearing.contracts import HEARING_MODEL_NODE_PROMPTS
from app.graph_runtime.postgres_bulkhead import PostgresGraphFanoutBulkhead
from app.graph_runtime.registry import VersionBinding
from app.graph_runtime.result import ResultBindings, TERMINAL_DRAFT_ADAPTER
from app.graph_runtime.state import CommonGraphState, validate_graph_state
from app.graph_runtime.target_e2e import (
    TargetE2EInputAuthorizer,
    TargetE2EThreadIdentityResolver,
)
from app.graph_runtime.target_e2e_composite import (
    TARGET_E2E_CHECKPOINT_SCHEMA_VERSION,
    TARGET_E2E_GRAPH_KEY,
    TARGET_E2E_GRAPH_VERSION,
    TARGET_E2E_OUTPUT_SCHEMA_VERSION,
    TARGET_E2E_ROOM_TYPES,
    TargetE2ECompositeExecutor,
    TargetE2ERoomProvider,
)
from app.graph_runtime.target_e2e_room_adapters import (
    build_target_e2e_intake_provider,
)
from app.graph_runtime.topology import build_shadow_kernel_graph
from app.harness.evidence_asset_loader import EvidenceAssetLoader
from app.harness.model_runner import HarnessModelRunner
from app.harness.prompt_composer import PromptRepository
from app.security.invocation_envelope import (
    InvocationClaims,
    ReconciliationClaims,
    VerifiedInvocation,
    VerifiedReconciliation,
    invocation_binding_claims,
)
from app.llm import LiteLlmProxyClient
from app.model_runtime.transports import StructuredClientTransport


_MAX_COGNITIVE_REVISION = (1 << 63) - 1
_SYNTHETIC_COMMIT_STEPS = 5


class DeploymentManifestThreadResolver:
    """Resolve only complete thread tuples frozen in the deployment manifest."""

    def __init__(
        self,
        bindings: tuple[GraphShadowBindingSettings, ...],
        threads: tuple[GraphShadowThreadSettings, ...],
    ) -> None:
        self._bindings = {_binding_key(binding): binding for binding in bindings}
        self._settings = {thread.thread_id: thread for thread in threads}
        self._identities = {thread.thread_id: _thread_identity(thread) for thread in threads}

    async def resolve(
        self,
        *,
        command: RoomGraphCommand,
        verified_invocation: VerifiedInvocation | None = None,
        verified_reconciliation: VerifiedReconciliation | None = None,
    ) -> ThreadIdentity:
        binding = self._bindings.get(
            (
                command.graph_key,
                command.graph_version,
                command.checkpoint_schema_version,
            )
        )
        if binding is None:
            raise GraphThreadBindingError("Graph binding is absent from the synthetic manifest")
        _require_manifest_command(command, binding)
        _require_verified_credential(
            command=command,
            binding=binding,
            verified_invocation=verified_invocation,
            verified_reconciliation=verified_reconciliation,
        )

        configured = self._settings.get(command.thread_id)
        expected = self._identities.get(command.thread_id)
        if configured is None or expected is None:
            raise GraphThreadBindingError("thread is absent from the synthetic manifest")
        actual_scope = ActorScopeBinding.from_json(command.actor_scope.model_dump(mode="json"))
        actual = (
            command.thread_id,
            command.tenant_surrogate,
            command.case_id,
            command.room_type,
            command.room_epoch,
            actual_scope,
            command.graph_key,
            command.graph_version,
            command.checkpoint_schema_version,
            command.request_hash,
        )
        required = (
            expected.thread_id,
            expected.tenant_surrogate,
            expected.case_id,
            expected.room_type.value,
            expected.room_epoch,
            expected.actor_scope,
            expected.graph_key,
            expected.graph_version,
            expected.checkpoint_schema_version,
            configured.request_hash,
        )
        if actual != required:
            raise GraphThreadBindingError(
                "signed command differs from the synthetic thread manifest"
            )
        return expected


class DeploymentManifestInputAuthorizer:
    """Allow only exact hash-bound synthetic inputs; it never loads their contents."""

    def __init__(
        self,
        bindings: tuple[GraphShadowBindingSettings, ...],
        threads: tuple[GraphShadowThreadSettings, ...],
    ) -> None:
        self._bindings = {_binding_key(binding): binding for binding in bindings}
        self._threads = {thread.thread_id: thread for thread in threads}
        self._identities = {thread.thread_id: _thread_identity(thread) for thread in threads}
        self._inputs = {
            thread.thread_id: {_configured_input_key(item): item for item in thread.allowed_inputs}
            for thread in threads
        }

    async def authorize(
        self,
        *,
        command: RoomGraphCommand,
        thread: ThreadIdentity,
    ) -> None:
        configured_thread = self._threads.get(command.thread_id)
        expected_thread = self._identities.get(command.thread_id)
        binding = self._bindings.get(
            (
                command.graph_key,
                command.graph_version,
                command.checkpoint_schema_version,
            )
        )
        if (
            configured_thread is None
            or expected_thread is None
            or thread != expected_thread
            or binding is None
            or not hmac.compare_digest(
                command.request_hash,
                configured_thread.request_hash,
            )
        ):
            raise GraphThreadBindingError(
                "command is outside the signed-synthetic deployment manifest"
            )

        _require_manifest_command(command, binding)

        approved = self._inputs[command.thread_id]
        references = [command.domain_snapshot_ref]
        if command.event_ref is not None:
            references.append(command.event_ref)
        for reference in references:
            configured_input = approved.get(_snapshot_key(reference))
            if configured_input is None or (
                thread.shared_session and configured_input.visibility != "FORMAL"
            ):
                raise GraphThreadBindingError(
                    "command input is absent from the immutable synthetic manifest"
                )


def build_graph_runtime_bindings(
    settings: Settings,
    *,
    target_e2e_provider_factory: (
        Callable[[GraphExecutorKernel], Iterable[TargetE2ERoomProvider]] | None
    ) = None,
    target_e2e_specialized_provider_factory: (
        Callable[[GraphExecutorKernel], Iterable[TargetE2ERoomProvider]] | None
    ) = None,
) -> GraphRuntimeBindings:
    """Build non-overridable exact bindings from validated deployment settings."""

    if settings.graph_gateway_mode == "SHADOW":
        bindings = tuple(settings.graph_shadow_bindings)
        threads = tuple(settings.graph_shadow_threads)
        if not bindings or not threads:
            raise ValueError("signed-synthetic SHADOW bindings are incomplete")
        resolver = DeploymentManifestThreadResolver(bindings, threads)
        authorizer = DeploymentManifestInputAuthorizer(bindings, threads)
    elif settings.graph_gateway_mode == "TARGET_E2E_CANDIDATE":
        bindings = tuple(settings.graph_target_e2e_bindings)
        if not bindings:
            raise ValueError("target-E2E runtime requires exact candidate bindings")
        resolver = TargetE2EThreadIdentityResolver()
        authorizer = TargetE2EInputAuthorizer()
    else:
        raise ValueError(
            "production Graph bindings are available only in SHADOW or TARGET_E2E_CANDIDATE"
        )
    structured_client: LiteLlmProxyClient | None = None
    intake_transport: Any = None
    intake_exchange: JavaIntakeExchangeClient | None = None
    evidence_workflow: EvidenceTurnWorkflowV2 | None = None
    hearing_workflow: HearingFlowWorkflows | None = None
    target_uses_default_providers = (
        settings.graph_gateway_mode == "TARGET_E2E_CANDIDATE"
        and target_e2e_provider_factory is None
    )
    shadow_has_intake = settings.graph_gateway_mode == "SHADOW" and any(
        binding.graph_key == "intake.v2" for binding in bindings
    )
    if target_uses_default_providers or shadow_has_intake:
        structured_client = LiteLlmProxyClient(
            settings.resolved_llm_base_url,
            settings.resolved_llm_model,
            settings.resolved_llm_api_key,
            settings.llm_timeout_seconds,
        )
        intake_transport = StructuredClientTransport(structured_client)
        intake_exchange = JavaIntakeExchangeClient(
            java_api_service_url=settings.java_api_service_url,
            java_service_secret=settings.java_service_secret,
        )
        if target_uses_default_providers:
            evidence_workflow = _build_target_e2e_evidence_workflow(
                settings=settings,
                structured_client=structured_client,
            )
            hearing_workflow = _build_target_e2e_hearing_workflow(structured_client)

    async def open_http_resources() -> None:
        if intake_exchange is not None:
            await intake_exchange.aopen()
        if structured_client is not None:
            await structured_client.aopen()

    async def close_http_resources() -> None:
        try:
            if intake_exchange is not None:
                await intake_exchange.aclose()
        finally:
            if structured_client is not None:
                await structured_client.aclose()

    def executor_registry_factory(
        kernel: GraphExecutorKernel,
    ) -> ExactShadowExecutorRegistry:
        if settings.graph_gateway_mode == "TARGET_E2E_CANDIDATE":
            providers = (
                target_e2e_provider_factory(kernel)
                if target_e2e_provider_factory is not None
                else _build_target_e2e_room_providers(
                    kernel,
                    intake_transport=intake_transport,
                    intake_exchange=intake_exchange,
                    intake_provider=(
                        structured_client.governed_provider
                        if structured_client is not None
                        else None
                    ),
                    intake_model=(
                        structured_client.governed_model
                        if structured_client is not None
                        else None
                    ),
                    evidence_workflow=evidence_workflow,
                    hearing_workflow=hearing_workflow,
                    specialized_provider_factory=target_e2e_specialized_provider_factory,
                )
            )
            return ExactShadowExecutorRegistry(
                (
                    _target_e2e_executor_registration(
                        bindings[0],
                        kernel,
                        providers=providers,
                        intake_provider=(
                            structured_client.governed_provider
                            if structured_client is not None
                            else None
                        ),
                        intake_model=(
                            structured_client.governed_model
                            if structured_client is not None
                            else None
                        ),
                        evidence_provider=(
                            structured_client.governed_provider
                            if structured_client is not None
                            else None
                        ),
                        evidence_model=(
                            structured_client.governed_model
                            if structured_client is not None
                            else None
                        ),
                        hearing_provider=(
                            structured_client.governed_provider
                            if structured_client is not None
                            else None
                        ),
                        hearing_model=(
                            structured_client.governed_model
                            if structured_client is not None
                            else None
                        ),
                    ),
                )
            )
        registrations = [
            _executor_registration(
                binding,
                kernel,
                intake_transport=intake_transport,
                intake_exchange=intake_exchange,
                intake_provider=(
                    structured_client.governed_provider if structured_client is not None else None
                ),
                intake_model=(
                    structured_client.governed_model if structured_client is not None else None
                ),
            )
            for binding in bindings
        ]
        return ExactShadowExecutorRegistry(registrations)

    return GraphRuntimeBindings(
        thread_identity_resolver=resolver,
        input_authorizer=authorizer,
        executor_registry_factory=executor_registry_factory,
        resource_opener=(
            open_http_resources
            if structured_client is not None or intake_exchange is not None
            else None
        ),
        resource_closer=(
            close_http_resources
            if structured_client is not None or intake_exchange is not None
            else None
        ),
        intake_infrastructure_preparer=(
            structured_client.aprepare_intake_infrastructure
            if target_uses_default_providers and structured_client is not None
            else None
        ),
    )


def _target_e2e_executor_registration(
    configured: GraphShadowBindingSettings,
    kernel: GraphExecutorKernel,
    *,
    providers: Iterable[TargetE2ERoomProvider],
    intake_provider: str | None = None,
    intake_model: str | None = None,
    evidence_provider: str | None = None,
    evidence_model: str | None = None,
    hearing_provider: str | None = None,
    hearing_model: str | None = None,
) -> ShadowExecutorRegistration:
    del kernel
    if (
        configured.graph_key != TARGET_E2E_GRAPH_KEY
        or configured.graph_version != TARGET_E2E_GRAPH_VERSION
        or configured.checkpoint_schema_version != TARGET_E2E_CHECKPOINT_SCHEMA_VERSION
        or configured.output_schema_version != TARGET_E2E_OUTPUT_SCHEMA_VERSION
        or frozenset(configured.allowed_room_types)
        != {room.value for room in TARGET_E2E_ROOM_TYPES}
    ):
        raise GraphContractError("target-E2E executor differs from the frozen composite binding")
    binding = _version_binding(configured)
    if (intake_provider is None) != (intake_model is None):
        raise GraphContractError("target-E2E Intake provider binding is incomplete")
    if (evidence_provider is None) != (evidence_model is None):
        raise GraphContractError("target-E2E Evidence provider binding is incomplete")
    if (hearing_provider is None) != (hearing_model is None):
        raise GraphContractError("target-E2E Hearing provider binding is incomplete")
    room_provider_bindings: list[tuple[str, ProviderRuntimeBinding]] = []
    if intake_provider is not None and intake_model is not None:
        room_provider_bindings.append(
            (
                RoomType.INTAKE.value,
                ProviderRuntimeBinding(
                    model_profile_id=binding.model_profile_id,
                    provider=intake_provider,
                    model=intake_model,
                    allowed_nodes=frozenset({BASELINE_INTAKE_NODE_NAME}),
                ),
            )
        )
    if evidence_provider is not None and evidence_model is not None:
        room_provider_bindings.append(
            (
                RoomType.EVIDENCE.value,
                ProviderRuntimeBinding(
                    model_profile_id=binding.model_profile_id,
                    provider=evidence_provider,
                    model=evidence_model,
                    allowed_nodes=frozenset({EVIDENCE_TURN_MODEL_NODE_NAME}),
                ),
            )
        )
    if hearing_provider is not None and hearing_model is not None:
        room_provider_bindings.append(
            (
                RoomType.HEARING.value,
                ProviderRuntimeBinding(
                    model_profile_id=binding.model_profile_id,
                    provider=hearing_provider,
                    model=hearing_model,
                    allowed_nodes=frozenset(HEARING_MODEL_NODE_PROMPTS),
                ),
            )
        )
    return ShadowExecutorRegistration(
        binding=binding,
        executor=TargetE2ECompositeExecutor(providers),
        provider_binding=ProviderRuntimeBinding(
            model_profile_id=binding.model_profile_id,
            provider="target-e2e-composite",
            model="room-provider-dispatch",
            allowed_nodes=frozenset(room.value for room in TARGET_E2E_ROOM_TYPES),
        ),
        room_provider_bindings=tuple(room_provider_bindings),
    )


def _build_target_e2e_room_providers(
    kernel: GraphExecutorKernel,
    *,
    intake_transport: Any,
    intake_exchange: Any,
    intake_provider: str | None,
    intake_model: str | None,
    evidence_workflow: EvidenceTurnWorkflowV2 | None,
    hearing_workflow: HearingFlowWorkflows | None,
    specialized_provider_factory: (
        Callable[[GraphExecutorKernel], Iterable[TargetE2ERoomProvider]] | None
    ),
) -> tuple[TargetE2ERoomProvider, ...]:
    if (
        intake_transport is None
        or intake_exchange is None
        or not intake_provider
        or not intake_model
    ):
        raise GraphContractError("TARGET_E2E_INTAKE_RUNTIME_DEPENDENCIES_REQUIRED")
    if specialized_provider_factory is None:
        raise GraphContractError("TARGET_E2E_SPECIALIZED_ROOM_RUNTIME_REQUIRED")
    if (
        not callable(getattr(evidence_workflow, "run", None))
        or not callable(getattr(evidence_workflow, "arun", None))
        or getattr(evidence_workflow, "protocol_version", None)
        != "evidence-turn-result.v2"
    ):
        raise GraphContractError("TARGET_E2E_FORMAL_EVIDENCE_WORKFLOW_REQUIRED")
    if not callable(getattr(hearing_workflow, "target_e2e_invocation", None)):
        raise GraphContractError("TARGET_E2E_FORMAL_HEARING_WORKFLOW_REQUIRED")
    bind_evidence_workflow = getattr(
        specialized_provider_factory,
        "with_evidence_workflow",
        None,
    )
    if not callable(bind_evidence_workflow):
        raise GraphContractError("TARGET_E2E_FORMAL_EVIDENCE_FACTORY_REQUIRED")
    specialized_provider_factory = bind_evidence_workflow(evidence_workflow)
    bind_hearing_workflow = getattr(
        specialized_provider_factory,
        "with_hearing_workflow",
        None,
    )
    if not callable(bind_hearing_workflow):
        raise GraphContractError("TARGET_E2E_FORMAL_HEARING_FACTORY_REQUIRED")
    specialized_provider_factory = bind_hearing_workflow(hearing_workflow)
    specialized = tuple(specialized_provider_factory(kernel))
    if {getattr(provider, "room_type", None) for provider in specialized} != {
        RoomType.EVIDENCE,
        RoomType.HEARING,
        RoomType.REVIEW,
    } or len(specialized) != 3:
        raise GraphContractError("TARGET_E2E_SPECIALIZED_ROOM_RUNTIME_INVALID")
    return (
        build_target_e2e_intake_provider(
            saver=kernel.saver,
            transport=intake_transport,
            provider=intake_provider,
            model=intake_model,
            exchange=intake_exchange,
        ),
        *specialized,
    )


def _build_target_e2e_evidence_workflow(
    *,
    settings: Settings,
    structured_client: LiteLlmProxyClient,
) -> EvidenceTurnWorkflowV2:
    """Bind the formal Clerk to the lifecycle-owned shared model client."""

    if not isinstance(structured_client, LiteLlmProxyClient):
        raise GraphContractError("TARGET_E2E_FORMAL_EVIDENCE_MODEL_REQUIRED")
    return EvidenceTurnWorkflowV2(
        model_runner=HarnessModelRunner(
            llm=structured_client,
            prompts=PromptRepository(),
        ),
        asset_loader=EvidenceAssetLoader(
            java_api_service_url=settings.java_api_service_url,
            java_service_secret=settings.java_service_secret,
        ),
    )


def _build_target_e2e_hearing_workflow(
    structured_client: LiteLlmProxyClient,
) -> HearingFlowWorkflows:
    """Bind all seven formal Hearing operations to the lifecycle-owned model client."""

    if not isinstance(structured_client, LiteLlmProxyClient):
        raise GraphContractError("TARGET_E2E_FORMAL_HEARING_MODEL_REQUIRED")
    return HearingFlowWorkflows(
        HarnessModelRunner(
            llm=structured_client,
            prompts=PromptRepository(),
        )
    )


def _executor_registration(
    configured: GraphShadowBindingSettings,
    kernel: GraphExecutorKernel,
    *,
    intake_transport: Any = None,
    intake_exchange: Any = None,
    intake_provider: str | None = None,
    intake_model: str | None = None,
) -> ShadowExecutorRegistration:
    if configured.graph_key == "intake.v2":
        return _intake_executor_registration(
            configured,
            kernel,
            transport=intake_transport,
            exchange=intake_exchange,
            provider=intake_provider,
            model=intake_model,
        )
    if configured.graph_key == "evidence.v2":
        durable_bulkhead = getattr(kernel, "durable_bulkhead", None)
        if not isinstance(durable_bulkhead, PostgresGraphFanoutBulkhead):
            raise GraphContractError("evidence.v2 durable PostgreSQL bulkhead is required")
        raise GraphContractError("evidence.v2 exact executor binding is unavailable")
    binding = _version_binding(configured)
    builder = build_shadow_kernel_graph(
        validate_command=_validate_synthetic_state,
        execute_graph=_advance_revision,
        project_result=_project_synthetic_result,
    )
    graph = builder.compile(checkpointer=kernel.saver)
    executor = CompiledGraphShadowExecutor(
        graph=cast(CompiledStateGraphPort, graph),
        saver=kernel.saver,
        initial_state=_initial_state,
        terminal_plan=_terminal_plan,
        start_node="validate_command",
    )
    return ShadowExecutorRegistration(
        binding=binding,
        executor=executor,
        provider_binding=ProviderRuntimeBinding(
            model_profile_id=binding.model_profile_id,
            provider="none",
            model="deterministic-synthetic",
            allowed_nodes=frozenset({"execute_graph"}),
        ),
    )


def _intake_executor_registration(
    configured: GraphShadowBindingSettings,
    kernel: GraphExecutorKernel,
    *,
    transport: Any,
    exchange: Any,
    provider: str | None,
    model: str | None,
) -> ShadowExecutorRegistration:
    require_exact_intake_binding(configured)
    if (
        transport is None
        or exchange is None
        or not provider
        or not model
        or not callable(getattr(exchange, "load", None))
        or not callable(getattr(exchange, "put", None))
    ):
        raise ValueError("intake.v2 production binding dependencies are incomplete")
    binding = _version_binding(configured)
    executor = CompiledIntakeGraphShadowExecutor(
        saver=kernel.saver,
        transport=transport,
        provider=provider,
        model=model,
        input_loader=exchange,
        proposal_store=exchange,
    )
    return ShadowExecutorRegistration(
        binding=binding,
        executor=executor,
        provider_binding=ProviderRuntimeBinding(
            model_profile_id=binding.model_profile_id,
            provider=provider,
            model=model,
            allowed_nodes=frozenset({BASELINE_INTAKE_NODE_NAME}),
        ),
    )


def _initial_state(execution: GatewayExecution) -> Mapping[str, Any]:
    record = execution.thread_record
    if not isinstance(record, ThreadRecord):
        raise GraphContractError("signed-synthetic execution has no authoritative thread revision")
    command = execution.admission.command
    invocation = command.invocation_context
    registry = execution.admission.registry.binding
    baseline_revision = record.cognitive_revision
    if (
        not isinstance(baseline_revision, int)
        or isinstance(baseline_revision, bool)
        or baseline_revision < 0
        or baseline_revision > _MAX_COGNITIVE_REVISION - _SYNTHETIC_COMMIT_STEPS
    ):
        raise GraphContractError("signed-synthetic thread revision cannot advance safely")
    return {
        "bindings": {
            "schema_version": "graph-command-binding.v1",
            "command_id": command.command_id,
            "logical_run_id": command.logical_run_id,
            "attempt_id": command.attempt_id,
            "tenant_surrogate": command.tenant_surrogate,
            "case_id": command.case_id,
            "room_type": command.room_type,
            "room_epoch": command.room_epoch,
            "actor_scope_hash": canonical_sha256(command.actor_scope.model_dump(mode="json")),
            "thread_id": command.thread_id,
        },
        "version_pins": {
            "schema_version": "graph-version-pins.v1",
            "graph_key": command.graph_key,
            "graph_version": command.graph_version,
            "checkpoint_schema_version": command.checkpoint_schema_version,
            "state_schema_version": registry.state_schema_version,
            "prompt_version": invocation.prompt_profile_id,
            "model_profile_id": invocation.model_profile_id,
            "output_schema_version": invocation.output_schema_version,
            "policy_version": invocation.policy_version,
            "guardrail_version": invocation.guardrail_version,
            "tool_policy_version": registry.tool_policy_version,
        },
        "cognitive_revision": baseline_revision + 1,
        "messages": {},
        "work_items": {},
        "work_results": {},
        "artifact_refs": {},
        "node_results": {},
        "execution_receipts": {},
        "usage_by_invocation": {},
    }


def _validate_synthetic_state(state: CommonGraphState) -> dict[str, Any]:
    validate_graph_state(cast(dict[str, object], dict(state)))
    return {"cognitive_revision": _next_revision(state)}


def _advance_revision(state: CommonGraphState) -> dict[str, Any]:
    validate_graph_state(cast(dict[str, object], dict(state)))
    return {"cognitive_revision": _next_revision(state)}


def _project_synthetic_result(state: CommonGraphState) -> dict[str, Any]:
    validate_graph_state(cast(dict[str, object], dict(state)))
    return {
        "cognitive_revision": _next_revision(state),
        "terminal_draft": {"status": "COMPLETED"},
    }


def _next_revision(state: Mapping[str, Any]) -> int:
    revision = _current_revision(state)
    if revision >= _MAX_COGNITIVE_REVISION:
        raise GraphContractError("synthetic Graph cognitive revision is exhausted")
    return revision + 1


def _current_revision(state: Mapping[str, Any]) -> int:
    revision = state.get("cognitive_revision")
    if not isinstance(revision, int) or isinstance(revision, bool) or revision < 0:
        raise GraphContractError("synthetic Graph cognitive revision is invalid")
    return revision


def _terminal_plan(
    execution: GatewayExecution,
    state: Mapping[str, Any],
) -> TerminalResultPlan:
    command = execution.admission.command
    invocation = command.invocation_context
    revision = state.get("cognitive_revision")
    if not isinstance(revision, int) or isinstance(revision, bool) or revision < 1:
        raise GraphContractError("synthetic terminal revision is invalid")
    try:
        draft = TERMINAL_DRAFT_ADAPTER.validate_python(state.get("terminal_draft"))
    except ValueError as error:
        raise GraphContractError("synthetic terminal draft is invalid") from error
    if draft.status != "COMPLETED":
        raise GraphContractError("synthetic terminal result must be COMPLETED")
    for field in (
        "messages",
        "work_items",
        "work_results",
        "artifact_refs",
        "node_results",
        "execution_receipts",
        "usage_by_invocation",
    ):
        if state.get(field) != {}:
            raise GraphContractError(
                "synthetic terminal state contains model, tool, or domain effects"
            )
    if "result_json" in state:
        raise GraphContractError("synthetic terminal state already contains a result")
    return TerminalResultPlan(
        draft=draft,
        bindings=ResultBindings(
            command_id=command.command_id,
            logical_run_id=command.logical_run_id,
            attempt_id=command.attempt_id,
            graph_key=command.graph_key,
            graph_version=command.graph_version,
            checkpoint_id="pending",
            cognitive_revision=revision,
            public_event_proposals=(),
            artifact_operations=(),
            usage=Usage(input_tokens=0, output_tokens=0, total_tokens=0),
            execution_metadata=ExecutionMetadata(
                prompt_version=invocation.prompt_profile_id,
                model_profile_id=invocation.model_profile_id,
                schema_version=invocation.output_schema_version,
                policy_version=invocation.policy_version,
                guardrail_version=invocation.guardrail_version,
            ),
        ),
    )


def _require_manifest_command(
    command: RoomGraphCommand,
    binding: GraphShadowBindingSettings,
) -> None:
    invocation = command.invocation_context
    profile = (
        command.schema_version,
        invocation.agent_profile_id,
        invocation.prompt_profile_id,
        invocation.model_profile_id,
        invocation.output_schema_version,
        invocation.policy_version,
        invocation.guardrail_version,
    )
    expected_profile = (
        binding.command_schema_version,
        binding.agent_profile_id,
        binding.prompt_version,
        binding.model_profile_id,
        binding.output_schema_version,
        binding.policy_version,
        binding.guardrail_version,
    )
    if (
        command.room_type not in binding.allowed_room_types
        or command.stage_code not in binding.allowed_stage_codes
        or profile != expected_profile
        or invocation.tool_capabilities
    ):
        raise GraphThreadBindingError(
            "command profile, room, stage, or tools differ from the deterministic SHADOW binding"
        )


def _require_verified_credential(
    *,
    command: RoomGraphCommand,
    binding: GraphShadowBindingSettings,
    verified_invocation: VerifiedInvocation | None,
    verified_reconciliation: VerifiedReconciliation | None,
) -> None:
    if verified_invocation is not None:
        if (
            verified_reconciliation is not None
            or type(verified_invocation) is not VerifiedInvocation
        ):
            raise GraphThreadBindingError("trusted invocation credential type differs")
        verified: VerifiedInvocation | VerifiedReconciliation = verified_invocation
        if type(verified.claims) is not InvocationClaims:
            raise GraphThreadBindingError("trusted invocation claims type differs")
        if verified.key_id != command.invocation_context.envelope_key_id:
            raise GraphThreadBindingError("trusted invocation key binding differs")
    elif verified_reconciliation is not None:
        if type(verified_reconciliation) is not VerifiedReconciliation:
            raise GraphThreadBindingError("trusted reconciliation credential type differs")
        verified = verified_reconciliation
        if type(verified.claims) is not ReconciliationClaims:
            raise GraphThreadBindingError("trusted reconciliation claims type differs")
        if (
            verified.claims.capability != "RECONCILE_ONLY"
            or verified.claims.original_envelope_key_id
            != command.invocation_context.envelope_key_id
        ):
            raise GraphThreadBindingError("trusted reconciliation authority differs")
    else:
        raise GraphThreadBindingError("trusted invocation credential is missing")

    expected = invocation_binding_claims(
        command,
        registry_binding_hash=binding.binding_hash,
        tool_policy_version=binding.tool_policy_version,
    )
    actual = verified.claims.model_dump(mode="json")
    for name, value in expected.items():
        candidate = actual.get(name)
        if isinstance(value, int):
            matches = type(candidate) is int and candidate == value
        else:
            matches = isinstance(candidate, str) and hmac.compare_digest(candidate, value)
        if not matches:
            raise GraphThreadBindingError(f"trusted invocation differs from the manifest at {name}")
    if (
        not hmac.compare_digest(command.request_hash, str(expected["request_hash"]))
        or not hmac.compare_digest(verified.request_hash, command.request_hash)
        or re.fullmatch(r"[0-9a-f]{64}", verified.transport_certificate_sha256) is None
    ):
        raise GraphThreadBindingError("trusted invocation transport or self-hash differs")


def _version_binding(configured: GraphShadowBindingSettings) -> VersionBinding:
    return VersionBinding(
        graph_key=configured.graph_key,
        graph_version=configured.graph_version,
        checkpoint_schema_version=configured.checkpoint_schema_version,
        state_schema_version=configured.state_schema_version,
        state_schema_hash=configured.state_schema_hash,
        command_schema_version=configured.command_schema_version,
        result_schema_version=configured.result_schema_version,
        prompt_version=configured.prompt_version,
        model_profile_id=configured.model_profile_id,
        output_schema_version=configured.output_schema_version,
        policy_version=configured.policy_version,
        guardrail_version=configured.guardrail_version,
        tool_policy_version=configured.tool_policy_version,
        binding_hash=configured.binding_hash,
        code_build_id=configured.code_build_id,
    )


def _thread_identity(configured: GraphShadowThreadSettings) -> ThreadIdentity:
    return ThreadIdentity(
        thread_id=configured.thread_id,
        tenant_surrogate=configured.tenant_surrogate,
        case_id=configured.case_id,
        room_type=RoomType(configured.room_type),
        room_epoch=configured.room_epoch,
        actor_scope=ActorScopeBinding(
            actor_id=configured.actor_id,
            actor_role=ActorRole(configured.actor_role),
            audience=Audience(configured.audience),
            capabilities=configured.actor_capabilities,
        ),
        agent_session_id=configured.agent_session_id,
        shared_session=configured.shared_session,
        graph_key=configured.graph_key,
        graph_version=configured.graph_version,
        checkpoint_schema_version=configured.checkpoint_schema_version,
    )


def _binding_key(
    configured: GraphShadowBindingSettings,
) -> tuple[str, str, str]:
    return (
        configured.graph_key,
        configured.graph_version,
        configured.checkpoint_schema_version,
    )


def _configured_input_key(
    configured: GraphShadowInputSettings,
) -> tuple[str, str, str, str, int]:
    return (
        configured.artifact_id,
        configured.schema_version,
        configured.uri,
        configured.sha256,
        configured.size_bytes,
    )


def _snapshot_key(reference: SnapshotRef) -> tuple[str, str, str, str, int]:
    return (
        reference.artifact_id,
        reference.schema_version,
        reference.uri,
        reference.sha256,
        reference.size_bytes,
    )
