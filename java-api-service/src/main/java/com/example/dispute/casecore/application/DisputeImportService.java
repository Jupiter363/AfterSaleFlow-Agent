package com.example.dispute.casecore.application;

import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Facade for direct and simulated external imports.
 *
 * <p>A fair process-wide gate is acquired before the transactional bean, so
 * waiting never consumes a database transaction.
 */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class DisputeImportService {

    private final ExternalCaseImportTransactionService transactionService;
    private final SingleInstanceImportGate importGate;

    public DisputeImportService(
            ExternalCaseImportTransactionService transactionService,
            SingleInstanceImportGate importGate) {
        this.transactionService = transactionService;
        this.importGate = importGate;
    }

    public ImportedDisputeView importDispute(
            ImportDisputeCommand command,
            AuthenticatedActor actor,
            String idempotencyKey) {
        return importOne(
                command,
                actor,
                idempotencyKey,
                directImportTraceId(idempotencyKey),
                directImportRequestId(idempotencyKey));
    }

    public ImportedDisputeView importDispute(
            ImportDisputeCommand command,
            AuthenticatedActor actor,
            String idempotencyKey,
            String traceId,
            String requestId) {
        return importOne(command, actor, idempotencyKey, traceId, requestId);
    }

    public SimulatedImportResultView simulateExternalImport(
            SimulateExternalImportCommand command,
            AuthenticatedActor actor,
            String idempotencyKey,
            String traceId,
            String requestId) {
        if (actor.role() != ActorRole.SYSTEM) {
            throw new SecurityException("external dispute simulation requires service identity");
        }
        requireText(idempotencyKey, "idempotencyKey");
        return importGate.execute(
                () ->
                        transactionService.simulateExternalImport(
                                command, actor, idempotencyKey, traceId, requestId));
    }

    private ImportedDisputeView importOne(
            ImportDisputeCommand command,
            AuthenticatedActor actor,
            String idempotencyKey,
            String traceId,
            String requestId) {
        return importGate.execute(
                () ->
                        transactionService.importDispute(
                                command, actor, idempotencyKey, traceId, requestId));
    }

    private static String directImportTraceId(String idempotencyKey) {
        return "direct-import-trace-" + compactImportKey(idempotencyKey);
    }

    private static String directImportRequestId(String idempotencyKey) {
        return "direct-import-request-" + compactImportKey(idempotencyKey);
    }

    private static String compactImportKey(String value) {
        String normalized =
                value == null || value.isBlank()
                        ? UUID.randomUUID().toString().replace("-", "")
                        : value.replaceAll("[^A-Za-z0-9_.:-]", "-");
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
