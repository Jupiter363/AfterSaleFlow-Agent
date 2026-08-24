package com.example.dispute.workflow.targete2e.graph;

import com.example.dispute.workflow.activity.agent.AgentGraphReconciliationClient;
import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway;
import com.example.dispute.workflow.activity.agent.AgentRunProgress;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.AssemblyConflictException;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameExecutionClient;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameExecutionClient.FrameExecutionReceipt;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelRunTerminalStore;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelRunTerminalStore.TerminalCommand;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelRunTerminalStore.TerminalReceipt;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.GraphReconcileResponse;
import java.util.Objects;

/** Java-owned execution gateway for the exact Intake parallel V4 profile. */
public final class TargetE2EIntakeParallelExecutionGateway implements AgentRunExecutionGateway {

    private static final String READY_MISSING = "INTAKE_PARALLEL_READY_MISSING";

    private final IntakeParallelFrameExecutionClient frameExecutionClient;
    private final TargetE2EIntakeParallelAssemblyCoordinator assemblyCoordinator;
    private final AgentGraphReconciliationClient reconciliationClient;
    private final IntakeParallelRunTerminalStore terminalStore;

    public TargetE2EIntakeParallelExecutionGateway(
            IntakeParallelFrameExecutionClient frameExecutionClient,
            TargetE2EIntakeParallelAssemblyCoordinator assemblyCoordinator,
            AgentGraphReconciliationClient reconciliationClient,
            IntakeParallelRunTerminalStore terminalStore) {
        this.frameExecutionClient =
                IntakeParallelFrameExecutionClient.required(frameExecutionClient);
        this.assemblyCoordinator =
                Objects.requireNonNull(assemblyCoordinator, "assemblyCoordinator");
        this.reconciliationClient =
                Objects.requireNonNull(reconciliationClient, "reconciliationClient");
        this.terminalStore = Objects.requireNonNull(terminalStore, "terminalStore");
    }

    @Override
    public Completion execute(
            ExecuteAgentRunRequest request,
            ExecutionMode executionMode,
            ProgressListener progressListener,
            AgentRunCancellationToken cancellationToken) {
        requireParallel(request);
        Objects.requireNonNull(executionMode, "executionMode");
        Objects.requireNonNull(progressListener, "progressListener");
        Objects.requireNonNull(cancellationToken, "cancellationToken")
                .throwIfCancellationRequested();

        GraphReconcileResponse reconciliation;
        if (executionMode == ExecutionMode.RECONCILE_ONLY) {
            reconciliation = reconciliationClient.reconcile(request, cancellationToken);
        } else {
            reconciliation = readyOrExecute(
                    request, progressListener, cancellationToken);
        }
        cancellationToken.throwIfCancellationRequested();
        TerminalReceipt terminal = terminalStore.appendOrLoad(
                new TerminalCommand(request, reconciliation));
        progressListener.onProgress(new AgentRunProgress(
                terminal.result().lastSequenceNo(),
                terminal.result().publicOutputEmitted(),
                true));
        return new Completion(
                terminal.result().graphResult(),
                terminal.result().lastSequenceNo(),
                terminal.result().publicOutputEmitted(),
                terminal.result());
    }

    private GraphReconcileResponse readyOrExecute(
            ExecuteAgentRunRequest request,
            ProgressListener progressListener,
            AgentRunCancellationToken cancellationToken) {
        try {
            return reconciliationClient.reconcile(request, cancellationToken);
        } catch (AssemblyConflictException missing) {
            if (!READY_MISSING.equals(missing.code())) {
                throw missing;
            }
        }
        FrameExecutionReceipt frames = frameExecutionClient.executeOrResume(
                request, progressListener, cancellationToken);
        progressListener.onProgress(new AgentRunProgress(
                frames.lastSequenceNo(), frames.publicOutputEmitted(), false));
        assemblyCoordinator.assembleReady(request, frames.frameSetId(), cancellationToken);
        // Do not trust the transient assembler return value as terminal authority. Reload and
        // decode the immutable READY artifact before entering the atomic FINAL transaction.
        return reconciliationClient.reconcile(request, cancellationToken);
    }

    private static void requireParallel(ExecuteAgentRunRequest request) {
        if (request == null
                || !ExecuteAgentRunRequest.isParallelIntakeCommand(request.command())
                || !"agent-stream.v4".equals(request.streamProtocol())) {
            throw new IllegalArgumentException(
                    "parallel Intake gateway requires the explicit V4 profile");
        }
    }
}
