package com.example.dispute.workflow.runtime.ingress.rooms;

import com.example.dispute.review.domain.ActionSnapshotHasher;
import com.example.dispute.hearing.domain.HearingDecisionAction;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads the Review decision's durable authority facts while the command admission transaction is open. */
public final class JdbcTargetReviewInvocationFactsLoader {
  private static final String TARGET_HEARING_PROMPT_VERSION = "hearing-flow.v2";
  private static final String TARGET_HEARING_PROFILE_VERSION = "hearing-judge-v2";
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
  private static final Set<String> NON_EXECUTING_DECISIONS = Set.of(
      "REJECT", "REQUEST_MORE_EVIDENCE", "ESCALATE_MANUAL");
  static final String SQL = """
      select command.command_id, command.tenant_surrogate, command.case_id, command.room_epoch,
             command.actor_id, command.actor_role, command.expected_process_revision,
             command.payload_sha256, command.deadline_at,
             event.id as event_id, event.event_json::text as event_json,
             task.id as review_task_id, task.plan_id as task_plan_id,
             task.packet_id as task_packet_id, task.policy_decision_id as task_policy_decision_id,
             task.task_status,
             task.assigned_reviewer_id, task.due_at,
             packet.id as packet_id, packet.case_id as packet_case_id, packet.plan_id as packet_plan_id,
             packet.packet_version, packet.case_summary_json::text, packet.claims_json::text,
             packet.issues_json::text, packet.evidence_matrix_json::text, packet.draft_json::text,
             packet.remedy_json::text, packet.risk_flags_json::text, packet.ruleset_version,
             packet.prompt_version, packet.profile_version, packet.action_hash,
             packet.agent_run_refs_json::text, packet.packet_status, packet.frozen,
             packet.frozen_at, packet.expires_at, packet.case_version, packet.dossier_version,
             packet.issue_version, packet.adjudication_draft_version,
             packet.deliberation_report_version, packet.remedy_plan_version,
             approval.id as approval_record_id, approval.review_task_id as approval_task_id,
             approval.review_packet_id as approval_packet_id, approval.review_packet_version,
             approval.action_hash as approval_hash,
             approval.action_snapshot_hash, approval.policy_version, approval.reviewer_id,
             approval.decision_type, approval.ai_decision_action,
             approval.reviewer_decision_action,
             approval.original_plan_json::text as approval_original_plan_json,
             approval.approved_plan_json::text as approval_approved_plan_json,
             draft.id as draft_id, policy.id as policy_decision_id,
             policy.policy_version as authoritative_policy_version
        from case_command command
        join case_timeline_event event
          on event.case_id = command.case_id
         and event.event_json ->> 'command_id' = command.command_id
         and event.event_key = ('target-review-decision:' || (event.event_json ->> 'approval_record_id'))
        join human_review_record approval
          on approval.id = event.event_json ->> 'approval_record_id'
        join review_task task on task.id = approval.review_task_id and task.case_id = command.case_id
        join review_packet packet on packet.id = task.packet_id and packet.case_id = task.case_id
        join remedy_plan plan on plan.id = task.plan_id and plan.case_id = task.case_id
        join adjudication_draft draft on draft.id = plan.adjudication_draft_id and draft.case_id = task.case_id
        join approval_policy_decision policy
          on policy.id = task.policy_decision_id
         and policy.case_id = task.case_id
         and policy.plan_id = task.plan_id
       where command.tenant_surrogate = ? and command.case_id = ? and command.command_id = ?
         and command.command_type = 'REVIEW_DECISION' and command.room_type = 'REVIEW'
       for update of command, event, approval, task, packet, plan, draft, policy
      """;

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public JdbcTargetReviewInvocationFactsLoader(DataSource dataSource, ObjectMapper mapper) {
    this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
  }

  public Facts load(RoomGraphCommand command, long fencingToken) {
    List<Row> rows = jdbc.query(SQL, (rs, ignored) -> row(rs), command.tenantSurrogate(), command.caseId(), command.commandId());
    if (rows.size() != 1) throw new IllegalStateException("target Review invocation facts are absent or ambiguous");
    Row value = rows.getFirst();
    JsonNode event = parse(value.eventJson, "review decision event");
    require(command, fencingToken, value, event);
    JsonNode frozenPacket = frozenPacket(value);
    return new Facts(value.reviewTaskId, value.packetId, value.packetVersion, value.taskStatus, fencingToken,
        deadline(value), ContractJson.sha256Hex(mapper.valueToTree(command.actorScope())), frozenPacket, ContractJson.sha256Hex(frozenPacket),
        value.actionHash, event, ContractJson.sha256Hex(event), refs(value.claimsJson, value.evidenceMatrixJson,
            value.draftJson, value.rulesetVersion, value.packetId, value.draftId));
  }

  private void require(RoomGraphCommand command, long fence, Row value, JsonNode event) {
    if (!command.commandId().equals(value.commandId) || !command.tenantSurrogate().equals(value.tenant)
        || !command.caseId().equals(value.caseId) || command.roomEpoch() != value.roomEpoch
        || command.processRevision() != value.processRevision || !"PLATFORM_REVIEWER".equals(value.actorRole)
        || !value.actorId.equals(value.assignedReviewerId)
        || !value.actorId.equals(value.approvalReviewerId)
        || !value.caseId.equals(value.packetCaseId)
        || !value.taskPlanId.equals(value.packetPlanId)
        || !"FROZEN".equals(value.packetStatus) || !value.frozen || value.expiresAt == null
        || value.actionHash == null || !value.actionHash.matches("[0-9a-f]{64}")
        || !value.packetId.equals(value.taskPacketId) || !value.packetId.equals(value.approvalPacketId)
        || value.packetVersion != value.approvalPacketVersion || !value.reviewTaskId.equals(value.approvalTaskId)
        || value.taskPolicyDecisionId == null
        || !value.taskPolicyDecisionId.equals(value.policyDecisionId)
        || value.policyVersion == null || !value.policyVersion.equals(value.authoritativePolicyVersion)
        || !value.payloadHash.equals(ContractJson.sha256Hex(event))
        || !value.reviewTaskId.equals(event.path("review_task_id").asText())
        || !value.packetId.equals(event.path("packet_id").asText())
        || value.packetVersion != event.path("packet_version").asInt(-1)
        || !value.actorId.equals(event.path("reviewer_id").asText())
        || fence != event.path("fencing_token").asLong(-1)
        || command.processRevision() != event.path("case_process_revision").asLong(-1)
        || command.roomEpoch() != event.path("room_epoch").asLong(-1)) {
      throw new IllegalStateException("target Review invocation facts do not bind the admitted decision");
    }
    requireDecisionMaterialIdentity(event, value.caseId, command.commandId(), value.approvalRecordId,
        value.approvalHash, value.policyDecisionId, value.policyVersion);
    requireActionBinding(
        mapper,
        value.decisionType,
        value.actionHash,
        value.approvalActionHash,
        parse(value.remedyJson, "frozen remedy"),
        parse(value.approvalOriginalPlanJson, "approval original plan"),
        parse(value.approvalApprovedPlanJson, "approval approved plan"),
        event,
        TARGET_HEARING_PROMPT_VERSION.equals(value.promptVersion)
            && TARGET_HEARING_PROFILE_VERSION.equals(value.profileVersion),
        value.aiDecisionAction,
        value.reviewerDecisionAction);
  }

  static void requireDecisionMaterialIdentity(JsonNode event, String caseId, String commandId,
      String approvalRecordId, String approvalHash, String policyDecisionId, String policyVersion) {
    Objects.requireNonNull(event, "event");
    if (!"production-runtime-review-human-decision-event.v1".equals(event.path("schema_version").asText())
        || !Objects.equals(caseId, event.path("case_id").asText())
        || !Objects.equals(commandId, event.path("command_id").asText())
        || !Objects.equals(approvalRecordId, event.path("approval_record_id").asText())
        || !Objects.equals(approvalHash, event.path("approval_hash").asText())
        || !Objects.equals(policyDecisionId, event.path("policy_decision_id").asText())
        || !Objects.equals(policyVersion, event.path("policy_version").asText())) {
      throw new IllegalStateException(
          "target Review decision event does not bind its receipt, policy, and command material");
    }
  }

  static void requireActionBinding(ObjectMapper mapper, String decisionType, String frozenActionHash,
      String approvedActionHash, JsonNode frozenPlan, JsonNode approvalOriginalPlan,
      JsonNode approvalApprovedPlan, JsonNode event) {
    requireActionBinding(mapper, decisionType, frozenActionHash, approvedActionHash, frozenPlan,
        approvalOriginalPlan, approvalApprovedPlan, event, false, null, null);
  }

  static void requireActionBinding(ObjectMapper mapper, String decisionType, String frozenActionHash,
      String approvedActionHash, JsonNode frozenPlan, JsonNode approvalOriginalPlan,
      JsonNode approvalApprovedPlan, JsonNode event, boolean boundedDecisionActionReview,
      String aiDecisionAction, String reviewerDecisionAction) {
    Objects.requireNonNull(mapper, "mapper");
    Objects.requireNonNull(frozenPlan, "frozenPlan");
    Objects.requireNonNull(approvalOriginalPlan, "approvalOriginalPlan");
    Objects.requireNonNull(approvalApprovedPlan, "approvalApprovedPlan");
    Objects.requireNonNull(event, "event");
    if (frozenActionHash == null || !frozenActionHash.matches("[0-9a-f]{64}")
        || !frozenPlan.isObject()
        || !frozenPlan.path("actions").isArray() || !frozenPlan.path("notifications").isArray()
        || !Objects.equals(decisionType, event.path("decision").asText())
        || !frozenActionHash.equals(event.path("frozen_action_snapshot_hash").asText())
        || !frozenActionHash.equals(ActionSnapshotHasher.hash(mapper, frozenPlan))) {
      throw new IllegalStateException("target Review decision action hashes do not bind canonical plans");
    }
    if (boundedDecisionActionReview) {
      requireBoundedDecisionActionBinding(mapper, decisionType, frozenActionHash,
          approvedActionHash, frozenPlan, approvalOriginalPlan, approvalApprovedPlan, event,
          aiDecisionAction, reviewerDecisionAction);
      return;
    }
    if (!frozenPlan.equals(approvalOriginalPlan)
        || !approvalOriginalPlan.equals(event.path("original_plan"))) {
      throw new IllegalStateException("target Review decision action hashes do not bind canonical plans");
    }
    if ("APPROVE".equals(decisionType) || "MODIFY_AND_APPROVE".equals(decisionType)) {
      if (approvedActionHash == null || !approvedActionHash.matches("[0-9a-f]{64}")
          || !approvalApprovedPlan.isObject()
          || !approvalApprovedPlan.path("actions").isArray()
          || !approvalApprovedPlan.path("notifications").isArray()
          || !approvalApprovedPlan.equals(event.path("approved_plan"))
          || !approvedActionHash.equals(event.path("approved_action_snapshot_hash").asText())
          || !approvedActionHash.equals(ActionSnapshotHasher.hash(mapper, approvalApprovedPlan))) {
        throw new IllegalStateException(
            "target Review decision action hashes do not bind canonical plans");
      }
    }
    if ("APPROVE".equals(decisionType)) {
      if (!frozenPlan.equals(approvalApprovedPlan)
          || !frozenActionHash.equals(approvedActionHash)) {
        throw new IllegalStateException("target Review APPROVE must retain the frozen action hash");
      }
    } else if ("MODIFY_AND_APPROVE".equals(decisionType)) {
      if (frozenPlan.equals(approvalApprovedPlan)
          || frozenActionHash.equals(approvedActionHash)
          || !frozenPlan.path("id").equals(approvalApprovedPlan.path("id"))
          || !frozenPlan.path("version").equals(approvalApprovedPlan.path("version"))) {
        throw new IllegalStateException(
            "target Review MODIFY_AND_APPROVE must carry a changed approved action hash");
      }
    } else if (NON_EXECUTING_DECISIONS.contains(decisionType)) {
      if (!isAbsentApprovedPlan(approvalApprovedPlan)
          || !approvalApprovedPlan.equals(event.path("approved_plan"))
          || !Objects.equals(frozenActionHash, approvedActionHash)
          || !Objects.equals(frozenActionHash,
              event.path("approved_action_snapshot_hash").asText())
          || carriesExecutionAuthorization(event)) {
        throw new IllegalStateException(
            "target Review non-executing decision must not carry execution authorization");
      }
    } else {
      throw new IllegalStateException("target Review invocation has an unsupported decision type");
    }
  }

  private static void requireBoundedDecisionActionBinding(ObjectMapper mapper, String decisionType,
      String frozenActionHash, String approvedActionHash, JsonNode frozenPlan,
      JsonNode approvalOriginalPlan, JsonNode approvalApprovedPlan, JsonNode event,
      String aiDecisionAction, String reviewerDecisionAction) {
    if (!HearingDecisionAction.supports(aiDecisionAction)
        || !Objects.equals(aiDecisionAction, event.path("ai_decision_action").asText())
        || !Objects.equals(reviewerDecisionAction,
            event.path("reviewer_decision_action").asText())) {
      throw new IllegalStateException("target Review decision codes do not bind durable authority");
    }
    String frozenDecisionAction = frozenPlan.path("decision_action").asText();
    if (!frozenDecisionAction.isBlank() && !aiDecisionAction.equals(frozenDecisionAction)) {
      throw new IllegalStateException("target Review frozen AI decision code conflicts");
    }
    com.fasterxml.jackson.databind.node.ObjectNode expectedOriginal =
        ((com.fasterxml.jackson.databind.node.ObjectNode) frozenPlan).deepCopy();
    expectedOriginal.put("decision_action", aiDecisionAction);
    if (!expectedOriginal.equals(approvalOriginalPlan)
        || !approvalOriginalPlan.equals(event.path("original_plan"))
        || !Objects.equals(frozenActionHash, approvedActionHash)) {
      throw new IllegalStateException("target Review decision action hashes do not bind canonical plans");
    }
    if ("APPROVE".equals(decisionType) || "MODIFY_AND_APPROVE".equals(decisionType)) {
      boolean approve = "APPROVE".equals(decisionType);
      if (!HearingDecisionAction.supports(reviewerDecisionAction)
          || approve != aiDecisionAction.equals(reviewerDecisionAction)) {
        throw new IllegalStateException("target Review human decision code conflicts");
      }
      com.fasterxml.jackson.databind.node.ObjectNode expectedApproved = expectedOriginal.deepCopy();
      expectedApproved.put("decision_action", reviewerDecisionAction);
      if (!expectedApproved.equals(approvalApprovedPlan)
          || !approvalApprovedPlan.equals(event.path("approved_plan"))
          || !frozenActionHash.equals(event.path("approved_action_snapshot_hash").asText())
          || !frozenActionHash.equals(ActionSnapshotHasher.hash(mapper, approvalApprovedPlan))) {
        throw new IllegalStateException("target Review human decision code conflicts");
      }
      return;
    }
    if ("ESCALATE_MANUAL".equals(decisionType)) {
      if (!"ESCALATE_MANUAL".equals(reviewerDecisionAction)
          || !isAbsentApprovedPlan(approvalApprovedPlan)
          || !approvalApprovedPlan.equals(event.path("approved_plan"))
          || !frozenActionHash.equals(event.path("approved_action_snapshot_hash").asText())
          || carriesExecutionAuthorization(event)) {
        throw new IllegalStateException(
            "target Review manual escalation must not carry execution authorization");
      }
      return;
    }
    throw new IllegalStateException("target Review bounded decision type is unsupported");
  }

  private static boolean isAbsentApprovedPlan(JsonNode approvedPlan) {
    return approvedPlan.isNull() || (approvedPlan.isObject() && approvedPlan.isEmpty());
  }

  private static boolean carriesExecutionAuthorization(JsonNode event) {
    JsonNode executionAuthorized = event.get("execution_authorized");
    if (executionAuthorized != null
        && (!executionAuthorized.isBoolean() || executionAuthorized.booleanValue())) {
      return true;
    }
    return hasNonNullField(event, "approved_action_snapshot_ref")
        || hasNonNullField(event, "operation_key_hash");
  }

  private static boolean hasNonNullField(JsonNode event, String field) {
    return event.has(field) && !event.path(field).isNull();
  }

  private JsonNode frozenPacket(Row value) {
    var packet = mapper.createObjectNode();
    packet.put("id", value.packetId); packet.put("case_id", value.packetCaseId); packet.put("plan_id", value.packetPlanId);
    packet.put("packet_version", value.packetVersion); packet.put("case_version", value.caseVersion);
    packet.put("dossier_version", value.dossierVersion); packet.put("issue_version", value.issueVersion);
    packet.put("adjudication_draft_version", value.adjudicationDraftVersion);
    packet.put("deliberation_report_version", value.deliberationReportVersion); packet.put("remedy_plan_version", value.remedyPlanVersion);
    packet.put("ruleset_version", value.rulesetVersion); packet.put("action_hash", value.actionHash);
    packet.set("agent_run_refs", parse(value.agentRunRefsJson, "agent run refs"));
    packet.set("case_summary", parse(value.caseSummaryJson, "case summary")); packet.set("claims", parse(value.claimsJson, "claims"));
    packet.set("issues", parse(value.issuesJson, "issues")); packet.set("evidence_matrix", parse(value.evidenceMatrixJson, "evidence matrix"));
    packet.set("draft", parse(value.draftJson, "draft")); packet.set("remedy", parse(value.remedyJson, "remedy"));
    packet.set("risk_flags", parse(value.riskFlagsJson, "risk flags")); packet.put("status", value.packetStatus);
    packet.put("review_task_status", value.taskStatus); packet.put("assigned_reviewer_id", value.assignedReviewerId);
    packet.put("review_deadline", deadline(value).toString()); return packet;
  }

  Refs refs(String claims, String matrix, String draft, String ruleset, String packetId, String draftId) {
    var facts = mapper.createArrayNode(); collect(parse(claims, "claims"), facts, "fact_id", "claim_id");
    collect(parse(matrix, "evidence matrix"), facts, "fact_id", "claim_id", "evidence_id");
    var rules = mapper.createArrayNode(); addIdentifier(rules, ruleset); collect(parse(draft, "draft"), rules, "rule_id", "rule_code", "rule_version");
    var drafts = mapper.createArrayNode(); addIdentifier(drafts, draftId == null ? packetId : draftId);
    Set<String> factRefs = sorted(facts);
    Set<String> ruleRefs = sorted(rules);
    Set<String> draftRefs = sorted(drafts);
    Set<String> deliberationRefs = Set.of();
    rejectCrossCategoryOverlap(factRefs, ruleRefs, draftRefs, deliberationRefs);
    return new Refs(array(factRefs), array(ruleRefs), array(draftRefs), array(deliberationRefs));
  }
  private Set<String> sorted(com.fasterxml.jackson.databind.node.ArrayNode source) {
    Set<String> values = new TreeSet<>(); source.forEach(value -> values.add(value.asText())); return values;
  }
  private com.fasterxml.jackson.databind.node.ArrayNode array(Set<String> values) {
    var result = mapper.createArrayNode(); values.forEach(result::add); return result;
  }
  private static void rejectCrossCategoryOverlap(Set<String> facts, Set<String> rules, Set<String> drafts, Set<String> deliberations) {
    Set<String> seen = new java.util.HashSet<>();
    for (Set<String> group : List.of(facts, rules, drafts, deliberations)) {
      for (String ref : group) if (!seen.add(ref)) throw new IllegalStateException("frozen review authorized reference appears in multiple categories");
    }
  }
  private static void collect(JsonNode node, com.fasterxml.jackson.databind.node.ArrayNode target, String... names) {
    if (node == null || node.isNull()) return;
    if (node.isObject()) { node.fields().forEachRemaining(field -> {
      for (String name : names) {
        if (name.equals(field.getKey()) && field.getValue().isTextual()) {
          addIdentifier(target, field.getValue().asText());
        } else if ((name + "s").equals(field.getKey()) && field.getValue().isArray()) {
          field.getValue().forEach(value -> {
            if (value.isTextual()) addIdentifier(target, value.asText());
          });
        }
      }
      collect(field.getValue(), target, names);
    }); }
    else if (node.isArray()) node.forEach(item -> collect(item, target, names));
  }
  private static void addIdentifier(com.fasterxml.jackson.databind.node.ArrayNode target, String value) {
    if (value == null || !IDENTIFIER.matcher(value).matches()) return;
    for (JsonNode current : target) if (value.equals(current.asText())) return;
    target.add(value);
  }
  private JsonNode parse(String json, String field) { try { return mapper.readTree(json); } catch (Exception error) { throw new IllegalStateException("frozen review " + field + " is invalid", error); } }
  private static java.time.Instant deadline(Row value) { return value.dueAt != null && value.dueAt.toInstant().isBefore(value.expiresAt.toInstant()) ? value.dueAt.toInstant() : value.expiresAt.toInstant(); }
  private static Row row(ResultSet r) throws SQLException { return new Row(r.getString("command_id"), r.getString("tenant_surrogate"), r.getString("case_id"), r.getLong("room_epoch"), r.getString("actor_id"), r.getString("actor_role"), r.getLong("expected_process_revision"), r.getString("payload_sha256"), r.getString("event_json"), r.getString("review_task_id"), r.getString("task_plan_id"), r.getString("task_packet_id"), r.getString("task_policy_decision_id"), r.getString("task_status"), r.getString("assigned_reviewer_id"), offset(r,"due_at"), r.getString("packet_id"), r.getString("packet_case_id"), r.getString("packet_plan_id"), r.getInt("packet_version"), r.getString("case_summary_json"), r.getString("claims_json"), r.getString("issues_json"), r.getString("evidence_matrix_json"), r.getString("draft_json"), r.getString("remedy_json"), r.getString("risk_flags_json"), r.getString("ruleset_version"), r.getString("prompt_version"), r.getString("profile_version"), r.getString("action_hash"), r.getString("agent_run_refs_json"), r.getString("packet_status"), r.getBoolean("frozen"), offset(r,"expires_at"), r.getLong("case_version"), r.getInt("dossier_version"), r.getInt("issue_version"), r.getInt("adjudication_draft_version"), r.getInt("deliberation_report_version"), r.getInt("remedy_plan_version"), r.getString("approval_record_id"), r.getString("approval_task_id"), r.getString("approval_packet_id"), r.getInt("review_packet_version"), r.getString("approval_hash"), r.getString("action_snapshot_hash"), r.getString("policy_version"), r.getString("reviewer_id"), r.getString("decision_type"), r.getString("ai_decision_action"), r.getString("reviewer_decision_action"), r.getString("approval_original_plan_json"), r.getString("approval_approved_plan_json"), r.getString("draft_id"), r.getString("policy_decision_id"), r.getString("authoritative_policy_version")); }
  private static OffsetDateTime offset(ResultSet r, String name) throws SQLException { return r.getObject(name, OffsetDateTime.class); }
  public record Facts(String reviewTaskId, String packetId, int packetVersion, String taskStatus, long fencingToken, java.time.Instant deadline, String reviewerActorHash, JsonNode frozenPacket, String frozenPacketHash, String actionHash, JsonNode event, String eventHash, Refs refs) {
    public Facts {
      Objects.requireNonNull(frozenPacket, "frozenPacket");
      Objects.requireNonNull(event, "event");
      Objects.requireNonNull(refs, "refs");
      if (!ContractJson.sha256Hex(frozenPacket).equals(frozenPacketHash)
          || !frozenPacket.path("action_hash").asText().equals(actionHash)
          || !ContractJson.sha256Hex(event).equals(eventHash)) {
        throw new IllegalArgumentException("target Review invocation hashes do not bind their frozen facts");
      }
    }
  }
  public record Refs(com.fasterxml.jackson.databind.node.ArrayNode facts, com.fasterxml.jackson.databind.node.ArrayNode rules, com.fasterxml.jackson.databind.node.ArrayNode drafts, com.fasterxml.jackson.databind.node.ArrayNode deliberations) {}
  private record Row(String commandId,String tenant,String caseId,long roomEpoch,String actorId,String actorRole,long processRevision,String payloadHash,String eventJson,String reviewTaskId,String taskPlanId,String taskPacketId,String taskPolicyDecisionId,String taskStatus,String assignedReviewerId,OffsetDateTime dueAt,String packetId,String packetCaseId,String packetPlanId,int packetVersion,String caseSummaryJson,String claimsJson,String issuesJson,String evidenceMatrixJson,String draftJson,String remedyJson,String riskFlagsJson,String rulesetVersion,String promptVersion,String profileVersion,String actionHash,String agentRunRefsJson,String packetStatus,boolean frozen,OffsetDateTime expiresAt,long caseVersion,int dossierVersion,int issueVersion,int adjudicationDraftVersion,int deliberationReportVersion,int remedyPlanVersion,String approvalRecordId,String approvalTaskId,String approvalPacketId,int approvalPacketVersion,String approvalHash,String approvalActionHash,String policyVersion,String approvalReviewerId,String decisionType,String aiDecisionAction,String reviewerDecisionAction,String approvalOriginalPlanJson,String approvalApprovedPlanJson,String draftId,String policyDecisionId,String authoritativePolicyVersion) {}
}
