import { describe, expect, it } from "vitest";
import { streamCardPresentation } from "./agentSpeakerPresentation";

describe("hearing flow v2 stream presentation", () => {
  it.each([
    ["HEARING_INTAKE_QUESTIONS", "INTAKE_OFFICER", "default"],
    ["HEARING_INTAKE_SYNTHESIS", "INTAKE_OFFICER", "default"],
    ["HEARING_EVIDENCE_REQUESTS", "EVIDENCE_CLERK", "default"],
    ["HEARING_EVIDENCE_SYNTHESIS", "EVIDENCE_CLERK", "default"],
    ["HEARING_JUDGE_V1", "PRESIDING_JUDGE", "adjudication-draft"],
    ["HEARING_JURY_REVIEW", "JURY_PANEL", "jury-review"],
    ["HEARING_JUDGE_V2", "PRESIDING_JUDGE", "adjudication-draft-v2"],
  ])("maps %s to its declared role", (operation, senderRole, key) => {
    expect(
      streamCardPresentation({ operation, fieldPath: "public_message" }),
    ).toMatchObject({ senderRole, key });
  });

  it("does not treat every public_message as a jury message", () => {
    expect(
      streamCardPresentation({
        operation: "HEARING_INTAKE_SYNTHESIS",
        fieldPath: "public_message",
      }),
    ).toMatchObject({ senderRole: "INTAKE_OFFICER" });
  });
});
