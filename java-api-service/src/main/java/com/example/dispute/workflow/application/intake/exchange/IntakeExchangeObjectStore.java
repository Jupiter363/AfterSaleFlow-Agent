package com.example.dispute.workflow.application.intake.exchange;

import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeAuthorityValidationPort.PayloadLoadGrant;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeAuthorityValidationPort.ProposalPutGrant;
import java.util.Arrays;

/** Private immutable object-store boundary used after exact Java authority succeeds. */
public interface IntakeExchangeObjectStore {

    LoadedPayload load(PayloadLoadGrant grant);

    StoredProposal put(ProposalPutGrant grant, byte[] canonicalProposal);

    record LoadedPayload(
            String artifactId,
            String schemaVersion,
            String uri,
            String objectVersion,
            String sha256,
            long sizeBytes,
            byte[] canonicalPayload) {

        public LoadedPayload {
            canonicalPayload = canonicalPayload == null
                    ? null
                    : Arrays.copyOf(canonicalPayload, canonicalPayload.length);
        }

        @Override
        public byte[] canonicalPayload() {
            return canonicalPayload == null
                    ? null
                    : Arrays.copyOf(canonicalPayload, canonicalPayload.length);
        }
    }

    record StoredProposal(
            String artifactId,
            String schemaVersion,
            String uri,
            String objectVersion,
            String sha256,
            long sizeBytes) {}
}
