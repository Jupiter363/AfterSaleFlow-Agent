package com.example.dispute.workflow.targete2e.rooms.review;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;

/**
 * PostgreSQL reader for the human-decision transactional outbox. It is deliberately read-only:
 * an AgentRun may observe a decision but cannot mint, alter, or mark the decision delivered.
 */
public final class JdbcTargetReviewOutcomeHandoffStore implements TargetReviewOutcomeHandoffStore {
  static final String EVENT_TYPE = "TARGET_REVIEW_OUTCOME_HANDOFF";
  private static final String SCHEMA = "target-e2e-review-outcome-handoff.v1";
  private final DataSource dataSource;
  private final ObjectMapper mapper;

  public JdbcTargetReviewOutcomeHandoffStore(DataSource dataSource, ObjectMapper mapper) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
  }

  @Override public Snapshot require(Route route) {
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try { return require(connection, route); }
    finally { DataSourceUtils.releaseConnection(connection, dataSource); }
  }

  @Override public Snapshot requireInTransaction(Connection transaction, Route route) {
    Objects.requireNonNull(transaction, "transaction");
    return require(transaction, route);
  }

  private Snapshot require(Connection connection, Route route) {
    String sql = """
        select id, event_payload_json::text
          from notification_outbox
         where case_id = ? and event_type = ?
           and event_payload_json ->> 'schema_version' = ?
           and event_payload_json ->> 'activation_id' = ?
           and event_payload_json ->> 'activation_manifest_hash' = ?
           and event_payload_json ->> 'tenant_surrogate' = ?
           and event_payload_json ->> 'command_id' = ?
           and event_payload_json ->> 'room_epoch' = ?
           and event_payload_json ->> 'room_fencing_token' = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, route.caseId()); statement.setString(2, EVENT_TYPE); statement.setString(3, SCHEMA);
      statement.setString(4, route.activationId()); statement.setString(5, route.activationManifestHash());
      statement.setString(6, route.tenantSurrogate()); statement.setString(7, route.commandId());
      statement.setString(8, Long.toString(route.roomEpoch())); statement.setString(9, Long.toString(route.roomFencingToken()));
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) throw new IllegalStateException("target Review human-decision handoff is absent");
        Snapshot snapshot = decode(rows.getString(1), rows.getString(2), route);
        if (rows.next()) throw new IllegalStateException("target Review human-decision handoff is ambiguous");
        return snapshot;
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("target Review human-decision handoff read failed", failure);
    }
  }

  private Snapshot decode(String rowId, String json, Route expected) {
    try {
      JsonNode node = mapper.readTree(json);
      if (!(node instanceof ObjectNode object)) throw new IllegalStateException("Review handoff is not an object");
      Document document = mapper.treeToValue(node, Document.class);
      if (!SCHEMA.equals(document.schemaVersion()) || !rowId.equals(document.handoffId())) {
        throw new IllegalStateException("target Review handoff schema or id is invalid");
      }
      ObjectNode preimage = object.deepCopy(); preimage.remove("handoff_hash");
      if (!document.handoffHash().equals(ContractJson.sha256Hex(preimage))) {
        throw new IllegalStateException("target Review handoff hash is not canonical");
      }
      Route actual = new Route(document.activationId(), document.activationManifestHash(), document.tenantSurrogate(),
          document.caseId(), document.commandId(), document.roomEpoch(), document.roomFencingToken());
      if (!actual.equals(expected)) throw new IllegalStateException("target Review handoff route conflicts with request");
      return new Snapshot(document.handoffId(), document.handoffHash(), actual, document.humanDecision());
    } catch (JsonProcessingException | IllegalArgumentException failure) {
      throw new IllegalStateException("target Review human-decision handoff is malformed", failure);
    }
  }

  /** Exact payload required from the Java human-decision transaction before a relay can run. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Document(String schemaVersion, String handoffId, String handoffHash, String activationId,
      String activationManifestHash, String tenantSurrogate, String caseId, String commandId, long roomEpoch,
      long roomFencingToken, TargetReviewHumanDecisionReceipt humanDecision) {}
}
