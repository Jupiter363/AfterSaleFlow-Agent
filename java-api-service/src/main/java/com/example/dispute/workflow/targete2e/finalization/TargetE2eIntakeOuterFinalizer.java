package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.agentstream.application.AgentRunFormalResultCommitter;
import com.example.dispute.agentstream.application.AgentRunFormalResultCommitter.FormalResultCommit;
import com.example.dispute.agentstream.application.AgentRunV2ManifestFactory;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt.CommitStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationReceipt.CommitFacts;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationReceiptLedger.AppendCommand;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationReceiptLedger.StoredReceipt;
import java.util.Objects;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Caller-owned outer transaction for Java Domain/manifest commit plus immutable target receipt.
 *
 * <p>This class deliberately has no discovery annotation. A target-only source set may construct it
 * with one REQUIRED transaction template; normal production cannot discover the formal sink.
 */
public final class TargetE2eIntakeOuterFinalizer {

    private final TransactionTemplate transactions;
    private final TargetE2eAuthorizedIntakeFinalizationSource source;
    private final TargetE2eAgentRunV2FinalizationFactsProvider factsProvider;
    private final AgentRunV2ManifestFactory manifestFactory;
    private final AgentRunFormalResultCommitter formalCommitter;
    private final TargetE2eFinalizationReceiptLedger receiptLedger;

    public TargetE2eIntakeOuterFinalizer(
            TransactionTemplate transactions,
            TargetE2eAuthorizedIntakeFinalizationSource source,
            TargetE2eAgentRunV2FinalizationFactsProvider factsProvider,
            AgentRunV2ManifestFactory manifestFactory,
            AgentRunFormalResultCommitter formalCommitter,
            TargetE2eFinalizationReceiptLedger receiptLedger) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        if (transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || transactions.getIsolationLevel()
                        != TransactionDefinition.ISOLATION_REPEATABLE_READ
                || transactions.isReadOnly()) {
            throw new IllegalArgumentException(
                    "target finalization requires one writable repeatable-read REQUIRED transaction");
        }
        this.source = Objects.requireNonNull(source, "source");
        this.factsProvider = Objects.requireNonNull(factsProvider, "factsProvider");
        this.manifestFactory = Objects.requireNonNull(manifestFactory, "manifestFactory");
        this.formalCommitter = Objects.requireNonNull(formalCommitter, "formalCommitter");
        this.receiptLedger = Objects.requireNonNull(receiptLedger, "receiptLedger");
    }

    public StoredReceipt finalizeResult(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
        StoredReceipt stored = transactions.execute(ignored -> finalizeInTransaction(request, result));
        return Objects.requireNonNull(stored, "target finalization transaction returned null");
    }

    private StoredReceipt finalizeInTransaction(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
        if (request.command().roomType() != RoomType.INTAKE
                || !TargetE2eExecutionLaneVerifier.GRAPH_KEY.equals(
                        request.command().graphKey())
                || !TargetE2eExecutionLaneVerifier.GRAPH_VERSION.equals(
                        request.command().graphVersion())
                || !TargetE2eExecutionLaneVerifier.CHECKPOINT_SCHEMA_VERSION.equals(
                        request.command().checkpointSchemaVersion())) {
            throw rejected(
                    "TARGET_E2E_INTAKE_FINALIZER_UNSUPPORTED",
                    "target Intake outer finalizer requires the exact target Graph pins");
        }
        var authorized = source.resolve(request, result);
        var facts = factsProvider.create(authorized, request, result);
        var manifestCommit = manifestFactory.create(request, result, facts);
        TargetE2eFinalizationReceiptCodec.requireManifestHash(
                manifestCommit.manifest(), manifestCommit.manifestHash());

        AgentRunFinalizationReceipt domainReceipt = formalCommitter.commit(
                new FormalResultCommit(request, result, manifestCommit));
        TargetE2eFinalizationReceipt targetReceipt = receipt(
                authorized, request, result, manifestCommit.manifest().manifestId(),
                manifestCommit.manifestHash(), domainReceipt);
        AppendCommand append = new AppendCommand(
                authorized.evidence().activationManifestHash(), targetReceipt);
        if (domainReceipt.commitStatus() == CommitStatus.COMMITTED) {
            return receiptLedger.append(append);
        }
        if (domainReceipt.commitStatus() == CommitStatus.ALREADY_COMMITTED) {
            StoredReceipt original = receiptLedger
                    .find(targetReceipt.activationId(), targetReceipt.logicalRunId())
                    .orElseThrow(() -> rejected(
                            "TARGET_E2E_ORIGINAL_RECEIPT_MISSING",
                            "committed AgentRun has no atomically persisted target receipt"));
            TargetE2eFinalizationReceiptLedger.requireExact(original, append);
            return original;
        }
        throw rejected(
                "TARGET_E2E_DOMAIN_COMMIT_STATUS_INVALID",
                "Java formal commit did not return a terminal commit status");
    }

    private static TargetE2eFinalizationReceipt receipt(
            TargetE2eAuthorizedIntakeFinalizationSource.AuthorizedState authorized,
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            String manifestId,
            String manifestHash,
            AgentRunFinalizationReceipt domainReceipt) {
        var state = authorized.state();
        var evidence = authorized.evidence();
        var graph = result.graphResult();
        if (!manifestId.equals(domainReceipt.manifestId())
                || !manifestHash.equals(domainReceipt.manifestHash())
                || state.run().fencingToken() != domainReceipt.fencingToken()
                || !result.resultHash().equals(domainReceipt.finalResultHash())) {
            throw rejected(
                    "TARGET_E2E_DOMAIN_RECEIPT_MISMATCH",
                    "Java formal receipt conflicts with the target receipt source");
        }
        return TargetE2eFinalizationReceipt.committed(new CommitFacts(
                authorized.activation().activationId(),
                state.run().tenantSurrogate(),
                state.run().caseId(),
                RoomType.INTAKE,
                state.run().roomEpoch(),
                state.run().fencingToken(),
                state.run().processRevision(),
                state.projection().lastCommandSequence(),
                request.logicalRunId(),
                request.attemptId(),
                evidence.commandHash(),
                evidence.commandEnvelopeHash(),
                graph.graphKey(),
                graph.graphVersion(),
                request.command().checkpointSchemaVersion(),
                graph.checkpointId(),
                evidence.resultHash(),
                evidence.proposalHash(),
                evidence.resultEnvelopeHash(),
                manifestId,
                manifestHash,
                evidence.isolatedDomainDbBindingHash(),
                domainReceipt.committedAt()));
    }

    private static TargetE2eFinalizationRejectedException rejected(
            String code, String message) {
        return new TargetE2eFinalizationRejectedException(code, message);
    }
}
