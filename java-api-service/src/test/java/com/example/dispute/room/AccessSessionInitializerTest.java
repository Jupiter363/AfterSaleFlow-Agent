package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.AccessSessionInitializer;
import com.example.dispute.room.domain.PermissionLevel;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseAccessSessionRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccessSessionInitializerTest {

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private CaseAccessSessionRepository repository;
    @Mock private FulfillmentCaseEntity disputeCase;

    @Test
    void persistsAnAccessSessionInTheExplicitTargetTenant() {
        String tenantId = "tenant-target-activation";
        String caseId = "CASE_TARGET_ACCESS";
        AuthenticatedActor actor = new AuthenticatedActor("user-local", ActorRole.USER);
        AccessSessionInitializer initializer = new AccessSessionInitializer(caseRepository, repository);
        when(caseRepository.findByIdForUpdate(caseId)).thenReturn(Optional.of(disputeCase));
        when(repository
                        .findByTenantIdAndCaseIdAndActorIdAndActorRoleAndPermissionLevel(
                                tenantId,
                                caseId,
                                actor.actorId(),
                                actor.role(),
                                PermissionLevel.PARTY_USER))
                .thenReturn(Optional.empty());
        when(repository.save(any(CaseAccessSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CaseAccessSessionEntity result = initializer.initializeInCurrentTransaction(
                tenantId, caseId, actor, PermissionLevel.PARTY_USER);

        assertThat(result.getTenantId()).isEqualTo(tenantId);
    }
}
