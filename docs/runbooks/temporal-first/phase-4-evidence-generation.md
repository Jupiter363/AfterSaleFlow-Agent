# Phase 4 Candidate Evidence Generation

The Phase 4 checkpoint has two fail-closed tools. The runner executes and normalizes the four
source suites at one immutable candidate SHA. The generator then assembles the `P4-BATCH-3`
engineering bundle without rerunning Java, Python, frontend, Docker, Temporal, database, or
browser work.

## Preconditions

- Freeze one full candidate SHA in a clean detached worktree.
- Use `scripts/run_phase4_candidate_checkpoint.py` to run the four matrix commands. It adds
  candidate-specific JUnit output arguments, consolidates candidate-specific Surefire reports,
  and binds every normalized report to the full `candidate_commit` and exact `source_command_id`.
- Keep failed or quarantined attempts outside the accepted source directory.
- Preserve the runner directory. Its manifest records the environment, exact command transforms,
  start/end timestamps, durations, exit codes, stdout/stderr/report paths, and SHA-256 values.

The accepted source directory must contain these reports:

```text
python-phase4-junit.xml    source_command_id=python_phase_4
java-phase4-junit.xml      source_command_id=java_phase_4
frontend-phase4-junit.xml  source_command_id=frontend_phase_4
static-phase4-junit.xml    source_command_id=static_phase_4
```

The parent runner directory also contains `source-execution-manifest.json`; the generator requires
that exact sibling relationship and rejects reports copied from another attempt.

## Freeze And Run

Run from the repository root in the clean detached candidate worktree:

```powershell
$candidate = git rev-parse HEAD
D:\miniconda\python.exe scripts/run_phase4_candidate_checkpoint.py `
  --candidate-commit $candidate `
  --execute `
  --run-dir ".codex-run/phase4-candidate-$($candidate.Substring(0, 12))" `
  --environment-id local-phase4-candidate
```

Without `--execute`, the runner prints the exact expanded plan and changes no files. It refuses a
branch-attached, dirty, or different HEAD and rechecks HEAD after every source command. A failed
source stops the run with `REQUIRES_CLASSIFICATION`; no failed report enters `source/`.

Classify before any retry. Only `INFRA` may resume the failed source on the same SHA:

```powershell
D:\miniconda\python.exe scripts/run_phase4_candidate_checkpoint.py `
  --candidate-commit $candidate `
  --execute --resume `
  --run-dir ".codex-run/phase4-candidate-$($candidate.Substring(0, 12))" `
  --failure-classification java_phase_4=INFRA
```

`PRODUCT` and `FIXTURE` classify and block that candidate; fix the owning code or fixture and
freeze a new SHA. `EXTERNAL_GATE` never authorizes a source-suite PASS or promotion.

## Assemble

Read `verification_started_at` and `verification_finished_at` from the PASS execution manifest,
then run:

```powershell
D:\miniconda\python.exe scripts/generate_phase4_candidate_evidence.py `
  --release-id phase-4-YYYYMMDD `
  --candidate-commit <full-candidate-sha> `
  --base-commit <full-phase-4-entry-evidence-sha> `
  --engineering-started-at <ISO-8601-timestamp> `
  --verification-started-at <ISO-8601-timestamp> `
  --verification-finished-at <ISO-8601-timestamp> `
  --source-dir ".codex-run/phase4-candidate-<sha12>/source" `
  --execution-manifest ".codex-run/phase4-candidate-<sha12>/source-execution-manifest.json"
```

The default output is `test-reports/temporal-first/<release-id>/phase-4/`. Assembly uses a staging
directory and publishes the bundle only after every check passes.

## Fail-Closed Checks

- HEAD must equal the fixed candidate SHA, be detached, and have no tracked, staged, or untracked
  changes before and after assembly.
- All four reports must bind to that SHA and their exact source command IDs.
- The execution manifest must be `PASS`, bind the same SHA and matrix commands, carry four
  zero-exit accepted command records, authenticate its environment/dependency hashes and source
  reports, and retain only classified same-SHA `INFRA` attempts outside accepted reports.
- Empty, failed, errored, skipped, flaky, duplicate, counter-drifted, mixed-candidate, or
  wrong-command reports are rejected.
- Every source command selector, every Phase 4 Check-ID selector, and every baseline selector must
  resolve to a testcase in the declared source report.
- Batches 0-2 are filtered views of the source reports. Batch 3 is their deduplicated source union;
  every derived report records the four source-report SHA-256 values.
- The output must match the matrix-declared 15-file set exactly, including the immutable source
  execution manifest.
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
