package com.example.dispute.room.infrastructure.persistence.repository;

import com.example.dispute.config.ActorRole;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.AgentConversationSessionEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentConversationSessionRepository
        extends JpaRepository<AgentConversationSessionEntity, String> {

    Optional<AgentConversationSessionEntity>
            findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId(
                    String tenantId,
                    String caseId,
                    RoomType roomType,
                    String actorId,
                    ActorRole actorRole,
                    String agentKey,
                    String promptProfileId);
}
