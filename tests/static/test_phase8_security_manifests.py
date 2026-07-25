from __future__ import annotations

from pathlib import Path
from typing import Any, Iterator

import yaml


ROOT = Path(__file__).resolve().parents[2]
PHASE8 = ROOT / "deploy" / "production" / "phase8"
SECURITY = PHASE8 / "security"
RUNBOOK = (
    ROOT
    / "docs"
    / "runbooks"
    / "temporal-first"
    / "phase-8-security-hardening.md"
)


def _docs(path: Path) -> list[dict[str, Any]]:
    values = list(yaml.safe_load_all(path.read_text(encoding="utf-8")))
    assert all(isinstance(value, dict) for value in values), path
    return values


def _named(path: Path, kind: str) -> dict[str, dict[str, Any]]:
    return {
        value["metadata"]["name"]: value
        for value in _docs(path)
        if value.get("kind") == kind
    }


def _walk(value: Any) -> Iterator[tuple[str | None, Any]]:
    if isinstance(value, dict):
        for key, child in value.items():
            yield key, child
            yield from _walk(child)
    elif isinstance(value, list):
        for child in value:
            yield None, child
            yield from _walk(child)


def _annotation_yaml(path: Path, key: str) -> dict[str, Any]:
    config = _docs(path)[0]
    value = yaml.safe_load(config["metadata"]["annotations"][key])
    assert isinstance(value, dict), key
    return value


def test_service_accounts_are_distinct_and_have_no_implicit_authority() -> None:
    accounts = _named(SECURITY / "workload-identities.yaml", "ServiceAccount")
    expected = {
        "after-sale-java-api",
        "after-sale-java-control-worker",
        "after-sale-java-agent-worker",
        "after-sale-python-agent",
        "after-sale-litellm",
        "after-sale-otel-collector",
        "after-sale-pgbouncer-domain-api",
        "after-sale-pgbouncer-domain-control",
        "after-sale-pgbouncer-domain-agent",
        "after-sale-pgbouncer-graph-agent",
        "after-sale-pgbouncer-reporting-read",
        "after-sale-migration-runner",
        "after-sale-archive-writer",
        "after-sale-release-operator",
    }
    assert set(accounts) == expected
    assert all(account["automountServiceAccountToken"] is False for account in accounts.values())
    identity_ids = {
        account["metadata"]["labels"]["phase8.after-sale-flow.dev/identity-id"]
        for account in accounts.values()
    }
    assert len(identity_ids) == len(accounts)
    for name in (
        "after-sale-migration-runner",
        "after-sale-archive-writer",
        "after-sale-release-operator",
    ):
        assert accounts[name]["metadata"]["annotations"][
            "phase8.after-sale-flow.dev/implicit-production-authority"
        ] == "forbidden"

    otel = accounts["after-sale-otel-collector"]
    assert otel["metadata"]["labels"] == {
        "app.kubernetes.io/part-of": "after-sale-flow",
        "app.kubernetes.io/name": "otel-collector",
        "phase8.after-sale-flow.dev/identity-id": "otel-collector",
    }


def test_rbac_is_exact_namespaced_and_contains_no_wildcards_or_secret_reads() -> None:
    docs = _docs(SECURITY / "rbac.yaml")
    assert {doc["kind"] for doc in docs} == {"Role", "RoleBinding"}
    for key, value in _walk(docs):
        assert value != "*", (key, value)
        if isinstance(value, list):
            assert "*" not in value

    roles = {doc["metadata"]["name"]: doc for doc in docs if doc["kind"] == "Role"}
    expected_roles = {
        "phase8-capacity-policy-reader": "after-sale-phase8-capacity-policy",
        "phase8-kms-vault-reference-reader": "after-sale-phase8-kms-vault-policy",
        "phase8-object-store-policy-reader": "after-sale-phase8-object-store-policy",
    }
    assert set(roles) == set(expected_roles)
    for name, resource_name in expected_roles.items():
        assert roles[name]["rules"] == [
            {
                "apiGroups": [""],
                "resources": ["configmaps"],
                "resourceNames": [resource_name],
                "verbs": ["get"],
            }
        ]

    bindings = {
        doc["metadata"]["name"]: doc
        for doc in docs
        if doc["kind"] == "RoleBinding"
    }
    assert {
        subject["name"]
        for subject in bindings["phase8-capacity-policy-readers"]["subjects"]
    } == {
        "after-sale-java-api",
        "after-sale-java-control-worker",
        "after-sale-java-agent-worker",
        "after-sale-python-agent",
        "after-sale-litellm",
    }
    assert {
        subject["name"]
        for subject in bindings["phase8-kms-vault-reference-readers"]["subjects"]
    } == {
        "after-sale-java-api",
        "after-sale-java-control-worker",
        "after-sale-java-agent-worker",
        "after-sale-python-agent",
        "after-sale-litellm",
        "after-sale-archive-writer",
    }
    assert {
        subject["name"]
        for subject in bindings["phase8-object-store-policy-readers"]["subjects"]
    } == {
        "after-sale-java-api",
        "after-sale-java-agent-worker",
        "after-sale-python-agent",
        "after-sale-archive-writer",
    }
    bound_subjects = {
        subject["name"] for binding in bindings.values() for subject in binding["subjects"]
    }
    assert not {
        "after-sale-otel-collector",
        "after-sale-migration-runner",
        "after-sale-release-operator",
        "after-sale-pgbouncer-domain-api",
        "after-sale-pgbouncer-domain-control",
        "after-sale-pgbouncer-domain-agent",
        "after-sale-pgbouncer-graph-agent",
        "after-sale-pgbouncer-reporting-read",
    } & bound_subjects


def test_security_bundle_contains_no_secret_resource_or_secret_value_source() -> None:
    forbidden_keys = {"data", "stringData", "secretKeyRef", "secretRef"}
    for path in sorted(SECURITY.glob("*.yaml")):
        for doc in _docs(path):
            assert doc["kind"] != "Secret", path
            for key, _ in _walk(doc):
                assert key not in forbidden_keys, (path, key)


def test_network_policy_is_default_deny_with_exact_runtime_and_pool_ingress() -> None:
    policies = _named(SECURITY / "network-policies.yaml", "NetworkPolicy")
    deny = policies["default-deny-ingress-and-egress"]["spec"]
    assert deny == {"podSelector": {}, "policyTypes": ["Ingress", "Egress"]}

    exact_ingress = {
        "python-agent-ingress-from-java-agent-worker": (
            "after-sale-python-agent",
            "after-sale-java-agent-worker",
            8000,
        ),
        "litellm-ingress-from-python-agent": (
            "after-sale-litellm",
            "after-sale-python-agent",
            4000,
        ),
        "pgbouncer-domain-api-ingress": (
            "after-sale-pgbouncer-domain-api",
            "after-sale-java-api",
            6432,
        ),
        "pgbouncer-domain-control-ingress": (
            "after-sale-pgbouncer-domain-control",
            "after-sale-java-control-worker",
            6432,
        ),
        "pgbouncer-domain-agent-ingress": (
            "after-sale-pgbouncer-domain-agent",
            "after-sale-java-agent-worker",
            6432,
        ),
        "pgbouncer-graph-agent-ingress": (
            "after-sale-pgbouncer-graph-agent",
            "after-sale-python-agent",
            6432,
        ),
        "pgbouncer-reporting-read-ingress": (
            "after-sale-pgbouncer-reporting-read",
            "after-sale-java-api",
            6432,
        ),
    }
    for policy_name, (destination, source, port) in exact_ingress.items():
        spec = policies[policy_name]["spec"]
        assert spec["podSelector"] == {
            "matchLabels": {"app.kubernetes.io/name": destination}
        }
        assert spec["ingress"] == [
            {
                "from": [
                    {
                        "podSelector": {
                            "matchLabels": {"app.kubernetes.io/name": source}
                        }
                    }
                ],
                "ports": [{"protocol": "TCP", "port": port}],
            }
        ]
    assert "pgbouncer-ingress-by-isolated-client-pool" not in policies


def test_istio_v1_contract_is_strict_default_deny_and_identity_scoped() -> None:
    docs = _docs(SECURITY / "mtls-policies.yaml")
    assert all(doc["apiVersion"] == "security.istio.io/v1" for doc in docs)
    peers = [doc for doc in docs if doc["kind"] == "PeerAuthentication"]
    assert len(peers) == 1
    assert peers[0]["spec"] == {"mtls": {"mode": "STRICT"}}
    assert peers[0]["metadata"]["annotations"] == {
        "phase8.after-sale-flow.dev/policy-mode": "RENDER_ONLY",
        "phase8.after-sale-flow.dev/crd-readiness": "REQUIRED_EXTERNAL",
        "phase8.after-sale-flow.dev/dataplane-interception": "REQUIRED_EXTERNAL",
        "phase8.after-sale-flow.dev/enforcement-receipt": "REQUIRED_EXTERNAL",
    }

    auth = {
        doc["metadata"]["name"]: doc
        for doc in docs
        if doc["kind"] == "AuthorizationPolicy"
    }
    assert auth["phase8-default-deny"]["spec"] == {}
    for name, policy in auth.items():
        if name == "phase8-default-deny":
            continue
        assert policy["spec"]["action"] == "ALLOW"
        assert policy["spec"]["selector"]["matchLabels"]
        principals = policy["spec"]["rules"][0]["from"][0]["source"]["principals"]
        assert principals
        assert all(
            principal.startswith(
                "cluster.local/ns/after-sale-flow-phase8-render-only/sa/"
            )
            for principal in principals
        )

    otel = auth["otel-from-phase8-workloads"]["spec"]
    assert otel["selector"]["matchLabels"] == {
        "app.kubernetes.io/part-of": "after-sale-flow",
        "app.kubernetes.io/name": "otel-collector",
    }
    assert set(otel["rules"][0]["to"][0]["operation"]["ports"]) == {
        "4317",
        "4318",
    }


def test_kms_vault_and_private_immutable_object_contracts_resolve() -> None:
    kms_path = SECURITY / "kms-vault-policy.yaml"
    object_path = SECURITY / "object-store-policy.yaml"
    kms_config = _docs(kms_path)[0]
    object_config = _docs(object_path)[0]
    assert kms_config["immutable"] is True
    assert object_config["immutable"] is True

    authorities = _annotation_yaml(
        kms_path, "phase8.after-sale-flow.dev/authority-contract"
    )
    kms_refs = set(authorities["kms"].values()) - {"REQUIRED_EXTERNAL"}
    assert all(str(value).startswith("kms://keys.invalid/") for value in kms_refs)
    assert authorities["controls"]["reference_is_credential"] is False
    assert authorities["controls"]["render_can_close_external_gate"] is False

    storage = _annotation_yaml(
        object_path, "phase8.after-sale-flow.dev/storage-contract"
    )
    assert storage["defaults"] == {
        "acl": "private",
        "public_access": "BLOCKED",
        "versioning": "REQUIRED",
        "immutable_versions": "REQUIRED",
        "object_lock": "REQUIRED",
        "overwrite_by_logical_key": "forbidden",
        "transport_tls": "REQUIRED_EXTERNAL",
        "access_audit_log": "REQUIRED",
        "access_audit_receipt": "REQUIRED_EXTERNAL",
        "lifecycle_and_legal_hold_approval": "REQUIRED_EXTERNAL",
    }
    assert set(storage["scopes"]) == {"evidence", "graph_input", "graph_output", "audit"}
    object_kms_refs = {
        scope["kms_key_ref"] for scope in storage["scopes"].values()
    }
    assert object_kms_refs <= kms_refs
    assert storage["immutable_reference"]["required_fields"] == [
        "bucket",
        "key",
        "version_id",
        "sha256",
    ]


def test_runbook_preserves_five_runtime_blockers_and_external_enforcement_gates() -> None:
    text = RUNBOOK.read_text(encoding="utf-8")
    blockers = (
        "TEMPORAL_CLOUD_TLS_OR_MTLS_CREDENTIAL_ADAPTER_ACCEPTED",
        "TRUSTED_PROXY_OR_DIRECT_MTLS_ASGI_IDENTITY_BRIDGE_ACCEPTED",
        "REPORTING_READ_REPLICA_ROUTING_ACCEPTED",
        "OBJECT_STORE_WORKLOAD_IDENTITY_PROVIDER_CHAIN_ACCEPTED",
        "LANGFUSE_IDENTITY_PROMPT_OUTPUT_REDACTION_ACCEPTED",
    )
    external_gates = (
        "ISTIO_SECURITY_IO_V1_CRD_READINESS_ACCEPTED",
        "ISTIO_DATAPLANE_INTERCEPTION_ACCEPTED",
        "ISTIO_STRICT_MTLS_ENFORCEMENT_ACCEPTED",
        "ISTIO_AUTHORIZATION_POLICY_ENFORCEMENT_ACCEPTED",
        "I3_I4_OTEL_NAMESPACE_LABEL_SERVICE_ACCOUNT_AND_PORT_BINDING_ACCEPTED",
    )
    for marker in (*blockers, *external_gates):
        assert marker in text
    for marker in (
        "artifact_mode: RENDER_ONLY_NONDEPLOYABLE",
        "production_checkpoint: PENDING_EXTERNAL",
        "promotion_gate: PENDING",
        "production_apply: FORBIDDEN",
        "real_traffic: FORBIDDEN",
        "after-sale-otel-collector",
        "app.kubernetes.io/name` | `otel-collector",
        "4317",
        "4318",
        "must not import any I4 path",
        "cannot substitute",
    ):
        assert marker in text
