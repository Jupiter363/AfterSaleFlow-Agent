package com.example.dispute.workflow.agentrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.persistence.AgentRunPersistenceFixtures;
import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.ExecutionMode;
import com.example.dispute.workflow.activity.agent.ProfileSelectingAgentRunExecutionGateway;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class ProfileSelectingAgentRunExecutionGatewayTest {

    @Test
    void routesOnlyTheExplicitParallelProfileToTheV4Delegate() {
        AgentRunExecutionGateway legacy = mock(AgentRunExecutionGateway.class);
        AgentRunExecutionGateway parallel = mock(AgentRunExecutionGateway.class);
        var request = AgentRunPersistenceFixtures.parallelIntakeRequest();
        var expected = new AgentRunExecutionGateway.Completion(
                AgentRunPersistenceFixtures.parallelIntakeGraphResult(), 7L, true);
        when(parallel.execute(any(), any(), any(), any())).thenReturn(expected);

        var actual = new ProfileSelectingAgentRunExecutionGateway(legacy, parallel)
                .execute(
                        request,
                        ExecutionMode.EXECUTE_OR_RECONCILE,
                        ignored -> {},
                        new AgentRunCancellationToken());

        assertThat(actual).isSameAs(expected);
        verify(parallel).execute(any(), any(), any(), any());
        verify(legacy, never()).execute(any(), any(), any(), any());
    }

    @Test
    void keepsEveryLegacyV3RequestOnTheExistingDelegate() {
        AgentRunExecutionGateway legacy = mock(AgentRunExecutionGateway.class);
        AgentRunExecutionGateway parallel = mock(AgentRunExecutionGateway.class);
        var request = AgentRunPersistenceFixtures.requestV3(1L, "attempt-001");
        var expected = new AgentRunExecutionGateway.Completion(
                AgentRunPersistenceFixtures.parallelIntakeGraphResult(), 7L, true);
        when(legacy.execute(any(), any(), any(), any())).thenReturn(expected);

        var actual = new ProfileSelectingAgentRunExecutionGateway(legacy, parallel)
                .execute(
                        request,
                        ExecutionMode.RECONCILE_ONLY,
                        ignored -> {},
                        new AgentRunCancellationToken());

        assertThat(actual).isSameAs(expected);
        verify(legacy).execute(any(), any(), any(), any());
        verify(parallel, never()).execute(any(), any(), any(), any());
    }

    @Test
    void rejectsReservedParallelMarkersAtTheSignedCommandBoundary() {
        assertThatThrownBy(ProfileSelectingAgentRunExecutionGatewayTest::mixedParallelMarkerRequest)
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    private static ExecuteAgentRunRequest mixedParallelMarkerRequest() {
        ExecuteAgentRunRequest source = AgentRunPersistenceFixtures.parallelIntakeRequest();
        var mapper = JsonMapper.builder().findAndAddModules().build();
        ObjectNode commandJson = mapper.valueToTree(source.command());
        commandJson.remove("event_ref");
        commandJson.remove("request_hash");
        commandJson.put("request_hash", ContractJson.sha256Hex(commandJson));
        try {
            RoomGraphCommand command = mapper.treeToValue(commandJson, RoomGraphCommand.class);
            return new ExecuteAgentRunRequest(
                    ExecuteAgentRunRequest.SCHEMA_VERSION,
                    source.agentRunId(),
                    source.attemptNo(),
                    "agent-stream.v3",
                    source.logicalInputHash(),
                    source.previousAttemptId(),
                    source.resetRequired(),
                    source.publicSequenceOffset(),
                    command);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("test command encoding failed", failure);
        }
    }
}
