package com.example.dispute.workflow.targete2e.graph;

import com.example.dispute.workflow.activity.agent.GraphStreamVisibilityPolicy;
import java.util.Map;
import java.util.Set;

/**
 * Frozen public stream boundary for the target Intake Graph.
 *
 * <p>The graph binary and this Java reader are deployed as one target-E2E contract. Registry
 * configuration may repeat this exact policy for auditability, but it may not add, remove, or
 * redirect a public field.
 */
public final class TargetE2EGraphStreamVisibility {

  // Python graph constants are not shared with this Java reader; this locks the wire contract.
  private static final String INTAKE_BASELINE_WIRE_NODE = "intake_turn_case_detail";

  private static final Map<String, Set<String>> INTAKE_VISIBLE_FIELDS =
      Map.of(
          INTAKE_BASELINE_WIRE_NODE,
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
              "case_detail.admission"));

  private TargetE2EGraphStreamVisibility() {}

  /**
   * Resolves the build-bound policy and rejects configuration drift before a worker can consume a
   * target Graph stream.
   */
  public static Map<String, Set<String>> requireExactPolicy(Map<String, Set<String>> configured) {
    if (configured == null || configured.isEmpty()) {
      return INTAKE_VISIBLE_FIELDS;
    }
    Map<String, Set<String>> validated = GraphStreamVisibilityPolicy.immutablePolicy(configured);
    if (!INTAKE_VISIBLE_FIELDS.equals(validated)) {
      throw new IllegalStateException(
          "target E2E Graph stream visibility differs from the frozen Intake contract");
    }
    return INTAKE_VISIBLE_FIELDS;
  }

  public static Map<String, Set<String>> frozenPolicy() {
    return INTAKE_VISIBLE_FIELDS;
  }
}
