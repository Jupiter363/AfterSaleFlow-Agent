package com.example.dispute.workflow.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.common.exception.IdempotencyConflictException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.TenantAuthorityProperties;
import com.example.dispute.infrastructure.persistence.repository.AuditLogRepository;
import com.example.dispute.workflow.application.command.AcceptCaseCommand;
import com.example.dispute.workflow.application.command.CaseCommandAcceptance;
import com.example.dispute.workflow.application.command.CaseCommandDeliveryTrigger;
import com.example.dispute.workflow.application.command.CaseCommandService;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.infrastructure.security.ConfiguredTenantAuthority;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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

@DataJpaTest(
        properties = {
            "spring.jpa.hibernate.ddl-auto=validate",
            "app.security.tenant-authority.surrogate=legacy-default"
        })
@Testcontainers
@Import({
    CaseCommandService.class,
    ConfiguredTenantAuthority.class,
    CaseCommandServiceIntegrationTest.CommandTestConfiguration.class
})
@EnableConfigurationProperties(TenantAuthorityProperties.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CaseCommandServiceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "case_command_service")
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
                                + "/case_command_service");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @Autowired private CaseCommandService service;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void persistsOneCommandAndOutboxAndCommitsConflictAudit() {
        String caseId = "CASE_CommandIdempotency";
        insertEvidenceCase(caseId, "user-idempotency", "merchant-idempotency");

        var first =
                service.accept(
                        caseId,
                        "command.idempotency.1",
                        command("urn:command:idempotency", "a".repeat(64)),
                        user("user-idempotency"),
                        "TRACE_first",
                        "REQ_first",
                        null);
        var replay =
                service.accept(
                        caseId,
                        "command.idempotency.1",
                        command("urn:command:idempotency", "a".repeat(64)),
                        user("user-idempotency"),
                        "TRACE_replay",
                        "REQ_replay",
                        "00-11111111111111111111111111111111-2222222222222222-01");

        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.command().requestHash()).isEqualTo(first.command().requestHash());
        assertThat(countRows("case_command", caseId)).isEqualTo(1);
        assertThat(countRows("case_command_outbox", caseId)).isEqualTo(1);

        assertThatThrownBy(
                        () ->
                                service.accept(
                                        caseId,
                                        "command.idempotency.1",
                                        command(
                                                "urn:command:idempotency",
                                                "b".repeat(64)),
                                        user("user-idempotency"),
                                        "TRACE_conflict",
                                        "REQ_conflict",
                                        null))
                .isInstanceOf(IdempotencyConflictException.class);

        assertThat(countRows("case_command", caseId)).isEqualTo(1);
        assertThat(countRows("case_command_outbox", caseId)).isEqualTo(1);
        assertThat(auditLogRepository.findAllByCaseIdOrderByCreatedAtDesc(caseId))
                .singleElement()
                .satisfies(
                        audit -> {
                            assertThat(audit.getOutcome()).isEqualTo("CONFLICT");
                            assertThat(audit.getAction())
                                    .isEqualTo("CASE_COMMAND_IDEMPOTENCY_CONFLICT");
                        });
    }

    @Test
    void rollsBackTheCommandWhenTheOutboxInsertFails() {
        String caseId = "CASE_CommandAtomicity";
        insertEvidenceCase(caseId, "user-atomicity", "merchant-atomicity");
        installFailingOutboxTrigger();
        try {
            assertThatThrownBy(
                            () ->
                                    service.accept(
                                            caseId,
                                            "command.atomic-fail",
                                            command(
                                                    "urn:command:atomicity",
                                                    "a".repeat(64)),
                                            user("user-atomicity"),
                                            "TRACE_atomicity",
                                            "REQ_atomicity",
                                            null))
                    .isInstanceOf(RuntimeException.class);

            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from case_command where command_id = ?",
                                    Long.class,
                                    "command.atomic-fail"))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from case_command_outbox where update_id = ?",
                                    Long.class,
                                    "command.atomic-fail"))
                    .isZero();
        } finally {
            removeFailingOutboxTrigger();
        }
    }

    @Test
    void allocatesStrictlyIncreasingSequencesForConcurrentCaseCommands() throws Exception {
        String caseId = "CASE_CommandSequence";
        insertEvidenceCase(caseId, "user-sequence", "merchant-sequence");
        int commandCount = 8;
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(commandCount)) {
            List<Future<CaseCommandAcceptance>> futures = new ArrayList<>();
            for (int index = 0; index < commandCount; index++) {
                int commandIndex = index;
                futures.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    return service.accept(
                                            caseId,
                                            "command.sequence." + commandIndex,
                                            command(
                                                    "urn:command:sequence:"
                                                            + commandIndex,
                                                    "a".repeat(64)),
                                            user("user-sequence"),
                                            "TRACE_sequence_" + commandIndex,
                                            "REQ_sequence_" + commandIndex,
                                            null);
                                }));
            }
            start.countDown();
            for (Future<CaseCommandAcceptance> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(
                        jdbc.queryForList(
                                "select case_command_sequence from case_command where case_id = ? order by case_command_sequence",
                                Long.class,
                                caseId))
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from case_command_outbox where case_id = ?",
                                Long.class,
                                caseId))
                .isEqualTo(commandCount);
    }

    @Test
    void serializesConcurrentConflictingUsesOfOneTenantCommandId() throws Exception {
        String firstCaseId = "CASE_CommandConflictOne";
        String secondCaseId = "CASE_CommandConflictTwo";
        insertEvidenceCase(firstCaseId, "user-concurrent", "merchant-concurrent-one");
        insertEvidenceCase(secondCaseId, "user-concurrent", "merchant-concurrent-two");
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Object>> futures =
                    List.of(
                            executor.submit(
                                    () ->
                                            acceptOrCapture(
                                                    start,
                                                    firstCaseId,
                                                    "a".repeat(64))),
                            executor.submit(
                                    () ->
                                            acceptOrCapture(
                                                    start,
                                                    secondCaseId,
                                                    "b".repeat(64))));
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(results.stream().filter(CaseCommandAcceptance.class::isInstance))
                    .hasSize(1);
            assertThat(results.stream().filter(IdempotencyConflictException.class::isInstance))
                    .hasSize(1);
        }

        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from case_command where command_id = ?",
                                Long.class,
                                "command.concurrent-conflict"))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from case_command_outbox where update_id = ?",
                                Long.class,
                                "command.concurrent-conflict"))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                """
                                select count(*) from audit_log
                                where resource_id = 'command.concurrent-conflict'
                                  and outcome = 'CONFLICT'
                                """,
                                Long.class))
                .isEqualTo(1);
    }

    private Object acceptOrCapture(CountDownLatch start, String caseId, String hash)
            throws InterruptedException {
        start.await();
        try {
            return service.accept(
                    caseId,
                    "command.concurrent-conflict",
                    command("urn:command:concurrent", hash),
                    user("user-concurrent"),
                    "TRACE_concurrent_" + hash.charAt(0),
                    "REQ_concurrent_" + hash.charAt(0),
                    null);
        } catch (IdempotencyConflictException exception) {
            return exception;
        }
    }

    private void insertEvidenceCase(String caseId, String userId, String merchantId) {
        String roomId = "ROOM_" + caseId.substring("CASE_".length());
        String epochId = "EPOCH_" + caseId.substring("CASE_".length());
        jdbc.update(
                """
                insert into fulfillment_dispute_case (
                    id, user_id, merchant_id, creation_idempotency_key,
                    case_type, case_status, initiator_role, initiator_id,
                    respondent_role, respondent_id, risk_level, title, description,
                    current_room, created_by, updated_by
                ) values (?, ?, ?, ?, 'DISPUTE', 'EVIDENCE_OPEN', 'USER', ?,
                    'MERCHANT', ?, 'HIGH', 'Command test case',
                    'Durable command acceptance fixture.', 'EVIDENCE', 'test', 'test')
                """,
                caseId,
                userId,
                merchantId,
                "create-" + caseId,
                userId,
                merchantId);
        jdbc.update(
                """
                insert into case_room (
                    id, case_id, room_type, room_status, opened_at, created_by, updated_by
                ) values (?, ?, 'EVIDENCE', 'OPEN', now(), 'test', 'test')
                """,
                roomId,
                caseId);
        jdbc.update(
                """
                insert into case_process_projection (
                    case_id, tenant_surrogate, macro_phase, current_room, room_phase,
                    writer_mode, process_revision, room_epoch, fencing_token
                ) values (?, 'legacy-default', 'EVIDENCE_OPEN', 'EVIDENCE', 'OPEN',
                    'LEGACY', 0, 0, 0)
                """,
                caseId);
        jdbc.update(
                """
                insert into case_room_epoch (
                    id, tenant_surrogate, case_id, room_id, room_type, room_epoch,
                    writer_mode, lifecycle_status, process_revision, room_revision,
                    fencing_token, stream_protocol, activated_at
                ) values (?, 'legacy-default', ?, ?, 'EVIDENCE', 0, 'LEGACY',
                    'ACTIVE', 0, 0, 0, 'agent_stream.v1', now())
                """,
                epochId,
                caseId,
                roomId);
    }

    private void installFailingOutboxTrigger() {
        jdbc.execute(
                """
                create or replace function reject_test_case_command_outbox()
                returns trigger language plpgsql as $$
                begin
                    if new.update_id = 'command.atomic-fail' then
                        raise exception 'forced outbox failure';
                    end if;
                    return new;
                end;
                $$
                """);
        jdbc.execute(
                """
                create trigger reject_test_case_command_outbox_trigger
                before insert on case_command_outbox
                for each row execute function reject_test_case_command_outbox()
                """);
    }

    private long countRows(String table, String caseId) {
        if (!table.equals("case_command") && !table.equals("case_command_outbox")) {
            throw new IllegalArgumentException("unsupported test table");
        }
        return jdbc.queryForObject(
                "select count(*) from " + table + " where case_id = ?", Long.class, caseId);
    }

    private void removeFailingOutboxTrigger() {
        jdbc.execute(
                "drop trigger if exists reject_test_case_command_outbox_trigger on case_command_outbox");
        jdbc.execute("drop function if exists reject_test_case_command_outbox()");
    }

    private static AcceptCaseCommand command(String uri, String payloadHash) {
        return new AcceptCaseCommand(
                CommandType.EVIDENCE_SUBMIT,
                RoomType.EVIDENCE,
                0,
                new PayloadRef("evidence-command.v1", uri, payloadHash, 128),
                0,
                NOW.plusSeconds(3600));
    }

    private static AuthenticatedActor user(String actorId) {
        return new AuthenticatedActor(actorId, ActorRole.USER);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CommandTestConfiguration {

        @Bean
        @Primary
        Clock commandClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        @Primary
        ObjectMapper commandObjectMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        CaseCommandDeliveryTrigger commandDeliveryTrigger() {
            return outboxId -> {};
        }
    }
}
