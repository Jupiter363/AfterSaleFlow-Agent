from __future__ import annotations

from dataclasses import replace
from types import MappingProxyType
from typing import Mapping

from .adapters import (
    ADAPTERS,
    ADAPTER_REGISTRY,
    ActiveReferenceAdapter,
    ReadOnlyPagedReader,
)
from .model import (
    ActiveReferenceReport,
    Authority,
    CompletenessStatus,
    Decision,
    ReferenceClass,
    ReferenceRow,
    ScanContext,
    SourceSystem,
    canonical_sha256,
)


ReaderKey = ReferenceClass | SourceSystem | Authority

_READ_ONLY_CAPABILITIES = {
    "credential_loading": False,
    "delete": False,
    "mutation": False,
    "network": False,
    "off_activation": False,
    "retirement": False,
    "subprocess": False,
}


def adapter_inventory() -> tuple[dict[str, object], ...]:
    return tuple(
        {
            "authority": definition.authority.value,
            "high_watermark_ledger_id": definition.high_watermark_ledger_id,
            "owner": definition.owner,
            "query_hash": definition.query_hash,
            "query_id": definition.query_id,
            "query_requirements": list(definition.query_requirements),
            "reference_class": definition.reference_class.value,
            "source_system": definition.source_system.value,
            "wave2_authorities": [
                authority.value for authority in definition.wave2_authorities
            ],
        }
        for definition in ADAPTER_REGISTRY.values()
    )


def adapter_inventory_hash() -> str:
    return canonical_sha256(list(adapter_inventory()))


def verify_sealed_active_reference_report(
    report: ActiveReferenceReport,
) -> str:
    """Recompute every immutable hash without changing single-scan decisions."""

    if not isinstance(report, ActiveReferenceReport):
        raise TypeError("report must be ActiveReferenceReport")
    sealed_rows = tuple(replace(row) for row in report.rows)
    sealed_report = replace(report, rows=sealed_rows)
    for row in sealed_report.rows:
        expected_row_hash = canonical_sha256(row.to_dict(include_hash=False))
        if row.row_hash != expected_row_hash:
            raise ValueError("sealed report row hash mismatch")
    expected_report_hash = canonical_sha256(
        sealed_report.to_dict(include_hash=False)
    )
    if sealed_report.report_hash != expected_report_hash:
        raise ValueError("sealed report hash mismatch")
    if sealed_report.to_dict()["capabilities"] != _READ_ONLY_CAPABILITIES:
        raise ValueError("sealed report capabilities must remain read-only")
    return expected_report_hash


def _reader_for(
    readers: Mapping[ReaderKey, ReadOnlyPagedReader],
    adapter: ActiveReferenceAdapter,
) -> ReadOnlyPagedReader | None:
    definition = adapter.definition
    for key in (
        definition.reference_class,
        definition.authority,
        definition.source_system,
    ):
        reader = readers.get(key)
        if reader is not None:
            return reader
    return None


def build_active_reference_report(
    context: ScanContext,
    readers: Mapping[ReaderKey, ReadOnlyPagedReader],
) -> ActiveReferenceReport:
    """Run one fail-closed, read-only scan across the fixed 35-class inventory."""

    rows: list[ReferenceRow] = []
    for adapter in ADAPTERS.values():
        reader = _reader_for(readers, adapter)
        if reader is None:
            row = adapter._blocked_row(  # noqa: SLF001 - same package fail-closed path
                context,
                [],
                set(),
                0,
                "MISSING_AUTHORITY_READER",
                status=CompletenessStatus.UNKNOWN,
            )
        else:
            try:
                row = adapter.scan(reader, context)
            except Exception:
                row = adapter._blocked_row(  # noqa: SLF001
                    context,
                    [],
                    set(),
                    0,
                    "PARSE_SCHEMA_ERROR",
                    status=CompletenessStatus.ERROR,
                )
        rows.append(row)

    immutable_rows = tuple(rows)
    decision = (
        Decision.BLOCK_DELETE
        if any(row.decision is Decision.BLOCK_DELETE for row in immutable_rows)
        else Decision.RETAIN
    )
    return ActiveReferenceReport(
        candidate_sha=context.candidate_sha,
        candidate_version=context.candidate_version,
        retirement_target=context.retirement_target,
        environment=context.environment,
        environment_manifest_hash=context.environment_manifest_hash,
        credentials_class=context.credentials_class,
        tool_versions=context.tool_versions,
        scan_started_at=context.scan_started_at,
        scan_completed_at=context.scan_completed_at,
        inventory_hash=adapter_inventory_hash(),
        rows=immutable_rows,
        decision=decision,
    )


def immutable_registry() -> Mapping[ReferenceClass, object]:
    """Expose registry membership without exposing a mutable backing dictionary."""

    return MappingProxyType(dict(ADAPTER_REGISTRY))
