package com.example.dispute.workflow.application.intake;

/** Frozen bounded operation-key construction for one formal Intake turn. */
public final class IntakeFinalizationOperationKey {

    public static final int MAX_KEY_LENGTH = 512;

    private IntakeFinalizationOperationKey() {}

    public static String create(
            String caseId,
            long roomEpoch,
            String threadId,
            String commandId,
            String resultHash) {
        caseId = IntakeContractSupport.identifier(caseId, "caseId");
        IntakeContractSupport.nonNegative(roomEpoch, "roomEpoch");
        threadId = IntakeContractSupport.threadId(threadId);
        commandId = IntakeContractSupport.identifier(commandId, "commandId");
        resultHash = IntakeContractSupport.sha256(resultHash, "resultHash");
        String key = "intake.turn.finalize:"
                + caseId + ":" + roomEpoch + ":" + threadId + ":" + commandId + ":" + resultHash;
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("finalization operation key exceeds 512 characters");
        }
        return key;
    }
}
