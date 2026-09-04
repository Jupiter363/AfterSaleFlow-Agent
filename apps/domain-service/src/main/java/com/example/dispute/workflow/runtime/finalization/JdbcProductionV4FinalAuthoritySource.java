package com.example.dispute.workflow.runtime.finalization;

import com.example.dispute.workflow.contract.v1.AgentStreamEventV4;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** PostgreSQL source/delivery/HWM reader for one exact agent-stream.v4 FINAL. */
public final class JdbcProductionV4FinalAuthoritySource
        implements ProductionV4FinalAuthoritySource {

    private static final String LOAD_SQL = """
            select source.id as source_event_id,
                   source.sequence_no as source_sequence_no,
                   source.event_type as source_event_type,
                   source.audience as source_audience,
                   source.payload_hash as source_payload_hash,
                   source.payload_json::text as source_payload_json,
                   source.created_at as source_created_at,
                   delivery.event_id as delivery_event_id,
                   delivery.sequence_no as delivery_sequence_no,
                   delivery.event_type as delivery_event_type,
                   delivery.audience as delivery_audience,
                   delivery.canonical_payload_sha256 as delivery_payload_hash,
                   delivery.payload_json::text as delivery_payload_json,
                   delivery.source_event_created_at,
                   delivery.actor_id,
                   delivery.audience_actor_ids_json::text as audience_actor_ids_json,
                   delivery.source_store,
                   watermark.highest_contiguous_sequence_no
              from agent_run_stream_event source
              join agent_run_stream_event_delivery delivery
                on delivery.event_id = source.id
               and delivery.stream_protocol = source.stream_protocol
               and delivery.agent_run_id = source.agent_run_id
               and delivery.agent_run_attempt_id = source.agent_run_attempt_id
               and delivery.sequence_no = source.sequence_no
              join agent_run_stream_delivery_high_watermark watermark
                on watermark.stream_protocol = source.stream_protocol
               and watermark.agent_run_id = source.agent_run_id
               and watermark.agent_run_attempt_id = source.agent_run_attempt_id
             where source.stream_protocol = 'agent-stream.v4'
               and source.agent_run_id = :runId
               and source.agent_run_attempt_id = :attemptId
               and source.sequence_no = :sequenceNo
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcProductionV4FinalAuthoritySource(
            DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = new NamedParameterJdbcTemplate(
                Objects.requireNonNull(dataSource, "dataSource"));
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
    }

    @Override
    public Optional<FinalAuthority> load(String runId, String attemptId, long sequenceNo) {
        if (runId == null || runId.isBlank() || attemptId == null || attemptId.isBlank()) {
            throw new IllegalArgumentException("runId and attemptId are required");
        }
        if (sequenceNo < 0) {
            throw new IllegalArgumentException("sequenceNo must not be negative");
        }
        List<Row> rows = jdbc.query(
                LOAD_SQL,
                Map.of("runId", runId, "attemptId", attemptId, "sequenceNo", sequenceNo),
                (rs, ignored) -> new Row(
                        rs.getString("source_event_id"),
                        rs.getLong("source_sequence_no"),
                        rs.getString("source_event_type"),
                        rs.getString("source_audience"),
                        rs.getString("source_payload_hash"),
                        rs.getString("source_payload_json"),
                        instant(rs.getTimestamp("source_created_at")),
                        rs.getString("delivery_event_id"),
                        rs.getLong("delivery_sequence_no"),
                        rs.getString("delivery_event_type"),
                        rs.getString("delivery_audience"),
                        rs.getString("delivery_payload_hash"),
                        rs.getString("delivery_payload_json"),
                        instant(rs.getTimestamp("source_event_created_at")),
                        rs.getString("actor_id"),
                        rs.getString("audience_actor_ids_json"),
                        rs.getString("source_store"),
                        rs.getLong("highest_contiguous_sequence_no")));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        if (rows.size() != 1) {
            throw new IllegalStateException("agent-stream.v4 FINAL authority is ambiguous");
        }
        Row row = rows.getFirst();
        JsonNode sourceNode = readObject(row.sourcePayloadJson(), "source event");
        JsonNode deliveryNode = readObject(row.deliveryPayloadJson(), "delivery event");
        String canonicalHash = ContractJson.sha256Hex(sourceNode);
        boolean exact = row.sourceEventId().equals(row.deliveryEventId())
                && row.sourceSequenceNo() == row.deliverySequenceNo()
                && row.sourceSequenceNo() == sequenceNo
                && "final".equals(row.sourceEventType())
                && row.sourceEventType().equals(row.deliveryEventType())
                && row.sourceAudience().equals(row.deliveryAudience())
                && row.sourcePayloadHash().equals(row.deliveryPayloadHash())
                && row.sourcePayloadHash().equals(canonicalHash)
                && sourceNode.equals(deliveryNode)
                && row.sourceCreatedAt().equals(row.deliverySourceCreatedAt())
                && "agent_run_stream_event".equals(row.sourceStore())
                && row.durableHighWatermark() == sequenceNo;
        if (!exact) {
            throw new IllegalStateException(
                    "agent-stream.v4 source and delivery FINAL authorities conflict");
        }
        AgentStreamEventV4 event;
        try {
            event = objectMapper.treeToValue(sourceNode, AgentStreamEventV4.class);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("agent-stream.v4 FINAL cannot be decoded", failure);
        }
        JsonNode actors = readArray(row.audienceActorIdsJson(), "audience actors");
        if (actors.size() != 1 || !row.actorId().equals(actors.get(0).asText())) {
            throw new IllegalStateException(
                    "agent-stream.v4 FINAL audience actor authority conflicts");
        }
        return Optional.of(new FinalAuthority(
                event,
                row.sourceEventId(),
                canonicalHash,
                row.durableHighWatermark(),
                row.actorId()));
    }

    private JsonNode readObject(String value, String label) {
        JsonNode node = read(value, label);
        if (!node.isObject()) {
            throw new IllegalStateException(label + " must be a JSON object");
        }
        return node;
    }

    private JsonNode readArray(String value, String label) {
        JsonNode node = read(value, label);
        if (!node.isArray()) {
            throw new IllegalStateException(label + " must be a JSON array");
        }
        return node;
    }

    private JsonNode read(String value, String label) {
        try {
            return objectMapper.readTree(value);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException(label + " cannot be decoded", failure);
        }
    }

    private static Instant instant(Timestamp value) {
        return Objects.requireNonNull(value, "terminal timestamp").toInstant();
    }

    private record Row(
            String sourceEventId,
            long sourceSequenceNo,
            String sourceEventType,
            String sourceAudience,
            String sourcePayloadHash,
            String sourcePayloadJson,
            Instant sourceCreatedAt,
            String deliveryEventId,
            long deliverySequenceNo,
            String deliveryEventType,
            String deliveryAudience,
            String deliveryPayloadHash,
            String deliveryPayloadJson,
            Instant deliverySourceCreatedAt,
            String actorId,
            String audienceActorIdsJson,
            String sourceStore,
            long durableHighWatermark) {}
}
