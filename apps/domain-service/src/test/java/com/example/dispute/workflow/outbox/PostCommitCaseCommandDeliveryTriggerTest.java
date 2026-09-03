package com.example.dispute.workflow.outbox;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
import com.example.dispute.workflow.infrastructure.outbox.PostCommitCaseCommandDeliveryTrigger;
import com.example.dispute.workflow.infrastructure.outbox.TemporalCommandDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class PostCommitCaseCommandDeliveryTriggerTest {

    @Mock private TemporalCommandDispatcher dispatcher;

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void dispatchesOnlyAfterTheAcceptanceTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();
        var trigger =
                new PostCommitCaseCommandDeliveryTrigger(
                        new PostCommitSideEffectExecutor(Runnable::run), dispatcher);

        trigger.deliveryRequested("COUT_COMMIT");

        verify(dispatcher, never()).dispatchNow("COUT_COMMIT");
        TransactionSynchronization synchronization =
                TransactionSynchronizationManager.getSynchronizations().getFirst();
        synchronization.afterCommit();
        verify(dispatcher).dispatchNow("COUT_COMMIT");
    }

    @Test
    void rollbackNeverInvokesTheFastPath() {
        TransactionSynchronizationManager.initSynchronization();
        var trigger =
                new PostCommitCaseCommandDeliveryTrigger(
                        new PostCommitSideEffectExecutor(Runnable::run), dispatcher);

        trigger.deliveryRequested("COUT_ROLLBACK");
        TransactionSynchronization synchronization =
                TransactionSynchronizationManager.getSynchronizations().getFirst();
        synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(dispatcher, never()).dispatchNow("COUT_ROLLBACK");
    }
}
