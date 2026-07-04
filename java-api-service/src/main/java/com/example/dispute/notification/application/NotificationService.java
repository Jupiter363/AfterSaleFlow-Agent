package com.example.dispute.notification.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.notification.infrastructure.persistence.entity.NotificationEntity;
import com.example.dispute.notification.infrastructure.persistence.entity.NotificationOutboxEntity;
import com.example.dispute.notification.infrastructure.persistence.repository.NotificationOutboxRepository;
import com.example.dispute.notification.infrastructure.persistence.repository.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    private final NotificationRepository repository;
    private final NotificationOutboxRepository outboxRepository;
    private final Clock clock;

    public NotificationService(
            NotificationRepository repository,
            NotificationOutboxRepository outboxRepository,
            Clock clock) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
        this.clock = clock;
    }

    @Transactional
    public NotificationView send(NotificationCommand command) {
        return repository
                .findByBusinessEventKeyAndRecipientId(
                        command.businessEventKey(), command.recipientId())
                .map(NotificationService::view)
                .orElseGet(() -> create(command));
    }

    @Transactional(readOnly = true)
    public List<NotificationView> list(AuthenticatedActor actor) {
        return repository.findAllByRecipientIdOrderByCreatedAtDesc(actor.actorId())
                .stream()
                .map(NotificationService::view)
                .toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount(AuthenticatedActor actor) {
        return repository.countByRecipientIdAndReadAtIsNull(actor.actorId());
    }

    @Transactional
    public NotificationView markRead(String notificationId, AuthenticatedActor actor) {
        NotificationEntity entity =
                repository
                        .findByIdAndRecipientId(notificationId, actor.actorId())
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.CASE_NOT_FOUND,
                                                "notification not visible",
                                                Map.of(
                                                        "notification_id",
                                                        notificationId)));
        entity.markRead(clock.instant());
        repository.save(entity);
        return view(entity);
    }

    @Transactional
    public long markAllRead(AuthenticatedActor actor) {
        Instant now = clock.instant();
        List<NotificationEntity> unread =
                repository.findAllByRecipientIdOrderByCreatedAtDesc(actor.actorId())
                        .stream()
                        .filter(entity -> entity.getReadAt() == null)
                        .peek(entity -> entity.markRead(now))
                        .toList();
        if (!unread.isEmpty()) {
            repository.saveAll(unread);
        }
        return unread.size();
    }

    private NotificationView create(NotificationCommand command) {
        Instant now = clock.instant();
        NotificationEntity saved =
                repository.save(
                        NotificationEntity.create(
                                "NOTICE_" + compactUuid(),
                                command.caseId(),
                                command.businessEventKey(),
                                command.recipientId(),
                                command.recipientRole(),
                                command.notificationType(),
                                command.title(),
                                command.body(),
                                command.deepLink(),
                                command.payloadJson(),
                                now));
        if (!outboxRepository.existsByBusinessEventKey(command.businessEventKey())) {
            outboxRepository.save(
                    NotificationOutboxEntity.pending(
                            "OUTBOX_" + compactUuid(),
                            command.caseId(),
                            command.businessEventKey(),
                            command.notificationType().name(),
                            command.payloadJson(),
                            now));
        }
        return view(saved);
    }

    private static NotificationView view(NotificationEntity entity) {
        return new NotificationView(
                entity.getId(),
                entity.getCaseId(),
                entity.getRecipientId(),
                entity.getRecipientRole(),
                entity.getNotificationType(),
                entity.getTitle(),
                entity.getBody(),
                entity.getDeepLink(),
                entity.getReadAt() != null,
                entity.getCreatedAt(),
                entity.getReadAt());
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
