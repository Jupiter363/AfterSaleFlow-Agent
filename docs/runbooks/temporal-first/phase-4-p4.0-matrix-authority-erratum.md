# Phase 4 P4.0 Matrix Proposal Authority Erratum

## Status

```text
decision: ACCEPTED_CONTRACT_CORRECTION
contract_candidate: COMMIT_CONTAINING_THIS_ERRATUM
batch_0_reauthentication: REQUIRED
runtime_modes: DISABLED_OR_SIGNED_SYNTHETIC_SHADOW
MIG-003: PENDING_PROMOTION
MIG-004: PENDING_PROMOTION
```

This additive erratum corrects the incomplete wire expression of the P4.0 statement
`unilateral_or_bilateral_matrix_patch proposal`. It does not authorize a Graph to create either a
formal unilateral matrix or a bilateral frozen matrix. The corrected
`intake-turn-proposal.v2.matrix_patch` union is:

```text
null
| unilateral_case_matrix.draft.v1
| case_fact_matrix.delta.v2
```

The two non-null members are semantic proposals from one current actor. They are not formal matrix
projections.

## Actor And Java Authority

Java remains the sole authority for the case roles, the current actor, respondent unlock, formal
source membership, and every business transition:

| Java-authorized actor state | Graph proposal allowed | Java-owned result |
| --- | --- | --- |
| Current actor is the initiator | `unilateral_case_matrix.draft.v1` | Derive or update the formal unilateral matrix |
| Current actor is the respondent and respondent Intake is unlocked by the locked initiator matrix | `case_fact_matrix.delta.v2` | Merge the delta with the locked initiator matrix and derive `BILATERAL_FROZEN` |
| Any other or ambiguous state | `null` only; a non-null patch fails closed | No matrix transition |

The Graph must not infer actor role or unlock from model text. Those facts come only from the
Java-authorized private snapshot/command and its formal dossier projection. Each private Graph
thread remains isolated to one actor scope.

The model never emits `matrix_id`, `matrix_version`, `fact_id`, `source_binding`, formal
`source_refs`, `generation_ref`, `parent_ref`, `party_map`, `content_hash`, `matrix_kind`,
`fact_indexes`, `truth_status`, a freeze flag, or a formal unilateral/bilateral schema. Java derives
and validates those fields from current domain authority.

## Strict Proposal Shapes

`unilateral_case_matrix.draft.v1` contains exactly:

```text
schema_version
fact_rows[1..100]
summary_source_fact_keys[1..100]
```

Each unilateral row contains exactly `fact_key`, `category`, `fact_target`, `materiality`,
`position_summary`, `asserted_value`, and `source_scope`.

`case_fact_matrix.delta.v2` contains exactly:

```text
schema_version
fact_rows[1..200]
summary_source_fact_keys[1..200]
respondent_claim (optional)
```

Each delta row requires `fact_key`, `category`, `fact_target`, `materiality`, `stance`,
`position_summary`, and `source_scope`. It may carry `asserted_value`, `agreed_statement`, and
`conflict_summary`. `respondent_claim` may carry `attitude`, `position_summary`, and an optional
`alternative_proposal`. These fields reuse the existing `CaseFactMatrixDeltaV2` vocabulary and
bounds.

`FACT_*` is a reference to a stable fact already present in the Java-authorized visible matrix;
`NEW_*` is only a proposal-local key. `summary_source_fact_keys` can reference only rows in the same
proposal. `PREVIOUS_MATRIX` cannot introduce a `NEW_*` fact. Python rejects unauthorized or rebound
references, and Java repeats the authoritative membership and merge checks.

The complete canonical `intake-turn-proposal.v2` remains limited to 65,536 UTF-8 bytes. Both schema
copies and their positive/negative fixtures must remain identical and independently validated.

## Compatibility And Re-authentication

- Existing `matrix_patch: null` proposals remain valid.
- Existing strict `unilateral_case_matrix.draft.v1` proposals remain valid.
- Arbitrary objects and any formal or frozen matrix remain invalid.
- `case_fact_matrix.delta.v2` is the missing strict respondent proposal branch promised by the P4.0
  contract text. Readers built from the earlier incomplete schema reject it, so this candidate must
  pass a new exact-SHA P4.0 Batch 0 before implementation integration.
- The earlier `f626fca3` evidence remains historical evidence for its exact contract only. It is not
  evidence for this amended candidate.

This correction does not enable real shadow traffic, `TEMPORAL` Intake allocation, a formal
Finalizer sink, canary, or promotion. `MIG-003` and `MIG-004` remain `PENDING_PROMOTION`.
