# Phase 3-A Graph persistence entry notes

> Status: `P3.0 CONTRACT REVIEWED`. This file is the persistence-owner deep reference; the shared
> contract pack and Phase 3 execution plan remain authoritative.

## 1. Gate and baseline

Phase 3 engineering is allowed only under ADR 0008:

```text
engineering_permission: DISABLED_OR_SHADOW_ONLY
promotion_gate: PENDING
formal_graph_writer: FORBIDDEN
room_cutover_or_migration: FORBIDDEN
```

The following baseline was inspected at `e42925d8`:

- `requirements.txt` pins `langgraph==1.2.6`, `rfc8785==0.1.4`, and
  `jsonschema==4.26.0`, but does not directly pin LangChain Core, the PostgreSQL
  checkpointer, Psycopg, a pool, or OTel.
- `requirements-dev.txt` has Pytest only. There is no Hypothesis or PostgreSQL integration harness.
- `Settings` is process-cached and currently has no Graph mode, DSN, schema, pool, or migration
  contract.
- Python has no migration directory or migration runner. Domain migrations are Java/Flyway owned
  and must not be reused for Graph state.
- Local Compose creates several logical databases with one bootstrap superuser. It does not create
  an isolated Graph database or roles, and Python receives no PostgreSQL credentials.
- Python currently depends only on LiteLLM and Langfuse health. `/health` is liveness-only and does
  not prove a Graph schema is usable.
- The Python 3.11 slim image copies only `requirements.txt` and `app/`; future Graph SQL migrations
  will need an explicit image copy step for `migrations/graph/`.

## 2. Exact dependency contract

The versions below were checked against their published package metadata on 2026-07-19. Keep the
existing `langgraph==1.2.6`; its constraints are `langchain-core>=1.4.7,<2` and
`langgraph-checkpoint>=4.1,<5`. `langgraph-checkpoint-postgres==3.1.0` uses the same checkpoint
range and requires Psycopg/Psycopg Pool 3.2 or newer.

### Runtime direct pins

| Package | Exact pin | Reason |
| --- | --- | --- |
| `langgraph` | `langgraph==1.2.6` | Preserve the already selected Graph runtime for this phase. |
| `langchain-core` | `langchain-core==1.4.9` | Make the Runnable/Message/ChatModel protocol a direct dependency. |
| `langgraph-checkpoint-postgres` | `langgraph-checkpoint-postgres==3.1.0` | Production checkpoint implementation; no `MemorySaver` recovery path. |
| `psycopg` | `psycopg[binary]==3.3.4` | Binary wheel is required by the slim image; avoid an implicit system `libpq` dependency. |
| `psycopg-pool` | `psycopg-pool==3.3.1` | Explicit bounded async pool dependency. |
| `PyJWT` | `PyJWT[crypto]==2.13.0` | ES256 verification with maintained cryptography primitives. |
| `rfc8785` | `rfc8785==0.1.4` | Preserve the shared canonical hash protocol. |
| `jsonschema` | `jsonschema==4.26.0` | Preserve contract validation behavior. |
| `opentelemetry-api` | `opentelemetry-api==1.44.0` | Graph persistence spans and metrics API. |
| `opentelemetry-sdk` | `opentelemetry-sdk==1.44.0` | Process-owned telemetry provider. |
| `opentelemetry-exporter-otlp-proto-http` | `opentelemetry-exporter-otlp-proto-http==1.44.0` | Matches the repository's OTLP/HTTP collector contract. |

`langfuse==4.11.0` already accepts OTel `>=1.33.1,<2`, so the OTel pins above are compatible.

### Development direct pins

| Package | Exact pin | Reason |
| --- | --- | --- |
| `hypothesis` | `hypothesis==6.156.9` | Reducer, command/hash, and lease state-machine properties. |
| `testcontainers[postgres]` | `testcontainers[postgres]==4.14.2` | PostgreSQL 16 migration, ACL, crash-boundary, and concurrency tests. |
| `pip-tools` | `pip-tools==7.6.0` | Generate a fully resolved, hash-locked deployment file. |

Direct pins alone are not a reproducible supply-chain boundary. P3.1 must generate a committed
`requirements.lock` containing every transitive package and SHA-256 hash, run `pip check`, and make
the Docker image install the lock with `--require-hashes`. Dependency upgrades are separate reviewed
changes, never an incidental re-resolve during an image build.

## 3. Database and role isolation

### 3.1 Topology

- Logical database: `dispute_graph` by default (`GRAPH_DB_NAME`). Production may use a separate
  cluster; local Compose may share the PostgreSQL 16 server, but not the logical database or role.
- Schema: `graph_runtime` (`GRAPH_DB_SCHEMA`) for LangGraph internal checkpoint tables and G001-G003.
- Fixed search path: `graph_runtime,pg_catalog`. No writable `public` schema appears in the path.
- Domain, Temporal, Langfuse, and LiteLLM databases are not reachable with Graph credentials.
- Java receives neither the Graph runtime nor migration credential. Python receives no Java Domain
  DSN or write credential.

### 3.2 Roles

| Role | Login | Contract |
| --- | --- | --- |
| `graph_owner` | No | Owns database objects; never supplied to a container. |
| `graph_migrator` | Migration job only | May assume the owner role and run setup/G migrations. It is absent from the application pod. |
| `graph_runtime` | Python only | `CONNECT`, schema `USAGE`, and least-privilege DML only. No schema/database DDL or role membership. |
| `graph_retention` | Scheduled maintenance only | May delete approved expired shadow/checkpoint data after reference checks. |

Required grants and revocations:

1. Revoke `CONNECT` and schema creation from `PUBLIC`; grant explicit database access per role.
2. Revoke `CREATE` on `public` and `graph_runtime` from `graph_runtime`.
3. Revoke Domain database `CONNECT` from `PUBLIC`; grant it only to explicit Domain roles. This is
   required to prove `SEC-005`, not just to hide the Domain DSN.
4. Runtime may select/insert/update checkpoint tables and G001 runtime tables. It has no migration
   ledger update, registry mutation, table delete, or shadow TTL purge privilege.
5. Registry/version rows are migration/admin written and runtime read-only. Results are append-only.
6. Set owner default privileges explicitly so a later migration cannot silently omit runtime grants.

All identifiers are bounded strings, timestamps are `timestamptz`, revisions/tokens are non-negative
`bigint`, and hashes are lowercase `char(64)` values checked by `^[0-9a-f]{64}$`. Use `CHECK` values,
not PostgreSQL enum types, so additive status evolution remains migratable.

## 4. G001-G003 schema contract

### 4.1 Migration control tables

The migration runner bootstraps two control tables before G001:

- `graph_schema_migration(version, sha256, applied_at, package_versions, execution_id)` records one
  immutable checksum per migration. A repeated version with another checksum fails closed.
- `graph_runtime_control(environment_generation, migration_status, restore_status, verified_at,
  verification_hash)` is written by the migration/restore validation job. Readiness requires
  `migration_status='CURRENT'` and `restore_status='VERIFIED'`.

Neither table is writable by `graph_runtime`.

### 4.2 `G001_graph_runtime.sql`

#### `graph_thread_registry`

The row binds an opaque `thread_id` to these immutable scope fields:

- tenant surrogate, case ID, room type and room epoch;
- canonical actor-scope JSON plus its RFC 8785 hash;
- agent/reviewer session or the fixed shared-hearing scope;
- graph key/version and checkpoint schema version;
- lifecycle status, cognitive revision, last checkpoint namespace/ID, and timestamps.

The primary key is `thread_id`. A unique scope key prevents two thread IDs for the same
tenant/case/room-epoch/actor-scope/session/graph version. Scope and version columns are immutable.
Shared Hearing scope can contain only formal artifact references and cannot store private transcript
text. `cognitive_revision` advances by compare-and-set and never decreases.

#### `agent_graph_command`

Required fields include:

- `(thread_id, command_id)` primary key;
- request schema, canonical validated command JSON (maximum 64 KiB), and RFC 8785 request hash;
- execution mode, deadline, graph/checkpoint/prompt/model/policy/guardrail bindings;
- status, attempt count, current fencing token, start/committed checkpoint references;
- result reference/hash, retry-safe error code, and lifecycle timestamps.

Allowed lifecycle is explicit: `REGISTERED -> EXECUTING -> RESULT_CHECKPOINTED -> COMPLETED`, with
terminal `CANCELLED` or `ABORTED`. Recoverable provider/transport failures are attempt facts and a
lease takeover remains `EXECUTING`; they do not add command states. Immutable columns and request
JSON/hash are protected by a database trigger.

On duplicate command:

- same `(thread_id, command_id, request_hash)` returns the existing state/result;
- a different request hash raises a typed security conflict and performs no update/model call.

#### `agent_graph_result`

There is exactly one immutable result row per `(thread_id, command_id)`. It stores result schema,
execution mode, checkpoint namespace/ID, cognitive revision, one of `COMPLETED`, `NEEDS_INPUT`,
`NEEDS_REVIEW`, or `FAILED`, bounded result JSON (maximum 64 KiB), RFC 8785 result hash, usage and
creation metadata. Insertion and the command's completed/result reference update occur in one short
fenced transaction.

Do not make `result_hash` globally unique: two valid commands may produce identical canonical
output. The uniqueness contract is one immutable command-bound result, with a unique
`(thread_id, command_id, result_hash)` binding.

#### `agent_graph_lease`

One row per thread stores owner/execution ID, command ID, monotonically increasing fencing token,
status, acquisition/renewal/expiry/release timestamps, and a row revision. Database time is
authoritative.

- Acquire/takeover is one conditional `INSERT ... ON CONFLICT ... DO UPDATE` that increments the old
  token and succeeds only for a missing, expired, released, or explicitly cancelled lease.
- Renewal/release matches thread, owner, command, and token and must affect exactly one row.
- Model/network work never holds a database transaction or lease row lock.
- Every checkpoint, pending-write, command, thread revision, and result commit takes the lease row
  lock and validates owner/token in the same short transaction as its write.

### 4.3 Fenced `PostgresSaver` requirement

`AsyncPostgresSaver` internal tables do not enforce the application lease token. Therefore a raw
`AsyncPostgresSaver(pool)` must never be injected into a compiled Graph.

Expose a `FencedPostgresSaver` adapter that:

1. Accepts only a trusted runtime context containing thread, command, owner, and fencing token.
2. For `aput` and `aput_writes`, acquires one pool connection and begins a short transaction.
3. Locks `agent_graph_lease`, validates the unexpired owner/token/command, then delegates to an
   `AsyncPostgresSaver` bound to that same connection.
4. Commits the lease check and checkpoint write atomically. A zero-row/stale check is a typed
   non-committing fence error.
5. Persists command ID/hash, cognitive revision, checkpoint schema, and fence in checkpoint metadata
   so the checkpoint-to-ledger reconciler can prove identity.

If the old writer locks and commits before takeover, that checkpoint precedes takeover and is valid.
If takeover locks first, the old token is rejected. A separate check followed by a pool-based saver
call is not sufficient because takeover can occur between those operations.

### 4.4 `G002_graph_version_registry.sql`

`agent_graph_version_registry` is keyed by graph key, graph version, and checkpoint schema version. It
contains state schema hash, code/build ID, command/result contract versions, prompt/model/policy/
guardrail bindings and hashes, activation/retirement timestamps, `accepts_new_threads`, and
`loadable`.

- G002 adds a restrictive foreign key from `graph_thread_registry` to the exact version row.
- Java/room epoch supplies the version. Python validates the exact binding; it never silently picks
  a newer active version.
- Retirement first sets `accepts_new_threads=false`; active references remain `loadable=true`.
- Delete is forbidden while any thread, command, result, checkpoint manifest, or audit manifest
  references the version. Runtime has read-only access.

### 4.5 `G003_shadow_comparison.sql`

`agent_graph_shadow_comparison` stores a generated comparison ID, tenant/case labels, candidate thread and
command, graph/version, input/legacy/candidate immutable refs and hashes, comparator version,
bounded normalized diff, invariant outcomes, status, created time, and `expires_at`.

- Every row is `SHADOW` and has `formal_eligible=false` enforced by a check constraint.
- It stores refs/hashes and normalized fields, not raw private prompts, transcripts, or hidden
  reasoning.
- There is no Domain foreign key and no Domain write credential.
- Formal Finalizer code has no repository/import path to this table.
- Only `graph_retention` can purge expired rows; indexes cover expiry and graph/version/time.
- The default expiry is 30 days; evidence-manifest references block deletion and produce a cleanup receipt.

## 5. Migration and startup contract

Production startup ordering is:

```text
Graph DB/roles -> one-shot graph-migrate job -> schema probe -> Python readiness -> shadow traffic
```

The application replicas do not compete for DDL.

1. The migration job connects directly to PostgreSQL as `graph_migrator`, fixes the search path, and
   obtains a session advisory lock scoped to database/schema. It must not use transaction-mode
   PgBouncer for this lock.
2. Under the lock it runs `AsyncPostgresSaver.setup()` once for the pinned package, then applies
   G001, G002, and G003 in order. Each application migration and its checksum ledger row commit in
   the same transaction.
3. Gaps, reordered versions, checksum drift, insufficient grants, unknown future schema, or a dirty
   prior execution fail the job. There is no automatic downgrade or destructive repair.
4. The job runs bounded consistency probes and writes the verified runtime-control marker before it
   exits successfully.
5. Compose/Kubernetes starts Python only after the job succeeds. Local-only locked setup may be
   enabled explicitly, but it still uses the migration role and advisory lock. Production app
   startup never calls `setup()`.
6. Rollback stops new Graph selection and runs old code/schema versions. G tables and checkpoint
   tables remain; drops and in-place checkpoint rewrites are forbidden.

The local PostgreSQL init script must eventually create `GRAPH_DB_NAME` and isolated roles. Passwords
come from local secret placeholders or the deployment secret manager, not SQL files, image layers,
logs, or the runtime settings dump.

## 6. Pool, saver, and readiness lifecycle

### 6.1 Configuration

Add validated settings with these initial defaults:

| Setting | Default/constraint |
| --- | --- |
| `graph_gateway_mode` | `DISABLED`; Phase 3 permits only `DISABLED` or `SHADOW`. |
| `graph_database_dsn` | Secret and optional/unused in `DISABLED`; required in `SHADOW`. |
| `graph_database_schema` | `graph_runtime`; identifier allowlist only. |
| `graph_pool_min_size` | `2` (`0` in isolated tests only). |
| `graph_pool_max_size` | `16`, must be at least min size. |
| `graph_pool_max_waiting` | `64`; excess admission fails quickly. |
| `graph_pool_acquire_timeout_seconds` | `3.0`, positive and no greater than command budget. |
| `graph_pool_max_idle_seconds` | `300`. |
| `graph_pool_max_lifetime_seconds` | `1800`. |
| `graph_readiness_timeout_seconds` | `2.0`. |
| expected migrations | Checkpointer migration signature plus `G003` checksum set. |

The runtime settings model must reject a migration/owner DSN, a formal mode, invalid pool bounds, or
SHADOW without a runtime DSN. DISABLED may receive a pre-provisioned runtime DSN but must not open or
probe it. Secret values are excluded from repr, JSON, logs, and OTel attributes.

### 6.2 Process lifecycle

- Construct one `AsyncConnectionPool(open=False)` in the FastAPI lifespan on the serving event loop.
- Connection kwargs are `autocommit=True`, `prepare_threshold=0`, and `row_factory=dict_row`, as
  required by the PostgreSQL saver. Set a bounded connect timeout, application name, statement/
  lock/idle-in-transaction timeouts, and a connection health callback.
- Open and `wait()` once before reporting ready. Create one process-lifetime fenced saver facade;
  never create pools/savers per request or put them into Graph state.
- Ledger operations use explicit short `conn.transaction()` blocks even though pool connections are
  autocommit by default.
- Admission control, not 1,000 database connections, handles room scale. Enforce
  `replicas * graph_pool_max_size + migration/admin headroom <= database/PgBouncer budget`.
- On shutdown, reject new Graph commands, drain bounded in-flight persistence work, then close the
  pool. A forced timeout cancels work without reporting a commit.

### 6.3 Readiness and fail-closed behavior

Keep liveness independent from PostgreSQL. Add a separate readiness probe:

- In `DISABLED`, no Graph pool is created and Graph selection/endpoints remain disabled.
- In `SHADOW`, readiness requires a pool connection within timeout, the exact database/user/schema,
  runtime `USAGE` but no `CREATE`, all LangGraph and G001-G003 tables, exact migration checksums,
  a verified restore marker, and bounded ledger/lease consistency queries.
- Readiness is read-only. It never runs `setup()`, applies DDL, repairs rows, or falls back to
  `MemorySaver`/SQLite/in-memory state.
- Missing schema/ledger/lease/registry, wrong role/search path, pool exhaustion, checksum mismatch,
  database loss, or an unverified restore returns not-ready/503 and rejects Graph commands before a
  model call.
- After backup restore, a separate validation job checks checkpoint-command-result-fence references
  and marks the restored generation verified. App replicas cannot self-approve a restore.

The existing `/health` compatibility endpoint may remain liveness during the transition, but the
container/orchestrator readiness check must move to the new graph-aware endpoint when SHADOW is
enabled.

## 7. Focused test matrix

These tests provide evidence; this notes task does not mark any check as passed.

| Level | Required scenarios | Primary checks |
| --- | --- | --- |
| Pure config/dependency | Disabled without DSN; SHADOW requires runtime DSN; formal/migration DSN rejected; pool bounds; exact installed versions and `pip check`. | GRAPH-001/002, SEC-005 |
| Migration/PostgreSQL 16 | Clean setup, idempotent rerun, two-job advisory-lock race, checksum drift, gap/order failure, transactional rollback, pinned checkpointer migration signature. | GRAPH-001/002 |
| ACL | Runtime DML succeeds; runtime DDL/registry delete/migration update fails; runtime cannot connect Domain DB; Java role cannot write Graph DB. | SEC-005 |
| Pool/lifecycle | One pool per lifespan, no pool when disabled, bounded acquire/waiters, startup failure, graceful drain/close, no secret/state leakage. | GRAPH-002/010/011 |
| Command ledger | Concurrent same command/hash returns one result and one model-call permit; different hash is immutable conflict; expired/cancelled command cannot run. | GRAPH-003/004 |
| Lease/fence | Concurrent acquire, expiry/takeover, monotonic token, renew/release ownership, stale command/result/thread commit rejection. | GRAPH-005/007 |
| Fenced saver | Stale `aput` and `aput_writes` rejected; takeover race on either side of row lock; raw saver static-import/injection test fails. | GRAPH-001/005 |
| Crash recovery | Before model; after model before checkpoint; after checkpoint before ledger completion; after command completion before response. Assert provider call counts. | GRAPH-006, HA-003/004/005 |
| Readiness/restore | Missing each migration/table, wrong role/schema, DB down, pool full, unverified restore, valid restored checkpoint/ledger/fence chain. | GRAPH-002, HA-011, DR-002 |
| G002/G003 | Version pin/retire/load; active-reference delete blocked; shadow row never formal-eligible; TTL only through retention role; no raw private text. | GRAPH-008/009, SEC-003/004/005 |
| Capacity | 100 async synthetic commands, pool wait bounds, checkpoint p95/state bytes/lease contention metrics; no unbounded task or connection creation. | PERF-009, OBS-002/003 |

Use `tests/graph_runtime/unit/`, `tests/graph_runtime/integration/`, and
`tests/static/test_graph_import_boundaries.py`. PostgreSQL integration runs serially per isolated
database; pure/property tests may run in parallel. Full crash/failover/restore and 100-concurrency
checks stay at the Phase 3 unified checkpoint, not after every slice.

## 8. Suggested ownership for implementation

### Persistence agent owned paths

```text
python-agent-service/requirements.txt
python-agent-service/requirements-dev.txt
python-agent-service/requirements.lock
python-agent-service/pyproject.toml
python-agent-service/migrations/graph/**
python-agent-service/app/graph_runtime/checkpoint.py
python-agent-service/app/graph_runtime/migrations.py
python-agent-service/app/graph_runtime/readiness.py
python-agent-service/app/graph_runtime/persistence_models.py
python-agent-service/tests/graph_runtime/unit/test_checkpoint_*.py
python-agent-service/tests/graph_runtime/integration/test_graph_persistence_*.py
python-agent-service/tests/static/test_graph_import_boundaries.py
```

`app/graph_runtime/ledger.py` and `lease.py` should move to the later P3.3 owner after the G001 port
interfaces and transaction/fence contract are frozen.

### Primary/shared integration paths

```text
python-agent-service/app/config.py
python-agent-service/app/main.py
python-agent-service/Dockerfile
docker-compose.yml
.env.example
deploy/postgresql/init-multiple-databases.sh
```

These files cross service/bootstrap ownership and should have one primary integrator after focused
agent commits, with an immediate Python context/readiness smoke test.

### Forbidden for Phase 3-A persistence work

```text
docs/api/README.md
java-api-service/src/main/resources/db/migration/**
java-api-service/src/main/java/**
python-agent-service/app/agents/**
python-agent-service/app/graphs/**
python-agent-service/app/model_runtime/**
python-agent-service/app/api/**
python-agent-service/app/security/**
frontend/**
```

The persistence layer must not import a Domain adapter, FastAPI request, model/provider client,
prompt repository, tool implementation, or `MemorySaver`. P3.0 review must settle this contract
before P3.1/P3.2 production edits begin.
