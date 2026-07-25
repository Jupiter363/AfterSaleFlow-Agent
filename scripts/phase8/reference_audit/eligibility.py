from __future__ import annotations

import re
from dataclasses import dataclass, field, replace
from datetime import datetime, timedelta, timezone
from enum import Enum

from .adapters import ADAPTER_REGISTRY
from .model import (
    ActiveReferenceReport,
    Authority,
    CompletenessStatus,
    Decision,
    HighWatermark,
    ReferenceClass,
    ReferenceRow,
    SourceSystem,
    canonical_json_bytes,
    canonical_sha256,
    format_timestamp,
)
from .report import adapter_inventory_hash, verify_sealed_active_reference_report


ELIGIBILITY_SCHEMA_VERSION = "phase8-cleanup-eligibility.v1"
QUIESCENCE_SCHEMA_VERSION = "phase8-cleanup-quiescence-evidence.v1"
ARCHIVE_RETENTION_SCHEMA_VERSION = "phase8-archive-retention-evidence.v1"
MINIMUM_HOT_RETENTION = timedelta(hours=24)
MAXIMUM_QUIESCENCE_OBSERVATION_GAP = timedelta(minutes=5)
ARCHIVE_INVENTORY_QUERY_ID = "phase8.archive-retention.applicable-ranges.v1"
ARCHIVE_INVENTORY_LEDGER_ID = "agent_run_stream_archive_inventory"
ARCHIVE_INVENTORY_QUERY_HASH = canonical_sha256(
    {
        "authority": "DOMAIN_LEDGER",
        "candidate_bound": True,
        "environment_bound": True,
        "query_id": ARCHIVE_INVENTORY_QUERY_ID,
        "required_fields": [
            "partition_name",
            "partition_range_start",
            "partition_range_end",
            "run_id",
            "attempt_id",
            "first_sequence_no",
            "last_sequence_no",
        ],
        "terminal_only": True,
    }
)

_SHA1_RE = re.compile(r"^[0-9a-f]{40}$")
_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
_IDENTIFIER_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}$")
_NO_ACTION_CAPABILITIES = {
    "action": False,
    "authorization": False,
    "credential_loading": False,
    "database": False,
    "delete": False,
    "mutation": False,
    "network": False,
    "off_activation": False,
    "retirement": False,
    "secret_access": False,
    "subprocess": False,
    "temporal": False,
    "v047_creation": False,
}
_EVALUATOR_ELIGIBLE_TOKEN = object()


def _require_identifier(value: str, name: str) -> None:
    if not isinstance(value, str) or not _IDENTIFIER_RE.fullmatch(value):
        raise ValueError(f"{name} must be a non-secret stable identifier")


def _require_sha1(value: str, name: str) -> None:
    if not isinstance(value, str) or not _SHA1_RE.fullmatch(value):
        raise ValueError(f"{name} must be a lowercase full SHA-1")


def _require_sha256(value: str, name: str) -> None:
    if not isinstance(value, str) or not _SHA256_RE.fullmatch(value):
        raise ValueError(f"{name} must be a lowercase SHA-256")


def _require_utc(value: datetime, name: str) -> None:
    if (
        not isinstance(value, datetime)
        or value.tzinfo is None
        or value.utcoffset() != timedelta(0)
    ):
        raise ValueError(f"{name} must be an aware UTC datetime")


def _require_bool(value: bool, name: str) -> None:
    if not isinstance(value, bool):
        raise TypeError(f"{name} must be boolean")


def _require_non_negative_int(value: int, name: str) -> None:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError(f"{name} must be a non-negative integer")


def _require_positive_window(value: timedelta, name: str) -> None:
    if not isinstance(value, timedelta) or value <= timedelta(0):
        raise ValueError(f"{name} must be a positive timedelta")


def _seal_or_verify(instance: object, payload: dict[str, object], name: str) -> None:
    expected = canonical_sha256(payload)
    current = getattr(instance, name)
    if not isinstance(current, str):
        raise TypeError(f"{name} must be a string")
    if current:
        _require_sha256(current, name)
        if current != expected:
            raise ValueError(f"{name} does not match canonical evidence content")
    else:
        object.__setattr__(instance, name, expected)


def _timedelta_microseconds(value: timedelta) -> int:
    return (
        value.days * 86_400_000_000
        + value.seconds * 1_000_000
        + value.microseconds
    )


@dataclass(frozen=True, slots=True)
class LedgerQuiescenceSample:
    reference_class: ReferenceClass
    source_system: SourceSystem
    authority: Authority
    query_id: str
    query_hash: str
    ledger_id: str
    high_watermark: HighWatermark
    active_reference_count: int
    new_producer_count: int
    new_reference_count: int
    completeness_status: CompletenessStatus
    sample_hash: str = field(default="")

    def __post_init__(self) -> None:
        if not isinstance(self.reference_class, ReferenceClass):
            raise TypeError("reference_class must be ReferenceClass")
        if not isinstance(self.source_system, SourceSystem):
            raise TypeError("source_system must be SourceSystem")
        if not isinstance(self.authority, Authority):
            raise TypeError("authority must be Authority")
        if not isinstance(self.high_watermark, HighWatermark):
            raise TypeError("high_watermark must be HighWatermark")
        if not isinstance(self.completeness_status, CompletenessStatus):
            raise TypeError("completeness_status must be CompletenessStatus")
        _require_identifier(self.query_id, "query_id")
        _require_sha256(self.query_hash, "query_hash")
        _require_identifier(self.ledger_id, "ledger_id")
        for name in (
            "active_reference_count",
            "new_producer_count",
            "new_reference_count",
        ):
            _require_non_negative_int(getattr(self, name), name)
        _seal_or_verify(self, self.to_dict(include_hash=False), "sample_hash")

    def to_dict(self, *, include_hash: bool = True) -> dict[str, object]:
        value: dict[str, object] = {
            "active_reference_count": self.active_reference_count,
            "authority": self.authority.value,
            "completeness_status": self.completeness_status.value,
            "high_watermark": self.high_watermark.to_dict(),
            "ledger_id": self.ledger_id,
            "new_producer_count": self.new_producer_count,
            "new_reference_count": self.new_reference_count,
            "query_hash": self.query_hash,
            "query_id": self.query_id,
            "reference_class": self.reference_class.value,
            "source_system": self.source_system.value,
        }
        if include_hash:
            value["sample_hash"] = self.sample_hash
        return value


@dataclass(frozen=True, slots=True)
class QuiescenceCheckpoint:
    observed_at: datetime
    samples: tuple[LedgerQuiescenceSample, ...]
    previous_checkpoint_hash: str | None
    checkpoint_hash: str = field(default="")

    def __post_init__(self) -> None:
        _require_utc(self.observed_at, "observed_at")
        if not isinstance(self.samples, tuple) or any(
            not isinstance(sample, LedgerQuiescenceSample)
            for sample in self.samples
        ):
            raise TypeError("samples must be an immutable typed tuple")
        object.__setattr__(
            self,
            "samples",
            tuple(
                sorted(
                    self.samples,
                    key=lambda sample: (
                        sample.reference_class.value,
                        sample.ledger_id,
                    ),
                )
            ),
        )
        if (
            len(self.samples) != len(ReferenceClass)
            or {sample.reference_class for sample in self.samples}
            != set(ReferenceClass)
        ):
            raise ValueError("checkpoint must contain each fixed class exactly once")
        if self.previous_checkpoint_hash is not None:
            _require_sha256(
                self.previous_checkpoint_hash,
                "previous_checkpoint_hash",
            )
        _seal_or_verify(
            self,
            self.to_dict(include_hash=False),
            "checkpoint_hash",
        )

    def to_dict(self, *, include_hash: bool = True) -> dict[str, object]:
        value: dict[str, object] = {
            "observed_at": format_timestamp(self.observed_at),
            "previous_checkpoint_hash": self.previous_checkpoint_hash,
            "samples": [sample.to_dict() for sample in self.samples],
        }
        if include_hash:
            value["checkpoint_hash"] = self.checkpoint_hash
        return value


@dataclass(frozen=True, slots=True)
class AuthorityWindowPolicy:
    authority: Authority
    visibility_window: timedelta
    retention_window: timedelta
    policy_hash: str = field(default="")

    def __post_init__(self) -> None:
        if not isinstance(self.authority, Authority):
            raise TypeError("authority must be Authority")
        _require_positive_window(self.visibility_window, "visibility_window")
        _require_positive_window(self.retention_window, "retention_window")
        _seal_or_verify(self, self.to_dict(include_hash=False), "policy_hash")

    def to_dict(self, *, include_hash: bool = True) -> dict[str, object]:
        value: dict[str, object] = {
            "authority": self.authority.value,
            "retention_window_microseconds": _timedelta_microseconds(
                self.retention_window
            ),
            "visibility_window_microseconds": _timedelta_microseconds(
                self.visibility_window
            ),
        }
        if include_hash:
            value["policy_hash"] = self.policy_hash
        return value


@dataclass(frozen=True, slots=True)
class CleanupWindowPolicyReceipt:
    candidate_sha: str
    candidate_version: str
    retirement_target: str
    environment: str
    environment_manifest_hash: str
    inventory_hash: str
    policy_id: str
    issued_at: datetime
    valid_from: datetime
    valid_through: datetime
    authority_windows: tuple[AuthorityWindowPolicy, ...]
    evidence_reference: str
    receipt_hash: str = field(default="")
    schema_version: str = "phase8-cleanup-window-policy.v1"
    status: str = "ACCEPTED"

    def __post_init__(self) -> None:
        if self.schema_version != "phase8-cleanup-window-policy.v1":
            raise ValueError("invalid cleanup window policy schema")
        if self.status != "ACCEPTED":
            raise ValueError("cleanup window policy status must be ACCEPTED")
        _require_sha1(self.candidate_sha, "candidate_sha")
        for name in (
            "candidate_version",
            "retirement_target",
            "environment",
            "policy_id",
            "evidence_reference",
        ):
            _require_identifier(getattr(self, name), name)
        for name in ("environment_manifest_hash", "inventory_hash"):
            _require_sha256(getattr(self, name), name)
        for name in ("issued_at", "valid_from", "valid_through"):
            _require_utc(getattr(self, name), name)
        if self.issued_at > self.valid_from or self.valid_through <= self.valid_from:
            raise ValueError("cleanup window policy validity is inconsistent")
        if not isinstance(self.authority_windows, tuple) or any(
            not isinstance(item, AuthorityWindowPolicy)
            for item in self.authority_windows
        ):
            raise TypeError("authority_windows must be an immutable typed tuple")
        object.__setattr__(
            self,
            "authority_windows",
            tuple(sorted(self.authority_windows, key=lambda item: item.authority.value)),
        )
        if (
            len(self.authority_windows) != len(Authority)
            or {item.authority for item in self.authority_windows} != set(Authority)
        ):
            raise ValueError("window policy must cover every Authority exactly once")
        _seal_or_verify(self, self.to_dict(include_hash=False), "receipt_hash")

    def to_dict(self, *, include_hash: bool = True) -> dict[str, object]:
        value: dict[str, object] = {
            "authority_windows": [item.to_dict() for item in self.authority_windows],
            "candidate_sha": self.candidate_sha,
            "candidate_version": self.candidate_version,
            "environment": self.environment,
            "environment_manifest_hash": self.environment_manifest_hash,
            "evidence_reference": self.evidence_reference,
            "inventory_hash": self.inventory_hash,
            "issued_at": format_timestamp(self.issued_at),
            "policy_id": self.policy_id,
            "retirement_target": self.retirement_target,
            "schema_version": self.schema_version,
            "status": self.status,
            "valid_from": format_timestamp(self.valid_from),
            "valid_through": format_timestamp(self.valid_through),
        }
        if include_hash:
            value["receipt_hash"] = self.receipt_hash
        return value


@dataclass(frozen=True, slots=True)
class QuiescenceEvidence:
    candidate_sha: str
    candidate_version: str
    retirement_target: str
    environment: str
    environment_manifest_hash: str
    inventory_hash: str
    first_report_hash: str
    second_report_hash: str
    window_policy: CleanupWindowPolicyReceipt
    maximum_observation_gap: timedelta
    checkpoints: tuple[QuiescenceCheckpoint, ...]
    evidence_reference: str
    evidence_hash: str = field(default="")
    schema_version: str = QUIESCENCE_SCHEMA_VERSION

    def __post_init__(self) -> None:
        if self.schema_version != QUIESCENCE_SCHEMA_VERSION:
            raise ValueError(f"schema_version must be {QUIESCENCE_SCHEMA_VERSION}")
        _require_sha1(self.candidate_sha, "candidate_sha")
        for name in ("candidate_version", "retirement_target", "environment"):
            _require_identifier(getattr(self, name), name)
        for name in (
            "environment_manifest_hash",
            "inventory_hash",
            "first_report_hash",
            "second_report_hash",
        ):
            _require_sha256(getattr(self, name), name)
        if not isinstance(self.window_policy, CleanupWindowPolicyReceipt):
            raise TypeError("window_policy must be CleanupWindowPolicyReceipt")
        _require_positive_window(
            self.maximum_observation_gap,
            "maximum_observation_gap",
        )
        if self.maximum_observation_gap > MAXIMUM_QUIESCENCE_OBSERVATION_GAP:
            raise ValueError("maximum_observation_gap exceeds the closed policy")
        if (
            not isinstance(self.checkpoints, tuple)
            or len(self.checkpoints) < 2
            or any(
                not isinstance(checkpoint, QuiescenceCheckpoint)
                for checkpoint in self.checkpoints
            )
        ):
            raise ValueError("checkpoints must contain at least two typed records")
        _require_identifier(self.evidence_reference, "evidence_reference")
        _seal_or_verify(
            self,
            self.to_dict(include_hash=False),
            "evidence_hash",
        )

    def to_dict(self, *, include_hash: bool = True) -> dict[str, object]:
        value: dict[str, object] = {
            "candidate_sha": self.candidate_sha,
            "candidate_version": self.candidate_version,
            "checkpoints": [checkpoint.to_dict() for checkpoint in self.checkpoints],
            "environment": self.environment,
            "environment_manifest_hash": self.environment_manifest_hash,
            "evidence_reference": self.evidence_reference,
            "first_report_hash": self.first_report_hash,
            "inventory_hash": self.inventory_hash,
            "maximum_observation_gap_microseconds": _timedelta_microseconds(
                self.maximum_observation_gap
            ),
            "retirement_target": self.retirement_target,
            "schema_version": self.schema_version,
            "second_report_hash": self.second_report_hash,
            "window_policy": self.window_policy.to_dict(),
        }
        if include_hash:
            value["evidence_hash"] = self.evidence_hash
        return value


@dataclass(frozen=True, slots=True)
class SequenceValidationDocument:
    object_creation_receipt_id: str
    object_creation_receipt_sha256: str
    compatibility_report_sha256: str
    source_event_count: int
    target_event_count: int
    sequence_contiguous: bool = True
    event_identity_exact: bool = True
    release_evidence_complete: bool = False
    document_hash: str = field(default="")
    schema_version: str = "agent-stream-sequence-identity-validation.v1"
    status: str = "PASS"

    def __post_init__(self) -> None:
        if self.schema_version != "agent-stream-sequence-identity-validation.v1":
            raise ValueError("invalid sequence validation schema")
        if self.status != "PASS":
            raise ValueError("sequence validation status must be PASS")
        _require_identifier(self.object_creation_receipt_id, "object_creation_receipt_id")
        for name in (
            "object_creation_receipt_sha256",
            "compatibility_report_sha256",
        ):
            _require_sha256(getattr(self, name), name)
        _require_non_negative_int(self.source_event_count, "source_event_count")
        _require_non_negative_int(self.target_event_count, "target_event_count")
        for name in (
            "sequence_contiguous",
            "event_identity_exact",
            "release_evidence_complete",
        ):
            _require_bool(getattr(self, name), name)
        _seal_or_verify(self, self.to_dict(include_hash=False), "document_hash")

    def to_dict(self, *, include_hash: bool = True) -> dict[str, object]:
        value: dict[str, object] = {
            "compatibility_report_sha256": self.compatibility_report_sha256,
            "event_identity_exact": self.event_identity_exact,
            "object_creation_receipt_id": self.object_creation_receipt_id,
            "object_creation_receipt_sha256": self.object_creation_receipt_sha256,
            "release_evidence_complete": self.release_evidence_complete,
            "schema_version": self.schema_version,
            "sequence_contiguous": self.sequence_contiguous,
            "source_event_count": self.source_event_count,
            "status": self.status,
            "target_event_count": self.target_event_count,
        }
        if include_hash:
            value["document_hash"] = self.document_hash
        return value


@dataclass(frozen=True, slots=True)
class AudienceValidationDocument:
    compatibility_report_sha256: str
    audience_parity: bool = True
    actor_id_parity: bool = True
    cursor_parity: bool = True
    release_evidence_complete: bool = False
    document_hash: str = field(default="")
    schema_version: str = "agent-stream-audience-cursor-validation.v1"
    status: str = "PASS"

    def __post_init__(self) -> None:
        if self.schema_version != "agent-stream-audience-cursor-validation.v1":
            raise ValueError("invalid audience validation schema")
        if self.status != "PASS":
            raise ValueError("audience validation status must be PASS")
        _require_sha256(self.compatibility_report_sha256, "compatibility_report_sha256")
        for name in (
            "audience_parity",
            "actor_id_parity",
            "cursor_parity",
            "release_evidence_complete",
        ):
            _require_bool(getattr(self, name), name)
        _seal_or_verify(self, self.to_dict(include_hash=False), "document_hash")

    def to_dict(self, *, include_hash: bool = True) -> dict[str, object]:
        value: dict[str, object] = {
            "actor_id_parity": self.actor_id_parity,
            "audience_parity": self.audience_parity,
            "compatibility_report_sha256": self.compatibility_report_sha256,
            "cursor_parity": self.cursor_parity,
            "release_evidence_complete": self.release_evidence_complete,
            "schema_version": self.schema_version,
            "status": self.status,
        }
        if include_hash:
            value["document_hash"] = self.document_hash
        return value


@dataclass(frozen=True, slots=True)
class ApplicableArchiveRange:
    partition_name: str
    partition_range_start: datetime
    partition_range_end: datetime
    run_id: str
    attempt_id: str
    first_sequence_no: int
    last_sequence_no: int
    range_hash: str = field(default="")

    def __post_init__(self) -> None:
        for name in ("partition_name", "run_id", "attempt_id"):
            _require_identifier(getattr(self, name), name)
        _require_utc(self.partition_range_start, "partition_range_start")
        _require_utc(self.partition_range_end, "partition_range_end")
        if self.partition_range_end <= self.partition_range_start:
            raise ValueError("partition range must be increasing")
        _require_non_negative_int(self.first_sequence_no, "first_sequence_no")
        _require_non_negative_int(self.last_sequence_no, "last_sequence_no")
        if self.last_sequence_no < self.first_sequence_no:
            raise ValueError("sequence range must be increasing")
        _seal_or_verify(self, self.to_dict(include_hash=False), "range_hash")

    def identity(self) -> tuple[object, ...]:
        return (
            self.partition_name,
            self.partition_range_start,
            self.partition_range_end,
            self.run_id,
            self.attempt_id,
            self.first_sequence_no,
            self.last_sequence_no,
        )

    def to_dict(self, *, include_hash: bool = True) -> dict[str, object]:
        value: dict[str, object] = {
            "attempt_id": self.attempt_id,
            "first_sequence_no": self.first_sequence_no,
            "last_sequence_no": self.last_sequence_no,
            "partition_name": self.partition_name,
            "partition_range_end": format_timestamp(self.partition_range_end),
            "partition_range_start": format_timestamp(self.partition_range_start),
            "run_id": self.run_id,
        }
        if include_hash:
            value["range_hash"] = self.range_hash
        return value


@dataclass(frozen=True, slots=True)
class ArchiveInventoryReceipt:
    candidate_sha: str
    candidate_version: str
    retirement_target: str
    environment: str
    environment_manifest_hash: str
    inventory_hash: str
    inventory_id: str
    high_watermark: HighWatermark
    observed_at: datetime
    applicable_ranges: tuple[ApplicableArchiveRange, ...]
    evidence_reference: str
    receipt_hash: str = field(default="")
    schema_version: str = "agent-stream-archive-range-inventory.v1"
    query_id: str = ARCHIVE_INVENTORY_QUERY_ID
    query_hash: str = ARCHIVE_INVENTORY_QUERY_HASH
    authority: Authority = Authority.DOMAIN_LEDGER
    completeness_status: CompletenessStatus = CompletenessStatus.COMPLETE

    def __post_init__(self) -> None:
        if self.schema_version != "agent-stream-archive-range-inventory.v1":
            raise ValueError("invalid archive range inventory schema")
        if (
            self.query_id != ARCHIVE_INVENTORY_QUERY_ID
            or self.query_hash != ARCHIVE_INVENTORY_QUERY_HASH
            or self.authority is not Authority.DOMAIN_LEDGER
            or self.completeness_status is not CompletenessStatus.COMPLETE
        ):
            raise ValueError("archive inventory query or authority drift")
        _require_sha1(self.candidate_sha, "candidate_sha")
        for name in (
            "candidate_version",
            "retirement_target",
            "environment",
            "inventory_id",
            "evidence_reference",
        ):
            _require_identifier(getattr(self, name), name)
        for name in ("environment_manifest_hash", "inventory_hash", "query_hash"):
            _require_sha256(getattr(self, name), name)
        if not isinstance(self.high_watermark, HighWatermark):
            raise TypeError("high_watermark must be HighWatermark")
        _require_utc(self.observed_at, "observed_at")
        if not isinstance(self.applicable_ranges, tuple) or not self.applicable_ranges:
            raise ValueError("inventory must contain applicable archive ranges")
        if any(
            not isinstance(item, ApplicableArchiveRange)
            for item in self.applicable_ranges
        ):
            raise TypeError("inventory applicable range has invalid type")
        object.__setattr__(
            self,
            "applicable_ranges",
            tuple(sorted(self.applicable_ranges, key=lambda item: item.identity())),
        )
        identities = [item.identity() for item in self.applicable_ranges]
        if len(set(identities)) != len(identities):
            raise ValueError("inventory contains duplicate applicable ranges")
        _seal_or_verify(self, self.to_dict(include_hash=False), "receipt_hash")

    def to_dict(self, *, include_hash: bool = True) -> dict[str, object]:
        ranges = [item.to_dict() for item in self.applicable_ranges]
        value: dict[str, object] = {
            "applicable_range_count": len(ranges),
            "applicable_range_inventory_hash": canonical_sha256(ranges),
            "applicable_ranges": ranges,
            "authority": self.authority.value,
            "candidate_sha": self.candidate_sha,
            "candidate_version": self.candidate_version,
            "completeness_status": self.completeness_status.value,
            "environment": self.environment,
            "environment_manifest_hash": self.environment_manifest_hash,
            "evidence_reference": self.evidence_reference,
            "high_watermark": self.high_watermark.to_dict(),
            "inventory_hash": self.inventory_hash,
            "inventory_id": self.inventory_id,
            "observed_at": format_timestamp(self.observed_at),
            "query_hash": self.query_hash,
            "query_id": self.query_id,
            "retirement_target": self.retirement_target,
            "schema_version": self.schema_version,
        }
        if include_hash:
            value["receipt_hash"] = self.receipt_hash
        return value


@dataclass(frozen=True, slots=True)
class ArchiveManifestDocument:
    manifest_id: str
    target_partition_name: str
    partition_range_start: datetime
    partition_range_end: datetime
    run_id: str
    attempt_id: str
    first_sequence_no: int
    last_sequence_no: int
    event_count: int
    canonical_events_hash: str
    object_uri: str
    object_version: str
    object_hash: str
    terminal_event_id: str
    terminal_payload_hash: str
    execution_manifest_id: str
    execution_manifest_hash: str
    object_creation_receipt_id: str
    object_creation_receipt_hash: str
    created_by: str
    manifest_hash: str = field(default="")
    schema_version: str = "agent-stream-archive-manifest.v1"
    stream_protocol: str = "agent-stream.v2"
    authority_scope: str = "DELIVERY_STORAGE_ONLY"
    formal_business_authority: bool = False

    def __post_init__(self) -> None:
        if self.schema_version != "agent-stream-archive-manifest.v1":
            raise ValueError("invalid archive manifest schema")
        if self.stream_protocol != "agent-stream.v2":
            raise ValueError("stream_protocol must be agent-stream.v2")
        if self.authority_scope != "DELIVERY_STORAGE_ONLY":
            raise ValueError("archive manifest has invalid authority scope")
        _require_bool(self.formal_business_authority, "formal_business_authority")
        for name in (
            "manifest_id",
            "target_partition_name",
            "run_id",
            "attempt_id",
            "object_version",
            "terminal_event_id",
            "execution_manifest_id",
            "object_creation_receipt_id",
            "created_by",
        ):
            _require_identifier(getattr(self, name), name)
        if not isinstance(self.object_uri, str) or not re.fullmatch(
            r"(?:s3|minio|urn):.+",
            self.object_uri,
        ):
            raise ValueError("object_uri must use an approved immutable scheme")
        for name in (
            "canonical_events_hash",
            "object_hash",
            "terminal_payload_hash",
            "execution_manifest_hash",
            "object_creation_receipt_hash",
        ):
            _require_sha256(getattr(self, name), name)
        for name in ("first_sequence_no", "last_sequence_no", "event_count"):
            _require_non_negative_int(getattr(self, name), name)
        _require_utc(self.partition_range_start, "partition_range_start")
        _require_utc(self.partition_range_end, "partition_range_end")
        if self.partition_range_end <= self.partition_range_start:
            raise ValueError("partition range must be increasing")
        _seal_or_verify(self, self.to_dict(include_hash=False), "manifest_hash")

    def to_dict(self, *, include_hash: bool = True) -> dict[str, object]:
        value: dict[str, object] = {
            "attempt_id": self.attempt_id,
            "authority_scope": self.authority_scope,
            "canonical_events_hash": self.canonical_events_hash,
            "created_by": self.created_by,
            "event_count": self.event_count,
            "execution_manifest_hash": self.execution_manifest_hash,
            "execution_manifest_id": self.execution_manifest_id,
            "first_sequence_no": self.first_sequence_no,
            "formal_business_authority": self.formal_business_authority,
            "last_sequence_no": self.last_sequence_no,
            "manifest_id": self.manifest_id,
            "object_creation_receipt_hash": self.object_creation_receipt_hash,
            "object_creation_receipt_id": self.object_creation_receipt_id,
            "object_hash": self.object_hash,
            "object_uri": self.object_uri,
            "object_version": self.object_version,
            "partition_range_end": format_timestamp(self.partition_range_end),
            "partition_range_start": format_timestamp(self.partition_range_start),
            "run_id": self.run_id,
            "schema_version": self.schema_version,
            "stream_protocol": self.stream_protocol,
            "target_partition_name": self.target_partition_name,
            "terminal_event_id": self.terminal_event_id,
            "terminal_payload_hash": self.terminal_payload_hash,
        }
        if include_hash:
            value["manifest_hash"] = self.manifest_hash
        return value


@dataclass(frozen=True, slots=True)
class ArchiveReceiptDocument:
    receipt_id: str
    manifest_id: str
    manifest_hash: str
    target_partition_name: str
    run_id: str
    attempt_id: str
    first_sequence_no: int
    last_sequence_no: int
    event_count: int
    canonical_events_hash: str
    object_version: str
    object_hash: str
    object_readback_hash: str
    sequence_validation_hash: str
    audience_validation_hash: str
    delivery_high_watermark: int
    hot_retention_started_at: datetime
    hot_retention_eligible_at: datetime
    verified_at: datetime
    verified_by: str
    receipt_hash: str = field(default="")
    schema_version: str = "agent-stream-archive-receipt.v1"
    stream_protocol: str = "agent-stream.v2"
    authority_scope: str = "DELIVERY_STORAGE_ONLY"
    formal_business_authority: bool = False
    release_evidence_complete: bool = False

    def __post_init__(self) -> None:
        if self.schema_version != "agent-stream-archive-receipt.v1":
            raise ValueError("invalid archive receipt schema")
        if self.stream_protocol != "agent-stream.v2":
            raise ValueError("stream_protocol must be agent-stream.v2")
        if self.authority_scope != "DELIVERY_STORAGE_ONLY":
            raise ValueError("archive receipt has invalid authority scope")
        for name in (
            "formal_business_authority",
            "release_evidence_complete",
        ):
            _require_bool(getattr(self, name), name)
        for name in (
            "receipt_id",
            "manifest_id",
            "target_partition_name",
            "run_id",
            "attempt_id",
            "object_version",
            "verified_by",
        ):
            _require_identifier(getattr(self, name), name)
        for name in (
            "manifest_hash",
            "canonical_events_hash",
            "object_hash",
            "object_readback_hash",
            "sequence_validation_hash",
            "audience_validation_hash",
        ):
            _require_sha256(getattr(self, name), name)
        for name in (
            "first_sequence_no",
            "last_sequence_no",
            "event_count",
            "delivery_high_watermark",
        ):
            _require_non_negative_int(getattr(self, name), name)
        for name in (
            "hot_retention_started_at",
            "hot_retention_eligible_at",
            "verified_at",
        ):
            _require_utc(getattr(self, name), name)
        _seal_or_verify(self, self.to_dict(include_hash=False), "receipt_hash")

    def to_dict(self, *, include_hash: bool = True) -> dict[str, object]:
        value: dict[str, object] = {
            "attempt_id": self.attempt_id,
            "audience_validation_hash": self.audience_validation_hash,
            "authority_scope": self.authority_scope,
            "canonical_events_hash": self.canonical_events_hash,
            "delivery_high_watermark": self.delivery_high_watermark,
            "event_count": self.event_count,
            "first_sequence_no": self.first_sequence_no,
            "formal_business_authority": self.formal_business_authority,
            "hot_retention_eligible_at": format_timestamp(self.hot_retention_eligible_at),
            "hot_retention_started_at": format_timestamp(self.hot_retention_started_at),
            "last_sequence_no": self.last_sequence_no,
            "manifest_hash": self.manifest_hash,
            "manifest_id": self.manifest_id,
            "object_hash": self.object_hash,
            "object_readback_hash": self.object_readback_hash,
            "object_version": self.object_version,
            "receipt_id": self.receipt_id,
            "release_evidence_complete": self.release_evidence_complete,
            "run_id": self.run_id,
            "schema_version": self.schema_version,
            "sequence_validation_hash": self.sequence_validation_hash,
            "stream_protocol": self.stream_protocol,
            "target_partition_name": self.target_partition_name,
            "verified_at": format_timestamp(self.verified_at),
            "verified_by": self.verified_by,
        }
        if include_hash:
            value["receipt_hash"] = self.receipt_hash
        return value


@dataclass(frozen=True, slots=True)
class RetentionBindingEvidence:
    run_id: str
    attempt_id: str
    terminal_sequence_no: int
    terminal_event_id: str
    terminal_payload_hash: str
    execution_manifest_id: str
    execution_manifest_hash: str
    finalized_at: datetime
    terminal_event_observed_at: datetime
    immutable_manifest_observed_at: datetime
    durable_delivery_high_watermark: int
    inventory_receipt_hash: str
    second_report_hash: str
    evidence_reference: str
    binding_hash: str = field(default="")

    def __post_init__(self) -> None:
        for name in (
            "run_id",
            "attempt_id",
            "terminal_event_id",
            "execution_manifest_id",
            "evidence_reference",
        ):
            _require_identifier(getattr(self, name), name)
        for name in (
            "terminal_payload_hash",
            "execution_manifest_hash",
            "inventory_receipt_hash",
            "second_report_hash",
        ):
            _require_sha256(getattr(self, name), name)
        _require_non_negative_int(self.terminal_sequence_no, "terminal_sequence_no")
        _require_non_negative_int(
            self.durable_delivery_high_watermark,
            "durable_delivery_high_watermark",
        )
        for name in (
            "finalized_at",
            "terminal_event_observed_at",
            "immutable_manifest_observed_at",
        ):
            _require_utc(getattr(self, name), name)
        _seal_or_verify(self, self.to_dict(include_hash=False), "binding_hash")

    def to_dict(self, *, include_hash: bool = True) -> dict[str, object]:
        value: dict[str, object] = {
            "attempt_id": self.attempt_id,
            "durable_delivery_high_watermark": self.durable_delivery_high_watermark,
            "evidence_reference": self.evidence_reference,
            "execution_manifest_hash": self.execution_manifest_hash,
            "execution_manifest_id": self.execution_manifest_id,
            "finalized_at": format_timestamp(self.finalized_at),
            "immutable_manifest_observed_at": format_timestamp(
                self.immutable_manifest_observed_at
            ),
            "inventory_receipt_hash": self.inventory_receipt_hash,
            "run_id": self.run_id,
            "second_report_hash": self.second_report_hash,
            "terminal_event_id": self.terminal_event_id,
            "terminal_event_observed_at": format_timestamp(
                self.terminal_event_observed_at
            ),
            "terminal_payload_hash": self.terminal_payload_hash,
            "terminal_sequence_no": self.terminal_sequence_no,
        }
        if include_hash:
            value["binding_hash"] = self.binding_hash
        return value


@dataclass(frozen=True, slots=True)
class ArchiveArtifactEvidence:
    applicable_range: ApplicableArchiveRange
    manifest: ArchiveManifestDocument
    receipt: ArchiveReceiptDocument
    sequence_validation: SequenceValidationDocument
    audience_validation: AudienceValidationDocument
    retention_binding: RetentionBindingEvidence
    receipt_status: str = "VERIFIED"
    artifact_hash: str = field(default="")

    def __post_init__(self) -> None:
        for value, expected, name in (
            (self.applicable_range, ApplicableArchiveRange, "applicable_range"),
            (self.manifest, ArchiveManifestDocument, "manifest"),
            (self.receipt, ArchiveReceiptDocument, "receipt"),
            (self.sequence_validation, SequenceValidationDocument, "sequence_validation"),
            (self.audience_validation, AudienceValidationDocument, "audience_validation"),
            (self.retention_binding, RetentionBindingEvidence, "retention_binding"),
        ):
            if not isinstance(value, expected):
                raise TypeError(f"{name} has invalid type")
        if self.receipt_status != "VERIFIED":
            raise ValueError("receipt_status must be VERIFIED")
        _seal_or_verify(self, self.to_dict(include_hash=False), "artifact_hash")

    def to_dict(self, *, include_hash: bool = True) -> dict[str, object]:
        value: dict[str, object] = {
            "applicable_range": self.applicable_range.to_dict(),
            "audience_validation": self.audience_validation.to_dict(),
            "manifest": self.manifest.to_dict(),
            "receipt": self.receipt.to_dict(),
            "receipt_status": self.receipt_status,
            "retention_binding": self.retention_binding.to_dict(),
            "sequence_validation": self.sequence_validation.to_dict(),
        }
        if include_hash:
            value["artifact_hash"] = self.artifact_hash
        return value


class ControlEvidenceKind(str, Enum):
    OLD_READER = "OLD_READER"
    RESTORE = "RESTORE"
    ROLLBACK = "ROLLBACK"


_CONTROL_RESULT_CODES = {
    ControlEvidenceKind.OLD_READER: "OLD_READERS_ENDED_STORE_READ_ONLY",
    ControlEvidenceKind.RESTORE: "RESTORE_CHECKSUM_VERIFIED",
    ControlEvidenceKind.ROLLBACK: "ROLLBACK_COMPLETED_COMPATIBLE",
}


def _expected_control_result_hash(
    kind: ControlEvidenceKind,
    semantic_result: str,
    status: str,
) -> str:
    return canonical_sha256(
        {
            "kind": kind.value,
            "semantic_result": semantic_result,
            "status": status,
        }
    )


@dataclass(frozen=True, slots=True)
class ControlEvidenceReceipt:
    kind: ControlEvidenceKind
    candidate_sha: str
    candidate_version: str
    retirement_target: str
    environment: str
    environment_manifest_hash: str
    inventory_hash: str
    verified_at: datetime
    verified_by: str
    evidence_reference: str
    result_hash: str
    semantic_result: str
    read_only_since: datetime | None
    receipt_hash: str = field(default="")
    schema_version: str = "phase8-cleanup-control-receipt.v1"
    status: str = "PASS"

    def __post_init__(self) -> None:
        if not isinstance(self.kind, ControlEvidenceKind):
            raise TypeError("kind must be ControlEvidenceKind")
        if self.schema_version != "phase8-cleanup-control-receipt.v1":
            raise ValueError("invalid cleanup control receipt schema")
        if self.status != "PASS":
            raise ValueError("cleanup control receipt status must be PASS")
        if self.semantic_result != _CONTROL_RESULT_CODES[self.kind]:
            raise ValueError("cleanup control semantic result does not match kind")
        _require_sha1(self.candidate_sha, "candidate_sha")
        for name in (
            "candidate_version",
            "retirement_target",
            "environment",
            "verified_by",
            "evidence_reference",
        ):
            _require_identifier(getattr(self, name), name)
        for name in (
            "environment_manifest_hash",
            "inventory_hash",
            "result_hash",
        ):
            _require_sha256(getattr(self, name), name)
        _require_utc(self.verified_at, "verified_at")
        if self.kind is ControlEvidenceKind.OLD_READER:
            _require_utc(self.read_only_since, "read_only_since")
            if self.read_only_since > self.verified_at:
                raise ValueError("read_only_since must not follow verification")
        elif self.read_only_since is not None:
            raise ValueError("read_only_since is only valid for OLD_READER")
        if self.result_hash != _expected_control_result_hash(
            self.kind,
            self.semantic_result,
            self.status,
        ):
            raise ValueError("control result_hash does not bind the typed PASS result")
        _seal_or_verify(self, self.to_dict(include_hash=False), "receipt_hash")

    def to_dict(self, *, include_hash: bool = True) -> dict[str, object]:
        value: dict[str, object] = {
            "candidate_sha": self.candidate_sha,
            "candidate_version": self.candidate_version,
            "environment": self.environment,
            "environment_manifest_hash": self.environment_manifest_hash,
            "evidence_reference": self.evidence_reference,
            "inventory_hash": self.inventory_hash,
            "kind": self.kind.value,
            "result_hash": self.result_hash,
            "retirement_target": self.retirement_target,
            "read_only_since": (
                format_timestamp(self.read_only_since)
                if self.read_only_since is not None
                else None
            ),
            "schema_version": self.schema_version,
            "semantic_result": self.semantic_result,
            "status": self.status,
            "verified_at": format_timestamp(self.verified_at),
            "verified_by": self.verified_by,
        }
        if include_hash:
            value["receipt_hash"] = self.receipt_hash
        return value


@dataclass(frozen=True, slots=True)
class ArchiveRetentionEvidence:
    candidate_sha: str
    candidate_version: str
    retirement_target: str
    environment: str
    environment_manifest_hash: str
    inventory_hash: str
    inventory_receipt: ArchiveInventoryReceipt
    applicable_ranges: tuple[ApplicableArchiveRange, ...]
    archives: tuple[ArchiveArtifactEvidence, ...]
    old_store_read_only_since: datetime
    old_reader_evidence: ControlEvidenceReceipt
    restore_evidence: ControlEvidenceReceipt
    rollback_evidence: ControlEvidenceReceipt
    evidence_reference: str
    evidence_hash: str = field(default="")
    schema_version: str = ARCHIVE_RETENTION_SCHEMA_VERSION

    def __post_init__(self) -> None:
        if self.schema_version != ARCHIVE_RETENTION_SCHEMA_VERSION:
            raise ValueError(
                f"schema_version must be {ARCHIVE_RETENTION_SCHEMA_VERSION}"
            )
        _require_sha1(self.candidate_sha, "candidate_sha")
        for name in ("candidate_version", "retirement_target", "environment"):
            _require_identifier(getattr(self, name), name)
        for name in ("environment_manifest_hash", "inventory_hash"):
            _require_sha256(getattr(self, name), name)
        if not isinstance(self.inventory_receipt, ArchiveInventoryReceipt):
            raise TypeError("inventory_receipt must be ArchiveInventoryReceipt")
        if not isinstance(self.applicable_ranges, tuple) or not self.applicable_ranges:
            raise ValueError("applicable_ranges must be a non-empty immutable tuple")
        if any(
            not isinstance(item, ApplicableArchiveRange)
            for item in self.applicable_ranges
        ):
            raise TypeError("applicable_ranges contains an invalid item")
        object.__setattr__(
            self,
            "applicable_ranges",
            tuple(sorted(self.applicable_ranges, key=lambda item: item.identity())),
        )
        if not isinstance(self.archives, tuple) or not self.archives:
            raise ValueError("archives must be a non-empty immutable tuple")
        if any(not isinstance(item, ArchiveArtifactEvidence) for item in self.archives):
            raise TypeError("archives contains an invalid item")
        object.__setattr__(
            self,
            "archives",
            tuple(
                sorted(
                    self.archives,
                    key=lambda item: item.applicable_range.identity(),
                )
            ),
        )
        for name in ("old_reader_evidence", "restore_evidence", "rollback_evidence"):
            if not isinstance(getattr(self, name), ControlEvidenceReceipt):
                raise TypeError(f"{name} must be ControlEvidenceReceipt")
        _require_utc(self.old_store_read_only_since, "old_store_read_only_since")
        _require_identifier(self.evidence_reference, "evidence_reference")
        _seal_or_verify(self, self.to_dict(include_hash=False), "evidence_hash")

    def to_dict(self, *, include_hash: bool = True) -> dict[str, object]:
        value: dict[str, object] = {
            "applicable_range_count": len(self.applicable_ranges),
            "applicable_range_inventory_hash": canonical_sha256(
                [item.to_dict() for item in self.applicable_ranges]
            ),
            "applicable_ranges": [item.to_dict() for item in self.applicable_ranges],
            "archives": [item.to_dict() for item in self.archives],
            "candidate_sha": self.candidate_sha,
            "candidate_version": self.candidate_version,
            "environment": self.environment,
            "environment_manifest_hash": self.environment_manifest_hash,
            "evidence_reference": self.evidence_reference,
            "inventory_hash": self.inventory_hash,
            "inventory_receipt": self.inventory_receipt.to_dict(),
            "old_reader_evidence": self.old_reader_evidence.to_dict(),
            "old_store_read_only_since": format_timestamp(
                self.old_store_read_only_since
            ),
            "restore_evidence": self.restore_evidence.to_dict(),
            "retirement_target": self.retirement_target,
            "rollback_evidence": self.rollback_evidence.to_dict(),
            "schema_version": self.schema_version,
        }
        if include_hash:
            value["evidence_hash"] = self.evidence_hash
        return value


@dataclass(frozen=True, slots=True, init=False)
class CleanupEligibilityDecision:
    decision: Decision
    evaluated_at: datetime
    candidate_sha: str | None
    first_report_hash: str | None
    second_report_hash: str | None
    reason_codes: tuple[str, ...]
    evidence_hashes: tuple[str, ...]
    decision_hash: str = field(default="")
    schema_version: str = ELIGIBILITY_SCHEMA_VERSION

    @classmethod
    def _from_evaluator(
        cls,
        *,
        token: object,
        decision: Decision,
        evaluated_at: datetime,
        candidate_sha: str | None,
        first_report_hash: str | None,
        second_report_hash: str | None,
        reason_codes: tuple[str, ...],
        evidence_hashes: tuple[str, ...],
    ) -> CleanupEligibilityDecision:
        if token is not _EVALUATOR_ELIGIBLE_TOKEN:
            raise ValueError("cleanup decisions can only be produced by the evaluator")
        instance = object.__new__(cls)
        for name, value in (
            ("decision", decision),
            ("evaluated_at", evaluated_at),
            ("candidate_sha", candidate_sha),
            ("first_report_hash", first_report_hash),
            ("second_report_hash", second_report_hash),
            ("reason_codes", reason_codes),
            ("evidence_hashes", evidence_hashes),
            ("decision_hash", ""),
            ("schema_version", ELIGIBILITY_SCHEMA_VERSION),
        ):
            object.__setattr__(instance, name, value)
        instance.__post_init__()
        return instance

    def __post_init__(self) -> None:
        if self.schema_version != ELIGIBILITY_SCHEMA_VERSION:
            raise ValueError(f"schema_version must be {ELIGIBILITY_SCHEMA_VERSION}")
        if not isinstance(self.decision, Decision):
            raise TypeError("decision must be Decision")
        _require_utc(self.evaluated_at, "evaluated_at")
        if self.candidate_sha is not None:
            _require_sha1(self.candidate_sha, "candidate_sha")
        for name in ("first_report_hash", "second_report_hash"):
            value = getattr(self, name)
            if value is not None:
                _require_sha256(value, name)
        if (
            not isinstance(self.reason_codes, tuple)
            or not self.reason_codes
            or len(set(self.reason_codes)) != len(self.reason_codes)
        ):
            raise ValueError("reason_codes must be a non-empty unique tuple")
        for reason in self.reason_codes:
            _require_identifier(reason, "reason_code")
        if (
            not isinstance(self.evidence_hashes, tuple)
            or len(set(self.evidence_hashes)) != len(self.evidence_hashes)
        ):
            raise ValueError("evidence_hashes must be a unique tuple")
        for evidence_hash in self.evidence_hashes:
            _require_sha256(evidence_hash, "evidence_hash")
        _seal_or_verify(
            self,
            self.to_dict(include_hash=False),
            "decision_hash",
        )

    @property
    def authorizes_cleanup(self) -> bool:
        return False

    @property
    def human_authorization_required(self) -> bool:
        return True

    def __str__(self) -> str:
        return self.decision.value

    def to_dict(self, *, include_hash: bool = True) -> dict[str, object]:
        value: dict[str, object] = {
            "authorizes_cleanup": False,
            "candidate_sha": self.candidate_sha,
            "capabilities": dict(_NO_ACTION_CAPABILITIES),
            "decision": self.decision.value,
            "evaluated_at": format_timestamp(self.evaluated_at),
            "evidence_hashes": list(self.evidence_hashes),
            "first_report_hash": self.first_report_hash,
            "human_authorization_required": True,
            "reason_codes": list(self.reason_codes),
            "schema_version": self.schema_version,
            "second_report_hash": self.second_report_hash,
        }
        if include_hash:
            value["decision_hash"] = self.decision_hash
        return value

    def to_json_bytes(self) -> bytes:
        return canonical_json_bytes(self.to_dict())


def _safe_sha(value: object, pattern: re.Pattern[str]) -> str | None:
    return value if isinstance(value, str) and pattern.fullmatch(value) else None


def _blocked_decision(
    *,
    evaluated_at: object,
    first_report: object,
    second_report: object,
    reason: str,
    evidence_hashes: tuple[str, ...] = (),
) -> CleanupEligibilityDecision:
    safe_time = (
        evaluated_at
        if isinstance(evaluated_at, datetime)
        and evaluated_at.tzinfo is not None
        and evaluated_at.utcoffset() == timedelta(0)
        else datetime(1970, 1, 1, tzinfo=timezone.utc)
    )
    candidate_sha = _safe_sha(getattr(first_report, "candidate_sha", None), _SHA1_RE)
    first_hash = _safe_sha(getattr(first_report, "report_hash", None), _SHA256_RE)
    second_hash = _safe_sha(getattr(second_report, "report_hash", None), _SHA256_RE)
    safe_hashes = tuple(
        value for value in evidence_hashes if _safe_sha(value, _SHA256_RE) is not None
    )
    return CleanupEligibilityDecision._from_evaluator(
        token=_EVALUATOR_ELIGIBLE_TOKEN,
        decision=Decision.BLOCK_DELETE,
        evaluated_at=safe_time,
        candidate_sha=candidate_sha,
        first_report_hash=first_hash,
        second_report_hash=second_hash,
        reason_codes=(reason,),
        evidence_hashes=tuple(dict.fromkeys(safe_hashes)),
    )


def _report_rows(report: ActiveReferenceReport) -> dict[ReferenceClass, ReferenceRow]:
    replace(report)
    verify_sealed_active_reference_report(report)
    if report.inventory_hash != adapter_inventory_hash():
        raise ValueError("report inventory is not the closed-world registry")
    if report.decision not in (Decision.RETAIN, Decision.BLOCK_DELETE):
        raise ValueError("single-scan report has an invalid decision")
    for name in ("scan_started_at", "scan_completed_at"):
        _require_utc(getattr(report, name), f"report.{name}")

    rows: dict[ReferenceClass, ReferenceRow] = {}
    retention_boundary: datetime | None = None
    for row in report.rows:
        replace(row)
        if not isinstance(row, ReferenceRow) or row.reference_class in rows:
            raise ValueError("report rows are not the exact typed inventory")
        definition = ADAPTER_REGISTRY.get(row.reference_class)
        if definition is None or (
            row.source_system is not definition.source_system
            or row.authority is not definition.authority
            or row.wave2_authorities != definition.wave2_authorities
            or row.query_id != definition.query_id
            or row.query_hash != definition.query_hash
            or row.owner != definition.owner
        ):
            raise ValueError("row authority or query identity drifted from registry")
        if (
            row.candidate_version != report.candidate_version
            or row.environment_manifest_hash != report.environment_manifest_hash
            or row.target != report.retirement_target
        ):
            raise ValueError("row context drifted from report")
        if row.completeness_status is not CompletenessStatus.COMPLETE:
            raise ValueError("incomplete active-reference row")
        if (
            row.page_count <= 0
            or row.scan_high_watermark is None
            or row.authority_high_watermark is None
            or row.scan_high_watermark != row.authority_high_watermark
            or not row.scan_high_watermark.durable
            or row.scan_high_watermark.ledger_id
            != definition.high_watermark_ledger_id
            or row.scan_high_watermark.candidate_version != report.candidate_version
            or row.scan_high_watermark.environment_manifest_hash
            != report.environment_manifest_hash
        ):
            raise ValueError("row high-watermark is incomplete or non-authoritative")
        replace(row.scan_high_watermark)
        replace(row.authority_high_watermark)
        _require_utc(row.retention_boundary, "row.retention_boundary")
        _require_utc(row.scan_high_watermark.observed_at, "watermark.observed_at")
        if not (
            report.scan_started_at
            <= row.scan_high_watermark.observed_at
            <= report.scan_completed_at
        ):
            raise ValueError("row high-watermark is outside the scan interval")
        if (
            row.observed_replica_lag_seconds is None
            or row.observed_replica_lag_seconds > row.replica_lag_bound_seconds
        ):
            raise ValueError("row replica lag is unknown or out of bounds")
        if retention_boundary is None:
            retention_boundary = row.retention_boundary
        elif row.retention_boundary != retention_boundary:
            raise ValueError("report rows have mixed retention boundaries")
        if row.active_count == 0:
            if (
                row.oldest_reference_at is not None
                or row.newest_reference_at is not None
                or row.decision is not Decision.BLOCK_DELETE
                or "SINGLE_SCAN_ZERO_NOT_ELIGIBLE" not in row.reason_codes
            ):
                raise ValueError("zero row does not retain single-scan fail closure")
        elif (
            row.oldest_reference_at is None
            or row.newest_reference_at is None
            or row.decision is not Decision.RETAIN
            or "ACTIVE_REFERENCES" not in row.reason_codes
        ):
            raise ValueError("active row is not complete trusted retention evidence")
        rows[row.reference_class] = row
    if set(rows) != set(ReferenceClass):
        raise ValueError("report does not cover the exact 35-class inventory")
    expected_decision = (
        Decision.BLOCK_DELETE
        if any(row.decision is Decision.BLOCK_DELETE for row in rows.values())
        else Decision.RETAIN
    )
    if report.decision is not expected_decision:
        raise ValueError("report decision does not match its sealed rows")
    return rows


def _same_scan_contract(
    first_report: ActiveReferenceReport,
    second_report: ActiveReferenceReport,
    first_rows: dict[ReferenceClass, ReferenceRow],
    second_rows: dict[ReferenceClass, ReferenceRow],
) -> None:
    if (
        first_report.candidate_sha != second_report.candidate_sha
        or first_report.candidate_version != second_report.candidate_version
        or first_report.retirement_target != second_report.retirement_target
        or first_report.environment != second_report.environment
        or first_report.environment_manifest_hash
        != second_report.environment_manifest_hash
        or first_report.inventory_hash != second_report.inventory_hash
        or first_report.credentials_class is not second_report.credentials_class
        or dict(first_report.tool_versions) != dict(second_report.tool_versions)
    ):
        raise ValueError("report candidate or scan contract drift")
    for reference_class in ReferenceClass:
        first = first_rows[reference_class]
        second = second_rows[reference_class]
        if (
            first.source_system is not second.source_system
            or first.authority is not second.authority
            or first.wave2_authorities != second.wave2_authorities
            or first.query_id != second.query_id
            or first.query_hash != second.query_hash
            or first.owner != second.owner
            or first.replica_lag_bound_seconds != second.replica_lag_bound_seconds
        ):
            raise ValueError("authority, query, or HWM semantics drifted between scans")
        first_hwm = first.scan_high_watermark
        second_hwm = second.scan_high_watermark
        assert first_hwm is not None and second_hwm is not None
        if (
            first_hwm.ledger_id != second_hwm.ledger_id
            or second_hwm.sequence < first_hwm.sequence
            or second_hwm.sequence != first_hwm.sequence
            or second_hwm.observed_at < first_hwm.observed_at
        ):
            raise ValueError("high-watermark regressed, advanced, or changed semantics")


def _evidence_context_matches(
    evidence: (
        QuiescenceEvidence
        | ArchiveRetentionEvidence
        | ArchiveInventoryReceipt
        | CleanupWindowPolicyReceipt
    ),
    report: ActiveReferenceReport,
) -> bool:
    return (
        evidence.candidate_sha == report.candidate_sha
        and evidence.candidate_version == report.candidate_version
        and evidence.retirement_target == report.retirement_target
        and evidence.environment == report.environment
        and evidence.environment_manifest_hash == report.environment_manifest_hash
        and evidence.inventory_hash == report.inventory_hash
    )


def _verify_evidence_seal(
    evidence: QuiescenceEvidence | ArchiveRetentionEvidence,
) -> None:
    replace(evidence)
    if evidence.evidence_hash != canonical_sha256(
        evidence.to_dict(include_hash=False)
    ):
        raise ValueError("evidence hash mismatch")


def _verify_named_seal(
    value: object,
    *,
    hash_name: str,
) -> None:
    replace(value)
    current = getattr(value, hash_name)
    expected = canonical_sha256(value.to_dict(include_hash=False))
    if current != expected:
        raise ValueError(f"{hash_name} mismatch")


def _verify_window_policy(
    policy: CleanupWindowPolicyReceipt,
    first_report: ActiveReferenceReport,
    second_report: ActiveReferenceReport,
    first_rows: dict[ReferenceClass, ReferenceRow],
    second_rows: dict[ReferenceClass, ReferenceRow],
    visibility_window: timedelta,
    retention_window: timedelta,
    evaluated_at: datetime,
) -> timedelta:
    _verify_named_seal(policy, hash_name="receipt_hash")
    if not _evidence_context_matches(policy, first_report):
        raise ValueError("cleanup window policy context drift")
    if (
        policy.issued_at > first_report.scan_started_at
        or policy.valid_from > first_report.scan_started_at
        or policy.valid_through < evaluated_at
    ):
        raise ValueError("cleanup window policy does not cover the evidence interval")
    policies = {item.authority: item for item in policy.authority_windows}
    if len(policies) != len(Authority) or set(policies) != set(Authority):
        raise ValueError("cleanup window policy authority inventory is incomplete")
    for item in policy.authority_windows:
        _verify_named_seal(item, hash_name="policy_hash")
    for report_rows in (first_rows, second_rows):
        for row in report_rows.values():
            if policy.receipt_hash not in row.evidence_references:
                raise ValueError("sealed report row does not bind window policy receipt")
            if (
                row.retention_boundary
                > (
                    first_report.scan_started_at
                    if report_rows is first_rows
                    else second_report.scan_started_at
                )
                - policies[row.authority].retention_window
            ):
                raise ValueError("sealed row does not cover its authority retention policy")
    required_visibility = max(
        item.visibility_window for item in policy.authority_windows
    )
    required_retention = max(
        item.retention_window for item in policy.authority_windows
    )
    if (
        visibility_window < required_visibility
        or retention_window < required_retention
    ):
        raise ValueError("caller windows cannot shrink the sealed cleanup policy")
    return max(
        visibility_window,
        retention_window,
        required_visibility,
        required_retention,
    )


def _verify_quiescence_chain(
    evidence: QuiescenceEvidence,
    first_report: ActiveReferenceReport,
    second_report: ActiveReferenceReport,
    first_rows: dict[ReferenceClass, ReferenceRow],
    second_rows: dict[ReferenceClass, ReferenceRow],
) -> None:
    if (
        evidence.first_report_hash != first_report.report_hash
        or evidence.second_report_hash != second_report.report_hash
        or not _evidence_context_matches(evidence, first_report)
    ):
        raise ValueError("quiescence context or report binding mismatch")
    checkpoints = evidence.checkpoints
    if (
        checkpoints[0].observed_at != first_report.scan_completed_at
        or checkpoints[-1].observed_at != second_report.scan_started_at
        or checkpoints[0].previous_checkpoint_hash is not None
    ):
        raise ValueError("quiescence checkpoints do not bind both scan boundaries")

    prior_checkpoint: QuiescenceCheckpoint | None = None
    prior_samples: dict[ReferenceClass, LedgerQuiescenceSample] | None = None
    for checkpoint in checkpoints:
        _verify_named_seal(checkpoint, hash_name="checkpoint_hash")
        if prior_checkpoint is not None:
            if (
                checkpoint.previous_checkpoint_hash
                != prior_checkpoint.checkpoint_hash
                or checkpoint.observed_at <= prior_checkpoint.observed_at
                or checkpoint.observed_at - prior_checkpoint.observed_at
                > evidence.maximum_observation_gap
            ):
                raise ValueError("quiescence checkpoint chain has a gap or hash break")
        samples = {sample.reference_class: sample for sample in checkpoint.samples}
        if len(samples) != len(ReferenceClass) or set(samples) != set(ReferenceClass):
            raise ValueError("quiescence checkpoint inventory is incomplete")
        for reference_class, sample in samples.items():
            _verify_named_seal(sample, hash_name="sample_hash")
            definition = ADAPTER_REGISTRY[reference_class]
            watermark = sample.high_watermark
            replace(watermark)
            if (
                sample.authority is not definition.authority
                or sample.source_system is not definition.source_system
                or sample.query_id != definition.query_id
                or sample.query_hash != definition.query_hash
                or sample.ledger_id != definition.high_watermark_ledger_id
                or watermark.ledger_id != sample.ledger_id
                or watermark.candidate_version != first_report.candidate_version
                or watermark.environment_manifest_hash
                != first_report.environment_manifest_hash
                or not watermark.durable
                or sample.completeness_status is not CompletenessStatus.COMPLETE
                or sample.active_reference_count != 0
                or sample.new_producer_count != 0
                or sample.new_reference_count != 0
            ):
                raise ValueError("quiescence ledger sample is incomplete or active")
            _require_utc(watermark.observed_at, "quiescence watermark observed_at")
            if (
                watermark.observed_at > checkpoint.observed_at
                or checkpoint.observed_at - watermark.observed_at
                > evidence.maximum_observation_gap
            ):
                raise ValueError("quiescence ledger sample is stale or future-dated")
            if prior_samples is not None:
                prior = prior_samples[reference_class].high_watermark
                if (
                    watermark.ledger_id != prior.ledger_id
                    or watermark.sequence != prior.sequence
                    or watermark.observed_at < prior.observed_at
                ):
                    raise ValueError("quiescence HWM advanced, regressed, or drifted")
        prior_checkpoint = checkpoint
        prior_samples = samples

    first_samples = {
        sample.reference_class: sample for sample in checkpoints[0].samples
    }
    second_samples = {
        sample.reference_class: sample for sample in checkpoints[-1].samples
    }
    for reference_class in ReferenceClass:
        if (
            first_samples[reference_class].high_watermark
            != first_rows[reference_class].scan_high_watermark
            or second_samples[reference_class].high_watermark
            != second_rows[reference_class].scan_high_watermark
        ):
            raise ValueError("quiescence HWM does not bind sealed scan HWM")


def _control_matches(
    receipt: ControlEvidenceReceipt,
    kind: ControlEvidenceKind,
    report: ActiveReferenceReport,
) -> bool:
    return (
        receipt.kind is kind
        and receipt.status == "PASS"
        and receipt.schema_version == "phase8-cleanup-control-receipt.v1"
        and receipt.semantic_result == _CONTROL_RESULT_CODES[kind]
        and receipt.result_hash
        == _expected_control_result_hash(
            kind,
            receipt.semantic_result,
            receipt.status,
        )
        and receipt.candidate_sha == report.candidate_sha
        and receipt.candidate_version == report.candidate_version
        and receipt.retirement_target == report.retirement_target
        and receipt.environment == report.environment
        and receipt.environment_manifest_hash == report.environment_manifest_hash
        and receipt.inventory_hash == report.inventory_hash
    )


def _verify_archive_artifact(
    artifact: ArchiveArtifactEvidence,
    current_after: datetime,
    evaluated_at: datetime,
) -> None:
    for value, hash_name in (
        (artifact.applicable_range, "range_hash"),
        (artifact.manifest, "manifest_hash"),
        (artifact.receipt, "receipt_hash"),
        (artifact.sequence_validation, "document_hash"),
        (artifact.audience_validation, "document_hash"),
        (artifact.retention_binding, "binding_hash"),
        (artifact, "artifact_hash"),
    ):
        _verify_named_seal(value, hash_name=hash_name)

    archive_range = artifact.applicable_range
    manifest = artifact.manifest
    receipt = artifact.receipt
    sequence = artifact.sequence_validation
    audience = artifact.audience_validation
    binding = artifact.retention_binding
    if archive_range.identity() != (
        manifest.target_partition_name,
        manifest.partition_range_start,
        manifest.partition_range_end,
        manifest.run_id,
        manifest.attempt_id,
        manifest.first_sequence_no,
        manifest.last_sequence_no,
    ):
        raise ValueError("manifest does not bind the applicable archive range")
    if (
        receipt.manifest_id != manifest.manifest_id
        or receipt.manifest_hash != manifest.manifest_hash
        or receipt.target_partition_name != manifest.target_partition_name
        or receipt.run_id != manifest.run_id
        or receipt.attempt_id != manifest.attempt_id
        or receipt.first_sequence_no != manifest.first_sequence_no
        or receipt.last_sequence_no != manifest.last_sequence_no
        or receipt.event_count != manifest.event_count
        or receipt.canonical_events_hash != manifest.canonical_events_hash
        or receipt.object_version != manifest.object_version
        or receipt.object_hash != manifest.object_hash
        or receipt.object_readback_hash != manifest.object_hash
    ):
        raise ValueError("archive receipt does not bind exact manifest/object content")
    if (
        receipt.sequence_validation_hash != sequence.document_hash
        or receipt.audience_validation_hash != audience.document_hash
        or sequence.compatibility_report_sha256
        != audience.compatibility_report_sha256
        or sequence.object_creation_receipt_id
        != manifest.object_creation_receipt_id
        or sequence.object_creation_receipt_sha256
        != manifest.object_creation_receipt_hash
        or not sequence.sequence_contiguous
        or not sequence.event_identity_exact
        or sequence.source_event_count != receipt.event_count
        or sequence.target_event_count != receipt.event_count
        or not audience.audience_parity
        or not audience.actor_id_parity
        or not audience.cursor_parity
        or sequence.release_evidence_complete
        or audience.release_evidence_complete
    ):
        raise ValueError("archive parity documents are incomplete or mismatched")
    if (
        receipt.first_sequence_no != 0
        or receipt.last_sequence_no != receipt.event_count - 1
        or receipt.delivery_high_watermark != receipt.last_sequence_no
        or receipt.hot_retention_started_at != binding.finalized_at
        or receipt.hot_retention_eligible_at
        < receipt.hot_retention_started_at + MINIMUM_HOT_RETENTION
        or receipt.verified_at < receipt.hot_retention_started_at
        or manifest.run_id != binding.run_id
        or manifest.attempt_id != binding.attempt_id
        or manifest.last_sequence_no != binding.terminal_sequence_no
        or manifest.terminal_event_id != binding.terminal_event_id
        or manifest.terminal_payload_hash != binding.terminal_payload_hash
        or manifest.execution_manifest_id != binding.execution_manifest_id
        or manifest.execution_manifest_hash != binding.execution_manifest_hash
        or binding.durable_delivery_high_watermark
        < receipt.delivery_high_watermark
        or binding.terminal_event_observed_at < binding.finalized_at
        or binding.immutable_manifest_observed_at < binding.finalized_at
        or receipt.verified_at < binding.terminal_event_observed_at
        or receipt.verified_at < binding.immutable_manifest_observed_at
        or manifest.formal_business_authority
        or receipt.formal_business_authority
        or receipt.release_evidence_complete
    ):
        raise ValueError("archive terminal, HWM, retention, or authority binding failed")
    if any(
        timestamp < current_after or timestamp > evaluated_at
        for timestamp in (
            receipt.verified_at,
            binding.terminal_event_observed_at,
            binding.immutable_manifest_observed_at,
        )
    ):
        raise ValueError("archive revalidation is stale, future-dated, or incomplete")
    if (
        manifest.partition_range_end > evaluated_at
        or binding.finalized_at > evaluated_at
        or receipt.hot_retention_eligible_at > evaluated_at
    ):
        raise ValueError("archive source or retention timestamp is future-dated")


def _verify_archive_coverage(
    evidence: ArchiveRetentionEvidence,
    report: ActiveReferenceReport,
    second_report: ActiveReferenceReport,
    second_rows: dict[ReferenceClass, ReferenceRow],
    retention_window: timedelta,
    evaluated_at: datetime,
) -> None:
    if not _evidence_context_matches(evidence, report):
        raise ValueError("archive retention context drift")
    inventory = evidence.inventory_receipt
    _verify_named_seal(inventory, hash_name="receipt_hash")
    if not _evidence_context_matches(inventory, report):
        raise ValueError("authoritative archive inventory context drift")
    replace(inventory.high_watermark)
    if (
        inventory.high_watermark.ledger_id != ARCHIVE_INVENTORY_LEDGER_ID
        or inventory.high_watermark.candidate_version != report.candidate_version
        or inventory.high_watermark.environment_manifest_hash
        != report.environment_manifest_hash
        or not inventory.high_watermark.durable
        or inventory.high_watermark.observed_at != inventory.observed_at
        or inventory.observed_at < second_report.scan_started_at
        or inventory.observed_at > second_report.scan_completed_at
    ):
        raise ValueError("archive inventory HWM is stale, non-durable, or drifting")
    for reference_class in (
        ReferenceClass.OBJECT_STORE_MANIFEST,
        ReferenceClass.AGENT_STREAM_V1_TELEMETRY,
    ):
        if inventory.receipt_hash not in second_rows[reference_class].evidence_references:
            raise ValueError("sealed second scan does not bind archive inventory receipt")
    if (
        tuple(item.identity() for item in evidence.applicable_ranges)
        != tuple(item.identity() for item in inventory.applicable_ranges)
    ):
        raise ValueError("caller archive range list differs from authoritative inventory")

    for value, hash_name in (
        (evidence.old_reader_evidence, "receipt_hash"),
        (evidence.restore_evidence, "receipt_hash"),
        (evidence.rollback_evidence, "receipt_hash"),
    ):
        _verify_named_seal(value, hash_name=hash_name)
    if (
        not _control_matches(
            evidence.old_reader_evidence,
            ControlEvidenceKind.OLD_READER,
            report,
        )
        or not _control_matches(
            evidence.restore_evidence,
            ControlEvidenceKind.RESTORE,
            report,
        )
        or not _control_matches(
            evidence.rollback_evidence,
            ControlEvidenceKind.ROLLBACK,
            report,
        )
    ):
        raise ValueError("old-reader, restore, or rollback evidence context drift")
    controls = (
        evidence.old_reader_evidence,
        evidence.restore_evidence,
        evidence.rollback_evidence,
    )
    if any(
        item.verified_at < second_report.scan_completed_at
        or item.verified_at > evaluated_at
        for item in controls
    ):
        raise ValueError("control evidence is stale or future-dated")
    if (
        evaluated_at - evidence.old_store_read_only_since < retention_window
        or evidence.old_reader_evidence.read_only_since
        != evidence.old_store_read_only_since
        or evidence.old_reader_evidence.verified_at
        < evidence.old_store_read_only_since + retention_window
    ):
        raise ValueError("old storage read-only window is incomplete")

    applicable = [item.identity() for item in evidence.applicable_ranges]
    archived = [item.applicable_range.identity() for item in evidence.archives]
    if (
        len(set(applicable)) != len(applicable)
        or len(set(archived)) != len(archived)
        or set(applicable) != set(archived)
    ):
        raise ValueError("archive receipts do not cover every applicable range exactly once")
    for item in evidence.applicable_ranges:
        _verify_named_seal(item, hash_name="range_hash")
    for artifact in evidence.archives:
        if (
            artifact.retention_binding.inventory_receipt_hash
            != inventory.receipt_hash
            or artifact.retention_binding.second_report_hash
            != second_report.report_hash
        ):
            raise ValueError("current retention revalidation context drift")
        _verify_archive_artifact(
            artifact,
            second_report.scan_completed_at,
            evaluated_at,
        )


def evaluate_cleanup_eligibility(
    first_report: ActiveReferenceReport,
    second_report: ActiveReferenceReport,
    *,
    visibility_window: timedelta,
    retention_window: timedelta,
    quiescence: QuiescenceEvidence,
    archive_retention: ArchiveRetentionEvidence,
    evaluated_at: datetime,
) -> CleanupEligibilityDecision:
    """Evaluate evidence only; ELIGIBLE is input to separate human authorization."""

    try:
        _require_utc(evaluated_at, "evaluated_at")
        _require_positive_window(visibility_window, "visibility_window")
        _require_positive_window(retention_window, "retention_window")
        if not isinstance(quiescence, QuiescenceEvidence):
            raise TypeError("quiescence must be QuiescenceEvidence")
        if not isinstance(archive_retention, ArchiveRetentionEvidence):
            raise TypeError("archive_retention must be ArchiveRetentionEvidence")
        _verify_evidence_seal(quiescence)
        _verify_evidence_seal(archive_retention)
        first_rows = _report_rows(first_report)
        second_rows = _report_rows(second_report)
        _same_scan_contract(first_report, second_report, first_rows, second_rows)
    except Exception:
        return _blocked_decision(
            evaluated_at=evaluated_at,
            first_report=first_report,
            second_report=second_report,
            reason="INVALID_OR_UNSEALED_EVIDENCE",
        )

    report_hashes = (first_report.report_hash, second_report.report_hash)
    evidence_hashes = report_hashes + (
        quiescence.evidence_hash,
        archive_retention.evidence_hash,
    )
    all_rows = (*first_rows.values(), *second_rows.values())
    if any(row.active_count > 0 for row in all_rows):
        return CleanupEligibilityDecision._from_evaluator(
            token=_EVALUATOR_ELIGIBLE_TOKEN,
            decision=Decision.RETAIN,
            evaluated_at=evaluated_at,
            candidate_sha=first_report.candidate_sha,
            first_report_hash=first_report.report_hash,
            second_report_hash=second_report.report_hash,
            reason_codes=("COMPLETE_ACTIVE_REFERENCES",),
            evidence_hashes=report_hashes,
        )

    try:
        full_window = _verify_window_policy(
            quiescence.window_policy,
            first_report,
            second_report,
            first_rows,
            second_rows,
            visibility_window,
            retention_window,
            evaluated_at,
        )
        if second_report.scan_started_at < first_report.scan_completed_at + full_window:
            raise ValueError("two zero scans do not span the full window")
        if (
            evaluated_at < second_report.scan_completed_at
            or evaluated_at < quiescence.checkpoints[-1].observed_at
        ):
            raise ValueError("evaluation precedes completed scan or quiescence evidence")
        for report, rows in (
            (first_report, first_rows),
            (second_report, second_rows),
        ):
            if any(
                row.retention_boundary > report.scan_started_at - retention_window
                for row in rows.values()
            ):
                raise ValueError("scan retention boundary is shorter than requested")
        if (
            quiescence.checkpoints[-1].observed_at
            - quiescence.checkpoints[0].observed_at
            < full_window
        ):
            raise ValueError("quiescence chain is shorter than the full window")
        _verify_quiescence_chain(
            quiescence,
            first_report,
            second_report,
            first_rows,
            second_rows,
        )
        _verify_archive_coverage(
            archive_retention,
            first_report,
            second_report,
            second_rows,
            retention_window,
            evaluated_at,
        )
    except Exception:
        return _blocked_decision(
            evaluated_at=evaluated_at,
            first_report=first_report,
            second_report=second_report,
            reason="ELIGIBILITY_PREREQUISITE_NOT_MET",
            evidence_hashes=evidence_hashes,
        )

    return CleanupEligibilityDecision._from_evaluator(
        token=_EVALUATOR_ELIGIBLE_TOKEN,
        decision=Decision.ELIGIBLE,
        evaluated_at=evaluated_at,
        candidate_sha=first_report.candidate_sha,
        first_report_hash=first_report.report_hash,
        second_report_hash=second_report.report_hash,
        reason_codes=("HUMAN_CLEANUP_AUTHORIZATION_INPUT_ONLY",),
        evidence_hashes=evidence_hashes,
    )


__all__ = [
    "ARCHIVE_RETENTION_SCHEMA_VERSION",
    "ARCHIVE_INVENTORY_LEDGER_ID",
    "ARCHIVE_INVENTORY_QUERY_HASH",
    "ARCHIVE_INVENTORY_QUERY_ID",
    "ELIGIBILITY_SCHEMA_VERSION",
    "MAXIMUM_QUIESCENCE_OBSERVATION_GAP",
    "MINIMUM_HOT_RETENTION",
    "QUIESCENCE_SCHEMA_VERSION",
    "ApplicableArchiveRange",
    "ArchiveArtifactEvidence",
    "ArchiveInventoryReceipt",
    "ArchiveManifestDocument",
    "ArchiveReceiptDocument",
    "ArchiveRetentionEvidence",
    "AuthorityWindowPolicy",
    "AudienceValidationDocument",
    "CleanupWindowPolicyReceipt",
    "CleanupEligibilityDecision",
    "ControlEvidenceKind",
    "ControlEvidenceReceipt",
    "LedgerQuiescenceSample",
    "QuiescenceEvidence",
    "QuiescenceCheckpoint",
    "RetentionBindingEvidence",
    "SequenceValidationDocument",
    "evaluate_cleanup_eligibility",
]
