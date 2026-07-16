export const HEARING_FLOW_SCHEMA = "hearing_flow.v2";

export const HEARING_FLOW_STAGES = Object.freeze([
  { code: "COURT_PREPARING", label: "资料装载", group: "案情交接", owner: "SYSTEM" },
  { code: "CASE_INTRODUCTION", label: "案情介绍", group: "案情交接", owner: "INTAKE_OFFICER" },
  { code: "EVIDENCE_INTRODUCTION", label: "证据介绍", group: "案情交接", owner: "EVIDENCE_CLERK" },
  { code: "INTAKE_QUESTIONS_GENERATING", label: "生成问题", group: "案情澄清", owner: "INTAKE_OFFICER" },
  { code: "PARTY_ANSWERS_OPEN", label: "双方回答", group: "案情澄清", owner: "PARTIES", deadline: true },
  { code: "INTAKE_SYNTHESIZING", label: "整理案情", group: "案情澄清", owner: "INTAKE_OFFICER" },
  { code: "EVIDENCE_REQUESTS_GENERATING", label: "定向补证", group: "证据核验", owner: "EVIDENCE_CLERK" },
  { code: "PARTY_EVIDENCE_OPEN", label: "双方补证", group: "证据核验", owner: "PARTIES", deadline: true },
  { code: "EVIDENCE_SYNTHESIZING", label: "整理证据", group: "证据核验", owner: "EVIDENCE_CLERK" },
  { code: "DOSSIER_FREEZING", label: "冻结卷宗", group: "卷宗冻结", owner: "SYSTEM" },
  { code: "JUDGE_V1_GENERATING", label: "法官草案 V1", group: "裁决评审", owner: "PRESIDING_JUDGE" },
  { code: "JURY_REVIEWING", label: "评审复核", group: "裁决评审", owner: "JURY_PANEL" },
  { code: "JUDGE_V2_GENERATING", label: "法官草案 V2", group: "裁决评审", owner: "PRESIDING_JUDGE" },
  { code: "HUMAN_REVIEW_OPEN", label: "人工审核", group: "人工审核", owner: "SYSTEM" },
  { code: "CLOSED", label: "庭审封存", group: "人工审核", owner: "SYSTEM" },
]);

const STAGE_BY_CODE = new Map(HEARING_FLOW_STAGES.map((stage) => [stage.code, stage]));

export const HEARING_FLOW_GROUPS = Object.freeze([
  "案情交接",
  "案情澄清",
  "证据核验",
  "卷宗冻结",
  "裁决评审",
  "人工审核",
]);

export function normalizeHearingFlowStage(value) {
  const code = String(value || "").trim().toUpperCase();
  return STAGE_BY_CODE.has(code) ? code : "COURT_PREPARING";
}

export function hearingFlowStage(status = {}) {
  const explicit =
    status.flow_stage ??
    status.flowStage ??
    status.stage_code ??
    status.stageCode;
  return normalizeHearingFlowStage(explicit);
}

export function hearingFlowStageDefinition(value) {
  return STAGE_BY_CODE.get(normalizeHearingFlowStage(value));
}

export function hearingFlowProgress(value) {
  const activeCode = normalizeHearingFlowStage(value);
  const activeIndex = HEARING_FLOW_STAGES.findIndex((stage) => stage.code === activeCode);

  return HEARING_FLOW_GROUPS.map((label, index) => {
    const stageIndexes = HEARING_FLOW_STAGES
      .map((stage, stageIndex) => (stage.group === label ? stageIndex : -1))
      .filter((stageIndex) => stageIndex >= 0);
    const first = stageIndexes[0];
    const last = stageIndexes.at(-1);
    const tone = activeIndex > last ? "complete" : activeIndex >= first ? "active" : "pending";
    return {
      key: label,
      number: index + 1,
      label,
      tone,
      status: tone === "complete" ? "已完成" : tone === "active" ? "进行中" : "未开始",
      connectorTone: index === HEARING_FLOW_GROUPS.length - 1 ? "none" : tone === "complete" ? "complete" : "pending",
    };
  });
}

export function isPartyInputStage(value) {
  return ["PARTY_ANSWERS_OPEN", "PARTY_EVIDENCE_OPEN"].includes(
    normalizeHearingFlowStage(value),
  );
}

export function isJudgeLlmStage(value) {
  return ["JUDGE_V1_GENERATING", "JUDGE_V2_GENERATING"].includes(
    normalizeHearingFlowStage(value),
  );
}
