package com.example.dispute.workflow.runtime.rooms.review;

import com.example.dispute.review.domain.ReviewPacketContentHasher;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWireTypes.RuntimeMode;
import com.example.dispute.workflow.contract.outcome.v1.OutcomeWorkflowStart;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Locks the authoritative Review facts while deriving the exact Outcome start. No browser, Graph,
 * or provision-payload reference is trusted as a review artifact.
 */
public final class JdbcTargetReviewOutcomeStartBindingPort implements TargetReviewOutcomeStartBindingPort {
  static final String SQL = """
      select epoch.id as epoch_id, epoch.tenant_surrogate, epoch.case_id, epoch.room_id,
             epoch.room_type, epoch.room_epoch, epoch.writer_mode, epoch.lifecycle_status,
             epoch.provisioning_status, epoch.fencing_token, epoch.process_revision, epoch.room_revision,
             epoch.room_temporal_workflow_id, epoch.room_workflow_build_id, epoch.graph_version,
             activation.activation_id, activation.activation_manifest_hash,
             activation_state.expires_at as activation_expires_at,
             task.id as review_task_id, task.plan_id as task_plan_id, task.packet_id as task_packet_id,
             task.policy_decision_id as task_policy_decision_id,
             task.created_at as review_opened_at, task.due_at as review_due_at,
             packet.id as packet_id, packet.case_id as packet_case_id, packet.plan_id as packet_plan_id,
             packet.packet_version, packet.case_summary_json::text, packet.claims_json::text,
             packet.issues_json::text, packet.evidence_matrix_json::text, packet.draft_json::text,
             packet.remedy_json::text, packet.risk_flags_json::text, packet.packet_status,
             packet.case_version, packet.dossier_version, packet.issue_version,
             packet.adjudication_draft_version, packet.deliberation_report_version,
             packet.remedy_plan_version, packet.ruleset_version, packet.prompt_version,
             packet.skill_version, packet.profile_version, packet.action_hash,
             packet.agent_run_refs_json::text, packet.frozen, packet.frozen_at, packet.expires_at,
             policy.id as policy_decision_id, policy.policy_version
        from case_room_epoch epoch
        join production_runtime_room_epoch_binding activation
          on activation.epoch_id = epoch.id
         and activation.tenant_surrogate = epoch.tenant_surrogate
         and activation.case_id = epoch.case_id
         and activation.room_type = epoch.room_type
         and activation.room_epoch = epoch.room_epoch
         and activation.room_fencing_token = epoch.fencing_token
        join production_runtime_activation activation_state
          on activation_state.activation_id = activation.activation_id
         and activation_state.manifest_hash = activation.activation_manifest_hash
         and activation_state.execution_lane = activation.execution_lane
         and activation_state.isolated_domain_db_binding_hash = activation.isolated_domain_db_binding_hash
        join production_runtime_review_epoch_task_binding review_binding
          on review_binding.epoch_id = epoch.id
         and review_binding.tenant_surrogate = epoch.tenant_surrogate
         and review_binding.case_id = epoch.case_id
         and review_binding.room_epoch = epoch.room_epoch
         and review_binding.room_fencing_token = epoch.fencing_token
        join review_task task
          on task.id = review_binding.review_task_id
         and task.case_id = review_binding.case_id
         and task.plan_id = review_binding.plan_id
         and task.policy_decision_id = review_binding.policy_decision_id
         and task.task_status in ('PENDING', 'ASSIGNED', 'IN_REVIEW')
        join review_packet packet
          on packet.id = task.packet_id and packet.case_id = task.case_id and packet.plan_id = task.plan_id
        join remedy_plan plan
          on plan.id = task.plan_id and plan.case_id = task.case_id and plan.adjudication_draft_id is not null
        join adjudication_draft draft
          on draft.id = plan.adjudication_draft_id and draft.case_id = task.case_id
        join approval_policy_decision policy
          on policy.id = task.policy_decision_id
         and policy.case_id = task.case_id
         and policy.plan_id = task.plan_id
       where epoch.id = ? and epoch.tenant_surrogate = ? and epoch.case_id = ?
         and epoch.room_id = ? and epoch.room_type = 'REVIEW' and epoch.room_epoch = ?
         and epoch.fencing_token = ?
         and activation_state.lifecycle_status = 'ACTIVE'
         and activation_state.expires_at > current_timestamp
       for update of epoch, activation, activation_state, review_binding, task, packet, plan, draft, policy
      """;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final ObjectMapper mapper;

  public JdbcTargetReviewOutcomeStartBindingPort(
      DataSource dataSource, TransactionTemplate transactions, ObjectMapper mapper) {
    this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
  }

  @Override
  public Binding bind(ProvisionRoomEpoch provision) {
    requireReviewProvision(provision);
    return Objects.requireNonNull(
        transactions.execute(ignored -> bindLocked(provision)), "Review start binding transaction returned null");
  }

  private Binding bindLocked(ProvisionRoomEpoch provision) {
    List<Row> rows = jdbc.query(SQL, (rs, ignored) -> row(rs), provision.epochId(),
        provision.tenantSurrogate(), provision.caseId(), provision.roomId(), provision.roomEpoch(),
        provision.fencingToken());
    if (rows.isEmpty()) {
      throw new IllegalStateException(
          "Review Outcome start requires a durable open review_task and frozen review_packet before epoch provisioning");
    }
    if (rows.size() != 1) {
      throw new IllegalStateException("Review Outcome start has ambiguous open review_task facts");
    }
    Row value = rows.getFirst();
    requireEpochMatches(provision, value);
    requireFrozenPacket(value);

    TargetReviewFrozenExecutionContract execution = TargetReviewFrozenExecutionContract.fromFrozenFacts(
        value.packetId, value.actionHash, value.remedyJson, mapper, value.roomRevision);
    String packetHash = packetHash(value);
    String draftHash = ContractJson.sha256Hex(parse(value.draftJson, "frozen review draft"));
    Instant openedAt = value.openedAt.toInstant();
    Instant deadline = reviewDeadline(value);
    OutcomeWorkflowStart start = new OutcomeWorkflowStart(
        OutcomeWorkflowStart.SCHEMA_VERSION,
        provision.roomWorkflowId(), provision.caseId(), value.reviewTaskId, value.packetId, packetHash,
        value.packetId + ":draft", draftHash, execution.actionSnapshotRef(), execution.actionSnapshotHash(),
        execution.requiredOperationSetRef(), execution.requiredOperationSetHash(),
        execution.requiredOperationCount(), value.epoch, execution.kernelRevision(), value.fence, openedAt, deadline,
        runtimeMode(provision.writerMode()), provision.roomWorkflowBuildId(), value.policyVersion,
        provision.graphVersion(), value.promptVersion, value.profileVersion,
        provision.writerMode() == WriterMode.SHADOW);
    return new Binding(value.activationId, value.activationManifestHash, start);
  }

  private static void requireReviewProvision(ProvisionRoomEpoch provision) {
    provision = Objects.requireNonNull(provision, "provision");
    if (provision.roomType() != RoomType.REVIEW || provision.roomWorkflowBuildId() == null
        || provision.roomWorkflowBuildId().isBlank()) {
      throw new IllegalArgumentException("Outcome start binding requires a v2 REVIEW ProvisionRoomEpoch");
    }
  }

  private static void requireEpochMatches(ProvisionRoomEpoch provision, Row value) {
    if (!"REVIEW".equals(value.roomType)
        || !allowedProvisioningState(value.lifecycleStatus, value.provisioningStatus)
        || !provision.writerMode().name().equals(value.writerMode)
        || !provision.tenantSurrogate().equals(value.tenant) || !provision.caseId().equals(value.caseId)
        || !provision.roomId().equals(value.roomId) || provision.roomEpoch() != value.epoch
        || provision.fencingToken() != value.fence
        || provision.initialProcessRevision() != value.processRevision
        || provision.initialRoomRevision() != value.roomRevision
        || !provision.roomWorkflowId().equals(value.roomWorkflowId)
        || !provision.roomWorkflowBuildId().equals(value.roomWorkflowBuildId)
        || !provision.graphVersion().equals(value.graphVersion)) {
      throw new IllegalStateException("Review Outcome start provision conflicts with the locked epoch authority");
    }
  }

  /**
   * The start binding runs inside the Temporal provisioning update, before the bootstrap relay can
   * persist the child run id and advance the epoch to ACTIVE/READY. A redelivered, already committed
   * provisioning may observe ACTIVE/READY; mixed pairs are never authoritative.
   */
  static boolean allowedProvisioningState(String lifecycleStatus, String provisioningStatus) {
    return ("PROVISIONING".equals(lifecycleStatus)
            && "PROVISIONING".equals(provisioningStatus))
        || ("ACTIVE".equals(lifecycleStatus) && "READY".equals(provisioningStatus));
  }

  private static void requireFrozenPacket(Row value) {
    if (!value.packetId.equals(value.taskPacketId) || !value.taskPlanId.equals(value.packetPlanId)
        || !value.caseId.equals(value.packetCaseId) || !value.frozen || !"FROZEN".equals(value.packetStatus)
        || value.openedAt == null || value.expiresAt == null || value.activationExpiresAt == null
        || value.actionHash == null
        || !value.actionHash.matches("[0-9a-f]{64}") || value.taskPolicyDecisionId == null
        || !value.taskPolicyDecisionId.equals(value.policyDecisionId) || value.policyVersion == null
        || value.policyVersion.isBlank()) {
      throw new IllegalStateException("Review Outcome start frozen task, packet, action, or policy binding is invalid");
    }
  }

  private JsonNode parse(String json, String fact) {
    try {
      return mapper.readTree(json);
    } catch (Exception failure) {
      throw new IllegalStateException(fact + " is not canonical JSON", failure);
    }
  }

  private String packetHash(Row value) {
    Map<String, Object> content = new TreeMap<>();
    content.put("action_hash", value.actionHash);
    content.put("adjudication_draft_version", value.adjudicationDraftVersion);
    content.put("agent_run_refs", parse(value.agentRunRefsJson, "agent run refs"));
    content.put("case_id", value.packetCaseId);
    content.put("case_summary", parse(value.caseSummaryJson, "case summary"));
    content.put("case_version", value.caseVersion);
    content.put("claims", parse(value.claimsJson, "claims"));
    content.put("deliberation_report_version", value.deliberationReportVersion);
    content.put("dossier_version", value.dossierVersion);
    content.put("draft", parse(value.draftJson, "frozen review draft"));
    content.put("evidence_matrix", parse(value.evidenceMatrixJson, "evidence matrix"));
    content.put("expires_at", value.expiresAt.toInstant().toString());
    content.put("frozen_at", value.frozenAt.toInstant().toString());
    content.put("issue_version", value.issueVersion);
    content.put("issues", parse(value.issuesJson, "issues"));
    content.put("packet_id", value.packetId);
    content.put("packet_status", value.packetStatus);
    content.put("packet_version", value.packetVersion);
    content.put("plan_id", value.packetPlanId);
    content.put("profile_version", value.profileVersion);
    content.put("prompt_version", value.promptVersion);
    content.put("remedy", parse(value.remedyJson, "frozen review remedy"));
    content.put("remedy_plan_version", value.remedyPlanVersion);
    content.put("risk_flags", parse(value.riskFlagsJson, "risk flags"));
    content.put("ruleset_version", value.rulesetVersion);
    content.put("skill_version", value.skillVersion);
    return ReviewPacketContentHasher.hash(mapper, content);
  }

  private static Instant reviewDeadline(Row value) {
    return earliestDeadline(
        value.dueAt == null ? null : value.dueAt.toInstant(),
        value.expiresAt.toInstant(),
        value.activationExpiresAt.toInstant());
  }

  /**
   * Human Review is part of the signed Target activation, so its workflow deadline may never
   * outlive that activation even when the generic task or packet carries a longer business SLA.
   */
  static Instant earliestDeadline(
      Instant taskDeadline, Instant packetDeadline, Instant activationDeadline) {
    Objects.requireNonNull(packetDeadline, "packetDeadline");
    Objects.requireNonNull(activationDeadline, "activationDeadline");
    Instant deadline = packetDeadline.isBefore(activationDeadline)
        ? packetDeadline : activationDeadline;
    return taskDeadline == null || taskDeadline.isAfter(deadline) ? deadline : taskDeadline;
  }

  private static RuntimeMode runtimeMode(WriterMode writerMode) {
    return writerMode == WriterMode.SHADOW
        ? RuntimeMode.JAVA_SIGNED_SYNTHETIC_NOOP_SHADOW : RuntimeMode.TEMPORAL;
  }

  private static Row row(ResultSet rs) throws SQLException {
    return new Row(rs.getString("tenant_surrogate"), rs.getString("case_id"), rs.getString("room_id"),
        rs.getString("room_type"), rs.getLong("room_epoch"), rs.getString("writer_mode"),
        rs.getString("lifecycle_status"), rs.getString("provisioning_status"), rs.getLong("fencing_token"),
        rs.getLong("process_revision"), rs.getLong("room_revision"), rs.getString("room_temporal_workflow_id"),
        rs.getString("room_workflow_build_id"), rs.getString("graph_version"), rs.getString("activation_id"),
        rs.getString("activation_manifest_hash"), offset(rs, "activation_expires_at"),
        rs.getString("review_task_id"),
        rs.getString("task_plan_id"), rs.getString("task_packet_id"),
        rs.getString("task_policy_decision_id"), offset(rs, "review_opened_at"),
        offset(rs, "review_due_at"), rs.getString("packet_id"), rs.getString("packet_case_id"),
        rs.getString("packet_plan_id"), rs.getInt("packet_version"), rs.getString("case_summary_json"),
        rs.getString("claims_json"), rs.getString("issues_json"), rs.getString("evidence_matrix_json"),
        rs.getString("draft_json"), rs.getString("remedy_json"), rs.getString("risk_flags_json"),
        rs.getString("packet_status"), rs.getLong("case_version"), rs.getInt("dossier_version"),
        rs.getInt("issue_version"), rs.getInt("adjudication_draft_version"),
        rs.getInt("deliberation_report_version"), rs.getInt("remedy_plan_version"),
        rs.getString("ruleset_version"), rs.getString("prompt_version"), rs.getString("skill_version"),
        rs.getString("profile_version"), rs.getString("action_hash"), rs.getString("agent_run_refs_json"),
        rs.getBoolean("frozen"), offset(rs, "frozen_at"), offset(rs, "expires_at"),
        rs.getString("policy_decision_id"), rs.getString("policy_version"));
  }

  private static OffsetDateTime offset(ResultSet rs, String column) throws SQLException {
    var value = rs.getObject(column, OffsetDateTime.class);
    return rs.wasNull() ? null : value;
  }

  private record Row(String tenant, String caseId, String roomId, String roomType, long epoch,
      String writerMode, String lifecycleStatus, String provisioningStatus, long fence, long processRevision,
      long roomRevision, String roomWorkflowId, String roomWorkflowBuildId, String graphVersion,
      String activationId, String activationManifestHash, OffsetDateTime activationExpiresAt,
      String reviewTaskId, String taskPlanId,
      String taskPacketId, String taskPolicyDecisionId, OffsetDateTime openedAt, OffsetDateTime dueAt,
      String packetId, String packetCaseId, String packetPlanId, int packetVersion, String caseSummaryJson,
      String claimsJson, String issuesJson, String evidenceMatrixJson, String draftJson, String remedyJson,
      String riskFlagsJson, String packetStatus, long caseVersion, int dossierVersion, int issueVersion,
      int adjudicationDraftVersion, int deliberationReportVersion, int remedyPlanVersion, String rulesetVersion,
      String promptVersion, String skillVersion, String profileVersion, String actionHash, String agentRunRefsJson,
      boolean frozen, OffsetDateTime frozenAt, OffsetDateTime expiresAt, String policyDecisionId,
      String policyVersion) {}
}
