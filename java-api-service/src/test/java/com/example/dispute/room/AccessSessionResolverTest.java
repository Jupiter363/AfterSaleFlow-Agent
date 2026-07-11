package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.AccessSessionInitializer;
import com.example.dispute.room.application.AccessSessionResolver;
import com.example.dispute.room.domain.PermissionLevel;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseAccessSessionRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseParticipantRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class AccessSessionResolverTest {

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private CaseParticipantRepository participantRepository;
    @Mock private CaseAccessSessionRepository accessSessionRepository;
    @Mock private AccessSessionInitializer accessSessionInitializer;

    private AccessSessionResolver resolver;

    @BeforeEach
    void setUp() {
        resolver =
                new AccessSessionResolver(
                        caseRepository,
                        participantRepository,
                        accessSessionRepository,
                        accessSessionInitializer);
    }

    @Test
    void resolvesUserOwnerToPartyUserAccessSession() {
        FulfillmentCaseEntity dispute = dispute();
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(accessSessionRepository
                        .findByTenantIdAndCaseIdAndActorIdAndActorRoleAndPermissionLevel(
                                "default",
                                dispute.getId(),
                                "user-local",
                                ActorRole.USER,
                                PermissionLevel.PARTY_USER))
                .thenReturn(Optional.empty());
        CaseAccessSessionEntity created =
                CaseAccessSessionEntity.create(
                        "ACCESS_CREATED_USER",
                        "default",
                        dispute.getId(),
                        "user-local",
                        ActorRole.USER,
                        PermissionLevel.PARTY_USER,
                        "user-local");
        when(accessSessionInitializer.initializeInCurrentTransaction(
                        dispute.getId(),
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        PermissionLevel.PARTY_USER))
                .thenReturn(created);

        CaseAccessSessionEntity session =
                resolver.resolve(
                        dispute.getId(), new AuthenticatedActor("user-local", ActorRole.USER));

        assertThat(session.getActorId()).isEqualTo("user-local");
        assertThat(session.getPermissionLevel()).isEqualTo(PermissionLevel.PARTY_USER);
        verify(accessSessionInitializer)
                .initializeInCurrentTransaction(
                        dispute.getId(),
                        new AuthenticatedActor("user-local", ActorRole.USER),
                        PermissionLevel.PARTY_USER);
    }

    @Test
    void initializesMissingSessionInIndependentTransactionWhenCallerIsReadOnly() {
        FulfillmentCaseEntity dispute = dispute();
        AuthenticatedActor actor = new AuthenticatedActor("user-local", ActorRole.USER);
        CaseAccessSessionEntity created =
                CaseAccessSessionEntity.create(
                        "ACCESS_CREATED_READ_ONLY",
                        "default",
                        dispute.getId(),
                        actor.actorId(),
                        actor.role(),
                        PermissionLevel.PARTY_USER,
                        actor.actorId());
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(accessSessionRepository
                        .findByTenantIdAndCaseIdAndActorIdAndActorRoleAndPermissionLevel(
                                "default",
                                dispute.getId(),
                                actor.actorId(),
                                actor.role(),
                                PermissionLevel.PARTY_USER))
                .thenReturn(Optional.empty());
        when(accessSessionInitializer.initializeInNewTransaction(
                        dispute.getId(), actor, PermissionLevel.PARTY_USER))
                .thenReturn(created);

        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        try {
            assertThat(resolver.resolve(dispute.getId(), actor).getId())
                    .isEqualTo("ACCESS_CREATED_READ_ONLY");
        } finally {
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        }

        verify(accessSessionInitializer)
                .initializeInNewTransaction(
                        dispute.getId(), actor, PermissionLevel.PARTY_USER);
    }

    @Test
    void rejectsPartyActorOutsideCaseParticipants() {
        FulfillmentCaseEntity dispute = dispute();
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(participantRepository.existsByCaseIdAndActorIdAndParticipantRole(
                        dispute.getId(), "other-user", ActorRole.USER))
                .thenReturn(false);

        assertThatThrownBy(
                        () ->
                                resolver.resolve(
                                        dispute.getId(),
                                        new AuthenticatedActor("other-user", ActorRole.USER)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("access session");
    }

    @Test
    void resolvesReviewerToReviewerAllWithoutCaseParticipation() {
        FulfillmentCaseEntity dispute = dispute();
        when(caseRepository.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(accessSessionRepository
                        .findByTenantIdAndCaseIdAndActorIdAndActorRoleAndPermissionLevel(
                                "default",
                                dispute.getId(),
                                "reviewer-local",
                                ActorRole.PLATFORM_REVIEWER,
                                PermissionLevel.REVIEWER_ALL))
                .thenReturn(
                        Optional.of(
                                CaseAccessSessionEntity.create(
                                        "ACCESS_EXISTING_REVIEWER",
                                        "default",
                                        dispute.getId(),
                                        "reviewer-local",
                                        ActorRole.PLATFORM_REVIEWER,
                                        PermissionLevel.REVIEWER_ALL,
                                        "reviewer-local")));

        CaseAccessSessionEntity session =
                resolver.resolve(
                        dispute.getId(),
                        new AuthenticatedActor("reviewer-local", ActorRole.PLATFORM_REVIEWER));

        assertThat(session.getId()).isEqualTo("ACCESS_EXISTING_REVIEWER");
        assertThat(session.getPermissionLevel()).isEqualTo(PermissionLevel.REVIEWER_ALL);
    }

    private static FulfillmentCaseEntity dispute() {
        return FulfillmentCaseEntity.imported(
                "CASE_ACCESS_SESSION",
                "ORDER-ACCESS",
                null,
                "LOG-ACCESS",
                "user-local",
                "merchant-local",
                "idem-access",
                "SIGNED_NOT_RECEIVED",
                "Marked delivered but not received",
                "The user states that the signed parcel was never received.",
                RiskLevel.HIGH,
                CaseStatus.EVIDENCE_OPEN,
                "EVIDENCE",
                OffsetDateTime.parse("2026-07-03T02:00:00Z"),
                "OMS",
                "EXT-ACCESS",
                "external-adapter");
    }
}
