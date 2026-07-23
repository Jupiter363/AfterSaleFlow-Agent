# ADR 0014: Phase 5 Evidence Migration Contract Erratum

- Status: ACCEPTED FOR P5-R2
- Scope: Phase 5 Wave B engineering only
- Authorized migration: `java-api-service/src/main/resources/db/migration/V043_5__evidence_finalization_and_operational_recovery.sql`

P5-C3 needs one additive Java migration after Wave A acceptance. `V043_4__evidence_graph_bindings.sql`
is immutable and remains pinned by SHA-256
`f2872430c63db6b8f561ef982ea4b3329d04bd7ecde744aaa625880c02399cb0`.

The authorized migration is additive only. It may create durable receipt, terminal summary,
receipt/load binding, Java authority snapshot, and operational recovery structures. It must not
enable a formal Evidence Finalizer sink, `TEMPORAL` Evidence allocation, real-case shadow, canary,
promotion, or any production traffic path.

The Java domain remains the business authority. Graph and Temporal may supply workflow progress,
leases, retries, and recovery signals, but C3 must validate Java authority and durable receipt facts
before exposing committed terminal or recovery state.
