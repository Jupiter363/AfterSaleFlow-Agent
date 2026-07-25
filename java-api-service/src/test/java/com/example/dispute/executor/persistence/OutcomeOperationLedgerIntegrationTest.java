package com.example.dispute.executor.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.executor.domain.ledger.OutcomeAttemptObservation;
import com.example.dispute.executor.domain.ledger.OutcomeAttemptObservation.ObservationType;
import com.example.dispute.executor.domain.ledger.OutcomeClosureReadiness;
import com.example.dispute.executor.domain.ledger.OutcomeCompensationParent;
import com.example.dispute.executor.domain.ledger.OutcomeLedgerRejectedException;
import com.example.dispute.executor.domain.ledger.OutcomeOperation;
import com.example.dispute.executor.domain.ledger.OutcomeOperation.OperationKind;
import com.example.dispute.executor.domain.ledger.OutcomeOperation.RetryClass;
import com.example.dispute.executor.domain.ledger.OutcomeOperationLedger.ProjectionExpectation;
import com.example.dispute.executor.domain.ledger.OutcomeOperationReceipt;
import com.example.dispute.executor.domain.ledger.OutcomeOperationReceipt.ClosureDisposition;
import com.example.dispute.executor.domain.ledger.OutcomeOperationReceipt.ReceiptAuthority;
import com.example.dispute.executor.domain.ledger.OutcomeOperationReceipt.ReceiptStatus;
import com.example.dispute.executor.domain.ledger.OutcomeOperationState;
import com.example.dispute.executor.domain.ledger.OutcomeProcessProjection;
import com.example.dispute.executor.domain.ledger.OutcomeProcessProjection.ProcessState;
import com.example.dispute.executor.domain.ledger.OutcomeProcessProjection.RuntimeMode;
import com.example.dispute.executor.domain.ledger.OutcomeProcessProjection.WriterMode;
import com.example.dispute.executor.infrastructure.persistence.JdbcOutcomeOperationLedger;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** PostgreSQL proof for V045 Outcome operation fencing, recovery, and compensation facts. */
@Testcontainers
class OutcomeOperationLedgerIntegrationTest {

    private static final String DB = "outcome_operation_ledger";
    private static final String USER = "dispute_test";
    private static final String PASSWORD = "local_test_password";
    private static final String TENANT = "tenant-outcome-ledger";
    private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");
    private static final String REQUEST_HASH = "a".repeat(64);
    private static final String RECEIPT_HASH = "b".repeat(64);
    private static final String PACKET_CONTENT_HASH = "c".repeat(64);
    private static final String DECISION_REQUEST_HASH = "d".repeat(64);
    private static final String ACTION_HASH = "e".repeat(64);
    private static final String RESPONSE_HASH = "f".repeat(64);
    private static final String APPROVAL_HASH = "approval-ledger-hash-v1";
    private static final String POLICY_VERSION = "outcome-policy.v1";

    @Container
    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
                    DockerImageName.parse("public.ecr.aws/docker/library/postgres:16-alpine"))
            .withEnv("POSTGRES_DB", DB)
            .withEnv("POSTGRES_USER", USER)
            .withEnv("POSTGRES_PASSWORD", PASSWORD)
            .withExposedPorts(5432)
            .waitingFor(Wait.forListeningPort());

    private static JdbcTemplate jdbc;
    private static JdbcOutcomeOperationLedger ledger;
    private static DataSource dataSource;

    @BeforeAll
    static void migrateDatabase() {
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ':'
                + POSTGRES.getMappedPort(5432) + '/' + DB;
        Flyway.configure()
                .dataSource(url, USER, PASSWORD)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        dataSource = new DriverManagerDataSource(url, USER, PASSWORD);
        jdbc = new JdbcTemplate(dataSource);
        ledger = ledger(dataSource);
    }

    @Test
    void migrationCreatesTheProjectionLedgersAndDerivedReadModels() {
        assertThat(tableExists("outcome_process_projection")).isTrue();
        assertThat(tableExists("outcome_operation")).isTrue();
        assertThat(tableExists("outcome_operation_attempt_observation")).isTrue();
        assertThat(tableExists("outcome_operation_receipt")).isTrue();
        assertThat(tableExists("outcome_compensation_parent_binding")).isTrue();
        assertThat(viewExists("outcome_operation_state")).isTrue();
        assertThat(viewExists("outcome_closure_readiness")).isTrue();
        assertThat(number("select max(installed_rank) from flyway_schema_history where success"))
                .isGreaterThanOrEqualTo(53);
    }

    @Test
    void reservationIsAtomicIdempotentAndRejectsHashOrFenceSubstitution() {
        Fixture fixture = insertFixture("RESERVE");
        OutcomeOperation command = operation(fixture, "reserve", REQUEST_HASH, 1, OperationKind.OPERATION, true);

        OutcomeOperation first = ledger.reserve(command, null);
        OutcomeOperation replay = ledger.reserve(command, null);

        assertThat(replay).isEqualTo(first);
        assertThat(number(
                        "select count(*) from outcome_operation where projection_id = ?",
                        fixture.projectionId()))
                .isEqualTo(1);

        assertThatThrownBy(() -> ledger.reserve(
                        operation(fixture, "reserve", "1".repeat(64), 1, OperationKind.OPERATION, true),
                        null))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_IDEMPOTENCY_CONFLICT");

        OutcomeOperation stale = new OutcomeOperation(
                "OP_STALE_" + fixture.suffix(),
                fixture.projectionId(),
                TENANT,
                fixture.caseId(),
                1,
                8,
                1,
                0,
                OperationKind.OPERATION,
                2,
                "outcome.effect:" + fixture.caseId() + ":stale",
                "2".repeat(64),
                fixture.packetId(),
                1,
                PACKET_CONTENT_HASH,
                ACTION_HASH,
                fixture.approvalId(),
                APPROVAL_HASH + '-' + fixture.suffix(),
                DECISION_REQUEST_HASH,
                POLICY_VERSION,
                null,
                ACTION_HASH,
                "refund-adapter",
                "v1",
                RetryClass.STATUS_QUERY_REQUIRED,
                "provider-key:" + fixture.caseId() + ":stale",
                true,
                true,
                NOW);
        assertThatThrownBy(() -> ledger.reserve(stale, null))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_STALE_PROJECTION");
        assertThat(number(
                        "select count(*) from outcome_operation where projection_id = ?",
                        fixture.projectionId()))
                .isEqualTo(1);
    }

    @Test
    void reservationRejectsAnotherValidSameCaseApprovalOutsideProjectionAuthority() {
        Fixture fixture = insertFixture("PROJECTION_AUTHORITY");
        String otherApprovalId = "APPROVAL_OTHER_" + fixture.suffix();
        String otherApprovalHash = APPROVAL_HASH + "-OTHER-" + fixture.suffix();
        cloneApproval(fixture.approvalId(), otherApprovalId, otherApprovalHash);
        OutcomeOperation substituted = operation(
                fixture, "authority-substitution", REQUEST_HASH, 1,
                OperationKind.OPERATION, true, true, RetryClass.STATUS_QUERY_REQUIRED,
                0, 0, otherApprovalId, otherApprovalHash,
                DECISION_REQUEST_HASH, ACTION_HASH, NOW);

        assertThatThrownBy(() -> ledger.reserve(substituted, null))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_OPERATION_PROJECTION_AUTHORITY_CONFLICT");
        assertThat(number(
                        "select count(*) from human_review_record where id = ?",
                        otherApprovalId))
                .isEqualTo(1);
        assertThat(number(
                        "select count(*) from outcome_operation where projection_id = ?",
                        fixture.projectionId()))
                .isZero();
    }

    @Test
    void formalRequiredReservationRejectsNullMissingForgedAndDuplicateActionAuthority() {
        Fixture nullFixture = insertFixture("ACTION_NULL");
        OutcomeOperation nullAction = withActionRecordId(
                operation(
                        nullFixture,
                        "null-action",
                        REQUEST_HASH,
                        1,
                        OperationKind.OPERATION,
                        true),
                null);
        assertThatThrownBy(() -> ledger.reserve(nullAction, null))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_REQUIRED_ACTION_AUTHORITY_INVALID");

        Fixture missingFixture = insertFixture("ACTION_MISSING");
        jdbc.update(
                "delete from action_record where id = ?",
                actionRecordId(missingFixture.suffix(), 1));
        assertActionAuthorityRejected(operation(
                missingFixture,
                "missing-action",
                REQUEST_HASH,
                1,
                OperationKind.OPERATION,
                true));

        Fixture typeFixture = insertFixture("ACTION_TYPE");
        jdbc.update(
                "update action_record set action_type = 'UNAPPROVED' where id = ?",
                actionRecordId(typeFixture.suffix(), 1));
        assertActionAuthorityRejected(operation(
                typeFixture,
                "forged-type",
                REQUEST_HASH,
                1,
                OperationKind.OPERATION,
                true));

        Fixture keyFixture = insertFixture("ACTION_KEY");
        jdbc.update(
                "update action_record set idempotency_key = ? where id = ?",
                "UNAPPROVED:" + keyFixture.caseId(),
                actionRecordId(keyFixture.suffix(), 1));
        assertActionAuthorityRejected(operation(
                keyFixture,
                "forged-key",
                REQUEST_HASH,
                1,
                OperationKind.OPERATION,
                true));

        Fixture duplicateFixture = insertFixture("ACTION_DUPLICATE", 2);
        OutcomeOperation first = operation(
                duplicateFixture,
                "duplicate-first",
                REQUEST_HASH,
                1,
                OperationKind.OPERATION,
                true);
        OutcomeOperation duplicate = withActionRecordId(
                operation(
                        duplicateFixture,
                        "duplicate-second",
                        "1".repeat(64),
                        2,
                        OperationKind.OPERATION,
                        true),
                first.actionRecordId());
        ledger.reserve(first, null);
        assertThatThrownBy(() -> ledger.reserve(duplicate, null))
                .isInstanceOf(DataAccessException.class);
        assertThat(number(
                        "select count(*) from outcome_operation where projection_id = ?",
                        duplicateFixture.projectionId()))
                .isEqualTo(1);
    }

    @Test
    void notificationReservationUsesTheToolExecutorCanonicalIdentity() {
        String notification = "EMAIL_CUSTOMER";
        Fixture fixture = insertFixture("ACTION_NOTIFICATION", 0, List.of(notification));
        OutcomeOperation operation = operation(
                fixture,
                "notification",
                REQUEST_HASH,
                1,
                OperationKind.OPERATION,
                false);

        assertThat(ledger.reserve(operation, null)).isEqualTo(operation);
        assertThat(jdbc.queryForObject(
                        "select idempotency_key from action_record where id = ?",
                        String.class,
                        operation.actionRecordId()))
                .isEqualTo(approvedNotificationKey(
                        fixture.caseId(), 1, 0, notification));
    }

    @Test
    void closureRejectsRunningAndFailedFormalActionRecords() {
        for (String status : List.of("RUNNING", "FAILED")) {
            Fixture fixture = insertFixture("ACTION_STATUS_" + status);
            OutcomeOperation operation = operation(
                    fixture,
                    "status-" + status.toLowerCase(),
                    REQUEST_HASH,
                    1,
                    OperationKind.OPERATION,
                    false);
            jdbc.update(
                    "update action_record set execution_status = ? where id = ?",
                    status,
                    operation.actionRecordId());
            ledger.reserve(operation, null);
            ledger.recordReceipt(receipt(
                    operation,
                    "RECEIPT_ACTION_STATUS_" + status,
                    RECEIPT_HASH,
                    7,
                    ReceiptAuthority.DIRECT_RESPONSE));

            assertThatThrownBy(() -> ledger.advanceProjection(
                            expectation(fixture),
                            ProcessState.READY_TO_CLOSE,
                            NOW.plusSeconds(20)))
                    .isInstanceOf(OutcomeLedgerRejectedException.class)
                    .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                    .isEqualTo("OUTCOME_REQUIRED_ACTION_NOT_SUCCEEDED");
        }
    }

    @Test
    void compensationBarrierDoesNotRequireTheOriginalActionRecordToRemainSucceeded() {
        Fixture fixture = insertFixture("COMPENSATION_ACTION_STATUS");
        OutcomeOperation original = operation(
                fixture,
                "original",
                REQUEST_HASH,
                1,
                OperationKind.OPERATION,
                true);
        ledger.reserve(original, null);
        OutcomeOperationReceipt originalReceipt = receipt(
                original,
                "RECEIPT_COMPENSATION_ACTION_STATUS",
                RECEIPT_HASH,
                7,
                ReceiptAuthority.DIRECT_RESPONSE);
        ledger.recordReceipt(originalReceipt);
        jdbc.update(
                "update action_record set execution_status = 'FAILED' where id = ?",
                original.actionRecordId());

        OutcomeOperation compensation = operation(
                fixture,
                "compensation",
                "1".repeat(64),
                2,
                OperationKind.COMPENSATION,
                false);
        OutcomeCompensationParent parent = compensationParent(
                fixture,
                compensation,
                original,
                originalReceipt,
                1,
                "ACTION_STATUS");

        assertThat(ledger.reserve(compensation, parent)).isEqualTo(compensation);
    }

    @Test
    void postgresTimestampPrecisionIsCanonicalAcrossPersistedExactReplays() {
        Fixture fixture = insertFixture("TIMESTAMP_REPLAY");
        Instant first = Instant.parse("2026-07-24T12:00:00.123456789Z");
        Instant replay = Instant.parse("2026-07-24T12:00:00.123456001Z");
        Instant canonical = Instant.parse("2026-07-24T12:00:00.123456Z");
        OutcomeOperation original = operation(
                fixture, "timestamp-original", REQUEST_HASH, 1,
                OperationKind.OPERATION, true, true, RetryClass.STATUS_QUERY_REQUIRED,
                0, 0, fixture.approvalId(), APPROVAL_HASH + '-' + fixture.suffix(),
                DECISION_REQUEST_HASH, ACTION_HASH, first);
        OutcomeOperation replayOperation = withReservedAt(original, replay);
        assertThat(ledger.reserve(original, null)).isEqualTo(replayOperation);
        assertThat(ledger.reserve(replayOperation, null)).isEqualTo(original);

        OutcomeAttemptObservation attempt = observationAt(
                original, 1, ObservationType.INVOCATION_DISPATCHED, true, false, first);
        OutcomeAttemptObservation replayAttempt = observationAt(
                original, 1, ObservationType.INVOCATION_DISPATCHED, true, false, replay);
        assertThat(ledger.appendAttempt(attempt)).isEqualTo(replayAttempt);
        assertThat(ledger.appendAttempt(replayAttempt)).isEqualTo(attempt);

        OutcomeOperationReceipt terminal = receiptAt(
                original, "RECEIPT_TIMESTAMP", RECEIPT_HASH,
                ReceiptAuthority.DIRECT_RESPONSE, first);
        OutcomeOperationReceipt replayTerminal = receiptAt(
                original, "RECEIPT_TIMESTAMP", RECEIPT_HASH,
                ReceiptAuthority.DIRECT_RESPONSE, replay);
        assertThat(ledger.recordReceipt(terminal)).isEqualTo(replayTerminal);
        assertThat(ledger.recordReceipt(replayTerminal)).isEqualTo(terminal);

        OutcomeOperation compensation = operation(
                fixture, "timestamp-compensation", "1".repeat(64), 2,
                OperationKind.COMPENSATION, false, true, RetryClass.STATUS_QUERY_REQUIRED,
                0, 0, fixture.approvalId(), APPROVAL_HASH + '-' + fixture.suffix(),
                DECISION_REQUEST_HASH, ACTION_HASH, first);
        OutcomeCompensationParent parent = compensationParentAt(
                fixture, compensation, original, terminal, 1, "TIMESTAMP", first);
        OutcomeOperation replayCompensation = withReservedAt(compensation, replay);
        OutcomeCompensationParent replayParent = compensationParentAt(
                fixture, replayCompensation, original, replayTerminal, 1, "TIMESTAMP", replay);
        assertThat(ledger.reserve(compensation, parent)).isEqualTo(replayCompensation);
        assertThat(ledger.reserve(replayCompensation, replayParent)).isEqualTo(compensation);

        assertThat(original.reservedAt()).isEqualTo(canonical);
        assertThat(attempt.observedAt()).isEqualTo(canonical);
        assertThat(terminal.completedAt()).isEqualTo(canonical);
        assertThat(parent.createdAt()).isEqualTo(canonical);
        assertThat(jdbc.queryForObject(
                                "select reserved_at from outcome_operation where operation_id = ?",
                                OffsetDateTime.class,
                                original.operationId())
                        .toInstant())
                .isEqualTo(canonical);
    }

    @Test
    void redispatchRequiresPreviousPermissionAndCompatibleRetryClass() {
        Fixture blindFixture = insertFixture("RETRY_BLIND");
        OutcomeOperation blind = operation(
                blindFixture, "blind", REQUEST_HASH, 1, OperationKind.OPERATION,
                true, true, RetryClass.IDEMPOTENT_PROVIDER);
        ledger.reserve(blind, null);
        ledger.appendAttempt(observation(
                blind, 1, ObservationType.INVOCATION_DISPATCHED, true, false));
        assertThatThrownBy(() -> ledger.appendAttempt(observation(
                        blind, 2, ObservationType.INVOCATION_DISPATCHED, true, false)))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_RETRY_NOT_PERMITTED");

        Fixture classFixture = insertFixture("RETRY_CLASS");
        OutcomeOperation nonRetryable = operation(
                classFixture, "non-retryable", REQUEST_HASH, 1, OperationKind.OPERATION,
                true, true, RetryClass.NON_RETRYABLE);
        ledger.reserve(nonRetryable, null);
        assertThatThrownBy(() -> ledger.appendAttempt(observation(
                        nonRetryable, 1, ObservationType.PRE_EFFECT_RETRYABLE_FAILURE, false, true)))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_RETRY_CLASS_FORBIDDEN");
        assertThat(number(
                        "select count(*) from outcome_operation_attempt_observation where operation_id = ?",
                        nonRetryable.operationId()))
                .isZero();

        Fixture boundedFixture = insertFixture("RETRY_BOUNDED");
        OutcomeOperation bounded = operation(
                boundedFixture, "bounded", REQUEST_HASH, 1, OperationKind.OPERATION,
                true, true, RetryClass.BOUNDED_PRE_EFFECT);
        ledger.reserve(bounded, null);
        ledger.appendAttempt(observation(
                bounded, 1, ObservationType.PRE_EFFECT_RETRYABLE_FAILURE, false, true));
        ledger.appendAttempt(observation(
                bounded, 2, ObservationType.INVOCATION_DISPATCHED, true, false));
        assertThat(number(
                        "select count(*) from outcome_operation_attempt_observation where operation_id = ?",
                        bounded.operationId()))
                .isEqualTo(2);
    }

    @Test
    void ambiguousAttemptMustReconcileAndCannotBlindlyRetry() {
        Fixture fixture = insertFixture("AMBIGUOUS");
        OutcomeOperation operation = operation(
                fixture, "ambiguous", REQUEST_HASH, 1, OperationKind.OPERATION, true);
        ledger.reserve(operation, null);
        ledger.appendAttempt(observation(
                operation, 1, ObservationType.INVOCATION_DISPATCHED, true, false));
        ledger.appendAttempt(observation(operation, 2, ObservationType.AMBIGUOUS, true, false));

        assertThatThrownBy(() -> ledger.appendAttempt(observation(
                        operation, 3, ObservationType.INVOCATION_DISPATCHED, true, false)))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_AMBIGUOUS_RECONCILIATION_REQUIRED");
        assertThat(number(
                        "select count(*) from outcome_operation_attempt_observation where operation_id = ?",
                        operation.operationId()))
                .isEqualTo(2);

        ledger.appendAttempt(observation(operation, 3, ObservationType.RECONCILING, true, false));
        OutcomeOperationState reconciling = ledger.readOperationStates(expectation(fixture)).getFirst();
        assertThat(reconciling.status()).isEqualTo(OutcomeOperationState.Status.RECONCILING);
        assertThat(reconciling.javaAuthoritative()).isFalse();

        OutcomeOperationReceipt receipt = receipt(
                operation, "RECEIPT_AMBIGUOUS", RECEIPT_HASH, 7, ReceiptAuthority.PROVIDER_STATUS_QUERY);
        ledger.recordReceipt(receipt);
        OutcomeOperationState resolved = ledger.readOperationStates(expectation(fixture)).getFirst();
        assertThat(resolved.status()).isEqualTo(OutcomeOperationState.Status.SUCCEEDED);
        assertThat(resolved.javaAuthoritative()).isTrue();
        assertThat(resolved.receiptHash()).isEqualTo(RECEIPT_HASH);
    }

    @Test
    void terminalReceiptAndAmbiguousAttemptCannotBothCommitAcrossTwoConnections()
            throws Exception {
        Fixture fixture = insertFixture("LIFECYCLE_RACE");
        OutcomeOperation operation = operation(
                fixture, "lifecycle-race", REQUEST_HASH, 1, OperationKind.OPERATION, true);
        ledger.reserve(operation, null);
        OutcomeAttemptObservation ambiguous =
                observation(operation, 1, ObservationType.AMBIGUOUS, true, false);
        OutcomeOperationReceipt terminal = receipt(
                operation,
                "RECEIPT_LIFECYCLE_RACE",
                RECEIPT_HASH,
                7,
                ReceiptAuthority.DIRECT_RESPONSE);
        JdbcOutcomeOperationLedger attemptConnection = ledger(dataSource);
        JdbcOutcomeOperationLedger receiptConnection = ledger(dataSource);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> attemptResult = executor.submit(
                    () -> raceCall(ready, start, () -> attemptConnection.appendAttempt(ambiguous)));
            Future<Throwable> receiptResult = executor.submit(
                    () -> raceCall(ready, start, () -> receiptConnection.recordReceipt(terminal)));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Throwable attemptFailure = attemptResult.get(10, TimeUnit.SECONDS);
            Throwable receiptFailure = receiptResult.get(10, TimeUnit.SECONDS);
            int failureCount = (attemptFailure == null ? 0 : 1) + (receiptFailure == null ? 0 : 1);
            assertThat(failureCount).isEqualTo(1);
            assertIntendedLifecycleRaceLoser(
                    attemptFailure == null ? receiptFailure : attemptFailure);
            assertThat(number(
                            "select count(*) from outcome_operation_attempt_observation where operation_id = ?",
                            operation.operationId())
                            + number(
                                    "select count(*) from outcome_operation_receipt where operation_id = ?",
                                    operation.operationId()))
                    .isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void terminalReceiptRequiresExactOperationHashAndFenceAndIsImmutable() {
        Fixture fixture = insertFixture("RECEIPT");
        OutcomeOperation operation = operation(
                fixture, "receipt", REQUEST_HASH, 1, OperationKind.OPERATION, true);
        ledger.reserve(operation, null);

        OutcomeOperationReceipt staleFence = receipt(
                operation, "RECEIPT_STALE", RECEIPT_HASH, 8, ReceiptAuthority.DIRECT_RESPONSE);
        assertThatThrownBy(() -> ledger.recordReceipt(staleFence))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_RECEIPT_BINDING_CONFLICT");
        assertThat(number(
                        "select count(*) from outcome_operation_receipt where operation_id = ?",
                        operation.operationId()))
                .isZero();

        OutcomeOperationReceipt committed = receipt(
                operation, "RECEIPT_EXACT", RECEIPT_HASH, 7, ReceiptAuthority.DIRECT_RESPONSE);
        assertThat(ledger.recordReceipt(committed)).isEqualTo(committed);
        assertThat(ledger.recordReceipt(committed)).isEqualTo(committed);
        OutcomeOperationReceipt substituted = receipt(
                operation,
                "RECEIPT_SUBSTITUTED",
                "9".repeat(64),
                7,
                ReceiptAuthority.DIRECT_RESPONSE);
        assertThatThrownBy(() -> ledger.recordReceipt(substituted))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_RECEIPT_CONFLICT");

        assertThatThrownBy(() -> jdbc.update(
                        "update outcome_operation_receipt set receipt_hash = ? where operation_id = ?",
                        "8".repeat(64),
                        operation.operationId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void closureRequiresTheImmutableExpectedCountAndEveryRequiredTerminalSuccess() {
        Fixture empty = insertFixture("CLOSURE_EMPTY", 0);
        OutcomeClosureReadiness zeroExpected = ledger.closureReadiness(expectation(empty));
        assertThat(zeroExpected.expectedRequiredOperationCount()).isZero();
        assertThat(zeroExpected.requiredOperationCount()).isZero();
        assertThat(zeroExpected.closureReady()).isTrue();

        Fixture fixture = insertFixture("CLOSURE_COUNT", 2);
        OutcomeClosureReadiness noReservations = ledger.closureReadiness(expectation(fixture));
        assertThat(noReservations.expectedRequiredOperationCount()).isEqualTo(2);
        assertThat(noReservations.requiredOperationCount()).isZero();
        assertThat(noReservations.closureReady()).isFalse();

        OutcomeOperation first = operation(
                fixture, "closure-first", REQUEST_HASH, 1, OperationKind.OPERATION, true);
        ledger.reserve(first, null);
        ledger.recordReceipt(receipt(
                first, "RECEIPT_CLOSURE_FIRST", RECEIPT_HASH, 7, ReceiptAuthority.DIRECT_RESPONSE));
        assertThat(ledger.closureReadiness(expectation(fixture)).closureReady()).isFalse();

        OutcomeOperation second = operation(
                fixture, "closure-second", "1".repeat(64), 2, OperationKind.OPERATION, true);
        ledger.reserve(second, null);
        ledger.recordReceipt(receipt(
                second,
                "RECEIPT_CLOSURE_SECOND",
                "2".repeat(64),
                7,
                ReceiptAuthority.DIRECT_RESPONSE));
        OutcomeClosureReadiness complete = ledger.closureReadiness(expectation(fixture));
        assertThat(complete.requiredOperationCount()).isEqualTo(2);
        assertThat(complete.unresolvedOperationCount()).isZero();
        assertThat(complete.blockedOperationCount()).isZero();
        assertThat(complete.closureReady()).isTrue();

        assertThatThrownBy(() -> jdbc.update(
                        "update outcome_process_projection set expected_required_operation_count = 1 where projection_id = ?",
                        fixture.projectionId()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void failedManualRecoveryAmbiguousReconcilingAndMissingReceiptsBlockClosure() {
        Fixture fixture = insertFixture("CLOSURE_BLOCKERS", 5);
        OutcomeOperation missing = operation(
                fixture, "missing", REQUEST_HASH, 1, OperationKind.OPERATION, true);
        OutcomeOperation ambiguous = operation(
                fixture, "ambiguous", "1".repeat(64), 2, OperationKind.OPERATION, true);
        OutcomeOperation reconciling = operation(
                fixture, "reconciling", "2".repeat(64), 3, OperationKind.OPERATION, true);
        OutcomeOperation failed = operation(
                fixture, "failed", "3".repeat(64), 4, OperationKind.OPERATION, true);
        OutcomeOperation manual = operation(
                fixture, "manual", "4".repeat(64), 5, OperationKind.OPERATION, true);
        for (OutcomeOperation operation : List.of(missing, ambiguous, reconciling, failed, manual)) {
            ledger.reserve(operation, null);
        }
        ledger.appendAttempt(observation(ambiguous, 1, ObservationType.AMBIGUOUS, true, false));
        ledger.appendAttempt(observation(reconciling, 1, ObservationType.AMBIGUOUS, true, false));
        ledger.appendAttempt(observation(reconciling, 2, ObservationType.RECONCILING, true, false));
        ledger.recordReceipt(receipt(
                failed,
                "RECEIPT_FAILED",
                "5".repeat(64),
                ReceiptStatus.FAILED,
                ClosureDisposition.BLOCKED));
        ledger.recordReceipt(receipt(
                manual,
                "RECEIPT_MANUAL",
                "6".repeat(64),
                ReceiptStatus.FAILED,
                ClosureDisposition.MANUAL_RECOVERY));

        OutcomeClosureReadiness readiness = ledger.closureReadiness(expectation(fixture));
        assertThat(readiness.closureReady()).isFalse();
        assertThat(readiness.unresolvedOperationCount()).isEqualTo(3);
        assertThat(readiness.reconciliationOperationCount()).isEqualTo(2);
        assertThat(readiness.blockedOperationCount()).isEqualTo(2);
        assertThat(ledger.readOperationStates(expectation(fixture)))
                .extracting(OutcomeOperationState::status)
                .containsExactly(
                        OutcomeOperationState.Status.RESERVED,
                        OutcomeOperationState.Status.AMBIGUOUS,
                        OutcomeOperationState.Status.RECONCILING,
                        OutcomeOperationState.Status.FAILED,
                        OutcomeOperationState.Status.MANUAL_RECOVERY);
    }

    @Test
    void compensationHasAnAppendOnlyReverseParentAndBlocksClosureUntilTerminal() {
        Fixture fixture = insertFixture("COMPENSATE");
        OutcomeOperation original = operation(
                fixture, "original", REQUEST_HASH, 1, OperationKind.OPERATION, true);
        ledger.reserve(original, null);
        OutcomeOperationReceipt originalReceipt = receipt(
                original,
                "RECEIPT_ORIGINAL",
                RECEIPT_HASH,
                7,
                ReceiptAuthority.DIRECT_RESPONSE);
        ledger.recordReceipt(originalReceipt);
        assertThat(ledger.closureReadiness(expectation(fixture)).closureReady()).isTrue();

        OutcomeOperation compensation = operation(
                fixture,
                "compensation",
                "1".repeat(64),
                2,
                OperationKind.COMPENSATION,
                false);
        OutcomeCompensationParent parent = new OutcomeCompensationParent(
                "COMP_PARENT_" + fixture.suffix(),
                "2".repeat(64),
                compensation.operationId(),
                original.operationId(),
                originalReceipt.receiptId(),
                originalReceipt.receiptHash(),
                "compensation-policy.v1",
                1,
                TENANT,
                fixture.caseId(),
                1,
                7,
                NOW.plusSeconds(3));
        ledger.reserve(compensation, parent);

        List<OutcomeCompensationParent> parents =
                ledger.findCompensationParents(expectation(fixture));
        assertThat(parents).containsExactly(parent);
        OutcomeOperationState compensationState = ledger.readOperationStates(expectation(fixture)).get(1);
        assertThat(compensationState.operationKind()).isEqualTo(OperationKind.COMPENSATION);
        assertThat(compensationState.parentOperationId()).isEqualTo(original.operationId());
        assertThat(compensationState.parentReceiptHash()).isEqualTo(originalReceipt.receiptHash());
        assertThat(compensationState.reverseOrder()).isEqualTo(1);

        OutcomeClosureReadiness blocked = ledger.closureReadiness(expectation(fixture));
        assertThat(blocked.closureReady()).isFalse();
        assertThat(blocked.pendingCompensationCount()).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(
                        "update outcome_compensation_parent_binding set parent_receipt_hash = ? where child_operation_id = ?",
                        "3".repeat(64),
                        compensation.operationId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        ledger.recordReceipt(receipt(
                compensation,
                "RECEIPT_COMPENSATION",
                "4".repeat(64),
                7,
                ReceiptAuthority.DIRECT_RESPONSE));
        OutcomeClosureReadiness ready = ledger.closureReadiness(expectation(fixture));
        assertThat(ready.closureReady()).isTrue();
        assertThat(ready.unresolvedOperationCount()).isZero();
        assertThat(ready.blockedOperationCount()).isZero();
        assertThat(ready.pendingCompensationCount()).isZero();
    }

    @Test
    void compensationBarrierRejectsEarlyReservationAndBindsLateHigherSequenceSuccess() {
        Fixture fixture = insertFixture("COMPENSATION_BARRIER", 2);
        OutcomeOperation first = operation(
                fixture, "barrier-first", REQUEST_HASH, 1, OperationKind.OPERATION, true);
        OutcomeOperation second = operation(
                fixture, "barrier-second", "1".repeat(64), 2, OperationKind.OPERATION, true);
        OutcomeOperation optional = operation(
                fixture, "barrier-optional", "2".repeat(64), 3, OperationKind.OPERATION, true, false);
        ledger.reserve(first, null);
        ledger.reserve(second, null);
        ledger.reserve(optional, null);
        OutcomeOperationReceipt firstReceipt = receipt(
                first, "RECEIPT_BARRIER_FIRST", RECEIPT_HASH, 7, ReceiptAuthority.DIRECT_RESPONSE);
        OutcomeOperationReceipt secondReceipt = receipt(
                second, "RECEIPT_BARRIER_SECOND", "3".repeat(64), 7, ReceiptAuthority.DIRECT_RESPONSE);
        ledger.recordReceipt(firstReceipt);

        OutcomeOperation compensation = operation(
                fixture, "barrier-compensation", "4".repeat(64), 4, OperationKind.COMPENSATION, false);
        OutcomeCompensationParent firstCandidate = compensationParent(
                fixture, compensation, first, firstReceipt, 1, "EARLY");
        assertThatThrownBy(() -> ledger.reserve(compensation, firstCandidate))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_COMPENSATION_BARRIER_UNRESOLVED");

        ledger.recordReceipt(secondReceipt);
        OutcomeCompensationParent frozenParent = compensationParent(
                fixture, compensation, second, secondReceipt, 1, "FROZEN");
        ledger.reserve(compensation, frozenParent);

        assertThat(ledger.findCompensationParents(expectation(fixture)))
                .extracting(OutcomeCompensationParent::parentOperationId)
                .containsExactly(second.operationId());
        assertThat(ledger.recordReceipt(secondReceipt)).isEqualTo(secondReceipt);
        assertThatThrownBy(() -> ledger.recordReceipt(receipt(
                        optional,
                        "RECEIPT_BARRIER_OPTIONAL_LATE",
                        "5".repeat(64),
                        7,
                        ReceiptAuthority.DIRECT_RESPONSE)))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_ORIGINAL_RECEIPT_AFTER_COMPENSATION");
    }

    @Test
    void compensationBarrierAndLateReceiptRaceCannotDeadlockOrChangeFrozenParent() throws Exception {
        Fixture fixture = insertFixture("COMPENSATION_BARRIER_RACE", 2);
        OutcomeOperation first = operation(
                fixture, "race-first", REQUEST_HASH, 1, OperationKind.OPERATION, true);
        OutcomeOperation second = operation(
                fixture, "race-second", "1".repeat(64), 2, OperationKind.OPERATION, true);
        ledger.reserve(first, null);
        ledger.reserve(second, null);
        OutcomeOperationReceipt firstReceipt = receipt(
                first, "RECEIPT_RACE_FIRST", RECEIPT_HASH, 7, ReceiptAuthority.DIRECT_RESPONSE);
        OutcomeOperationReceipt secondReceipt = receipt(
                second, "RECEIPT_RACE_SECOND", "2".repeat(64), 7, ReceiptAuthority.DIRECT_RESPONSE);
        ledger.recordReceipt(firstReceipt);

        OutcomeOperation compensation = operation(
                fixture, "race-compensation", "3".repeat(64), 3, OperationKind.COMPENSATION, false);
        OutcomeCompensationParent secondParent = compensationParent(
                fixture, compensation, second, secondReceipt, 1, "RACE");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> compensationResult = executor.submit(() -> raceCall(
                    ready, start, () -> ledger.reserve(compensation, secondParent)));
            Future<Throwable> receiptResult = executor.submit(() -> raceCall(
                    ready, start, () -> ledger.recordReceipt(secondReceipt)));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            Throwable compensationFailure = compensationResult.get(10, TimeUnit.SECONDS);
            Throwable receiptFailure = receiptResult.get(10, TimeUnit.SECONDS);
            assertThat(receiptFailure).isNull();
            if (compensationFailure != null) {
                assertThat(compensationFailure)
                        .isInstanceOf(OutcomeLedgerRejectedException.class)
                        .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                        .isEqualTo("OUTCOME_COMPENSATION_BARRIER_UNRESOLVED");
                ledger.reserve(compensation, secondParent);
            }
            assertThat(ledger.findCompensationParents(expectation(fixture)))
                    .extracting(OutcomeCompensationParent::parentOperationId)
                    .containsExactly(second.operationId());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void closureReadinessCommitAndCompensationAdmissionShareOneAtomicScopeBarrier()
            throws Exception {
        Fixture fixture = insertFixture("CLOSURE_COMPENSATION_RACE");
        OutcomeOperation original = operation(
                fixture, "race-original", REQUEST_HASH, 1, OperationKind.OPERATION, true);
        ledger.reserve(original, null);
        OutcomeOperationReceipt originalReceipt = receipt(
                original, "RECEIPT_CLOSURE_RACE", RECEIPT_HASH,
                7, ReceiptAuthority.DIRECT_RESPONSE);
        ledger.recordReceipt(originalReceipt);
        OutcomeOperation compensation = operation(
                fixture, "race-compensation", "1".repeat(64),
                2, OperationKind.COMPENSATION, false);
        OutcomeCompensationParent parent = compensationParent(
                fixture, compensation, original, originalReceipt, 1, "CLOSURE_RACE");
        JdbcOutcomeOperationLedger closureConnection = ledger(dataSource);
        JdbcOutcomeOperationLedger compensationConnection = ledger(dataSource);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> closureResult = executor.submit(() -> raceCall(
                    ready, start, () -> closureConnection.advanceProjection(
                            expectation(fixture), ProcessState.READY_TO_CLOSE, NOW.plusSeconds(30))));
            Future<Throwable> compensationResult = executor.submit(() -> raceCall(
                    ready, start, () -> compensationConnection.reserve(compensation, parent)));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Throwable closureFailure = closureResult.get(10, TimeUnit.SECONDS);
            Throwable compensationFailure = compensationResult.get(10, TimeUnit.SECONDS);
            assertThat((closureFailure == null ? 0 : 1) + (compensationFailure == null ? 0 : 1))
                    .isEqualTo(1);
            assertIntendedClosureCompensationRaceLoser(
                    closureFailure == null ? compensationFailure : closureFailure);
            String state = jdbc.queryForObject(
                    "select process_state from outcome_process_projection where projection_id = ?",
                    String.class,
                    fixture.projectionId());
            long compensationCount = number(
                    "select count(*) from outcome_operation where operation_id = ?",
                    compensation.operationId());
            assertThat(state.equals(ProcessState.READY_TO_CLOSE.name()) && compensationCount == 1)
                    .isFalse();
            assertThat(compensationCount)
                    .isEqualTo(state.equals(ProcessState.READY_TO_CLOSE.name()) ? 0 : 1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void committedReadinessRejectsCompensationEvenWithTheCurrentProjectionFence() {
        Fixture fixture = insertFixture("CLOSURE_COMMITTED");
        OutcomeOperation original = operation(
                fixture, "committed-original", REQUEST_HASH, 1, OperationKind.OPERATION, true);
        ledger.reserve(original, null);
        OutcomeOperationReceipt originalReceipt = receipt(
                original, "RECEIPT_CLOSURE_COMMITTED", RECEIPT_HASH,
                7, ReceiptAuthority.DIRECT_RESPONSE);
        ledger.recordReceipt(originalReceipt);
        ledger.advanceProjection(
                expectation(fixture), ProcessState.READY_TO_CLOSE, NOW.plusSeconds(30));

        OutcomeOperation compensation = operation(
                fixture, "committed-compensation", "1".repeat(64), 2,
                OperationKind.COMPENSATION, false, true, RetryClass.STATUS_QUERY_REQUIRED,
                1, 1, fixture.approvalId(), APPROVAL_HASH + '-' + fixture.suffix(),
                DECISION_REQUEST_HASH, ACTION_HASH, NOW.plusSeconds(31));
        OutcomeCompensationParent parent = compensationParent(
                fixture, compensation, original, originalReceipt, 1, "COMMITTED");

        assertThatThrownBy(() -> ledger.reserve(compensation, parent))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_RESERVATION_AFTER_CLOSURE_FORBIDDEN");
        assertThat(number(
                        "select count(*) from outcome_operation where operation_id = ?",
                        compensation.operationId()))
                .isZero();

        OutcomeProcessProjection closed = ledger.advanceProjection(
                expectation(fixture, 1), ProcessState.CLOSED, NOW.plusSeconds(32));
        assertThat(closed.processState()).isEqualTo(ProcessState.CLOSED);
        assertThatThrownBy(() -> ledger.advanceProjection(
                        expectation(fixture, 2), ProcessState.READY_TO_CLOSE, NOW.plusSeconds(33)))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_PROJECTION_TERMINAL_TRANSITION_INVALID");
        OutcomeProcessProjection evaluationPending = ledger.advanceProjection(
                expectation(fixture, 2), ProcessState.EVALUATION_PENDING, NOW.plusSeconds(33));
        OutcomeProcessProjection evaluated = ledger.advanceProjection(
                expectation(fixture, 3), ProcessState.EVALUATED, NOW.plusSeconds(34));
        assertThat(evaluationPending.processState()).isEqualTo(ProcessState.EVALUATION_PENDING);
        assertThat(evaluated.processState()).isEqualTo(ProcessState.EVALUATED);
    }

    @Test
    void compensationConsumesSucceededParentsOnceInExactDescendingOrderWithoutGaps() {
        Fixture fixture = insertFixture("COMPENSATION_ORDER", 2);
        OutcomeOperation lower = operation(
                fixture, "lower-parent", REQUEST_HASH, 1, OperationKind.OPERATION, true);
        OutcomeOperation higher = operation(
                fixture, "higher-parent", "1".repeat(64), 2, OperationKind.OPERATION, true);
        ledger.reserve(lower, null);
        ledger.reserve(higher, null);
        OutcomeOperationReceipt lowerReceipt = receipt(
                lower,
                "RECEIPT_LOWER_PARENT",
                RECEIPT_HASH,
                7,
                ReceiptAuthority.DIRECT_RESPONSE);
        OutcomeOperationReceipt higherReceipt = receipt(
                higher,
                "RECEIPT_HIGHER_PARENT",
                "2".repeat(64),
                7,
                ReceiptAuthority.DIRECT_RESPONSE);
        ledger.recordReceipt(lowerReceipt);
        ledger.recordReceipt(higherReceipt);

        OutcomeOperation firstCompensation = operation(
                fixture,
                "first-compensation",
                "3".repeat(64),
                3,
                OperationKind.COMPENSATION,
                false);
        assertThatThrownBy(() -> ledger.reserve(
                        firstCompensation,
                        compensationParent(
                                fixture,
                                firstCompensation,
                                lower,
                                lowerReceipt,
                                1,
                                "EARLY")))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_COMPENSATION_ORDER_INVALID");
        assertThatThrownBy(() -> ledger.reserve(
                        firstCompensation,
                        compensationParent(
                                fixture,
                                firstCompensation,
                                higher,
                                higherReceipt,
                                2,
                                "GAP")))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_COMPENSATION_ORDER_INVALID");

        OutcomeCompensationParent firstParent = compensationParent(
                fixture, firstCompensation, higher, higherReceipt, 1, "FIRST");
        ledger.reserve(firstCompensation, firstParent);
        OutcomeOperation secondCompensation = operation(
                fixture,
                "second-compensation",
                "4".repeat(64),
                4,
                OperationKind.COMPENSATION,
                false);
        assertThatThrownBy(() -> ledger.reserve(
                        secondCompensation,
                        compensationParent(
                                fixture,
                                secondCompensation,
                                higher,
                                higherReceipt,
                                2,
                                "DUPLICATE")))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_COMPENSATION_ORDER_INVALID");

        OutcomeCompensationParent secondParent = compensationParent(
                fixture, secondCompensation, lower, lowerReceipt, 2, "SECOND");
        ledger.reserve(secondCompensation, secondParent);
        assertThat(ledger.findCompensationParents(expectation(fixture)))
                .extracting(
                        OutcomeCompensationParent::parentOperationId,
                        OutcomeCompensationParent::reverseOrder)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(higher.operationId(), 1L),
                        org.assertj.core.groups.Tuple.tuple(lower.operationId(), 2L));
    }

    private static Fixture insertFixture(String suffix) {
        return insertFixture(suffix, 1);
    }

    private static Fixture insertFixture(String suffix, int expectedRequiredOperationCount) {
        return insertFixture(suffix, expectedRequiredOperationCount, List.of());
    }

    private static Fixture insertFixture(
            String suffix, int approvedActionCount, List<String> approvedNotifications) {
        int expectedRequiredOperationCount = approvedActionCount + approvedNotifications.size();
        String caseId = "CASE_OUTCOME_" + suffix;
        String roomId = "ROOM_OUTCOME_" + suffix;
        String epochId = "EPOCH_OUTCOME_" + suffix;
        String projectionId = "PROJECTION_OUTCOME_" + suffix;
        String planId = "PLAN_OUTCOME_" + suffix;
        String packetId = "PACKET_OUTCOME_" + suffix;
        String taskId = "TASK_OUTCOME_" + suffix;
        String approvalId = "APPROVAL_OUTCOME_" + suffix;
        String approvedActionsJson = approvedActionsJson(caseId, approvedActionCount);
        String approvedNotificationsJson = approvedNotificationsJson(approvedNotifications);
        String approvedPlanJson = approvedPlanJson(
                approvedActionsJson, approvedNotificationsJson);
        OffsetDateTime now = NOW.atOffset(ZoneOffset.UTC);

        jdbc.update(
                """
                insert into fulfillment_dispute_case (
                    id, user_id, merchant_id, creation_idempotency_key,
                    case_type, case_status, initiator_role, initiator_id,
                    respondent_role, respondent_id, risk_level, title, description,
                    current_room, created_by, updated_by
                ) values (?, ?, ?, ?, 'DISPUTE', 'REVIEW', 'USER', ?,
                    'MERCHANT', ?, 'HIGH', 'Outcome ledger fixture',
                    'Phase 7 Outcome operation integration fixture.',
                    'REVIEW', 'outcome-ledger-test', 'outcome-ledger-test')
                """,
                caseId,
                "user-" + suffix,
                "merchant-" + suffix,
                "create-outcome-" + suffix,
                "user-" + suffix,
                "merchant-" + suffix);
        jdbc.update(
                """
                insert into remedy_plan (
                    id, case_id, plan_version, source_route, plan_status, risk_level,
                    actions_json, preconditions_json, notification_plan_json,
                    created_by, updated_by
                ) values (?, ?, 1, 'FULL_HEARING', 'PENDING_APPROVAL', 'HIGH',
                    ?::jsonb,
                    '["PLATFORM_REVIEW_APPROVED"]'::jsonb,
                    ?::jsonb, 'outcome-ledger-test', 'outcome-ledger-test')
                """,
                planId,
                caseId,
                approvedActionsJson,
                approvedNotificationsJson);
        jdbc.update(
                """
                insert into review_packet (
                    id, case_id, plan_id, packet_version, case_summary_json,
                    claims_json, issues_json, evidence_matrix_json, draft_json,
                    remedy_json, risk_flags_json, packet_status, case_version,
                    dossier_version, issue_version, adjudication_draft_version,
                    deliberation_report_version, remedy_plan_version, ruleset_version,
                    prompt_version, skill_version, profile_version, action_hash,
                    frozen, frozen_at, expires_at, agent_run_refs_json,
                    created_at, updated_at, created_by, updated_by
                ) values (?, ?, ?, 1, '{}'::jsonb, '[]'::jsonb, '[]'::jsonb,
                    '[]'::jsonb, '{}'::jsonb, '{"id":"plan","actions":[]}'::jsonb,
                    '[]'::jsonb, 'FROZEN', 1, 1, 1, 1, 0, 1,
                    'ruleset-v1', 'prompt-v1', 'skill-v1', 'profile-v1', ?,
                    true, ?, ?, '[]'::jsonb, ?, ?,
                    'outcome-ledger-test', 'outcome-ledger-test')
                """,
                packetId,
                caseId,
                planId,
                ACTION_HASH,
                now,
                now.plusDays(7),
                now,
                now);
        jdbc.update(
                """
                insert into review_task (
                    id, case_id, plan_id, packet_id, task_status, priority,
                    assigned_reviewer_id, required_role, due_at, decision_json,
                    completed_at, created_at, updated_at, created_by, updated_by
                ) values (?, ?, ?, ?, 'APPROVED', 'HIGH', 'reviewer-outcome',
                    'PLATFORM_REVIEWER', ?, jsonb_build_object(
                        'request_hash', ?,
                        'packet_content_hash', ?,
                        'approved_action_hash', ?,
                        'policy_version', ?,
                        'outcome_epoch', 1,
                        'fencing_token', 7,
                        'process_revision', 0
                    ), ?, ?, ?, 'outcome-ledger-test', 'outcome-ledger-test')
                """,
                taskId,
                caseId,
                planId,
                packetId,
                now.plusHours(4),
                DECISION_REQUEST_HASH,
                PACKET_CONTENT_HASH,
                ACTION_HASH,
                POLICY_VERSION,
                now,
                now,
                now);
        jdbc.update(
                """
                insert into human_review_record (
                    id, case_id, review_task_id, plan_id, reviewer_id, reviewer_role,
                    decision_type, original_plan_json, approved_plan_json,
                    decision_reason, action_hash, packet_version, expires_at,
                    review_packet_id, review_packet_version, policy_version,
                    action_snapshot_hash, approval_expires_at, created_at, created_by
                ) values (?, ?, ?, ?, 'reviewer-outcome', 'PLATFORM_REVIEWER',
                    'APPROVE', ?::jsonb, ?::jsonb,
                    'Approved by integration fixture', ?, 1, ?, ?, 1, ?, ?, ?, ?,
                    'outcome-ledger-test')
                """,
                approvalId,
                caseId,
                taskId,
                planId,
                approvedPlanJson,
                approvedPlanJson,
                APPROVAL_HASH + '-' + suffix,
                now.plusDays(7),
                packetId,
                POLICY_VERSION,
                ACTION_HASH,
                now.plusDays(7),
                now);
        for (int sequence = 1; sequence <= expectedRequiredOperationCount; sequence++) {
            String actionType;
            String idempotencyKey;
            if (sequence <= approvedActionCount) {
                actionType = "REFUND";
                idempotencyKey = approvedActionKey(caseId, sequence);
            } else {
                int notificationIndex = sequence - approvedActionCount - 1;
                actionType = approvedNotifications.get(notificationIndex);
                idempotencyKey = approvedNotificationKey(
                        caseId, 1, notificationIndex, actionType);
            }
            jdbc.update(
                    """
                    insert into action_record (
                        id, case_id, plan_id, approval_record_id, action_type,
                        risk_level, idempotency_key, approved_by, executed_by,
                        request_json, result_json, execution_status, attempt_count,
                        execution_time, review_packet_id, action_snapshot_hash,
                        evidence_refs_json, rule_refs_json, agent_run_refs_json,
                        created_at, created_by
                    ) values (?, ?, ?, ?, ?, 'HIGH', ?,
                        'reviewer-outcome', 'outcome-ledger-test', '{}'::jsonb,
                        '{}'::jsonb, 'SUCCEEDED', 1, ?, ?, ?, '[]'::jsonb,
                        '[]'::jsonb, '[]'::jsonb, ?, 'outcome-ledger-test')
                    """,
                    actionRecordId(suffix, sequence),
                    caseId,
                    planId,
                    approvalId,
                    actionType,
                    idempotencyKey,
                    now,
                    packetId,
                    ACTION_HASH,
                    now);
        }
        jdbc.update(
                """
                insert into case_room (
                    id, case_id, room_type, room_status, opened_at,
                    created_by, updated_by
                ) values (?, ?, 'REVIEW', 'OPEN', ?,
                    'outcome-ledger-test', 'outcome-ledger-test')
                """,
                roomId,
                caseId,
                now);
        jdbc.update(
                """
                insert into case_process_projection (
                    case_id, tenant_surrogate, macro_phase, current_room,
                    room_phase, writer_mode, process_revision, room_epoch,
                    fencing_token, last_command_sequence, last_case_event_sequence,
                    temporal_build_id, projected_at, updated_at
                ) values (?, ?, 'REVIEW', 'REVIEW', 'DECISION_RECORDED',
                    'LEGACY', 0, 1, 7, 0, 0, 'legacy-java.v1', ?, ?)
                """,
                caseId,
                TENANT,
                now,
                now);
        jdbc.update(
                """
                insert into case_room_epoch (
                    id, tenant_surrogate, case_id, room_id, room_type, room_epoch,
                    writer_mode, lifecycle_status, process_revision, room_revision,
                    fencing_token, temporal_build_id, graph_key, graph_version,
                    checkpoint_schema_version, stream_protocol,
                    selection_schema_version, process_contract_version, workflow_type,
                    activated_at, created_at, updated_at
                ) values (?, ?, ?, ?, 'REVIEW', 1, 'LEGACY', 'ACTIVE', 0, 0, 7,
                    'legacy-java.v1', 'outcome.legacy', 'outcome-process.v1',
                    'legacy-checkpoint.v1', 'agent_stream.v1',
                    'room-epoch-selection.v1', 'case-process-contract.v1',
                    'LegacyJavaRoomState', ?, ?, ?)
                """,
                epochId,
                TENANT,
                caseId,
                roomId,
                now,
                now,
                now);
        OutcomeProcessProjection projection = new OutcomeProcessProjection(
                projectionId,
                TENANT,
                caseId,
                epochId,
                1,
                WriterMode.LEGACY,
                RuntimeMode.DISABLED,
                7,
                0,
                0,
                approvalId,
                DECISION_REQUEST_HASH,
                ACTION_HASH,
                expectedRequiredOperationCount,
                ProcessState.DECISION_RECORDED,
                NOW,
                NOW);
        ledger.createProjection(projection);
        return new Fixture(
                suffix,
                caseId,
                epochId,
                projectionId,
                planId,
                packetId,
                taskId,
                approvalId,
                expectedRequiredOperationCount);
    }

    private static OutcomeOperation operation(
            Fixture fixture,
            String keySuffix,
            String requestHash,
            long sequence,
            OperationKind kind,
            boolean compensable) {
        return operation(fixture, keySuffix, requestHash, sequence, kind, compensable, true);
    }

    private static OutcomeOperation operation(
            Fixture fixture,
            String keySuffix,
            String requestHash,
            long sequence,
            OperationKind kind,
            boolean compensable,
            boolean requiredForClosure) {
        return operation(
                fixture, keySuffix, requestHash, sequence, kind, compensable,
                requiredForClosure, RetryClass.STATUS_QUERY_REQUIRED);
    }

    private static OutcomeOperation operation(
            Fixture fixture,
            String keySuffix,
            String requestHash,
            long sequence,
            OperationKind kind,
            boolean compensable,
            boolean requiredForClosure,
            RetryClass retryClass) {
        return operation(
                fixture, keySuffix, requestHash, sequence, kind, compensable,
                requiredForClosure, retryClass, 0, 0, fixture.approvalId(),
                APPROVAL_HASH + '-' + fixture.suffix(), DECISION_REQUEST_HASH,
                ACTION_HASH, NOW.plusSeconds(sequence));
    }

    private static OutcomeOperation operation(
            Fixture fixture,
            String keySuffix,
            String requestHash,
            long sequence,
            OperationKind kind,
            boolean compensable,
            boolean requiredForClosure,
            RetryClass retryClass,
            long processRevision,
            long outcomeRevision,
            String approvalRecordId,
            String approvalHash,
            String decisionRequestHash,
            String actionSnapshotHash,
            Instant reservedAt) {
        String actionRecordId = kind == OperationKind.OPERATION
                        && requiredForClosure
                        && sequence <= fixture.expectedRequiredOperationCount()
                ? actionRecordId(fixture.suffix(), sequence)
                : null;
        return new OutcomeOperation(
                "OP_" + fixture.suffix() + '_' + sequence,
                fixture.projectionId(),
                TENANT,
                fixture.caseId(),
                1,
                7,
                processRevision,
                outcomeRevision,
                kind,
                sequence,
                "outcome.effect:" + fixture.caseId() + ':' + keySuffix,
                requestHash,
                fixture.packetId(),
                1,
                PACKET_CONTENT_HASH,
                ACTION_HASH,
                approvalRecordId,
                approvalHash,
                decisionRequestHash,
                POLICY_VERSION,
                actionRecordId,
                actionSnapshotHash,
                "refund-adapter",
                "v1",
                retryClass,
                "provider-key:" + fixture.caseId() + ':' + keySuffix,
                requiredForClosure,
                compensable,
                reservedAt);
    }

    private static OutcomeAttemptObservation observation(
            OutcomeOperation operation,
            int sequence,
            ObservationType type,
            boolean effectMayHaveOccurred,
            boolean retryPermitted) {
        return observationAt(
                operation, sequence, type, effectMayHaveOccurred, retryPermitted,
                NOW.plusSeconds(sequence));
    }

    private static OutcomeAttemptObservation observationAt(
            OutcomeOperation operation,
            int sequence,
            ObservationType type,
            boolean effectMayHaveOccurred,
            boolean retryPermitted,
            Instant observedAt) {
        return new OutcomeAttemptObservation(
                "OBS_" + operation.operationId() + '_' + sequence,
                Integer.toHexString(sequence).repeat(64).substring(0, 64),
                operation.operationId(),
                operation.tenantSurrogate(),
                operation.caseId(),
                operation.outcomeEpoch(),
                operation.fencingToken(),
                operation.requestHash(),
                sequence,
                type,
                "INVOCATION_" + operation.operationId(),
                "urn:outcome:observation:" + operation.operationId() + ':' + sequence,
                RESPONSE_HASH,
                effectMayHaveOccurred,
                retryPermitted,
                observedAt);
    }

    private static OutcomeCompensationParent compensationParent(
            Fixture fixture,
            OutcomeOperation child,
            OutcomeOperation parent,
            OutcomeOperationReceipt parentReceipt,
            long reverseOrder,
            String bindingSuffix) {
        return compensationParentAt(
                fixture, child, parent, parentReceipt, reverseOrder, bindingSuffix,
                NOW.plusSeconds(child.operationSequence()));
    }

    private static OutcomeCompensationParent compensationParentAt(
            Fixture fixture,
            OutcomeOperation child,
            OutcomeOperation parent,
            OutcomeOperationReceipt parentReceipt,
            long reverseOrder,
            String bindingSuffix,
            Instant createdAt) {
        return new OutcomeCompensationParent(
                "COMP_PARENT_" + fixture.suffix() + '_' + bindingSuffix,
                Integer.toHexString(bindingSuffix.hashCode()).replace("-", "a").repeat(64)
                        .substring(0, 64),
                child.operationId(),
                parent.operationId(),
                parentReceipt.receiptId(),
                parentReceipt.receiptHash(),
                "compensation-policy.v1",
                reverseOrder,
                TENANT,
                fixture.caseId(),
                1,
                7,
                createdAt);
    }

    private static OutcomeOperationReceipt receipt(
            OutcomeOperation operation,
            String receiptId,
            String receiptHash,
            long fencingToken,
            ReceiptAuthority authority) {
        return receipt(
                operation,
                receiptId,
                receiptHash,
                fencingToken,
                authority,
                ReceiptStatus.SUCCEEDED,
                ClosureDisposition.SATISFIED);
    }

    private static OutcomeOperationReceipt receipt(
            OutcomeOperation operation,
            String receiptId,
            String receiptHash,
            ReceiptStatus status,
            ClosureDisposition disposition) {
        return receipt(
                operation,
                receiptId,
                receiptHash,
                operation.fencingToken(),
                ReceiptAuthority.DIRECT_RESPONSE,
                status,
                disposition);
    }

    private static OutcomeOperationReceipt receipt(
            OutcomeOperation operation,
            String receiptId,
            String receiptHash,
            long fencingToken,
            ReceiptAuthority authority,
            ReceiptStatus status,
            ClosureDisposition disposition) {
        return receiptAt(
                operation, receiptId, receiptHash, fencingToken, authority,
                status, disposition, NOW.plusSeconds(10));
    }

    private static OutcomeOperationReceipt receiptAt(
            OutcomeOperation operation,
            String receiptId,
            String receiptHash,
            ReceiptAuthority authority,
            Instant completedAt) {
        return receiptAt(
                operation, receiptId, receiptHash, operation.fencingToken(), authority,
                ReceiptStatus.SUCCEEDED, ClosureDisposition.SATISFIED, completedAt);
    }

    private static OutcomeOperationReceipt receiptAt(
            OutcomeOperation operation,
            String receiptId,
            String receiptHash,
            long fencingToken,
            ReceiptAuthority authority,
            ReceiptStatus status,
            ClosureDisposition disposition,
            Instant completedAt) {
        return new OutcomeOperationReceipt(
                receiptId,
                receiptHash,
                operation.operationId(),
                operation.tenantSurrogate(),
                operation.caseId(),
                operation.outcomeEpoch(),
                fencingToken,
                operation.requestHash(),
                status,
                authority,
                "EXTERNAL_" + receiptId,
                "urn:outcome:receipt:" + receiptId,
                RESPONSE_HASH,
                disposition,
                completedAt);
    }

    private static OutcomeOperation withReservedAt(OutcomeOperation operation, Instant reservedAt) {
        return new OutcomeOperation(
                operation.operationId(), operation.projectionId(), operation.tenantSurrogate(),
                operation.caseId(), operation.outcomeEpoch(), operation.fencingToken(),
                operation.processRevision(), operation.outcomeRevision(), operation.operationKind(),
                operation.operationSequence(), operation.operationKey(), operation.requestHash(),
                operation.reviewPacketId(), operation.reviewPacketVersion(), operation.reviewPacketHash(),
                operation.reviewPacketActionHash(), operation.approvalRecordId(), operation.approvalHash(),
                operation.decisionRequestHash(), operation.decisionPolicyVersion(), operation.actionRecordId(),
                operation.actionSnapshotHash(), operation.adapterId(), operation.adapterVersion(),
                operation.retryClass(), operation.externalIdempotencyKey(), operation.requiredForClosure(),
                operation.compensable(), reservedAt);
    }

    private static OutcomeOperation withActionRecordId(
            OutcomeOperation operation, String actionRecordId) {
        return new OutcomeOperation(
                operation.operationId(), operation.projectionId(), operation.tenantSurrogate(),
                operation.caseId(), operation.outcomeEpoch(), operation.fencingToken(),
                operation.processRevision(), operation.outcomeRevision(), operation.operationKind(),
                operation.operationSequence(), operation.operationKey(), operation.requestHash(),
                operation.reviewPacketId(), operation.reviewPacketVersion(), operation.reviewPacketHash(),
                operation.reviewPacketActionHash(), operation.approvalRecordId(), operation.approvalHash(),
                operation.decisionRequestHash(), operation.decisionPolicyVersion(), actionRecordId,
                operation.actionSnapshotHash(), operation.adapterId(), operation.adapterVersion(),
                operation.retryClass(), operation.externalIdempotencyKey(), operation.requiredForClosure(),
                operation.compensable(), operation.reservedAt());
    }

    private static void assertActionAuthorityRejected(OutcomeOperation operation) {
        assertThatThrownBy(() -> ledger.reserve(operation, null))
                .isInstanceOf(OutcomeLedgerRejectedException.class)
                .extracting(failure -> ((OutcomeLedgerRejectedException) failure).code())
                .isEqualTo("OUTCOME_REQUIRED_ACTION_AUTHORITY_INVALID");
    }

    private static ProjectionExpectation expectation(Fixture fixture) {
        return expectation(fixture, 0);
    }

    private static ProjectionExpectation expectation(Fixture fixture, long revision) {
        return new ProjectionExpectation(
                fixture.projectionId(), TENANT, fixture.caseId(), 1, 7, revision, revision);
    }

    private static void cloneApproval(
            String sourceApprovalId, String targetApprovalId, String targetApprovalHash) {
        jdbc.update(
                """
                insert into human_review_record (
                    id, case_id, review_task_id, plan_id, reviewer_id, reviewer_role,
                    decision_type, original_plan_json, approved_plan_json,
                    decision_reason, action_hash, packet_version, expires_at,
                    review_packet_id, review_packet_version, policy_version,
                    action_snapshot_hash, approval_expires_at, created_at, created_by
                )
                select ?, case_id, review_task_id, plan_id, reviewer_id, reviewer_role,
                       decision_type, original_plan_json, approved_plan_json,
                       decision_reason, ?, packet_version, expires_at,
                       review_packet_id, review_packet_version, policy_version,
                       action_snapshot_hash, approval_expires_at, created_at, created_by
                  from human_review_record
                 where id = ?
                """,
                targetApprovalId,
                targetApprovalHash,
                sourceApprovalId);
    }

    private static boolean tableExists(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select to_regclass('public.' || ?) is not null", Boolean.class, table));
    }

    private static boolean viewExists(String view) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from information_schema.views where table_schema = 'public' and table_name = ?)",
                Boolean.class,
                view));
    }

    private static long number(String sql, Object... parameters) {
        Long value = jdbc.queryForObject(sql, Long.class, parameters);
        return value == null ? 0 : value;
    }

    private static String approvedActionsJson(
            String caseId, int expectedRequiredOperationCount) {
        java.util.ArrayList<String> actions = new java.util.ArrayList<>();
        for (int sequence = 1; sequence <= expectedRequiredOperationCount; sequence++) {
            actions.add("{\"action_type\":\"REFUND\",\"idempotency_key\":\""
                    + approvedActionKey(caseId, sequence)
                    + "\"}");
        }
        return '[' + String.join(",", actions) + ']';
    }

    private static String approvedPlanJson(String actionsJson, String notificationsJson) {
        return "{\"id\":\"plan\",\"actions\":" + actionsJson
                + ",\"notifications\":" + notificationsJson + '}';
    }

    private static String approvedNotificationsJson(List<String> notifications) {
        return '[' + String.join(",", notifications.stream()
                .map(value -> "\"" + value + "\"")
                .toList()) + ']';
    }

    private static String approvedActionKey(String caseId, long sequence) {
        return "OUTCOME_ACTION:" + caseId + ':' + sequence;
    }

    private static String approvedNotificationKey(
            String caseId, int planVersion, int notificationIndex, String notification) {
        return "REMEDY:" + caseId + ':' + planVersion + ":NOTIFICATION:"
                + notificationIndex + ':' + notification;
    }

    private static String actionRecordId(String suffix, long sequence) {
        return "ACTION_OUTCOME_" + suffix + '_' + sequence;
    }

    private static JdbcOutcomeOperationLedger ledger(DataSource source) {
        return new JdbcOutcomeOperationLedger(
                new NamedParameterJdbcTemplate(source),
                new TransactionTemplate(new DataSourceTransactionManager(source)));
    }

    private static Throwable raceCall(
            CountDownLatch ready, CountDownLatch start, RaceAction action) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                return new AssertionError("race start latch timed out");
            }
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static void assertIntendedLifecycleRaceLoser(Throwable failure) {
        assertThat(failure).isNotNull();
        if (failure instanceof OutcomeLedgerRejectedException rejected) {
            assertThat(rejected.code())
                    .isIn(
                            "OUTCOME_OPERATION_ALREADY_TERMINAL",
                            "OUTCOME_AMBIGUOUS_RECONCILIATION_REQUIRED");
            return;
        }
        assertThat(failure).isInstanceOf(DataAccessException.class);
        SQLException sqlFailure = findSqlFailure(failure);
        if (sqlFailure == null) {
            throw new AssertionError("database race loser has no SQLState", failure);
        }
        String sqlState = sqlFailure.getSQLState();
        assertThat(sqlState)
                .as("deadlock, serialization, and connection concurrency failures are test failures")
                .doesNotStartWith("08")
                .isNotIn("40P01", "40001", "55P03")
                .isEqualTo("23514");
        assertThat(failure.getMessage())
                .containsAnyOf(
                        "Terminal Outcome receipt forbids later attempts",
                        "AMBIGUOUS Outcome operation requires RECONCILING before receipt");
    }

    private static void assertIntendedClosureCompensationRaceLoser(Throwable failure) {
        assertThat(failure).isNotNull();
        if (failure instanceof OutcomeLedgerRejectedException rejected) {
            assertThat(rejected.code())
                    .isIn(
                            "OUTCOME_CLOSURE_NOT_READY",
                            "OUTCOME_STALE_PROJECTION",
                            "OUTCOME_RESERVATION_AFTER_CLOSURE_FORBIDDEN");
            return;
        }
        assertThat(failure).isInstanceOf(DataAccessException.class);
        SQLException sqlFailure = findSqlFailure(failure);
        if (sqlFailure == null) {
            throw new AssertionError("database race loser has no SQLState", failure);
        }
        assertThat(sqlFailure.getSQLState())
                .as("deadlock, serialization, and connection concurrency failures are test failures")
                .doesNotStartWith("08")
                .isNotIn("40P01", "40001", "55P03")
                .isEqualTo("23514");
    }

    private static SQLException findSqlFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlFailure) {
                return sqlFailure;
            }
            current = current.getCause();
        }
        return null;
    }

    @FunctionalInterface
    private interface RaceAction {
        void run();
    }

    private record Fixture(
            String suffix,
            String caseId,
            String epochId,
            String projectionId,
            String planId,
            String packetId,
            String taskId,
            String approvalId,
            int expectedRequiredOperationCount) {}
}
