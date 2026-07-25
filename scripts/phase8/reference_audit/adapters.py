from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from types import MappingProxyType
from typing import Mapping, Protocol, runtime_checkable

from .model import (
    Authority,
    CompletenessStatus,
    Decision,
    HighWatermark,
    ReferenceClass,
    ReferenceEvidence,
    ReferencePage,
    ReferenceRow,
    ScanContext,
    SourceSystem,
    Wave2Authority,
    canonical_sha256,
    watermark_matches_context,
)


class ReferenceReadError(RuntimeError):
    """Base class for an injected reader failure."""


class ReferenceQueryError(ReferenceReadError):
    pass


class ReferencePermissionError(ReferenceReadError):
    pass


class ReferenceTimeoutError(ReferenceReadError):
    pass


@dataclass(frozen=True, slots=True)
class PageRequest:
    reference_class: ReferenceClass
    source_system: SourceSystem
    authority: Authority
    query_id: str
    query_hash: str
    candidate_version: str
    retirement_target: str
    environment_manifest_hash: str
    retention_boundary: datetime
    expected_high_watermark_ledger_id: str
    page_token: str | None
    page_ordinal: int
    page_size: int


@runtime_checkable
class ReadOnlyPagedReader(Protocol):
    def read_page(self, request: PageRequest) -> ReferencePage:
        """Read one authoritative page without changing source state."""


@dataclass(frozen=True, slots=True)
class AdapterDefinition:
    reference_class: ReferenceClass
    source_system: SourceSystem
    authority: Authority
    owner: str
    query_id: str
    query_requirements: tuple[str, ...]
    wave2_authorities: tuple[Wave2Authority, ...] = ()

    @property
    def high_watermark_ledger_id(self) -> str:
        return f"p8-hwm:{self.query_id}"

    @property
    def query_hash(self) -> str:
        return canonical_sha256(
            {
                "authority": self.authority.value,
                "query_id": self.query_id,
                "query_requirements": list(self.query_requirements),
                "reference_class": self.reference_class.value,
                "source_system": self.source_system.value,
                "high_watermark_ledger_id": self.high_watermark_ledger_id,
                "wave2_authorities": [
                    authority.value for authority in self.wave2_authorities
                ],
            }
        )


def _definition(
    reference_class: ReferenceClass,
    source_system: SourceSystem,
    authority: Authority,
    owner: str,
    *query_requirements: str,
    wave2: tuple[Wave2Authority, ...] = (),
) -> AdapterDefinition:
    suffix = reference_class.value.lower().replace("_", "-")
    return AdapterDefinition(
        reference_class=reference_class,
        source_system=source_system,
        authority=authority,
        owner=owner,
        query_id=f"p8.active-reference.{suffix}.v1",
        query_requirements=tuple(query_requirements),
        wave2_authorities=wave2,
    )


_DEFINITIONS = (
    _definition(
        ReferenceClass.TEMPORAL_WORKFLOW,
        SourceSystem.TEMPORAL,
        Authority.TEMPORAL_VISIBILITY,
        "temporal-platform",
        "visibility_pages_are_exhausted",
        "workflow_type_and_execution_identity",
        "retained_history_reachability",
    ),
    _definition(
        ReferenceClass.TEMPORAL_CHILD,
        SourceSystem.TEMPORAL,
        Authority.TEMPORAL_VISIBILITY,
        "temporal-platform",
        "parent_child_execution_identity",
        "retained_history_reachability",
    ),
    _definition(
        ReferenceClass.TEMPORAL_CONTINUE_AS_NEW,
        SourceSystem.TEMPORAL,
        Authority.TEMPORAL_VISIBILITY,
        "temporal-platform",
        "complete_continue_as_new_lineage",
        "retained_history_reachability",
    ),
    _definition(
        ReferenceClass.TEMPORAL_SCHEDULE,
        SourceSystem.TEMPORAL,
        Authority.TEMPORAL_SCHEDULE_VISIBILITY,
        "temporal-platform",
        "schedule_identity_and_action_target",
        "schedule_pages_are_exhausted",
    ),
    _definition(
        ReferenceClass.TEMPORAL_PENDING_WORK,
        SourceSystem.TEMPORAL,
        Authority.TEMPORAL_VISIBILITY,
        "temporal-platform",
        "pending_activity_and_task_queue_references",
        "workflow_execution_identity",
    ),
    _definition(
        ReferenceClass.TEMPORAL_ROOM_EPOCH_BUILD_REACHABILITY,
        SourceSystem.TEMPORAL,
        Authority.TEMPORAL_VISIBILITY,
        "temporal-platform",
        "temporal_epoch_only",
        "workflow_type_worker_build_id_room_epoch_join",
        wave2=(Wave2Authority.TEMPORAL_EPOCH,),
    ),
    _definition(
        ReferenceClass.WORKER_BUILD_ID,
        SourceSystem.TEMPORAL,
        Authority.TEMPORAL_WORKER_BUILD_REACHABILITY,
        "temporal-platform",
        "worker_build_id_reachability",
        "compatible_task_queue_versions",
    ),
    _definition(
        ReferenceClass.GRAPH_THREAD,
        SourceSystem.GRAPH_POSTGRESQL,
        Authority.GRAPH_LEDGER,
        "graph-platform",
        "graph_thread_identity",
        "nonterminal_thread_reachability",
    ),
    _definition(
        ReferenceClass.GRAPH_VERSION,
        SourceSystem.GRAPH_POSTGRESQL,
        Authority.GRAPH_LEDGER,
        "graph-platform",
        "graph_registry_version",
        "deployed_reader_reachability",
    ),
    _definition(
        ReferenceClass.GRAPH_CHECKPOINT,
        SourceSystem.GRAPH_POSTGRESQL,
        Authority.GRAPH_LEDGER,
        "graph-platform",
        "checkpoint_command_result_identity",
        "graph_lease_reachability",
    ),
    _definition(
        ReferenceClass.ROOM_EPOCH,
        SourceSystem.DOMAIN_POSTGRESQL,
        Authority.DOMAIN_LEDGER,
        "domain-platform",
        "nonterminal_room_epoch_writer_mode_and_fence",
        "workflow_graph_prompt_schema_policy_codec_artifact_stream_version_pins",
        "legacy_candidate_left_join_projection_preserves_absence",
        wave2=(
            Wave2Authority.TEMPORAL_EPOCH,
            Wave2Authority.PROJECTION_ABSENCE,
        ),
    ),
    _definition(
        ReferenceClass.LEGACY_V1_LOGICAL_RUN,
        SourceSystem.DOMAIN_POSTGRESQL,
        Authority.DOMAIN_LEGACY_V1_LEDGER,
        "agent-run-platform",
        "logical_run_version_equals_v1",
        "executor_owner_equals_legacy_worker",
        "nonterminal_logical_run_identity",
        wave2=(Wave2Authority.LEGACY_V1, Wave2Authority.LEGACY_WORKER),
    ),
    _definition(
        ReferenceClass.LEGACY_V1_ATTEMPT,
        SourceSystem.DOMAIN_POSTGRESQL,
        Authority.DOMAIN_LEGACY_WORKER_CANDIDATE_LEDGER,
        "agent-run-platform",
        "attempt_version_equals_v1",
        "executor_owner_equals_legacy_worker",
        "would_be_legacy_executor_candidate",
        wave2=(Wave2Authority.LEGACY_V1, Wave2Authority.LEGACY_WORKER),
    ),
    _definition(
        ReferenceClass.HOT_STREAM_READER,
        SourceSystem.DOMAIN_POSTGRESQL,
        Authority.DOMAIN_LEDGER,
        "stream-platform",
        "hot_stream_reader_registration",
        "agent_stream_v1_reader_reachability",
    ),
    _definition(
        ReferenceClass.DOMAIN_CASE_COMMAND,
        SourceSystem.DOMAIN_POSTGRESQL,
        Authority.DOMAIN_PROJECTION_ABSENCE_LEDGER,
        "domain-platform",
        "pending_case_command",
        "legacy_candidate_is_authoritative_without_projection",
        "left_join_projection_and_retain_absent_projection",
        wave2=(Wave2Authority.PROJECTION_ABSENCE,),
    ),
    _definition(
        ReferenceClass.DOMAIN_OPERATION,
        SourceSystem.DOMAIN_POSTGRESQL,
        Authority.DOMAIN_LEDGER,
        "domain-platform",
        "pending_domain_operation",
        "operation_receipt_and_business_identity",
    ),
    _definition(
        ReferenceClass.DOMAIN_FINALIZER,
        SourceSystem.DOMAIN_POSTGRESQL,
        Authority.DOMAIN_PROJECTION_ABSENCE_LEDGER,
        "domain-platform",
        "pending_formal_finalizer_work",
        "domain_authority_independent_of_projection_presence",
        wave2=(Wave2Authority.PROJECTION_ABSENCE,),
    ),
    _definition(
        ReferenceClass.DEPLOYED_API_VERSION,
        SourceSystem.DEPLOYMENT_INVENTORY,
        Authority.DEPLOYMENT_MANIFEST,
        "release-platform",
        "java_python_and_api_version_inventory",
        "candidate_environment_identity",
    ),
    _definition(
        ReferenceClass.DEPLOYED_WORKER_VERSION,
        SourceSystem.DEPLOYMENT_INVENTORY,
        Authority.DEPLOYMENT_MANIFEST,
        "release-platform",
        "worker_and_task_queue_version_inventory",
        "candidate_environment_identity",
    ),
    _definition(
        ReferenceClass.DEPLOYED_GRAPH_VERSION,
        SourceSystem.DEPLOYMENT_INVENTORY,
        Authority.DEPLOYMENT_MANIFEST,
        "release-platform",
        "graph_runtime_and_registry_version_inventory",
        "candidate_environment_identity",
    ),
    _definition(
        ReferenceClass.DEPLOYED_COMPATIBILITY_READER_VERSION,
        SourceSystem.DEPLOYMENT_INVENTORY,
        Authority.DEPLOYMENT_MANIFEST,
        "release-platform",
        "every_compatibility_reader_path",
        "candidate_environment_identity",
    ),
    _definition(
        ReferenceClass.OUTBOX,
        SourceSystem.DOMAIN_POSTGRESQL,
        Authority.DOMAIN_LEDGER,
        "domain-platform",
        "pending_outbox_operation",
        "same_transaction_business_identity",
    ),
    _definition(
        ReferenceClass.LEASE,
        SourceSystem.DOMAIN_POSTGRESQL,
        Authority.DOMAIN_LEDGER,
        "domain-platform",
        "domain_lease_identity",
        "graph_lease_evidence_join",
        "lease_owner_and_expiry",
    ),
    _definition(
        ReferenceClass.STREAM_CURSOR,
        SourceSystem.DOMAIN_POSTGRESQL,
        Authority.DOMAIN_LEDGER,
        "stream-platform",
        "stream_and_replay_cursor_reachability",
        "archive_and_retention_durable_high_watermarks",
    ),
    _definition(
        ReferenceClass.LEGACY_READER_VERSION,
        SourceSystem.DEPLOYMENT_INVENTORY,
        Authority.DEPLOYMENT_MANIFEST,
        "release-platform",
        "old_reader_version_inventory",
        "retained_window_reachability",
    ),
    _definition(
        ReferenceClass.MEMORY_FRAME_READER,
        SourceSystem.DEPLOYMENT_INVENTORY,
        Authority.DEPLOYMENT_MANIFEST,
        "release-platform",
        "memory_frame_reader_call_sites",
        "deployed_configuration_reachability",
    ),
    _definition(
        ReferenceClass.LEGACY_ENDPOINT_CALLER,
        SourceSystem.DEPLOYMENT_INVENTORY,
        Authority.DEPLOYMENT_MANIFEST,
        "release-platform",
        "java_python_legacy_endpoint_callers",
        "retired_hearing_adapter_call_sites",
    ),
    _definition(
        ReferenceClass.OBJECT_STORE_MANIFEST,
        SourceSystem.OBJECT_STORAGE,
        Authority.IMMUTABLE_OBJECT_MANIFEST,
        "object-storage-platform",
        "immutable_versioned_object_manifest",
        "archive_manifest_and_content_hash",
    ),
    _definition(
        ReferenceClass.OBJECT_STORE_CODEC,
        SourceSystem.OBJECT_STORAGE,
        Authority.IMMUTABLE_OBJECT_MANIFEST,
        "object-storage-platform",
        "payload_codec_manifest",
        "old_codec_reader_reachability",
    ),
    _definition(
        ReferenceClass.OBJECT_STORE_SCHEMA,
        SourceSystem.OBJECT_STORAGE,
        Authority.IMMUTABLE_OBJECT_MANIFEST,
        "object-storage-platform",
        "schema_manifest_version_and_hash",
        "retained_object_reachability",
    ),
    _definition(
        ReferenceClass.OBJECT_STORE_PROMPT,
        SourceSystem.OBJECT_STORAGE,
        Authority.IMMUTABLE_OBJECT_MANIFEST,
        "object-storage-platform",
        "prompt_manifest_version_and_hash",
        "retained_object_reachability",
    ),
    _definition(
        ReferenceClass.OBJECT_STORE_ARTIFACT,
        SourceSystem.OBJECT_STORAGE,
        Authority.IMMUTABLE_OBJECT_MANIFEST,
        "object-storage-platform",
        "artifact_manifest_version_and_hash",
        "retained_object_reachability",
    ),
    _definition(
        ReferenceClass.RETAINED_WINDOW_FRONTEND_LEGACY_ENDPOINT,
        SourceSystem.DEPLOYMENT_INVENTORY,
        Authority.DEPLOYMENT_MANIFEST,
        "frontend-platform",
        "retained_frontend_route_inventory",
        "legacy_endpoint_version_reachability",
    ),
    _definition(
        ReferenceClass.RETAINED_WINDOW_API_LEGACY_ENDPOINT,
        SourceSystem.DEPLOYMENT_INVENTORY,
        Authority.DEPLOYMENT_MANIFEST,
        "api-platform",
        "retained_api_endpoint_inventory",
        "legacy_endpoint_version_reachability",
    ),
    _definition(
        ReferenceClass.AGENT_STREAM_V1_TELEMETRY,
        SourceSystem.TELEMETRY,
        Authority.AGENT_STREAM_TELEMETRY_LEDGER,
        "observability-platform",
        "agent_stream_v1_event_and_cursor_telemetry",
        "archive_and_compatibility_reader_telemetry",
    ),
)


ADAPTER_REGISTRY: Mapping[ReferenceClass, AdapterDefinition] = MappingProxyType(
    {definition.reference_class: definition for definition in _DEFINITIONS}
)

if len(ADAPTER_REGISTRY) != 35 or set(ADAPTER_REGISTRY) != set(ReferenceClass):
    raise RuntimeError("active-reference adapter registry must cover exactly 35 classes")


class ActiveReferenceAdapter:
    def __init__(self, definition: AdapterDefinition):
        self._definition = definition

    @property
    def definition(self) -> AdapterDefinition:
        return self._definition

    def scan(
        self, reader: ReadOnlyPagedReader, context: ScanContext
    ) -> ReferenceRow:
        records: list[ReferenceEvidence] = []
        identities: set[str] = set()
        evidence_references = {f"query:{self._definition.query_hash}"}
        page_evidence_references: set[str] = set()
        seen_tokens: set[str] = set()
        token: str | None = None
        first_watermark: HighWatermark | None = None
        authority_watermark: HighWatermark | None = None
        maximum_lag: float | None = None

        for page_ordinal in range(context.max_pages_per_class):
            request = PageRequest(
                reference_class=self._definition.reference_class,
                source_system=self._definition.source_system,
                authority=self._definition.authority,
                query_id=self._definition.query_id,
                query_hash=self._definition.query_hash,
                candidate_version=context.candidate_version,
                retirement_target=context.retirement_target,
                environment_manifest_hash=context.environment_manifest_hash,
                retention_boundary=context.retention_boundary,
                expected_high_watermark_ledger_id=(
                    self._definition.high_watermark_ledger_id
                ),
                page_token=token,
                page_ordinal=page_ordinal,
                page_size=context.page_size,
            )
            try:
                page = reader.read_page(request)
            except (ReferencePermissionError, PermissionError):
                return self._blocked_row(
                    context, records, evidence_references, page_ordinal, "PERMISSION_ERROR"
                )
            except (ReferenceTimeoutError, TimeoutError):
                return self._blocked_row(
                    context, records, evidence_references, page_ordinal, "QUERY_TIMEOUT"
                )
            except (ReferenceQueryError, ReferenceReadError):
                return self._blocked_row(
                    context, records, evidence_references, page_ordinal, "QUERY_ERROR"
                )
            except Exception:
                return self._blocked_row(
                    context, records, evidence_references, page_ordinal, "QUERY_ERROR"
                )

            if not isinstance(page, ReferencePage):
                return self._blocked_row(
                    context, records, evidence_references, page_ordinal + 1, "PARSE_SCHEMA_ERROR"
                )
            try:
                page_error = self._validate_page(page, request, context)
            except Exception:
                return self._blocked_row(
                    context,
                    records,
                    evidence_references,
                    page_ordinal + 1,
                    "PARSE_SCHEMA_ERROR",
                )
            if page_error is not None:
                status, reason = page_error
                return self._blocked_row(
                    context,
                    records,
                    evidence_references,
                    page_ordinal + 1,
                    reason,
                    status=status,
                    scan_high_watermark=page.scan_high_watermark,
                    authority_high_watermark=page.authority_high_watermark,
                    observed_lag=page.replica_lag_seconds,
                )

            assert page.scan_high_watermark is not None
            assert page.authority_high_watermark is not None
            if page.query_evidence_reference in page_evidence_references:
                return self._blocked_row(
                    context,
                    records,
                    evidence_references,
                    page_ordinal + 1,
                    "DUPLICATE_PAGE_EVIDENCE",
                    status=CompletenessStatus.PARTIAL,
                    scan_high_watermark=page.scan_high_watermark,
                    authority_high_watermark=page.authority_high_watermark,
                    observed_lag=page.replica_lag_seconds,
                )
            page_evidence_references.add(page.query_evidence_reference)
            evidence_references.add(page.query_evidence_reference)
            if first_watermark is None:
                first_watermark = page.scan_high_watermark
                authority_watermark = page.authority_high_watermark
            elif (
                page.scan_high_watermark != first_watermark
                or page.authority_high_watermark != authority_watermark
            ):
                return self._blocked_row(
                    context,
                    records,
                    evidence_references,
                    page_ordinal + 1,
                    "HIGH_WATERMARK_DRIFT",
                    status=CompletenessStatus.PARTIAL,
                    scan_high_watermark=page.scan_high_watermark,
                    authority_high_watermark=page.authority_high_watermark,
                    observed_lag=page.replica_lag_seconds,
                )

            maximum_lag = max(maximum_lag or 0.0, page.replica_lag_seconds or 0.0)
            for record in page.records:
                if not isinstance(record, ReferenceEvidence):
                    return self._blocked_row(
                        context,
                        records,
                        evidence_references,
                        page_ordinal + 1,
                        "PARSE_SCHEMA_ERROR",
                    )
                if record.identity in identities:
                    return self._blocked_row(
                        context,
                        records,
                        evidence_references,
                        page_ordinal + 1,
                        "DUPLICATE_REFERENCE_IDENTITY",
                        status=CompletenessStatus.PARTIAL,
                        scan_high_watermark=first_watermark,
                        authority_high_watermark=authority_watermark,
                        observed_lag=maximum_lag,
                    )
                identities.add(record.identity)
                evidence_references.add(record.evidence_reference)
                records.append(record)

            next_token = page.next_page_token
            if next_token is None:
                decision = Decision.RETAIN if records else Decision.BLOCK_DELETE
                reasons = (
                    ("ACTIVE_REFERENCES",)
                    if records
                    else (
                        "SINGLE_SCAN_ZERO_NOT_ELIGIBLE",
                        "SECOND_SCAN_AND_QUIESCENCE_REQUIRED",
                    )
                )
                return self._row(
                    context=context,
                    records=records,
                    evidence_references=evidence_references,
                    page_count=page_ordinal + 1,
                    completeness_status=CompletenessStatus.COMPLETE,
                    decision=decision,
                    reason_codes=reasons,
                    scan_high_watermark=first_watermark,
                    authority_high_watermark=authority_watermark,
                    observed_lag=maximum_lag,
                )
            if next_token == token or next_token in seen_tokens:
                return self._blocked_row(
                    context,
                    records,
                    evidence_references,
                    page_ordinal + 1,
                    "PAGINATION_TOKEN_LOOP",
                    status=CompletenessStatus.PARTIAL,
                    scan_high_watermark=first_watermark,
                    authority_high_watermark=authority_watermark,
                    observed_lag=maximum_lag,
                )
            seen_tokens.add(next_token)
            token = next_token

        return self._blocked_row(
            context,
            records,
            evidence_references,
            context.max_pages_per_class,
            "PAGINATION_LIMIT_EXCEEDED",
            status=CompletenessStatus.PARTIAL,
            scan_high_watermark=first_watermark,
            authority_high_watermark=authority_watermark,
            observed_lag=maximum_lag,
        )

    def _validate_page(
        self, page: ReferencePage, request: PageRequest, context: ScanContext
    ) -> tuple[CompletenessStatus, str] | None:
        if page.completeness_status is not CompletenessStatus.COMPLETE:
            reason = {
                CompletenessStatus.UNKNOWN: "UNKNOWN_RESULT",
                CompletenessStatus.PARTIAL: "PARTIAL_RESULT",
                CompletenessStatus.ERROR: "QUERY_ERROR",
            }[page.completeness_status]
            return page.completeness_status, page.error_code or reason
        if (
            page.source_system is not self._definition.source_system
            or page.authority is not self._definition.authority
            or page.reference_class is not self._definition.reference_class
            or page.query_id != self._definition.query_id
            or page.query_hash != self._definition.query_hash
        ):
            return CompletenessStatus.ERROR, "QUERY_IDENTITY_DRIFT"
        if (
            page.candidate_version != context.candidate_version
            or page.retirement_target != context.retirement_target
            or page.environment_manifest_hash != context.environment_manifest_hash
        ):
            return CompletenessStatus.ERROR, "CANDIDATE_ENVIRONMENT_DRIFT"
        if page.retention_boundary != context.retention_boundary:
            return CompletenessStatus.ERROR, "RETENTION_BOUNDARY_DRIFT"
        if (
            page.requested_page_token != request.page_token
            or page.page_ordinal != request.page_ordinal
        ):
            return CompletenessStatus.PARTIAL, "PAGINATION_CURSOR_DRIFT"
        if (
            page.scan_high_watermark is None
            or page.authority_high_watermark is None
        ):
            return CompletenessStatus.UNKNOWN, "MISSING_AUTHORITY_HIGH_WATERMARK"
        if not watermark_matches_context(page.scan_high_watermark, context) or not (
            watermark_matches_context(page.authority_high_watermark, context)
        ):
            return CompletenessStatus.ERROR, "INVALID_OR_STALE_HIGH_WATERMARK"
        if page.scan_high_watermark != page.authority_high_watermark:
            return CompletenessStatus.PARTIAL, "AUTHORITY_HIGH_WATERMARK_MISMATCH"
        if (
            page.scan_high_watermark.ledger_id
            != self._definition.high_watermark_ledger_id
        ):
            return CompletenessStatus.ERROR, "AUTHORITY_LEDGER_MISMATCH"
        if page.replica_lag_seconds is None:
            return CompletenessStatus.UNKNOWN, "UNKNOWN_REPLICA_LAG"
        if page.replica_lag_seconds > context.max_replica_lag_seconds:
            return CompletenessStatus.ERROR, "REPLICA_LAG_OUT_OF_BOUNDS"
        if not (
            context.scan_started_at <= page.observed_at <= context.scan_completed_at
        ):
            return CompletenessStatus.ERROR, "PAGE_OBSERVATION_TIME_OUT_OF_BOUNDS"
        return None

    def _blocked_row(
        self,
        context: ScanContext,
        records: list[ReferenceEvidence],
        evidence_references: set[str],
        page_count: int,
        reason: str,
        *,
        status: CompletenessStatus = CompletenessStatus.ERROR,
        scan_high_watermark: HighWatermark | None = None,
        authority_high_watermark: HighWatermark | None = None,
        observed_lag: float | None = None,
    ) -> ReferenceRow:
        evidence_references.add(
            f"failure:{self._definition.query_hash}:{reason}"
        )
        return self._row(
            context=context,
            records=records,
            evidence_references=evidence_references,
            page_count=page_count,
            completeness_status=status,
            decision=Decision.BLOCK_DELETE,
            reason_codes=(reason,),
            scan_high_watermark=scan_high_watermark,
            authority_high_watermark=authority_high_watermark,
            observed_lag=observed_lag,
        )

    def _row(
        self,
        *,
        context: ScanContext,
        records: list[ReferenceEvidence],
        evidence_references: set[str],
        page_count: int,
        completeness_status: CompletenessStatus,
        decision: Decision,
        reason_codes: tuple[str, ...],
        scan_high_watermark: HighWatermark | None,
        authority_high_watermark: HighWatermark | None,
        observed_lag: float | None,
    ) -> ReferenceRow:
        timestamps = [record.referenced_at for record in records]
        return ReferenceRow(
            source_system=self._definition.source_system,
            authority=self._definition.authority,
            wave2_authorities=self._definition.wave2_authorities,
            reference_class=self._definition.reference_class,
            query_id=self._definition.query_id,
            query_hash=self._definition.query_hash,
            candidate_version=context.candidate_version,
            environment_manifest_hash=context.environment_manifest_hash,
            target=context.retirement_target,
            active_count=len(records),
            oldest_reference_at=min(timestamps) if timestamps else None,
            newest_reference_at=max(timestamps) if timestamps else None,
            retention_boundary=context.retention_boundary,
            scan_high_watermark=scan_high_watermark,
            authority_high_watermark=authority_high_watermark,
            replica_lag_bound_seconds=context.max_replica_lag_seconds,
            observed_replica_lag_seconds=observed_lag,
            completeness_status=completeness_status,
            owner=self._definition.owner,
            decision=decision,
            reason_codes=reason_codes,
            evidence_references=tuple(sorted(evidence_references)),
            page_count=page_count,
        )


ADAPTERS: Mapping[ReferenceClass, ActiveReferenceAdapter] = MappingProxyType(
    {
        reference_class: ActiveReferenceAdapter(definition)
        for reference_class, definition in ADAPTER_REGISTRY.items()
    }
)
