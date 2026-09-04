from __future__ import annotations

import base64
import binascii
import datetime as dt
import json
import os
from pathlib import Path
from typing import Any

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import (
    decode_dss_signature,
    encode_dss_signature,
)

import common


ZERO_SHA256 = "0" * 64
RECORD_KEYS = {
    "schema_version",
    "sequence",
    "previous_record_hash",
    "recorded_at",
    "fresh_until",
    "source_kind",
    "source_identity",
    "run_context_hash",
    "candidate_sha",
    "activation_id",
    "environment_generation",
    "compose_project",
    "temporal_namespace",
    "run_nonce",
    "case_id",
    "payload_type",
    "payload_hash",
    "payload",
    "record_hash",
    "attestation",
}
ATTESTATION_KEYS = {"algorithm", "key_id", "public_key_sha256", "signature"}


def _b64url(payload: bytes) -> str:
    return base64.urlsafe_b64encode(payload).rstrip(b"=").decode("ascii")


def _b64url_decode(value: str, context: str) -> bytes:
    if not isinstance(value, str) or not value or "=" in value:
        raise common.ProductionError(f"{context} is not canonical base64url")
    try:
        decoded = base64.b64decode(
            value + "=" * (-len(value) % 4), altchars=b"-_", validate=True
        )
    except (binascii.Error, ValueError) as error:
        raise common.ProductionError(f"{context} is not canonical base64url") from error
    if _b64url(decoded) != value:
        raise common.ProductionError(f"{context} is not canonical base64url")
    return decoded


def load_private_key(path: Path) -> ec.EllipticCurvePrivateKey:
    common.assert_regular_single_link(path, "attestation private key")
    key = serialization.load_pem_private_key(path.read_bytes(), password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey) or not isinstance(
        key.curve, ec.SECP256R1
    ):
        raise common.ProductionError("attestation private key must be P-256")
    return key


def load_public_key(path: Path) -> ec.EllipticCurvePublicKey:
    common.assert_regular_single_link(path, "attestation public key")
    key = serialization.load_pem_public_key(path.read_bytes())
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(
        key.curve, ec.SECP256R1
    ):
        raise common.ProductionError("attestation public key must be P-256")
    return key


def public_key_sha256(key: ec.EllipticCurvePublicKey) -> str:
    encoded = key.public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return common.file_sha256_bytes(encoded)


def _sign(key: ec.EllipticCurvePrivateKey, payload: bytes) -> str:
    return _b64url(key.sign(payload, ec.ECDSA(hashes.SHA256())))


def _verify(key: ec.EllipticCurvePublicKey, signature: str, payload: bytes) -> None:
    try:
        key.verify(
            _b64url_decode(signature, "ECDSA signature"),
            payload,
            ec.ECDSA(hashes.SHA256()),
        )
    except InvalidSignature as error:
        raise common.ProductionError("P-256 attestation signature is invalid") from error


def attest_document(
    document: dict[str, Any],
    private_key: ec.EllipticCurvePrivateKey,
    public_key: ec.EllipticCurvePublicKey,
    *,
    key_id: str,
) -> dict[str, Any]:
    sealed = common.seal_self_hash(document)
    return {
        **sealed,
        "attestation": {
            "algorithm": "ES256",
            "key_id": key_id,
            "public_key_sha256": public_key_sha256(public_key),
            "signature": _sign(private_key, common.canonical_bytes(sealed)),
        },
    }


def verify_attested_document(
    document: dict[str, Any],
    public_key: ec.EllipticCurvePublicKey,
    *,
    expected_key_sha256: str,
    context: str,
) -> None:
    attestation = document.get("attestation")
    if not isinstance(attestation, dict) or set(attestation) != ATTESTATION_KEYS:
        raise common.ProductionError(f"{context} attestation fields drifted")
    unsigned = dict(document)
    unsigned.pop("attestation")
    common.verify_self_hash(unsigned, context)
    fingerprint = public_key_sha256(public_key)
    if (
        fingerprint != expected_key_sha256
        or attestation["algorithm"] != "ES256"
        or attestation["public_key_sha256"] != fingerprint
    ):
        raise common.ProductionError(f"{context} attestation identity is invalid")
    _verify(public_key, attestation["signature"], common.canonical_bytes(unsigned))


def sign_compact_jws(
    payload: dict[str, Any],
    private_key: ec.EllipticCurvePrivateKey,
    *,
    key_id: str,
    typ: str,
) -> str:
    protected = {"alg": "ES256", "kid": key_id, "typ": typ}
    protected_segment = _b64url(common.canonical_bytes(protected))
    payload_segment = _b64url(common.canonical_bytes(payload))
    signing_input = f"{protected_segment}.{payload_segment}".encode("ascii")
    der = private_key.sign(signing_input, ec.ECDSA(hashes.SHA256()))
    r, s = decode_dss_signature(der)
    raw = r.to_bytes(32, "big") + s.to_bytes(32, "big")
    return f"{protected_segment}.{payload_segment}.{_b64url(raw)}"


def verify_compact_jws(
    compact: str,
    public_keys: dict[str, ec.EllipticCurvePublicKey],
    *,
    expected_typ: str,
    expected_issuer: str,
    expected_audience: str,
    now: dt.datetime | None = None,
) -> tuple[dict[str, Any], str]:
    if not isinstance(compact, str) or len(compact) > 1024 * 1024:
        raise common.ProductionError("compact JWS is missing or too large")
    parts = compact.split(".")
    if len(parts) != 3:
        raise common.ProductionError("compact JWS must have three segments")
    try:
        protected = json.loads(_b64url_decode(parts[0], "JWS protected header"))
        payload = json.loads(_b64url_decode(parts[1], "JWS payload"))
    except json.JSONDecodeError as error:
        raise common.ProductionError("compact JWS JSON is invalid") from error
    if not isinstance(protected, dict) or set(protected) != {"alg", "kid", "typ"}:
        raise common.ProductionError("JWS protected header fields drifted")
    if protected["alg"] != "ES256" or protected["typ"] != expected_typ:
        raise common.ProductionError("JWS algorithm or type is invalid")
    key_id = protected["kid"]
    key = public_keys.get(key_id)
    if key is None:
        raise common.ProductionError("JWS signing key is not trusted")
    raw = _b64url_decode(parts[2], "JWS signature")
    if len(raw) != 64:
        raise common.ProductionError("ES256 JWS signature must be 64 bytes")
    der = encode_dss_signature(
        int.from_bytes(raw[:32], "big"), int.from_bytes(raw[32:], "big")
    )
    try:
        key.verify(
            der, f"{parts[0]}.{parts[1]}".encode("ascii"), ec.ECDSA(hashes.SHA256())
        )
    except InvalidSignature as error:
        raise common.ProductionError("Java evidence JWS signature is invalid") from error
    if not isinstance(payload, dict):
        raise common.ProductionError("JWS payload must be an object")
    if payload.get("iss") != expected_issuer or payload.get("aud") != expected_audience:
        raise common.ProductionError("JWS issuer or audience is invalid")
    observed = now or common.utc_now()
    issued = payload.get("iat")
    expires = payload.get("exp")
    if type(issued) is not int or type(expires) is not int:
        raise common.ProductionError("JWS iat and exp must be integer epoch seconds")
    if expires <= issued or expires - issued > 300:
        raise common.ProductionError("JWS freshness window exceeds five minutes")
    epoch = int(observed.timestamp())
    if issued > epoch + 5 or expires < epoch:
        raise common.ProductionError("JWS is future-dated or expired")
    return payload, key_id


def _ledger_lines(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    common.assert_regular_single_link(path, "evidence ledger")
    records: list[dict[str, Any]] = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        try:
            value = json.loads(line)
        except json.JSONDecodeError as error:
            raise common.ProductionError(
                f"evidence ledger line {number} is invalid"
            ) from error
        if not isinstance(value, dict):
            raise common.ProductionError(
                f"evidence ledger line {number} is not an object"
            )
        records.append(value)
    return records


def verify_ledger(
    path: Path,
    public_key: ec.EllipticCurvePublicKey,
    *,
    expected_public_key_sha256: str,
    expected_context: dict[str, Any],
    require_fresh_last: bool = False,
) -> list[dict[str, Any]]:
    records = _ledger_lines(path)
    previous = ZERO_SHA256
    prior_time: dt.datetime | None = None
    fingerprint = public_key_sha256(public_key)
    if fingerprint != expected_public_key_sha256:
        raise common.ProductionError(
            "ledger public key fingerprint does not match host lock"
        )
    for index, record in enumerate(records, 1):
        if (
            set(record) != RECORD_KEYS
            or record.get("schema_version") != "production-runtime-evidence-ledger-record.v1"
        ):
            raise common.ProductionError("evidence ledger record fields drifted")
        if record["sequence"] != index or record["previous_record_hash"] != previous:
            raise common.ProductionError(
                "evidence ledger sequence or previous hash is broken"
            )
        for key, value in expected_context.items():
            if record.get(key) != value:
                raise common.ProductionError(
                    f"evidence ledger context binding drifted: {key}"
                )
        if record["payload_hash"] != common.canonical_sha256(record["payload"]):
            raise common.ProductionError("evidence ledger payload hash is invalid")
        unsigned = dict(record)
        attestation = unsigned.pop("attestation")
        claimed_hash = unsigned.pop("record_hash")
        expected_hash = common.canonical_sha256(unsigned)
        if claimed_hash != expected_hash:
            raise common.ProductionError("evidence ledger record self-hash is invalid")
        if not isinstance(attestation, dict) or set(attestation) != ATTESTATION_KEYS:
            raise common.ProductionError("evidence ledger attestation fields drifted")
        if (
            attestation["algorithm"] != "ES256"
            or attestation["public_key_sha256"] != fingerprint
            or not isinstance(attestation["key_id"], str)
        ):
            raise common.ProductionError(
                "evidence ledger attestation identity is invalid"
            )
        _verify(
            public_key,
            attestation["signature"],
            common.canonical_bytes({**unsigned, "record_hash": claimed_hash}),
        )
        recorded = common.parse_timestamp(record["recorded_at"], "ledger recorded_at")
        fresh_until = common.parse_timestamp(
            record["fresh_until"], "ledger fresh_until"
        )
        if fresh_until <= recorded or fresh_until - recorded > dt.timedelta(minutes=10):
            raise common.ProductionError("ledger record freshness window is invalid")
        if prior_time is not None and recorded < prior_time:
            raise common.ProductionError("ledger timestamps are not monotonic")
        prior_time = recorded
        previous = claimed_hash
    if require_fresh_last and (
        not records
        or common.parse_timestamp(records[-1]["fresh_until"], "last ledger freshness")
        < common.utc_now()
    ):
        raise common.ProductionError("last ledger attestation is missing or stale")
    return records


def append_record(
    path: Path,
    private_key: ec.EllipticCurvePrivateKey,
    public_key: ec.EllipticCurvePublicKey,
    *,
    key_id: str,
    context: dict[str, Any],
    source_kind: str,
    source_identity: str,
    case_id: str | None,
    payload_type: str,
    payload: dict[str, Any],
) -> dict[str, Any]:
    if source_kind not in {"HARNESS_DIRECT", "JAVA_SIGNED"}:
        raise common.ProductionError("ledger source kind is not trusted")
    append_lock = path.with_suffix(path.suffix + ".append.lock")
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0)
    try:
        descriptor = os.open(append_lock, flags, 0o600)
    except FileExistsError as error:
        raise common.ProductionError(
            "another process owns the evidence ledger append lock"
        ) from error
    os.close(descriptor)
    try:
        fingerprint = public_key_sha256(public_key)
        existing = verify_ledger(
            path,
            public_key,
            expected_public_key_sha256=fingerprint,
            expected_context=context,
        )
        now = common.utc_now()
        unsigned = {
            "schema_version": "production-runtime-evidence-ledger-record.v1",
            "sequence": len(existing) + 1,
            "previous_record_hash": existing[-1]["record_hash"]
            if existing
            else ZERO_SHA256,
            "recorded_at": now.isoformat(timespec="milliseconds"),
            "fresh_until": (now + dt.timedelta(minutes=5)).isoformat(
                timespec="milliseconds"
            ),
            "source_kind": source_kind,
            "source_identity": source_identity,
            **context,
            "case_id": case_id,
            "payload_type": payload_type,
            "payload_hash": common.canonical_sha256(payload),
            "payload": payload,
        }
        record_hash = common.canonical_sha256(unsigned)
        signed = {**unsigned, "record_hash": record_hash}
        record = {
            **signed,
            "attestation": {
                "algorithm": "ES256",
                "key_id": key_id,
                "public_key_sha256": fingerprint,
                "signature": _sign(private_key, common.canonical_bytes(signed)),
            },
        }
        path.parent.mkdir(parents=True, exist_ok=True)
        line = common.canonical_bytes(record) + b"\n"
        output_flags = (
            os.O_WRONLY | os.O_APPEND | os.O_CREAT | getattr(os, "O_BINARY", 0)
        )
        output = os.open(path, output_flags, 0o600)
        try:
            os.write(output, line)
            os.fsync(output)
        finally:
            os.close(output)
        return record
    finally:
        append_lock.unlink(missing_ok=True)
