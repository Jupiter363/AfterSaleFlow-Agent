package com.example.dispute.casecore.domain;

import com.example.dispute.config.ActorRole;
import java.util.Objects;
import java.util.Optional;

/** Immutable initiator/respondent identity assignment stored on a dispute case. */
public record CasePartyAssignment(
        String initiatorId,
        ActorRole initiatorRole,
        String respondentId,
        ActorRole respondentRole) {

    public CasePartyAssignment {
        initiatorId = required(initiatorId, "initiatorId");
        respondentId = required(respondentId, "respondentId");
        initiatorRole = requirePartyRole(initiatorRole, "initiatorRole");
        respondentRole = requirePartyRole(respondentRole, "respondentRole");
        if (initiatorRole == respondentRole) {
            throw new IllegalArgumentException("initiator and respondent roles must differ");
        }
    }

    public static CasePartyAssignment fromParticipants(
            String userId, String merchantId, ActorRole initiatorRole) {
        ActorRole checkedInitiator = requirePartyRole(initiatorRole, "initiatorRole");
        if (checkedInitiator == ActorRole.USER) {
            return new CasePartyAssignment(
                    userId, ActorRole.USER, merchantId, ActorRole.MERCHANT);
        }
        return new CasePartyAssignment(
                merchantId, ActorRole.MERCHANT, userId, ActorRole.USER);
    }

    /** Match the stable actor id first, then verify the claimed role for that side. */
    public Optional<CasePartyPosition> resolve(String actorId, ActorRole actorRole) {
        if (actorId == null || actorRole == null) {
            return Optional.empty();
        }
        boolean initiatorIdMatches = actorId.equals(initiatorId);
        boolean respondentIdMatches = actorId.equals(respondentId);
        if (initiatorIdMatches && actorRole == initiatorRole) {
            return Optional.of(CasePartyPosition.INITIATOR);
        }
        if (respondentIdMatches && actorRole == respondentRole) {
            return Optional.of(CasePartyPosition.RESPONDENT);
        }
        return Optional.empty();
    }

    public String idFor(ActorRole role) {
        if (role == initiatorRole) {
            return initiatorId;
        }
        if (role == respondentRole) {
            return respondentId;
        }
        throw new IllegalArgumentException("role is not a case party");
    }

    private static ActorRole requirePartyRole(ActorRole role, String field) {
        Objects.requireNonNull(role, field + " must not be null");
        if (role != ActorRole.USER && role != ActorRole.MERCHANT) {
            throw new IllegalArgumentException(field + " must be USER or MERCHANT");
        }
        return role;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
