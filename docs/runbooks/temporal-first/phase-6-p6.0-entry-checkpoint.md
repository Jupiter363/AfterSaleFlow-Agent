# Phase 6 P6.0 Entry Checkpoint

## Decision

```text
P6.0: PASS
contract_gate: P6.0 PASS
engineering_execution: ALLOWED_WITH_DISABLED_OR_JAVA_SIGNED_SYNTHETIC_SHADOW_RESTRICTIONS
next_phase_permission: PHASE_6_ENGINEERING_ONLY
promotion_gate: PENDING
MIG-004: PENDING_PROMOTION
MIG-005: PENDING_PROMOTION
MIG-006: PENDING_PROMOTION
formal_hearing_runtime: LEGACY_JAVA_HEARING_FLOW_V2
real_or_party_data_shadow: FORBIDDEN
temporal_hearing_allocation: FORBIDDEN
formal_graph_sink: FORBIDDEN
canary: FORBIDDEN
promotion: FORBIDDEN
java_hearing_business_writer: SOLE_FORMAL_WRITER
```

This checkpoint authorizes Phase 6 engineering implementation only. It is not the Phase 6
engineering checkpoint, does not grant Phase 7 permission, and has no production-promotion effect.
The current formal Hearing path remains Java-owned `hearing_flow.v2`; new Phase 6 execution remains
`DISABLED` or Java-signed synthetic `SHADOW`.

## Candidate And Evidence Binding

- Tested contract candidate: `f338eb5df0c37d40a7b7293a1ae999dc8ea18b0c`
- Separate entry-evidence commit: `07ec856ff23fb166b73aae72895dad8b2fd13264`
- Accepted Phase 5 checkpoint: `d3ea271188be57adac49592879aaf3417e90c5c0`
- Evidence directory:
  `test-reports/temporal-first/phase-6-entry-20260724-f338eb5d/phase-6-entry`

The evidence commit is the direct child of the tested candidate and changes exactly eight generated
evidence artifacts. `entry-metrics.json` intentionally records
`PASS_AWAITING_EVIDENCE_COMMIT`; committing that immutable bundle realizes its
`entry_effect_after_commit=P6_0_ENGINEERING_ENTRY_PASS`. The archived bytes are not rewritten or
relabeled.

The earlier candidate `77a4afca93d63b95c57c5867b72a6e8ab3e7e4a4` and its local green run are
superseded diagnostics. They are not part of this checkpoint because the candidate lacked the
bound entry-evidence generator and negative tests.

## Batch 0 Result

| Source suite | Tests | Failures | Errors | Skipped | Exit |
| --- | ---: | ---: | ---: | ---: | ---: |
| Static contract, runner, generator, and traceability gates | 37 | 0 | 0 | 0 | 0 |
| Python Hearing V2 baseline | 23 | 0 | 0 | 0 | 0 |
| Java Hearing runtime, persistence, dossier, and handoff baseline | 18 | 0 | 0 | 0 | 0 |
| Frontend Hearing view, flow utility, and API baseline | 70 | 0 | 0 | 0 | 0 |
| **Total** | **148** | **0** | **0** | **0** | **0** |

All four normalized JUnit roots bind candidate `f338eb5d` and their exact source command IDs. The
artifact index authenticates every other evidence file by SHA-256 and byte size. The sealed
execution manifest binds the clean detached candidate, environment snapshot, command order,
timestamps, durations, exit codes, report hashes, and zero quarantined, reused, or unclassified
attempts.

## Engineering Authorization

First-wave tasks `P6-A1`, `P6-B1`, `P6-C1`, `P6-D1`, and `P6-E1` may start from the acceptance
commit containing this checkpoint. Exact paths and forbidden paths remain authoritative in
`plans/phase-6-owner-briefs.yaml`; shared files remain primary-owned.

The following remain forbidden:

- real-case, active-case, party-data, or unsigned shadow traffic;
- `TEMPORAL` Hearing allocation or replacement of the formal Java process;
- a formal Graph/Finalizer sink or any Graph-owned Domain mutation;
- changing the fixed 15-stage order, the two party waits, the 0-50 per-party supplement limit,
  participant identity rules, privacy barrier, or V1/Jury/V2 ID/hash parent chain;
- rewriting V001-V043_5, historical V035/V037 rows, or moving an active flow between writer modes;
- canary, production traffic, promotion, or a claim that `MIG-004`, `MIG-005`, or `MIG-006` passed.

P6.1-P6.8 still require focused owner tests and in-flight P0 review. The later Phase 6 engineering
candidate must run its centralized checkpoint before any Phase 7 engineering permission is granted.
