package com.example.dispute.workflow.infrastructure.persistence.repository;

import com.example.dispute.workflow.infrastructure.persistence.entity.CaseCommandOutboxEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.OutboxStatus;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaseCommandOutboxRepository
        extends JpaRepository<CaseCommandOutboxEntity, String> {

    Optional<CaseCommandOutboxEntity> findByCaseCommandId(String caseCommandId);

    @Query(
            value =
                    """
                    select *
                    from case_command_outbox
                    where id = :outboxId
                      and (
                        (outbox_status in ('PENDING', 'RETRY') and available_at <= :now)
                        or
                        (outbox_status = 'CLAIMED' and lease_expires_at <= :now)
                      )
                    for update skip locked
                    """,
            nativeQuery = true)
    Optional<CaseCommandOutboxEntity> lockDeliverableById(
            @Param("outboxId") String outboxId, @Param("now") OffsetDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            select outbox
              from CaseCommandOutboxEntity outbox
             where outbox.id = :outboxId
               and outbox.outboxStatus = :claimedStatus
               and outbox.leaseOwner = :leaseToken
               and outbox.leaseExpiresAt > :resolvedAt
            """)
    Optional<CaseCommandOutboxEntity> lockClaimedById(
            @Param("outboxId") String outboxId,
            @Param("leaseToken") String leaseToken,
            @Param("resolvedAt") OffsetDateTime resolvedAt,
            @Param("claimedStatus") OutboxStatus claimedStatus);

    @Query(
            value =
                    """
                    select *
                    from case_command_outbox
                    where (
                        (outbox_status in ('PENDING', 'RETRY') and available_at <= :now)
                        or
                        (outbox_status = 'CLAIMED' and lease_expires_at <= :now)
                    )
                    order by available_at, created_at, id
                    limit :batchSize
                    for update skip locked
                    """,
            nativeQuery = true)
    List<CaseCommandOutboxEntity> lockNextDeliverable(
            @Param("now") OffsetDateTime now, @Param("batchSize") int batchSize);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            """
            update CaseCommandOutboxEntity outbox
               set outbox.outboxStatus = :deliveredStatus,
                   outbox.deliveredAt = :deliveredAt,
                   outbox.temporalRunId = :temporalRunId,
                   outbox.leaseOwner = null,
                   outbox.leaseExpiresAt = null,
                   outbox.lastErrorCode = null,
                   outbox.lastErrorDetail = null,
                   outbox.updatedAt = :deliveredAt,
                   outbox.version = outbox.version + 1
             where outbox.id = :outboxId
               and outbox.outboxStatus = :claimedStatus
               and outbox.leaseOwner = :leaseToken
               and outbox.leaseExpiresAt > :deliveredAt
            """)
    int markDelivered(
            @Param("outboxId") String outboxId,
            @Param("leaseToken") String leaseToken,
            @Param("temporalRunId") String temporalRunId,
            @Param("deliveredAt") OffsetDateTime deliveredAt,
            @Param("claimedStatus") OutboxStatus claimedStatus,
            @Param("deliveredStatus") OutboxStatus deliveredStatus);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            """
            update CaseCommandOutboxEntity outbox
               set outbox.outboxStatus = :retryStatus,
                   outbox.availableAt = :availableAt,
                   outbox.leaseOwner = null,
                   outbox.leaseExpiresAt = null,
                   outbox.lastErrorCode = :errorCode,
                   outbox.lastErrorDetail = :errorDetail,
                   outbox.updatedAt = :failedAt,
                   outbox.version = outbox.version + 1
             where outbox.id = :outboxId
               and outbox.outboxStatus = :claimedStatus
               and outbox.leaseOwner = :leaseToken
               and outbox.leaseExpiresAt > :failedAt
            """)
    int markRetry(
            @Param("outboxId") String outboxId,
            @Param("leaseToken") String leaseToken,
            @Param("availableAt") OffsetDateTime availableAt,
            @Param("errorCode") String errorCode,
            @Param("errorDetail") String errorDetail,
            @Param("failedAt") OffsetDateTime failedAt,
            @Param("claimedStatus") OutboxStatus claimedStatus,
            @Param("retryStatus") OutboxStatus retryStatus);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            """
            update CaseCommandOutboxEntity outbox
               set outbox.outboxStatus = :deadLetterStatus,
                   outbox.leaseOwner = null,
                   outbox.leaseExpiresAt = null,
                   outbox.lastErrorCode = :errorCode,
                   outbox.lastErrorDetail = :errorDetail,
                   outbox.updatedAt = :failedAt,
                   outbox.version = outbox.version + 1
             where outbox.id = :outboxId
               and outbox.outboxStatus = :claimedStatus
               and outbox.leaseOwner = :leaseToken
               and outbox.leaseExpiresAt > :failedAt
            """)
    int markDeadLetter(
            @Param("outboxId") String outboxId,
            @Param("leaseToken") String leaseToken,
            @Param("errorCode") String errorCode,
            @Param("errorDetail") String errorDetail,
            @Param("failedAt") OffsetDateTime failedAt,
            @Param("claimedStatus") OutboxStatus claimedStatus,
            @Param("deadLetterStatus") OutboxStatus deadLetterStatus);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            """
            update CaseCommandOutboxEntity outbox
               set outbox.outboxStatus = :reconciledStatus,
                   outbox.leaseOwner = null,
                   outbox.leaseExpiresAt = null,
                   outbox.lastErrorCode = :reasonCode,
                   outbox.lastErrorDetail = :reasonDetail,
                   outbox.updatedAt = :reconciledAt,
                   outbox.version = outbox.version + 1
             where outbox.id = :outboxId
               and outbox.outboxStatus = :claimedStatus
               and outbox.leaseOwner = :leaseToken
               and outbox.leaseExpiresAt > :reconciledAt
            """)
    int markReconciled(
            @Param("outboxId") String outboxId,
            @Param("leaseToken") String leaseToken,
            @Param("reasonCode") String reasonCode,
            @Param("reasonDetail") String reasonDetail,
            @Param("reconciledAt") OffsetDateTime reconciledAt,
            @Param("claimedStatus") OutboxStatus claimedStatus,
            @Param("reconciledStatus") OutboxStatus reconciledStatus);
}
