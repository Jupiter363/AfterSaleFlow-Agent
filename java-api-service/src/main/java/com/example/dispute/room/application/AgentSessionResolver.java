package com.example.dispute.room.application;

import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.AgentConversationSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.repository.AgentConversationSessionRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AgentSessionResolver {

    private final AgentConversationSessionRepository repository;
    private final AgentSessionInitializer initializer;

    public AgentSessionResolver(
            AgentConversationSessionRepository repository,
            AgentSessionInitializer initializer) {
        this.repository = repository;
        this.initializer = initializer;
    }

    @Transactional
    public AgentConversationSessionEntity resolve(
            CaseAccessSessionEntity accessSession,
            RoomType roomType,
            String agentKey,
            String promptProfileId,
            String memoryPolicyId) {
        return find(accessSession, roomType, agentKey, promptProfileId)
                .orElseGet(
                        () ->
                                initialize(
                                        accessSession,
                                        roomType,
                                        agentKey,
                                        promptProfileId,
                                        memoryPolicyId));
    }

    private AgentConversationSessionEntity initialize(
            CaseAccessSessionEntity accessSession,
            RoomType roomType,
            String agentKey,
            String promptProfileId,
            String memoryPolicyId) {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            return initializer.initializeInNewTransaction(
                    accessSession,
                    roomType,
                    agentKey,
                    promptProfileId,
                    memoryPolicyId);
        }
        return initializer.initializeInCurrentTransaction(
                accessSession, roomType, agentKey, promptProfileId, memoryPolicyId);
    }

    private Optional<AgentConversationSessionEntity> find(
            CaseAccessSessionEntity accessSession,
            RoomType roomType,
            String agentKey,
            String promptProfileId) {
        return repository
                .findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId(
                        accessSession.getTenantId(),
                        accessSession.getCaseId(),
                        roomType,
                        accessSession.getActorId(),
                        accessSession.getActorRole(),
                        agentKey,
                        promptProfileId);
    }
}
