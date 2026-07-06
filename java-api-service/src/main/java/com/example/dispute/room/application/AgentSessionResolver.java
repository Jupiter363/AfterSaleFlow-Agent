package com.example.dispute.room.application;

import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.AgentConversationSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.repository.AgentConversationSessionRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentSessionResolver {

    private final AgentConversationSessionRepository repository;

    public AgentSessionResolver(AgentConversationSessionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public AgentConversationSessionEntity resolve(
            CaseAccessSessionEntity accessSession,
            RoomType roomType,
            String agentKey,
            String promptProfileId,
            String memoryPolicyId) {
        return repository
                .findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId(
                        accessSession.getTenantId(),
                        accessSession.getCaseId(),
                        roomType,
                        accessSession.getActorId(),
                        accessSession.getActorRole(),
                        agentKey,
                        promptProfileId)
                .orElseGet(
                        () ->
                                repository.save(
                                        AgentConversationSessionEntity.create(
                                                "AGENT_SESSION_" + compactUuid(),
                                                accessSession,
                                                roomType,
                                                agentKey,
                                                promptProfileId,
                                                memoryPolicyId,
                                                accessSession.getActorId())));
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
