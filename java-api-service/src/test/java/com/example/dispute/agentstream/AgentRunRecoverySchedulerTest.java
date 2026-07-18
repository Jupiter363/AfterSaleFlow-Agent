package com.example.dispute.agentstream;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunLifecycleService;
import com.example.dispute.agentstream.application.AgentRunRecoveryScheduler;
import com.example.dispute.agentstream.application.AgentRunStreamEventService;
import com.example.dispute.agentstream.application.AgentRunWorker;
import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
import com.example.dispute.config.AppProperties;
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.workflow.config.AgentRunV2Properties;
import com.example.dispute.workflow.config.AgentRunV2Properties.SchedulerMode;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunExecutorKind;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRunRecoverySchedulerTest {

    private final AgentRunRepository runRepository = mock(AgentRunRepository.class);
    private final AgentRunWorker worker = mock(AgentRunWorker.class);
    private final AgentRunLifecycleService lifecycleService =
            mock(AgentRunLifecycleService.class);
    private final AgentRunStreamEventService eventService =
            mock(AgentRunStreamEventService.class);

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
                        AgentRunProtocol.V2.wireValue(),
                        AgentRunExecutorKind.TEMPORAL_ACTIVITY,
                        "PENDING");
    }

    @Test
    void detectorModeObservesV2RowsWithoutExecutingOrFailingThem() {
        AgentRunEntity pending = mock(AgentRunEntity.class);
        AgentRunEntity running = mock(AgentRunEntity.class);
        when(runRepository
                        .findTop20ByProtocolAndExecutorKindAndRunStatusAndStreamOperationIsNotNullOrderByCreatedAtAsc(
                                AgentRunProtocol.V2.wireValue(),
                                AgentRunExecutorKind.TEMPORAL_ACTIVITY,
                                "PENDING"))
                .thenReturn(List.of(pending));
        when(runRepository
                        .findTop20ByProtocolAndExecutorKindAndRunStatusAndStreamOperationIsNotNullOrderByCreatedAtAsc(
                                AgentRunProtocol.V2.wireValue(),
                                AgentRunExecutorKind.TEMPORAL_ACTIVITY,
                                "RUNNING"))
                .thenReturn(List.of(running));

        scheduler(SchedulerMode.DETECTOR).recoverPendingRuns();

        verifyNoInteractions(worker, lifecycleService, eventService);
        verify(runRepository, never())
                .findTop20ByProtocolAndExecutorKindAndRunStatusAndStreamOperationIsNotNullOrderByCreatedAtAsc(
                        AgentRunProtocol.V1.wireValue(),
                        AgentRunExecutorKind.LEGACY_WORKER,
                        "PENDING");
    }

    @Test
    void offModeDoesNotReadOrMutateTheQueue() {
        scheduler(SchedulerMode.OFF).recoverPendingRuns();

        verifyNoInteractions(runRepository, worker, lifecycleService, eventService);
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
        return new AgentRunRecoveryScheduler(
                runRepository,
                worker,
                lifecycleService,
                eventService,
                new PostCommitSideEffectExecutor(Runnable::run),
                appProperties,
                v2Properties);
    }
}
