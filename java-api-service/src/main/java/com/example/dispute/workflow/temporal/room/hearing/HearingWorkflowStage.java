package com.example.dispute.workflow.temporal.room.hearing;

import java.util.List;

/** Frozen process stages for the future Hearing Temporal workflow. */
public enum HearingWorkflowStage {
  COURT_PREPARING(false),
  CASE_INTRODUCTION(false),
  EVIDENCE_INTRODUCTION(false),
  INTAKE_QUESTIONS_GENERATING(false),
  PARTY_ANSWERS_OPEN(true),
  INTAKE_SYNTHESIZING(false),
  EVIDENCE_REQUESTS_GENERATING(false),
  PARTY_EVIDENCE_OPEN(true),
  EVIDENCE_SYNTHESIZING(false),
  DOSSIER_FREEZING(false),
  JUDGE_V1_GENERATING(false),
  JURY_REVIEWING(false),
  JUDGE_V2_GENERATING(false),
  HUMAN_REVIEW_OPEN(false),
  CLOSED(false);

  private static final List<HearingWorkflowStage> ORDER = List.of(values());

  private final boolean partyWait;

  HearingWorkflowStage(boolean partyWait) {
    this.partyWait = partyWait;
  }

  public int sequence() {
    return ordinal() + 1;
  }

  public boolean isPartyWait() {
    return partyWait;
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
