package com.example.dispute.caseintake.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakePartyCompletionEntity;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.data.jpa.domain.Specification;

/** Actor-scoped case visibility with sequential intake disclosure. */
public final class CaseVisibilitySpecification {

    private static final Set<CaseStatus> RESPONDENT_LOCKED_STATUSES =
            EnumSet.of(
                    CaseStatus.INTAKE_PENDING,
                    CaseStatus.INTAKE_IN_PROGRESS,
                    CaseStatus.WAITING_SLOT_COMPLETION,
                    CaseStatus.INTAKE_COMPLETED);

    private CaseVisibilitySpecification() {}

    public static Specification<FulfillmentCaseEntity> visibleTo(
            AuthenticatedActor actor) {
        if (!isParty(actor.role())) {
            return (root, query, criteria) -> criteria.conjunction();
        }
        return (root, query, criteria) -> {
            Predicate initiatorIdMatches =
                    criteria.equal(root.get("initiatorId"), actor.actorId());
            Predicate respondentIdMatches =
                    criteria.equal(root.get("respondentId"), actor.actorId());

            Predicate initiatorIdentity =
                    criteria.and(
                            initiatorIdMatches,
                            criteria.equal(root.get("initiatorRole"), actor.role()));
            Predicate respondentIdentity =
                    criteria.and(
                            respondentIdMatches,
                            criteria.equal(root.get("respondentRole"), actor.role()));

            Subquery<Integer> initiatorCompletion = query.subquery(Integer.class);
            Root<CaseIntakePartyCompletionEntity> completion =
                    initiatorCompletion.from(CaseIntakePartyCompletionEntity.class);
            initiatorCompletion
                    .select(criteria.literal(1))
                    .where(
                            criteria.equal(completion.get("caseId"), root.get("id")),
                            criteria.equal(
                                    completion.get("participantRole"),
                                    root.get("initiatorRole")),
                            criteria.equal(
                                    completion.get("participantId"),
                                    root.get("initiatorId")),
                            criteria.equal(completion.get("completionStatus"), "COMPLETED"));

            Predicate respondentIsUnlocked =
                    criteria.or(
                            criteria.not(root.get("caseStatus").in(RESPONDENT_LOCKED_STATUSES)),
                            criteria.exists(initiatorCompletion));
            return criteria.or(
                    initiatorIdentity,
                    criteria.and(respondentIdentity, respondentIsUnlocked));
        };
    }

    public static boolean respondentNeedsInitiatorCompletion(CaseStatus status) {
        return RESPONDENT_LOCKED_STATUSES.contains(status);
    }

    private static boolean isParty(ActorRole role) {
        return role == ActorRole.USER || role == ActorRole.MERCHANT;
    }
}
