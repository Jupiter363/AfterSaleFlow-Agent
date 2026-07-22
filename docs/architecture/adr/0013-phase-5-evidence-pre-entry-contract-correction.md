# ADR 0013: Phase 5 Evidence Pre-Entry Contract Correction

- Status: ACCEPTED
- Date: 2026-07-23
- Scope: One atomic Evidence v2 contract correction before the first P5.0 acceptance
- Approval: repository owner direction to correct the uncovered P0 before Phase 5 entry

## Context

Candidate `45d7f087eafe4f50be0d491b3d612446a3e1e94e` completed a diagnostic
P5-BATCH-0 with all four source suites green: static 122, Python 61, Java 67, and frontend 97,
for 347 selected tests passing. Its local execution manifest records `status=PASS`,
`batch_0=PASS`, source `accepted=true`, and `contract_gate=P5.0_AWAITING_ENTRY_EVIDENCE_COMMIT`.
The run is quarantined: it exposed, rather than closed, a P0 in the proposed Evidence v2 authority contract.
The batch manifest did not directly carry a Java ES256 signature, and one ambiguous output schema
pin was used where per-item assessment and terminal batch proposal pins must be independent. The
diagnostic source artifacts exist locally, but no repository P5.0 entry-evidence bundle or
checkpoint was assembled, committed, or accepted.

The affected v1 schemas were contract-candidate material only. They have never been accepted by a
P5.0 evidence commit, released, promoted, selected by a runtime epoch, or consumed by a compatible
reader. Phase 5 engineering remains blocked, so correcting them in place does not reinterpret a
published wire contract. Creating v2 names now would instead imply that the defective v1 contract
had an accepted compatibility obligation.

## Decision

Authorize exactly one atomic, in-place correction of the unaccepted Evidence v2 contract set
before the first P5.0 acceptance:

1. `evidence-batch-manifest.v1` directly carries `signature_algorithm=ES256`, `signing_key_id`,
   and `signature`. Its schema `x-signature` metadata declares
   `input_encoding=ASCII_LOWERCASE_HEX_TEXT` and `encoding=JOSE_P1363_BASE64URL`.
   `manifest_hash` is the lowercase RFC 8785 SHA-256 hex digest produced while omitting exactly
   `manifest_hash` and `signature`; ES256 signs that 64-character ASCII text, not the decoded
   32-byte digest. `evidence-asset-capability.v1` uses the same signature input and output
   encodings.
2. The manifest profile replaces ambiguous `output_schema_version` with the independent pins
   `assessment_output_schema_version=evidence-item-assessment.v1` and
   `terminal_output_schema_version=evidence-batch-proposal.v1`. Both pins propagate unchanged
   through the manifest, item assessment, terminal proposal, and process projection; an asset
   capability binds `profile_versions_hash` for the exact split profile. The finalization receipt
   is not falsely declared to carry `profile_versions`.
3. `authorization_proof_ref` is not an Evidence v2 authority field. The manifest signature is the
   authority proof; capability signatures remain direct. All schemas, fixtures, hashes, the
   compatibility matrix, Python validators, and Java parity tests must use that single model.
4. The manifest signature binds the Java room `fencing_token`. `RoomGraphCommand.v1` has no such
   field, so the Graph gateway cross-binds only actual command identity, room epoch, thread,
   snapshot, graph/checkpoint, and invocation/profile fields against the manifest. It then
   independently enforces the current Graph lease fence before checkpoint mutation. The room fence
   and Graph lease fence are distinct tokens; Java Finalizer revalidates the room fence. The schema
   declares the gateway check as `x-gateway-cross-binding` with failure
   `BEFORE_CHECKPOINT_MUTATION`.
5. `RoomGraphCommand.domain_snapshot_ref` authenticates the complete transport artifact, not the
   internal manifest self-hash. Its SHA-256 and size cover the full RFC 8785 canonical signed
   manifest bytes, including `manifest_hash` and `signature`; its immutable allowlisted URI is
   content-addressed by that full-payload SHA-256. The gateway verifies the raw snapshot SHA, size,
   and URI before parsing, requires parsed JSON to re-canonicalize to the same bytes, then verifies
   the internal `manifest_hash` with `manifest_hash` and `signature` omitted, and only then verifies
   the Java ES256 signature. The full-payload and internal hashes are not interchangeable.
6. `RoomGraphCommand.invocation_context.output_schema_version` and the Graph registry output schema
   both pin the terminal `evidence-batch-proposal.v1`. The internal per-item LCEL parser alone pins
   `evidence-item-assessment.v1`; the assessment pin is never substituted as transport metadata.

The final pre-entry authority mapping is normative in every Phase 5 governance document:

```text
snapshot_payload_hash_scope: FULL_RFC8785_CANONICAL_SIGNED_MANIFEST_BYTES
snapshot_payload_size_scope: EXACT_FULL_CANONICAL_SIGNED_MANIFEST_BYTES
snapshot_payload_uri: IMMUTABLE_CONTENT_ADDRESSED_BY_SNAPSHOT_SHA256
internal_manifest_hash_scope: RFC8785_OMIT_MANIFEST_HASH_AND_SIGNATURE
snapshot_and_internal_hashes_interchangeable: false
validation_order: SNAPSHOT_SHA_SIZE_URI -> PARSE_CANONICAL_JSON -> INTERNAL_MANIFEST_HASH -> JAVA_ES256_SIGNATURE
room_graph_command_output_schema_version: evidence-batch-proposal.v1
graph_registry_output_schema_version: evidence-batch-proposal.v1
item_lcel_parser_output_schema_version: evidence-item-assessment.v1
java_room_fence_source: SIGNED_MANIFEST
graph_lease_fence_source: CURRENT_GRAPH_LEASE
fence_tokens_interchangeable: false
engineering_execution: BLOCKED_PENDING_P5_0_ENTRY_EVIDENCE
```

The correction is indivisible. A candidate containing only the signature change, only the split
pins, stale fixture hashes, or one-language validation is invalid. The corrected candidate must
include every regenerated positive and negative fixture hash, Python contract validation, Java
parity, and the governance static gates in one clean commit lineage. P5-BATCH-0 must then run in
full from a new exact clean detached SHA, and entry evidence must be committed separately.

The `45d7f087` diagnostic result and every earlier Phase 5 diagnostic result are non-transitive.
ADR 0013 retroactively quarantines the complete run and overrides its local `PASS` and
`accepted=true` flags for entry-gate purposes. Reports cannot be copied, relabelled, partially
reused, or assembled into P5.0 evidence. The quarantine is a governance fact, not a failed
production deployment.

After the first P5.0 acceptance, this exception expires. Any authority-bearing field, hash
preimage, signature scope, trust binding, or assessment/terminal pin change requires a new schema version,
a compatibility plan, and a new accepted ADR. No later change may cite this ADR to edit an
accepted v1 contract in place.

## Runtime And Promotion Boundaries

This ADR changes contract preparation only:

- `contract_gate: P5.0 NOT_RUN` remains true until fresh exact-SHA evidence is committed.
- Runtime remains `DISABLED` or Java-signed synthetic `SHADOW`; the formal path remains `LEGACY`.
- Real-case shadow, real party data, `TEMPORAL` Evidence allocation, a formal Graph sink, canary,
  production traffic, and promotion remain forbidden.
- Java and Domain PostgreSQL remain the only formal Evidence authority and writer.
- `MIG-004` and `MIG-005` remain `PENDING_PROMOTION`.

## Consequences

The first accepted Evidence v2 contract has one auditable Java trust root and unambiguous output
pins without pretending the rejected draft was a released version. Entry costs one new full Batch
0, fixture regeneration, and two-language parity run. That cost is mandatory because the prior
green suites did not exercise the missing authority invariants.
