package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccessSessionResolverTest {

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private CaseParticipantRepository participantRepository;
    @Mock private CaseAccessSessionRepository accessSessionRepository;

    private AccessSessionResolver resolver;

    @BeforeEach
    void setUp() {
        resolver =
                new AccessSessionResolver(
                        caseRepository, participantRepository, accessSessionRepository);
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
        when(accessSessionRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CaseAccessSessionEntity session =
                resolver.resolve(
                        dispute.getId(), new AuthenticatedActor("user-local", ActorRole.USER));

        assertThat(session.getActorId()).isEqualTo("user-local");
        assertThat(session.getPermissionLevel()).isEqualTo(PermissionLevel.PARTY_USER);
        ArgumentCaptor<CaseAccessSessionEntity> saved =
                ArgumentCaptor.forClass(CaseAccessSessionEntity.class);
        verify(accessSessionRepository).save(saved.capture());
        assertThat(saved.getValue().getCaseId()).isEqualTo(dispute.getId());
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
