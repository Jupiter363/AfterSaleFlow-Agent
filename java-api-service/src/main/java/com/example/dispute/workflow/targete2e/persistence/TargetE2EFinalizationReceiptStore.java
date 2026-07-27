package com.example.dispute.workflow.targete2e.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/** Appends target-E2E evidence on the caller-owned Java Finalizer transaction. */
public final class TargetE2EFinalizationReceiptStore {

    private static final String INSERT = """
            insert into target_e2e_finalization_receipt (
                receipt_id, schema_version, execution_lane, activation_id,
                activation_manifest_hash, tenant_surrogate, case_id, room_type,
                room_epoch, room_fencing_token, process_revision, stage_sequence,
                logical_run_id, attempt_id, command_hash, command_envelope_hash,
                graph_key, graph_version, checkpoint_schema_version, checkpoint_id,
                result_hash, proposal_hash, result_envelope_hash, agent_run_manifest_id,
                agent_run_manifest_hash, isolated_domain_db_binding_hash, committed_at,
                receipt_hash, receipt_canonical_bytes, formal_writer, domain_commit_status
            ) values (
                ?, 'target-e2e-finalization-receipt.v1', 'TARGET_E2E_CANDIDATE',
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, 'JAVA_FINALIZER_ONLY', 'COMMITTED'
            ) on conflict do nothing
            """;

    public AppendResult append(Connection transaction, Receipt receipt) {
        Objects.requireNonNull(transaction, "transaction must not be null");
        Objects.requireNonNull(receipt, "receipt must not be null");
        try {
            if (transaction.getAutoCommit()) {
                throw new IllegalArgumentException(
                        "receipt must be appended inside the Java Finalizer transaction");
            }
            int inserted = insert(transaction, receipt);
            PersistedReceipt persisted = find(transaction, receipt.activationId(), receipt.logicalRunId());
            if (persisted == null) {
                throw new TargetE2EPersistenceException(
                        "FINALIZATION_RECEIPT_NOT_FOUND",
                        "receipt append did not produce durable evidence");
            }
            if (!persisted.receiptHash().equals(receipt.receiptHash())
                    || !persisted.receiptId().equals(receipt.receiptId())
                    || !Arrays.equals(persisted.canonicalBytes(), receipt.canonicalBytes())) {
                throw new TargetE2EPersistenceException(
                        "FINALIZATION_RECEIPT_CONFLICT",
                        "logical run is already finalized with different receipt evidence");
            }
            return new AppendResult(
                    inserted == 1 ? CommitStatus.COMMITTED : CommitStatus.ALREADY_COMMITTED,
                    persisted.receiptId(),
                    persisted.receiptHash(),
                    persisted.committedAt(),
                    persisted.canonicalBytes());
        } catch (SQLException failure) {
            throw new TargetE2EPersistenceException(
                    "FINALIZATION_RECEIPT_APPEND_FAILED", failure.getMessage(), failure);
        }
    }

    private static int insert(Connection connection, Receipt receipt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            int i = 1;
            statement.setString(i++, receipt.receiptId());
            statement.setString(i++, receipt.activationId());
            statement.setString(i++, receipt.activationManifestHash());
            statement.setString(i++, receipt.tenantSurrogate());
            statement.setString(i++, receipt.caseId());
            statement.setString(i++, receipt.roomType());
            statement.setLong(i++, receipt.roomEpoch());
            statement.setLong(i++, receipt.roomFencingToken());
            statement.setLong(i++, receipt.processRevision());
            statement.setLong(i++, receipt.stageSequence());
            statement.setString(i++, receipt.logicalRunId());
            statement.setString(i++, receipt.attemptId());
            statement.setString(i++, receipt.commandHash());
            statement.setString(i++, receipt.commandEnvelopeHash());
            statement.setString(i++, receipt.graphKey());
            statement.setString(i++, receipt.graphVersion());
            statement.setString(i++, receipt.checkpointSchemaVersion());
            statement.setString(i++, receipt.checkpointId());
            statement.setString(i++, receipt.resultHash());
            statement.setString(i++, receipt.proposalHash());
            statement.setString(i++, receipt.resultEnvelopeHash());
            statement.setString(i++, receipt.agentRunManifestId());
            statement.setString(i++, receipt.agentRunManifestHash());
            statement.setString(i++, receipt.isolatedDomainDbBindingHash());
            statement.setTimestamp(i++, Timestamp.from(receipt.committedAt()));
            statement.setString(i++, receipt.receiptHash());
            statement.setBytes(i, receipt.canonicalBytes());
            return statement.executeUpdate();
        }
    }

    private static PersistedReceipt find(
            Connection connection, String activationId, String logicalRunId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select receipt_id, receipt_hash, committed_at, receipt_canonical_bytes
                  from target_e2e_finalization_receipt
                 where activation_id = ? and logical_run_id = ?
                """)) {
            statement.setString(1, activationId);
            statement.setString(2, logicalRunId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new PersistedReceipt(
                        result.getString("receipt_id"),
                        result.getString("receipt_hash"),
                        result.getTimestamp("committed_at").toInstant(),
                        result.getBytes("receipt_canonical_bytes"));
            }
        }
    }

    public enum CommitStatus {
        COMMITTED,
        ALREADY_COMMITTED
    }

    public record AppendResult(
            CommitStatus status,
            String receiptId,
            String receiptHash,
            Instant committedAt,
            byte[] canonicalBytes) {
        public AppendResult {
            canonicalBytes = canonicalBytes.clone();
        }

        @Override
        public byte[] canonicalBytes() {
            return canonicalBytes.clone();
        }
    }

    public record Receipt(
            String receiptId,
            String activationId,
            String activationManifestHash,
            String tenantSurrogate,
            String caseId,
            String roomType,
            long roomEpoch,
            long roomFencingToken,
            long processRevision,
            long stageSequence,
            String logicalRunId,
            String attemptId,
            String commandHash,
            String commandEnvelopeHash,
            String graphKey,
            String graphVersion,
            String checkpointSchemaVersion,
            String checkpointId,
            String resultHash,
            String proposalHash,
            String resultEnvelopeHash,
            String agentRunManifestId,
            String agentRunManifestHash,
            String isolatedDomainDbBindingHash,
            Instant committedAt,
            String receiptHash,
            byte[] canonicalBytes) {
        public Receipt {
            Objects.requireNonNull(committedAt, "committedAt must not be null");
            Objects.requireNonNull(canonicalBytes, "canonicalBytes must not be null");
            canonicalBytes = canonicalBytes.clone();
            for (String value : new String[] {
                receiptId,
                activationId,
                activationManifestHash,
                tenantSurrogate,
                caseId,
                roomType,
                logicalRunId,
                attemptId,
                commandHash,
                commandEnvelopeHash,
                graphKey,
                graphVersion,
                checkpointSchemaVersion,
                checkpointId,
                resultHash,
                proposalHash,
                resultEnvelopeHash,
                agentRunManifestId,
                agentRunManifestHash,
                isolatedDomainDbBindingHash,
                receiptHash
            }) {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException("receipt bindings must not be blank");
                }
            }
        }

        @Override
        public byte[] canonicalBytes() {
            return canonicalBytes.clone();
        }
    }

    private record PersistedReceipt(
            String receiptId, String receiptHash, Instant committedAt, byte[] canonicalBytes) {
        private PersistedReceipt {
            canonicalBytes = canonicalBytes.clone();
        }

        @Override
        public byte[] canonicalBytes() {
            return canonicalBytes.clone();
        }
    }
}
