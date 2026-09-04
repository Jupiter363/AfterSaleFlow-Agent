# ADR 0010: Graph Command Transport And Stream Policy

## Status

Accepted. The original Phase 3 implementation window kept runtime `DISABLED` by default and allowed
only signed synthetic `SHADOW`. The current manifest-bound `PRODUCTION` lane is governed by ADR 0018
and the Production Runtime release contract. This ADR still does not give Python or Graph a formal
room-writer role; only the Java finalization boundary may commit a proposal.

## Current implementation note (2026-09-04)

The signed command, immutable body/nonce, fresh JWS delivery, fixed HTTP allowlist, public-field
policy and proposal-only result rules remain current. Production Runtime UAT uses
`all-rooms.production-runtime.v2` and `production-runtime-graph.2026-08-18.3`; Intake V4 transports three typed
Frame lanes through `agent-stream.v4`, while Evidence, Hearing and Outcome use `agent-stream.v3`.

## Context

The Python Graph endpoint accepts an immutable `RoomGraphCommand` and returns Agent Stream v2
NDJSON. The public terminal frame intentionally contains only a result reference and SHA-256, while
the Java Activity requires the complete, schema-validated `RoomGraphResult`. Java also needs an
independent public-field policy: accepting a field allowlist from a browser, request body, or model
would let an untrusted caller redefine what may be disclosed.

Delivery retries must preserve the exact command body and `command_nonce`, but replay protection
requires a fresh JWS `jti`. A command can outlive key rotation, so the key named by the immutable
`invocation_context.envelope_key_id` must remain resolvable for the bounded recovery window.

## Decision

### Execution credential

Java signs the exact schema-validated command body with an ES256 execution credential. The header
contains exactly `alg`, `kid`, and `typ=graph-command+jwt`. Claims contain exactly the invocation
bindings required by the Python `InvocationEnvelopeVerifier`; execution credentials never contain
the reconciliation-only `capability` or `original_envelope_key_id` claims.

Every HTTP delivery uses the same command bytes and a fresh short-lived `jti`. The signer resolves
the exact retained key named by `envelope_key_id`; silently substituting the current active key is
forbidden. Reconciliation uses its distinct `graph-reconcile+jwt` credential and cannot execute a
model, tool, or graph node.

### HTTP and NDJSON boundary

The production transport receives an mTLS-configured `HttpClient`. It follows no redirects and
requires the response URI to equal the request URI. Cancellation cancels the HTTP future and closes
the active response body. Request and response byte limits, per-line limits, strict UTF-8 decoding,
duplicate JSON member rejection, content type, content encoding, cache directives, and
`X-Agent-Run-Id` are validated before an event reaches durable projection.

The execution response must be `application/x-ndjson`, identity encoded, and `no-store`. Events are
validated against Agent Stream v2 and the exact run, attempt, audience, and policy binding. Sequence
numbers are contiguous from the Python sequence-zero identity handshake, one terminal is required,
and Python `attempt_reset` is rejected. Java remains the only owner of the public prelude and reset.

### Public visibility policy

Java resolves public fields from a server-owned, immutable policy keyed by the complete executable
binding:

```text
graph_key
graph_version
checkpoint_schema_version
agent_profile_id
prompt_profile_id
model_profile_id
output_schema_version
policy_version
guardrail_version
audience
```

The resolved policy binds allowed `(node, field)` pairs. Every binding in the returned policy must
equal the signed command. An unknown, partial, conflicting, or null policy fails before HTTP
dispatch. A command field, actor capability, browser registry, Python response, or generic fallback
cannot widen this policy. A graph version that intentionally emits no visible deltas registers an
explicit empty policy rather than relying on an implicit default.

### Terminal materialization

The stream `final` frame carries only `final_result_ref` and `final_result_hash`; Java never treats
it as the complete result. After receiving and validating that frame, the command client invokes the
result-only reconciliation endpoint with the same immutable command. The returned
`GraphReconcileResponse` must bind the same command, attempt, checkpoint, reference, result hash,
versions, and execution metadata. Only then may the client return `RoomGraphResult` to the durable
execution gateway.

If the final frame is durable but reconciliation or Java result persistence is lost, the Activity
uses `RECONCILE_TERMINAL`; it does not call the model again. An error or `attempt_aborted` stream
terminal is mapped to the closed Java recovery action table and never followed by result
reconciliation.

### Assembly and rollout

Spring assembly has an independent graph-client mode with only `DISABLED` and `SHADOW`; the default
is `DISABLED`. A shadow worker fails closed when the exact-key resolver, execution signer, mTLS
transport, visibility policy, reconciliation client, or durable stream store is absent. Plain HTTP
is permitted only by an explicit local/test switch. No Phase 3 configuration can select a formal
writer or invoke Finalizer from shadow output.

The `DISABLED` mode creates no command client or execution gateway. A `SHADOW` transport must
explicitly attest `MUTUAL_TLS`; an unverified transport is rejected even when its URI uses HTTPS.
`LOCAL_PLAINTEXT` is accepted only with both the explicit plaintext switch and an active `local` or
`test` Spring profile. The shadow gateway is also rejected when formal AgentRun writing is enabled,
so the engineering assembly cannot accidentally become the Temporal Finalizer input path.

Java loads the contract schemas from immutable classpath resources embedded in the application
artifact. A contract test compares every embedded byte with the repository-authoritative
`contracts/agent-platform/v1` pack, preventing a Docker image from depending on an external source
tree path or silently carrying a divergent schema copy.

## Consequences

- Streaming progress remains incremental and durable without buffering the complete response.
- A final result requires a second, result-only HTTP exchange, but this removes raw result JSON from
  the public stream and gives recovery one authoritative materialization path.
- Key rotation retains old execution keys for the command recovery window.
- Adding a graph version requires an exact Java visibility-policy registration and its negative
  disclosure tests before signed shadow execution.
- mTLS identity and KMS/HSM provisioning remain deployment gates; unit tests may inject synthetic
  keys and transports without weakening production defaults.

## Rejected Alternatives

- Put `RoomGraphResult` or raw model JSON in the public terminal frame.
- Accept a visible-field allowlist from `RoomGraphCommand`, a browser request, or Python.
- Infer visibility from `graph_key` alone or use a wildcard/default policy.
- Reuse a consumed `jti`, mutate the command nonce, or rehash a delivery retry.
- Sign an old command with whichever execution key is currently active.
- Treat HTTP retryability or an NDJSON `retryable` boolean as sufficient recovery authority.
