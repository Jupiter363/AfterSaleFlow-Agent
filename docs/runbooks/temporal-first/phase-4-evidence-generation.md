# Phase 4 Candidate Evidence Generation

This generator assembles the `P4-BATCH-3` engineering bundle from four source JUnit reports. It is
service-free: source suites run separately at the unified candidate checkpoint, and this command
does not rerun Java, Python, frontend, Docker, Temporal, database, or browser work.

## Preconditions

- Freeze one full candidate SHA in a clean detached worktree.
- Run the four `P4-BATCH-3` source commands from
  `plans/phase-4-intake-pilot-test-batches.yaml` at that SHA.
- Normalize each report with its full `candidate_commit` and exact `source_command_id` root
  attributes. The generator refuses to add missing provenance to raw reports.
- Keep failed or quarantined attempts outside the accepted source directory.
- Record the verification start and finish timestamps with explicit UTC offsets.

The accepted source directory must contain these reports:

```text
python-phase4-junit.xml    source_command_id=python_phase_4
java-phase4-junit.xml      source_command_id=java_phase_4
frontend-phase4-junit.xml  source_command_id=frontend_phase_4
static-phase4-junit.xml    source_command_id=static_phase_4
```

## Command

Run from the repository root in the clean detached candidate worktree:

```powershell
D:\miniconda\python.exe scripts/generate_phase4_candidate_evidence.py `
  --release-id phase-4-YYYYMMDD `
  --candidate-commit <full-candidate-sha> `
  --base-commit <full-phase-4-entry-evidence-sha> `
  --engineering-started-at <ISO-8601-timestamp> `
  --verification-started-at <ISO-8601-timestamp> `
  --verification-finished-at <ISO-8601-timestamp> `
  --source-dir <candidate-bound-source-report-directory>
```

The default output is `test-reports/temporal-first/<release-id>/phase-4/`. Assembly uses a staging
directory and publishes the bundle only after every check passes.

## Fail-Closed Checks

- HEAD must equal the fixed candidate SHA, be detached, and have no tracked, staged, or untracked
  changes before and after assembly.
- All four reports must bind to that SHA and their exact source command IDs.
- Empty, failed, errored, skipped, flaky, duplicate, counter-drifted, mixed-candidate, or
  wrong-command reports are rejected.
- Every source command selector, every Phase 4 Check-ID selector, and every baseline selector must
  resolve to a testcase in the declared source report.
- Batches 0-2 are filtered views of the source reports. Batch 3 is their deduplicated source union;
  every derived report records the four source-report SHA-256 values.
- The output must match the matrix-declared 14-file set exactly.
- Runtime restrictions and all external gates remain closed. `MIG-003` and `MIG-004` remain
  `PENDING_PROMOTION`.

Only a complete accepted bundle reports:

```text
engineering_checkpoint: PASS
promotion_gate: PENDING
next_phase_permission: PHASE_5_ENGINEERING_ONLY
MIG-003: PENDING_PROMOTION
MIG-004: PENDING_PROMOTION
```

This result permits Phase 5 engineering only. It does not authorize real-case shadow, a formal
Intake Finalizer, `TEMPORAL` Intake allocation, canary traffic, or production promotion.
