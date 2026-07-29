package com.example.dispute.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.room.application.ParticipantService;
import com.example.dispute.room.domain.ParticipantStatus;
import com.example.dispute.room.infrastructure.persistence.entity.CaseParticipantEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseParticipantRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ParticipantServiceTest {

    private static final String CASE_ID = "CASE_IMPORTED_TARGET";
    private static final AuthenticatedActor USER =
            new AuthenticatedActor("user-local", ActorRole.USER);
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-29T02:00:00Z");

    @Mock private CaseParticipantRepository repository;

    @Test
    void activatesOnlyTheInvitedTargetIntakeActor() {
        CaseParticipantEntity invited = CaseParticipantEntity.invited(
                "PART_USER", CASE_ID, USER.actorId(), USER.role(), NOW.minusMinutes(1), "importer");
        when(repository.findByCaseIdAndActorIdAndParticipantRole(CASE_ID, USER.actorId(), USER.role()))
                .thenReturn(Optional.of(invited));

        new ParticipantService(repository).activateExistingParty(CASE_ID, USER, NOW);

        assertThat(invited.getParticipantStatus()).isEqualTo(ParticipantStatus.ACTIVE);
        verify(repository).findByCaseIdAndActorIdAndParticipantRole(CASE_ID, USER.actorId(), USER.role());
        verify(repository, never())
                .findByCaseIdAndActorIdAndParticipantRole(CASE_ID, "merchant-local", ActorRole.MERCHANT);
    }

    @Test
    void doesNotChangeAnAlreadyActiveParty() {
        CaseParticipantEntity active = CaseParticipantEntity.active(
                "PART_USER", CASE_ID, USER.actorId(), USER.role(), NOW.minusMinutes(1), "importer");
        when(repository.findByCaseIdAndActorIdAndParticipantRole(CASE_ID, USER.actorId(), USER.role()))
                .thenReturn(Optional.of(active));

        new ParticipantService(repository).activateExistingParty(CASE_ID, USER, NOW);

        assertThat(active.getParticipantStatus()).isEqualTo(ParticipantStatus.ACTIVE);
    }

    @Test
    void rejectsTrustedOrSystemActorsBeforeLookingUpParticipants() {
        AuthenticatedActor system = new AuthenticatedActor("target-control", ActorRole.SYSTEM);

        assertThatThrownBy(() -> new ParticipantService(repository).activateExistingParty(CASE_ID, system, NOW))
                .isInstanceOf(SecurityException.class)
                .hasMessage("target Intake activation requires a case party");

        verify(repository, never()).findByCaseIdAndActorIdAndParticipantRole(
                CASE_ID, system.actorId(), system.role());
    }
}
