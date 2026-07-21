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

## Contract Erratum Re-authentication

An independent C2 review found that the exact frozen Finalizer operation-key formula can reach 403
characters when its legal identifiers are at their contract bounds, while the original receipt and
ledger bound was 256. Commit `f626fca3` corrects the dedicated `operation_key` capacity to 512 in
the receipt schema, Java validator, JPA mapping, and V043. The formula remains lossless; no field is
truncated or replaced with another derived hash.

The amended contract was re-authenticated on 2026-07-21 from clean detached worktree
`.codex-run/phase4-erratum-f626fca3` at exact candidate
`f626fca36265af70bee061829f242d3cd1b67cb9`:

| Source suite | Tests | Failures | Errors | Skipped | Exit |
| --- | ---: | ---: | ---: | ---: | ---: |
| Static contract and boundary gates | 25 | 0 | 0 | 0 | 0 |
| Python Intake, stream, security, and Runnable baseline | 70 | 0 | 0 | 0 | 0 |
| Java Intake, session, persistence, selector, and stream baseline | 83 | 0 | 0 | 0 | 0 |
| Frontend Intake, overview, shell, stream, and room-store baseline | 120 | 0 | 0 | 0 | 0 |
| **Total** | **298** | **0** | **0** | **0** | **0** |

This supersedes `cf1ae353` only as the current P4.0 contract baseline. It does not authorize a new
runtime mode or formal traffic: engineering execution remains restricted to `DISABLED` or signed
synthetic `SHADOW`, and `promotion_gate`, `MIG-003`, and `MIG-004` remain `PENDING`.

## Matrix Proposal Erratum Re-authentication

The contract candidate containing
[`phase-4-p4.0-matrix-authority-erratum.md`](./phase-4-p4.0-matrix-authority-erratum.md) adds the
missing strict `case_fact_matrix.delta.v2` respondent proposal branch while retaining the strict
unilateral draft and `null` branches. The corrected contract was independently reviewed and
re-authenticated on 2026-07-21 from clean detached worktree
`.codex-run/phase4-matrix-erratum-0740c9b7` at exact candidate
`0740c9b73b7385249ed5645cf1dee10909173049`:

| Source suite | Tests | Failures | Errors | Skipped | Exit |
| --- | ---: | ---: | ---: | ---: | ---: |
| Static contract and boundary gates | 30 | 0 | 0 | 0 | 0 |
| Python Intake, stream, security, and Runnable baseline | 70 | 0 | 0 | 0 | 0 |
| Java Intake, session, persistence, selector, and stream baseline | 83 | 0 | 0 | 0 | 0 |
| Frontend Intake, overview, shell, stream, and room-store baseline | 120 | 0 | 0 | 0 | 0 |
| **Total** | **303** | **0** | **0** | **0** | **0** |

The immutable JUnit reports, exact commands, durations, hashes, environment classification, and
independent-review result are archived under
`test-reports/temporal-first/phase-4-matrix-erratum-20260721-r1/phase-4-entry`.

Its status is:

```text
matrix_erratum_candidate: PASS_AT_0740c9b73b7385249ed5645cf1dee10909173049
implementation_integration: AUTHORIZED_FOR_PHASE_4_ENGINEERING_ONLY
previous_authenticated_candidate: f626fca36265af70bee061829f242d3cd1b67cb9
MIG-003: PENDING_PROMOTION
MIG-004: PENDING_PROMOTION
```

The earlier 298-test result remains historical evidence for
`f626fca36265af70bee061829f242d3cd1b67cb9`; it is not relabelled as matrix-erratum evidence.
Runtime restrictions remain unchanged: this PASS permits implementation integration only and does
not authorize real shadow traffic, `TEMPORAL` allocation, a formal Finalizer runtime sink, canary,
or promotion.
