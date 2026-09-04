package com.example.dispute.workflow.runtime.artifact.finalization;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.runtime.finalization.ProductionFinalizationReceipt;
import com.example.dispute.workflow.runtime.finalization.ProductionCommandCompletionWriter;
import com.example.dispute.workflow.runtime.finalization.ProductionIntakeOuterFinalizer.CommandCompletionWriter;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.CommandAdmissionSnapshot;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.CommandCompletion;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Completes exactly the pre-admitted command on the target Finalizer transaction.
 *
 * <p>This adapter intentionally never uses the ledger overload that opens or commits its own
 * connection. That would allow a completion marker to outlive a rolled-back domain finalization.
 */
public final class JdbcProductionIntakeCommandCompletionWriter
        implements CommandCompletionWriter, ProductionCommandCompletionWriter {

    private final DataSource dataSource;
    private final ProductionActivationLedger activationLedger;

    public JdbcProductionIntakeCommandCompletionWriter(
            DataSource dataSource, ProductionActivationLedger activationLedger) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.activationLedger = Objects.requireNonNull(activationLedger, "activationLedger");
    }

    @Override
    public void complete(ExecuteAgentRunRequest request, ProductionFinalizationReceipt receipt) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(receipt, "receipt");
        Connection connection = callerOwnedConnection();
        try {
            CommandAdmissionSnapshot admission = activationLedger
                    .queryCommandAdmission(
                            connection, receipt.activationId(), request.command().commandId())
                    .orElseThrow(() -> new IllegalStateException(
                            "target finalization command admission is absent"));
            requireExactAdmission(admission, request, receipt);
            activationLedger.completeCommand(connection, new CommandCompletion(
                    admission.admissionId(),
                    receipt.activationId(),
                    request.command().commandId(),
                    receipt.commandHash(),
                    receipt.commandEnvelopeHash(),
                    receipt.receiptHash()));
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private Connection callerOwnedConnection() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException(
                    "target command completion requires the active writable Finalizer transaction");
        }
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            if (connection.getAutoCommit()) {
                DataSourceUtils.releaseConnection(connection, dataSource);
                throw new IllegalStateException(
                        "target command completion requires the caller-owned Finalizer transaction");
            }
            return connection;
        } catch (SQLException failure) {
            DataSourceUtils.releaseConnection(connection, dataSource);
            throw new IllegalStateException(
                    "target command completion cannot inspect the Finalizer transaction", failure);
        }
    }

    private static void requireExactAdmission(
            CommandAdmissionSnapshot admission,
            ExecuteAgentRunRequest request,
            ProductionFinalizationReceipt receipt) {
        boolean exact = admission.activationId().equals(receipt.activationId())
                && request.command().tenantSurrogate().equals(receipt.tenantSurrogate())
                && request.command().caseId().equals(receipt.caseId())
                && admission.tenantSurrogate().equals(receipt.tenantSurrogate())
                && admission.caseId().equals(receipt.caseId())
                && admission.commandId().equals(request.command().commandId())
                && admission.commandHash().equals(receipt.commandHash())
                && admission.commandEnvelopeHash().equals(receipt.commandEnvelopeHash())
                && request.command().roomEpoch() == receipt.roomEpoch()
                && admission.roomEpoch() == receipt.roomEpoch()
                && admission.roomFencingToken() == receipt.roomFencingToken();
        if (!exact) {
            throw new IllegalStateException(
                    "target finalization completion conflicts with the admitted command identity");
        }
    }
}
