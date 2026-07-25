# Phase 8 P8.0 Contract Review Closure

## Review State

```text
review_status: AWAITING_INTEGRATED_P0_REVIEW
review_scope: P8_0_CONTRACT_CANDIDATE_ONLY
entry_decision: NOT_DECIDED
P8.0: NOT_RUN
engineering_execution: BLOCKED_PENDING_P8_0_ACCEPTANCE
production_checkpoint: PENDING_EXTERNAL
promotion_gate: PENDING
MIG-003: PENDING_PROMOTION
MIG-004: PENDING_PROMOTION
MIG-005: PENDING_PROMOTION
MIG-006: PENDING_PROMOTION
MIG-007: PENDING_PROMOTION
MIG-008: PENDING_PROMOTION
real_case_or_party_data: FORBIDDEN
```

Despite the filename, this document does not claim review closure. It is the frozen question and
disposition register that integrated reviewers must evaluate against the complete P8.0 candidate.
No question below is `PASS`, `CLOSED`, or accepted. A status changes only in separately reviewed
evidence or acceptance material bound to the exact frozen candidate.

The accepted handoff is exactly:

```text
C7: 4ddeeabb39ce7b7de41ecc4f44e17ece389d2840
E7: f1c1ca16228641f1072eb358c6df9235dc239914
A7: e3acedc64d161f0342c8db3d5c313c2f404ea462
phase_7_tests: 149 static + 22 Python + 276 Java + 60 frontend = 507
phase_7_engineering_checkpoint: PASS
next_phase_permission: PHASE_8_ENGINEERING_ONLY
```

Those counts and decisions are Phase 7 handoff evidence only. They answer no Phase 8 P0 question.

```text
C8: SOLE_PARENT_DIRECT_CHILD_OF_A7
E8: SOLE_PARENT_DIRECT_CHILD_OF_C8
A8: SOLE_PARENT_DIRECT_CHILD_OF_E8
ONLY_A8_MAY_RECORD_P8_0_PASS: true
```

## P0 Contract Questions

| ID | Integrated P0 question | Required disposition evidence | Status |
| --- | --- | --- | --- |
| `P0-P8-HANDOFF-001` | Does the candidate authenticate the exact sole-parent `C7 -> E7 -> A7` chain, A7 path scope, 507-test Phase 7 evidence, and the unchanged `PENDING_PROMOTION` state of MIG-006/007? | Git-object ancestry, tree/path diff, sealed Phase 7 manifest/hash validation, and explicit no-relabel assertion. | `AWAITING_INTEGRATED_REVIEW` |
| `P0-P8-ENTRY-TOPOLOGY-002` | Is the exact topology A7 -> sole-parent exact-twelve-path-allowlist C8 with no implementation or self-PASS -> Batch 0 from clean detached exact-SHA C8 -> sole-parent evidence-only E8 with no release decision -> sole-parent checkpoint-only A8, with only A8 allowed to record P8.0 PASS and release engineering owners? | Exact 12-path C8 allowlist; clean-tree and fixed local Git/Python `shell=false` proof; E8's exact 12 regular blobs under one `phase-8-entry` prefix; `artifact-sha256.json` indexing exactly the other 11 with extras/missing/non-blobs rejected; E8 result ceiling `PASS_AWAITING_CHECKPOINT_A8` and permission `PENDING_A8_CHECKPOINT`; all sole-parent checks; and A8 checkpoint validation. | `AWAITING_INTEGRATED_REVIEW` |
| `P0-P8-BASELINE-003` | Does the baseline distinguish verified A7 facts from stale `3eebc36b` observations and require exact-candidate re-audit instead of carrying old values forward? | Per-observation provenance/classification plus Batch 0 commands and fail-closed results for current defaults, routes, schemas, services, and suites. | `AWAITING_INTEGRATED_REVIEW` |
| `P0-P8-REFERENCE-004` | Does the audit cover Temporal Workflow type/Build ID/room epoch; `case_room_epoch` writer/Workflow-build/Graph/stream pins; pending `case_command`/outbox/`domain_operation`/Finalizer work; nonterminal V1 AgentRun logical/attempt rows and hot readers; object-store manifests; retained-window legacy endpoints; `agent_stream.v1` telemetry; and every old runtime reference; do `UNKNOWN`, `PARTIAL`, and `ERROR` always produce `BLOCK_DELETE`; and does eligibility require two complete zero windows plus continuous full-interval no-new-reference proof? | Closed schema, authoritative adapter inventory, pagination/high-watermark tests, producer/selector/deployment quiescence and a continuous monotonic no-new-reference ledger/high-watermark between scans, lag bounds, query/report hashes, and adversarial query/permission/lag failure fixtures. | `AWAITING_INTEGRATED_REVIEW` |
| `P0-P8-SCHEDULER-005` | Does the contract separate the three legacy executors (`AgentRunRecoveryScheduler`, Hearing deadline, Hearing review handoff) from retained outbox/control-recovery/SSE/Activity-heartbeat schedules; avoid blanket retirement; represent Temporal writer + OFF without regression; enumerate exact V1+LEGACY would-be candidates even without a projection; and limit detector to proposals/observation with no formal reconciliation, mutation, enqueue, phase advance, or time ownership? | Named classification with unknown -> `BLOCK_DELETE`, transition representability, one-executor/epoch-fence tests, executor-equivalent candidate-set and missing-projection tests, mutation-free detector proof, per-owner observation/rollback contract, and release-only OFF assertion. | `AWAITING_INTEGRATED_REVIEW` |
| `P0-P8-V046-006` | Is V046 delivery-storage-only, leaving Java plus the Domain PostgreSQL transaction/Finalizer as the sole formal commit boundary; unable to treat stream `final`, HWM, or archive receipts as business completion; additive and concurrency-safe; globally identity-fenced; exact in V1/V2 replay and actor-audience semantics; PostgreSQL/Java authoritative; and barred from real engineering apply/switch? | Disposable-DB migration/restart/conflict/dual-write/replay and formal-authority negative tests; global identity plus highest-contiguous HWM proof; compatible rollback; and a rule that future `MIG-008=PASS` binds the same release candidate/checkpoint to accepted separately authorized expand/apply, backfill/HWM, capture/dual-write, parity/archive, reader-switch, writer-switch/rollback, and old-store retention receipts. | `AWAITING_INTEGRATED_REVIEW` |
| `P0-P8-V047-007` | Is V047 absent from P8.0 and engineering, with authoring deferred until `MIG-000..008=PASS`, GATE-001..010, two zero scans, retention, restore/rollback, and six signatures are real? | Repository absence/static no-delete checks plus a separate cleanup authorization and candidate topology contract. | `AWAITING_INTEGRATED_REVIEW` |
| `P0-P8-RELEASE-008` | Can any local, mock, rendered, synthetic, or repository result be mislabeled as production load, chaos, security, replay, PITR, DR, rotation, soak, canary, or promotion evidence? | External-evidence schema and adversarial intake tests showing missing operator, environment, deployment, credential, window, signature, or report remains `EXTERNAL_GATE`. | `AWAITING_INTEGRATED_REVIEW` |
| `P0-P8-RECOVERY-009` | Do rollback and recovery preserve formal facts, additive stores, compatible History/checkpoint/payload reads, and external-effect receipts without direct internal-table edits or blind replay? | Ordered recovery contract, fail-closed runner tests, idempotency/compensation rules, object/version hashes, rotation compatibility, and immutable receipt checks. | `AWAITING_INTEGRATED_REVIEW` |
| `P0-P8-PRIVACY-010` | Do audit, telemetry, load, and evidence contracts prevent secrets, PII, raw evidence, hidden reasoning, and high-cardinality identifiers; require concrete KMS/Vault-reference, workload-identity, least-RBAC, default-deny NetworkPolicy, mTLS, and private immutable/versioned object ACL/audit assets/tests; permit only fixed local Git/Python `shell=false` argv in Batch 0; and keep recovery/DR/rotation dry runs free of network/subprocess/cloud/DB/Temporal/secret-env access? | Exact security manifest/test paths, closed schemas, label/cardinality lint, cross-scope fixtures, secret scans, policy lint, immutable object-version/ACL/audit proof, Batch 0 argv allowlist tests, operational dry-run denial tests, and owner/runbook wiring. | `AWAITING_INTEGRATED_REVIEW` |
| `P0-P8-TEAM-011` | Does the adaptive 1+11 topology preserve five disjoint implementation owners, three P0 lanes, two verification lanes, one lookahead lane, exact path ownership, and continuous P0 coverage? | Owner brief/schema validation, overlap checks, activation/backfill policy, and at-least-50%-P0-in-flight rule. | `AWAITING_INTEGRATED_REVIEW` |
| `P0-P8-TEST-LIMITS-012` | Do scheduling contracts cap light tests at two concurrent processes, reserve one Maven/Testcontainers lane, use focused checks during editing, and defer full/E2E verification to the unified checkpoint? | Machine schedule resource classes, token/lease checks, dependency ordering, and no duplicate full-suite commands in owner batches. | `AWAITING_INTEGRATED_REVIEW` |
| `P0-P8-AUTHORITY-013` | Do all documents preserve Java/Domain as sole formal ledger, Temporal process time/failure only for approved epochs, Graph private cognition with no Domain sink or long wait, formal Intake/Evidence/Hearing/Outcome legacy while MIG-003..007 are pending, and forbid scheduler OFF, real V046 apply/switch, V047, real production exercises, canary, and promotion? | Cross-document authority/marker validation and negative tests covering every forbidden action and decision ceiling. | `AWAITING_INTEGRATED_REVIEW` |

## Review Disposition Rules

- Each question is reviewed against the integrated frozen diff, not an owner's isolated draft.
- Review evidence names the reviewer lane, candidate SHA, reviewed paths, finding IDs, disposition,
  and evidence hashes. An author cannot close the only review of their own contract.
- Any unresolved authority, transaction, Temporal determinism, migration, destructive cleanup,
  security, privacy, evidence-integrity, rollback, or external-gate ambiguity is P0 and blocks C8.
- A proposed wording fix creates a new candidate when it changes a frozen contract. The prior
  Batch 0 result cannot follow the changed bytes.
- A reviewer may record `NOT_APPLICABLE` only with a concrete contract reference and independent
  concurrence; silence or missing evidence is not closure.
- A 429, 503, or tool failure preserves the open finding and partial work. It never lowers review
  depth, model capability, or the required P0 coverage.
- The P8.0 SHA-256 self-seal proves byte integrity and drift detection only. It is not a signer,
  operator, source, or execution-authenticity proof. P8.0 assumes a
  `NON_HOSTILE_LOCAL_ENGINEERING_OPERATOR`; malicious local-administrator resistance remains
  outside this engineering gate.
- The consolidated disposition is an unsigned engineering process attestation. It must close all
  13 topics through the fixed `authority`, `data_migration`, and `security_privacy` lanes, bind each
  distinct reviewer to the exact C8 tree, twelve-path diff/blob hashes, manifest/report hashes and
  assigned topics, and record `self_approved: false`.
- Local seals and process receipts cannot satisfy or be relabeled as production evidence. Real
  cryptographic execution/operator/environment/deployment attestation remains `EXTERNAL_GATE`.

Post-commit review is P0-only. Review lanes remain in flight while implementation proceeds after
entry, with at least half of planned P0 review active as stable diffs become available. Reviewers
do not edit implementation-owned paths and do not substitute for the five implementation owners.

## Engineering Versus Release Review

Integrated P0 review may close the contract questions and allow Batch 0. It cannot close release
facts that do not yet exist. The following remain release-lane evidence even after all contract
questions are closed:

The real release lane remains closed until `MIG-000..007=PASS`, all 99 behavior baselines are
current, and no P0 is open. A7 and P8.0 provide engineering permission only. V047 requires the
additional `MIG-008=PASS`, so its migration prerequisite is `MIG-000..008=PASS`.

- real scheduler observation and `DETECTOR -> OFF` authorization;
- production V046 apply, backfill, validation, reader/writer switch, and observation;
- production-equivalent three-failure-domain deployment, load, burst, chaos, and failover;
- security fuzz, real rotation, Domain PITR, Temporal regional DR, Graph/object restore,
  reconciliation, and 24-hour soak;
- six-role signatures, `GATE-001..010`, migration promotion, canary, and promotion; and
- any separate V047 authoring, apply, switch, or destructive cleanup decision.

Reviewing a scenario, runner, manifest, runbook, or validator proves only engineering readiness to
collect that evidence. It does not prove the operation ran.

## Current Decision

```text
integrated_p0_review: NOT_RECORDED
P8.0: NOT_RUN
P8_0_ENGINEERING_ENTRY_PASS: NOT_ISSUED
implementation: BLOCKED
engineering_checkpoint: NOT_RUN
production_checkpoint: PENDING_EXTERNAL
promotion_gate: PENDING
MIG-006: PENDING_PROMOTION
MIG-007: PENDING_PROMOTION
MIG-008: PENDING_PROMOTION
```

The next valid action is integrated P0 review of the complete contract candidate, followed by a
fresh exact-candidate Batch 0, sole-parent evidence-only E8, and sole-parent checkpoint-only A8 if
every gate passes. Only A8 may record P8.0 PASS or release engineering owners. This
register does not authorize scheduler `OFF`, real V046 apply or switch, V047, production traffic,
load, chaos, PITR, DR, rotation, canary, promotion, or any migration pass.
