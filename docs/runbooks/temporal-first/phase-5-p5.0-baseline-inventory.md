# Phase 5 P5.0 Evidence Baseline Inventory

## Status

```text
inventory_status: FROZEN_AT_ENTRY_EXCEPTION_BASE
observed_commit: d6f66d6d8634aac20b77b9b66a22cbb77370c4fe
contract_gate: P5.0 NOT_RUN
engineering_execution_at_observed_commit: BLOCKED_PENDING_PHASE_4_ENGINEERING_CHECKPOINT
current_phase_4_engineering_checkpoint: PASS
current_engineering_execution: BLOCKED_PENDING_P5_0_ENTRY_EVIDENCE
candidate_scope_integrity: REPAIRS_CLASSIFIED_REQUIRES_FRESH_EXACT_SHA_BATCH_0
promotion_gate: PENDING
MIG-004: PENDING_PROMOTION
MIG-005: PENDING_PROMOTION
runtime_default: DISABLED
formal_evidence_writer: JAVA_DOMAIN_POSTGRESQL_ONLY
```

This inventory records facts at the ADR 0012 engineering-exception commit. It is not an entry
evidence bundle, an implementation authorization, a production-capacity result, or a promotion
approval. The historical observation predates the accepted Phase 4 checkpoint; current gate state
is recorded separately above so the old `P5-G0` fact cannot be mistaken for a current blocker. The
authoritative constraints remain:

- [`phase-5-evidence-pilot-execution.md`](../../../plans/phase-5-evidence-pilot-execution.md)
- [`phase-5-evidence-pilot-test-batches.yaml`](../../../plans/phase-5-evidence-pilot-test-batches.yaml)
- [`phase-5-p5.0-contract-pack.md`](./phase-5-p5.0-contract-pack.md)
- [`0012-phase-5-evidence-engineering-exception.md`](../../architecture/adr/0012-phase-5-evidence-engineering-exception.md)
- [`phase-5-p5.0-review-closure.md`](./phase-5-p5.0-review-closure.md)

## Immutable Product Boundaries

| Surface | Baseline | Phase 5 engineering rule |
| --- | --- | --- |
| Public Evidence submission | `EvidenceSubmissionRequest.evidenceIds` is `@Size(min = 1, max = 50)` | Remains 1-50 until separate product/API/frontend approval |
| Closed synthetic Evidence manifests | No P5 contract exists yet | May exercise exactly 1, 8 and 100 items after P5.0, never through the public endpoint or a formal sink |
| Hearing supplementation | `HearingEvidenceBatchRequest.evidenceIds` is `@Size(max = 50)` and dossier validation repeats the 50-file bound | Remains 0-50 per party and is outside Phase 5 |
| Formal Evidence truth | Java services and Domain PostgreSQL | Graph produces proposals only |
| Runtime | Existing `LEGACY` path | New Graph code remains `DISABLED` or Java-signed synthetic `SHADOW` |

The existing 100-card Vue and Playwright fixtures are rendering-capacity evidence only. They do
not approve a 100-file public request. `EvidenceRoomView.submitPendingBatch()` currently sends all
pending IDs in one request and relies on the Java request validator for the public 50-item limit;
there is no public 100-item API contract or compatible batching protocol.

## Java Baseline

### Submission And Catalog

| Concern | Current implementation | Preserved invariant or gap |
| --- | --- | --- |
| HTTP submission | `evidence/api/EvidenceSubmissionRequest.java`, `EvidenceController.submitBatch` | Bean validation enforces 1-50 before application code |
| Transactional submission | `EvidenceSubmissionService.submit/createSubmission` | Locks the case, authenticates a party, deduplicates IDs in order, checks case/owner/pending status, persists one idempotent batch, marks items submitted, posts one room reference and audits |
| Hearing reuse | `EvidenceSubmissionService.submissionRoom` | The legacy service posts to `HEARING` when the case is already there; Phase 5 must not change this supplementation behavior |
| Upload and object metadata | `EvidenceApplicationService.upload` | Persists object location/hash/type/size/visibility, declaration and optional per-item model-processing authorization |
| Read models | `EvidenceCatalogService`, `EvidenceDossierQueryService` | Actor-scoped catalog and frozen dossier readers remain compatibility baselines |

`EvidenceSubmissionCommand` is an internal application record and does not itself establish a new
100-item contract. Closed synthetic manifests must use their own versioned contract and cannot be
routed through this command as if public approval existed.

### Completion, Timer And Hearing Transition

| Concern | Current implementation | Baseline behavior |
| --- | --- | --- |
| Party completion | `EvidenceCompletionService.complete` | Participant/idempotency keyed completion; respondent may complete with zero evidence; initiator needs at least one formally submitted item |
| Early seal | `EvidenceCompletionService.complete` | When both participants complete, Java freezes the dossier, seals Evidence, opens Hearing, allocates the room transition, emits lifecycle/notification effects and starts Hearing |
| Expiry | `EvidenceCompletionService.expire` | Java freezes and performs the same formal transition on deadline expiry |
| Existing timer | `EvidenceWindowWorkflowImpl` | Legacy Workflow ID `evidence-window-{caseId}`, legacy task queue, original window, warning 30 minutes before expiry, idempotent role set |
| Signal delivery | `EvidenceWindowCoordinator` | Start and completion Signal are post-commit side effects; this is not the future typed Evidence child command loop |

The current completion service owns too many formal transition effects for the target architecture.
P5 must introduce mode-aware receipts and deterministic ordering without enabling a new formal
writer under ADR 0012. Existing active workflows remain `LEGACY`; a future `EvidenceRoomWorkflow`
cannot share timer ownership for the same case/epoch.

### Freezer, Verification And Agent Turn

| Concern | Current implementation | Baseline behavior |
| --- | --- | --- |
| Dossier freeze | `EvidenceDossierFreezer.freeze/createFrozen` | Idempotent by case/version; excludes latest `REJECTED` verification, snapshots accepted items, scores, timeline, fact links, review tasks and handoffs |
| Formal assessment writes | `EvidenceAgentTurnService.persistEvidenceAssessments` | Java creates versioned verification rows and applies human-review/low-relevance/authenticity policy |
| Matrix and memory | `EvidenceAgentTurnService` | Persists room-turn memory, validates frozen matrix scope, consumes agent patches and freezes a new dossier for Hearing supplementation |
| External agent call | `RestClientEvidenceAgentTurnClient` | Calls `/internal/agents/evidence/turn`; rejects unsafe schema and final liability/remedy claims |

The future Java Finalizer must validate complete manifest membership, hashes, epoch/fence, actor
scope and version pins before exactly one ACID merge. It cannot trust the current one-turn response
as a terminal 100-item batch proposal.

### Current Asset Boundary

The current path is useful but is not the P5 production capability:

1. Python `EvidenceAssetLoader` calls `GET /internal/evidence/{caseId}/{evidenceId}/content`.
2. `InternalEvidenceController` authenticates an `X-Service-Secret` and a SYSTEM principal.
3. `EvidenceApplicationService.contentForModel` rechecks case membership, reader authority and
   either desensitization or persisted `model_processing_authorized=true`.
4. Python permits only internal Java hosts, image MIME/magic agreement, at most three images,
   4 MiB per image and 10 MiB total, and matching declared/actual SHA-256.
5. The returned asset manifest distinguishes `LOADED` pixels from metadata-only items.

Missing for P5 production authorization: a signed capability tied to the exact batch manifest,
tenant/case, Evidence epoch/fence, owner, visibility, immutable object version, MIME/size/hash,
expiry/nonce and actual-load receipt. ADR 0012 therefore permits only immutable synthetic fixtures
and Java-signed synthetic capabilities; party data and production object references remain closed.

## Persistence Baseline

| Migration | Existing facts |
| --- | --- |
| `V002` + `V007` | Evidence dossier/items, evidence requests/submissions and claim-evidence links; dossier uniqueness becomes case plus version |
| `V008` | Immutable `evidence_dossier_item` snapshots and final governance tables |
| `V011` + `V036` | Versioned verification and party completion; completion uniqueness is case/version/participant ID |
| `V022` + `V023` | Idempotent submission batches, submission status and active file-hash uniqueness |
| `V040` | Generic immutable payload snapshots and execution manifests; legacy Evidence objects are backfilled when hash/object refs are resolvable |
| `V043` through `V043_3` | Assigned to Intake Graph/authority/comparison/signed-synthetic admission |

`V043_4__evidence_graph_bindings.sql` does not exist at the observed commit. It is the only reserved
Phase 5 additive migration identity. Older migrations must never be edited, and the generic V040
snapshot tables do not substitute for an Evidence manifest/finalizer binding.

## Python Baseline

| Area | Current implementation | P5 gap |
| --- | --- | --- |
| Graph | `agents/evidence_clerk/workflow.py` compiles `load_context -> reason_with_llm -> apply_authenticity_guardrails` | One synchronous turn, one model call, no durable saver, registry, command ledger, recovery or deterministic 1/8/100 waves |
| State | `EvidenceTurnGraphState` carries `memory_frame` and one-turn output fields | Not the P5 versioned cognitive state; response still returns `memory_frame` |
| Context | `EvidenceContextAssembler` validates actor/session/attachments and projects at most 20 visible items to the prompt | Not an immutable 100-item manifest scheduler |
| Output | `EvidenceTurnLlmOutput.evidence_assessments` and human-review lists are capped at 50 | Cannot prove complete 100-item terminal coverage |
| Assets | `EvidenceAssetLoader` enforces current secret/MIME/size/hash/privacy gates | Lacks P5 signed manifest/epoch/fence/object-version capability |
| Reducer | Generic `graph_runtime.reducers.merge_keyed_json` has associativity/order/idempotency/conflict property tests | Not wired to Evidence IDs or a terminal batch proposal |

There is no `python-agent-service/app/graphs/evidence/**` implementation and no
`contracts/agent-platform/evidence/v2/**` contract at this baseline. References to `evidence.v2`
inside generic epoch/recovery fixtures are compatibility placeholders, not an executable Evidence
Graph.

## Frontend Baseline

`EvidenceRoomView.vue` already provides actor-scoped catalog views, upload declaration, pending and
submitted rails, human-review details, completion gating, server deadline display, active AgentRun
recovery, role-switch stale-response protection and read-only history mode. Existing tests include:

- component rendering of 100 USER and 100 MERCHANT submitted cards in bounded rails;
- Playwright layout of the same 100-card fixtures;
- public API request shape and idempotency for a one-item batch;
- initiator-empty completion gate and respondent-independent completion;
- low relevance distinct from suspected forgery;
- reviewer visibility, accessibility, active run, role switch, late response and history locks.

Missing P5 evidence includes a versioned projection contract, synthetic-only 100-item mode marker,
1/8/100 API/store fixtures, public-limit-50 negative tests, complete terminal coverage status and
future Graph run/recovery fields. The existing 100-card fixtures must be preserved but relabeled as
disabled/synthetic rendering evidence until approval.

## Baseline Test Map

| Capability | Existing focused suites | What they do not prove |
| --- | --- | --- |
| Submission/catalog | `EvidenceSubmissionServiceTest`, `EvidenceApiIntegrationTest`, `EvidenceCatalogServiceTest` | 100-item public approval or signed manifests |
| Completion/window | `EvidenceCompletionServiceTest`, `EvidenceWindowWorkflowTest`, `EvidenceWindowCoordinatorTest` | Typed child kill/replay, immutable command order or selector cutover |
| Freezer/verification | `EvidenceDossierFreezerTest`, `EvidenceVerificationAndCatalogServiceTest`, `EvidenceAgentTurnServiceTest` | Exactly-once 100-item proposal merge after Graph recovery |
| Asset access | `EvidenceApplicationServiceTest`, `InternalEvidenceControllerTest`, Python asset-loader cases | P5 production capability, object version or epoch/fence binding |
| Clerk/guardrails | `test_evidence_clerk_turn.py`, `test_evidence_fact_mapping_policy.py` | Durable `evidence.v2`, Send waves, keyed batch recovery or 1/8/100 coverage |
| Generic reducer | `test_reducers.py` | Evidence-specific membership and proposal hash |
| UI/API | `EvidenceRoomView.test.js`, `evidence.test.js`, `evidence-room.layout.spec.js` | Public 100 approval or backend 100 submission acceptance |

## Post-Baseline Candidate Repairs

The accepted Phase 4 candidate `1ba6e17fa2182156825f42d7e243978cf23ccdb4` and evidence commit
`b8697ce7a46f4494d250d21f27a076f0711ae04d` close the historical handoff gap. Contract preparation
then exposed only entry-path baseline defects, not permission to implement Evidence v2:

- `99cdd435` and `d76fde17` make handoff authentication and generated evidence stable across
  checkout line endings.
- `24a705dc` isolates adversarial architecture fixtures from Spring component scanning.
- `a3be6744`, `e97e1341`, and `fb69bd4c` remove class-level `final` from three existing Spring
  beans while retaining transaction propagation, persistence order, idempotency and Java authority.
- `c9e6c7ba` preserves long Surefire report identity through deterministic short retained paths.
- Main commit `b9201f0bc1d9ad7fca1cc0ca7b68cd75e62a503a`, tree-equivalent to source commit
  `79b8c797522671aa46f2299198eab7ba6f651006`, contains a `FIXTURE` correction that transitions
  the diagnostic case from completed Intake to `EVIDENCE_OPEN` through the domain admission method,
  uses audited case-scoped cleanup, rebuilds the case unconditionally, and scopes its Evidence count.
- The same exact commit contains a separate baseline `PRODUCT` repair: response mapping converts a
  non-null `occurred_at` to the same instant at UTC while preserving null. This prevents immediate
  create and PostgreSQL reload views from returning different offsets for the same accepted item;
  it does not change the stored instant, authorization, formal writer, runtime mode, or promotion.

These changes are admissible only as the bounded candidate-scope repair set documented in the
execution plan. They do not update this inventory's historical product observations, do not grant
engineering execution, and require a fresh exact-SHA Batch 0 after the complete set is integrated.
The 27/27 focused repair checks bound to `b9201f0b` / source `79b8c797` are diagnostic repair
evidence, not P5.0 PASS.

## P5-G0 Through P5-G10 Resolution Map

| Gate | Classification after ADR 0012 | Resolution owner/task | Required closure |
| --- | --- | --- | --- |
| `P5-G0` Phase 4 handoff | Closed for engineering entry | R / `P5-0` | Candidate `1ba6e17f` and evidence `b8697ce7` grant `PHASE_5_ENGINEERING_ONLY`; keep the authenticated artifact immutable |
| `P5-G1` MIG-004 promotion | Promotion blocker, not synthetic engineering entry | External promotion authority | Keep `MIG-004=PENDING_PROMOTION`; no local substitution |
| `P5-G2` 100-file public contract | Public activation blocker | D0 preserves 50; A/C/D use closed synthetic fixtures; product/API/frontend approval is external | Public remains 1-50; synthetic 1/8/100 is visibly non-public and no-sink |
| `P5-G3` bulkheads | Phase 5 engineering exit obligation | E0 harness, E1 implementation; A supplies graph integration | `GRAPH-016` room/tenant/global permits, bounded queues, fairness, cancellation/recovery evidence |
| `P5-G4` production assets | Production promotion blocker with an engineering subset | C1 signed synthetic authority; E0 no-sink harness | Synthetic capability proof now; production identity/object authorization later |
| `P5-G5` Evidence contracts | Engineering foundation | R contract freeze; A/C parity | Closed schemas, fixtures, canonical hashes and cross-language parity |
| `P5-G6` Evidence bindings | Engineering foundation | C1/C2 | Add only `V043_4`, idempotent receipts and formal Java ownership |
| `P5-G7` transition authority | Engineering implementation, activation forbidden | B1/B2 timer kernel; C2 receipts/Finalizer | Deterministic ordering and committed Java receipts; legacy stays active |
| `P5-G8` durable graph | Engineering implementation | A1/A2 | Versioned `evidence.v2`, 1/8/100 waves, recovery and keyed terminal proposal |
| `P5-G9` selector/no-sink | Engineering implementation, promotion separately blocked | E0 static harness; E1/E2 selector/parity/recovery | Fail-closed Evidence selector, signed synthetic SHADOW only, formal sink unreachable |
| `P5-G10` candidate-scope integrity | P5.0 entry blocker | R / final candidate freeze | Integrate only the classified repair set, review the final diff, and pass Batch 0 on one fresh exact clean detached SHA |

## Ownership And Parallelism Review

The machine plan correctly defines one primary (`R`) and five active implementation owners
(`A`-`E`) in both implementation waves. D0 and E0 are genuinely independent foundations:

- `P5-D0` depends only on `P5-0` and owns compatibility projections plus baseline API/UI fixtures.
- `P5-E0` depends only on `P5-0` and owns bulkhead/no-sink/metrics/synthetic harness contracts.
- Neither may depend on A2/B2/C2; D1 and E1 carry those later dependencies.

Before delegation, R must narrow two intentional wildcard overlaps into exact file grants:

1. A owns `tests/graphs/evidence/**` while E may own only named recovery tests.
2. E and R both name `tests/static/test_phase5_*.py`; R retains shared contract/candidate tests and
   grants E only explicit no-sink/bulkhead harness files.

D must not edit formal Evidence rules or Hearing supplementation. E must not activate a formal
sink, secrets, deployment or another room migration. C must not edit Hearing or older migrations.
B must not implement Domain repositories. These boundaries are conditions of safe parallelism,
not suggestions.

## Non-Claims

This inventory does not claim P5.0 entry, `GRAPH-016` closure, public 100-file compatibility,
production asset authorization, a formal Finalizer, `TEMPORAL` Evidence allocation, real shadow,
load/soak/failover/DR, canary, `MIG-004=PASS`, `MIG-005=PASS` or production readiness. It also does
not treat any pre-final-candidate diagnostic run as P5.0 PASS.
