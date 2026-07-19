package com.example.dispute.agentstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentExecutionManifestStore.ManifestCommit;
import com.example.dispute.agentstream.application.AgentRunFormalResultCommitter;
import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.agentstream.application.AgentRunLedger.LogicalRun;
import com.example.dispute.agentstream.application.AgentRunV2FinalizationFactsProvider;
import com.example.dispute.agentstream.application.AgentRunV2FinalizationGateway;
import com.example.dispute.agentstream.application.AgentRunV2ManifestFactory;
import com.example.dispute.agentstream.application.AgentRunV2ManifestFactory.FinalizationFacts;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt.CommitStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunExecutorKind;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class AgentRunV2FinalizationGatewayTest {

    @Mock private AgentRunLedger ledger;
    @Mock private AgentRunV2FinalizationFactsProvider factsProvider;
    @Mock private AgentRunV2ManifestFactory manifestFactory;
    @Mock private AgentRunFormalResultCommitter committer;
    @Mock private ManifestCommit manifestCommit;

    private ExecuteAgentRunRequest request;
    private ExecuteAgentRunResult result;
    private FinalizationFacts facts;
    private AgentRunV2FinalizationGateway gateway;

    @BeforeEach
    void setUp() throws Exception {
        AgentRunV2ManifestFactoryTest fixtures = new AgentRunV2ManifestFactoryTest();
        fixtures.setUp();
        request = fixtures.request();
        result = fixtures.result();
        facts = fixtures.facts(7, result.resultHash());
        gateway =
                new AgentRunV2FinalizationGateway(
                        ledger, factsProvider, manifestFactory, committer);
    }

    @Test
    void returnsTheCommitterReplayReceiptWithoutCreatingAnotherResultPath() {
        when(factsProvider.resolve(request, result)).thenReturn(facts);
        when(ledger.findByLogicalKey(request.command().caseId(), facts.logicalIdempotencyKey()))
                .thenReturn(Optional.of(logicalRun(7)));
        when(manifestFactory.create(request, result, facts)).thenReturn(manifestCommit);
        AgentRunFinalizationReceipt replay = receipt(CommitStatus.ALREADY_COMMITTED);
        when(committer.commit(any())).thenReturn(replay);

        assertThat(gateway.finalizeResult(request, result)).isEqualTo(replay);
        verify(committer).commit(any());
    }

    @Test
    void rejectsAStaleFenceBeforeManifestConstructionOrFormalCommit() {
        when(factsProvider.resolve(request, result)).thenReturn(facts);
        when(ledger.findByLogicalKey(request.command().caseId(), facts.logicalIdempotencyKey()))
                .thenReturn(Optional.of(logicalRun(8)));

        assertThatThrownBy(() -> gateway.finalizeResult(request, result))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fence");
        verify(manifestFactory, never()).create(any(), any(), any());
        verify(committer, never()).commit(any());
    }

    private LogicalRun logicalRun(long fencingToken) {
        return new LogicalRun(
                request.agentRunId(),
                request.command().caseId(),
                facts.logicalIdempotencyKey(),
                AgentRunProtocol.V2,
                AgentRunExecutorKind.TEMPORAL_ACTIVITY,
                "EPOCH_EVIDENCE_001",
                request.command().roomEpoch(),
                request.command().processRevision(),
                fencingToken,
                "RESULT_READY",
                null,
                result.resultHash(),
                "agent-run-lineage.v1",
                request.logicalInputHash(),
                3,
                request.command().deadlineAt(),
                2);
    }

    private AgentRunFinalizationReceipt receipt(CommitStatus status) {
        return new AgentRunFinalizationReceipt(
                AgentRunFinalizationReceipt.SCHEMA_VERSION,
                request.agentRunId(),
                request.logicalRunId(),
                request.attemptId(),
                request.attemptNo(),
                facts.fencingToken(),
                result.resultHash(),
                "agent-manifest-v2-test",
                "a".repeat(64),
                result.lastSequenceNo(),
                status,
                result.completedAt());
    }
}
