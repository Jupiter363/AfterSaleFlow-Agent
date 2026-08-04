package com.example.dispute.workflow.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.infrastructure.persistence.RoomEpochScanClaimStore;
import com.example.dispute.workflow.infrastructure.persistence.RoomEpochScanClaimStore.ClaimedRoomEpoch;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RoomEpochScanClaimStoreIntegrationTest {

    private static final String USERNAME = "dispute_test";
    private static final String PASSWORD = "local_test_password";
    private static final Instant SCAN_TIME = Instant.parse("2030-01-01T00:00:00Z");

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "room_epoch_scan_claims")
                    .withEnv("POSTGRES_USER", USERNAME)
                    .withEnv("POSTGRES_PASSWORD", PASSWORD)
                    .withExposedPorts(5432)
                    .waitingFor(Wait.forListeningPort());

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateAndInsertCandidates() {
        String jdbcUrl =
                "jdbc:postgresql://"
                        + POSTGRESQL.getHost()
                        + ":"
                        + POSTGRESQL.getMappedPort(5432)
                        + "/room_epoch_scan_claims";
        dataSource = new DriverManagerDataSource(jdbcUrl, USERNAME, PASSWORD);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        jdbc = new JdbcTemplate(dataSource);
        for (int index = 1; index <= 7; index++) {
            insertCandidate(jdbc, index);
        }
    }

    @BeforeEach
    void resetCandidates() {
        jdbc.update("delete from case_process_projection where case_id like 'CASE_Scan%'");
        jdbc.update("delete from case_room_epoch where case_id like 'CASE_Scan%'");
        jdbc.update("delete from case_room where case_id like 'CASE_Scan%'");
        jdbc.update("delete from fulfillment_dispute_case where id like 'CASE_Scan%'");
        for (int index = 1; index <= 7; index++) {
            insertCandidate(jdbc, index);
        }
        jdbc.update(
                """
                update case_room_epoch
                   set domain_event_recovery_next_scan_at = clock_timestamp() - interval '1 second',
                       domain_event_recovery_claim_token = null,
                       domain_event_recovery_claimed_until = null,
                       projection_reconciliation_next_scan_at = clock_timestamp() - interval '1 second',
                       projection_reconciliation_claim_token = null,
                       projection_reconciliation_claimed_until = null
                 where case_id like 'CASE_Scan%'
                """);
    }

    @Test
    void moreThanTwoBatchesRemainFairAndTwoWorkersNeverOwnTheSameEpoch() throws Exception {
        RoomEpochScanClaimStore workerOne =
                new RoomEpochScanClaimStore(new NamedParameterJdbcTemplate(dataSource));
        RoomEpochScanClaimStore workerTwo =
                new RoomEpochScanClaimStore(new NamedParameterJdbcTemplate(dataSource));

        assertFairAndExclusiveAcrossFourBatches(ScanKind.DOMAIN_EVENT, workerOne, workerTwo);
        assertFairAndExclusiveAcrossFourBatches(ScanKind.PROJECTION, workerOne, workerTwo);
    }

    @Test
    void expiredClaimsAreReclaimedAndOldTokensCannotRenewOrComplete() {
        RoomEpochScanClaimStore firstWorker = store();
        RoomEpochScanClaimStore secondWorker = store();
        isolateCandidate(ScanKind.DOMAIN_EVENT, "EPOCH_Scan1");

        ClaimedRoomEpoch stale =
                firstWorker
                        .claimDomainEventRecovery(1, Duration.ofMinutes(5))
                        .getFirst();
        assertThat(stale.fencingToken()).isEqualTo(1);
        jdbc.update(
                """
                update case_room_epoch
                   set domain_event_recovery_claimed_until = clock_timestamp() - interval '1 second'
                 where id = ?
                """,
                stale.epochId());

        ClaimedRoomEpoch reclaimed =
                secondWorker
                        .claimDomainEventRecovery(1, Duration.ofMinutes(5))
                        .getFirst();

        assertThat(reclaimed.epochId()).isEqualTo(stale.epochId());
        assertThat(reclaimed.claimToken()).isNotEqualTo(stale.claimToken());
        assertThat(firstWorker.renewDomainEventRecovery(stale, Duration.ofMinutes(5)))
                .isFalse();
        assertThat(firstWorker.completeDomainEventRecovery(stale, Duration.ofMinutes(1)))
                .isFalse();
        assertThat(secondWorker.renewDomainEventRecovery(reclaimed, Duration.ofMinutes(5)))
                .isTrue();
        assertThat(secondWorker.completeDomainEventRecovery(reclaimed, Duration.ofHours(1)))
                .isTrue();

        Double secondsUntilNextScan =
                jdbc.queryForObject(
                        """
                        select extract(epoch from (
                            domain_event_recovery_next_scan_at - clock_timestamp()
                        ))
                          from case_room_epoch
                         where id = ?
                        """,
                        Double.class,
                        reclaimed.epochId());
        assertThat(secondsUntilNextScan).isBetween(3_590.0, 3_601.0);
    }

    @Test
    void provisioningPendingEpochIsExcludedFromBothRecoveryScans() {
        insertCandidate(jdbc, 8, false);
        RoomEpochScanClaimStore worker = store();

        isolateCandidate(ScanKind.DOMAIN_EVENT, "EPOCH_Scan8");
        assertThat(worker.claimDomainEventRecovery(1, Duration.ofMinutes(5))).isEmpty();

        isolateCandidate(ScanKind.PROJECTION, "EPOCH_Scan8");
        assertThat(worker.claimProjectionReconciliation(1, Duration.ofMinutes(5))).isEmpty();
    }

    @Test
    void readyNotificationDoesNotCreatePermanentPriorityClaimOrBreakGapLoading() {
        RoomEpochScanClaimStore worker = store();
        String fixtureSuffix = "ReadyPriority";
        String caseId = "CASE_" + fixtureSuffix;
        String epochId = "EPOCH_" + fixtureSuffix;
        insertCandidate(jdbc, fixtureSuffix, 91, true);
        isolateCandidate(ScanKind.DOMAIN_EVENT, epochId);
        jdbc.update(
                """
                update case_room_epoch
                   set projection_reconciliation_next_scan_at =
                           clock_timestamp() + interval '1 hour'
                 where id = ?
                """,
                epochId);
        jdbc.update(
                "update case_process_projection set last_case_event_sequence = 10 where case_id = ?",
                caseId);
        insertTimelineEvent(caseId, 11, "INTAKE_PROJECTION_READY");

        assertThat(worker.claimPriorityDomainEventRecovery(1, Duration.ofMinutes(5))).isEmpty();

        List<ClaimedRoomEpoch> fairClaims =
                worker.claimDomainEventRecovery(1, Duration.ofMinutes(5));
        assertThat(fairClaims).extracting(ClaimedRoomEpoch::epochId).containsExactly(epochId);
        jdbc.update(
                """
                update case_room_epoch
                   set domain_event_recovery_next_scan_at = clock_timestamp() - interval '1 second',
                       domain_event_recovery_claim_token = null,
                       domain_event_recovery_claimed_until = null
                 where id = ?
                """,
                epochId);

        insertTimelineEvent(caseId, 12, "ROOM_MESSAGE_CREATED");

        assertThat(worker.claimPriorityDomainEventRecovery(1, Duration.ofMinutes(5)))
                .extracting(ClaimedRoomEpoch::epochId)
                .containsExactly(epochId);
        assertThat(
                        jdbc.queryForList(
                                """
                                select sequence_no
                                  from case_timeline_event
                                 where case_id = ?
                                   and sequence_no between 11 and 12
                                 order by sequence_no
                                """,
                                Long.class,
                                caseId))
                .containsExactly(11L, 12L);
    }

    @Test
    void terminalizedOrReboundEpochsLoseClaimOwnershipBeforeSideEffects() {
        RoomEpochScanClaimStore worker = store();
        isolateCandidate(ScanKind.DOMAIN_EVENT, "EPOCH_Scan2");
        ClaimedRoomEpoch terminalized =
                worker.claimDomainEventRecovery(1, Duration.ofMinutes(5)).getFirst();

        jdbc.update(
                """
                update case_room_epoch
                   set lifecycle_status = 'TERMINAL',
                       process_revision = process_revision + 1,
                       room_revision = room_revision + 1,
                       terminal_at = greatest(clock_timestamp(), updated_at),
                       updated_at = greatest(clock_timestamp(), updated_at),
                       version = version + 1
                 where id = ?
                """,
                terminalized.epochId());

        assertThat(worker.renewDomainEventRecovery(terminalized, Duration.ofMinutes(5)))
                .isFalse();
        assertThat(worker.completeDomainEventRecovery(terminalized, Duration.ofSeconds(5)))
                .isFalse();

        resetCandidates();
        isolateCandidate(ScanKind.PROJECTION, "EPOCH_Scan3");
        ClaimedRoomEpoch rebound =
                worker.claimProjectionReconciliation(1, Duration.ofMinutes(5)).getFirst();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                                        update case_room_epoch
                                           set temporal_workflow_id = temporal_workflow_id || ':rebound'
                                         where id = ?
                                        """,
                                        rebound.epochId()))
                .hasMessageContaining("immutable execution selection cannot be rewritten");

        ClaimedRoomEpoch reboundBinding =
                new ClaimedRoomEpoch(
                        rebound.claimToken(),
                        rebound.epochId(),
                        rebound.tenantSurrogate(),
                        rebound.caseId(),
                        rebound.roomType(),
                        rebound.roomEpoch(),
                        rebound.fencingToken(),
                        rebound.temporalWorkflowId() + ":rebound");

        assertThat(worker.renewProjectionReconciliation(reboundBinding, Duration.ofMinutes(5)))
                .isFalse();
        assertThat(
                        worker.completeProjectionReconciliation(
                                reboundBinding, Duration.ofSeconds(30)))
                .isFalse();
    }

    private static void assertFairAndExclusiveAcrossFourBatches(
            ScanKind kind,
            RoomEpochScanClaimStore workerOne,
            RoomEpochScanClaimStore workerTwo)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<ClaimedRoomEpoch> first;
        List<ClaimedRoomEpoch> second;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<List<ClaimedRoomEpoch>> firstFuture =
                    executor.submit(() -> concurrentClaim(kind, workerOne, ready, start));
            Future<List<ClaimedRoomEpoch>> secondFuture =
                    executor.submit(() -> concurrentClaim(kind, workerTwo, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first = firstFuture.get(10, TimeUnit.SECONDS);
            second = secondFuture.get(10, TimeUnit.SECONDS);
        }

        assertThat(first).hasSize(2);
        assertThat(second).hasSize(2);
        assertThat(epochIds(first)).doesNotContainAnyElementsOf(epochIds(second));

        Set<String> allEpochs = new HashSet<>();
        complete(kind, workerOne, first, allEpochs);
        complete(kind, workerTwo, second, allEpochs);

        int claimCalls = 2;
        while (allEpochs.size() < 7) {
            RoomEpochScanClaimStore worker = claimCalls % 2 == 0 ? workerOne : workerTwo;
            List<ClaimedRoomEpoch> next = claim(kind, worker);
            assertThat(next).isNotEmpty();
            complete(kind, worker, next, allEpochs);
            claimCalls++;
        }

        assertThat(claimCalls).isEqualTo(4);
        assertThat(allEpochs).hasSize(7);
        assertThat(claim(kind, workerOne)).isEmpty();
    }

    private static List<ClaimedRoomEpoch> concurrentClaim(
            ScanKind kind,
            RoomEpochScanClaimStore store,
            CountDownLatch ready,
            CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent claim start was not released");
        }
        return claim(kind, store);
    }

    private static List<ClaimedRoomEpoch> claim(
            ScanKind kind, RoomEpochScanClaimStore store) {
        return switch (kind) {
            case DOMAIN_EVENT ->
                    store.claimDomainEventRecovery(2, Duration.ofMinutes(5));
            case PROJECTION ->
                    store.claimProjectionReconciliation(2, Duration.ofMinutes(5));
        };
    }

    private static void complete(
            ScanKind kind,
            RoomEpochScanClaimStore store,
            List<ClaimedRoomEpoch> claims,
            Set<String> allEpochs) {
        for (ClaimedRoomEpoch claim : claims) {
            assertThat(allEpochs.add(claim.epochId())).isTrue();
            boolean completed =
                    switch (kind) {
                        case DOMAIN_EVENT ->
                                store.completeDomainEventRecovery(
                                        claim, Duration.ofHours(1));
                        case PROJECTION ->
                                store.completeProjectionReconciliation(
                                        claim, Duration.ofHours(1));
                    };
            assertThat(completed).isTrue();
        }
    }

    private static Set<String> epochIds(List<ClaimedRoomEpoch> claims) {
        Set<String> ids = new HashSet<>();
        claims.forEach(claim -> ids.add(claim.epochId()));
        return ids;
    }

    private static RoomEpochScanClaimStore store() {
        return new RoomEpochScanClaimStore(new NamedParameterJdbcTemplate(dataSource));
    }

    private static void isolateCandidate(ScanKind kind, String epochId) {
        String prefix =
                kind == ScanKind.DOMAIN_EVENT
                        ? "domain_event_recovery"
                        : "projection_reconciliation";
        jdbc.update(
                "update case_room_epoch set "
                        + prefix
                        + "_next_scan_at = clock_timestamp() + interval '1 hour', "
                        + prefix
                        + "_claim_token = null, "
                        + prefix
                        + "_claimed_until = null");
        jdbc.update(
                "update case_room_epoch set "
                        + prefix
                        + "_next_scan_at = clock_timestamp() - interval '1 second' where id = ?",
                epochId);
    }

    private static void insertCandidate(JdbcTemplate jdbc, int index) {
        insertCandidate(jdbc, index, true);
    }

    private static void insertCandidate(JdbcTemplate jdbc, int index, boolean ready) {
        insertCandidate(jdbc, "Scan" + index, index, ready);
    }

    private static void insertCandidate(
            JdbcTemplate jdbc, String suffix, int fencingToken, boolean ready) {
        String caseId = "CASE_" + suffix;
        String roomId = "ROOM_" + suffix;
        OffsetDateTime createdAt =
                OffsetDateTime.ofInstant(
                        SCAN_TIME.minusSeconds(100L - fencingToken), ZoneOffset.UTC);
        String caseWorkflowId =
                CaseProcessWorkflowProtocol.caseWorkflowId("tenant-scan-claim", caseId);
        String roomWorkflowId =
                CaseProcessWorkflowProtocol.roomWorkflowId(caseId, RoomType.EVIDENCE, 1);
        String provisioningStatus = ready ? "READY" : "PENDING";
        String activationStatus = ready ? "READY" : "PREPARING";
        String caseRunId = ready ? "run-scan-claim-" + fencingToken : null;
        String roomRunId = ready ? "run-room-scan-claim-" + fencingToken : null;
        jdbc.update(
                """
                insert into fulfillment_dispute_case (
                    id, user_id, merchant_id, creation_idempotency_key,
                    case_type, case_status, initiator_role, initiator_id,
                    respondent_role, respondent_id, risk_level, title, description,
                    current_room, created_by, updated_by
                ) values (?, ?, ?, ?, 'DISPUTE', 'EVIDENCE_OPEN', 'USER', ?,
                    'MERCHANT', ?, 'LOW', 'Scan claim fixture',
                    'Persistent claim fairness fixture.', 'EVIDENCE', 'test', 'test')
                """,
                caseId,
                "user-" + suffix,
                "merchant-" + suffix,
                "create-" + suffix,
                "user-" + suffix,
                "merchant-" + suffix);
        jdbc.update(
                """
                insert into case_room (
                    id, case_id, room_type, room_status, opened_at, created_by, updated_by
                ) values (?, ?, 'EVIDENCE', 'OPEN', ?, 'test', 'test')
                """,
                roomId,
                caseId,
                createdAt);
        jdbc.update(
                """
                insert into case_room_epoch (
                    id, tenant_surrogate, case_id, room_id, room_type, room_epoch,
                    writer_mode, lifecycle_status, provisioning_status,
                    process_revision, room_revision, fencing_token,
                    temporal_workflow_id, temporal_run_id,
                    room_temporal_workflow_id, room_temporal_run_id, temporal_build_id,
                    graph_key, graph_version, checkpoint_schema_version, stream_protocol,
                    selection_schema_version, process_contract_version, workflow_type,
                    activated_at, provisioned_at, created_at, updated_at
                ) values (?, 'tenant-scan-claim', ?, ?, 'EVIDENCE', 1,
                    'SHADOW', 'ACTIVE', ?, 0, 0, ?, ?, ?, ?, ?, 'build-scan-claim',
                    'evidence.v2', '1.0.0', 'checkpoint.v1', 'agent_stream.v1',
                    'room-epoch-selection.v1', 'case-process-contract.v1',
                    'CaseProcessWorkflow', ?,
                    case when ? = 'READY' then ? else null end, ?, ?)
                """,
                "EPOCH_" + suffix,
                caseId,
                roomId,
                provisioningStatus,
                fencingToken,
                caseWorkflowId,
                caseRunId,
                roomWorkflowId,
                roomRunId,
                createdAt,
                provisioningStatus,
                createdAt,
                createdAt,
                createdAt);
        jdbc.update(
                """
                insert into case_process_projection (
                    case_id, tenant_surrogate, macro_phase, current_room, room_phase,
                    writer_mode, writer_activation_status, process_revision, room_epoch,
                    fencing_token, last_command_sequence, last_case_event_sequence,
                    temporal_workflow_id, temporal_run_id, temporal_build_id,
                    projected_at, updated_at
                ) values (?, 'tenant-scan-claim', 'EVIDENCE_OPEN', 'EVIDENCE', 'OPEN',
                    'SHADOW', ?, 0, 1, ?, 0, 0, ?, ?, 'build-scan-claim', ?, ?)
                """,
                caseId,
                activationStatus,
                fencingToken,
                caseWorkflowId,
                caseRunId,
                createdAt,
                createdAt);
    }

    private static void insertTimelineEvent(String caseId, long sequence, String eventType) {
        OffsetDateTime createdAt =
                OffsetDateTime.ofInstant(SCAN_TIME.plusSeconds(sequence), ZoneOffset.UTC);
        jdbc.update(
                """
                insert into case_timeline_event (
                    id, case_id, sequence_no, event_type, event_time,
                    source_refs_json, event_json, audience_json,
                    audience_actor_ids_json, event_key, created_at, created_by
                ) values (?, ?, ?, ?, ?, '[]', '{}', '[]', '[]', ?, ?, 'test')
                """,
                "EVENT_" + caseId.replace("CASE_", "") + "_" + sequence,
                caseId,
                sequence,
                eventType,
                createdAt,
                "scan-claim-event:" + sequence,
                createdAt);
    }

    private enum ScanKind {
        DOMAIN_EVENT,
        PROJECTION
    }
}
