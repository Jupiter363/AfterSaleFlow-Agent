package com.example.dispute.room.infrastructure.persistence.repository;

import com.example.dispute.config.ActorRole;
import com.example.dispute.room.domain.PermissionLevel;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseAccessSessionRepository
        extends JpaRepository<CaseAccessSessionEntity, String> {

    Optional<CaseAccessSessionEntity>
            findByTenantIdAndCaseIdAndActorIdAndActorRoleAndPermissionLevel(
                    String tenantId,
                    String caseId,
                    String actorId,
                    ActorRole actorRole,
                    PermissionLevel permissionLevel);
}
