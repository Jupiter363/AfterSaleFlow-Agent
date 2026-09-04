package com.example.dispute.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.workflow.application.command.TenantAuthority;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocationException;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.ActivateRoomEpoch;
import com.example.dispute.workflow.application.epoch.RoomEpochSelection;
import com.example.dispute.workflow.application.epoch.RoomEpochSelection.TargetActivationBinding;
import com.example.dispute.workflow.application.epoch.RoomEpochSelector;
import com.example.dispute.workflow.application.epoch.TransactionalRoomEpochAllocator;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.bootstrap.RoomEpochBootstrapEnqueuer;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseProcessProjectionEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseProcessProjectionRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.example.dispute.workflow.runtime.temporal.TargetRoomEpochBindingWriter;
import com.example.dispute.workflow.runtime.temporal.TargetRoomEpochBindingWriter.BindingContext;
import com.example.dispute.workflow.runtime.temporal.TargetTypedRoomProtocol;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

class TargetRoomEpochBindingWriterTest {

    private static final String TENANT = "tenant-target";
    private static final String CASE_ID = "CASE_TARGET_0001";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-27T10:00:00Z");

    @Test
    void persistsExactTargetBindingAfterEpochFlushAndBeforeBootstrap() {
        Fixture fixture = fixture(provider(mock(TargetRoomEpochBindingWriter.class)));
        TargetRoomEpochBindingWriter writer =
                fixture.targetBindingWriterProvider().stream().findFirst().orElseThrow();

        fixture.allocator().activate(command());

        ArgumentCaptor<BindingContext> captor = ArgumentCaptor.forClass(BindingContext.class);
        verify(writer).persist(captor.capture());
        BindingContext context = captor.getValue();
        assertThat(context.tenantSurrogate()).isEqualTo(TENANT);
        assertThat(context.caseId()).isEqualTo(CASE_ID);
        assertThat(context.roomType()).isEqualTo(RoomType.INTAKE);
        assertThat(context.roomEpoch()).isZero();
        assertThat(context.fencingToken()).isEqualTo(1);
        assertThat(context.selection().targetActivationBinding())
                .isEqualTo(targetSelection().targetActivationBinding());
        InOrder order = inOrder(fixture.epochRepository(), writer, fixture.bootstrapEnqueuer());
        order.verify(fixture.epochRepository()).saveAndFlush(any(CaseRoomEpochEntity.class));
        order.verify(writer).persist(any(BindingContext.class));
        order.verify(fixture.bootstrapEnqueuer())
                .enqueue(any(CaseRoomEpochEntity.class), any(CaseProcessProjectionEntity.class), any());
    }

    @Test
    void targetSelectionFailsClosedWhenTheTransactionalBindingWriterIsMissing() {
        Fixture fixture = fixture(provider());

        assertThatThrownBy(() -> fixture.allocator().activate(command()))
                .isInstanceOfSatisfying(
                        RoomEpochAllocationException.class,
                        failure ->
                                assertThat(failure.reasonCode())
                                        .isEqualTo("PRODUCTION_RUNTIME_ACTIVATION_BINDING_UNAVAILABLE"));
    }

    private static Fixture fixture(ObjectProvider<TargetRoomEpochBindingWriter> writerProvider) {
        FulfillmentCaseRepository caseRepository = mock(FulfillmentCaseRepository.class);
        CaseRoomEpochRepository epochRepository = mock(CaseRoomEpochRepository.class);
        CaseProcessProjectionRepository projectionRepository =
                mock(CaseProcessProjectionRepository.class);
        RoomEpochSelector selector = mock(RoomEpochSelector.class);
        TenantAuthority tenantAuthority = () -> TENANT;
        RoomEpochBootstrapEnqueuer enqueuer = mock(RoomEpochBootstrapEnqueuer.class);
        ObjectProvider<RoomEpochBootstrapEnqueuer> enqueuerProvider = mock(ObjectProvider.class);

        when(caseRepository.findByIdForUpdate(CASE_ID))
                .thenReturn(Optional.of(mock(FulfillmentCaseEntity.class)));
        when(epochRepository.findWriterSlotByCaseIdForUpdate(CASE_ID))
                .thenReturn(Optional.empty());
        when(epochRepository.findMaxRoomEpoch(CASE_ID, RoomType.INTAKE))
                .thenReturn(Optional.empty());
        when(epochRepository.findMaxFencingToken(CASE_ID)).thenReturn(Optional.empty());
        when(epochRepository.saveAndFlush(any(CaseRoomEpochEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(projectionRepository.findByIdForUpdate(CASE_ID)).thenReturn(Optional.empty());
        when(projectionRepository.saveAndFlush(any(CaseProcessProjectionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(selector.selectForNewEpoch(any(), any())).thenReturn(targetSelection());
        when(enqueuerProvider.getIfAvailable()).thenReturn(enqueuer);

        var allocator =
                new TransactionalRoomEpochAllocator(
                        caseRepository,
                        epochRepository,
                        projectionRepository,
                        selector,
                        tenantAuthority,
                        enqueuerProvider,
                        writerProvider);
        return new Fixture(allocator, writerProvider, epochRepository, enqueuer);
    }

    private static ActivateRoomEpoch command() {
        return new ActivateRoomEpoch(
                CASE_ID,
                "ROOM_TARGET_INTAKE",
                RoomType.INTAKE,
                "INTAKE",
                "OPEN",
                NOW.plusHours(1),
                NOW);
    }

    private static RoomEpochSelection targetSelection() {
        return new RoomEpochSelection(
                WriterMode.TEMPORAL,
                RoomEpochSelection.V2,
                "case-process-contract.v1",
                "CaseProcessWorkflow",
                "p9-case-build",
                TargetTypedRoomProtocol.workflowType(RoomType.INTAKE),
                "p9-control-build",
                "all-rooms.production-runtime.v1",
                TargetTypedRoomProtocol.GRAPH_VERSION,
                "production-runtime-checkpoint.v1",
                "agent-stream.v2",
                new TargetActivationBinding(
                        "p9act.v1.0123456789abcdef0123456789abcdef",
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        "PRODUCTION",
                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
    }

    @SafeVarargs
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T... values) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.stream()).thenAnswer(ignored -> Stream.of(values));
        return provider;
    }

    private record Fixture(
            TransactionalRoomEpochAllocator allocator,
            ObjectProvider<TargetRoomEpochBindingWriter> targetBindingWriterProvider,
            CaseRoomEpochRepository epochRepository,
            RoomEpochBootstrapEnqueuer bootstrapEnqueuer) {}
}
