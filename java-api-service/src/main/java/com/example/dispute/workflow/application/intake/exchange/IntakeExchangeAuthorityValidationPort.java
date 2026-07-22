package com.example.dispute.workflow.application.intake.exchange;

import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeContract.PayloadLoadRequest;
import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeContract.ProposalPutRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * Java-owned authorization boundary for the private Intake exchange.
 *
 * <p>Implementations must resolve the current Java registration, admitted command, immutable input
 * binding, stage/process revision and signed-synthetic SHADOW eligibility. A successful return is an
 * exact grant, not a partial identity match.
 */
public interface IntakeExchangeAuthorityValidationPort {

    PayloadLoadGrant requirePayloadLoad(PayloadLoadClaim claim);

    ProposalPutGrant requireProposalPut(ProposalPutClaim claim);

    record PayloadLoadClaim(PayloadLoadRequest request) {
        public PayloadLoadClaim {
            Objects.requireNonNull(request, "request");
        }
    }

    record PayloadLoadGrant(PayloadLoadRequest request, String objectVersion) {
        public PayloadLoadGrant {
            Objects.requireNonNull(request, "request");
            IntakeExchangeContract.identifier(objectVersion, "objectVersion");
        }
    }

    record ProposalPutClaim(ProposalPutRequest request, JsonNode canonicalProposal) {
        public ProposalPutClaim {
            Objects.requireNonNull(request, "request");
            if (canonicalProposal == null || !canonicalProposal.isObject()) {
                throw new IllegalArgumentException("canonicalProposal must be an object");
            }
            canonicalProposal = canonicalProposal.deepCopy();
        }

        @Override
        public JsonNode canonicalProposal() {
            return canonicalProposal.deepCopy();
        }
    }

    record ProposalPutGrant(ProposalPutRequest request) {
        public ProposalPutGrant {
            Objects.requireNonNull(request, "request");
        }
    }

    /** Explicit fail-closed authority result. */
    final class Rejected extends SecurityException {
        public Rejected(String message) {
            super(message);
        }

        public Rejected(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
