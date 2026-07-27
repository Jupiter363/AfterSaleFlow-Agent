# Phase 9 P9.0 Target-Architecture E2E Contract Pack

## Gate State

```text
document_status: P9_0_CONTRACT_FREEZE_CANDIDATE
P9.0: NOT_RUN
runtime_activation: BLOCKED
execution_lane: TARGET_E2E_CANDIDATE
environment_class: ISOLATED_PREPRODUCTION
phase8_engineering_checkpoint: PASS
accepted_phase8_checkpoint_commit: 654d92201254b36a7a10da4a3cdb29ebec6c5fc0
production_checkpoint: PENDING_EXTERNAL
promotion_gate: PENDING
MIG-003..008: PENDING_PROMOTION
production_formal_selector_default: LEGACY
target_e2e_activation_default: DISABLED
```

This contract candidate cannot pass P9.0 or activate a runtime. Default-off implementation may be
integrated and reviewed, but target workers remain unreachable until separate exact-candidate
evidence and acceptance plus a fresh activation capability. No statement here is production
authorization.

## Governing Sources

The frozen Phase 9 source set is:

- `plans/phase-9-target-architecture-e2e-execution.md`;
- `plans/phase-9-target-architecture-e2e-test-batches.yaml`;
- `docs/architecture/adr/0017-target-architecture-preproduction-e2e.md`;
- this contract pack;
- `contracts/agent-platform/target-e2e/**`; and
- `tests/static/test_phase9_target_e2e_contract.py`.

The source architecture plan, ADRs 0011 and 0016, and the Phase 8 engineering checkpoint remain
upstream constraints. Phase 9 creates a distinct isolated lane; it does not reinterpret SHADOW,
make Phase 8 production evidence, or relax production defaults.

## P9.0 Evidence Topology

P9.0 uses separate immutable responsibilities:

```text
F9: reviewed contract freeze
I9: one integrated default-off implementation candidate retaining exact F9 contract blobs
E9: evidence-only direct child of I9
A9: checkpoint-only direct child of E9
activation manifest candidateSha: exact I9
```

`F9` cannot self-pass. `I9` cannot change a frozen contract blob without a new freeze review. `E9`
records commands, measured environment, start/end times, raw reports, hashes, candidate/tree,
images, Builds, namespace, database identities, activation ID/nonce, failure class, and clean-state
proof; it makes no decision. Only `A9` may record P9.0 isolated activation acceptance. Evidence
from different candidates, environments, generations, activations, images, Builds, namespaces,
database identities, attempts, or clocks cannot be combined.

## Frozen Authority

| Boundary | Phase 9 authority |
| --- | --- |
| Temporal | Process order, waits, timers, retry, cancellation, compensation scheduling |
| LangGraph | Bounded private cognition and proposals only |
| Graph PostgreSQL | Cognitive checkpoints, command/attempt/lease/result ledger only |
| Java Finalizer | Only formal Domain writer |
| Domain PostgreSQL | Formal test facts and projections for the isolated bound scope |
| Frontend | Command submission and Java projection display only |

Python receives no Domain credential. Graph cannot create a formal message, evidence fact,
Artifact, decision, ActionRecord, receipt, transition, closure, evaluation, audit, or external
effect. The Java Finalizer revalidates activation, command, proposal, tenant/case/room,
epoch/revision/fence, Build/Graph, and operation identity inside the Domain transaction.

The only room types are `INTAKE`, `EVIDENCE`, `HEARING`, and `REVIEW`. `DRAFT` and `OUTCOME` remain
projections/routes.

## Activation Manifest v1

Schema: `contracts/agent-platform/target-e2e/v1/target-e2e-activation-manifest.schema.json`.
Semantic policy: `activation-validation-policy.v1.json`.

Required exact bindings are:

```text
contractVersion = target-e2e-activation.v1
executionLane = TARGET_E2E_CANDIDATE
environmentId + environmentGeneration
candidateSha = 40 lowercase hex
issuedAt + expiresAt (lifetime <= 7200 seconds)
activationId = p9act.v1.<32 lowercase hex>
unique nonce
tenantSurrogate
caseScope.mode = EXPLICIT_CASE_IDS OR ISOLATED_SYNTHETIC_NEW_CASES
explicit allowedCaseIds OR exact caseIdPrefix + maxCases (1..16) + signed fixture set
allowedRoomTypes
caseBuildId + controlBuildId + agentBuildId
Graph key + version + checkpointSchemaVersion + bindingHash + codeBuildId
javaApi + temporalControlWorker + temporalAgentWorker + pythonAgent + frontend image digests
Temporal namespace
Domain and Graph cluster/database/runtime-principal identities
Graph proposal-only + no Domain credentials
Java Finalizer only formal writer
javaDomainCommitAllowed = true only for the bound isolated Domain database
graphDomainWriteAllowed = false
externalEffectsAllowed = false
productionTrafficAllowed = false
production defaults LEGACY + DISABLED
```

Manifest self-hash is lowercase SHA-256 of RFC 8785 canonical JSON with only `manifestHash`
omitted. The deployment artifact is compact ES256 JWS over the full canonical manifest payload,
with protected header `typ=target-e2e-activation+jwt` and a trusted Java `kid`.

The activation is registered at deployment startup from a read-only mounted file. An
environment adapter is allowed for isolated automation. The only optional bootstrap HTTP header
is `X-AfterSaleFlow-Target-E2E-Activation`; it is forbidden at Graph endpoints. The activation is
not a per-command credential and cannot be reused as a bearer.

The Java control ledger atomically registers or attaches the identity
`(environmentId, environmentGeneration, activationId, nonce, manifestHash)` before target worker
admission and retains it through expiry. Exact replicas attach to the existing row only when every
persisted Build, image, Graph, namespace, database, scope, and authority binding hash also matches;
no second consumption row is created. Activation ID and nonce are unique grant keys. Reuse for a
different deployment, environment, generation, manifest hash, or binding set is replay and fails
without storing any private signing key or database secret.

There is no case wildcard. For `ISOLATED_SYNTHETIC_NEW_CASES`, Java atomically reserves one of at
most 16 signed slots and persists the server-generated case ID before first epoch selection. Its ID
must match the exact signed prefix. Same-ID retry is idempotent only for the original activation
slot; wrong prefix, exhausted capacity, or cross-activation reuse fails closed. This enables an
isolated browser create flow without authorizing real or production traffic.

Admission rejects an expired manifest, an `issuedAt` in the future, an expiry at/before issue, a
lifetime over two hours, replay, or any wrong environment, generation, SHA, lane, version, Build,
Graph key/version/checkpoint/binding hash/code Build, image digest, Temporal namespace, database
identity, tenant, room, case, or synthetic scope. Validation fails before worker admission and
cannot partially activate services.

## Command And Result Compatibility

`room-graph-command.v1` is unchanged. The additive body wrapper is
`target-e2e-graph-command-envelope.v1`:

```json
{
  "schema_version": "target-e2e-graph-command-envelope.v1",
  "execution_lane": "TARGET_E2E_CANDIDATE",
  "activation_id": "p9act.v1.<32-lowercase-hex>",
  "command_hash": "<sha256-rfc8785-full-embedded-command>",
  "command_envelope_hash": "<sha256-rfc8785-wrapper-omitting-self-hash>",
  "command": {"schema_version": "room-graph-command.v1"}
}
```

The complete embedded command includes its verified `request_hash` when computing `command_hash`.
`command_envelope_hash` hashes the RFC 8785 wrapper with only that field omitted.
The normal per-command Java compact JWS uses protected
`typ=target-e2e-graph-command+jwt` and adds signed `execution_lane`, `activation_id`, and
`command_hash` plus `command_envelope_hash` claims. The claims, body, recomputed hashes, registered
activation, and request context must all match before Graph ledger or checkpoint mutation.

Graph produces `target-e2e-graph-result-envelope.v1` with the same lane/activation/command-envelope
chain and `graph_output_authority=PROPOSAL_ONLY`. `result_hash` equals the nested v1 `output_hash`,
computed from the full nested result with only `output_hash` omitted. The result also binds the
exact room-specific `proposal_hash` and its wrapper self-hash.

Java alone produces `target-e2e-finalization-receipt.v1`. In addition to those hashes, it binds
tenant/case, room type/epoch, fence, process revision, stage sequence, logical run/attempt, Graph
key/version/checkpoint schema/checkpoint ID, AgentRun manifest ID/hash, isolated Domain database
binding hash, and commit time. Its receipt self-hash omits only `receipt_hash`, and
`formal_writer=JAVA_FINALIZER_ONLY`. Neither wrapper changes an embedded v1 contract, permits
cross-case/epoch/database replay, or allows a SHADOW record to be relabeled.

## Fixture Contract

The fixture set contains no real tenant, party, case, provider, database, host, secret, private
key, certificate, or valid signature material.

- `fixtures/valid/target-e2e-activation-allowlist-valid.json` proves isolated case allowlisting.
- `fixtures/valid/target-e2e-activation-synthetic-valid.json` proves bounded synthetic browser
  creation with exact prefix and capacity.
- `fixtures/runtime/isolated-preproduction-context.json` is a non-secret measured-context golden.
- `fixtures/invalid/activation-invalid-cases.json` defines mutation-based negative fixtures for
  time, replay, and every runtime binding.
- `fixtures/canonical/activation-canonical-golden.json` freezes the RFC 8785 self-hash and JWS
  protected header while explicitly carrying no valid signature.

## Slice Gates

| Slice | Entry | Exit |
| --- | --- | --- |
| P9-S1 activation/isolation | Contract freeze reviewed | All invalid bindings fail closed; defaults disabled; DB separation proven |
| P9-S2 Temporal/all rooms | S1 contract APIs stable | Four typed room replays pass; target epochs cannot use legacy executor |
| P9-S3 Graph proposals | S1 activation + S2 wrapper | Exact all-room registry/checkpoint recovery; no Domain capability |
| P9-S4 Java/browser | S2 handoff + S3 result stable | Java transaction/receipt idempotency and browser projection pass |
| P9-S5 unified/recovery | S1-S4 green and all P0 closed | One exact all-room activation passes, drains, and leaves zero unresolved rows |

Focused batches run during each slice. Docker, browser, all-room recovery, and unified assertions
run once at Batch 4. The batch schedule is authoritative for process limits and test grouping.

## Unified Evidence Minimum

The final scenario proves:

- no manifest means no target admission;
- the exact manifest registers once, identical replicas attach idempotently, and conflicting
  replay fails;
- browser creation/import and all formal commands use the durable Java command/outbox path;
- all four room epochs are `TEMPORAL` with pinned Workflow/Run/Build/Graph identities;
- every AgentRun uses `agent-stream.v2` and `TEMPORAL_ACTIVITY`;
- Graph command, attempt, lease, checkpoint, and result hashes form one causal chain;
- Graph output is proposal-only and Java commits the sole formal receipt;
- Java/Python/worker restart windows recover without duplicate cognition, fact, or effect;
- SSE reconnect and page reload recover from durable Java projection; and
- drain rejects new work and ends with no unresolved ledger, outbox, lease, attempt, finalization,
  compensation, or projection row.

The evidence includes scoped Domain/Graph SQL, database privilege inspection, public Temporal
Workflow descriptions/history scans, logs/traces privacy scans, browser assertions, and immutable
artifact hashes. Internal Temporal persistence tables are not edited or treated as the public
verification API.

## Drain And Failure

Lifecycle is `REGISTERED -> ACTIVE -> DRAINING -> DRAINED`; a pre-admission invalid capability is
`REJECTED`, and an operator stop may produce `REVOKED`. `DRAINING` rejects new cases and commands
while compatible pinned workers finish accepted work. Expiry also blocks new admission but does
not discard accepted work.

An incomplete bounded drain suspends at a safe boundary for manual reconciliation. It never
switches an active epoch to LEGACY, bypasses Java Finalizer, blindly retries an ambiguous external
effect, deletes a ledger/checkpoint/history/fact, or renews a manifest in place. Retry requires a
new activation ID, nonce, environment generation, and signature.

## Exit Ceiling

The maximum P9.0 success is:

```text
TARGET_ARCHITECTURE_PREPRODUCTION_E2E: PASS
production_checkpoint: PENDING_EXTERNAL
promotion_gate: PENDING
MIG-003..008: PENDING_PROMOTION
next_permission: SEPARATELY_AUTHORIZED_EXTERNAL_PRODUCTION_CHECKPOINT_ONLY
```

It is an engineering checkpoint, not production readiness. Production credentials, traffic,
database changes, scheduler retirement, V046 switching, canary, promotion, `GATE-*`, migration
promotion, and V047 cleanup remain outside this contract and require separate external authority.
