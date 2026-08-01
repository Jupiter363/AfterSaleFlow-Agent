package com.example.dispute.workflow.targete2e.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.agentstream.application.AgentStreamProtocolException;
import com.example.dispute.agentstream.infrastructure.AgentNdjsonStreamClient;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
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
                "intake_lcel",
                Set.of(
                    "room_utterance",
                    "case_detail.case_story",
                    "case_detail.references",
                    "case_detail.party_positions",
                    "case_detail.dispute_focus",
                    "case_detail.requested_resolution",
                    "case_detail.claim_resolution",
                    "case_detail.respondent_attitude",
                    "case_detail.dispute_core_state",
                    "case_detail.risk_assessment",
                    "case_detail.missing_information",
                    "case_detail.intake_quality",
                    "case_detail.admission")));
  }

  @Test
  void rejectsAnyConfiguredPolicyThatDriftsFromTheBuildBoundContract() {
    assertThat(TargetE2EGraphStreamVisibility.requireExactPolicy(
            TargetE2EGraphStreamVisibility.frozenPolicy()))
        .isSameAs(TargetE2EGraphStreamVisibility.frozenPolicy());

    assertThatThrownBy(
            () ->
                TargetE2EGraphStreamVisibility.requireExactPolicy(
                    Map.of("intake_lcel", Set.of("room_utterance"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("frozen Intake contract");
  }

  @Test
  void permitsOnlyTheTargetIntakeNodeAndFieldsInTheV2Stream() {
    var allowed = state();
    parse(allowed, event(0, "attempt_started", "{\"node\":\"authorize_and_load\"}"));

    assertThat(
            parse(
                    allowed,
                    event(
                        1,
                        "visible_delta",
                        "{\"node\":\"intake_lcel\",\"field\":\"room_utterance\","
                            + "\"delta\":\"\\\"hello\\\"\"}"))
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
                        "{\"node\":\"other_lcel\",\"field\":\"room_utterance\","
                            + "\"delta\":\"\\\"not-public\\\"\"}")))
        .isInstanceOf(AgentStreamProtocolException.class)
        .hasMessageContaining("non-public field");

    var wrongField = state();
    parse(wrongField, event(0, "attempt_started", "{\"node\":\"authorize_and_load\"}"));
    assertThatThrownBy(
            () ->
                parse(
                    wrongField,
                    event(
                        1,
                        "visible_delta",
                        "{\"node\":\"intake_lcel\",\"field\":\"reasoning_content\","
                            + "\"delta\":\"not-public\"}")))
        .isInstanceOf(AgentStreamProtocolException.class)
        .hasMessageContaining("non-public field");
  }

  private static AgentNdjsonStreamClient.V2ProtocolState state() {
    return new AgentNdjsonStreamClient.V2ProtocolState(
        "run-1", "attempt-1", Audience.USER, TargetE2EGraphStreamVisibility.frozenPolicy());
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
