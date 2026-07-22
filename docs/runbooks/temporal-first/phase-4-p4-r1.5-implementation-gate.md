# Phase 4 P4-R1.5 Implementation Gate

## Decision

```text
P4-R1.5-IMPLEMENTATION-GATE: PASS
engineering_execution: PHASE_4_ENGINEERING_ONLY
graph_runtime: DISABLED_OR_SIGNED_SYNTHETIC_SHADOW
new_epoch_mode: LEGACY
real_case_shadow: FORBIDDEN
temporal_intake_allocation: FORBIDDEN
formal_intake_sink: FORBIDDEN
promotion_gate: PENDING
MIG-003: PENDING_PROMOTION
MIG-004: PENDING_PROMOTION
```

This gate closes the additive R1.5 authority implementation and permits the dependent Phase 4
engineering slices to proceed. It does not authorize real traffic, a `TEMPORAL` Intake epoch,
formal Finalizer assembly, canary, or production promotion.

## Candidate Binding

- Tested implementation candidate: `7b781f83126526602e81f52b0c162b462a242ac9`
- Candidate branch: `codex/temporal-langgraph-room-refactor`
- Verification date: `2026-07-22` (`Asia/Shanghai`)
- Protected worktree exception: the user's deletion of `docs/api/README.md` remained unstaged and
  was not included in the candidate or any R1.5 commit.

## Verification

The deduplicated Java batch first ran 104 tests and exposed three P0 gate failures. The accepted
result uses the repository failure policy: preserve the initial result, fix only the affected
scope, and rerun the complete failed classes instead of repeating every passing class.

| Verification | Result |
| --- | --- |
| R1.5 Java batch, unaffected cases | 101 passed |
| `JdbcIntakeChildBridgeReadPortTest` | 13 passed |
| `IntakeChildBridgeActivitiesTest` | 14 passed after SHADOW-gate fixture correction |
| `IntakeAuthorityWorkerRegistrationTest` and `IntakeFormalSinkAssemblyTest` | 11 passed after removing runtime reflection |
| `IntakeRoomWorkflowWorkerRecoveryTest` | 1 passed after compensating for the in-process Temporal test server's unsupported sticky reset |
| `TemporalWorkerConfigurationTest` | 7 passed |
| PostgreSQL `MigrationIntegrationTest` | 48 migrations applied, validated, and rerun idempotently; 1 test passed |
| Phase 4 no-formal-sink and R1.5 static gates | 63 passed |

The recovery fixture still performs an abrupt old-worker shutdown, replacement-worker replay,
idempotent duplicate command delivery, exact Activity-count checks, and a post-restart command. A
test-only helper clears the in-memory server's dead sticky route because temporal-test-server
1.35 does not implement `ResetStickyTaskQueue` or sticky fallback. Production code was not changed
for that harness limitation.

## Review Closure

- Independent SQL/schema review found no P0 in the bridge queries, joins, aliases, cardinality, or
  placeholder ordering.
- The bridge now validates persisted selection hashes and committed turn/branch evidence from the
  immutable Java ledgers.
- Dynamic reflection was removed from the discoverable worker-registration path; the v2 facade and
  contract are now strongly typed.
- The SHADOW bridge and dormant TEMPORAL formal ports intentionally do not interoperate under this
  gate. ADR 0011 and the R1.5 manifest require that fail-closed boundary. A future promotion
  contract must explicitly admit TEMPORAL authority before those formal ports can be registered.
- No current-scope P0 remains open. P1 and high-availability hardening are deferred and do not
  change the runtime restrictions above.

## Authorized Next Step

`P4-D1` versioned Intake projection and `P4-E1` Intake-only synthetic selector/parity work may now
start in parallel. Phase 5 implementation remains blocked until the Phase 4 engineering checkpoint
grants `PHASE_5_ENGINEERING_ONLY`; production promotion remains independently blocked.
