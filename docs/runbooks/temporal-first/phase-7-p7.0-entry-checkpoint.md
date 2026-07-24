# Phase 7 P7.0 Entry Checkpoint

## Decision

```text
P7.0: PASS
contract_gate: P7.0 PASS
entry_effect: P7_0_ENGINEERING_ENTRY_PASS
engineering_implementation: ALLOWED_UNDER_ADR_0016_ONLY
next_phase_permission: PHASE_7_ENGINEERING_ONLY_AFTER_P7_0_PASS
promotion_gate: PENDING
MIG-006: PENDING_PROMOTION
MIG-007: PENDING_PROMOTION
formal_outcome_selector: LEGACY
allowed_new_runtime_modes: [DISABLED, JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW]
temporal_outcome_allocation: FORBIDDEN
formal_outcome_workflow: FORBIDDEN
formal_outcome_graph_sink: FORBIDDEN
real_tool_effects: FORBIDDEN
real_or_party_data_shadow: FORBIDDEN
production_traffic: FORBIDDEN
canary: FORBIDDEN
promotion: FORBIDDEN
java_outcome_business_truth: SOLE_FORMAL_TRUTH
```

This checkpoint authorizes Phase 7 engineering implementation only under
`ADR_0016_ACCEPTED_FOR_ENGINEERING_ONLY`. It is not the Phase 7 engineering checkpoint and has no
production-promotion effect. The formal Review, execution, closure, and Outcome path remains the
legacy Java path. New engineering execution remains `DISABLED` or isolated
`JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW` and cannot produce an external effect.

Java and Domain PostgreSQL remain the sole formal business truth and writers for ReviewPacket,
ReviewTask, reviewer authorization, human decision, ApprovalRecord, ActionRecord, external receipt,
compensation fact, case status, closure, evaluation trace, audit, and query projection. Temporal may
be implemented only as the disabled or synthetic no-op time/failure kernel described by ADR 0016;
Graph and LCEL remain private advisory cognition with no decision or tool authority.

## Candidate And Evidence Binding

- Tested contract candidate `C7`: `0aa260f722fced0eba4314bd4793e415b5bf0b05`
- Separate entry-evidence commit `E7`: `e29cefb3e028bb84f6a227e46fecdf5711eba48c`
- Accepted Phase 6 checkpoint `A6`: `d18a1f130a925429e8c2dfd11352cea4ca8673a0`
- Release: `phase-7-entry-20260724-0aa260f7`
- Evidence directory:
  `test-reports/temporal-first/phase-7-entry-20260724-0aa260f7/phase-7-entry`

`E7` is the direct child of `C7` and changes only the 28 archived Phase 7 entry-evidence files.
`entry-metrics.json` preserves `result=PASS_AWAITING_EVIDENCE_COMMIT` and records
`entry_effect_after_commit=P7_0_ENGINEERING_ENTRY_PASS`; committing that immutable bundle realizes
the entry effect without rewriting or relabeling its bytes. The candidate scope is contract-only:
17 contract, plan, runner, generator, and static-test paths relative to `A6`, with no Phase 7
product implementation.

The evidence binds the clean detached candidate, its accepted Phase 6 base, four source commands,
environment snapshot, command hashes, raw reports, normalized reports, timestamps, durations, exit
codes, and the SHA-256 archive index. Its provenance manifest maps and authenticates 18 accepted raw
stdout, stderr, and JUnit/Surefire artifacts.

## Batch 0 Result

| Source suite | Tests | Failures | Errors | Skipped | Exit |
| --- | ---: | ---: | ---: | ---: | ---: |
| Static Phase 7 contract, plan, evidence, and traceability gates | 78 | 0 | 0 | 0 | 0 |
| Python Evaluation baseline | 3 | 0 | 0 | 0 | 0 |
| Java Review, Outcome, policy, frozen packet, and Evaluation client baseline | 18 | 0 | 0 | 0 | 0 |
| Frontend Draft, Review, Outcome, and review API baseline | 41 | 0 | 0 | 0 | 0 |
| **Total** | **140** | **0** | **0** | **0** | **0** |

All four normalized JUnit roots bind `C7` and the exact executed command IDs. Batch 0 was
deliberately sequential and used at most one heavy process because it had one Java source; its
light-process ceiling was two. The run reported zero quarantined or unclassified attempts and did
not mix candidates, attempts, or reports from another run.

## Discarded Fixture Diagnostics

Two earlier candidates/runs are diagnostics only:

| Candidate | Classification | Diagnostic |
| --- | --- | --- |
| `1b11643ab58e265547b5114f2e42d59a3d2962de` | `FIXTURE` | nested-parent omission in evidence archive assembly; output rejected before acceptance |
| `c26c8a0609e43f5b5fd697e9f7ec443ea2e913ac` | `FIXTURE` | Windows `MAX_PATH` failure while archiving full provenance paths; `C7` bounds archive paths while retaining authenticated source mappings |

No raw report, normalized report, manifest, metric, or test count from either diagnostic run was
reused. All four accepted source suites were rerun from clean detached `C7`.

## Evidence Trust Boundary

The execution-manifest seal is the local unkeyed
`SHA256_CANONICAL_JSON_EXCLUDING_MANIFEST_SHA256` integrity check. Together with the committed Git
objects, artifact index, and candidate-bound manifests, it is trusted-primary engineering evidence
for this repository checkpoint only. It is not a cryptographic signer identity, CI identity,
keyless transparency-log proof, or supply-chain attestation.

A signed CI/SLSA attestation remains an external promotion gate. This local evidence cannot satisfy
that gate, cannot authorize production, and cannot change `MIG-006` or `MIG-007` from
`PENDING_PROMOTION`.

## Engineering Authorization

The implementation owners declared in `plans/phase-7-owner-briefs.yaml` may start after the
acceptance commit containing this checkpoint. Exact ownership, forbidden paths, integration waves,
focused checks, and in-flight P0 review remain mandatory. Across Phase 7 implementation, the
combined Maven/Testcontainers/heavy-process ceiling is two and the light-process ceiling is two;
Batch 0's sequential heavy-one topology does not lower that global implementation allowance.

Engineering may implement only the ADR 0016 scope:

- a disabled or Java-signed synthetic no-op Outcome workflow kernel;
- Java-authoritative review command and receipt adapters;
- private read-only `outcome/review.v1` Graph and governed LCEL protocol without tools;
- additive V045 operation/receipt/compensation engineering ledgers;
- no-op synthetic tool Activities, retry classification, reconciliation, and compensation ordering;
- closure-before-read-only-evaluation ordering and replay/rollback fixtures; and
- compatibility-preserving Draft, Review, and Outcome projections.

P7.0 closes only the contract entry gate. It closes no product implementation, runtime, migration,
replay, reliability, privacy, tool-effect, compensation, UI integration, or Phase 7 engineering
checkpoint gap.

## Still Forbidden

The following remain forbidden:

- `TEMPORAL` Outcome allocation, formal `OutcomeRoomWorkflow`, or replacement of the Java path;
- a formal `outcome/review.v1` sink or any Graph/Agent decision, execution, or Domain-write authority;
- real tools, external calls, money movement, fulfillment, notifications, or other effects;
- real case, tenant, reviewer, party, packet, action, production object, or production-shadow data;
- production credentials, secrets, IAM changes, deployment changes, or production traffic;
- changing the five human decision values or allowing timeout to approve;
- treating `AMBIGUOUS` as terminal, retryable, compensatable, or closure-compatible before an
  authoritative receipt query or Java reconciliation resolves it;
- adding `DRAFT` or `OUTCOME` to `RoomType`; and
- canary, promotion, or a claim that `MIG-006` or `MIG-007` passed.

Phase 7 engineering still requires focused implementation evidence, P0 review, centralized
candidate verification, and a separate engineering acceptance checkpoint. Production promotion
continues to require the external security, operations, tool-owner, signed CI/SLSA, canary, and
promotion approvals defined by the master plan.
