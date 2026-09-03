package com.example.dispute.hearing.infrastructure.persistence;

import com.example.dispute.hearing.domain.HearingAuthorityCommit;
import com.example.dispute.hearing.domain.HearingAuthorityExpectation;
import com.example.dispute.hearing.domain.HearingAuthorityLedger;
import com.example.dispute.hearing.domain.HearingAuthorityRejectedException;
import com.example.dispute.hearing.domain.HearingDomainReceipt;
import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.hearing.domain.HearingFormalCommitResult;
import com.example.dispute.hearing.domain.HearingWriterMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL authority ledger. Deliberately not registered as a Bean until the Phase 6 runtime
 * barriers admit a formal Hearing Activity sink.
 */
public final class JdbcHearingAuthorityLedger implements HearingAuthorityLedger {

    private static final String RECEIPT_COLUMNS = """
            schema_version, receipt_id, receipt_hash, operation_type, operation_key,
            request_hash, tenant_surrogate, case_id, flow_instance_id, epoch_id,
            hearing_epoch, writer_mode, fencing_token, source_stage,
            source_stage_sequence, source_process_revision, source_room_revision,
            stage_code, stage_sequence, stage_deadline_at, process_revision,
            room_revision, result_ref, result_hash, committed_event_sequence,
            temporal_namespace, temporal_workflow_id, temporal_run_id,
            temporal_build_or_deployment, temporal_history_event_id, committed_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcHearingAuthorityLedger(
            NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public HearingDomainReceipt commitOrReplay(
            HearingAuthorityCommit command, FormalCommitAction formalCommitAction) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(formalCommitAction, "formalCommitAction");
        HearingDomainReceipt receipt = transactions.execute(ignored -> {
            lockSemanticOperation(command);
            Optional<HearingDomainReceipt> replay = findCommitted(command);
            if (replay.isPresent()) {
                HearingDomainReceipt committed = replay.orElseThrow();
                committed.requireReplayOf(command);
                return committed;
            }

            AuthorityRow authority = lockAuthority(command.authority());
            requireExactAuthority(command.authority(), authority);
            if (authority.writerMode() == HearingWriterMode.SHADOW) {
                throw rejected(
                        "HEARING_SHADOW_FORMAL_WRITE_FORBIDDEN",
                        "SHADOW authority cannot commit formal Hearing facts");
            }

            jdbc.getJdbcTemplate().execute("set local app.hearing_authority_commit = 'on'");
            HearingFormalCommitResult result = Objects.requireNonNull(
                    formalCommitAction.commit(), "formalCommitAction returned no result");
            requireLegalResult(command.authority(), result);
            requireFormalCursor(command.authority(), result);

            HearingDomainReceipt committed = HearingDomainReceipt.committed(
                    command,
                    result,
                    authority.temporalNamespace(),
                    authority.temporalWorkflowId(),
                    authority.temporalRunId(),
                    authority.temporalBuildOrDeployment());
            insertReceipt(committed);
            if (committed.operationType() == HearingAuthorityCommit.OperationType.CLOSE) {
                registerCloseCompletionGuard(committed);
            } else {
                advanceCaseProjection(committed);
                advanceEpoch(committed);
                acknowledgeProjection(committed);
            }
            return committed;
        });
        return Objects.requireNonNull(receipt, "Hearing receipt transaction returned no result");
    }

    @Override
    public HearingDomainReceipt completeCloseTransition(
            HearingAuthorityCommit command, CloseTransitionAction closeTransitionAction) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(closeTransitionAction, "closeTransitionAction");
        if (command.operationType() != HearingAuthorityCommit.OperationType.CLOSE) {
            throw new IllegalArgumentException("only a CLOSE receipt can complete a room transition");
        }
        HearingDomainReceipt receipt = transactions.execute(ignored -> {
            lockSemanticOperation(command);
            HearingDomainReceipt committed = findCommitted(command)
                    .orElseThrow(() -> rejected(
                            "HEARING_CLOSE_RECEIPT_NOT_COMMITTED",
                            "the close room transition has no durable receipt"));
            committed.requireReplayOf(command);
            if (isCloseTransitionComplete(committed)) {
                return committed;
            }
            requirePendingCloseTransition(committed);
            closeTransitionAction.transition(committed);
            requireSuccessorRoomAuthority(committed);
            acknowledgeProjection(committed);
            requireCompletedCloseTransition(committed);
            return committed;
        });
        return Objects.requireNonNull(receipt, "Hearing close transition returned no receipt");
    }

    @Override
    public Optional<HearingDomainReceipt> findCommitted(
            String tenantSurrogate, String operationKey) {
        HearingAuthorityExpectation.identifier(tenantSurrogate, "tenantSurrogate");
        if (operationKey == null || operationKey.isBlank() || operationKey.length() > 512) {
            throw new IllegalArgumentException("operationKey must be bounded non-blank text");
        }
        return exactlyOneOrEmpty(
                jdbc.query(
                        "select %s from hearing_domain_receipt where tenant_surrogate = :tenant and operation_key = :operationKey"
                                .formatted(RECEIPT_COLUMNS),
                        Map.of("tenant", tenantSurrogate, "operationKey", operationKey),
                        JdbcHearingAuthorityLedger::mapReceipt),
                "multiple Hearing receipts share one semantic operation");
    }

    private Optional<HearingDomainReceipt> findCommitted(HearingAuthorityCommit command) {
        return findCommitted(command.authority().tenantSurrogate(), command.operationKey());
    }

    private void lockSemanticOperation(HearingAuthorityCommit command) {
        jdbc.query(
                "select pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
                Map.of(
                        "lockKey",
                        command.authority().tenantSurrogate() + ":" + command.operationKey()),
                resultSet -> {
                    if (!resultSet.next()) {
                        throw rejected(
                                "HEARING_OPERATION_LOCK_FAILED",
                                "database did not acquire the Hearing operation lock");
                    }
                    return null;
                });
    }

    private AuthorityRow lockAuthority(HearingAuthorityExpectation expected) {
        List<AuthorityRow> rows = jdbc.query(
                """
                select projection.tenant_surrogate,
                       projection.case_id,
                       projection.flow_instance_id,
                       projection.epoch_id,
                       projection.hearing_epoch,
                       projection.writer_mode,
                       projection.current_stage,
                       projection.stage_sequence,
                       projection.process_revision,
                       projection.room_revision,
                       projection.fencing_token,
                       projection.temporal_namespace,
                       projection.temporal_workflow_id,
                       projection.temporal_run_id,
                       projection.temporal_build_or_deployment,
                       epoch.lifecycle_status,
                       process.current_room,
                       process.writer_mode as process_writer_mode,
                       process.room_epoch as process_room_epoch,
                       process.process_revision as case_process_revision,
                       process.fencing_token as process_fencing_token
                  from hearing_temporal_projection projection
                  join case_room_epoch epoch
                    on epoch.id = projection.epoch_id
                   and epoch.tenant_surrogate = projection.tenant_surrogate
                   and epoch.case_id = projection.case_id
                   and epoch.room_type = 'HEARING'
                   and epoch.room_epoch = projection.hearing_epoch
                   and epoch.fencing_token = projection.fencing_token
                  join case_process_projection process
                    on process.case_id = projection.case_id
                  join hearing_flow_instance flow
                    on flow.id = projection.flow_instance_id
                   and flow.case_id = projection.case_id
                 where projection.tenant_surrogate = :tenant
                   and projection.case_id = :caseId
                   and projection.flow_instance_id = :flowInstanceId
                   and projection.epoch_id = :epochId
                 for update of projection, epoch, process, flow
                """,
                new MapSqlParameterSource()
                        .addValue("tenant", expected.tenantSurrogate())
                        .addValue("caseId", expected.caseId())
                        .addValue("flowInstanceId", expected.flowInstanceId())
                        .addValue("epochId", expected.epochId()),
                (row, ignored) -> new AuthorityRow(
                        row.getString("tenant_surrogate"),
                        row.getString("case_id"),
                        row.getString("flow_instance_id"),
                        row.getString("epoch_id"),
                        row.getLong("hearing_epoch"),
                        HearingWriterMode.valueOf(row.getString("writer_mode")),
                        HearingFlowStage.valueOf(row.getString("current_stage")),
                        row.getInt("stage_sequence"),
                        row.getLong("process_revision"),
                        row.getLong("room_revision"),
                        row.getLong("fencing_token"),
                        row.getString("temporal_namespace"),
                        row.getString("temporal_workflow_id"),
                        row.getString("temporal_run_id"),
                        row.getString("temporal_build_or_deployment"),
                        row.getString("lifecycle_status"),
                        row.getString("current_room"),
                        row.getString("process_writer_mode"),
                        row.getLong("process_room_epoch"),
                        row.getLong("case_process_revision"),
                        row.getLong("process_fencing_token")));
        return exactlyOneOrEmpty(rows, "multiple authority rows match one Hearing flow")
                .orElseThrow(() -> rejected(
                        "HEARING_AUTHORITY_NOT_FOUND",
                        "no exact Hearing projection and epoch authority exists"));
    }

    private static void requireExactAuthority(
            HearingAuthorityExpectation expected, AuthorityRow actual) {
        boolean exact = expected.tenantSurrogate().equals(actual.tenantSurrogate())
                && expected.caseId().equals(actual.caseId())
                && expected.flowInstanceId().equals(actual.flowInstanceId())
                && expected.epochId().equals(actual.epochId())
                && expected.roomEpoch() == actual.roomEpoch()
                && expected.writerMode() == actual.writerMode()
                && expected.stage() == actual.stage()
                && expected.stageSequence() == actual.stageSequence()
                && expected.processRevision() == actual.processRevision()
                && expected.roomRevision() == actual.roomRevision()
                && expected.fencingToken() == actual.fencingToken()
                && "ACTIVE".equals(actual.lifecycleStatus())
                && "HEARING".equals(actual.currentRoom())
                && expected.writerMode().name().equals(actual.processWriterMode())
                && expected.roomEpoch() == actual.processRoomEpoch()
                && expected.processRevision() == actual.caseProcessRevision()
                && expected.fencingToken() == actual.processFencingToken();
        if (!exact) {
            throw rejected(
                    "HEARING_STALE_AUTHORITY",
                    "expected epoch, stage, revision, or fence is no longer current");
        }
    }

    private static void requireLegalResult(
            HearingAuthorityExpectation authority, HearingFormalCommitResult result) {
        boolean same = result.stage() == authority.stage()
                && result.stageSequence() == authority.stageSequence();
        boolean adjacent = result.stage().ordinal() == authority.stage().ordinal() + 1
                && result.stageSequence() == authority.stageSequence() + 1;
        if (!same && !adjacent) {
            throw rejected(
                    "HEARING_ILLEGAL_STAGE_COMMIT",
                    "formal mutation did not preserve or advance to the adjacent Hearing stage");
        }
    }

    private void requireFormalCursor(
            HearingAuthorityExpectation authority, HearingFormalCommitResult result) {
        Integer matches = jdbc.queryForObject(
                """
                select count(*)
                  from hearing_flow_instance
                 where id = :flowInstanceId
                   and case_id = :caseId
                   and current_stage = :stage
                   and stage_sequence = :stageSequence
                   and shared_deadline_at is not distinct from :stageDeadlineAt
                """,
                new MapSqlParameterSource()
                        .addValue("flowInstanceId", authority.flowInstanceId())
                        .addValue("caseId", authority.caseId())
                        .addValue("stage", result.stage().name())
                        .addValue("stageSequence", result.stageSequence())
                        .addValue("stageDeadlineAt", offset(result.sharedDeadlineAt())),
                Integer.class);
        if (!Integer.valueOf(1).equals(matches)) {
            throw rejected(
                    "HEARING_FORMAL_RESULT_NOT_COMMITTED",
                    "formal mutation result is not visible in the Java V035 cursor");
        }
    }

    private void insertReceipt(HearingDomainReceipt receipt) {
        int inserted = jdbc.update(
                """
                insert into hearing_domain_receipt (
                    schema_version, receipt_id, receipt_hash, operation_type,
                    operation_key, request_hash, tenant_surrogate, case_id,
                    flow_instance_id, epoch_id, room_type, hearing_epoch,
                    writer_mode, fencing_token, source_stage, source_stage_sequence,
                    source_process_revision, source_room_revision, stage_code,
                    stage_sequence, stage_deadline_at, process_revision, room_revision,
                    result_ref, result_hash, committed_event_sequence,
                    temporal_namespace, temporal_workflow_id, temporal_run_id,
                    temporal_build_or_deployment, temporal_history_event_id, committed_at
                ) values (
                    :schemaVersion, :receiptId, :receiptHash, :operationType,
                    :operationKey, :requestHash, :tenant, :caseId,
                    :flowInstanceId, :epochId, 'HEARING', :roomEpoch,
                    :writerMode, :fencingToken, :sourceStage, :sourceStageSequence,
                    :sourceProcessRevision, :sourceRoomRevision, :stage,
                    :stageSequence, :stageDeadlineAt, :processRevision, :roomRevision,
                    :resultRef, :resultHash, :committedEventSequence,
                    :temporalNamespace, :temporalWorkflowId, :temporalRunId,
                    :temporalBuildOrDeployment, :temporalHistoryEventId, :committedAt
                )
                """,
                parameters(receipt));
        requireUpdated(inserted, "HEARING_RECEIPT_INSERT_FAILED");
    }

    private void advanceCaseProjection(HearingDomainReceipt receipt) {
        int updated = jdbc.update(
                """
                update case_process_projection
                   set process_revision = :processRevision,
                       updated_at = greatest(updated_at, :committedAt),
                       version = version + 1
                 where case_id = :caseId
                   and current_room = 'HEARING'
                   and writer_mode = :writerMode
                   and room_epoch = :roomEpoch
                   and process_revision = :sourceProcessRevision
                   and fencing_token = :fencingToken
                """,
                parameters(receipt));
        requireUpdated(updated, "HEARING_CASE_PROCESS_CAS_FAILED");
    }

    private void advanceEpoch(HearingDomainReceipt receipt) {
        int updated = jdbc.update(
                """
                update case_room_epoch
                   set process_revision = :processRevision,
                       room_revision = :roomRevision,
                       updated_at = greatest(updated_at, :committedAt),
                       version = version + 1
                 where id = :epochId
                   and tenant_surrogate = :tenant
                   and case_id = :caseId
                   and room_type = 'HEARING'
                   and room_epoch = :roomEpoch
                   and writer_mode = :writerMode
                   and lifecycle_status = 'ACTIVE'
                   and process_revision = :sourceProcessRevision
                   and room_revision = :sourceRoomRevision
                   and fencing_token = :fencingToken
                """,
                parameters(receipt));
        requireUpdated(updated, "HEARING_EPOCH_CAS_FAILED");
    }

    private void acknowledgeProjection(HearingDomainReceipt receipt) {
        int updated = jdbc.update(
                """
                update hearing_temporal_projection
                   set current_stage = :stage,
                       stage_sequence = :stageSequence,
                       stage_deadline_at = :stageDeadlineAt,
                       process_revision = :processRevision,
                       room_revision = :roomRevision,
                       last_acknowledged_receipt_id = :receiptId,
                       last_acknowledged_receipt_hash = :receiptHash,
                       last_acknowledged_history_event_id = :temporalHistoryEventId,
                       updated_at = greatest(updated_at, :committedAt)
                 where flow_instance_id = :flowInstanceId
                   and case_id = :caseId
                   and epoch_id = :epochId
                   and tenant_surrogate = :tenant
                   and hearing_epoch = :roomEpoch
                   and writer_mode = :writerMode
                   and (
                        (current_stage = :sourceStage
                            and stage_sequence = :sourceStageSequence)
                        or
                        (writer_mode = 'LEGACY'
                            and current_stage = :stage
                            and stage_sequence = :stageSequence)
                   )
                   and process_revision = :sourceProcessRevision
                   and room_revision = :sourceRoomRevision
                   and fencing_token = :fencingToken
                """,
                parameters(receipt));
        requireUpdated(updated, "HEARING_PROJECTION_CAS_FAILED");
    }

    private void registerCloseCompletionGuard(HearingDomainReceipt receipt) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw rejected(
                    "HEARING_CLOSE_TRANSACTION_REQUIRED",
                    "a close receipt requires an atomic room-transition transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                requireCompletedCloseTransition(receipt);
            }
        });
    }

    private void requirePendingCloseTransition(HearingDomainReceipt receipt) {
        Integer matches = jdbc.queryForObject(
                """
                select count(*)
                  from hearing_temporal_projection projection
                  join hearing_flow_instance flow
                    on flow.id = projection.flow_instance_id
                   and flow.case_id = projection.case_id
                  join case_room_epoch epoch
                    on epoch.id = projection.epoch_id
                   and epoch.tenant_surrogate = projection.tenant_surrogate
                   and epoch.case_id = projection.case_id
                   and epoch.room_type = 'HEARING'
                   and epoch.room_epoch = projection.hearing_epoch
                   and epoch.fencing_token = projection.fencing_token
                  join case_process_projection process
                    on process.case_id = projection.case_id
                 where projection.tenant_surrogate = :tenant
                   and projection.case_id = :caseId
                   and projection.flow_instance_id = :flowInstanceId
                   and projection.epoch_id = :epochId
                   and (
                       (projection.current_stage = :sourceStage
                           and projection.stage_sequence = :sourceStageSequence)
                       or
                       (projection.writer_mode = 'LEGACY'
                           and projection.current_stage = :stage
                           and projection.stage_sequence = :stageSequence)
                   )
                   and projection.process_revision = :sourceProcessRevision
                   and projection.room_revision = :sourceRoomRevision
                   and projection.fencing_token = :fencingToken
                   and flow.current_stage = :stage
                   and flow.stage_sequence = :stageSequence
                   and flow.flow_status = 'CLOSED'
                   and epoch.lifecycle_status = 'ACTIVE'
                   and epoch.process_revision = :sourceProcessRevision
                   and epoch.room_revision = :sourceRoomRevision
                   and process.current_room = 'HEARING'
                   and process.process_revision = :sourceProcessRevision
                   and process.room_epoch = :roomEpoch
                   and process.fencing_token = :fencingToken
                """,
                parameters(receipt),
                Integer.class);
        if (!Integer.valueOf(1).equals(matches)) {
            throw rejected(
                    "HEARING_CLOSE_SOURCE_AUTHORITY_NOT_EXACT",
                    "the close receipt no longer owns the exact source room authority");
        }
    }

    private void requireSuccessorRoomAuthority(HearingDomainReceipt receipt) {
        Integer matches = jdbc.queryForObject(
                """
                select count(*)
                  from case_room_epoch source
                  join case_process_projection process
                    on process.case_id = source.case_id
                 where source.id = :epochId
                   and source.tenant_surrogate = :tenant
                   and source.case_id = :caseId
                   and source.room_type = 'HEARING'
                   and source.room_epoch = :roomEpoch
                   and source.fencing_token = :fencingToken
                   and source.lifecycle_status = 'TERMINAL'
                   and source.process_revision = :processRevision
                   and source.room_revision = :roomRevision
                   and process.current_room = 'REVIEW'
                   and process.process_revision = :processRevision
                   and (
                       (source.writer_mode = 'LEGACY'
                           and process.writer_mode = 'LEGACY'
                           and process.writer_activation_status = 'READY')
                       or
                       (source.writer_mode = 'TEMPORAL'
                           and process.writer_mode = 'TEMPORAL'
                           and process.writer_activation_status = 'PREPARING')
                   )
                   and exists (
                       select 1
                         from case_room_epoch successor
                        where successor.case_id = source.case_id
                          and successor.tenant_surrogate = source.tenant_surrogate
                          and successor.room_type = 'REVIEW'
                          and successor.process_revision = :processRevision
                          and successor.fencing_token = process.fencing_token
                          and (
                              (source.writer_mode = 'LEGACY'
                                  and successor.writer_mode = 'LEGACY'
                                  and successor.lifecycle_status = 'ACTIVE'
                                  and successor.provisioning_status = 'NOT_REQUIRED')
                              or
                              (source.writer_mode = 'TEMPORAL'
                                  and successor.writer_mode = 'TEMPORAL'
                                  and successor.lifecycle_status = 'PREPARING'
                                  and successor.provisioning_status = 'PENDING')
                          )
                   )
                """,
                parameters(receipt),
                Integer.class);
        if (!Integer.valueOf(1).equals(matches)) {
            throw rejected(
                    "HEARING_CLOSE_SUCCESSOR_AUTHORITY_NOT_EXACT",
                    "the close receipt did not create the exact Review room authority");
        }
    }

    private boolean isCloseTransitionComplete(HearingDomainReceipt receipt) {
        Integer matches = completedCloseTransitionCount(receipt);
        if (matches == null || matches < 0 || matches > 1) {
            throw rejected(
                    "HEARING_DURABLE_INTEGRITY_VIOLATION",
                    "the close receipt has ambiguous room-transition authority");
        }
        return matches == 1;
    }

    private void requireCompletedCloseTransition(HearingDomainReceipt receipt) {
        if (!isCloseTransitionComplete(receipt)) {
            throw rejected(
                    "HEARING_CLOSE_TRANSITION_INCOMPLETE",
                    "the close receipt and Review room transition must commit atomically");
        }
    }

    private Integer completedCloseTransitionCount(HearingDomainReceipt receipt) {
        return jdbc.queryForObject(
                """
                select count(*)
                  from hearing_temporal_projection projection
                  join case_room_epoch source
                    on source.id = projection.epoch_id
                   and source.tenant_surrogate = projection.tenant_surrogate
                   and source.case_id = projection.case_id
                   and source.room_type = 'HEARING'
                   and source.room_epoch = projection.hearing_epoch
                   and source.fencing_token = projection.fencing_token
                  join case_process_projection process
                    on process.case_id = projection.case_id
                 where projection.tenant_surrogate = :tenant
                   and projection.case_id = :caseId
                   and projection.flow_instance_id = :flowInstanceId
                   and projection.epoch_id = :epochId
                   and projection.current_stage = :stage
                   and projection.stage_sequence = :stageSequence
                   and projection.process_revision = :processRevision
                   and projection.room_revision = :roomRevision
                   and projection.last_acknowledged_receipt_id = :receiptId
                   and projection.last_acknowledged_receipt_hash = :receiptHash
                   and source.lifecycle_status = 'TERMINAL'
                   and source.process_revision = :processRevision
                   and source.room_revision = :roomRevision
                   and process.current_room = 'REVIEW'
                   and process.process_revision = :processRevision
                   and exists (
                       select 1
                         from case_room_epoch successor
                        where successor.case_id = source.case_id
                          and successor.tenant_surrogate = source.tenant_surrogate
                          and successor.room_type = 'REVIEW'
                          and successor.process_revision = :processRevision
                          and successor.fencing_token = process.fencing_token
                          and (
                              (source.writer_mode = 'LEGACY'
                                  and process.writer_mode = 'LEGACY'
                                  and process.writer_activation_status = 'READY'
                                  and successor.writer_mode = 'LEGACY'
                                  and successor.lifecycle_status = 'ACTIVE'
                                  and successor.provisioning_status = 'NOT_REQUIRED')
                              or
                              (source.writer_mode = 'TEMPORAL'
                                  and process.writer_mode = 'TEMPORAL'
                                  and successor.writer_mode = 'TEMPORAL'
                                  and (
                                      (process.writer_activation_status = 'PREPARING'
                                          and successor.lifecycle_status = 'PREPARING'
                                          and successor.provisioning_status = 'PENDING')
                                      or
                                      (process.writer_activation_status = 'PROVISIONING'
                                          and successor.lifecycle_status = 'PROVISIONING'
                                          and successor.provisioning_status = 'PROVISIONING')
                                      or
                                      (process.writer_activation_status = 'READY'
                                          and successor.lifecycle_status = 'ACTIVE'
                                          and successor.provisioning_status = 'READY')
                                  )
                              )
                          )
                   )
                """,
                parameters(receipt),
                Integer.class);
    }

    private static MapSqlParameterSource parameters(HearingDomainReceipt receipt) {
        return new MapSqlParameterSource()
                .addValue("schemaVersion", receipt.schemaVersion())
                .addValue("receiptId", receipt.receiptId())
                .addValue("receiptHash", receipt.receiptHash())
                .addValue("operationType", receipt.operationType().name())
                .addValue("operationKey", receipt.operationKey())
                .addValue("requestHash", receipt.requestHash())
                .addValue("tenant", receipt.tenantSurrogate())
                .addValue("caseId", receipt.caseId())
                .addValue("flowInstanceId", receipt.flowInstanceId())
                .addValue("epochId", receipt.epochId())
                .addValue("roomEpoch", receipt.roomEpoch())
                .addValue("writerMode", receipt.writerMode().name())
                .addValue("fencingToken", receipt.fencingToken())
                .addValue("sourceStage", receipt.sourceStage().name())
                .addValue("sourceStageSequence", receipt.sourceStageSequence())
                .addValue("sourceProcessRevision", receipt.sourceProcessRevision())
                .addValue("sourceRoomRevision", receipt.sourceRoomRevision())
                .addValue("stage", receipt.stage().name())
                .addValue("stageSequence", receipt.stageSequence())
                .addValue("stageDeadlineAt", offset(receipt.sharedDeadlineAt()))
                .addValue("processRevision", receipt.processRevision())
                .addValue("roomRevision", receipt.roomRevision())
                .addValue("resultRef", receipt.resultRef())
                .addValue("resultHash", receipt.resultHash())
                .addValue("committedEventSequence", receipt.committedEventSequence())
                .addValue("temporalNamespace", receipt.temporalNamespace())
                .addValue("temporalWorkflowId", receipt.temporalWorkflowId())
                .addValue("temporalRunId", receipt.temporalRunId())
                .addValue("temporalBuildOrDeployment", receipt.temporalBuildOrDeployment())
                .addValue("temporalHistoryEventId", receipt.temporalHistoryEventId())
                .addValue("committedAt", offset(receipt.committedAt()));
    }

    private static HearingDomainReceipt mapReceipt(ResultSet row, int ignored) throws SQLException {
        try {
            return new HearingDomainReceipt(
                    row.getString("schema_version"),
                    row.getString("receipt_id"),
                    row.getString("receipt_hash"),
                    HearingAuthorityCommit.OperationType.valueOf(row.getString("operation_type")),
                    row.getString("operation_key"),
                    row.getString("request_hash"),
                    row.getString("tenant_surrogate"),
                    row.getString("case_id"),
                    row.getString("flow_instance_id"),
                    row.getString("epoch_id"),
                    row.getLong("hearing_epoch"),
                    HearingWriterMode.valueOf(row.getString("writer_mode")),
                    row.getLong("fencing_token"),
                    HearingFlowStage.valueOf(row.getString("source_stage")),
                    row.getInt("source_stage_sequence"),
                    row.getLong("source_process_revision"),
                    row.getLong("source_room_revision"),
                    HearingFlowStage.valueOf(row.getString("stage_code")),
                    row.getInt("stage_sequence"),
                    instant(row, "stage_deadline_at"),
                    row.getLong("process_revision"),
                    row.getLong("room_revision"),
                    row.getString("result_ref"),
                    row.getString("result_hash"),
                    row.getLong("committed_event_sequence"),
                    row.getString("temporal_namespace"),
                    row.getString("temporal_workflow_id"),
                    row.getString("temporal_run_id"),
                    row.getString("temporal_build_or_deployment"),
                    nullableLong(row, "temporal_history_event_id"),
                    Objects.requireNonNull(instant(row, "committed_at"), "committed_at"));
        } catch (RuntimeException failure) {
            throw new HearingAuthorityRejectedException(
                    "HEARING_RECEIPT_CORRUPT",
                    "persisted Hearing receipt is not canonical",
                    failure);
        }
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static OffsetDateTime offset(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static void requireUpdated(int count, String code) {
        if (count != 1) {
            throw rejected(code, "fenced Hearing database mutation did not affect exactly one row");
        }
    }

    private static <T> Optional<T> exactlyOneOrEmpty(List<T> values, String message) {
        if (values.size() > 1) {
            throw rejected("HEARING_DURABLE_INTEGRITY_VIOLATION", message);
        }
        return values.stream().findFirst();
    }

    private static HearingAuthorityRejectedException rejected(String code, String message) {
        return new HearingAuthorityRejectedException(code, message);
    }

    private record AuthorityRow(
            String tenantSurrogate,
            String caseId,
            String flowInstanceId,
            String epochId,
            long roomEpoch,
            HearingWriterMode writerMode,
            HearingFlowStage stage,
            int stageSequence,
            long processRevision,
            long roomRevision,
            long fencingToken,
            String temporalNamespace,
            String temporalWorkflowId,
            String temporalRunId,
            String temporalBuildOrDeployment,
            String lifecycleStatus,
            String currentRoom,
            String processWriterMode,
            long processRoomEpoch,
            long caseProcessRevision,
            long processFencingToken) {}
}
