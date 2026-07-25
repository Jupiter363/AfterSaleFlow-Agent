from __future__ import annotations

import ast
from dataclasses import FrozenInstanceError, replace
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from scripts.phase8.reference_audit import (
    ADAPTER_REGISTRY,
    Authority,
    CompletenessStatus,
    CredentialClass,
    Decision,
    HighWatermark,
    ReferenceClass,
    ReferenceEvidence,
    ReferencePage,
    ScanContext,
    build_active_reference_report,
    canonical_sha256,
)
from scripts.phase8.reference_audit.model import ActiveReferenceReport
from scripts.phase8.reference_audit.report import (
    adapter_inventory_hash,
    verify_sealed_active_reference_report,
)
from scripts.phase8.reference_audit.eligibility import (
    MAXIMUM_QUIESCENCE_OBSERVATION_GAP,
    ApplicableArchiveRange,
    ArchiveArtifactEvidence,
    ArchiveInventoryReceipt,
    ArchiveManifestDocument,
    ArchiveReceiptDocument,
    ArchiveRetentionEvidence,
    AuthorityWindowPolicy,
    AudienceValidationDocument,
    CleanupEligibilityDecision,
    CleanupWindowPolicyReceipt,
    ControlEvidenceKind,
    ControlEvidenceReceipt,
    LedgerQuiescenceSample,
    QuiescenceCheckpoint,
    QuiescenceEvidence,
    RetentionBindingEvidence,
    SequenceValidationDocument,
    evaluate_cleanup_eligibility,
)


ROOT = Path(__file__).resolve().parents[2]
ELIGIBILITY_SOURCE = ROOT / "scripts/phase8/reference_audit/eligibility.py"
NOW = datetime(2026, 7, 25, 12, 0, tzinfo=timezone.utc)
ENVIRONMENT_HASH = "e" * 64
VISIBILITY_WINDOW = timedelta(minutes=10)
RETENTION_WINDOW = timedelta(minutes=10)


def _context(
    *,
    started_at: datetime,
    completed_at: datetime,
    candidate_sha: str = "a" * 40,
    candidate_version: str = "release-2026.07.25",
    retirement_target: str = "legacy-worker-v1",
    environment: str = "synthetic-phase8",
    environment_manifest_hash: str = ENVIRONMENT_HASH,
) -> ScanContext:
    return ScanContext(
        candidate_sha=candidate_sha,
        candidate_version=candidate_version,
        retirement_target=retirement_target,
        environment=environment,
        environment_manifest_hash=environment_manifest_hash,
        credentials_class=CredentialClass.REPORTING_READ_ONLY,
        tool_versions={"reference-audit": "1.0.0"},
        scan_started_at=started_at,
        scan_completed_at=completed_at,
        retention_boundary=started_at - RETENTION_WINDOW,
        max_replica_lag_seconds=5.0,
        max_high_watermark_age=timedelta(hours=1),
    )


class _Reader:
    def __init__(
        self,
        context: ScanContext,
        *,
        sequence: int,
        active: bool = False,
        status: CompletenessStatus = CompletenessStatus.COMPLETE,
    ) -> None:
        self.context = context
        self.sequence = sequence
        self.active = active
        self.status = status

    def read_page(self, request: object) -> ReferencePage:
        watermark = HighWatermark(
            ledger_id=request.expected_high_watermark_ledger_id,
            sequence=self.sequence,
            observed_at=self.context.scan_started_at,
            candidate_version=request.candidate_version,
            environment_manifest_hash=request.environment_manifest_hash,
        )
        records = (
            (
                ReferenceEvidence(
                    identity=f"reference:{request.reference_class.value}",
                    referenced_at=self.context.scan_started_at - timedelta(hours=1),
                    evidence_reference=f"record:{request.reference_class.value}",
                ),
            )
            if self.active
            else ()
        )
        error_code = None if self.status is CompletenessStatus.COMPLETE else "SCAN_FAILED"
        return ReferencePage(
            source_system=request.source_system,
            authority=request.authority,
            reference_class=request.reference_class,
            query_id=request.query_id,
            query_hash=request.query_hash,
            candidate_version=request.candidate_version,
            retirement_target=request.retirement_target,
            environment_manifest_hash=request.environment_manifest_hash,
            retention_boundary=request.retention_boundary,
            requested_page_token=request.page_token,
            next_page_token=None,
            page_ordinal=request.page_ordinal,
            records=records,
            completeness_status=self.status,
            scan_high_watermark=watermark,
            authority_high_watermark=watermark,
            replica_lag_seconds=1.0,
            observed_at=self.context.scan_started_at,
            query_evidence_reference=f"page:{request.reference_class.value}",
            error_code=error_code,
        )


def _report(
    *,
    started_at: datetime,
    completed_at: datetime,
    sequence: int,
    active: bool = False,
    status: CompletenessStatus = CompletenessStatus.COMPLETE,
    **context_changes: object,
) -> ActiveReferenceReport:
    context = _context(
        started_at=started_at,
        completed_at=completed_at,
        **context_changes,  # type: ignore[arg-type]
    )
    reader = _Reader(context, sequence=sequence, active=active, status=status)
    report = build_active_reference_report(
        context,
        {
            definition.source_system: reader
            for definition in ADAPTER_REGISTRY.values()
        },
    )
    assert len(report.rows) == 35
    assert report.inventory_hash == adapter_inventory_hash()
    assert verify_sealed_active_reference_report(report) == report.report_hash
    return report


def _zero_pair() -> tuple[ActiveReferenceReport, ActiveReferenceReport]:
    return _report_pair()


def _report_pair(
    *,
    span: timedelta = RETENTION_WINDOW,
    active: bool = False,
    first_sequence: int = 100,
    second_sequence: int = 100,
) -> tuple[ActiveReferenceReport, ActiveReferenceReport]:
    first = _report(
        started_at=NOW,
        completed_at=NOW + timedelta(minutes=5),
        sequence=first_sequence,
        active=active,
    )
    second_started = first.scan_completed_at + span
    second = _report(
        started_at=second_started,
        completed_at=second_started + timedelta(minutes=5),
        sequence=second_sequence,
        active=active,
    )
    policy = _window_policy(first, second.scan_completed_at)
    first = _bind_evidence_to_rows(first, policy.receipt_hash)
    second = _bind_evidence_to_rows(second, policy.receipt_hash)
    archive_range = _archive_range(second.scan_completed_at)
    inventory = _archive_inventory(
        first,
        second.scan_completed_at - timedelta(minutes=2),
        archive_range,
    )
    return first, _bind_archive_inventory(second, inventory.receipt_hash)


def _reseal_report(
    report: ActiveReferenceReport,
    *,
    rows: tuple[object, ...] | None = None,
    **changes: object,
) -> ActiveReferenceReport:
    values = dict(changes)
    if rows is not None:
        values["rows"] = rows
    values["report_hash"] = ""
    return replace(report, **values)


def _tamper(instance: object, name: str, value: object) -> object:
    object.__setattr__(instance, name, value)
    return instance


def _quiescence(
    first: ActiveReferenceReport,
    second: ActiveReferenceReport,
    *,
    reverse_samples: bool = False,
) -> QuiescenceEvidence:
    observed_at = [first.scan_completed_at]
    while observed_at[-1] + MAXIMUM_QUIESCENCE_OBSERVATION_GAP < second.scan_started_at:
        observed_at.append(observed_at[-1] + MAXIMUM_QUIESCENCE_OBSERVATION_GAP)
    if observed_at[-1] != second.scan_started_at:
        observed_at.append(second.scan_started_at)

    first_rows = {row.reference_class: row for row in first.rows}
    second_rows = {row.reference_class: row for row in second.rows}
    checkpoints: list[QuiescenceCheckpoint] = []
    previous_hash: str | None = None
    for index, checkpoint_time in enumerate(observed_at):
        samples: list[LedgerQuiescenceSample] = []
        for reference_class in ReferenceClass:
            definition = ADAPTER_REGISTRY[reference_class]
            if index == 0:
                watermark = first_rows[reference_class].scan_high_watermark
            elif index == len(observed_at) - 1:
                watermark = second_rows[reference_class].scan_high_watermark
            else:
                watermark = replace(
                    first_rows[reference_class].scan_high_watermark,
                    observed_at=checkpoint_time,
                )
            assert watermark is not None
            samples.append(
                LedgerQuiescenceSample(
                    reference_class=reference_class,
                    source_system=definition.source_system,
                    authority=definition.authority,
                    query_id=definition.query_id,
                    query_hash=definition.query_hash,
                    ledger_id=definition.high_watermark_ledger_id,
                    high_watermark=watermark,
                    active_reference_count=0,
                    new_producer_count=0,
                    new_reference_count=0,
                    completeness_status=CompletenessStatus.COMPLETE,
                )
            )
        if reverse_samples:
            samples.reverse()
        checkpoint = QuiescenceCheckpoint(
            observed_at=checkpoint_time,
            samples=tuple(samples),
            previous_checkpoint_hash=previous_hash,
        )
        checkpoints.append(checkpoint)
        previous_hash = checkpoint.checkpoint_hash

    return QuiescenceEvidence(
        candidate_sha=first.candidate_sha,
        candidate_version=first.candidate_version,
        retirement_target=first.retirement_target,
        environment=first.environment,
        environment_manifest_hash=first.environment_manifest_hash,
        inventory_hash=first.inventory_hash,
        first_report_hash=first.report_hash,
        second_report_hash=second.report_hash,
        window_policy=_window_policy(first, second.scan_completed_at),
        maximum_observation_gap=MAXIMUM_QUIESCENCE_OBSERVATION_GAP,
        checkpoints=tuple(checkpoints),
        evidence_reference="quiescence:sealed-chain",
    )


def _control(
    kind: ControlEvidenceKind,
    report: ActiveReferenceReport,
    evaluated_at: datetime,
) -> ControlEvidenceReceipt:
    semantic_results = {
        ControlEvidenceKind.OLD_READER: "OLD_READERS_ENDED_STORE_READ_ONLY",
        ControlEvidenceKind.RESTORE: "RESTORE_CHECKSUM_VERIFIED",
        ControlEvidenceKind.ROLLBACK: "ROLLBACK_COMPLETED_COMPATIBLE",
    }
    semantic_result = semantic_results[kind]
    return ControlEvidenceReceipt(
        kind=kind,
        candidate_sha=report.candidate_sha,
        candidate_version=report.candidate_version,
        retirement_target=report.retirement_target,
        environment=report.environment,
        environment_manifest_hash=report.environment_manifest_hash,
        inventory_hash=report.inventory_hash,
        verified_at=evaluated_at,
        verified_by="independent-verifier",
        evidence_reference=f"control:{kind.value.lower()}",
        result_hash=canonical_sha256(
            {
                "kind": kind.value,
                "semantic_result": semantic_result,
                "status": "PASS",
            }
        ),
        semantic_result=semantic_result,
        read_only_since=(
            evaluated_at - RETENTION_WINDOW
            if kind is ControlEvidenceKind.OLD_READER
            else None
        ),
    )


def _window_policy(
    report: ActiveReferenceReport,
    evaluated_at: datetime,
) -> CleanupWindowPolicyReceipt:
    return CleanupWindowPolicyReceipt(
        candidate_sha=report.candidate_sha,
        candidate_version=report.candidate_version,
        retirement_target=report.retirement_target,
        environment=report.environment,
        environment_manifest_hash=report.environment_manifest_hash,
        inventory_hash=report.inventory_hash,
        policy_id="cleanup-window-policy-001",
        issued_at=report.scan_started_at - timedelta(minutes=1),
        valid_from=report.scan_started_at,
        valid_through=evaluated_at,
        authority_windows=tuple(
            AuthorityWindowPolicy(
                authority=authority,
                visibility_window=VISIBILITY_WINDOW,
                retention_window=RETENTION_WINDOW,
            )
            for authority in Authority
        ),
        evidence_reference="cleanup-policy:authoritative",
    )


def _bind_evidence_to_rows(
    report: ActiveReferenceReport,
    evidence_hash: str,
) -> ActiveReferenceReport:
    rows = tuple(
        replace(
            row,
            evidence_references=tuple(
                sorted(set((*row.evidence_references, evidence_hash)))
            ),
            row_hash="",
        )
        for row in report.rows
    )
    return replace(report, rows=rows, report_hash="")


def _archive_range(evaluated_at: datetime) -> ApplicableArchiveRange:
    return ApplicableArchiveRange(
        partition_name="agent-stream-2026-07-23",
        partition_range_start=evaluated_at - timedelta(days=2),
        partition_range_end=evaluated_at - timedelta(days=1),
        run_id="run-001",
        attempt_id="attempt-001",
        first_sequence_no=0,
        last_sequence_no=2,
    )


def _archive_inventory(
    report: ActiveReferenceReport,
    evaluated_at: datetime,
    archive_range: ApplicableArchiveRange,
) -> ArchiveInventoryReceipt:
    return ArchiveInventoryReceipt(
        candidate_sha=report.candidate_sha,
        candidate_version=report.candidate_version,
        retirement_target=report.retirement_target,
        environment=report.environment,
        environment_manifest_hash=report.environment_manifest_hash,
        inventory_hash=report.inventory_hash,
        inventory_id="archive-range-inventory-001",
        high_watermark=HighWatermark(
            ledger_id="agent_run_stream_archive_inventory",
            sequence=1,
            observed_at=evaluated_at,
            candidate_version=report.candidate_version,
            environment_manifest_hash=report.environment_manifest_hash,
        ),
        observed_at=evaluated_at,
        applicable_ranges=(archive_range,),
        evidence_reference="archive-inventory:authoritative",
    )


def _bind_archive_inventory(
    report: ActiveReferenceReport,
    receipt_hash: str,
) -> ActiveReferenceReport:
    required = {
        ReferenceClass.OBJECT_STORE_MANIFEST,
        ReferenceClass.AGENT_STREAM_V1_TELEMETRY,
    }
    rows = tuple(
        replace(
            row,
            evidence_references=tuple(
                sorted(set((*row.evidence_references, receipt_hash)))
            ),
            row_hash="",
        )
        if row.reference_class in required
        else row
        for row in report.rows
    )
    return replace(report, rows=rows, report_hash="")


def _archive(
    report: ActiveReferenceReport,
    second_report: ActiveReferenceReport,
    evaluated_at: datetime,
) -> ArchiveRetentionEvidence:
    archive_range = _archive_range(evaluated_at)
    partition_start = archive_range.partition_range_start
    partition_end = archive_range.partition_range_end
    compatibility_hash = "c" * 64
    object_creation_hash = canonical_sha256(
        {
            "object_uri": "s3://phase8-archive/run-001.jsonl",
            "object_version": "version-001",
            "object_hash": "d" * 64,
        }
    )
    sequence = SequenceValidationDocument(
        object_creation_receipt_id="object-receipt-001",
        object_creation_receipt_sha256=object_creation_hash,
        compatibility_report_sha256=compatibility_hash,
        source_event_count=3,
        target_event_count=3,
    )
    audience = AudienceValidationDocument(
        compatibility_report_sha256=compatibility_hash,
    )
    manifest = ArchiveManifestDocument(
        manifest_id="archive-manifest-001",
        target_partition_name=archive_range.partition_name,
        partition_range_start=partition_start,
        partition_range_end=partition_end,
        run_id=archive_range.run_id,
        attempt_id=archive_range.attempt_id,
        first_sequence_no=archive_range.first_sequence_no,
        last_sequence_no=archive_range.last_sequence_no,
        event_count=3,
        canonical_events_hash="a" * 64,
        object_uri="s3://phase8-archive/run-001.jsonl",
        object_version="version-001",
        object_hash="d" * 64,
        terminal_event_id="terminal-event-002",
        terminal_payload_hash="e" * 64,
        execution_manifest_id="execution-manifest-001",
        execution_manifest_hash="f" * 64,
        object_creation_receipt_id=sequence.object_creation_receipt_id,
        object_creation_receipt_hash=sequence.object_creation_receipt_sha256,
        created_by="archive-worker",
    )
    hot_retention_started_at = evaluated_at - timedelta(hours=24)
    receipt = ArchiveReceiptDocument(
        receipt_id="archive-receipt-001",
        manifest_id=manifest.manifest_id,
        manifest_hash=manifest.manifest_hash,
        target_partition_name=manifest.target_partition_name,
        run_id=manifest.run_id,
        attempt_id=manifest.attempt_id,
        first_sequence_no=manifest.first_sequence_no,
        last_sequence_no=manifest.last_sequence_no,
        event_count=manifest.event_count,
        canonical_events_hash=manifest.canonical_events_hash,
        object_version=manifest.object_version,
        object_hash=manifest.object_hash,
        object_readback_hash=manifest.object_hash,
        sequence_validation_hash=sequence.document_hash,
        audience_validation_hash=audience.document_hash,
        delivery_high_watermark=2,
        hot_retention_started_at=hot_retention_started_at,
        hot_retention_eligible_at=evaluated_at,
        verified_at=evaluated_at,
        verified_by="archive-verifier",
    )
    inventory_receipt = _archive_inventory(
        report,
        evaluated_at - timedelta(minutes=2),
        archive_range,
    )
    binding = RetentionBindingEvidence(
        run_id=manifest.run_id,
        attempt_id=manifest.attempt_id,
        terminal_sequence_no=manifest.last_sequence_no,
        terminal_event_id=manifest.terminal_event_id,
        terminal_payload_hash=manifest.terminal_payload_hash,
        execution_manifest_id=manifest.execution_manifest_id,
        execution_manifest_hash=manifest.execution_manifest_hash,
        finalized_at=hot_retention_started_at,
        terminal_event_observed_at=evaluated_at,
        immutable_manifest_observed_at=evaluated_at,
        durable_delivery_high_watermark=2,
        inventory_receipt_hash=inventory_receipt.receipt_hash,
        second_report_hash=second_report.report_hash,
        evidence_reference="retention:terminal-and-manifest",
    )
    artifact = ArchiveArtifactEvidence(
        applicable_range=archive_range,
        manifest=manifest,
        receipt=receipt,
        sequence_validation=sequence,
        audience_validation=audience,
        retention_binding=binding,
    )
    return ArchiveRetentionEvidence(
        candidate_sha=report.candidate_sha,
        candidate_version=report.candidate_version,
        retirement_target=report.retirement_target,
        environment=report.environment,
        environment_manifest_hash=report.environment_manifest_hash,
        inventory_hash=report.inventory_hash,
        inventory_receipt=inventory_receipt,
        applicable_ranges=(archive_range,),
        archives=(artifact,),
        old_store_read_only_since=evaluated_at - RETENTION_WINDOW,
        old_reader_evidence=_control(
            ControlEvidenceKind.OLD_READER, report, evaluated_at
        ),
        restore_evidence=_control(ControlEvidenceKind.RESTORE, report, evaluated_at),
        rollback_evidence=_control(ControlEvidenceKind.ROLLBACK, report, evaluated_at),
        evidence_reference="archive-retention:complete",
    )


def _evaluate(
    first: ActiveReferenceReport,
    second: ActiveReferenceReport,
    *,
    quiescence: QuiescenceEvidence | None = None,
    archive: ArchiveRetentionEvidence | None = None,
    evaluated_at: datetime | None = None,
) -> CleanupEligibilityDecision:
    when = evaluated_at or second.scan_completed_at
    return evaluate_cleanup_eligibility(
        first,
        second,
        visibility_window=VISIBILITY_WINDOW,
        retention_window=RETENTION_WINDOW,
        quiescence=quiescence or _quiescence(first, second),
        archive_retention=archive or _archive(first, second, when),
        evaluated_at=when,
    )


_ARCHIVE_COMPONENT_HASH = {
    "applicable_range": "range_hash",
    "manifest": "manifest_hash",
    "receipt": "receipt_hash",
    "sequence_validation": "document_hash",
    "audience_validation": "document_hash",
    "retention_binding": "binding_hash",
}


def _change_archive_component(
    evidence: ArchiveRetentionEvidence,
    component_name: str,
    **changes: object,
) -> ArchiveRetentionEvidence:
    artifact = evidence.archives[0]
    component = getattr(artifact, component_name)
    changed = replace(
        component,
        **changes,
        **{_ARCHIVE_COMPONENT_HASH[component_name]: ""},
    )
    changed_artifact = replace(
        artifact,
        **{component_name: changed},
        artifact_hash="",
    )
    return replace(evidence, archives=(changed_artifact,), evidence_hash="")


def _unsafe_change_archive_component(
    evidence: ArchiveRetentionEvidence,
    component_name: str,
    field_name: str,
    value: object,
) -> ArchiveRetentionEvidence:
    artifact = evidence.archives[0]
    component = getattr(artifact, component_name)
    object.__setattr__(component, field_name, value)
    hash_name = _ARCHIVE_COMPONENT_HASH[component_name]
    object.__setattr__(
        component,
        hash_name,
        canonical_sha256(component.to_dict(include_hash=False)),
    )
    object.__setattr__(
        artifact,
        "artifact_hash",
        canonical_sha256(artifact.to_dict(include_hash=False)),
    )
    object.__setattr__(
        evidence,
        "evidence_hash",
        canonical_sha256(evidence.to_dict(include_hash=False)),
    )
    return evidence


def _unsafe_change_control(
    evidence: ArchiveRetentionEvidence,
    name: str,
    field_name: str,
    value: object,
) -> ArchiveRetentionEvidence:
    receipt = getattr(evidence, name)
    object.__setattr__(receipt, field_name, value)
    object.__setattr__(
        receipt,
        "receipt_hash",
        canonical_sha256(receipt.to_dict(include_hash=False)),
    )
    object.__setattr__(
        evidence,
        "evidence_hash",
        canonical_sha256(evidence.to_dict(include_hash=False)),
    )
    return evidence


def test_report_fixture_is_two_independent_sealed_complete_exact_35_zero_scans() -> None:
    first, second = _zero_pair()

    assert first is not second
    assert first.report_hash != second.report_hash
    assert first.scan_completed_at + RETENTION_WINDOW == second.scan_started_at
    for report in (first, second):
        assert len(report.rows) == 35
        assert {row.reference_class for row in report.rows} == set(ADAPTER_REGISTRY)
        assert {row.active_count for row in report.rows} == {0}
        assert {row.completeness_status for row in report.rows} == {
            CompletenessStatus.COMPLETE
        }
        assert verify_sealed_active_reference_report(report) == report.report_hash


def test_cleanup_evidence_models_are_frozen_slotted_and_advisory() -> None:
    from scripts.phase8.reference_audit.eligibility import (
        ArchiveRetentionEvidence,
        CleanupEligibilityDecision,
        QuiescenceEvidence,
    )

    assert QuiescenceEvidence.__dataclass_params__.frozen
    assert ArchiveRetentionEvidence.__dataclass_params__.frozen
    assert CleanupEligibilityDecision.__dataclass_params__.frozen
    assert "__slots__" in vars(QuiescenceEvidence)
    assert "__slots__" in vars(ArchiveRetentionEvidence)
    assert "__slots__" in vars(CleanupEligibilityDecision)

    for field_name in ("authorizes_cleanup", "human_authorization_required"):
        assert isinstance(vars(CleanupEligibilityDecision)[field_name], property)


def test_exact_max_window_complete_typed_evidence_is_the_only_eligible_path() -> None:
    first, second = _report_pair()
    quiescence = _quiescence(first, second)
    archive = _archive(first, second, second.scan_completed_at)

    decision = _evaluate(first, second, quiescence=quiescence, archive=archive)

    assert decision.decision is Decision.ELIGIBLE
    assert decision.authorizes_cleanup is False
    assert decision.human_authorization_required is True
    assert decision.reason_codes == ("HUMAN_CLEANUP_AUTHORIZATION_INPUT_ONLY",)
    assert decision.evidence_hashes == (
        first.report_hash,
        second.report_hash,
        quiescence.evidence_hash,
        archive.evidence_hash,
    )
    assert decision.decision_hash == canonical_sha256(
        decision.to_dict(include_hash=False)
    )
    assert decision.to_json_bytes() == decision.to_json_bytes()
    document = decision.to_dict()
    assert document["decision"] == "ELIGIBLE"
    assert all(value is False for value in document["capabilities"].values())
    assert "PASS" not in decision.to_json_bytes().decode("ascii")
    assert "AUTHORIZED" not in decision.to_json_bytes().decode("ascii")
    with pytest.raises(FrozenInstanceError):
        decision.decision = Decision.RETAIN  # type: ignore[misc]


def test_single_scan_cannot_be_reused_as_two_independent_zero_scans() -> None:
    report = _report(
        started_at=NOW,
        completed_at=NOW + timedelta(minutes=5),
        sequence=100,
    )

    assert _evaluate(report, report).decision is Decision.BLOCK_DELETE


def test_complete_active_references_always_retain() -> None:
    first, second = _report_pair(active=True)

    decision = _evaluate(first, second)

    assert decision.decision is Decision.RETAIN
    assert decision.reason_codes == ("COMPLETE_ACTIVE_REFERENCES",)
    assert decision.authorizes_cleanup is False


def test_boolean_summaries_and_direct_eligible_decisions_cannot_forge_evidence() -> None:
    old_quiescence_flags = {
        "continuous",
        "no_new_producers",
        "no_observation_gaps",
        "durable_high_watermarks",
    }
    old_archive_flags = {
        "all_runs_terminal",
        "sequence_contiguous",
        "archive_readback_verified",
        "terminal_event_retained",
        "immutable_manifest",
        "durable_high_watermark",
        "old_store_read_only",
        "compatible_old_readers_ended",
        "restore_evidence_current",
        "rollback_evidence_current",
    }
    assert not (old_quiescence_flags & set(QuiescenceEvidence.__dataclass_fields__))
    assert not (old_archive_flags & set(ArchiveRetentionEvidence.__dataclass_fields__))

    with pytest.raises(TypeError):
        CleanupEligibilityDecision(  # type: ignore[call-arg]
            decision=Decision.ELIGIBLE,
            evaluated_at=NOW,
            candidate_sha="a" * 40,
            first_report_hash="b" * 64,
            second_report_hash="c" * 64,
            reason_codes=("FORGED",),
            evidence_hashes=(),
        )


def _rechain_quiescence(
    evidence: QuiescenceEvidence,
    checkpoints: tuple[QuiescenceCheckpoint, ...],
) -> QuiescenceEvidence:
    rebuilt: list[QuiescenceCheckpoint] = []
    previous_hash: str | None = None
    for checkpoint in checkpoints:
        item = replace(
            checkpoint,
            previous_checkpoint_hash=previous_hash,
            checkpoint_hash="",
        )
        rebuilt.append(item)
        previous_hash = item.checkpoint_hash
    return replace(evidence, checkpoints=tuple(rebuilt), evidence_hash="")


def _unsafe_reseal_quiescence_chain(
    evidence: QuiescenceEvidence,
) -> QuiescenceEvidence:
    previous_hash: str | None = None
    for checkpoint in evidence.checkpoints:
        object.__setattr__(checkpoint, "previous_checkpoint_hash", previous_hash)
        object.__setattr__(
            checkpoint,
            "checkpoint_hash",
            canonical_sha256(checkpoint.to_dict(include_hash=False)),
        )
        previous_hash = checkpoint.checkpoint_hash
    object.__setattr__(
        evidence,
        "evidence_hash",
        canonical_sha256(evidence.to_dict(include_hash=False)),
    )
    return evidence


def _change_quiescence_sample(
    evidence: QuiescenceEvidence,
    checkpoint_index: int,
    **changes: object,
) -> QuiescenceEvidence:
    checkpoints = list(evidence.checkpoints)
    checkpoint = checkpoints[checkpoint_index]
    samples = list(checkpoint.samples)
    samples[0] = replace(samples[0], **changes, sample_hash="")
    checkpoints[checkpoint_index] = replace(
        checkpoint,
        samples=tuple(samples),
        checkpoint_hash="",
    )
    return _rechain_quiescence(evidence, tuple(checkpoints))


def test_quiescence_requires_exact_35_ledger_inventory_and_unbroken_hash_chain() -> None:
    first, second = _report_pair()
    valid = _quiescence(first, second)

    missing_checkpoint = valid.checkpoints[1]
    object.__setattr__(
        missing_checkpoint,
        "samples",
        missing_checkpoint.samples[:-1],
    )
    object.__setattr__(
        missing_checkpoint,
        "checkpoint_hash",
        canonical_sha256(missing_checkpoint.to_dict(include_hash=False)),
    )
    missing = _unsafe_reseal_quiescence_chain(valid)
    assert _evaluate(first, second, quiescence=missing).decision is Decision.BLOCK_DELETE

    valid = _quiescence(first, second)
    duplicate_checkpoint = valid.checkpoints[1]
    duplicate_samples = list(duplicate_checkpoint.samples)
    duplicate_samples[-1] = duplicate_samples[0]
    object.__setattr__(duplicate_checkpoint, "samples", tuple(duplicate_samples))
    object.__setattr__(
        duplicate_checkpoint,
        "checkpoint_hash",
        canonical_sha256(duplicate_checkpoint.to_dict(include_hash=False)),
    )
    duplicate = _unsafe_reseal_quiescence_chain(valid)
    assert _evaluate(first, second, quiescence=duplicate).decision is Decision.BLOCK_DELETE

    broken = _quiescence(first, second)
    object.__setattr__(broken.checkpoints[1], "previous_checkpoint_hash", "f" * 64)
    assert _evaluate(first, second, quiescence=broken).decision is Decision.BLOCK_DELETE


@pytest.mark.parametrize(
    ("mutation", "value"),
    [
        ("active_reference_count", 1),
        ("new_producer_count", 1),
        ("new_reference_count", 1),
        ("completeness_status", CompletenessStatus.PARTIAL),
        ("ledger_id", "drifted-ledger"),
        ("query_hash", "9" * 64),
        ("authority", Authority.DOMAIN_LEDGER),
    ],
)
def test_quiescence_sample_activity_or_semantic_drift_blocks_delete(
    mutation: str,
    value: object,
) -> None:
    first, second = _report_pair()
    evidence = _change_quiescence_sample(
        _quiescence(first, second),
        1,
        **{mutation: value},
    )

    assert _evaluate(first, second, quiescence=evidence).decision is Decision.BLOCK_DELETE


def test_quiescence_gap_stale_future_and_hwm_regression_or_advance_block() -> None:
    first, second = _report_pair()
    valid = _quiescence(first, second)
    middle = replace(
        valid.checkpoints[1],
        observed_at=valid.checkpoints[1].observed_at + timedelta(microseconds=1),
        checkpoint_hash="",
    )
    gap = _rechain_quiescence(
        valid,
        (valid.checkpoints[0], middle, valid.checkpoints[2]),
    )
    assert _evaluate(first, second, quiescence=gap).decision is Decision.BLOCK_DELETE

    for sequence in (99, 101):
        valid = _quiescence(first, second)
        watermark = replace(
            valid.checkpoints[1].samples[0].high_watermark,
            sequence=sequence,
        )
        drifted = _change_quiescence_sample(
            valid,
            1,
            high_watermark=watermark,
        )
        assert _evaluate(first, second, quiescence=drifted).decision is Decision.BLOCK_DELETE

    valid = _quiescence(first, second)
    checkpoint = valid.checkpoints[1]
    future = replace(
        checkpoint.samples[0].high_watermark,
        observed_at=checkpoint.observed_at + timedelta(microseconds=1),
    )
    evidence = _change_quiescence_sample(valid, 1, high_watermark=future)
    assert _evaluate(first, second, quiescence=evidence).decision is Decision.BLOCK_DELETE


@pytest.mark.parametrize(
    ("field_name", "invalid_value"),
    [
        ("sequence", -1),
        ("durable", "true"),
    ],
)
def test_sealed_reports_reject_invalid_nested_high_watermark_types(
    field_name: str,
    invalid_value: object,
) -> None:
    report = _report(
        started_at=NOW,
        completed_at=NOW + timedelta(minutes=5),
        sequence=100,
    )
    row = report.rows[0]
    assert row.scan_high_watermark is row.authority_high_watermark
    object.__setattr__(row.scan_high_watermark, field_name, invalid_value)
    object.__setattr__(
        row,
        "row_hash",
        canonical_sha256(row.to_dict(include_hash=False)),
    )
    object.__setattr__(
        report,
        "report_hash",
        canonical_sha256(report.to_dict(include_hash=False)),
    )

    with pytest.raises((TypeError, ValueError)):
        verify_sealed_active_reference_report(report)


@pytest.mark.parametrize(
    "status",
    [
        CompletenessStatus.UNKNOWN,
        CompletenessStatus.PARTIAL,
        CompletenessStatus.ERROR,
    ],
)
def test_unknown_partial_and_error_reports_never_escape_fail_closed(
    status: CompletenessStatus,
) -> None:
    first, second = _report_pair()
    incomplete = _report(
        started_at=second.scan_started_at,
        completed_at=second.scan_completed_at,
        sequence=100,
        status=status,
    )

    assert incomplete.decision is Decision.BLOCK_DELETE
    assert {row.completeness_status for row in incomplete.rows} == {status}


def test_missing_duplicate_class_and_stale_row_or_report_hashes_break_the_seal() -> None:
    for mutation in ("missing", "duplicate", "row_hash", "report_hash"):
        report = _report(
            started_at=NOW,
            completed_at=NOW + timedelta(minutes=5),
            sequence=100,
        )
        if mutation == "missing":
            object.__setattr__(report, "rows", report.rows[:-1])
        elif mutation == "duplicate":
            rows = list(report.rows)
            rows[-1] = rows[0]
            object.__setattr__(report, "rows", tuple(rows))
        elif mutation == "row_hash":
            object.__setattr__(report.rows[0], "row_hash", "f" * 64)
        else:
            object.__setattr__(report, "report_hash", "f" * 64)

        with pytest.raises((TypeError, ValueError)):
            verify_sealed_active_reference_report(report)


def test_policy_is_exact_context_bound_and_present_in_every_row_of_both_scans() -> None:
    first, second = _report_pair()
    quiescence = _quiescence(first, second)
    policy = quiescence.window_policy

    assert {item.authority for item in policy.authority_windows} == set(Authority)
    assert len(policy.authority_windows) == len(Authority)
    assert all(
        policy.receipt_hash in row.evidence_references
        for report in (first, second)
        for row in report.rows
    )
    assert _evaluate(first, second, quiescence=quiescence).decision is Decision.ELIGIBLE


def test_policy_missing_duplicate_failed_expired_future_or_unbound_blocks() -> None:
    first, second = _report_pair()
    cases: list[tuple[ActiveReferenceReport, ActiveReferenceReport, QuiescenceEvidence]] = []

    for windows in (
        _quiescence(first, second).window_policy.authority_windows[:-1],
        (
            *_quiescence(first, second).window_policy.authority_windows[:-1],
            _quiescence(first, second).window_policy.authority_windows[0],
        ),
    ):
        policy = _quiescence(first, second).window_policy
        object.__setattr__(policy, "authority_windows", windows)
        object.__setattr__(
            policy,
            "receipt_hash",
            canonical_sha256(policy.to_dict(include_hash=False)),
        )
        q = replace(_quiescence(first, second), window_policy=policy, evidence_hash="")
        cases.append((first, second, q))

    for field_name, value in (
        ("status", "FAILED"),
        ("valid_through", second.scan_completed_at - timedelta(microseconds=1)),
        ("issued_at", first.scan_started_at + timedelta(microseconds=1)),
    ):
        policy = _quiescence(first, second).window_policy
        object.__setattr__(policy, field_name, value)
        object.__setattr__(
            policy,
            "receipt_hash",
            canonical_sha256(policy.to_dict(include_hash=False)),
        )
        q = replace(_quiescence(first, second), window_policy=policy, evidence_hash="")
        cases.append((first, second, q))

    missing_row = first.rows[0]
    policy_hash = _quiescence(first, second).window_policy.receipt_hash
    first_without_policy = _reseal_report(
        first,
        rows=(
            replace(
                missing_row,
                evidence_references=tuple(
                    item
                    for item in missing_row.evidence_references
                    if item != policy_hash
                ),
                row_hash="",
            ),
            *first.rows[1:],
        ),
    )
    cases.append(
        (
            first_without_policy,
            second,
            _quiescence(first_without_policy, second),
        )
    )

    assert cases
    for case_first, case_second, quiescence in cases:
        assert (
            _evaluate(case_first, case_second, quiescence=quiescence).decision
            is Decision.BLOCK_DELETE
        )


def test_caller_cannot_shrink_policy_windows_and_one_microsecond_short_span_blocks() -> None:
    first, second = _report_pair()
    quiescence = _quiescence(first, second)
    archive = _archive(first, second, second.scan_completed_at)
    for visibility, retention in (
        (VISIBILITY_WINDOW - timedelta(microseconds=1), RETENTION_WINDOW),
        (VISIBILITY_WINDOW, RETENTION_WINDOW - timedelta(microseconds=1)),
    ):
        decision = evaluate_cleanup_eligibility(
            first,
            second,
            visibility_window=visibility,
            retention_window=retention,
            quiescence=quiescence,
            archive_retention=archive,
            evaluated_at=second.scan_completed_at,
        )
        assert decision.decision is Decision.BLOCK_DELETE

    short_first, short_second = _report_pair(
        span=RETENTION_WINDOW - timedelta(microseconds=1)
    )
    assert _evaluate(short_first, short_second).decision is Decision.BLOCK_DELETE


def test_archive_inventory_is_within_second_scan_bound_and_complete() -> None:
    first, second = _report_pair()
    archive = _archive(first, second, second.scan_completed_at)
    inventory = archive.inventory_receipt

    assert second.scan_started_at <= inventory.observed_at <= second.scan_completed_at
    assert inventory.high_watermark.observed_at == inventory.observed_at
    for reference_class in (
        ReferenceClass.OBJECT_STORE_MANIFEST,
        ReferenceClass.AGENT_STREAM_V1_TELEMETRY,
    ):
        row = next(row for row in second.rows if row.reference_class is reference_class)
        assert inventory.receipt_hash in row.evidence_references
    assert _evaluate(first, second, archive=archive).decision is Decision.ELIGIBLE


def test_archive_inventory_future_stale_wrong_hwm_unbound_or_omitted_range_blocks() -> None:
    first, second = _report_pair()
    for observed_at in (
        second.scan_started_at - timedelta(microseconds=1),
        second.scan_completed_at + timedelta(microseconds=1),
    ):
        archive = _archive(first, second, second.scan_completed_at)
        hwm = replace(archive.inventory_receipt.high_watermark, observed_at=observed_at)
        inventory = replace(
            archive.inventory_receipt,
            observed_at=observed_at,
            high_watermark=hwm,
            receipt_hash="",
        )
        evidence = replace(archive, inventory_receipt=inventory, evidence_hash="")
        assert _evaluate(first, second, archive=evidence).decision is Decision.BLOCK_DELETE

    archive = _archive(first, second, second.scan_completed_at)
    wrong_hwm = replace(
        archive.inventory_receipt.high_watermark,
        ledger_id="wrong-archive-ledger",
    )
    evidence = replace(
        archive,
        inventory_receipt=replace(
            archive.inventory_receipt,
            high_watermark=wrong_hwm,
            receipt_hash="",
        ),
        evidence_hash="",
    )
    assert _evaluate(first, second, archive=evidence).decision is Decision.BLOCK_DELETE

    archive = _archive(first, second, second.scan_completed_at)
    required_row = next(
        row
        for row in second.rows
        if row.reference_class is ReferenceClass.OBJECT_STORE_MANIFEST
    )
    unbound_second = _reseal_report(
        second,
        rows=tuple(
            replace(
                row,
                evidence_references=tuple(
                    item
                    for item in row.evidence_references
                    if item != archive.inventory_receipt.receipt_hash
                ),
                row_hash="",
            )
            if row is required_row
            else row
            for row in second.rows
        ),
    )
    assert _evaluate(first, unbound_second).decision is Decision.BLOCK_DELETE

    archive = _archive(first, second, second.scan_completed_at)
    extra_range = replace(
        archive.applicable_ranges[0],
        partition_name="agent-stream-2026-07-24",
        run_id="run-002",
        attempt_id="attempt-002",
        range_hash="",
    )
    inventory = replace(
        archive.inventory_receipt,
        applicable_ranges=(*archive.applicable_ranges, extra_range),
        receipt_hash="",
    )
    omitted = replace(
        archive,
        inventory_receipt=inventory,
        evidence_hash="",
    )
    second_with_inventory = _bind_archive_inventory(second, inventory.receipt_hash)
    assert (
        _evaluate(
            first,
            second_with_inventory,
            quiescence=_quiescence(first, second_with_inventory),
            archive=omitted,
        ).decision
        is Decision.BLOCK_DELETE
    )


@pytest.mark.parametrize(
    ("component", "field_name", "value"),
    [
        ("applicable_range", "partition_name", "wrong-partition"),
        ("manifest", "event_count", 2),
        ("manifest", "canonical_events_hash", "1" * 64),
        ("manifest", "object_version", "version-002"),
        ("manifest", "object_hash", "2" * 64),
        ("manifest", "terminal_event_id", "wrong-terminal"),
        ("manifest", "execution_manifest_id", "wrong-manifest"),
        ("manifest", "object_creation_receipt_hash", "3" * 64),
        ("manifest", "formal_business_authority", True),
        ("receipt", "first_sequence_no", 1),
        ("receipt", "last_sequence_no", 1),
        ("receipt", "event_count", 2),
        ("receipt", "object_version", "version-002"),
        ("receipt", "object_hash", "4" * 64),
        ("receipt", "object_readback_hash", "5" * 64),
        ("receipt", "sequence_validation_hash", "6" * 64),
        ("receipt", "audience_validation_hash", "7" * 64),
        ("receipt", "delivery_high_watermark", 1),
        ("receipt", "formal_business_authority", True),
        ("receipt", "release_evidence_complete", True),
        ("sequence_validation", "object_creation_receipt_id", "wrong-receipt"),
        ("sequence_validation", "object_creation_receipt_sha256", "8" * 64),
        ("sequence_validation", "compatibility_report_sha256", "9" * 64),
        ("sequence_validation", "source_event_count", 2),
        ("sequence_validation", "target_event_count", 2),
        ("sequence_validation", "sequence_contiguous", False),
        ("sequence_validation", "event_identity_exact", False),
        ("sequence_validation", "release_evidence_complete", True),
        ("audience_validation", "audience_parity", False),
        ("audience_validation", "actor_id_parity", False),
        ("audience_validation", "cursor_parity", False),
        ("audience_validation", "release_evidence_complete", True),
        ("retention_binding", "terminal_sequence_no", 1),
        ("retention_binding", "terminal_event_id", "wrong-terminal"),
        ("retention_binding", "terminal_payload_hash", "1" * 64),
        ("retention_binding", "execution_manifest_id", "wrong-manifest"),
        ("retention_binding", "execution_manifest_hash", "2" * 64),
        ("retention_binding", "durable_delivery_high_watermark", 1),
        ("retention_binding", "inventory_receipt_hash", "3" * 64),
        ("retention_binding", "second_report_hash", "4" * 64),
    ],
)
def test_archive_partition_range_count_object_receipt_terminal_manifest_and_hwm_drift_blocks(
    component: str,
    field_name: str,
    value: object,
) -> None:
    first, second = _report_pair()
    archive = _change_archive_component(
        _archive(first, second, second.scan_completed_at),
        component,
        **{field_name: value},
    )

    assert _evaluate(first, second, archive=archive).decision is Decision.BLOCK_DELETE


@pytest.mark.parametrize(
    ("component", "field_name", "value"),
    [
        ("sequence_validation", "status", "FAIL"),
        ("sequence_validation", "schema_version", "wrong.v1"),
        ("audience_validation", "status", "FAIL"),
        ("manifest", "schema_version", "wrong.v1"),
        ("manifest", "authority_scope", "BUSINESS_AUTHORITY"),
        ("receipt", "schema_version", "wrong.v1"),
        ("receipt", "authority_scope", "BUSINESS_AUTHORITY"),
    ],
)
def test_fully_resealed_nested_schema_status_or_scope_forgery_blocks(
    component: str,
    field_name: str,
    value: object,
) -> None:
    first, second = _report_pair()
    archive = _unsafe_change_archive_component(
        _archive(first, second, second.scan_completed_at),
        component,
        field_name,
        value,
    )

    assert _evaluate(first, second, archive=archive).decision is Decision.BLOCK_DELETE


def test_fully_resealed_missing_receipt_identity_and_failed_receipt_status_block() -> None:
    first, second = _report_pair()
    missing_id = _unsafe_change_archive_component(
        _archive(first, second, second.scan_completed_at),
        "receipt",
        "receipt_id",
        "",
    )
    assert _evaluate(first, second, archive=missing_id).decision is Decision.BLOCK_DELETE

    archive = _archive(first, second, second.scan_completed_at)
    artifact = archive.archives[0]
    object.__setattr__(artifact, "receipt_status", "FAILED")
    object.__setattr__(
        artifact,
        "artifact_hash",
        canonical_sha256(artifact.to_dict(include_hash=False)),
    )
    object.__setattr__(
        archive,
        "evidence_hash",
        canonical_sha256(archive.to_dict(include_hash=False)),
    )
    assert _evaluate(first, second, archive=archive).decision is Decision.BLOCK_DELETE


@pytest.mark.parametrize(
    ("name", "field_name", "value"),
    [
        ("old_reader_evidence", "status", "FAILED"),
        ("restore_evidence", "status", "FAILED"),
        ("rollback_evidence", "status", "FAILED"),
        ("old_reader_evidence", "semantic_result", "FALSE"),
        ("restore_evidence", "semantic_result", "FALSE"),
        ("rollback_evidence", "semantic_result", "FALSE"),
        ("old_reader_evidence", "result_hash", "0" * 64),
        ("restore_evidence", "result_hash", "0" * 64),
        ("rollback_evidence", "result_hash", "0" * 64),
    ],
)
def test_control_receipt_failure_wrong_semantics_or_result_hash_blocks(
    name: str,
    field_name: str,
    value: object,
) -> None:
    first, second = _report_pair()
    archive = _unsafe_change_control(
        _archive(first, second, second.scan_completed_at),
        name,
        field_name,
        value,
    )

    assert _evaluate(first, second, archive=archive).decision is Decision.BLOCK_DELETE


def test_archive_retention_and_current_revalidation_time_boundaries_fail_closed() -> None:
    first, second = _report_pair()
    evaluated_at = second.scan_completed_at
    archive = _archive(first, second, evaluated_at)

    too_short = _change_archive_component(
        archive,
        "receipt",
        hot_retention_started_at=evaluated_at - timedelta(hours=24)
        + timedelta(microseconds=1),
    )
    assert _evaluate(first, second, archive=too_short).decision is Decision.BLOCK_DELETE

    for field_name in ("terminal_event_observed_at", "immutable_manifest_observed_at"):
        stale = _change_archive_component(
            _archive(first, second, evaluated_at),
            "retention_binding",
            **{field_name: second.scan_completed_at - timedelta(microseconds=1)},
        )
        assert _evaluate(first, second, archive=stale).decision is Decision.BLOCK_DELETE

    future_receipt = _change_archive_component(
        _archive(first, second, evaluated_at),
        "receipt",
        verified_at=evaluated_at + timedelta(microseconds=1),
    )
    assert _evaluate(first, second, archive=future_receipt).decision is Decision.BLOCK_DELETE

    assert (
        _evaluate(
            first,
            second,
            evaluated_at=second.scan_completed_at - timedelta(microseconds=1),
        ).decision
        is Decision.BLOCK_DELETE
    )

    quiescence = _quiescence(first, second)
    archive = _archive(first, second, evaluated_at)
    for invalid_time in (
        evaluated_at.replace(tzinfo=None),
        evaluated_at.astimezone(timezone(timedelta(hours=8))),
    ):
        assert (
            _evaluate(
                first,
                second,
                quiescence=quiescence,
                archive=archive,
                evaluated_at=invalid_time,
            ).decision
            is Decision.BLOCK_DELETE
        )


def test_report_and_evidence_hash_mutation_candidate_contract_drift_and_timezone_block() -> None:
    for mutation in ("row_hash", "report_hash", "quiescence_hash", "archive_hash"):
        first, second = _report_pair()
        quiescence = _quiescence(first, second)
        archive = _archive(first, second, second.scan_completed_at)
        if mutation == "row_hash":
            object.__setattr__(second.rows[0], "row_hash", "f" * 64)
        elif mutation == "report_hash":
            object.__setattr__(second, "report_hash", "f" * 64)
        elif mutation == "quiescence_hash":
            object.__setattr__(quiescence, "evidence_hash", "f" * 64)
        else:
            object.__setattr__(archive, "evidence_hash", "f" * 64)
        assert (
            _evaluate(first, second, quiescence=quiescence, archive=archive).decision
            is Decision.BLOCK_DELETE
        )

    for field_name, value in (
        ("candidate_sha", "b" * 40),
        ("candidate_version", "other-version"),
        ("retirement_target", "other-target"),
        ("environment", "other-environment"),
        ("environment_manifest_hash", "b" * 64),
        ("inventory_hash", "c" * 64),
    ):
        first, second = _report_pair()
        object.__setattr__(second, field_name, value)
        object.__setattr__(
            second,
            "report_hash",
            canonical_sha256(second.to_dict(include_hash=False)),
        )
        assert _evaluate(first, second).decision is Decision.BLOCK_DELETE

    first, second = _report_pair()
    quiescence = _quiescence(first, second)
    archive = _archive(first, second, second.scan_completed_at)
    non_utc = timezone(timedelta(hours=8))
    object.__setattr__(second, "scan_started_at", second.scan_started_at.astimezone(non_utc))
    assert (
        _evaluate(first, second, quiescence=quiescence, archive=archive).decision
        is Decision.BLOCK_DELETE
    )


def test_query_authority_and_report_hwm_semantic_drift_blocks() -> None:
    for changes in (
        {"query_id": "drifted-query"},
        {"query_hash": "1" * 64},
        {"authority": Authority.DOMAIN_LEDGER},
    ):
        first, second = _report_pair()
        rows = list(second.rows)
        rows[0] = replace(rows[0], **changes, row_hash="")
        second = _reseal_report(second, rows=tuple(rows))
        assert _evaluate(first, second).decision is Decision.BLOCK_DELETE

    for sequence in (99, 101):
        first, second = _report_pair(second_sequence=sequence)
        assert _evaluate(first, second).decision is Decision.BLOCK_DELETE


def test_overlap_and_retention_boundary_short_by_one_microsecond_block() -> None:
    first, second = _report_pair()
    quiescence = _quiescence(first, second)
    archive = _archive(first, second, second.scan_completed_at)
    object.__setattr__(
        second,
        "scan_started_at",
        first.scan_completed_at - timedelta(microseconds=1),
    )
    object.__setattr__(
        second,
        "report_hash",
        canonical_sha256(second.to_dict(include_hash=False)),
    )
    assert (
        _evaluate(first, second, quiescence=quiescence, archive=archive).decision
        is Decision.BLOCK_DELETE
    )

    first, second = _report_pair()
    shortened_rows = tuple(
        replace(
            row,
            retention_boundary=row.retention_boundary + timedelta(microseconds=1),
            row_hash="",
        )
        for row in second.rows
    )
    second = _reseal_report(second, rows=shortened_rows)
    assert _evaluate(first, second).decision is Decision.BLOCK_DELETE


def test_reordering_typed_set_members_is_canonically_deterministic() -> None:
    first, second = _report_pair()
    forward = _quiescence(first, second)
    reversed_samples = _quiescence(first, second, reverse_samples=True)

    assert forward.evidence_hash == reversed_samples.evidence_hash
    forward_decision = _evaluate(first, second, quiescence=forward)
    reversed_decision = _evaluate(first, second, quiescence=reversed_samples)
    assert forward_decision.decision_hash == reversed_decision.decision_hash


def test_eligibility_module_has_no_effectful_capability_or_release_claim_surface() -> None:
    source = ELIGIBILITY_SOURCE.read_text(encoding="utf-8")
    tree = ast.parse(source, filename=str(ELIGIBILITY_SOURCE))
    forbidden_import_roots = {
        "asyncio",
        "boto3",
        "httpx",
        "os",
        "psycopg",
        "requests",
        "socket",
        "subprocess",
        "temporalio",
        "urllib",
    }
    forbidden_names = {
        "action",
        "authorized",
        "delete",
        "disable",
        "drop",
        "off",
        "remove",
        "retire",
        "v047",
    }

    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            roots = {alias.name.split(".")[0] for alias in node.names}
            assert not (roots & forbidden_import_roots)
        elif isinstance(node, ast.ImportFrom) and node.module:
            assert node.module.split(".")[0] not in forbidden_import_roots
        elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            assert node.name.lower() not in forbidden_names
        elif isinstance(node, ast.Name):
            assert node.id.lower() not in forbidden_names
        elif isinstance(node, ast.Attribute):
            assert node.attr.lower() not in forbidden_names

    assert "V047" not in source
    assert "Decision.PASS" not in source
    assert "Decision.AUTHORIZED" not in source
    assert "authorizes_cleanup" in source
    assert "human_authorization_required" in source
