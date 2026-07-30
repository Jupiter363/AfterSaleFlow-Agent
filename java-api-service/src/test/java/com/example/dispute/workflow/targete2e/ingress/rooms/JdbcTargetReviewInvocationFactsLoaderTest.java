package com.example.dispute.workflow.targete2e.ingress.rooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.review.domain.ActionSnapshotHasher;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class JdbcTargetReviewInvocationFactsLoaderTest {
  private final JdbcTargetReviewInvocationFactsLoader loader = new JdbcTargetReviewInvocationFactsLoader(
      Mockito.mock(DataSource.class), new ObjectMapper());

  @Test
  void canonicalizesRepeatedFrozenFactAndRuleReferences() {
    var refs = loader.refs("[{\"claim_id\":\"FACT_2\"},{\"claim_ids\":[\"FACT_1\",\"FACT_2\"]}]",
        "[{\"evidence_ids\":[\"FACT_1\"]}]", "{\"rule_ids\":[\"RULE_2\",\"RULE_1\",\"RULE_2\"]}",
        "RULE_0", "PACKET_1", "DRAFT_1");

    assertThat(refs.facts()).extracting(value -> value.asText()).containsExactly("FACT_1", "FACT_2");
    assertThat(refs.rules()).extracting(value -> value.asText()).containsExactly("RULE_0", "RULE_1", "RULE_2");
    assertThat(refs.drafts()).extracting(value -> value.asText()).containsExactly("DRAFT_1");
  }

  @Test
  void rejectsReferenceThatAppearsInMoreThanOneCapabilityCategory() {
    assertThatThrownBy(() -> loader.refs("[{\"claim_id\":\"DUPLICATE\"}]", "[]",
        "{\"rule_id\":\"DUPLICATE\"}", "RULE_0", "PACKET_1", "DRAFT_1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("multiple categories");
  }

  @Test
  void ignoresTextOutsideExactSingularOrPluralCapabilityKeys() {
    var refs = loader.refs("{\"labels\":[\"FACT_FORBIDDEN\"],\"fact_ids\":[\"FACT_ALLOWED\"]}",
        "[]", "{\"notes\":[\"RULE_FORBIDDEN\"]}", "RULE_0", "PACKET_1", "DRAFT_1");

    assertThat(refs.facts()).extracting(value -> value.asText()).containsExactly("FACT_ALLOWED");
    assertThat(refs.rules()).extracting(value -> value.asText()).containsExactly("RULE_0");
  }

  @Test
  void selectsEveryFrozenPacketVersionRequiredByTheRowMapper() {
    assertThat(JdbcTargetReviewInvocationFactsLoader.SQL)
        .contains(
            "packet.case_version",
            "packet.dossier_version",
            "packet.issue_version",
            "packet.adjudication_draft_version",
            "packet.deliberation_report_version",
            "packet.remedy_plan_version",
            "approval.decision_type",
            "approval.original_plan_json::text as approval_original_plan_json",
            "approval.approved_plan_json::text as approval_approved_plan_json");
  }

  @Test
  void readsOnlyTheExactPolicyDecisionPinnedByTheReviewTask() {
    assertThat(JdbcTargetReviewInvocationFactsLoader.SQL)
        .contains(
            "task.policy_decision_id as task_policy_decision_id",
            "policy.id = task.policy_decision_id",
            "policy.case_id = task.case_id",
            "policy.plan_id = task.plan_id",
            "policy.id as policy_decision_id")
        .doesNotContain("created_at <= task.created_at", "order by value.created_at");
  }

  @Test
  void readsOnlyTheDecisionEventBoundToTheExactCommand() {
    assertThat(JdbcTargetReviewInvocationFactsLoader.SQL)
        .contains("event.event_json ->> 'command_id' = command.command_id");
  }

  @Test
  void approveRetainsTheCanonicalFrozenActionHash() throws Exception {
    JsonNode frozen = plan("frozen-key", "frozen");
    String frozenHash = ActionSnapshotHasher.hash(new ObjectMapper(), frozen);
    JsonNode event = decisionEvent("APPROVE", frozen, frozen, frozenHash, frozenHash);

    assertThatCode(() -> JdbcTargetReviewInvocationFactsLoader.requireActionBinding(
        new ObjectMapper(), "APPROVE", frozenHash, frozenHash, frozen, frozen.deepCopy(),
        frozen.deepCopy(), event))
        .doesNotThrowAnyException();
  }

  @Test
  void modifyAndApproveBindsTheCanonicalChangedApprovedPlan() throws Exception {
    JsonNode frozen = plan("frozen-key", "frozen");
    JsonNode approved = plan("approved-key", "approved");
    String frozenHash = ActionSnapshotHasher.hash(new ObjectMapper(), frozen);
    String approvedHash = ActionSnapshotHasher.hash(new ObjectMapper(), approved);
    JsonNode event = decisionEvent(
        "MODIFY_AND_APPROVE", frozen, approved, frozenHash, approvedHash);

    assertThatCode(() -> JdbcTargetReviewInvocationFactsLoader.requireActionBinding(
        new ObjectMapper(), "MODIFY_AND_APPROVE", frozenHash, approvedHash, frozen,
        frozen.deepCopy(), approved, event))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectBindsTheFrozenOriginalPlanWithoutAuthorizingExecution() throws Exception {
    assertNonExecutingDecisionBinds("REJECT");
  }

  @Test
  void requestMoreEvidenceBindsTheFrozenOriginalPlanWithoutAuthorizingExecution()
      throws Exception {
    assertNonExecutingDecisionBinds("REQUEST_MORE_EVIDENCE");
  }

  @Test
  void escalateManualBindsTheFrozenOriginalPlanWithoutAuthorizingExecution() throws Exception {
    assertNonExecutingDecisionBinds("ESCALATE_MANUAL");
  }

  @Test
  void rejectsNonExecutingDecisionWhenTheOriginalPlanDriftsFromTheFrozenPacket()
      throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode frozen = plan("frozen-key", "frozen");
    JsonNode drifted = plan("drifted-key", "drifted");
    String frozenHash = ActionSnapshotHasher.hash(mapper, frozen);
    JsonNode event = decisionEvent(
        "REJECT", drifted, mapper.createObjectNode(), frozenHash, frozenHash);

    assertThatThrownBy(() -> JdbcTargetReviewInvocationFactsLoader.requireActionBinding(
        mapper, "REJECT", frozenHash, frozenHash, frozen, drifted,
        mapper.createObjectNode(), event))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("canonical plans");
  }

  @Test
  void rejectsNonExecutingDecisionThatCarriesExecutionAuthorization() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode frozen = plan("frozen-key", "frozen");
    String frozenHash = ActionSnapshotHasher.hash(mapper, frozen);
    var event = (com.fasterxml.jackson.databind.node.ObjectNode) decisionEvent(
        "ESCALATE_MANUAL", frozen, mapper.createObjectNode(), frozenHash, frozenHash);
    event.put("execution_authorized", true);

    assertThatThrownBy(() -> JdbcTargetReviewInvocationFactsLoader.requireActionBinding(
        mapper, "ESCALATE_MANUAL", frozenHash, frozenHash, frozen, frozen.deepCopy(),
        mapper.createObjectNode(), event))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must not carry execution authorization");
  }

  @Test
  void rejectsPolicyOrCommandMaterialDriftInTheDecisionEvent() {
    var receiptDrift = decisionMaterialEvent();
    receiptDrift.put("approval_record_id", "APPROVAL_2");

    assertThatThrownBy(() -> JdbcTargetReviewInvocationFactsLoader.requireDecisionMaterialIdentity(
        receiptDrift, "CASE_1", "COMMAND_1", "APPROVAL_1", "a".repeat(64),
        "POLICY_1", "policy-v1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("receipt, policy, and command material");

    var policyDrift = decisionMaterialEvent();
    policyDrift.put("policy_version", "policy-v2");

    assertThatThrownBy(() -> JdbcTargetReviewInvocationFactsLoader.requireDecisionMaterialIdentity(
        policyDrift, "CASE_1", "COMMAND_1", "APPROVAL_1", "a".repeat(64),
        "POLICY_1", "policy-v1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("receipt, policy, and command material");

    var commandDrift = decisionMaterialEvent();
    commandDrift.put("command_id", "COMMAND_2");
    assertThatThrownBy(() -> JdbcTargetReviewInvocationFactsLoader.requireDecisionMaterialIdentity(
        commandDrift, "CASE_1", "COMMAND_1", "APPROVAL_1", "a".repeat(64),
        "POLICY_1", "policy-v1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("receipt, policy, and command material");
  }

  @Test
  void rejectsSameVersionDecisionEventBoundToAnotherPolicyDecision() {
    var event = decisionMaterialEvent();
    event.put("policy_decision_id", "POLICY_2");

    assertThatThrownBy(() -> JdbcTargetReviewInvocationFactsLoader.requireDecisionMaterialIdentity(
        event, "CASE_1", "COMMAND_1", "APPROVAL_1", "a".repeat(64),
        "POLICY_1", "policy-v1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("receipt, policy, and command material");
  }

  @Test
  void rejectsLegacyDecisionEventWithoutExactPolicyDecisionIdentity() {
    var event = decisionMaterialEvent();
    event.remove("policy_decision_id");

    assertThatThrownBy(() -> JdbcTargetReviewInvocationFactsLoader.requireDecisionMaterialIdentity(
        event, "CASE_1", "COMMAND_1", "APPROVAL_1", "a".repeat(64),
        "POLICY_1", "policy-v1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("receipt, policy, and command material");
  }

  @Test
  void rejectsDecisionEventHashConfusedForTheApprovedActionHash() throws Exception {
    JsonNode frozen = plan("frozen-key", "frozen");
    JsonNode approved = plan("approved-key", "approved");
    String frozenHash = ActionSnapshotHasher.hash(new ObjectMapper(), frozen);
    String approvedHash = ActionSnapshotHasher.hash(new ObjectMapper(), approved);
    var event = (com.fasterxml.jackson.databind.node.ObjectNode) decisionEvent(
        "MODIFY_AND_APPROVE", frozen, approved, frozenHash, approvedHash);
    String confusedEventHash = ContractJson.sha256Hex(event);
    event.put("approved_action_snapshot_hash", confusedEventHash);

    assertThatThrownBy(() -> JdbcTargetReviewInvocationFactsLoader.requireActionBinding(
        new ObjectMapper(), "MODIFY_AND_APPROVE", frozenHash, confusedEventHash, frozen,
        frozen.deepCopy(), approved, event))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("canonical plans");
  }

  @Test
  void rejectsDecisionEventHashUsedAsTheFrozenActionHash() {
    ObjectMapper mapper = new ObjectMapper();
    var packet = mapper.createObjectNode().put("action_hash", "a".repeat(64));
    var event = mapper.createObjectNode().put("approval_record_id", "APPROVAL_1");
    var empty = mapper.createArrayNode();
    var refs = new JdbcTargetReviewInvocationFactsLoader.Refs(empty, empty, empty, empty);

    assertThatThrownBy(() -> new JdbcTargetReviewInvocationFactsLoader.Facts(
        "REVIEW_1", "PACKET_1", 1, "COMPLETED", 7, Instant.parse("2026-07-29T01:00:00Z"),
        "b".repeat(64), packet, com.example.dispute.workflow.contract.v1.ContractJson.sha256Hex(packet),
        com.example.dispute.workflow.contract.v1.ContractJson.sha256Hex(event), event,
        com.example.dispute.workflow.contract.v1.ContractJson.sha256Hex(event), refs))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("frozen facts");
  }

  private JsonNode plan(String idempotencyKey, String marker) throws Exception {
    return new ObjectMapper().readTree("""
        {
          "id": "PLAN_1",
          "version": 3,
          "actions": [{
            "action_type": "TARGET_NO_EXTERNAL_EFFECT",
            "effect_class": "NO_EXTERNAL_EFFECT",
            "idempotency_key": "%s",
            "marker": "%s"
          }],
          "preconditions": [],
          "notifications": []
        }
        """.formatted(idempotencyKey, marker));
  }

  private JsonNode decisionEvent(String decision, JsonNode originalPlan, JsonNode approvedPlan,
      String frozenHash, String approvedHash) {
    var event = new ObjectMapper().createObjectNode();
    event.put("decision", decision);
    event.put("frozen_action_snapshot_hash", frozenHash);
    event.put("approved_action_snapshot_hash", approvedHash);
    event.set("original_plan", originalPlan.deepCopy());
    event.set("approved_plan", approvedPlan.deepCopy());
    return event;
  }

  private void assertNonExecutingDecisionBinds(String decision) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode frozen = plan("frozen-key", "frozen");
    JsonNode noApprovedPlan = "REQUEST_MORE_EVIDENCE".equals(decision)
        ? mapper.nullNode()
        : mapper.createObjectNode();
    String frozenHash = ActionSnapshotHasher.hash(mapper, frozen);
    JsonNode event = decisionEvent(
        decision, frozen, noApprovedPlan, frozenHash, frozenHash);

    assertThatCode(() -> JdbcTargetReviewInvocationFactsLoader.requireActionBinding(
        mapper, decision, frozenHash, frozenHash, frozen, frozen.deepCopy(),
        noApprovedPlan, event))
        .doesNotThrowAnyException();
  }

  private com.fasterxml.jackson.databind.node.ObjectNode decisionMaterialEvent() {
    return new ObjectMapper().createObjectNode()
        .put("schema_version", "target-e2e-review-human-decision-event.v1")
        .put("case_id", "CASE_1")
        .put("command_id", "COMMAND_1")
        .put("approval_record_id", "APPROVAL_1")
        .put("approval_hash", "a".repeat(64))
        .put("policy_decision_id", "POLICY_1")
        .put("policy_version", "policy-v1");
  }
}
