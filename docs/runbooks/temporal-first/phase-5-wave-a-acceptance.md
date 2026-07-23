# Phase 5 Wave A Acceptance

This checkpoint authenticates the already committed Wave A Batch 1 evidence. It does not rerun the
362 tests and it does not promote any runtime path.

## Required commit sequence

1. `C`: tested candidate `edfd54952dcc5a07d87a90fdb094c01b1a7df79b`.
2. `E`: evidence-only commit `0292321fdb376c3392c86daf6cf98365bfee7c4a`, whose sole parent is `C`.
3. `T`: reviewed tooling-only commit containing this runner, generator, contract, tests, and still-blocked plans.
4. `A`: acceptance-evidence-only commit generated from a clean detached `T`; its result remains `PASS_AWAITING_STATE_TRANSITION_COMMIT`.
5. `O`: state-transition-only commit whose sole parent is `A`; it binds `C`, `E`, `T`, and `A` and is the first commit allowed to record Wave A `INTEGRATED`, barrier `OPEN`, Wave B `READY`, and `P5-R2` `READY`.

No commit may be amended or rebased between these steps. `A` never embeds its own future commit SHA;
`O` derives and binds it from Git history.

## Run on reviewed tooling commit

Use a clean detached worktree at full SHA `T`. The runner output must live in a fresh `.codex-run`
directory. Replace `<T>` with the reviewed full tooling SHA.

```powershell
D:\miniconda\python.exe scripts/run_phase5_wave_a_acceptance.py `
  --candidate-commit <T> `
  --execute `
  --run-dir .codex-run/phase5-wave-a-acceptance-<T-prefix>

D:\miniconda\python.exe scripts/generate_phase5_wave_a_acceptance.py `
  --candidate-commit <T> `
  --execution-manifest .codex-run/phase5-wave-a-acceptance-<T-prefix>/phase5-wave-a-acceptance-execution.json
```

Commit only the three generated files under the fixed acceptance output directory. Do not edit the
three Phase 5 plans in `A`. After `A` is reviewed, create `O` by changing only the three paths listed
in the matrix state-transition contract. The verifier computes canonical post-images from `A`: it
changes only the Wave A and Wave B statuses, acceptance status and bindings, barrier status and
bindings, and P5-R2 status, then appends one fixed SHA-binding record to the execution plan. The
three committed blobs must byte-match those computed post-images. Candidate-wave, owners, runtime
modes, traffic, formal sink, TEMPORAL allocation, real-case shadow, canary, promotion, `MIG-004`,
and `MIG-005` therefore remain byte-for-byte unchanged.

Verify `O` from the reviewed tooling, passing both full SHAs. The verifier reads raw commit parent
headers and rejects an `A` bundle whose `accepted-tooling-candidate.txt` is not the externally
reviewed `T`.

```powershell
D:\miniconda\python.exe scripts/run_phase5_wave_a_acceptance.py `
  --candidate-commit <O> `
  --verify-state-transition `
  --expected-tooling-commit <T>
```
