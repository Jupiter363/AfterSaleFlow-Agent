import { describe, expect, it } from "vitest";
import {
  HEARING_FLOW_GROUPS,
  HEARING_FLOW_STAGES,
  hearingFlowProgress,
  hearingFlowStage,
  isJudgeLlmStage,
  isPartyInputStage,
  normalizeHearingFlowStage,
} from "./hearingFlow";

describe("hearing flow v2", () => {
  it("keeps all fifteen stages in the same six presentation groups", () => {
    expect(HEARING_FLOW_STAGES).toHaveLength(15);
    expect(new Set(HEARING_FLOW_STAGES.map((stage) => stage.code)).size).toBe(15);
    expect(HEARING_FLOW_GROUPS).toHaveLength(6);
    expect(new Set(HEARING_FLOW_STAGES.map((stage) => stage.group))).toEqual(
      new Set(HEARING_FLOW_GROUPS),
    );
  });

  it("reads the authoritative stage without inspecting messages", () => {
    expect(hearingFlowStage({ flow_stage: "PARTY_ANSWERS_OPEN" })).toBe(
      "PARTY_ANSWERS_OPEN",
    );
    expect(hearingFlowStage({ flowStage: "EVIDENCE_SYNTHESIZING" })).toBe(
      "EVIDENCE_SYNTHESIZING",
    );
    expect(hearingFlowStage({ stage_code: "PARTY_ANSWERS_OPEN" })).toBe(
      "PARTY_ANSWERS_OPEN",
    );
  });

  it("uses the preparing state for absent or unknown server values", () => {
    expect(normalizeHearingFlowStage()).toBe("COURT_PREPARING");
    expect(normalizeHearingFlowStage("old_round_two")).toBe("COURT_PREPARING");
    expect(normalizeHearingFlowStage("INTAKE_CLARIFICATION")).toBe(
      "COURT_PREPARING",
    );
  });

  it("builds stable macro progress from the explicit state order", () => {
    const progress = hearingFlowProgress("PARTY_EVIDENCE_OPEN");
    expect(progress.map((item) => item.tone)).toEqual([
      "complete",
      "complete",
      "active",
      "pending",
      "pending",
      "pending",
    ]);
  });

  it("limits party input and judge calls to their declared stages", () => {
    expect(isPartyInputStage("PARTY_ANSWERS_OPEN")).toBe(true);
    expect(isPartyInputStage("INTAKE_SYNTHESIZING")).toBe(false);
    expect(isJudgeLlmStage("DOSSIER_FREEZING")).toBe(false);
    expect(isJudgeLlmStage("JUDGE_V1_GENERATING")).toBe(true);
  });
});
