package com.example.dispute.workflow.targete2e.graph;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Reads the immutable Java Intake thread binding before a target JWS is issued. */
public final class JdbcTargetE2EAgentSessionResolver implements TargetE2EAgentSessionResolver {

  private static final String SQL = """
      select agent_session_id
        from case_intake_graph_thread_binding
       where tenant_surrogate = :tenantSurrogate
         and case_id = :caseId
         and room_type = 'INTAKE'
         and room_epoch = :roomEpoch
         and thread_id = :threadId
         and actor_id = :actorId
         and actor_role = :actorRole
         and audience = :audience
         and actor_scope_hash = :actorScopeHash
         and graph_key = :graphKey
         and graph_version = :graphVersion
         and checkpoint_schema_version = :checkpointSchemaVersion
      """;

  private final NamedParameterJdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JdbcTargetE2EAgentSessionResolver(DataSource dataSource, ObjectMapper objectMapper) {
    this.jdbc = new NamedParameterJdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  @Override
  public String resolve(RoomGraphCommand command) {
    Objects.requireNonNull(command, "command");
    if (command.roomType() != com.example.dispute.workflow.contract.v1.ContractTypes.RoomType.INTAKE) {
      return null;
    }
    MapSqlParameterSource parameters = new MapSqlParameterSource()
        .addValue("tenantSurrogate", command.tenantSurrogate())
        .addValue("caseId", command.caseId())
        .addValue("roomEpoch", command.roomEpoch())
        .addValue("threadId", command.threadId())
        .addValue("actorId", command.actorScope().actorId())
        .addValue("actorRole", command.actorScope().actorRole().name())
        .addValue("audience", command.actorScope().audience().name())
        .addValue("actorScopeHash", ContractJson.sha256Hex(objectMapper.valueToTree(command.actorScope())))
        .addValue("graphKey", command.graphKey())
        .addValue("graphVersion", command.graphVersion())
        .addValue("checkpointSchemaVersion", command.checkpointSchemaVersion());
    List<Map<String, Object>> rows = jdbc.queryForList(SQL, parameters);
    if (rows.size() != 1 || !(rows.getFirst().get("agent_session_id") instanceof String sessionId)
        || !sessionId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new IllegalStateException("target Intake command has no exact Java AgentSession binding");
    }
    return sessionId;
  }
}
