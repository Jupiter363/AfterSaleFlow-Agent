package com.example.dispute.workflow.targete2e.rooms.hearing;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter.CommitCommand;
import com.example.dispute.hearing.domain.HearingAuthorityExpectation;
import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.hearing.domain.HearingFormalTransition;
import com.example.dispute.hearing.domain.HearingWriterMode;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.ObjectMapper;
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
  private final ObjectMapper mapper;

  public JdbcTargetHearingFormalAuthorityLoader(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.jdbc = new JdbcTemplate(dataSource);
    this.mapper = new ObjectMapper();
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
        null,
        nextStageId(authority, target), nextStageInput(authority, target, targetSequence),
        graph.actorScope().actorId(), matrixAuthority(authority), parents(authority));
  }

  private MatrixAuthority matrixAuthority(HearingAuthorityExpectation authority) {
    return switch (authority.stage()) {
      case INTAKE_QUESTIONS_GENERATING -> oneMatrix(jdbc.query("""
          select (d.dossier_json -> 'case_fact_matrix' ->> 'matrix_version')::integer,
                 d.dossier_json -> 'case_fact_matrix' ->> 'content_hash'
            from case_intake_dossier d
           where d.case_id = ? and d.room_type = 'INTAKE'
           for update
          """, (row, ignored) -> new MatrixAuthority(row.getInt(1), row.getString(2)),
          authority.caseId()), "question source matrix");
      case EVIDENCE_REQUESTS_GENERATING -> oneMatrix(jdbc.query("""
          select (s.output_json -> 'case_fact_matrix' ->> 'matrix_version')::integer,
                 s.output_json -> 'case_fact_matrix' ->> 'content_hash'
            from hearing_flow_stage s
           where s.case_id = ? and s.flow_instance_id = ?
             and s.stage_code = 'INTAKE_SYNTHESIZING' and s.stage_status = 'COMPLETED'
           for update
          """, (row, ignored) -> new MatrixAuthority(row.getInt(1), row.getString(2)),
          authority.caseId(), authority.flowInstanceId()), "request successor matrix");
      default -> null;
    };
  }

  private Parents parents(HearingAuthorityExpectation authority) {
    var dossier = jdbc.query("""
        select id, content_hash from hearing_trial_dossier
         where case_id = ? and flow_instance_id = ? for update
        """, (row, ignored) -> new Ref(row.getString(1), row.getString(2)),
        authority.caseId(), authority.flowInstanceId());
    var artifacts = jdbc.query("""
        select artifact.artifact_type, artifact.id, artifact.content_hash,
               artifact.payload_json::text, stage.stage_code, stage.stage_sequence,
               stage.processor_role
          from hearing_flow_artifact artifact
          join hearing_flow_stage stage
            on stage.flow_instance_id = artifact.flow_instance_id
           and stage.case_id = artifact.case_id
           and stage.agent_run_id = artifact.agent_run_id
           and stage.stage_status = 'COMPLETED'
         where artifact.case_id = ? and artifact.flow_instance_id = ?
           and artifact.artifact_type in ('JUDGE_PROPOSAL', 'JURY_REVIEW_REPORT')
         for update of artifact, stage
        """, (row, ignored) -> verifiedArtifact(mapper, authority,
        row.getString(1), row.getString(2), row.getString(3), row.getString(4),
        row.getString(5), row.getInt(6), row.getString(7)),
        authority.caseId(), authority.flowInstanceId());
    return mapParents(dossier.isEmpty() ? null : oneRef(dossier, "dossier"), artifacts);
  }

  static Parents mapParents(Ref dossier, List<Artifact> artifacts) {
    ParentAuthority proposal = null; ParentAuthority report = null;
    Artifact reportArtifact = null;
    for (Artifact artifact : artifacts) {
      require(Objects.equals(dossier, artifact.dossierParent), "artifact dossier parent");
      if ("JUDGE_PROPOSAL".equals(artifact.type)) {
        proposal = unique(proposal, artifact.authority, "proposal");
      }
      if ("JURY_REVIEW_REPORT".equals(artifact.type)) {
        report = unique(report, artifact.authority, "jury report");
        reportArtifact = artifact;
      }
    }
    if (reportArtifact != null) {
      require(proposal != null
          && proposal.outer().equals(reportArtifact.formalProposalParent)
          && proposal.source().equals(reportArtifact.sourceProposalParent),
          "jury report parent pairing");
    }
    return new Parents(dossier, proposal, report);
  }

  private static Ref oneRef(List<Ref> rows, String label) {
    if (rows.size() != 1) throw new IllegalStateException("target Hearing " + label + " is absent or ambiguous");
    return rows.getFirst();
  }
  private static MatrixAuthority oneMatrix(List<MatrixAuthority> rows, String label) {
    if (rows.size() != 1) {
      throw new IllegalStateException("target Hearing " + label + " is absent or ambiguous");
    }
    return rows.getFirst();
  }
  private static ParentAuthority unique(
      ParentAuthority current, ParentAuthority candidate, String label) {
    if (current != null) throw new IllegalStateException("target Hearing " + label + " is ambiguous");
    return candidate;
  }

  static Artifact verifiedArtifact(ObjectMapper mapper, HearingAuthorityExpectation authority,
      String type, String id, String contentHash, String payloadJson,
      String stageCode, int stageSequence, String processorRole) {
    Objects.requireNonNull(mapper, "mapper");
    Objects.requireNonNull(authority, "authority");
    Ref outer = new Ref(id, contentHash);
    ObjectNode wrapper = parseObject(mapper, payloadJson, "formal artifact wrapper");
    ObjectNode unsigned = wrapper.deepCopy();
    unsigned.remove("content_hash");
    require(contentHash.equals(wrapper.path("content_hash").asText())
        && contentHash.equals(ContractJson.sha256Hex(unsigned)), "formal artifact wrapper hash");

    String wrapperSchema;
    String wrapperIdField;
    String sourceSchema;
    String sourceIdField;
    String sourceHashField;
    String expectedStage;
    int expectedSequence;
    String expectedRole;
    if ("JUDGE_PROPOSAL".equals(type)) {
      wrapperSchema = "judge_proposal.v1"; wrapperIdField = "proposal_id";
      sourceSchema = "hearing_judge_v1.v1"; sourceIdField = "proposal_id";
      sourceHashField = "proposal_hash"; expectedStage = "JUDGE_V1_GENERATING";
      expectedSequence = HearingFlowStage.JUDGE_V1_GENERATING.ordinal() + 1;
      expectedRole = "PRESIDING_JUDGE";
    } else if ("JURY_REVIEW_REPORT".equals(type)) {
      wrapperSchema = "jury_review_report.v1"; wrapperIdField = "report_id";
      sourceSchema = "hearing_jury_review.v1"; sourceIdField = "review_id";
      sourceHashField = "review_hash"; expectedStage = "JURY_REVIEWING";
      expectedSequence = HearingFlowStage.JURY_REVIEWING.ordinal() + 1;
      expectedRole = "JURY_PANEL";
    } else {
      throw new IllegalStateException("target Hearing formal artifact type is invalid");
    }
    require(wrapperSchema.equals(wrapper.path("schema_version").asText())
        && id.equals(wrapper.path(wrapperIdField).asText()), "formal artifact wrapper identity");
    ObjectNode source = requiredObject(wrapper, "proposal", "nested Python source");
    require(sourceSchema.equals(source.path("schema_version").asText())
        && authority.caseId().equals(source.path("case_id").asText())
        && authority.flowInstanceId().equals(source.path("workflow_id").asText())
        && source.path("stage_sequence").asInt(-1) == expectedSequence,
        "nested Python source coordinates");
    Ref nested = new Ref(source.path(sourceIdField).asText(), source.path(sourceHashField).asText());
    require(nested.hash().equals(JdbcTargetHearingAgentStageInputFactory.pythonContentHash(
        mapper, source, sourceHashField)), "nested Python source hash");
    require(expectedStage.equals(stageCode) && expectedSequence == stageSequence
        && expectedRole.equals(processorRole), "formal artifact stage role");
    Ref dossierParent = ref(wrapper, "trial_dossier_id", "trial_dossier_hash");
    require(dossierParent.equals(ref(source, "trial_dossier_id", "trial_dossier_hash")),
        "nested Python dossier parent");
    Ref formalProposalParent = "JURY_REVIEW_REPORT".equals(type)
        ? ref(wrapper, "proposal_id", "proposal_content_hash") : null;
    Ref sourceProposalParent = "JURY_REVIEW_REPORT".equals(type)
        ? ref(source, "reviewed_proposal_id", "reviewed_proposal_hash") : null;
    return new Artifact(type, new ParentAuthority(outer, nested), dossierParent,
        formalProposalParent, sourceProposalParent);
  }

  private static ObjectNode parseObject(ObjectMapper mapper, String json, String label) {
    try {
      var value = mapper.readTree(json);
      if (value instanceof ObjectNode object) return object;
    } catch (Exception failure) {
      throw new IllegalStateException("target Hearing " + label + " is invalid", failure);
    }
    throw new IllegalStateException("target Hearing " + label + " is invalid");
  }

  private static ObjectNode requiredObject(ObjectNode source, String field, String label) {
    if (source.path(field) instanceof ObjectNode object) return object;
    throw new IllegalStateException("target Hearing " + label + " is invalid");
  }

  private static Ref ref(ObjectNode source, String idField, String hashField) {
    return new Ref(source.path(idField).asText(), source.path(hashField).asText());
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
      MatrixAuthority matrixAuthority,
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
      boolean actionRequiresMatrix = authority.stage() == HearingFlowStage.INTAKE_QUESTIONS_GENERATING
          || authority.stage() == HearingFlowStage.EVIDENCE_REQUESTS_GENERATING;
      if (actionRequiresMatrix != (matrixAuthority != null)) {
        throw new IllegalArgumentException("target Hearing action matrix authority is invalid");
      }
    }

    public HearingFormalTransition transitionFor(String sourceOutputJson) {
      if (resultStage.hasSharedPartyDeadline() != (sharedDeadlineAt != null)) {
        throw new IllegalStateException("target Hearing party deadline authority is absent");
      }
      return new HearingFormalTransition(sourceStageId, resultStage, resultStageSequence,
          sharedDeadlineAt, targetStageId, targetInputJson, sourceOutputJson, actorId);
    }

    public FormalAuthorityBinding withPartyStageDeadline(
        TargetHearingCommandMaterial.PartyStageAuthority partyAuthority,
        java.time.Instant committedAt) {
      if (!resultStage.hasSharedPartyDeadline()) {
        if (sharedDeadlineAt != null) {
          throw new IllegalStateException("target Hearing non-party stage carries a deadline");
        }
        return this;
      }
      if (sharedDeadlineAt != null || partyAuthority == null || committedAt == null) {
        throw new IllegalStateException("target Hearing party deadline authority is absent");
      }
      require(partyAuthority.tenantSurrogate().equals(authority.tenantSurrogate())
          && partyAuthority.caseId().equals(authority.caseId())
          && partyAuthority.roomEpoch() == authority.roomEpoch()
          && partyAuthority.fencingToken() == authority.fencingToken(),
          "party deadline coordinates");
      java.time.Instant hearingDeadline = partyAuthority.hearingDeadlineAt();
      if (!hearingDeadline.isAfter(committedAt)) {
        throw new IllegalStateException("target Hearing party deadline is already elapsed");
      }
      java.time.Instant windowDeadline;
      try {
        windowDeadline = committedAt.plusSeconds(partyAuthority.partyStageWindowSeconds());
      } catch (RuntimeException failure) {
        throw new IllegalStateException("target Hearing party deadline cannot be derived", failure);
      }
      java.time.Instant derived = hearingDeadline.isBefore(windowDeadline)
          ? hearingDeadline : windowDeadline;
      return new FormalAuthorityBinding(authority, sourceStageId, resultStage,
          resultStageSequence, derived, targetStageId, targetInputJson, actorId,
          matrixAuthority, parents);
    }
  }

  public record MatrixAuthority(int version, String hash) {
    public MatrixAuthority {
      if (version < 1 || hash == null || !hash.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("target Hearing matrix authority is invalid");
      }
    }
  }
  public record Parents(
      Ref dossier, ParentAuthority proposalAuthority, ParentAuthority reportAuthority) {
    public Ref proposal() { return proposalAuthority == null ? null : proposalAuthority.outer(); }
    public Ref report() { return reportAuthority == null ? null : reportAuthority.outer(); }
    public Ref proposalSource() { return proposalAuthority == null ? null : proposalAuthority.source(); }
    public Ref reportSource() { return reportAuthority == null ? null : reportAuthority.source(); }
  }
  public record ParentAuthority(Ref outer, Ref source) {
    public ParentAuthority {
      outer = Objects.requireNonNull(outer, "outer");
      source = Objects.requireNonNull(source, "source");
    }
  }
  public record Ref(String id, String hash) {
    public Ref {
      id = HearingAuthorityExpectation.identifier(id, "id");
      if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("hash is invalid");
    }
  }
  record Artifact(String type, ParentAuthority authority, Ref dossierParent,
      Ref formalProposalParent, Ref sourceProposalParent) {}

  private record Cursor(String epochId, long roomEpoch, long processRevision, long roomRevision,
      long fence, String flowId, HearingFlowStage stage, int stageSequence, String sourceStageId) {}
}
