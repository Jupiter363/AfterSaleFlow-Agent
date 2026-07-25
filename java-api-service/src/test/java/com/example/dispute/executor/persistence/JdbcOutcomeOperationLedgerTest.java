package com.example.dispute.executor.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.executor.domain.ledger.OutcomeAttemptObservation;
import com.example.dispute.executor.domain.ledger.OutcomeAttemptObservation.ObservationType;
import com.example.dispute.executor.domain.ledger.OutcomeClosureReadiness;
import com.example.dispute.executor.domain.ledger.OutcomeCompensationParent;
import com.example.dispute.executor.domain.ledger.OutcomeLedgerRejectedException;
import com.example.dispute.executor.domain.ledger.OutcomeOperation;
import com.example.dispute.executor.domain.ledger.OutcomeOperation.OperationKind;
import com.example.dispute.executor.domain.ledger.OutcomeOperation.RetryClass;
import com.example.dispute.executor.domain.ledger.OutcomeOperationLedger.OperationLookup;
import com.example.dispute.executor.domain.ledger.OutcomeOperationLedger.ProjectionExpectation;
import com.example.dispute.executor.domain.ledger.OutcomeOperationReceipt;
import com.example.dispute.executor.domain.ledger.OutcomeOperationReceipt.ClosureDisposition;
import com.example.dispute.executor.domain.ledger.OutcomeOperationReceipt.ReceiptAuthority;
import com.example.dispute.executor.domain.ledger.OutcomeOperationReceipt.ReceiptStatus;
import com.example.dispute.executor.domain.ledger.OutcomeProcessProjection;
import com.example.dispute.executor.domain.ledger.OutcomeProcessProjection.ProcessState;
import com.example.dispute.executor.domain.ledger.OutcomeProcessProjection.RuntimeMode;
import com.example.dispute.executor.domain.ledger.OutcomeProcessProjection.WriterMode;
import com.example.dispute.executor.infrastructure.persistence.JdbcOutcomeOperationLedger;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class JdbcOutcomeOperationLedgerTest {

    private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String HASH_C = "c".repeat(64);

    @Mock private NamedParameterJdbcTemplate jdbc;
    @Mock private TransactionTemplate transactions;
    @Mock private TransactionStatus transactionStatus;

    private JdbcOutcomeOperationLedger ledger;

    @BeforeEach
    void setUp() {
        ledger = new JdbcOutcomeOperationLedger(jdbc, transactions);
        lenient().when(transactions.execute(any()))
                .thenAnswer(invocation -> {
                    TransactionCallback<?> callback = invocation.getArgument(0);
                    return callback.doInTransaction(transactionStatus);
                });
        lenient().when(jdbc.queryForObject(
                        contains("pg_advisory_xact_lock"), anyMap(), any(Class.class)))
                .thenReturn(new Object());
    }

    @Test
    void sameOperationKeyWithDifferentRequestHashFailsBeforeInsert() {
        OutcomeOperation committed = operation(HASH_A);
        when(jdbc.query(contains("from outcome_operation where tenant_surrogate"), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of(committed));

        assertThatThrownBy(() -> ledger.reserve(operation(HASH_B), null))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_IDEMPOTENCY_CONFLICT");
        verify(jdbc, never()).update(
                contains("insert into outcome_operation ("), any(MapSqlParameterSource.class));
    }

    @Test
    void ambiguousCanOnlyAdvanceToReconciliationAndNeverBlindRetry() {
        OutcomeOperation operation = operation(HASH_A);
        OutcomeAttemptObservation ambiguous = observation(1, ObservationType.AMBIGUOUS, true, false);
        OutcomeAttemptObservation blindRetry = observation(2, ObservationType.INVOCATION_DISPATCHED, true, false);
        when(jdbc.query(contains("where observation_id"), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of());
        when(jdbc.query(contains("from outcome_operation where operation_id"), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of(operation));
        when(jdbc.query(contains("order by attempt_sequence desc limit 1"), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of(ambiguous));

        assertThatThrownBy(() -> ledger.appendAttempt(blindRetry))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_AMBIGUOUS_RECONCILIATION_REQUIRED");
        verify(jdbc, never()).update(
                contains("insert into outcome_operation_attempt_observation"),
                any(MapSqlParameterSource.class));
    }

    @Test
    void reconciliationRejectsNonAuthoritativeDirectResponse() {
        OutcomeOperation operation = operation(HASH_A);
        OutcomeAttemptObservation reconciling =
                observation(2, ObservationType.RECONCILING, true, false);
        when(jdbc.query(contains("from outcome_operation_receipt where operation_id"), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of());
        when(jdbc.query(contains("from outcome_operation where operation_id"), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of(operation));
        when(jdbc.query(contains("order by attempt_sequence desc limit 1"), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of(reconciling));

        assertThatThrownBy(() -> ledger.recordReceipt(receipt(ReceiptAuthority.DIRECT_RESPONSE)))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_RECONCILIATION_AUTHORITY_REQUIRED");
        verify(jdbc, never()).update(
                contains("insert into outcome_operation_receipt"),
                any(MapSqlParameterSource.class));
    }

    @Test
    void succeededReceiptHasOnlySatisfiedClosureDisposition() {
        assertThatThrownBy(() -> new OutcomeOperationReceipt(
                        "RECEIPT_1",
                        HASH_A,
                        "OPERATION_1",
                        "TENANT_1",
                        "CASE_1",
                        1,
                        7,
                        HASH_B,
                        ReceiptStatus.SUCCEEDED,
                        ReceiptAuthority.PROVIDER_STATUS_QUERY,
                        "PROVIDER_RECEIPT_1",
                        "urn:outcome:receipt:1",
                        HASH_C,
                        ClosureDisposition.BLOCKED,
                        NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must satisfy closure");
    }

    @Test
    void lookupUsesCaseEpochAndOperationKeyAsTheSemanticIdentity() {
        OutcomeOperation operation = operation(HASH_A);
        when(jdbc.query(contains("from outcome_operation where tenant_surrogate"), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of(operation));

        assertThat(ledger.findOperation(new OperationLookup(
                        operation.tenantSurrogate(),
                        operation.caseId(),
                        operation.outcomeEpoch(),
                        operation.operationKey())))
                .containsSame(operation);
    }

    @Test
    void projectionBootstrapAdvertisesOnlyDecisionRecorded() {
        assertThatThrownBy(() -> ledger.createProjection(projection(ProcessState.REVIEW_WAIT, NOW)))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_PROJECTION_BOOTSTRAP_STATE_INVALID");
        verify(jdbc, never()).update(
                contains("insert into outcome_process_projection"),
                any(MapSqlParameterSource.class));
    }

    @Test
    void reservationRequiresTheExactLockedProjectionAuthorityTuple() {
        when(jdbc.query(
                        contains("from outcome_operation where tenant_surrogate"),
                        anyMap(),
                        any(RowMapper.class)))
                .thenReturn(List.of());
        when(jdbc.query(
                        contains("from outcome_process_projection where projection_id"),
                        any(MapSqlParameterSource.class),
                        any(RowMapper.class)))
                .thenReturn(List.of(projection(NOW)));
        List<OutcomeOperation> substitutions = List.of(
                operation(HASH_A, RetryClass.STATUS_QUERY_REQUIRED, "APPROVAL_2", HASH_B,
                        "action-snapshot-hash-1", NOW),
                operation(HASH_A, RetryClass.STATUS_QUERY_REQUIRED, "APPROVAL_1", HASH_C,
                        "action-snapshot-hash-1", NOW),
                operation(HASH_A, RetryClass.STATUS_QUERY_REQUIRED, "APPROVAL_1", HASH_B,
                        "action-snapshot-hash-2", NOW));

        for (OutcomeOperation substitution : substitutions) {
            assertThatThrownBy(() -> ledger.reserve(substitution, null))
                    .isInstanceOf(OutcomeLedgerRejectedException.class)
                    .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                    .isEqualTo("OUTCOME_OPERATION_PROJECTION_AUTHORITY_CONFLICT");
        }
        verify(jdbc, never()).update(
                contains("insert into outcome_operation ("), any(MapSqlParameterSource.class));
    }

    @Test
    void formalRequiredReservationRequiresAnAuthorizedActionRecord() {
        OutcomeProcessProjection projection = projection(NOW);
        OutcomeOperation missing = operationWithAuthority(
                projection, OperationKind.OPERATION, 1, null, "refund-adapter", true);
        stubNewReservation(projection);

        assertThatThrownBy(() -> ledger.reserve(missing, null))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_REQUIRED_ACTION_AUTHORITY_INVALID");

        OutcomeOperation forged = operationWithAuthority(
                projection, OperationKind.OPERATION, 1, "ACTION_FORGED", "refund-adapter", true);
        when(jdbc.queryForObject(
                        contains("outcome_required_action_record_is_authorized"),
                        anyMap(),
                        any(Class.class)))
                .thenReturn(false);
        assertThatThrownBy(() -> ledger.reserve(forged, null))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_REQUIRED_ACTION_AUTHORITY_INVALID");
        verify(jdbc, never()).update(
                contains("insert into outcome_operation ("), any(MapSqlParameterSource.class));
    }

    @Test
    void formalRequiredReservationAcceptsOnlyTheDatabaseNormalizedAuthority() {
        OutcomeProcessProjection projection = projection(NOW);
        OutcomeOperation operation = operationWithAuthority(
                projection, OperationKind.OPERATION, 1, "ACTION_1", "refund-adapter", true);
        stubNewReservation(projection);
        when(jdbc.queryForObject(
                        contains("outcome_required_action_record_is_authorized"),
                        anyMap(),
                        any(Class.class)))
                .thenReturn(true);
        when(jdbc.update(
                        contains("insert into outcome_operation ("),
                        any(MapSqlParameterSource.class)))
                .thenReturn(1);

        assertThat(ledger.reserve(operation, null)).isEqualTo(operation);
    }

    @Test
    void signedSyntheticRequiredReservationEnforcesTheNoopAuthorityEnvelope() {
        OutcomeProcessProjection projection = syntheticProjection(1);
        OutcomeOperation valid = operationWithAuthority(
                projection, OperationKind.OPERATION, 1, null, "SYNTHETIC_NOOP_ONLY", true);
        stubNewReservation(projection);
        when(jdbc.update(
                        contains("insert into outcome_operation ("),
                        any(MapSqlParameterSource.class)))
                .thenReturn(1);

        assertThat(ledger.reserve(valid, null)).isEqualTo(valid);

        for (OutcomeOperation forged : List.of(
                operationWithAuthority(
                        projection, OperationKind.OPERATION, 1, "ACTION_1",
                        "SYNTHETIC_NOOP_ONLY", true),
                operationWithAuthority(
                        projection, OperationKind.OPERATION, 1, null,
                        "refund-adapter", true),
                operationWithAuthority(
                        projection, OperationKind.OPERATION, 2, null,
                        "SYNTHETIC_NOOP_ONLY", true))) {
            stubNewReservation(projection);
            assertThatThrownBy(() -> ledger.reserve(forged, null))
                    .isInstanceOf(OutcomeLedgerRejectedException.class)
                    .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                    .isEqualTo("OUTCOME_SYNTHETIC_ACTION_AUTHORITY_INVALID");
        }
        for (OutcomeProcessProjection badScope : List.of(
                syntheticProjection(1, "TENANT_1", "OUTCOME_SYNTHETIC_CASE_1"),
                syntheticProjection(1, "OUTCOME_SYNTHETIC_TENANT_1", "CASE_1"))) {
            OutcomeOperation forged = operationWithAuthority(
                    badScope,
                    OperationKind.OPERATION,
                    1,
                    null,
                    "SYNTHETIC_NOOP_ONLY",
                    true);
            stubNewReservation(badScope);
            assertThatThrownBy(() -> ledger.reserve(forged, null))
                    .isInstanceOf(OutcomeLedgerRejectedException.class)
                    .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                    .isEqualTo("OUTCOME_SYNTHETIC_ACTION_AUTHORITY_INVALID");
        }
    }

    @Test
    void nonRequiredOriginalPreservesTheNullableActionRecordContract() {
        OutcomeProcessProjection projection = projection(NOW);
        OutcomeOperation optional = operationWithAuthority(
                projection, OperationKind.OPERATION, 1, null, "refund-adapter", false);
        stubNewReservation(projection);
        when(jdbc.update(
                        contains("insert into outcome_operation ("),
                        any(MapSqlParameterSource.class)))
                .thenReturn(1);

        assertThat(ledger.reserve(optional, null)).isEqualTo(optional);
        verify(jdbc, never()).queryForObject(
                contains("outcome_required_action_record_is_authorized"),
                anyMap(),
                any(Class.class));
    }

    @Test
    void terminalAdvanceRequiresExactApprovedActionsAndSucceededActionRecords() {
        ProjectionExpectation expectation = new ProjectionExpectation(
                "PROJECTION_1", "TENANT_1", "CASE_1", 1, 7, 11, 5);
        when(jdbc.query(
                        contains("from outcome_process_projection where projection_id"),
                        any(MapSqlParameterSource.class),
                        any(RowMapper.class)))
                .thenReturn(List.of(projection(NOW)));
        when(jdbc.queryForObject(
                        contains("outcome_required_action_set_is_exact"),
                        anyMap(),
                        any(Class.class)))
                .thenReturn(false);

        assertThatThrownBy(() -> ledger.advanceProjection(
                        expectation, ProcessState.READY_TO_CLOSE, NOW.plusSeconds(1)))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_REQUIRED_ACTION_SET_INVALID");

        when(jdbc.queryForObject(
                        contains("outcome_required_action_set_is_exact"),
                        anyMap(),
                        any(Class.class)))
                .thenReturn(true);
        when(jdbc.queryForObject(
                        contains("outcome_required_action_records_succeeded"),
                        anyMap(),
                        any(Class.class)))
                .thenReturn(false);
        assertThatThrownBy(() -> ledger.advanceProjection(
                        expectation, ProcessState.READY_TO_CLOSE, NOW.plusSeconds(1)))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_REQUIRED_ACTION_NOT_SUCCEEDED");
        verify(jdbc, never()).update(
                contains("update outcome_process_projection"),
                any(MapSqlParameterSource.class));
    }

    @Test
    void redispatchRequiresPriorPermissionAndCompatibleRetryClass() {
        OutcomeOperation idempotent = operation(HASH_A, RetryClass.IDEMPOTENT_PROVIDER);
        stubAttemptTransition(
                idempotent, observation(1, ObservationType.INVOCATION_DISPATCHED, true, false));
        assertThatThrownBy(() -> ledger.appendAttempt(
                        observation(2, ObservationType.INVOCATION_DISPATCHED, true, false)))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_RETRY_NOT_PERMITTED");

        OutcomeOperation statusQueryRequired =
                operation(HASH_A, RetryClass.STATUS_QUERY_REQUIRED);
        stubAttemptTransition(
                statusQueryRequired,
                observation(1, ObservationType.PRE_EFFECT_RETRYABLE_FAILURE, false, true));
        assertThatThrownBy(() -> ledger.appendAttempt(
                        observation(2, ObservationType.INVOCATION_DISPATCHED, true, false)))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_RETRY_CLASS_FORBIDDEN");
    }

    @Test
    void nonRetryableOperationCannotPublishRetryAuthorityOnItsFirstObservation() {
        OutcomeOperation operation = operation(HASH_A, RetryClass.NON_RETRYABLE);
        when(jdbc.query(contains("where observation_id"), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of());
        when(jdbc.query(
                        contains("from outcome_operation where operation_id"),
                        anyMap(),
                        any(RowMapper.class)))
                .thenReturn(List.of(operation));
        when(jdbc.query(
                        contains("order by attempt_sequence desc limit 1"),
                        anyMap(),
                        any(RowMapper.class)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> ledger.appendAttempt(
                        observation(1, ObservationType.PRE_EFFECT_RETRYABLE_FAILURE, false, true)))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_RETRY_CLASS_FORBIDDEN");
        verify(jdbc, never()).update(
                contains("insert into outcome_operation_attempt_observation"),
                any(MapSqlParameterSource.class));
    }

    @Test
    void statusQueryClassRedispatchesOnlyAfterExplicitNoEffectConfirmation() {
        OutcomeOperation operation = operation(HASH_A, RetryClass.STATUS_QUERY_REQUIRED);
        OutcomeAttemptObservation retry =
                observation(3, ObservationType.INVOCATION_DISPATCHED, true, false);
        stubAttemptTransition(
                operation, observation(2, ObservationType.NO_EFFECT_CONFIRMED, false, true));
        when(jdbc.query(
                        contains("from outcome_operation_receipt where operation_id"),
                        anyMap(),
                        any(RowMapper.class)))
                .thenReturn(List.of());
        when(jdbc.update(
                        contains("insert into outcome_operation_attempt_observation"),
                        any(MapSqlParameterSource.class)))
                .thenReturn(1);

        assertThat(ledger.appendAttempt(retry)).isEqualTo(retry);
    }

    @Test
    void domainTimestampsCanonicalizeBeforeEveryExactReplayComparison() {
        Instant first = Instant.parse("2026-07-24T12:00:00.123456789Z");
        Instant replay = Instant.parse("2026-07-24T12:00:00.123456001Z");
        Instant canonical = Instant.parse("2026-07-24T12:00:00.123456Z");
        OutcomeOperation firstOperation = operation(
                HASH_A, RetryClass.STATUS_QUERY_REQUIRED, "APPROVAL_1", HASH_B,
                "action-snapshot-hash-1", first);
        OutcomeOperation replayOperation = operation(
                HASH_A, RetryClass.STATUS_QUERY_REQUIRED, "APPROVAL_1", HASH_B,
                "action-snapshot-hash-1", replay);
        OutcomeAttemptObservation firstAttempt = observationAt(
                1, ObservationType.INVOCATION_DISPATCHED, true, false, first);
        OutcomeAttemptObservation replayAttempt = observationAt(
                1, ObservationType.INVOCATION_DISPATCHED, true, false, replay);
        OutcomeOperationReceipt firstReceipt = receiptAt(ReceiptAuthority.DIRECT_RESPONSE, first);
        OutcomeOperationReceipt replayReceipt = receiptAt(ReceiptAuthority.DIRECT_RESPONSE, replay);
        OutcomeCompensationParent firstParent = compensationParent(first);
        OutcomeCompensationParent replayParent = compensationParent(replay);

        assertThat(projection(first)).isEqualTo(projection(replay));
        assertThat(firstOperation.reservedAt()).isEqualTo(canonical);
        firstOperation.requireExactReplay(replayOperation);
        assertThat(firstAttempt.observedAt()).isEqualTo(canonical);
        firstAttempt.requireExactReplay(replayAttempt);
        assertThat(firstReceipt.completedAt()).isEqualTo(canonical);
        firstReceipt.requireExactReplay(replayReceipt);
        assertThat(firstParent.createdAt()).isEqualTo(canonical);
        firstParent.requireExactReplay(replayParent);
    }

    @Test
    void closureReadinessLocksScopeBeforeProjectionAndReadinessRows() {
        ProjectionExpectation expectation = new ProjectionExpectation(
                "PROJECTION_1", "TENANT_1", "CASE_1", 1, 7, 11, 5);
        when(jdbc.query(
                        contains("from outcome_process_projection where projection_id"),
                        any(MapSqlParameterSource.class),
                        any(RowMapper.class)))
                .thenReturn(List.of(projection(NOW)));
        when(jdbc.query(
                        contains("from outcome_closure_readiness"),
                        any(MapSqlParameterSource.class),
                        any(RowMapper.class)))
                .thenReturn(List.of(new OutcomeClosureReadiness(
                        "PROJECTION_1", "TENANT_1", "CASE_1", 1, 7,
                        1, 1, 0, 0, 0, 0, true)));

        assertThat(ledger.closureReadiness(expectation).closureReady()).isTrue();

        InOrder order = inOrder(jdbc);
        order.verify(jdbc).queryForObject(
                contains("pg_advisory_xact_lock"), anyMap(), any(Class.class));
        order.verify(jdbc).query(
                contains("from outcome_process_projection where projection_id"),
                any(MapSqlParameterSource.class),
                any(RowMapper.class));
        order.verify(jdbc).query(
                contains("from outcome_closure_readiness"),
                any(MapSqlParameterSource.class),
                any(RowMapper.class));
    }

    @Test
    void terminalProjectionStatesAreFrozenToTheOneWayClosureEvaluationChain() {
        ProjectionExpectation expectation = new ProjectionExpectation(
                "PROJECTION_1", "TENANT_1", "CASE_1", 1, 7, 11, 5);
        when(jdbc.query(
                        contains("from outcome_process_projection where projection_id"),
                        any(MapSqlParameterSource.class),
                        any(RowMapper.class)))
                .thenReturn(
                        List.of(projection(ProcessState.READY_TO_CLOSE, NOW)),
                        List.of(projection(ProcessState.READY_TO_CLOSE, NOW)),
                        List.of(projection(ProcessState.DECISION_RECORDED, NOW)),
                        List.of(projection(ProcessState.REVIEW_WAIT, NOW)));

        for (ProcessState invalid : List.of(
                ProcessState.DECISION_RECORDED, ProcessState.EVALUATION_PENDING)) {
            assertThatThrownBy(() -> ledger.advanceProjection(expectation, invalid, NOW.plusSeconds(1)))
                    .isInstanceOf(OutcomeLedgerRejectedException.class)
                    .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                    .isEqualTo("OUTCOME_PROJECTION_TERMINAL_TRANSITION_INVALID");
        }
        assertThatThrownBy(() -> ledger.advanceProjection(
                        expectation, ProcessState.CLOSED, NOW.plusSeconds(1)))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_PROJECTION_TERMINAL_TRANSITION_INVALID");
        assertThatThrownBy(() -> ledger.advanceProjection(
                        expectation, ProcessState.READY_TO_CLOSE, NOW.plusSeconds(1)))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_PROJECTION_TERMINAL_TRANSITION_INVALID");
        verify(jdbc, never()).update(
                contains("update outcome_process_projection"),
                any(MapSqlParameterSource.class));
    }

    private static OutcomeOperation operation(String requestHash) {
        return operation(requestHash, RetryClass.STATUS_QUERY_REQUIRED);
    }

    private static OutcomeOperation operation(String requestHash, RetryClass retryClass) {
        return operation(
                requestHash, retryClass, "APPROVAL_1", HASH_B,
                "action-snapshot-hash-1", NOW);
    }

    private static OutcomeOperation operation(
            String requestHash,
            RetryClass retryClass,
            String approvalRecordId,
            String decisionRequestHash,
            String actionSnapshotHash,
            Instant reservedAt) {
        return new OutcomeOperation(
                "OPERATION_1",
                "PROJECTION_1",
                "TENANT_1",
                "CASE_1",
                1,
                7,
                11,
                5,
                OperationKind.OPERATION,
                1,
                "outcome.effect:CASE_1:1:1",
                requestHash,
                "PACKET_1",
                1,
                HASH_C,
                "packet-action-hash-1",
                approvalRecordId,
                "approval-hash-1",
                decisionRequestHash,
                "policy-v1",
                "ACTION_1",
                actionSnapshotHash,
                "refund-adapter",
                "v1",
                retryClass,
                "provider-key-1",
                true,
                true,
                reservedAt);
    }

    private static OutcomeOperation operationWithAuthority(
            OutcomeProcessProjection projection,
            OperationKind kind,
            long sequence,
            String actionRecordId,
            String adapterId,
            boolean requiredForClosure) {
        return new OutcomeOperation(
                "OPERATION_" + sequence,
                projection.projectionId(),
                projection.tenantSurrogate(),
                projection.caseId(),
                projection.outcomeEpoch(),
                projection.fencingToken(),
                projection.processRevision(),
                projection.outcomeRevision(),
                kind,
                sequence,
                "outcome.effect:" + projection.caseId() + ':' + sequence,
                HASH_A,
                "PACKET_1",
                1,
                HASH_C,
                "packet-action-hash-1",
                projection.decisionAuthorityReceiptId(),
                "approval-hash-1",
                projection.decisionRequestHash(),
                "policy-v1",
                actionRecordId,
                projection.approvedOperationSetHash(),
                adapterId,
                "v1",
                RetryClass.STATUS_QUERY_REQUIRED,
                "provider-key-" + sequence,
                requiredForClosure,
                false,
                NOW.plusSeconds(sequence));
    }

    private static OutcomeAttemptObservation observation(
            int sequence,
            ObservationType type,
            boolean effectMayHaveOccurred,
            boolean retryPermitted) {
        return observationAt(
                sequence, type, effectMayHaveOccurred, retryPermitted, NOW.plusSeconds(sequence));
    }

    private static OutcomeAttemptObservation observationAt(
            int sequence,
            ObservationType type,
            boolean effectMayHaveOccurred,
            boolean retryPermitted,
            Instant observedAt) {
        return new OutcomeAttemptObservation(
                "OBSERVATION_" + sequence,
                HASH_A,
                "OPERATION_1",
                "TENANT_1",
                "CASE_1",
                1,
                7,
                HASH_A,
                sequence,
                type,
                "INVOCATION_1",
                "urn:outcome:observation:" + sequence,
                HASH_B,
                effectMayHaveOccurred,
                retryPermitted,
                observedAt);
    }

    private static OutcomeOperationReceipt receipt(ReceiptAuthority authority) {
        return receiptAt(authority, NOW.plusSeconds(3));
    }

    private static OutcomeOperationReceipt receiptAt(ReceiptAuthority authority, Instant completedAt) {
        return new OutcomeOperationReceipt(
                "RECEIPT_1",
                HASH_A,
                "OPERATION_1",
                "TENANT_1",
                "CASE_1",
                1,
                7,
                HASH_A,
                ReceiptStatus.SUCCEEDED,
                authority,
                "PROVIDER_RECEIPT_1",
                "urn:outcome:receipt:1",
                HASH_C,
                ClosureDisposition.SATISFIED,
                completedAt);
    }

    private static OutcomeProcessProjection projection(Instant timestamp) {
        return projection(ProcessState.DECISION_RECORDED, timestamp);
    }

    private static OutcomeProcessProjection projection(ProcessState state, Instant timestamp) {
        return new OutcomeProcessProjection(
                "PROJECTION_1", "TENANT_1", "CASE_1", "EPOCH_1", 1,
                WriterMode.LEGACY, RuntimeMode.DISABLED, 7, 11, 5,
                "APPROVAL_1", HASH_B, "action-snapshot-hash-1", 1,
                state, timestamp, timestamp);
    }

    private static OutcomeProcessProjection syntheticProjection(long expectedRequiredCount) {
        return syntheticProjection(
                expectedRequiredCount,
                "OUTCOME_SYNTHETIC_TENANT_1",
                "OUTCOME_SYNTHETIC_CASE_1");
    }

    private static OutcomeProcessProjection syntheticProjection(
            long expectedRequiredCount, String tenantSurrogate, String caseId) {
        return new OutcomeProcessProjection(
                "PROJECTION_SYNTHETIC_1",
                tenantSurrogate,
                caseId,
                "EPOCH_SYNTHETIC_1",
                1,
                WriterMode.SHADOW,
                RuntimeMode.JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW,
                7,
                11,
                5,
                "APPROVAL_1",
                HASH_B,
                "action-snapshot-hash-1",
                expectedRequiredCount,
                ProcessState.DECISION_RECORDED,
                NOW,
                NOW);
    }

    private static OutcomeCompensationParent compensationParent(Instant createdAt) {
        return new OutcomeCompensationParent(
                "BINDING_1", HASH_C, "COMPENSATION_1", "OPERATION_1",
                "RECEIPT_1", HASH_A, "policy-v1", 1, "TENANT_1", "CASE_1",
                1, 7, createdAt);
    }

    private void stubAttemptTransition(
            OutcomeOperation operation, OutcomeAttemptObservation previous) {
        when(jdbc.query(contains("where observation_id"), anyMap(), any(RowMapper.class)))
                .thenReturn(List.of());
        when(jdbc.query(
                        contains("from outcome_operation where operation_id"),
                        anyMap(),
                        any(RowMapper.class)))
                .thenReturn(List.of(operation));
        when(jdbc.query(
                        contains("order by attempt_sequence desc limit 1"),
                        anyMap(),
                        any(RowMapper.class)))
                .thenReturn(List.of(previous));
    }

    private void stubNewReservation(OutcomeProcessProjection projection) {
        when(jdbc.query(
                        contains("from outcome_operation where tenant_surrogate"),
                        anyMap(),
                        any(RowMapper.class)))
                .thenReturn(List.of());
        when(jdbc.query(
                        contains("from outcome_process_projection where projection_id"),
                        any(MapSqlParameterSource.class),
                        any(RowMapper.class)))
                .thenReturn(List.of(projection));
    }
}
