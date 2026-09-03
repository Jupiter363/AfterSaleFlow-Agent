package com.example.dispute.workflow.infrastructure.persistence.intake.parallel;

import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.AssemblyConflictException;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ReadyArtifact;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ReadyLookup;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.AssemblyState;
import com.example.dispute.workflow.contract.v1.ContractTypes.ArtifactOperationType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphResult.ArtifactOperation;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eFinalizationReceiptLedger.StoredReceipt;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eIntakeParallelAssemblyFinalizationPort;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eRoomFinalizationStrategy.ReceiptBindings;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Marks parallel assembly COMMITTED only inside the existing target formal transaction. */
public final class JdbcTargetE2eIntakeParallelAssemblyFinalizationPort
        implements TargetE2eIntakeParallelAssemblyFinalizationPort {

    private static final String PROPOSAL_SCHEMA = "intake-turn-proposal.v2";

    private final IntakeParallelAssemblyStore assemblyStore;
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcTargetE2eIntakeParallelAssemblyFinalizationPort(
            IntakeParallelAssemblyStore assemblyStore, NamedParameterJdbcTemplate jdbc) {
        this.assemblyStore = Objects.requireNonNull(assemblyStore, "assemblyStore");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public LockedAssembly lockAndRevalidate(
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            ReceiptBindings bindings) {
        requireWritableFormalTransaction();
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(bindings, "bindings");
        if (!ExecuteAgentRunRequest.isParallelIntakeCommand(request.command())
                || result.outcome() != ExecuteAgentRunResult.Outcome.COMPLETED
                || result.graphResult() == null) {
            throw conflict(
                    "INTAKE_PARALLEL_FORMAL_PROFILE_INVALID",
                    "parallel assembly finalization requires an exact completed V4 Intake run");
        }

        var authority = assemblyStore.lockReadyForTerminal(new ReadyLookup(
                request.logicalRunId(),
                request.attemptId(),
                request.command().commandId(),
                request.command().requestHash()));
        ReadyArtifact artifact = authority.artifact();
        ArtifactOperation proposal = uniqueProposal(result);
        boolean exact = result.resultHash().equals(artifact.graphResultSha256())
                && bindings.commandEnvelopeHash().equals(artifact.commandEnvelopeSha256())
                && bindings.proposalHash().equals(artifact.targetProposalSha256())
                && bindings.resultEnvelopeHash().equals(artifact.resultEnvelopeSha256())
                && bindings.checkpointId().equals(result.graphResult().checkpointId())
                && proposal.artifact().artifactId().equals(artifact.proposalArtifactId())
                && proposal.artifact().schemaVersion().equals(PROPOSAL_SCHEMA)
                && proposal.artifact().uri().equals(artifact.proposalUri())
                && proposal.artifact().sha256().equals(artifact.proposalSha256());
        if (!exact) {
            throw conflict(
                    "INTAKE_PARALLEL_FORMAL_ARTIFACT_MISMATCH",
                    "formal finalization evidence differs from immutable READY artifacts");
        }
        return new LockedAssembly(
                authority,
                artifact.commandEnvelopeSha256(),
                artifact.targetProposalSha256(),
                artifact.resultEnvelopeSha256(),
                artifact.graphResultSha256());
    }

    @Override
    public void markCommitted(LockedAssembly locked, StoredReceipt storedReceipt) {
        requireWritableFormalTransaction();
        Objects.requireNonNull(locked, "locked");
        Objects.requireNonNull(storedReceipt, "storedReceipt");
        var receipt = storedReceipt.receipt();
        if (!locked.commandEnvelopeSha256().equals(receipt.commandEnvelopeHash())
                || !locked.targetProposalSha256().equals(receipt.proposalHash())
                || !locked.resultEnvelopeSha256().equals(receipt.resultEnvelopeHash())
                || !locked.graphResultSha256().equals(receipt.resultHash())) {
            throw conflict(
                    "INTAKE_PARALLEL_FORMAL_RECEIPT_MISMATCH",
                    "target receipt differs from locked parallel assembly authority");
        }
        if (locked.authority().state() == AssemblyState.COMMITTED) {
            requireCommittedReplay(locked, storedReceipt);
            return;
        }

        ReadyArtifact artifact = locked.authority().artifact();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("frameSetId", locked.authority().frameSetId())
                .addValue("expectedVersion", locked.authority().frameSetVersion())
                .addValue("proposalArtifactId", artifact.proposalArtifactId())
                .addValue("proposalSha256", artifact.proposalSha256())
                .addValue("resultArtifactId", artifact.resultArtifactId())
                .addValue("graphResultSha256", artifact.graphResultSha256())
                .addValue("terminalReceiptId", storedReceipt.receiptId())
                .addValue("committedAt", Timestamp.from(receipt.committedAt()));
        int updated = jdbc.update(
                """
                update intake_parallel_frame_set
                   set assembly_state = 'COMMITTED',
                       terminal_receipt_id = :terminalReceiptId,
                       committed_at = :committedAt,
                       updated_at = greatest(updated_at, :committedAt),
                       version = version + 1
                 where frame_set_id = :frameSetId
                   and assembly_state = 'READY'
                   and version = :expectedVersion
                   and proposal_artifact_id = :proposalArtifactId
                   and proposal_sha256 = :proposalSha256
                   and graph_result_artifact_id = :resultArtifactId
                   and graph_result_sha256 = :graphResultSha256
                   and terminal_receipt_id is null
                """,
                parameters);
        if (updated != 1) {
            throw conflict(
                    "INTAKE_PARALLEL_FORMAL_COMMIT_CAS_FAILED",
                    "parallel assembly changed before formal receipt binding");
        }
    }

    private void requireCommittedReplay(LockedAssembly locked, StoredReceipt storedReceipt) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select assembly_state, version, terminal_receipt_id, committed_at
                  from intake_parallel_frame_set
                 where frame_set_id = :frameSetId
                   and proposal_artifact_id = :proposalArtifactId
                   and proposal_sha256 = :proposalSha256
                   and graph_result_artifact_id = :resultArtifactId
                   and graph_result_sha256 = :graphResultSha256
                """,
                new MapSqlParameterSource()
                        .addValue("frameSetId", locked.authority().frameSetId())
                        .addValue(
                                "proposalArtifactId",
                                locked.authority().artifact().proposalArtifactId())
                        .addValue("proposalSha256", locked.authority().artifact().proposalSha256())
                        .addValue(
                                "resultArtifactId",
                                locked.authority().artifact().resultArtifactId())
                        .addValue(
                                "graphResultSha256",
                                locked.authority().artifact().graphResultSha256()));
        if (rows.size() != 1) {
            throw conflict(
                    "INTAKE_PARALLEL_FORMAL_COMMITTED_AUTHORITY_MISSING",
                    "committed parallel assembly cannot be reloaded exactly");
        }
        Map<String, Object> row = rows.getFirst();
        Instant committedAt = instant(row.get("committed_at"));
        if (!AssemblyState.COMMITTED.name().equals(row.get("assembly_state"))
                || ((Number) row.get("version")).longValue()
                        != locked.authority().frameSetVersion()
                || !storedReceipt.receiptId().equals(row.get("terminal_receipt_id"))
                || !storedReceipt.receipt().committedAt().equals(committedAt)) {
            throw conflict(
                    "INTAKE_PARALLEL_FORMAL_COMMITTED_REPLAY_CONFLICT",
                    "committed parallel assembly differs from the original target receipt");
        }
    }

    private static ArtifactOperation uniqueProposal(ExecuteAgentRunResult result) {
        List<ArtifactOperation> proposals = result.graphResult().artifactOperations().stream()
                .filter(operation -> operation.operation() == ArtifactOperationType.PROPOSE_PATCH)
                .toList();
        if (proposals.size() != 1) {
            throw conflict(
                    "INTAKE_PARALLEL_FORMAL_PROPOSAL_CARDINALITY_INVALID",
                    "parallel Graph result must expose exactly one proposal artifact");
        }
        return proposals.getFirst();
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        throw conflict(
                "INTAKE_PARALLEL_FORMAL_COMMITTED_AUTHORITY_CORRUPT",
                "committed assembly timestamp is invalid");
    }

    private static void requireWritableFormalTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException(
                    "parallel assembly formalization requires the caller-owned writable transaction");
        }
    }

    private static AssemblyConflictException conflict(String code, String message) {
        return new AssemblyConflictException(code, message);
    }
}
