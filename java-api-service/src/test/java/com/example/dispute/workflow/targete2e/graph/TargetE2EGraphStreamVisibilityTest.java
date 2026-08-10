package com.example.dispute.workflow.targete2e.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.agentstream.application.AgentStreamProtocolException;
import com.example.dispute.agentstream.infrastructure.AgentNdjsonStreamClient;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TargetE2EGraphStreamVisibilityTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

  @Test
  void suppliesTheBuildBoundPolicyWhenTheRegistryLeavesVisibilityUnset() {
    assertThat(TargetE2EGraphStreamVisibility.requireExactPolicy(Map.of()))
        .isSameAs(TargetE2EGraphStreamVisibility.frozenPolicy())
        .containsExactly(
            Map.entry(
                "intake_turn_case_detail",
                Set.of(
                    "room_utterance",
                    "case_detail.case_story.title",
                    "case_detail.case_story.one_sentence_summary",
                    "case_detail.references.order_reference",
                    "case_detail.references.after_sales_reference",
                    "case_detail.references.logistics_reference",
                    "case_detail.party_positions.user_claim",
                    "case_detail.party_positions.merchant_claim",
                    "case_detail.party_positions.initiator_position",
                    "case_detail.party_positions.platform_observation",
                    "case_detail.claim_resolution.normalized_statement",
                    "case_detail.claim_resolution.request_reason",
                    "case_detail.claim_resolution.requested_items",
                    "case_detail.respondent_attitude.position",
                    "case_detail.dispute_core_state.core_conflict",
                    "case_detail.dispute_focus.core_issue",
                    "case_detail.intake_quality.improvement_reason",
                    "case_detail.case_story",
                    "case_detail.references",
                    "case_detail.party_positions",
                    "case_detail.claim_resolution",
                    "case_detail.respondent_attitude",
                    "case_detail.dispute_core_state",
                    "case_detail.dispute_focus",
                    "case_detail.risk_assessment",
                    "case_detail.missing_information",
                    "case_detail.intake_quality")));
  }

  @Test
  void rejectsAnyConfiguredPolicyThatDriftsFromTheBuildBoundContract() {
    assertThat(TargetE2EGraphStreamVisibility.requireExactPolicy(
            TargetE2EGraphStreamVisibility.frozenPolicy()))
        .isSameAs(TargetE2EGraphStreamVisibility.frozenPolicy());

    assertThatThrownBy(
            () ->
                TargetE2EGraphStreamVisibility.requireExactPolicy(
                    Map.of("intake_turn_case_detail", Set.of("room_utterance"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("frozen Intake contract");
  }

  @Test
  void permitsOnlyTheFrozenIntakeV2VisibleFields() {
    var allowed = state();
    parse(allowed, event(0, "attempt_started", "{\"node\":\"authorize_and_load\"}"));

    assertThat(
            parse(
                    allowed,
                    event(
                        1,
                        "visible_delta",
                        "{\"node\":\"intake_turn_case_detail\","
                            + "\"field\":\"case_detail.case_story.title\","
                            + "\"delta\":\"title\"}"))
                .eventType())
        .isEqualTo(StreamEventType.VISIBLE_DELTA);
    assertThat(
            parse(
                    allowed,
                    event(
                        2,
                        "visible_delta",
                        "{\"node\":\"intake_turn_case_detail\","
                            + "\"field\":\"case_detail.case_story\",\"delta\":\"{}\"}"))
                .eventType())
        .isEqualTo(StreamEventType.VISIBLE_DELTA);
    assertThat(
            parse(
                    allowed,
                    event(
                        3,
                        "visible_delta",
                        "{\"node\":\"intake_turn_case_detail\",\"field\":\"room_utterance\","
                            + "\"delta\":\"hello\"}"))
                .eventType())
        .isEqualTo(StreamEventType.VISIBLE_DELTA);

    var wrongNode = state();
    parse(wrongNode, event(0, "attempt_started", "{\"node\":\"authorize_and_load\"}"));
    assertThatThrownBy(
            () ->
                parse(
                    wrongNode,
                    event(
                        1,
                        "visible_delta",
                        "{\"node\":\"intake_lcel\",\"field\":\"room_utterance\","
                            + "\"delta\":\"\\\"not-public\\\"\"}")))
        .isInstanceOf(AgentStreamProtocolException.class)
        .hasMessageContaining("non-public field");

    var unknownNode = state();
    parse(unknownNode, event(0, "attempt_started", "{\"node\":\"authorize_and_load\"}"));
    assertThatThrownBy(
            () ->
                parse(
                    unknownNode,
                    event(
                        1,
                        "visible_delta",
                        "{\"node\":\"other_lcel\",\"field\":\"room_utterance\","
                            + "\"delta\":\"not-public\"}")))
        .isInstanceOf(AgentStreamProtocolException.class)
        .hasMessageContaining("non-public field");

    var unknownField = state();
    parse(unknownField, event(0, "attempt_started", "{\"node\":\"authorize_and_load\"}"));
    assertThatThrownBy(
            () ->
                parse(
                    unknownField,
                    event(
                        1,
                        "visible_delta",
                        "{\"node\":\"intake_turn_case_detail\",\"field\":\"case_detail.unknown_field\","
                            + "\"delta\":\"not-public\"}")))
        .isInstanceOf(AgentStreamProtocolException.class)
        .hasMessageContaining("non-public field");

    var legacyAdmission = state();
    parse(legacyAdmission, event(0, "attempt_started", "{\"node\":\"authorize_and_load\"}"));
    assertThatThrownBy(
            () ->
                parse(
                    legacyAdmission,
                    event(
                        1,
                        "visible_delta",
                        "{\"node\":\"intake_turn_case_detail\",\"field\":\"case_detail.admission\","
                            + "\"delta\":\"not-public\"}")))
        .isInstanceOf(AgentStreamProtocolException.class)
        .hasMessageContaining("non-public field");

    var reasoningContent = state();
    parse(reasoningContent, event(0, "attempt_started", "{\"node\":\"authorize_and_load\"}"));
    assertThatThrownBy(
            () ->
                parse(
                    reasoningContent,
                    event(
                        1,
                        "visible_delta",
                        "{\"node\":\"intake_turn_case_detail\",\"field\":\"reasoning_content\","
                            + "\"delta\":\"not-public\"}")))
        .isInstanceOf(AgentStreamProtocolException.class)
        .hasMessageContaining("non-public field");

    var evidenceNode = state();
    parse(evidenceNode, event(0, "attempt_started", "{\"node\":\"authorize_and_load\"}"));
    assertThatThrownBy(
            () ->
                parse(
                    evidenceNode,
                    event(
                        1,
                        "visible_delta",
                        "{\"node\":\"evidence_turn\",\"field\":\"room_utterance\","
                            + "\"delta\":\"not-public\"}")))
        .isInstanceOf(AgentStreamProtocolException.class)
        .hasMessageContaining("non-public field");
  }

  @Test
  void permitsOnlyEvidenceRoomUtteranceForTheEvidenceRoom() {
    var allowed = state(RoomType.EVIDENCE);
    parse(allowed, event(0, "attempt_started", "{\"node\":\"authorize_and_load\"}"));

    assertThat(
            parse(
                    allowed,
                    event(
                        1,
                        "visible_delta",
                        "{\"node\":\"evidence_turn\",\"field\":\"room_utterance\","
                            + "\"delta\":\"public\"}"))
                .eventType())
        .isEqualTo(StreamEventType.VISIBLE_DELTA);

    assertEvidenceFieldRejected("evidence_turn", "reasoning_content");
    assertEvidenceFieldRejected("evidence_turn", "unknown_field");
    assertEvidenceFieldRejected("intake_turn_case_detail", "room_utterance");
    assertEvidenceFieldRejected("unknown_node", "room_utterance");
  }

  @Test
  void hearingAndReviewRoomsExposeNoVisibleFields() {
    assertThat(TargetE2EGraphStreamVisibility.frozenPolicy(RoomType.HEARING)).isEmpty();
    assertThat(TargetE2EGraphStreamVisibility.frozenPolicy(RoomType.REVIEW)).isEmpty();

    for (RoomType roomType : Set.of(RoomType.HEARING, RoomType.REVIEW)) {
      assertRoomFieldRejected(roomType, "evidence_turn", "room_utterance");
      assertRoomFieldRejected(roomType, "intake_turn_case_detail", "room_utterance");
    }
  }

  private static void assertEvidenceFieldRejected(String node, String field) {
    assertRoomFieldRejected(RoomType.EVIDENCE, node, field);
  }

  private static void assertRoomFieldRejected(RoomType roomType, String node, String field) {
    var state = state(roomType);
    parse(state, event(0, "attempt_started", "{\"node\":\"authorize_and_load\"}"));
    assertThatThrownBy(
            () ->
                parse(
                    state,
                    event(
                        1,
                        "visible_delta",
                        "{\"node\":\""
                            + node
                            + "\",\"field\":\""
                            + field
                            + "\",\"delta\":\"not-public\"}")))
        .isInstanceOf(AgentStreamProtocolException.class)
        .hasMessageContaining("non-public field");
  }

  private static AgentNdjsonStreamClient.V2ProtocolState state() {
    return state(RoomType.INTAKE);
  }

  private static AgentNdjsonStreamClient.V2ProtocolState state(RoomType roomType) {
    return new AgentNdjsonStreamClient.V2ProtocolState(
        "run-1",
        "attempt-1",
        Audience.USER,
        TargetE2EGraphStreamVisibility.frozenPolicy(roomType));
  }

  private static com.example.dispute.workflow.contract.v1.AgentStreamEvent parse(
      AgentNdjsonStreamClient.V2ProtocolState state, String line) {
    return AgentNdjsonStreamClient.parseV2Line(MAPPER, line, state);
  }

  private static String event(long sequence, String eventType, String payload) {
    return "{\"schema_version\":\"agent-stream.v2\",\"run_id\":\"run-1\","
        + "\"attempt_id\":\"attempt-1\",\"sequence_no\":"
        + sequence
        + ",\"event_type\":\""
        + eventType
        + "\",\"audience\":\"USER\",\"occurred_at\":\"2026-08-02T00:00:00Z\","
        + "\"payload\":"
        + payload
        + "}";
  }
}
