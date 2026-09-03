package com.example.dispute.workflow.application.authority.payload;

import com.example.dispute.workflow.application.authority.epoch.EpochPartyAuthority.Party;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import java.util.Objects;

/** Immutable server-resolved private route used by payload and command authority records. */
public record IntakeAuthorityRoute(
        String partyAuthorityId,
        String epochId,
        String accessSessionId,
        String registrationId,
        String tenantSurrogate,
        String caseId,
        long roomEpoch,
        long fencingToken,
        String threadId,
        String actorId,
        ActorRole actorRole,
        String actorScopeHash,
        String agentSessionId,
        Party party) {

    public IntakeAuthorityRoute {
        identifier(partyAuthorityId, "partyAuthorityId", 128);
        identifier(epochId, "epochId", 64);
        identifier(accessSessionId, "accessSessionId", 64);
        identifier(registrationId, "registrationId", 128);
        identifier(tenantSurrogate, "tenantSurrogate", 128);
        identifier(caseId, "caseId", 64);
        if (roomEpoch < 0 || fencingToken <= 0) {
            throw new IllegalArgumentException("roomEpoch must be non-negative and fencingToken positive");
        }
        if (threadId == null || !threadId.matches("grt[.]v1[.][0-9a-f]{32}")) {
            throw new IllegalArgumentException("threadId must be a graph thread v1 id");
        }
        identifier(actorId, "actorId", 128);
        Objects.requireNonNull(actorRole, "actorRole must not be null");
        if (actorRole != ActorRole.USER && actorRole != ActorRole.MERCHANT) {
            throw new IllegalArgumentException("actorRole must be USER or MERCHANT");
        }
        if (actorScopeHash == null || !actorScopeHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("actorScopeHash must be a lowercase SHA-256");
        }
        identifier(agentSessionId, "agentSessionId", 64);
        Objects.requireNonNull(party, "party must not be null");
    }

    private static void identifier(String value, String field, int maximumLength) {
        if (value == null
                || value.length() > maximumLength
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0," + (maximumLength - 1) + "}")) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
    }
}
