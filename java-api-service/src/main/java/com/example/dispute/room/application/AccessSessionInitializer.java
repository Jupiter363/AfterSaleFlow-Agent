package com.example.dispute.room.application;

import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.domain.PermissionLevel;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseAccessSessionRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccessSessionInitializer {

    private final FulfillmentCaseRepository caseRepository;
    private final CaseAccessSessionRepository accessSessionRepository;

    public AccessSessionInitializer(
            FulfillmentCaseRepository caseRepository,
            CaseAccessSessionRepository accessSessionRepository) {
        this.caseRepository = caseRepository;
        this.accessSessionRepository = accessSessionRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CaseAccessSessionEntity initializeInCurrentTransaction(
            String caseId, AuthenticatedActor actor, PermissionLevel permissionLevel) {
        return initialize(caseId, actor, permissionLevel);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CaseAccessSessionEntity initializeInNewTransaction(
            String caseId, AuthenticatedActor actor, PermissionLevel permissionLevel) {
        return initialize(caseId, actor, permissionLevel);
    }

    private CaseAccessSessionEntity initialize(
            String caseId, AuthenticatedActor actor, PermissionLevel permissionLevel) {
        caseRepository
                .findByIdForUpdate(caseId)
                .orElseThrow(() -> new IllegalArgumentException("case not found"));
        return accessSessionRepository
                .findByTenantIdAndCaseIdAndActorIdAndActorRoleAndPermissionLevel(
                        AccessSessionResolver.DEFAULT_TENANT,
                        caseId,
                        actor.actorId(),
                        actor.role(),
                        permissionLevel)
                .orElseGet(
                        () ->
                                accessSessionRepository.save(
                                        CaseAccessSessionEntity.create(
                                                "ACCESS_" + compactUuid(),
                                                AccessSessionResolver.DEFAULT_TENANT,
                                                caseId,
                                                actor.actorId(),
                                                actor.role(),
                                                permissionLevel,
                                                actor.actorId())));
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
