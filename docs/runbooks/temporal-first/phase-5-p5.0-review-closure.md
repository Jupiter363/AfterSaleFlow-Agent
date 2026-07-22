# Phase 5 P5.0 Independent Review Closure

## Disposition

```text
review_status: CLOSED_WITH_BLOCKERS_CLASSIFIED
review_basis_commit: d6f66d6d8634aac20b77b9b66a22cbb77370c4fe
contract_gate: P5.0 NOT_RUN
historical_engineering_execution_at_review_basis: BLOCKED_PENDING_PHASE_4_ENGINEERING_CHECKPOINT
current_phase_4_engineering_checkpoint: PASS
engineering_execution: BLOCKED_PENDING_P5_0_ENTRY_EVIDENCE
candidate_scope_integrity: PRE_ENTRY_CONTRACT_CORRECTION_REQUIRES_FRESH_EXACT_SHA_BATCH_0
promotion_gate: PENDING
MIG-004: PENDING_PROMOTION
MIG-005: PENDING_PROMOTION
pre_entry_contract_correction: ADR_0013_ACCEPTED_ATOMIC_ONCE
```

This closes the independent document and repository-fact review requested before the final P5.0
candidate. The later accepted Phase 4 evidence closes the historical `P5-G0`, but this closure does
not authorize implementation or turn an engineering exception into a promotion exception. The
reviewed fact set is
[`phase-5-p5.0-baseline-inventory.md`](./phase-5-p5.0-baseline-inventory.md).

## Reviewed Inputs

- ADR 0012, ADR 0013, Phase 5 execution plan, machine test batches and P5.0 contract pack.
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

### R1: P5-G0 Is Closed By Accepted Phase 4 Evidence

**Disposition: ACCEPTED HANDOFF, HISTORICAL FINDING RETAINED.** At the review basis commit there was
no accepted Phase 4 engineering checkpoint. Candidate `1ba6e17f` and evidence commit `b8697ce7`
subsequently grant `PHASE_5_ENGINEERING_ONLY`. This closes only the handoff dependency: final P5.0
candidate integrity, exact-SHA Batch 0 and separate entry evidence remain mandatory.

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

### R8: Post-Contract Repairs Require A New Candidate

**Disposition: ACCEPT AS ENTRY REPAIRS WITH EXACT-SHA REVALIDATION.** Commits `99cdd435`,
`d76fde17`, `24a705dc`, `a3be6744`, `c9e6c7ba`, `e97e1341`, and `fb69bd4c` are limited to evidence
authentication/publication, runner retention, test-fixture isolation, and existing Spring bean
proxyability. The proxy repairs remove only class-level `final`; they do not change transaction
annotations, locks, SQL, idempotency, formal writers, runtime modes or Evidence behavior.

Exact main repair commit `b9201f0bc1d9ad7fca1cc0ca7b68cd75e62a503a` is patch-equivalent on the
three changed repair paths to source commit `79b8c797522671aa46f2299198eab7ba6f651006` and has two
distinct dispositions:

- **`FIXTURE`: Evidence admission and cleanup.** The `EvidenceApiIntegrationTest` case was only
  `INTAKE_COMPLETED`, while production access correctly requires an Evidence-open case. The fixture
  now uses the domain admission transition to reach `EVIDENCE_OPEN`, performs cleanup through the
  existing audited, case-scoped purge service, rebuilds unconditionally, and scopes its Evidence
  count to that case.
- **`PRODUCT`: canonical UTC response mapping.** The immediate create view retained the request
  offset while a PostgreSQL reload returned the same instant at UTC, so one accepted item could have
  two response representations. `toView` now applies `withOffsetSameInstant(UTC)` to non-null
  `occurred_at` and preserves null. This canonicalizes the API response only; it does not change the
  stored instant, request rules, authorization, persistence order, or Java writer authority.

At source SHA `79b8c797522671aa46f2299198eab7ba6f651006`, Evidence API/service checks passed
15/15, retained Security checks passed 8/8, and Intake progress checks passed 4/4, for 27/27 focused
checks. Main commit `b9201f0b` carries the identical three-path patch; no exact-main test execution
is claimed. These checks do not substitute for the fresh exact-SHA Batch 0. Weakening
`SecurityConfiguration`, controller/service authorization, production state checks, runtime
restrictions, or promotion gates remains forbidden.

No diagnostic run from an earlier SHA is accepted. `P5-G10` remains blocked until the repair set is
integrated, the final diff is reviewed, and Batch 0 passes from one fresh clean detached SHA.

### R9: The Unaccepted Authority Contract Requires One Atomic Correction

**Disposition: ACCEPT ADR 0013 AND QUARANTINE THE DIAGNOSTIC RUN.** Candidate
`45d7f087eafe4f50be0d491b3d612446a3e1e94e` completed static 122, Python 61, Java
67, and frontend 97 green tests, for 347 total. Its local manifest records `status=PASS`,
`batch_0=PASS`, source `accepted=true`, and
`contract_gate=P5.0_AWAITING_ENTRY_EVIDENCE_COMMIT`, but the coverage missed a P0: the batch
manifest lacked its own Java ES256 signature and one output pin ambiguously represented both item
assessment and terminal proposal contracts. Source artifacts exist locally, but no repository P5.0
entry-evidence bundle or
checkpoint was assembled, committed, or accepted. ADR 0013 overrides the local PASS and accepted
flags for entry-gate purposes, so the run is diagnostic evidence of a gap, not evidence that closes
the gate.

The affected v1 names have never been accepted, released, promoted, selected by an epoch, or
consumed. Review therefore accepts one in-place correction before first P5.0 acceptance, but only
as an indivisible change: direct ES256 `JOSE_P1363_BASE64URL` manifest signature over a hash
omitting exactly the hash and signature, signing the ASCII lowercase 64-character hash text
(`ASCII_LOWERCASE_HEX_TEXT`) rather than decoded digest bytes; independent
`assessment_output_schema_version=evidence-item-assessment.v1` and
`terminal_output_schema_version=evidence-batch-proposal.v1` pins propagated through
manifest/item/terminal/projection, with capability binding the
exact split `profile_versions_hash` and no false finalization-receipt profile claim; no
`authorization_proof_ref`; actual `room-graph-command.v1` identity/thread/epoch/snapshot/profile
`x-gateway-cross-binding` with failure `BEFORE_CHECKPOINT_MUTATION`; independent Graph lease-fence
enforcement; and Java Finalizer revalidation of the distinct room fence authenticated by the
manifest. All fixture hashes must be regenerated, with Python plus Java parity over the same bytes.

Review additionally freezes the two integrity layers and output roles. `domain_snapshot_ref`
SHA-256, exact size, and content-addressed immutable URI bind the full RFC 8785 canonical signed
manifest bytes and are verified before parsing. The internal `manifest_hash` is then recomputed
with `manifest_hash` and `signature` omitted before Java ES256 signature verification; it is never
interchangeable with the snapshot payload hash. RoomGraphCommand invocation and Graph registry
output use terminal `evidence-batch-proposal.v1`, while the internal item LCEL parser uses
`evidence-item-assessment.v1`.

```text
snapshot_payload_hash_scope: FULL_RFC8785_CANONICAL_SIGNED_MANIFEST_BYTES
snapshot_payload_size_scope: EXACT_FULL_CANONICAL_SIGNED_MANIFEST_BYTES
snapshot_payload_uri: IMMUTABLE_CONTENT_ADDRESSED_BY_SNAPSHOT_SHA256
internal_manifest_hash_scope: RFC8785_OMIT_MANIFEST_HASH_AND_SIGNATURE
snapshot_and_internal_hashes_interchangeable: false
validation_order: SNAPSHOT_SHA_SIZE_URI -> PARSE_CANONICAL_JSON -> INTERNAL_MANIFEST_HASH -> JAVA_ES256_SIGNATURE
room_graph_command_output_schema_version: evidence-batch-proposal.v1
graph_registry_output_schema_version: evidence-batch-proposal.v1
item_lcel_parser_output_schema_version: evidence-item-assessment.v1
java_room_fence_source: SIGNED_MANIFEST
graph_lease_fence_source: CURRENT_GRAPH_LEASE
fence_tokens_interchangeable: false
engineering_execution: BLOCKED_PENDING_P5_0_ENTRY_EVIDENCE
```

Any partial correction or attempt to reuse `45d7f087` reports fails `P5-G10`. The corrected
candidate requires a new exact clean detached SHA and full Batch 0. Once P5.0 is first accepted,
future authority, hash, signature, trust-binding, or output-pin changes require a new schema version,
compatibility plan, and accepted ADR. Runtime remains `DISABLED` or Java-signed synthetic
`SHADOW`; real shadow, `TEMPORAL`, formal sink, canary, and promotion remain forbidden.

## P5-G Closure Acceptance

| Gate | Review result | May engineering proceed after P5.0? | May promotion proceed? |
| --- | --- | --- | --- |
| `P5-G0` | CLOSED by `1ba6e17f` / `b8697ce7` | Yes, subject to P5.0 and `P5-G10` | No |
| `P5-G1` | Classified external | Yes under ADR 0012 | No |
| `P5-G2` | Split: public open, synthetic contracted | Yes for closed 1/8/100 only | No public 100 until approval |
| `P5-G3` | Assigned to E0/E1 exit | Yes, implementation required | No until engineering and production gates |
| `P5-G4` | Split: synthetic C1, production external | Yes for signed synthetic only | No |
| `P5-G5` | ADR 0013 atomic correction assigned to R/A/C | Yes after corrected exact-SHA P5.0 | No implicit effect |
| `P5-G6` | Assigned to C | Yes after P5.0 | No implicit effect |
| `P5-G7` | Assigned to B/C, activation closed | Kernel/receipt engineering only | No |
| `P5-G8` | Assigned to A | Disabled/synthetic engineering only | No |
| `P5-G9` | Assigned to E, formal sink closed | Disabled/synthetic engineering only | No |
| `P5-G10` | Entry repairs plus atomic contract correction; `45d7f087` quarantined | No, until new exact-SHA full Batch 0 and evidence commit | No |

## Required Entry Sequence

1. Preserve accepted Phase 4 candidate `1ba6e17f` and evidence commit `b8697ce7` with
   `next_phase_permission: PHASE_5_ENGINEERING_ONLY`.
2. Integrate and independently review only the classified candidate-scope repairs, including both
   sub-repairs in `b9201f0bc1d9ad7fca1cc0ca7b68cd75e62a503a`; no Phase 5 feature implementation
   is allowed.
3. Apply ADR 0013's direct ES256 manifest signature and independent assessment/terminal pins as
   one atomic correction, remove `authorization_proof_ref`, regenerate every fixture hash, and
   pass Python validation plus Java parity over the same bytes.
4. Freeze a new P5.0 contract candidate containing ADR 0012/0013 restrictions, this factual
   baseline, the machine schedule, exact owner briefs and the closed repair ledger.
5. Run P5-BATCH-0 once from the new exact clean detached candidate SHA; do not reuse any of the 347
   passing diagnostic results from `45d7f087`.
6. Commit entry evidence separately with commands, timestamps, durations, exit codes, JUnit/report
   hashes, environment and the protected unrelated-worktree exception.
7. Only then start A-E. Runtime remains `LEGACY`/`DISABLED` or Java-signed synthetic `SHADOW`.

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
fresh exact-SHA proof of `P5-G10`, promotion remains pending, and Java remains the only formal
Evidence writer.
