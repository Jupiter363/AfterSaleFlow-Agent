package com.example.dispute.workflow.runtime.finalization;

import com.example.dispute.agentstream.application.AgentRunFormalResultCommitter;
import com.example.dispute.agentstream.application.AgentRunFormalResultCommitter.FormalResultCommit;
import com.example.dispute.agentstream.application.AgentRunV2ManifestFactory;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt.CommitStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationReceipt.CommitFacts;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationReceiptLedger.AppendCommand;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationReceiptLedger.StoredReceipt;
import java.util.Objects;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Caller-owned outer transaction for Java Domain/manifest commit plus immutable target receipt.
 *
 * <p>This class deliberately has no discovery annotation. A production-only source set may construct it
 * with one REQUIRED transaction template; normal production cannot discover the formal sink.
 */
public final class ProductionIntakeOuterFinalizer {

    private final TransactionTemplate transactions;
    private final ProductionAuthorizedIntakeFinalizationSource source;
    private final ProductionAgentRunV2FinalizationFactsProvider factsProvider;
    private final AgentRunV2ManifestFactory manifestFactory;
    private final AgentRunFormalResultCommitter formalCommitter;
    private final ProductionFinalizationReceiptLedger receiptLedger;
    private final CommandCompletionWriter completionWriter;

    public ProductionIntakeOuterFinalizer(
            TransactionTemplate transactions,
            ProductionAuthorizedIntakeFinalizationSource source,
            ProductionAgentRunV2FinalizationFactsProvider factsProvider,
            AgentRunV2ManifestFactory manifestFactory,
            AgentRunFormalResultCommitter formalCommitter,
            ProductionFinalizationReceiptLedger receiptLedger,
            CommandCompletionWriter completionWriter) {
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
        this.completionWriter = Objects.requireNonNull(completionWriter, "completionWriter");
    }

    public StoredReceipt finalizeResult(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
        return finalizeAgentRunResult(request, result).targetReceipt();
    }

    /**
     * Finalizes one target Intake result and returns the ordinary receipt consumed by Temporal.
     *
     * <p>The target receipt append and admitted-command completion are deliberately inside the
     * same caller-owned transaction as the Java formal commit.
     */
    public FinalizationOutcome finalizeAgentRunResult(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
        FinalizationOutcome finalized =
                transactions.execute(ignored -> finalizeInTransaction(request, result));
        return Objects.requireNonNull(finalized, "target finalization transaction returned null");
    }

    private FinalizationOutcome finalizeInTransaction(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
        if (request.command().roomType() != RoomType.INTAKE
                || !ProductionExecutionLaneVerifier.GRAPH_KEY.equals(
                        request.command().graphKey())
                || !ProductionExecutionLaneVerifier.GRAPH_VERSION.equals(
                        request.command().graphVersion())
                || !ProductionExecutionLaneVerifier.CHECKPOINT_SCHEMA_VERSION.equals(
                        request.command().checkpointSchemaVersion())) {
            throw rejected(
                    "PRODUCTION_RUNTIME_INTAKE_FINALIZER_UNSUPPORTED",
                    "target Intake outer finalizer requires the exact target Graph pins");
        }
        var authorized = source.resolve(request, result);
        var facts = factsProvider.create(authorized, request, result);
        var manifestCommit = manifestFactory.create(request, result, facts);
        ProductionFinalizationReceiptCodec.requireManifestHash(
                manifestCommit.manifest(), manifestCommit.manifestHash());

        AgentRunFinalizationReceipt domainReceipt = formalCommitter.commit(
                new FormalResultCommit(request, result, manifestCommit));
        ProductionFinalizationReceipt targetReceipt = receipt(
                authorized, request, result, manifestCommit.manifest().manifestId(),
                manifestCommit.manifestHash(), domainReceipt);
        AppendCommand append = new AppendCommand(
                authorized.evidence().activationManifestHash(), targetReceipt);
        if (domainReceipt.commitStatus() == CommitStatus.COMMITTED) {
            StoredReceipt stored = receiptLedger.append(append);
            completionWriter.complete(request, stored.receipt());
            return new FinalizationOutcome(stored, domainReceipt);
        }
        if (domainReceipt.commitStatus() == CommitStatus.ALREADY_COMMITTED) {
            StoredReceipt original = receiptLedger
                    .find(targetReceipt.activationId(), targetReceipt.logicalRunId())
                    .orElseThrow(() -> rejected(
                            "PRODUCTION_RUNTIME_ORIGINAL_RECEIPT_MISSING",
                            "committed AgentRun has no atomically persisted target receipt"));
            ProductionFinalizationReceiptLedger.requireExact(original, append);
            completionWriter.complete(request, original.receipt());
            return new FinalizationOutcome(original, domainReceipt);
        }
        throw rejected(
                "PRODUCTION_RUNTIME_DOMAIN_COMMIT_STATUS_INVALID",
                "Java formal commit did not return a terminal commit status");
    }

    private static ProductionFinalizationReceipt receipt(
            ProductionAuthorizedIntakeFinalizationSource.AuthorizedState authorized,
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
                    "PRODUCTION_RUNTIME_DOMAIN_RECEIPT_MISMATCH",
                    "Java formal receipt conflicts with the target receipt source");
        }
        return ProductionFinalizationReceipt.committed(new CommitFacts(
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

    private static ProductionFinalizationRejectedException rejected(
            String code, String message) {
        return new ProductionFinalizationRejectedException(code, message);
    }

    /** Writes the immutable completion row using the active outer transaction only. */
    @FunctionalInterface
    public interface CommandCompletionWriter {

        void complete(ExecuteAgentRunRequest request, ProductionFinalizationReceipt receipt);
    }

    public record FinalizationOutcome(
            StoredReceipt targetReceipt, AgentRunFinalizationReceipt agentRunReceipt) {
        public FinalizationOutcome {
            targetReceipt = Objects.requireNonNull(targetReceipt, "targetReceipt");
            agentRunReceipt = Objects.requireNonNull(agentRunReceipt, "agentRunReceipt");
        }
    }
}
