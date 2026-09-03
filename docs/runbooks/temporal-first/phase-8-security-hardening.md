# Phase 8 Security Hardening

## Status And Authority

```text
artifact_mode: RENDER_ONLY_NONDEPLOYABLE
engineering_checkpoint: NOT_CLAIMED_BY_THIS_ARTIFACT
production_checkpoint: PENDING_EXTERNAL
promotion_gate: PENDING
production_apply: FORBIDDEN
real_traffic: FORBIDDEN
```

The manifests under `infra/kubernetes/production` declare an engineering target. They deliberately
use reserved `.invalid` image registries and external endpoints, do not contain a `Namespace` or
`Secret`, and do not authorize `kubectl apply`. A successful YAML parse or Kustomize render is not
deployment, security enforcement, three-domain placement, production capacity, or promotion
evidence.

## Identity And Transport Boundary

API, Java control worker, Java Agent worker, Python Agent, LiteLLM, OTel, and each PgBouncer pool
have separate Kubernetes service accounts. Tokens are not automatically mounted. Namespaced Roles
grant only the named ConfigMap reads; OTel receives no Kubernetes discovery permission. There are
no wildcard verbs, resources, or API groups and no permission to read Secrets.

Migration runner, archive writer, and release operator also have distinct service accounts. Their
existence grants no implicit production authority; migration and release identities have no RBAC
binding, while the archive identity can read only the KMS/Vault and object-store reference
contracts. External authorization and environment identity federation remain mandatory.

NetworkPolicy starts with ingress and egress default deny. It then admits only the declared
API-to-pool, worker-to-pool, Java-Agent-to-Python, Python-to-LiteLLM, and workload-to-OTel paths.
The per-pool PgBouncer policies do not permit cross-pool clients. DNS is the only common egress.
External Temporal, database, provider, object-store, and edge connectivity remains intentionally
unusable until an authorized environment supplies and verifies its exact policy.

The service-mesh resources use `security.istio.io/v1`. Namespace PeerAuthentication requests
`STRICT` mTLS, and an empty namespace AuthorizationPolicy establishes default deny before narrow
workload policies allow authenticated principals and ports. These are render-only desired-state
objects. Actual Istio CRD availability, webhook/schema acceptance, proxy or ambient dataplane
interception, certificate issuance and trust, strict mTLS enforcement, and AuthorizationPolicy
enforcement are external gates.

The I3/I4 OTel join is exact and must remain atomic:

| Field | Required value |
| --- | --- |
| Deployment and Service resource name | `after-sale-otel-collector` |
| `app.kubernetes.io/part-of` | `after-sale-flow` |
| `app.kubernetes.io/name` | `otel-collector` |
| Service account | `after-sale-otel-collector` |
| OTLP gRPC | `4317` |
| OTLP HTTP | `4318` |

I4 owns the OTel workload and Service. I3 owns its ServiceAccount, RBAC, NetworkPolicy, mTLS policy,
PDB, and HPA boundary. The I3 kustomization must not import any I4 path. A separately signed
same-candidate receipt must prove the rendered I3/I4 namespace, resource name, labels, service
account, ports, configuration hash, deployment hash, environment, and attempt lineage agree.

## Five Runtime Security Blockers

Exactly these five known runtime gaps remain release-blocking. A manifest reference cannot close
any of them:

1. `TEMPORAL_CLOUD_TLS_OR_MTLS_CREDENTIAL_ADAPTER_ACCEPTED`: the Temporal Cloud credential adapter,
   TLS or mTLS policy, namespace endpoint, rotation, and old-key compatibility need external proof.
2. `TRUSTED_PROXY_OR_DIRECT_MTLS_ASGI_IDENTITY_BRIDGE_ACCEPTED`: Python ASGI must derive trusted
   workload identity from an accepted proxy or direct mTLS path and reject mutable spoofed headers.
3. `REPORTING_READ_REPLICA_ROUTING_ACCEPTED`: the reporting service and `reporting-ro` PgBouncer pool
   point at a reserved `.invalid` endpoint until DBA evidence proves read-only replica routing.
4. `OBJECT_STORE_WORKLOAD_IDENTITY_PROVIDER_CHAIN_ACCEPTED`: workload identity to private object
   storage, KMS authorization, versioning, immutability, ACL, audit, and readback need external proof.
5. `LANGFUSE_IDENTITY_PROMPT_OUTPUT_REDACTION_ACCEPTED`: identity metadata plus prompt and output
   export remain blocked until runtime redaction and leakage tests are accepted.

Missing, failed, partial, stale, mixed-context, unsigned, invalid, untrusted, self-approved, or
secret-bearing evidence keeps both the production checkpoint and promotion pending.

## KMS, Vault, And Object Storage

`kms-vault-policy.yaml` contains authority references only. It has no key, token, password,
certificate, credential, encrypted secret payload, or production endpoint. The external security
preflight must bind versioned key references, authorized workload identities, rotation overlap,
old-key reads, and actual encrypt/decrypt authorization to the immutable release candidate.

`object-store-policy.yaml` declares separate evidence, graph-input, graph-output, and audit scopes.
Every scope is private, versioned, immutable, KMS-backed, access-audited, and referenced by
`bucket`, `key`, `version_id`, and `sha256`. Provisioning, provider ACLs, public-access blocking,
Object Lock, legal hold, lifecycle configuration, identity federation, audit delivery, and object
readback are all external gates. ConfigMap text is only a policy contract and never an object-store
control-plane receipt.

## Render Check

Only the following local check is authorized by this runbook:

```powershell
kubectl kustomize infra/kubernetes/production
```

The security bundle must contain no `Secret`, `data`, or `stringData`, and the complete output must
contain no Secret resource, concrete production namespace, credential, or non-`.invalid` workload
image. Do not pipe the output to `kubectl apply`.

## External Preflight Receipts

Before any real traffic, Security and SRE must accept independently signed receipts for all five
runtime blockers plus:

- `ISTIO_SECURITY_IO_V1_CRD_READINESS_ACCEPTED`;
- `ISTIO_DATAPLANE_INTERCEPTION_ACCEPTED`;
- `ISTIO_STRICT_MTLS_ENFORCEMENT_ACCEPTED`;
- `ISTIO_AUTHORIZATION_POLICY_ENFORCEMENT_ACCEPTED`; and
- `I3_I4_OTEL_NAMESPACE_LABEL_SERVICE_ACCOUNT_AND_PORT_BINDING_ACCEPTED`.

Every receipt binds the same candidate SHA, configuration SHA-256, environment identity,
deployment-manifest SHA-256, attempt ID, operator, authorization, signer, trust root, timestamp,
status, evidence SHA-256, signed-payload SHA-256, signature, and receipt SHA-256. The signer must be
authorized, unexpired, non-revoked, independent from the runner/generator/candidate/evidence author,
and verified against the independent trust root. Engineering render output cannot substitute for
these receipts.
