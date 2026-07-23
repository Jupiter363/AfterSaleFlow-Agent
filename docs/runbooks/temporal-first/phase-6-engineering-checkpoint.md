# Phase 6 Hearing Engineering Checkpoint

- Candidate `C6`: `ea046eae2792cd5afb9929bca40da8fb8c77a9bd`
- Evidence commit `E6`: `e674263e9026e3fec46ec295767d432807f5ab44`
- Release: `phase-6-20260724-ea046eae`
- Evidence path: `test-reports/temporal-first/phase-6-20260724-ea046eae/phase-6/`
- Acceptance: this document and its static verifier are committed separately as the sole child of
  `E6`.

```text
engineering_checkpoint: PASS
contract_gate: P6.0 PASS
promotion_gate: PENDING
next_phase_permission: PHASE_7_ENGINEERING_ONLY
MIG-004: PENDING_PROMOTION
MIG-005: PENDING_PROMOTION
MIG-006: PENDING_PROMOTION
```

The clean candidate-bound checkpoint executed 143 Java, 60 Python, 74 frontend, and 46 static
tests, for 323 tests with zero failures, errors, or skips. The Java source used one Maven fork and
covered the 15-stage Temporal kernel, timer and receipt replay, V044 PostgreSQL fencing, Java
Finalizers, signed-synthetic guards, detector-only scheduler controls, recovery and rollback,
side-effect-free projections, actor privacy, and command-side initialization for imported Hearing
cases. Python covered all four Hearing graph families, governed LCEL, bounded `Send`, reducers,
state lenses, checkpoint recovery, and the existing Hearing V2 schema baseline. Frontend covered
all 15 stages, six progress groups, history, API compatibility, and actor-switch isolation.

The exact runner topology was one Maven process plus two light processes, followed by the static
source. The first candidate attempt `785485e4` was rejected as `FIXTURE` before Java launched
because Windows could not resolve a relative batch wrapper from Python `subprocess`; its partial
Python/frontend reports were not reused. The wrapper was fixed, a new candidate was frozen, and
all four sources were rerun from `ea046eae`.

This checkpoint proves engineering behavior only. It does not allocate a Temporal Hearing epoch,
make LangGraph output formal, use real case or party data for shadow traffic, authorize canary
traffic, or promote `MIG-006`.

```text
hearing_graph_runtime: DISABLED_OR_JAVA_SIGNED_SYNTHETIC_SHADOW_ONLY
legacy_java_hearing_writer: PRESERVED
temporal_hearing_allocation: FORBIDDEN
formal_graph_sink: FORBIDDEN
real_case_shadow: FORBIDDEN
production_traffic: FORBIDDEN
canary: FORBIDDEN
promotion: FORBIDDEN
```

`PHASE_7_ENGINEERING_ONLY` permits a separately contracted Phase 7 entry process. It does not
satisfy the production promotion preconditions stated as `MIG-006=PASS` in the long-term source
plan, and it does not allow an Outcome Workflow to invoke real tools or create external effects.
