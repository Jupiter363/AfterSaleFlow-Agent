package com.example.dispute.workflow.activity.tool;

/** Parent-bound proof that a synthetic no-op required no compensating effect. */
public record SyntheticNoopCompensationObservation(
        String schemaVersion,
        String observationId,
        String fixtureId,
        String parentOperationId,
        String parentReceiptHash,
        String status,
        boolean syntheticOnly,
        boolean toolInvoked,
        boolean externalEffectCreated,
        boolean formalBusinessWriteCreated,
        boolean projectionOnly) {

    public static SyntheticNoopCompensationObservation from(
            SyntheticNoopExecutionReceipt parent) {
        if (parent == null || parent.closureRelevant() || parent.externalEffectCreated()) {
            throw new IllegalArgumentException("parent must be a zero-effect synthetic receipt");
        }
        return new SyntheticNoopCompensationObservation(
                "outcome-synthetic-compensation-observation.v1",
                "obs." + parent.receiptHash().substring(0, 32),
                parent.fixtureId(),
                parent.operationId(),
                parent.receiptHash(),
                "NOT_REQUIRED_NO_EFFECT",
                true,
                false,
                false,
                false,
                true);
    }

    public SyntheticNoopCompensationObservation {
        if (!"outcome-synthetic-compensation-observation.v1".equals(schemaVersion)
                || observationId == null
                || !observationId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                || fixtureId == null
                || !fixtureId.matches("OUTCOME_SYNTHETIC_[A-Z0-9._:-]{1,110}")
                || parentOperationId == null
                || !parentOperationId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                || parentReceiptHash == null
                || !parentReceiptHash.matches("[0-9a-f]{64}")
                || !"NOT_REQUIRED_NO_EFFECT".equals(status)
                || !syntheticOnly
                || toolInvoked
                || externalEffectCreated
                || formalBusinessWriteCreated
                || !projectionOnly) {
            throw new IllegalArgumentException("invalid synthetic compensation observation");
        }
    }
}
