package com.example.dispute.workflow.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.Incomplete;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.ReconciliationTarget;
import com.example.dispute.workflow.application.projection.AuthoritativeProcessStateReader.Unavailable;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.infrastructure.projection.SdkTemporalAuthoritativeProcessStateReader;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessSnapshot;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflow;
import io.temporal.client.WorkflowClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SdkTemporalAuthoritativeProcessStateReaderTest {

    private static final ReconciliationTarget TARGET =
            new ReconciliationTarget("tenant-reader", "CASE_Reader", "case-process:reader");

    @Mock private WorkflowClient workflowClient;
    @Mock private CaseProcessWorkflow workflow;

    private SdkTemporalAuthoritativeProcessStateReader reader;

    @BeforeEach
    void setUp() {
        reader = new SdkTemporalAuthoritativeProcessStateReader(workflowClient);
        when(workflowClient.newWorkflowStub(
                        CaseProcessWorkflow.class, TARGET.temporalWorkflowId()))
                .thenReturn(workflow);
    }

    @Test
    void mapsTheControlPlaneQueryToAnExplicitlyIncompleteObservation() {
        when(workflow.state()).thenReturn(snapshot(TARGET.caseId()));

        var result = reader.read(TARGET);

        assertThat(result).isInstanceOf(Incomplete.class);
        Incomplete incomplete = (Incomplete) result;
        assertThat(incomplete.reasonCode())
                .isEqualTo("CASE_PROCESS_SNAPSHOT_V1_INCOMPLETE_FOR_REPAIR");
        assertThat(incomplete.observation().processRevision()).isEqualTo(7);
        assertThat(incomplete.observation().lastCommandSequence()).isEqualTo(11);
        assertThat(incomplete.observation().lastCaseEventSequence()).isEqualTo(20);
    }

    @Test
    void rejectsAQueryResponseFromAnotherCase() {
        when(workflow.state()).thenReturn(snapshot("CASE_Other"));

        var result = reader.read(TARGET);

        assertThat(result).isEqualTo(new Unavailable("TEMPORAL_QUERY_SCOPE_MISMATCH"));
    }

    private static CaseProcessSnapshot snapshot(String caseId) {
        return new CaseProcessSnapshot(
                "case-process-snapshot.v1",
                TARGET.temporalWorkflowId(),
                "run-reader-1",
                TARGET.tenantSurrogate(),
                caseId,
                "CONTROL_PLANE_SHADOW",
                RoomType.EVIDENCE,
                2,
                "room-workflow:reader",
                7,
                12,
                21,
                11,
                20,
                0,
                0,
                8,
                11,
                20,
                1,
                null,
                null,
                List.of("command-reader"));
    }
}
