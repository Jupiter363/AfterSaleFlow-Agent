package com.example.dispute.agentstream.infrastructure.persistence;

import com.example.dispute.workflow.contract.v1.AgentStreamEventV4;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Transaction-bound writer for the parallel Intake {@code agent-stream.v4} lane.
 *
 * <p>The caller owns the attempt-row lock and the technical staging transaction. This writer
 * deliberately has no transaction annotation and cannot be used outside an active transaction,
 * so a public event can never outlive the Frame progress/result mutation that authorizes it.
 */
@Component
public final class PostgresAgentRunV4EventWriter {

    private static final String INSERT_SOURCE_SQL =
            """
            insert into agent_run_stream_event (
                id, agent_run_id, agent_run_attempt_id, sequence_no,
                event_type, payload_json, created_at, created_by,
                stream_protocol, audience, payload_hash
            ) values (
                :eventId, :runId, :attemptId, :sequenceNo,
                :eventType, cast(:eventJson as jsonb), :occurredAt,
                'intake-parallel-frame-v4', 'agent-stream.v4', :audience, :eventHash
            )
            on conflict (agent_run_id, agent_run_attempt_id, sequence_no)
                where stream_protocol = 'agent-stream.v4'
            do nothing
            """;

    private static final String RECORD_DELIVERY_SQL =
            """
            select was_inserted, highest_contiguous_sequence_no
              from record_agent_run_stream_delivery(
                   :eventId, 'agent-stream.v4', :runId, :attemptId,
                   :sequenceNo, :eventType, cast(:eventJson as jsonb),
                   :eventHash, :audience, :actorId,
                   cast(:audienceActorIdsJson as jsonb), :occurredAt,
                   'agent_run_stream_event', 'intake-parallel-frame-v4')
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PostgresAgentRunV4EventWriter(
            NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        this.objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public EventWriteReceipt appendInCurrentTransaction(EventWriteCommand command) {
        Objects.requireNonNull(command, "command");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "agent-stream.v4 append requires the caller technical transaction");
        }

        AgentStreamEventV4 event = new AgentStreamEventV4(
                "agent-stream.v4",
                command.runId(),
                command.attemptId(),
                command.sequenceNo(),
                command.eventType(),
                command.audience(),
                command.occurredAt(),
                command.payload());
        JsonNode eventNode = objectMapper.valueToTree(event);
        String canonicalEventJson = ContractJson.canonicalString(eventNode);
        String eventHash = ContractJson.sha256Hex(eventNode);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("eventId", command.eventId())
                .addValue("runId", command.runId())
                .addValue("attemptId", command.attemptId())
                .addValue("sequenceNo", command.sequenceNo())
                .addValue("eventType", command.eventType().wireValue())
                .addValue("eventJson", canonicalEventJson)
                .addValue("occurredAt", command.occurredAt())
                .addValue("audience", command.audience().name())
                .addValue("eventHash", eventHash)
                .addValue("actorId", command.actorId())
                .addValue("audienceActorIdsJson", command.audienceActorIdsJson());

        int inserted = jdbc.update(INSERT_SOURCE_SQL, parameters);
        if (inserted != 1) {
            throw new IllegalStateException(
                    "agent-stream.v4 sequence was already bound before ingress admission");
        }
        List<DeliveryRow> delivery = jdbc.query(
                RECORD_DELIVERY_SQL,
                parameters,
                (resultSet, rowNumber) -> new DeliveryRow(
                        resultSet.getBoolean("was_inserted"),
                        resultSet.getLong("highest_contiguous_sequence_no")));
        if (delivery.size() != 1) {
            throw new IllegalStateException(
                    "agent-stream.v4 delivery append returned ambiguous authority");
        }
        DeliveryRow row = delivery.getFirst();
        if (!row.inserted()) {
            throw new IllegalStateException(
                    "new agent-stream.v4 source event replayed an existing delivery row");
        }
        if (row.highestContiguousSequenceNo() < command.sequenceNo()) {
            throw new IllegalStateException(
                    "agent-stream.v4 delivery watermark is behind the source event");
        }
        return new EventWriteReceipt(
                command.eventId(),
                canonicalEventJson,
                eventHash,
                row.highestContiguousSequenceNo());
    }

    public record EventWriteCommand(
            String eventId,
            String runId,
            String attemptId,
            long sequenceNo,
            AgentStreamEventV4.EventType eventType,
            Audience audience,
            Instant occurredAt,
            AgentStreamEventV4.Payload payload,
            String actorId,
            String audienceActorIdsJson) {

        public EventWriteCommand {
            requireText(eventId, "eventId");
            requireText(runId, "runId");
            requireText(attemptId, "attemptId");
            if (sequenceNo < 0) {
                throw new IllegalArgumentException("sequenceNo must not be negative");
            }
            eventType = Objects.requireNonNull(eventType, "eventType");
            audience = Objects.requireNonNull(audience, "audience");
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
            payload = Objects.requireNonNull(payload, "payload");
            requireText(actorId, "actorId");
            requireText(audienceActorIdsJson, "audienceActorIdsJson");
        }
    }

    public record EventWriteReceipt(
            String eventId,
            String canonicalEventJson,
            String eventSha256,
            long durableHighWatermark) {

        public EventWriteReceipt {
            requireText(eventId, "eventId");
            requireText(canonicalEventJson, "canonicalEventJson");
            requireText(eventSha256, "eventSha256");
            if (durableHighWatermark < 0) {
                throw new IllegalArgumentException("durableHighWatermark must not be negative");
            }
        }
    }

    private record DeliveryRow(boolean inserted, long highestContiguousSequenceNo) {}

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
