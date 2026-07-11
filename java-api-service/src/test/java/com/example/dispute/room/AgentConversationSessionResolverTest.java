package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.room.application.AgentSessionInitializer;
import com.example.dispute.room.application.AgentSessionResolver;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class AgentConversationSessionResolverTest {

    @Mock private AgentConversationSessionRepository repository;
    @Mock private AgentSessionInitializer initializer;

    private AgentSessionResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new AgentSessionResolver(repository, initializer);
    }

    @Test
    void resolvesSameActorRoomAgentAndProfileToExistingSession() {
        CaseAccessSessionEntity accessSession = userAccessSession();
        AgentConversationSessionEntity existing =
                AgentConversationSessionEntity.create(
                        "AGENT_SESSION_EXISTING",
                        accessSession,
                        RoomType.EVIDENCE,
                        "EVIDENCE_CLERK",
                        "EVIDENCE_CLERK:USER:v1",
                        "MEMEO_DEFAULT",
                        "system");
        when(repository
                        .findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId(
                                "default",
                                "CASE_AGENT_SESSION",
                                RoomType.EVIDENCE,
                                "user-local",
                                ActorRole.USER,
                                "EVIDENCE_CLERK",
                                "EVIDENCE_CLERK:USER:v1"))
                .thenReturn(Optional.of(existing));

        AgentConversationSessionEntity result =
                resolver.resolve(
                        accessSession,
                        RoomType.EVIDENCE,
                        "EVIDENCE_CLERK",
                        "EVIDENCE_CLERK:USER:v1",
                        "MEMEO_DEFAULT");

        assertThat(result.getId()).isEqualTo("AGENT_SESSION_EXISTING");
        assertThat(result.getAccessSessionId()).isEqualTo(accessSession.getId());
    }

    @Test
    void createsSessionWithDeterministicScopeAndAccessSessionLink() {
        CaseAccessSessionEntity accessSession = userAccessSession();
        when(repository
                        .findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId(
                                "default",
                                "CASE_AGENT_SESSION",
                                RoomType.INTAKE,
                                "user-local",
                                ActorRole.USER,
                                "DISPUTE_INTAKE_OFFICER",
                                "DISPUTE_INTAKE_OFFICER:USER:v1"))
                .thenReturn(Optional.empty());
        AgentConversationSessionEntity created =
                AgentConversationSessionEntity.create(
                        "AGENT_SESSION_CREATED",
                        accessSession,
                        RoomType.INTAKE,
                        "DISPUTE_INTAKE_OFFICER",
                        "DISPUTE_INTAKE_OFFICER:USER:v1",
                        "MEMEO_DEFAULT",
                        "user-local");
        when(initializer.initializeInCurrentTransaction(
                        accessSession,
                        RoomType.INTAKE,
                        "DISPUTE_INTAKE_OFFICER",
                        "DISPUTE_INTAKE_OFFICER:USER:v1",
                        "MEMEO_DEFAULT"))
                .thenReturn(created);

        AgentConversationSessionEntity result =
                resolver.resolve(
                        accessSession,
                        RoomType.INTAKE,
                        "DISPUTE_INTAKE_OFFICER",
                        "DISPUTE_INTAKE_OFFICER:USER:v1",
                        "MEMEO_DEFAULT");

        assertThat(result.getAccessSessionId()).isEqualTo(accessSession.getId());
        assertThat(result.getConversationScope())
                .contains("CASE_AGENT_SESSION")
                .contains("INTAKE")
                .contains("user-local")
                .contains("DISPUTE_INTAKE_OFFICER")
                .contains(accessSession.getId());
        verify(initializer)
                .initializeInCurrentTransaction(
                        accessSession,
                        RoomType.INTAKE,
                        "DISPUTE_INTAKE_OFFICER",
                        "DISPUTE_INTAKE_OFFICER:USER:v1",
                        "MEMEO_DEFAULT");
    }

    @Test
    void differentAgentKeysDoNotShareSession() {
        CaseAccessSessionEntity accessSession = userAccessSession();
        when(repository
                        .findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId(
                                "default",
                                "CASE_AGENT_SESSION",
                                RoomType.EVIDENCE,
                                "user-local",
                                ActorRole.USER,
                                "EVIDENCE_CLERK",
                                "EVIDENCE_CLERK:USER:v1"))
                .thenReturn(Optional.empty());
        AgentConversationSessionEntity created =
                AgentConversationSessionEntity.create(
                        "AGENT_SESSION_EVIDENCE",
                        accessSession,
                        RoomType.EVIDENCE,
                        "EVIDENCE_CLERK",
                        "EVIDENCE_CLERK:USER:v1",
                        "MEMEO_DEFAULT",
                        "user-local");
        when(initializer.initializeInCurrentTransaction(
                        accessSession,
                        RoomType.EVIDENCE,
                        "EVIDENCE_CLERK",
                        "EVIDENCE_CLERK:USER:v1",
                        "MEMEO_DEFAULT"))
                .thenReturn(created);

        AgentConversationSessionEntity result =
                resolver.resolve(
                        accessSession,
                        RoomType.EVIDENCE,
                        "EVIDENCE_CLERK",
                        "EVIDENCE_CLERK:USER:v1",
                        "MEMEO_DEFAULT");

        assertThat(result.getAgentKey()).isEqualTo("EVIDENCE_CLERK");
        assertThat(result.getConversationScope()).contains("EVIDENCE_CLERK");
    }

    @Test
    void initializesMissingAgentSessionInIndependentTransactionWhenCallerIsReadOnly() {
        CaseAccessSessionEntity accessSession = userAccessSession();
        AgentConversationSessionEntity created =
                AgentConversationSessionEntity.create(
                        "AGENT_SESSION_READ_ONLY",
                        accessSession,
                        RoomType.INTAKE,
                        "DISPUTE_INTAKE_OFFICER",
                        "DISPUTE_INTAKE_OFFICER:USER:v1",
                        "MEMEO_DEFAULT",
                        "user-local");
        when(repository
                        .findByTenantIdAndCaseIdAndRoomTypeAndActorIdAndActorRoleAndAgentKeyAndPromptProfileId(
                                "default",
                                "CASE_AGENT_SESSION",
                                RoomType.INTAKE,
                                "user-local",
                                ActorRole.USER,
                                "DISPUTE_INTAKE_OFFICER",
                                "DISPUTE_INTAKE_OFFICER:USER:v1"))
                .thenReturn(Optional.empty());
        when(initializer.initializeInNewTransaction(
                        accessSession,
                        RoomType.INTAKE,
                        "DISPUTE_INTAKE_OFFICER",
                        "DISPUTE_INTAKE_OFFICER:USER:v1",
                        "MEMEO_DEFAULT"))
                .thenReturn(created);

        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        try {
            AgentConversationSessionEntity result =
                    resolver.resolve(
                            accessSession,
                            RoomType.INTAKE,
                            "DISPUTE_INTAKE_OFFICER",
                            "DISPUTE_INTAKE_OFFICER:USER:v1",
                            "MEMEO_DEFAULT");
            assertThat(result.getId()).isEqualTo("AGENT_SESSION_READ_ONLY");
        } finally {
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        }

        verify(initializer)
                .initializeInNewTransaction(
                        accessSession,
                        RoomType.INTAKE,
                        "DISPUTE_INTAKE_OFFICER",
                        "DISPUTE_INTAKE_OFFICER:USER:v1",
                        "MEMEO_DEFAULT");
    }

    private static CaseAccessSessionEntity userAccessSession() {
        return CaseAccessSessionEntity.create(
                "ACCESS_USER_AGENT_SESSION",
                "default",
                "CASE_AGENT_SESSION",
                "user-local",
                ActorRole.USER,
                PermissionLevel.PARTY_USER,
                "system");
    }
}
