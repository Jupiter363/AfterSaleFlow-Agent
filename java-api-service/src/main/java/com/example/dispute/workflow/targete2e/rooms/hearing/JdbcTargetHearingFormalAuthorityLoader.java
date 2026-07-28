package com.example.dispute.workflow.targete2e.rooms.hearing;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter.CommitCommand;
import com.example.dispute.hearing.domain.HearingAuthorityExpectation;
import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.hearing.domain.HearingFormalTransition;
import com.example.dispute.hearing.domain.HearingWriterMode;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;

/** Loads the Java-owned Hearing cursor under the same outer finalization transaction. */
public final class JdbcTargetHearingFormalAuthorityLoader {
  private static final String SQL_CURSOR = """
      select p.tenant_surrogate, p.epoch_id, p.hearing_epoch, p.writer_mode,
             p.process_revision, p.room_revision, p.fencing_token,
             f.id as flow_id, f.current_stage, f.stage_sequence,
             s.id as source_stage_id
        from hearing_temporal_projection p
        join case_room_epoch e on e.id = p.epoch_id
          and e.tenant_surrogate = p.tenant_surrogate and e.case_id = p.case_id
          and e.room_type = 'HEARING' and e.room_epoch = p.hearing_epoch
          and e.fencing_token = p.fencing_token and e.writer_mode = p.writer_mode
        join hearing_flow_instance f on f.id = p.flow_instance_id and f.case_id = p.case_id
        join hearing_flow_stage s on s.flow_instance_id = f.id and s.case_id = f.case_id
          and s.stage_code = f.current_stage and s.stage_sequence = f.stage_sequence
       where p.tenant_surrogate = ? and p.case_id = ? and p.hearing_epoch = ?
         and p.writer_mode = 'TEMPORAL'
       for update of e, p, f, s
      """;

  private final DataSource dataSource;
  private final JdbcTemplate jdbc;

  public JdbcTargetHearingFormalAuthorityLoader(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.jdbc = new JdbcTemplate(dataSource);
  }

  public FormalAuthorityBinding load(
      CommitCommand command, TargetHearingCommandMaterialStore.Snapshot material) {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(material, "material");
    Connection connection = DataSourceUtils.getConnection(dataSource);
    boolean transactional = false;
    try {
      transactional = DataSourceUtils.isConnectionTransactional(connection, dataSource);
      if (!transactional) {
        throw new IllegalStateException("target Hearing authority load requires the outer transaction");
      }
    } finally {
      if (!transactional) {
        DataSourceUtils.releaseConnection(connection, dataSource);
      }
    }
    var graph = command.request().command();
    Cursor cursor = one(jdbc.query(SQL_CURSOR, JdbcTargetHearingFormalAuthorityLoader::cursor,
        graph.tenantSurrogate(), graph.caseId(), graph.roomEpoch()));
    require(cursor.roomEpoch == graph.roomEpoch()
        && cursor.fence == material.admission().roomFencingToken()
        && cursor.processRevision == graph.processRevision()
        && cursor.stage.name().equals(graph.stageCode())
        && cursor.stageSequence == graph.stageSequence(), "V051 command cursor binding");
    HearingAuthorityExpectation authority = new HearingAuthorityExpectation(
        graph.tenantSurrogate(), graph.caseId(), cursor.flowId, cursor.epochId, cursor.roomEpoch,
        HearingWriterMode.TEMPORAL, cursor.stage, cursor.stageSequence, cursor.processRevision,
        cursor.roomRevision, cursor.fence);
    HearingFlowStage target = next(cursor.stage);
    int targetSequence = cursor.stageSequence + 1;
    return new FormalAuthorityBinding(authority, cursor.sourceStageId, target, targetSequence,
        target.hasSharedPartyDeadline() ? graph.deadlineAt() : null,
        nextStageId(authority, target), nextStageInput(authority, target, targetSequence),
        graph.actorScope().actorId(), parents(authority));
  }

  private Parents parents(HearingAuthorityExpectation authority) {
    var dossier = jdbc.query("""
        select id, content_hash from hearing_trial_dossier
         where case_id = ? and flow_instance_id = ? for update
        """, (row, ignored) -> new Ref(row.getString(1), row.getString(2)),
        authority.caseId(), authority.flowInstanceId());
    var artifacts = jdbc.query("""
        select artifact_type, id, content_hash from hearing_flow_artifact
         where case_id = ? and flow_instance_id = ? for update
        """, (row, ignored) -> new Artifact(row.getString(1), new Ref(row.getString(2), row.getString(3))),
        authority.caseId(), authority.flowInstanceId());
    Ref proposal = null; Ref report = null;
    for (Artifact artifact : artifacts) {
      if ("JUDGE_PROPOSAL".equals(artifact.type)) proposal = unique(proposal, artifact.ref, "proposal");
      if ("JURY_REVIEW_REPORT".equals(artifact.type)) report = unique(report, artifact.ref, "jury report");
    }
    return new Parents(dossier.isEmpty() ? null : oneRef(dossier, "dossier"), proposal, report);
  }

  private static Ref oneRef(List<Ref> rows, String label) {
    if (rows.size() != 1) throw new IllegalStateException("target Hearing " + label + " is absent or ambiguous");
    return rows.getFirst();
  }
  private static Ref unique(Ref current, Ref candidate, String label) {
    if (current != null) throw new IllegalStateException("target Hearing " + label + " is ambiguous");
    return candidate;
  }

  private static Cursor cursor(ResultSet row, int ignored) throws SQLException {
    return new Cursor(row.getString("epoch_id"), row.getLong("hearing_epoch"),
        row.getLong("process_revision"), row.getLong("room_revision"), row.getLong("fencing_token"),
        row.getString("flow_id"), parseStage(row.getString("current_stage"), "current stage"),
        row.getInt("stage_sequence"), row.getString("source_stage_id"));
  }

  private static HearingFlowStage next(HearingFlowStage stage) {
    HearingFlowStage[] stages = HearingFlowStage.values();
    if (stage.ordinal() + 1 >= stages.length) {
      throw new IllegalStateException("target Hearing cursor is already closed");
    }
    return stages[stage.ordinal() + 1];
  }

  private static String nextStageId(HearingAuthorityExpectation authority, HearingFlowStage target) {
    String seed = authority.tenantSurrogate() + ':' + authority.caseId() + ':' + authority.roomEpoch()
        + ':' + authority.flowInstanceId() + ':' + target.name() + ':' + (target.ordinal() + 1);
    return "hearing-stage-" + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8))
        .toString().replace("-", "");
  }

  private static String nextStageInput(HearingAuthorityExpectation authority, HearingFlowStage target,
      int targetSequence) {
    ObjectNode input = JsonNodeFactory.instance.objectNode();
    input.put("case_id", authority.caseId());
    input.put("schema_version", "hearing_stage_input.v1");
    input.put("stage_code", target.name());
    input.put("stage_sequence", targetSequence);
    input.put("workflow_id", authority.flowInstanceId());
    return ContractJson.canonicalString(input);
  }

  private static Cursor one(List<Cursor> rows) {
    if (rows.size() != 1) throw new IllegalStateException("target Hearing authority is absent or ambiguous");
    return rows.getFirst();
  }

  private static HearingFlowStage parseStage(String value, String field) {
    try { return HearingFlowStage.valueOf(value); }
    catch (RuntimeException failure) { throw new IllegalStateException("target Hearing " + field + " is invalid", failure); }
  }

  private static void require(boolean condition, String label) {
    if (!condition) throw new IllegalStateException("target Hearing " + label + " drifted");
  }

  public record FormalAuthorityBinding(
      HearingAuthorityExpectation authority,
      String sourceStageId,
      HearingFlowStage resultStage,
      int resultStageSequence,
      java.time.Instant sharedDeadlineAt,
      String targetStageId,
      String targetInputJson,
      String actorId,
      Parents parents) {
    public FormalAuthorityBinding {
      authority = Objects.requireNonNull(authority, "authority");
      sourceStageId = HearingAuthorityExpectation.identifier(sourceStageId, "sourceStageId");
      resultStage = Objects.requireNonNull(resultStage, "resultStage");
      targetStageId = HearingAuthorityExpectation.identifier(targetStageId, "targetStageId");
      actorId = HearingAuthorityExpectation.identifier(actorId, "actorId");
      parents = Objects.requireNonNull(parents, "parents");
      if (resultStageSequence != resultStage.ordinal() + 1 || targetInputJson == null) {
        throw new IllegalArgumentException("target Hearing authority transition is invalid");
      }
    }

    public HearingFormalTransition transitionFor(String sourceOutputJson) {
      return new HearingFormalTransition(sourceStageId, resultStage, resultStageSequence,
          sharedDeadlineAt, targetStageId, targetInputJson, sourceOutputJson, actorId);
    }
  }

  public record Parents(Ref dossier, Ref proposal, Ref report) {}
  public record Ref(String id, String hash) {
    public Ref {
      id = HearingAuthorityExpectation.identifier(id, "id");
      if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("hash is invalid");
    }
  }
  private record Artifact(String type, Ref ref) {}

  private record Cursor(String epochId, long roomEpoch, long processRevision, long roomRevision,
      long fence, String flowId, HearingFlowStage stage, int stageSequence, String sourceStageId) {}
}
