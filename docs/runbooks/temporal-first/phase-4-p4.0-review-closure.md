# Phase 4 P4.0 Independent Review Closure

- Review scope: Phase 4 execution plan, test matrix, current Intake/Graph/Temporal/Java/frontend code
- Review role: delegated implementation-map owner, read-only
- Gate result before fixes: `BLOCKED`
- Closure target: P4.0 contract candidate, not Phase 4 implementation

| Finding | Resolution in the contract candidate | Remaining implementation owner |
| --- | --- | --- |
| Global selector accepted `TEMPORAL` without an engineering lock | Added fail-closed non-LEGACY and TEMPORAL activation locks, defaults/config tests, and static gate | E replaces global semantics with the room-specific stable Intake selector before candidate exit |
| Batch 0 and Batch 3 did not cover the full declared baseline | Added all 27 IDs, Java/Python/frontend focused sources, `RoomShell`, stream/security/Runnable tests, and a baseline inventory | R generates exact candidate coverage evidence |
| `RoomGraphResult.v1` had only ref/hash and no complete Intake artifact protocol | Added immutable proposal-store flow plus six strict schemas, positive/negative fixtures, recursive forbidden-key checks, and RFC 8785 vectors | A implements proposal publication; C implements Java load/hash validation; R integrates transport |
| P4 task numbers lacked input/output/dependency/DoD | Added machine-readable task contracts for P4-0, R0G, A1/A2, B1/B2, C1/C2, D1/D2, E1/E2, R1/R2 | Each named owner |
| Shared implementation paths were unowned | Assigned `CaseProcessWorkflowImpl` and `production_bindings.py` to R; message/session/case Intake paths to C; projection/API/frontend paths to D | Named owner only |
| Recovery/static/V043 ownership overlapped | Removed delegated change-route overlap; E owns recovery harness, R owns static gates, C owns V043 with R review | R enforces briefs and staging boundaries |
| Case Workflow ignored persisted child type | Added `room-epoch-selection.v2`, distinct Case/room type/build fields, `typed-intake-room-child-v1` replay marker, and R1 DoD | R implements versioned typed child dispatch after Wave A foundations |
| `TEMP-020`/`TEMP-023` would be overclaimed and LCEL regressions were absent | Marked both Temporal checks `PARTIAL_ENGINEERING`; added LCEL/contract regression IDs and focused tests | B supplies partial evidence; A/R supply regressions |
| Batch 1 claimed persistent recovery while configured without a database | Limited Batch 1 to service-free contracts and explicitly deferred PostgreSQL/crash/transaction claims to Batch 2 | R owns the single heavy token in Batch 2 |
| `MIG-003=PENDING` had no explicit Phase 4 engineering exception | Added ADR 0011; real shadow, formal Finalizer, `TEMPORAL`, canary, and promotion remain forbidden | R reports both promotion states as pending |

## Closure Decision

The planning and contract findings are closed for purposes of freezing a P4.0 contract candidate.
This is not permission to implement P4-A1 through P4-E2 yet. The candidate must first pass Batch 0
from a clean detached worktree and its reports must be archived in a separate entry-evidence commit.

Typed child dispatch, V043, Graph/LCEL Intake runtime, Temporal branches, formal ports, projections,
selector replacement, parity, and recovery remain implementation work assigned to their owners.
Current runtime behavior remains `LEGACY`/`DISABLED`, except explicitly enabled signed synthetic
infrastructure that still cannot reach a formal sink.
