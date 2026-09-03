package com.example.dispute.workflow.application.authority.payload;

/** Atomic persistence port for one payload, command authority, and durable command-outbox tuple. */
public interface IntakePayloadAuthorityStore {

    AcceptanceReceipt accept(Acceptance request);

    record Acceptance(
            IntakePayloadAuthority payload,
            IntakeCommandAuthority command,
            IntakeCommandOutboxBinding outbox) {
        public Acceptance {
            if (payload == null || command == null || outbox == null) {
                throw new IllegalArgumentException("payload, command, and outbox are required");
            }
            command.requirePayload(payload);
            if (!command.commandId().equals(outbox.updateId())) {
                throw new IllegalArgumentException("outbox update id must equal the bound command id");
            }
        }
    }

    record AcceptanceReceipt(
            String payloadAuthorityId, String caseCommandId, String outboxId, boolean replay) {}
}
