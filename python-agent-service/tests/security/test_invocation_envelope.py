from __future__ import annotations

import json
from pathlib import Path

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import ec

from app.contracts.v1.models import RoomGraphCommand
from app.security.invocation_envelope import (
    InvocationEnvelopeError,
    InvocationEnvelopeVerifier,
    ReconciliationEnvelopeVerifier,
    ResolvedVerificationKey,
    TransportIdentity,
    extract_bearer_token,
    invocation_binding_claims,
)


ROOT = Path(__file__).resolve().parents[3]
VECTOR = (
    ROOT / "contracts/agent-platform/v1/fixtures/canonical-hash/room-graph-command-self-hash.json"
)
NOW = 2_000_000_000
KID = "java-invocation-es256-1"
CERTIFICATE_SHA256 = "c" * 64


class StaticKeyResolver:
    def __init__(
        self,
        public_key: object,
        *,
        kid: str = KID,
        curve: str = "P-256",
    ) -> None:
        self.public_key = public_key
        self.kid = kid
        self.curve = curve

    def resolve(self, kid: str) -> ResolvedVerificationKey:
        if kid != self.kid:
            raise KeyError(kid)
        return ResolvedVerificationKey(
            kid=self.kid,
            public_key=self.public_key,
            curve=self.curve,  # type: ignore[arg-type]
        )


@pytest.fixture(scope="module")
def private_key() -> ec.EllipticCurvePrivateKey:
    return ec.generate_private_key(ec.SECP256R1())


@pytest.fixture()
def command() -> RoomGraphCommand:
    vector = json.loads(VECTOR.read_text(encoding="utf-8"))
    return RoomGraphCommand.model_validate(
        {**vector["input"], vector["hash_field"]: vector["sha256"]}
    )


@pytest.fixture()
def transport_identity() -> TransportIdentity:
    return TransportIdentity(
        service_id="java-api-service",
        authenticated=True,
        certificate_sha256=CERTIFICATE_SHA256,
    )


def signed_token(
    private_key: ec.EllipticCurvePrivateKey,
    command: RoomGraphCommand,
    *,
    header_overrides: dict[str, object] | None = None,
    claim_overrides: dict[str, object] | None = None,
) -> str:
    claims: dict[str, object] = {
        "iss": "java-api-service",
        "aud": "python-agent-service",
        "sub": "graph-command",
        "iat": NOW,
        "nbf": NOW,
        "exp": NOW + 60,
        "jti": "transport-nonce-001",
        **invocation_binding_claims(command),
        **(claim_overrides or {}),
    }
    headers: dict[str, object] = {
        "alg": "ES256",
        "kid": KID,
        "typ": "graph-command+jwt",
        **(header_overrides or {}),
    }
    return jwt.encode(claims, private_key, algorithm="ES256", headers=headers)


def signed_reconciliation_token(
    private_key: ec.EllipticCurvePrivateKey,
    command: RoomGraphCommand,
    *,
    kid: str = KID,
    claim_overrides: dict[str, object] | None = None,
) -> str:
    claims: dict[str, object] = {
        "iss": "java-api-service",
        "aud": "python-agent-service",
        "sub": "graph-reconcile",
        "capability": "RECONCILE_ONLY",
        "original_envelope_key_id": command.invocation_context.envelope_key_id,
        "iat": NOW,
        "nbf": NOW,
        "exp": NOW + 60,
        "jti": "reconcile-transport-nonce-001",
        **invocation_binding_claims(command),
        **(claim_overrides or {}),
    }
    return jwt.encode(
        claims,
        private_key,
        algorithm="ES256",
        headers={"alg": "ES256", "kid": kid, "typ": "graph-reconcile+jwt"},
    )


def verifier(
    private_key: ec.EllipticCurvePrivateKey,
    *,
    resolver: StaticKeyResolver | None = None,
) -> InvocationEnvelopeVerifier:
    return InvocationEnvelopeVerifier(
        key_resolver=resolver or StaticKeyResolver(private_key.public_key()),
        now=lambda: NOW,
    )


def reconciliation_verifier(
    private_key: ec.EllipticCurvePrivateKey,
    *,
    resolver: StaticKeyResolver | None = None,
) -> ReconciliationEnvelopeVerifier:
    return ReconciliationEnvelopeVerifier(
        key_resolver=resolver or StaticKeyResolver(private_key.public_key()),
        now=lambda: NOW,
    )


def test_valid_es256_envelope_binds_body_and_transport_without_reusing_command_nonce(
    private_key: ec.EllipticCurvePrivateKey,
    command: RoomGraphCommand,
    transport_identity: TransportIdentity,
) -> None:
    token = signed_token(private_key, command)

    verified = verifier(private_key).verify(
        token=token,
        command=command,
        transport_identity=transport_identity,
    )

    assert verified.key_id == KID
    assert verified.request_hash == command.request_hash
    assert verified.claims.command_nonce == command.invocation_context.envelope_nonce
    assert verified.claims.jti == "transport-nonce-001"
    assert verified.claims.jti != verified.claims.command_nonce
    assert verified.transport_certificate_sha256 == CERTIFICATE_SHA256


def test_reconciliation_uses_a_current_key_and_preserves_original_key_lineage(
    private_key: ec.EllipticCurvePrivateKey,
    command: RoomGraphCommand,
    transport_identity: TransportIdentity,
) -> None:
    current_kid = "java-invocation-es256-2"
    resolver = StaticKeyResolver(private_key.public_key(), kid=current_kid)
    token = signed_reconciliation_token(
        private_key,
        command,
        kid=current_kid,
    )

    verified = reconciliation_verifier(private_key, resolver=resolver).verify(
        token=token,
        command=command,
        transport_identity=transport_identity,
    )

    assert verified.key_id == current_kid
    assert verified.key_id != command.invocation_context.envelope_key_id
    assert verified.claims.original_envelope_key_id == command.invocation_context.envelope_key_id
    assert verified.claims.capability == "RECONCILE_ONLY"
    assert verified.request_hash == command.request_hash


def test_execution_and_reconciliation_credentials_are_not_interchangeable(
    private_key: ec.EllipticCurvePrivateKey,
    command: RoomGraphCommand,
    transport_identity: TransportIdentity,
) -> None:
    with pytest.raises(InvocationEnvelopeError) as execution_error:
        verifier(private_key).verify(
            token=signed_reconciliation_token(private_key, command),
            command=command,
            transport_identity=transport_identity,
        )
    assert execution_error.value.code == "INVOCATION_JWS_HEADER_REJECTED"

    with pytest.raises(InvocationEnvelopeError) as reconciliation_error:
        reconciliation_verifier(private_key).verify(
            token=signed_token(private_key, command),
            command=command,
            transport_identity=transport_identity,
        )
    assert reconciliation_error.value.code == "INVOCATION_JWS_HEADER_REJECTED"


@pytest.mark.parametrize(
    ("claim_overrides", "code"),
    [
        ({"capability": "EXECUTE"}, "INVOCATION_JWS_CLAIMS_REJECTED"),
        (
            {"original_envelope_key_id": "java-invocation-es256-forged"},
            "INVOCATION_ORIGINAL_ENVELOPE_KEY_ID_MISMATCH",
        ),
        ({"sub": "graph-command"}, "INVOCATION_JWS_CLAIMS_REJECTED"),
    ],
)
def test_reconciliation_purpose_capability_and_lineage_fail_closed(
    private_key: ec.EllipticCurvePrivateKey,
    command: RoomGraphCommand,
    transport_identity: TransportIdentity,
    claim_overrides: dict[str, object],
    code: str,
) -> None:
    with pytest.raises(InvocationEnvelopeError) as captured:
        reconciliation_verifier(private_key).verify(
            token=signed_reconciliation_token(
                private_key,
                command,
                claim_overrides=claim_overrides,
            ),
            command=command,
            transport_identity=transport_identity,
        )
    assert captured.value.code == code


@pytest.mark.parametrize(
    ("authorization", "code"),
    [
        (None, "INVOCATION_AUTHORIZATION_MISSING"),
        ("bearer token", "INVOCATION_AUTHORIZATION_REJECTED"),
        ("Bearer", "INVOCATION_AUTHORIZATION_REJECTED"),
        ("Bearer token extra", "INVOCATION_AUTHORIZATION_REJECTED"),
    ],
)
def test_bearer_header_is_exact(authorization: str | None, code: str) -> None:
    with pytest.raises(InvocationEnvelopeError) as captured:
        extract_bearer_token(authorization)
    assert captured.value.code == code

    assert extract_bearer_token("Bearer header.payload.signature") == "header.payload.signature"


@pytest.mark.parametrize(
    "identity",
    [
        TransportIdentity("java-api-service", False, CERTIFICATE_SHA256),
        TransportIdentity("browser", True, CERTIFICATE_SHA256),
        TransportIdentity("java-api-service", True, "not-a-fingerprint"),
    ],
)
def test_transport_identity_fails_closed_before_jws_use(
    private_key: ec.EllipticCurvePrivateKey,
    command: RoomGraphCommand,
    identity: TransportIdentity,
) -> None:
    with pytest.raises(InvocationEnvelopeError) as captured:
        verifier(private_key).verify(
            token=signed_token(private_key, command),
            command=command,
            transport_identity=identity,
        )
    assert captured.value.code.startswith("INVOCATION_MTLS_")


def test_command_self_hash_is_recomputed_instead_of_trusting_the_field(
    private_key: ec.EllipticCurvePrivateKey,
    command: RoomGraphCommand,
    transport_identity: TransportIdentity,
) -> None:
    tampered = command.model_copy(update={"request_hash": "0" * 64})

    with pytest.raises(InvocationEnvelopeError) as captured:
        verifier(private_key).verify(
            token=signed_token(private_key, command),
            command=tampered,
            transport_identity=transport_identity,
        )
    assert captured.value.code == "INVOCATION_COMMAND_SELF_HASH_MISMATCH"


@pytest.mark.parametrize(
    ("claim_overrides", "code"),
    [
        ({"tenant_surrogate": "tenant-forged"}, "INVOCATION_TENANT_SURROGATE_MISMATCH"),
        ({"case_id": "case-forged"}, "INVOCATION_CASE_ID_MISMATCH"),
        ({"room_epoch": 999}, "INVOCATION_ROOM_EPOCH_MISMATCH"),
        (
            {"thread_id": "grt.v1.11111111111111111111111111111111"},
            "INVOCATION_THREAD_ID_MISMATCH",
        ),
        ({"capabilities_hash": "1" * 64}, "INVOCATION_CAPABILITIES_HASH_MISMATCH"),
        ({"profile_bindings_hash": "2" * 64}, "INVOCATION_PROFILE_BINDINGS_HASH_MISMATCH"),
    ],
)
def test_forged_scope_and_governance_claims_fail_closed(
    private_key: ec.EllipticCurvePrivateKey,
    command: RoomGraphCommand,
    transport_identity: TransportIdentity,
    claim_overrides: dict[str, object],
    code: str,
) -> None:
    with pytest.raises(InvocationEnvelopeError) as captured:
        verifier(private_key).verify(
            token=signed_token(private_key, command, claim_overrides=claim_overrides),
            command=command,
            transport_identity=transport_identity,
        )
    assert captured.value.code == code


@pytest.mark.parametrize(
    ("claim_overrides", "code"),
    [
        ({"iat": NOW, "nbf": NOW, "exp": NOW + 61}, "INVOCATION_JWS_LIFETIME_REJECTED"),
        ({"iat": NOW + 6, "nbf": NOW + 6, "exp": NOW + 60}, "INVOCATION_JWS_NOT_YET_VALID"),
        ({"iat": NOW - 60, "nbf": NOW - 60, "exp": NOW - 6}, "INVOCATION_JWS_EXPIRED"),
        ({"iat": NOW, "nbf": NOW - 1, "exp": NOW + 60}, "INVOCATION_JWS_TIME_ORDER_REJECTED"),
    ],
)
def test_time_window_is_bounded_and_uses_five_second_skew(
    private_key: ec.EllipticCurvePrivateKey,
    command: RoomGraphCommand,
    transport_identity: TransportIdentity,
    claim_overrides: dict[str, object],
    code: str,
) -> None:
    with pytest.raises(InvocationEnvelopeError) as captured:
        verifier(private_key).verify(
            token=signed_token(private_key, command, claim_overrides=claim_overrides),
            command=command,
            transport_identity=transport_identity,
        )
    assert captured.value.code == code


def test_extra_claim_and_header_are_rejected(
    private_key: ec.EllipticCurvePrivateKey,
    command: RoomGraphCommand,
    transport_identity: TransportIdentity,
) -> None:
    extra_claim = signed_token(private_key, command, claim_overrides={"admin": True})
    with pytest.raises(InvocationEnvelopeError) as claims_error:
        verifier(private_key).verify(
            token=extra_claim,
            command=command,
            transport_identity=transport_identity,
        )
    assert claims_error.value.code == "INVOCATION_JWS_CLAIMS_REJECTED"

    extra_header = signed_token(private_key, command, header_overrides={"cty": "application/json"})
    with pytest.raises(InvocationEnvelopeError) as header_error:
        verifier(private_key).verify(
            token=extra_header,
            command=command,
            transport_identity=transport_identity,
        )
    assert header_error.value.code == "INVOCATION_JWS_HEADER_REJECTED"


def test_unknown_algorithm_and_untrusted_key_metadata_are_rejected(
    private_key: ec.EllipticCurvePrivateKey,
    command: RoomGraphCommand,
    transport_identity: TransportIdentity,
) -> None:
    hs_token = jwt.encode(
        {"iss": "java-api-service"},
        "x" * 32,
        algorithm="HS256",
        headers={"kid": KID, "typ": "graph-command+jwt"},
    )
    with pytest.raises(InvocationEnvelopeError) as algorithm_error:
        verifier(private_key).verify(
            token=hs_token,
            command=command,
            transport_identity=transport_identity,
        )
    assert algorithm_error.value.code == "INVOCATION_JWS_HEADER_REJECTED"

    wrong_curve = StaticKeyResolver(private_key.public_key(), curve="P-384")
    with pytest.raises(InvocationEnvelopeError) as key_error:
        verifier(private_key, resolver=wrong_curve).verify(
            token=signed_token(private_key, command),
            command=command,
            transport_identity=transport_identity,
        )
    assert key_error.value.code == "INVOCATION_JWS_KEY_REJECTED"


def test_signature_issuer_and_audience_are_verified(
    private_key: ec.EllipticCurvePrivateKey,
    command: RoomGraphCommand,
    transport_identity: TransportIdentity,
) -> None:
    unrelated_key = ec.generate_private_key(ec.SECP256R1())
    with pytest.raises(InvocationEnvelopeError) as signature_error:
        verifier(private_key).verify(
            token=signed_token(unrelated_key, command),
            command=command,
            transport_identity=transport_identity,
        )
    assert signature_error.value.code == "INVOCATION_JWS_CLAIMS_REJECTED"

    for override in (
        {"iss": "browser"},
        {"aud": "another-service"},
    ):
        with pytest.raises(InvocationEnvelopeError) as claims_error:
            verifier(private_key).verify(
                token=signed_token(private_key, command, claim_overrides=override),
                command=command,
                transport_identity=transport_identity,
            )
        assert claims_error.value.code == "INVOCATION_JWS_CLAIMS_REJECTED"


def test_command_key_binding_cannot_follow_an_unrelated_signing_key(
    private_key: ec.EllipticCurvePrivateKey,
    command: RoomGraphCommand,
    transport_identity: TransportIdentity,
) -> None:
    other_kid = "java-invocation-es256-2"
    token = signed_token(private_key, command, header_overrides={"kid": other_kid})
    resolver = StaticKeyResolver(private_key.public_key(), kid=other_kid)

    with pytest.raises(InvocationEnvelopeError) as captured:
        verifier(private_key, resolver=resolver).verify(
            token=token,
            command=command,
            transport_identity=transport_identity,
        )
    assert captured.value.code == "INVOCATION_COMMAND_KEY_ID_MISMATCH"
