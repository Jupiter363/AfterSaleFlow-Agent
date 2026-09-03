package com.example.dispute.workflow.infrastructure.persistence.repository;

import com.example.dispute.workflow.infrastructure.persistence.entity.RoomEpochBootstrapOutboxEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.BootstrapOutboxStatus;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomEpochBootstrapOutboxRepository
        extends JpaRepository<RoomEpochBootstrapOutboxEntity, String> {

    Optional<RoomEpochBootstrapOutboxEntity> findByEpochId(String epochId);

    @Query(
            value =
                    """
                    select *
                      from room_epoch_bootstrap_outbox
                     where id = :outboxId
                       and (
                         (outbox_status in ('PENDING', 'RETRY') and available_at <= :now)
                         or (outbox_status = 'CLAIMED' and lease_expires_at <= :now)
                       )
                     for update skip locked
                    """,
            nativeQuery = true)
    Optional<RoomEpochBootstrapOutboxEntity> lockDeliverableById(
            @Param("outboxId") String outboxId, @Param("now") OffsetDateTime now);

    @Query(
            value =
                    """
                    select *
                      from room_epoch_bootstrap_outbox
                     where (
                         (outbox_status in ('PENDING', 'RETRY') and available_at <= :now)
                         or (outbox_status = 'CLAIMED' and lease_expires_at <= :now)
                       )
                     order by available_at, created_at, id
                     limit :batchSize
                     for update skip locked
                    """,
            nativeQuery = true)
    List<RoomEpochBootstrapOutboxEntity> lockNextDeliverable(
            @Param("now") OffsetDateTime now, @Param("batchSize") int batchSize);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            select outbox
              from RoomEpochBootstrapOutboxEntity outbox
             where outbox.id = :outboxId
               and outbox.outboxStatus = :claimedStatus
               and outbox.leaseOwner = :leaseToken
               and outbox.leaseExpiresAt > :resolvedAt
            """)
    Optional<RoomEpochBootstrapOutboxEntity> lockClaimedById(
            @Param("outboxId") String outboxId,
            @Param("leaseToken") String leaseToken,
            @Param("resolvedAt") OffsetDateTime resolvedAt,
            @Param("claimedStatus") BootstrapOutboxStatus claimedStatus);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            """
            update RoomEpochBootstrapOutboxEntity outbox
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
            @Param("claimedStatus") BootstrapOutboxStatus claimedStatus,
            @Param("retryStatus") BootstrapOutboxStatus retryStatus);
}
