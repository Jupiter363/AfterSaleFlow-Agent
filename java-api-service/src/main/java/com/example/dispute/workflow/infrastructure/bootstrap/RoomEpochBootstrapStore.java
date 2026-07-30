package com.example.dispute.workflow.infrastructure.bootstrap;

import static com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.BootstrapOutboxStatus.CLAIMED;
import static com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.BootstrapOutboxStatus.RETRY;

import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.workflow.application.epoch.ConfiguredRoomEpochSelector;
import com.example.dispute.workflow.application.epoch.RoomEpochSelection;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpochReceipt;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseProcessProjectionEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.RoomEpochBootstrapOutboxEntity;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseProcessProjectionRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.RoomEpochBootstrapOutboxRepository;
import com.example.dispute.workflow.targete2e.temporal.room.hearing.TargetHearingProvisioningRunIds;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RoomEpochBootstrapStore implements RoomEpochBootstrapEnqueuer {

    private final RoomEpochBootstrapOutboxRepository outboxRepository;
    private final FulfillmentCaseRepository caseRepository;
    private final CaseProcessProjectionRepository projectionRepository;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbcTemplate;
    private final RoomEpochProvisioningMapper mapper;

    public RoomEpochBootstrapStore(
            RoomEpochBootstrapOutboxRepository outboxRepository,
            FulfillmentCaseRepository caseRepository,
            CaseProcessProjectionRepository projectionRepository,
            EntityManager entityManager,
            JdbcTemplate jdbcTemplate,
            RoomEpochProvisioningMapper mapper) {
        this.outboxRepository = outboxRepository;
        this.caseRepository = caseRepository;
        this.projectionRepository = projectionRepository;
        this.entityManager = entityManager;
        this.jdbcTemplate = jdbcTemplate;
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String enqueue(
            CaseRoomEpochEntity epoch,
            CaseProcessProjectionEntity projection,
            OffsetDateTime availableAt) {
        Objects.requireNonNull(epoch, "epoch must not be null");
        Objects.requireNonNull(projection, "projection must not be null");
        OffsetDateTime requestedAt =
                Objects.requireNonNull(availableAt, "availableAt must not be null");
        ProvisionRoomEpoch command = mapper.fromLockedState(epoch, projection, requestedAt);
        String payloadJson = mapper.toJson(command);
        String payloadSha256 = command.payloadSha256();
        Optional<RoomEpochBootstrapOutboxEntity> existing =
                outboxRepository.findByEpochId(command.epochId());
        if (existing.isPresent()) {
            ProvisionRoomEpoch existingCommand = mapper.fromOutbox(existing.orElseThrow());
            if (!existingCommand.equals(command)) {
                throw new IllegalStateException("room epoch bootstrap was enqueued with a different payload");
            }
            return existing.orElseThrow().getId();
        }
        String outboxId = deterministicOutboxId(command.epochId());
        RoomEpochBootstrapOutboxEntity outbox =
                RoomEpochBootstrapOutboxEntity.pending(
                        outboxId,
                        command,
                        CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                        CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE,
                        payloadJson,
                        payloadSha256,
                        requestedAt);
        outboxRepository.save(outbox);
        return outboxId;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ClaimedRoomEpochBootstrap> claimById(
            String outboxId, OffsetDateTime now, Duration leaseDuration) {
        requireLeaseDuration(leaseDuration);
        return outboxRepository
                .lockDeliverableById(outboxId, now)
                .map(outbox -> claim(outbox, now, leaseDuration));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ClaimedRoomEpochBootstrap> claimNext(
            OffsetDateTime now, Duration leaseDuration) {
        requireLeaseDuration(leaseDuration);
        List<RoomEpochBootstrapOutboxEntity> rows =
                outboxRepository.lockNextDeliverable(now, 1);
        return rows.isEmpty()
                ? Optional.empty()
                : Optional.of(claim(rows.getFirst(), now, leaseDuration));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean beginProvisioning(
            ClaimedRoomEpochBootstrap delivery, OffsetDateTime startedAt) {
        Optional<LockedBootstrap> locked = lockAggregate(delivery, startedAt);
        if (locked.isEmpty()) {
            return false;
        }
        LockedBootstrap state = locked.orElseThrow();
        int epochRows;
        if (delivery.command().writerMode() == WriterMode.TEMPORAL) {
            epochRows =
                    jdbcTemplate.update(
                            """
                            update case_room_epoch
                               set lifecycle_status = 'PROVISIONING',
                                   provisioning_status = 'PROVISIONING',
                                   updated_at = ?,
                                   version = version + 1
                             where id = ?
                               and writer_mode = 'TEMPORAL'
                               and lifecycle_status in ('PREPARING', 'PROVISIONING')
                               and provisioning_status in ('PENDING', 'PROVISIONING')
                            """,
                            startedAt,
                            state.epoch().getId());
        } else {
            epochRows =
                    jdbcTemplate.update(
                            """
                            update case_room_epoch
                               set provisioning_status = 'PROVISIONING',
                                   updated_at = ?,
                                   version = version + 1
                             where id = ?
                               and writer_mode = 'SHADOW'
                               and lifecycle_status = 'ACTIVE'
                               and provisioning_status in ('PENDING', 'PROVISIONING')
                            """,
                            startedAt,
                            state.epoch().getId());
        }
        int projectionRows =
                jdbcTemplate.update(
                        """
                        update case_process_projection
                           set writer_activation_status = 'PROVISIONING',
                               updated_at = ?,
                               version = version + 1
                         where case_id = ?
                           and room_epoch = ?
                           and fencing_token = ?
                           and writer_mode = ?
                           and writer_activation_status in ('PREPARING', 'PROVISIONING')
                        """,
                        startedAt,
                        delivery.command().caseId(),
                        delivery.command().roomEpoch(),
                        delivery.command().fencingToken(),
                        delivery.command().writerMode().name());
        requireSingle(epochRows, "epoch begin provisioning");
        requireSingle(projectionRows, "projection begin provisioning");
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean finalizeProvisioning(
            ClaimedRoomEpochBootstrap delivery,
            ProvisionRoomEpochReceipt receipt,
            OffsetDateTime finalizedAt) {
        Optional<LockedBootstrap> locked = lockAggregate(delivery, finalizedAt);
        if (locked.isEmpty()) {
            return false;
        }
        LockedBootstrap state = locked.orElseThrow();
        mapper.requireReceipt(delivery.command(), receipt, state.outbox());
        int epochRows =
                jdbcTemplate.update(
                        """
                        update case_room_epoch
                           set lifecycle_status = 'ACTIVE',
                               provisioning_status = 'READY',
                               temporal_run_id = ?,
                               room_temporal_run_id = ?,
                               provisioned_at = ?,
                               provisioning_failure_code = null,
                               updated_at = ?,
                               version = version + 1
                         where id = ?
                           and writer_mode = ?
                           and lifecycle_status in ('ACTIVE', 'PROVISIONING')
                           and provisioning_status = 'PROVISIONING'
                           and temporal_run_id is null
                           and room_temporal_run_id is null
                        """,
                        receipt.caseWorkflowRunId(),
                        receipt.roomWorkflowRunId(),
                        finalizedAt,
                        finalizedAt,
                        delivery.epochId(),
                        delivery.command().writerMode().name());
        int projectionRows =
                jdbcTemplate.update(
                        """
                        update case_process_projection
                           set writer_activation_status = 'READY',
                               temporal_run_id = ?,
                               updated_at = ?,
                               version = version + 1
                         where case_id = ?
                           and room_epoch = ?
                           and fencing_token = ?
                           and writer_mode = ?
                           and writer_activation_status = 'PROVISIONING'
                           and temporal_run_id is null
                        """,
                        receipt.caseWorkflowRunId(),
                        finalizedAt,
                        delivery.command().caseId(),
                        delivery.command().roomEpoch(),
                        delivery.command().fencingToken(),
                        delivery.command().writerMode().name());
        int hearingProjectionRows = bindHearingRoomRun(delivery, receipt, finalizedAt);
        int outboxRows =
                jdbcTemplate.update(
                        """
                        update room_epoch_bootstrap_outbox
                           set outbox_status = 'DELIVERED',
                               delivered_at = ?,
                               case_temporal_run_id = ?,
                               room_temporal_run_id = ?,
                               lease_owner = null,
                               lease_expires_at = null,
                               last_error_code = null,
                               last_error_detail = null,
                               updated_at = ?,
                               version = version + 1
                         where id = ?
                           and outbox_status = 'CLAIMED'
                           and lease_owner = ?
                           and lease_expires_at > ?
                        """,
                        finalizedAt,
                        receipt.caseWorkflowRunId(),
                        receipt.roomWorkflowRunId(),
                        finalizedAt,
                        delivery.outboxId(),
                        delivery.leaseToken(),
                        finalizedAt);
        requireSingle(epochRows, "epoch activation");
        requireSingle(projectionRows, "projection activation");
        if (requiresHearingActivationBinding(delivery.command())) {
            requireSingle(hearingProjectionRows, "Hearing projection activation");
        }
        requireSingle(outboxRows, "bootstrap delivery completion");
        return true;
    }

    private int bindHearingRoomRun(
            ClaimedRoomEpochBootstrap delivery,
            ProvisionRoomEpochReceipt receipt,
            OffsetDateTime finalizedAt) {
        ProvisionRoomEpoch command = delivery.command();
        if (!requiresHearingActivationBinding(command)) {
            return 0;
        }
        jdbcTemplate.queryForObject(
                "select set_config('app.hearing_activation_commit', 'on', true)", String.class);
        return jdbcTemplate.update(
                """
                update hearing_temporal_projection
                   set temporal_run_id = ?,
                       updated_at = ?
                 where flow_instance_id = ?
                   and case_id = ?
                   and tenant_surrogate = ?
                   and epoch_id = ?
                   and room_type = 'HEARING'
                   and hearing_epoch = ?
                   and writer_mode = 'TEMPORAL'
                   and process_revision = ?
                   and room_revision = ?
                   and fencing_token = ?
                   and current_stage = 'COURT_PREPARING'
                   and stage_sequence = 1
                   and temporal_workflow_id = ?
                   and temporal_run_id = ?
                   and temporal_build_or_deployment = ?
                   and last_acknowledged_receipt_id is null
                   and last_acknowledged_receipt_hash is null
                   and last_acknowledged_history_event_id is null
                """,
                receipt.roomWorkflowRunId(),
                finalizedAt,
                command.roomId(),
                command.caseId(),
                command.tenantSurrogate(),
                command.epochId(),
                command.roomEpoch(),
                command.initialProcessRevision(),
                command.initialRoomRevision(),
                command.fencingToken(),
                command.roomWorkflowId(),
                TargetHearingProvisioningRunIds.provisional(command.epochId()),
                command.roomWorkflowBuildId());
    }

    static boolean requiresHearingActivationBinding(ProvisionRoomEpoch command) {
        return command.roomType() == RoomType.HEARING && command.writerMode() == WriterMode.TEMPORAL;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRetry(
            ClaimedRoomEpochBootstrap delivery,
            String errorCode,
            String errorDetail,
            OffsetDateTime availableAt,
            OffsetDateTime failedAt) {
        return outboxRepository.markRetry(
                        delivery.outboxId(),
                        delivery.leaseToken(),
                        availableAt,
                        truncate(errorCode, 64),
                        truncate(errorDetail, 4096),
                        failedAt,
                        CLAIMED,
                        RETRY)
                == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean deadLetter(
            ClaimedRoomEpochBootstrap delivery,
            String errorCode,
            String errorDetail,
            OffsetDateTime failedAt) {
        Optional<LockedBootstrap> locked = lockAggregate(delivery, failedAt);
        if (locked.isEmpty()) {
            return false;
        }
        LockedBootstrap state = locked.orElseThrow();
        String durableCode = truncate(errorCode, 64);
        int epochRows;
        int projectionRows;
        if (delivery.command().writerMode() == WriterMode.TEMPORAL) {
            epochRows =
                    jdbcTemplate.update(
                            """
                            update case_room_epoch
                               set lifecycle_status = 'PROVISIONING_FAILED',
                                   provisioning_status = 'FAILED',
                                   provisioning_failure_code = ?,
                                   terminal_at = ?,
                                   updated_at = ?,
                                   version = version + 1
                             where id = ?
                               and writer_mode = 'TEMPORAL'
                               and lifecycle_status in ('PREPARING', 'PROVISIONING')
                               and provisioning_status in ('PENDING', 'PROVISIONING')
                            """,
                            durableCode,
                            failedAt,
                            failedAt,
                            delivery.epochId());
            projectionRows =
                    updateProjectionFailure(delivery, "FAILED", failedAt);
        } else {
            installLegacyFallback(state, durableCode, failedAt);
            epochRows = 1;
            projectionRows = 1;
        }
        int outboxRows =
                jdbcTemplate.update(
                        """
                        update room_epoch_bootstrap_outbox
                           set outbox_status = 'DEAD_LETTER',
                               lease_owner = null,
                               lease_expires_at = null,
                               last_error_code = ?,
                               last_error_detail = ?,
                               updated_at = ?,
                               version = version + 1
                         where id = ?
                           and outbox_status = 'CLAIMED'
                           and lease_owner = ?
                           and lease_expires_at > ?
                        """,
                        durableCode,
                        truncate(errorDetail, 4096),
                        failedAt,
                        delivery.outboxId(),
                        delivery.leaseToken(),
                        failedAt);
        requireSingle(epochRows, "epoch provisioning failure");
        requireSingle(projectionRows, "projection provisioning failure");
        requireSingle(outboxRows, "bootstrap dead letter");
        return true;
    }

    private void installLegacyFallback(
            LockedBootstrap state, String failureCode, OffsetDateTime failedAt) {
        CaseRoomEpochEntity failed = state.epoch();
        CaseProcessProjectionEntity projection = state.projection();
        long nextProcessRevision = Math.addExact(failed.getProcessRevision(), 1);
        long terminalRoomRevision = Math.addExact(failed.getRoomRevision(), 1);
        long nextRoomEpoch =
                requiredLong(
                        jdbcTemplate.queryForObject(
                                """
                                select coalesce(max(room_epoch), -1) + 1
                                  from case_room_epoch
                                 where case_id = ? and room_type = ?
                                """,
                                Long.class,
                                failed.getCaseId(),
                                failed.getRoomType().name()),
                        "next room epoch");
        long nextFencingToken =
                requiredLong(
                        jdbcTemplate.queryForObject(
                                """
                                select coalesce(max(fencing_token), 0) + 1
                                  from case_room_epoch
                                 where case_id = ?
                                """,
                                Long.class,
                                failed.getCaseId()),
                        "next fencing token");

        failed.terminalizeFailedShadowProvisioning(
                failed.getFencingToken(),
                nextProcessRevision,
                terminalRoomRevision,
                failureCode,
                failedAt);
        entityManager.flush();

        RoomEpochSelection legacy =
                ConfiguredRoomEpochSelector.terminalLegacySelection(
                        failed.getRoomType());
        CaseRoomEpochEntity fallback =
                CaseRoomEpochEntity.active(
                        "CRE_" + UUID.randomUUID().toString().replace("-", ""),
                        failed.getTenantSurrogate(),
                        failed.getCaseId(),
                        failed.getRoomId(),
                        failed.getRoomType(),
                        nextRoomEpoch,
                        WriterMode.LEGACY,
                        nextProcessRevision,
                        0,
                        nextFencingToken,
                        null,
                        null,
                        legacy.buildId(),
                        legacy.graphKey(),
                        legacy.graphVersion(),
                        legacy.checkpointSchemaVersion(),
                        legacy.streamProtocol(),
                        legacy.selectionSchemaVersion(),
                        legacy.processContractVersion(),
                        legacy.workflowType(),
                        failedAt);
        entityManager.persist(fallback);
        entityManager.flush();

        projection.switchTo(
                failed.getRoomEpoch(),
                failed.getFencingToken(),
                projection.getMacroPhase(),
                projection.getCurrentRoom(),
                projection.getRoomPhase(),
                WriterMode.LEGACY,
                nextProcessRevision,
                nextRoomEpoch,
                nextFencingToken,
                projection.getProjectedDeadlineAt(),
                null,
                null,
                legacy.buildId(),
                failedAt);
        entityManager.flush();
    }

    private int updateProjectionFailure(
            ClaimedRoomEpochBootstrap delivery,
            String activationStatus,
            OffsetDateTime failedAt) {
        return jdbcTemplate.update(
                """
                update case_process_projection
                   set writer_activation_status = ?,
                       updated_at = ?,
                       version = version + 1
                 where case_id = ?
                   and room_epoch = ?
                   and fencing_token = ?
                   and writer_mode = ?
                   and writer_activation_status in ('PREPARING', 'PROVISIONING')
                """,
                activationStatus,
                failedAt,
                delivery.command().caseId(),
                delivery.command().roomEpoch(),
                delivery.command().fencingToken(),
                delivery.command().writerMode().name());
    }

    private Optional<LockedBootstrap> lockAggregate(
            ClaimedRoomEpochBootstrap delivery, OffsetDateTime resolvedAt) {
        caseRepository
                .findByIdForUpdate(delivery.command().caseId())
                .orElseThrow(() -> new IllegalStateException("bootstrap case no longer exists"));
        CaseRoomEpochEntity epoch =
                entityManager.find(
                        CaseRoomEpochEntity.class,
                        delivery.epochId(),
                        LockModeType.PESSIMISTIC_WRITE);
        if (epoch == null) {
            throw new IllegalStateException("bootstrap epoch no longer exists");
        }
        CaseProcessProjectionEntity projection =
                projectionRepository
                        .findByIdForUpdate(delivery.command().caseId())
                        .orElseThrow(
                                () -> new IllegalStateException("bootstrap projection no longer exists"));
        Optional<RoomEpochBootstrapOutboxEntity> outbox =
                outboxRepository.lockClaimedById(
                        delivery.outboxId(), delivery.leaseToken(), resolvedAt, CLAIMED);
        if (outbox.isEmpty()) {
            return Optional.empty();
        }
        mapper.requireLockedState(delivery.command(), epoch, projection, outbox.orElseThrow());
        return Optional.of(new LockedBootstrap(epoch, projection, outbox.orElseThrow()));
    }

    private ClaimedRoomEpochBootstrap claim(
            RoomEpochBootstrapOutboxEntity outbox,
            OffsetDateTime now,
            Duration leaseDuration) {
        String leaseToken = UUID.randomUUID().toString();
        OffsetDateTime leaseExpiresAt = now.plus(leaseDuration);
        outbox.claim(leaseToken, now, leaseExpiresAt);
        ProvisionRoomEpoch command = mapper.fromOutbox(outbox);
        return new ClaimedRoomEpochBootstrap(
                outbox.getId(),
                outbox.getEpochId(),
                outbox.getWorkflowType(),
                outbox.getTaskQueue(),
                outbox.getUpdateId(),
                outbox.getPayloadSha256(),
                command,
                outbox.getAttemptCount(),
                leaseToken,
                leaseExpiresAt);
    }

    private static String deterministicOutboxId(String epochId) {
        return "REBOOT_"
                + UUID.nameUUIDFromBytes(epochId.getBytes(StandardCharsets.UTF_8));
    }

    private static void requireLeaseDuration(Duration leaseDuration) {
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
    }

    private static void requireSingle(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException(operation + " lost its fence");
        }
    }

    private static String truncate(String value, int maxLength) {
        String required = value == null || value.isBlank() ? "UNKNOWN" : value;
        return required.length() <= maxLength ? required : required.substring(0, maxLength);
    }

    private static long requiredLong(Long value, String field) {
        if (value == null || value < 0) {
            throw new IllegalStateException(field + " is unavailable");
        }
        return value;
    }

    private record LockedBootstrap(
            CaseRoomEpochEntity epoch,
            CaseProcessProjectionEntity projection,
            RoomEpochBootstrapOutboxEntity outbox) {}
}
