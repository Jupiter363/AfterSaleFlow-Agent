# Phase 3 P3.0 Graph And LCEL Contract Pack

## Entry Gate And Scope

```text
base_commit: e42925d8e897900a28cdb0a1198617883243c572
phase_2_engineering_checkpoint: PASS
phase_2_promotion_gate: PENDING
next_phase_permission: PHASE_3_ENGINEERING_ONLY
graph_gateway_default: DISABLED
allowed_execution: SYNTHETIC_SHADOW_ONLY
formal_room_cutover: FORBIDDEN
formal_business_writer: JAVA_ONLY
```

ADR 0008 permits P3.1-P3.8 engineering while the graph gateway and every registry selection remain
disabled by default. This exception does not satisfy `MIG-002` or `MIG-003`, authorize a production
deployment, migrate a room epoch, remove a legacy endpoint, or let a graph result reach a formal
Finalizer. Shadow output is comparison data only: it cannot create a formal message, Artifact,
review task, domain transition, or tool effect.

The normative sources for this pack, in precedence order, are accepted ADRs 0001, 0004, 0005,
0006, and 0008; the v1 JSON Schemas; the production verification checklist; and the Phase 3 plan.
This pack intentionally closes every implementation-choice gap found during P3.0. A newly found
contradiction returns to the primary owner for a recorded contract update; an owner never silently
chooses a local interpretation.

## Authority And Storage Boundary

| State | Authority and writer | Store | Phase 3 restriction |
| --- | --- | --- | --- |
| Process phase, waits, timers, retries | Temporal Workflow | Temporal Event History | No Phase 3 room cutover |
| Formal messages, evidence, Artifacts, decisions, actions | Java domain transaction | Domain PostgreSQL | Java remains the only formal writer |
| Graph checkpoints, cognitive revisions, node results | Versioned Python graph runtime | Graph PostgreSQL | Shadow-only and disabled by default |
| Prompt, Message, ChatModel, Parser, callback flow | LangChain Core and LCEL | Trace and manifest references | Never owns process or domain decisions |
| Presentation state | Vue | Browser memory | Never infers a formal transition |

Graph PostgreSQL is isolated from Domain and Temporal persistence by database or schema, role,
pool, migration, backup, and resource limits. Python receives no Domain database credentials. Java
receives no Graph checkpoint write credentials. Production pools are protected independently; a
Graph pool failure must not consume Domain or Temporal connection capacity.

The controlled migration job owns DDL. It runs the pinned `langgraph-checkpoint-postgres` setup and
the repository migrations under an advisory lock, then records/probes the expected schema version.
Application replicas do not race on DDL. Startup order is:

```text
migration job -> schema/version probe -> dependency probes -> Python readiness
```

The three repository migrations have these frozen responsibilities:

| Migration | Owned data | Frozen constraints |
| --- | --- | --- |
| `G001_graph_runtime.sql` | `agent_graph_command`, `agent_graph_command_attempt`, `agent_graph_result`, `agent_graph_lease`, `graph_thread_registry`, `agent_graph_invocation_nonce` | Unique `(thread_id, command_id)`, request-hash conflict detection, monotonic thread fence, immutable result hash, durable nonce replay rejection |
| `G002_graph_version_registry.sql` | `agent_graph_version_registry` and its active-reference query/view | Version state is `DISABLED`, `SHADOW`, or `RETIRED`; a referenced version remains loadable and cannot be deleted |
| `G003_shadow_comparison.sql` | `agent_graph_shadow_comparison` with bounded parity fields and expiry | No Domain foreign-key write authority, no formal Finalizer consumer, 30-day default retention |

The checkpointer package's internal tables remain package-owned and are not duplicated in G001.
Domain migration V042 stores only graph command, result, checkpoint references, and hashes; graph
payloads and checkpoint state do not move into Domain PostgreSQL.

## Thread And Command Identity

The logical thread binding is the complete tuple below, not only the printable `thread_id`:

```text
tenant_surrogate
case_id
room_epoch
actor_scope
agent_session
graph_key
graph_version
checkpoint_schema_version
```

The tenant value is the opaque, non-PII surrogate issued by Java. Intake and Evidence private
threads are distinct for each actor and agent session. A shared Hearing thread may consume only
formal, authorized, visibility-filtered Artifact references and never either party's private chat.
No component may parse a `thread_id` and treat the parsed values as authorization; the signed
command, registry binding, and persisted thread registry must agree on every identity field.

Java allocates and durably stores an opaque UUIDv7 thread identifier using the wire form
`grt.v1.<32-lowercase-hex-digits>`. The identifier is not a reversible tuple encoding and Python
never derives authority from it. Intake and Evidence bind the exact party actor scope and agent
session. Shared Hearing uses a `SYSTEM` actor scope plus a persisted shared-session marker and
rejects private conversation references. Review binds the exact `PLATFORM_REVIEWER` actor and
agent session; it never aliases a party thread. Fixed fixtures cover each legal shape and
cross-scope rejection.

Command identity is the unique pair `(thread_id, command_id)`. The ledger also stores the RFC 8785
canonical request hash, deadline, graph/checkpoint/profile versions, checkpoint/result references,
and result hash. The behavior is fixed:

- Same identity and same validated hash before completion observes the existing command and never
  creates a second logical command.
- Same identity and same validated hash after completion returns the committed checkpoint/result
  and the identical result hash without another model call.
- Same identity with a different hash fails closed and emits a security audit event.
- A request with a stale room epoch, mismatched graph version, expired deadline, invalid reference
  hash, or mismatched signed binding fails before graph dispatch.

Canonical hashes use RFC 8785 bytes and SHA-256. `RoomGraphCommand.request_hash` is the SHA-256 of
the RFC 8785 encoding of the complete command object with the top-level `request_hash` member
omitted. `RoomGraphResult.output_hash` uses the same rule with the top-level `output_hash` member
omitted. The member is omitted, not blanked or set to null. Nested Snapshot, Event, Result, and
Artifact hashes are verified independently before use. The P3.0 canonical-hash fixture is the
cross-language authority for this rule; changing the preimage requires a new contract version.

## Ledger And Lease Protocol

### Semantic State Machine

`agent_graph_command.status` is text guarded by a `CHECK`, rather than a PostgreSQL enum, so a
rolling deployment can add a state without an enum lock-step migration. Its exact values are
`REGISTERED`, `EXECUTING`, `RESULT_CHECKPOINTED`, `COMPLETED`, `CANCELLED`, and `ABORTED`:

| Semantic phase | Durable facts | Legal next behavior |
| --- | --- | --- |
| Absent | No command row | Validate and insert once, or lose to the unique-key winner |
| `REGISTERED` | Identity, hash, deadline, and version bindings exist; no committed result | Acquire or observe a lease; same-hash callers join/replay |
| `EXECUTING` | A current, unexpired lease and fencing token own graph writes | Renew, checkpoint, cancel, expire, or allow takeover |
| `RESULT_CHECKPOINTED` | A terminal checkpoint binds command, request hash, fence, and result hash/reference; ledger completion may lag | Reconcile to `COMPLETED` without invoking the model |
| `COMPLETED` | Command, checkpoint, immutable result, and result hash agree | Return cached result on every same-hash replay |
| `CANCELLED` | A Java-authorized cancellation durably fenced the execution | Return the same cancellation; a retry requires a new command ID |
| `ABORTED` | Deadline, retry budget, or non-result infrastructure policy ended the command | Return the durable failure; a retry requires a new command ID |
| Rejected before registration | Security, schema, binding, deadline, or hash validation failed | No command/checkpoint/result mutation; emit bounded audit data |

Legal transitions are `REGISTERED -> EXECUTING`, `EXECUTING -> RESULT_CHECKPOINTED -> COMPLETED`,
and `REGISTERED|EXECUTING -> CANCELLED|ABORTED`. Lease takeover keeps `EXECUTING` and creates a new
`agent_graph_command_attempt`; it does not invent a new command. Once a terminal checkpoint exists,
reconciliation wins over a late cancellation or infrastructure timeout. Failure and cancellation
rows store a bounded error code and classification, never provider text or hidden reasoning.

`RoomGraphResult.status=FAILED` is a valid four-valued graph terminal result, not an infrastructure
ledger status. Provider transport failure, lease loss, cancellation, and retry exhaustion must not
be collapsed into that value unless a validated `RoomGraphResult` was intentionally projected and
checkpointed. Infrastructure outcomes use only the six-value command state machine above.

### Lease Acquisition And Takeover

The lease is persisted at thread granularity because commands for one cognitive thread must be
serialized. Its primary key is `thread_id`; the row also binds the active `command_id`, `owner_id`,
and fencing token. The first successful acquisition returns token `1`. The lease duration is 30
seconds and the owner renews every 10 seconds using the Graph PostgreSQL clock. Redis may reduce
contention but is never authoritative. A takeover is legal only after the prior lease has expired
or after a Java `SYSTEM` envelope carrying `graph.command.cancel` explicitly cancels it. Initial
acquisition, renewal, cancellation, and takeover are compare-and-set operations that return the
effective token; a caller never calculates one in application memory. Cancellation increments the
token in the same transaction so an in-flight response is fenced immediately.

The following is a schema-neutral statement of the required takeover predicate. Final column names
belong to G001, but weakening the predicate is forbidden:

```sql
UPDATE agent_graph_lease
   SET owner_id = :new_owner,
       command_id = :command_id,
       fencing_token = fencing_token + 1,
       lease_expires_at = clock_timestamp() + interval '30 seconds',
       cancelled_at = NULL
 WHERE thread_id = :thread_id
   AND (lease_expires_at <= clock_timestamp() OR cancelled_at IS NOT NULL)
RETURNING fencing_token;
```

Renewal requires the same thread, owner, and fencing token, an unexpired lease, and no cancellation.
Zero affected rows means lease loss. The owner stops producing state immediately and may not turn a
late provider response into a checkpoint or result.

### Fenced Commit Predicate

Every checkpoint, result, and ledger-completion write first locks and validates the single current
lease row inside the same Graph PostgreSQL transaction that performs that write:

```sql
SELECT fencing_token
  FROM agent_graph_lease
 WHERE thread_id = :thread_id
   AND owner_id = :owner_id
   AND fencing_token = :fencing_token
   AND cancelled_at IS NULL
   AND lease_expires_at > clock_timestamp()
 FOR UPDATE;
```

The command mutation additionally requires matching `thread_id`, `command_id`, `request_hash`, room
epoch, graph version, and checkpoint schema version. A result mutation additionally requires an
empty result binding or the identical existing result hash. The guarded row count must be exactly
one. Any zero-row or conflicting-row outcome rolls back and is classified as stale fence, expired
lease, binding conflict, or idempotent replay; it never falls through to an unconditional write.

For checkpointer integration, the lease guard and checkpoint write share one psycopg connection and
one explicit transaction. The runtime checks the locked lease row, invokes `PostgresSaver` bound to
that same direct connection, updates the command/result binding, and commits once. Application use
of a pool-backed saver that opens a second connection for this path is forbidden. A check in one
transaction followed by a saver call in another is a time-of-check/time-of-use violation. The
checkpoint metadata binds at least thread, command, request hash, graph version, checkpoint schema
version, fencing token, and last committed result hash/reference. Ledger reconciliation validates
all bindings before completing the command.

### Four Crash Boundaries

1. Before model invocation: retry from the prior committed checkpoint under a current fence.
2. After model response but before checkpoint: record the failed attempt and emit stream reset
   semantics; the remaining deadline and retry budget decide whether another provider call is legal.
3. After checkpoint but before command completion: reconcile the checkpoint's committed command and
   result hash/reference into the ledger without invoking the model.
4. After command completion but before the Java response: return the immutable cached result and
   identical result hash.

Recovery never depends on Python process memory or pod affinity. A replacement pod must reproduce
the same behavior from Graph PostgreSQL, immutable object references, and signed command context.

## Registry And Version Pinning

At room epoch creation, Java persists graph key/version, checkpoint schema version, prompt/model/
schema/policy/guardrail/tool versions, stream protocol, writer mode, and fencing selection. Dynamic
flags select only a new epoch. An active thread never follows a mutable default to a newer graph.

The GraphRegistry must:

- Default every graph selection to disabled in Phase 3.
- Admit only explicitly authorized synthetic shadow commands while the exception is active.
- Resolve the exact graph and checkpoint schema versions pinned to the room epoch.
- Fail closed for an absent, disabled, retired-incompatible, or signature-mismatched binding.
- Keep old graph code and checkpoint readers loadable while any thread, command, checkpoint,
  manifest, or audit reference remains active.
- Allow migration only at an approved safe checkpoint with tested compatibility; never silently
  migrate an active thread during deployment.
- Stop assigning a version on rollback but preserve its tables, checkpoints, and committed results.

Registry state has exact values `DISABLED`, `SHADOW`, and `RETIRED`. `DISABLED` is the default and
admits nothing; `SHADOW` admits only signed synthetic shadow commands; `RETIRED` prevents new
assignment but remains loadable for referenced threads. Phase 3 has no state that authorizes a
formal writer. Registry rows pin graph, checkpoint, prompt, model, output schema, policy,
guardrail, tool policy, and state schema versions as one immutable binding.

G003 shadow comparison receives only authorized immutable input/output references and bounded
normalized differences. Its fixed parity columns are schema, privacy, guardrail, formal-fields,
reference-hash, transition, terminal-classification, and invariant status, plus a JSON detail value
limited to 64 KiB. It cannot store private text, be queried as a formal result source, or be called
by a formal Finalizer. Exact natural-language bytes are not required to match. Rows expire after 30
days by default; deletion skips a row referenced by an engineering evidence manifest and records a
cleanup receipt. Phase 3 never treats an expired comparison as promotion evidence.

## Typed State, Router, Reducer, And Result

Checkpoint state contains only bounded, serializable values and immutable references. It never
contains model/database clients, connection pools, secrets, request objects, tracing clients, tool
implementations, or complete large snapshots. Message windows, summaries, pending work, Artifact
references, and state bytes require explicit limits and metrics.

The Phase 3 hard limits are 1 MiB per serialized checkpoint state, 256 KiB per node patch, 32
messages and 64 KiB total message content, 8 KiB per message, 16 KiB for a memory summary, 64
pending-work entries, 100 Artifact references at 2 KiB each, and 8 `Send` items per dispatch. The
runtime rejects an over-limit state or patch before checkpointing and emits an alert at 80 percent
of any byte or item limit. Phase 3 uses no application-level checkpoint compression; oversized
data moves to immutable hash-bound object references instead of relying on compression ratios.

Every model node has an explicit State Lens that selects only the fields required by its typed
prompt input. Passing the whole state into a prompt is forbidden. Room topology is explicit typed
`StateGraph` code, not a dynamic JSON workflow DSL. Unknown router values fail closed.

`Send` is reserved for independent work. Fan-out is limited to 8 per room and is also constrained
by tenant and global semaphores. Fan-in uses stable keys. Reducers must be associative and
deterministic across completion order; duplicate identical values are idempotent, while duplicate
keys with conflicting payloads are protocol errors rather than last-write-wins.

The terminal projector emits exactly one `RoomGraphResult.v1` status:

| Status | Required branch | Forbidden sibling branches |
| --- | --- | --- |
| `COMPLETED` | No terminal detail object | `needs_input`, `needs_review`, `error` |
| `NEEDS_INPUT` | `needs_input` | `needs_review`, `error` |
| `NEEDS_REVIEW` | `needs_review` | `needs_input`, `error` |
| `FAILED` | `error` | `needs_input`, `needs_review` |

All statuses still require the command/run/attempt and graph/checkpoint bindings, bounded proposal
arrays, output hash, usage, and execution metadata required by the JSON Schema. Formal party waits
and deadlines project `NEEDS_INPUT` back to Temporal. LangGraph `interrupt` does not become a second
long-running wait or deadline owner.

A checkpoint is migration-safe only when its metadata contains `migration_safe=true`, the graph is
at a version-declared quiescent node, all reducers have completed, and there is no pending `Send`,
model call, tool proposal, or uncommitted result. Retirement requires zero nonterminal thread rows,
zero nonterminal commands, and zero retained checkpoint, manifest, or evidence references for the
exact version binding. The reference query ships with G002 and is archived before cleanup. Phase 3
may mark a version `RETIRED` but does not delete checkpointer data.

## Governed Runnable Protocol

Every model node executes this real object flow:

```text
Typed Graph State
  -> State Lens
  -> typed prompt input
  -> ChatPromptTemplate
  -> ChatPromptValue
  -> SystemMessage / HumanMessage
  -> GovernedChatModel
  -> AIMessage
  -> structured parser
  -> Pydantic output
  -> deterministic business guardrail
  -> typed state patch
```

The production chain is equivalent to `lens | prompt | model | parser | guardrail`; it is not a
`RunnableLambda` wrapper around the old monolithic HTTP call. `GovernedChatModel` implements
`invoke`, `ainvoke`, `batch`, and `stream`, accepts `RunnableConfig` callbacks, and preserves trace,
usage, latency, provider/model, prompt, schema, policy, and guardrail metadata. The old LiteLLM
client may remain only as a provider transport adapter; graph business nodes cannot call model HTTP
directly.

Untrusted case, evidence, and asset text enters Human messages only. System instructions, Agent
identity, Prompt Profile, Model Profile, response schema, token budget, temperature, and tool
allowlist come from the signed Java context and versioned server registry; browser input and Graph
state cannot override them. Provider strict JSON Schema, Pydantic validation, and deterministic
business guardrails run in that order.

Local transient provider retries are at most two, schema repair is at most once for the same raw
result, and both stay within the command's absolute deadline and remaining budget. Compatibility
fallback for a provider that rejects response format must be explicit and bounded. Business or
guardrail failure is not reclassified as infrastructure failure. Hidden reasoning is not requested,
read, persisted, traced, streamed, or returned. Multimodal data is loaded only by an authorized
AssetLoader after URI, type, size, ownership, manifest, and content-hash verification.

## Invocation Envelope And Capabilities

Internal Java-Python traffic requires service-mesh mTLS plus a Java-signed compact JWT/JWS in the
`Authorization: Bearer <token>` header. The protected header is exactly `alg=ES256`,
`typ=graph-command+jwt`, and a registered `kid`; every other algorithm fails closed. Registered
claims are `iss=java-api-service`, `aud=python-agent-service`, `sub=graph-command`, `iat`, `nbf`,
`exp`, and `jti`. The token lifetime is at most 60 seconds and clock skew is at most 5 seconds.
Private claims bind command ID, request hash, tenant surrogate, case, room epoch, thread ID, graph
and checkpoint versions, plus SHA-256 bindings for actor scope, effective capabilities, and all
prompt, model, schema, policy, guardrail, and tool profile versions. Python verifies the signature,
claims, every body binding, and a durable nonce record before it creates or replays a graph command.
Their exact wire names are `command_id`, `command_nonce`, `request_hash`, `tenant_surrogate`,
`case_id`, `room_epoch`, `thread_id`, `graph_key`, `graph_version`,
`checkpoint_schema_version`, `actor_scope_hash`, `capabilities_hash`, and
`profile_bindings_hash`. `command_nonce` binds the stable command-body `envelope_nonce`; `jti` is a
fresh transport replay nonce and does not reuse `command_nonce`.

Verification keys rotate through KMS and overlapping verification keys keep valid in-flight work
readable. Unknown algorithm, key ID, issuer, audience, capability, profile, or binding fails closed.
The browser cannot supply trusted envelope claims. Trace fields are propagated but are not an
authorization source.

The verifier resolves public keys from the configured Java JWKS/KMS adapter and caches only keys
whose `kid`, curve `P-256`, and `use=sig` are valid. Current and previous verification keys overlap
for at least 65 seconds and remain verifiable while a nonterminal command references their `kid`.
The durable nonce key is `(issuer, kid, jti)` and is retained for 24 hours;
an exact token replay is rejected even for an idempotent command. A transport or Activity retry
must re-sign the identical body and request hash with a fresh `jti`, `iat`, and `exp`. Nonce
insertion and command registration share one Graph PostgreSQL transaction; after a failed insertion
the caller obtains a new signed envelope. Production mTLS identity comes from the authenticated
ASGI or service-mesh transport adapter, never an untrusted browser header.

Effective capabilities are the intersection of signed actor scope, signed invocation capabilities,
registry policy, and Python's server-side allowlist. A tool proposal must also match its parameter
schema and actor scope, and Java performs the final server-side authorization. Python cannot use a
capability to bypass the Java Tool Executor or create a formal external effect.

ES256 and the 60-second maximum lifetime cannot be substituted by a shared static secret, an
unsigned development header, or a locally chosen algorithm. Local tests inject a transport
identity and verification-key resolver through explicit interfaces; those fixtures do not create a
runtime bypass.

## Fail-Closed Readiness

Liveness reports only process health. Readiness stays false unless all enabled dependencies and
bindings required for the admitted mode are verified:

- Expected checkpointer and G001-G003 migrations are present and compatible.
- The Graph database pool can perform a transaction and schema/version probe.
- Checkpoint, ledger, lease, and registry repositories are available.
- The selected graph/checkpoint/profile versions are registered and loadable.
- Signature verification keys and the durable nonce/replay store are available.
- Required immutable-reference/hash validators and authorized asset loaders are available.
- Gateway and registry mode agree; Phase 3 admits synthetic shadow only.
- A restored Graph database passes checkpoint/ledger/fence consistency checks before ready.

Missing checkpoint, ledger, lease, signature, nonce, or registry dependencies fail closed. A
readiness failure cannot fall back to MemorySaver, process memory, an unsigned internal endpoint,
an unversioned graph, or direct model transport.

## Planned Verification Matrix

All tests below are planned Phase 3 evidence, not PASS claims. Focused component tests run during
the slices; full Docker, 1,000-room load, long soak, production failover, and DR remain at the
agreed unified checkpoint.

| Check IDs | Planned test or evidence |
| --- | --- |
| `CONTRACT-001`, `CONTRACT-002`, `CONTRACT-009`, `CONTRACT-010` | Extend `tests/contracts/test_agent_platform_v1.py` plus Java fixture parity for schema versions and compatibility matrix |
| `CONTRACT-003`, `CONTRACT-004`, `CONTRACT-011`, `CONTRACT-012` | Positive/negative command fixtures for unknown version, missing/extra fields, size, scope, deadline, trusted-profile and reference rules |
| `CONTRACT-005`, `CONTRACT-006` | RFC 8785 fixed vectors and same-ID same/different-hash ledger integration tests with audit assertion |
| `CONTRACT-007`, `CONTRACT-008` | Receiver-side Snapshot/Event/Result/Artifact hash tests and Temporal payload reference-size static checks |
| `CONTRACT-013` | Positive and negative fixtures for all four result statuses and mutually exclusive detail objects |
| `GRAPH-001`, `GRAPH-002`, `GRAPH-010` | PostgreSQL checkpointer setup, failed migration/readiness, stateless replacement-pod recovery tests |
| `GRAPH-003`, `GRAPH-004` | Concurrent ledger uniqueness, cached replay, and hash-conflict integration tests |
| `GRAPH-005`, `GRAPH-007` | Lease takeover, stale-fence late commit, stale epoch/thread/result rejection tests |
| `GRAPH-006` | Four-boundary crash harness covering model call count, reset, reconcile, and cached response |
| `GRAPH-008`, `GRAPH-009` | Cross-tenant/case/actor/session fuzz and shared-Hearing private-input rejection tests |
| `GRAPH-011`, `GRAPH-012`, `GRAPH-013` | State serialization/size property tests and State Lens prompt-input capture tests |
| `GRAPH-014`, `GRAPH-015` | Graph rendering/static topology check and exhaustive/unknown Router tests |
| `GRAPH-016` | Room limit 8 plus tenant/global semaphore and bounded-queue tests |
| `GRAPH-017`, `GRAPH-018`, `GRAPH-019` | Hypothesis keyed-reducer associativity, determinism, duplication, order, conflict, and patch-hash tests |
| `GRAPH-020` | Formal wait/deadline projection tests proving `NEEDS_INPUT` and no long graph interrupt |
| `GRAPH-021`, `GRAPH-022` | Registry pin, rolling-deploy, retired-version reference, and explicit safe-migration tests |
| `LCEL-001`, `LCEL-002` | Object-capture tests for Lens, prompt input, ChatPromptValue, Messages, parser schema, guardrail, and patch |
| `LCEL-003`, `LCEL-011` | `invoke/ainvoke/batch/stream`, callback propagation, async 100-concurrency stub, pool/leak tests |
| `LCEL-004`, `LCEL-005`, `LCEL-010` | System/Human separation and trusted profile/model/tool override rejection tests |
| `LCEL-006`, `LCEL-007`, `LCEL-012` | Strict schema, Pydantic, guardrail order; bounded fallback/retry/repair/deadline tests |
| `LCEL-008`, `LCEL-013` | Metadata/trace assertions and reasoning-field absence scans across stream, DB, and logs |
| `LCEL-009` | AssetLoader URI/type/size/ownership/manifest/hash positive and negative tests |
| `LCEL-014` | Prompt injection, cross-party reference, forged visual state, and tool escalation failure tests |
| `SEC-002`, `SEC-003` | mTLS/JWS validation, expiry, nonce replay, forged identity/thread/epoch/capability tests |
| `SEC-004`, `SEC-005`, `SEC-006` | Existing audience matrix baseline, credential/IAM static checks, and PII scan |
| `SEC-007`, `SEC-008`, `SEC-009`, `SEC-010` | Injection, capability/parameter/actor enforcement, asset validation, and reasoning absence tests |
| `HA-003`, `HA-004`, `HA-005` | Python kill harness at all four Graph crash boundaries |
| `HA-008`, `HA-009` | LiteLLM/provider timeout, 429, 5xx, truncated stream, invalid JSON, retry and breaker tests |
| `HA-011` | Graph PostgreSQL failover/reconnect test with checkpoint recovery and stale-lease rejection |
| `OBS-001`, `OBS-002`, `OBS-003`, `OBS-004` | Trace propagation and immutable manifest assertions without hidden reasoning |
| `MIG-003` | Aggregate Phase 3 report proving every prerequisite above before any room migration |

## Frozen P3.0 Decisions

The implementation slices start from these decisions and may not reinterpret them locally:

1. Command status is the six-value text `CHECK` state machine defined above; graph `FAILED` remains
   a valid completed result and is not an infrastructure status.
2. Command and result self-hashes omit their own top-level hash member before RFC 8785 encoding; the
   shared fixed-vector fixture must pass in Java and Python before gateway work merges.
3. G001-G003 use the exact table responsibilities, registry states, parity fields, and retention
   rules in this pack. DDL remains expand-only and records a checksum in the migration ledger.
4. One thread has one 30-second database-clock lease, token starts at 1, renewal is every 10 seconds,
   cancellation increments the token, and fenced saver writes use the same direct connection and
   transaction as the locked lease predicate.
5. `thread_id` is an opaque Java-persisted UUIDv7 wire identifier. Full signed and registry bindings,
   rather than parsing the identifier, authorize private, shared-Hearing, and reviewer threads.
6. The invocation token is compact ES256 JWT/JWS with the fixed header, claims, 60-second lifetime,
   5-second skew, 24-hour durable nonce retention, and fresh-signature retry policy above.
7. State, patch, message, summary, pending-work, Artifact, and fan-out limits are numeric hard limits;
   there is no application compression, and migration-safe/version-retirement predicates are fixed.
8. Shadow comparison stores only the fixed bounded parity data, defaults to 30-day retention, and
   can never serve a formal Finalizer.

Any incompatible change requires an additive contract version or accepted ADR, updated shared
fixtures, and notification to every owner. A local green test is not permission to weaken these
decisions.
