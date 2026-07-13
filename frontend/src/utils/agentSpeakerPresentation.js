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
};

function normalizedRole(role) {
  return String(role || "").trim().toUpperCase();
}

export function agentSpeakerPresentation(role, fallbackIdentity = "数字人") {
  return ROLE_PRESENTATIONS[normalizedRole(role)] || {
    identity: String(fallbackIdentity || "数字人"),
    name: "助手",
  };
}

export function agentSpeakerLine(role, fallbackIdentity = "数字人") {
  const presentation = agentSpeakerPresentation(role, fallbackIdentity);
  return `${presentation.identity} · ${presentation.name} 正常发言：`;
}

export function agentSpeakerLineForIdentity(identity) {
  const normalizedIdentity = String(identity || "数字人");
  return `${normalizedIdentity} · ${IDENTITY_NAMES[normalizedIdentity] || "助手"} 正常发言：`;
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
  const field = String(fieldPath || "");

  if (field.endsWith("public_message") || node.includes("unified_jury")) {
    return {
      key: "jury-review",
      senderRole: "JURY_PANEL",
      ...agentSpeakerPresentation("JURY_PANEL"),
    };
  }

  if (node === "adjudication_draft_node") {
    return {
      key: "adjudication-draft",
      senderRole: "PRESIDING_JUDGE",
      ...agentSpeakerPresentation("PRESIDING_JUDGE"),
    };
  }

  if (node === "issue_framing_node") {
    return {
      key: "default",
      senderRole: "PRESIDING_JUDGE",
      ...agentSpeakerPresentation("PRESIDING_JUDGE"),
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
