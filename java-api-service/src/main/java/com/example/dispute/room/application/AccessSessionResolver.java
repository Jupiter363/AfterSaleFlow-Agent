package com.example.dispute.room.application;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.domain.PermissionLevel;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseAccessSessionRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseParticipantRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccessSessionResolver {

    public static final String DEFAULT_TENANT = "default";

    private final FulfillmentCaseRepository caseRepository;
    private final CaseParticipantRepository participantRepository;
    private final CaseAccessSessionRepository accessSessionRepository;

    public AccessSessionResolver(
            FulfillmentCaseRepository caseRepository,
            CaseParticipantRepository participantRepository,
            CaseAccessSessionRepository accessSessionRepository) {
        this.caseRepository = caseRepository;
        this.participantRepository = participantRepository;
        this.accessSessionRepository = accessSessionRepository;
    }

    @Transactional
    public CaseAccessSessionEntity resolve(String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity dispute =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(() -> new IllegalArgumentException("case not found"));
        PermissionLevel permissionLevel = permissionLevelFor(dispute, actor);
        return accessSessionRepository
                .findByTenantIdAndCaseIdAndActorIdAndActorRoleAndPermissionLevel(
                        DEFAULT_TENANT,
                        dispute.getId(),
                        actor.actorId(),
                        actor.role(),
                        permissionLevel)
                .orElseGet(
                        () ->
                                accessSessionRepository.save(
                                        CaseAccessSessionEntity.create(
                                                "ACCESS_" + compactUuid(),
                                                DEFAULT_TENANT,
                                                dispute.getId(),
                                                actor.actorId(),
                                                actor.role(),
                                                permissionLevel,
                                                actor.actorId())));
    }

    private PermissionLevel permissionLevelFor(
            FulfillmentCaseEntity dispute, AuthenticatedActor actor) {
        return switch (actor.role()) {
            case USER -> {
                assertPartyCanAccess(dispute, actor, ActorRole.USER, dispute.getUserId());
                yield PermissionLevel.PARTY_USER;
            }
            case MERCHANT -> {
                assertPartyCanAccess(
                        dispute, actor, ActorRole.MERCHANT, dispute.getMerchantId());
                yield PermissionLevel.PARTY_MERCHANT;
            }
            case CUSTOMER_SERVICE -> PermissionLevel.SERVICE_ASSIST;
            case PLATFORM_REVIEWER -> PermissionLevel.REVIEWER_ALL;
            case ADMIN -> PermissionLevel.ADMIN_ALL;
            case SYSTEM -> PermissionLevel.SYSTEM_ALL;
        };
    }

    private void assertPartyCanAccess(
            FulfillmentCaseEntity dispute,
            AuthenticatedActor actor,
            ActorRole expectedRole,
            String ownerActorId) {
        if (actor.actorId().equals(ownerActorId)) {
            return;
        }
        boolean participant =
                participantRepository.existsByCaseIdAndActorIdAndParticipantRole(
                        dispute.getId(), actor.actorId(), expectedRole);
        if (!participant) {
            throw new ForbiddenException("actor cannot create access session for this case");
        }
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
