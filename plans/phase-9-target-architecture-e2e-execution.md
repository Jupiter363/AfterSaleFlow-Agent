# Phase 9 Target-Architecture Isolated Preproduction E2E Execution Plan

## Status

```text
document_status: P9_0_CONTRACT_FREEZE_CANDIDATE
contract_gate: P9.0 NOT_RUN
runtime_activation: BLOCKED
execution_lane: TARGET_E2E_CANDIDATE
environment_class: ISOLATED_PREPRODUCTION
formal_writer: JAVA_FINALIZER_ONLY
production_formal_selector_default: LEGACY
target_e2e_activation_default: DISABLED
production_checkpoint: PENDING_EXTERNAL
promotion_gate: PENDING
MIG-003..008: PENDING_PROMOTION
```

This plan defines an engineering lane for one isolated preproduction target-architecture E2E. It
does not reopen, replace, or pass the Phase 8 external production checkpoint. The more restrictive
rule wins if this plan, ADR 0017, ADR 0011, ADR 0016, or the Phase 8 checkpoint differs.

## Frozen Objective

Prove one browser-driven case across `INTAKE`, `EVIDENCE`, `HEARING`, and `REVIEW` with:

```text
browser command
  -> Java command ledger and outbox
  -> Temporal Case/typed Room Workflow
  -> Temporal AgentRun V2 Activity
  -> Java-signed target-E2E Graph command wrapper
  -> proposal-only LangGraph/LCEL with Graph PostgreSQL checkpoint
  -> Java Finalizer transaction
  -> Domain PostgreSQL facts/projection/SSE
  -> browser-visible result
```

Graph never becomes a formal writer. Java Finalizer is the only formal Domain writer. `OUTCOME`
remains a route/projection within the Review/Outcome process and is not added to `RoomType`.

## Scope And Prohibitions

Allowed work is limited to default-off target-lane contracts, activation admission, isolated
environment wiring, typed Temporal routing, Graph proposal execution, Java finalizers, browser
adapters, focused verification, and the final isolated all-room E2E.

The following remain forbidden:

- production traffic, credentials, database identities, namespaces, or data;
- changing production defaults from `LEGACY`/`DISABLED`;
- reusing the activation JWS as a Graph bearer or per-command credential;
- giving Python a Domain database credential or Graph a formal sink;
- falling back to a legacy writer inside a `TEMPORAL` epoch;
- relabeling existing SHADOW commands, results, comparisons, or receipts;
- claiming any `GATE-*`, migration promotion, canary, release, or production-readiness result; and
- destructive cleanup or weakening existing LEGACY/SHADOW negative tests.

## P9.0 Entry Gate

P9.0 is the activation entry gate, not permission to weaken default-off engineering boundaries.
Implementation may be assembled behind an unreachable target profile while P9.0 is pending, but
no target worker may admit a case until all of these are true on one immutable integrated
candidate:

1. The accepted Phase 8 engineering checkpoint commit
   `654d92201254b36a7a10da4a3cdb29ebec6c5fc0` remains in ancestry and still records only an
   engineering pass.
2. ADR 0017, this plan, the batch schedule, contract pack, all target-E2E schemas, fixtures, and
   static contract test are unchanged from their reviewed contract freeze.
3. Focused contract, Java, Python, frontend, migration, and deployment-render batches pass from a
   clean materialization of one full 40-character lowercase candidate SHA.
4. All P0 reviews are closed against that same candidate and no report from another SHA,
   environment generation, image set, Build set, namespace, database identity, or attempt is mixed.
5. A separate evidence object records `P9.0=PASS_AWAITING_ACCEPTANCE`; a separate acceptance object
   records `P9.0=PASS` and permits isolated activation only.
6. A fresh `target-e2e-activation.v1` compact JWS is signed for the exact accepted candidate and
   measured isolated environment. Its activation ID and nonce are not registered to another
   environment, generation, manifest hash, or binding set.

Any contract change after freeze requires a new review and complete P9.0 evidence. The contract
candidate cannot pass itself.

## Activation And Wire Contract

The authoritative files are:

- `contracts/agent-platform/target-e2e/v1/target-e2e-activation-manifest.schema.json`;
- `contracts/agent-platform/target-e2e/v1/activation-validation-policy.v1.json`;
- `contracts/agent-platform/target-e2e/v1/target-e2e-graph-command-envelope.schema.json`;
- `contracts/agent-platform/target-e2e/v1/target-e2e-graph-result-envelope.schema.json`; and
- `contracts/agent-platform/target-e2e/v1/target-e2e-finalization-receipt.schema.json`.

Activation is loaded once at deployment startup and atomically registered in the shared Java
control ledger. Preferred transport is a read-only mounted file. The optional bootstrap header is
`X-AfterSaleFlow-Target-E2E-Activation`; it is rejected by Graph endpoints. Manifest lifetime is
at most two hours, future `issuedAt` is rejected, and conflicting nonce reuse fails before worker
admission. Exact replicas may attach idempotently to the one existing registration.

Case scope is either exact `EXPLICIT_CASE_IDS` or bounded `ISOLATED_SYNTHETIC_NEW_CASES`. The
latter has an exact uppercase prefix and `maxCases <= 16`; Java atomically consumes one activation
slot and persists the generated case ID before its first epoch selection. There is no wildcard.
Java Domain commit is allowed only for that isolated bound scope; Graph Domain writes,
external/production effects, and production traffic remain forbidden.

`room-graph-command.v1` remains frozen. `target-e2e-graph-command-envelope.v1` embeds it and binds
the lane, activation, SHA-256/RFC-8785 full-command hash, and canonical wrapper self-hash. The
per-command Java JWS uses
`typ=target-e2e-graph-command+jwt` and signs matching `execution_lane`, `activation_id`, and
`command_hash` plus `command_envelope_hash` claims. Result and finalization receipt repeat the same
causal binding. Result hash equals the nested v1 `output_hash` after its omit-only-output-hash rule;
the receipt additionally binds tenant/case/room/epoch/fence/revision/stage, Graph checkpoint,
AgentRun manifest, isolated Domain database, proposal, result envelope, and commit time.

## Five Implementation Slices

### P9-S1: Activation Trust And Isolation

**Owners:** contract, Java activation, and deployment owners in disjoint paths.

**Work:** add strict schemas/codecs; Java ES256 verifier; atomic activation ledger; nonce replay;
clock/lifetime checks; measured environment/build/image/namespace/database bindings; default-off
configuration; isolated network, volume, database, namespace, and identity material.

**Entry:** reviewed Phase 9 contract freeze; no target worker registered.

**Exit:** focused valid/invalid fixtures pass; every required mismatch fails before admission;
environment starts with `LEGACY`/`DISABLED` when the manifest is absent, expired, replayed, or
wrong; Graph principal cannot connect to Domain PostgreSQL.

### P9-S2: Temporal Control And Typed Rooms

**Owners:** Java control-plane/Temporal owner and Java activation owner in disjoint packages.

**Work:** target-only selector and epoch bootstrap; browser command admission; durable outbox;
CaseProcess routing; typed Intake, Evidence, Hearing, and Review/Outcome child registration;
AgentRun V2 dispatch; fences, retries, waits, timers, and projection updates.

**Entry:** S1 activation API and ledger contract published; lane still disabled by default.

**Exit:** focused deterministic/replay tests prove all four room types, command order, duplicate
absorption, stale-fence rejection, and no legacy executor for a target epoch. Existing LEGACY and
SHADOW tests remain green.

### P9-S3: Proposal-Only Graph Execution

**Owners:** Python Graph owner and contract owner in disjoint paths.

**Work:** target envelope admission; exact lane/activation/command-hash JWS checks; all-room Graph
registry binding; Graph DB command/attempt/lease/checkpoint/result persistence; bounded LCEL;
proposal result wrapper; restart/reconciliation behavior; explicit empty tool/formal-write policy.

**Entry:** S1 activation identity and S2 command wrapper producer available.

**Exit:** each room has one exact executor binding and checkpoint recovery test; wrong activation,
lane, tenant, case, room, command hash, Graph binding, nonce, and fence fail before checkpoint
mutation; import and credential tests prove no Domain adapter or credential is reachable.

### P9-S4: Java Finalization And Browser Flow

**Owners:** Java Finalizer owner and frontend owner in disjoint paths.

**Work:** target-only facts providers and room committers; proposal/result loading; exact binding
and fence revalidation; transactional AgentRun finalization; room facts, projections, audit, SSE;
browser command/API adapters and all expected intermediate/terminal views.

**Entry:** S2 workflow result handoff and S3 proposal envelope stable.

**Exit:** focused transaction tests prove one Java commit or identical replay receipt, different
hash conflict, rollback on partial failure, no direct Python write, and browser visibility from
durable Java projection rather than client-derived phase logic.

### P9-S5: Recovery, Drain, And Unified Evidence

**Owners:** deployment, verification, and primary integration owners.

**Work:** assemble the isolated environment; seal measured bindings; execute room-by-room smoke;
exercise Java API, control worker, agent worker, Python, Redis, Domain DB, and Graph DB restart
windows; run the final browser all-room scenario; capture DB/Temporal/Graph assertions and drain.

**Entry:** S1-S4 focused gates pass on one immutable candidate and all P0 findings are closed.

**Exit:** unified Batch 4 passes on one activation/environment generation and the activation reaches
`DRAINED`; no unresolved command, outbox, lease, AgentRun, finalization, compensation, or projection
gap remains. The result is an engineering checkpoint only.

## Focused Verification

During implementation, run only the owner-specific commands in
`phase-9-target-architecture-e2e-test-batches.yaml`. At most two light processes and the repository's
allowed isolated Maven/Testcontainers lanes may run concurrently. Do not repeatedly run the full
suite, Docker environment, browser flow, load, chaos, or recovery drills.

Required focused classes cover:

- schema, fixture, canonical hash, time, replay, scope, and exact-binding validation;
- activation ledger atomicity and startup fail-closed behavior;
- selector/epoch/CaseProcess/typed-child replay and writer guards;
- command-wrapper JWS, Graph gateway, all-room executors, checkpoints, lease/fence, and restart;
- each room's Java Finalizer transaction and receipt replay/conflict behavior; and
- frontend command submission, reconnect, projection rendering, and no client-side writer choice.

## Unified Isolated Checkpoint

Batch 4 runs once after S1-S5 integrate. It uses one clean candidate SHA, one activation ID/nonce,
one environment generation, one set of immutable image digests and Build IDs, one Temporal
namespace, and one distinct Domain/Graph database identity pair. It stops on the first hard failure.

The required scenario is:

1. Start the isolated environment with no activation and prove target admission is disabled.
2. Atomically register the exact activation JWS, prove identical replicas attach without a second
   consumption row, and prove any cross-binding/environment/generation replay is rejected.
3. Create/import the allowlisted isolated case through the browser.
4. Complete Intake, Evidence, Hearing, Review decision, Outcome execution/closure/evaluation, and
   every required human/system wait using the target lane.
5. Restart Python after a checkpoint, restart the agent worker after model completion, and restart
   Java after a committed Domain transaction but before response delivery; prove recovery without
   duplicate cognition, message, Artifact, operation, or external effect.
6. Reconnect SSE and reload every browser view from Java projections.
7. Execute the Domain, Graph, Temporal, security, and negative-default assertions below.
8. Close admission, drain accepted work, revoke the activation, and prove a new command is rejected.

## Required Database Assertions

Evidence records exact SQL text, result rows, row counts, and hashes. Queries are scoped by the
manifest tenant, case, activation, environment generation, and candidate.

**Activation/control ledger:** exactly one activation row exists for the five-part registration
identity `(environmentId, environmentGeneration, activationId, nonce, manifestHash)`;
its candidate, environment, Build, image, Graph, namespace, and database hashes match the manifest;
the terminal lifecycle is `DRAINED`; identical replicas attach to that row and no second
nonce-consumption row exists. Activation ID and nonce cannot arm a different environment,
generation, manifest hash, or binding set. For browser-created
synthetic cases, each generated ID has exactly one atomic reservation below the signed capacity,
matches the exact prefix, and is bound before its first room epoch; no seventeenth slot or wildcard
match exists.

**Domain PostgreSQL:** `case_room_epoch` contains the complete ordered
`INTAKE/EVIDENCE/HEARING/REVIEW` set with `writer_mode=TEMPORAL`, READY/terminal provisioning,
non-null Case and Room Workflow/Run IDs, pinned Build/Graph/checkpoint values, and monotonic fences.
No target case epoch is `LEGACY` or `SHADOW`.

`case_command` and `case_command_outbox` contain no nonterminal or dead-letter row;
`room_epoch_bootstrap_outbox` is fully delivered; `case_process_projection` equals the highest
accepted process revision. Every `agent_run` uses `agent-stream.v2` and
`executor_kind=TEMPORAL_ACTIVITY`; each logical run has bounded attempts, one terminal result, one
immutable `agent_execution_manifest`, and committed Java finalization. No target run uses
`LEGACY_WORKER`.

Room facts and receipts are append-only and causally bound: Intake dossier/message commit,
`case_evidence_finalization_receipt`, `hearing_domain_receipt`, reviewer decision,
`outcome_operation_receipt`, closed snapshot, and evaluation all match the same tenant/case,
activation, epoch/fence/revision/stage, command/envelope hash, Graph checkpoint, result/proposal
hash, AgentRun manifest, isolated Domain database binding, and Java receipt chain. Duplicate formal
message, Artifact, decision, operation, receipt, closure, or evaluation counts are zero.

**Graph PostgreSQL:** `agent_graph_version_registry` has exactly the manifest binding;
`agent_graph_command`, `agent_graph_command_attempt`, and `agent_graph_result` have one causal row
per logical dispatch with `execution_lane=TARGET_E2E_CANDIDATE`, the same activation ID, and matching
command/result hashes. Every completed command has a durable checkpoint and result. No unresolved
command, active `agent_graph_lease`, conflicting hash, cross-tenant/case thread, or unreferenced
checkpoint remains.

Database grants prove the Python/Graph runtime principal has no CONNECT, schema usage, table,
sequence, or function privilege in Domain PostgreSQL, and the Java Domain runtime principal has no
Graph checkpoint/ledger write privilege. No credential or connection string is present in Graph
state, Temporal History, result, receipt, log, trace, or evidence artifact.

**Temporal:** public Workflow APIs show the exact namespace, Build IDs, Workflow/Run IDs, typed
children, closed histories, and no stuck task/update/signal. History contains references, hashes,
revisions, epochs, fences, and deadlines only; it contains no prompt body, private reasoning,
credential, binary evidence, or unrestricted URL.

## Hard Failures

Any wrong or mixed binding, nonce replay, future/overlong/expired capability, double writer,
legacy executor, lost accepted command, duplicate formal fact/effect, stale-fence success,
unrecoverable checkpoint, cross-scope read, Graph Domain access, unapproved effect, unresolved
ledger/outbox/lease, or production-default mutation fails the checkpoint. A rerun cannot erase the
failed attempt; classify it as `PRODUCT`, `FIXTURE`, `INFRA`, `CONTRACT`, or `EXTERNAL_GATE` first.

## Rollback And Drain

1. Set activation lifecycle to `DRAINING` and reject new cases/commands.
2. Keep exact compatible control/agent/Graph workers available for already accepted work.
3. Drain Temporal updates/Activities, Java outboxes/finalizers, and Graph commands/leases to a
   bounded deadline while preserving immutable receipts.
4. If drain fails, suspend at a safe boundary and require manual reconciliation; never hand an
   active epoch to LEGACY.
5. Record `DRAINED` or `REVOKED`, stop target-only workers, and retain histories, databases,
   manifests, proposals, receipts, and evidence.
6. A retry uses a higher environment generation and a newly signed activation ID and nonce.

## Exit Decision

The maximum successful result is:

```text
P9.0: PASS
TARGET_ARCHITECTURE_PREPRODUCTION_E2E: PASS
production_checkpoint: PENDING_EXTERNAL
promotion_gate: PENDING
MIG-003..008: PENDING_PROMOTION
next_permission: SEPARATELY_AUTHORIZED_EXTERNAL_PRODUCTION_CHECKPOINT_ONLY
```

Failure yields `P9.0: FAIL`, target activation disabled, and `next_permission: BLOCKED`. Neither
result authorizes production deployment, canary, migration promotion, or destructive cleanup.
