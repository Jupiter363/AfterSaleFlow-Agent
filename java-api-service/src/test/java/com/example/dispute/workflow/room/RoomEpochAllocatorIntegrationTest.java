package com.example.dispute.workflow.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.application.command.TenantAuthority;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocationException;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.ActivateRoomEpoch;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.RoomEpochAllocation;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.TerminalRoomEpoch;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.TerminateRoomEpoch;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.TransitionRoomEpoch;
import com.example.dispute.workflow.application.epoch.RoomEpochSelection;
import com.example.dispute.workflow.application.epoch.RoomEpochSelection.TargetActivationBinding;
import com.example.dispute.workflow.application.epoch.RoomEpochSelector;
import com.example.dispute.workflow.application.epoch.TransactionalRoomEpochAllocator;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.bootstrap.RoomEpochBootstrapEnqueuer;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseProcessProjectionEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import com.example.dispute.workflow.targete2e.temporal.TargetRoomEpochBindingWriter;
import com.example.dispute.workflow.targete2e.temporal.TargetRoomEpochBindingWriter.BindingContext;
import com.example.dispute.workflow.targete2e.temporal.TargetTypedRoomProtocol;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaSystemException;
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
    TransactionalRoomEpochAllocator.class,
    RoomEpochAllocatorIntegrationTest.EpochAllocatorTestConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RoomEpochAllocatorIntegrationTest {

    private static final String TENANT = "tenant-epoch-allocator";
    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 7, 18, 9, 0, 0, 0, ZoneOffset.UTC);

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "room_epoch_allocator")
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
                                + "/room_epoch_allocator");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @Autowired private RoomEpochAllocator allocator;
    @Autowired private TrackingRoomEpochSelector selector;
    @Autowired private CapturingBootstrapEnqueuer bootstrapEnqueuer;
    @Autowired private CapturingTargetBindingWriter targetBindingWriter;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void resetFixtures() {
        jdbc.execute("drop trigger if exists trg_test_reject_projection_switch on case_process_projection");
        jdbc.execute("drop function if exists reject_test_projection_switch()");
        jdbc.update(
                "delete from case_process_projection where case_id like 'CASE_ALLOC_%'");
        jdbc.update("delete from case_room_epoch where case_id like 'CASE_ALLOC_%'");
        jdbc.update("delete from case_room where case_id like 'CASE_ALLOC_%'");
        jdbc.update("delete from fulfillment_dispute_case where id like 'CASE_ALLOC_%'");
        selector.reset();
        bootstrapEnqueuer.reset();
        targetBindingWriter.reset();
    }

    @Test
    void selectorRunsOnceForInsertionAndNeverForIdempotentActiveReplay() {
        String caseId = "CASE_ALLOC_STICKY";
        insertCaseAndRooms(caseId, RoomType.INTAKE);

        RoomEpochAllocation inserted =
                inTransaction(() -> allocator.activate(activate(caseId, RoomType.INTAKE, NOW)));
        selector.useShadow("shadow-build-v2", "2.0.0");
        RoomEpochAllocation replayed =
                inTransaction(
                        () ->
                                allocator.activate(
                                        activate(caseId, RoomType.INTAKE, NOW.plusMinutes(1))));

        assertThat(selector.calls()).isEqualTo(1);
        assertThat(replayed.epochId()).isEqualTo(inserted.epochId());
        assertThat(replayed.selection()).isEqualTo(inserted.selection());
        assertThat(replayed.selection().buildId()).isEqualTo("legacy-build-v1");
        assertThat(countEpochs(caseId)).isEqualTo(1);
    }

    @Test
    void concurrentActivationCreatesOneActiveEpochAndOnePersistedSelection() throws Exception {
        String caseId = "CASE_ALLOC_CONCURRENT";
        insertCaseAndRooms(caseId, RoomType.INTAKE);
        ActivateRoomEpoch command = activate(caseId, RoomType.INTAKE, NOW);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<RoomEpochAllocation> first =
                    executor.submit(() -> concurrentActivate(command, ready, start));
            Future<RoomEpochAllocation> second =
                    executor.submit(() -> concurrentActivate(command, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            RoomEpochAllocation firstAllocation = first.get(10, TimeUnit.SECONDS);
            RoomEpochAllocation secondAllocation = second.get(10, TimeUnit.SECONDS);
            assertThat(secondAllocation.epochId()).isEqualTo(firstAllocation.epochId());
        }

        assertThat(selector.calls()).isEqualTo(1);
        assertThat(countEpochs(caseId)).isEqualTo(1);
        assertThat(activeEpochCount(caseId)).isEqualTo(1);
    }

    @Test
    void transitionsKeepCaseCursorsAndAdvanceRoomEpochsAndFencesMonotonically() {
        String caseId = "CASE_ALLOC_MONOTONIC";
        insertCaseAndRooms(
                caseId, RoomType.INTAKE, RoomType.EVIDENCE, RoomType.HEARING);

        RoomEpochAllocation intake =
                inTransaction(() -> allocator.activate(activate(caseId, RoomType.INTAKE, NOW)));
        jdbc.update(
                """
                update case_process_projection
                   set last_command_sequence = 41, last_case_event_sequence = 52
                 where case_id = ?
                """,
                caseId);
        RoomEpochAllocation evidence =
                inTransaction(
                        () ->
                                allocator.transition(
                                        transition(
                                                caseId,
                                                RoomType.INTAKE,
                                                RoomType.EVIDENCE,
                                                NOW.plusMinutes(1))));
        RoomEpochAllocation hearing =
                inTransaction(
                        () ->
                                allocator.transition(
                                        transition(
                                                caseId,
                                                RoomType.EVIDENCE,
                                                RoomType.HEARING,
                                                NOW.plusMinutes(2))));
        RoomEpochAllocation evidenceAgain =
                inTransaction(
                        () ->
                                allocator.transition(
                                        transition(
                                                caseId,
                                                RoomType.HEARING,
                                                RoomType.EVIDENCE,
                                                NOW.plusMinutes(3))));

        assertThat(intake.roomEpoch()).isZero();
        assertThat(evidence.roomEpoch()).isZero();
        assertThat(hearing.roomEpoch()).isZero();
        assertThat(evidenceAgain.roomEpoch()).isEqualTo(1);
        assertThat(
                        List.of(
                                intake.fencingToken(),
                                evidence.fencingToken(),
                                hearing.fencingToken(),
                                evidenceAgain.fencingToken()))
                .containsExactly(1L, 2L, 3L, 4L);
        assertThat(
                        jdbc.queryForObject(
                                "select last_command_sequence from case_process_projection where case_id = ?",
                                Long.class,
                                caseId))
                .isEqualTo(41L);
        assertThat(
                        jdbc.queryForObject(
                                "select last_case_event_sequence from case_process_projection where case_id = ?",
                                Long.class,
                                caseId))
                .isEqualTo(52L);
        assertThat(
                        jdbc.queryForObject(
                                "select count(distinct temporal_workflow_id) from case_room_epoch where case_id = ?",
                                Long.class,
                                caseId))
                .isZero();
        assertThat(activeEpochCount(caseId)).isEqualTo(1);
        assertThat(selector.calls()).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                        "select projection_ref is null and projection_sha256 is null from case_process_projection where case_id = ?",
                        Boolean.class,
                        caseId))
                .isTrue();
    }

    @Test
    void transitionPersistsAndReplaysOneExactFrozenProjectionPair() {
        String caseId = "CASE_ALLOC_FROZEN_PROJECTION";
        String projectionRef =
                "urn:after-sale-flow:intake-event:EVIB_FROZEN#/result/frozen_submission/matrix";
        String projectionSha256 = "a".repeat(64);
        insertCaseAndRooms(caseId, RoomType.INTAKE, RoomType.EVIDENCE);
        inTransaction(() -> allocator.activate(activate(caseId, RoomType.INTAKE, NOW)));
        TransitionRoomEpoch command = new TransitionRoomEpoch(
                caseId,
                RoomType.INTAKE,
                roomId(caseId, RoomType.EVIDENCE),
                RoomType.EVIDENCE,
                "EVIDENCE_OPEN",
                "OPEN",
                NOW.plusHours(1),
                NOW.plusMinutes(1),
                projectionRef,
                projectionSha256);

        RoomEpochAllocation inserted = inTransaction(() -> allocator.transition(command));
        RoomEpochAllocation replayed = inTransaction(() -> allocator.transition(command));

        assertThat(replayed.epochId()).isEqualTo(inserted.epochId());
        assertThat(countEpochs(caseId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select projection_ref from case_process_projection where case_id = ?",
                        String.class,
                        caseId))
                .isEqualTo(projectionRef);
        assertThat(jdbc.queryForObject(
                        "select projection_sha256 from case_process_projection where case_id = ?",
                        String.class,
                        caseId))
                .isEqualTo(projectionSha256);
        assertThatThrownBy(() -> inTransaction(() -> allocator.transition(
                        new TransitionRoomEpoch(
                                caseId,
                                RoomType.INTAKE,
                                roomId(caseId, RoomType.EVIDENCE),
                                RoomType.EVIDENCE,
                                "EVIDENCE_OPEN",
                                "OPEN",
                                NOW.plusHours(1),
                                NOW.plusMinutes(1),
                                projectionRef,
                                "b".repeat(64)))))
                .isInstanceOfSatisfying(
                        RoomEpochAllocationException.class,
                        failure -> assertThat(failure.reasonCode())
                                .isEqualTo("ROOM_EPOCH_PROJECTION_AUTHORITY_CONFLICT"));
        assertThatThrownBy(() -> new TransitionRoomEpoch(
                        caseId,
                        RoomType.INTAKE,
                        roomId(caseId, RoomType.EVIDENCE),
                        RoomType.EVIDENCE,
                        "EVIDENCE_OPEN",
                        "OPEN",
                        NOW.plusHours(1),
                        NOW.plusMinutes(1),
                        projectionRef,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both be absent or present");
    }

    @Test
    void legacyTransitionAndReplayPreserveExistingFrozenProjectionAuthority() {
        String caseId = "CASE_ALLOC_FROZEN_THEN_LEGACY";
        String projectionRef =
                "urn:after-sale-flow:intake-event:EVIB_RETAINED#/result/frozen_submission/matrix";
        String projectionSha256 = "c".repeat(64);
        insertCaseAndRooms(
                caseId, RoomType.INTAKE, RoomType.EVIDENCE, RoomType.HEARING);
        inTransaction(() -> allocator.activate(activate(caseId, RoomType.INTAKE, NOW)));
        inTransaction(() -> allocator.transition(
                new TransitionRoomEpoch(
                        caseId,
                        RoomType.INTAKE,
                        roomId(caseId, RoomType.EVIDENCE),
                        RoomType.EVIDENCE,
                        "EVIDENCE_OPEN",
                        "OPEN",
                        NOW.plusHours(1),
                        NOW.plusMinutes(1),
                        projectionRef,
                        projectionSha256)));
        TransitionRoomEpoch legacy = transition(
                caseId,
                RoomType.EVIDENCE,
                RoomType.HEARING,
                NOW.plusMinutes(2));

        RoomEpochAllocation inserted = inTransaction(() -> allocator.transition(legacy));
        RoomEpochAllocation replayed = inTransaction(() -> allocator.transition(legacy));

        assertThat(replayed.epochId()).isEqualTo(inserted.epochId());
        assertThat(jdbc.queryForObject(
                        "select count(*) from case_room_epoch where case_id = ? and room_type = 'HEARING'",
                        Long.class,
                        caseId))
                .isEqualTo(1L);
        assertThat(countEpochs(caseId)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                        "select projection_ref from case_process_projection where case_id = ?",
                        String.class,
                        caseId))
                .isEqualTo(projectionRef);
        assertThat(jdbc.queryForObject(
                        "select projection_sha256 from case_process_projection where case_id = ?",
                        String.class,
                        caseId))
                .isEqualTo(projectionSha256);
        assertThat(selector.calls()).isEqualTo(3);
    }

    @Test
    void transitionRollsBackOldAndNewEpochFlushesWhenProjectionPersistenceFails() {
        String caseId = "CASE_ALLOC_ROLLBACK";
        insertCaseAndRooms(caseId, RoomType.INTAKE, RoomType.EVIDENCE);
        RoomEpochAllocation active =
                inTransaction(() -> allocator.activate(activate(caseId, RoomType.INTAKE, NOW)));
        installProjectionFailureTrigger();

        try {
            assertThatThrownBy(
                            () ->
                                    inTransaction(
                                            () ->
                                                    allocator.transition(
                                                            transition(
                                                                    caseId,
                                                                    RoomType.INTAKE,
                                                                    RoomType.EVIDENCE,
                                                                    NOW.plusMinutes(1),
                                                                    "FAIL_TRANSITION"))))
                    .isInstanceOf(JpaSystemException.class)
                    .hasMessageContaining("forced projection switch failure");
        } finally {
            removeProjectionFailureTrigger();
        }

        assertThat(countEpochs(caseId)).isEqualTo(1);
        assertThat(activeEpochId(caseId)).isEqualTo(active.epochId());
        assertThat(
                        jdbc.queryForObject(
                                "select current_room from case_process_projection where case_id = ?",
                                String.class,
                                caseId))
                .isEqualTo("INTAKE");
        assertThat(
                        jdbc.queryForObject(
                                "select process_revision from case_process_projection where case_id = ?",
                                Long.class,
                                caseId))
                .isZero();
    }

    @Test
    void temporalSelectionCreatesPreparingStateAndEnqueuesBootstrapAtomically() {
        String caseId = "CASE_ALLOC_TEMPORAL";
        insertCaseAndRooms(caseId, RoomType.INTAKE);
        selector.useTemporal();

        RoomEpochAllocation allocation =
                inTransaction(
                        () -> allocator.activate(activate(caseId, RoomType.INTAKE, NOW)));

        assertThat(selector.calls()).isEqualTo(1);
        assertThat(allocation.lifecycleStatus()).isEqualTo(EpochLifecycleStatus.PREPARING);
        assertThat(allocation.selection().selectionSchemaVersion())
                .isEqualTo("room-epoch-selection.v2");
        assertThat(allocation.selection().caseWorkflowType())
                .isEqualTo(CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE);
        assertThat(allocation.selection().roomWorkflowType())
                .isEqualTo("IntakeRoomWorkflow");
        assertThat(allocation.selection().roomWorkflowBuildId())
                .isEqualTo("p9-control-build");
        assertThat(countEpochs(caseId)).isEqualTo(1);
        assertThat(projectionCount(caseId)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select lifecycle_status from case_room_epoch where case_id = ?",
                                String.class,
                                caseId))
                .isEqualTo("PREPARING");
        assertThat(
                        jdbc.queryForObject(
                                """
                                select workflow_type || ':' || temporal_build_id || ':' ||
                                       room_workflow_type || ':' || room_workflow_build_id
                                  from case_room_epoch where case_id = ?
                                """,
                                String.class,
                                caseId))
                .isEqualTo(
                        "CaseProcessWorkflow:p9-control-build:"
                                + "IntakeRoomWorkflow:p9-control-build");
        assertThat(
                        jdbc.queryForObject(
                                "select writer_activation_status from case_process_projection where case_id = ?",
                                String.class,
                                caseId))
                .isEqualTo("PREPARING");
        assertThat(bootstrapEnqueuer.epochId()).isEqualTo(allocation.epochId());
        assertThat(targetBindingWriter.binding().epochId()).isEqualTo(allocation.epochId());
        assertThat(targetBindingWriter.binding().selection().targetActivationBinding())
                .isEqualTo(TrackingRoomEpochSelector.targetActivationBinding());
        assertThat(allocation.selection().targetActivationBinding()).isNull();
    }

    @Test
    void targetTemporalSelectionPersistsAnExactActivationBindingForEveryRoomType() {
        selector.useTemporal();

        for (RoomType roomType : RoomType.values()) {
            String caseId = "CASE_ALLOC_TARGET_" + roomType.name();
            insertCaseAndRooms(caseId, roomType);
            targetBindingWriter.reset();

            RoomEpochAllocation allocation =
                    inTransaction(() -> allocator.activate(activate(caseId, roomType, NOW)));

            BindingContext binding = targetBindingWriter.binding();
            assertThat(allocation.writerMode()).isEqualTo(WriterMode.TEMPORAL);
            assertThat(allocation.selection().roomWorkflowType())
                    .isEqualTo(TargetTypedRoomProtocol.workflowType(roomType));
            assertThat(binding.caseId()).isEqualTo(caseId);
            assertThat(binding.roomType()).isEqualTo(roomType);
            assertThat(binding.roomEpoch()).isZero();
            assertThat(binding.fencingToken()).isPositive();
            assertThat(binding.selection().writerMode()).isEqualTo(allocation.writerMode());
            assertThat(binding.selection().graphKey()).isEqualTo(allocation.selection().graphKey());
            assertThat(binding.selection().targetActivationBinding())
                    .isEqualTo(TrackingRoomEpochSelector.targetActivationBinding());
            assertThat(allocation.selection().targetActivationBinding()).isNull();
        }
    }

    @Test
    void temporalPreparingEpochCannotTransitionOrTerminate() {
        String caseId = "CASE_ALLOC_TEMPORAL_NOT_READY";
        insertCaseAndRooms(caseId, RoomType.INTAKE, RoomType.EVIDENCE);
        selector.useTemporal();
        RoomEpochAllocation preparing =
                inTransaction(() -> allocator.activate(activate(caseId, RoomType.INTAKE, NOW)));

        assertThatThrownBy(
                        () ->
                                inTransaction(
                                        () ->
                                                allocator.transition(
                                                        transition(
                                                                caseId,
                                                                RoomType.INTAKE,
                                                                RoomType.EVIDENCE,
                                                                NOW.plusMinutes(1)))))
                .isInstanceOfSatisfying(
                        RoomEpochAllocationException.class,
                        failure ->
                                assertThat(failure.reasonCode())
                                        .isEqualTo("ROOM_EPOCH_PROVISIONING_INCOMPLETE"));
        assertThatThrownBy(
                        () ->
                                inTransaction(
                                        () ->
                                                allocator.terminate(
                                                        new TerminateRoomEpoch(
                                                                caseId,
                                                                RoomType.INTAKE,
                                                                "CANCELLED",
                                                                "CLOSED",
                                                                NOW.plusMinutes(1)))))
                .isInstanceOfSatisfying(
                        RoomEpochAllocationException.class,
                        failure ->
                                assertThat(failure.reasonCode())
                                        .isEqualTo("ROOM_EPOCH_PROVISIONING_INCOMPLETE"));
        assertThat(
                        jdbc.queryForObject(
                                "select id from case_room_epoch where case_id = ?",
                                String.class,
                                caseId))
                .isEqualTo(preparing.epochId());
        assertThat(
                        jdbc.queryForObject(
                                "select lifecycle_status from case_room_epoch where id = ?",
                                String.class,
                                preparing.epochId()))
                .isEqualTo("PREPARING");
    }

    @Test
    void terminalImportIsLegacyIdempotentAndNeverReadsTheSelector() {
        String caseId = "CASE_ALLOC_TERMINAL_IMPORT";
        insertCaseAndRooms(caseId, RoomType.EVIDENCE);
        TerminalRoomEpoch command =
                new TerminalRoomEpoch(
                        caseId,
                        roomId(caseId, RoomType.EVIDENCE),
                        RoomType.EVIDENCE,
                        "COMPLETED",
                        "CLOSED",
                        NOW);

        RoomEpochAllocation inserted =
                inTransaction(() -> allocator.recordTerminal(command));
        RoomEpochAllocation replayed =
                inTransaction(() -> allocator.recordTerminal(command));

        assertThat(selector.calls()).isZero();
        assertThat(replayed.epochId()).isEqualTo(inserted.epochId());
        assertThat(inserted.writerMode()).isEqualTo(WriterMode.LEGACY);
        assertThat(inserted.lifecycleStatus()).isEqualTo(EpochLifecycleStatus.TERMINAL);
        assertThat(inserted.temporalWorkflowId()).isNull();
        assertThat(inserted.selection().workflowType()).isEqualTo("LegacyJavaRoomState");
        assertThat(countEpochs(caseId)).isEqualTo(1);
        assertThat(activeEpochCount(caseId)).isZero();
    }

    @Test
    void terminationAndItsReplayReuseTheSameTerminalEpoch() {
        String caseId = "CASE_ALLOC_TERMINATE";
        insertCaseAndRooms(caseId, RoomType.INTAKE);
        inTransaction(() -> allocator.activate(activate(caseId, RoomType.INTAKE, NOW)));
        TerminateRoomEpoch command =
                new TerminateRoomEpoch(
                        caseId,
                        RoomType.INTAKE,
                        "CANCELLED",
                        "CLOSED",
                        NOW.plusMinutes(1));

        RoomEpochAllocation terminated =
                inTransaction(() -> allocator.terminate(command));
        RoomEpochAllocation replayed =
                inTransaction(() -> allocator.terminate(command));

        assertThat(replayed.epochId()).isEqualTo(terminated.epochId());
        assertThat(replayed.processRevision()).isEqualTo(1);
        assertThat(replayed.roomRevision()).isEqualTo(1);
        assertThat(activeEpochCount(caseId)).isZero();
        assertThat(selector.calls()).isEqualTo(1);
    }

    @Test
    void backwardTransitionTimeIsRejectedWithoutMutatingTheActiveEpoch() {
        String caseId = "CASE_ALLOC_BACKWARD_TIME";
        insertCaseAndRooms(caseId, RoomType.INTAKE, RoomType.EVIDENCE);
        RoomEpochAllocation active =
                inTransaction(() -> allocator.activate(activate(caseId, RoomType.INTAKE, NOW)));

        assertThatThrownBy(
                        () ->
                                inTransaction(
                                        () ->
                                                allocator.transition(
                                                        transition(
                                                                caseId,
                                                                RoomType.INTAKE,
                                                                RoomType.EVIDENCE,
                                                                NOW.minusSeconds(1)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("terminal time cannot move backward");

        assertThat(activeEpochId(caseId)).isEqualTo(active.epochId());
        assertThat(countEpochs(caseId)).isEqualTo(1);
    }

    @Test
    void databaseRejectsActiveWorkflowConflictAndImmutableSelectionRewrite() {
        String firstCase = "CASE_ALLOC_DB_FIRST";
        String secondCase = "CASE_ALLOC_DB_SECOND";
        insertCaseAndRooms(firstCase, RoomType.INTAKE);
        insertCaseAndRooms(secondCase, RoomType.INTAKE);
        RoomEpochAllocation first =
                inTransaction(
                        () -> allocator.activate(activate(firstCase, RoomType.INTAKE, NOW)));

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update case_room_epoch set temporal_build_id = 'rewritten' where id = ?",
                                        first.epochId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("immutable execution selection cannot be rewritten");
        assertThatThrownBy(
                        () ->
                                insertEpochDirectly(
                                        "EPOCH_ACTIVE_WORKFLOW_CONFLICT",
                                        secondCase,
                                        RoomType.INTAKE,
                                        "ACTIVE",
                                        WriterMode.SHADOW,
                                        first.temporalWorkflowId(),
                                        null,
                                        NOW,
                                        null,
                                        NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsBlankWorkflowBindingAndBackwardTerminalInterval() {
        String blankCase = "CASE_ALLOC_DB_BLANK";
        String timeCase = "CASE_ALLOC_DB_TIME";
        insertCaseAndRooms(blankCase, RoomType.INTAKE);
        insertCaseAndRooms(timeCase, RoomType.INTAKE);

        assertThatThrownBy(
                        () ->
                                insertEpochDirectly(
                                        "EPOCH_BLANK_WORKFLOW",
                                        blankCase,
                                        RoomType.INTAKE,
                                        "ACTIVE",
                                        WriterMode.SHADOW,
                                        "   ",
                                        null,
                                        NOW,
                                        null,
                                        NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                insertEpochDirectly(
                                        "EPOCH_BACKWARD_INTERVAL",
                                        timeCase,
                                        RoomType.INTAKE,
                                        "TERMINAL",
                                        WriterMode.LEGACY,
                                        null,
                                        null,
                                        NOW,
                                        NOW.minusSeconds(1),
                                        NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private RoomEpochAllocation concurrentActivate(
            ActivateRoomEpoch command, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent allocator start was not released");
        }
        return inTransaction(() -> allocator.activate(command));
    }

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private ActivateRoomEpoch activate(
            String caseId, RoomType roomType, OffsetDateTime occurredAt) {
        return new ActivateRoomEpoch(
                caseId,
                roomId(caseId, roomType),
                roomType,
                roomType == RoomType.INTAKE ? "INTAKE_PENDING" : roomType.name() + "_OPEN",
                "OPEN",
                occurredAt.plusHours(1),
                occurredAt);
    }

    private TransitionRoomEpoch transition(
            String caseId,
            RoomType expectedRoom,
            RoomType nextRoom,
            OffsetDateTime occurredAt) {
        return transition(
                caseId,
                expectedRoom,
                nextRoom,
                occurredAt,
                nextRoom.name() + "_OPEN");
    }

    private TransitionRoomEpoch transition(
            String caseId,
            RoomType expectedRoom,
            RoomType nextRoom,
            OffsetDateTime occurredAt,
            String macroPhase) {
        return new TransitionRoomEpoch(
                caseId,
                expectedRoom,
                roomId(caseId, nextRoom),
                nextRoom,
                macroPhase,
                "OPEN",
                occurredAt.plusHours(1),
                occurredAt);
    }

    private void insertCaseAndRooms(String caseId, RoomType... rooms) {
        jdbc.update(
                """
                insert into fulfillment_dispute_case (
                    id, user_id, merchant_id, creation_idempotency_key,
                    case_type, case_status, initiator_role, initiator_id,
                    respondent_role, respondent_id, risk_level, title, description,
                    current_room, created_by, updated_by
                ) values (?, ?, ?, ?, 'DISPUTE', 'INTAKE_PENDING', 'USER', ?,
                    'MERCHANT', ?, 'LOW', 'Epoch allocator fixture',
                    'Transactional room epoch allocator integration fixture.',
                    'INTAKE', 'epoch-test', 'epoch-test')
                """,
                caseId,
                "user-" + caseId,
                "merchant-" + caseId,
                "create-" + caseId,
                "user-" + caseId,
                "merchant-" + caseId);
        for (RoomType room : rooms) {
            jdbc.update(
                    """
                    insert into case_room (
                        id, case_id, room_type, room_status, opened_at,
                        created_by, updated_by
                    ) values (?, ?, ?, 'OPEN', ?, 'epoch-test', 'epoch-test')
                    """,
                    roomId(caseId, room),
                    caseId,
                    room.name(),
                    NOW);
        }
    }

    private void insertEpochDirectly(
            String epochId,
            String caseId,
            RoomType roomType,
            String lifecycle,
            WriterMode writerMode,
            String workflowId,
            String runId,
            OffsetDateTime activatedAt,
            OffsetDateTime terminalAt,
            OffsetDateTime updatedAt) {
        jdbc.update(
                """
                insert into case_room_epoch (
                    id, tenant_surrogate, case_id, room_id, room_type, room_epoch,
                    writer_mode, lifecycle_status, process_revision, room_revision,
                    fencing_token, temporal_workflow_id, temporal_run_id, temporal_build_id,
                    graph_key, graph_version, checkpoint_schema_version, stream_protocol,
                    selection_schema_version, process_contract_version, workflow_type,
                    activated_at, terminal_at, created_at, updated_at
                ) values (?, ?, ?, ?, ?, 0, ?, ?, 0, 0, 1, ?, ?, 'direct-build',
                    'intake.direct', '1.0.0', 'checkpoint.v1', 'agent-stream.v2',
                    'room-epoch-selection.v1', 'case-process-contract.v1', ?,
                    ?, ?, ?, ?)
                """,
                epochId,
                TENANT,
                caseId,
                roomId(caseId, roomType),
                roomType.name(),
                writerMode.name(),
                lifecycle,
                workflowId,
                runId,
                writerMode == WriterMode.LEGACY
                        ? "LegacyJavaRoomState"
                        : CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                activatedAt,
                terminalAt,
                activatedAt,
                updatedAt);
    }

    private void installProjectionFailureTrigger() {
        jdbc.execute(
                """
                create or replace function reject_test_projection_switch()
                returns trigger language plpgsql as $$
                begin
                    if new.macro_phase = 'FAIL_TRANSITION' then
                        raise exception 'forced projection switch failure';
                    end if;
                    return new;
                end
                $$
                """);
        jdbc.execute(
                """
                create trigger trg_test_reject_projection_switch
                before update on case_process_projection
                for each row execute function reject_test_projection_switch()
                """);
    }

    private void removeProjectionFailureTrigger() {
        jdbc.execute(
                "drop trigger if exists trg_test_reject_projection_switch on case_process_projection");
        jdbc.execute("drop function if exists reject_test_projection_switch()");
    }

    private long countEpochs(String caseId) {
        return jdbc.queryForObject(
                "select count(*) from case_room_epoch where case_id = ?",
                Long.class,
                caseId);
    }

    private long activeEpochCount(String caseId) {
        return jdbc.queryForObject(
                "select count(*) from case_room_epoch where case_id = ? and lifecycle_status = 'ACTIVE'",
                Long.class,
                caseId);
    }

    private long projectionCount(String caseId) {
        return jdbc.queryForObject(
                "select count(*) from case_process_projection where case_id = ?",
                Long.class,
                caseId);
    }

    private String activeEpochId(String caseId) {
        return jdbc.queryForObject(
                "select id from case_room_epoch where case_id = ? and lifecycle_status = 'ACTIVE'",
                String.class,
                caseId);
    }

    private static String roomId(String caseId, RoomType roomType) {
        return "ROOM_" + caseId.substring("CASE_".length()) + "_" + roomType.name();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EpochAllocatorTestConfiguration {

        @Bean
        TrackingRoomEpochSelector roomEpochSelector() {
            return new TrackingRoomEpochSelector();
        }

        @Bean
        TenantAuthority tenantAuthority() {
            return () -> TENANT;
        }

        @Bean
        CapturingBootstrapEnqueuer roomEpochBootstrapEnqueuer() {
            return new CapturingBootstrapEnqueuer();
        }

        @Bean
        CapturingTargetBindingWriter targetRoomEpochBindingWriter() {
            return new CapturingTargetBindingWriter();
        }
    }

    static final class TrackingRoomEpochSelector implements RoomEpochSelector {

        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<SelectorConfiguration> configuration =
                new AtomicReference<>();

        TrackingRoomEpochSelector() {
            reset();
        }

        @Override
        public RoomEpochSelection selectForNewEpoch(RoomType roomType) {
            calls.incrementAndGet();
            SelectorConfiguration selected = configuration.get();
            boolean legacy = selected.writerMode() == WriterMode.LEGACY;
            if (!legacy) {
                if (selected.writerMode() == WriterMode.SHADOW
                        && roomType != RoomType.INTAKE) {
                    throw new IllegalStateException(
                            "test selector refuses SHADOW non-INTAKE selection");
                }
                boolean target = selected.writerMode() == WriterMode.TEMPORAL;
                return new RoomEpochSelection(
                        selected.writerMode(),
                        "room-epoch-selection.v2",
                        "case-process-contract.v1",
                        CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                        selected.buildId(),
                        target
                                ? TargetTypedRoomProtocol.workflowType(roomType)
                                : "IntakeRoomWorkflow",
                        target ? selected.buildId() : "intake-room.synthetic.v1",
                        target ? TargetTypedRoomProtocol.GRAPH_KEY : "intake.v2",
                        selected.graphVersion(),
                        target
                                ? TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION
                                : "intake-checkpoint.v2",
                        "agent-stream.v2",
                        selected.writerMode() == WriterMode.TEMPORAL
                                ? targetActivationBinding()
                                : null);
            }
            return new RoomEpochSelection(
                    selected.writerMode(),
                    "room-epoch-selection.v1",
                    "case-process-contract.v1",
                    "LegacyJavaRoomState",
                    selected.buildId(),
                    roomType.name().toLowerCase(Locale.ROOT) + ".test",
                    selected.graphVersion(),
                    "checkpoint.v1",
                    "agent-stream.v2");
        }

        void reset() {
            calls.set(0);
            useLegacy();
        }

        void useLegacy() {
            configuration.set(
                    new SelectorConfiguration(
                            WriterMode.LEGACY, "legacy-build-v1", "1.0.0"));
        }

        void useShadow(String buildId, String graphVersion) {
            configuration.set(
                    new SelectorConfiguration(WriterMode.SHADOW, buildId, graphVersion));
        }

        void useTemporal() {
            configuration.set(
                    new SelectorConfiguration(
                            WriterMode.TEMPORAL,
                            "p9-control-build",
                            TargetTypedRoomProtocol.GRAPH_VERSION));
        }

        int calls() {
            return calls.get();
        }

        private record SelectorConfiguration(
                WriterMode writerMode, String buildId, String graphVersion) {}

        static TargetActivationBinding targetActivationBinding() {
            return new TargetActivationBinding(
                    "p9act.v1.0123456789abcdef0123456789abcdef",
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "TARGET_E2E_CANDIDATE",
                    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        }
    }

    static final class CapturingTargetBindingWriter implements TargetRoomEpochBindingWriter {

        private final AtomicReference<BindingContext> binding = new AtomicReference<>();

        @Override
        public void persist(BindingContext context) {
            binding.set(context);
        }

        void reset() {
            binding.set(null);
        }

        BindingContext binding() {
            return binding.get();
        }
    }

    static final class CapturingBootstrapEnqueuer implements RoomEpochBootstrapEnqueuer {

        private final AtomicReference<String> epochId = new AtomicReference<>();

        @Override
        public String enqueue(
                CaseRoomEpochEntity epoch,
                CaseProcessProjectionEntity projection,
                OffsetDateTime availableAt) {
            assertThat(projection.getCaseId()).isEqualTo(epoch.getCaseId());
            assertThat(projection.getFencingToken()).isEqualTo(epoch.getFencingToken());
            epochId.set(epoch.getId());
            return "REBOOT_test";
        }

        String epochId() {
            return epochId.get();
        }

        void reset() {
            epochId.set(null);
        }
    }
}
