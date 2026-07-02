package com.example.dispute.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.notification.application.NotificationCommand;
import com.example.dispute.notification.application.NotificationService;
import com.example.dispute.notification.domain.NotificationType;
import com.example.dispute.notification.infrastructure.persistence.entity.NotificationEntity;
import com.example.dispute.notification.infrastructure.persistence.repository.NotificationOutboxRepository;
import com.example.dispute.notification.infrastructure.persistence.repository.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private NotificationOutboxRepository outboxRepository;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service =
                new NotificationService(
                        notificationRepository,
                        outboxRepository,
                        Clock.fixed(
                                Instant.parse("2026-07-03T00:00:00Z"),
                                ZoneOffset.UTC));
    }

    @Test
    void createsOneInboxMessageAndOneOutboxEventForAnIdempotentSummons() {
        when(notificationRepository.findByBusinessEventKeyAndRecipientId(
                        "CASE_1:intake-accepted", "merchant-local"))
                .thenReturn(Optional.empty());
        when(notificationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(outboxRepository.existsByBusinessEventKey("CASE_1:intake-accepted"))
                .thenReturn(false);

        var result =
                service.send(
                        new NotificationCommand(
                                "CASE_1",
                                "CASE_1:intake-accepted",
                                "merchant-local",
                                ActorRole.MERCHANT,
                                NotificationType.DISPUTE_SUMMONS,
                                "争议审理传票",
                                "订单争议已受理，请在两小时内进入证据书记官室。",
                                "/disputes/CASE_1/evidence",
                                "{\"deadline_at\":\"2026-07-03T02:00:00Z\"}"));

        assertThat(result.recipientId()).isEqualTo("merchant-local");
        assertThat(result.read()).isFalse();
        verify(notificationRepository).save(any(NotificationEntity.class));
        verify(outboxRepository).save(any());
    }

    @Test
    void replayingTheSameBusinessEventReturnsTheExistingInboxMessage() {
        NotificationEntity existing =
                NotificationEntity.create(
                        "NOTICE_EXISTING",
                        "CASE_1",
                        "CASE_1:intake-accepted",
                        "merchant-local",
                        ActorRole.MERCHANT,
                        NotificationType.DISPUTE_SUMMONS,
                        "争议审理传票",
                        "请进入证据书记官室",
                        "/disputes/CASE_1/evidence",
                        "{}",
                        Instant.parse("2026-07-03T00:00:00Z"));
        when(notificationRepository.findByBusinessEventKeyAndRecipientId(
                        "CASE_1:intake-accepted", "merchant-local"))
                .thenReturn(Optional.of(existing));

        var replayed =
                service.send(
                        new NotificationCommand(
                                "CASE_1",
                                "CASE_1:intake-accepted",
                                "merchant-local",
                                ActorRole.MERCHANT,
                                NotificationType.DISPUTE_SUMMONS,
                                "争议审理传票",
                                "重复内容不会产生第二封信",
                                "/disputes/CASE_1/evidence",
                                "{}"));

        assertThat(replayed.id()).isEqualTo("NOTICE_EXISTING");
        verify(notificationRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void marksOnlyTheRecipientsOwnMessageAsRead() {
        NotificationEntity existing =
                NotificationEntity.create(
                        "NOTICE_1",
                        "CASE_1",
                        "CASE_1:intake-accepted",
                        "merchant-local",
                        ActorRole.MERCHANT,
                        NotificationType.DISPUTE_SUMMONS,
                        "争议审理传票",
                        "请进入证据书记官室",
                        "/disputes/CASE_1/evidence",
                        "{}",
                        Instant.parse("2026-07-03T00:00:00Z"));
        when(notificationRepository.findByIdAndRecipientId(
                        "NOTICE_1", "merchant-local"))
                .thenReturn(Optional.of(existing));

        var read =
                service.markRead(
                        "NOTICE_1",
                        new AuthenticatedActor(
                                "merchant-local", ActorRole.MERCHANT));

        assertThat(read.read()).isTrue();
        ArgumentCaptor<NotificationEntity> saved =
                ArgumentCaptor.forClass(NotificationEntity.class);
        verify(notificationRepository).save(saved.capture());
        assertThat(saved.getValue().getReadAt())
                .isEqualTo(Instant.parse("2026-07-03T00:00:00Z"));
    }
}
