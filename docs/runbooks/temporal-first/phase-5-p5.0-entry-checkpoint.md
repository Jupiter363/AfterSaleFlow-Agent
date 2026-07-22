# Phase 5 P5.0 Entry Checkpoint

## Decision

```text
P5.0: PASS
contract_gate: P5.0 PASS
engineering_execution: ALLOWED_WITH_DISABLED_JAVA_SIGNED_SYNTHETIC_SHADOW_RESTRICTIONS
next_phase_permission: PHASE_5_ENGINEERING_ONLY
phase_6_permission: FORBIDDEN
promotion_gate: PENDING
MIG-004: PENDING_PROMOTION
MIG-005: PENDING_PROMOTION
formal_evidence_runtime: LEGACY
real_case_shadow: FORBIDDEN
temporal_evidence_allocation: FORBIDDEN
formal_graph_sink: FORBIDDEN
canary: FORBIDDEN
promotion: FORBIDDEN
java_evidence_ledger_writer: SOLE_FORMAL_WRITER
pre_entry_contract_correction: ADR_0013_CONSUMED_AND_EXPIRED
```

This checkpoint authorizes Phase 5 engineering implementation only. It is not the Phase 5
engineering checkpoint, does not grant Phase 6 permission, and has no production-promotion effect.
The formal Evidence path remains `LEGACY`; new Graph execution remains `DISABLED` or Java-signed
synthetic `SHADOW`.

## Candidate And Evidence Binding

- Tested contract candidate: `e70492a11e23307382ea762d0e8e7f57ab58870b`
- Separate entry-evidence commit: `e5f6019b71a90174c09aecdcba336bd12788b75b`
- Integrated engineering base: `09d65875ff6edfbc76d0d2a0e42610690e500bfd`
- Evidence directory:
  `test-reports/temporal-first/phase-5-entry-20260723-e70492a1/phase-5-entry`
- Upstream Phase 4 candidate: `1ba6e17fa2182156825f42d7e243978cf23ccdb4`
- Upstream Phase 4 evidence commit: `b8697ce7a46f4494d250d21f27a076f0711ae04d`

The evidence commit contains exactly the eight generated P5.0 artifacts. Its parent is the tested
candidate, and it preserves `entry-metrics.json.result=PASS_AWAITING_EVIDENCE_COMMIT`. Committing
that immutable bundle realizes its recorded `entry_effect_after_commit=P5_0_ENGINEERING_ENTRY_PASS`;
the evidence bytes are not rewritten or relabelled.

## Batch 0 Result

| Source suite | Tests | Failures | Errors | Skipped | Exit |
| --- | ---: | ---: | ---: | ---: | ---: |
| Static contract and boundary gates | 145 | 0 | 0 | 0 | 0 |
| Python Evidence, Graph, security, and Runnable baseline | 61 | 0 | 0 | 0 | 0 |
| Java Evidence, authority, persistence, and assembly baseline | 93 | 0 | 0 | 0 | 0 |
| Frontend Evidence, shell, stream, and store baseline | 97 | 0 | 0 | 0 | 0 |
| **Total** | **396** | **0** | **0** | **0** | **0** |

All four normalized JUnit roots bind candidate `e70492a1` and their exact source command IDs. The
artifact index authenticates every other file by SHA-256 and byte size. The archived execution
manifest binds the clean detached candidate, environment snapshot, Phase 4 handoff, command order,
timestamps, durations, exit codes, report hashes, and zero reused or unclassified attempts.

## Engineering Authorization

Wave A tasks `P5-A1`, `P5-B1`, `P5-C1`, `P5-D0`, and `P5-E0` may start from the integrated base.
Wave B and the Phase 5 candidate checkpoint remain blocked behind their declared integration and
test barriers.

The generic process-local bulkhead foundation was integrated by
`ca18e53e6f051004d20c6f8879f6ed440ab0dc20`, with the Evidence room-cap correction at
`09d65875ff6edfbc76d0d2a0e42610690e500bfd`. This foundation enforces the per-room maximum of
eight, but it does not prove cross-replica tenant/global coordination and does not close
`GRAPH-016`. That closure remains assigned to `P5-E1` as Phase 5 engineering exit evidence.

The following remain forbidden:

- real-case, party-data, or production shadow traffic;
- `TEMPORAL` Evidence allocation or replacement of the formal legacy process;
- a formal Graph/Finalizer sink or any Graph-owned Domain mutation;
- public 100-file submission, production asset authorization, or Hearing supplement changes;
- canary, production traffic, promotion, or a claim that `MIG-004` or `MIG-005` passed;
- any Phase 6 engineering permission before the Phase 5 engineering checkpoint passes.

ADR 0013 expired at this first P5.0 acceptance. A later authority field, hash preimage, signature
scope, trust binding, or assessment/terminal pin change requires a new schema version,
compatibility plan, accepted ADR, and new checkpoint; this entry exception cannot be reused.
