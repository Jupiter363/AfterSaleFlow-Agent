package com.example.dispute.agentstream.application;

import com.example.dispute.agentstream.application.AgentRunFormalResultCommitter.FormalResultCommit;
import com.example.dispute.agentstream.application.AgentRunLedger.LogicalRun;
import com.example.dispute.workflow.activity.agent.AgentRunFinalizationGateway;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunExecutorKind;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Real Finalizer gateway that validates the persisted fence before the atomic formal commit. */
@Component
@ConditionalOnBean(AgentRunV2FinalizationFactsProvider.class)
@ConditionalOnProperty(name = "app.agent-run-v2.enabled", havingValue = "true")
@ConditionalOnProperty(
        name = "app.target-e2e.enabled",
        havingValue = "false",
        matchIfMissing = true)
public final class AgentRunV2FinalizationGateway implements AgentRunFinalizationGateway {

    private final AgentRunLedger ledger;
    private final AgentRunV2FinalizationFactsProvider factsProvider;
    private final AgentRunV2ManifestFactory manifestFactory;
    private final AgentRunFormalResultCommitter committer;

    public AgentRunV2FinalizationGateway(
            AgentRunLedger ledger,
            AgentRunV2FinalizationFactsProvider factsProvider,
            AgentRunV2ManifestFactory manifestFactory,
            AgentRunFormalResultCommitter committer) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.factsProvider = Objects.requireNonNull(factsProvider, "factsProvider");
        this.manifestFactory = Objects.requireNonNull(manifestFactory, "manifestFactory");
        this.committer = Objects.requireNonNull(committer, "committer");
    }

    @Override
    public AgentRunFinalizationReceipt finalizeResult(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
        AgentRunV2ManifestFactory.FinalizationFacts facts =
                Objects.requireNonNull(
                        factsProvider.resolve(request, result),
                        "finalization facts provider returned null");
        LogicalRun logicalRun =
                ledger.findByLogicalKey(request.command().caseId(), facts.logicalIdempotencyKey())
                        .orElseThrow(
                                () -> new IllegalStateException("logical AgentRun was not found"));
        requireFence(logicalRun, request, facts);
        return committer.commit(
                new FormalResultCommit(
                        request, result, manifestFactory.create(request, result, facts)));
    }

    private static void requireFence(
            LogicalRun logicalRun,
            ExecuteAgentRunRequest request,
            AgentRunV2ManifestFactory.FinalizationFacts facts) {
        if (!request.agentRunId().equals(logicalRun.agentRunId())
                || !request.command().caseId().equals(logicalRun.caseId())
                || !facts.logicalIdempotencyKey().equals(logicalRun.logicalIdempotencyKey())
                || logicalRun.protocol() != AgentRunProtocol.V2
                || logicalRun.executorKind() != AgentRunExecutorKind.TEMPORAL_ACTIVITY
                || request.command().roomEpoch() != logicalRun.roomEpoch()
                || request.command().processRevision() != logicalRun.processRevision()
                || facts.fencingToken() != logicalRun.fencingToken()) {
            throw new IllegalStateException(
                    "finalization facts conflict with the persisted AgentRun fence");
        }
    }
}
