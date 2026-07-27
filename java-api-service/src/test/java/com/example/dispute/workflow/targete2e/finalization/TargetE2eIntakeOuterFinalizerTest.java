package com.example.dispute.workflow.targete2e.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunFormalResultCommitter;
import com.example.dispute.agentstream.application.AgentRunFormalResultCommitter.FormalResultCommit;
import com.example.dispute.agentstream.application.AgentRunV2ManifestFactory;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt.CommitStatus;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationReceiptLedger.AppendCommand;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationReceiptLedger.StoredReceipt;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class TargetE2eIntakeOuterFinalizerTest {

    @Test
    void firstCommitAndReplayReturnTheOriginalCommittedReceiptBytes() {
        var fixture = TargetE2eFinalizationFixture.valid();
        PlatformTransactionManager transactionManager = transactionManager();
        TransactionTemplate transactions = transactions(transactionManager);
        var source = TargetE2eFinalizationFixture.authorizedSource(fixture);
        var facts = new TargetE2eAgentRunV2FinalizationFactsProvider(source);
        var manifestFactory = new AgentRunV2ManifestFactory(
                JsonMapper.builder().findAndAddModules().build());
        AgentRunFormalResultCommitter committer = mock(AgentRunFormalResultCommitter.class);
        AtomicInteger calls = new AtomicInteger();
        when(committer.commit(any(FormalResultCommit.class))).thenAnswer(invocation -> {
            FormalResultCommit command = invocation.getArgument(0);
            CommitStatus status = calls.getAndIncrement() == 0
                    ? CommitStatus.COMMITTED
                    : CommitStatus.ALREADY_COMMITTED;
            return domainReceipt(fixture, command, status);
        });
        InMemoryLedger ledger = new InMemoryLedger();
        var outer = new TargetE2eIntakeOuterFinalizer(
                transactions, source, facts, manifestFactory, committer, ledger);

        StoredReceipt committed = outer.finalizeResult(fixture.request(), fixture.result());
        StoredReceipt replayed = outer.finalizeResult(fixture.request(), fixture.result());

        assertThat(committed.receipt().domainCommitStatus())
                .isEqualTo(TargetE2eFinalizationReceipt.DomainCommitStatus.COMMITTED);
        assertThat(replayed.receipt()).isEqualTo(committed.receipt());
        assertThat(replayed.canonicalBytes()).containsExactly(committed.canonicalBytes());
        assertThat(ledger.appendCount).isEqualTo(1);
        verify(transactionManager, times(2)).commit(any(TransactionStatus.class));
    }

    @Test
    void receiptFailureRollsBackTheCallerOwnedFormalTransaction() {
        var fixture = TargetE2eFinalizationFixture.valid();
        PlatformTransactionManager transactionManager = transactionManager();
        TransactionTemplate transactions = transactions(transactionManager);
        var source = TargetE2eFinalizationFixture.authorizedSource(fixture);
        var facts = new TargetE2eAgentRunV2FinalizationFactsProvider(source);
        var manifestFactory = new AgentRunV2ManifestFactory(
                JsonMapper.builder().findAndAddModules().build());
        AgentRunFormalResultCommitter committer = mock(AgentRunFormalResultCommitter.class);
        when(committer.commit(any(FormalResultCommit.class))).thenAnswer(invocation ->
                domainReceipt(fixture, invocation.getArgument(0), CommitStatus.COMMITTED));
        TargetE2eFinalizationReceiptLedger failingLedger = new TargetE2eFinalizationReceiptLedger() {
            @Override
            public Optional<StoredReceipt> find(String activationId, String logicalRunId) {
                return Optional.empty();
            }

            @Override
            public StoredReceipt append(AppendCommand command) {
                throw new IllegalStateException("injected receipt failure");
            }
        };
        var outer = new TargetE2eIntakeOuterFinalizer(
                transactions, source, facts, manifestFactory, committer, failingLedger);

        assertThatThrownBy(() -> outer.finalizeResult(fixture.request(), fixture.result()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected receipt failure");

        verify(committer).commit(any(FormalResultCommit.class));
        verify(transactionManager).rollback(any(TransactionStatus.class));
    }

    @Test
    void committedAgentRunWithoutOriginalTargetReceiptIsRejected() {
        var fixture = TargetE2eFinalizationFixture.valid();
        PlatformTransactionManager transactionManager = transactionManager();
        var source = TargetE2eFinalizationFixture.authorizedSource(fixture);
        var facts = new TargetE2eAgentRunV2FinalizationFactsProvider(source);
        var manifestFactory = new AgentRunV2ManifestFactory(
                JsonMapper.builder().findAndAddModules().build());
        AgentRunFormalResultCommitter committer = mock(AgentRunFormalResultCommitter.class);
        when(committer.commit(any(FormalResultCommit.class))).thenAnswer(invocation ->
                domainReceipt(fixture, invocation.getArgument(0), CommitStatus.ALREADY_COMMITTED));
        InMemoryLedger emptyLedger = new InMemoryLedger();
        var outer = new TargetE2eIntakeOuterFinalizer(
                transactions(transactionManager),
                source,
                facts,
                manifestFactory,
                committer,
                emptyLedger);

        assertThatThrownBy(() -> outer.finalizeResult(fixture.request(), fixture.result()))
                .isInstanceOf(TargetE2eFinalizationRejectedException.class)
                .hasMessageContaining("no atomically persisted target receipt");
        assertThat(emptyLedger.appendCount).isZero();
        verify(transactionManager).rollback(any(TransactionStatus.class));
    }

    private static AgentRunFinalizationReceipt domainReceipt(
            TargetE2eFinalizationFixture.Fixture fixture,
            FormalResultCommit command,
            CommitStatus status) {
        return new AgentRunFinalizationReceipt(
                AgentRunFinalizationReceipt.SCHEMA_VERSION,
                fixture.request().agentRunId(),
                fixture.request().logicalRunId(),
                fixture.request().attemptId(),
                fixture.request().attemptNo(),
                fixture.state().run().fencingToken(),
                fixture.result().resultHash(),
                command.manifestCommit().manifest().manifestId(),
                command.manifestCommit().manifestHash(),
                fixture.result().lastSequenceNo(),
                status,
                TargetE2eFinalizationFixture.NOW);
    }

    private static PlatformTransactionManager transactionManager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(ignored -> new SimpleTransactionStatus());
        return manager;
    }

    private static TransactionTemplate transactions(PlatformTransactionManager manager) {
        TransactionTemplate transactions = new TransactionTemplate(manager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transactions;
    }

    private static final class InMemoryLedger implements TargetE2eFinalizationReceiptLedger {
        private StoredReceipt stored;
        private int appendCount;

        @Override
        public Optional<StoredReceipt> find(String activationId, String logicalRunId) {
            return Optional.ofNullable(stored)
                    .filter(value -> value.receipt().activationId().equals(activationId))
                    .filter(value -> value.receipt().logicalRunId().equals(logicalRunId));
        }

        @Override
        public StoredReceipt append(AppendCommand command) {
            appendCount++;
            byte[] bytes = TargetE2eFinalizationReceiptCodec.canonicalBytes(command.receipt());
            if (stored == null) {
                stored = new StoredReceipt(
                        "p9fin.v1." + "3".repeat(32),
                        command.activationManifestHash(),
                        command.receipt(),
                        bytes);
            } else {
                TargetE2eFinalizationReceiptLedger.requireExact(stored, command);
            }
            return stored;
        }
    }
}
