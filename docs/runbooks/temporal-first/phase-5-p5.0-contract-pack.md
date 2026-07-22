# Phase 5 P5.0 Evidence Contract Pack

## Entry State And Authorization

```text
document_status: DRAFT
contract_gate: P5.0 NOT_RUN
contract_prep_base: 49e1c22f0e7203478cde2ea568058db86231092d
engineering_execution: BLOCKED
phase_4_engineering_checkpoint: NOT_RECORDED
next_phase_permission: BLOCKED
MIG-004: PENDING_PROMOTION
MIG-005: PENDING
java_evidence_ledger_writer: SOLE_FORMAL_WRITER
graph_runtime: DISABLED_OR_JAVA_SIGNED_SYNTHETIC_SHADOW_ONLY
real_case_shadow: FORBIDDEN
temporal_evidence_allocation: FORBIDDEN
formal_graph_sink: FORBIDDEN
canary: FORBIDDEN
promotion: FORBIDDEN
```

This pack freezes the intended P5.0 contract shape for review; it is not entry evidence and grants
no implementation permission. The Phase 5 source plan requires `MIG-004=PASS`, a recorded 100-file
contract approval, an approved object/asset authorization boundary, and complete Graph fan-out
bulkhead evidence. None is recorded at the contract-prep base.

The companion documents are the
[Phase 5 Evidence Pilot Execution Plan](../../../plans/phase-5-evidence-pilot-execution.md) and its
[centralized test-batch policy](../../../plans/phase-5-evidence-pilot-test-batches.yaml). Normative
source and Check IDs come from `plans/temporal-langgraph-room-refactor.md` section 7.6,
`docs/acceptance/temporal-first-agent-platform-verification-checklist.md`, and
`tests/acceptance/temporal-first-check-manifest.yaml`; preserved room behavior comes from
`docs/acceptance/current-room-function-baseline.md`.

P5.0 follows a two-commit gate. A later contract candidate freezes the approved pack and static
gate material. Batch 0 then runs from that exact clean detached SHA. A separate evidence commit
records the tested SHA, commands, durations, exit codes, report paths and hashes. Implementation
starts only after that evidence commit says P5.0 `PASS` and all upstream approvals remain valid.

## Open P0 Contract Gaps

| Gap | State at prep base | Required closure |
| --- | --- | --- |
| `P5-G0` Phase 4 engineering handoff | No Phase 4 engineering checkpoint or `PHASE_5_ENGINEERING_ONLY` grant exists | One accepted Phase 4 candidate and immutable checkpoint |
| `P5-G1` migration prerequisite | `MIG-004` remains `PENDING_PROMOTION` | Independent `MIG-004=PASS` record; synthetic evidence is insufficient |
| `P5-G2` 100-file product contract | Public `EvidenceSubmissionRequest` still has `@Size(max = 50)`; no approval record found | Product/API/frontend approval, 1/50/100 compatibility contract, exact schema/UI change |
| `P5-G3` fan-out bulkheads | Phase 3 marks `GRAPH-016` `PARTIAL_ENGINEERING`; only the per-room eight-item unit bound is evidenced | Tenant/global semaphores, bounded queues, fairness, cancellation and recovery evidence |
| `P5-G4` authorized asset boundary | The current loader checks visibility, privacy, MIME, size and hash, but uses the legacy service-secret endpoint and is not bound to an approved P5 immutable manifest/epoch/fence capability | Approved mTLS/signed capability, immutable object version, owner/visibility and actual-load receipt contract |
| `P5-G5` Evidence wire contracts | No `contracts/agent-platform/evidence/v2/**` contract set exists | Closed schemas, canonical hashes, fixtures and Java/Python parity before implementation |
| `P5-G6` Evidence bindings | Existing V043/V043_1 migrations are Intake-only; no Evidence graph/manifest/finalizer binding exists | Freeze and implement additive `V043_2__evidence_graph_bindings.sql`; never edit older migrations |
| `P5-G7` formal transition authority | `EvidenceCompletionService.complete/expire` currently freeze, transition to Hearing, and start Hearing directly; coordinator delivery is post-commit side effect | Mode-aware Java events/receipts, durable dispatch and a single future Temporal ordering path |
| `P5-G8` durable Evidence graph | Current Evidence clerk is a one-turn graph that returns `memory_frame`; it has no P5 checkpoint registration, 100-item `Send`, keyed reducer, or immutable batch proposal | Version-pinned `evidence.v2` using the Phase 3 durable kernel |
| `P5-G9` runtime selector and no-sink proof | Evidence has no admitted typed child selector or formal-sink isolation proof | Fail-closed Evidence-specific selector and static assembly evidence, still synthetic-only |

Every row is blocking for the relevant entry or implementation slice. This draft does not resolve
any row by describing its target state.

## Baseline Behavior To Preserve

The following code is a behavioral baseline, not a target authority endorsement:

- `EvidenceWindowWorkflowImpl` already owns a shared timer, uses a 30-minute warning lead, and
  deduplicates `USER`/`MERCHANT` completion Signals in a set. Its comment that says 15 minutes is
  stale; the executable 30-minute behavior is authoritative.
- `EvidenceWindowCoordinator` starts stable `evidence-window-{caseId}` workflows and Signals after
  commit through `PostCommitSideEffectExecutor`. The stable ID prevents a simple duplicate timer,
  but the post-commit dispatch is not the Phase 5 durable command/outbox contract.
- `EvidenceCompletionService.complete` records party completion and, after both parties, freezes the
  dossier, advances Evidence to Hearing, and starts the Hearing runtime. `expire` can perform the
  same formal transition. Phase 5 must separate Java formal facts from Temporal ordering without
  changing admission semantics.
- `EvidenceSubmissionService` stores idempotent batches and formal party Evidence IDs. The public
  request accepts 1-50 IDs today. This is preserved until the separate 100-file approval passes.
- `EvidenceDossierFreezer` provides an idempotent `(case, dossier version)` freeze and reads formal
  verification rows. It remains Java-owned.
- `EvidenceAgentTurnService` validates assessment coverage and persists formal verification,
  matrix, review, message and memory-related results on the legacy path. A Graph proposal may not
  call or replace these repositories.
- `EvidenceAssetLoader` is a useful fail-closed baseline for privacy, MIME magic bytes, byte limits,
  hash validation and explicit `LOADED` status, but its current maximum of three images per turn
  and legacy transport do not prove the P5 batch authorization boundary.
- `EvidenceRoomView.vue` already renders pending/private/review Evidence, timer/history behavior and
  active interactions. Phase 5 extends scale and projections without moving authority client-side.

## Authority And Modes

| Data or decision | Sole authority and formal writer | Reader/proposer | P5 rule |
| --- | --- | --- | --- |
| Evidence metadata, original object identity and authorization | Java ledger + versioned object store | Authorized loader, Graph by capability only | Object refs and hashes only cross service boundaries |
| Submission batches and party completion facts | Java + Domain PostgreSQL | Temporal by committed receipt, UI by projection | Java appends idempotently; Temporal orders future process effects |
| Shared deadline, warning and party wait | Temporal for a future admitted Evidence epoch | Java projection/UI | One original deadline; no reset on submit or first completion |
| Per-item cognitive assessment | `evidence.v2` checkpoint/ledger | Java Finalizer | Proposal only; no formal truth or phase authority |
| Verification, review queue, dossier and fact-evidence matrix | Java + Domain PostgreSQL | Graph proposes bounded patches | One Java transaction after full valid coverage |
| Hearing admission/opening | Java admission rule and idempotent receipt, ordered by Temporal | UI projection | Graph cannot request or execute Hearing opening directly |

| Mode | Process owner | Formal writer | Graph input | Sink |
| --- | --- | --- | --- | --- |
| `LEGACY` | Current Evidence path and legacy window Workflow | Java | Current clerk contract | Existing Java services |
| `SHADOW` | Legacy path | Java legacy path | Java-signed synthetic manifest only under this gate | Isolated comparison ledger |
| `TEMPORAL` | `EvidenceRoomWorkflow.v1` | Java Activities/Finalizer | Version-pinned formal manifest | Forbidden until separately promoted |

The persisted mode is immutable within an epoch. A legacy Evidence case never gains a new typed
timer in place. A future promoted epoch uses only the typed child; it cannot also start
`evidence-window-{caseId}`.

## Contract Set

The later P5.0 candidate must add closed schemas and positive/negative fixtures under
`contracts/agent-platform/evidence/v2/` for at least:

| Contract | Responsibility |
| --- | --- |
| `evidence-thread-registration.v1` | Exact private actor/session, tenant/case, Evidence epoch/fence and version pins |
| `evidence-batch-manifest.v1` | Java-authorized immutable batch and its 1-100 item membership |
| `evidence-item-manifest.v1` | One Evidence ID, owner, visibility, object version/ref/hash, metadata and parse refs |
| `evidence-item-assessment.v1` | Bounded proposal with stable item key, source bindings, modalities, scores/reasons and review need |
| `evidence-batch-proposal.v1` | Complete deterministic keyed assessment map and proposed matrix/review operations |
| `evidence-room-command.v1` | Temporal-to-Java/Python reference-only command envelope |
| `evidence-domain-receipt.v1` | Java committed operation receipt used by Workflow replay |
| `evidence-process-projection.v1` | Java-authorized UI projection for mode, epoch/fence, timer, pending state and active run |

All contracts reject unknown fields, noncanonical hashes, duplicate stable keys, missing version
pins, unauthorized formal-action fields, and oversized values. Canonical JSON hashing uses the
existing RFC 8785 convention. Schema changes are additive versions, never silent reinterpretation.

## Private Thread And Manifest Registration

Java issues one opaque private thread per exact tuple:

```text
(tenant_surrogate, case_id, room_epoch, fencing_token,
 actor_scope_hash, participant_id, agent_session_id,
 graph_key, graph_version, checkpoint_schema_version)
```

The actor role is `USER` or `MERCHANT`, audience equals that party, and the registered room is
exactly `EVIDENCE`. A reopened epoch or different participant/session gets another thread. A
Reviewer reads Java-authorized review projections and does not borrow a party Graph thread.

`evidence-batch-manifest.v1` binds:

```text
schema_version, manifest_id, manifest_hash
tenant_surrogate, case_id, room_id, room_epoch, fencing_token
actor_id, actor_role, participant_id, actor_scope_hash, agent_session_id
submission_batch_id, submission_revision, dossier_target_version
graph/checkpoint/state/prompt/model/output/policy/guardrail/tool versions
issued_at, expires_at, item_count, ordered_item_keys, items
```

`item_count` is 1-100 only after approval. `ordered_item_keys` is sorted by stable Evidence ID for
canonical hashing; display/submission order remains separate metadata. Every item binds:

```text
evidence_id, owner_participant_id, owner_role, visibility
object_ref, immutable_object_version, object_sha256
content_type, byte_size, original_filename
parse_ref, parse_hash, parse_status
privacy_basis, permitted_modalities, formal_evidence_revision
```

The exact same manifest ID/hash replays. The same ID with another body hash, duplicate Evidence ID,
wrong owner/visibility, mutable object ref, stale epoch/fence, an item outside the formal submission,
or a count over the admitted limit fails before checkpoint mutation. Python never queries Domain
PostgreSQL to repair a manifest.

Under the current gate only entries from a Java-signed synthetic fixture registry are registerable.
Real case IDs, party data, and production object refs are rejected.

## Evidence Graph State And Topology

`EvidenceGraphStateV2` contains only bounded canonical JSON and immutable refs:

```text
schema_version = evidence-graph-state.v2
bindings and version_pins
command_id, logical_run_id, attempt_id
manifest_ref/hash and ordered_item_keys
next_dispatch_index
in_flight_keys (maximum 8)
validated_outputs (Evidence-ID keyed reducer)
execution_receipts
proposed_fact_matrix_patch
proposed_review_items
usage_by_invocation
terminal_proposal_ref/hash or error
```

No object bytes, full OCR corpus, database/model client, secret, request object, tool executor,
formal Java entity, Temporal payload, or `memory_frame` enters checkpoint state. Large content stays
behind immutable refs. The Phase 3 byte ceilings remain in force. P5 adds an Evidence-specific
maximum of 100 manifest/pending keys through a versioned state contract; it does not raise the
maximum active `Send` count of eight.

The explicit topology is:

```text
START
  -> authorize_registration_and_manifest
  -> plan_next_deterministic_wave
  -> Send assess_evidence_item (0..8 independent keys)
  -> validate_item_assessment
  -> keyed_fan_in
  -> more_items? -> plan_next_deterministic_wave
  -> require_complete_valid_coverage
  -> build_matrix_and_review_proposal
  -> project_evidence_batch_proposal
  -> checkpoint_terminal
  -> END
```

Unknown routes fail closed. Graph has no long party wait, timer, formal completion, dossier freeze,
or Hearing transition. Node failure does not become a valid item assessment. A retry may reuse
identical cached validated outputs and schedule missing work, but cannot overwrite a stable key.

## Send, Bulkhead, And Reducer Contract

- One `Send` equals one independent manifest Evidence ID. A node cannot introduce another key or
  expand the manifest.
- A room has at most eight in-flight item nodes. Dispatch order is the canonical item-key order in
  deterministic waves; completion order is irrelevant.
- Tenant and global semaphores are acquired in a fixed order, use bounded queues and timeouts, and
  release on success, failure, cancellation and worker loss. Queue saturation returns a typed
  retryable failure; it never starts untracked work.
- Timer/control task queues are isolated from model and asset-load saturation.
- The reducer key is exact `evidence_id`. Values carry their canonical self-hash. Identical values
  are idempotent; another hash for the same key is a protocol conflict and fails the command.
- The reducer is associative, deterministic and completion-order independent. The final map and
  proposal arrays sort by Evidence ID before hashing. Last-write-wins is forbidden.
- A batch proposal exists only when the validated key set exactly equals manifest membership and
  each value is a valid `COMPLETED` or `NEEDS_REVIEW` assessment. A node error, missing key, extra
  key, or conflicting key produces no proposal eligible for Java merge.
- 1, 8 and 100 item cases, randomized completion order, duplicates, conflicts, cancellation, queue
  saturation, and crashes at items 1/8/100 are mandatory properties.

## Item Assessment Proposal

Each `evidence-item-assessment.v1` binds the command/run/attempt, thread, manifest/item hash,
Evidence revision, actor scope, and all profile versions. Its business payload is limited to:

```text
assessment_status = COMPLETED | NEEDS_REVIEW
authenticity_score and authenticity_reason_codes
relevance_score and relevance_reason_codes
completeness_score
confidence
candidate_fact_links (allowed fact IDs and source refs only)
inspected_modalities
asset_load_receipt_ref/hash or null
limitations
review_reasons
```

These are proposals. `authenticity_score` is not a formal finding of fabrication. Low relevance
does not set an authenticity reason, and low confidence does not prevent a bounded assessment from
becoming terminal; it may require Java-visible human review. Free text cannot introduce a new fact,
Evidence ID, object ref, policy, tool, participant, room transition or formal status.

## Authorized Asset Loading

The loader accepts only a server-issued capability containing the exact registration, manifest and
item bindings. Before reading bytes it verifies service mTLS identity, Java ES256 signature and
nonce, capability/profile allowlists, tenant/case, Evidence room epoch/fence, actor scope,
participant/owner/visibility, immutable object version, URI allowlist, MIME, declared size,
privacy basis, permitted modality, expiry and SHA-256.

The object response is streamed under per-item and batch byte/time budgets. The loader verifies
actual length, magic-byte MIME and SHA-256. It returns an opaque capability plus an immutable
receipt. The receipt status is one of:

```text
LOADED
NOT_REQUESTED
PRIVACY_REVIEW_REQUIRED
UNSUPPORTED_MODALITY
FILE_TOO_LARGE
TOTAL_SIZE_LIMIT_EXCEEDED
LOAD_DEADLINE_EXCEEDED
FETCH_FAILED
MIME_MISMATCH
HASH_MISSING
HASH_INVALID
HASH_MISMATCH
OWNER_OR_SCOPE_MISMATCH
```

Only `LOADED` permits `IMAGE_PIXELS` in `inspected_modalities`. OCR or metadata review uses its own
explicit modality. A model statement claiming visual inspection without a matching loader-issued
receipt is removed or rejected by the deterministic guardrail. A loader failure never falls back
to a browser URL, arbitrary network fetch, unchecked base64, local file, or unverified object.

## Evidence Workflow Protocol

The future `EvidenceRoomWorkflow.v1` holds only deterministic process state:

```text
OPEN
WAITING_PARTIES
ASSESSING
READY_TO_FREEZE
COMPLETED
```

Terminal reason (`BOTH_PARTIES_COMPLETED`, `DEADLINE_EXPIRED`, `ADMISSION_FAILED`, `CANCELLED`) and
party completion, warning-sent, original deadline, pending operation key, manifest/Graph refs,
process/room revision, epoch/fence, and version pins are separate fields. Handlers validate envelope
shape and enqueue refs; the deterministic main loop consumes Java command/event sequence.

The original shared deadline is fixed when Evidence opens. The warning fires 30 minutes before
that deadline. Submission, Graph retry, first party completion, worker restart and Continue-As-New
do not change it. Duplicate completion Signals for the same participant return the original
receipt. Raw party text, OCR and Evidence bytes never enter Temporal History.

Process ordering is:

| Event/condition | Java fact | Workflow action |
| --- | --- | --- |
| Formal submission | Idempotent batch and Evidence revisions | Request a manifest/Graph run by ref; keep original deadline |
| First party completes | Append one completion fact | Persist wait; do not freeze or reset timer |
| Both parties complete before deadline | Both completion receipts exist | Wait for valid terminal assessment coverage, then request admission/freeze once |
| Deadline expires | Append expiry evaluation receipt | Permit one-party absence; still wait/reconcile required assessment receipts |
| Initiator has zero formally admitted Evidence | Java admission failure receipt | Complete without Hearing |
| Respondent has zero Evidence | Valid completion/absence under current rule | Does not by itself block freeze/Hearing |
| Java merge/freeze commits | Idempotent domain receipt | Request Hearing opening once and complete from its receipt |

Temporal cannot enter Hearing merely because Graph completed. Only the Java admission/freeze and
Hearing-opening receipts authorize the transition.

## Activity And Operation Keys

Every Java Activity is reference-only and idempotent through the domain operation/outbox ledger.
The exact candidate schemas must bind operation keys to tenant, case, room epoch/fence and source
revision. Required semantic keys include:

```text
evidence.manifest.issue:{case_id}:{room_epoch}:{submission_batch_id}:{submission_revision}
evidence.graph.request:{case_id}:{room_epoch}:{manifest_hash}:{logical_run_id}
evidence.party.complete:{case_id}:{room_epoch}:{participant_id}:{completion_request_id}
evidence.deadline.warn:{case_id}:{room_epoch}:{deadline_revision}
evidence.deadline.expire:{case_id}:{room_epoch}:{deadline_revision}
evidence.batch.merge:{case_id}:{room_epoch}:{manifest_hash}:{dossier_target_version}
evidence.dossier.freeze:{case_id}:{room_epoch}:{dossier_target_version}
evidence.hearing.open:{case_id}:{room_epoch}:{freeze_receipt_hash}
```

An exact replay returns the original receipt. The same semantic key with a different canonical
input hash is a conflict. Activity commit followed by lost completion response must replay from the
ledger without a second submission, assessment merge, freeze, audit, outbox or Hearing open.

## Java Finalizer Contract

Java accepts an `evidence-batch-proposal.v1` only when it can revalidate:

- exact tenant/case/Evidence room, active epoch/fence, process/room revision and writer mode;
- thread/participant/actor scope and Agent Session authority;
- command, logical run, attempt, manifest, item, source/object and proposal hashes;
- exact manifest membership and 1-100 approved limit;
- one valid terminal assessment per item and no key conflicts;
- permitted modalities against loader receipts;
- schema/graph/checkpoint/prompt/model/policy/guardrail/tool version pins;
- fact and Evidence references against the current Java formal ledger;
- authenticity/relevance separation, review policy and admission invariants.

One ACID transaction writes only approved formal verification versions, review-queue items,
fact-evidence matrix changes, AgentRun terminal result, audit/event/outbox and merge receipt. Dossier
freeze and Hearing opening use their own idempotent receipts in deterministic Workflow order. A
partial, stale, invalid, unauthorized or incomplete proposal writes nothing. The merge count for a
manifest/dossier target must be exactly one.

## Additive Domain Persistence

The frozen migration identity is `V043_2__evidence_graph_bindings.sql`, sequenced after the existing
`V043_1__intake_authority_bindings.sql`. V044 and V045 remain reserved. The future migration is
expand-only and may add only the Evidence-specific authorization/version/reference/idempotency
bindings needed by Java, including:

- immutable Evidence private-thread registration bound to `case_room_epoch` epoch/fence;
- immutable manifest registration and exact item membership/hash/owner/visibility refs;
- proposal/finalizer operation references and unique merge/freeze idempotency constraints;
- projection fields required to expose explicit mode/revision/pending/timer state safely.

It stores no Graph checkpoint body, object bytes, OCR corpus, model hidden reasoning or new
`memory_frame` truth. Existing Evidence/submission/verification/dossier tables remain the formal
ledger. Historical rows and legacy readers remain compatible. Existing migrations are never edited.

## Projection And Compatibility

`evidence-process-projection.v1` is Java-owned and exposes explicit:

```text
writer_mode, process_revision, room_revision, room_epoch, fencing_token
workflow/graph/profile versions
room_phase, pending_state, original_deadline_at, warning_sent_at
party_completion status
active logical run and attempt descriptor
assessment total/completed/review/failed counts
dossier version, history_mode, last_event_sequence
```

The UI never infers formal completion from model text, a local countdown, a missing stream, or
Graph state. Projection lag renders as processing/unavailable according to the versioned contract.
Refresh and role switch recover only current actor-authorized runs and discard late old-scope
responses. Existing URLs, private/reviewer views, 740px room shell, 1060px breakpoint, accessibility,
history behavior and 1-50 requests remain compatible while approved 100-card support is added.

## Shadow Parity And Observability

Signed synthetic shadow uses an isolated fixture manifest and comparison ledger. It cannot resolve
a formal Finalizer or use a production object ref. Fixed parity columns are:

```text
manifest membership/hash
assessment key coverage and terminal class
authenticity/relevance category
fact/source refs
loaded-modality receipts
review classification
matrix canonical hash
admission classification
timer/completion ordering
merge-count invariant
privacy/authority invariant
```

Natural-language bytes are excluded. Metrics include fan-out active/queued counts by bounded labels,
tenant/global permit wait, item terminal gap, reducer conflicts, object/hash rejects, loaded modality,
review count, timer lag, Activity retries, merge/freeze counts and stale-fence rejects. Payloads,
filenames, raw IDs, private text, bytes and hidden reasoning do not enter labels or logs.

Any unauthorized asset byte, privacy violation, conflicting reducer key, stale-fence success,
assessment coverage gap at merge, merge/freeze count other than one, or Hearing open without a Java
admission receipt is a zero-tolerance stop condition.

## Versioning And Rollback

An epoch pins Workflow type/build, graph, checkpoint/state, prompt/model/output, policy/guardrail,
tool, stream and contract versions. Referenced versions remain loadable until no active thread,
command, History, checkpoint, manifest, proposal or evidence report points to them. Deployment never
implicitly migrates an active epoch.

Engineering rollback disables the synthetic registry entry, fences nonterminal Graph work and
retains ledgers/checkpoints. It does not delete additive data or enable an unsigned/legacy fallback.

A future canary rollback sets new allocation to zero. Active `TEMPORAL` epochs keep their original
timer and are reconciled at a safe wait boundary using formal Java refs and a higher fence when
needed. After a formal merge/freeze or Hearing-opening receipt commits, recovery rolls forward from
that receipt; it never restarts a legacy timer, reopens Evidence, or deletes an immutable dossier.

## P5.0 Verification Requirements

P5.0 Batch 0 is blocked until `P5-G0..G4` are closed. When admitted, it must run from the exact
contract-candidate SHA and prove the legacy baseline plus static gate conditions. Later engineering
evidence maps:

| Checks | Required proof |
| --- | --- |
| `ROOM-EVIDENCE-001` | Actor/participant/Reviewer visibility matrix and formal Intake dossier scope |
| `ROOM-EVIDENCE-002` | 1/8/100 items, maximum eight active, complete valid coverage and one merge |
| `ROOM-EVIDENCE-003`, `GRAPH-017..019` | Associativity, determinism, replay, random order and key-conflict properties |
| `ROOM-EVIDENCE-004`, `GRAPH-009`, `LCEL-009` | Owner/visibility/object/hash/MIME/actual-load positive and negative cases |
| `ROOM-EVIDENCE-005`, `TEMP-020..024` | 30-minute warning, original deadline, completion/expiry races, kill/replay and lost response |
| `ROOM-EVIDENCE-006` | Initiator admission gate and no Hearing without committed Java receipt |
| `GRAPH-016`, `ENV-014` | Room/tenant/global bulkheads, bounded queues and 100-file capacity; product approval remains separate |
| `EVD-001..015` | Every preserved Evidence baseline behavior, not an aggregate suite label |
| `UI-001`, `UI-003..005`, `CORE-001..010`, `SEC-001..006` | Projection, stream, privacy, accessibility, history, recovery and security compatibility |
| `MIG-005` | Engineering aggregate remains `PENDING_PROMOTION` until separate real-shadow/canary evidence |

No regression, browser, Docker, Maven, Pytest or service suite ran during this contract-prep task.
Only static document cross-reference and YAML parsing checks are permitted for this draft.

## Frozen P5.0 Decisions

1. Java Evidence/domain ledgers remain the only formal writers.
2. Temporal owns wait, original shared timer and party-completion ordering for admitted future
   Evidence epochs; Graph owns none of them.
3. Graph output is a proposal and no formal Graph sink is reachable under the current gate.
4. The batch contract is 1-100 only after recorded approval; per-room active fan-out is at most
   eight with tenant/global bounded bulkheads.
5. Fan-in is Evidence-ID keyed, associative, deterministic, replay-idempotent and conflict-failing.
6. Asset access requires an immutable Java-signed owner/visibility/manifest/hash capability and an
   actual-load receipt; visual inspection cannot be inferred.
7. Authenticity, relevance, completeness and confidence remain separate. Low relevance is not
   fabrication and low confidence may route to review without blocking terminal assessment.
8. The shared deadline is not reset; warning lead is 30 minutes; duplicate completion is idempotent.
9. Java performs one validated merge/freeze sequence and Hearing opens only from its committed
   admission receipt.
10. Runtime remains `LEGACY`, `DISABLED`, or Java-signed synthetic `SHADOW`; real shadow,
    `TEMPORAL`, formal sink, canary and promotion are forbidden at entry.
11. Additive Evidence bindings use `V043_2__evidence_graph_bindings.sql`; older migrations are
    immutable and V044/V045 stay reserved.
12. Hearing supplementation is outside Phase 5 and remains unchanged at its existing 50-file limit.
13. The team is primary plus five logical owners in two waves; the primary centralizes expensive
    tests and owns one final candidate checkpoint.
14. P5.0 remains `DRAFT/BLOCKED` until every entry gap is closed and exact-SHA Batch 0 evidence is
    committed separately.
