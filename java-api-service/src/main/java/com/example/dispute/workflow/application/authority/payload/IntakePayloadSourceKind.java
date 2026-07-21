package com.example.dispute.workflow.application.authority.payload;

/** Closed source kinds accepted by the P4-R1.5 Intake payload authority boundary. */
public enum IntakePayloadSourceKind {
    EXISTING_PRIVATE_EVENT("intake-turn-event.v2", 32 * 1024, false),
    SERVER_MINTED_HUMAN_INPUT("intake-human-input-command.v1", 32 * 1024, true),
    SERVER_CANONICAL_BRANCH("intake-branch-command.v1", 16 * 1024, true);

    private final String schemaVersion;
    private final long maximumSizeBytes;
    private final boolean requiresPutReceipt;

    IntakePayloadSourceKind(
            String schemaVersion, long maximumSizeBytes, boolean requiresPutReceipt) {
        this.schemaVersion = schemaVersion;
        this.maximumSizeBytes = maximumSizeBytes;
        this.requiresPutReceipt = requiresPutReceipt;
    }

    public String schemaVersion() {
        return schemaVersion;
    }

    public long maximumSizeBytes() {
        return maximumSizeBytes;
    }

    public boolean requiresPutReceipt() {
        return requiresPutReceipt;
    }
}
