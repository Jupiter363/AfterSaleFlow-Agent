package com.example.dispute.workflow.runtime.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.workflow.application.command.TenantAuthority;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.TransitionRoomEpoch;
import com.example.dispute.workflow.application.epoch.RoomEpochSelector;
import com.example.dispute.workflow.application.epoch.RoomEpochSelection;
import com.example.dispute.workflow.application.epoch.RoomEpochSelection.TargetActivationBinding;
import com.example.dispute.workflow.application.epoch.RoomEpochBootstrapDeliveryTrigger;
import com.example.dispute.workflow.application.epoch.TransactionalRoomEpochAllocator;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.bootstrap.RoomEpochBootstrapEnqueuer;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseProcessProjectionEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochProvisioningStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.WriterActivationStatus;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseProcessProjectionRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.example.dispute.workflow.runtime.temporal.TargetRoomEpochBindingWriter.BindingContext;
import com.example.dispute.workflow.runtime.temporal.TargetRoomEpochBindingWriter.SuccessorContext;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class JdbcTargetRoomEpochBindingWriterTest {

    @Test
    void isRegisteredAsTheTransactionalTargetBindingWriter() {
        assertThat(JdbcTargetRoomEpochBindingWriter.class).hasAnnotation(Repository.class);
    }

    @AfterEach
    void clearTransactionMarker() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void writesTheExactActivationAndEpochTupleInsideTheAllocationTransaction() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(contains("insert into production_runtime_room_epoch_binding"),
                        isA(MapSqlParameterSource.class)))
                .thenReturn(1);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

        new JdbcTargetRoomEpochBindingWriter(jdbc).persist(context());

        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(
                contains("insert into production_runtime_room_epoch_binding"), parameters.capture());
        assertThat(parameters.getValue().getValue("epochId")).isEqualTo("epoch-1");
        assertThat(parameters.getValue().getValue("activationId"))
                .isEqualTo("p9act.v1.0123456789abcdef0123456789abcdef");
        assertThat(parameters.getValue().getValue("roomType")).isEqualTo("HEARING");
        assertThat(parameters.getValue().getValue("roomEpoch")).isEqualTo(2L);
        assertThat(parameters.getValue().getValue("roomFencingToken")).isEqualTo(7L);
        assertThat(parameters.getValue().getValue("intakeRoomMessageExecutionProfileId"))
                .isEqualTo("MONOLITHIC_V3");
    }

    @Test
    void pinsNewTargetIntakeEpochsToTheParallelRoomMessageProfile() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(
                        contains("insert into production_runtime_room_epoch_binding"),
                        isA(MapSqlParameterSource.class)))
                .thenReturn(1);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

        new JdbcTargetRoomEpochBindingWriter(jdbc).persist(context(RoomType.INTAKE));

        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(
                contains("insert into production_runtime_room_epoch_binding"), parameters.capture());
        assertThat(parameters.getValue().getValue("roomType")).isEqualTo("INTAKE");
        assertThat(parameters.getValue().getValue("intakeRoomMessageExecutionProfileId"))
                .isEqualTo("PARALLEL_FRAMES_V1");
    }

    @Test
    void targetSuccessorSelectionIsInheritedFromTheExactLockedSourceAuthority() throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        ResultSet row = mock(ResultSet.class);
        when(row.getString(1)).thenReturn("p9act.v1.0123456789abcdef0123456789abcdef");
        when(row.getString(2)).thenReturn("a".repeat(64));
        when(row.getString(3)).thenReturn("PRODUCTION");
        when(row.getString(4)).thenReturn("b".repeat(64));
        when(jdbc.query(
                        anyString(),
                        isA(MapSqlParameterSource.class),
                        isA(RowMapper.class)))
                .thenAnswer(
                        invocation -> {
                            @SuppressWarnings("unchecked")
                            RowMapper<TargetActivationBinding> mapper = invocation.getArgument(2);
                            return List.of(mapper.mapRow(row, 0));
                        });
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        JdbcTargetRoomEpochBindingWriter writer = new JdbcTargetRoomEpochBindingWriter(jdbc);

        RoomEpochSelection first = writer.selectSuccessor(successorContext());
        RoomEpochSelection replay = writer.selectSuccessor(successorContext());

        assertThat(replay).isEqualTo(first);
        assertThat(first.roomWorkflowType())
                .isEqualTo(TargetTypedRoomProtocol.workflowType(RoomType.HEARING));
        assertThat(first.caseWorkflowBuildId()).isEqualTo("p9-case-build");
        assertThat(first.graphKey()).isEqualTo("all-rooms.production-runtime.v1");
        assertThat(first.targetActivationBinding()).isEqualTo(context().selection().targetActivationBinding());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc, times(2)).query(sql.capture(), parameters.capture(), isA(RowMapper.class));
        assertThat(sql.getValue())
                .contains("join case_process_projection projection")
                .contains("join production_runtime_room_epoch_binding binding")
                .contains("activation.lifecycle_status = 'DRAIN_ONLY'")
                .contains("epoch.lifecycle_status = 'ACTIVE'")
                .contains("epoch.provisioning_status = 'READY'")
                .contains("projection.writer_activation_status = 'READY'")
                .contains("for update of epoch, projection, binding, activation");
        assertThat(parameters.getValue().getValue("sourceEpochId")).isEqualTo("epoch-evidence");
        assertThat(parameters.getValue().getValue("sourceRoomType")).isEqualTo("EVIDENCE");
        assertThat(parameters.getValue().getValue("nextRoomType")).isEqualTo("HEARING");

        NamedParameterJdbcTemplate missing = mock(NamedParameterJdbcTemplate.class);
        when(missing.query(
                        anyString(),
                        isA(MapSqlParameterSource.class),
                        isA(RowMapper.class)))
                .thenReturn(List.of());
        assertThatThrownBy(
                        () ->
                                new JdbcTargetRoomEpochBindingWriter(missing)
                                        .selectSuccessor(successorContext()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "TEMPORAL room epoch selection requires exact target activation authority");

        assertAllocatorInheritsTargetSuccessorWithoutFreshSelection();
    }

    @Test
    void rejectsCallsOutsideTheWritableAllocationTransaction() {
        JdbcTargetRoomEpochBindingWriter writer =
                new JdbcTargetRoomEpochBindingWriter(mock(NamedParameterJdbcTemplate.class));

        assertThatThrownBy(() -> writer.persist(context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "target room epoch binding requires the active writable allocation transaction");

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(() -> writer.persist(context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "target room epoch binding requires the active writable allocation transaction");
    }

    @Test
    void rejectsAnInsertThatDoesNotPersistExactlyOneImmutableBinding() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(contains("insert into production_runtime_room_epoch_binding"),
                        isA(MapSqlParameterSource.class)))
                .thenReturn(0);
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> new JdbcTargetRoomEpochBindingWriter(jdbc).persist(context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "target room epoch activation binding was not persisted exactly once");
    }

    private static BindingContext context() {
        return context(RoomType.HEARING);
    }

    private static BindingContext context(RoomType roomType) {
        return new BindingContext(
                "epoch-1",
                "tenant-target",
                "CASE_TARGET_0001",
                roomType,
                2,
                7,
                new RoomEpochSelection(
                        WriterMode.TEMPORAL,
                        RoomEpochSelection.V2,
                        "case-process-contract.v1",
                        "CaseProcessWorkflow",
                        "p9-case-build",
                        TargetTypedRoomProtocol.workflowType(roomType),
                        "p9-control-build",
                        "all-rooms.production-runtime.v1",
                        TargetTypedRoomProtocol.GRAPH_VERSION,
                        "production-runtime-checkpoint.v1",
                        "agent-stream.v2",
                        new TargetActivationBinding(
                                "p9act.v1.0123456789abcdef0123456789abcdef",
                                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                "PRODUCTION",
                                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")));
    }

    private static SuccessorContext successorContext() {
        return new SuccessorContext(
                "epoch-evidence",
                "tenant-target",
                "CASE_TARGET_0001",
                RoomType.EVIDENCE,
                1,
                6,
                11,
                "case-process-workflow",
                RoomType.HEARING,
                new RoomEpochSelection(
                        WriterMode.TEMPORAL,
                        RoomEpochSelection.V2,
                        "case-process-contract.v1",
                        "CaseProcessWorkflow",
                        "p9-case-build",
                        TargetTypedRoomProtocol.workflowType(RoomType.EVIDENCE),
                        "p9-control-build",
                        "all-rooms.production-runtime.v1",
                        TargetTypedRoomProtocol.GRAPH_VERSION,
                        "production-runtime-checkpoint.v1",
                        "agent-stream.v2"));
    }

    private static void assertAllocatorInheritsTargetSuccessorWithoutFreshSelection()
            throws Exception {
        String caseId = "CASE_TARGET_TRANSITION";
        String tenant = "tenant-target";
        OffsetDateTime occurredAt =
                OffsetDateTime.of(2026, 8, 16, 1, 0, 0, 0, ZoneOffset.UTC);
        String caseWorkflowId =
                com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol
                        .caseWorkflowId(tenant, caseId);
        RoomEpochSelection sourcePins = successorContext().sourceSelection();
        CaseRoomEpochEntity source =
                CaseRoomEpochEntity.preparing(
                        "epoch-evidence",
                        tenant,
                        caseId,
                        "ROOM_TARGET_EVIDENCE",
                        RoomType.EVIDENCE,
                        1,
                        11,
                        5,
                        6,
                        caseWorkflowId,
                        sourcePins.caseWorkflowBuildId(),
                        sourcePins.graphKey(),
                        sourcePins.graphVersion(),
                        sourcePins.checkpointSchemaVersion(),
                        sourcePins.streamProtocol(),
                        sourcePins.selectionSchemaVersion(),
                        sourcePins.processContractVersion(),
                        sourcePins.caseWorkflowType(),
                        sourcePins.roomWorkflowType(),
                        sourcePins.roomWorkflowBuildId(),
                        occurredAt);
        setField(source, "lifecycleStatus", EpochLifecycleStatus.ACTIVE);
        setField(source, "provisioningStatus", EpochProvisioningStatus.READY);
        setField(source, "temporalRunId", "case-run-evidence");
        setField(source, "roomTemporalRunId", "room-run-evidence");
        setField(source, "provisionedAt", occurredAt);

        CaseProcessProjectionEntity projection =
                CaseProcessProjectionEntity.initialize(
                        caseId,
                        tenant,
                        "EVIDENCE_OPEN",
                        "EVIDENCE",
                        "OPEN",
                        WriterMode.TEMPORAL,
                        11,
                        1,
                        6,
                        occurredAt.plusHours(1),
                        caseWorkflowId,
                        "case-run-evidence",
                        sourcePins.caseWorkflowBuildId(),
                        occurredAt);
        setField(projection, "writerActivationStatus", WriterActivationStatus.READY);

        FulfillmentCaseRepository caseRepository = mock(FulfillmentCaseRepository.class);
        CaseRoomEpochRepository epochRepository = mock(CaseRoomEpochRepository.class);
        CaseProcessProjectionRepository projectionRepository =
                mock(CaseProcessProjectionRepository.class);
        RoomEpochSelector freshSelector = mock(RoomEpochSelector.class);
        TenantAuthority tenantAuthority = mock(TenantAuthority.class);
        RoomEpochBootstrapEnqueuer bootstrap = mock(RoomEpochBootstrapEnqueuer.class);
        RoomEpochBootstrapDeliveryTrigger deliveryTrigger =
                mock(RoomEpochBootstrapDeliveryTrigger.class);
        TargetRoomEpochBindingWriter bindingAuthority =
                mock(TargetRoomEpochBindingWriter.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RoomEpochBootstrapEnqueuer> bootstrapProvider =
                mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RoomEpochBootstrapDeliveryTrigger> deliveryProvider =
                mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<TargetRoomEpochBindingWriter> bindingProvider =
                mock(ObjectProvider.class);
        AtomicReference<CaseRoomEpochEntity> writerSlot = new AtomicReference<>(source);

        when(caseRepository.findByIdForUpdate(caseId))
                .thenReturn(
                        Optional.of(
                                mock(
                                        com.example.dispute.infrastructure.persistence.entity
                                                .FulfillmentCaseEntity.class)));
        when(epochRepository.findWriterSlotByCaseIdForUpdate(caseId))
                .thenAnswer(ignored -> Optional.of(writerSlot.get()));
        when(epochRepository.findMaxRoomEpoch(caseId, RoomType.HEARING))
                .thenReturn(Optional.empty());
        when(epochRepository.findMaxFencingToken(caseId)).thenReturn(Optional.of(6L));
        when(epochRepository.saveAndFlush(any(CaseRoomEpochEntity.class)))
                .thenAnswer(
                        invocation -> {
                            CaseRoomEpochEntity saved = invocation.getArgument(0);
                            if (saved.getLifecycleStatus() != EpochLifecycleStatus.TERMINAL) {
                                writerSlot.set(saved);
                            }
                            return saved;
                        });
        when(projectionRepository.findByIdForUpdate(caseId))
                .thenReturn(Optional.of(projection));
        when(projectionRepository.saveAndFlush(any(CaseProcessProjectionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(tenantAuthority.tenantSurrogate()).thenReturn(tenant);
        when(bootstrapProvider.getIfAvailable()).thenReturn(bootstrap);
        when(deliveryProvider.stream())
                .thenAnswer(ignored -> java.util.stream.Stream.of(deliveryTrigger));
        when(bootstrap.enqueue(
                        any(CaseRoomEpochEntity.class),
                        any(CaseProcessProjectionEntity.class),
                        any(OffsetDateTime.class)))
                .thenReturn("REBOOT_TARGET_SUCCESSOR");
        when(bindingProvider.stream())
                .thenAnswer(ignored -> java.util.stream.Stream.of(bindingAuthority));
        when(bindingAuthority.selectSuccessor(any(SuccessorContext.class)))
                .thenAnswer(
                        invocation -> {
                            SuccessorContext context = invocation.getArgument(0);
                            RoomEpochSelection pins = context.sourceSelection();
                            return new RoomEpochSelection(
                                    WriterMode.TEMPORAL,
                                    pins.selectionSchemaVersion(),
                                    pins.processContractVersion(),
                                    pins.caseWorkflowType(),
                                    pins.caseWorkflowBuildId(),
                                    TargetTypedRoomProtocol.workflowType(context.nextRoomType()),
                                    pins.roomWorkflowBuildId(),
                                    pins.graphKey(),
                                    pins.graphVersion(),
                                    pins.checkpointSchemaVersion(),
                                    pins.streamProtocol(),
                                    context().selection().targetActivationBinding());
                        });

        TransactionalRoomEpochAllocator allocator =
                new TransactionalRoomEpochAllocator(
                        caseRepository,
                        epochRepository,
                        projectionRepository,
                        freshSelector,
                        tenantAuthority,
                        bootstrapProvider,
                        deliveryProvider,
                        bindingProvider);
        TransitionRoomEpoch command =
                new TransitionRoomEpoch(
                        caseId,
                        RoomType.EVIDENCE,
                        "ROOM_TARGET_HEARING",
                        RoomType.HEARING,
                        "HEARING_OPEN",
                        "OPEN",
                        occurredAt.plusHours(2),
                        occurredAt.plusMinutes(1));

        var successor = allocator.transition(command);
        CaseRoomEpochEntity successorEntity = writerSlot.get();
        ArgumentCaptor<BindingContext> persisted = ArgumentCaptor.forClass(BindingContext.class);
        verify(bindingAuthority).persist(persisted.capture());
        verify(bindingAuthority).selectSuccessor(any(SuccessorContext.class));
        verifyNoInteractions(freshSelector);
        assertThat(successor.roomType()).isEqualTo(RoomType.HEARING);
        assertThat(persisted.getValue().selection().targetActivationBinding())
                .isEqualTo(context().selection().targetActivationBinding());
        assertThat(persisted.getValue().selection().caseWorkflowBuildId())
                .isEqualTo(sourcePins.caseWorkflowBuildId());
        assertThat(persisted.getValue().selection().graphVersion())
                .isEqualTo(sourcePins.graphVersion());

        setField(successorEntity, "lifecycleStatus", EpochLifecycleStatus.ACTIVE);
        setField(successorEntity, "provisioningStatus", EpochProvisioningStatus.READY);
        setField(successorEntity, "temporalRunId", "case-run-hearing");
        setField(successorEntity, "roomTemporalRunId", "room-run-hearing");
        setField(successorEntity, "provisionedAt", occurredAt.plusMinutes(2));
        setField(projection, "writerActivationStatus", WriterActivationStatus.READY);
        setField(projection, "temporalRunId", "case-run-hearing");

        var replay = allocator.transition(command);
        assertThat(replay.epochId()).isEqualTo(successor.epochId());
        verify(bindingAuthority, times(1)).selectSuccessor(any(SuccessorContext.class));
        verify(bindingAuthority, times(1)).persist(any(BindingContext.class));
        verifyNoInteractions(freshSelector);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
