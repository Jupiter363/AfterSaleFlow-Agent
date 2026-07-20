# Phase 4 P4.0 Intake Baseline Inventory

## Status

```text
inventory_status: FROZEN_FOR_BATCH_0
baseline_result: NOT_RUN
tested_candidate_sha: PENDING
implementation_gate: BLOCKED
```

```text
covered_baseline_ids:
INT-001, INT-002, INT-003, INT-004, INT-005, INT-006, INT-007, INT-008, INT-009, INT-010,
OVR-003,
CORE-004, CORE-005, CORE-006, CORE-007, CORE-008, CORE-009, CORE-010,
SEC-001, SEC-002, SEC-003, SEC-004, SEC-005, SEC-006,
UI-001, UI-003, UI-004
```

This inventory authenticates the current Intake behavior before Phase 4 implementation. It is not
a PASS claim. Batch 0 runs the listed focused suites from the exact P4.0 contract-candidate SHA in
a clean detached worktree. The later entry-evidence commit records the tested SHA, commands,
durations, exit codes, JUnit paths, report hashes, ID coverage, and any classified failure.

## Baseline Coverage

| Baseline IDs | Required behavior | Focused executable sources |
| --- | --- | --- |
| `INT-001`, `SEC-001..003` | Exact actor/session/private-message isolation and no reviewer/admin impersonation | `AgentConversationSessionResolverTest`, `IntakeAgentTurnServiceTest`, `IntakeRoomServiceTest`, `IntakeRoomServiceIntegrationTest`, `IntakeRoomControllerTest`, `IntakeRoomView.test.js` |
| `INT-002..007`, `OVR-003` | Initiator accept/reject/cancel, respondent lock and independent completion, bilateral freeze, Evidence handoff once | `IntakeSequentialWorkflowTest`, `IntakeRoomServiceTest`, `IntakeRoomServiceIntegrationTest`, `IntakeProgressServiceTest`, `IntakeRoomControllerTest`, `DisputeOverviewView.test.js`, `IntakeRoomView.test.js` |
| `INT-008` | Original statement and external reference fidelity | `test_intake_turn.py`, `test_intake_case_detail_dossier.py`, `IntakeAgentTurnServiceTest` |
| `INT-009`, `CORE-004`, `CORE-006`, `CORE-008`, `CORE-009` | Immediate own-message visibility, active-run recovery, bounded stream lifecycle, final authority, attempt reset semantics | `AgentRunStreamEventServiceTest`, `RoomMessageAndEventServiceTest`, `test_streaming.py`, `test_streaming_v2.py`, `agentStream.test.js`, `stores/agentStream.test.js`, `IntakeRoomView.test.js` |
| `INT-010`, `CORE-007`, `SEC-004..006` | No memory/internal/reasoning leakage, trusted model boundary, no formal tool capability | `RoomTurnMemoryQueryServiceTest`, `test_intake_turn.py`, `test_graph_security_runtime.py`, `test_runnable_factory.py`, `IntakeAgentTurnServiceTest`, `IntakeRoomView.test.js` |
| `CORE-005` | Role change/page leave clears prior scope and rejects late results | `stores/room.test.js`, `stores/agentStream.test.js`, `IntakeRoomView.test.js` |
| `CORE-010` | Completed Intake is history-read-only | `IntakeRoomView.test.js`, `IntakeRoomControllerTest`, `IntakeRoomServiceTest` |
| `UI-001`, `UI-003`, `UI-004` | 740px shell, 1060px breakpoint, accessible focus controls, bounded long content | `RoomShell.test.js`, `IntakeRoomView.test.js` |

## Batch 0 Commands

Commands remain source-controlled by
`plans/phase-4-intake-pilot-test-batches.yaml`. They must execute once per accepted entry candidate:

```text
root static contract/gate checks
Python Intake, stream, security, and Runnable baseline
Java Intake, private-session, memory, message/event, and stream baseline
Frontend Intake, overview, RoomShell, stream, and room-store baseline
```

No browser E2E, full repository regression, real provider, real case shadow, load, soak, failover, or
DR runs at P4.0. Those remain the agreed unified/external checkpoints. Focused unit/component
evidence may authenticate `UI-001/003/004`; it does not claim cross-browser production parity.

## Failure Policy

Every red item is classified before rerun:

| Class | Entry behavior |
| --- | --- |
| `PRODUCT` | Block implementation; fix the owning product path and create a new contract-candidate SHA |
| `FIXTURE` | Prove the unchanged baseline contract, correct the fixture without weakening assertions, and rerun the exact affected suite |
| `INFRA` | Preserve the failed attempt, restore the environment, and rerun only the failed source suite on the same SHA |
| `EXTERNAL_GATE` | Keep formal traffic and promotion blocked; do not convert the missing external evidence into an engineering failure |

The protected user deletion `docs/api/README.md` is an unrelated working-tree exception and must
remain untouched and unstaged throughout the gate.
