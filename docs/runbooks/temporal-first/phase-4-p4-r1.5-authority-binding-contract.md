# Phase 4 P4-R1.5 Intake Authority-Binding Contract

Status: `FROZEN_FOR_IMPLEMENTATION`

Normative artifacts:

- [Authority manifest](../../../plans/phase-4-r15-authority-binding-contract.yaml)
- [Manifest schema](../../../plans/phase-4-r15-authority-binding-contract.schema.json)

This contract closes a P4-R1 integration gap. V043 permits multiple private Graph registrations
for one case actor because Agent Session is part of the private tuple. The generic Case command and
event references do not identify a private route target. A bridge reader cannot choose a
registration by role, time, status, or `findFirst` without risking cross-session disclosure.

The closure is additive. It does not modify the frozen P4.0 Graph wire contracts and does not add
`agent_session_id` to a browser request or `RoomGraphCommand.v1`. Java freezes the source
authorization, route target, and payload provenance before a command can enter the v2 bridge.

## Authority Semantics

The three identities have different meanings and must not be conflated:

1. Human source authority is the authenticated actor plus the active access session resolved by
   Java. It proves who may submit to the case.
2. The Agent Session is a server-selected route target pinned by the epoch-party authority. The
   command is not claimed to originate from that Agent Session.
3. The payload is a server-minted or server-verified immutable artifact bound to both the source
   authority and the frozen route target. A client URI or hash is never authority by itself.

No trusted browser field selects an access session, Agent Session, registration, or thread.

## V044 Relations

Four immutable relations define the bridge authority. V044 adds every candidate key needed by the
declared composite foreign keys; two unrelated single-column foreign keys are not an equivalent
proof.

### Epoch Selection

`case_intake_epoch_selection_binding` is a one-to-one child of a v2 Intake
`case_room_epoch`. Its primary key is `epoch_id`; its exact tuple is also unique:

```text
epoch_id, tenant_surrogate, case_id, room_type=INTAKE, room_epoch, fencing_token
```

It stores the complete `room-epoch-selection.v2` pin set and RFC 8785 SHA-256 hash. It also pins
the Java Agent Session profile version needed to resolve the server-side route. This closes the
current mismatch in which the JSON contract contains all profile pins but `case_room_epoch`
contains only the Graph/checkpoint/stream subset.

The selected Agent Session profile is a bounded, versioned registry identity. Java canonicalizes
this exact RFC 8785 object as UTF-8 and hashes it with SHA-256:

```text
agent_key
actor_role
prompt_version
agent_session_profile_version = agent-session-profile.v1
```

The immutable Java registry resolves that object to
`prompt_profile_id=asp.v1.<64-lowercase-sha256-hex>`. Its encoded length is always 71, below the
`varchar(128)` storage bound even when every canonical input is at its 128-character contract
limit. Lookup requires both the exact ID and exact canonical input; a mutable default or a
truncated concatenation is forbidden. `agent_key=DISPUTE_INTAKE_OFFICER` and
`memory_policy_id=GRAPH_PRIVATE_NO_MEMORY_FRAME_V1` remain fixed. Registration prompt/model pins
must equal the epoch selection. The Java Agent Session is an identity boundary only; the memory
policy forbids legacy `memory_frame` writes for this route.

### Case-Party Authority

`case_intake_epoch_party_authority` has primary key `authority_id` and
`UNIQUE(epoch_id, party)`. Exactly one `INITIATOR` row and one `RESPONDENT` row are persisted
for a bound epoch.

Party position comes from the case facts, not from a global role mapping:

```text
INITIATOR -> fulfillment_dispute_case.initiator_id + initiator_role
RESPONDENT -> fulfillment_dispute_case.respondent_id + respondent_role
```

Both actor ID and role must match exactly and exactly one position must match. This supports
user-initiated and merchant-initiated cases. `USER -> INITIATOR` and
`MERCHANT -> RESPONDENT` are forbidden shortcuts.

Each party row pins:

```text
tenant_surrogate, case_id, session_tenant_id, session_case_id, room_type=INTAKE,
room_epoch, fencing_token, registration_id, registration_hash, thread_id,
actor_id, actor_role, audience, actor_scope_hash, access_session_id,
permission_level, agent_session_id, agent_key, prompt_profile_id,
agent_session_profile_version, memory_policy_id
```

The row directly references `case_access_session` by the exact composite
`(id, tenant_id, case_id, actor_id, actor_role, permission_level)`. It references
`agent_conversation_session` by the exact composite
`(id, tenant_id, case_id, room_type, access_session_id, actor_id, actor_role, agent_key,
prompt_version, agent_session_profile_version, prompt_profile_id, memory_policy_id)`. V044 adds
the two explicit version columns for new v2 sessions and adds those candidate keys; historical
sessions remain readable. Separate composite foreign keys prove the complete epoch and V043
registration tuples. The row checks
`session_tenant_id=tenant_surrogate`, `session_case_id=case_id`, and the role/permission matrix
`USER/PARTY_USER` or `MERCHANT/PARTY_MERCHANT`.

`case_access_session.status=ACTIVE` and `agent_conversation_session.status=ACTIVE` are checked in
the epoch-binding transaction, the command-acceptance transaction, and again by the start read.
Status is mutable and is therefore deliberately absent from every foreign key and immutable
candidate key. The database constrains `party` to `INITIATOR | RESPONDENT`, but
`UNIQUE(epoch_id, party)` proves only at most one row for each value. The epoch-binding transaction
must insert both rows, assert exact count two with both enum members present, and only then make the
bootstrap outbox deliverable. V043 may
contain other valid registrations for the same actor; they are not candidates after this row is
committed. In-epoch rebinding is forbidden.

### Payload Authority

`case_intake_command_payload_authority` records one immutable artifact prepared for a bound
command. The artifact is scoped to:

```text
tenant, case, epoch, actor, access session, case-party position, party authority
```

The row pins the exact party route, artifact ID, schema, immutable object URI/version, content
hash, and byte size. `source_kind` is closed to this enum:

```text
EXISTING_PRIVATE_EVENT
SERVER_MINTED_HUMAN_INPUT
SERVER_CANONICAL_BRANCH
```

The command/schema matrix is also closed:

| Command | Source kind | Schema | Maximum | Disposition under R1.5 |
| --- | --- | --- | ---: | --- |
| `INTAKE_MESSAGE` | `EXISTING_PRIVATE_EVENT` | `intake-turn-event.v2` | 32 KiB | `INERT_EXTERNAL_EVENT`; signed synthetic shadow only |
| `INTAKE_MESSAGE` | `SERVER_MINTED_HUMAN_INPUT` | `intake-human-input-command.v1` | 32 KiB | `ACTIVITY_ORCHESTRATED`; forbidden until P4-E1 |
| `INTAKE_CONFIRM` | `SERVER_CANONICAL_BRANCH` | `intake-branch-command.v1` | 16 KiB | `ACTIVITY_ORCHESTRATED`; forbidden until P4-E1 |
| `INTAKE_CANCEL` | `SERVER_CANONICAL_BRANCH` | `intake-branch-command.v1` | 16 KiB | `ACTIVITY_ORCHESTRATED`; forbidden until P4-E1 |

A database CHECK enforces the row shape. Every source has the complete non-null party route and
artifact tuple. `EXISTING_PRIVATE_EVENT` additionally requires every V043 EVENT composite column
to be non-null and requires `put_receipt_schema_version`, `put_receipt_id`,
`put_receipt_stored_at`, and `put_receipt_hash` to be null. Both server-minted kinds require those
four put-receipt snapshot
columns to be non-null and require
`existing_event_binding_id/existing_event_binding_type` to be null. The schema constants in the
matrix are part of the same CHECK; a mixed EVENT/put-receipt row is invalid.

An `EXISTING_PRIVATE_EVENT` has a direct composite foreign key to the immutable V043
`case_intake_snapshot_binding` row. The key includes `binding_type=EVENT`, schema, artifact
ID/URI/object version/hash/size, and the exact registration, tenant, case, room, epoch, fence,
thread, actor scope, Agent Session, and audience route. A binding on another registration is not a
candidate even if its artifact fields match.

For `SERVER_MINTED_HUMAN_INPUT`, Java RFC 8785-canonicalizes and bounds the payload, performs an
immutable object put before opening the authority database transaction, and verifies an
`intake-command-payload-put-receipt.v1`. The receipt binds the exact tenant, case, registration,
actor, access session, source kind, artifact, schema, URI, object version, hash, size, storage time,
and receipt hash. If the database transaction fails, no command/outbox is deliverable. Cleanup uses
`intake.payload.orphan-cleanup:{tenant_surrogate}:{artifact_id}:{object_version}` and may delete
only that exact object version after the put is terminally abandoned and while no committed
payload authority references it. Cleanup and retry serialize on the put key. Repeated cleanup
returns `ALREADY_ABSENT_OR_DELETED`; a committed authority object is never deleted.

A `SERVER_CANONICAL_BRANCH` is generated from validated Java domain input, never from a raw client
payload reference. Its RFC 8785 `intake-branch-command.v1` object is limited to 16 KiB and rejects
additional fields. IDs and dispute type are at most 128 characters; confirmation/cancellation text
is at most 2,000 characters. The allowed operations are initiator accept/reject, respondent
confirm, and initiator cancel. Respondent cancel remains forbidden. The canonical object is put
immutably before the database transaction and its artifact ID/object version are verified by the
same exact `intake-command-payload-put-receipt.v1` fields and orphan-cleanup protocol.

Both server-minted sources derive a bounded 72-character put key:

```text
iput.v1.<sha256(RFC8785({tenant_surrogate, case_id, command_id, source_kind}))>
```

The first put binds `content_sha256` to that key. The same key and hash returns the same immutable
object version and receipt. The same key with a different hash conflicts at the put layer without
creating another object. Acceptance retries must reuse the receipt before terminal abandonment;
cleanup is allowed only after abandonment, and that command key cannot be retried afterward. This
keeps object identity, command ID, and request-hash semantics stable across database rollback/retry.

The authority row persists the receipt's schema version, ID, storage timestamp, and receipt hash;
its existing route/artifact columns persist every other receipt field. A read reconstructs the
receipt snapshot, excludes `receipt_hash` from its canonical input, recomputes SHA-256 over RFC
8785 UTF-8 bytes, and compares the stored hash. Receipt identity therefore remains independently
auditable without trusting a transient object-store response.

`CaseCommandRef.payloadRef` contains only schema version, URI, SHA-256, and byte size. Those four
values must exactly equal the authority row's `schema_version`, `object_uri`, `content_sha256`, and
`size_bytes`. `artifact_id` and `object_version` do not exist in `CaseCommandRef`; they are proven
separately by the V043 composite foreign key or the exact immutable-put provenance receipt. This
prevents a valid actor from referencing another Agent Session's private artifact under the same
case access scope without pretending the wire ref proves fields it does not carry.

### Command Authority

`case_intake_command_authority` is keyed by the internal `case_command.id`, has a unique
tenant/command ID and `UNIQUE(payload_authority_id)`, and references the exact epoch-party and
payload rows through composite foreign keys. The one-to-one payload constraint prevents one
authority artifact from authorizing two commands. It pins the access session, complete route,
request hash, command sequence/type, accepted room revision, and execution disposition. The direct
`case_command` composite foreign key is
`(id, tenant_surrogate, case_id, command_id, request_hash)`; V044 adds the matching candidate key.
The canonical request hash binds actor and the four-field payload ref.

`CaseCommandService` resolves the access session from the authenticated actor. It verifies the
case-party assignment, selected Agent Session membership/profile, and payload provenance. The
payload authority, command authority, `case_command`, and command outbox become visible in one
transaction. Idempotent replay compares the request, payload, and authority rows; a missing or
different row is an invariant failure.

The epoch selection and both party rows are likewise committed before the bootstrap outbox becomes
deliverable.

## Linearization And Revocation

R1.5 chooses an explicit acceptance linearization for inert commands:

- `readStart` requires current `REGISTERED` registrations and active access/Agent Sessions.
- Revocation committed before command acceptance rejects the transaction without a command or
  outbox.
- A committed `INERT_EXTERNAL_EVENT` command replays from its immutable acceptance snapshot even
  if the source session is later revoked. It does not invoke Graph or a formal Activity.
- A committed domain event replays its immutable receipt/operation facts and does not reopen
  current authorization.
- `ACTIVITY_ORCHESTRATED` remains blocked until P4-E1 defines an atomic terminal disposition for
  revoke-after-accept and supplies the signed synthetic driver.

This removes timing-dependent behavior from Activity retry while keeping the current R1.5 path
side-effect free. Tests cover revoke-before-accept, revoke-after-accept, and committed-event
delivery loss.

## Read Transactions

`JdbcIntakeChildBridgeReadPort` executes each read in one Spring transaction:

```java
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
```

It does not lock rows and does not call a repository that opens another transaction. All joins,
JSON decoding, canonical hash checks, and candidate-count checks use one database snapshot.

Database availability and retryable serialization/connection failures become
`ReadUnavailableException`. Missing or multiple rows, unsupported versions/dispositions,
malformed JSON, and tuple/hash drift are non-retryable invariants. A query must never collapse
duplicates and then select one.

### Start Read

The start read joins the exact epoch, unique bootstrap outbox, V044 selection, and both exact
case-party rows. It reparses `payload_json`, recomputes the provisioning hash, and compares every
Workflow, Graph, profile, epoch, fence, registration, and session pin.

### Command Read

The command read starts from the immutable command and payload authority rows. It validates
command ID/sequence/type, actor ID/role and case-party position, request/payload hashes,
epoch/fence, accepted revisions, deadline, and exact registration route. The bridge command source
must carry actor ID and role so the adapter can compare them; the adapter must not infer party from
role. It compares `payload_schema_version/payload_uri/payload_sha256/payload_size_bytes` in the
same read transaction; the 1024-character URI is deliberately not placed in a composite B-tree
key. R1.5 returns `executionContext=null` only for the admitted inert disposition.

### Event Read

The event read recomputes the source hash from `case_timeline_event.event_json`, parses only known
canonical Intake receipts, and follows the exact accepted command authority. A turn event requires
one completed operation, logical AgentRun, eligible attempt, committed manifest, output snapshot,
and proposal pointer.

The operation `result_sha256` is the finalization receipt hash; it must not be mistaken for the
Graph result hash. The Graph result hash comes from the committed manifest/output chain and must
match the event, AgentRun, attempt, and Graph execution reference. Branch events carry neither
AgentRun nor Graph references.

## Temporal Compatibility

V044 applies only to new bridge Activity names:

```text
BindIntakeChildStartV2
BindIntakeChildCommandV2
BindIntakeChildDomainEventV2
```

The Case Workflow selects them behind `typed-intake-bridge-authority-v1`. A completed v1 Activity
result replays from History. A scheduled or retrying v1 Activity remains pinned to the old worker
build until drained; it is not executed by the v2 reader. Old-build retirement requires visibility
evidence of zero open or pending v1 bridge Activities. Ambiguous v1 data is never backfilled by
guessing. Missing V044 authority fails closed only on the v2 path.

Tests cover completed v1 replay, scheduled-but-uncompleted v1 routing/build pinning, and v2 missing
authority.

## Worker Assembly

- `CASE_CONTROL` registers exactly one `IntakeChildBridgeActivitiesV2Adapter`, backed by the
  unique read-only v2 port.
- `ROOM_CONTROL` registers both `RoomControlWorkflowImpl` and `IntakeRoomWorkflowImpl`.
- No current worker or Spring bean registers `IntakeRoomActivitiesAdapter`, a formal commit/branch
  port, or a Finalizer.

Registration changes require a focused application-context smoke. The ArchUnit/static
no-formal-sink gates remain mandatory.

## Exit Rules

Implementation begins only from a commit containing the manifest, schema, runbook, and exact
static gate. R1.5 completion requires:

- V044 PK/UK/FK and immutability tests;
- atomic epoch authority/bootstrap and command/payload/outbox transaction tests;
- both case-party orientations and cross-session payload rejection;
- focused JDBC/PostgreSQL and REPEATABLE_READ race tests;
- revocation and committed-event delivery-loss races;
- v1 completed/uncompleted History compatibility and typed-child replay;
- worker context smoke and no-formal-sink gates;
- independent review on the integrated commit.

This gate does not claim production registration delivery. The registration outbox, signed Python
`graph.thread.register` endpoint, real shadow authorization, KMS/ACL evidence, `TEMPORAL`
allocation, canary, and promotion remain blocked.
