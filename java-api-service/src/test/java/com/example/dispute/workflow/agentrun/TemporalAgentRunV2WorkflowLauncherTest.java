package com.example.dispute.workflow.agentrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.application.AgentRunV2WorkflowLaunchException;
import com.example.dispute.workflow.application.AgentRunV2WorkflowLauncher.StartDisposition;
import com.example.dispute.workflow.application.TemporalAgentRunV2WorkflowLauncher;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.temporal.agentrun.AgentRunWorkflow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.client.WorkflowUpdateHandle;
import io.temporal.client.WorkflowUpdateStage;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;

class TemporalAgentRunV2WorkflowLauncherTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Path COMMAND_FIXTURE =
            Path.of(
                    "..",
                    "contracts",
                    "agent-platform",
                    "v1",
                    "fixtures",
                    "valid",
                    "room-graph-command-valid.json");

    @Test
    void derivesOneStableWorkflowIdFromTheLogicalRun() {
        assertThat(TemporalAgentRunV2WorkflowLauncher.workflowId("logical-run-001"))
                .isEqualTo("agent-run-v2:logical-run-001");
        assertThat(TemporalAgentRunV2WorkflowLauncher.workflowId("logical-run-001"))
                .isEqualTo("agent-run-v2:logical-run-001");
    }

    @Test
    void hashesUnsafeOrOversizedLogicalIdentitiesIntoManifestSafeIds() {
        String unsafe = TemporalAgentRunV2WorkflowLauncher.workflowId("logical/run/unsafe");
        String oversized =
                TemporalAgentRunV2WorkflowLauncher.workflowId("logical-run-" + "x".repeat(128));

        assertThat(unsafe)
                .isEqualTo(TemporalAgentRunV2WorkflowLauncher.workflowId("logical/run/unsafe"))
                .matches("agent-run-v2:[0-9a-f]{64}")
                .hasSizeLessThanOrEqualTo(128);
        assertThat(oversized)
                .matches("agent-run-v2:[0-9a-f]{64}")
                .hasSizeLessThanOrEqualTo(128)
                .isNotEqualTo(unsafe);
    }

    @Test
    void rejectsMissingLogicalIdentity() {
        assertThatThrownBy(() -> TemporalAgentRunV2WorkflowLauncher.workflowId(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("logicalRunId");
    }

    @Test
    void sendsLaterAttemptsDirectlyAsDeterministicUpdates() throws Exception {
        ExecuteAgentRunRequest request = request(2);
        String workflowId = TemporalAgentRunV2WorkflowLauncher.workflowId(request.logicalRunId());
        WorkflowClient client = mock(WorkflowClient.class);
        WorkflowStub workflow = mock(WorkflowStub.class);
        @SuppressWarnings("unchecked")
        WorkflowUpdateHandle<ExecuteAgentRunResult> handle = mock(WorkflowUpdateHandle.class);
        WorkflowExecution execution =
                WorkflowExecution.newBuilder()
                        .setWorkflowId(workflowId)
                        .setRunId("temporal-run-002")
                        .build();
        when(client.newUntypedWorkflowStub(workflowId)).thenReturn(workflow);
        when(workflow.startUpdate(any(UpdateOptions.class), eq(request))).thenReturn(handle);
        when(handle.getExecution()).thenReturn(execution);

        var receipt = new TemporalAgentRunV2WorkflowLauncher(client).start(request);

        assertThat(receipt.workflowId()).isEqualTo(workflowId);
        assertThat(receipt.runId()).isEqualTo("temporal-run-002");
        assertThat(receipt.disposition()).isEqualTo(StartDisposition.ATTEMPT_ACCEPTED);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<UpdateOptions> options = ArgumentCaptor.forClass(UpdateOptions.class);
        verify(workflow).startUpdate(options.capture(), eq(request));
        assertThat(options.getValue().getUpdateName()).isEqualTo(AgentRunWorkflow.ATTEMPT_UPDATE);
        assertThat(options.getValue().getUpdateId()).isEqualTo(request.attemptId());
        assertThat(options.getValue().getWaitForStage()).isEqualTo(WorkflowUpdateStage.ACCEPTED);
        verify(client, never())
                .newWorkflowStub(eq(AgentRunWorkflow.class), any(WorkflowOptions.class));
    }

    @Test
    void classifiesTemporalValidatorRejectionAsPermanent() throws Exception {
        ExecuteAgentRunRequest request = request(2);
        String workflowId = TemporalAgentRunV2WorkflowLauncher.workflowId(request.logicalRunId());
        WorkflowClient client = mock(WorkflowClient.class);
        WorkflowStub workflow = mock(WorkflowStub.class);
        WorkflowExecution execution =
                WorkflowExecution.newBuilder()
                        .setWorkflowId(workflowId)
                        .setRunId("temporal-run-002")
                        .build();
        WorkflowUpdateException rejection =
                new WorkflowUpdateException(
                        execution,
                        request.attemptId(),
                        AgentRunWorkflow.ATTEMPT_UPDATE,
                        new IllegalArgumentException("attempt command conflicts"));
        when(client.newUntypedWorkflowStub(workflowId)).thenReturn(workflow);
        when(workflow.startUpdate(any(UpdateOptions.class), eq(request))).thenThrow(rejection);

        assertThatThrownBy(() -> new TemporalAgentRunV2WorkflowLauncher(client).start(request))
                .isInstanceOfSatisfying(
                        AgentRunV2WorkflowLaunchException.class,
                        failure -> {
                            assertThat(failure.retryable()).isFalse();
                            assertThat(failure.code()).isEqualTo("TEMPORAL_UPDATE_REJECTED");
                        });
    }

    private static ExecuteAgentRunRequest request(long attemptNo) throws Exception {
        JsonNode wrapper = MAPPER.readTree(COMMAND_FIXTURE.toFile());
        RoomGraphCommand command =
                MAPPER.treeToValue(wrapper.required("instance"), RoomGraphCommand.class);
        return new ExecuteAgentRunRequest(
                ExecuteAgentRunRequest.SCHEMA_VERSION,
                command.logicalRunId(),
                attemptNo,
                "agent-stream.v2",
                command);
    }
}
