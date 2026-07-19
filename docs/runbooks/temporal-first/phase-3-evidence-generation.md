# Phase 3 Candidate Evidence Generation

This runbook creates the Phase 3 engineering checkpoint from one clean candidate commit. It does
not authorize production promotion or formal room traffic.

## Preconditions

- Freeze and commit the Phase 3 candidate. The tracked and untracked worktree must be clean.
- Review `phase-3-graph-lcel-test-batches.yaml` and
  `phase-3-engineering-evidence-policy.yaml` at that candidate.
- Choose a new release ID. The evidence directory and checkpoint document must not already exist.
- Keep the single heavy-test token for the complete run. The generator executes the Python, static,
  and Java source suites sequentially.

## Command

Run from the repository root:

```powershell
D:\miniconda\python.exe scripts/generate_phase3_candidate_evidence.py `
  --release-id phase-3-YYYYMMDD `
  --base-commit <full-phase-3-contract-gate-sha> `
  --engineering-started-at <ISO-8601-timestamp>
```

The generator reads the committed `P3-BATCH-3` commands. It adds candidate-specific Pytest JUnit
paths and a unique Maven Surefire report suffix; it does not discover or run a broader suite. It
checks the candidate SHA after every command and rejects tracked worktree drift.

## Fail-Closed Checks

- Every Pytest selector and every Java class named by `-Dtest` must appear in the source reports.
- JUnit declared counters must match testcase content; failure, error, skip, duplicate identity, or
  an empty report rejects the checkpoint.
- All source reports carry the same full candidate SHA and exact command ID.
- Batch 1 and Batch 2 are filtered from the final source execution using the committed selectors;
  Batch 3 is the deduplicated union. No batch command is rerun to manufacture a view.
- Every matrix Check ID has exactly one expanded status, and only `PASS_ENGINEERING`,
  `PARTIAL_ENGINEERING`, `PENDING_EXTERNAL`, or `PENDING_PROMOTION` are accepted.
- `MIG-003` is always `PENDING_PROMOTION`.
- The final evidence directory must contain exactly the files required by the committed matrix.

On a source-command failure, the partial evidence directory and `.work` input remain for diagnosis.
Do not reuse it. Classify the failure, fix or document it, freeze a new candidate if tracked code
changes, and use a new release ID.

## Outputs

The evidence directory is
`test-reports/temporal-first/<release-id>/phase-3/` and contains:

```text
python-phase3-junit.xml
static-phase3-junit.xml
java-phase3-junit.xml
batch-1-junit.xml
batch-2-junit.xml
batch-3-junit.xml
phase-metrics.json
check-id-coverage.json
candidate-commit.txt
```

The same run writes `phase-3-engineering-checkpoint.md`. The checkpoint keeps these states
separate:

```text
engineering_checkpoint: PASS
promotion_gate: PENDING
next_phase_permission: PHASE_4_ENGINEERING_ONLY
```

External mTLS identity, an independent cross-service synthetic driver, 1,000-room load, 24-hour
soak, Graph PostgreSQL failover/DR, and production approvals remain pending even when engineering
tests pass.
