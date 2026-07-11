package com.example.dispute.room.application;

import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.AgentConversationSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.repository.AgentConversationSessionRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentSessionInitializer {

    private final FulfillmentCaseRepository caseRepository;
    private final AgentConversationSessionRepository repository;

    public AgentSessionInitializer(
            FulfillmentCaseRepository caseRepository,
            AgentConversationSessionRepository repository) {
        this.caseRepository = caseRepository;
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AgentConversationSessionEntity initializeInCurrentTransaction(
            CaseAccessSessionEntity accessSession,
            RoomType roomType,
            String agentKey,
            String promptProfileId,
            String memoryPolicyId) {
        return initialize(
                accessSession, roomType, agentKey, promptProfileId, memoryPolicyId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AgentConversationSessionEntity initializeInNewTransaction(
            CaseAccessSessionEntity accessSession,
            RoomType roomType,
            String agentKey,
            String promptProfileId,
            String memoryPolicyId) {
        return initialize(
                accessSession, roomType, agentKey, promptProfileId, memoryPolicyId);
    }

    private AgentConversationSessionEntity initialize(
            CaseAccessSessionEntity accessSession,
            RoomType roomType,
            String agentKey,
            String promptProfileId,
            String memoryPolicyId) {
        caseRepository
                .findByIdForUpdate(accessSession.getCaseId())
                .orElseThrow(() -> new IllegalArgumentException("case not found"));
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
