package com.example.dispute.workflow.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.workflow.application.command.TenantAuthority;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocationException;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.ActivateRoomEpoch;
import com.example.dispute.workflow.application.epoch.RoomEpochAllocator.RoomEpochAllocation;
import com.example.dispute.workflow.application.epoch.RoomEpochBootstrapDeliveryTrigger;
import com.example.dispute.workflow.application.epoch.RoomEpochSelection;
import com.example.dispute.workflow.application.epoch.RoomEpochSelectionContext;
import com.example.dispute.workflow.application.epoch.RoomEpochSelector;
import com.example.dispute.workflow.application.epoch.TransactionalRoomEpochAllocator;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.infrastructure.bootstrap.PostCommitRoomEpochBootstrapDeliveryTrigger;
import com.example.dispute.workflow.infrastructure.bootstrap.RoomEpochBootstrapDispatcher;
import com.example.dispute.workflow.infrastructure.bootstrap.RoomEpochBootstrapEnqueuer;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseProcessProjectionEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseProcessProjectionRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class RoomEpochBootstrapDeliveryTriggerTest {

    private static final String TENANT = "tenant-bootstrap-trigger";
    private static final String CASE_ID = "CASE_BOOTSTRAP_TRIGGER";
    private static final String ROOM_ID = "ROOM_BOOTSTRAP_TRIGGER_INTAKE";
    private static final String OUTBOX_ID = "REBOOT_exact_outbox";
    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 8, 17, 6, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void newEpochDispatchesExactOutboxOnlyAfterCommitAndKeepsPollingFallbackDurable() {
        RoomEpochBootstrapDispatcher dispatcher = mock(RoomEpochBootstrapDispatcher.class);
        Fixture success = fixture(WriterMode.SHADOW, dispatcher, true);
        when(dispatcher.dispatchNow(OUTBOX_ID))
                .thenAnswer(
                        invocation -> {
                            assertThat(success.outbox().committedIds()).containsExactly(OUTBOX_ID);
                            return true;
                        });

        RoomEpochAllocation first = success.inTransaction(() -> success.allocator().activate(command()));
        RoomEpochAllocation replay = success.inTransaction(() -> success.allocator().activate(command()));

        assertThat(replay).isEqualTo(first);
        assertThat(success.outbox().calls()).isEqualTo(1);
        assertThat(success.outbox().committedIds()).containsExactly(OUTBOX_ID);
        verify(dispatcher, times(1)).dispatchNow(OUTBOX_ID);

        RoomEpochBootstrapDispatcher rollbackDispatcher =
                mock(RoomEpochBootstrapDispatcher.class);
        Fixture rolledBack = fixture(WriterMode.SHADOW, rollbackDispatcher, true);
        rolledBack.rollback(() -> rolledBack.allocator().activate(command()));
        assertThat(rolledBack.outbox().committedIds()).isEmpty();
        verifyNoInteractions(rollbackDispatcher);

        RoomEpochBootstrapDispatcher failingDispatcher =
                mock(RoomEpochBootstrapDispatcher.class);
        when(failingDispatcher.dispatchNow(OUTBOX_ID))
                .thenThrow(new IllegalStateException("simulated delivery failure"));
        Fixture deliveryFailure = fixture(WriterMode.SHADOW, failingDispatcher, true);

        RoomEpochAllocation committed =
                deliveryFailure.inTransaction(
                        () -> deliveryFailure.allocator().activate(command()));

        assertThat(committed.caseId()).isEqualTo(CASE_ID);
        assertThat(deliveryFailure.outbox().committedIds()).containsExactly(OUTBOX_ID);
        verify(failingDispatcher).dispatchNow(OUTBOX_ID);

        Fixture missingTrigger = fixture(WriterMode.SHADOW, mock(RoomEpochBootstrapDispatcher.class), false);
        assertThatThrownBy(
                        () ->
                                missingTrigger.inTransaction(
                                        () -> missingTrigger.allocator().activate(command())))
                .isInstanceOf(RoomEpochAllocationException.class)
                .satisfies(
                        failure ->
                                assertThat(((RoomEpochAllocationException) failure).reasonCode())
                                        .isEqualTo("ROOM_EPOCH_BOOTSTRAP_DELIVERY_UNAVAILABLE"));
        assertThat(missingTrigger.outbox().calls()).isZero();

        RoomEpochBootstrapDispatcher legacyDispatcher =
                mock(RoomEpochBootstrapDispatcher.class);
        Fixture legacy = fixture(WriterMode.LEGACY, legacyDispatcher, false);
        RoomEpochAllocation legacyAllocation =
                legacy.inTransaction(() -> legacy.allocator().activate(command()));
        assertThat(legacyAllocation.writerMode()).isEqualTo(WriterMode.LEGACY);
        assertThat(legacy.outbox().calls()).isZero();
        verify(legacyDispatcher, never()).dispatchNow(any());
    }

    private static Fixture fixture(
            WriterMode writerMode,
            RoomEpochBootstrapDispatcher dispatcher,
            boolean deliveryAvailable) {
        FulfillmentCaseRepository cases = mock(FulfillmentCaseRepository.class);
        CaseRoomEpochRepository epochs = mock(CaseRoomEpochRepository.class);
        CaseProcessProjectionRepository projections =
                mock(CaseProcessProjectionRepository.class);
        RoomEpochSelector selector = mock(RoomEpochSelector.class);
        TransactionalOutbox outbox = new TransactionalOutbox();
        AtomicReference<CaseRoomEpochEntity> epochState = new AtomicReference<>();
        AtomicReference<CaseProcessProjectionEntity> projectionState =
                new AtomicReference<>();

        when(cases.findByIdForUpdate(CASE_ID))
                .thenReturn(Optional.of(mock(FulfillmentCaseEntity.class)));
        when(epochs.findWriterSlotByCaseIdForUpdate(CASE_ID))
                .thenAnswer(ignored -> Optional.ofNullable(epochState.get()));
        when(projections.findByIdForUpdate(CASE_ID))
                .thenAnswer(ignored -> Optional.ofNullable(projectionState.get()));
        when(epochs.findMaxRoomEpoch(CASE_ID, RoomType.INTAKE))
                .thenReturn(Optional.empty());
        when(epochs.findMaxFencingToken(CASE_ID)).thenReturn(Optional.empty());
        when(epochs.saveAndFlush(any(CaseRoomEpochEntity.class)))
                .thenAnswer(
                        invocation -> {
                            CaseRoomEpochEntity value = invocation.getArgument(0);
                            epochState.set(value);
                            return value;
                        });
        when(projections.saveAndFlush(any(CaseProcessProjectionEntity.class)))
                .thenAnswer(
                        invocation -> {
                            CaseProcessProjectionEntity value = invocation.getArgument(0);
                            projectionState.set(value);
                            return value;
                        });
        when(selector.selectForNewEpoch(
                        eq(RoomType.INTAKE), any(RoomEpochSelectionContext.class)))
                .thenReturn(selection(writerMode));

        ObjectProvider<RoomEpochBootstrapEnqueuer> enqueuerProvider = provider(outbox);
        TenantAuthority tenantAuthority = () -> TENANT;
        TransactionalRoomEpochAllocator allocator;
        if (deliveryAvailable) {
            RoomEpochBootstrapDeliveryTrigger trigger =
                    new PostCommitRoomEpochBootstrapDeliveryTrigger(
                            new PostCommitSideEffectExecutor(Runnable::run),
                            dispatcher);
            allocator =
                    new TransactionalRoomEpochAllocator(
                            cases,
                            epochs,
                            projections,
                            selector,
                            tenantAuthority,
                            enqueuerProvider,
                            provider(trigger),
                            null);
        } else {
            allocator =
                    new TransactionalRoomEpochAllocator(
                            cases,
                            epochs,
                            projections,
                            selector,
                            tenantAuthority,
                            enqueuerProvider,
                            null);
        }
        return new Fixture(
                allocator,
                outbox,
                new TransactionTemplate(new NoResourceTransactionManager()));
    }

    private static RoomEpochSelection selection(WriterMode writerMode) {
        return new RoomEpochSelection(
                writerMode,
                RoomEpochSelection.V1,
                "case-process-contract.v1",
                writerMode == WriterMode.LEGACY
                        ? "LegacyJavaRoomState"
                        : CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE,
                writerMode == WriterMode.LEGACY ? "legacy-build" : "shadow-build",
                "intake.v2",
                "graph.2026-08-17",
                "checkpoint.v2",
                "agent-stream.v2");
    }

    private static ActivateRoomEpoch command() {
        return new ActivateRoomEpoch(
                CASE_ID,
                ROOM_ID,
                RoomType.INTAKE,
                "INTAKE",
                "OPEN",
                NOW.plusMinutes(30),
                NOW);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        when(provider.stream()).thenAnswer(ignored -> Stream.of(value));
        return provider;
    }

    private record Fixture(
            TransactionalRoomEpochAllocator allocator,
            TransactionalOutbox outbox,
            TransactionTemplate transactions) {

        <T> T inTransaction(java.util.function.Supplier<T> action) {
            return transactions.execute(ignored -> action.get());
        }

        void rollback(Runnable action) {
            transactions.executeWithoutResult(
                    status -> {
                        action.run();
                        status.setRollbackOnly();
                    });
        }
    }

    private static final class TransactionalOutbox implements RoomEpochBootstrapEnqueuer {

        private final AtomicInteger calls = new AtomicInteger();
        private final Set<String> committedIds = new LinkedHashSet<>();

        @Override
        public String enqueue(
                CaseRoomEpochEntity epoch,
                CaseProcessProjectionEntity projection,
                OffsetDateTime availableAt) {
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
            assertThat(projection.getCaseId()).isEqualTo(epoch.getCaseId());
            assertThat(availableAt).isEqualTo(NOW);
            calls.incrementAndGet();
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            committedIds.add(OUTBOX_ID);
                        }
                    });
            return OUTBOX_ID;
        }

        int calls() {
            return calls.get();
        }

        Set<String> committedIds() {
            return Set.copyOf(committedIds);
        }
    }

    private static final class NoResourceTransactionManager
            extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
