# Phase 5 P5.0 Independent Review Closure

## Disposition

```text
review_status: CLOSED_WITH_BLOCKERS_CLASSIFIED
review_basis_commit: d6f66d6d8634aac20b77b9b66a22cbb77370c4fe
contract_gate: P5.0 NOT_RUN
engineering_execution: BLOCKED_PENDING_PHASE_4_ENGINEERING_CHECKPOINT
promotion_gate: PENDING
MIG-004: PENDING_PROMOTION
MIG-005: PENDING_PROMOTION
```

This closes the independent document and repository-fact review requested before the final P5.0
candidate. It does not close `P5-G0`, authorize implementation, or turn an engineering exception
into a promotion exception. The reviewed fact set is
[`phase-5-p5.0-baseline-inventory.md`](./phase-5-p5.0-baseline-inventory.md).

## Reviewed Inputs

- ADR 0012, Phase 5 execution plan, machine test batches and P5.0 contract pack.
- Java Evidence submission, upload/catalog, completion, legacy window Workflow, dossier freezer,
  asset endpoint, Agent turn and migrations through `V043_3`.
- Python Evidence clerk, context assembler, asset loader, generic reducer and focused tests.
- Vue Evidence room, API/store surface, component/browser fixtures and current 100-card rendering.
- Phase 5 owner routes, dependency graph, one-primary/five-owner resources and D0/E0 contracts.

The review is based on source inspection at the exact commit above. No product code, contract,
migration, runtime mode, Phase 4 artifact, browser flow, load test or production system was changed
or exercised by this closure.

## Findings And Decisions

### R0: Engineering Exception Is Narrow And Coherent

**Disposition: ACCEPT.** ADR 0012 resolves the circular engineering-entry dependency without
representing pending external gates as passed. `P5-G1`, public activation in `P5-G2`, and production
activation in `P5-G4` stay outside repository-only engineering. `P5-G3` moves to the Phase 5 exit
where E1 can actually implement and prove it.

### R1: P5-G0 Still Blocks Candidate Freeze

**Disposition: BLOCK UNTIL UPSTREAM EVIDENCE.** At the review basis commit, there is no accepted
Phase 4 engineering checkpoint granting `PHASE_5_ENGINEERING_ONLY`. R may prepare facts and review
closure, but may not freeze the final P5.0 candidate, run Batch 0 or delegate implementation until
that immutable handoff exists.

### R2: 50/100 Product Boundary Is Explicit

**Disposition: ACCEPT WITH ENFORCEMENT.** The public Evidence request remains 1-50. Closed schemas,
tests and Java-signed synthetic fixtures may cover 1, 8 and 100 only after P5.0. Existing Vue and
Playwright 100-card fixtures prove layout capacity, not public submission approval. Hearing
supplementation remains a separate 0-50 per-party contract and is forbidden to Phase 5 owners.

D0 must add compatibility/negative fixtures that make those modes distinguishable. No owner may
raise `EvidenceSubmissionRequest` or `HearingEvidenceBatchRequest` to 100 under ADR 0012.

### R3: Java Remains The Formal Truth Boundary

**Disposition: ACCEPT WITH TARGETED REFACTOR.** Current Java services perform formal submission,
verification, dossier freeze, completion, transition and Hearing start. P5 may place deterministic
time/order in a new Workflow and cognition in Graph, but only committed Java receipts may merge or
advance formal state. Graph PostgreSQL and Domain PostgreSQL credentials remain mutually isolated.

C2 must make the future merge complete, idempotent, fence-bound and transactional. B2 consumes
receipts; it does not recreate business truth in Workflow code. E0/E1 must prove no formal sink is
reachable in disabled/synthetic assembly.

### R4: Current Evidence Clerk Is A Legacy Baseline, Not evidence.v2

**Disposition: REPLACE BY ADDITION.** The one-turn three-node graph, 20-item prompt projection,
50-assessment output, `memory_frame`, service-secret asset loader and generic reducer cannot be
relabelled as the P5 durable Graph. A1/A2 must add versioned Evidence state/topology/recovery while
preserving the current public behavior until selection changes in a future approved epoch.

### R5: Asset Controls Are Useful But Insufficient For Production

**Disposition: SYNTHETIC ONLY.** Existing case/actor authorization, desensitization or per-item
model consent, internal-host allowlist, MIME/magic, size and SHA checks remain reusable. They do not
bind bytes to a signed batch manifest, tenant, epoch/fence and immutable object version. C1 may
build that capability only for synthetic fixtures. Production mTLS/object authorization remains a
separate promotion gate.

### R6: Migration Identity Is Safe

**Disposition: ACCEPT.** `V043_4__evidence_graph_bindings.sql` is the first available Evidence
sub-version after committed Intake `V043` through `V043_3`. It is absent at baseline and must be
created additively by C. Editing or repurposing any older migration is prohibited.

### R7: One Primary And Five Owners Is Executable With Exact Grants

**Disposition: ACCEPT WITH PATH NARROWING.** The resource model and waves keep R plus A-E active.
The dependency graph is acyclic. D0 and E0 each depend only on P5-0 and can run independently from
A-C and from each other.

The broad plan routes still contain two collision risks which R must resolve in task briefs:

| Collision | Required closure before delegation |
| --- | --- |
| A `tests/graphs/evidence/**` vs E recovery-test subset | Assign each concrete recovery file to exactly one editor; the other owner reviews only |
| E and R `tests/static/test_phase5_*.py` | R retains shared plan/evidence gates; E receives new, explicitly named no-sink/bulkhead tests only |

All five delegated owners must receive concrete implementation work and edit permission; review-only
owners do not satisfy the plan. R owns shared contracts, selector assembly, candidate evidence and
the single heavy-test token.

## P5-G Closure Acceptance

| Gate | Review result | May engineering proceed after P5.0? | May promotion proceed? |
| --- | --- | --- | --- |
| `P5-G0` | OPEN, upstream | No, until exact checkpoint/permission | No |
| `P5-G1` | Classified external | Yes under ADR 0012 | No |
| `P5-G2` | Split: public open, synthetic contracted | Yes for closed 1/8/100 only | No public 100 until approval |
| `P5-G3` | Assigned to E0/E1 exit | Yes, implementation required | No until engineering and production gates |
| `P5-G4` | Split: synthetic C1, production external | Yes for signed synthetic only | No |
| `P5-G5` | Assigned to R/A/C | Yes after P5.0 | No implicit effect |
| `P5-G6` | Assigned to C | Yes after P5.0 | No implicit effect |
| `P5-G7` | Assigned to B/C, activation closed | Kernel/receipt engineering only | No |
| `P5-G8` | Assigned to A | Disabled/synthetic engineering only | No |
| `P5-G9` | Assigned to E, formal sink closed | Disabled/synthetic engineering only | No |

## Required Entry Sequence

1. Commit one accepted Phase 4 engineering checkpoint with
   `next_phase_permission: PHASE_5_ENGINEERING_ONLY`.
2. Freeze a new P5.0 contract candidate containing ADR 0012 restrictions, this factual baseline,
   the machine schedule and exact owner briefs. Do not include implementation.
3. Run P5-BATCH-0 once from the exact clean detached candidate SHA.
4. Commit entry evidence separately with commands, timestamps, durations, exit codes, JUnit/report
   hashes, environment and the protected unrelated-worktree exception.
5. Only then start A-E. Runtime remains `LEGACY`/`DISABLED` or Java-signed synthetic `SHADOW`.

## Required Focused Review Tests

The entry candidate should run the Phase 5 static/YAML suite. Later implementation owners use the
T0 checks in the machine plan. The final candidate must additionally prove:

- public max 50 remains enforced while synthetic manifests cover 1/8/100;
- Hearing supplements remain max 50 and unchanged;
- keyed reducer membership, replay, conflict and completion-order properties;
- room/tenant/global queue bounds, fairness, timeout, cancellation and release;
- signed asset manifest/hash/owner/visibility/object-version and actual-load receipt;
- original deadline, 30-minute warning, duplicate Signal, kill/replay and commit-response loss;
- exactly one Java merge/freeze and no Hearing open without a committed Java receipt;
- no formal sink in disabled/synthetic assembly and selectors fail closed.

Full regression, browser, load, soak, failover, DR, real shadow and canary remain centralized or
external checkpoints. A local static PASS cannot be relabelled as those results.

## Closure Statement

The P5.0 contract direction is reviewable and internally consistent after ADR 0012. The repository
facts, gaps, owner routes and resolution path are now explicit. Review closure is therefore
`CLOSED_WITH_BLOCKERS_CLASSIFIED`, while P5.0 remains `NOT_RUN`, engineering remains blocked on
`P5-G0`, promotion remains pending, and Java remains the only formal Evidence writer.
