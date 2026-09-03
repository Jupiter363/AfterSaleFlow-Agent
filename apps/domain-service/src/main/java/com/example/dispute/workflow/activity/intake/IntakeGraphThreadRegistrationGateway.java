package com.example.dispute.workflow.activity.intake;

import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistration;

/**
 * Consumer-side mTLS/ES256 transport boundary for graph.thread.register. The transactional-outbox
 * dispatcher invokes this only after the PENDING Domain binding commits; request-path code must not
 * call Python synchronously. Implementations never access Graph PostgreSQL directly.
 */
@FunctionalInterface
public interface IntakeGraphThreadRegistrationGateway {

    RegistrationReceipt ensureRegistered(IntakePrivateThreadRegistration registration);

    record RegistrationReceipt(
            String threadId, String registrationHash, RegistrationStatus status) {
        public RegistrationReceipt {
            if (threadId == null || !threadId.matches("grt\\.v1\\.[0-9a-f]{32}")) {
                throw new IllegalArgumentException("threadId is invalid");
            }
            if (registrationHash == null || !registrationHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("registrationHash is invalid");
            }
            if (status == null) {
                throw new IllegalArgumentException("status must not be null");
            }
        }
    }

    enum RegistrationStatus {
        REGISTERED,
        REPLAYED
    }
}
