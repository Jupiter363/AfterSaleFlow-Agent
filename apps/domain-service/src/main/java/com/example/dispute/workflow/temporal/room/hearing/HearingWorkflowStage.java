package com.example.dispute.workflow.temporal.room.hearing;

import java.util.List;

/** Frozen process stages for the future Hearing Temporal workflow. */
public enum HearingWorkflowStage {
  COURT_PREPARING(false, null),
  CASE_INTRODUCTION(false, null),
  EVIDENCE_INTRODUCTION(false, null),
  INTAKE_QUESTIONS_GENERATING(false, "intake_questions"),
  PARTY_ANSWERS_OPEN(true, null),
  INTAKE_SYNTHESIZING(false, "intake_synthesis"),
  EVIDENCE_REQUESTS_GENERATING(false, "evidence_requests"),
  PARTY_EVIDENCE_OPEN(true, null),
  EVIDENCE_SYNTHESIZING(false, "evidence_synthesis"),
  DOSSIER_FREEZING(false, null),
  JUDGE_V1_GENERATING(false, "judge_v1"),
  JURY_REVIEWING(false, "jury_review"),
  JUDGE_V2_GENERATING(false, "judge_v2"),
  HUMAN_REVIEW_OPEN(false, null),
  CLOSED(false, null);

  private static final List<HearingWorkflowStage> ORDER = List.of(values());

  private final boolean partyWait;
  private final String agentOperation;

  HearingWorkflowStage(boolean partyWait, String agentOperation) {
    this.partyWait = partyWait;
    this.agentOperation = agentOperation;
  }

  public int sequence() {
    return ordinal() + 1;
  }

  public boolean isPartyWait() {
    return partyWait;
  }

  public boolean requiresAgentRun() {
    return agentOperation != null;
  }

  public String agentOperation() {
    return agentOperation;
  }

  public HearingWorkflowStage next() {
    return this == CLOSED ? null : ORDER.get(ordinal() + 1);
  }

  public static HearingWorkflowStage atSequence(int sequence) {
    if (sequence < 1 || sequence > ORDER.size()) {
      throw new IllegalArgumentException("Hearing stage sequence must be between 1 and 15");
    }
    return ORDER.get(sequence - 1);
  }
}
