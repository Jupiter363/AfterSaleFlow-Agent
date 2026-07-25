from __future__ import annotations

import ast
import hashlib
import json
from dataclasses import FrozenInstanceError, replace
from datetime import datetime, timedelta, timezone
from pathlib import Path

import jsonschema
import pytest

from scripts.phase8.reference_audit import (
    ADAPTERS,
    ADAPTER_REGISTRY,
    Authority,
    CompletenessStatus,
    CredentialClass,
    Decision,
    HighWatermark,
    ReferenceClass,
    ReferenceEvidence,
    ReferencePage,
    ReferencePermissionError,
    ReferenceQueryError,
    ReferenceTimeoutError,
    ScanContext,
    Wave2Authority,
    adapter_inventory_hash,
    build_active_reference_report,
    canonical_sha256,
)


ROOT = Path(__file__).resolve().parents[2]
SCHEMA_PATH = (
    ROOT / "contracts/agent-platform/phase8/active-reference-report.schema.json"
)
PACKAGE = ROOT / "scripts/phase8/reference_audit"
NOW = datetime(2026, 7, 25, 12, 0, tzinfo=timezone.utc)
ENVIRONMENT_HASH = "e" * 64


def _context(**changes: object) -> ScanContext:
    values: dict[str, object] = {
        "candidate_sha": "a" * 40,
        "candidate_version": "release-2026.07.25",
        "retirement_target": "legacy-worker-v1",
        "environment": "synthetic-phase8",
        "environment_manifest_hash": ENVIRONMENT_HASH,
        "credentials_class": CredentialClass.REPORTING_READ_ONLY,
        "tool_versions": {"reference-audit": "1.0.0"},
        "scan_started_at": NOW,
        "scan_completed_at": NOW + timedelta(minutes=5),
        "retention_boundary": NOW - timedelta(days=30),
        "max_replica_lag_seconds": 5.0,
        "max_high_watermark_age": timedelta(hours=1),
        "page_size": 2,
        "max_pages_per_class": 4,
    }
    values.update(changes)
    return ScanContext(**values)  # type: ignore[arg-type]


def _page(
    request: object,
    context: ScanContext,
    *,
    records: tuple[ReferenceEvidence, ...] = (),
    next_page_token: str | None = None,
    status: CompletenessStatus = CompletenessStatus.COMPLETE,
    error_code: str | None = None,
) -> ReferencePage:
    watermark = HighWatermark(
        ledger_id=request.expected_high_watermark_ledger_id,
        sequence=41,
        observed_at=context.scan_started_at,
        candidate_version=context.candidate_version,
        environment_manifest_hash=context.environment_manifest_hash,
    )
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
        next_page_token=next_page_token,
        page_ordinal=request.page_ordinal,
        records=records,
        completeness_status=status,
        scan_high_watermark=watermark,
        authority_high_watermark=watermark,
        replica_lag_seconds=1.0,
        observed_at=context.scan_started_at + timedelta(minutes=1),
        query_evidence_reference=(
            f"page:{request.reference_class.value}:{request.page_ordinal}"
        ),
        error_code=error_code,
    )


class ZeroReader:
    def __init__(self, context: ScanContext):
        self.context = context

    def read_page(self, request: object) -> ReferencePage:
        return _page(request, self.context)


class ActiveReader(ZeroReader):
    def read_page(self, request: object) -> ReferencePage:
        record = ReferenceEvidence(
            identity=f"reference:{request.reference_class.value}",
            referenced_at=self.context.scan_started_at - timedelta(hours=1),
            evidence_reference=f"record:{request.reference_class.value}",
        )
        return _page(request, self.context, records=(record,))


def _schema() -> dict[str, object]:
    value = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    assert isinstance(value, dict)
    return value


def test_registry_and_schema_cover_exact_fixed_35_classes() -> None:
    assert len(ReferenceClass) == len(ADAPTER_REGISTRY) == len(ADAPTERS) == 35
    assert set(ADAPTER_REGISTRY) == set(ADAPTERS) == set(ReferenceClass)
    assert len({item.query_id for item in ADAPTER_REGISTRY.values()}) == 35
    assert len({item.query_hash for item in ADAPTER_REGISTRY.values()}) == 35
    assert len({item.high_watermark_ledger_id for item in ADAPTER_REGISTRY.values()}) == 35

    schema = _schema()
    schema_classes = set(schema["$defs"]["referenceClass"]["enum"])
    assert schema_classes == {item.value for item in ReferenceClass}
    assert len(schema["properties"]["rows"]["allOf"]) == 35
    assert set(schema["$defs"]["decision"]["enum"]) == {
        "RETAIN",
        "BLOCK_DELETE",
        "ELIGIBLE",
    }


def test_registry_is_immutable_and_query_hashes_are_canonical() -> None:
    reference_class = ReferenceClass.TEMPORAL_WORKFLOW
    with pytest.raises(TypeError):
        ADAPTER_REGISTRY[reference_class] = ADAPTER_REGISTRY[reference_class]  # type: ignore[index]
    definition = ADAPTER_REGISTRY[reference_class]
    expected = canonical_sha256(
        {
            "authority": definition.authority.value,
            "high_watermark_ledger_id": definition.high_watermark_ledger_id,
            "query_id": definition.query_id,
            "query_requirements": list(definition.query_requirements),
            "reference_class": definition.reference_class.value,
            "source_system": definition.source_system.value,
            "wave2_authorities": [
                item.value for item in definition.wave2_authorities
            ],
        }
    )
    assert definition.query_hash == expected
    with pytest.raises(FrozenInstanceError):
        definition.owner = "other"  # type: ignore[misc]


def test_wave2_authorities_are_not_collapsed_into_temporal_projection() -> None:
    temporal = ADAPTER_REGISTRY[
        ReferenceClass.TEMPORAL_ROOM_EPOCH_BUILD_REACHABILITY
    ]
    logical_run = ADAPTER_REGISTRY[ReferenceClass.LEGACY_V1_LOGICAL_RUN]
    attempt = ADAPTER_REGISTRY[ReferenceClass.LEGACY_V1_ATTEMPT]
    room_epoch = ADAPTER_REGISTRY[ReferenceClass.ROOM_EPOCH]
    absent_projection = ADAPTER_REGISTRY[ReferenceClass.DOMAIN_CASE_COMMAND]

    assert temporal.wave2_authorities == (Wave2Authority.TEMPORAL_EPOCH,)
    assert temporal.authority is Authority.TEMPORAL_VISIBILITY
    for definition in (logical_run, attempt):
        assert set(definition.wave2_authorities) == {
            Wave2Authority.LEGACY_V1,
            Wave2Authority.LEGACY_WORKER,
        }
        assert "v1" in " ".join(definition.query_requirements)
        assert "legacy_worker" in " ".join(definition.query_requirements)
    assert Wave2Authority.PROJECTION_ABSENCE in room_epoch.wave2_authorities
    assert absent_projection.authority is Authority.DOMAIN_PROJECTION_ABSENCE_LEDGER
    assert "absent_projection" in " ".join(absent_projection.query_requirements)
    room_pins = " ".join(room_epoch.query_requirements)
    for required_pin in (
        "workflow",
        "graph",
        "prompt",
        "schema",
        "policy",
        "codec",
        "artifact",
        "stream",
    ):
        assert required_pin in room_pins


def test_complete_zero_report_has_35_blocking_rows_and_valid_schema() -> None:
    context = _context()
    report = build_active_reference_report(
        context, {source: ZeroReader(context) for source in set(item.source_system for item in ADAPTER_REGISTRY.values())}
    )
    assert len(report.rows) == 35
    assert report.decision is Decision.BLOCK_DELETE
    assert {row.reference_class for row in report.rows} == set(ReferenceClass)
    for row in report.rows:
        assert row.completeness_status is CompletenessStatus.COMPLETE
        assert row.decision is Decision.BLOCK_DELETE
        assert row.active_count == 0
        assert row.reason_codes == (
            "SINGLE_SCAN_ZERO_NOT_ELIGIBLE",
            "SECOND_SCAN_AND_QUIESCENCE_REQUIRED",
        )
        assert any(item.startswith("page:") for item in row.evidence_references)
        assert len(row.row_hash) == 64
    document = report.to_dict()
    jsonschema.Draft202012Validator(_schema()).validate(document)
    assert hashlib.sha256(report.to_json_bytes()).hexdigest() != report.report_hash
    assert report.report_hash == canonical_sha256(report.to_dict(include_hash=False))
    assert report.inventory_hash == adapter_inventory_hash()
    assert report.to_dict()["capabilities"] == {
        "credential_loading": False,
        "delete": False,
        "mutation": False,
        "network": False,
        "off_activation": False,
        "retirement": False,
        "subprocess": False,
    }


def test_nonzero_references_are_retained_but_never_eligible() -> None:
    context = _context()
    reader = ActiveReader(context)
    report = build_active_reference_report(
        context, {source: reader for source in set(item.source_system for item in ADAPTER_REGISTRY.values())}
    )
    assert report.decision is Decision.RETAIN
    assert {row.decision for row in report.rows} == {Decision.RETAIN}
    assert {row.active_count for row in report.rows} == {1}
    jsonschema.Draft202012Validator(_schema()).validate(report.to_dict())


def test_missing_readers_emit_all_35_unknown_block_rows() -> None:
    report = build_active_reference_report(_context(), {})
    assert len(report.rows) == 35
    assert report.decision is Decision.BLOCK_DELETE
    assert {row.completeness_status for row in report.rows} == {
        CompletenessStatus.UNKNOWN
    }
    assert {row.reason_codes for row in report.rows} == {
        ("MISSING_AUTHORITY_READER",)
    }
    assert all(row.evidence_references for row in report.rows)
    jsonschema.Draft202012Validator(_schema()).validate(report.to_dict())


@pytest.mark.parametrize(
    ("failure", "reason"),
    [
        (ReferencePermissionError("denied"), "PERMISSION_ERROR"),
        (ReferenceTimeoutError("timeout"), "QUERY_TIMEOUT"),
        (ReferenceQueryError("failed"), "QUERY_ERROR"),
        (PermissionError("denied"), "PERMISSION_ERROR"),
        (TimeoutError("timeout"), "QUERY_TIMEOUT"),
    ],
)
def test_first_page_reader_failures_are_materialized(
    failure: Exception, reason: str
) -> None:
    class FailingReader:
        def read_page(self, request: object) -> ReferencePage:
            raise failure

    row = ADAPTERS[ReferenceClass.TEMPORAL_WORKFLOW].scan(
        FailingReader(), _context()
    )
    assert row.decision is Decision.BLOCK_DELETE
    assert row.completeness_status is CompletenessStatus.ERROR
    assert row.reason_codes == (reason,)
    assert row.active_count == 0
    assert row.page_count == 0
    assert any(item.startswith("failure:") for item in row.evidence_references)


def test_pagination_is_exhaustive_and_duplicate_free() -> None:
    context = _context()
    requests: list[object] = []

    class PagedReader:
        def read_page(self, request: object) -> ReferencePage:
            requests.append(request)
            record = ReferenceEvidence(
                identity=f"reference:{request.page_ordinal}",
                referenced_at=NOW - timedelta(hours=request.page_ordinal + 1),
                evidence_reference=f"record:{request.page_ordinal}",
            )
            return _page(
                request,
                context,
                records=(record,),
                next_page_token="cursor-1" if request.page_ordinal == 0 else None,
            )

    row = ADAPTERS[ReferenceClass.TEMPORAL_WORKFLOW].scan(
        PagedReader(), context
    )
    assert [request.page_token for request in requests] == [None, "cursor-1"]
    assert row.page_count == 2
    assert row.active_count == 2
    assert row.decision is Decision.RETAIN
    assert row.oldest_reference_at == NOW - timedelta(hours=2)
    assert row.newest_reference_at == NOW - timedelta(hours=1)


def test_token_loop_and_duplicate_reference_block_delete() -> None:
    context = _context()

    class LoopReader:
        def read_page(self, request: object) -> ReferencePage:
            return _page(request, context, next_page_token="loop")

    loop = ADAPTERS[ReferenceClass.TEMPORAL_WORKFLOW].scan(
        LoopReader(), context
    )
    assert loop.decision is Decision.BLOCK_DELETE
    assert loop.completeness_status is CompletenessStatus.PARTIAL
    assert loop.reason_codes == ("PAGINATION_TOKEN_LOOP",)

    record = ReferenceEvidence(
        identity="same-reference",
        referenced_at=NOW,
        evidence_reference="record:same-reference",
    )

    class DuplicateReader:
        def read_page(self, request: object) -> ReferencePage:
            return _page(
                request,
                context,
                records=(record,),
                next_page_token="next" if request.page_ordinal == 0 else None,
            )

    duplicate = ADAPTERS[ReferenceClass.TEMPORAL_WORKFLOW].scan(
        DuplicateReader(), context
    )
    assert duplicate.decision is Decision.BLOCK_DELETE
    assert duplicate.completeness_status is CompletenessStatus.PARTIAL
    assert duplicate.reason_codes == ("DUPLICATE_REFERENCE_IDENTITY",)


@pytest.mark.parametrize(
    ("change", "reason"),
    [
        ({"authority": Authority.DOMAIN_LEDGER}, "QUERY_IDENTITY_DRIFT"),
        ({"query_hash": "b" * 64}, "QUERY_IDENTITY_DRIFT"),
        ({"retirement_target": "other-target"}, "CANDIDATE_ENVIRONMENT_DRIFT"),
        ({"candidate_version": "other-version"}, "CANDIDATE_ENVIRONMENT_DRIFT"),
        ({"environment_manifest_hash": "b" * 64}, "CANDIDATE_ENVIRONMENT_DRIFT"),
        ({"retention_boundary": NOW - timedelta(days=29)}, "RETENTION_BOUNDARY_DRIFT"),
    ],
)
def test_page_provenance_drift_blocks_delete(
    change: dict[str, object], reason: str
) -> None:
    context = _context()

    class DriftReader:
        def read_page(self, request: object) -> ReferencePage:
            return replace(_page(request, context), **change)

    row = ADAPTERS[ReferenceClass.TEMPORAL_WORKFLOW].scan(
        DriftReader(), context
    )
    assert row.decision is Decision.BLOCK_DELETE
    assert row.reason_codes == (reason,)


def test_authority_watermark_lag_partial_and_page_limit_fail_closed() -> None:
    context = _context(max_pages_per_class=1)

    class WrongLedgerReader:
        def read_page(self, request: object) -> ReferencePage:
            page = _page(request, context)
            wrong = replace(page.scan_high_watermark, ledger_id="wrong-ledger")
            return replace(
                page,
                scan_high_watermark=wrong,
                authority_high_watermark=wrong,
            )

    wrong_ledger = ADAPTERS[ReferenceClass.TEMPORAL_WORKFLOW].scan(
        WrongLedgerReader(), context
    )
    assert wrong_ledger.reason_codes == ("AUTHORITY_LEDGER_MISMATCH",)

    class LagReader:
        def read_page(self, request: object) -> ReferencePage:
            return replace(_page(request, context), replica_lag_seconds=6.0)

    lag = ADAPTERS[ReferenceClass.TEMPORAL_WORKFLOW].scan(LagReader(), context)
    assert lag.reason_codes == ("REPLICA_LAG_OUT_OF_BOUNDS",)

    class PartialReader:
        def read_page(self, request: object) -> ReferencePage:
            return _page(
                request,
                context,
                status=CompletenessStatus.PARTIAL,
                error_code="PARTIAL_PAGINATION",
            )

    partial = ADAPTERS[ReferenceClass.TEMPORAL_WORKFLOW].scan(
        PartialReader(), context
    )
    assert partial.completeness_status is CompletenessStatus.PARTIAL
    assert partial.reason_codes == ("PARTIAL_PAGINATION",)

    class NeverEndingReader:
        def read_page(self, request: object) -> ReferencePage:
            return _page(request, context, next_page_token="another-page")

    page_limit = ADAPTERS[ReferenceClass.TEMPORAL_WORKFLOW].scan(
        NeverEndingReader(), context
    )
    assert page_limit.completeness_status is CompletenessStatus.PARTIAL
    assert page_limit.reason_codes == ("PAGINATION_LIMIT_EXCEEDED",)


def test_nan_lag_and_direct_single_scan_eligible_are_rejected() -> None:
    with pytest.raises(ValueError, match="finite"):
        _context(max_replica_lag_seconds=float("nan"))
    with pytest.raises(ValueError, match="integer"):
        HighWatermark(
            ledger_id="ledger",
            sequence=1.5,  # type: ignore[arg-type]
            observed_at=NOW,
            candidate_version="candidate",
            environment_manifest_hash=ENVIRONMENT_HASH,
        )
    with pytest.raises(TypeError, match="boolean"):
        HighWatermark(
            ledger_id="ledger",
            sequence=1,
            observed_at=NOW,
            candidate_version="candidate",
            environment_manifest_hash=ENVIRONMENT_HASH,
            durable="true",  # type: ignore[arg-type]
        )

    context = _context()
    definition = ADAPTER_REGISTRY[ReferenceClass.TEMPORAL_WORKFLOW]
    watermark = HighWatermark(
        ledger_id=definition.high_watermark_ledger_id,
        sequence=1,
        observed_at=NOW,
        candidate_version=context.candidate_version,
        environment_manifest_hash=context.environment_manifest_hash,
    )
    with pytest.raises(ValueError, match="finite"):
        ReferencePage(
            source_system=definition.source_system,
            authority=definition.authority,
            reference_class=definition.reference_class,
            query_id=definition.query_id,
            query_hash=definition.query_hash,
            candidate_version=context.candidate_version,
            retirement_target=context.retirement_target,
            environment_manifest_hash=context.environment_manifest_hash,
            retention_boundary=context.retention_boundary,
            requested_page_token=None,
            next_page_token=None,
            page_ordinal=0,
            records=(),
            completeness_status=CompletenessStatus.COMPLETE,
            scan_high_watermark=watermark,
            authority_high_watermark=watermark,
            replica_lag_seconds=float("nan"),
            observed_at=NOW,
            query_evidence_reference="page:nan",
        )

    zero_row = ADAPTERS[ReferenceClass.TEMPORAL_WORKFLOW].scan(
        ZeroReader(context), context
    )
    with pytest.raises(ValueError, match="single active-reference scan"):
        replace(zero_row, decision=Decision.ELIGIBLE, row_hash="")


def test_non_durable_authority_watermark_is_preserved_as_blocking_evidence() -> None:
    context = _context()

    class NonDurableReader:
        def read_page(self, request: object) -> ReferencePage:
            page = _page(request, context)
            watermark = replace(page.scan_high_watermark, durable=False)
            return replace(
                page,
                scan_high_watermark=watermark,
                authority_high_watermark=watermark,
            )

    sources = set(item.source_system for item in ADAPTER_REGISTRY.values())
    report = build_active_reference_report(
        context, {source: NonDurableReader() for source in sources}
    )
    assert report.decision is Decision.BLOCK_DELETE
    assert {row.reason_codes for row in report.rows} == {
        ("INVALID_OR_STALE_HIGH_WATERMARK",)
    }
    assert all(
        row.scan_high_watermark is not None
        and not row.scan_high_watermark.durable
        for row in report.rows
    )
    jsonschema.Draft202012Validator(_schema()).validate(report.to_dict())


def test_schema_rejects_duplicate_class_inventory_and_eligible_scan() -> None:
    context = _context()
    sources = set(item.source_system for item in ADAPTER_REGISTRY.values())
    document = build_active_reference_report(
        context, {source: ZeroReader(context) for source in sources}
    ).to_dict()
    validator = jsonschema.Draft202012Validator(_schema())

    duplicate = json.loads(json.dumps(document))
    duplicate["rows"][1]["reference_class"] = duplicate["rows"][0][
        "reference_class"
    ]
    assert list(validator.iter_errors(duplicate))

    eligible = json.loads(json.dumps(document))
    eligible["decision"] = "ELIGIBLE"
    eligible["rows"][0]["decision"] = "ELIGIBLE"
    assert list(validator.iter_errors(eligible))


def test_package_has_no_effectful_import_or_command_surface() -> None:
    forbidden_import_roots = {
        "asyncio",
        "boto3",
        "httpx",
        "os",
        "psycopg",
        "requests",
        "socket",
        "subprocess",
        "urllib",
    }
    forbidden_functions = {
        "delete",
        "disable",
        "deregister",
        "enqueue",
        "mutate",
        "retire",
        "run",
        "switch",
    }
    for path in PACKAGE.glob("*.py"):
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                assert not ({name.name.split(".")[0] for name in node.names} & forbidden_import_roots)
            elif isinstance(node, ast.ImportFrom) and node.module:
                assert node.module.split(".")[0] not in forbidden_import_roots
            elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                assert node.name.lower() not in forbidden_functions
