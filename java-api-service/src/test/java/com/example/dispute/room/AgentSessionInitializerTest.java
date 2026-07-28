package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.AgentSessionInitializer;
import com.example.dispute.room.domain.PermissionLevel;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.AgentConversationSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.repository.AgentConversationSessionRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentSessionInitializerTest {

    private static final String CASE_ID = "CASE_AGENT_SESSION";

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private AgentConversationSessionRepository repository;
    @Mock private FulfillmentCaseEntity disputeCase;

    private AgentSessionInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new AgentSessionInitializer(caseRepository, repository);
        when(caseRepository.findByIdForUpdate(CASE_ID)).thenReturn(Optional.of(disputeCase));
    }

    @Test
    void flushesNewSessionBeforeReturningToSameTransactionJdbcConsumers() {
        CaseAccessSessionEntity accessSession = accessSession();
        when(repository
                        .findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId(
                                "default",
                                CASE_ID,
                                RoomType.INTAKE,
                                "user-local",
                                ActorRole.USER,
                                "DISPUTE_INTAKE_OFFICER",
                                "DISPUTE_INTAKE_OFFICER:USER:v1"))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(AgentConversationSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AgentConversationSessionEntity result =
                initializer.initializeInCurrentTransaction(
                        accessSession,
                        RoomType.INTAKE,
                        "DISPUTE_INTAKE_OFFICER",
                        "DISPUTE_INTAKE_OFFICER:USER:v1",
                        "MEMEO_DEFAULT");

        assertThat(result.getAccessSessionId()).isEqualTo(accessSession.getId());
        verify(repository).saveAndFlush(any(AgentConversationSessionEntity.class));
        verify(repository, never()).save(any(AgentConversationSessionEntity.class));
    }

    @Test
    void reusesExistingSessionWithoutWritingItAgain() {
        CaseAccessSessionEntity accessSession = accessSession();
        AgentConversationSessionEntity existing =
                AgentConversationSessionEntity.create(
                        "AGENT_SESSION_EXISTING",
                        accessSession,
                        RoomType.INTAKE,
                        "DISPUTE_INTAKE_OFFICER",
                        "DISPUTE_INTAKE_OFFICER:USER:v1",
                        "MEMEO_DEFAULT",
                        "user-local");
        when(repository
                        .findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId(
                                "default",
                                CASE_ID,
                                RoomType.INTAKE,
                                "user-local",
                                ActorRole.USER,
                                "DISPUTE_INTAKE_OFFICER",
                                "DISPUTE_INTAKE_OFFICER:USER:v1"))
                .thenReturn(Optional.of(existing));

        AgentConversationSessionEntity result =
                initializer.initializeInCurrentTransaction(
                        accessSession,
                        RoomType.INTAKE,
                        "DISPUTE_INTAKE_OFFICER",
                        "DISPUTE_INTAKE_OFFICER:USER:v1",
                        "MEMEO_DEFAULT");

        assertThat(result).isSameAs(existing);
        verify(repository, never()).save(any(AgentConversationSessionEntity.class));
        verify(repository, never()).saveAndFlush(any(AgentConversationSessionEntity.class));
    }

    private static CaseAccessSessionEntity accessSession() {
        return CaseAccessSessionEntity.create(
                "ACCESS_USER_AGENT_SESSION",
                "default",
                CASE_ID,
                "user-local",
                ActorRole.USER,
                PermissionLevel.PARTY_USER,
                "system");
    }
}
