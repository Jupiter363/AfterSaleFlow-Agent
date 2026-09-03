package com.example.dispute.workflow.infrastructure.persistence.intake.parallel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.AssemblyConflictException;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ReadyArtifact;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ReadyAuthority;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.AssemblyState;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactOperationType;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.contract.v1.RoomGraphResult.ArtifactOperation;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationReceipt;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationReceiptLedger.StoredReceipt;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eRoomFinalizationStrategy.ReceiptBindings;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class JdbcTargetE2eIntakeParallelAssemblyFinalizationPortTest {

    private static final String COMMAND = "CMD_PARALLEL_1";
    private static final String RUN = "RUN_PARALLEL_1";
    private static final String ATTEMPT = "ATTEMPT_PARALLEL_1";
    private static final String REQUEST_HASH = "1".repeat(64);
    private static final String PROPOSAL_HASH = "2".repeat(64);
    private static final String TARGET_PROPOSAL_HASH = "3".repeat(64);
    private static final String RESULT_HASH = "4".repeat(64);
    private static final String COMMAND_ENVELOPE_HASH = "5".repeat(64);
    private static final String RESULT_ENVELOPE_HASH = "6".repeat(64);
    private static final Instant COMMITTED_AT = Instant.parse("2026-08-25T00:00:00Z");

    private IntakeParallelAssemblyStore assemblyStore;
    private NamedParameterJdbcTemplate jdbc;
    private JdbcTargetE2eIntakeParallelAssemblyFinalizationPort port;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        assemblyStore = mock(IntakeParallelAssemblyStore.class);
        jdbc = mock(NamedParameterJdbcTemplate.class);
        port = new JdbcTargetE2eIntakeParallelAssemblyFinalizationPort(assemblyStore, jdbc);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void locksExactReadyAuthorityAndBindsTheRealTargetReceipt() {
        ReadyAuthority ready = authority(AssemblyState.READY, 7);
        when(assemblyStore.lockReadyForTerminal(any())).thenReturn(ready);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        var locked = port.lockAndRevalidate(request(), result(), bindings());
        port.markCommitted(locked, storedReceipt());

        assertThat(locked.authority()).isSameAs(ready);
        verify(assemblyStore).lockReadyForTerminal(any());
        verify(jdbc).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void exactCommittedReplayDoesNotIssueASecondStateTransition() {
        ReadyAuthority committed = authority(AssemblyState.COMMITTED, 8);
        when(assemblyStore.lockReadyForTerminal(any())).thenReturn(committed);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(List.of(Map.of(
                        "assembly_state", "COMMITTED",
                        "version", 8L,
                        "terminal_receipt_id", "RECEIPT_PARALLEL_1",
                        "committed_at", Timestamp.from(COMMITTED_AT))));

        var locked = port.lockAndRevalidate(request(), result(), bindings());
        port.markCommitted(locked, storedReceipt());

        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void rejectsAProposalPointerThatDiffersFromReadyAuthority() {
        ReadyAuthority ready = authority(AssemblyState.READY, 7);
        when(assemblyStore.lockReadyForTerminal(any())).thenReturn(ready);
        ExecuteAgentRunResult drifted = result();
        when(drifted.graphResult().artifactOperations().getFirst().artifact().sha256())
                .thenReturn("9".repeat(64));

        assertThatThrownBy(() -> port.lockAndRevalidate(request(), drifted, bindings()))
                .isInstanceOf(AssemblyConflictException.class)
                .extracting(failure -> ((AssemblyConflictException) failure).code())
                .isEqualTo("INTAKE_PARALLEL_FORMAL_ARTIFACT_MISMATCH");
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    private static ExecuteAgentRunRequest request() {
        ExecuteAgentRunRequest request = mock(ExecuteAgentRunRequest.class);
        RoomGraphCommand command = mock(RoomGraphCommand.class);
        RoomGraphCommand.InvocationContext invocation = mock(RoomGraphCommand.InvocationContext.class);
        RoomGraphCommand.ActorScope actor = mock(RoomGraphCommand.ActorScope.class);
        when(request.command()).thenReturn(command);
        when(request.logicalRunId()).thenReturn(RUN);
        when(request.attemptId()).thenReturn(ATTEMPT);
        when(command.commandId()).thenReturn(COMMAND);
        when(command.requestHash()).thenReturn(REQUEST_HASH);
        when(command.roomType()).thenReturn(RoomType.INTAKE);
        when(command.roomId()).thenReturn("ROOM_1");
        when(command.isExactParallelIntakeProfile()).thenReturn(true);
        when(command.invocationContext()).thenReturn(invocation);
        when(command.actorScope()).thenReturn(actor);
        when(actor.actorRole()).thenReturn(ActorRole.USER);
        when(invocation.agentProfileId())
                .thenReturn(ExecuteAgentRunRequest.PARALLEL_INTAKE_AGENT_PROFILE_ID);
        when(invocation.outputSchemaVersion())
                .thenReturn(ExecuteAgentRunRequest.PARALLEL_INTAKE_OUTPUT_SCHEMA);
        when(command.eventRef()).thenReturn(mock(RoomGraphCommand.SnapshotRef.class));
        return request;
    }

    private static ExecuteAgentRunResult result() {
        ExecuteAgentRunResult result = mock(ExecuteAgentRunResult.class);
        RoomGraphResult graph = mock(RoomGraphResult.class);
        ArtifactOperation operation = mock(ArtifactOperation.class);
        com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer pointer =
                mock(com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactPointer.class);
        when(result.outcome()).thenReturn(ExecuteAgentRunResult.Outcome.COMPLETED);
        when(result.resultHash()).thenReturn(RESULT_HASH);
        when(result.graphResult()).thenReturn(graph);
        when(graph.checkpointId()).thenReturn("CHECKPOINT_PARALLEL_1");
        when(graph.artifactOperations()).thenReturn(List.of(operation));
        when(operation.operation()).thenReturn(ArtifactOperationType.PROPOSE_PATCH);
        when(operation.artifact()).thenReturn(pointer);
        when(pointer.artifactId()).thenReturn("intake.proposal." + PROPOSAL_HASH.substring(0, 32));
        when(pointer.schemaVersion()).thenReturn("intake-turn-proposal.v2");
        when(pointer.uri()).thenReturn("urn:target-e2e:proposal:intake:" + PROPOSAL_HASH);
        when(pointer.sha256()).thenReturn(PROPOSAL_HASH);
        return result;
    }

    private static ReceiptBindings bindings() {
        ReceiptBindings bindings = mock(ReceiptBindings.class);
        when(bindings.commandEnvelopeHash()).thenReturn(COMMAND_ENVELOPE_HASH);
        when(bindings.proposalHash()).thenReturn(TARGET_PROPOSAL_HASH);
        when(bindings.resultEnvelopeHash()).thenReturn(RESULT_ENVELOPE_HASH);
        when(bindings.checkpointId()).thenReturn("CHECKPOINT_PARALLEL_1");
        return bindings;
    }

    private static ReadyAuthority authority(AssemblyState state, long version) {
        ReadyArtifact artifact = mock(ReadyArtifact.class);
        when(artifact.proposalArtifactId())
                .thenReturn("intake.proposal." + PROPOSAL_HASH.substring(0, 32));
        when(artifact.proposalUri())
                .thenReturn("urn:target-e2e:proposal:intake:" + PROPOSAL_HASH);
        when(artifact.proposalSha256()).thenReturn(PROPOSAL_HASH);
        when(artifact.resultArtifactId())
                .thenReturn("intake.graph-result." + RESULT_HASH.substring(0, 32));
        when(artifact.graphResultSha256()).thenReturn(RESULT_HASH);
        when(artifact.commandEnvelopeSha256()).thenReturn(COMMAND_ENVELOPE_HASH);
        when(artifact.targetProposalSha256()).thenReturn(TARGET_PROPOSAL_HASH);
        when(artifact.resultEnvelopeSha256()).thenReturn(RESULT_ENVELOPE_HASH);
        return new ReadyAuthority(
                "FRAME_SET_PARALLEL_1", state, version, COMMITTED_AT.minusSeconds(10), artifact);
    }

    private static StoredReceipt storedReceipt() {
        StoredReceipt stored = mock(StoredReceipt.class);
        TargetE2eFinalizationReceipt receipt = mock(TargetE2eFinalizationReceipt.class);
        when(stored.receiptId()).thenReturn("RECEIPT_PARALLEL_1");
        when(stored.receipt()).thenReturn(receipt);
        when(receipt.commandEnvelopeHash()).thenReturn(COMMAND_ENVELOPE_HASH);
        when(receipt.proposalHash()).thenReturn(TARGET_PROPOSAL_HASH);
        when(receipt.resultEnvelopeHash()).thenReturn(RESULT_ENVELOPE_HASH);
        when(receipt.resultHash()).thenReturn(RESULT_HASH);
        when(receipt.committedAt()).thenReturn(COMMITTED_AT);
        return stored;
    }
}
