package com.example.dispute.room.infrastructure.delivery;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.AccessSessionResolver;
import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.application.SessionPermissionService;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseTimelineEventEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseParticipantRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseTimelineEventRepository;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class CaseEventServiceWakeupTest {

    private static final String CASE_ID = "CASE_1";
    private final CaseTimelineEventRepository eventRepository =
            mock(CaseTimelineEventRepository.class);
    private final FulfillmentCaseRepository caseRepository =
            mock(FulfillmentCaseRepository.class);
    private final CaseParticipantRepository participantRepository =
            mock(CaseParticipantRepository.class);
    private final AccessSessionResolver accessSessionResolver = mock(AccessSessionResolver.class);
    private final SessionPermissionService permissionService =
            mock(SessionPermissionService.class);
    private CaseEventService eventService;

    @BeforeEach
    void setUp() {
        eventService =
                new CaseEventService(
                        eventRepository,
                        caseRepository,
                        participantRepository,
                        accessSessionResolver,
                        permissionService,
                        JsonMapper.builder().build(),
                        Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void wakeupCatchesEachSubscriptionUpFromItsOwnDurableCursor() {
        FulfillmentCaseEntity dispute = mock(FulfillmentCaseEntity.class);
        CaseAccessSessionEntity accessSession = mock(CaseAccessSessionEntity.class);
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(dispute));
        when(accessSessionResolver.resolve(
                        CASE_ID, new AuthenticatedActor("admin-local", ActorRole.ADMIN)))
                .thenReturn(accessSession);
        when(accessSession.getActorRole()).thenReturn(ActorRole.ADMIN);
        when(eventRepository
                        .findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(CASE_ID, 3))
                .thenReturn(List.of(), List.of(event(4)));
        when(eventRepository
                        .findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(CASE_ID, 4))
                .thenReturn(List.of());

        SseEmitter emitter =
                eventService.subscribe(
                        CASE_ID,
                        3,
                        new AuthenticatedActor("admin-local", ActorRole.ADMIN));
        clearInvocations(eventRepository);

        eventService.wakeUp(CASE_ID);

        verify(eventRepository)
                .findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(CASE_ID, 3);
        clearInvocations(eventRepository);

        eventService.wakeUp(CASE_ID);

        verify(eventRepository)
                .findAllByCaseIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(CASE_ID, 4);
        emitter.complete();
    }

    @Test
    void rejectsBlankCaseIdBeforeTouchingSubscriptions() {
        assertThatThrownBy(() -> eventService.wakeUp(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("caseId");
    }

    private static CaseTimelineEventEntity event(long sequence) {
        return CaseTimelineEventEntity.create(
                "EVENT_" + sequence,
                CASE_ID,
                null,
                sequence,
                "INTAKE_PROJECTION_READY",
                Instant.parse("2026-08-04T00:00:00Z"),
                "[]",
                "{}",
                "[]",
                "[]",
                "projection-ready:" + sequence,
                "system");
    }
}
