# Phase 4 P4.0 Entry Checkpoint

## Decision

```text
P4.0: PASS
engineering_execution: ALLOWED_WITH_DISABLED_SIGNED_SYNTHETIC_SHADOW_RESTRICTIONS
promotion_gate: PENDING
MIG-003: PENDING_PROMOTION
MIG-004: PENDING_PROMOTION
formal_intake_writer: FORBIDDEN
```

This checkpoint authorizes Phase 4 engineering implementation only. It does not mark the Phase 4
engineering checkpoint complete, allocate an Intake room to a Temporal writer, admit real-case
shadow traffic, or authorize canary or production promotion.

## Candidate Binding

- Tested contract candidate: `cf1ae3533bf2525ee43574e81c45621f29e338a0`
- Candidate source branch: `codex/temporal-langgraph-room-refactor`
- Verification worktree: clean detached HEAD before source-suite execution
- Phase 3 candidate: `9351a9d65230ce5bfc332bc59ec567ecb8a964c5`
- Phase 3 evidence commit: `ffa24bba9848e7492b9946c68e5e56977f9494ce`
- Entry evidence: `test-reports/temporal-first/phase-4-entry-20260720-r3/phase-4-entry/`

The candidate HEAD, tracked worktree, and index were unchanged after every accepted source suite.
Reports from `fd8d1a1b`, `e123d3e3`, and the latter's diagnostic frontend run were not reused.

## Batch 0 Result

| Source suite | Tests | Failures | Errors | Skipped | Exit |
| --- | ---: | ---: | ---: | ---: | ---: |
| Static contract and boundary gates | 24 | 0 | 0 | 0 | 0 |
| Python Intake, stream, security, and Runnable baseline | 70 | 0 | 0 | 0 | 0 |
| Java Intake, session, persistence, selector, and stream baseline | 83 | 0 | 0 | 0 | 0 |
| Frontend Intake, overview, shell, stream, and room-store baseline | 120 | 0 | 0 | 0 | 0 |
| **Total** | **297** | **0** | **0** | **0** | **0** |

The normalized JUnit roots bind the full candidate SHA and their `p4_entry_*` command IDs. The
machine-readable metrics record the actual commands, UTC boundaries, durations, exit codes,
environment versions, dependency-manifest hashes, report hashes, and protected worktree exception.
Testcase `system-out` and `system-err` nodes are removed before archival so ephemeral credentials,
machine paths, container metadata, and verbose framework logs do not enter durable evidence.

## Baseline Coverage

All 27 frozen baseline IDs are `PASS` for the pre-refactor candidate:

```text
INT-001..010
OVR-003
CORE-004..010
SEC-001..006
UI-001, UI-003, UI-004
```

`baseline-id-coverage.json` binds every ID to exact JUnit report classnames. Focused component tests
authenticate the frozen UI baseline; they do not claim cross-browser parity or a production E2E
checkpoint.

## Failure Closure

- `fd8d1a1b` is quarantined as `FIXTURE`: the test allocator bypassed terminal LEGACY selection;
  its parent reproduced the same two Java errors.
- `e123d3e3` is quarantined as `FIXTURE`: the matrix frontend process executed zero tests because
  of Vitest worker bounds and detached `pnpm exec` behavior. Its 120-test diagnostic run is not
  accepted evidence.
- Two accepted-candidate Java collection preflights executed zero tests before the successful
  source suite. They are recorded as reporting-wrapper `FIXTURE` events and contribute no report.
- There are no open `PRODUCT` failures.

## Authorized Next Step

Wave A owners A, B, and C may start from the entry-evidence commit containing this checkpoint.
Runtime remains limited to `DISABLED` or signed synthetic `SHADOW`. The following remain forbidden:

- real-case or production shadow traffic;
- `TEMPORAL` Intake allocation or formal Java writer replacement;
- formal Finalizer resolution from a shadow registry;
- canary, production promotion, or any claim that `MIG-003` or `MIG-004` passed.

The unrelated user deletion `docs/api/README.md` remained untouched and unstaged.
