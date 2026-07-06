package com.example.dispute.common.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PostCommitSideEffectExecutorTest {

    private final QueuedExecutor sideEffects = new QueuedExecutor();
    private final PostCommitSideEffectExecutor executor =
            new PostCommitSideEffectExecutor(sideEffects);

    @AfterEach
    void tearDownSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void immediateSideEffectsAreDispatchedAsynchronouslyAndFailuresDoNotPropagateToTheBusinessRequest() {
        AtomicBoolean attempted = new AtomicBoolean(false);

        assertThatCode(
                        () ->
                                executor.execute(
                                        "temporal-signal",
                                        Map.of("case_id", "CASE_1"),
                                        () -> {
                                            attempted.set(true);
                                            throw new IllegalStateException("temporal unavailable");
                                        }))
                .doesNotThrowAnyException();
        assertThat(attempted).isFalse();
        assertThat(sideEffects.tasks).hasSize(1);

        assertThatCode(sideEffects::runAll).doesNotThrowAnyException();
        assertThat(attempted).isTrue();
    }

    @Test
    void afterCommitSideEffectFailuresDoNotPropagateToTheCommittedTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        AtomicBoolean attempted = new AtomicBoolean(false);

        executor.execute(
                "temporal-start",
                Map.of("case_id", "CASE_2"),
                () -> {
                    attempted.set(true);
                    throw new IllegalStateException("workflow missing");
                });

        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        TransactionSynchronization synchronization =
                TransactionSynchronizationManager.getSynchronizations().getFirst();

        assertThatCode(synchronization::afterCommit).doesNotThrowAnyException();
        assertThat(attempted).isFalse();
        assertThat(sideEffects.tasks).hasSize(1);

        assertThatCode(sideEffects::runAll).doesNotThrowAnyException();
        assertThat(attempted).isTrue();
    }

    private static final class QueuedExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void runAll() {
            List<Runnable> pending = new ArrayList<>(tasks);
            tasks.clear();
            pending.forEach(Runnable::run);
        }
    }
}
