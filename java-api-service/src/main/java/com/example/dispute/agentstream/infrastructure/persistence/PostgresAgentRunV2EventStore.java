package com.example.dispute.agentstream.infrastructure.persistence;

import com.example.dispute.agentstream.application.AgentRunReconciledFinalStore;
import com.example.dispute.agentstream.application.AgentRunStreamCursor;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore.BatchAppendReceipt;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore.CompatibilityReport;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore.NonRunningAttemptException;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL source of truth for attempt-scoped {@code agent-stream.v2} events. */
@Repository
public class PostgresAgentRunV2EventStore {

    private static final String INSERT_SQL =
            """
            insert into agent_run_stream_event (
                id, agent_run_id, agent_run_attempt_id, sequence_no,
                event_type, payload_json, created_at, created_by,
                stream_protocol, audience, payload_hash
            ) values (?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, 'agent-stream.v2', ?, ?)
            on conflict (agent_run_id, agent_run_attempt_id, sequence_no)
                where stream_protocol = 'agent-stream.v2'
            do nothing
            """;

    private static final String HASHES_SQL =
            """
            select sequence_no, payload_hash
              from agent_run_stream_event
             where agent_run_id = :runId
               and agent_run_attempt_id = :attemptId
               and stream_protocol = 'agent-stream.v2'
               and sequence_no in (:sequences)
            """;

    private static final String REPLAY_SQL =
            """
            select sequence_no, event_type, audience, payload_hash, payload_json::text
              from agent_run_stream_event
             where agent_run_id = ?
               and agent_run_attempt_id = ?
               and stream_protocol = 'agent-stream.v2'
               and sequence_no > ?
             order by sequence_no asc
             limit ?
            """;

    private static final String HIGH_WATERMARK_SQL =
            """
            select coalesce(max(sequence_no), -1)
              from agent_run_stream_event
             where agent_run_id = ?
               and agent_run_attempt_id = ?
               and stream_protocol = 'agent-stream.v2'
            """;

    private static final String TARGET_REPLAY_SQL =
            """
            select delivery.sequence_no, delivery.event_type, delivery.audience,
                   delivery.canonical_payload_sha256 as payload_hash,
                   delivery.payload_json::text
              from agent_run_stream_event_delivery delivery
              join agent_run_stream_delivery_high_watermark watermark
                on watermark.stream_protocol = delivery.stream_protocol
               and watermark.agent_run_id = delivery.agent_run_id
               and watermark.agent_run_attempt_id = delivery.agent_run_attempt_id
             where delivery.agent_run_id = ?
               and delivery.agent_run_attempt_id = ?
               and delivery.stream_protocol = 'agent-stream.v2'
               and delivery.sequence_no > ?
               and delivery.sequence_no <= watermark.highest_contiguous_sequence_no
             order by delivery.sequence_no asc
             limit ?
            """;

    private static final String TARGET_HIGH_WATERMARK_SQL =
            """
            select highest_contiguous_sequence_no
              from agent_run_stream_delivery_high_watermark
             where stream_protocol = 'agent-stream.v2'
               and agent_run_id = ?
               and agent_run_attempt_id = ?
            """;

    private static final String OLD_DELIVERY_SQL =
            """
            select event.id, event.sequence_no, event.event_type,
                   event.payload_json::text, event.payload_hash, event.audience,
                   event.created_at, run.created_by as actor_id,
                   run.stream_audience_actor_ids_json::text as audience_actor_ids_json
              from agent_run_stream_event event
              join agent_run run on run.id = event.agent_run_id
             where event.agent_run_id = :runId
               and event.agent_run_attempt_id = :attemptId
               and event.stream_protocol = 'agent-stream.v2'
               and event.sequence_no in (:sequences)
            """;

    private static final String RECORD_TARGET_SQL =
            """
            select was_inserted, highest_contiguous_sequence_no
              from record_agent_run_stream_delivery(
                   ?, 'agent-stream.v2', ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?,
                   cast(? as jsonb), ?, 'agent_run_stream_event',
                   'agent-stream-v2-dual-write')
            """;

    private static final String SOURCE_COMPATIBILITY_SQL =
            """
            select event.id as event_id, event.stream_protocol,
                   event.agent_run_id, event.agent_run_attempt_id,
                   event.sequence_no, event.event_type, event.payload_json::text,
                   event.payload_hash, event.audience, event.created_at,
                   run.created_by as actor_id,
                   run.stream_audience_json::text as audience_roles_json,
                   run.stream_audience_actor_ids_json::text as audience_actor_ids_json
              from agent_run_stream_event event
              join agent_run run on run.id = event.agent_run_id
             where event.stream_protocol = ?
               and event.agent_run_id = ?
               and event.agent_run_attempt_id = ?
             order by event.sequence_no asc
            """;

    private static final String TARGET_COMPATIBILITY_SQL =
            """
            select delivery.event_id, delivery.stream_protocol,
                   delivery.agent_run_id, delivery.agent_run_attempt_id,
                   delivery.sequence_no, delivery.event_type,
                   delivery.payload_json::text,
                   delivery.canonical_payload_sha256 as payload_hash,
                   delivery.audience, delivery.source_event_created_at as created_at,
                   delivery.actor_id,
                   run.stream_audience_json::text as audience_roles_json,
                   delivery.audience_actor_ids_json::text
              from agent_run_stream_event_delivery delivery
              join agent_run run on run.id = delivery.agent_run_id
             where delivery.stream_protocol = ?
               and delivery.agent_run_id = ?
               and delivery.agent_run_attempt_id = ?
             order by delivery.sequence_no asc
            """;

    private static final String LOCK_ATTEMPT_SQL =
            """
            select attempt_status
              from agent_run_attempt
             where agent_run_id = ? and id = ?
             for update
            """;

    private static final String ATTEMPT_EVENTS_SQL =
            """
            select sequence_no, event_type, audience, payload_hash, payload_json::text
              from agent_run_stream_event
             where agent_run_id = ?
               and agent_run_attempt_id = ?
               and stream_protocol = 'agent-stream.v2'
             order by sequence_no asc
            """;

    private static final String LAST_EVENT_TYPE_SQL =
            """
            select event_type
              from agent_run_stream_event
             where agent_run_id = ?
               and agent_run_attempt_id = ?
               and stream_protocol = 'agent-stream.v2'
             order by sequence_no desc
            limit 1
            """;

    private static final String PROJECT_ATTEMPT_PROGRESS_SQL =
            """
            update agent_run_attempt
               set last_sequence_no = greatest(last_sequence_no, ?),
                   public_output_emitted = public_output_emitted or ?,
                   final_frame_observed = final_frame_observed or ?,
                   updated_at = greatest(updated_at, clock_timestamp()),
                   attempt_version = attempt_version + 1
             where agent_run_id = ?
               and id = ?
               and (
                    last_sequence_no < ?
                    or (? and not public_output_emitted)
                    or (? and not final_frame_observed)
               )
            """;

    private static final String RETENTION_MANIFEST_SQL =
            """
            select event.sequence_no,
                   event.event_type,
                   event.audience,
                   event.payload_hash,
                   event.payload_json::text,
                   manifest.id as manifest_id,
                   manifest.manifest_sha256,
                   manifest.output_sha256,
                   manifest.finalized_at
              from agent_run run
              join agent_execution_manifest manifest
                on manifest.id = run.committed_manifest_id
               and manifest.logical_agent_run_id = run.id
               and manifest.attempt_id = run.committed_attempt_id
               and manifest.manifest_sha256 = run.committed_manifest_hash
               and manifest.output_sha256 = run.final_result_hash
               and manifest.terminal_status = 'COMPLETED'
              join agent_run_stream_event event
                on event.agent_run_id = run.id
               and event.agent_run_attempt_id = run.committed_attempt_id
               and event.sequence_no = run.final_stream_sequence_no
               and event.stream_protocol = 'agent-stream.v2'
               and event.event_type = 'final'
             where run.id = ?
               and run.committed_attempt_id = ?
               and run.finalization_status = 'COMMITTED'
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate writeTransaction;
    private final StreamCompatibilityMode compatibilityMode;

    @Autowired
    public PostgresAgentRunV2EventStore(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this(jdbc, objectMapper, transactionManager, StreamCompatibilityMode.defaultMode());
    }

    public PostgresAgentRunV2EventStore(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            StreamCompatibilityMode compatibilityMode) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        this.objectMapper =
                Objects.requireNonNull(objectMapper, "objectMapper must not be null").copy();
        this.objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.compatibilityMode = Objects.requireNonNull(compatibilityMode, "compatibilityMode");
    }

    public AgentRunV2StreamStore.AppendReceipt append(AgentStreamEvent event) {
        BatchAppendReceipt batch = appendBatch(List.of(requireEvent(event)));
        return new AgentRunV2StreamStore.AppendReceipt(
                batch.inserted().getFirst(), batch.durableHighWatermark());
    }

    /**
     * Appends one coalesced batch. A batch is deliberately scoped to one run attempt so its
     * high-watermark is unambiguous and can be used as a wake-up hint.
     */
    public BatchAppendReceipt appendBatch(List<AgentStreamEvent> events) {
        List<PersistedEvent> batch = prepareBatch(events);
        BatchAppendReceipt receipt =
                writeTransaction.execute(status -> appendInTransaction(batch));
        if (receipt == null) {
            throw new IllegalStateException("durable stream transaction returned no receipt");
        }
        return receipt;
    }

    /**
     * Appends Java recovery's single global error inside the caller's transaction.
     *
     * <p>This entry point is intentionally separate from the public stream append, whose
     * {@code REQUIRES_NEW} durability boundary must not outlive a rolled-back recovery ledger
     * transition.
     */
    public AgentRunV2StreamStore.AppendReceipt appendRecoveryErrorInCurrentTransaction(
            AgentStreamEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "recovery error append requires an actual caller transaction");
        }
        AgentStreamEvent required = requireEvent(event);
        if (required.eventType() != StreamEventType.ERROR) {
            throw new IllegalArgumentException(
                    "recovery error append accepts exactly one ERROR event");
        }
        if (compatibilityMode.writer() == StreamCompatibilityMode.Writer.TARGET_ONLY) {
            throw new IllegalStateException(
                    "target-only stream writes require a separately authorized release switch");
        }
        List<PersistedEvent> batch = prepareBatch(List.of(required));
        AgentRunAttemptStatus attemptStatus = lockAttempt(required.runId(), required.attemptId());
        if (!recoveryErrorAppendStatus(attemptStatus)) {
            throw new NonRunningAttemptException(attemptStatus);
        }
        BatchAppendReceipt appended = appendLocked(batch);
        return new AgentRunV2StreamStore.AppendReceipt(
                appended.inserted().getFirst(), appended.durableHighWatermark());
    }

    public AgentRunReconciledFinalStore.Receipt appendOrLoadReconciledFinal(
            AgentRunReconciledFinalStore.Request request) {
        Objects.requireNonNull(request, "request");
        AgentRunReconciledFinalStore.Receipt receipt = writeTransaction.execute(
                status -> appendOrLoadReconciledFinalInTransaction(request));
        if (receipt == null) {
            throw new IllegalStateException("reconciled final transaction returned no receipt");
        }
        return receipt;
    }

    public List<AgentStreamEvent> replay(
            String runId, String attemptId, long afterSequence, int limit) {
        requireIdentity(runId, "runId");
        requireIdentity(attemptId, "attemptId");
        if (afterSequence < -1) {
            throw new IllegalArgumentException("afterSequence must be at least -1");
        }
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        List<AgentStreamEvent> oldEvents = jdbc.query(
                REPLAY_SQL,
                (resultSet, rowNumber) ->
                        decodeAndVerify(
                                runId,
                                attemptId,
                                resultSet.getLong("sequence_no"),
                                resultSet.getString("event_type"),
                                resultSet.getString("audience"),
                                resultSet.getString("payload_hash"),
                                resultSet.getString("payload_json")),
                runId,
                attemptId,
                afterSequence,
                limit);
        if (compatibilityMode.reader() == StreamCompatibilityMode.Reader.OLD_ONLY) {
            return oldEvents;
        }
        List<AgentStreamEvent> targetEvents = replayTarget(
                runId, attemptId, afterSequence, limit);
        if (compatibilityMode.reader() == StreamCompatibilityMode.Reader.TARGET_ONLY) {
            validateCompatibility("agent-stream.v2", runId, attemptId).requireCompatible();
            return targetEvents;
        }
        return compatibleUnion(oldEvents, targetEvents, limit);
    }

    /** Returns the PostgreSQL high-watermark, or {@code -1} when the attempt has no events. */
    public long durableHighWatermark(String runId, String attemptId) {
        requireIdentity(runId, "runId");
        requireIdentity(attemptId, "attemptId");
        Long value =
                jdbc.queryForObject(HIGH_WATERMARK_SQL, Long.class, runId, attemptId);
        long oldHighWatermark = value == null ? -1 : value;
        if (compatibilityMode.reader() == StreamCompatibilityMode.Reader.OLD_ONLY) {
            return oldHighWatermark;
        }
        List<Long> target = jdbc.query(
                TARGET_HIGH_WATERMARK_SQL,
                (resultSet, rowNumber) -> resultSet.getLong(1),
                runId,
                attemptId);
        long targetHighWatermark = target.isEmpty() ? -1 : target.getFirst();
        if (target.size() > 1) {
            throw new IllegalStateException("target delivery high-watermark is ambiguous");
        }
        return compatibilityMode.reader() == StreamCompatibilityMode.Reader.TARGET_ONLY
                ? targetHighWatermark
                : Math.max(oldHighWatermark, targetHighWatermark);
    }

    public CompatibilityReport validateCompatibility(
            String streamProtocol, String runId, String attemptId) {
        requireIdentity(streamProtocol, "streamProtocol");
        requireIdentity(runId, "runId");
        requireIdentity(attemptId, "attemptId");
        if (!Set.of("agent_stream.v1", "agent-stream.v2").contains(streamProtocol)) {
            throw new IllegalArgumentException("unsupported streamProtocol");
        }
        CompatibilityReport report = writeTransaction.execute(status ->
                validateCompatibilityInTransaction(streamProtocol, runId, attemptId));
        if (report == null) {
            throw new IllegalStateException("compatibility transaction returned no report");
        }
        return report;
    }

    /**
     * Derives target-aware rollback coverage from authoritative rows. Unlike pre-switch parity,
     * this deliberately permits target-only suffixes while requiring exact conflict-free overlap.
     */
    public StreamCompatibilityMode.RollbackCoverage validateRollbackCoverage(
            String streamProtocol, String runId, String attemptId) {
        requireIdentity(streamProtocol, "streamProtocol");
        requireIdentity(runId, "runId");
        requireIdentity(attemptId, "attemptId");
        StreamCompatibilityMode.RollbackCoverage coverage = writeTransaction.execute(status -> {
            List<CompatibilityRow> source = loadCompatibilityRows(
                    SOURCE_COMPATIBILITY_SQL, true, streamProtocol, runId, attemptId);
            List<CompatibilityRow> target = loadCompatibilityRows(
                    TARGET_COMPATIBILITY_SQL, false, streamProtocol, runId, attemptId);
            Map<String, CompatibilityRow> sourceByIdentity = new LinkedHashMap<>();
            for (CompatibilityRow row : source) {
                sourceByIdentity.put(row.eventId(), row);
            }
            Map<Long, CompatibilityRow> union = new TreeMap<>();
            boolean overlapExact = true;
            for (CompatibilityRow row : source) {
                union.put(row.sequenceNo(), row);
            }
            for (CompatibilityRow row : target) {
                CompatibilityRow sourceRow = sourceByIdentity.get(row.eventId());
                if (sourceRow != null && !compatibilityEquivalent(sourceRow, row)) {
                    overlapExact = false;
                }
                CompatibilityRow sequenceRow = union.putIfAbsent(row.sequenceNo(), row);
                if (sequenceRow != null && !compatibilityEquivalent(sequenceRow, row)) {
                    overlapExact = false;
                }
            }
            boolean coversEveryOldEvent = source.stream().allMatch(old ->
                    target.stream().anyMatch(candidate ->
                            compatibilityEquivalent(old, candidate)));
            boolean targetOnlyWriteObserved = target.stream()
                    .anyMatch(row -> !sourceByIdentity.containsKey(row.eventId()));
            List<CompatibilityRow> unionRows = List.copyOf(union.values());
            boolean unionContiguous = (unionRows.isEmpty()
                            || unionRows.getFirst().sequenceNo() == 0)
                    && terminalOrderValid(unionRows);
            long unionMaximum = union.isEmpty() ? -1 : union.keySet().stream()
                    .mapToLong(Long::longValue)
                    .max()
                    .orElse(-1);
            long targetWatermark = targetHighWatermark(streamProtocol, runId, attemptId);
            boolean cursorStable = union.values().stream()
                            .map(CompatibilityRow::compositeCursor)
                            .distinct()
                            .count()
                    == union.size();
            return new StreamCompatibilityMode.RollbackCoverage(
                    source.size(),
                    target.size(),
                    targetOnlyWriteObserved,
                    overlapExact && coversEveryOldEvent,
                    unionContiguous,
                    targetWatermark == unionMaximum,
                    cursorStable);
        });
        if (coverage == null) {
            throw new IllegalStateException("rollback coverage transaction returned no report");
        }
        return coverage;
    }

    /**
     * Projects the immutable terminal/AgentExecutionManifest evidence used by the later partition
     * cleanup workflow. Until Phase 8 records compaction or archive verification, the returned
     * manifest intentionally rejects deletion.
     */
    public Optional<AgentRunStreamRetentionManifest> retentionManifest(
            String runId, String attemptId) {
        requireIdentity(runId, "runId");
        requireIdentity(attemptId, "attemptId");
        List<AgentRunStreamRetentionManifest> manifests =
                jdbc.query(
                        RETENTION_MANIFEST_SQL,
                        (resultSet, rowNumber) -> {
                            long terminalSequence = resultSet.getLong("sequence_no");
                            String terminalPayloadHash = resultSet.getString("payload_hash");
                            AgentStreamEvent terminal =
                                    decodeAndVerify(
                                            runId,
                                            attemptId,
                                            terminalSequence,
                                            resultSet.getString("event_type"),
                                            resultSet.getString("audience"),
                                            terminalPayloadHash,
                                            resultSet.getString("payload_json"));
                            String outputHash = resultSet.getString("output_sha256");
                            if (!Objects.equals(
                                    terminal.payload().finalResultHash(), outputHash)) {
                                throw new IllegalStateException(
                                        "terminal event conflicts with the formal output hash");
                            }
                            java.time.Instant finalizedAt =
                                    resultSet.getTimestamp("finalized_at").toInstant();
                            return new AgentRunStreamRetentionManifest(
                                    runId,
                                    attemptId,
                                    terminalSequence,
                                    terminalPayloadHash,
                                    resultSet.getString("manifest_id"),
                                    resultSet.getString("manifest_sha256"),
                                    finalizedAt,
                                    finalizedAt.plusSeconds(24 * 60 * 60),
                                    false,
                                    false);
                        },
                        runId,
                        attemptId);
        if (manifests.size() > 1) {
            throw new IllegalStateException("retention manifest identity is ambiguous");
        }
        return manifests.stream().findFirst();
    }

    private BatchAppendReceipt appendInTransaction(List<PersistedEvent> batch) {
        if (compatibilityMode.writer() == StreamCompatibilityMode.Writer.TARGET_ONLY) {
            throw new IllegalStateException(
                    "target-only stream writes require a separately authorized release switch");
        }
        AgentStreamEvent first = batch.getFirst().event();
        AgentRunAttemptStatus attemptStatus = lockAttempt(first.runId(), first.attemptId());
        if (attemptStatus != AgentRunAttemptStatus.RUNNING) {
            return loadExactReplayLocked(batch, attemptStatus);
        }
        return appendLocked(batch);
    }

    private static boolean recoveryErrorAppendStatus(AgentRunAttemptStatus status) {
        return status == AgentRunAttemptStatus.RUNNING
                || status == AgentRunAttemptStatus.FAILED
                || status == AgentRunAttemptStatus.ABORTED
                || status == AgentRunAttemptStatus.CANCELLED;
    }

    private BatchAppendReceipt loadExactReplayLocked(
            List<PersistedEvent> batch, AgentRunAttemptStatus attemptStatus) {
        Map<Long, String> storedHashes = loadStoredHashes(batch);
        for (PersistedEvent candidate : batch) {
            String storedHash = storedHashes.get(candidate.event().sequenceNo());
            if (storedHash == null) {
                throw new NonRunningAttemptException(attemptStatus);
            }
            if (!storedHash.equals(candidate.payloadHash())) {
                throw new IllegalStateException(
                        "durable stream sequence is bound to another payload hash");
            }
        }

        if (compatibilityMode.writer() == StreamCompatibilityMode.Writer.DUAL_WRITE) {
            mirrorBatchToTarget(batch);
        }

        AgentStreamEvent first = batch.getFirst().event();
        long highWatermark = durableHighWatermark(first.runId(), first.attemptId());
        projectAttemptProgress(batch.stream().map(PersistedEvent::event).toList());
        return new BatchAppendReceipt(
                java.util.Collections.nCopies(batch.size(), false), highWatermark);
    }

    private BatchAppendReceipt appendLocked(List<PersistedEvent> batch) {
        AgentStreamEvent first = batch.getFirst().event();
        requireAppendPosition(batch);
        int[] updateCounts =
                jdbc.batchUpdate(
                        INSERT_SQL,
                        new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                            @Override
                            public void setValues(PreparedStatement statement, int index)
                                    throws java.sql.SQLException {
                                PersistedEvent event = batch.get(index);
                                statement.setString(1, event.id());
                                statement.setString(2, event.event().runId());
                                statement.setString(3, event.event().attemptId());
                                statement.setLong(4, event.event().sequenceNo());
                                statement.setString(5, event.event().eventType().wireValue());
                                statement.setString(6, event.canonicalJson());
                                statement.setTimestamp(
                                        7, Timestamp.from(event.event().occurredAt()));
                                statement.setString(8, "agent-stream-v2");
                                statement.setString(9, event.event().audience().name());
                                statement.setString(10, event.payloadHash());
                            }

                            @Override
                            public int getBatchSize() {
                                return batch.size();
                            }
                        });

        if (updateCounts.length != batch.size()) {
            throw new IllegalStateException("durable stream batch returned incomplete results");
        }
        List<Boolean> inserted = new ArrayList<>(updateCounts.length);
        for (int updateCount : updateCounts) {
            if (updateCount == Statement.EXECUTE_FAILED) {
                throw new IllegalStateException("durable stream batch insert failed");
            }
            inserted.add(updateCount > 0 || updateCount == Statement.SUCCESS_NO_INFO);
        }

        Map<Long, String> storedHashes = loadStoredHashes(batch);
        for (PersistedEvent candidate : batch) {
            String storedHash = storedHashes.get(candidate.event().sequenceNo());
            if (storedHash == null) {
                throw new IllegalStateException(
                        "durable stream append did not persist the requested sequence");
            }
            if (!storedHash.equals(candidate.payloadHash())) {
                throw new IllegalStateException(
                        "durable stream sequence is bound to another payload hash");
            }
        }

        if (compatibilityMode.writer() == StreamCompatibilityMode.Writer.DUAL_WRITE) {
            mirrorBatchToTarget(batch);
        }

        long highWatermark = durableHighWatermark(first.runId(), first.attemptId());
        long appendedMaximum =
                batch.stream().mapToLong(item -> item.event().sequenceNo()).max().orElseThrow();
        if (highWatermark < appendedMaximum) {
            throw new IllegalStateException("PostgreSQL high-watermark is behind the append batch");
        }
        projectAttemptProgress(
                batch.stream().map(PersistedEvent::event).toList());
        return new BatchAppendReceipt(inserted, highWatermark);
    }

    private AgentRunReconciledFinalStore.Receipt appendOrLoadReconciledFinalInTransaction(
            AgentRunReconciledFinalStore.Request request) {
        AgentRunAttemptStatus attemptStatus =
                lockAttempt(request.logicalRunId(), request.attemptId());
        if (attemptStatus != AgentRunAttemptStatus.RUNNING
                && attemptStatus != AgentRunAttemptStatus.RESULT_READY
                && attemptStatus != AgentRunAttemptStatus.COMPLETED) {
            if (attemptStatus == AgentRunAttemptStatus.FAILED
                    || attemptStatus == AgentRunAttemptStatus.ABORTED
                    || attemptStatus == AgentRunAttemptStatus.CANCELLED) {
                throw new NonRunningAttemptException(attemptStatus);
            }
            throw new AgentRunReconciledFinalStore.ConflictException(
                    "reconciled final conflicts with terminal attempt status " + attemptStatus);
        }
        List<AgentStreamEvent> events = loadAttemptEvents(
                request.logicalRunId(), request.attemptId());
        if (events.isEmpty()
                || events.getFirst().sequenceNo() != 0
                || events.getFirst().eventType() != StreamEventType.ATTEMPT_STARTED) {
            throw new AgentRunReconciledFinalStore.ConflictException(
                    "reconciled final requires a durable attempt_started prelude");
        }
        boolean publicOutputEmitted = false;
        for (int index = 0; index < events.size(); index++) {
            AgentStreamEvent event = events.get(index);
            if (event.sequenceNo() != index
                    || !event.runId().equals(request.logicalRunId())
                    || !event.attemptId().equals(request.attemptId())
                    || event.audience() != request.audience()
                    || (index > 0 && event.eventType() == StreamEventType.ATTEMPT_STARTED)) {
                throw new AgentRunReconciledFinalStore.ConflictException(
                        "durable public attempt history is inconsistent");
            }
            publicOutputEmitted |= event.eventType() == StreamEventType.VISIBLE_DELTA;
            if (event.eventType() == StreamEventType.FINAL) {
                if (index != events.size() - 1
                        || !request.resultRef().equals(event.payload().finalResultRef())
                        || !request.resultHash().equals(event.payload().finalResultHash())) {
                    throw new AgentRunReconciledFinalStore.ConflictException(
                            "durable final differs from the reconciled result");
                }
                projectAttemptProgress(events);
                return new AgentRunReconciledFinalStore.Receipt(
                        event,
                        false,
                        event.sequenceNo(),
                        publicOutputEmitted);
            }
            if (event.eventType() == StreamEventType.ERROR
                    || event.eventType() == StreamEventType.ATTEMPT_ABORTED) {
                throw new AgentRunReconciledFinalStore.ConflictException(
                        "durable public attempt already has another terminal event");
            }
        }

        long sequence = events.getLast().sequenceNo() + 1;
        Instant occurredAt = databaseNow();
        AgentStreamEvent finalEvent = new AgentStreamEvent(
                "agent-stream.v2",
                request.logicalRunId(),
                request.attemptId(),
                sequence,
                StreamEventType.FINAL,
                request.audience(),
                occurredAt,
                new AgentStreamEvent.Payload(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        request.resultRef(),
                        request.resultHash(),
                        null,
                        null));
        BatchAppendReceipt appended = appendLocked(prepareBatch(List.of(finalEvent)));
        if (!appended.inserted().getFirst()
                || appended.durableHighWatermark() != sequence) {
            throw new AgentRunReconciledFinalStore.ConflictException(
                    "reconciled final was not the next durable event");
        }
        return new AgentRunReconciledFinalStore.Receipt(
                finalEvent,
                true,
                sequence,
                publicOutputEmitted);
    }

    private void projectAttemptProgress(List<AgentStreamEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        AgentStreamEvent first = events.getFirst();
        long lastSequenceNo = -1;
        boolean publicOutputEmitted = false;
        boolean finalFrameObserved = false;
        for (AgentStreamEvent event : events) {
            if (!first.runId().equals(event.runId())
                    || !first.attemptId().equals(event.attemptId())) {
                throw new IllegalArgumentException(
                        "attempt progress projection requires one run attempt");
            }
            lastSequenceNo = Math.max(lastSequenceNo, event.sequenceNo());
            publicOutputEmitted |= event.eventType() == StreamEventType.VISIBLE_DELTA;
            finalFrameObserved |= event.eventType() == StreamEventType.FINAL;
        }
        int updated = jdbc.update(
                PROJECT_ATTEMPT_PROGRESS_SQL,
                lastSequenceNo,
                publicOutputEmitted,
                finalFrameObserved,
                first.runId(),
                first.attemptId(),
                lastSequenceNo,
                publicOutputEmitted,
                finalFrameObserved);
        if (updated < 0 || updated > 1) {
            throw new IllegalStateException(
                    "durable stream progress projection updated an invalid attempt count");
        }
    }

    private AgentRunAttemptStatus lockAttempt(String runId, String attemptId) {
        List<String> statuses = jdbc.query(
                LOCK_ATTEMPT_SQL,
                (resultSet, rowNumber) -> resultSet.getString("attempt_status"),
                runId,
                attemptId);
        if (statuses.size() != 1) {
            throw new AgentRunReconciledFinalStore.ConflictException(
                    "public AgentRun attempt is missing or ambiguous");
        }
        try {
            return AgentRunAttemptStatus.valueOf(statuses.getFirst());
        } catch (IllegalArgumentException exception) {
            throw new AgentRunReconciledFinalStore.ConflictException(
                    "public AgentRun attempt has an invalid status", exception);
        }
    }

    private List<AgentStreamEvent> loadAttemptEvents(String runId, String attemptId) {
        return jdbc.query(
                ATTEMPT_EVENTS_SQL,
                (resultSet, rowNumber) -> decodeAndVerify(
                        runId,
                        attemptId,
                        resultSet.getLong("sequence_no"),
                        resultSet.getString("event_type"),
                        resultSet.getString("audience"),
                        resultSet.getString("payload_hash"),
                        resultSet.getString("payload_json")),
                runId,
                attemptId);
    }

    private Instant databaseNow() {
        Timestamp timestamp = jdbc.queryForObject(
                "select clock_timestamp()", Timestamp.class);
        if (timestamp == null) {
            throw new IllegalStateException("database clock returned no timestamp");
        }
        return timestamp.toInstant();
    }

    private void requireAppendPosition(List<PersistedEvent> batch) {
        AgentStreamEvent first = batch.getFirst().event();
        long highWatermark = durableHighWatermark(first.runId(), first.attemptId());
        List<PersistedEvent> newEvents = batch.stream()
                .filter(candidate -> candidate.event().sequenceNo() > highWatermark)
                .toList();
        if (newEvents.isEmpty()) {
            return;
        }
        if (newEvents.getFirst().event().sequenceNo() != highWatermark + 1) {
            throw new IllegalStateException("durable stream append would create a sequence gap");
        }
        List<String> lastTypes = jdbc.query(
                LAST_EVENT_TYPE_SQL,
                (resultSet, rowNumber) -> resultSet.getString("event_type"),
                first.runId(),
                first.attemptId());
        if (lastTypes.size() > 1) {
            throw new IllegalStateException("durable stream has an ambiguous terminal position");
        }
        long expected = highWatermark + 1;
        String previousType = lastTypes.isEmpty() ? null : lastTypes.getFirst();
        for (PersistedEvent candidate : newEvents) {
            AgentStreamEvent event = candidate.event();
            if (event.sequenceNo() != expected++) {
                throw new IllegalStateException(
                        "durable stream append is not contiguous");
            }
            if (isGlobalTerminal(previousType)
                    || ("attempt_aborted".equals(previousType)
                            && event.eventType() != StreamEventType.ERROR)) {
                throw new IllegalStateException("durable stream append follows a terminal event");
            }
            if (event.sequenceNo() == 0
                    && event.eventType() != StreamEventType.ATTEMPT_STARTED) {
                throw new IllegalStateException(
                        "durable stream must begin with attempt_started");
            }
            if (event.sequenceNo() > 0
                    && event.eventType() == StreamEventType.ATTEMPT_STARTED) {
                throw new IllegalStateException(
                        "durable stream cannot repeat attempt_started");
            }
            previousType = event.eventType().wireValue();
        }
    }

    private Map<Long, String> loadStoredHashes(List<PersistedEvent> batch) {
        AgentStreamEvent first = batch.getFirst().event();
        List<Long> sequences =
                batch.stream().map(item -> item.event().sequenceNo()).distinct().toList();
        MapSqlParameterSource parameters =
                new MapSqlParameterSource()
                        .addValue("runId", first.runId())
                        .addValue("attemptId", first.attemptId())
                        .addValue("sequences", sequences);
        Map<Long, String> hashes = new LinkedHashMap<>();
        namedJdbc.query(
                HASHES_SQL,
                parameters,
                (org.springframework.jdbc.core.RowCallbackHandler)
                        resultSet ->
                                hashes.put(
                                        resultSet.getLong("sequence_no"),
                                        resultSet.getString("payload_hash")));
        return hashes;
    }

    private void mirrorBatchToTarget(List<PersistedEvent> batch) {
        AgentStreamEvent first = batch.getFirst().event();
        List<Long> sequences =
                batch.stream().map(item -> item.event().sequenceNo()).distinct().toList();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("runId", first.runId())
                .addValue("attemptId", first.attemptId())
                .addValue("sequences", sequences);
        Map<Long, OldDelivery> oldRows = new LinkedHashMap<>();
        namedJdbc.query(
                OLD_DELIVERY_SQL,
                parameters,
                (org.springframework.jdbc.core.RowCallbackHandler) resultSet -> {
                    OldDelivery row = new OldDelivery(
                            resultSet.getString("id"),
                            resultSet.getLong("sequence_no"),
                            resultSet.getString("event_type"),
                            resultSet.getString("payload_json"),
                            resultSet.getString("payload_hash"),
                            resultSet.getString("audience"),
                            resultSet.getTimestamp("created_at").toInstant(),
                            resultSet.getString("actor_id"),
                            resultSet.getString("audience_actor_ids_json"));
                    if (oldRows.put(row.sequenceNo(), row) != null) {
                        throw new IllegalStateException(
                                "old stream sequence is ambiguous during dual-write");
                    }
                });
        if (oldRows.size() != sequences.size()) {
            throw new IllegalStateException(
                    "dual-write cannot resolve every old-store event identity");
        }
        for (PersistedEvent candidate : batch) {
            OldDelivery source = oldRows.get(candidate.event().sequenceNo());
            if (!candidate.payloadHash().equals(source.payloadHash())
                    || !candidate.canonicalJson().equals(
                            canonicalJson(source.payloadJson()))) {
                throw new IllegalStateException(
                        "dual-write source row conflicts with the requested canonical payload");
            }
            List<TargetWriteReceipt> receipts = jdbc.query(
                    RECORD_TARGET_SQL,
                    (resultSet, rowNumber) -> new TargetWriteReceipt(
                            resultSet.getBoolean("was_inserted"),
                            resultSet.getLong("highest_contiguous_sequence_no")),
                    source.eventId(),
                    first.runId(),
                    first.attemptId(),
                    source.sequenceNo(),
                    source.eventType(),
                    source.payloadJson(),
                    source.payloadHash(),
                    source.audience(),
                    source.actorId(),
                    source.audienceActorIdsJson(),
                    Timestamp.from(source.createdAt()));
            if (receipts.size() != 1) {
                throw new IllegalStateException(
                        "V046 dual-write function returned an invalid receipt count");
            }
        }
    }

    private List<AgentStreamEvent> replayTarget(
            String runId, String attemptId, long afterSequence, int limit) {
        return jdbc.query(
                TARGET_REPLAY_SQL,
                (resultSet, rowNumber) -> decodeAndVerify(
                        runId,
                        attemptId,
                        resultSet.getLong("sequence_no"),
                        resultSet.getString("event_type"),
                        resultSet.getString("audience"),
                        resultSet.getString("payload_hash"),
                        resultSet.getString("payload_json")),
                runId,
                attemptId,
                afterSequence,
                limit);
    }

    private List<AgentStreamEvent> compatibleUnion(
            List<AgentStreamEvent> oldEvents,
            List<AgentStreamEvent> targetEvents,
            int limit) {
        TreeMap<Long, AgentStreamEvent> union = new TreeMap<>();
        for (AgentStreamEvent event : oldEvents) {
            union.put(event.sequenceNo(), event);
        }
        for (AgentStreamEvent event : targetEvents) {
            AgentStreamEvent previous = union.putIfAbsent(event.sequenceNo(), event);
            if (previous != null
                    && !ContractJson.sha256Hex(objectMapper.valueToTree(previous))
                            .equals(ContractJson.sha256Hex(objectMapper.valueToTree(event)))) {
                throw new IllegalStateException(
                        "compatible reader found conflicting old and target stream events");
            }
        }
        return union.values().stream().limit(limit).toList();
    }

    private CompatibilityReport validateCompatibilityInTransaction(
            String streamProtocol, String runId, String attemptId) {
        List<CompatibilityRow> source = loadCompatibilityRows(
                SOURCE_COMPATIBILITY_SQL, true, streamProtocol, runId, attemptId);
        List<CompatibilityRow> target = loadCompatibilityRows(
                TARGET_COMPATIBILITY_SQL, false, streamProtocol, runId, attemptId);
        boolean countParity = source.size() == target.size();
        boolean canonicalHashParity = paired(source, target,
                (left, right) -> left.canonicalHash().equals(right.canonicalHash()));
        boolean sourceContiguous = highestContiguousSequence(source) == source.size() - 1L;
        boolean targetContiguous = highestContiguousSequence(target) == target.size() - 1L;
        boolean sequenceParity = sourceContiguous
                && targetContiguous
                && paired(source, target, (left, right) ->
                        left.eventId().equals(right.eventId())
                                && left.streamProtocol().equals(right.streamProtocol())
                                && left.runId().equals(right.runId())
                                && left.attemptId().equals(right.attemptId())
                                && left.sequenceNo() == right.sequenceNo()
                                && left.eventType().equals(right.eventType())
                                && left.createdAt().equals(right.createdAt()));
        boolean actorIdParity = paired(source, target,
                (left, right) -> Objects.equals(left.actorId(), right.actorId()));
        boolean audienceParity = paired(source, target,
                (left, right) -> Objects.equals(left.audience(), right.audience()));
        boolean visibilityParity = paired(source, target, (left, right) ->
                left.audienceRolesCanonical().equals(right.audienceRolesCanonical())
                        && left.audienceActorIdsCanonical()
                                .equals(right.audienceActorIdsCanonical()));
        boolean resetParity = paired(source, target,
                (left, right) -> Objects.equals(left.resetAttemptId(), right.resetAttemptId()));
        boolean terminalParity = terminalOrderValid(source)
                && terminalOrderValid(target)
                && paired(source, target,
                        (left, right) -> left.terminal() == right.terminal());
        boolean compositeCursorParity = paired(source, target,
                (left, right) -> left.compositeCursor().equals(right.compositeCursor()));
        long expectedHighWatermark = highestContiguousSequence(source);
        long targetHighWatermark = targetHighWatermark(
                streamProtocol, runId, attemptId);
        boolean reconnectParity = countParity
                && sequenceParity
                && terminalParity
                && reconnectSuffixesMatch(source, target)
                && expectedHighWatermark == targetHighWatermark;
        return new CompatibilityReport(
                streamProtocol,
                runId,
                attemptId,
                source.size(),
                target.size(),
                countParity,
                canonicalHashParity,
                sequenceParity,
                actorIdParity,
                audienceParity,
                visibilityParity,
                resetParity,
                terminalParity,
                reconnectParity,
                compositeCursorParity);
    }

    private List<CompatibilityRow> loadCompatibilityRows(
            String sql,
            boolean source,
            String streamProtocol,
            String runId,
            String attemptId) {
        return jdbc.query(
                sql,
                (resultSet, rowNumber) -> {
                    String payloadJson = resultSet.getString("payload_json");
                    String canonicalHash = ContractJson.sha256Hex(readJson(payloadJson));
                    String storedHash = resultSet.getString("payload_hash");
                    if (!source || storedHash != null) {
                        if (!canonicalHash.equals(storedHash)) {
                            throw new IllegalStateException(
                                    (source ? "source" : "target")
                                            + " stream canonical payload hash conflicts");
                        }
                    }
                    return new CompatibilityRow(
                            resultSet.getString("event_id"),
                            resultSet.getString("stream_protocol"),
                            resultSet.getString("agent_run_id"),
                            resultSet.getString("agent_run_attempt_id"),
                            resultSet.getLong("sequence_no"),
                            resultSet.getString("event_type"),
                            canonicalHash,
                            resultSet.getString("audience"),
                            resultSet.getTimestamp("created_at").toInstant(),
                            resultSet.getString("actor_id"),
                            canonicalJson(resultSet.getString("audience_roles_json")),
                            canonicalJson(resultSet.getString("audience_actor_ids_json")),
                            resetAttemptId(payloadJson));
                },
                streamProtocol,
                runId,
                attemptId);
    }

    private long targetHighWatermark(String protocol, String runId, String attemptId) {
        List<Long> values = jdbc.query(
                """
                select highest_contiguous_sequence_no
                  from agent_run_stream_delivery_high_watermark
                 where stream_protocol = ? and agent_run_id = ?
                   and agent_run_attempt_id = ?
                """,
                (resultSet, rowNumber) -> resultSet.getLong(1),
                protocol,
                runId,
                attemptId);
        if (values.size() > 1) {
            throw new IllegalStateException("target delivery high-watermark is ambiguous");
        }
        return values.isEmpty() ? -1 : values.getFirst();
    }

    private static long highestContiguousSequence(List<CompatibilityRow> rows) {
        long expected = 0;
        for (CompatibilityRow row : rows) {
            if (row.sequenceNo() != expected) {
                break;
            }
            expected++;
        }
        return expected - 1;
    }

    private static boolean reconnectSuffixesMatch(
            List<CompatibilityRow> source, List<CompatibilityRow> target) {
        if (source.size() != target.size()) {
            return false;
        }
        for (int cursor = -1; cursor < source.size(); cursor++) {
            long afterSequence = cursor;
            List<String> sourceSuffix = source.stream()
                    .filter(row -> row.sequenceNo() > afterSequence)
                    .map(CompatibilityRow::compositeCursor)
                    .toList();
            List<String> targetSuffix = target.stream()
                    .filter(row -> row.sequenceNo() > afterSequence)
                    .map(CompatibilityRow::compositeCursor)
                    .toList();
            if (!sourceSuffix.equals(targetSuffix)) {
                return false;
            }
        }
        return true;
    }

    private static boolean terminalOrderValid(List<CompatibilityRow> rows) {
        CompatibilityRow previous = null;
        for (CompatibilityRow row : rows) {
            if (previous != null) {
                if (!previous.streamProtocol().equals(row.streamProtocol())
                        || !previous.runId().equals(row.runId())
                        || !previous.attemptId().equals(row.attemptId())
                        || previous.sequenceNo() == Long.MAX_VALUE
                        || row.sequenceNo() != previous.sequenceNo() + 1
                        || previous.globalTerminal()
                        || (previous.attemptAborted() && !row.error())) {
                    return false;
                }
            }
            previous = row;
        }
        return true;
    }

    private static boolean isGlobalTerminal(String eventType) {
        return "final".equals(eventType) || "error".equals(eventType);
    }

    private static boolean paired(
            List<CompatibilityRow> source,
            List<CompatibilityRow> target,
            java.util.function.BiPredicate<CompatibilityRow, CompatibilityRow> predicate) {
        if (source.size() != target.size()) {
            return false;
        }
        for (int index = 0; index < source.size(); index++) {
            if (!predicate.test(source.get(index), target.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean compatibilityEquivalent(
            CompatibilityRow left, CompatibilityRow right) {
        return left.eventId().equals(right.eventId())
                && left.streamProtocol().equals(right.streamProtocol())
                && left.runId().equals(right.runId())
                && left.attemptId().equals(right.attemptId())
                && left.sequenceNo() == right.sequenceNo()
                && left.eventType().equals(right.eventType())
                && left.canonicalHash().equals(right.canonicalHash())
                && Objects.equals(left.audience(), right.audience())
                && left.createdAt().equals(right.createdAt())
                && Objects.equals(left.actorId(), right.actorId())
                && left.audienceRolesCanonical().equals(right.audienceRolesCanonical())
                && left.audienceActorIdsCanonical().equals(right.audienceActorIdsCanonical())
                && Objects.equals(left.resetAttemptId(), right.resetAttemptId());
    }

    private String resetAttemptId(String payloadJson) {
        JsonNode payload = readJson(payloadJson);
        JsonNode nested = payload.path("payload").path("reset_attempt_id");
        JsonNode direct = payload.path("reset_attempt_id");
        JsonNode value = nested.isMissingNode() ? direct : nested;
        return value.isTextual() ? value.textValue() : null;
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stream compatibility JSON cannot be decoded", exception);
        }
    }

    private String canonicalJson(String value) {
        return ContractJson.canonicalString(readJson(value));
    }

    private List<PersistedEvent> prepareBatch(List<AgentStreamEvent> events) {
        if (events == null || events.isEmpty()) {
            throw new IllegalArgumentException("events must not be empty");
        }
        List<PersistedEvent> prepared = new ArrayList<>(events.size());
        String runId = null;
        String attemptId = null;
        long previousSequence = -1;
        for (AgentStreamEvent event : events) {
            requireEvent(event);
            if (event.sequenceNo() < 0) {
                throw new IllegalArgumentException("sequenceNo must not be negative");
            }
            if (event.sequenceNo() <= previousSequence) {
                throw new IllegalArgumentException(
                        "append batch sequences must be strictly increasing");
            }
            previousSequence = event.sequenceNo();
            if (runId == null) {
                runId = event.runId();
                attemptId = event.attemptId();
            } else if (!runId.equals(event.runId()) || !attemptId.equals(event.attemptId())) {
                throw new IllegalArgumentException(
                        "an append batch must contain exactly one run attempt");
            }
            JsonNode json = objectMapper.valueToTree(event);
            prepared.add(
                    new PersistedEvent(
                            "ARSE2_" + UUID.randomUUID().toString().replace("-", ""),
                            event,
                            ContractJson.canonicalString(json),
                            ContractJson.sha256Hex(json)));
        }
        return List.copyOf(prepared);
    }

    private AgentStreamEvent decodeAndVerify(
            String runId,
            String attemptId,
            long sequenceNo,
            String eventType,
            String audience,
            String payloadHash,
            String payloadJson) {
        try {
            JsonNode node = objectMapper.readTree(payloadJson);
            String actualHash = ContractJson.sha256Hex(node);
            if (!actualHash.equals(payloadHash)) {
                throw new IllegalStateException("durable stream payload hash verification failed");
            }
            AgentStreamEvent event = objectMapper.treeToValue(node, AgentStreamEvent.class);
            if (!event.runId().equals(runId)
                    || !event.attemptId().equals(attemptId)
                    || event.sequenceNo() != sequenceNo
                    || !event.eventType().wireValue().equals(eventType)
                    || !event.audience().name().equals(audience)) {
                throw new IllegalStateException(
                        "durable stream columns conflict with the hash-bound payload");
            }
            return event;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("durable stream payload cannot be decoded", exception);
        }
    }

    private static AgentStreamEvent requireEvent(AgentStreamEvent event) {
        return Objects.requireNonNull(event, "event must not be null");
    }

    private static String requireIdentity(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private record PersistedEvent(
            String id, AgentStreamEvent event, String canonicalJson, String payloadHash) {}

    private record OldDelivery(
            String eventId,
            long sequenceNo,
            String eventType,
            String payloadJson,
            String payloadHash,
            String audience,
            Instant createdAt,
            String actorId,
            String audienceActorIdsJson) {}

    private record TargetWriteReceipt(boolean inserted, long highestContiguousSequence) {}

    private record CompatibilityRow(
            String eventId,
            String streamProtocol,
            String runId,
            String attemptId,
            long sequenceNo,
            String eventType,
            String canonicalHash,
            String audience,
            Instant createdAt,
            String actorId,
            String audienceRolesCanonical,
            String audienceActorIdsCanonical,
            String resetAttemptId) {

        private boolean terminal() {
            return Set.of("final", "error", "attempt_aborted").contains(eventType);
        }

        private boolean globalTerminal() {
            return isGlobalTerminal(eventType);
        }

        private boolean attemptAborted() {
            return "attempt_aborted".equals(eventType);
        }

        private boolean error() {
            return "error".equals(eventType);
        }

        private String compositeCursor() {
            AgentRunProtocol protocol = AgentRunProtocol.V1.wireValue().equals(streamProtocol)
                    ? AgentRunProtocol.V1
                    : AgentRunProtocol.V2;
            return new AgentRunStreamCursor(
                            protocol,
                            protocol == AgentRunProtocol.V1 ? null : attemptId,
                            sequenceNo)
                    .wireValue();
        }
    }

}
