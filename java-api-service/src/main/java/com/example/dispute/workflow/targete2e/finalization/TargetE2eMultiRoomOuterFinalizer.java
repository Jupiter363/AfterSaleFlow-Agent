package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.agentstream.application.AgentRunFormalResultCommitter;
import com.example.dispute.agentstream.application.AgentRunFormalResultCommitter.FormalResultCommit;
import com.example.dispute.agentstream.application.AgentRunV2ManifestFactory;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt.CommitStatus;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationReceipt.CommitFacts;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationReceiptLedger.AppendCommand;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationReceiptLedger.StoredReceipt;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eRoomFinalizationStrategy.PreparedFinalization;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eRoomFinalizationStrategy.ReceiptBindings;
import java.util.Objects;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The single target-lane finalization owner for every room.
 *
 * <p>It opens one writable REQUIRED/REPEATABLE_READ transaction, materializes the durable FINAL
 * output, routes to exactly one room evidence strategy, calls the existing formal committer
 * (which selects the room domain writer), then atomically appends/replays the target receipt and
 * command completion. Room strategies may verify and prepare facts only.
 */
public final class TargetE2eMultiRoomOuterFinalizer {

    private final TransactionTemplate transactions;
    private final TargetE2eGraphOutputSnapshotMaterializer outputMaterializer;
    private final TargetE2eRoomFinalizationStrategyRegistry strategies;
    private final AgentRunV2ManifestFactory manifestFactory;
    private final AgentRunFormalResultCommitter formalCommitter;
    private final TargetE2eFinalizationReceiptLedger receiptLedger;
    private final TargetE2eCommandCompletionWriter completionWriter;

    public TargetE2eMultiRoomOuterFinalizer(
            TransactionTemplate transactions,
            TargetE2eGraphOutputSnapshotMaterializer outputMaterializer,
            TargetE2eRoomFinalizationStrategyRegistry strategies,
            AgentRunV2ManifestFactory manifestFactory,
            AgentRunFormalResultCommitter formalCommitter,
            TargetE2eFinalizationReceiptLedger receiptLedger,
            TargetE2eCommandCompletionWriter completionWriter) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        if (transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || transactions.getIsolationLevel()
                        != TransactionDefinition.ISOLATION_REPEATABLE_READ
                || transactions.isReadOnly()) {
            throw new IllegalArgumentException(
                    "target finalization requires one writable repeatable-read REQUIRED transaction");
        }
        this.outputMaterializer = Objects.requireNonNull(outputMaterializer, "outputMaterializer");
        this.strategies = Objects.requireNonNull(strategies, "strategies");
        this.manifestFactory = Objects.requireNonNull(manifestFactory, "manifestFactory");
        this.formalCommitter = Objects.requireNonNull(formalCommitter, "formalCommitter");
        this.receiptLedger = Objects.requireNonNull(receiptLedger, "receiptLedger");
        this.completionWriter = Objects.requireNonNull(completionWriter, "completionWriter");
    }

    public FinalizationOutcome finalizeAgentRunResult(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
        FinalizationOutcome outcome = transactions.execute(ignored -> {
            outputMaterializer.materializeInActiveTransaction(request, result);
            return finalizeInTransaction(request, result);
        });
        return Objects.requireNonNull(outcome, "target finalization transaction returned null");
    }

    private FinalizationOutcome finalizeInTransaction(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
        TargetE2eRoomFinalizationStrategy strategy = strategies.require(request);
        PreparedFinalization prepared = strategy.prepare(request, result);
        validatePrepared(request, result, strategy, prepared);

        var manifestCommit = manifestFactory.create(request, result, prepared.manifestFacts());
        TargetE2eFinalizationReceiptCodec.requireManifestHash(
                manifestCommit.manifest(), manifestCommit.manifestHash());
        AgentRunFinalizationReceipt domainReceipt = formalCommitter.commit(
                new FormalResultCommit(request, result, manifestCommit));
        var technicalAuthority =
                strategy.lockTechnicalAuthority(request, result, prepared);
        TargetE2eFinalizationReceipt targetReceipt = receipt(
                prepared.receiptBindings(), request, result,
                manifestCommit.manifest().manifestId(), manifestCommit.manifestHash(), domainReceipt);
        AppendCommand append = new AppendCommand(prepared.activationManifestHash(), targetReceipt);
        StoredReceipt stored;
        if (domainReceipt.commitStatus() == CommitStatus.COMMITTED) {
            stored = receiptLedger.append(append);
        } else if (domainReceipt.commitStatus() == CommitStatus.ALREADY_COMMITTED) {
            stored = receiptLedger
                    .find(targetReceipt.activationId(), targetReceipt.logicalRunId())
                    .orElseThrow(() -> rejected(
                            "TARGET_E2E_ORIGINAL_RECEIPT_MISSING",
                            "committed AgentRun has no atomically persisted target receipt"));
            TargetE2eFinalizationReceiptLedger.requireExact(stored, append);
        } else {
            throw rejected(
                    "TARGET_E2E_DOMAIN_COMMIT_STATUS_INVALID",
                    "Java formal commit did not return a terminal commit status");
        }
        strategy.commitTechnicalAuthority(
                request, result, prepared, technicalAuthority, stored);
        completionWriter.complete(request, stored.receipt());
        return new FinalizationOutcome(stored, domainReceipt);
    }

    private static void validatePrepared(
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            TargetE2eRoomFinalizationStrategy strategy,
            PreparedFinalization prepared) {
        Objects.requireNonNull(prepared, "strategy prepared finalization");
        ReceiptBindings bindings = prepared.receiptBindings();
        if (result.outcome() != ExecuteAgentRunResult.Outcome.COMPLETED
                || result.graphResult() == null
                || strategy.roomType() != request.command().roomType()
                || bindings.roomType() != request.command().roomType()
                || !bindings.tenantSurrogate().equals(request.command().tenantSurrogate())
                || !bindings.caseId().equals(request.command().caseId())
                || bindings.roomEpoch() != request.command().roomEpoch()
                || bindings.processRevision() != request.command().processRevision()
                || !bindings.graphKey().equals(request.command().graphKey())
                || !bindings.graphVersion().equals(request.command().graphVersion())
                || !bindings.checkpointSchemaVersion().equals(request.command().checkpointSchemaVersion())
                || !bindings.checkpointId().equals(result.graphResult().checkpointId())) {
            throw rejected(
                    "TARGET_E2E_ROOM_FINALIZATION_BINDINGS_INVALID",
                    "room strategy returned facts outside the execution request fence");
        }
    }

    private static TargetE2eFinalizationReceipt receipt(
            ReceiptBindings bindings,
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            String manifestId,
            String manifestHash,
            AgentRunFinalizationReceipt domainReceipt) {
        if (!manifestId.equals(domainReceipt.manifestId())
                || !manifestHash.equals(domainReceipt.manifestHash())
                || bindings.roomFencingToken() != domainReceipt.fencingToken()
                || !result.resultHash().equals(domainReceipt.finalResultHash())) {
            throw rejected(
                    "TARGET_E2E_DOMAIN_RECEIPT_MISMATCH",
                    "Java formal receipt conflicts with the target receipt source");
        }
        return TargetE2eFinalizationReceipt.committed(new CommitFacts(
                bindings.activationId(),
                bindings.tenantSurrogate(),
                bindings.caseId(),
                bindings.roomType(),
                bindings.roomEpoch(),
                bindings.roomFencingToken(),
                bindings.processRevision(),
                bindings.stageSequence(),
                request.logicalRunId(),
                request.attemptId(),
                bindings.commandHash(),
                bindings.commandEnvelopeHash(),
                bindings.graphKey(),
                bindings.graphVersion(),
                bindings.checkpointSchemaVersion(),
                bindings.checkpointId(),
                result.resultHash(),
                bindings.proposalHash(),
                bindings.resultEnvelopeHash(),
                manifestId,
                manifestHash,
                bindings.isolatedDomainDbBindingHash(),
                domainReceipt.committedAt()));
    }

    private static TargetE2eFinalizationRejectedException rejected(
            String code, String message) {
        return new TargetE2eFinalizationRejectedException(code, message);
    }

    public record FinalizationOutcome(
            StoredReceipt targetReceipt, AgentRunFinalizationReceipt agentRunReceipt) {
        public FinalizationOutcome {
            targetReceipt = Objects.requireNonNull(targetReceipt, "targetReceipt");
            agentRunReceipt = Objects.requireNonNull(agentRunReceipt, "agentRunReceipt");
        }
    }
}
