package com.example.dispute.executor.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.executor.domain.ledger.OutcomeAttemptObservation;
import com.example.dispute.executor.domain.ledger.OutcomeAttemptObservation.ObservationType;
import com.example.dispute.executor.domain.ledger.OutcomeLedgerRejectedException;
import com.example.dispute.executor.domain.ledger.OutcomeOperation;
import com.example.dispute.executor.domain.ledger.OutcomeOperation.OperationKind;
import com.example.dispute.executor.domain.ledger.OutcomeOperation.RetryClass;
import com.example.dispute.executor.domain.ledger.OutcomeOperationLedger.OperationLookup;
import com.example.dispute.executor.domain.ledger.OutcomeOperationReceipt;
import com.example.dispute.executor.domain.ledger.OutcomeOperationReceipt.ClosureDisposition;
import com.example.dispute.executor.domain.ledger.OutcomeOperationReceipt.ReceiptAuthority;
import com.example.dispute.executor.domain.ledger.OutcomeOperationReceipt.ReceiptStatus;
import com.example.dispute.executor.infrastructure.persistence.JdbcOutcomeOperationLedger;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    private static OutcomeOperation operation(String requestHash) {
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
                "APPROVAL_1",
                "approval-hash-1",
                HASH_B,
                "policy-v1",
                "ACTION_1",
                "action-snapshot-hash-1",
                "refund-adapter",
                "v1",
                RetryClass.STATUS_QUERY_REQUIRED,
                "provider-key-1",
                true,
                true,
                NOW);
    }

    private static OutcomeAttemptObservation observation(
            int sequence,
            ObservationType type,
            boolean effectMayHaveOccurred,
            boolean retryPermitted) {
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
                NOW.plusSeconds(sequence));
    }

    private static OutcomeOperationReceipt receipt(ReceiptAuthority authority) {
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
                NOW.plusSeconds(3));
    }
}
