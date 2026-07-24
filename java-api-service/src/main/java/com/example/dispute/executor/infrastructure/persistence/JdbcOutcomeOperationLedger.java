package com.example.dispute.executor.infrastructure.persistence;

import com.example.dispute.executor.domain.ledger.OutcomeAttemptObservation;
import com.example.dispute.executor.domain.ledger.OutcomeClosureReadiness;
import com.example.dispute.executor.domain.ledger.OutcomeCompensationParent;
import com.example.dispute.executor.domain.ledger.OutcomeLedgerRejectedException;
import com.example.dispute.executor.domain.ledger.OutcomeOperation;
import com.example.dispute.executor.domain.ledger.OutcomeOperationLedger;
import com.example.dispute.executor.domain.ledger.OutcomeOperationReceipt;
import com.example.dispute.executor.domain.ledger.OutcomeOperationState;
import com.example.dispute.executor.domain.ledger.OutcomeProcessProjection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL Outcome ledger foundation. Deliberately not registered as a Bean while Phase 7 is
 * engineering-only and the formal Outcome selector remains LEGACY.
 */
public final class JdbcOutcomeOperationLedger implements OutcomeOperationLedger {

    private static final String OPERATION_LIFECYCLE_LOCK_PREFIX =
            "outcome-operation-lifecycle:";
    private static final String COMPENSATION_SCOPE_LOCK_PREFIX =
            "outcome-compensation-order:";

    // Existing lifecycle lock order: scope -> semantic/lifecycle -> projection -> operation -> fact.
    // A transaction may skip ranks, but must never acquire an earlier rank after a later one.

    private static final String PROJECTION_COLUMNS = """
            projection_id, tenant_surrogate, case_id, epoch_id, outcome_epoch,
            writer_mode, runtime_mode, fencing_token, process_revision,
            outcome_revision, decision_authority_receipt_id, decision_request_hash,
            approved_operation_set_hash, expected_required_operation_count,
            process_state, projected_at, updated_at
            """;
    private static final String OPERATION_COLUMNS = """
            operation_id, projection_id, tenant_surrogate, case_id, outcome_epoch,
            fencing_token, process_revision, outcome_revision, operation_kind,
            operation_sequence, operation_key, request_hash, review_packet_id, review_packet_version,
            review_packet_hash, review_packet_action_hash, approval_record_id, approval_hash, action_record_id,
            decision_request_hash, decision_policy_version, action_snapshot_hash,
            adapter_id, adapter_version, retry_class,
            external_idempotency_key, required_for_closure, compensable, reserved_at
            """;
    private static final String ATTEMPT_COLUMNS = """
            observation_id, observation_hash, operation_id, tenant_surrogate, case_id,
            outcome_epoch, fencing_token, request_hash, attempt_sequence,
            observation_type, external_invocation_id, observation_ref,
            observation_payload_hash, effect_may_have_occurred, retry_permitted, observed_at
            """;
    private static final String RECEIPT_COLUMNS = """
            receipt_id, receipt_hash, operation_id, tenant_surrogate, case_id,
            outcome_epoch, fencing_token, request_hash, receipt_status,
            receipt_authority, external_receipt_id, response_ref, response_hash,
            closure_disposition, completed_at
            """;
    private static final String COMPENSATION_COLUMNS = """
            binding_id, binding_hash, child_operation_id, parent_operation_id,
            parent_receipt_id, parent_receipt_hash, compensation_policy_version,
            reverse_order, tenant_surrogate, case_id,
            outcome_epoch, fencing_token, created_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcOutcomeOperationLedger(
            NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public OutcomeProcessProjection createProjection(OutcomeProcessProjection projection) {
        Objects.requireNonNull(projection, "projection");
        OutcomeProcessProjection result = transactions.execute(ignored -> {
            lockSemantic("projection:" + projection.tenantSurrogate() + ':'
                    + projection.caseId() + ':' + projection.outcomeEpoch());
            Optional<OutcomeProcessProjection> existing = findProjection(
                    projection.tenantSurrogate(), projection.caseId(), projection.outcomeEpoch());
            if (existing.isPresent()) {
                OutcomeProcessProjection committed = existing.orElseThrow();
                if (!committed.equals(projection)) {
                    throw rejected(
                            "OUTCOME_PROJECTION_CONFLICT",
                            "Outcome epoch already has a different immutable projection binding");
                }
                return committed;
            }
            int inserted = jdbc.update(
                    """
                    insert into outcome_process_projection (
                        projection_id, schema_version, tenant_surrogate, case_id, epoch_id,
                        room_type, outcome_epoch, writer_mode, runtime_mode, fencing_token,
                        process_revision, outcome_revision, decision_authority_receipt_id,
                        decision_request_hash, approved_operation_set_hash,
                        expected_required_operation_count, process_state, projected_at, updated_at
                    ) values (
                        :projectionId, :schemaVersion, :tenantSurrogate, :caseId, :epochId,
                        'REVIEW', :outcomeEpoch, :writerMode, :runtimeMode, :fencingToken,
                        :processRevision, :outcomeRevision, :decisionAuthorityReceiptId,
                        :decisionRequestHash, :approvedOperationSetHash,
                        :expectedRequiredOperationCount, :processState, :projectedAt, :updatedAt
                    )
                    """,
                    projectionParameters(projection));
            requireSingleInsert(inserted, "OUTCOME_PROJECTION_INSERT_FAILED");
            return projection;
        });
        return Objects.requireNonNull(result, "projection transaction returned no result");
    }

    @Override
    public OutcomeProcessProjection advanceProjection(
            ProjectionExpectation expectation,
            OutcomeProcessProjection.ProcessState nextState,
            Instant advancedAt) {
        Objects.requireNonNull(expectation, "expectation");
        Objects.requireNonNull(nextState, "nextState");
        Objects.requireNonNull(advancedAt, "advancedAt");
        OutcomeProcessProjection result = transactions.execute(ignored -> {
            OutcomeProcessProjection current = lockProjection(expectation);
            MapSqlParameterSource parameters = expectationParameters(expectation)
                    .addValue("nextProcessRevision", expectation.processRevision() + 1)
                    .addValue("nextOutcomeRevision", expectation.outcomeRevision() + 1)
                    .addValue("nextState", nextState.name())
                    .addValue("advancedAt", timestamp(advancedAt));
            int epochUpdated = jdbc.update(
                    """
                    update case_room_epoch
                       set process_revision = :nextProcessRevision,
                           room_revision = :nextOutcomeRevision,
                           updated_at = :advancedAt,
                           version = version + 1
                     where id = :epochId
                       and tenant_surrogate = :tenantSurrogate
                       and case_id = :caseId
                       and room_type = 'REVIEW'
                       and room_epoch = :outcomeEpoch
                       and fencing_token = :fencingToken
                       and process_revision = :processRevision
                       and room_revision = :outcomeRevision
                    """,
                    parameters.addValue("epochId", current.epochId()));
            if (epochUpdated != 1) {
                throw rejected("OUTCOME_STALE_AUTHORITY", "case_room_epoch revision fence rejected");
            }
            int projectionUpdated = jdbc.update(
                    """
                    update outcome_process_projection
                       set process_revision = :nextProcessRevision,
                           outcome_revision = :nextOutcomeRevision,
                           process_state = :nextState,
                           updated_at = :advancedAt
                     where projection_id = :projectionId
                       and tenant_surrogate = :tenantSurrogate
                       and case_id = :caseId
                       and outcome_epoch = :outcomeEpoch
                       and fencing_token = :fencingToken
                       and process_revision = :processRevision
                       and outcome_revision = :outcomeRevision
                    """,
                    parameters);
            if (projectionUpdated != 1) {
                throw rejected("OUTCOME_STALE_PROJECTION", "Outcome projection revision fence rejected");
            }
            return new OutcomeProcessProjection(
                    current.projectionId(),
                    current.tenantSurrogate(),
                    current.caseId(),
                    current.epochId(),
                    current.outcomeEpoch(),
                    current.writerMode(),
                    current.runtimeMode(),
                    current.fencingToken(),
                    expectation.processRevision() + 1,
                    expectation.outcomeRevision() + 1,
                    current.decisionAuthorityReceiptId(),
                    current.decisionRequestHash(),
                    current.approvedOperationSetHash(),
                    current.expectedRequiredOperationCount(),
                    nextState,
                    current.projectedAt(),
                    advancedAt);
        });
        return Objects.requireNonNull(result, "projection transaction returned no result");
    }

    @Override
    public OutcomeOperation reserve(
            OutcomeOperation operation, OutcomeCompensationParent compensationParent) {
        Objects.requireNonNull(operation, "operation");
        requireCompensationShape(operation, compensationParent);
        OutcomeOperation result = transactions.execute(ignored -> {
            lockSemantic(compensationOrderKey(operation));
            lockSemantic("operation:" + operation.tenantSurrogate() + ':'
                    + operation.caseId() + ':' + operation.outcomeEpoch() + ':'
                    + operation.operationKey());
            Optional<OutcomeOperation> replay = findOperation(lookup(operation));
            if (replay.isPresent()) {
                OutcomeOperation committed = replay.orElseThrow();
                committed.requireExactReplay(operation);
                requireExactCompensationReplay(committed, compensationParent);
                return committed;
            }
            OutcomeProcessProjection projection = lockProjection(expectation(operation));
            if (compensationParent != null) {
                requireNextCompensationParent(projection, compensationParent);
            }
            int inserted = jdbc.update(
                    """
                    insert into outcome_operation (
                        operation_id, schema_version, projection_id, tenant_surrogate, case_id,
                        outcome_epoch, fencing_token, process_revision, outcome_revision,
                        operation_kind, operation_sequence, operation_key, request_hash, review_packet_id,
                        review_packet_version, review_packet_hash, review_packet_action_hash,
                        approval_record_id,
                        approval_hash, decision_request_hash, decision_policy_version,
                        action_record_id, action_snapshot_hash, adapter_id,
                        adapter_version, retry_class, external_idempotency_key,
                        required_for_closure, compensable, reserved_at
                    ) values (
                        :operationId, :schemaVersion, :projectionId, :tenantSurrogate, :caseId,
                        :outcomeEpoch, :fencingToken, :processRevision, :outcomeRevision,
                        :operationKind, :operationSequence, :operationKey, :requestHash, :reviewPacketId,
                        :reviewPacketVersion, :reviewPacketHash, :reviewPacketActionHash,
                        :approvalRecordId,
                        :approvalHash, :decisionRequestHash, :decisionPolicyVersion,
                        :actionRecordId, :actionSnapshotHash, :adapterId,
                        :adapterVersion, :retryClass, :externalIdempotencyKey,
                        :requiredForClosure, :compensable, :reservedAt
                    )
                    """,
                    operationParameters(operation));
            requireSingleInsert(inserted, "OUTCOME_OPERATION_INSERT_FAILED");
            if (compensationParent != null) {
                insertCompensationParent(compensationParent);
            }
            return operation;
        });
        return Objects.requireNonNull(result, "operation transaction returned no result");
    }

    @Override
    public OutcomeAttemptObservation appendAttempt(OutcomeAttemptObservation observation) {
        Objects.requireNonNull(observation, "observation");
        OutcomeAttemptObservation result = transactions.execute(ignored -> {
            lockSemantic(compensationOrderKey(observation));
            lockSemantic(operationLifecycleKey(observation.operationId()));
            Optional<OutcomeAttemptObservation> replay = findAttempt(observation.observationId());
            if (replay.isPresent()) {
                OutcomeAttemptObservation committed = replay.orElseThrow();
                committed.requireExactReplay(observation);
                return committed;
            }
            OutcomeOperation operation = lockOperation(observation.operationId());
            requireAttemptBinding(operation, observation);
            Optional<OutcomeAttemptObservation> latest = latestAttempt(operation.operationId());
            requireSafeAttemptTransition(latest, observation);
            if (findReceipt(operation.operationId()).isPresent()) {
                throw rejected(
                        "OUTCOME_OPERATION_ALREADY_TERMINAL",
                        "authoritative receipt forbids another attempt observation");
            }
            int inserted = jdbc.update(
                    """
                    insert into outcome_operation_attempt_observation (
                        observation_id, schema_version, observation_hash, operation_id,
                        tenant_surrogate, case_id, outcome_epoch, fencing_token, request_hash,
                        attempt_sequence, observation_type, external_invocation_id,
                        observation_ref, observation_payload_hash, effect_may_have_occurred,
                        retry_permitted, observed_at
                    ) values (
                        :observationId, :schemaVersion, :observationHash, :operationId,
                        :tenantSurrogate, :caseId, :outcomeEpoch, :fencingToken, :requestHash,
                        :attemptSequence, :observationType, :externalInvocationId,
                        :observationRef, :observationPayloadHash, :effectMayHaveOccurred,
                        :retryPermitted, :observedAt
                    )
                    """,
                    attemptParameters(observation));
            requireSingleInsert(inserted, "OUTCOME_ATTEMPT_INSERT_FAILED");
            return observation;
        });
        return Objects.requireNonNull(result, "attempt transaction returned no result");
    }

    @Override
    public OutcomeOperationReceipt recordReceipt(OutcomeOperationReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        OutcomeOperationReceipt result = transactions.execute(ignored -> {
            lockSemantic(compensationOrderKey(receipt));
            lockSemantic(operationLifecycleKey(receipt.operationId()));
            Optional<OutcomeOperationReceipt> replay = findReceipt(receipt.operationId());
            if (replay.isPresent()) {
                OutcomeOperationReceipt committed = replay.orElseThrow();
                committed.requireExactReplay(receipt);
                return committed;
            }
            OutcomeOperation operation = lockOperation(receipt.operationId());
            requireReceiptBinding(operation, receipt);
            rejectOriginalReceiptAfterCompensationStarted(operation);
            Optional<OutcomeAttemptObservation> latest = latestAttempt(operation.operationId());
            if (latest.isPresent()
                    && latest.orElseThrow().observationType()
                            == OutcomeAttemptObservation.ObservationType.AMBIGUOUS) {
                throw rejected(
                        "OUTCOME_AMBIGUOUS_RECONCILIATION_REQUIRED",
                        "AMBIGUOUS operation must enter RECONCILING before terminal receipt");
            }
            if (latest.isPresent()
                    && latest.orElseThrow().observationType()
                            == OutcomeAttemptObservation.ObservationType.RECONCILING
                    && receipt.receiptAuthority()
                            == OutcomeOperationReceipt.ReceiptAuthority.DIRECT_RESPONSE) {
                throw rejected(
                        "OUTCOME_RECONCILIATION_AUTHORITY_REQUIRED",
                        "RECONCILING operation requires callback, status query, or Java reconciliation");
            }
            int inserted = jdbc.update(
                    """
                    insert into outcome_operation_receipt (
                        receipt_id, schema_version, receipt_hash, operation_id,
                        tenant_surrogate, case_id, outcome_epoch, fencing_token, request_hash,
                        receipt_status, receipt_authority, external_receipt_id,
                        response_ref, response_hash, closure_disposition, completed_at
                    ) values (
                        :receiptId, :schemaVersion, :receiptHash, :operationId,
                        :tenantSurrogate, :caseId, :outcomeEpoch, :fencingToken, :requestHash,
                        :receiptStatus, :receiptAuthority, :externalReceiptId,
                        :responseRef, :responseHash, :closureDisposition, :completedAt
                    )
                    """,
                    receiptParameters(receipt));
            requireSingleInsert(inserted, "OUTCOME_RECEIPT_INSERT_FAILED");
            return receipt;
        });
        return Objects.requireNonNull(result, "receipt transaction returned no result");
    }

    @Override
    public Optional<OutcomeOperation> findOperation(OperationLookup lookup) {
        Objects.requireNonNull(lookup, "lookup");
        return exactlyOneOrEmpty(
                jdbc.query(
                        "select %s from outcome_operation where tenant_surrogate = :tenantSurrogate and case_id = :caseId and outcome_epoch = :outcomeEpoch and operation_key = :operationKey"
                                .formatted(OPERATION_COLUMNS),
                        Map.of(
                                "tenantSurrogate", lookup.tenantSurrogate(),
                                "caseId", lookup.caseId(),
                                "outcomeEpoch", lookup.outcomeEpoch(),
                                "operationKey", lookup.operationKey()),
                        JdbcOutcomeOperationLedger::mapOperation),
                "multiple Outcome operations share one semantic key");
    }

    @Override
    public Optional<OutcomeOperationReceipt> findReceipt(String operationId) {
        Objects.requireNonNull(operationId, "operationId");
        return exactlyOneOrEmpty(
                jdbc.query(
                        "select %s from outcome_operation_receipt where operation_id = :operationId"
                                .formatted(RECEIPT_COLUMNS),
                        Map.of("operationId", operationId),
                        JdbcOutcomeOperationLedger::mapReceipt),
                "multiple authoritative receipts share one Outcome operation");
    }

    @Override
    public List<OutcomeOperationState> readOperationStates(ProjectionExpectation expectation) {
        Objects.requireNonNull(expectation, "expectation");
        List<OutcomeOperationState> states = transactions.execute(ignored -> {
            lockProjection(expectation);
            return List.copyOf(jdbc.query(
                    """
                    select projection_id, operation_id, operation_kind, operation_sequence,
                           operation_key, request_hash, required_for_closure, compensable,
                           tenant_surrogate, case_id, outcome_epoch, fencing_token,
                           process_revision, outcome_revision, operation_status,
                           receipt_id, receipt_hash, java_authoritative,
                           parent_operation_id, parent_receipt_id, parent_receipt_hash,
                           compensation_policy_version, reverse_order
                      from outcome_operation_state
                     where projection_id = :projectionId
                       and tenant_surrogate = :tenantSurrogate
                       and case_id = :caseId
                       and outcome_epoch = :outcomeEpoch
                       and fencing_token = :fencingToken
                     order by operation_sequence, operation_id
                    """,
                    expectationParameters(expectation),
                    JdbcOutcomeOperationLedger::mapOperationState));
        });
        return Objects.requireNonNull(states, "operation state transaction returned no result");
    }

    @Override
    public List<OutcomeCompensationParent> findCompensationParents(
            ProjectionExpectation expectation) {
        Objects.requireNonNull(expectation, "expectation");
        List<OutcomeCompensationParent> parents = transactions.execute(ignored -> {
            lockProjection(expectation);
            return List.copyOf(jdbc.query(
                    """
                    select %s
                      from outcome_compensation_parent_binding
                     where tenant_surrogate = :tenantSurrogate
                       and case_id = :caseId
                       and outcome_epoch = :outcomeEpoch
                       and fencing_token = :fencingToken
                     order by reverse_order, binding_id
                    """.formatted(COMPENSATION_COLUMNS),
                    expectationParameters(expectation),
                    JdbcOutcomeOperationLedger::mapCompensation));
        });
        return Objects.requireNonNull(parents, "compensation read transaction returned no result");
    }

    @Override
    public OutcomeClosureReadiness closureReadiness(ProjectionExpectation expectation) {
        Objects.requireNonNull(expectation, "expectation");
        OutcomeClosureReadiness readiness = transactions.execute(ignored -> {
            lockProjection(expectation);
            List<OutcomeClosureReadiness> rows = jdbc.query(
                    """
                    select projection_id, tenant_surrogate, case_id, outcome_epoch, fencing_token,
                           expected_required_operation_count, required_operation_count,
                           unresolved_operation_count,
                           blocked_operation_count, reconciliation_operation_count,
                           pending_compensation_count, closure_ready
                      from outcome_closure_readiness
                     where projection_id = :projectionId
                       and tenant_surrogate = :tenantSurrogate
                       and case_id = :caseId
                       and outcome_epoch = :outcomeEpoch
                       and fencing_token = :fencingToken
                    """,
                    expectationParameters(expectation),
                    JdbcOutcomeOperationLedger::mapClosureReadiness);
            return exactlyOneOrEmpty(rows, "multiple closure projections match one Outcome epoch")
                    .orElseThrow(() -> rejected(
                            "OUTCOME_CLOSURE_PROJECTION_MISSING",
                            "closure prerequisites have no exact Outcome projection"));
        });
        return Objects.requireNonNull(readiness, "closure read transaction returned no result");
    }

    private Optional<OutcomeProcessProjection> findProjection(
            String tenantSurrogate, String caseId, long outcomeEpoch) {
        return exactlyOneOrEmpty(
                jdbc.query(
                        "select %s from outcome_process_projection where tenant_surrogate = :tenantSurrogate and case_id = :caseId and outcome_epoch = :outcomeEpoch"
                                .formatted(PROJECTION_COLUMNS),
                        Map.of(
                                "tenantSurrogate", tenantSurrogate,
                                "caseId", caseId,
                                "outcomeEpoch", outcomeEpoch),
                        JdbcOutcomeOperationLedger::mapProjection),
                "multiple Outcome projections share one case epoch");
    }

    private OutcomeProcessProjection lockProjection(ProjectionExpectation expectation) {
        List<OutcomeProcessProjection> rows = jdbc.query(
                "select %s from outcome_process_projection where projection_id = :projectionId and tenant_surrogate = :tenantSurrogate and case_id = :caseId and outcome_epoch = :outcomeEpoch and fencing_token = :fencingToken and process_revision = :processRevision and outcome_revision = :outcomeRevision for update"
                        .formatted(PROJECTION_COLUMNS),
                expectationParameters(expectation),
                JdbcOutcomeOperationLedger::mapProjection);
        return exactlyOneOrEmpty(rows, "multiple Outcome projections share one fenced identity")
                .orElseThrow(() -> rejected(
                        "OUTCOME_STALE_PROJECTION", "Outcome projection fence or revision is stale"));
    }

    private OutcomeOperation lockOperation(String operationId) {
        List<OutcomeOperation> rows = jdbc.query(
                "select %s from outcome_operation where operation_id = :operationId for update"
                        .formatted(OPERATION_COLUMNS),
                Map.of("operationId", operationId),
                JdbcOutcomeOperationLedger::mapOperation);
        return exactlyOneOrEmpty(rows, "multiple Outcome operations share one id")
                .orElseThrow(() -> rejected(
                        "OUTCOME_OPERATION_MISSING", "Outcome operation does not exist"));
    }

    private Optional<OutcomeAttemptObservation> findAttempt(String observationId) {
        return exactlyOneOrEmpty(
                jdbc.query(
                        "select %s from outcome_operation_attempt_observation where observation_id = :observationId"
                                .formatted(ATTEMPT_COLUMNS),
                        Map.of("observationId", observationId),
                        JdbcOutcomeOperationLedger::mapAttempt),
                "multiple Outcome observations share one id");
    }

    private Optional<OutcomeAttemptObservation> latestAttempt(String operationId) {
        return exactlyOneOrEmpty(
                jdbc.query(
                        "select %s from outcome_operation_attempt_observation where operation_id = :operationId order by attempt_sequence desc limit 1"
                                .formatted(ATTEMPT_COLUMNS),
                        Map.of("operationId", operationId),
                        JdbcOutcomeOperationLedger::mapAttempt),
                "multiple Outcome observations occupy the latest sequence");
    }

    private Optional<OutcomeCompensationParent> findCompensationParent(String childOperationId) {
        return exactlyOneOrEmpty(
                jdbc.query(
                        "select %s from outcome_compensation_parent_binding where child_operation_id = :childOperationId"
                                .formatted(COMPENSATION_COLUMNS),
                        Map.of("childOperationId", childOperationId),
                        JdbcOutcomeOperationLedger::mapCompensation),
                "multiple compensation parents share one child operation");
    }

    private void insertCompensationParent(OutcomeCompensationParent parent) {
        int inserted = jdbc.update(
                """
                insert into outcome_compensation_parent_binding (
                    binding_id, schema_version, binding_hash, child_operation_id,
                    parent_operation_id, parent_receipt_id, parent_receipt_hash,
                    compensation_policy_version, reverse_order, tenant_surrogate,
                    case_id, outcome_epoch, fencing_token, created_at
                ) values (
                    :bindingId, :schemaVersion, :bindingHash, :childOperationId,
                    :parentOperationId, :parentReceiptId, :parentReceiptHash,
                    :compensationPolicyVersion, :reverseOrder, :tenantSurrogate,
                    :caseId, :outcomeEpoch, :fencingToken, :createdAt
                )
                """,
                new MapSqlParameterSource()
                        .addValue("bindingId", parent.bindingId())
                        .addValue("schemaVersion", OutcomeCompensationParent.SCHEMA_VERSION)
                        .addValue("bindingHash", parent.bindingHash())
                        .addValue("childOperationId", parent.childOperationId())
                        .addValue("parentOperationId", parent.parentOperationId())
                        .addValue("parentReceiptId", parent.parentReceiptId())
                        .addValue("parentReceiptHash", parent.parentReceiptHash())
                        .addValue("compensationPolicyVersion", parent.compensationPolicyVersion())
                        .addValue("reverseOrder", parent.reverseOrder())
                        .addValue("tenantSurrogate", parent.tenantSurrogate())
                        .addValue("caseId", parent.caseId())
                        .addValue("outcomeEpoch", parent.outcomeEpoch())
                        .addValue("fencingToken", parent.fencingToken())
                        .addValue("createdAt", timestamp(parent.createdAt())));
        requireSingleInsert(inserted, "OUTCOME_COMPENSATION_PARENT_INSERT_FAILED");
    }

    private void requireNextCompensationParent(
            OutcomeProcessProjection projection, OutcomeCompensationParent candidate) {
        MapSqlParameterSource scope = new MapSqlParameterSource()
                .addValue("projectionId", projection.projectionId())
                .addValue("tenantSurrogate", projection.tenantSurrogate())
                .addValue("caseId", projection.caseId())
                .addValue("outcomeEpoch", projection.outcomeEpoch())
                .addValue("fencingToken", projection.fencingToken());
        List<CompensationBarrier> barriers = jdbc.query(
                """
                select count(*) as required_operation_count,
                       count(receipt.operation_id) as terminal_required_operation_count,
                       count(*) filter (
                           where receipt.operation_id is null
                             and latest.observation_type in ('AMBIGUOUS', 'RECONCILING')
                       ) as unresolved_reconciliation_count
                  from outcome_operation operation
                  left join outcome_operation_receipt receipt
                    on receipt.operation_id = operation.operation_id
                  left join lateral (
                      select observation.observation_type
                        from outcome_operation_attempt_observation observation
                       where observation.operation_id = operation.operation_id
                       order by observation.attempt_sequence desc
                       limit 1
                  ) latest on true
                 where operation.projection_id = :projectionId
                   and operation.operation_kind = 'OPERATION'
                   and operation.required_for_closure
                """,
                scope,
                (row, ignored) -> new CompensationBarrier(
                        row.getLong("required_operation_count"),
                        row.getLong("terminal_required_operation_count"),
                        row.getLong("unresolved_reconciliation_count")));
        CompensationBarrier barrier = exactlyOneOrEmpty(
                        barriers, "multiple compensation barriers match one Outcome projection")
                .orElseThrow(() -> rejected(
                        "OUTCOME_COMPENSATION_BARRIER_MISSING",
                        "compensation prerequisites have no exact Outcome projection"));
        if (barrier.requiredOperationCount() != projection.expectedRequiredOperationCount()) {
            throw rejected(
                    "OUTCOME_REQUIRED_OPERATION_SET_INCOMPLETE",
                    "compensation cannot start before the approved required operation set is reserved");
        }
        if (barrier.terminalRequiredOperationCount()
                        != projection.expectedRequiredOperationCount()
                || barrier.unresolvedReconciliationCount() != 0) {
            throw rejected(
                    "OUTCOME_COMPENSATION_BARRIER_UNRESOLVED",
                    "compensation cannot start before every approved required original operation is terminal and resolved");
        }

        Long existingBindingCount = jdbc.queryForObject(
                """
                select count(*)
                  from outcome_compensation_parent_binding
                 where tenant_surrogate = :tenantSurrogate
                   and case_id = :caseId
                   and outcome_epoch = :outcomeEpoch
                """,
                scope,
                Long.class);
        long expectedReverseOrder = Objects.requireNonNull(
                        existingBindingCount, "compensation binding count")
                + 1;
        List<ExpectedCompensationParent> expectedParents = jdbc.query(
                """
                select operation.operation_id, receipt.receipt_id, receipt.receipt_hash
                  from outcome_operation operation
                  join outcome_operation_receipt receipt
                    on receipt.operation_id = operation.operation_id
                   and receipt.receipt_status = 'SUCCEEDED'
                   and receipt.closure_disposition = 'SATISFIED'
                 where operation.projection_id = :projectionId
                   and operation.tenant_surrogate = :tenantSurrogate
                   and operation.case_id = :caseId
                   and operation.outcome_epoch = :outcomeEpoch
                   and operation.fencing_token = :fencingToken
                   and operation.operation_kind = 'OPERATION'
                   and operation.compensable
                 order by operation.operation_sequence desc, operation.operation_id desc
                 limit 1 offset :existingBindingCount
                """,
                scope.addValue("existingBindingCount", existingBindingCount),
                (row, ignored) -> new ExpectedCompensationParent(
                        row.getString("operation_id"),
                        row.getString("receipt_id"),
                        row.getString("receipt_hash")));
        ExpectedCompensationParent expected = exactlyOneOrEmpty(
                        expectedParents, "multiple parents occupy one compensation reverse order")
                .orElseThrow(() -> rejected(
                        "OUTCOME_COMPENSATION_PARENT_MISSING",
                        "no succeeded compensable parent is available at the next reverse order"));
        if (candidate.reverseOrder() != expectedReverseOrder
                || !candidate.parentOperationId().equals(expected.operationId())
                || !candidate.parentReceiptId().equals(expected.receiptId())
                || !candidate.parentReceiptHash().equals(expected.receiptHash())) {
            throw rejected(
                    "OUTCOME_COMPENSATION_ORDER_INVALID",
                    "compensation must bind the next exact succeeded parent in reverse operation order");
        }
    }

    private void requireExactCompensationReplay(
            OutcomeOperation operation, OutcomeCompensationParent candidate) {
        Optional<OutcomeCompensationParent> committed = findCompensationParent(operation.operationId());
        if (operation.operationKind() == OutcomeOperation.OperationKind.OPERATION) {
            if (candidate != null || committed.isPresent()) {
                throw rejected(
                        "OUTCOME_COMPENSATION_PARENT_FORBIDDEN",
                        "OPERATION cannot have a compensation parent");
            }
            return;
        }
        if (candidate == null || committed.isEmpty()) {
            throw rejected(
                    "OUTCOME_COMPENSATION_PARENT_MISSING",
                    "COMPENSATION operation requires an immutable parent receipt");
        }
        committed.orElseThrow().requireExactReplay(candidate);
    }

    private static void requireCompensationShape(
            OutcomeOperation operation, OutcomeCompensationParent parent) {
        boolean compensation = operation.operationKind() == OutcomeOperation.OperationKind.COMPENSATION;
        if (compensation != (parent != null)) {
            throw rejected(
                    "OUTCOME_COMPENSATION_PARENT_SHAPE_INVALID",
                    "only COMPENSATION operations require a parent receipt");
        }
        if (parent != null
                && (!parent.childOperationId().equals(operation.operationId())
                        || !parent.tenantSurrogate().equals(operation.tenantSurrogate())
                        || !parent.caseId().equals(operation.caseId())
                        || parent.outcomeEpoch() != operation.outcomeEpoch()
                        || parent.fencingToken() != operation.fencingToken())) {
            throw rejected(
                    "OUTCOME_COMPENSATION_PARENT_SCOPE_CONFLICT",
                    "compensation parent does not match child operation authority scope");
        }
        if (compensation && !operation.requiredForClosure()) {
            throw rejected(
                    "OUTCOME_COMPENSATION_CLOSURE_BYPASS_FORBIDDEN",
                    "compensation operations must remain closure prerequisites");
        }
    }

    private static void requireAttemptBinding(
            OutcomeOperation operation, OutcomeAttemptObservation observation) {
        if (!operation.operationId().equals(observation.operationId())
                || !operation.tenantSurrogate().equals(observation.tenantSurrogate())
                || !operation.caseId().equals(observation.caseId())
                || operation.outcomeEpoch() != observation.outcomeEpoch()
                || operation.fencingToken() != observation.fencingToken()
                || !operation.requestHash().equals(observation.requestHash())) {
            throw rejected(
                    "OUTCOME_ATTEMPT_BINDING_CONFLICT",
                    "attempt observation does not match immutable operation authority");
        }
    }

    private static void requireReceiptBinding(
            OutcomeOperation operation, OutcomeOperationReceipt receipt) {
        if (!operation.operationId().equals(receipt.operationId())
                || !operation.tenantSurrogate().equals(receipt.tenantSurrogate())
                || !operation.caseId().equals(receipt.caseId())
                || operation.outcomeEpoch() != receipt.outcomeEpoch()
                || operation.fencingToken() != receipt.fencingToken()
                || !operation.requestHash().equals(receipt.requestHash())) {
            throw rejected(
                    "OUTCOME_RECEIPT_BINDING_CONFLICT",
                    "receipt does not match immutable operation authority");
        }
    }

    private static void requireSafeAttemptTransition(
            Optional<OutcomeAttemptObservation> previous,
            OutcomeAttemptObservation next) {
        if (previous.isEmpty()) {
            if (next.attemptSequence() != 1
                    || next.observationType()
                            == OutcomeAttemptObservation.ObservationType.RECONCILING) {
                throw rejected(
                        "OUTCOME_ATTEMPT_SEQUENCE_INVALID",
                        "attempt sequence must start at one outside reconciliation");
            }
            return;
        }
        OutcomeAttemptObservation committed = previous.orElseThrow();
        if (next.attemptSequence() != committed.attemptSequence() + 1) {
            throw rejected(
                    "OUTCOME_ATTEMPT_SEQUENCE_INVALID", "attempt observations must be consecutive");
        }
        if (committed.observationType() == OutcomeAttemptObservation.ObservationType.AMBIGUOUS
                && next.observationType()
                        != OutcomeAttemptObservation.ObservationType.RECONCILING) {
            throw rejected(
                    "OUTCOME_AMBIGUOUS_RECONCILIATION_REQUIRED",
                    "AMBIGUOUS operation permits only RECONCILING as its next observation");
        }
        if (committed.observationType()
                        == OutcomeAttemptObservation.ObservationType.RECONCILING
                && next.observationType()
                        != OutcomeAttemptObservation.ObservationType.RECONCILING
                && next.observationType()
                        != OutcomeAttemptObservation.ObservationType.NO_EFFECT_CONFIRMED) {
            throw rejected(
                    "OUTCOME_RECONCILING_INVOCATION_FORBIDDEN",
                    "RECONCILING operation cannot dispatch another effect");
        }
    }

    private void lockSemantic(String key) {
        jdbc.queryForObject(
                "select pg_advisory_xact_lock(hashtextextended(:semanticKey, 0))",
                Map.of("semanticKey", key),
                Object.class);
    }

    private static String operationLifecycleKey(String operationId) {
        return OPERATION_LIFECYCLE_LOCK_PREFIX + operationId;
    }

    private static String compensationOrderKey(OutcomeOperation operation) {
        return compensationOrderKey(
                operation.tenantSurrogate(), operation.caseId(), operation.outcomeEpoch());
    }

    private static String compensationOrderKey(OutcomeOperationReceipt receipt) {
        return compensationOrderKey(
                receipt.tenantSurrogate(), receipt.caseId(), receipt.outcomeEpoch());
    }

    private static String compensationOrderKey(OutcomeAttemptObservation observation) {
        return compensationOrderKey(
                observation.tenantSurrogate(), observation.caseId(), observation.outcomeEpoch());
    }

    private static String compensationOrderKey(
            String tenantSurrogate, String caseId, long outcomeEpoch) {
        return COMPENSATION_SCOPE_LOCK_PREFIX + tenantSurrogate + ':'
                + caseId + ':' + outcomeEpoch;
    }

    private void rejectOriginalReceiptAfterCompensationStarted(OutcomeOperation operation) {
        if (operation.operationKind() != OutcomeOperation.OperationKind.OPERATION) {
            return;
        }
        Boolean compensationStarted = jdbc.queryForObject(
                """
                select exists (
                    select 1
                      from outcome_operation compensation
                     where compensation.projection_id = :projectionId
                       and compensation.operation_kind = 'COMPENSATION'
                )
                """,
                Map.of("projectionId", operation.projectionId()),
                Boolean.class);
        if (Boolean.TRUE.equals(compensationStarted)) {
            throw rejected(
                    "OUTCOME_ORIGINAL_RECEIPT_AFTER_COMPENSATION",
                    "new original terminal receipts are forbidden after compensation starts");
        }
    }

    private record CompensationBarrier(
            long requiredOperationCount,
            long terminalRequiredOperationCount,
            long unresolvedReconciliationCount) {}

    private static ProjectionExpectation expectation(OutcomeOperation operation) {
        return new ProjectionExpectation(
                operation.projectionId(),
                operation.tenantSurrogate(),
                operation.caseId(),
                operation.outcomeEpoch(),
                operation.fencingToken(),
                operation.processRevision(),
                operation.outcomeRevision());
    }

    private static OperationLookup lookup(OutcomeOperation operation) {
        return new OperationLookup(
                operation.tenantSurrogate(),
                operation.caseId(),
                operation.outcomeEpoch(),
                operation.operationKey());
    }

    private static MapSqlParameterSource projectionParameters(OutcomeProcessProjection value) {
        return new MapSqlParameterSource()
                .addValue("projectionId", value.projectionId())
                .addValue("schemaVersion", OutcomeProcessProjection.SCHEMA_VERSION)
                .addValue("tenantSurrogate", value.tenantSurrogate())
                .addValue("caseId", value.caseId())
                .addValue("epochId", value.epochId())
                .addValue("outcomeEpoch", value.outcomeEpoch())
                .addValue("writerMode", value.writerMode().name())
                .addValue("runtimeMode", value.runtimeMode().name())
                .addValue("fencingToken", value.fencingToken())
                .addValue("processRevision", value.processRevision())
                .addValue("outcomeRevision", value.outcomeRevision())
                .addValue("decisionAuthorityReceiptId", value.decisionAuthorityReceiptId())
                .addValue("decisionRequestHash", value.decisionRequestHash())
                .addValue("approvedOperationSetHash", value.approvedOperationSetHash())
                .addValue("expectedRequiredOperationCount", value.expectedRequiredOperationCount())
                .addValue("processState", value.processState().name())
                .addValue("projectedAt", timestamp(value.projectedAt()))
                .addValue("updatedAt", timestamp(value.updatedAt()));
    }

    private static MapSqlParameterSource operationParameters(OutcomeOperation value) {
        return new MapSqlParameterSource()
                .addValue("operationId", value.operationId())
                .addValue("schemaVersion", OutcomeOperation.SCHEMA_VERSION)
                .addValue("projectionId", value.projectionId())
                .addValue("tenantSurrogate", value.tenantSurrogate())
                .addValue("caseId", value.caseId())
                .addValue("outcomeEpoch", value.outcomeEpoch())
                .addValue("fencingToken", value.fencingToken())
                .addValue("processRevision", value.processRevision())
                .addValue("outcomeRevision", value.outcomeRevision())
                .addValue("operationKind", value.operationKind().name())
                .addValue("operationSequence", value.operationSequence())
                .addValue("operationKey", value.operationKey())
                .addValue("requestHash", value.requestHash())
                .addValue("reviewPacketId", value.reviewPacketId())
                .addValue("reviewPacketVersion", value.reviewPacketVersion())
                .addValue("reviewPacketHash", value.reviewPacketHash())
                .addValue("reviewPacketActionHash", value.reviewPacketActionHash())
                .addValue("approvalRecordId", value.approvalRecordId())
                .addValue("approvalHash", value.approvalHash())
                .addValue("decisionRequestHash", value.decisionRequestHash())
                .addValue("decisionPolicyVersion", value.decisionPolicyVersion())
                .addValue("actionRecordId", value.actionRecordId())
                .addValue("actionSnapshotHash", value.actionSnapshotHash())
                .addValue("adapterId", value.adapterId())
                .addValue("adapterVersion", value.adapterVersion())
                .addValue("retryClass", value.retryClass().name())
                .addValue("externalIdempotencyKey", value.externalIdempotencyKey())
                .addValue("requiredForClosure", value.requiredForClosure())
                .addValue("compensable", value.compensable())
                .addValue("reservedAt", timestamp(value.reservedAt()));
    }

    private static MapSqlParameterSource attemptParameters(OutcomeAttemptObservation value) {
        return new MapSqlParameterSource()
                .addValue("observationId", value.observationId())
                .addValue("schemaVersion", OutcomeAttemptObservation.SCHEMA_VERSION)
                .addValue("observationHash", value.observationHash())
                .addValue("operationId", value.operationId())
                .addValue("tenantSurrogate", value.tenantSurrogate())
                .addValue("caseId", value.caseId())
                .addValue("outcomeEpoch", value.outcomeEpoch())
                .addValue("fencingToken", value.fencingToken())
                .addValue("requestHash", value.requestHash())
                .addValue("attemptSequence", value.attemptSequence())
                .addValue("observationType", value.observationType().name())
                .addValue("externalInvocationId", value.externalInvocationId())
                .addValue("observationRef", value.observationRef())
                .addValue("observationPayloadHash", value.observationPayloadHash())
                .addValue("effectMayHaveOccurred", value.effectMayHaveOccurred())
                .addValue("retryPermitted", value.retryPermitted())
                .addValue("observedAt", timestamp(value.observedAt()));
    }

    private static MapSqlParameterSource receiptParameters(OutcomeOperationReceipt value) {
        return new MapSqlParameterSource()
                .addValue("receiptId", value.receiptId())
                .addValue("schemaVersion", OutcomeOperationReceipt.SCHEMA_VERSION)
                .addValue("receiptHash", value.receiptHash())
                .addValue("operationId", value.operationId())
                .addValue("tenantSurrogate", value.tenantSurrogate())
                .addValue("caseId", value.caseId())
                .addValue("outcomeEpoch", value.outcomeEpoch())
                .addValue("fencingToken", value.fencingToken())
                .addValue("requestHash", value.requestHash())
                .addValue("receiptStatus", value.receiptStatus().name())
                .addValue("receiptAuthority", value.receiptAuthority().name())
                .addValue("externalReceiptId", value.externalReceiptId())
                .addValue("responseRef", value.responseRef())
                .addValue("responseHash", value.responseHash())
                .addValue("closureDisposition", value.closureDisposition().name())
                .addValue("completedAt", timestamp(value.completedAt()));
    }

    private static MapSqlParameterSource expectationParameters(ProjectionExpectation value) {
        return new MapSqlParameterSource()
                .addValue("projectionId", value.projectionId())
                .addValue("tenantSurrogate", value.tenantSurrogate())
                .addValue("caseId", value.caseId())
                .addValue("outcomeEpoch", value.outcomeEpoch())
                .addValue("fencingToken", value.fencingToken())
                .addValue("processRevision", value.processRevision())
                .addValue("outcomeRevision", value.outcomeRevision());
    }

    private static OutcomeProcessProjection mapProjection(ResultSet row, int ignored)
            throws SQLException {
        return new OutcomeProcessProjection(
                row.getString("projection_id"),
                row.getString("tenant_surrogate"),
                row.getString("case_id"),
                row.getString("epoch_id"),
                row.getLong("outcome_epoch"),
                OutcomeProcessProjection.WriterMode.valueOf(row.getString("writer_mode")),
                OutcomeProcessProjection.RuntimeMode.valueOf(row.getString("runtime_mode")),
                row.getLong("fencing_token"),
                row.getLong("process_revision"),
                row.getLong("outcome_revision"),
                row.getString("decision_authority_receipt_id"),
                row.getString("decision_request_hash"),
                row.getString("approved_operation_set_hash"),
                row.getLong("expected_required_operation_count"),
                OutcomeProcessProjection.ProcessState.valueOf(row.getString("process_state")),
                row.getObject("projected_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OutcomeOperation mapOperation(ResultSet row, int ignored) throws SQLException {
        return new OutcomeOperation(
                row.getString("operation_id"),
                row.getString("projection_id"),
                row.getString("tenant_surrogate"),
                row.getString("case_id"),
                row.getLong("outcome_epoch"),
                row.getLong("fencing_token"),
                row.getLong("process_revision"),
                row.getLong("outcome_revision"),
                OutcomeOperation.OperationKind.valueOf(row.getString("operation_kind")),
                row.getLong("operation_sequence"),
                row.getString("operation_key"),
                row.getString("request_hash"),
                row.getString("review_packet_id"),
                row.getInt("review_packet_version"),
                row.getString("review_packet_hash"),
                row.getString("review_packet_action_hash"),
                row.getString("approval_record_id"),
                row.getString("approval_hash"),
                row.getString("decision_request_hash"),
                row.getString("decision_policy_version"),
                row.getString("action_record_id"),
                row.getString("action_snapshot_hash"),
                row.getString("adapter_id"),
                row.getString("adapter_version"),
                OutcomeOperation.RetryClass.valueOf(row.getString("retry_class")),
                row.getString("external_idempotency_key"),
                row.getBoolean("required_for_closure"),
                row.getBoolean("compensable"),
                row.getObject("reserved_at", OffsetDateTime.class).toInstant());
    }

    private static OutcomeAttemptObservation mapAttempt(ResultSet row, int ignored)
            throws SQLException {
        return new OutcomeAttemptObservation(
                row.getString("observation_id"),
                row.getString("observation_hash"),
                row.getString("operation_id"),
                row.getString("tenant_surrogate"),
                row.getString("case_id"),
                row.getLong("outcome_epoch"),
                row.getLong("fencing_token"),
                row.getString("request_hash"),
                row.getInt("attempt_sequence"),
                OutcomeAttemptObservation.ObservationType.valueOf(row.getString("observation_type")),
                row.getString("external_invocation_id"),
                row.getString("observation_ref"),
                row.getString("observation_payload_hash"),
                row.getBoolean("effect_may_have_occurred"),
                row.getBoolean("retry_permitted"),
                row.getObject("observed_at", OffsetDateTime.class).toInstant());
    }

    private static OutcomeOperationReceipt mapReceipt(ResultSet row, int ignored)
            throws SQLException {
        return new OutcomeOperationReceipt(
                row.getString("receipt_id"),
                row.getString("receipt_hash"),
                row.getString("operation_id"),
                row.getString("tenant_surrogate"),
                row.getString("case_id"),
                row.getLong("outcome_epoch"),
                row.getLong("fencing_token"),
                row.getString("request_hash"),
                OutcomeOperationReceipt.ReceiptStatus.valueOf(row.getString("receipt_status")),
                OutcomeOperationReceipt.ReceiptAuthority.valueOf(row.getString("receipt_authority")),
                row.getString("external_receipt_id"),
                row.getString("response_ref"),
                row.getString("response_hash"),
                OutcomeOperationReceipt.ClosureDisposition.valueOf(
                        row.getString("closure_disposition")),
                row.getObject("completed_at", OffsetDateTime.class).toInstant());
    }

    private static OutcomeCompensationParent mapCompensation(ResultSet row, int ignored)
            throws SQLException {
        return new OutcomeCompensationParent(
                row.getString("binding_id"),
                row.getString("binding_hash"),
                row.getString("child_operation_id"),
                row.getString("parent_operation_id"),
                row.getString("parent_receipt_id"),
                row.getString("parent_receipt_hash"),
                row.getString("compensation_policy_version"),
                row.getLong("reverse_order"),
                row.getString("tenant_surrogate"),
                row.getString("case_id"),
                row.getLong("outcome_epoch"),
                row.getLong("fencing_token"),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static OutcomeClosureReadiness mapClosureReadiness(ResultSet row, int ignored)
            throws SQLException {
        return new OutcomeClosureReadiness(
                row.getString("projection_id"),
                row.getString("tenant_surrogate"),
                row.getString("case_id"),
                row.getLong("outcome_epoch"),
                row.getLong("fencing_token"),
                row.getLong("expected_required_operation_count"),
                row.getLong("required_operation_count"),
                row.getLong("unresolved_operation_count"),
                row.getLong("blocked_operation_count"),
                row.getLong("reconciliation_operation_count"),
                row.getLong("pending_compensation_count"),
                row.getBoolean("closure_ready"));
    }

    private static OutcomeOperationState mapOperationState(ResultSet row, int ignored)
            throws SQLException {
        return new OutcomeOperationState(
                row.getString("projection_id"),
                row.getString("operation_id"),
                OutcomeOperation.OperationKind.valueOf(row.getString("operation_kind")),
                row.getLong("operation_sequence"),
                row.getString("operation_key"),
                row.getString("request_hash"),
                row.getBoolean("required_for_closure"),
                row.getBoolean("compensable"),
                row.getString("tenant_surrogate"),
                row.getString("case_id"),
                row.getLong("outcome_epoch"),
                row.getLong("fencing_token"),
                row.getLong("process_revision"),
                row.getLong("outcome_revision"),
                OutcomeOperationState.Status.valueOf(row.getString("operation_status")),
                row.getString("receipt_id"),
                row.getString("receipt_hash"),
                row.getBoolean("java_authoritative"),
                row.getString("parent_operation_id"),
                row.getString("parent_receipt_id"),
                row.getString("parent_receipt_hash"),
                row.getString("compensation_policy_version"),
                nullableLong(row, "reverse_order"));
    }

    private static Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static OffsetDateTime timestamp(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }

    private static <T> Optional<T> exactlyOneOrEmpty(List<T> rows, String conflictMessage) {
        if (rows.size() > 1) {
            throw rejected("OUTCOME_LEDGER_CORRUPT", conflictMessage);
        }
        return rows.stream().findFirst();
    }

    private static void requireSingleInsert(int inserted, String code) {
        if (inserted != 1) {
            throw rejected(code, "expected exactly one append-only ledger row");
        }
    }

    private static OutcomeLedgerRejectedException rejected(String code, String message) {
        return new OutcomeLedgerRejectedException(code, message);
    }

    private record ExpectedCompensationParent(
            String operationId, String receiptId, String receiptHash) {}
}
