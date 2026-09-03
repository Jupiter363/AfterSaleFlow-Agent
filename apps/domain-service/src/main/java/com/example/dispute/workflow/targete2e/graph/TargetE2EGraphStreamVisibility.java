package com.example.dispute.workflow.targete2e.graph;

import com.example.dispute.workflow.activity.agent.GraphStreamVisibilityPolicy;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Frozen public stream boundary for target room Graphs.
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
              "ordered_sections",
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
              "case_detail.intake_quality"));

  private static final Map<String, Set<String>> EVIDENCE_VISIBLE_FIELDS =
      Map.of("evidence_turn", Set.of("room_utterance"));

  private static final Set<String> HEARING_PUBLIC_MESSAGE = Set.of("public_message");

  /**
   * Public text emitted by the non-frame Hearing operations.
   *
   * <p>Hearing intake questions and synthesis use the v3 public-frame protocol and therefore do
   * not appear here. The remaining model nodes project only their validated terminal
   * {@code public_message} as legacy-compatible deltas; no reasoning or structured proposal field
   * is public.
   */
  private static final Map<String, Set<String>> HEARING_VISIBLE_FIELDS =
      Map.of(
          "hearing_evidence_requests", HEARING_PUBLIC_MESSAGE,
          "hearing_evidence_synthesis", HEARING_PUBLIC_MESSAGE,
          "hearing_judge_v1", HEARING_PUBLIC_MESSAGE,
          "hearing_jury_review", HEARING_PUBLIC_MESSAGE,
          "hearing_judge_v2", HEARING_PUBLIC_MESSAGE);

  private static final Map<String, Set<String>> NO_VISIBLE_FIELDS = Map.of();

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

  /**
   * Validates the registry/build template before selecting the room-specific public boundary.
   */
  public static Map<String, Set<String>> requireExactPolicy(
      RoomType roomType, Map<String, Set<String>> configured) {
    requireExactPolicy(configured);
    return frozenPolicy(roomType);
  }

  public static Map<String, Set<String>> frozenPolicy() {
    return INTAKE_VISIBLE_FIELDS;
  }

  public static Map<String, Set<String>> frozenPolicy(RoomType roomType) {
    return switch (Objects.requireNonNull(roomType, "roomType")) {
      case INTAKE -> INTAKE_VISIBLE_FIELDS;
      case EVIDENCE -> EVIDENCE_VISIBLE_FIELDS;
      case HEARING -> HEARING_VISIBLE_FIELDS;
      case REVIEW -> NO_VISIBLE_FIELDS;
    };
  }
}
