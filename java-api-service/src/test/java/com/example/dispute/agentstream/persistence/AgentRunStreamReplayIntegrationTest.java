package com.example.dispute.agentstream.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory;
import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.agentstream.application.AgentRunReconciledFinalStore;
import com.example.dispute.agentstream.application.AgentRunStreamEventService;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore.AppendReceipt;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore.BatchAppendReceipt;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore.CompatibilityMismatchException;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore.NonRunningAttemptException;
import com.example.dispute.agentstream.infrastructure.persistence.PostgresAgentRunV2EventStore;
import com.example.dispute.agentstream.infrastructure.persistence.StreamBackfillCoordinator;
import com.example.dispute.agentstream.infrastructure.persistence.StreamBackfillCoordinator.CursorStatus;
import com.example.dispute.agentstream.infrastructure.persistence.StreamCompatibilityMode;
import com.example.dispute.agentstream.infrastructure.persistence.AgentRunStreamEventRepository;
import com.example.dispute.agentstream.infrastructure.delivery.AgentRunStreamWakeupPublisher;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.infrastructure.persistence.repository.AgentRunAttemptRepository;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.room.application.AccessSessionResolver;
import com.example.dispute.room.application.SessionPermissionService;
import com.example.dispute.room.domain.PermissionLevel;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent.Payload;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Testcontainers
@Import({
    PostgresAgentRunV2EventStore.class,
    StreamBackfillCoordinator.class,
    com.example.dispute.agentstream.infrastructure.persistence.JpaAgentRunLedger.class,
    AgentRunStreamReplayIntegrationTest.JsonTestConfig.class
})
class AgentRunStreamReplayIntegrationTest {

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "agent_run_stream_replay")
                    .withEnv("POSTGRES_USER", "dispute_test")
                    .withEnv("POSTGRES_PASSWORD", "local_test_password")
                    .withExposedPorts(5432);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AgentRunStreamReplayIntegrationTest::jdbcUrl);
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AgentRunLedger ledger;
    @Autowired private PostgresAgentRunV2EventStore eventStore;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AgentRunRepository runRepository;
    @Autowired private AgentRunAttemptRepository attemptRepository;
    @Autowired private AgentRunStreamEventRepository eventRepository;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void genericAppendStillRejectsEventsAfterFinal() {
        ReadyRun ready = readyRun("ATTEMPT_FINALIZATION_APPEND_GUARD");
        AgentStreamEvent terminalError = finalizationError(
                ready.agentRunId(), ready.attemptId(), 4, Audience.USER, false);

        jdbc.update(
                "update agent_run_attempt set attempt_status = 'RUNNING' where id = ?",
                ready.attemptId());
        assertThatThrownBy(() -> eventStore.append(terminalError))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal event");

        jdbc.update(
                "update agent_run_attempt set attempt_status = 'ABORTED' where id = ?",
                ready.attemptId());
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            assertThatThrownBy(() ->
                            eventStore.appendRecoveryErrorInCurrentTransaction(terminalError))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("terminal event");
            status.setRollbackOnly();
        });

        assertThat(eventStore.replay(ready.agentRunId(), ready.attemptId(), -1, 100))
                .extracting(AgentStreamEvent::eventType, AgentStreamEvent::sequenceNo)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                StreamEventType.ATTEMPT_STARTED, 0L),
                        org.assertj.core.groups.Tuple.tuple(
                                StreamEventType.VISIBLE_DELTA, 1L),
                        org.assertj.core.groups.Tuple.tuple(
                                StreamEventType.VISIBLE_DELTA, 2L),
                        org.assertj.core.groups.Tuple.tuple(StreamEventType.FINAL, 3L));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void nonRetryableFinalizationFailureReplacesHiddenFinalWithDurableError() {
        ReadyRun ready = readyRun("ATTEMPT_FINALIZATION_REJECTED");
        Object command = finalizationFailureCommand(ready);

        Object receipt = recordFinalizationFailure(command);

        assertThat(receipt).isNotNull();
        assertThat(recordAccessor(receipt, "agentRunId")).isEqualTo(ready.agentRunId());
        assertThat(recordAccessor(receipt, "attemptId")).isEqualTo(ready.attemptId());
        assertThat(recordAccessor(receipt, "resultHash")).isEqualTo(ready.resultHash());
        assertThat(recordAccessor(receipt, "terminalSequenceNo")).isEqualTo(4L);
        assertThat(recordAccessor(receipt, "attemptStatus")).isEqualTo(AgentRunAttemptStatus.ABORTED);
        assertThat(recordAccessor(receipt, "safeErrorCode"))
                .isEqualTo("INTAKE_INITIATOR_MATRIX_AUTHORITY_INVALID");
        assertThat(recordAccessor(receipt, "replayed")).isEqualTo(false);

        assertThat(jdbc.queryForMap(
                        """
                        select run_status, finalization_status, result_ready_attempt_id,
                               committed_attempt_id, final_result_hash, error_code,
                               error_retryable, error_message, stop_reason,
                               final_stream_sequence_no, committed_manifest_id,
                               committed_manifest_hash
                          from agent_run where id = ?
                        """,
                        ready.agentRunId()))
                .satisfies(state -> {
                    assertThat(state.get("run_status")).isEqualTo("ABORTED");
                    assertThat(state.get("finalization_status")).isEqualTo("UNCOMMITTED");
                    assertThat(state.get("result_ready_attempt_id"))
                            .isEqualTo(ready.attemptId());
                    assertThat(state.get("committed_attempt_id")).isNull();
                    assertThat(state.get("final_result_hash")).isEqualTo(ready.resultHash());
                    assertThat(state.get("error_code"))
                            .isEqualTo("INTAKE_INITIATOR_MATRIX_AUTHORITY_INVALID");
                    assertThat(state.get("error_retryable")).isEqualTo(false);
                    assertThat(state.get("error_message"))
                            .isEqualTo("Agent run finalization was rejected.");
                    assertThat(state.get("stop_reason")).isEqualTo("FINALIZATION_REJECTED");
                    assertThat(state.get("final_stream_sequence_no")).isNull();
                    assertThat(state.get("committed_manifest_id")).isNull();
                    assertThat(state.get("committed_manifest_hash")).isNull();
                });
        assertThat(jdbc.queryForMap(
                        """
                        select attempt_status, result_hash, last_sequence_no,
                               public_output_emitted, final_frame_observed,
                               error_code, error_retryable, termination_code
                          from agent_run_attempt where id = ?
                        """,
                        ready.attemptId()))
                .satisfies(state -> {
                    assertThat(state.get("attempt_status")).isEqualTo("ABORTED");
                    assertThat(state.get("result_hash")).isEqualTo(ready.resultHash());
                    assertThat(state.get("last_sequence_no")).isEqualTo(4L);
                    assertThat(state.get("public_output_emitted")).isEqualTo(true);
                    assertThat(state.get("final_frame_observed")).isEqualTo(true);
                    assertThat(state.get("error_code"))
                            .isEqualTo("INTAKE_INITIATOR_MATRIX_AUTHORITY_INVALID");
                    assertThat(state.get("error_retryable")).isEqualTo(false);
                    assertThat(state.get("termination_code")).isEqualTo("FAIL_LOGICAL_RUN");
                });

        List<AgentStreamEvent> persisted =
                eventStore.replay(ready.agentRunId(), ready.attemptId(), -1, 100);
        assertThat(persisted)
                .extracting(AgentStreamEvent::eventType, AgentStreamEvent::sequenceNo)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                StreamEventType.ATTEMPT_STARTED, 0L),
                        org.assertj.core.groups.Tuple.tuple(
                                StreamEventType.VISIBLE_DELTA, 1L),
                        org.assertj.core.groups.Tuple.tuple(
                                StreamEventType.VISIBLE_DELTA, 2L),
                        org.assertj.core.groups.Tuple.tuple(StreamEventType.FINAL, 3L),
                        org.assertj.core.groups.Tuple.tuple(StreamEventType.ERROR, 4L));
        AgentStreamEvent error = persisted.getLast();
        assertThat(error.audience()).isEqualTo(Audience.USER);
        assertThat(error.payload().errorCode())
                .isEqualTo("INTAKE_INITIATOR_MATRIX_AUTHORITY_INVALID");
        assertThat(error.payload().retryable()).isFalse();
        assertThat(jdbc.queryForObject(
                        "select count(*) from agent_execution_manifest where case_id = ?",
                        Long.class,
                        ready.caseId()))
                .isZero();

        AccessSessionResolver resolver = mock(AccessSessionResolver.class);
        AuthenticatedActor actor = new AuthenticatedActor("user-persistence", ActorRole.USER);
        when(resolver.resolve(ready.caseId(), actor))
                .thenReturn(CaseAccessSessionEntity.create(
                        "CAS_" + ready.attemptId(),
                        "tenant-persistence",
                        ready.caseId(),
                        actor.actorId(),
                        ActorRole.USER,
                        PermissionLevel.PARTY_USER,
                        "test"));
        AgentRunStreamEventService publicStream = new AgentRunStreamEventService(
                runRepository,
                attemptRepository,
                eventRepository,
                mock(AgentRunStreamWakeupPublisher.class),
                resolver,
                new SessionPermissionService(),
                objectMapper);
        assertThat(publicStream.replay(ready.agentRunId(), -1L, actor))
                .extracting(view -> view.type())
                .containsExactly(
                        "attempt_started", "visible_delta", "visible_delta", "error");
        assertThat(publicStream.replay(
                        ready.agentRunId(),
                        "v2:" + ready.attemptId() + ":2",
                        actor))
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.type()).isEqualTo("error");
                    assertThat(view.sequence()).isEqualTo(4L);
                    assertThat(view.code())
                            .isEqualTo("INTAKE_INITIATOR_MATRIX_AUTHORITY_INVALID");
                    assertThat(view.retryable()).isFalse();
                    assertThat(view.message()).isEqualTo("数字人生成失败，请稍后重试。");
                });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void finalizationFailureReplayIsExactAndIdempotent() {
        ReadyRun ready = readyRun("ATTEMPT_FINALIZATION_REPLAY");
        Object command = finalizationFailureCommand(ready);

        Object inserted = recordFinalizationFailure(command);
        Object replayed = recordFinalizationFailure(command);

        assertThat(recordAccessor(inserted, "replayed")).isEqualTo(false);
        assertThat(recordAccessor(replayed, "replayed")).isEqualTo(true);
        for (String accessor : List.of(
                "agentRunId",
                "attemptId",
                "resultHash",
                "terminalSequenceNo",
                "attemptStatus",
                "safeErrorCode")) {
            assertThat(recordAccessor(replayed, accessor))
                    .as(accessor)
                    .isEqualTo(recordAccessor(inserted, accessor));
        }
        assertThat(jdbc.queryForObject(
                        """
                        select count(*) from agent_run_stream_event
                         where agent_run_id = ? and agent_run_attempt_id = ?
                           and sequence_no = 4 and event_type = 'error'
                        """,
                        Long.class,
                        ready.agentRunId(),
                        ready.attemptId()))
                .isEqualTo(1L);

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                        appendFinalizationErrorInCurrentTransaction(finalizationError(
                                ready.agentRunId(),
                                ready.attemptId(),
                                5,
                                Audience.USER,
                                false))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void finalizationFailureRejectsStaleCommittedCrossAttemptAndTamperedBindings() {
        ReadyRun ready = readyRun("ATTEMPT_FINALIZATION_TAMPER");
        Object exact = finalizationFailureCommand(ready);
        recordFinalizationFailure(exact);

        for (Object stale : List.of(
                finalizationFailureCommand(
                        ready,
                        ready.agentRunId(),
                        ready.attemptId(),
                        "command-stale",
                        ready.commandRequestHash(),
                        ready.resultHash(),
                        ready.finalSequenceNo(),
                        ready.publicOutputEmitted(),
                        "INTAKE_INITIATOR_MATRIX_AUTHORITY_INVALID"),
                finalizationFailureCommand(
                        ready,
                        ready.agentRunId(),
                        ready.attemptId(),
                        ready.commandId(),
                        "d".repeat(64),
                        ready.resultHash(),
                        ready.finalSequenceNo(),
                        ready.publicOutputEmitted(),
                        "INTAKE_INITIATOR_MATRIX_AUTHORITY_INVALID"),
                finalizationFailureCommand(
                        ready,
                        ready.agentRunId(),
                        ready.attemptId(),
                        ready.commandId(),
                        ready.commandRequestHash(),
                        "e".repeat(64),
                        ready.finalSequenceNo(),
                        ready.publicOutputEmitted(),
                        "INTAKE_INITIATOR_MATRIX_AUTHORITY_INVALID"),
                finalizationFailureCommand(
                        ready,
                        ready.agentRunId(),
                        ready.attemptId(),
                        ready.commandId(),
                        ready.commandRequestHash(),
                        ready.resultHash(),
                        ready.finalSequenceNo() - 1,
                        ready.publicOutputEmitted(),
                        "INTAKE_INITIATOR_MATRIX_AUTHORITY_INVALID"),
                finalizationFailureCommand(
                        ready,
                        ready.agentRunId(),
                        "ATTEMPT_CROSS_BOUNDARY",
                        ready.commandId(),
                        ready.commandRequestHash(),
                        ready.resultHash(),
                        ready.finalSequenceNo(),
                        ready.publicOutputEmitted(),
                        "INTAKE_INITIATOR_MATRIX_AUTHORITY_INVALID"),
                finalizationFailureCommand(
                        ready,
                        ready.agentRunId(),
                        ready.attemptId(),
                        ready.commandId(),
                        ready.commandRequestHash(),
                        ready.resultHash(),
                        ready.finalSequenceNo(),
                        false,
                        "INTAKE_INITIATOR_MATRIX_AUTHORITY_INVALID"),
                finalizationFailureCommand(
                        ready,
                        ready.agentRunId(),
                        ready.attemptId(),
                        ready.commandId(),
                        ready.commandRequestHash(),
                        ready.resultHash(),
                        ready.finalSequenceNo(),
                        ready.publicOutputEmitted(),
                        "AGENT_RUN_FINALIZATION_REJECTED"))) {
            assertRecorderRejected(stale);
        }

        jdbc.update(
                """
                update agent_run_stream_event set audience = 'MERCHANT'
                 where agent_run_id = ? and agent_run_attempt_id = ? and sequence_no = 4
                """,
                ready.agentRunId(),
                ready.attemptId());
        assertRecorderRejected(exact);
        jdbc.update(
                """
                update agent_run_stream_event set audience = 'USER'
                 where agent_run_id = ? and agent_run_attempt_id = ? and sequence_no = 4
                """,
                ready.agentRunId(),
                ready.attemptId());

        String originalPayload = jdbc.queryForObject(
                """
                select payload_json::text from agent_run_stream_event
                 where agent_run_id = ? and agent_run_attempt_id = ? and sequence_no = 4
                """,
                String.class,
                ready.agentRunId(),
                ready.attemptId());
        String originalHash = jdbc.queryForObject(
                """
                select payload_hash from agent_run_stream_event
                 where agent_run_id = ? and agent_run_attempt_id = ? and sequence_no = 4
                """,
                String.class,
                ready.agentRunId(),
                ready.attemptId());
        jdbc.update(
                """
                update agent_run_stream_event
                   set payload_json = jsonb_set(
                       payload_json, '{payload,error_code}', '"TAMPERED_CODE"'::jsonb)
                 where agent_run_id = ? and agent_run_attempt_id = ? and sequence_no = 4
                """,
                ready.agentRunId(),
                ready.attemptId());
        assertRecorderRejected(exact);
        jdbc.update(
                """
                update agent_run_stream_event
                   set payload_json = cast(? as jsonb), payload_hash = ?
                 where agent_run_id = ? and agent_run_attempt_id = ? and sequence_no = 4
                """,
                originalPayload,
                originalHash,
                ready.agentRunId(),
                ready.attemptId());

        jdbc.update(
                """
                update agent_run
                   set finalization_status = 'COMMITTED', committed_attempt_id = ?,
                       committed_manifest_id = 'MANIFEST_COMMITTED_TAMPER',
                       committed_manifest_hash = ?, final_stream_sequence_no = 3,
                       finalized_at = clock_timestamp()
                 where id = ?
                """,
                ready.attemptId(),
                "a".repeat(64),
                ready.agentRunId());
        assertRecorderRejected(exact);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void dedicatedFinalizationAppendRejectsUnauthorizedPostFinalShapes() {
        ReadyRun ready = readyRun("ATTEMPT_FINALIZATION_DEDICATED_APPEND");
        jdbc.update(
                "update agent_run_attempt set attempt_status = 'ABORTED' where id = ?",
                ready.attemptId());
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertDedicatedAppendRejected(transaction, finalizationError(
                ready.agentRunId(), ready.attemptId(), 4, Audience.USER, true));
        assertDedicatedAppendRejected(
                transaction,
                event(
                        ready.agentRunId(),
                        ready.attemptId(),
                        4,
                        StreamEventType.VISIBLE_DELTA,
                        "not an error"));
        assertDedicatedAppendRejected(transaction, finalizationError(
                ready.agentRunId(), ready.attemptId(), 5, Audience.USER, false));
        assertDedicatedAppendRejected(transaction, finalizationError(
                ready.agentRunId(), ready.attemptId(), 4, Audience.MERCHANT, false));
        assertDedicatedAppendRejected(transaction, finalizationError(
                ready.agentRunId(), "ATTEMPT_NOT_OWNED", 4, Audience.USER, false));
        assertThat(eventStore.replay(ready.agentRunId(), ready.attemptId(), -1, 100))
                .extracting(AgentStreamEvent::eventType)
                .containsExactly(
                        StreamEventType.ATTEMPT_STARTED,
                        StreamEventType.VISIBLE_DELTA,
                        StreamEventType.VISIBLE_DELTA,
                        StreamEventType.FINAL);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void dualWriteParityAllowsOnlyFinalizationFinalThenError() {
        ReadyRun ready = readyRun("ATTEMPT_FINALIZATION_PARITY");
        recordFinalizationFailure(finalizationFailureCommand(ready));
        for (long sequence = 0; sequence <= 4; sequence++) {
            TestSource source = source(ready.agentRunId(), ready.attemptId(), sequence);
            assertThat(recordTarget(source, source.payloadHash())).isTrue();
        }
        assertThat(eventStore
                        .validateCompatibility(
                                "agent-stream.v2", ready.agentRunId(), ready.attemptId())
                        .requireCompatible()
                        .terminalParity())
                .isTrue();

        AgentStreamEvent late = event(
                ready.agentRunId(),
                ready.attemptId(),
                5,
                StreamEventType.VISIBLE_DELTA,
                "post-error events remain forbidden");
        String lateJson = ContractJson.canonicalString(objectMapper.valueToTree(late));
        String lateHash = ContractJson.sha256Hex(objectMapper.valueToTree(late));
        TestSource basis = source(ready.agentRunId(), ready.attemptId(), 4);
        TestSource lateSource = new TestSource(
                "P8_POST_FINALIZATION_ERROR_" + ready.attemptId(),
                "agent-stream.v2",
                ready.agentRunId(),
                ready.attemptId(),
                5,
                "visible_delta",
                lateJson,
                lateHash,
                "USER",
                late.occurredAt(),
                basis.actorId(),
                basis.audienceActorIdsJson());
        insertV2Source(lateSource);
        assertThat(recordTarget(lateSource, lateHash)).isTrue();
        var incompatible = eventStore.validateCompatibility(
                "agent-stream.v2", ready.agentRunId(), ready.attemptId());
        assertThat(incompatible.countParity()).isTrue();
        assertThat(incompatible.sequenceParity()).isTrue();
        assertThat(incompatible.terminalParity()).isFalse();
        assertThat(incompatible.reconnectParity()).isFalse();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void batchAppendAndRequiresNewPreserveReplayAcrossConflictsAttemptsAndOuterRollback() {
        insertCase();
        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);
        AgentRunLedger.LogicalRun logical =
                ledger.createOrLoad(AgentRunPersistenceFixtures.logicalRun("ATTEMPT_STREAM_1"));
        AgentRunLedger.Attempt first =
                ledger.startNextAttempt(
                        logical.agentRunId(),
                        AgentRunPersistenceFixtures.allocation(1, "ATTEMPT_STREAM_1"),
                        AgentRunPersistenceFixtures.STARTED_AT);

        List<AgentStreamEvent> firstBatch =
                List.of(
                        event(first.attemptId(), 1, StreamEventType.VISIBLE_DELTA, "alpha"),
                        event(first.attemptId(), 2, StreamEventType.VISIBLE_DELTA, " beta"));

        BatchAppendReceipt receipt = eventStore.appendBatch(firstBatch);

        assertThat(receipt.insertedCount()).isEqualTo(2);
        assertThat(receipt.inserted()).containsExactly(true, true);
        assertThat(receipt.durableHighWatermark()).isEqualTo(2);
        assertThat(attemptProgress(first.attemptId()))
                .isEqualTo(new AttemptProgress(2, true, false));
        assertThat(eventStore.replay(logical.agentRunId(), first.attemptId(), 0, 100))
                .extracting(AgentStreamEvent::sequenceNo)
                .containsExactly(1L, 2L);

        BatchAppendReceipt duplicateBatch = eventStore.appendBatch(firstBatch);
        assertThat(duplicateBatch.inserted()).containsExactly(false, false);
        assertThat(duplicateBatch.insertedCount()).isZero();
        assertThat(duplicateBatch.durableHighWatermark()).isEqualTo(2);
        AgentStreamEvent newSuffix =
                event(first.attemptId(), 3, StreamEventType.VISIBLE_DELTA, " gamma");
        BatchAppendReceipt duplicatePrefixAndNewSuffix =
                eventStore.appendBatch(List.of(firstBatch.get(0), firstBatch.get(1), newSuffix));
        assertThat(duplicatePrefixAndNewSuffix.inserted()).containsExactly(false, false, true);
        assertThat(duplicatePrefixAndNewSuffix.insertedCount()).isEqualTo(1);
        assertThat(duplicatePrefixAndNewSuffix.durableHighWatermark()).isEqualTo(3);
        assertThatThrownBy(
                        () ->
                                eventStore.appendBatch(
                                        List.of(firstBatch.get(1), firstBatch.get(0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly increasing");

        assertThat(eventStore.append(firstBatch.get(0)))
                .satisfies(
                        duplicate -> {
                            assertThat(duplicate.inserted()).isFalse();
                            assertThat(duplicate.durableHighWatermark()).isEqualTo(3);
                        });
        assertThatThrownBy(
                        () ->
                                eventStore.append(
                                        event(
                                                first.attemptId(),
                                                1,
                                                StreamEventType.VISIBLE_DELTA,
                                                "conflicting")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payload hash");
        assertThat(eventStore.durableHighWatermark(logical.agentRunId(), first.attemptId()))
                .isEqualTo(3);

        PostgresAgentRunV2EventStore recoveryStore = new PostgresAgentRunV2EventStore(
                jdbc,
                objectMapper,
                transactionManager,
                StreamCompatibilityMode.DUAL_WRITE_OLD_READ);
        AgentStreamEvent recoveryError = new AgentStreamEvent(
                "agent-stream.v2",
                logical.agentRunId(),
                first.attemptId(),
                4,
                StreamEventType.ERROR,
                Audience.USER,
                Instant.parse("2026-07-19T01:00:04Z"),
                new Payload(
                        null, null, null, null, null, null,
                        null, null, "RECOVERY_EXHAUSTED", false));
        AtomicReference<AppendReceipt> recoveryReceipt = new AtomicReference<>();
        outerTransaction.executeWithoutResult(status -> {
            assertThatThrownBy(() -> recoveryStore.appendRecoveryErrorInCurrentTransaction(
                            event(
                                    first.attemptId(),
                                    4,
                                    StreamEventType.VISIBLE_DELTA,
                                    "not a recovery error")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exactly one ERROR");
            for (long sequence = 0; sequence <= 3; sequence++) {
                TestSource source = source(logical.agentRunId(), first.attemptId(), sequence);
                assertThat(recordTarget(source, source.payloadHash())).isTrue();
            }
            recoveryReceipt.set(
                    recoveryStore.appendRecoveryErrorInCurrentTransaction(recoveryError));
            assertThat(recoveryStore.durableHighWatermark(
                            logical.agentRunId(), first.attemptId()))
                    .isEqualTo(4);
            assertThat(targetHighWatermark(
                            "agent-stream.v2", logical.agentRunId(), first.attemptId()))
                    .isEqualTo(4);
            assertThat(attemptProgress(first.attemptId()))
                    .isEqualTo(new AttemptProgress(4, true, false));
            status.setRollbackOnly();
        });
        assertThat(recoveryReceipt.get())
                .isEqualTo(new AppendReceipt(true, 4));
        assertThat(eventStore.durableHighWatermark(logical.agentRunId(), first.attemptId()))
                .isEqualTo(3);
        assertThat(attemptProgress(first.attemptId()))
                .isEqualTo(new AttemptProgress(3, true, false));
        assertThat(jdbc.queryForObject(
                        """
                        select count(*) from agent_run_stream_event_delivery
                         where stream_protocol = 'agent-stream.v2'
                           and agent_run_id = ? and agent_run_attempt_id = ?
                        """,
                        Long.class,
                        logical.agentRunId(),
                        first.attemptId()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        """
                        select count(*) from agent_run_stream_delivery_high_watermark
                         where stream_protocol = 'agent-stream.v2'
                           and agent_run_id = ? and agent_run_attempt_id = ?
                        """,
                        Long.class,
                        logical.agentRunId(),
                        first.attemptId()))
                .isZero();
        assertThatThrownBy(() ->
                        recoveryStore.appendRecoveryErrorInCurrentTransaction(recoveryError))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("actual caller transaction");

        ledger.recordAttemptFailure(
                logical.agentRunId(),
                first.attemptId(),
                1,
                AgentRunAttemptStatus.FAILED,
                "PROVIDER_TIMEOUT",
                AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT,
                AgentRunPersistenceFixtures.COMPLETED_AT);
        jdbc.update(
                """
                update agent_run_attempt
                   set last_sequence_no = 0,
                       public_output_emitted = false
                 where id = ?
                """,
                first.attemptId());
        AttemptProgress terminalProgress = new AttemptProgress(3, true, false);
        AgentStreamEvent lateVisibleDelta =
                event(first.attemptId(), 4, StreamEventType.VISIBLE_DELTA, "too late");
        for (AgentRunAttemptStatus nonRunningStatus :
                List.of(
                        AgentRunAttemptStatus.PENDING,
                        AgentRunAttemptStatus.RESULT_READY,
                        AgentRunAttemptStatus.COMPLETED,
                        AgentRunAttemptStatus.FAILED,
                        AgentRunAttemptStatus.ABORTED,
                        AgentRunAttemptStatus.CANCELLED)) {
            jdbc.update(
                    "update agent_run_attempt set attempt_status = ? where id = ?",
                    nonRunningStatus.name(),
                    first.attemptId());

            assertThat(eventStore.append(newSuffix))
                    .satisfies(
                            replay -> {
                                assertThat(replay.inserted()).isFalse();
                                assertThat(replay.durableHighWatermark()).isEqualTo(3);
                            });
            assertThat(attemptProgress(first.attemptId())).isEqualTo(terminalProgress);
            assertThatThrownBy(() -> eventStore.append(lateVisibleDelta))
                    .isInstanceOf(NonRunningAttemptException.class)
                    .hasMessageContaining("require a RUNNING attempt")
                    .hasMessageContaining(nonRunningStatus.name())
                    .satisfies(failure -> assertThat(
                                    ((NonRunningAttemptException) failure).attemptStatus())
                            .isEqualTo(nonRunningStatus));
            assertThat(eventStore.durableHighWatermark(logical.agentRunId(), first.attemptId()))
                    .isEqualTo(3);
            assertThat(attemptProgress(first.attemptId())).isEqualTo(terminalProgress);
        }
        assertThatThrownBy(() -> eventStore.append(event(
                        first.attemptId(),
                        3,
                        StreamEventType.VISIBLE_DELTA,
                        "conflicting terminal replay")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payload hash");
        assertThat(jdbc.queryForObject(
                        """
                        select count(*)
                          from agent_run_stream_event
                         where agent_run_id = ?
                           and agent_run_attempt_id = ?
                           and sequence_no = 4
                           and stream_protocol = 'agent-stream.v2'
                        """,
                        Long.class,
                        logical.agentRunId(),
                        first.attemptId()))
                .isZero();
        jdbc.update(
                "update agent_run_attempt set attempt_status = 'FAILED' where id = ?",
                first.attemptId());
        assertThatThrownBy(() -> eventStore.appendOrLoadReconciledFinal(
                        new AgentRunReconciledFinalStore.Request(
                                logical.agentRunId(),
                                first.attemptId(),
                                Audience.USER,
                                "urn:after-sale-flow:graph-result:" + "d".repeat(64),
                                "d".repeat(64))))
                .isInstanceOf(NonRunningAttemptException.class)
                .hasMessageContaining("status is FAILED")
                .satisfies(failure -> assertThat(
                                ((NonRunningAttemptException) failure).attemptStatus())
                        .isEqualTo(AgentRunAttemptStatus.FAILED));
        AgentRunLedger.Attempt second =
                ledger.startNextAttempt(
                        logical.agentRunId(),
                        AgentRunPersistenceFixtures.allocation(2, "ATTEMPT_STREAM_2"),
                        AgentRunPersistenceFixtures.COMPLETED_AT.plusSeconds(1));
        assertThat(second.previousAttemptId()).isEqualTo(first.attemptId());
        assertThat(second.resetRequired()).isTrue();
        assertThat(second.publicSequenceOffset()).isEqualTo(1);
        assertThat(second.lastSequenceNo()).isEqualTo(1);
        assertThat(eventStore.replay(logical.agentRunId(), second.attemptId(), -1, 100))
                .extracting(AgentStreamEvent::eventType, AgentStreamEvent::sequenceNo)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                StreamEventType.ATTEMPT_STARTED, 0L),
                        org.assertj.core.groups.Tuple.tuple(
                                StreamEventType.ATTEMPT_RESET, 1L));
        assertThat(eventStore.replay(logical.agentRunId(), second.attemptId(), -1, 100).get(1)
                        .payload()
                        .resetAttemptId())
                .isEqualTo(first.attemptId());
        assertThat(eventStore.replay(logical.agentRunId(), second.attemptId(), -1, 100).get(1)
                        .payload()
                        .reasonCode())
                .isEqualTo(AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT.name());

        String committedTitle =
                jdbc.queryForObject(
                        "select title from fulfillment_dispute_case where id = ?",
                        String.class,
                        AgentRunPersistenceFixtures.CASE_ID);
        AtomicReference<AppendReceipt> appendReceipt = new AtomicReference<>();
        outerTransaction.executeWithoutResult(
                status -> {
                    jdbc.update(
                            "update fulfillment_dispute_case set title = ? where id = ?",
                            "outer transaction must roll back",
                            AgentRunPersistenceFixtures.CASE_ID);
                    appendReceipt.set(
                            eventStore.append(
                                    event(
                                            second.attemptId(),
                                            2,
                                            StreamEventType.VISIBLE_DELTA,
                                            "durable after outer rollback")));
                    status.setRollbackOnly();
                });

        assertThat(eventStore.durableHighWatermark(logical.agentRunId(), second.attemptId()))
                .isEqualTo(2);
        assertThat(attemptProgress(second.attemptId()))
                .isEqualTo(new AttemptProgress(2, true, false));
        assertThat(appendReceipt.get())
                .isNotNull()
                .satisfies(
                        durableReceipt -> {
                            assertThat(durableReceipt.inserted()).isTrue();
                            assertThat(durableReceipt.durableHighWatermark()).isEqualTo(2);
                        });
        assertThat(
                        jdbc.queryForObject(
                                "select title from fulfillment_dispute_case where id = ?",
                                String.class,
                                AgentRunPersistenceFixtures.CASE_ID))
                .isEqualTo(committedTitle);
        assertThat(eventStore.replay(logical.agentRunId(), second.attemptId(), -1, 100))
                .extracting(AgentStreamEvent::attemptId, AgentStreamEvent::sequenceNo)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(second.attemptId(), 0L),
                        org.assertj.core.groups.Tuple.tuple(second.attemptId(), 1L),
                        org.assertj.core.groups.Tuple.tuple(second.attemptId(), 2L));
        assertThat(eventStore.replay(logical.agentRunId(), first.attemptId(), -1, 100))
                .hasSize(4)
                .allMatch(event -> event.attemptId().equals(first.attemptId()));

        AgentRunReconciledFinalStore.Request finalRequest =
                new AgentRunReconciledFinalStore.Request(
                        logical.agentRunId(),
                        second.attemptId(),
                        Audience.USER,
                        "urn:after-sale-flow:graph-result:" + "f".repeat(64),
                        "f".repeat(64));
        AtomicReference<AgentRunReconciledFinalStore.Receipt> insertedFinalReference =
                new AtomicReference<>();
        outerTransaction.executeWithoutResult(
                status -> {
                    jdbc.update(
                            "update fulfillment_dispute_case set title = ? where id = ?",
                            "reconciled final outer transaction must roll back",
                            AgentRunPersistenceFixtures.CASE_ID);
                    insertedFinalReference.set(
                            eventStore.appendOrLoadReconciledFinal(finalRequest));
                    status.setRollbackOnly();
                });
        AgentRunReconciledFinalStore.Receipt insertedFinal = insertedFinalReference.get();
        assertThat(attemptProgress(second.attemptId()))
                .isEqualTo(new AttemptProgress(3, true, true));
        jdbc.update(
                """
                update agent_run_attempt
                   set last_sequence_no = 0,
                       public_output_emitted = false,
                       final_frame_observed = false
                 where id = ?
                """,
                second.attemptId());
        AgentRunReconciledFinalStore.Receipt cachedFinal =
                eventStore.appendOrLoadReconciledFinal(finalRequest);

        assertThat(insertedFinal).isNotNull();
        assertThat(insertedFinal.inserted()).isTrue();
        assertThat(insertedFinal.durableHighWatermark()).isEqualTo(3);
        assertThat(insertedFinal.publicOutputEmitted()).isTrue();
        assertThat(cachedFinal.inserted()).isFalse();
        assertThat(cachedFinal.finalEvent()).isEqualTo(insertedFinal.finalEvent());
        assertThat(cachedFinal.finalEvent().occurredAt())
                .isEqualTo(insertedFinal.finalEvent().occurredAt());
        assertThat(cachedFinal.durableHighWatermark()).isEqualTo(3);
        assertThat(attemptProgress(second.attemptId()))
                .isEqualTo(new AttemptProgress(3, true, true));
        assertThat(
                        jdbc.queryForObject(
                                "select title from fulfillment_dispute_case where id = ?",
                                String.class,
                                AgentRunPersistenceFixtures.CASE_ID))
                .isEqualTo(committedTitle);
        assertThatThrownBy(() -> eventStore.appendOrLoadReconciledFinal(
                        new AgentRunReconciledFinalStore.Request(
                                logical.agentRunId(),
                                second.attemptId(),
                                Audience.USER,
                                "urn:after-sale-flow:graph-result:" + "e".repeat(64),
                                "e".repeat(64))))
                .isInstanceOf(AgentRunReconciledFinalStore.ConflictException.class)
                .hasMessageContaining("differs");
        assertThat(eventStore.replay(logical.agentRunId(), second.attemptId(), -1, 100))
                .extracting(AgentStreamEvent::eventType, AgentStreamEvent::sequenceNo)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                StreamEventType.ATTEMPT_STARTED, 0L),
                        org.assertj.core.groups.Tuple.tuple(
                                StreamEventType.ATTEMPT_RESET, 1L),
                        org.assertj.core.groups.Tuple.tuple(
                                StreamEventType.VISIBLE_DELTA, 2L),
                        org.assertj.core.groups.Tuple.tuple(StreamEventType.FINAL, 3L));
        assertThat(eventStore.replay(logical.agentRunId(), second.attemptId(), -1, 100).getLast())
                .isEqualTo(insertedFinal.finalEvent());
        assertThatThrownBy(() -> eventStore.append(event(
                        second.attemptId(),
                        4,
                        StreamEventType.VISIBLE_DELTA,
                        "late")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal");

        dualWriteThenBackfillIsIdempotent(logical.agentRunId(), second.attemptId());
        transientFailureLeavesCursorResumable();
        backfillThenDualWriteIsIdempotent(logical.agentRunId(), second.attemptId());
        hashConflictRollsBackIdentityTargetAndWatermark(
                logical.agentRunId(), second.attemptId());
        assertThat(eventStore
                        .validateCompatibility(
                                "agent-stream.v2", logical.agentRunId(), first.attemptId())
                        .requireCompatible()
                        .compatible())
                .isTrue();
        assertThat(eventStore
                        .validateCompatibility(
                                "agent-stream.v2", logical.agentRunId(), second.attemptId())
                        .requireCompatible()
                        .compatible())
                .isTrue();
        targetOnlyWriteRequiresCompatibleUnionRollback(
                logical.agentRunId(), first.attemptId());
        gappedV1AndV2ParityFailsClosed(logical.agentRunId(), second.attemptId());
    }

    private void dualWriteThenBackfillIsIdempotent(String runId, String attemptId) {
        TestSource source = source(runId, attemptId, 3);
        assertThat(recordTarget(source, source.payloadHash())).isTrue();
        assertThat(recordTarget(source, source.payloadHash())).isFalse();
    }

    private void transientFailureLeavesCursorResumable() {
        StreamBackfillCoordinator coordinator =
                new StreamBackfillCoordinator(jdbc, objectMapper, transactionManager);
        var cursor = coordinator.start("P8_STREAM_BACKFILL", 2, "phase8-test");
        jdbc.execute(
                "alter table agent_run_stream_event_delivery rename to "
                        + "agent_run_stream_event_delivery_unavailable");
        try {
            assertThatThrownBy(() -> coordinator.resume(cursor.backfillId()))
                    .isInstanceOf(DataAccessException.class);
        } finally {
            jdbc.execute(
                    "alter table agent_run_stream_event_delivery_unavailable rename to "
                            + "agent_run_stream_event_delivery");
        }
        assertThat(coordinator.cursor(cursor.backfillId()))
                .satisfies(failed -> {
                    assertThat(failed.status()).isEqualTo(CursorStatus.FAILED);
                    assertThat(failed.processedCount()).isZero();
                    assertThat(failed.conflictCount()).isZero();
                });

        assertThat(coordinator.resume(cursor.backfillId()).cursor().conflictCount()).isZero();
        while (coordinator.cursor(cursor.backfillId()).status() != CursorStatus.COMPLETE) {
            coordinator.resume(cursor.backfillId());
        }
        assertThat(coordinator.cursor(cursor.backfillId()))
                .satisfies(completed -> {
                    assertThat(completed.status()).isEqualTo(CursorStatus.COMPLETE);
                    assertThat(completed.conflictCount()).isZero();
                    assertThat(completed.lastProcessed()).isEqualTo(completed.upperBound());
                });
    }

    private void backfillThenDualWriteIsIdempotent(String runId, String attemptId) {
        TestSource source = source(runId, attemptId, 0);
        long targetCount = targetCount();
        assertThat(recordTarget(source, source.payloadHash())).isFalse();
        assertThat(targetCount()).isEqualTo(targetCount);
        assertThat(targetCount()).isEqualTo(sourceCount());
    }

    private void hashConflictRollsBackIdentityTargetAndWatermark(
            String runId, String attemptId) {
        TestSource source = source(runId, attemptId, 0);
        long identityCount = identityCount();
        long targetCount = targetCount();
        long highWatermark = targetHighWatermark("agent-stream.v2", runId, attemptId);

        assertThatThrownBy(() -> recordTarget(source, "e".repeat(64)))
                .isInstanceOf(DataAccessException.class);
        assertThat(identityCount()).isEqualTo(identityCount);
        assertThat(targetCount()).isEqualTo(targetCount);
        assertThat(targetHighWatermark("agent-stream.v2", runId, attemptId))
                .isEqualTo(highWatermark);
    }

    private void gappedV1AndV2ParityFailsClosed(String runId, String attemptId) {
        AgentStreamEvent gap = event(
                attemptId, 5, StreamEventType.VISIBLE_DELTA, "gap must fail parity");
        String gapJson = ContractJson.canonicalString(objectMapper.valueToTree(gap));
        String gapHash = ContractJson.sha256Hex(objectMapper.valueToTree(gap));
        jdbc.update(
                """
                insert into agent_run_stream_event (
                    id, agent_run_id, agent_run_attempt_id, sequence_no,
                    event_type, payload_json, created_at, created_by,
                    stream_protocol, audience, payload_hash
                ) values ('P8_GAP_V2', ?, ?, 5, 'visible_delta', cast(? as jsonb),
                          clock_timestamp(), 'phase8-test', 'agent-stream.v2', 'USER', ?)
                """,
                runId,
                attemptId,
                gapJson,
                gapHash);
        recordTarget(source(runId, attemptId, 5), gapHash);

        insertV1Gap(runId, attemptId, 0, "P8_GAP_V1_0", "start");
        insertV1Gap(runId, attemptId, 2, "P8_GAP_V1_2", "visible_delta");

        assertThat(eventStore.validateCompatibility("agent-stream.v2", runId, attemptId)
                        .sequenceParity())
                .isFalse();
        assertThat(eventStore.validateCompatibility("agent-stream.v2", runId, attemptId)
                        .reconnectParity())
                .isFalse();
        assertThat(eventStore.validateCompatibility("agent_stream.v1", runId, attemptId)
                        .sequenceParity())
                .isFalse();
        assertThatThrownBy(() -> eventStore
                        .validateCompatibility("agent_stream.v1", runId, attemptId)
                        .requireCompatible())
                .isInstanceOf(CompatibilityMismatchException.class);
    }

    private void targetOnlyWriteRequiresCompatibleUnionRollback(
            String runId, String attemptId) {
        AgentStreamEvent targetOnly =
                event(attemptId, 4, StreamEventType.ATTEMPT_ABORTED, null);
        String payloadJson =
                ContractJson.canonicalString(objectMapper.valueToTree(targetOnly));
        String payloadHash = ContractJson.sha256Hex(objectMapper.valueToTree(targetOnly));
        TestSource basis = source(runId, attemptId, 3);
        TestSource targetOnlySource = new TestSource(
                "P8_TARGET_ONLY_V2",
                "agent-stream.v2",
                runId,
                attemptId,
                4,
                "attempt_aborted",
                payloadJson,
                payloadHash,
                "USER",
                Instant.parse("2026-07-25T12:00:04Z"),
                basis.actorId(),
                basis.audienceActorIdsJson());
        assertThat(recordTarget(targetOnlySource, payloadHash)).isTrue();

        var coverage = eventStore.validateRollbackCoverage(
                "agent-stream.v2", runId, attemptId);
        assertThat(coverage.targetOnlyWriteObserved()).isTrue();
        assertThat(coverage.compatibleUnion()).isTrue();
        assertThat(StreamCompatibilityMode.requireTransition(
                        StreamCompatibilityMode.TARGET_ONLY,
                        StreamCompatibilityMode.TARGET_AWARE_ROLLBACK,
                        null,
                        coverage))
                .isEqualTo(StreamCompatibilityMode.TARGET_AWARE_ROLLBACK);
        assertThatThrownBy(() -> StreamCompatibilityMode.requireTransition(
                        StreamCompatibilityMode.TARGET_ONLY,
                        StreamCompatibilityMode.OLD_COMPATIBLE,
                        null,
                        coverage))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("old-only rollback is forbidden");
        postTerminalParityAndRollbackFailClosed(targetOnlySource, basis);
    }

    private void postTerminalParityAndRollbackFailClosed(
            TestSource terminalSource, TestSource basis) {
        insertV2Source(terminalSource);
        AgentStreamEvent recoveryError = new AgentStreamEvent(
                "agent-stream.v2",
                terminalSource.runId(),
                terminalSource.attemptId(),
                5,
                StreamEventType.ERROR,
                Audience.USER,
                Instant.parse("2026-07-25T12:00:05Z"),
                new Payload(
                        null, null, null, null, null, null,
                        null, null, "RECOVERY_EXHAUSTED", false));
        String recoveryErrorJson =
                ContractJson.canonicalString(objectMapper.valueToTree(recoveryError));
        String recoveryErrorHash = ContractJson.sha256Hex(objectMapper.valueToTree(recoveryError));
        TestSource recoveryErrorSource = new TestSource(
                "P8_RECOVERY_ERROR_V2",
                "agent-stream.v2",
                terminalSource.runId(),
                terminalSource.attemptId(),
                5,
                "error",
                recoveryErrorJson,
                recoveryErrorHash,
                "USER",
                recoveryError.occurredAt(),
                basis.actorId(),
                basis.audienceActorIdsJson());
        insertV2Source(recoveryErrorSource);
        assertThat(recordTarget(recoveryErrorSource, recoveryErrorHash)).isTrue();
        assertThat(eventStore.validateCompatibility(
                        "agent-stream.v2", terminalSource.runId(), terminalSource.attemptId())
                        .terminalParity())
                .isTrue();
        assertThat(eventStore
                        .validateRollbackCoverage(
                                "agent-stream.v2",
                                terminalSource.runId(),
                                terminalSource.attemptId())
                        .compatibleUnion())
                .isTrue();

        AgentStreamEvent late = event(
                terminalSource.attemptId(),
                6,
                StreamEventType.VISIBLE_DELTA,
                "post-terminal rows must fail closed");
        String lateJson = ContractJson.canonicalString(objectMapper.valueToTree(late));
        String lateHash = ContractJson.sha256Hex(objectMapper.valueToTree(late));
        TestSource lateSource = new TestSource(
                "P8_POST_TERMINAL_V2",
                "agent-stream.v2",
                terminalSource.runId(),
                terminalSource.attemptId(),
                6,
                "visible_delta",
                lateJson,
                lateHash,
                "USER",
                Instant.parse("2026-07-25T12:00:06Z"),
                basis.actorId(),
                basis.audienceActorIdsJson());
        insertV2Source(lateSource);
        assertThat(recordTarget(lateSource, lateHash)).isTrue();

        var parity = eventStore.validateCompatibility(
                "agent-stream.v2", terminalSource.runId(), terminalSource.attemptId());
        assertThat(parity.countParity()).isTrue();
        assertThat(parity.sequenceParity()).isTrue();
        assertThat(parity.terminalParity()).isFalse();
        assertThat(parity.reconnectParity()).isFalse();
        assertThat(eventStore
                        .validateRollbackCoverage(
                                "agent-stream.v2",
                                terminalSource.runId(),
                                terminalSource.attemptId())
                        .compatibleUnion())
                .isFalse();
    }

    private void insertV2Source(TestSource source) {
        jdbc.update(
                """
                insert into agent_run_stream_event (
                    id, agent_run_id, agent_run_attempt_id, sequence_no,
                    event_type, payload_json, created_at, created_by,
                    stream_protocol, audience, payload_hash
                ) values (?, ?, ?, ?, ?, cast(? as jsonb), ?, 'phase8-test',
                          'agent-stream.v2', ?, ?)
                """,
                source.eventId(),
                source.runId(),
                source.attemptId(),
                source.sequence(),
                source.eventType(),
                source.payloadJson(),
                java.sql.Timestamp.from(source.createdAt()),
                source.audience(),
                source.payloadHash());
    }

    private void insertV1Gap(
            String runId, String attemptId, long sequence, String eventId, String eventType) {
        String payload = "{\"schema_version\":\"agent_stream.v1\",\"sequence\":"
                + sequence
                + "}";
        String hash = ContractJson.sha256Hex(readTree(payload));
        jdbc.update(
                """
                insert into agent_run_stream_event (
                    id, agent_run_id, agent_run_attempt_id, sequence_no,
                    event_type, payload_json, created_at, created_by,
                    stream_protocol, payload_hash
                ) values (?, ?, ?, ?, ?, cast(? as jsonb), clock_timestamp(),
                          'phase8-test', 'agent_stream.v1', ?)
                """,
                eventId,
                runId,
                attemptId,
                sequence,
                eventType,
                payload,
                hash);
        assertThat(recordTarget(source(runId, attemptId, sequence), hash)).isTrue();
    }

    private boolean recordTarget(TestSource source, String payloadHash) {
        Boolean inserted = jdbc.queryForObject(
                """
                select was_inserted
                  from record_agent_run_stream_delivery(
                       ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, cast(? as jsonb),
                       ?, 'agent_run_stream_event', 'phase8-test')
                """,
                Boolean.class,
                source.eventId(),
                source.protocol(),
                source.runId(),
                source.attemptId(),
                source.sequence(),
                source.eventType(),
                source.payloadJson(),
                payloadHash,
                source.audience(),
                source.actorId(),
                source.audienceActorIdsJson(),
                java.sql.Timestamp.from(source.createdAt()));
        return Boolean.TRUE.equals(inserted);
    }

    private TestSource source(String runId, String attemptId, long sequence) {
        return jdbc.queryForObject(
                """
                select event.id, event.stream_protocol, event.agent_run_id,
                       event.agent_run_attempt_id, event.sequence_no, event.event_type,
                       event.payload_json::text, event.payload_hash, event.audience,
                       event.created_at, run.created_by as actor_id,
                       run.stream_audience_actor_ids_json::text as audience_actor_ids_json
                  from agent_run_stream_event event
                  join agent_run run on run.id = event.agent_run_id
                 where event.agent_run_id = ? and event.agent_run_attempt_id = ?
                   and event.sequence_no = ?
                 order by event.stream_protocol desc
                 limit 1
                """,
                (resultSet, rowNumber) -> new TestSource(
                        resultSet.getString("id"),
                        resultSet.getString("stream_protocol"),
                        resultSet.getString("agent_run_id"),
                        resultSet.getString("agent_run_attempt_id"),
                        resultSet.getLong("sequence_no"),
                        resultSet.getString("event_type"),
                        resultSet.getString("payload_json"),
                        resultSet.getString("payload_hash"),
                        resultSet.getString("audience"),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getString("actor_id"),
                        resultSet.getString("audience_actor_ids_json")),
                runId,
                attemptId,
                sequence);
    }

    private long sourceCount() {
        return jdbc.queryForObject("select count(*) from agent_run_stream_event", Long.class);
    }

    private long targetCount() {
        return jdbc.queryForObject(
                "select count(*) from agent_run_stream_event_delivery", Long.class);
    }

    private long identityCount() {
        return jdbc.queryForObject(
                "select count(*) from agent_run_stream_event_identity", Long.class);
    }

    private long targetHighWatermark(String protocol, String runId, String attemptId) {
        return jdbc.queryForObject(
                """
                select highest_contiguous_sequence_no
                  from agent_run_stream_delivery_high_watermark
                 where stream_protocol = ? and agent_run_id = ?
                   and agent_run_attempt_id = ?
                """,
                Long.class,
                protocol,
                runId,
                attemptId);
    }

    private com.fasterxml.jackson.databind.JsonNode readTree(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record TestSource(
            String eventId,
            String protocol,
            String runId,
            String attemptId,
            long sequence,
            String eventType,
            String payloadJson,
            String payloadHash,
            String audience,
            Instant createdAt,
            String actorId,
            String audienceActorIdsJson) {}

    private AttemptProgress attemptProgress(String attemptId) {
        return jdbc.queryForObject(
                """
                select last_sequence_no, public_output_emitted, final_frame_observed
                  from agent_run_attempt
                 where id = ?
                """,
                (resultSet, rowNumber) -> new AttemptProgress(
                        resultSet.getLong("last_sequence_no"),
                        resultSet.getBoolean("public_output_emitted"),
                        resultSet.getBoolean("final_frame_observed")),
                attemptId);
    }

    private record AttemptProgress(
            long lastSequenceNo,
            boolean publicOutputEmitted,
            boolean finalFrameObserved) {}

    private AgentStreamEvent event(
            String attemptId, long sequence, StreamEventType eventType, String delta) {
        return event(
                AgentRunPersistenceFixtures.RUN_ID,
                attemptId,
                sequence,
                eventType,
                delta);
    }

    private AgentStreamEvent event(
            String runId,
            String attemptId,
            long sequence,
            StreamEventType eventType,
            String delta) {
        return new AgentStreamEvent(
                "agent-stream.v2",
                runId,
                attemptId,
                sequence,
                eventType,
                Audience.USER,
                Instant.parse("2026-07-19T01:00:00Z").plusMillis(sequence),
                new Payload(
                        "answer",
                        delta == null ? null : "text",
                        delta,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));
    }

    private ReadyRun readyRun(String attemptId) {
        String runId = "RUN_" + attemptId;
        String caseId = "CASE_" + attemptId;
        String logicalKey = "logical-" + attemptId;
        String commandId = "command-" + attemptId;
        insertCase(caseId);
        RoomGraphCommand command = reboundCommand(
                runId, caseId, attemptId, commandId);
        AgentRunCommandBindingFactory.Context context =
                new AgentRunCommandBindingFactory.Context(
                        "ROOM_V2_PERSISTENCE",
                        "EPOCH_V2_PERSISTENCE",
                        "EVIDENCE_ANALYZE",
                        logicalKey);
        AgentRunCommandBindingFactory.Binding binding =
                new AgentRunCommandBindingFactory(objectMapper).bind(context, command);
        AgentRunLedger.CreateLogicalRun basis =
                AgentRunPersistenceFixtures.logicalRun(attemptId);
        AgentRunLedger.CreateLogicalRun create = new AgentRunLedger.CreateLogicalRun(
                runId,
                basis.tenantSurrogate(),
                caseId,
                basis.roomId(),
                basis.operation(),
                logicalKey,
                basis.protocol(),
                basis.executorKind(),
                basis.roomEpochId(),
                basis.roomType(),
                basis.roomEpoch(),
                basis.processRevision(),
                basis.fencingToken(),
                command.requestHash(),
                binding.logicalInputHash(),
                basis.attemptLimit(),
                basis.deadlineAt(),
                basis.createdAt());
        AgentRunLedger.LogicalRun logical = ledger.createOrLoad(create);
        AgentRunLedger.Attempt attempt = ledger.startNextAttempt(
                logical.agentRunId(),
                new AgentRunLedger.AttemptAllocation(1, command, binding),
                AgentRunPersistenceFixtures.STARTED_AT);
        eventStore.append(event(
                runId, attempt.attemptId(), 1, StreamEventType.VISIBLE_DELTA, "visible"));
        eventStore.append(event(
                runId, attempt.attemptId(), 2, StreamEventType.VISIBLE_DELTA, " output"));
        ExecuteAgentRunResult result = reboundResult(
                runId, attempt.attemptId(), command.commandId());
        AgentRunReconciledFinalStore.Receipt finalReceipt =
                eventStore.appendOrLoadReconciledFinal(
                        new AgentRunReconciledFinalStore.Request(
                                logical.agentRunId(),
                                attempt.attemptId(),
                                Audience.USER,
                                "urn:after-sale-flow:graph-result:" + result.resultHash(),
                                result.resultHash()));
        assertThat(finalReceipt.finalEvent().sequenceNo()).isEqualTo(result.lastSequenceNo());
        assertThat(finalReceipt.publicOutputEmitted()).isTrue();
        ledger.recordResultReady(result);
        assertThat(attemptProgress(attempt.attemptId()))
                .isEqualTo(new AttemptProgress(3, true, true));
        return new ReadyRun(
                logical.agentRunId(),
                caseId,
                command.logicalRunId(),
                attempt.attemptId(),
                attempt.attemptNo(),
                command.commandId(),
                command.requestHash(),
                result.resultHash(),
                result.lastSequenceNo(),
                result.publicOutputEmitted());
    }

    private RoomGraphCommand reboundCommand(
            String runId, String caseId, String attemptId, String commandId) {
        ObjectMapper commandMapper = objectMapper.copy();
        commandMapper.setSerializationInclusion(
                com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        commandMapper.disable(
                com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        ObjectNode json = commandMapper.valueToTree(
                AgentRunPersistenceFixtures.request(1, attemptId).command());
        json.put("command_id", commandId);
        json.put("logical_run_id", runId);
        json.put("attempt_id", attemptId);
        json.put("case_id", caseId);
        json.remove("request_hash");
        json.put("request_hash", ContractJson.sha256Hex(json));
        try {
            return commandMapper.treeToValue(json, RoomGraphCommand.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new AssertionError("rebound command fixture is invalid", failure);
        }
    }

    private ExecuteAgentRunResult reboundResult(
            String runId, String attemptId, String commandId) {
        ObjectNode json = objectMapper.valueToTree(
                AgentRunPersistenceFixtures.result(1, attemptId));
        json.put("agent_run_id", runId);
        json.put("logical_run_id", runId);
        json.put("attempt_id", attemptId);
        ObjectNode graphResult = (ObjectNode) json.required("graph_result");
        graphResult.put("command_id", commandId);
        graphResult.put("logical_run_id", runId);
        graphResult.put("attempt_id", attemptId);
        try {
            return objectMapper.treeToValue(json, ExecuteAgentRunResult.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new AssertionError("rebound result fixture is invalid", failure);
        }
    }

    private Object finalizationFailureCommand(ReadyRun ready) {
        return finalizationFailureCommand(
                ready,
                ready.agentRunId(),
                ready.attemptId(),
                ready.commandId(),
                ready.commandRequestHash(),
                ready.resultHash(),
                ready.finalSequenceNo(),
                ready.publicOutputEmitted(),
                "INTAKE_INITIATOR_MATRIX_AUTHORITY_INVALID");
    }

    private Object finalizationFailureCommand(
            ReadyRun ready,
            String agentRunId,
            String attemptId,
            String commandId,
            String commandRequestHash,
            String resultHash,
            long finalSequenceNo,
            boolean publicOutputEmitted,
            String safeErrorCode) {
        try {
            Class<?> commandType = Class.forName(
                    "com.example.dispute.workflow.activity.agent."
                            + "AgentRunFinalizationFailureRecorder$Command");
            Constructor<?> constructor = commandType.getConstructor(
                    String.class,
                    String.class,
                    String.class,
                    long.class,
                    String.class,
                    String.class,
                    String.class,
                    long.class,
                    boolean.class,
                    String.class);
            return constructor.newInstance(
                    agentRunId,
                    ready.logicalRunId(),
                    attemptId,
                    ready.attemptNo(),
                    commandId,
                    commandRequestHash,
                    resultHash,
                    finalSequenceNo,
                    publicOutputEmitted,
                    safeErrorCode);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(
                    "expected AgentRunFinalizationFailureRecorder.Command API is missing",
                    failure);
        }
    }

    private Object recordFinalizationFailure(Object command) {
        try {
            Method method = AgentRunLedger.class.getMethod(
                    "recordFinalizationFailure", command.getClass());
            return method.invoke(ledger, command);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError("ledger recorder failed with a checked exception", cause);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(
                    "expected AgentRunLedger.recordFinalizationFailure API is missing",
                    failure);
        }
    }

    private void assertRecorderRejected(Object command) {
        assertThatThrownBy(() -> recordFinalizationFailure(command))
                .isInstanceOf(RuntimeException.class);
    }

    private Object appendFinalizationErrorInCurrentTransaction(AgentStreamEvent event) {
        try {
            Method method = PostgresAgentRunV2EventStore.class.getMethod(
                    "appendFinalizationErrorInCurrentTransaction", AgentStreamEvent.class);
            return method.invoke(eventStore, event);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError(
                    "dedicated finalization error append failed with a checked exception", cause);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(
                    "expected appendFinalizationErrorInCurrentTransaction API is missing",
                    failure);
        }
    }

    private void assertDedicatedAppendRejected(
            TransactionTemplate transaction, AgentStreamEvent event) {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                        appendFinalizationErrorInCurrentTransaction(event)))
                .isInstanceOf(RuntimeException.class);
    }

    private static Object recordAccessor(Object record, String accessor) {
        try {
            return record.getClass().getMethod(accessor).invoke(record);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("recorder receipt is missing accessor " + accessor, failure);
        }
    }

    private AgentStreamEvent finalizationError(
            String runId,
            String attemptId,
            long sequence,
            Audience audience,
            boolean retryable) {
        return new AgentStreamEvent(
                "agent-stream.v2",
                runId,
                attemptId,
                sequence,
                StreamEventType.ERROR,
                audience,
                AgentRunPersistenceFixtures.COMPLETED_AT.plusSeconds(1),
                new Payload(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "INTAKE_INITIATOR_MATRIX_AUTHORITY_INVALID",
                        retryable));
    }

    private record ReadyRun(
            String agentRunId,
            String caseId,
            String logicalRunId,
            String attemptId,
            long attemptNo,
            String commandId,
            String commandRequestHash,
            String resultHash,
            long finalSequenceNo,
            boolean publicOutputEmitted) {}

    private void insertCase() {
        insertCase(AgentRunPersistenceFixtures.CASE_ID);
    }

    private void insertCase(String caseId) {
        jdbc.update(
                """
                insert into fulfillment_dispute_case (
                    id, user_id, merchant_id, creation_idempotency_key,
                    case_type, case_status, initiator_role, initiator_id,
                    respondent_role, respondent_id, risk_level,
                    title, description, current_room, created_by, updated_by
                ) values (?, 'user-persistence', 'merchant-persistence', ?,
                          'DISPUTE', 'EVIDENCE_OPEN', 'USER', 'user-persistence',
                          'MERCHANT', 'merchant-persistence', 'MEDIUM',
                          'AgentRun stream replay', 'AgentRun stream replay fixture',
                          'EVIDENCE', 'test', 'test')
                """,
                caseId,
                "idem-stream-replay-" + caseId);
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://"
                + POSTGRESQL.getHost()
                + ':'
                + POSTGRESQL.getMappedPort(5432)
                + "/agent_run_stream_replay";
    }

    static class JsonTestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
