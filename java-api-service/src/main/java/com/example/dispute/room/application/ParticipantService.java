package com.example.dispute.room.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseParticipantEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseParticipantRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ParticipantService {

    private final CaseParticipantRepository repository;

    public ParticipantService(CaseParticipantRepository repository) {
        this.repository = repository;
    }

    public void addInitiator(
            FulfillmentCaseEntity dispute,
            AuthenticatedActor initiator,
            OffsetDateTime now) {
        if (!casePartyOwnsCase(dispute, initiator)) {
            assertTrustedIntakeActor(initiator);
            return;
        }
        repository.saveAll(
                List.of(
                        participant(
                                dispute.getId(),
                                initiator.actorId(),
                                initiator.role(),
                                initiator,
                                now)));
    }

    public void inviteBoth(
            FulfillmentCaseEntity dispute,
            AuthenticatedActor initiator,
            OffsetDateTime now) {
        assertCanConductIntake(dispute, initiator);
        CaseParticipantEntity user =
                participant(
                        dispute.getId(),
                        dispute.getUserId(),
                        ActorRole.USER,
                        initiator,
                        now);
        CaseParticipantEntity merchant =
                participant(
                        dispute.getId(),
                        dispute.getMerchantId(),
                        ActorRole.MERCHANT,
                        initiator,
                        now);
        repository.saveAll(List.of(user, merchant));
    }

    public void ensureImportedParties(
            FulfillmentCaseEntity dispute,
            AuthenticatedActor systemActor,
            OffsetDateTime now) {
        if (systemActor.role() != ActorRole.SYSTEM) {
            throw new SecurityException(
                    "external dispute participants require service identity");
        }
        if (!repository.existsByCaseIdAndActorIdAndParticipantRole(
                dispute.getId(), dispute.getUserId(), ActorRole.USER)) {
            repository.save(
                    CaseParticipantEntity.invited(
                            participantId(),
                            dispute.getId(),
                            dispute.getUserId(),
                            ActorRole.USER,
                            now,
                            systemActor.actorId()));
        }
        if (!repository.existsByCaseIdAndActorIdAndParticipantRole(
                dispute.getId(),
                dispute.getMerchantId(),
                ActorRole.MERCHANT)) {
            repository.save(
                    CaseParticipantEntity.invited(
                            participantId(),
                            dispute.getId(),
                            dispute.getMerchantId(),
                            ActorRole.MERCHANT,
                            now,
                            systemActor.actorId()));
        }
    }

    private CaseParticipantEntity participant(
            String caseId,
            String actorId,
            ActorRole role,
            AuthenticatedActor initiator,
            OffsetDateTime now) {
        var existing =
                repository.findByCaseIdAndActorIdAndParticipantRole(
                        caseId, actorId, role);
        if (existing.isPresent()) {
            CaseParticipantEntity participant = existing.get();
            if (role == initiator.role()
                    && actorId.equals(initiator.actorId())) {
                participant.activate(now, initiator.actorId());
            }
            return participant;
        }
        if (role == initiator.role() && actorId.equals(initiator.actorId())) {
            return CaseParticipantEntity.active(
                    participantId(), caseId, actorId, role, now, initiator.actorId());
        }
        return CaseParticipantEntity.invited(
                participantId(), caseId, actorId, role, now, initiator.actorId());
    }

    private static void assertCanConductIntake(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        if (!casePartyOwnsCase(dispute, actor) && !trustedIntakeActor(actor)) {
            throw new SecurityException("intake confirmation requires a case party");
        }
    }

    private static boolean casePartyOwnsCase(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        return actor.role() == ActorRole.USER
                        && actor.actorId().equals(dispute.getUserId())
                || actor.role() == ActorRole.MERCHANT
                        && actor.actorId().equals(dispute.getMerchantId());
    }

    private static void assertTrustedIntakeActor(AuthenticatedActor actor) {
        if (!trustedIntakeActor(actor)) {
            throw new SecurityException("intake confirmation requires a case party");
        }
    }

    private static boolean trustedIntakeActor(AuthenticatedActor actor) {
        return switch (actor.role()) {
            case CUSTOMER_SERVICE, PLATFORM_REVIEWER, ADMIN, SYSTEM -> true;
            case USER, MERCHANT -> false;
        };
    }

    private static String participantId() {
        return "PART_" + UUID.randomUUID().toString().replace("-", "");
    }
}
