package com.example.dispute.workflow.targete2e.artifact.finalization;

import com.example.dispute.agentstream.application.AgentRunV2StreamStore;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Persists the exact durable FINAL-result reference as the AgentRun output snapshot.
 *
 * <p>The target finalizer cannot use a URI carried by an in-memory graph result: only the
 * append-validated terminal stream record is an admissible output source. The caller executes
 * its finalization callback inside this same transaction, so the state reader observes the
 * snapshot without creating a separately committed provenance row.
 */
public final class TargetE2eGraphOutputSnapshotMaterializer {

    private static final String LOCK_RUN_SQL = """
            select id
              from agent_run
             where id = :agentRunId
             for update
            """;

    private static final String LOAD_SQL = """
            select id, room_type, snapshot_type, source_type, source_id, schema_version,
                   object_uri, object_version, content_sha256, size_bytes, content_type,
                   visibility, created_by
              from immutable_payload_snapshot
             where tenant_surrogate = :tenantSurrogate
               and source_type = 'AGENT_RUN'
               and source_id = :agentRunId
             for update
            """;

    private static final String INSERT_SQL = """
            insert into immutable_payload_snapshot (
                id, tenant_surrogate, case_id, room_type, snapshot_type,
                source_type, source_id, schema_version, object_uri, object_version,
                content_sha256, size_bytes, content_type, visibility,
                legal_hold, created_at, created_by
            ) values (
                :id, :tenantSurrogate, :caseId, :roomType, 'AGENT_OUTPUT',
                'AGENT_RUN', :agentRunId, :schemaVersion, :resultRef, null,
                :resultHash, 0, 'application/json', 'INTERNAL',
                false, now(), 'target-e2e-agent-output-materializer'
            )
            """;

    private static final String CREATED_BY = "target-e2e-agent-output-materializer";
    private static final Set<String> ALLOWED_URI_SCHEMES = Set.of("s3", "minio", "urn");

    private final NamedParameterJdbcTemplate jdbc;
    private final AgentRunV2StreamStore streamStore;
    private final TransactionTemplate transactions;

    public TargetE2eGraphOutputSnapshotMaterializer(
            DataSource dataSource,
            AgentRunV2StreamStore streamStore,
            PlatformTransactionManager transactionManager) {
        this.jdbc = new NamedParameterJdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
        this.streamStore = Objects.requireNonNull(streamStore, "streamStore");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(
                transactionManager, "transactionManager"));
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** Materializes the output then invokes finalization under the same writable transaction. */
    public <T> T materializeThen(
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            TransactionalFinalization<T> finalization) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(finalization, "finalization");
        T value = transactions.execute(ignored -> {
            materialize(request, result);
            return finalization.finalizeResult();
        });
        return Objects.requireNonNull(value, "target finalization returned null");
    }

    /**
     * Materializes under the caller's already-active target finalization transaction.
     *
     * <p>The multi-room gateway uses this method so the stream-derived snapshot, domain commit,
     * target receipt, and admission completion share one physical transaction.
     */
    public void materializeInActiveTransaction(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException(
                    "target output snapshot requires the active writable Finalizer transaction");
        }
        materialize(request, result);
    }

    private void materialize(ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
        if (result.outcome() != ExecuteAgentRunResult.Outcome.COMPLETED
                || result.graphResult() == null
                || !request.agentRunId().equals(result.agentRunId())
                || !request.logicalRunId().equals(result.logicalRunId())
                || !request.attemptId().equals(result.attemptId())
                || request.attemptNo() != result.attemptNo()
                || !result.resultHash().equals(result.graphResult().outputHash())) {
            throw new IllegalArgumentException("target graph output snapshot input is invalid");
        }
        lockLogicalRun(request.agentRunId());
        String resultRef = durableFinalResultRef(request, result);
        Map<String, ?> parameters = Map.of(
                "id", snapshotId(request.agentRunId(), result.resultHash()),
                "tenantSurrogate", request.command().tenantSurrogate(),
                "caseId", request.command().caseId(),
                "roomType", request.command().roomType().name(),
                "agentRunId", request.agentRunId(),
                "schemaVersion", result.graphResult().schemaVersion(),
                "resultRef", resultRef,
                "resultHash", result.resultHash());
        List<SnapshotRow> existing = jdbc.query(LOAD_SQL, parameters, (rs, ignored) -> new SnapshotRow(
                rs.getString("id"),
                rs.getString("room_type"),
                rs.getString("snapshot_type"),
                rs.getString("source_type"),
                rs.getString("source_id"),
                rs.getString("schema_version"),
                rs.getString("object_uri"),
                rs.getString("object_version"),
                rs.getString("content_sha256"),
                rs.getLong("size_bytes"),
                rs.getString("content_type"),
                rs.getString("visibility"),
                rs.getString("created_by")));
        if (existing.isEmpty()) {
            if (jdbc.update(INSERT_SQL, parameters) != 1) {
                throw new IllegalStateException("target AgentRun graph output snapshot was not inserted");
            }
            return;
        }
        if (existing.size() != 1 || !existing.getFirst().matches(parameters)) {
            throw new IllegalStateException("target AgentRun graph output snapshot conflicts with replay");
        }
    }

    private void lockLogicalRun(String agentRunId) {
        List<String> rows = jdbc.query(
                LOCK_RUN_SQL, Map.of("agentRunId", agentRunId), (rs, ignored) -> rs.getString("id"));
        if (rows.size() != 1 || !agentRunId.equals(rows.getFirst())) {
            throw new IllegalStateException("target AgentRun is absent or ambiguous");
        }
    }

    private String durableFinalResultRef(ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
        long previous = Math.subtractExact(result.lastSequenceNo(), 1L);
        List<AgentStreamEvent> events = streamStore.replay(
                request.agentRunId(), request.attemptId(), previous, 2);
        if (events.size() != 1) {
            throw new IllegalStateException("target AgentRun durable final is absent or ambiguous");
        }
        AgentStreamEvent terminal = events.getFirst();
        if (terminal.eventType() != StreamEventType.FINAL
                || terminal.sequenceNo() != result.lastSequenceNo()
                || !request.agentRunId().equals(terminal.runId())
                || !request.attemptId().equals(terminal.attemptId())
                || request.command().actorScope().audience() != terminal.audience()
                || terminal.payload() == null
                || !result.resultHash().equals(terminal.payload().finalResultHash())) {
            throw new IllegalStateException("target AgentRun durable final conflicts with completed result");
        }
        String resultRef = terminal.payload().finalResultRef();
        if (!immutableUri(resultRef)) {
            throw new IllegalStateException("target AgentRun durable final has an invalid result reference");
        }
        return resultRef;
    }

    private static boolean immutableUri(String value) {
        if (value == null || value.isBlank() || value.length() > 1024) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return uri.isAbsolute()
                    && ALLOWED_URI_SCHEMES.contains(uri.getScheme())
                    && uri.getRawSchemeSpecificPart() != null
                    && !uri.getRawSchemeSpecificPart().isBlank();
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private static String snapshotId(String agentRunId, String resultHash) {
        String hash = ContractJson.sha256Hex(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode()
                        .add(agentRunId)
                        .add(resultHash));
        return "tgo_" + hash.substring(0, 60);
    }

    @FunctionalInterface
    public interface TransactionalFinalization<T> {
        T finalizeResult();
    }

    private record SnapshotRow(
            String id,
            String roomType,
            String snapshotType,
            String sourceType,
            String sourceId,
            String schemaVersion,
            String objectUri,
            String objectVersion,
            String contentSha256,
            long sizeBytes,
            String contentType,
            String visibility,
            String createdBy) {

        private boolean matches(Map<String, ?> expected) {
            return Objects.equals(id, expected.get("id"))
                    && Objects.equals(roomType, expected.get("roomType"))
                    && "AGENT_OUTPUT".equals(snapshotType)
                    && "AGENT_RUN".equals(sourceType)
                    && Objects.equals(sourceId, expected.get("agentRunId"))
                    && Objects.equals(schemaVersion, expected.get("schemaVersion"))
                    && Objects.equals(objectUri, expected.get("resultRef"))
                    && objectVersion == null
                    && Objects.equals(contentSha256, expected.get("resultHash"))
                    && sizeBytes == 0
                    && "application/json".equals(contentType)
                    && "INTERNAL".equals(visibility)
                    && CREATED_BY.equals(createdBy);
        }
    }
}
