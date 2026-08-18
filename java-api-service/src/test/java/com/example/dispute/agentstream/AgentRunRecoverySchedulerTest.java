package com.example.dispute.agentstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunLifecycleService;
import com.example.dispute.agentstream.application.AgentRunRecoveryScheduler;
import com.example.dispute.agentstream.application.AgentRunStreamEventService;
import com.example.dispute.agentstream.application.AgentRunV2Coordinator;
import com.example.dispute.agentstream.application.AgentRunV2RecoveryService;
import com.example.dispute.agentstream.application.AgentRunWorker;
import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
import com.example.dispute.config.AppProperties;
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.workflow.config.AgentRunV2Properties;
import com.example.dispute.workflow.config.AgentRunV2Properties.SchedulerMode;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunExecutorKind;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class AgentRunRecoverySchedulerTest {

    private final AgentRunRepository runRepository = mock(AgentRunRepository.class);
    private final AgentRunWorker worker = mock(AgentRunWorker.class);
    private final AgentRunLifecycleService lifecycleService = mock(AgentRunLifecycleService.class);
    private final AgentRunStreamEventService eventService = mock(AgentRunStreamEventService.class);
    private final JdbcTemplate detectorJdbc = mock(JdbcTemplate.class);
    private final AgentRunV2RecoveryService v2RecoveryService =
            mock(AgentRunV2RecoveryService.class);
    private final AgentRunV2Coordinator v2Coordinator = mock(AgentRunV2Coordinator.class);

    @Test
    void executorModeClaimsOnlyLegacyV1Rows() {
        AgentRunEntity pending = mock(AgentRunEntity.class);
        when(pending.getId()).thenReturn("AGENT_RUN_V1");
        when(runRepository
                        .findTop20ByProtocolAndExecutorKindAndRunStatusAndStreamOperationIsNotNullOrderByCreatedAtAsc(
                                AgentRunProtocol.V1.wireValue(),
                                AgentRunExecutorKind.LEGACY_WORKER,
                                "PENDING"))
                .thenReturn(List.of(pending));

        scheduler(SchedulerMode.EXECUTOR).recoverPendingRuns();

        verify(worker).execute("AGENT_RUN_V1");
        verify(runRepository)
                .findTop20ByProtocolAndExecutorKindAndRunStatusAndStreamOperationIsNotNullOrderByCreatedAtAsc(
                        AgentRunProtocol.V1.wireValue(),
                        AgentRunExecutorKind.LEGACY_WORKER,
                        "RUNNING");
        verify(runRepository, never())
                .findTop20ByProtocolAndExecutorKindAndRunStatusAndStreamOperationIsNotNullOrderByCreatedAtAsc(
                        AgentRunProtocol.V3.wireValue(),
                        AgentRunExecutorKind.TEMPORAL_ACTIVITY,
                        "PENDING");
    }

    @Test
    void detectorModeAggregatesLegacyOwnershipAndRecoversOnlyNarrowV2Candidates() {
        AgentRunEntity pending = mock(AgentRunEntity.class);
        AgentRunEntity running = mock(AgentRunEntity.class);
        ExecuteAgentRunRequest request = mock(ExecuteAgentRunRequest.class);
        when(pending.getId()).thenReturn("AGENT_RUN_V2_PENDING");
        when(running.getId()).thenReturn("AGENT_RUN_V2_RUNNING");
        when(detectorJdbc.queryForMap(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(
                        Map.<String, Object>of(
                                "candidate_count", 27L,
                                "pending_count", 21L,
                                "running_count", 6L));
        when(runRepository
                        .findTop20ByProtocolAndExecutorKindAndRunStatusAndStreamOperationIsNotNullOrderByCreatedAtAsc(
                                AgentRunProtocol.V3.wireValue(),
                                AgentRunExecutorKind.TEMPORAL_ACTIVITY,
                                "PENDING"))
                .thenReturn(List.of(pending));
        when(runRepository
                        .findTop20ByProtocolAndExecutorKindAndRunStatusAndStreamOperationIsNotNullOrderByCreatedAtAsc(
                                AgentRunProtocol.V3.wireValue(),
                                AgentRunExecutorKind.TEMPORAL_ACTIVITY,
                                "RUNNING"))
                .thenReturn(List.of(running));
        when(v2RecoveryService.prepare("AGENT_RUN_V2_PENDING"))
                .thenReturn(Optional.of(request));
        when(v2RecoveryService.prepare("AGENT_RUN_V2_RUNNING"))
                .thenReturn(Optional.empty());

        scheduler(SchedulerMode.DETECTOR).recoverPendingRuns();

        verifyNoInteractions(worker, lifecycleService, eventService);
        verify(v2RecoveryService).prepare("AGENT_RUN_V2_PENDING");
        verify(v2RecoveryService).prepare("AGENT_RUN_V2_RUNNING");
        verify(v2Coordinator).dispatchAllocatedAttempt(request);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(detectorJdbc).queryForMap(sql.capture());
        assertThat(sql.getValue())
                .contains(
                        "from agent_run",
                        "protocol = 'agent_stream.v1'",
                        "executor_kind = 'LEGACY_WORKER'",
                        "stream_operation is not null",
                        "run_status in ('PENDING', 'RUNNING')")
                .doesNotContain("limit", "hearing_temporal_projection");
    }

    @Test
    void detectorModeDoesNotScanV2WhenThisProcessOwnsNoRetryPreparer() {
        when(detectorJdbc.queryForMap(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(
                        Map.<String, Object>of(
                                "candidate_count", 0L,
                                "pending_count", 0L,
                                "running_count", 0L));
        AgentRunRecoveryScheduler scheduler = scheduler(SchedulerMode.DETECTOR);
        when(v2RecoveryService.isRecoveryConfigured()).thenReturn(false);

        scheduler.recoverPendingRuns();

        verify(v2RecoveryService).isRecoveryConfigured();
        verifyNoInteractions(runRepository, worker, lifecycleService, eventService, v2Coordinator);
        verify(v2RecoveryService, never()).prepare(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void detectorModeFailsClosedWhenTheAggregateIsMissingOrIncomplete() {
        when(detectorJdbc.queryForMap(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(
                        Map.<String, Object>of(
                                "candidate_count", 3L,
                                "pending_count", 1L,
                                "running_count", 1L));

        assertThatThrownBy(() -> scheduler(SchedulerMode.DETECTOR).recoverPendingRuns())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete or inconsistent");

        verifyNoInteractions(runRepository, worker, lifecycleService, eventService, v2RecoveryService, v2Coordinator);
        verify(runRepository, never())
                .findTop20ByProtocolAndExecutorKindAndRunStatusAndStreamOperationIsNotNullOrderByCreatedAtAsc(
                        AgentRunProtocol.V3.wireValue(),
                        AgentRunExecutorKind.TEMPORAL_ACTIVITY,
                        "PENDING");
    }

    @Test
    void offModeDoesNotReadOrMutateTheQueue() {
        scheduler(SchedulerMode.OFF).recoverPendingRuns();

        verifyNoInteractions(
                runRepository,
                worker,
                lifecycleService,
                eventService,
                detectorJdbc,
                v2RecoveryService,
                v2Coordinator);
    }

    private AgentRunRecoveryScheduler scheduler(SchedulerMode mode) {
        AppProperties appProperties = mock(AppProperties.class);
        when(appProperties.agent())
                .thenReturn(new AppProperties.Integration("http://agent", "secret", 1_000));
        AgentRunV2Properties v2Properties =
                new AgentRunV2Properties(
                        mode != SchedulerMode.EXECUTOR,
                        AgentRunProtocol.V1,
                        mode,
                        Duration.ofMinutes(10),
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(5));
        when(v2RecoveryService.isRecoveryConfigured()).thenReturn(true);
        return new AgentRunRecoveryScheduler(
                runRepository,
                worker,
                lifecycleService,
                eventService,
                new PostCommitSideEffectExecutor(Runnable::run),
                appProperties,
                v2Properties,
                detectorJdbc,
                v2RecoveryService,
                v2Coordinator);
    }
}
