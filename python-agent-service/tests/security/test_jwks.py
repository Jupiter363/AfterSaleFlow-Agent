from __future__ import annotations

from copy import deepcopy

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import ec

from app.security.invocation_envelope import InvocationEnvelopeError
from app.security.jwks import JwksVerificationKeyResolver


def public_jwk(kid: str) -> dict[str, object]:
    key = ec.generate_private_key(ec.SECP256R1())
    value = jwt.algorithms.ECAlgorithm.to_jwk(key.public_key(), as_dict=True)
    return {**value, "kid": kid, "use": "sig", "alg": "ES256"}


def test_jwks_install_is_atomic_sorted_and_resolvable() -> None:
    resolver = JwksVerificationKeyResolver()

    snapshot = resolver.install({"keys": [public_jwk("key-b"), public_jwk("key-a")]})

    assert snapshot.generation == 1
    assert snapshot.key_ids == ("key-a", "key-b")
    assert resolver.resolve("key-a").curve == "P-256"
    assert resolver.snapshot() == snapshot


def test_rotation_retains_referenced_previous_key_without_accepting_unknown_key() -> None:
    resolver = JwksVerificationKeyResolver()
    resolver.install({"keys": [public_jwk("key-old")]})

    snapshot = resolver.install(
        {"keys": [public_jwk("key-current")]},
        retain_key_ids={"key-old"},
    )

    assert snapshot.key_ids == ("key-current", "key-old")
    assert resolver.resolve("key-old").kid == "key-old"

    with pytest.raises(InvocationEnvelopeError) as captured:
        resolver.install(
            {"keys": [public_jwk("key-next")]},
            retain_key_ids={"never-loaded"},
        )
    assert captured.value.code == "INVOCATION_JWKS_RETAINED_KEY_MISSING"
    assert resolver.snapshot() == snapshot


@pytest.mark.parametrize(
    "mutator",
    [
        lambda key: key.update(crv="P-384"),
        lambda key: key.update(use="enc"),
        lambda key: key.update(alg="ES384"),
        lambda key: key.update(kty="RSA"),
        lambda key: key.update(d="private-material"),
        lambda key: key.pop("kid"),
    ],
)
def test_non_es256_public_signing_keys_are_rejected(mutator) -> None:
    key = public_jwk("key-1")
    mutator(key)

    with pytest.raises(InvocationEnvelopeError) as captured:
        JwksVerificationKeyResolver().install({"keys": [key]})
    assert captured.value.code == "INVOCATION_JWKS_KEY_REJECTED"


def test_duplicate_key_ids_and_private_or_extra_document_fields_fail_closed() -> None:
    key = public_jwk("key-1")
    with pytest.raises(InvocationEnvelopeError) as duplicate:
        JwksVerificationKeyResolver().install({"keys": [key, deepcopy(key)]})
    assert duplicate.value.code == "INVOCATION_JWKS_DUPLICATE_KEY"

    with pytest.raises(InvocationEnvelopeError) as document:
        JwksVerificationKeyResolver().install({"keys": [key], "issuer": "untrusted"})
    assert document.value.code == "INVOCATION_JWKS_DOCUMENT_REJECTED"


def test_unknown_key_id_fails_closed() -> None:
    resolver = JwksVerificationKeyResolver()
    resolver.install({"keys": [public_jwk("key-1")]})

    with pytest.raises(InvocationEnvelopeError) as captured:
        resolver.resolve("key-unknown")
    assert captured.value.code == "INVOCATION_JWS_KEY_UNAVAILABLE"
