package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import java.util.Objects;

/** Preserves the strict target Intake authorization/source/binding checks as one room strategy. */
public final class TargetE2eIntakeRoomFinalizationStrategy
        implements TargetE2eRoomFinalizationStrategy {

    private final TargetE2eAuthorizedIntakeFinalizationSource source;
    private final TargetE2eAgentRunV2FinalizationFactsProvider factsProvider;
    private final TargetE2eIntakeParallelAssemblyFinalizationPort parallelFinalization;

    public TargetE2eIntakeRoomFinalizationStrategy(
            TargetE2eAuthorizedIntakeFinalizationSource source,
            TargetE2eAgentRunV2FinalizationFactsProvider factsProvider,
            TargetE2eIntakeParallelAssemblyFinalizationPort parallelFinalization) {
        this.source = Objects.requireNonNull(source, "source");
        this.factsProvider = Objects.requireNonNull(factsProvider, "factsProvider");
        this.parallelFinalization = Objects.requireNonNull(
                parallelFinalization, "parallelFinalization");
    }

    @Override
    public RoomType roomType() {
        return RoomType.INTAKE;
    }

    @Override
    public boolean supports(ExecuteAgentRunRequest request) {
        return request != null
                && request.command().roomType() == RoomType.INTAKE
                && TargetE2eExecutionLaneVerifier.GRAPH_KEY.equals(request.command().graphKey())
                && TargetE2eExecutionLaneVerifier.GRAPH_VERSION.equals(request.command().graphVersion())
                && TargetE2eExecutionLaneVerifier.CHECKPOINT_SCHEMA_VERSION.equals(
                        request.command().checkpointSchemaVersion());
    }

    @Override
    public PreparedFinalization prepare(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
        if (!supports(request)) {
            throw new TargetE2eFinalizationRejectedException(
                    "TARGET_E2E_INTAKE_FINALIZER_UNSUPPORTED",
                    "target Intake finalizer requires the exact target Graph pins");
        }
        var authorized = source.resolve(request, result);
        var facts = factsProvider.create(authorized, request, result);
        var state = authorized.state();
        var evidence = authorized.evidence();
        var graph = result.graphResult();
        return new PreparedFinalization(
                evidence.activationManifestHash(),
                new ReceiptBindings(
                        authorized.activation().activationId(),
                        state.run().tenantSurrogate(),
                        state.run().caseId(),
                        RoomType.INTAKE,
                        state.run().roomEpoch(),
                        state.run().fencingToken(),
                        state.run().processRevision(),
                        state.projection().lastCommandSequence(),
                        evidence.commandHash(),
                        evidence.commandEnvelopeHash(),
                        graph.graphKey(),
                        graph.graphVersion(),
                        request.command().checkpointSchemaVersion(),
                        graph.checkpointId(),
                        evidence.proposalHash(),
                        evidence.resultEnvelopeHash(),
                        evidence.isolatedDomainDbBindingHash(),
                        state.attempt().completedAt()),
                facts);
    }

    @Override
    public TechnicalAuthority lockTechnicalAuthority(
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            PreparedFinalization prepared) {
        if (!ExecuteAgentRunRequest.isParallelIntakeCommand(request.command())) {
            return NoTechnicalAuthority.INSTANCE;
        }
        return parallelFinalization.lockAndRevalidate(
                request, result, prepared.receiptBindings());
    }

    @Override
    public void commitTechnicalAuthority(
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            PreparedFinalization prepared,
            TechnicalAuthority authority,
            TargetE2eFinalizationReceiptLedger.StoredReceipt storedReceipt) {
        if (!ExecuteAgentRunRequest.isParallelIntakeCommand(request.command())) {
            TargetE2eRoomFinalizationStrategy.super.commitTechnicalAuthority(
                    request, result, prepared, authority, storedReceipt);
            return;
        }
        if (!(authority
                instanceof TargetE2eIntakeParallelAssemblyFinalizationPort.LockedAssembly locked)) {
            throw new TargetE2eFinalizationRejectedException(
                    "INTAKE_PARALLEL_FORMAL_AUTHORITY_MISSING",
                    "parallel Intake finalization lost its locked assembly authority");
        }
        parallelFinalization.markCommitted(locked, storedReceipt);
    }
}
