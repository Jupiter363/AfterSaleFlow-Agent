# Phase 8 P8.0 Engineering Entry Checkpoint

## Decision

```text
P8.0: PASS
contract_gate: P8.0 PASS
entry_effect: P8_0_ENGINEERING_ENTRY_PASS
engineering_execution: ALLOWED_WITHIN_PHASE_8_ENGINEERING_LANE
implementation: ALLOWED_WITHIN_PHASE_8_ENGINEERING_LANE
next_phase_permission: PHASE_8_ENGINEERING_ONLY
production_checkpoint: PENDING_EXTERNAL
promotion_gate: PENDING
MIG-003: PENDING_PROMOTION
MIG-004: PENDING_PROMOTION
MIG-005: PENDING_PROMOTION
MIG-006: PENDING_PROMOTION
MIG-007: PENDING_PROMOTION
MIG-008: PENDING_PROMOTION
```

This checkpoint is the only authoritative object in the replacement P8.0 chain that records
`P8.0: PASS` and issues `P8_0_ENGINEERING_ENTRY_PASS`. It authorizes the separately bounded Phase 8
engineering lane only. It is not a production-readiness, release, migration, canary, or promotion
decision.

## Immutable Topology

The accepted chain is exact and uses one parent at every boundary:

| Object | Commit | Tree | Sole parent |
| --- | --- | --- | --- |
| Phase 7 candidate `C7` | `4ddeeabb39ce7b7de41ecc4f44e17ece389d2840` | `a618efc2567595cf60fcb5f0cd35e4b42a94c272` | `d323c9239a4d9c348ca1a650ee67a68cb4df9850` |
| Phase 7 evidence `E7` | `f1c1ca16228641f1072eb358c6df9235dc239914` | `75ee96add9a237a536d07e256a367a9f33ec0150` | `4ddeeabb39ce7b7de41ecc4f44e17ece389d2840` |
| Phase 7 checkpoint `A7` | `e3acedc64d161f0342c8db3d5c313c2f404ea462` | `02aede44f715fa8576d0bc3ca23488579655cc16` | `f1c1ca16228641f1072eb358c6df9235dc239914` |
| Replacement Phase 8 contract candidate `C8'` | `74f4cb6bc2ac78f17aacdb36378e72ff650d60b6` | `63c3e8259cd5fdc2bd0efd656a87d55f03ea87c7` | `e3acedc64d161f0342c8db3d5c313c2f404ea462` |
| Replacement Phase 8 evidence `E8'` | `3463e0cd774f80e452294fe32cf243bfa826eef0` | `91de2f750a126e52747f39256bd54b02e1514477` | `74f4cb6bc2ac78f17aacdb36378e72ff650d60b6` |

Release `phase-8-entry-20260725-74f4cb6bc2ac` is stored under
`test-reports/temporal-first/phase-8-entry-20260725-74f4cb6bc2ac/phase-8-entry/`.
`C8'` is the exact twelve-path contract-only child of `A7`; it contains no Phase 8 implementation.
`E8'` is its evidence-only direct child; it adds exactly the twelve regular blobs below and changes
no contract, implementation, product, migration, runtime, deployment, or production path.

The replacement acceptance commit `A8'` must be the sole-parent direct child of `E8'` and must add exactly these
two `100644` regular blobs and no other path:

1. `docs/runbooks/temporal-first/phase-8-p8.0-entry-checkpoint.md`
2. `tests/static/test_phase8_p8_0_entry_checkpoint.py`

Any other parent, modification, deletion, rename, non-regular object, mode, or third path invalidates
this checkpoint and requires a new acceptance candidate.

## Batch 0 Evidence

Batch 0 executed once from clean detached `C8'`, with no retry, resume, quarantine, or report reuse.
The manifest records attempt number `1` and this immutable attempt-ledger marker:

```text
attempt_ledger_sha256: 86b6b3a4b1c5fe93b2a71d7295e5b81c58f08f7be9b5238df2bf1de9f57be61d
```

| Source | Tests | Failures | Errors | Skipped | Exit |
| --- | ---: | ---: | ---: | ---: | ---: |
| Static Phase 8 entry contract, runner, evidence, Phase 7 checkpoint, and traceability gates | 113 | 0 | 0 | 0 | 0 |
| **Total** | **113** | **0** | **0** | **0** | **0** |

The accepted report and execution bindings are:

```text
candidate_commit: 74f4cb6bc2ac78f17aacdb36378e72ff650d60b6
candidate_tree_sha: 63c3e8259cd5fdc2bd0efd656a87d55f03ea87c7
manifest_file_sha256: 7f4a939dbfb051d53b5e47266c7b567bb66dd44750eb07cdc89a71f0d8e73626
manifest_self_seal_sha256: 311365dfb17e5006dc67278fa1a2ac5d06bcd7d055844e967442717c159f5975
environment_file_sha256: b45eaf9f6450fda4dc0dac2f3dc5b8e457c10c0816d9140e89a959ac67250fd5
normalized_junit_sha256: 992918edf2da7c11674c7df83c10dc2a716098f55a723bb77a46ccf6114fe340
raw_junit_sha256: 45e1430a77cf6bf5ed58e27ecd4426b52cfdd2cf1c60e6f69aef06f94b7441aa
command_argv_sha256: e911ba1f46fb67970a4e83141f0b2ffe2c239547456326b4f985b9d3cb38c055
```

The manifest self-seal is an unkeyed integrity seal only. It does not prove operator identity,
execution provenance, production authorization, or a cryptographic production attestation.

## Evidence Closure

`E8'` contains exactly twelve `100644` regular Git blobs. `artifact-sha256.json` indexes exactly
the other eleven and does not index itself.

| Evidence path | Git blob | SHA-256 |
| --- | --- | --- |
| `.gitattributes` | `fd38c7f3a405823634fae4895a9fc49f6c7f952a` | `3e5e82fc72e044ea9af807a2030b73dbb94800d2cd1775302063b2eee761ba1e` |
| `artifact-sha256.json` | `677203e8117651fa9bd5f0702f055426ea8c53c4` | `f77f781a480617e8567ef57a11348fce88a9539a558bffa6af5730a4168fcc8d` |
| `candidate.txt` | `b52b037ab247a03724663b18c0741e65b0084369` | `702a264c1ba33d16c63b3726760d8de4bc814a1f1927562cba4a87a69baf163d` |
| `p/00-stdout.log` | `5bb59053b2dd1365a8c02b787a2f6b3e7a27c4b3` | `ee7f59fe61df9c3dd8cb2c442811cdf5604e0b17ae3bb7d7de32d9642af8b67e` |
| `p/01-stderr.log` | `e69de29bb2d1d6434b8b29ae775ad8c2e48c5391` | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `p/02-junit.xml` | `aaec11e6f9a0393fb8cf25d4718f3923aa26241c` | `45e1430a77cf6bf5ed58e27ecd4426b52cfdd2cf1c60e6f69aef06f94b7441aa` |
| `p0-review-disposition.json` | `d6b09985af02112c6ea242d24a0842e892a1fe7b` | `bfa6c3f72e150385efb0c9e9830e0c1888faeb0d3d58a609c8f4576230fc10a4` |
| `phase8-entry-decision.json` | `fdc193e8413b13074907e501322571cce8a379f5` | `43f8a4eb6e11930d3282a67c365f1752ead54996c197da638622ad2332c96be3` |
| `phase8-entry-execution-manifest.json` | `a68d308216de50e92713652c33b45037536c81f3` | `7f4a939dbfb051d53b5e47266c7b567bb66dd44750eb07cdc89a71f0d8e73626` |
| `provenance-manifest.json` | `b93074d4c879938417494de7df06787a019699a5` | `201025d2bfb66abdc5f6ff5bf84951d588053bb74a82680ab4255ad10e3ce494` |
| `source-tree-environment.json` | `9e90ed9b76ae3ae422a0df80bd042aa768c0fe69` | `b45eaf9f6450fda4dc0dac2f3dc5b8e457c10c0816d9140e89a959ac67250fd5` |
| `static-phase8-entry.xml` | `55ece14b5088b3c23da13c8e9cc7b24908a7688d` | `992918edf2da7c11674c7df83c10dc2a716098f55a723bb77a46ccf6114fe340` |

The closure-critical artifact hashes are therefore:

```text
artifact_sha256_index_sha256: f77f781a480617e8567ef57a11348fce88a9539a558bffa6af5730a4168fcc8d
p0_review_disposition_sha256: bfa6c3f72e150385efb0c9e9830e0c1888faeb0d3d58a609c8f4576230fc10a4
phase8_entry_decision_sha256: 43f8a4eb6e11930d3282a67c365f1752ead54996c197da638622ad2332c96be3
provenance_manifest_sha256: 201025d2bfb66abdc5f6ff5bf84951d588053bb74a82680ab4255ad10e3ce494
E8_regular_blob_count: 12
artifact_sha256_index_entry_count: 11
```

The candidate-bound consolidated P0 disposition records `ALL_P0_CLOSED`, `open_p0_count: 0`,
`self_approved: false`, and exactly thirteen closed P0 topics across the independent `authority`,
`data_migration`, and `security_privacy` lanes. The `E8'` decision correctly stopped at
`PASS_AWAITING_CHECKPOINT_A8` with `next_phase_permission: PENDING_A8_CHECKPOINT`; this replacement
`A8'` checkpoint authenticates that evidence and realizes the engineering-entry effect without
changing the sealed evidence bytes.

## Superseded Historical Chain

The former Phase 8 chain is superseded and historical-only: `C8`
`6d4f9946ab357a7d3193ea1680473fe923322eb0`, `E8`
`4dc398d359806ab41ea702df54112956d17920ae`, and `A8`
`7e3cbace3d206aef5eb23a03d36878a00634c9a9`. It is retained only under annotated tag
`refs/tags/phase8-superseded-a8-7e3cbace` (tag object
`bd72fe8cc86e0383c645d069a04874a7eabc16ca`, peeling to the former `A8`) and confers no current
engineering, implementation, production, migration, release, canary, or promotion authority.

```text
superseded_historical_C8: 6d4f9946ab357a7d3193ea1680473fe923322eb0
superseded_historical_E8: 4dc398d359806ab41ea702df54112956d17920ae
superseded_historical_A8: 7e3cbace3d206aef5eb23a03d36878a00634c9a9
superseded_historical_ref: refs/tags/phase8-superseded-a8-7e3cbace
superseded_historical_ref_must_not_move: true
superseded_historical_chain_authority: HISTORICAL_OLD_CONTRACT_ONLY
superseded_historical_chain_authorizes_replacement_contract_or_implementation: false
```

## Engineering Authorization

The accepted Phase 8 owners may now implement only the fail-closed engineering work defined by the
contract pack: reference-inventory and eligibility controls, scheduler lifecycle controls without
real `OFF` activation, additive V046 machinery in disposable PostgreSQL, render-only deployment and
security manifests, observability, synthetic capacity harnesses, and fixture-only recovery tooling.
Java and Domain PostgreSQL remain the sole formal business ledger; Temporal owns process time and
failure only for separately approved epochs, and LangGraph/LCEL remain bounded cognition with no
formal Domain authority.

This checkpoint does not make the historical 1,000-room target observed or satisfied. Phase 8
engineering must still produce focused implementation evidence, candidate-bound P0 review, the
unified engineering checkpoint, and separately authorized external production evidence.

## Production Boundary

```text
production_authorization: FORBIDDEN
scheduler_OFF: FORBIDDEN
production_V046_apply_or_switch: FORBIDDEN
V047: FORBIDDEN
real_case_or_party_data: FORBIDDEN
production_traffic: FORBIDDEN
production_load: FORBIDDEN
production_chaos: FORBIDDEN
production_PITR: FORBIDDEN
production_DR: FORBIDDEN
production_rotation: FORBIDDEN
production_soak: FORBIDDEN
canary: FORBIDDEN
promotion: FORBIDDEN
```

No local SHA-256 seal or P8.0 engineering receipt can substitute for the external production
checkpoint, approved environment and change window, real release evidence, owner signatures, or
the master `MIG-000..007=PASS` entry condition. Destructive V047 work additionally requires
`MIG-000..008=PASS` and its own immutable release candidate and authorization. Until those
conditions are met, all real scheduler, V046, V047, load, chaos, PITR, DR, rotation, soak, canary,
and promotion actions remain forbidden.
