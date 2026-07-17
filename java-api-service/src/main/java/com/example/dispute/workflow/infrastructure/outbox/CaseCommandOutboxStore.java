package com.example.dispute.workflow.infrastructure.outbox;

import static com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.OutboxStatus.CLAIMED;
import static com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.OutboxStatus.DEAD_LETTER;
import static com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.OutboxStatus.DELIVERED;
import static com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.OutboxStatus.RETRY;

import com.example.dispute.workflow.application.command.CaseCommandReferenceMapper;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseCommandEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseCommandOutboxEntity;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseCommandOutboxRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseCommandRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CaseCommandOutboxStore {

    private final CaseCommandOutboxRepository outboxRepository;
    private final CaseCommandRepository commandRepository;
    private final ObjectMapper objectMapper;

    public CaseCommandOutboxStore(
            CaseCommandOutboxRepository outboxRepository,
            CaseCommandRepository commandRepository,
            ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.commandRepository = commandRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ClaimedCaseCommandDelivery> claimById(
            String outboxId, OffsetDateTime now, Duration leaseDuration) {
        requireLeaseDuration(leaseDuration);
        return outboxRepository
                .lockDeliverableById(outboxId, now)
                .map(outbox -> claim(outbox, now, leaseDuration));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ClaimedCaseCommandDelivery> claimBatch(
            OffsetDateTime now, Duration leaseDuration, int batchSize) {
        requireLeaseDuration(leaseDuration);
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        }
        return outboxRepository.lockNextDeliverable(now, batchSize).stream()
                .map(outbox -> claim(outbox, now, leaseDuration))
                .toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markDelivered(
            ClaimedCaseCommandDelivery delivery,
            String temporalRunId,
            OffsetDateTime deliveredAt) {
        if (temporalRunId == null
                || temporalRunId.isBlank()
                || temporalRunId.length() > 128) {
            throw new IllegalArgumentException("temporalRunId is invalid");
        }
        int updated =
                outboxRepository.markDelivered(
                        delivery.outboxId(),
                        delivery.leaseToken(),
                        temporalRunId,
                        deliveredAt,
                        CLAIMED,
                        DELIVERED);
        if (updated == 0) {
            return false;
        }
        CaseCommandEntity command = lockedCommand(delivery.caseCommandId());
        command.markOrchestrationAccepted(deliveredAt);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRetry(
            ClaimedCaseCommandDelivery delivery,
            String errorCode,
            String errorDetail,
            OffsetDateTime availableAt,
            OffsetDateTime failedAt) {
        return outboxRepository.markRetry(
                        delivery.outboxId(),
                        delivery.leaseToken(),
                        availableAt,
                        errorCode,
                        errorDetail,
                        failedAt,
                        CLAIMED,
                        RETRY)
                == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markDeadLetter(
            ClaimedCaseCommandDelivery delivery,
            String errorCode,
            String errorDetail,
            OffsetDateTime failedAt) {
        int updated =
                outboxRepository.markDeadLetter(
                        delivery.outboxId(),
                        delivery.leaseToken(),
                        errorCode,
                        errorDetail,
                        failedAt,
                        CLAIMED,
                        DEAD_LETTER);
        if (updated == 0) {
            return false;
        }
        CaseCommandEntity command = lockedCommand(delivery.caseCommandId());
        command.markOrchestrationFailed(errorCode, failedAt);
        return true;
    }

    private ClaimedCaseCommandDelivery claim(
            CaseCommandOutboxEntity outbox,
            OffsetDateTime now,
            Duration leaseDuration) {
        String leaseToken = UUID.randomUUID().toString();
        OffsetDateTime leaseExpiresAt = now.plus(leaseDuration);
        outbox.claim(leaseToken, now, leaseExpiresAt);
        CaseCommandEntity command =
                commandRepository
                        .findById(outbox.getCaseCommandId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "outbox references a missing case command"));
        return new ClaimedCaseCommandDelivery(
                outbox.getId(),
                outbox.getCaseCommandId(),
                outbox.getDeliveryKind(),
                outbox.getWorkflowId(),
                outbox.getWorkflowType(),
                outbox.getTaskQueue(),
                outbox.getUpdateId(),
                CaseCommandReferenceMapper.fromEntity(command, objectMapper),
                outbox.getAttemptCount(),
                leaseToken,
                leaseExpiresAt);
    }

    private CaseCommandEntity lockedCommand(String commandId) {
        return commandRepository
                .findByIdForUpdate(commandId)
                .orElseThrow(
                        () -> new IllegalStateException("case command no longer exists"));
    }

    private static void requireLeaseDuration(Duration leaseDuration) {
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
    }
}
