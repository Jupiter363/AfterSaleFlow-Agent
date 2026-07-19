package com.example.dispute.agentstream.infrastructure.persistence;

import com.example.dispute.agentstream.application.AgentRunV2StreamStore;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore.BatchAppendReceipt;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
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

    public PostgresAgentRunV2EventStore(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        this.objectMapper =
                Objects.requireNonNull(objectMapper, "objectMapper must not be null").copy();
        this.objectMapper.setSerializationInclusion(JsonInclude.Include.ALWAYS);
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
        return jdbc.query(
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
    }

    /** Returns the PostgreSQL high-watermark, or {@code -1} when the attempt has no events. */
    public long durableHighWatermark(String runId, String attemptId) {
        requireIdentity(runId, "runId");
        requireIdentity(attemptId, "attemptId");
        Long value =
                jdbc.queryForObject(HIGH_WATERMARK_SQL, Long.class, runId, attemptId);
        return value == null ? -1 : value;
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

        AgentStreamEvent first = batch.getFirst().event();
        long highWatermark = durableHighWatermark(first.runId(), first.attemptId());
        long appendedMaximum =
                batch.stream().mapToLong(item -> item.event().sequenceNo()).max().orElseThrow();
        if (highWatermark < appendedMaximum) {
            throw new IllegalStateException("PostgreSQL high-watermark is behind the append batch");
        }
        return new BatchAppendReceipt(inserted, highWatermark);
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

}
