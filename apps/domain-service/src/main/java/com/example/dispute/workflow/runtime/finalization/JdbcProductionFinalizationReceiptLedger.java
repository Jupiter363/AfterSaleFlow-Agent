package com.example.dispute.workflow.runtime.finalization;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationReceipt.DomainCommitStatus;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationReceipt.FormalWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** V047 adapter preserving the original COMMITTED receipt bytes on every replay. */
public final class JdbcProductionFinalizationReceiptLedger
        implements ProductionFinalizationReceiptLedger {

    private static final String INSERT = """
            insert into production_runtime_finalization_receipt (
                receipt_id, schema_version, execution_lane, activation_id,
                activation_manifest_hash, tenant_surrogate, case_id, room_type,
                room_epoch, room_fencing_token, process_revision, stage_sequence,
                logical_run_id, attempt_id, command_hash, command_envelope_hash,
                graph_key, graph_version, checkpoint_schema_version, checkpoint_id,
                result_hash, proposal_hash, result_envelope_hash, agent_run_manifest_id,
                agent_run_manifest_hash, isolated_domain_db_binding_hash, committed_at,
                receipt_hash, receipt_canonical_bytes, formal_writer, domain_commit_status
            ) values (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?
            ) on conflict do nothing
            """;

    private static final String SELECT = """
            select receipt_id, schema_version, execution_lane, activation_id,
                   activation_manifest_hash, tenant_surrogate, case_id, room_type,
                   room_epoch, room_fencing_token, process_revision, stage_sequence,
                   logical_run_id, attempt_id, command_hash, command_envelope_hash,
                   graph_key, graph_version, checkpoint_schema_version, checkpoint_id,
                   result_hash, proposal_hash, result_envelope_hash, agent_run_manifest_id,
                   agent_run_manifest_hash, isolated_domain_db_binding_hash, committed_at,
                   receipt_hash, receipt_canonical_bytes, formal_writer, domain_commit_status
              from production_runtime_finalization_receipt
             where activation_id = ? and logical_run_id = ?
            """;

    private final DataSource dataSource;

    public JdbcProductionFinalizationReceiptLedger(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public Optional<StoredReceipt> find(String activationId, String logicalRunId) {
        Connection connection = transactionalConnection();
        try {
            return Optional.ofNullable(find(connection, activationId, logicalRunId));
        } catch (SQLException failure) {
            throw persistence("target receipt lookup failed", failure);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    @Override
    public StoredReceipt append(AppendCommand command) {
        Objects.requireNonNull(command, "command");
        Connection connection = transactionalConnection();
        try {
            insert(connection, command);
            StoredReceipt persisted = find(
                    connection,
                    command.receipt().activationId(),
                    command.receipt().logicalRunId());
            if (persisted == null) {
                throw conflict("receipt append produced no durable row");
            }
            ProductionFinalizationReceiptLedger.requireExact(persisted, command);
            return persisted;
        } catch (SQLException failure) {
            throw persistence("target receipt append failed", failure);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private Connection transactionalConnection() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException(
                    "target receipt requires an active writable Java Finalizer transaction");
        }
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            if (connection.getAutoCommit()) {
                DataSourceUtils.releaseConnection(connection, dataSource);
                throw new IllegalStateException(
                        "target receipt requires the caller-owned Java Finalizer transaction");
            }
            return connection;
        } catch (SQLException failure) {
            DataSourceUtils.releaseConnection(connection, dataSource);
            throw persistence("cannot inspect target receipt transaction", failure);
        }
    }

    private static void insert(Connection connection, AppendCommand command) throws SQLException {
        ProductionFinalizationReceipt receipt = command.receipt();
        byte[] canonicalBytes = ProductionFinalizationReceiptCodec.canonicalBytes(receipt);
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            int index = 1;
            statement.setString(index++, receiptId(receipt));
            statement.setString(index++, receipt.schemaVersion());
            statement.setString(index++, receipt.executionLane());
            statement.setString(index++, receipt.activationId());
            statement.setString(index++, command.activationManifestHash());
            statement.setString(index++, receipt.tenantSurrogate());
            statement.setString(index++, receipt.caseId());
            statement.setString(index++, receipt.roomType().name());
            statement.setLong(index++, receipt.roomEpoch());
            statement.setLong(index++, receipt.roomFencingToken());
            statement.setLong(index++, receipt.processRevision());
            statement.setLong(index++, receipt.stageSequence());
            statement.setString(index++, receipt.logicalRunId());
            statement.setString(index++, receipt.attemptId());
            statement.setString(index++, receipt.commandHash());
            statement.setString(index++, receipt.commandEnvelopeHash());
            statement.setString(index++, receipt.graphKey());
            statement.setString(index++, receipt.graphVersion());
            statement.setString(index++, receipt.checkpointSchemaVersion());
            statement.setString(index++, receipt.checkpointId());
            statement.setString(index++, receipt.resultHash());
            statement.setString(index++, receipt.proposalHash());
            statement.setString(index++, receipt.resultEnvelopeHash());
            statement.setString(index++, receipt.agentRunManifestId());
            statement.setString(index++, receipt.agentRunManifestHash());
            statement.setString(index++, receipt.isolatedDomainDbBindingHash());
            statement.setTimestamp(index++, Timestamp.from(receipt.committedAt()));
            statement.setString(index++, receipt.receiptHash());
            statement.setBytes(index++, canonicalBytes);
            statement.setString(index++, receipt.formalWriter().name());
            statement.setString(index, receipt.domainCommitStatus().name());
            statement.executeUpdate();
        }
    }

    private static StoredReceipt find(
            Connection connection, String activationId, String logicalRunId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT)) {
            statement.setString(1, activationId);
            statement.setString(2, logicalRunId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                byte[] canonicalBytes = result.getBytes("receipt_canonical_bytes");
                ProductionFinalizationReceipt decoded =
                        ProductionFinalizationReceiptCodec.decodeCanonical(canonicalBytes);
                ProductionFinalizationReceipt columns = receipt(result);
                if (!decoded.equals(columns)) {
                    throw conflict("receipt columns differ from original canonical bytes");
                }
                StoredReceipt stored = new StoredReceipt(
                        result.getString("receipt_id"),
                        result.getString("activation_manifest_hash"),
                        decoded,
                        canonicalBytes);
                if (result.next()) {
                    throw conflict("receipt identity is not unique");
                }
                return stored;
            }
        }
    }

    private static ProductionFinalizationReceipt receipt(ResultSet result) throws SQLException {
        return new ProductionFinalizationReceipt(
                result.getString("schema_version"),
                result.getString("execution_lane"),
                result.getString("activation_id"),
                result.getString("tenant_surrogate"),
                result.getString("case_id"),
                RoomType.valueOf(result.getString("room_type")),
                result.getLong("room_epoch"),
                result.getLong("room_fencing_token"),
                result.getLong("process_revision"),
                result.getLong("stage_sequence"),
                result.getString("logical_run_id"),
                result.getString("attempt_id"),
                result.getString("command_hash"),
                result.getString("command_envelope_hash"),
                result.getString("graph_key"),
                result.getString("graph_version"),
                result.getString("checkpoint_schema_version"),
                result.getString("checkpoint_id"),
                result.getString("result_hash"),
                result.getString("proposal_hash"),
                result.getString("result_envelope_hash"),
                result.getString("agent_run_manifest_id"),
                result.getString("agent_run_manifest_hash"),
                result.getString("isolated_domain_db_binding_hash"),
                result.getTimestamp("committed_at").toInstant(),
                result.getString("receipt_hash"),
                FormalWriter.valueOf(result.getString("formal_writer")),
                DomainCommitStatus.valueOf(result.getString("domain_commit_status")));
    }

    private static String receiptId(ProductionFinalizationReceipt receipt) {
        String identity = receipt.activationId() + ':' + receipt.logicalRunId();
        return "p9fin.v1." + sha256(identity).substring(0, 32);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static ProductionFinalizationRejectedException conflict(String message) {
        return new ProductionFinalizationRejectedException(
                "PRODUCTION_RUNTIME_FINALIZATION_RECEIPT_CONFLICT", message);
    }

    private static ProductionFinalizationReceiptPersistenceException persistence(
            String message, SQLException failure) {
        String state = failure.getSQLState();
        boolean retryable = state != null && (state.startsWith("08") || state.startsWith("40"));
        return new ProductionFinalizationReceiptPersistenceException(
                message, failure, retryable);
    }
}
