package com.example.dispute.workflow.application.intake;

import java.util.Arrays;

/** Private object-store read port. Implementations must enforce immutable version and private ACL. */
@FunctionalInterface
public interface IntakeImmutableProposalReader {

    StoredProposal load(IntakeProposalReference reference);

    record StoredProposal(
            String artifactId,
            String schemaVersion,
            String uri,
            String objectVersion,
            String contentSha256,
            long sizeBytes,
            byte[] payload) {

        public StoredProposal {
            payload = payload == null ? null : Arrays.copyOf(payload, payload.length);
        }

        @Override
        public byte[] payload() {
            return payload == null ? null : Arrays.copyOf(payload, payload.length);
        }
    }
}
