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
issuedAt + expiresAt (lifetime <= 2592000 seconds / 30 days)
activationId = p9act.v1.<32 lowercase hex>
unique nonce
tenantSurrogate
caseScope.mode = EXPLICIT_CASE_IDS OR ISOLATED_SYNTHETIC_NEW_CASES
explicit allowedCaseIds OR exact caseIdPrefix + maxCases (1..16) + canonical fixtureSetHash
allowedRoomTypes
caseBuildId + controlBuildId + agentBuildId
Graph key + version + checkpointSchemaVersion + bindingHash + codeBuildId
javaApi + temporalControlWorker + temporalAgentWorker + pythonAgent + frontend image digests
Temporal namespace
physically different Domain/Graph cluster identities and database identities; principals also differ
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

`environmentGeneration` has a durable per-environment high-water. First registration must be
strictly higher and advances it atomically. Exact replica attach at the current high-water is
allowed; a different grant at that generation conflicts, a lower generation is stale, and a
drained/revoked generation is never reused.

There is no case wildcard. For `ISOLATED_SYNTHETIC_NEW_CASES`, Java loads the configured read-only
fixture bytes, rejects duplicate members/schema errors, canonicalizes the full document with RFC
8785, and requires SHA-256 equality with manifest and measured-context hashes. Raw request bytes or
a caller-supplied hash are not authority. Java then atomically reserves one of at most 16 signed
slots and persists the server-generated case ID before first epoch selection. A durable global
generated-ID tombstone is not partitioned by activation/environment and survives revoke. Same-ID
retry is idempotent only for the original activation/slot; wrong bytes/hash/prefix, exhausted
capacity, cross-activation reuse, or concurrent different-slot reuse fails closed.

Admission rejects an expired manifest, an `issuedAt` in the future, an expiry at/before issue, a
lifetime over two hours, replay, or any wrong environment, generation, SHA, lane, version, Build,
Graph key/version/checkpoint/binding hash/code Build, image digest, Temporal namespace, either
physical cluster/database identity,
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
  "room_fencing_token": 1,
  "command_hash": "<sha256-rfc8785-full-embedded-command>",
  "command_envelope_hash": "<sha256-rfc8785-wrapper-omitting-self-hash>",
  "command": {"schema_version": "room-graph-command.v1"}
}
```

The complete embedded command includes its verified `request_hash` when computing `command_hash`.
`command_envelope_hash` hashes the RFC 8785 wrapper with only that field omitted.
The normal per-command Java compact JWS uses protected
`typ=target-e2e-graph-command+jwt` and adds signed `execution_lane`, `activation_id`,
`room_fencing_token`, `command_hash`, and `command_envelope_hash` claims. The Java-signed Domain room
fence is distinct from the Graph runtime lease fence. Claims, body, recomputed hashes, registered
activation, and current room fence must match; Graph then separately acquires/verifies its lease
fence before ledger/checkpoint mutation. Neither fence can substitute for the other.

Graph produces `target-e2e-graph-result-envelope.v1` with the same lane/activation/room-fence/command-envelope
chain and `graph_output_authority=PROPOSAL_ONLY`. `result_hash` equals the nested v1 `output_hash`,
computed from the full nested result with only `output_hash` omitted. The result also binds the
exact room-specific `proposal_hash` and its wrapper self-hash.

Java alone produces `target-e2e-finalization-receipt.v1`. In addition to those hashes, it binds
tenant/case, room type/epoch, room fence, process revision, stage sequence, logical run/attempt, Graph
key/version/checkpoint schema/checkpoint ID, AgentRun manifest ID/hash, isolated Domain database
binding hash, and commit time. Its receipt self-hash omits only `receipt_hash`, and
`formal_writer=JAVA_FINALIZER_ONLY`. Neither wrapper changes an embedded v1 contract, permits
cross-case/epoch/database replay, or allows a SHADOW record to be relabeled.

Hash sources are exact. `agent_run_manifest_hash` hashes the full validated
`agent-execution-manifest.v1` at JSON pointer `""`. `isolated_domain_db_binding_hash` equals the
source `binding_hash`, computed from full validated
`target-e2e-isolated-domain-db-binding.v1` with only `binding_hash` omitted. For each of INTAKE,
EVIDENCE, HEARING, and REVIEW, `proposal_hash` hashes the exact validated
`target-e2e-room-proposal-source.v1` value at `/proposal`. Result and receipt hash fields must equal
these computed sources.

An immutable receipt has only `domain_commit_status=COMMITTED`. Same identity/hashes replay returns
the originally persisted receipt bytes and `receipt_hash` exactly; different hashes conflict.
`ALREADY_COMMITTED` may be an external observation but is never written into or substituted for the
receipt.

## Fixture Contract

The fixture set contains no real tenant, party, case, provider, database, host, secret, private
key, certificate, or valid signature material.

- `fixtures/valid/target-e2e-activation-allowlist-valid.json` proves isolated case allowlisting.
- `fixtures/valid/target-e2e-activation-synthetic-valid.json` proves bounded synthetic browser
  creation with exact prefix and capacity.
- `fixtures/synthetic/p9-synthetic-all-rooms-001.json` supplies the exact canonical fixture bytes.
- `fixtures/valid/target-e2e-isolated-domain-db-binding-valid.json` freezes the DB hash source.
- four `target-e2e-*-proposal-source-valid.json` fixtures freeze `/proposal` hashes per room.
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
| P9-S3 Graph proposals | S1 activation + S2 wrapper | Current signed room fence plus separate lease fence before checkpoint; no Domain capability |
| P9-S4 Java/browser | S2 handoff + S3 result stable | Non-discoverable target outer receipt adapter; exact immutable receipt replay and hash sources |
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
- expiry enters DRAIN_ONLY; only durable pre-expiry commands continue; DRAINED then
  REVOKED_TERMINAL ends with no unresolved ledger, outbox, lease, attempt, finalization,
  compensation, or projection row.

The evidence includes scoped Domain/Graph SQL, database privilege inspection, public Temporal
Workflow descriptions/history scans, logs/traces privacy scans, browser assertions, and immutable
artifact hashes. Internal Temporal persistence tables are not edited or treated as the public
verification API.

## Drain And Failure

Lifecycle is exactly `REGISTERED -> ACTIVE -> DRAIN_ONLY -> DRAINED -> REVOKED_TERMINAL`.
`DRAIN_ONLY` rejects new cases/commands; exact replicas attach only to a durable command admitted
before expiry with matching hashes and room epoch/fence. `DRAINED` accepts/executes nothing.
`REVOKED_TERMINAL` follows only after unresolved accepted work is zero, replicas detach, evidence
is sealed, and `drained_at < revoked_at`.

An incomplete bounded drain suspends at a safe boundary for manual reconciliation. It never
switches an active epoch to LEGACY, bypasses Java Finalizer, blindly retries an ambiguous external
effect, deletes a ledger/checkpoint/history/fact, or renews a manifest in place. Retry requires a
new activation ID, nonce, higher environment generation, and signature. Generation and global
generated-case tombstones are never reused.

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
