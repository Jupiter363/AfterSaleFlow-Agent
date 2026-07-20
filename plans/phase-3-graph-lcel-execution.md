# Phase 3 Graph And Governed LCEL Execution Plan

## Status

```text
plan_status: ENGINEERING_CHECKPOINT_COMPLETE
engineering_execution: ALLOWED_WITH_DISABLED_SHADOW_RESTRICTIONS
promotion_gate: MIG-003 PENDING
engineering_checkpoint: PASS
next_phase_permission: PHASE_4_ENGINEERING_ONLY
team_shape: primary + 3 delegated implementation agents
runtime_default: DISABLED
formal_room_writer: FORBIDDEN
```

The engineering candidate `9351a9d65230ce5bfc332bc59ec567ecb8a964c5` passed the unified
checkpoint on 2026-07-20: 525 Python, 30 static, and 273 Java tests, with no failures,
errors, or skips. The archived result and its exact limitations are recorded in
[Phase 3 Engineering Checkpoint](../docs/runbooks/temporal-first/phase-3-engineering-checkpoint.md)
and `test-reports/temporal-first/phase-3-20260720-r2/phase-3/`.

ADR 0008 authorizes engineering work only. Phase 3 may execute signed synthetic `SHADOW`
commands after its dependencies are ready, but it may not migrate a room epoch, call a formal
Finalizer, remove a legacy endpoint, or give Python Domain database write credentials. The shared
contract is [Phase 3 P3.0 Contract Pack](../docs/runbooks/temporal-first/phase-3-p3.0-contract-pack.md).

## Invariants

- Java remains the formal business writer; Temporal remains the owner of waits, time, and failure.
- Graph PostgreSQL is isolated from Domain and Temporal databases by database, role, pool, and
  migration ownership.
- Every graph selection defaults to `DISABLED`; Phase 3 has no formal-writer registry state.
- A command is canonical-hash bound, idempotent, version pinned, deadline bounded, signed, and
  replay protected before graph dispatch.
- A thread has one persisted lease. Every checkpoint/result/ledger completion is fenced in the same
  PostgreSQL transaction as the `PostgresSaver` write.
- State is bounded and serializable. Whole-state prompt injection, `MemorySaver` recovery, dynamic
  workflow JSON, and direct model HTTP from business nodes are forbidden.
- A model node runs a real `lens | prompt | model | parser | guardrail` Runnable object flow.
- Formal party waits return `NEEDS_INPUT` to Temporal; LangGraph does not own long-running waits.

## Team And Ownership

All delegated agents are implementation owners. They edit and test their owned paths directly and
return a commit-sized change; they are not review-only agents. They do not stage the user deletion
`docs/api/README.md` or any path outside their assignment.

| Owner | Runtime responsibility | Test responsibility | Forbidden boundary |
| --- | --- | --- | --- |
| A, persistence | Dependencies, Graph migrations, pool, fenced checkpointer, readiness | Migration, ACL, saver transaction, pool and restore tests | No ledger semantics, API, model runtime, Java, or frontend |
| B, gateway | Command ledger, lease, registry, gateway and recovery | Idempotency, fencing, version pin and crash-boundary tests | No migration ownership, LCEL, API signature implementation, Java, or frontend |
| C, state/model | Typed state, lens, reducers, terminal projector, governed model and LCEL | Property, object-flow, callback, async/stream and provider-policy tests | No SQL migration, ledger, API, Java, or frontend |
| R, primary | Shared contracts, config/lifecycle integration, signed endpoint, import boundaries and evidence | Test-token scheduling, cross-service contract/security checks and phase checkpoint | Does not duplicate delegated owned modules |

### Owner A Paths

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
```

### Owner B Paths

```text
python-agent-service/app/graph_runtime/errors.py
python-agent-service/app/graph_runtime/identity.py
python-agent-service/app/graph_runtime/ledger.py
python-agent-service/app/graph_runtime/lease.py
python-agent-service/app/graph_runtime/registry.py
python-agent-service/app/graph_runtime/gateway.py
python-agent-service/app/graph_runtime/recovery.py
python-agent-service/tests/graph_runtime/unit/test_ledger*.py
python-agent-service/tests/graph_runtime/unit/test_lease*.py
python-agent-service/tests/graph_runtime/unit/test_registry*.py
python-agent-service/tests/graph_runtime/unit/test_gateway*.py
python-agent-service/tests/graph_runtime/integration/test_graph_gateway*.py
```

### Owner C Paths

```text
python-agent-service/app/graph_runtime/state.py
python-agent-service/app/graph_runtime/state_lens.py
python-agent-service/app/graph_runtime/reducers.py
python-agent-service/app/graph_runtime/result.py
python-agent-service/app/graph_runtime/topology.py
python-agent-service/app/model_runtime/**
python-agent-service/app/harness/model_runner.py
python-agent-service/app/llm.py
python-agent-service/tests/graph_runtime/unit/test_state*.py
python-agent-service/tests/graph_runtime/unit/test_reducer*.py
python-agent-service/tests/graph_runtime/unit/test_result*.py
python-agent-service/tests/model_runtime/**
python-agent-service/tests/harness/test_model_runner.py
```

### Primary Paths

```text
contracts/agent-platform/v1/**
python-agent-service/app/graph_runtime/__init__.py
python-agent-service/app/model_runtime/__init__.py
python-agent-service/app/security/**
python-agent-service/app/api/graph_commands.py
python-agent-service/app/config.py
python-agent-service/app/main.py
python-agent-service/Dockerfile
docker-compose.yml
.env.example
deploy/postgresql/init-multiple-databases.sh
tests/static/test_graph_import_boundaries.py
tests/static/test_phase3_graph_execution_plan.py
plans/phase-3-*.md
plans/phase-3-*.yaml
docs/runbooks/temporal-first/phase-3-*.md
scripts/*phase*3*
test-reports/temporal-first/*/phase-3/**
```

Shared paths change only through a primary contract commit. If an owner needs one, it returns the
exact interface delta and continues on independent work until the primary publishes the change.

## Execution Waves

### Wave 0: P3.0 Contract Gate

The primary commits only the contract pack, canonical self-hash vectors, this execution plan, the
test-batch matrix, and its static test. The gate must prove:

- Phase 2 engineering checkpoint is `PASS` while promotion remains `PENDING`.
- ADR 0008 restricts runtime to `DISABLED` or signed synthetic `SHADOW`.
- G001-G003, ledger states, lease timing/fence transaction, thread identity, State limits, JWS
  claims, nonce retention, and shadow retention are numerically frozen.
- Dependency resolution succeeds for the exact pins.
- A, B, C, and R paths and Check IDs have one owner.

No runtime implementation starts from a base older than this commit.

### Wave 1: Independent Foundations

| Task | Owner | Scope | Depends on | Delivery |
| --- | --- | --- | --- | --- |
| `P3-A1` | A | Direct/locked dependencies, migration runner, G001-G003, isolated async pool, fenced saver, readiness probes | P3.0 | Persistence code, migrations and focused tests |
| `P3-B1` | B | Typed ledger/lease/registry ports and implementations, canonical identity validation, disabled/shadow selection | P3.0 | Runtime modules and pure/concurrency tests |
| `P3-C1` | C | Bounded typed state, State Lens, deterministic keyed reducers, routers, terminal projector | P3.0 | State modules and Hypothesis tests |
| `P3-R1a` | R | Settings/lifecycle contracts, import boundaries, ES256 verifier and transport-identity interfaces | P3.0 | Shared integration commit and security unit tests |

After one-at-a-time integration, the primary runs `P3-BATCH-1`. Failures are classified before any
rerun. Product and fixture fixes return to their owner.

### Wave 2: End-To-End Component Paths

| Task | Owner | Scope | Depends on | Delivery |
| --- | --- | --- | --- | --- |
| `P3-A2` | A | PostgreSQL ACL, migration race/checksum, restore/readiness, fenced saver takeover races | A1 and B1 ports | Integration tests and fixes |
| `P3-B2` | B | Validate -> nonce/register -> lease -> reconcile -> graph -> checkpoint -> result gateway and four-boundary recovery | A1, B1, C1 | Gateway/recovery tests and implementation |
| `P3-C2` | C | Native sync/async provider transport, `GovernedChatModel`, real Runnable chain, parser/guardrail, bounded retry/stream/callbacks | C1 and R1a policy ports | LCEL/model runtime and tests |
| `P3-R1b` | R | `/internal/graphs/commands/stream`, mTLS adapter, JWT/body binding, FastAPI lifespan, Compose/role integration | A1, B1, R1a | Endpoint and fail-closed integration tests |

The primary integrates in dependency order `A -> B -> C -> R`, then runs `P3-BATCH-2`. Only one
PostgreSQL/Testcontainers or other heavy process owns the test token at a time.

### Wave 3: Cross Review And Hardening

- A reviews B for same-connection fencing, transaction length, pool bounds and stale-writer paths.
- B reviews C for retry/deadline semantics, completion loss, model-call count and recovery metadata.
- C reviews A for checkpointer imports, state contamination, async lifecycle and callback evidence.
- R reviews every authority boundary, JWS/nonce path, version pin, hidden-reasoning exclusion,
  runtime default and Check-ID claim.
- Findings return to the owning agent for implementation. Reviewers do not silently edit another
  owner's path.

After fixes, the primary runs `P3-BATCH-3`, freezes one candidate commit, and creates evidence from
that same commit.

## Test Scheduling

The machine-readable policy is
[phase-3-graph-lcel-test-batches.yaml](./phase-3-graph-lcel-test-batches.yaml).

```text
active_primary_agents: 1
active_child_agents: 3
heavy_test_slots: 1
light_test_slots: 2
pytest_workers_for_db: 1
docker_compose_owner: primary
```

Delegated owners may run service-free checks under three minutes. PostgreSQL, Docker,
Testcontainers, a full Python suite, or a 100-concurrency run requires a `TEST_REQUEST`; the primary
grants one `TEST_TOKEN`. The primary runs deduplicated affected tests after each merged wave. Full
repository, browser, 1,000-room, soak, DR, and production failover verification remains the later
unified checkpoint and is not repeated per slice.

## Delegation Template

```text
task_id:
base_commit:
objective:
owned_paths:
forbidden_paths:
input_contracts:
required_check_ids:
tests_to_add_or_update:
allowed_local_checks:
tests_deferred_to_batch:
definition_of_done:
commit_message:
```

Every task brief states: "You are the implementation owner. Edit code and tests directly; do not
return review advice only." Every delivery returns commit/changed paths/tests/check IDs/shared
contract impact/remaining risk. An owner never stages unrelated changes.

## Engineering Exit

Phase 3 engineering checkpoint is `PASS` only when:

- P3.1-P3.8 implementation is integrated and all cross-review findings are closed.
- Graph PostgreSQL checkpoint, ledger, lease, registry and recovery paths pass from one candidate.
- Reducer properties and the real governed Runnable object flow pass, including async/stream and
  callback capture.
- Signed envelope, nonce replay, cross-scope, credential isolation and import boundaries fail
  closed.
- Any Python replica can restore a thread without local sticky state or a second model call after a
  committed terminal checkpoint.
- Runtime defaults remain `DISABLED`; synthetic `SHADOW` cannot reach a formal Finalizer.
- Evidence reports the three states separately:

```text
engineering_checkpoint: PASS | FAIL
promotion_gate: PASS | PENDING | FAIL
next_phase_permission: PHASE_4_ENGINEERING_ONLY | BLOCKED
```

`MIG-003` promotion remains `PENDING` until independent environment evidence and approval are
complete. An engineering pass does not authorize room migration or production cutover.
