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
        assertPartyOwnsCase(dispute, initiator);
        repository.saveAll(List.of(activeParticipant(dispute, initiator, now)));
    }

    public void inviteBoth(
            FulfillmentCaseEntity dispute,
            AuthenticatedActor initiator,
            OffsetDateTime now) {
        assertPartyOwnsCase(dispute, initiator);
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

    private static CaseParticipantEntity participant(
            String caseId,
            String actorId,
            ActorRole role,
            AuthenticatedActor initiator,
            OffsetDateTime now) {
        if (role == initiator.role() && actorId.equals(initiator.actorId())) {
            return CaseParticipantEntity.active(
                    participantId(), caseId, actorId, role, now, initiator.actorId());
        }
        return CaseParticipantEntity.invited(
                participantId(), caseId, actorId, role, now, initiator.actorId());
    }

    private static CaseParticipantEntity activeParticipant(
            FulfillmentCaseEntity dispute,
            AuthenticatedActor initiator,
            OffsetDateTime now) {
        return CaseParticipantEntity.active(
                participantId(),
                dispute.getId(),
                initiator.actorId(),
                initiator.role(),
                now,
                initiator.actorId());
    }

    private static void assertPartyOwnsCase(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        boolean owns =
                actor.role() == ActorRole.USER
                                && actor.actorId().equals(dispute.getUserId())
                        || actor.role() == ActorRole.MERCHANT
                                && actor.actorId().equals(dispute.getMerchantId());
        if (!owns) {
            throw new SecurityException("intake confirmation requires a case party");
        }
    }

    private static String participantId() {
        return "PART_" + UUID.randomUUID().toString().replace("-", "");
    }
}
