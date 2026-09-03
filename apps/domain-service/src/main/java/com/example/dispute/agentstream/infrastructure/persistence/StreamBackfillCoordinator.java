package com.example.dispute.agentstream.infrastructure.persistence;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded, resumable V046 backfill. The progress cursor never acts as a delivery watermark. */
@Repository
public class StreamBackfillCoordinator {

    private static final String LOAD_CURSOR_FOR_UPDATE_SQL =
            """
            select backfill_id, source_upper_bound_created_at,
                   source_upper_bound_event_id, last_source_created_at,
                   last_source_event_id, batch_limit, cursor_status,
                   processed_count, conflict_count
              from agent_run_stream_backfill_cursor
             where backfill_id = ?
             for update
            """;

    private static final String LOAD_CURSOR_SQL =
            LOAD_CURSOR_FOR_UPDATE_SQL.replace("             for update\n", "");

    private static final String LOAD_BATCH_SQL =
            """
            select source.id,
                   source.stream_protocol,
                   source.agent_run_id,
                   source.agent_run_attempt_id,
                   source.sequence_no,
                   source.event_type,
                   source.payload_json::text as payload_json,
                   source.payload_hash,
                   source.audience,
                   source.created_at,
                   run.created_by as actor_id,
                   run.stream_audience_json::text as audience_roles_json,
                   run.stream_audience_actor_ids_json::text as audience_actor_ids_json
              from agent_run_stream_event source
              join agent_run run on run.id = source.agent_run_id
             where (?::timestamptz is null
                    or (source.created_at, source.id) > (?::timestamptz, ?))
               and (source.created_at, source.id) <= (?::timestamptz, ?)
             order by source.created_at asc, source.id asc
             limit ?
            """;

    private static final String RECORD_DELIVERY_SQL =
            """
            select was_inserted, authoritative_recorded_at,
                   highest_contiguous_sequence_no
              from record_agent_run_stream_delivery(
                   ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, cast(? as jsonb),
                   ?, 'agent_run_stream_event', ?)
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;

    public StreamBackfillCoordinator(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Captures one immutable upper bound; a later retry always scans the same source snapshot. */
    public BackfillCursor start(String backfillId, int batchLimit, String createdBy) {
        requireText(backfillId, "backfillId");
        requireText(createdBy, "createdBy");
        if (batchLimit < 1 || batchLimit > 1_000) {
            throw new IllegalArgumentException("batchLimit must be between 1 and 1000");
        }
        BackfillCursor cursor = transaction.execute(status -> {
            List<Integer> existing = jdbc.query(
                    """
                    select 1 from agent_run_stream_backfill_cursor
                     where backfill_id = ? for update
                    """,
                    (resultSet, rowNumber) -> 1,
                    backfillId);
            if (!existing.isEmpty()) {
                BackfillCursor stored = loadCursor(backfillId, false);
                if (stored.batchLimit() != batchLimit) {
                    throw new IllegalStateException(
                            "backfill identity is already bound to another batch limit");
                }
                return stored;
            }
            List<SourcePosition> bounds = jdbc.query(
                    """
                    select created_at, id
                      from agent_run_stream_event
                     order by created_at desc, id desc
                     limit 1
                    """,
                    (resultSet, rowNumber) -> new SourcePosition(
                            resultSet.getTimestamp("created_at").toInstant(),
                            resultSet.getString("id")));
            if (bounds.isEmpty()) {
                throw new IllegalStateException("cannot start stream backfill without source rows");
            }
            SourcePosition bound = bounds.getFirst();
            int inserted = jdbc.update(
                    """
                    insert into agent_run_stream_backfill_cursor (
                        backfill_id, source_upper_bound_created_at,
                        source_upper_bound_event_id, batch_limit, created_by
                    ) values (?, ?, ?, ?, ?)
                    on conflict (backfill_id) do nothing
                    """,
                    backfillId,
                    Timestamp.from(bound.createdAt()),
                    bound.eventId(),
                    batchLimit,
                    createdBy);
            BackfillCursor stored = loadCursor(backfillId, true);
            if (inserted == 0
                    && (stored.batchLimit() != batchLimit
                            || !stored.upperBound().equals(bound))) {
                throw new IllegalStateException(
                        "backfill identity is already bound to another immutable snapshot");
            }
            return stored;
        });
        if (cursor == null) {
            throw new IllegalStateException("backfill start transaction returned no cursor");
        }
        return cursor;
    }

    /** Copies at most the cursor's immutable batch limit and commits progress with that batch. */
    public BatchReceipt resume(String backfillId) {
        requireText(backfillId, "backfillId");
        try {
            BatchReceipt receipt = transaction.execute(status -> resumeInTransaction(backfillId));
            if (receipt == null) {
                throw new IllegalStateException("backfill transaction returned no receipt");
            }
            return receipt;
        } catch (RuntimeException failure) {
            try {
                boolean immutableConflict = isImmutableConflict(failure);
                transaction.executeWithoutResult(status -> {
                    String sql = immutableConflict
                            ? """
                              update agent_run_stream_backfill_cursor
                                 set cursor_status = 'FAILED',
                                     conflict_count = conflict_count + 1
                               where backfill_id = ? and cursor_status <> 'COMPLETE'
                              """
                            : """
                              update agent_run_stream_backfill_cursor
                                 set cursor_status = 'FAILED'
                               where backfill_id = ? and cursor_status <> 'COMPLETE'
                              """;
                    jdbc.update(sql, backfillId);
                });
            } catch (RuntimeException markFailure) {
                failure.addSuppressed(markFailure);
            }
            throw failure;
        }
    }

    public BackfillCursor cursor(String backfillId) {
        requireText(backfillId, "backfillId");
        return loadCursor(backfillId, false);
    }

    private BatchReceipt resumeInTransaction(String backfillId) {
        BackfillCursor cursor = loadCursor(backfillId, true);
        if (cursor.status() == CursorStatus.COMPLETE) {
            return new BatchReceipt(cursor, 0, 0);
        }
        if (cursor.conflictCount() > 0) {
            throw new IllegalStateException(
                    "backfill cannot resume after an immutable identity/hash conflict");
        }
        jdbc.update(
                "update agent_run_stream_backfill_cursor set cursor_status = 'RUNNING' where backfill_id = ?",
                backfillId);

        Timestamp lastCreatedAt = cursor.lastProcessed() == null
                ? null
                : Timestamp.from(cursor.lastProcessed().createdAt());
        String lastEventId = cursor.lastProcessed() == null
                ? null
                : cursor.lastProcessed().eventId();
        List<SourceEvent> batch = jdbc.query(
                LOAD_BATCH_SQL,
                (resultSet, rowNumber) -> new SourceEvent(
                        resultSet.getString("id"),
                        resultSet.getString("stream_protocol"),
                        resultSet.getString("agent_run_id"),
                        resultSet.getString("agent_run_attempt_id"),
                        resultSet.getLong("sequence_no"),
                        resultSet.getString("event_type"),
                        resultSet.getString("payload_json"),
                        resultSet.getString("payload_hash"),
                        resultSet.getString("audience"),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getString("actor_id"),
                        resultSet.getString("audience_roles_json"),
                        resultSet.getString("audience_actor_ids_json")),
                lastCreatedAt,
                lastCreatedAt,
                lastEventId,
                Timestamp.from(cursor.upperBound().createdAt()),
                cursor.upperBound().eventId(),
                cursor.batchLimit());

        if (batch.isEmpty()) {
            throw new IllegalStateException(
                    "bounded backfill cursor has not reached its source bound but found no rows");
        }

        int inserted = 0;
        Map<String, Long> highWatermarks = new HashMap<>();
        for (SourceEvent source : batch) {
            source.requireBound();
            String canonicalHash = canonicalHash(source);
            DeliveryReceipt delivery = jdbc.queryForObject(
                    RECORD_DELIVERY_SQL,
                    (resultSet, rowNumber) -> new DeliveryReceipt(
                            resultSet.getBoolean("was_inserted"),
                            resultSet.getTimestamp("authoritative_recorded_at").toInstant(),
                            resultSet.getLong("highest_contiguous_sequence_no")),
                    source.eventId(),
                    source.streamProtocol(),
                    source.runId(),
                    source.attemptId(),
                    source.sequenceNo(),
                    source.eventType(),
                    source.payloadJson(),
                    canonicalHash,
                    source.audience(),
                    source.actorId(),
                    source.audienceActorIdsJson(),
                    Timestamp.from(source.createdAt()),
                    "stream-backfill:" + backfillId);
            if (delivery == null) {
                throw new IllegalStateException("V046 delivery function returned no receipt");
            }
            inserted += delivery.inserted() ? 1 : 0;
            String streamIdentity = source.streamProtocol()
                    + ':'
                    + source.runId()
                    + ':'
                    + source.attemptId();
            long previousHighWatermark = highWatermarks.getOrDefault(streamIdentity, -1L);
            if (delivery.highestContiguousSequence() < previousHighWatermark) {
                throw new IllegalStateException("delivery high-watermark regressed during backfill");
            }
            highWatermarks.put(streamIdentity, delivery.highestContiguousSequence());
        }

        SourceEvent last = batch.getLast();
        SourcePosition nextPosition = new SourcePosition(last.createdAt(), last.eventId());
        CursorStatus nextStatus = nextPosition.equals(cursor.upperBound())
                ? CursorStatus.COMPLETE
                : CursorStatus.RUNNING;
        int updated = jdbc.update(
                """
                update agent_run_stream_backfill_cursor
                   set last_source_created_at = ?, last_source_event_id = ?,
                       processed_count = processed_count + ?, cursor_status = ?
                 where backfill_id = ?
                """,
                Timestamp.from(nextPosition.createdAt()),
                nextPosition.eventId(),
                batch.size(),
                nextStatus.name(),
                backfillId);
        if (updated != 1) {
            throw new IllegalStateException("backfill cursor advance was not unique");
        }
        return new BatchReceipt(loadCursor(backfillId, false), batch.size(), inserted);
    }

    private BackfillCursor loadCursor(String backfillId, boolean forUpdate) {
        String sql = forUpdate ? LOAD_CURSOR_FOR_UPDATE_SQL : LOAD_CURSOR_SQL;
        List<BackfillCursor> cursors = jdbc.query(
                sql,
                (resultSet, rowNumber) -> {
                    Timestamp lastCreatedAt = resultSet.getTimestamp("last_source_created_at");
                    String lastEventId = resultSet.getString("last_source_event_id");
                    return new BackfillCursor(
                            resultSet.getString("backfill_id"),
                            new SourcePosition(
                                    resultSet.getTimestamp("source_upper_bound_created_at")
                                            .toInstant(),
                                    resultSet.getString("source_upper_bound_event_id")),
                            lastCreatedAt == null
                                    ? null
                                    : new SourcePosition(lastCreatedAt.toInstant(), lastEventId),
                            resultSet.getInt("batch_limit"),
                            CursorStatus.valueOf(resultSet.getString("cursor_status")),
                            resultSet.getLong("processed_count"),
                            resultSet.getLong("conflict_count"));
                },
                backfillId);
        if (cursors.size() != 1) {
            throw new IllegalStateException("backfill cursor is missing or ambiguous");
        }
        return cursors.getFirst();
    }

    private String canonicalHash(SourceEvent source) {
        try {
            JsonNode payload = objectMapper.readTree(source.payloadJson());
            String canonicalHash = ContractJson.sha256Hex(payload);
            if (AgentRunProtocol.V3.wireValue().equals(source.streamProtocol())
                    && !canonicalHash.equals(source.payloadHash())) {
                throw new IllegalStateException(
                        "V2 source payload hash conflicts with canonical payload");
            }
            return canonicalHash;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("source stream payload cannot be decoded", exception);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static boolean isImmutableConflict(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null
                    && (message.contains(
                                    "stream event identity or canonical payload hash conflicts")
                            || message.contains(
                                    "partitioned stream delivery row conflicts with immutable identity")
                            || message.contains("uq_stream_event_identity_sequence")
                            || message.contains("agent_run_stream_event_identity_pkey")
                            || message.contains(
                                    "V2 source payload hash conflicts with canonical payload"))) {
                return true;
            }
        }
        return false;
    }

    public enum CursorStatus {
        PENDING,
        RUNNING,
        FAILED,
        COMPLETE
    }

    public record SourcePosition(Instant createdAt, String eventId) {
        public SourcePosition {
            Objects.requireNonNull(createdAt, "createdAt");
            requireText(eventId, "eventId");
        }
    }

    public record BackfillCursor(
            String backfillId,
            SourcePosition upperBound,
            SourcePosition lastProcessed,
            int batchLimit,
            CursorStatus status,
            long processedCount,
            long conflictCount) {
        public BackfillCursor {
            requireText(backfillId, "backfillId");
            Objects.requireNonNull(upperBound, "upperBound");
            Objects.requireNonNull(status, "status");
            if (batchLimit < 1 || batchLimit > 1_000
                    || processedCount < 0
                    || conflictCount < 0) {
                throw new IllegalArgumentException("invalid backfill cursor values");
            }
            if (lastProcessed != null
                    && compare(lastProcessed, upperBound) > 0) {
                throw new IllegalArgumentException("backfill cursor exceeds its immutable bound");
            }
        }
    }

    public record BatchReceipt(BackfillCursor cursor, int processed, int inserted) {
        public BatchReceipt {
            Objects.requireNonNull(cursor, "cursor");
            if (processed < 0 || inserted < 0 || inserted > processed) {
                throw new IllegalArgumentException("invalid backfill batch receipt");
            }
        }
    }

    private record SourceEvent(
            String eventId,
            String streamProtocol,
            String runId,
            String attemptId,
            long sequenceNo,
            String eventType,
            String payloadJson,
            String payloadHash,
            String audience,
            Instant createdAt,
            String actorId,
            String audienceRolesJson,
            String audienceActorIdsJson) {
        private SourceEvent requireBound() {
            requireText(eventId, "eventId");
            requireText(runId, "runId");
            requireText(attemptId, "attemptId");
            requireText(eventType, "eventType");
            requireText(payloadJson, "payloadJson");
            requireText(actorId, "actorId");
            requireText(audienceRolesJson, "audienceRolesJson");
            requireText(audienceActorIdsJson, "audienceActorIdsJson");
            if (!AgentRunProtocol.V1.wireValue().equals(streamProtocol)
                    && !AgentRunProtocol.V3.wireValue().equals(streamProtocol)) {
                throw new IllegalStateException("unsupported source stream protocol");
            }
            if (sequenceNo < 0
                    || createdAt == null
                    || (AgentRunProtocol.V3.wireValue().equals(streamProtocol)
                            && (payloadHash == null || audience == null))) {
                throw new IllegalStateException("source stream event is not compatibility-bound");
            }
            return this;
        }
    }

    private record DeliveryReceipt(
            boolean inserted, Instant recordedAt, long highestContiguousSequence) {}

    private static int compare(SourcePosition left, SourcePosition right) {
        int time = left.createdAt().compareTo(right.createdAt());
        return time != 0 ? time : left.eventId().compareTo(right.eventId());
    }
}
