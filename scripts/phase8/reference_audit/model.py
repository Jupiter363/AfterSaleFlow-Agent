from __future__ import annotations

import hashlib
import json
import math
import re
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from enum import Enum
from types import MappingProxyType
from typing import Mapping


SCHEMA_VERSION = "phase8-active-reference-report.v1"
_SHA1_RE = re.compile(r"^[0-9a-f]{40}$")
_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
_IDENTIFIER_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}$")


class ReferenceClass(str, Enum):
    TEMPORAL_WORKFLOW = "TEMPORAL_WORKFLOW"
    TEMPORAL_CHILD = "TEMPORAL_CHILD"
    TEMPORAL_CONTINUE_AS_NEW = "TEMPORAL_CONTINUE_AS_NEW"
    TEMPORAL_SCHEDULE = "TEMPORAL_SCHEDULE"
    TEMPORAL_PENDING_WORK = "TEMPORAL_PENDING_WORK"
    TEMPORAL_ROOM_EPOCH_BUILD_REACHABILITY = (
        "TEMPORAL_ROOM_EPOCH_BUILD_REACHABILITY"
    )
    WORKER_BUILD_ID = "WORKER_BUILD_ID"
    GRAPH_THREAD = "GRAPH_THREAD"
    GRAPH_VERSION = "GRAPH_VERSION"
    GRAPH_CHECKPOINT = "GRAPH_CHECKPOINT"
    ROOM_EPOCH = "ROOM_EPOCH"
    LEGACY_V1_LOGICAL_RUN = "LEGACY_V1_LOGICAL_RUN"
    LEGACY_V1_ATTEMPT = "LEGACY_V1_ATTEMPT"
    HOT_STREAM_READER = "HOT_STREAM_READER"
    DOMAIN_CASE_COMMAND = "DOMAIN_CASE_COMMAND"
    DOMAIN_OPERATION = "DOMAIN_OPERATION"
    DOMAIN_FINALIZER = "DOMAIN_FINALIZER"
    DEPLOYED_API_VERSION = "DEPLOYED_API_VERSION"
    DEPLOYED_WORKER_VERSION = "DEPLOYED_WORKER_VERSION"
    DEPLOYED_GRAPH_VERSION = "DEPLOYED_GRAPH_VERSION"
    DEPLOYED_COMPATIBILITY_READER_VERSION = (
        "DEPLOYED_COMPATIBILITY_READER_VERSION"
    )
    OUTBOX = "OUTBOX"
    LEASE = "LEASE"
    STREAM_CURSOR = "STREAM_CURSOR"
    LEGACY_READER_VERSION = "LEGACY_READER_VERSION"
    MEMORY_FRAME_READER = "MEMORY_FRAME_READER"
    LEGACY_ENDPOINT_CALLER = "LEGACY_ENDPOINT_CALLER"
    OBJECT_STORE_MANIFEST = "OBJECT_STORE_MANIFEST"
    OBJECT_STORE_CODEC = "OBJECT_STORE_CODEC"
    OBJECT_STORE_SCHEMA = "OBJECT_STORE_SCHEMA"
    OBJECT_STORE_PROMPT = "OBJECT_STORE_PROMPT"
    OBJECT_STORE_ARTIFACT = "OBJECT_STORE_ARTIFACT"
    RETAINED_WINDOW_FRONTEND_LEGACY_ENDPOINT = (
        "RETAINED_WINDOW_FRONTEND_LEGACY_ENDPOINT"
    )
    RETAINED_WINDOW_API_LEGACY_ENDPOINT = (
        "RETAINED_WINDOW_API_LEGACY_ENDPOINT"
    )
    AGENT_STREAM_V1_TELEMETRY = "AGENT_STREAM_V1_TELEMETRY"


class SourceSystem(str, Enum):
    TEMPORAL = "TEMPORAL"
    DOMAIN_POSTGRESQL = "DOMAIN_POSTGRESQL"
    GRAPH_POSTGRESQL = "GRAPH_POSTGRESQL"
    DEPLOYMENT_INVENTORY = "DEPLOYMENT_INVENTORY"
    OBJECT_STORAGE = "OBJECT_STORAGE"
    TELEMETRY = "TELEMETRY"


class Authority(str, Enum):
    TEMPORAL_VISIBILITY = "TEMPORAL_VISIBILITY"
    TEMPORAL_SCHEDULE_VISIBILITY = "TEMPORAL_SCHEDULE_VISIBILITY"
    TEMPORAL_WORKER_BUILD_REACHABILITY = "TEMPORAL_WORKER_BUILD_REACHABILITY"
    DOMAIN_LEDGER = "DOMAIN_LEDGER"
    DOMAIN_LEGACY_V1_LEDGER = "DOMAIN_LEGACY_V1_LEDGER"
    DOMAIN_LEGACY_WORKER_CANDIDATE_LEDGER = (
        "DOMAIN_LEGACY_WORKER_CANDIDATE_LEDGER"
    )
    DOMAIN_PROJECTION_ABSENCE_LEDGER = "DOMAIN_PROJECTION_ABSENCE_LEDGER"
    GRAPH_LEDGER = "GRAPH_LEDGER"
    DEPLOYMENT_MANIFEST = "DEPLOYMENT_MANIFEST"
    IMMUTABLE_OBJECT_MANIFEST = "IMMUTABLE_OBJECT_MANIFEST"
    AGENT_STREAM_TELEMETRY_LEDGER = "AGENT_STREAM_TELEMETRY_LEDGER"


class Wave2Authority(str, Enum):
    TEMPORAL_EPOCH = "TEMPORAL_EPOCH"
    LEGACY_V1 = "LEGACY_V1"
    LEGACY_WORKER = "LEGACY_WORKER"
    PROJECTION_ABSENCE = "PROJECTION_ABSENCE"


class CompletenessStatus(str, Enum):
    COMPLETE = "COMPLETE"
    UNKNOWN = "UNKNOWN"
    PARTIAL = "PARTIAL"
    ERROR = "ERROR"


class Decision(str, Enum):
    RETAIN = "RETAIN"
    BLOCK_DELETE = "BLOCK_DELETE"
    ELIGIBLE = "ELIGIBLE"


class CredentialClass(str, Enum):
    REPORTING_READ_ONLY = "REPORTING_READ_ONLY"
    TEMPORAL_VISIBILITY_READ_ONLY = "TEMPORAL_VISIBILITY_READ_ONLY"
    INVENTORY_METADATA_READ_ONLY = "INVENTORY_METADATA_READ_ONLY"


def canonical_json_bytes(value: object) -> bytes:
    return json.dumps(
        value,
        allow_nan=False,
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def canonical_sha256(value: object) -> str:
    return hashlib.sha256(canonical_json_bytes(value)).hexdigest()


def format_timestamp(value: datetime) -> str:
    _require_aware(value, "timestamp")
    return value.astimezone(timezone.utc).isoformat(timespec="microseconds").replace(
        "+00:00", "Z"
    )


def _require_identifier(value: str, name: str) -> None:
    if not isinstance(value, str) or not _IDENTIFIER_RE.fullmatch(value):
        raise ValueError(f"{name} must be a non-secret stable identifier")


def _require_sha1(value: str, name: str) -> None:
    if not isinstance(value, str) or not _SHA1_RE.fullmatch(value):
        raise ValueError(f"{name} must be a lowercase full SHA-1")


def _require_sha256(value: str, name: str) -> None:
    if not isinstance(value, str) or not _SHA256_RE.fullmatch(value):
        raise ValueError(f"{name} must be a lowercase SHA-256")


def _require_aware(value: datetime, name: str) -> None:
    if not isinstance(value, datetime) or value.tzinfo is None:
        raise ValueError(f"{name} must be timezone-aware")


def _freeze_mapping(value: Mapping[str, str], name: str) -> Mapping[str, str]:
    frozen: dict[str, str] = {}
    for key, item in value.items():
        _require_identifier(key, f"{name} key")
        _require_identifier(item, f"{name}[{key}]")
        frozen[key] = item
    return MappingProxyType(dict(sorted(frozen.items())))


@dataclass(frozen=True, slots=True)
class HighWatermark:
    ledger_id: str
    sequence: int
    observed_at: datetime
    candidate_version: str
    environment_manifest_hash: str
    durable: bool = True

    def __post_init__(self) -> None:
        _require_identifier(self.ledger_id, "ledger_id")
        if (
            isinstance(self.sequence, bool)
            or not isinstance(self.sequence, int)
            or self.sequence < 0
        ):
            raise ValueError("sequence must be a non-negative integer")
        _require_aware(self.observed_at, "observed_at")
        _require_identifier(self.candidate_version, "candidate_version")
        _require_sha256(self.environment_manifest_hash, "environment_manifest_hash")
        if not isinstance(self.durable, bool):
            raise TypeError("durable must be boolean")

    def to_dict(self) -> dict[str, object]:
        return {
            "candidate_version": self.candidate_version,
            "durable": self.durable,
            "environment_manifest_hash": self.environment_manifest_hash,
            "ledger_id": self.ledger_id,
            "observed_at": format_timestamp(self.observed_at),
            "sequence": self.sequence,
        }


@dataclass(frozen=True, slots=True)
class ReferenceEvidence:
    identity: str
    referenced_at: datetime
    evidence_reference: str

    def __post_init__(self) -> None:
        _require_identifier(self.identity, "reference identity")
        _require_aware(self.referenced_at, "referenced_at")
        _require_identifier(self.evidence_reference, "evidence_reference")


@dataclass(frozen=True, slots=True)
class ReferencePage:
    source_system: SourceSystem
    authority: Authority
    reference_class: ReferenceClass
    query_id: str
    query_hash: str
    candidate_version: str
    retirement_target: str
    environment_manifest_hash: str
    retention_boundary: datetime
    requested_page_token: str | None
    next_page_token: str | None
    page_ordinal: int
    records: tuple[ReferenceEvidence, ...]
    completeness_status: CompletenessStatus
    scan_high_watermark: HighWatermark | None
    authority_high_watermark: HighWatermark | None
    replica_lag_seconds: float | None
    observed_at: datetime
    query_evidence_reference: str
    error_code: str | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.source_system, SourceSystem):
            raise TypeError("source_system must be SourceSystem")
        if not isinstance(self.authority, Authority):
            raise TypeError("authority must be Authority")
        if not isinstance(self.reference_class, ReferenceClass):
            raise TypeError("reference_class must be ReferenceClass")
        _require_identifier(self.query_id, "query_id")
        _require_sha256(self.query_hash, "query_hash")
        _require_identifier(self.candidate_version, "candidate_version")
        _require_identifier(self.retirement_target, "retirement_target")
        _require_sha256(self.environment_manifest_hash, "environment_manifest_hash")
        _require_aware(self.retention_boundary, "retention_boundary")
        if self.requested_page_token is not None:
            _require_identifier(self.requested_page_token, "requested_page_token")
        if self.next_page_token is not None:
            _require_identifier(self.next_page_token, "next_page_token")
        if self.page_ordinal < 0:
            raise ValueError("page_ordinal must be non-negative")
        if not isinstance(self.records, tuple):
            raise TypeError("records must be an immutable tuple")
        if not isinstance(self.completeness_status, CompletenessStatus):
            raise TypeError("completeness_status must be CompletenessStatus")
        if self.replica_lag_seconds is not None:
            if (
                isinstance(self.replica_lag_seconds, bool)
                or not isinstance(self.replica_lag_seconds, (int, float))
                or not math.isfinite(self.replica_lag_seconds)
                or self.replica_lag_seconds < 0
            ):
                raise ValueError("replica_lag_seconds must be finite and non-negative")
        _require_aware(self.observed_at, "observed_at")
        _require_identifier(self.query_evidence_reference, "query_evidence_reference")
        if self.error_code is not None:
            _require_identifier(self.error_code, "error_code")


@dataclass(frozen=True, slots=True)
class ScanContext:
    candidate_sha: str
    candidate_version: str
    retirement_target: str
    environment: str
    environment_manifest_hash: str
    credentials_class: CredentialClass
    tool_versions: Mapping[str, str]
    scan_started_at: datetime
    scan_completed_at: datetime
    retention_boundary: datetime
    max_replica_lag_seconds: float
    max_high_watermark_age: timedelta
    page_size: int = 500
    max_pages_per_class: int = 10_000

    def __post_init__(self) -> None:
        _require_sha1(self.candidate_sha, "candidate_sha")
        _require_identifier(self.candidate_version, "candidate_version")
        _require_identifier(self.retirement_target, "retirement_target")
        _require_identifier(self.environment, "environment")
        _require_sha256(self.environment_manifest_hash, "environment_manifest_hash")
        _require_aware(self.scan_started_at, "scan_started_at")
        _require_aware(self.scan_completed_at, "scan_completed_at")
        if self.scan_completed_at < self.scan_started_at:
            raise ValueError("scan_completed_at must not precede scan_started_at")
        _require_aware(self.retention_boundary, "retention_boundary")
        if self.retention_boundary > self.scan_started_at:
            raise ValueError("retention_boundary must not follow scan_started_at")
        if not isinstance(self.credentials_class, CredentialClass):
            raise TypeError("credentials_class must be CredentialClass")
        if (
            isinstance(self.max_replica_lag_seconds, bool)
            or not isinstance(self.max_replica_lag_seconds, (int, float))
            or not math.isfinite(self.max_replica_lag_seconds)
            or self.max_replica_lag_seconds < 0
        ):
            raise ValueError("max_replica_lag_seconds must be finite and non-negative")
        if self.max_high_watermark_age < timedelta(0):
            raise ValueError("max_high_watermark_age must be non-negative")
        if (
            isinstance(self.page_size, bool)
            or not isinstance(self.page_size, int)
            or isinstance(self.max_pages_per_class, bool)
            or not isinstance(self.max_pages_per_class, int)
            or self.page_size <= 0
            or self.max_pages_per_class <= 0
        ):
            raise ValueError("page limits must be positive")
        object.__setattr__(
            self,
            "tool_versions",
            _freeze_mapping(self.tool_versions, "tool_versions"),
        )
        if not self.tool_versions:
            raise ValueError("tool_versions must not be empty")


@dataclass(frozen=True, slots=True)
class ReferenceRow:
    source_system: SourceSystem
    authority: Authority
    wave2_authorities: tuple[Wave2Authority, ...]
    reference_class: ReferenceClass
    query_id: str
    query_hash: str
    candidate_version: str
    environment_manifest_hash: str
    target: str
    active_count: int
    oldest_reference_at: datetime | None
    newest_reference_at: datetime | None
    retention_boundary: datetime
    scan_high_watermark: HighWatermark | None
    authority_high_watermark: HighWatermark | None
    replica_lag_bound_seconds: float
    observed_replica_lag_seconds: float | None
    completeness_status: CompletenessStatus
    owner: str
    decision: Decision
    reason_codes: tuple[str, ...]
    evidence_references: tuple[str, ...]
    page_count: int
    row_hash: str = field(default="")

    def __post_init__(self) -> None:
        if not isinstance(self.source_system, SourceSystem):
            raise TypeError("source_system must be SourceSystem")
        if not isinstance(self.authority, Authority):
            raise TypeError("authority must be Authority")
        if not isinstance(self.reference_class, ReferenceClass):
            raise TypeError("reference_class must be ReferenceClass")
        if not isinstance(self.completeness_status, CompletenessStatus):
            raise TypeError("completeness_status must be CompletenessStatus")
        if not isinstance(self.decision, Decision):
            raise TypeError("decision must be Decision")
        if not isinstance(self.wave2_authorities, tuple) or any(
            not isinstance(authority, Wave2Authority)
            for authority in self.wave2_authorities
        ):
            raise TypeError("wave2_authorities must be an immutable typed tuple")
        _require_identifier(self.query_id, "query_id")
        _require_sha256(self.query_hash, "query_hash")
        _require_identifier(self.candidate_version, "candidate_version")
        _require_sha256(self.environment_manifest_hash, "environment_manifest_hash")
        _require_identifier(self.target, "target")
        if (
            isinstance(self.active_count, bool)
            or not isinstance(self.active_count, int)
            or isinstance(self.page_count, bool)
            or not isinstance(self.page_count, int)
            or self.active_count < 0
            or self.page_count < 0
        ):
            raise ValueError("counts must be non-negative")
        if self.oldest_reference_at is not None:
            _require_aware(self.oldest_reference_at, "oldest_reference_at")
        if self.newest_reference_at is not None:
            _require_aware(self.newest_reference_at, "newest_reference_at")
        if (
            self.oldest_reference_at is not None
            and self.newest_reference_at is not None
            and self.oldest_reference_at > self.newest_reference_at
        ):
            raise ValueError("oldest_reference_at must not follow newest_reference_at")
        _require_aware(self.retention_boundary, "retention_boundary")
        for name, value in (
            ("replica_lag_bound_seconds", self.replica_lag_bound_seconds),
            ("observed_replica_lag_seconds", self.observed_replica_lag_seconds),
        ):
            if value is not None and (
                isinstance(value, bool)
                or not isinstance(value, (int, float))
                or not math.isfinite(value)
                or value < 0
            ):
                raise ValueError(f"{name} must be finite and non-negative")
        _require_identifier(self.owner, "owner")
        for reason in self.reason_codes:
            _require_identifier(reason, "reason_code")
        if not self.reason_codes:
            raise ValueError("reason_codes must not be empty")
        for reference in self.evidence_references:
            _require_identifier(reference, "evidence_reference")
        if not self.evidence_references:
            raise ValueError("evidence_references must not be empty")
        if self.completeness_status is not CompletenessStatus.COMPLETE:
            if self.decision is not Decision.BLOCK_DELETE:
                raise ValueError("incomplete rows must BLOCK_DELETE")
        elif (
            self.scan_high_watermark is None
            or self.authority_high_watermark is None
            or self.scan_high_watermark != self.authority_high_watermark
            or self.observed_replica_lag_seconds is None
            or self.observed_replica_lag_seconds > self.replica_lag_bound_seconds
        ):
            raise ValueError(
                "COMPLETE requires matching authority watermarks and bounded known lag"
            )
        if self.decision is Decision.RETAIN and (
            self.completeness_status is not CompletenessStatus.COMPLETE
            or self.active_count == 0
        ):
            raise ValueError("RETAIN requires complete observed active references")
        if (
            self.completeness_status is CompletenessStatus.COMPLETE
            and self.active_count > 0
            and self.decision is not Decision.RETAIN
        ):
            raise ValueError("complete active references must be RETAIN")
        if self.decision is Decision.ELIGIBLE:
            raise ValueError("a single active-reference scan cannot be ELIGIBLE")
        expected_hash = canonical_sha256(self.to_dict(include_hash=False))
        if self.row_hash:
            _require_sha256(self.row_hash, "row_hash")
            if self.row_hash != expected_hash:
                raise ValueError("row_hash does not match canonical row content")
        else:
            object.__setattr__(self, "row_hash", expected_hash)

    def to_dict(self, *, include_hash: bool = True) -> dict[str, object]:
        value: dict[str, object] = {
            "active_count": self.active_count,
            "authority": self.authority.value,
            "authority_high_watermark": (
                self.authority_high_watermark.to_dict()
                if self.authority_high_watermark
                else None
            ),
            "candidate_version": self.candidate_version,
            "completeness_status": self.completeness_status.value,
            "decision": self.decision.value,
            "environment_manifest_hash": self.environment_manifest_hash,
            "evidence_references": list(self.evidence_references),
            "newest_reference_at": (
                format_timestamp(self.newest_reference_at)
                if self.newest_reference_at
                else None
            ),
            "observed_replica_lag_seconds": self.observed_replica_lag_seconds,
            "oldest_reference_at": (
                format_timestamp(self.oldest_reference_at)
                if self.oldest_reference_at
                else None
            ),
            "owner": self.owner,
            "page_count": self.page_count,
            "query_hash": self.query_hash,
            "query_id": self.query_id,
            "reason_codes": list(self.reason_codes),
            "reference_class": self.reference_class.value,
            "replica_lag_bound_seconds": self.replica_lag_bound_seconds,
            "retention_boundary": format_timestamp(self.retention_boundary),
            "scan_high_watermark": (
                self.scan_high_watermark.to_dict()
                if self.scan_high_watermark
                else None
            ),
            "source_system": self.source_system.value,
            "target": self.target,
            "wave2_authorities": [item.value for item in self.wave2_authorities],
        }
        if include_hash:
            value["row_hash"] = self.row_hash
        return value


@dataclass(frozen=True, slots=True)
class ActiveReferenceReport:
    candidate_sha: str
    candidate_version: str
    retirement_target: str
    environment: str
    environment_manifest_hash: str
    credentials_class: CredentialClass
    tool_versions: Mapping[str, str]
    scan_started_at: datetime
    scan_completed_at: datetime
    inventory_hash: str
    rows: tuple[ReferenceRow, ...]
    decision: Decision
    report_hash: str = field(default="")
    schema_version: str = SCHEMA_VERSION

    def __post_init__(self) -> None:
        if self.schema_version != SCHEMA_VERSION:
            raise ValueError(f"schema_version must be {SCHEMA_VERSION}")
        _require_sha1(self.candidate_sha, "candidate_sha")
        _require_identifier(self.candidate_version, "candidate_version")
        _require_identifier(self.retirement_target, "retirement_target")
        _require_identifier(self.environment, "environment")
        _require_sha256(self.environment_manifest_hash, "environment_manifest_hash")
        _require_sha256(self.inventory_hash, "inventory_hash")
        if not isinstance(self.credentials_class, CredentialClass):
            raise TypeError("credentials_class must be CredentialClass")
        if not isinstance(self.decision, Decision):
            raise TypeError("decision must be Decision")
        _require_aware(self.scan_started_at, "scan_started_at")
        _require_aware(self.scan_completed_at, "scan_completed_at")
        if self.scan_completed_at < self.scan_started_at:
            raise ValueError("scan_completed_at must not precede scan_started_at")
        if not isinstance(self.rows, tuple):
            raise TypeError("rows must be an immutable tuple")
        if (
            len(self.rows) != len(ReferenceClass)
            or {row.reference_class for row in self.rows} != set(ReferenceClass)
        ):
            raise ValueError("report must contain each of the fixed 35 classes exactly once")
        if any(
            row.candidate_version != self.candidate_version
            or row.environment_manifest_hash != self.environment_manifest_hash
            or row.target != self.retirement_target
            for row in self.rows
        ):
            raise ValueError("report rows must bind the same candidate, environment, and target")
        if self.decision is Decision.ELIGIBLE:
            if any(row.decision is not Decision.ELIGIBLE for row in self.rows):
                raise ValueError("ELIGIBLE report requires every row to be ELIGIBLE")
        elif self.decision is Decision.RETAIN:
            if any(row.decision is not Decision.RETAIN for row in self.rows):
                raise ValueError("RETAIN report cannot hide a blocking row")
        elif not any(row.decision is Decision.BLOCK_DELETE for row in self.rows):
            raise ValueError("BLOCK_DELETE report requires a blocking row")
        object.__setattr__(
            self,
            "tool_versions",
            _freeze_mapping(self.tool_versions, "tool_versions"),
        )
        if not self.tool_versions:
            raise ValueError("tool_versions must not be empty")
        expected_hash = canonical_sha256(self.to_dict(include_hash=False))
        if self.report_hash:
            _require_sha256(self.report_hash, "report_hash")
            if self.report_hash != expected_hash:
                raise ValueError("report_hash does not match canonical report content")
        else:
            object.__setattr__(self, "report_hash", expected_hash)

    def to_dict(self, *, include_hash: bool = True) -> dict[str, object]:
        value: dict[str, object] = {
            "candidate_sha": self.candidate_sha,
            "candidate_version": self.candidate_version,
            "capabilities": {
                "credential_loading": False,
                "delete": False,
                "mutation": False,
                "network": False,
                "off_activation": False,
                "retirement": False,
                "subprocess": False,
            },
            "credentials_class": self.credentials_class.value,
            "decision": self.decision.value,
            "environment": self.environment,
            "environment_manifest_hash": self.environment_manifest_hash,
            "inventory_hash": self.inventory_hash,
            "retirement_target": self.retirement_target,
            "rows": [row.to_dict() for row in self.rows],
            "scan_completed_at": format_timestamp(self.scan_completed_at),
            "scan_started_at": format_timestamp(self.scan_started_at),
            "schema_version": self.schema_version,
            "tool_versions": dict(self.tool_versions),
        }
        if include_hash:
            value["report_hash"] = self.report_hash
        return value

    def to_json_bytes(self) -> bytes:
        return canonical_json_bytes(self.to_dict())


def watermark_is_fresh(watermark: HighWatermark, context: ScanContext) -> bool:
    earliest = context.scan_started_at - context.max_high_watermark_age
    return earliest <= watermark.observed_at <= context.scan_completed_at


def watermark_matches_context(
    watermark: HighWatermark, context: ScanContext
) -> bool:
    return (
        watermark.durable
        and watermark.candidate_version == context.candidate_version
        and watermark.environment_manifest_hash == context.environment_manifest_hash
        and watermark_is_fresh(watermark, context)
    )
