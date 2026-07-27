# ADR 0017: Isolated Target-Architecture Preproduction E2E

- Status: ACCEPTED FOR ISOLATED PREPRODUCTION ENGINEERING ONLY
- Date: 2026-07-27
- Scope: Phase 9 target-architecture end-to-end candidate lane
- Production authorization: NONE

```text
production_checkpoint: PENDING_EXTERNAL
promotion_gate: PENDING
MIG-003..008: PENDING_PROMOTION
```

## Context

The Phase 8 engineering checkpoint accepted candidate `9233f9b489dff7d9624f2b0f21369d349f104cca`
through evidence `df97e398f03552bd6689b77925f7af6386fa7e16` and checkpoint commit
`654d92201254b36a7a10da4a3cdb29ebec6c5fc0`. It explicitly left the production checkpoint,
promotion gate, `MIG-006`, `MIG-007`, and `MIG-008` pending. Repository tests and synthetic
SHADOW evidence cannot close those external gates.

The target architecture nevertheless needs one production-shaped, isolated preproduction path to
prove that browser commands traverse Temporal, AgentRun V2, proposal-only LangGraph, and the Java
Finalizer for every real `RoomType`. Existing default paths cannot be relabeled for that purpose:
ADRs 0011 and 0016 keep their `LEGACY`, `DISABLED`, and synthetic-SHADOW restrictions, Graph may
not acquire Domain credentials, and production remains outside repository authority.

## Decision

Phase 9 adds one distinct execution lane with the exact literal `TARGET_E2E_CANDIDATE`. It is
available only in a dedicated `ISOLATED_PREPRODUCTION` environment and only after a short-lived
Java-signed activation manifest has been verified and consumed. The lane is neither `SHADOW` nor
production. It may commit formal test facts only to the manifest-bound isolated Domain database
for the manifest-bound tenant and case scope.

The authority split is invariant:

- Temporal owns process order, waits, timers, retries, cancellation, and compensation scheduling.
- LangGraph owns bounded cognitive state and emits proposals only.
- Python has Graph-database credentials only and has no Domain database credentials.
- Java Finalizer is the only formal Domain writer.
- Vue reads Java projections and submits commands; it owns no process or writer decision.
- Production defaults remain `formalCaseSelector=LEGACY` and `targetE2EActivation=DISABLED`.

`DRAFT` and `OUTCOME` remain projections. The all-room set is exactly `INTAKE`, `EVIDENCE`,
`HEARING`, and `REVIEW`; Phase 9 does not add an `OUTCOME` room type.

## Activation Capability

The activation payload conforms to `target-e2e-activation.v1`. It binds all of the following:

- `executionLane=TARGET_E2E_CANDIDATE`;
- `environmentId` and monotonic `environmentGeneration`;
- one full 40-character lowercase `candidateSha`;
- `issuedAt`, `expiresAt`, and one unique nonce;
- `tenantSurrogate` plus either explicit `allowedCaseIds` or bounded isolated synthetic new-case
  creation;
- `allowedRoomTypes`;
- exact case, control, and agent Build IDs;
- exact Graph key, version, checkpoint schema version, binding hash, and code Build ID;
- exact OCI image digests;
- Temporal namespace; and
- distinct Domain and Graph database cluster, database, and runtime-principal identities.

`activationId` is independently minted as `p9act.v1.<32-lowercase-hex>` before hashing. It is not
derived from `manifestHash`. The manifest self-hash is SHA-256 over RFC 8785 canonical JSON with
only `manifestHash` omitted. The deployed artifact is a compact ES256 JWS whose payload is the
complete canonical manifest; its protected `typ` is `target-e2e-activation+jwt` and `kid` must
resolve to an allowed Java control-plane public key.

The activation is deployment-scoped, not per-command. Java reads it once from a read-only mounted
file; an environment-variable adapter is permitted for isolated automation. An optional bootstrap
HTTP adapter uses exactly `X-AfterSaleFlow-Target-E2E-Activation`. That header is forbidden on
Graph command endpoints, and the activation JWS is never forwarded as a reusable bearer.

Java atomically registers `(environmentId, environmentGeneration, activationId, nonce,
manifestHash)` in the shared target-E2E control ledger before any target worker accepts work.
Additional replicas may attach idempotently only when that identity and every persisted binding
hash are exact; they create no second consumption row. Activation ID and nonce are unique grant
keys and cannot arm another deployment, environment, generation, manifest hash, or binding set.
Conflicting reuse is replay and is rejected. `issuedAt` in the future, `expiresAt <= issuedAt`, an
expired manifest, or a lifetime over 7,200 seconds is rejected.

`caseScope` has no wildcard. `EXPLICIT_CASE_IDS` contains a nonempty exact allowlist. For browser
creation before a server-generated case ID exists, `ISOLATED_SYNTHETIC_NEW_CASES` binds an exact
uppercase `caseIdPrefix`, `maxCases` from 1 through 16, and a signed fixture set. The Java ledger
atomically reserves one slot and persists the generated ID before first epoch selection. Repeating
the same generated ID is idempotent only for its original activation slot; wrong prefix, exhausted
capacity, or cross-activation reuse is rejected.

The manifest distinguishes authority precisely: `javaDomainCommitAllowed=true` permits only Java
Finalizer commits to the manifest-bound isolated Domain database;
`graphDomainWriteAllowed=false`, `externalEffectsAllowed=false`, and
`productionTrafficAllowed=false` forbid Graph Domain writes, external/production side effects,
and production traffic.

## Command, Proposal, And Receipt Binding

`room-graph-command.v1` remains unchanged. Phase 9 adds
`target-e2e-graph-command-envelope.v1` with exact members `schema_version`, `execution_lane`,
`activation_id`, `command_hash`, `command_envelope_hash`, and `command`. The embedded `command` is
the existing v1 value. `command_hash` is lowercase SHA-256 of the RFC 8785 canonical complete
embedded command, including its separately verified `request_hash`. `command_envelope_hash` is
SHA-256 of the RFC 8785 wrapper with only `command_envelope_hash` omitted.

Every command uses its normal short-lived Java compact JWS with protected
`typ=target-e2e-graph-command+jwt`. Its signed claims add exactly `execution_lane`,
`activation_id`, `command_hash`, and `command_envelope_hash`; all four must equal the body wrapper
and registered active deployment. Thus a valid activation is not itself a command credential.

Graph returns `target-e2e-graph-result-envelope.v1`, which repeats lane, activation, command hash,
and command-envelope hash and fixes `graph_output_authority=PROPOSAL_ONLY`. Its `result_hash`
equals the nested `room-graph-result.v1.output_hash`, whose preimage is the RFC 8785 full nested
result with only `output_hash` omitted. It also binds the exact room proposal hash and a result-
envelope hash computed with only that self-hash omitted.

Java validates all bindings and the current room epoch/revision/fence before committing. A
successful `target-e2e-finalization-receipt.v1` binds tenant, case, room type/epoch, fence, process
revision, stage sequence, logical run/attempt, command and envelope hashes, Graph key/version/
checkpoint schema/checkpoint ID, result/proposal/result-envelope hashes, AgentRun manifest ID/hash,
isolated Domain database binding hash, commit time, and Java-only writer. Its `receipt_hash` is
SHA-256 over RFC 8785 receipt JSON with only `receipt_hash` omitted. A result or receipt cannot be
replayed across those identities or relabeled from SHADOW.

## Fail-Closed Admission

Admission rejects before worker registration or case allocation when any of these is wrong,
missing, stale, mixed, or untrusted:

- manifest schema, canonical bytes, self-hash, signature, key, version, or lane;
- time window or replay state;
- environment identity/generation or candidate SHA;
- tenant, case/synthetic scope, or room scope;
- case/control/agent/Graph Build ID;
- Graph key/version/checkpoint/binding hash;
- any image digest, Temporal namespace, or database identity; or
- proposal-only Graph authority, database separation, Java-only formal writer, or production
  defaults.

No partial activation is permitted. Runtime values are measured from the deployed environment,
not copied from the manifest into the verifier's expected context.

## Rollback And Drain

Rollback first closes admission for new target cases and commands, then records the activation as
`DRAINING`. Accepted commands and active epochs are drained with the exact pinned images, Build
IDs, Graph binding, and Temporal namespace. The activation JWS is never renewed in place.

If bounded drain cannot complete, workflows are suspended at a recorded safe boundary and require
manual reconciliation. An active epoch never falls back to a legacy writer, Graph results never
bypass Java Finalizer, external effects are never blindly replayed, and additive ledgers,
checkpoints, histories, proposals, receipts, and Domain facts are preserved. A retry requires a
new activation ID, nonce, environment generation, and signed manifest.

## Engineering Versus Production

Phase 9 may record `TARGET_ARCHITECTURE_PREPRODUCTION_E2E: PASS` only for one exact isolated
candidate, environment generation, activation, image set, Build set, namespace, and database pair.
That is an engineering checkpoint. It cannot record a production `GATE-*`, migration promotion,
canary, scheduler retirement, production traffic, production readiness, or release approval.

The Phase 8 external production checkpoint remains a separate future process requiring explicit
authorization, real production-equivalent identities and controls, complete release evidence, and
the required independent signatures. Nothing in this ADR grants production credentials or
changes `MIG-003..008` from `PENDING_PROMOTION`.

## Consequences

Implementation may add default-off target-only adapters, ledgers, wrappers, worker registration,
Graph proposal executors, Java Finalizers, deployment isolation, and focused tests. Runtime
activation remains blocked until the P9.0 exact-candidate contract gate and manifest admission
both pass. Existing LEGACY, DISABLED, and signed synthetic SHADOW behavior and negative tests must
remain intact.
