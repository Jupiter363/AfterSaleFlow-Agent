import { reactive } from "vue";
import { hearingApi } from "../api/hearing";
import { createResourceState, loadResource } from "./resource";

export const hearingStore = reactive({
  hearing: createResourceState(null),
  deliberation: createResourceState(null),
  activeStage: "C1_ISSUE_FRAMING",
});

export const HEARING_STAGES = [
  ["C1_ISSUE_FRAMING", "争点整理"],
  ["C2_EVIDENCE_GAP", "证据缺口"],
  ["C3_EVIDENCE_REQUEST", "补证请求"],
  ["C4_EVIDENCE_CROSS_CHECK", "证据交叉核验"],
  ["C5_RULE_APPLICATION", "规则适用"],
  ["C6_DRAFT_GENERATION", "裁判草案"],
];

export function loadHearing(actor, caseId) {
  return loadResource(hearingStore.hearing, () => hearingApi.hearing(actor, caseId));
}

export function loadDeliberation(actor, caseId) {
  return loadResource(
    hearingStore.deliberation,
    () => hearingApi.deliberation(actor, caseId),
  );
}
