package com.example.dispute.workflow.infrastructure.persistence.intake.parallel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.infrastructure.persistence.PostgresAgentRunV4EventWriter;
import com.example.dispute.agentstream.infrastructure.persistence.PostgresAgentRunV4EventWriter.EventWriteCommand;
import com.example.dispute.agentstream.infrastructure.persistence.PostgresAgentRunV4EventWriter.TerminalWriteReceipt;
import com.example.dispute.agentstream.persistence.AgentRunPersistenceFixtures;
import com.example.dispute.infrastructure.persistence.entity.AgentRunAttemptEntity;
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.repository.AgentRunAttemptRepository;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ReadyArtifact;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ReadyAuthority;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.AssemblyState;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelRunTerminalStore.TerminalCommand;
import com.example.dispute.workflow.contract.v1.AgentRunAttemptHeartbeat;
import com.example.dispute.workflow.contract.v1.AgentStreamEventV4;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.GraphReconcileResponse;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class TransactionalIntakeParallelRunTerminalStoreTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Test
    void ownsOneIndependentReadCommittedTechnicalTransaction() throws Exception {
        Transactional transaction = TransactionalIntakeParallelRunTerminalStore.class
                .getMethod("appendOrLoad", TerminalCommand.class)
                .getAnnotation(Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(transaction.isolation()).isEqualTo(Isolation.READ_COMMITTED);
        assertThat(transaction.rollbackFor()).containsExactly(Exception.class);
    }

    @Test
    void reloadsMonotonicAttemptProgressAsTheFailureClassificationAuthority() {
        ExecuteAgentRunRequest request = AgentRunPersistenceFixtures.parallelIntakeRequest();
        AgentRunEntity run = AgentRunEntity.logicalV4(
                AgentRunPersistenceFixtures.logicalRunV4());
        run.bindV4Audience(
                request.command().actorScope().actorRole().name(),
                canonicalJson(List.of(request.command().actorScope().audience().name())),
                canonicalJson(List.of(request.command().actorScope().actorId())));
        run.markV4AttemptStarted();
        AgentRunAttemptEntity attempt = AgentRunAttemptEntity.startV4(
                request.agentRunId(),
                AgentRunPersistenceFixtures.parallelIntakeAllocation(),
                AgentRunPersistenceFixtures.STARTED_AT);
        attempt.recordHeartbeat(new AgentRunAttemptHeartbeat(
                AgentRunAttemptHeartbeat.SCHEMA_VERSION,
                request.agentRunId(),
                request.attemptId(),
                request.attemptNo(),
                6,
                true,
                false,
                AgentRunPersistenceFixtures.STARTED_AT.plusSeconds(2)));

        AgentRunRepository runRepository = mock(AgentRunRepository.class);
        AgentRunAttemptRepository attemptRepository = mock(AgentRunAttemptRepository.class);
        when(runRepository.findById(request.agentRunId())).thenReturn(Optional.of(run));
        when(attemptRepository.findById(request.attemptId())).thenReturn(Optional.of(attempt));
        TransactionalIntakeParallelRunTerminalStore store =
                new TransactionalIntakeParallelRunTerminalStore(
                        runRepository,
                        attemptRepository,
                        mock(IntakeParallelAssemblyStore.class),
                        mock(PostgresAgentRunV4EventWriter.class),
                        MAPPER);

        var progress = store.loadProgress(request);

        assertThat(progress.lastSequenceNo()).isEqualTo(6);
        assertThat(progress.publicOutputEmitted()).isTrue();
        assertThat(progress.finalFrameObserved()).isFalse();
    }

    @Test
    void atomicallyBindsReadyToOneDeterministicFinalAndReplaysItExactly() {
        ExecuteAgentRunRequest request = AgentRunPersistenceFixtures.parallelIntakeRequest();
        RoomGraphResult graphResult = AgentRunPersistenceFixtures.parallelIntakeGraphResult();
        AgentRunEntity run = AgentRunEntity.logicalV4(
                AgentRunPersistenceFixtures.logicalRunV4());
        run.bindV4Audience(
                request.command().actorScope().actorRole().name(),
                canonicalJson(List.of(request.command().actorScope().audience().name())),
                canonicalJson(List.of(request.command().actorScope().actorId())));
        run.markV4AttemptStarted();
        AgentRunAttemptEntity attempt = AgentRunAttemptEntity.startV4(
                request.agentRunId(),
                AgentRunPersistenceFixtures.parallelIntakeAllocation(),
                AgentRunPersistenceFixtures.STARTED_AT);
        attempt.recordHeartbeat(new AgentRunAttemptHeartbeat(
                AgentRunAttemptHeartbeat.SCHEMA_VERSION,
                request.agentRunId(),
                request.attemptId(),
                request.attemptNo(),
                4,
                true,
                false,
                AgentRunPersistenceFixtures.STARTED_AT.plusSeconds(2)));
        ReadyArtifact artifact = artifact(graphResult);
        ReadyAuthority ready = new ReadyAuthority(
                "FRAME_SET_TERMINAL_1",
                AssemblyState.READY,
                7,
                AgentRunPersistenceFixtures.COMPLETED_AT,
                artifact);
        GraphReconcileResponse reconciliation = reconciliation(request, graphResult, artifact);

        AgentRunRepository runRepository = mock(AgentRunRepository.class);
        AgentRunAttemptRepository attemptRepository = mock(AgentRunAttemptRepository.class);
        IntakeParallelAssemblyStore assemblyStore = mock(IntakeParallelAssemblyStore.class);
        PostgresAgentRunV4EventWriter writer = mock(PostgresAgentRunV4EventWriter.class);
        when(runRepository.findByIdForUpdate(request.agentRunId()))
                .thenReturn(Optional.of(run));
        when(attemptRepository.findByIdForUpdate(request.attemptId()))
                .thenReturn(Optional.of(attempt));
        when(assemblyStore.lockReadyForTerminal(any())).thenReturn(ready);
        AtomicInteger writes = new AtomicInteger();
        when(writer.appendOrLoadExactTerminalInCurrentTransaction(any()))
                .thenAnswer(invocation -> {
                    EventWriteCommand event = invocation.getArgument(0);
                    return new TerminalWriteReceipt(
                            event.eventId(),
                            writes.getAndIncrement() == 0,
                            canonicalJson(new AgentStreamEventV4(
                                    "agent-stream.v4",
                                    event.runId(),
                                    event.attemptId(),
                                    event.sequenceNo(),
                                    event.eventType(),
                                    event.audience(),
                                    event.occurredAt(),
                                    event.payload())),
                            "f".repeat(64),
                            event.sequenceNo());
                });
        TransactionalIntakeParallelRunTerminalStore store =
                new TransactionalIntakeParallelRunTerminalStore(
                        runRepository,
                        attemptRepository,
                        assemblyStore,
                        writer,
                        mapperWithDefaultNullInclusion());

        var first = store.appendOrLoad(new TerminalCommand(request, reconciliation));
        var replay = store.appendOrLoad(new TerminalCommand(request, reconciliation));

        assertThat(first.inserted()).isTrue();
        assertThat(replay.inserted()).isFalse();
        assertThat(replay.result()).isEqualTo(first.result());
        assertThat(replay.eventId()).isEqualTo(first.eventId());
        assertThat(replay.finalReceiptId()).isEqualTo(first.finalReceiptId());
        assertThat(first.result().completedAt())
                .isEqualTo(AgentRunPersistenceFixtures.COMPLETED_AT);
        assertThat(first.result().lastSequenceNo()).isEqualTo(5);
        assertThat(attempt.getLastSequenceNo()).isEqualTo(5);
        assertThat(attempt.isFinalFrameObserved()).isTrue();
        assertThat(run.getRunStatus()).isEqualTo("RESULT_READY");
        assertThat(run.getResultReadyAttemptId()).isEqualTo(request.attemptId());

        ArgumentCaptor<EventWriteCommand> events =
                ArgumentCaptor.forClass(EventWriteCommand.class);
        verify(writer, times(2)).appendOrLoadExactTerminalInCurrentTransaction(events.capture());
        assertThat(events.getAllValues()).allSatisfy(event -> {
            assertThat(event.sequenceNo()).isEqualTo(5);
            assertThat(event.eventType()).isEqualTo(AgentStreamEventV4.EventType.FINAL);
            assertThat(event.occurredAt())
                    .isEqualTo(AgentRunPersistenceFixtures.COMPLETED_AT);
            assertThat(event.payload().finalResultHash())
                    .isEqualTo(graphResult.outputHash());
        });
    }

    private static ReadyArtifact artifact(RoomGraphResult graphResult) {
        String proposalHash = "c".repeat(64);
        String resultHash = graphResult.outputHash();
        return new ReadyArtifact(
                "a".repeat(64),
                "intake.proposal." + proposalHash.substring(0, 32),
                "urn:target-e2e:proposal:intake:" + proposalHash,
                proposalHash,
                "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "profile-manifest-terminal",
                "intake.graph-result." + resultHash.substring(0, 32),
                "urn:target-e2e:result:intake:" + resultHash,
                resultHash,
                ContractJson.canonicalize(MAPPER.valueToTree(graphResult)),
                "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "d".repeat(64),
                "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "e".repeat(64),
                "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "f".repeat(64),
                "checkpoint-parallel-terminal",
                "1".repeat(64),
                "tool-policy-terminal");
    }

    private static GraphReconcileResponse reconciliation(
            ExecuteAgentRunRequest request,
            RoomGraphResult graphResult,
            ReadyArtifact artifact) {
        return new GraphReconcileResponse(
                "graph-reconcile-response.v1",
                GraphReconcileResponse.Disposition.RETURN_CACHED,
                request.command().threadId(),
                request.command().commandId(),
                request.command().requestHash(),
                request.logicalRunId(),
                request.attemptId(),
                request.command().graphKey(),
                request.command().graphVersion(),
                request.command().checkpointSchemaVersion(),
                artifact.checkpointNs(),
                graphResult.checkpointId(),
                artifact.resultRef(),
                artifact.graphResultSha256(),
                artifact.registryBindingSha256(),
                artifact.toolPolicyVersion(),
                graphResult);
    }

    private static String canonicalJson(Object value) {
        return ContractJson.canonicalString(MAPPER.valueToTree(value));
    }

    private static ObjectMapper mapperWithDefaultNullInclusion() {
        return JsonMapper.builder()
                .findAndAddModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }
}
