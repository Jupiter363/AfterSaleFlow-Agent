const ROLE_PRESENTATIONS = {
  CUSTOMER_SERVICE: { identity: "争议接待官", name: "小衡" },
  DISPUTE_INTAKE_OFFICER: { identity: "争议接待官", name: "小衡" },
  INTAKE_OFFICER: { identity: "争议接待官", name: "小衡" },
  EVIDENCE_CLERK: { identity: "证据书记官", name: "小册" },
  JUDGE: { identity: "主审法官", name: "小正" },
  AI_JUDGE: { identity: "主审法官", name: "小正" },
  PRESIDING_JUDGE: { identity: "主审法官", name: "小正" },
  JURY: { identity: "AI 评审员", name: "小察" },
  AI_JURY: { identity: "AI 评审员", name: "小察" },
  JURY_PANEL: { identity: "AI 评审员", name: "小察" },
  REVIEW_COPILOT: { identity: "审核解释官", name: "小译" },
};

const IDENTITY_NAMES = {
  "争议接待官": "小衡",
  "案情接待官": "小衡",
  "证据书记官": "小册",
  "主审法官": "小正",
  "AI 法官": "小正",
  "AI 评审员": "小察",
  "审核解释官": "小译",
  "外部案件导入助手": "助手",
};

const NAME_IDENTITIES = Object.fromEntries(
  Object.entries(IDENTITY_NAMES).map(([identity, name]) => [name, identity]),
);

function normalizedRole(role) {
  return String(role || "").trim().toUpperCase();
}

export function agentSpeakerPresentation(role, fallbackIdentity = "数字人") {
  return ROLE_PRESENTATIONS[normalizedRole(role)] || {
    identity: String(fallbackIdentity || "数字人"),
    name: "助手",
  };
}

export function agentSpeakerPresentationForIdentity(identity) {
  const normalizedIdentity = String(identity || "数字人").trim() || "数字人";
  const parts = normalizedIdentity
    .split(/\s*[·•]\s*/u)
    .map((part) => part.trim())
    .filter(Boolean);

  if (parts.length === 2 && NAME_IDENTITIES[parts[0]]) {
    return { identity: parts[1], name: parts[0] };
  }

  return {
    identity: normalizedIdentity,
    name: IDENTITY_NAMES[normalizedIdentity] || "助手",
  };
}

export function agentSpeakerTone(role, identity = "") {
  const normalizedIdentity = String(identity).toUpperCase();
  if (/接待|受理/u.test(normalizedIdentity)) return "intake";
  if (/书记官|证据/u.test(normalizedIdentity)) return "evidence";
  if (/法官|裁决/u.test(normalizedIdentity)) return "judge";
  if (/评审员|评审团|陪审/u.test(normalizedIdentity)) return "jury";
  if (/审核解释|审核辅助/u.test(normalizedIdentity)) return "review";
  if (/导入|引导/u.test(normalizedIdentity)) return "guide";

  const normalized = normalizedRole(role);
  if (/INTAKE|CUSTOMER_SERVICE/u.test(normalized)) return "intake";
  if (/EVIDENCE/u.test(normalized)) return "evidence";
  if (/JUDGE/u.test(normalized)) return "judge";
  if (/JURY/u.test(normalized)) return "jury";
  if (/REVIEW/u.test(normalized)) return "review";
  if (/GUIDE/u.test(normalized)) return "guide";
  return "default";
}

export function agentSpeakerLine(role, fallbackIdentity = "数字人") {
  const presentation = agentSpeakerPresentation(role, fallbackIdentity);
  return `${presentation.identity} ${presentation.name} 正常发言：`;
}

export function agentSpeakerLineForIdentity(identity) {
  const presentation = agentSpeakerPresentationForIdentity(identity);
  return `${presentation.identity} ${presentation.name} 正常发言：`;
}

export function streamCardPresentation({
  operation,
  nodeName,
  fieldPath,
  senderRole,
  agentLabel,
} = {}) {
  const operationCode = String(operation || "").toUpperCase();
  const node = String(nodeName || "").toLowerCase();

  if (
    operationCode === "HEARING_JURY_REVIEW" ||
    node === "hearing_jury_review"
  ) {
    return {
      key: "jury-review",
      senderRole: "JURY_PANEL",
      ...agentSpeakerPresentation("JURY_PANEL"),
    };
  }

  if (
    operationCode === "HEARING_JUDGE_V1" ||
    node === "hearing_judge_v1"
  ) {
    return {
      key: "adjudication-draft",
      senderRole: "PRESIDING_JUDGE",
      ...agentSpeakerPresentation("PRESIDING_JUDGE"),
    };
  }

  if (
    operationCode === "HEARING_JUDGE_V2" ||
    node === "hearing_judge_v2"
  ) {
    return {
      key: "adjudication-draft-v2",
      senderRole: "PRESIDING_JUDGE",
      ...agentSpeakerPresentation("PRESIDING_JUDGE"),
    };
  }

  if (
    operationCode.startsWith("HEARING_INTAKE_") ||
    node.startsWith("hearing_intake_")
  ) {
    return {
      key: "default",
      senderRole: "INTAKE_OFFICER",
      ...agentSpeakerPresentation("INTAKE_OFFICER"),
    };
  }

  if (
    operationCode.startsWith("HEARING_EVIDENCE_") ||
    node.startsWith("hearing_evidence_")
  ) {
    return {
      key: "default",
      senderRole: "EVIDENCE_CLERK",
      ...agentSpeakerPresentation("EVIDENCE_CLERK"),
    };
  }

  if (operationCode.startsWith("EVIDENCE_") || normalizedRole(senderRole) === "EVIDENCE_CLERK") {
    return {
      key: "default",
      senderRole: "EVIDENCE_CLERK",
      ...agentSpeakerPresentation("EVIDENCE_CLERK"),
    };
  }

  const presentation = agentSpeakerPresentation(senderRole, agentLabel);
  return {
    key: "default",
    senderRole: normalizedRole(senderRole),
    ...presentation,
  };
}
