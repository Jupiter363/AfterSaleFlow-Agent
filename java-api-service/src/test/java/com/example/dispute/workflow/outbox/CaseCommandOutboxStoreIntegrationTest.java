package com.example.dispute.workflow.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.infrastructure.outbox.CaseCommandOutboxStore;
import com.example.dispute.workflow.infrastructure.outbox.CaseCommandOutboxStore.ExpirationResolution;
import com.example.dispute.workflow.infrastructure.outbox.CaseCommandOutboxStore.PermanentFailureResolution;
import com.example.dispute.workflow.infrastructure.outbox.ClaimedCaseCommandDelivery;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Testcontainers
@Import({
    CaseCommandOutboxStore.class,
    CaseCommandOutboxStoreIntegrationTest.StoreTestConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CaseCommandOutboxStoreIntegrationTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 7, 17, 8, 0, 0, 0, ZoneOffset.UTC);
    private static final Duration LEASE = Duration.ofSeconds(30);

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "command_outbox_store")
                    .withEnv("POSTGRES_USER", "dispute_test")
                    .withEnv("POSTGRES_PASSWORD", "local_test_password")
                    .withExposedPorts(5432);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () ->
                        "jdbc:postgresql://"
                                + POSTGRESQL.getHost()
                                + ":"
                                + POSTGRESQL.getMappedPort(5432)
                                + "/command_outbox_store");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @Autowired private CaseCommandOutboxStore store;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void oneOwnerClaimsARowAndAnExpiredLeaseIsReclaimedWithFencing()
            throws Exception {
        String outboxId = insertDelivery("EXCLUSIVE", NOW);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Optional<ClaimedCaseCommandDelivery>> first =
                    executor.submit(() -> claimAfter(start, outboxId, NOW));
            Future<Optional<ClaimedCaseCommandDelivery>> second =
                    executor.submit(() -> claimAfter(start, outboxId, NOW));
            start.countDown();

            List<ClaimedCaseCommandDelivery> owners =
                    List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))
                            .stream()
                            .flatMap(Optional::stream)
                            .toList();
            assertThat(owners).hasSize(1);

            ClaimedCaseCommandDelivery expired = owners.getFirst();
            OffsetDateTime reclaimAt = NOW.plusSeconds(31);
            ClaimedCaseCommandDelivery reclaimed =
                    store.claimById(outboxId, reclaimAt, LEASE).orElseThrow();
            assertThat(reclaimed.leaseToken()).isNotEqualTo(expired.leaseToken());
            assertThat(reclaimed.attemptCount()).isEqualTo(2);

            assertThat(store.markDelivered(expired, "stale-run", reclaimAt))
                    .isFalse();
            assertThat(store.markDelivered(reclaimed, "active-run", reclaimAt))
                    .isTrue();
        }

        assertThat(outboxStatus(outboxId)).isEqualTo("DELIVERED");
        assertThat(commandStatus("CMD_EXCLUSIVE"))
                .isEqualTo("ORCHESTRATION_ACCEPTED");
        assertThat(
                        jdbc.queryForObject(
                                "select temporal_run_id from case_command_outbox where id = ?",
                                String.class,
                                outboxId))
                .isEqualTo("active-run");
    }

    @Test
    void skipLockedWorkersClaimDisjointBatches() throws Exception {
        Set<String> expected = new HashSet<>();
        for (int index = 1; index <= 4; index++) {
            expected.add(insertDelivery("BATCH" + index, NOW));
        }
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<List<ClaimedCaseCommandDelivery>> first =
                    executor.submit(() -> claimBatchAfter(start));
            Future<List<ClaimedCaseCommandDelivery>> second =
                    executor.submit(() -> claimBatchAfter(start));
            start.countDown();

            List<ClaimedCaseCommandDelivery> firstBatch =
                    first.get(15, TimeUnit.SECONDS);
            List<ClaimedCaseCommandDelivery> secondBatch =
                    second.get(15, TimeUnit.SECONDS);
            assertThat(firstBatch).hasSize(2);
            assertThat(secondBatch).hasSize(2);
            assertThat(firstBatch)
                    .extracting(ClaimedCaseCommandDelivery::outboxId)
                    .doesNotContainAnyElementsOf(
                            secondBatch.stream()
                                    .map(ClaimedCaseCommandDelivery::outboxId)
                                    .toList());

            List<String> claimedIds = new ArrayList<>();
            firstBatch.forEach(delivery -> claimedIds.add(delivery.outboxId()));
            secondBatch.forEach(delivery -> claimedIds.add(delivery.outboxId()));
            assertThat(claimedIds).containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    @Test
    void retryAvailabilityAndDeadLetterStateArePersistedDeterministically() {
        String outboxId = insertDelivery("DEAD", NOW);
        ClaimedCaseCommandDelivery first =
                store.claimById(outboxId, NOW, LEASE).orElseThrow();
        OffsetDateTime retryAt = NOW.plusSeconds(5);

        assertThat(
                        store.markRetry(
                                first,
                                "TEMPORAL_UNAVAILABLE",
                                "service unavailable",
                                retryAt,
                                NOW.plusSeconds(1)))
                .isTrue();
        assertThat(store.claimById(outboxId, NOW.plusSeconds(4), LEASE))
                .isEmpty();

        ClaimedCaseCommandDelivery retry =
                store.claimById(outboxId, retryAt, LEASE).orElseThrow();
        assertThat(retry.attemptCount()).isEqualTo(2);
        assertThat(
                        store.resolvePermanentFailure(
                                retry,
                                "TEMPORAL_INVALID_ARGUMENT",
                                "invalid request",
                                retryAt.plusSeconds(1)))
                .isEqualTo(PermanentFailureResolution.DEAD_LETTERED);

        assertThat(outboxStatus(outboxId)).isEqualTo("DEAD_LETTER");
        assertThat(commandStatus("CMD_DEAD")).isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "select status_reason_code from case_command where id = ?",
                                String.class,
                                "CMD_DEAD"))
                .isEqualTo("TEMPORAL_INVALID_ARGUMENT");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ORCHESTRATION_ACCEPTED",
                "APPLIED",
                "SHADOW_COMPLETED",
                "REJECTED",
                "FAILED",
                "EXPIRED"
            })
    void permanentFailureReconcilesWhenTheCommandAlreadyLeftPending(
            String existingStatus) {
        String suffix = "RESOLVED_" + existingStatus;
        String commandRowId = "CMD_" + suffix;
        String outboxId = insertDelivery(suffix, NOW);
        jdbc.update(
                "update case_command set command_status = ?, updated_at = ? where id = ?",
                existingStatus,
                NOW.plusSeconds(1),
                commandRowId);
        ClaimedCaseCommandDelivery delivery =
                store.claimById(outboxId, NOW.plusSeconds(2), LEASE).orElseThrow();

        assertThat(
                        store.resolvePermanentFailure(
                                delivery,
                                "TEMPORAL_UPDATE_REJECTED",
                                "late permanent response",
                                NOW.plusSeconds(3)))
                .isEqualTo(PermanentFailureResolution.RECONCILED);

        assertThat(outboxStatus(outboxId)).isEqualTo("RECONCILED");
        assertThat(commandStatus(commandRowId)).isEqualTo(existingStatus);
        assertThat(
                        jdbc.queryForObject(
                                "select last_error_code from case_command_outbox where id = ?",
                                String.class,
                                outboxId))
                .isEqualTo("COMMAND_ALREADY_" + existingStatus);
    }

    @Test
    void permanentFailureDoesNotResolveAnExpiredLease() {
        String outboxId = insertDelivery("STALE_PERMANENT", NOW);
        ClaimedCaseCommandDelivery delivery =
                store.claimById(outboxId, NOW, LEASE).orElseThrow();

        assertThat(
                        store.resolvePermanentFailure(
                                delivery,
                                "TEMPORAL_UPDATE_REJECTED",
                                "completion arrived after lease expiry",
                                delivery.leaseExpiresAt()))
                .isEqualTo(PermanentFailureResolution.STALE_LEASE);

        assertThat(outboxStatus(outboxId)).isEqualTo("CLAIMED");
        assertThat(commandStatus("CMD_STALE_PERMANENT"))
                .isEqualTo("PENDING_ORCHESTRATION");
    }

    @Test
    void permanentFailureOutboxAndCommandRollBackTogetherOnCommandFailure() {
        String outboxId = insertDelivery("DEAD_ATOMIC", NOW);
        ClaimedCaseCommandDelivery delivery =
                store.claimById(outboxId, NOW, LEASE).orElseThrow();
        installRejectingFailedCommandUpdateTrigger();
        try {
            assertThatThrownBy(
                            () ->
                                    store.resolvePermanentFailure(
                                            delivery,
                                            "TEMPORAL_UPDATE_REJECTED",
                                            "forced transaction rollback",
                                            NOW.plusSeconds(1)))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            removeRejectingFailedCommandUpdateTrigger();
        }

        assertThat(outboxStatus(outboxId)).isEqualTo("CLAIMED");
        assertThat(commandStatus("CMD_DEAD_ATOMIC"))
                .isEqualTo("PENDING_ORCHESTRATION");
    }

    @Test
    void deadlineFailureAtomicallyExpiresTheCommandAndDeadLettersTheOutbox() {
        String outboxId = insertDelivery("EXPIRED", NOW);
        OffsetDateTime expiredAt = NOW.plusHours(1);
        ClaimedCaseCommandDelivery delivery =
                store.claimById(outboxId, expiredAt, LEASE).orElseThrow();

        assertThat(
                        store.markExpired(
                                delivery,
                                "COMMAND_DEADLINE_EXPIRED",
                                "command deadline elapsed before Temporal execution",
                                expiredAt))
                .isEqualTo(ExpirationResolution.EXPIRED);

        assertThat(outboxStatus(outboxId)).isEqualTo("DEAD_LETTER");
        assertThat(commandStatus("CMD_EXPIRED")).isEqualTo("EXPIRED");
        assertThat(
                        jdbc.queryForObject(
                                "select status_reason_code from case_command where id = ?",
                                String.class,
                                "CMD_EXPIRED"))
                .isEqualTo("COMMAND_DEADLINE_EXPIRED");
        assertThat(
                        jdbc.queryForObject(
                                "select last_error_code from case_command_outbox where id = ?",
                                String.class,
                                outboxId))
                .isEqualTo("COMMAND_DEADLINE_EXPIRED");
    }

    @Test
    void deadlineRejectionReconcilesTheOutboxWithoutOverwritingAnAppliedCommand() {
        String outboxId = insertDelivery("APPLIED_EXPIRY_RACE", NOW);
        OffsetDateTime appliedAt = NOW.plusMinutes(30);
        OffsetDateTime resolvedAt = NOW.plusHours(1);
        jdbc.update(
                """
                update case_command
                   set command_status = 'APPLIED',
                       orchestrated_at = ?,
                       applied_at = ?,
                       updated_at = ?
                 where id = 'CMD_APPLIED_EXPIRY_RACE'
                """,
                appliedAt,
                appliedAt,
                appliedAt);
        ClaimedCaseCommandDelivery delivery =
                store.claimById(outboxId, resolvedAt, LEASE).orElseThrow();

        assertThat(
                        store.markExpired(
                                delivery,
                                "COMMAND_DEADLINE_EXPIRED",
                                "workflow rejection arrived after the domain commit",
                                resolvedAt))
                .isEqualTo(ExpirationResolution.RECONCILED);

        assertThat(outboxStatus(outboxId)).isEqualTo("RECONCILED");
        assertThat(commandStatus("CMD_APPLIED_EXPIRY_RACE")).isEqualTo("APPLIED");
        assertThat(
                        jdbc.queryForObject(
                                "select last_error_code from case_command_outbox where id = ?",
                                String.class,
                                outboxId))
                .isEqualTo("COMMAND_ALREADY_APPLIED");
    }

    @Test
    void deliveredOutboxAndAcceptedCommandRollBackTogetherOnCommandFailure() {
        String outboxId = insertDelivery("ATOMIC", NOW);
        ClaimedCaseCommandDelivery delivery =
                store.claimById(outboxId, NOW, LEASE).orElseThrow();
        installRejectingCommandUpdateTrigger();
        try {
            assertThatThrownBy(
                            () ->
                                    store.markDelivered(
                                            delivery,
                                            "run-atomic",
                                            NOW.plusSeconds(1)))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            removeRejectingCommandUpdateTrigger();
        }

        assertThat(outboxStatus(outboxId)).isEqualTo("CLAIMED");
        assertThat(commandStatus("CMD_ATOMIC"))
                .isEqualTo("PENDING_ORCHESTRATION");
    }

    private Optional<ClaimedCaseCommandDelivery> claimAfter(
            CountDownLatch start, String outboxId, OffsetDateTime now)
            throws InterruptedException {
        start.await();
        return store.claimById(outboxId, now, LEASE);
    }

    private List<ClaimedCaseCommandDelivery> claimBatchAfter(CountDownLatch start)
            throws InterruptedException {
        start.await();
        return store.claimBatch(NOW, LEASE, 2);
    }

    private String insertDelivery(String suffix, OffsetDateTime availableAt) {
        String caseId = "CASE_OUTBOX_" + suffix;
        String commandRowId = "CMD_" + suffix;
        String commandId = "command-" + suffix.toLowerCase();
        String outboxId = "COUT_" + suffix;
        jdbc.update(
                """
                insert into fulfillment_dispute_case (
                    id, user_id, merchant_id, creation_idempotency_key,
                    case_type, case_status, initiator_role, initiator_id,
                    respondent_role, respondent_id, risk_level, title, description,
                    current_room, created_by, updated_by
                ) values (?, ?, ?, ?, 'DISPUTE', 'EVIDENCE_OPEN', 'USER', ?,
                    'MERCHANT', ?, 'HIGH', 'Outbox test case',
                    'Temporal command delivery fixture.', 'EVIDENCE', 'test', 'test')
                """,
                caseId,
                "user-" + suffix,
                "merchant-" + suffix,
                "create-outbox-" + suffix,
                "user-" + suffix,
                "merchant-" + suffix);
        jdbc.update(
                """
                insert into case_command (
                    id, command_id, tenant_surrogate, case_id,
                    case_command_sequence, command_type, room_type, room_epoch,
                    actor_id, actor_role, actor_scopes_json,
                    payload_schema_version, payload_uri, payload_sha256,
                    payload_size_bytes, expected_process_revision,
                    occurred_at, deadline_at, traceparent, request_hash,
                    command_status, accepted_at, created_at, updated_at
                ) values (?, ?, 'tenant-outbox', ?, 1, 'EVIDENCE_SUBMIT',
                    'EVIDENCE', 0, ?, 'USER', '["case:command"]'::jsonb,
                    'evidence-command.v1', ?, ?, 10, 0, ?, ?, ?, ?,
                    'PENDING_ORCHESTRATION', ?, ?, ?)
                """,
                commandRowId,
                commandId,
                caseId,
                "user-" + suffix,
                "urn:test:" + suffix.toLowerCase(),
                "a".repeat(64),
                NOW,
                NOW.plusHours(1),
                "00-11111111111111111111111111111111-2222222222222222-01",
                "b".repeat(64),
                NOW,
                NOW,
                NOW);
        jdbc.update(
                """
                insert into case_command_outbox (
                    id, case_command_id, tenant_surrogate, case_id,
                    workflow_id, workflow_type, task_queue, delivery_kind,
                    update_id, outbox_status, available_at, attempt_count,
                    created_at, updated_at
                ) values (?, ?, 'tenant-outbox', ?, ?, 'CaseProcessWorkflow',
                    'case-control', 'UPDATE_WITH_START', ?, 'PENDING', ?, 0, ?, ?)
                """,
                outboxId,
                commandRowId,
                caseId,
                "case-process:tenant-outbox:" + caseId,
                commandId,
                availableAt,
                NOW,
                NOW);
        return outboxId;
    }

    private String outboxStatus(String outboxId) {
        return jdbc.queryForObject(
                "select outbox_status from case_command_outbox where id = ?",
                String.class,
                outboxId);
    }

    private String commandStatus(String commandId) {
        return jdbc.queryForObject(
                "select command_status from case_command where id = ?",
                String.class,
                commandId);
    }

    private void installRejectingCommandUpdateTrigger() {
        jdbc.execute(
                """
                create or replace function reject_outbox_test_command_update()
                returns trigger language plpgsql as $$
                begin
                    if new.id = 'CMD_ATOMIC'
                       and new.command_status = 'ORCHESTRATION_ACCEPTED' then
                        raise exception 'forced command status failure';
                    end if;
                    return new;
                end;
                $$
                """);
        jdbc.execute(
                """
                create trigger reject_outbox_test_command_update_trigger
                before update on case_command
                for each row execute function reject_outbox_test_command_update()
                """);
    }

    private void removeRejectingCommandUpdateTrigger() {
        jdbc.execute(
                "drop trigger if exists reject_outbox_test_command_update_trigger on case_command");
        jdbc.execute("drop function if exists reject_outbox_test_command_update()");
    }

    private void installRejectingFailedCommandUpdateTrigger() {
        jdbc.execute(
                """
                create or replace function reject_outbox_test_failed_command_update()
                returns trigger language plpgsql as $$
                begin
                    if new.id = 'CMD_DEAD_ATOMIC'
                       and new.command_status = 'FAILED' then
                        raise exception 'forced command failure transition rejection';
                    end if;
                    return new;
                end;
                $$
                """);
        jdbc.execute(
                """
                create trigger reject_outbox_test_failed_command_update_trigger
                before update on case_command
                for each row execute function reject_outbox_test_failed_command_update()
                """);
    }

    private void removeRejectingFailedCommandUpdateTrigger() {
        jdbc.execute(
                "drop trigger if exists reject_outbox_test_failed_command_update_trigger on case_command");
        jdbc.execute(
                "drop function if exists reject_outbox_test_failed_command_update()");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StoreTestConfiguration {

        @Bean
        @Primary
        ObjectMapper storeObjectMapper() {
            return JsonMapper.builder().build();
        }
    }
}
