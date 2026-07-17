package com.example.dispute.workflow.infrastructure.projection;

import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.AuthoritativeProcessObservation;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.Incomplete;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.ReadResult;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.ReconciliationTarget;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.Unavailable;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessSnapshot;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import org.springframework.stereotype.Component;

@Component
public final class SdkTemporalAuthoritativeProcessStateReader
        implements AuthoritativeProcessStateReader {

    private static final String INCOMPLETE_REASON =
            "CASE_PROCESS_SNAPSHOT_V1_INCOMPLETE_FOR_REPAIR";

    private final WorkflowClient workflowClient;

    public SdkTemporalAuthoritativeProcessStateReader(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    @Override
    public ReadResult read(ReconciliationTarget target) {
        try {
            CaseProcessWorkflow workflow =
                    workflowClient.newWorkflowStub(
                            CaseProcessWorkflow.class, target.temporalWorkflowId());
            CaseProcessSnapshot snapshot = workflow.state();
            if (snapshot == null
                    || !target.temporalWorkflowId().equals(snapshot.workflowId())
                    || !target.tenantSurrogate().equals(snapshot.tenantSurrogate())
                    || !target.caseId().equals(snapshot.caseId())) {
                return new Unavailable("TEMPORAL_QUERY_SCOPE_MISMATCH");
            }
            return incomplete(snapshot);
        } catch (WorkflowException exception) {
            return new Unavailable("TEMPORAL_QUERY_UNAVAILABLE");
        } catch (IllegalArgumentException exception) {
            return new Unavailable("TEMPORAL_SNAPSHOT_INVALID");
        }
    }

    private static Incomplete incomplete(CaseProcessSnapshot snapshot) {
        return new Incomplete(
                new AuthoritativeProcessObservation(
                        snapshot.tenantSurrogate(),
                        snapshot.caseId(),
                        snapshot.workflowId(),
                        snapshot.workflowRunId(),
                        snapshot.macroPhase(),
                        snapshot.activeRoomType(),
                        snapshot.activeRoomEpoch(),
                        snapshot.observedProcessRevision(),
                        previousSequence(snapshot.nextCommandSequence()),
                        previousSequence(snapshot.nextCaseEventSequence())),
                INCOMPLETE_REASON);
    }

    private static long previousSequence(long nextSequence) {
        return Math.max(0, nextSequence - 1);
    }
}
