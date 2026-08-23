package com.example.dispute.workflow.targete2e.rooms.hearing;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes one strict, replayable case event in the same transaction that creates an automatic
 * Hearing AgentRun. The event is a discovery descriptor only; case access plus the run's durable
 * stream projection mode remain the authority for the referenced SSE endpoint.
 */
public final class JdbcTargetHearingAgentRunStartedPublisher
    implements TargetHearingAgentRunStartedPublisher {

  static final String EVENT_TYPE = "AGENT_RUN_STARTED";
  static final String EVENT_SCHEMA = "target-hearing-agent-run-started.v3";
  static final String INTERNAL_EVENT_SCHEMA = "target-hearing-agent-run-started.v2";
  static final String LEGACY_EVENT_SCHEMA = "target-hearing-agent-run-started.v1";
  static final String STREAM_ACCESS = "ACTOR_VISIBLE";
  private static final String CONTROL_ACTOR = "hearing-control";
  private static final List<String> COURT_AUDIENCE =
      List.of("USER", "MERCHANT", "PLATFORM_REVIEWER", "ADMIN");

  private final DataSource dataSource;
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final Consumer<String> afterCommitNotifier;

  public JdbcTargetHearingAgentRunStartedPublisher(
      DataSource dataSource, ObjectMapper mapper, Consumer<String> afterCommitNotifier) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.jdbc = new JdbcTemplate(this.dataSource);
    this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
    this.afterCommitNotifier =
        Objects.requireNonNull(afterCommitNotifier, "afterCommitNotifier");
  }

  @Override
  public void publish(Event event) {
    Objects.requireNonNull(event, "event");
    requireCallerTransaction();
    lockCourt(event);
    Expected expected = expected(event, EVENT_SCHEMA);
    Stored existing = stored(event.caseId(), expected.eventKey());
    if (existing != null) {
      String storedSchema = existing.payload().path("schema_version").asText("");
      require(
          EVENT_SCHEMA.equals(storedSchema)
              || INTERNAL_EVENT_SCHEMA.equals(storedSchema)
              || LEGACY_EVENT_SCHEMA.equals(storedSchema),
          "Hearing AgentRun start event schema is unsupported");
      requireSame(existing, expected(event, storedSchema));
      return;
    }
    Long nextSequence = jdbc.queryForObject(
        "select coalesce(max(sequence_no), 0) + 1 from case_timeline_event where case_id = ?",
        Long.class,
        event.caseId());
    require(nextSequence != null && nextSequence > 0, "Hearing AgentRun event sequence is invalid");
    OffsetDateTime startedAt = expected.startedAt().atOffset(ZoneOffset.UTC);
    int inserted = jdbc.update(
        """
        insert into case_timeline_event (
          id, case_id, dossier_id, sequence_no, room_id, event_type, event_time,
          source_refs_json, event_json, audience_json, audience_actor_ids_json,
          event_key, created_at, created_by)
        values (?, ?, null, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb),
          cast(? as jsonb), '[]'::jsonb, ?, ?, ?)
        on conflict (case_id, event_key) where event_key is not null do nothing
        """,
        expected.id(),
        event.caseId(),
        nextSequence,
        event.roomId(),
        EVENT_TYPE,
        startedAt,
        canonical(expected.sourceRefs()),
        canonical(expected.payload()),
        canonical(mapper.valueToTree(COURT_AUDIENCE)),
        expected.eventKey(),
        startedAt,
        CONTROL_ACTOR);
    require(inserted == 1, "Hearing AgentRun start event insert failed");
    requireSame(
        Objects.requireNonNull(
            stored(event.caseId(), expected.eventKey()),
            "Hearing AgentRun start event was not stored"),
        expected);
    registerAfterCommit(event.caseId());
  }

  private void lockCourt(Event event) {
    List<String> rooms = jdbc.query(
        """
        select room.id
          from fulfillment_dispute_case dispute
          join case_room room on room.case_id = dispute.id
         where dispute.id = ? and room.id = ? and room.room_type = 'HEARING'
           and room.room_status in ('OPEN', 'WAITING')
         for update of dispute, room
        """,
        (row, ignored) -> row.getString(1),
        event.caseId(),
        event.roomId());
    require(rooms.size() == 1 && event.roomId().equals(rooms.getFirst()),
        "Hearing AgentRun start room is absent or ambiguous");
  }

  private Expected expected(Event event, String schemaVersion) {
    Instant startedAt = event.startedAt().truncatedTo(ChronoUnit.MICROS);
    ObjectNode payload = mapper.createObjectNode();
    payload.put("schema_version", schemaVersion);
    payload.put("tenant_surrogate", event.tenantSurrogate());
    payload.put("case_id", event.caseId());
    payload.put("room_id", event.roomId());
    payload.put("room_epoch", event.roomEpoch());
    payload.put("fencing_token", event.fencingToken());
    payload.put("flow_instance_id", event.flowInstanceId());
    payload.put("stage_code", event.stageCode());
    payload.put("stage_sequence", event.stageSequence());
    payload.put("operation", event.operation());
    payload.put("command_id", event.commandId());
    payload.put("agent_run_id", event.agentRunId());
    payload.put("attempt_id", event.attemptId());
    payload.put("status", event.status());
    if (LEGACY_EVENT_SCHEMA.equals(schemaVersion)) {
      payload.put(
          "stream_url",
          "/api/agent-runs/" + event.agentRunId() + "/events");
    } else if (INTERNAL_EVENT_SCHEMA.equals(schemaVersion)) {
      payload.put("stream_access", "INTERNAL_SYSTEM_ONLY");
    } else {
      payload.put("stream_access", STREAM_ACCESS);
      payload.put(
          "stream_url",
          "/api/agent-runs/" + event.agentRunId() + "/events");
    }
    payload.put("started_at", startedAt.toString());
    ArrayNode sourceRefs = mapper.createArrayNode();
    sourceRefs.add(event.agentRunId());
    sourceRefs.add(event.attemptId());
    sourceRefs.add(event.commandId());
    String key = "hearing-agent-run-started:" + event.agentRunId();
    String id = "EVENT_HEARING_RUN_"
        + ContractJson.sha256Hex(
                mapper.valueToTree(List.of(event.caseId(), event.roomId(), key)))
            .substring(0, 32);
    return new Expected(id, event.roomId(), key, startedAt, sourceRefs, payload);
  }

  private Stored stored(String caseId, String eventKey) {
    List<Stored> rows = jdbc.query(
        """
        select id, room_id, event_type, event_time, source_refs_json::text,
               event_json::text, audience_json::text, audience_actor_ids_json::text,
               event_key, created_at, created_by
          from case_timeline_event
         where case_id = ? and event_key = ?
         for update
        """,
        (row, ignored) -> new Stored(
            row.getString(1),
            row.getString(2),
            row.getString(3),
            row.getObject(4, OffsetDateTime.class).toInstant(),
            parse(row.getString(5)),
            parse(row.getString(6)),
            parse(row.getString(7)),
            parse(row.getString(8)),
            row.getString(9),
            row.getObject(10, OffsetDateTime.class).toInstant(),
            row.getString(11)),
        caseId,
        eventKey);
    require(rows.size() <= 1, "Hearing AgentRun start event is ambiguous");
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private void requireSame(Stored stored, Expected expected) {
    require(
        expected.id().equals(stored.id())
            && expected.roomId().equals(stored.roomId())
            && EVENT_TYPE.equals(stored.eventType())
            && expected.startedAt().equals(stored.eventTime())
            && expected.startedAt().equals(stored.createdAt())
            && canonical(expected.sourceRefs()).equals(canonical(stored.sourceRefs()))
            && canonical(expected.payload()).equals(canonical(stored.payload()))
            && canonical(mapper.valueToTree(COURT_AUDIENCE))
                .equals(canonical(stored.audience()))
            && canonical(mapper.createArrayNode())
                .equals(canonical(stored.audienceActorIds()))
            && expected.eventKey().equals(stored.eventKey())
            && CONTROL_ACTOR.equals(stored.createdBy()),
        "Hearing AgentRun start event replay drifted");
  }

  private void requireCallerTransaction() {
    require(
        TransactionSynchronizationManager.isActualTransactionActive(),
        "Hearing AgentRun start requires the caller transaction");
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      require(
          DataSourceUtils.isConnectionTransactional(connection, dataSource)
              && !connection.getAutoCommit(),
          "Hearing AgentRun start requires the bound database transaction");
    } catch (SQLException failure) {
      throw new IllegalStateException("cannot inspect Hearing AgentRun start transaction", failure);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  private void registerAfterCommit(String caseId) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            afterCommitNotifier.accept(caseId);
          }
        });
  }

  private String canonical(JsonNode value) {
    return ContractJson.canonicalString(value);
  }

  private JsonNode parse(String value) {
    try {
      JsonNode parsed = mapper.readTree(value);
      if (parsed == null) {
        throw new IllegalStateException("Hearing AgentRun event contains null JSON");
      }
      return parsed;
    } catch (Exception failure) {
      throw new IllegalStateException("Hearing AgentRun event contains invalid JSON", failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  private record Expected(
      String id,
      String roomId,
      String eventKey,
      Instant startedAt,
      JsonNode sourceRefs,
      JsonNode payload) {}

  private record Stored(
      String id,
      String roomId,
      String eventType,
      Instant eventTime,
      JsonNode sourceRefs,
      JsonNode payload,
      JsonNode audience,
      JsonNode audienceActorIds,
      String eventKey,
      Instant createdAt,
      String createdBy) {}
}
