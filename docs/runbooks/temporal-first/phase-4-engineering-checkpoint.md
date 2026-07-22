# Phase 4 Intake Pilot Engineering Checkpoint

- Candidate: `1ba6e17fa2182156825f42d7e243978cf23ccdb4`
- Evidence: `test-reports/temporal-first/phase-4-20260722-1ba6e17f/phase-4/`
- Runtime scope: Java-signed synthetic `SHADOW`; default remains `DISABLED`

```text
engineering_checkpoint: PASS
promotion_gate: PENDING
next_phase_permission: PHASE_5_ENGINEERING_ONLY
MIG-003: PENDING_PROMOTION
MIG-004: PENDING_PROMOTION
```

The clean detached candidate ran one deduplicated source execution with 327 Python, 294 Java,
129 frontend, and 134 static tests. All 884 tests passed with no failure, error, skip, quarantined
attempt, or mixed-candidate result. The four batch reports are derived views of those source
reports and carry the same candidate SHA.

The evidence generator resolved all policy selectors against tests that actually ran, including
parameterized Python cases and every Java class named by the policy. Core Check coverage records
16 `PASS_ENGINEERING`, 2 `PARTIAL_ENGINEERING`, and 1 `PENDING_PROMOTION` result. Baseline coverage
records 22 `PASS_ENGINEERING` and 5 `PARTIAL_ENGINEERING` results. Exact bindings and limitations
are in `check-id-coverage.json` and `baseline-id-coverage.json`.

This checkpoint is an engineering result, not production promotion. Real-case shadow, production
privacy scanning, formal Intake Finalizer reachability, `TEMPORAL` Intake epoch allocation,
persisted rollback races, production worker failover, and the `1% -> 5% -> 25% -> 100%` canary
sequence remain external gates. Until those gates pass, runtime stays `DISABLED` or Java-signed
synthetic `SHADOW`, Java remains the sole formal business writer, and no real party data may enter
the synthetic path.

Phase 5 may proceed for engineering work only under its separately committed P5.0 contract and
entry-evidence gate. This checkpoint does not authorize Evidence formal writes, real shadow,
`TEMPORAL` Evidence allocation, canary, or promotion.
