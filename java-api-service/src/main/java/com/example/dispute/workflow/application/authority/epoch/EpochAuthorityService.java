package com.example.dispute.workflow.application.authority.epoch;

import com.example.dispute.casecore.domain.CasePartyAssignment;
import com.example.dispute.workflow.infrastructure.persistence.authority.epoch.EpochAuthorityLockCoordinator.LockRequest;
import java.time.OffsetDateTime;
import java.util.List;

/** Application port for atomic R1.5 epoch/party authority binding. */
public interface EpochAuthorityService {

    EpochBindingReceipt bind(BindRequest request);

    void revokeAccessSession(RevocationRequest request);

    void revokeAgentSession(RevocationRequest request);

    void retireRegistration(RevocationRequest request);

    AcceptanceSnapshot acceptInert(AcceptanceRequest request);

    record BindRequest(
            EpochSelectionBinding selection,
            CasePartyAssignment caseParties,
            List<EpochPartyAuthority> parties,
            EpochBootstrapOutbox bootstrap,
            LockRequest locks) {
        public BindRequest {
            if (selection == null || caseParties == null || bootstrap == null || locks == null) {
                throw new IllegalArgumentException("selection, caseParties, bootstrap and locks are required");
            }
            parties = List.copyOf(parties);
            if (parties.size() != 2) {
                throw new IllegalArgumentException("exactly INITIATOR and RESPONDENT authorities are required");
            }
        }
    }

    record EpochBindingReceipt(
            String epochId,
            String bootstrapOutboxId,
            List<EpochPartyAuthority> parties,
            boolean created) {
        public EpochBindingReceipt {
            parties = List.copyOf(parties);
        }
    }

    record RevocationRequest(LockRequest locks, OffsetDateTime revokedAt) {
        public RevocationRequest {
            if (locks == null || revokedAt == null) {
                throw new IllegalArgumentException("locks and revokedAt are required");
            }
        }
    }

    record AcceptanceRequest(LockRequest locks, String epochId, long fencingToken) {
        public AcceptanceRequest {
            if (locks == null || epochId == null || epochId.isBlank()) {
                throw new IllegalArgumentException("locks and epochId are required");
            }
            if (fencingToken <= 0) {
                throw new IllegalArgumentException("fencingToken must be positive");
            }
        }
    }

    record AcceptanceSnapshot(String epochId, long fencingToken, boolean inert) {}
}
