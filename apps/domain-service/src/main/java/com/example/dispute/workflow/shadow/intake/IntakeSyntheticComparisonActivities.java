package com.example.dispute.workflow.shadow.intake;

import com.example.dispute.workflow.activity.intake.IntakeSnapshotPublicationPort;
import com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticAdmissionPort.ActivityAuthorization;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticComparisonLedger.CommitRequest;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticParityObservationPort.Observation;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityInvocationMode;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.BranchCommitRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.GraphExecutionRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.SnapshotPublicationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationReceipt;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.TurnFinalizationRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomActivities;
import io.temporal.failure.ApplicationFailure;
import java.util.Objects;

/**
 * Non-discoverable Activity implementation for signed synthetic message comparisons. It has no
 * formal port dependency and every branch method is permanently closed.
 */
public final class IntakeSyntheticComparisonActivities implements IntakeRoomActivities {

    public static final String AUTHORIZATION_FAILURE = "INTAKE_SYNTHETIC_AUTHORIZATION";
    public static final String BRANCH_FORBIDDEN = "INTAKE_SYNTHETIC_BRANCH_FORBIDDEN";

    private final IntakeSignedSyntheticAdmissionPort admission;
    private final IntakeSnapshotPublicationPort snapshots;
    private final IntakeSignedSyntheticGraphExecutionPort signedGraph;
    private final IntakeSyntheticParityObservationPort observations;
    private final IntakeShadowParityService parity;
    private final IntakeSyntheticComparisonLedger ledger;

    public IntakeSyntheticComparisonActivities(
            IntakeSignedSyntheticAdmissionPort admission,
            IntakeSnapshotPublicationPort snapshots,
            IntakeSignedSyntheticGraphExecutionPort signedGraph,
            IntakeSyntheticParityObservationPort observations,
            IntakeSyntheticComparisonLedger ledger) {
        this.admission = Objects.requireNonNull(admission, "admission must not be null");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots must not be null");
        this.signedGraph = Objects.requireNonNull(signedGraph, "signedGraph must not be null");
        this.observations = Objects.requireNonNull(observations, "observations must not be null");
        this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
        this.parity = new IntakeShadowParityService(ignored -> {});
    }

    @Override
    public SnapshotPublicationReceipt publishSnapshot(SnapshotPublicationRequest request) {
        requireAuthorized(
                request.envelope(),
                request.requestHash(),
                request.threadId(),
                request.agentSessionId());
        return Objects.requireNonNull(snapshots.publish(request), "snapshot receipt must not be null");
    }

    @Override
    public GraphExecutionReceipt executeGraph(GraphExecutionRequest request) {
        requireAuthorized(
                request.envelope(),
                request.requestHash(),
                request.threadId(),
                request.agentSessionId());
        return Objects.requireNonNull(signedGraph.execute(request), "Graph receipt must not be null");
    }

    @Override
    public TurnFinalizationReceipt finalizeTurn(TurnFinalizationRequest request) {
        requireAuthorized(
                request.envelope(),
                request.requestHash(),
                request.threadId(),
                request.agentSessionId());
        if (request.envelope().invocation().mode() == ActivityInvocationMode.RECONCILE_ONLY) {
            return ledger.find(request).map(result -> result.receipt()).orElse(null);
        }
        Observation observed = Objects.requireNonNull(
                observations.observe(request), "parity observation must not be null");
        String comparisonKey = IntakeSyntheticComparisonReceiptFactory.comparisonKey(request);
        IntakeShadowComparison comparison =
                parity.evaluate(comparisonKey, observed.legacy(), observed.shadow());
        TurnFinalizationReceipt receipt =
                ledger.commit(new CommitRequest(request, comparison, observed.projectedEventType()))
                        .receipt();
        receipt.requireMatches(request);
        return receipt;
    }

    @Override
    public BranchCommitReceipt acceptInitiator(BranchCommitRequest request) {
        throw branchForbidden();
    }

    @Override
    public BranchCommitReceipt rejectInitiator(BranchCommitRequest request) {
        throw branchForbidden();
    }

    @Override
    public BranchCommitReceipt cancelIntake(BranchCommitRequest request) {
        throw branchForbidden();
    }

    @Override
    public BranchCommitReceipt confirmRespondent(BranchCommitRequest request) {
        throw branchForbidden();
    }

    private void requireAuthorized(
            com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.ActivityEnvelope envelope,
            String requestHash,
            String threadId,
            String agentSessionId) {
        if (envelope.commandType() != IntakeCommandType.INTAKE_MESSAGE
                || !admission.isActivityAuthorized(
                        ActivityAuthorization.from(
                                envelope, requestHash, threadId, agentSessionId))) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "signed synthetic Activity admission is absent or stale",
                    AUTHORIZATION_FAILURE);
        }
    }

    private static ApplicationFailure branchForbidden() {
        return ApplicationFailure.newNonRetryableFailure(
                "comparison-only synthetic Intake worker forbids branch Activities",
                BRANCH_FORBIDDEN);
    }
}
