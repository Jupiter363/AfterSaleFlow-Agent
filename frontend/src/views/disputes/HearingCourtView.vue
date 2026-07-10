<script setup>
import {
  computed,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  watch,
} from "vue";
import { useRoute, useRouter } from "vue-router";
import { hearingApi } from "../../api/hearing";
import { evidenceApi } from "../../api/evidence";
import { roomApi } from "../../api/rooms";
import DigitalHuman from "../../components/avatar/DigitalHuman.vue";
import PhaseCountdown from "../../components/room/PhaseCountdown.vue";
import RoomShell from "../../components/room/RoomShell.vue";
import { actor } from "../../state/actor";
import {
  createRoomState,
  streamRoomEvents,
} from "../../stores/room";
import { displayRoomMessageText } from "../../utils/displayText";

const props = defineProps({
  initialHearing: { type: Object, default: null },
  initialEvidenceCatalog: { type: Object, default: null },
  viewerRole: { type: String, default: "" },
  deadlineAt: { type: String, default: "" },
  serverNow: { type: String, default: "" },
  roundLimit: { type: Number, default: 3 },
  confirmSettlementAction: { type: Function, default: null },
  eventStreamer: { type: Function, default: null },
  initialEvents: { type: Array, default: null },
  initialMessages: { type: Array, default: null },
  messageAction: { type: Function, default: null },
  proposeSettlementAction: { type: Function, default: null },
  supplementAction: { type: Function, default: null },
  submitEvidenceBatchAction: { type: Function, default: null },
  submitRoundAction: { type: Function, default: null },
  completeHearingAction: { type: Function, default: null },
});

const route = useRoute();
const router = useRouter();
const hearing = ref(props.initialHearing);
const evidenceCatalog = ref(props.initialEvidenceCatalog);
const agentState = ref("LISTENING");
const reviewGateOpen = ref(false);
const error = ref("");
const confirmingVersion = ref(null);
const messages = ref([...(props.initialMessages || [])]);
const caseEvents = ref([...(props.initialEvents || [])]);
const loadingState = reactive({
  hearing: props.initialHearing === null,
  evidence: props.initialEvidenceCatalog === null,
  messages: props.initialMessages === null,
  events: props.initialEvents === null,
});
const settlementOpen = ref(false);
const ledgerOpen = ref(false);
const proposalText = ref("");
const statementText = ref("");
const proposing = ref(false);
const supplementing = ref(false);
const submittingRound = ref(false);
const eventState = reactive(createRoomState());
const eventAbortController = new AbortController();
const caseId = computed(() => route.params.caseId);
const role = computed(() => props.viewerRole || actor.role);
const demoActorIds = {
  USER: "user-local",
  MERCHANT: "merchant-local",
  PLATFORM_REVIEWER: "reviewer-local",
};
const effectiveActor = computed(() => {
  if (actor.role === role.value) return actor;
  return {
    ...actor,
    id: demoActorIds[role.value] || actor.id,
    role: role.value,
  };
});
const isReviewer = computed(() => role.value === "PLATFORM_REVIEWER");
const rounds = computed(() => hearing.value?.rounds || []);
const settlements = computed(() => hearing.value?.settlements || []);
const hearingStatus = computed(() => hearing.value?.status || {});
const currentRound = computed(
  () => Math.max(1, ...rounds.value.map((round) => round.round_no || 1)),
);
const activeRound = computed(
  () =>
    rounds.value.find((round) => round.status === "OPEN") ||
    rounds.value.at(-1) ||
    null,
);
const activeSettlement = computed(
  () =>
    settlements.value.find((settlement) => settlement.status !== "SUPERSEDED") ||
    null,
);
const effectiveDeadline = computed(
  () =>
    props.deadlineAt ||
    new Date(Date.now() + 3 * 60 * 60 * 1000).toISOString(),
);
const effectiveServerNow = computed(
  () => props.serverNow || new Date().toISOString(),
);
const stageClockLocalNow = ref(Date.now());
const stageClockAnchorLocal = ref(Date.now());
const stageClockAnchorServer = ref(Date.parse(effectiveServerNow.value));

watch(
  effectiveServerNow,
  (value) => {
    const parsed = Date.parse(value);
    stageClockAnchorServer.value = Number.isFinite(parsed) ? parsed : Date.now();
    stageClockAnchorLocal.value = Date.now();
    stageClockLocalNow.value = Date.now();
  },
  { immediate: true },
);

const estimatedServerNowMs = computed(
  () => stageClockAnchorServer.value + (stageClockLocalNow.value - stageClockAnchorLocal.value),
);
const stageClockTimer = setInterval(() => {
  stageClockLocalNow.value = Date.now();
}, 1000);
const connectionState = computed(() => {
  if (eventState.connected) return "connected";
  if (eventState.reconnecting) return "reconnecting";
  return "offline";
});
const roundStatusLabels = {
  OPEN: "进行中",
  WAITING: "等待另一方",
  COMPLETED: "已完成",
  CLOSED: "已封存",
  TIMEOUT: "已超时",
  FORCED_CLOSED: "已强制收束",
};
const evidenceSourceType = computed(() => {
  if (role.value === "MERCHANT") return "MERCHANT_UPLOAD";
  if (role.value === "USER") return "USER_UPLOAD";
  return "PLATFORM_UPLOAD";
});
const partyRoles = ["USER", "MERCHANT"];
const reviewGateEvents = new Set([
  "HEARING_PHASE_CHANGED",
  "REVIEW_TASK_CREATED",
  "REVIEW_GATE_READY",
  "HUMAN_REVIEW_OPENED",
  "ADJUDICATION_DRAFT_READY",
]);
const isCaseParty = computed(() => partyRoles.includes(role.value));
const submittedRoles = computed(
  () => activeRound.value?.submitted_roles || activeRound.value?.submittedRoles || [],
);
const currentActorSubmitted = computed(
  () =>
    Boolean(
      activeRound.value?.current_actor_submitted ||
        activeRound.value?.currentActorSubmitted ||
        submittedRoles.value.includes(role.value) ||
        partyHasSpokenInActiveRound(role.value),
    ),
);
const activeRoundClosed = computed(() =>
  ["COMPLETED", "FORCED_CLOSED", "CLOSED"].includes(
    activeRound.value?.status || "",
  ),
);
const activeRoundNo = computed(
  () => activeRound.value?.round_no || activeRound.value?.roundNo || 0,
);
function messageRoundNo(message) {
  const raw =
    message?.hearing_round ??
    message?.hearingRound ??
    message?.round_no ??
    message?.roundNo ??
    activeRoundNo.value;
  const numeric = Number(raw);
  return Number.isFinite(numeric) ? numeric : 0;
}

function messageSenderRole(message) {
  return message?.sender_role || message?.senderRole || "";
}

function messageType(message) {
  return String(message?.message_type || message?.messageType || "").toUpperCase();
}

function partyHasSpokenInActiveRound(partyRole) {
  const roundNo = Number(activeRoundNo.value);
  if (!roundNo || !partyRole) return false;
  return messages.value.some(
    (message) =>
      messageSenderRole(message) === partyRole &&
      messageType(message) === "PARTY_TEXT" &&
      messageRoundNo(message) === roundNo,
  );
}
const allPartiesSubmittedInActiveRound = computed(() =>
  partyRoles.every(
    (partyRole) =>
      submittedRoles.value.includes(partyRole) ||
      partyHasSpokenInActiveRound(partyRole),
  ),
);
const finalRoundSealed = computed(
  () =>
    Boolean(statusField("final_round_sealed", "finalRoundSealed", false)) ||
    (activeRoundClosed.value && activeRoundNo.value >= props.roundLimit),
);
const reviewHandoffVisible = computed(
  () => isCaseParty.value && finalRoundSealed.value,
);
const hearingPhase = computed(() =>
  statusField("hearing_phase", "hearingPhase", ""),
);
const serverPhaseLabel = computed(() =>
  sanitizeHearingCopy(statusField("phase_label", "phaseLabel", "")),
);
const serverNextStepHint = computed(() =>
  sanitizeHearingCopy(statusField("next_step_hint", "nextStepHint", "")),
);
const serverCanCompleteHearing = computed(() =>
  Boolean(statusField("can_complete_hearing", "canCompleteHearing", false)),
);
const serverReviewGateReady = computed(
  () =>
    Boolean(statusField("review_gate_ready", "reviewGateReady", false)) ||
    hearingPhase.value === "REVIEW_GATE_READY",
);
const draftReadyForResult = computed(
  () =>
    serverCanCompleteHearing.value ||
    ["DRAFT_READY", "REVIEW_GATE_READY"].includes(hearingPhase.value),
);
const completeHearingHint = computed(
  () =>
    serverNextStepHint.value ||
    (serverCanCompleteHearing.value
      ? "AI 法官已生成裁决草案，可进入结果页查看草案说明。"
      : "三轮陈述封存后，需要等待 AI 法官生成裁决草案。"),
);
const completeHearingButtonLabel = computed(() =>
  serverCanCompleteHearing.value ? "查看裁决草案" : "等待裁决草案",
);
const reviewHandoffTitle = computed(() =>
  serverPhaseLabel.value ||
  (draftReadyForResult.value || serverReviewGateReady.value
    ? "裁决草案已生成"
    : "三轮陈述已封存，等待裁决草案"),
);
const reviewHandoffBody = computed(() =>
  serverNextStepHint.value ||
  (draftReadyForResult.value || serverReviewGateReady.value
    ? "AI 法官已生成裁决草案，可进入结果页查看草案说明。"
    : "本案已经达到三轮陈述上限，双方内容已自动封存。AI 法官会基于庭审记录和证据架输出确定裁决方案草案。"),
);
const counterpartyLabel = computed(() =>
  role.value === "USER" ? "商家" : "用户",
);
const canSubmitRound = computed(
  () =>
    !loadingState.hearing &&
    isCaseParty.value &&
    !currentActorSubmitted.value &&
    !activeRoundClosed.value,
);
const canSubmitStatement = computed(
  () =>
    !loadingState.hearing &&
    isCaseParty.value &&
    !activeRoundClosed.value,
);
const activeRoundDeadline = computed(
  () => activeRound.value?.round_deadline_at || activeRound.value?.roundDeadlineAt || "",
);
const roundStepLabels = ["事实陈述", "证据解释", "方案确认"];
const evidenceRailProfiles = {
  user: {
    key: "user",
    role: "USER",
    eyebrow: "USER EVIDENCE",
    title: "用户证据原件匣",
    description: "固定高度展示，更多材料在内部滚动。",
    badge: "用户侧",
    ariaLabel: "用户已提交证据",
    supplementLabel: "补充用户证据",
  },
  merchant: {
    key: "merchant",
    role: "MERCHANT",
    eyebrow: "MERCHANT EVIDENCE",
    title: "商家证据原件匣",
    description: "正式提交后进入庭审可见证据架。",
    badge: "商家侧",
    ariaLabel: "商家已提交证据",
    supplementLabel: "补充商家证据",
  },
};
const leftEvidenceRail = computed(() =>
  role.value === "MERCHANT" ? evidenceRailProfiles.merchant : evidenceRailProfiles.user,
);
const rightEvidenceRail = computed(() =>
  leftEvidenceRail.value.key === "merchant"
    ? evidenceRailProfiles.user
    : evidenceRailProfiles.merchant,
);
const evidenceItems = computed(() => evidenceCatalog.value?.items || []);
const leftEvidenceItems = computed(() =>
  evidenceItemsForRole(leftEvidenceRail.value.role),
);
const rightEvidenceItems = computed(() =>
  evidenceItemsForRole(rightEvidenceRail.value.role),
);
const activeRoundSummary = computed(() => summary(activeRound.value));
const currentRoundLabel = computed(
  () => roundStepLabels[Math.min(currentRound.value, roundStepLabels.length) - 1] || "庭审陈述",
);
const roundSubmitDescription = computed(() => {
  if (activeRoundClosed.value) {
    return "双方已提交本轮，本轮陈述已封存；第三轮结束后，AI 法官会统一生成确定的裁决方案草案。";
  }
  if (allPartiesSubmittedInActiveRound.value) {
    return "双方本轮陈述均已入卷，等待 AI 法官收束本轮并推进庭审流程。";
  }
  if (currentActorSubmitted.value) {
    return `已提交本轮，等待${counterpartyLabel.value}。双方都提交后，系统会自动封存本轮陈述并开放下一轮。`;
  }
  return "当前陈述、证据解释或对法官拟处理方向的确认或说明异议会被封装为本轮立场。双方都点击提交，或 5 分钟时效届满后，系统自动封存并推进流程。";
});
const statementInputDisabled = computed(
  () => !canSubmitStatement.value,
);
const stageDockMode = computed(() => {
  if (reviewHandoffVisible.value) return "handoff";
  if (activeRoundClosed.value) return "sealed";
  if (allPartiesSubmittedInActiveRound.value) return "waiting";
  if (currentActorSubmitted.value) return "waiting";
  return "active";
});
const stageDockTitle = computed(() => {
  if (reviewHandoffVisible.value) return reviewHandoffTitle.value;
  if (activeRoundClosed.value) return "本轮已封存";
  if (allPartiesSubmittedInActiveRound.value) return "双方已陈述，等待法官收束";
  if (currentActorSubmitted.value) return `已提交本轮，等待${counterpartyLabel.value}`;
  return `第 ${currentRound.value} 轮 · ${currentRoundLabel.value}`;
});
const stageDockBody = computed(() => {
  if (reviewHandoffVisible.value) return reviewHandoffBody.value;
  if (activeRoundClosed.value) {
    return "双方已提交本轮，陈述已经封存。AI 法官会读取庭审记录和证据架，生成本轮判断或推进到下一轮。";
  }
  if (allPartiesSubmittedInActiveRound.value) {
    return "双方本轮陈述均已进入庭审记录，等待 AI 法官收束本轮并决定是否推进下一阶段。";
  }
  if (currentActorSubmitted.value) {
    return `你的本轮立场已经入卷，系统会在${counterpartyLabel.value}提交或倒计时结束后自动封存。`;
  }
  return "请双方围绕法官问题完成本轮陈述。双方都提交，或 5 分钟倒计时届满后，本轮会自动封存。";
});
const stageDockBadge = computed(() => {
  if (reviewHandoffVisible.value) return draftReadyForResult.value || serverReviewGateReady.value ? "草案已生成" : "庭审封存";
  if (activeRoundClosed.value) return "本轮已封存";
  if (allPartiesSubmittedInActiveRound.value) return "等待法官";
  if (currentActorSubmitted.value) return "等待对方";
  return "进行中";
});
function formatStageClock(deadlineAt) {
  const deadlineMs = Date.parse(deadlineAt || "");
  if (!Number.isFinite(deadlineMs)) return "05:00";
  const totalSeconds = Math.max(
    0,
    Math.floor((deadlineMs - estimatedServerNowMs.value) / 1000),
  );
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  const parts =
    hours > 0
      ? [hours, minutes, seconds]
      : [minutes, seconds];
  return parts.map((value) => String(value).padStart(2, "0")).join(":");
}
const stageDockClock = computed(() => {
  if (reviewHandoffVisible.value || activeRoundClosed.value) {
    return { label: "当前轮次还剩：", value: "00:00" };
  }
  if (activeRoundDeadline.value) {
    return { label: "当前轮次还剩：", value: formatStageClock(activeRoundDeadline.value) };
  }
  return { label: "当前轮次还剩：", value: "05:00" };
});
const roundProgressItems = computed(() =>
  Array.from({ length: props.roundLimit }, (_, index) => {
    const roundNumber = index + 1;
    const round = rounds.value.find((item) => (item.round_no || item.roundNo) === roundNumber);
    let status = "未开始";
    let tone = "pending";
    if (roundNumber < currentRound.value || ["COMPLETED", "FORCED_CLOSED", "CLOSED"].includes(round?.status || "")) {
      status = ["COMPLETED", "FORCED_CLOSED", "CLOSED"].includes(round?.status || "") ? "已封存" : "已完成";
      tone = "complete";
    } else if (roundNumber === currentRound.value) {
      status = activeRoundClosed.value ? "已封存" : "进行中";
      tone = activeRoundClosed.value ? "complete" : "active";
    }
    return {
      number: roundNumber,
      label: roundStepLabels[index] || `第 ${roundNumber} 轮`,
      status,
      tone,
      connectorTone: index < props.roundLimit - 1 ? (tone === "complete" ? "complete" : "pending") : "none",
    };
  }),
);
const partySubmissionStatuses = computed(() =>
  [
    { role: "USER", label: "用户提交" },
    { role: "MERCHANT", label: "商家提交" },
  ].map((party) => {
    if (activeRoundClosed.value) {
      return { ...party, status: "已封存", tone: "sealed" };
    }
    if (
      submittedRoles.value.includes(party.role) ||
      partyHasSpokenInActiveRound(party.role)
    ) {
      return { ...party, status: "已提交", tone: "submitted" };
    }
    return { ...party, status: "未提交", tone: "pending" };
  }),
);
const timeStatus = computed(() => {
  if (reviewHandoffVisible.value) {
    return {
      label: "时间/封存",
      value: draftReadyForResult.value || serverReviewGateReady.value ? "草案已生成" : "等待裁决草案",
      tone: "waiting",
    };
  }
  if (activeRoundClosed.value) return { label: "时间/封存", value: "已封存", tone: "sealed" };
  if (activeRoundDeadline.value) {
    return {
      label: "本轮倒计时",
      value: formatStageClock(activeRoundDeadline.value),
      tone: "active",
    };
  }
  return { label: "时间/封存", value: "等待法官处理", tone: "waiting" };
});
const judgeReviewStatus = computed(() => {
  if (reviewHandoffVisible.value) {
    return {
      label: "法官/评审",
      value: draftReadyForResult.value || serverReviewGateReady.value ? "草案已生成" : "法官处理中",
      tone: "processing",
    };
  }
  if (activeRoundClosed.value) return { label: "法官/评审", value: "法官处理中", tone: "processing" };
  if (stageDockMode.value === "waiting") return { label: "法官/评审", value: "等待双方陈述", tone: "waiting" };
  return { label: "法官/评审", value: "法官提问中", tone: "active" };
});
const liveTranscriptItems = computed(() =>
  messages.value
    .filter((message) => !isSystemAuditOnlyMessage(message))
    .map((message, index) => {
      const rawText = message.message_text || message.text || message.content || "";
      if (!rawText) return null;
      const type = messageType(message);
      const text = transcriptTextForMessage(message);
      const senderRole = message.sender_role || message.senderRole || "";
      return {
        id: message.id || `live-message-${message.sequence_no || index}`,
        type: transcriptTypeForRole(senderRole),
        speaker: transcriptSpeakerForRole(senderRole),
        badge: transcriptBadgeForMessage(message),
        time: transcriptTime(message.created_at || message.createdAt),
        text,
        riskLevel: juryRiskLabel(messagePayload(message)?.risk_level),
        confidenceScore: juryConfidenceLabel(messagePayload(message)?.confidence_score),
        isFormalJuryReport: type === "JURY_REVIEW_REPORT",
      };
    })
    .filter(Boolean),
);
const courtTranscriptItems = computed(() => liveTranscriptItems.value);
const courtLedgerItems = computed(() => {
  const roundItems = rounds.value.map((round) => ({
    id: round.round_id || `round-${round.round_no}`,
    title: `第 ${round.round_no} 轮`,
    status: roundStatusLabel(round.status),
    text: ledgerRoundText(round),
    statusCode: round.status,
    tone: "round",
  }));
  const messageItems = messages.value
    .filter((message) => !isSystemAuditOnlyMessage(message))
    .map((message) => ledgerItemForMessage(message))
    .filter(Boolean);
  const eventItems = caseEvents.value
    .map((event) => ledgerItemForCaseEvent(event))
    .filter(Boolean);
  return [...roundItems, ...messageItems, ...eventItems].sort((left, right) => {
    const leftRound = left.roundNo || 0;
    const rightRound = right.roundNo || 0;
    if (leftRound !== rightRound) return leftRound - rightRound;
    return (left.sequenceNo || 0) - (right.sequenceNo || 0);
  });
});

const transcriptRoleProfiles = {
  INTAKE_OFFICER: { type: "intake", speaker: "案情接待官", badge: "案情接待" },
  EVIDENCE_CLERK: { type: "clerk", speaker: "证据书记官", badge: "证据归档" },
  JUDGE: { type: "judge", speaker: "主审法官", badge: "法官宣读" },
  JURY: { type: "jury", speaker: "AI 评审团", badge: "评审团观察" },
  AI_JURY: { type: "jury", speaker: "AI 评审团", badge: "评审团观察" },
  USER: { type: "user", speaker: "用户陈述", badge: "" },
  MERCHANT: { type: "merchant", speaker: "商家陈述", badge: "" },
};

function transcriptProfileForRole(senderRole) {
  return transcriptRoleProfiles[senderRole] || transcriptRoleProfiles.JUDGE;
}

function transcriptTypeForRole(senderRole) {
  return transcriptProfileForRole(senderRole).type;
}

function transcriptSpeakerForRole(senderRole) {
  return transcriptProfileForRole(senderRole).speaker;
}

function transcriptBadgeForRole(senderRole) {
  return transcriptProfileForRole(senderRole).badge;
}

function transcriptBadgeForMessage(message) {
  if (messageType(message) === "JURY_REVIEW_REPORT") return "评审团复核报告";
  return transcriptBadgeForRole(messageSenderRole(message));
}

function transcriptBadgeForItem(item) {
  if (item.badge) return item.badge;
  if (item.type === "judge") return "法官宣读";
  if (item.type === "jury") return "评审团观察";
  return "";
}

function rawMessageText(message) {
  return message?.message_text || message?.messageText || message?.text || message?.content || "";
}

function messagePayload(message) {
  const rawText = rawMessageText(message);
  if (!rawText || typeof rawText !== "string") return null;
  const trimmed = rawText.trim();
  if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null;
  try {
    return JSON.parse(trimmed);
  } catch {
    return null;
  }
}

function messageVisibility(message) {
  return String(
    message?.visibility ||
      message?.visibility_scope ||
      message?.visibilityScope ||
      messagePayload(message)?.visibility ||
      "",
  ).toUpperCase();
}

function isSystemAuditOnlyMessage(message) {
  return messageVisibility(message) === "SYSTEM_AUDIT_ONLY";
}

function juryRiskLabel(value) {
  const normalized = String(value || "").toUpperCase();
  return {
    LOW: "低风险",
    MEDIUM: "中风险",
    HIGH: "高风险",
  }[normalized] || "中风险";
}

function juryConfidenceLabel(value) {
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return "75/100";
  const score = numeric <= 1 ? numeric * 100 : numeric;
  return `${Math.round(score)}/100`;
}

function transcriptTextForMessage(message) {
  if (messageType(message) === "JURY_REVIEW_REPORT") {
    return formatJuryReviewReport(messagePayload(message), rawMessageText(message));
  }
  return stripTranscriptPreamble(
    sanitizeHearingCopy(displayRoomMessageText(rawMessageText(message))),
  );
}

function formatJuryReviewReport(payload, fallbackText = "") {
  if (!payload) {
    return stripTranscriptPreamble(
      sanitizeHearingCopy(displayRoomMessageText(fallbackText)),
    );
  }
  const parts = [];
  if (payload.summary) parts.push(sanitizeHearingCopy(displayRoomMessageText(payload.summary)));
  const recommendations = Array.isArray(payload.recommendations)
    ? payload.recommendations
    : payload.recommendation
      ? [payload.recommendation]
      : [];
  if (recommendations.length) {
    parts.push(`建议：${recommendations.map((item) => sanitizeHearingCopy(displayRoomMessageText(item))).join("；")}`);
  }
  if (payload.review_notes) {
    parts.push(sanitizeHearingCopy(displayRoomMessageText(payload.review_notes)));
  }
  return parts.filter(Boolean).join(" ") || "评审团已完成复核，报告已交由法官参考。";
}

function stripTranscriptPreamble(text) {
  return String(text || "").replace(
    /^(案情接待官宣读案情卷宗|证据书记官宣读证据卷宗)[：:]\s*/u,
    "",
  );
}

function transcriptTime(value) {
  if (!value) return "刚刚";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "刚刚";
  return date.toLocaleTimeString("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
}

function evidenceTypeLabel(type) {
  return {
    image: "图片",
    video: "视频",
    text: "文本",
  }[type] || "文件";
}

function evidenceItemsForRole(partyRole) {
  return evidenceItems.value.filter(
    (item) =>
      evidenceSubmittedByRole(item) === partyRole &&
      evidenceSubmissionStatus(item) === "SUBMITTED",
  );
}

function evidenceField(item, snakeCaseKey, camelCaseKey, fallback = "") {
  return item?.[snakeCaseKey] ?? item?.[camelCaseKey] ?? fallback;
}

function statusField(snakeCaseKey, camelCaseKey, fallback = "") {
  return hearingStatus.value?.[snakeCaseKey] ?? hearingStatus.value?.[camelCaseKey] ?? fallback;
}

function sanitizeHearingCopy(value) {
  return String(value || "")
    .replace(
      "裁决草案已经进入平台审核入口，可查看结果页并等待审核员确认。",
      "裁决草案已生成，可进入结果页查看草案说明。",
    )
    .replace("裁决草案已经进入平台审核入口", "裁决草案已生成")
    .replace("进入平台终审，等待审核员确认最终结果", "查看裁决草案并等待后续确认")
    .replace("最终由平台审核员确认", "后续进入确认流程")
    .replace("最终结果仍需平台审核确认", "最终结果以后续确认为准")
    .replace("最终结果仍需平台审核员确认", "最终结果以后续确认为准")
    .replace("等待平台审核员确认", "等待后续确认")
    .replace("希望平台审核员给出", "希望后续确认环节给出")
    .replaceAll("平台审核员确认", "后续确认")
    .replaceAll("审核员确认", "后续确认")
    .replaceAll("平台审核确认", "后续确认")
    .replaceAll("平台审核员", "后续确认环节")
    .replaceAll("平台终审", "后续确认")
    .replaceAll("审核员终审", "后续确认")
    .replaceAll("人类终审", "后续确认");
}

function statusAllowsCompletion(status) {
  return Boolean(status?.can_complete_hearing ?? status?.canCompleteHearing);
}

function evidenceSubmittedByRole(item) {
  return evidenceField(item, "submitted_by_role", "submittedByRole", "");
}

function evidenceId(item) {
  return (
    evidenceField(item, "evidence_id", "evidenceId", "") ||
    evidenceField(item, "id", "id", "")
  );
}

function evidenceOriginalFilename(item) {
  return evidenceField(item, "original_filename", "originalFilename", "");
}

function evidenceFilename(item) {
  return evidenceOriginalFilename(item) || evidenceId(item) || "未命名证据";
}

function evidenceSubmissionStatus(item) {
  return String(
    evidenceField(item, "submission_status", "submissionStatus", "SUBMITTED"),
  ).toUpperCase();
}

function evidenceSubmissionStatusLabel(item) {
  const status = evidenceSubmissionStatus(item);
  if (status === "PENDING_SUBMISSION") return "待提交";
  if (status === "SUBMITTED") return "已提交";
  if (status === "VOIDED") return "已作废";
  if (["LOCKED", "ADMITTED", "IN_DOSSIER"].includes(status)) return "已入卷";
  return status || "待确认";
}

function fileExtension(value) {
  const cleanValue = String(value || "").split(/[?#]/)[0];
  const fileName = cleanValue.split(/[\\/]/).pop() || "";
  const lastDotIndex = fileName.lastIndexOf(".");
  if (lastDotIndex <= 0 || lastDotIndex === fileName.length - 1) return "";
  return fileName.slice(lastDotIndex + 1).toLowerCase();
}

function evidenceCardType(item) {
  const extension = fileExtension(
    evidenceOriginalFilename(item) ||
      evidenceField(item, "content_url", "contentUrl", ""),
  );
  const evidenceType = String(
    evidenceField(item, "evidence_type", "evidenceType", ""),
  ).toUpperCase();
  if (
    ["mp4", "mov", "avi", "webm", "mkv", "m4v"].includes(extension) ||
    evidenceType === "VIDEO"
  ) {
    return "video";
  }
  if (
    ["png", "jpg", "jpeg", "webp", "gif", "bmp", "svg"].includes(extension) ||
    ["IMAGE", "CHAT_SCREENSHOT"].includes(evidenceType)
  ) {
    return "image";
  }
  return "text";
}

function evidenceCardTone(item) {
  const extension = fileExtension(evidenceOriginalFilename(item));
  const type = evidenceCardType(item);
  if (type === "video") return "gold";
  if (type === "image") return "blue";
  if (["md", "markdown"].includes(extension)) return "mint";
  return "purple";
}

function evidenceTypeCopy(item) {
  const type = evidenceCardType(item);
  if (type === "image") return "图片材料";
  if (type === "video") return "视频材料";
  return "文本材料";
}

function evidenceVerificationLabel(item) {
  const status = String(
    evidenceField(item, "verification_status", "verificationStatus", "PENDING"),
  ).toUpperCase();
  return {
    PENDING: "待核验",
    VERIFIED: "已核验",
    PLAUSIBLE: "基本可信",
    SUSPICIOUS: "存在疑点",
    REJECTED: "不予采纳",
    NEEDS_HUMAN_REVIEW: "待人工复核",
    PARTIALLY_VERIFIED: "部分核验",
    UNVERIFIED: "待核验",
  }[status] || "待核验";
}

function evidenceConfidence(item) {
  const raw = evidenceField(item, "confidence_score", "confidenceScore", null);
  if (raw === null || raw === undefined || raw === "") return "待评分";
  const numeric = Number(raw);
  if (!Number.isFinite(numeric)) return "待评分";
  const percentage = numeric <= 1 ? Math.round(numeric * 100) : Math.round(numeric);
  return `${percentage}%`;
}

function isMissingEvidenceCatalog(failure) {
  return ["EVIDENCE_NOT_FOUND", "RESOURCE_NOT_FOUND"].includes(failure?.code);
}

async function loadEvidenceCatalog(actorSnapshot = effectiveActor.value) {
  try {
    evidenceCatalog.value = await evidenceApi.catalog(actorSnapshot, caseId.value);
  } catch (failure) {
    if (isMissingEvidenceCatalog(failure)) {
      evidenceCatalog.value = { case_id: caseId.value, items: [] };
      return;
    }
    throw failure;
  }
}

function uploadedEvidenceId(uploaded) {
  return uploaded?.evidence_id || uploaded?.evidenceId || uploaded?.id || "";
}

function summary(round) {
  try {
    return JSON.parse(round?.summary_json || "{}");
  } catch {
    return { judge: round?.summary_json || "本轮记录正在整理。" };
  }
}

function ledgerRoundText(round) {
  const value = summary(round);
  return sanitizeHearingCopy(
    displayRoomMessageText(value.judge || value.clerk || value.jury || "本轮记录已封存。"),
  );
}

function ledgerItemForMessage(message) {
  const type = messageType(message);
  if (type === "PARTY_EVIDENCE_REFERENCE") {
    return {
      id: message.id || `evidence-${message.sequence_no || ""}`,
      title: `第 ${messageRoundNo(message)} 轮补充证据`,
      status: "已入卷",
      text: sanitizeHearingCopy(displayRoomMessageText(rawMessageText(message))),
      statusCode: "EVIDENCE_SUPPLEMENT",
      roundNo: messageRoundNo(message),
      sequenceNo: message.sequence_no || message.sequenceNo || 0,
      tone: "evidence",
    };
  }
  if (type === "EVIDENCE_DOSSIER_REVISED") {
    const payload = messagePayload(message) || {};
    const previous = payload.supersedes_version ?? payload.previous_version ?? payload.baseline_version;
    const active = payload.active_version ?? payload.dossier_version;
    const versionText = previous && active ? `v${previous} → v${active}` : "版本已更新";
    const reason = payload.revision_reason || payload.reason || "证据书记官已根据补证和双方解释更新证据矩阵。";
    return {
      id: message.id || `matrix-${message.sequence_no || ""}`,
      title: "证据矩阵更新",
      status: versionText,
      text: sanitizeHearingCopy(displayRoomMessageText(reason)),
      statusCode: "EVIDENCE_DOSSIER_REVISED",
      roundNo: messageRoundNo(message),
      sequenceNo: message.sequence_no || message.sequenceNo || 0,
      tone: "matrix",
    };
  }
  if (type === "JURY_REVIEW_REPORT") {
    return {
      id: message.id || `jury-${message.sequence_no || ""}`,
      title: `第 ${messageRoundNo(message)} 轮评审团复核报告`,
      status: "已交法官",
      text: formatJuryReviewReport(messagePayload(message), rawMessageText(message)),
      statusCode: "JURY_REVIEW_REPORT",
      roundNo: messageRoundNo(message),
      sequenceNo: message.sequence_no || message.sequenceNo || 0,
      tone: "jury",
    };
  }
  return null;
}

function caseEventType(event) {
  return event?.event_type || event?.eventType || event?.event || "";
}

function caseEventSequence(event) {
  return event?.sequence_no || event?.sequenceNo || event?.id || 0;
}

function caseEventPayload(event) {
  const raw =
    event?.payload_json ||
    event?.payloadJson ||
    event?.event_json ||
    event?.eventJson ||
    event?.payload ||
    {};
  if (typeof raw === "string") {
    try {
      return JSON.parse(raw);
    } catch {
      return {};
    }
  }
  return raw && typeof raw === "object" ? raw : {};
}

function participantRoleLabel(roleValue) {
  return {
    USER: "用户",
    MERCHANT: "商家",
    PLATFORM_REVIEWER: "审核员",
    SYSTEM: "系统",
  }[String(roleValue || "").toUpperCase()] || "当事人";
}

function stopReasonLabel(reasonValue) {
  const value = String(reasonValue || "").toUpperCase();
  if (value === "BOTH_PARTIES_SUBMITTED") return "双方已提交";
  if (value === "ROUND_DEADLINE_EXPIRED") return "超时自动封存";
  if (value === "MAX_ROUNDS") return "三轮已封存";
  if (value === "CONTINUE") return "继续下一轮";
  return "已封存";
}

function ledgerItemForCaseEvent(event) {
  const type = caseEventType(event);
  const payload = caseEventPayload(event);
  const sequenceNo = caseEventSequence(event);
  const roundNo = payload.round_no || payload.current_round_no || 99;
  if (type === "HEARING_ROUND_PARTY_SUBMITTED") {
    const roleLabel = participantRoleLabel(payload.participant_role);
    return {
      id: `event-${sequenceNo}`,
      title: `第 ${payload.round_no || "本"} 轮陈述提交`,
      status: `${roleLabel}已提交`,
      text: `${roleLabel}提交了本轮庭审陈述，系统已将该立场写入审理档案。`,
      statusCode: type,
      roundNo,
      sequenceNo,
      tone: "round",
    };
  }
  if (type === "HEARING_ROUND_COMPLETED") {
    return {
      id: `event-${sequenceNo}`,
      title: `第 ${payload.round_no || "本"} 轮封存`,
      status: stopReasonLabel(payload.stop_reason),
      text: "本轮双方材料已封存，后续法官、证据书记官和评审团会基于封存材料继续处理。",
      statusCode: type,
      roundNo,
      sequenceNo,
      tone: "round",
    };
  }
  if (type === "FINAL_DRAFT_REQUIRED") {
    return {
      id: `event-${sequenceNo}`,
      title: "法官进入裁决草案生成",
      status: "草案生成中",
      text: "三轮庭审已封存，法官将结合案情卷宗、最新证据矩阵和复核意见生成裁决草案。",
      statusCode: type,
      roundNo,
      sequenceNo,
      tone: "judge",
    };
  }
  if (type === "JURY_REVIEW_REPORT_READY") {
    return {
      id: `event-${sequenceNo}`,
      title: "评审团复核完成",
      status: "已交法官",
      text: "评审团复核报告已通过 A2A 通信交给法官，用于修订或确认裁决草案。",
      statusCode: type,
      roundNo,
      sequenceNo,
      tone: "jury",
    };
  }
  if (type === "HEARING_PHASE_CHANGED") {
    return {
      id: `event-${sequenceNo}`,
      title: "裁决草案状态更新",
      status: sanitizeHearingCopy(displayRoomMessageText(payload.phase_label || "草案已生成")),
      text: sanitizeHearingCopy(
        displayRoomMessageText(payload.next_step_hint || "裁决草案已生成，可进入结果页查看。"),
      ),
      statusCode: type,
      roundNo,
      sequenceNo,
      tone: "judge",
    };
  }
  if (type === "EXECUTION_ASSISTANT_HANDOFF") {
    return {
      id: `event-${sequenceNo}`,
      title: "执行专员助手",
      status: "已移交",
      text: "裁决已确认，方案已移交给执行专员助手处理；当前不触发真实下游业务工具。",
      statusCode: type,
      roundNo,
      sequenceNo,
      tone: "matrix",
    };
  }
  return null;
}

function roundStatusLabel(status) {
  return roundStatusLabels[status] || status || "待开始";
}

async function load() {
  try {
    const actorSnapshot = effectiveActor.value;
    if (hearing.value === null) {
      loadingState.hearing = true;
      hearing.value = await hearingApi.hearing(actorSnapshot, caseId.value);
      loadingState.hearing = false;
    }
    if (evidenceCatalog.value === null) {
      loadingState.evidence = true;
      await loadEvidenceCatalog(actorSnapshot);
      loadingState.evidence = false;
    }
    if (props.initialMessages === null) {
      loadingState.messages = true;
      messages.value = await roomApi.messages(
        actorSnapshot,
        caseId.value,
        "HEARING",
      );
      loadingState.messages = false;
    }
    if (props.initialEvents === null) {
      loadingState.events = true;
      caseEvents.value = await roomApi.events(actorSnapshot, caseId.value, 0);
      loadingState.events = false;
    }
  } catch (failure) {
    loadingState.hearing = false;
    loadingState.evidence = false;
    loadingState.messages = false;
    loadingState.events = false;
    error.value = failure.message;
    agentState.value = "ERROR";
  }
}

async function refreshHearing() {
  const actorSnapshot = effectiveActor.value;
  const [nextHearing, nextMessages, nextEvidenceCatalog, nextEvents] = await Promise.all([
    hearingApi.hearing(actorSnapshot, caseId.value),
    roomApi.messages(actorSnapshot, caseId.value, "HEARING"),
    evidenceApi.catalog(actorSnapshot, caseId.value).catch((failure) => {
      if (isMissingEvidenceCatalog(failure)) {
        return { case_id: caseId.value, items: [] };
      }
      throw failure;
    }),
    roomApi.events(actorSnapshot, caseId.value, 0),
  ]);
  hearing.value = nextHearing;
  messages.value = nextMessages;
  evidenceCatalog.value = nextEvidenceCatalog;
  caseEvents.value = nextEvents;
}

async function postMessage(command) {
  error.value = "";
  agentState.value = "THINKING";
  try {
    const saved = props.messageAction
      ? await props.messageAction(command)
      : await roomApi.postMessage(
          effectiveActor.value,
          caseId.value,
          "HEARING",
          command,
        );
    messages.value.push(saved);
    agentState.value = "SPEAKING";
    return saved;
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
    return null;
  }
}

async function submitStatementInput() {
  const text = statementText.value.trim();
  if (!text || statementInputDisabled.value) return;
  const saved = await postMessage({
    message_type: "PARTY_TEXT",
    text,
    attachment_refs: [],
  });
  if (saved) statementText.value = "";
}

async function proposeSettlement() {
  const text = proposalText.value.trim();
  if (!text) return;
  proposing.value = true;
  error.value = "";
  agentState.value = "THINKING";
  const command = {
    proposal_text: text,
    proposal_json: JSON.stringify({
      source: "PARTY_CONSENSUS",
      text,
    }),
  };
  try {
    const saved = props.proposeSettlementAction
      ? await props.proposeSettlementAction(command)
      : await hearingApi.proposeSettlement(
          effectiveActor.value,
          caseId.value,
          command,
        );
    hearing.value = {
      ...(hearing.value || {}),
      rounds: hearing.value?.rounds || [],
      settlements: [
        saved,
        ...(hearing.value?.settlements || []).filter(
          (item) => item.version !== saved.version,
        ),
      ],
    };
    settlementOpen.value = false;
    proposalText.value = "";
    agentState.value = "SPEAKING";
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
  } finally {
    proposing.value = false;
  }
}

async function supplementEvidence(event) {
  const file = event.target.files?.[0];
  if (!file) return;
  supplementing.value = true;
  error.value = "";
  try {
    const command = {
      file,
      evidenceType: file.type.startsWith("video/")
        ? "VIDEO"
        : "OTHER",
      sourceType: evidenceSourceType.value,
      visibility: "PARTIES",
    };
    const uploaded = props.supplementAction
      ? await props.supplementAction(command)
      : await evidenceApi.upload(effectiveActor.value, caseId.value, command);
    const attachmentId = uploadedEvidenceId(uploaded);
    if (attachmentId) {
      const batchCommand = {
        evidence_ids: [attachmentId],
        batch_note: `庭审补充证据：${file.name}`,
      };
      const submittedBatch = props.submitEvidenceBatchAction
        ? await props.submitEvidenceBatchAction(batchCommand)
        : await evidenceApi.submitBatch(
            effectiveActor.value,
            caseId.value,
            batchCommand,
          );
      const roomMessage =
        submittedBatch?.room_message || submittedBatch?.roomMessage || null;
      if (roomMessage) {
        messages.value.push(roomMessage);
        agentState.value = "SPEAKING";
      } else {
        await postMessage({
          message_type: "PARTY_EVIDENCE_REFERENCE",
          text: `已补充证据：${file.name}`,
          attachment_refs: [attachmentId],
        });
      }
    } else {
      await postMessage({
        message_type: "PARTY_TEXT",
        text: `已补充证据：${file.name}`,
        attachment_refs: [],
      });
    }
    await loadEvidenceCatalog(effectiveActor.value);
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
  } finally {
    supplementing.value = false;
    event.target.value = "";
  }
}

async function submitCurrentRound() {
  if (!isCaseParty.value) return;
  submittingRound.value = true;
  error.value = "";
  agentState.value = "THINKING";
  const command = {
    dossier_version: activeRound.value?.dossier_version || activeRound.value?.dossierVersion || 1,
    statement_json: JSON.stringify({
      submitted_by_role: role.value,
      submitted_message_ids: messages.value
        .filter((message) => message.sender_role === role.value)
        .map((message) => message.id)
        .filter(Boolean),
      active_round_no: currentRound.value,
    }),
  };
  try {
    const saved = props.submitRoundAction
      ? await props.submitRoundAction(command)
      : await hearingApi.submitRound(effectiveActor.value, caseId.value, command);
    if (!props.submitRoundAction) {
      await refreshHearing();
      agentState.value = saved.status === "COMPLETED" ? "THINKING" : "SPEAKING";
      return;
    }
    const existing = rounds.value.findIndex(
      (round) => round.round_no === saved.round_no,
    );
    const nextRounds =
      existing >= 0
        ? rounds.value.map((round, index) =>
            index === existing ? saved : round,
          )
        : [...rounds.value, saved];
    hearing.value = {
      ...(hearing.value || {}),
      rounds: nextRounds,
      settlements: hearing.value?.settlements || [],
    };
    agentState.value = saved.status === "COMPLETED" ? "THINKING" : "SPEAKING";
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
  } finally {
    submittingRound.value = false;
  }
}

function startEventStream() {
  const streamer = props.eventStreamer || streamRoomEvents;
  void streamer({
    actor: effectiveActor.value,
    caseId: caseId.value,
    roomType: "HEARING",
    state: eventState,
    signal: eventAbortController.signal,
    snapshotLoader: refreshHearing,
    applyEvent: async (event) => {
      const eventType = roomEventType(event);
      if (reviewGateEvents.has(eventType)) {
        reviewGateOpen.value = true;
        agentState.value = "HANDOFF";
        await refreshHearing();
      }
      if (eventType === "CASE_CLOSED") {
        await router.push(`/disputes/${caseId.value}/outcome`);
      }
    },
  });
}

function roomEventType(event) {
  return (
    event?.event ||
    event?.eventType ||
    event?.event_type ||
    event?.data?.event_type ||
    event?.data?.eventType ||
    ""
  );
}

async function confirmSettlement(version) {
  confirmingVersion.value = version;
  error.value = "";
  agentState.value = "THINKING";
  try {
    const result = props.confirmSettlementAction
      ? await props.confirmSettlementAction(version)
      : await hearingApi.confirmSettlement(effectiveActor.value, caseId.value, version);
    const index = settlements.value.findIndex(
      (settlement) => settlement.version === version,
    );
    if (index >= 0) {
      hearing.value = {
        ...hearing.value,
        settlements: hearing.value.settlements.map((settlement, itemIndex) =>
          itemIndex === index ? result : settlement,
        ),
      };
    }
    agentState.value =
      result.status === "CONFIRMED" ? "HANDOFF" : "SPEAKING";
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
  } finally {
    confirmingVersion.value = null;
  }
}

async function completeHearing() {
  if (!serverCanCompleteHearing.value) return;
  error.value = "";
  agentState.value = "THINKING";
  try {
    const status = props.completeHearingAction
      ? await props.completeHearingAction()
      : await hearingApi.complete(effectiveActor.value, caseId.value);
    hearing.value = {
      ...(hearing.value || {}),
      status,
    };
    if (statusAllowsCompletion(status)) {
      await router.push(`/disputes/${caseId.value}/outcome`);
      return;
    }
    agentState.value = "HANDOFF";
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
  }
}

onMounted(async () => {
  await load();
  if (
    props.eventStreamer ||
    props.initialHearing === null ||
    props.initialMessages === null
  ) {
    startEventStream();
  }
});
onBeforeUnmount(() => {
  eventAbortController.abort();
  clearInterval(stageClockTimer);
});
</script>

<template>
  <RoomShell
    eyebrow="AI NATIVE COURTROOM"
    title="AI 小法庭 · 履约争端庭审"
    :case-id="caseId"
    :connection-state="connectionState"
  >
    <template #clock>
      <div data-hearing-countdown>
        <PhaseCountdown
          label="庭审时效"
          :deadline-at="effectiveDeadline"
          :server-now="effectiveServerNow"
        />
      </div>
    </template>

    <template #agent>
      <section
        class="court-agent-strip"
        data-court-agent-strip
        aria-label="庭审数字人席位"
      >
        <DigitalHuman
          data-court-agent-card="judge"
          :state="agentState"
          name="衡衡"
          role="AI 法官"
          message="主持三轮陈述，第三轮后生成非最终裁决草案。"
        />
        <DigitalHuman
          data-court-agent-card="jury-a"
          state="THINKING"
          name="评审 A"
          role="AI 评审团"
          message="关注事实完整性、证据冲突和风险信号。"
        />
        <DigitalHuman
          data-court-agent-card="jury-b"
          state="LISTENING"
          name="评审 B"
          role="AI 评审团"
          message="关注裁决草案是否符合规则与双方可接受度。"
        />
      </section>
    </template>

    <main class="hearing-courtroom-page" data-hearing-courtroom-page>
      <aside
        class="party-evidence-rail party-evidence-rail--left"
        :class="`party-evidence-rail--${leftEvidenceRail.key}`"
        :data-party-evidence-rail="leftEvidenceRail.key"
        data-rail-position="left"
      >
        <header class="party-evidence-rail__header">
          <div>
            <span>{{ leftEvidenceRail.eyebrow }}</span>
            <h2>{{ leftEvidenceRail.title }}</h2>
            <p>{{ leftEvidenceRail.description }}</p>
          </div>
          <b>{{ leftEvidenceRail.badge }}</b>
        </header>

        <div class="evidence-pocket" :aria-label="leftEvidenceRail.ariaLabel">
          <article
            v-for="item in leftEvidenceItems"
            :key="evidenceId(item)"
            class="evidence-file-card"
            :class="`evidence-file-card--${evidenceCardTone(item)}`"
          >
            <i class="evidence-file-card__icon" :data-type="evidenceCardType(item)">
              {{ evidenceTypeLabel(evidenceCardType(item)) }}
            </i>
            <div>
              <strong>{{ evidenceFilename(item) }}</strong>
              <small>{{ evidenceTypeCopy(item) }} · {{ evidenceSubmissionStatusLabel(item) }}</small>
              <footer>
                <span>{{ evidenceConfidence(item) }}</span>
                <em>{{ evidenceVerificationLabel(item) }}</em>
              </footer>
            </div>
          </article>
          <div
            v-if="loadingState.evidence"
            class="evidence-pocket__empty evidence-pocket__empty--loading"
            data-evidence-loading
          >
            <strong>证据材料加载中</strong>
            <small>正在读取双方已提交的庭审证据，请稍候。</small>
          </div>
          <div
            v-else-if="!leftEvidenceItems.length"
            class="evidence-pocket__empty"
            data-evidence-empty="left"
          >
            <strong>暂无已提交证据</strong>
            <small>当前一侧尚未形成可展示的正式证据材料。</small>
          </div>
        </div>

        <label
          v-if="isCaseParty && leftEvidenceRail.role === role"
          class="evidence-supplement-button"
          :class="{ 'evidence-supplement-button--merchant': leftEvidenceRail.key === 'merchant' }"
          :data-supplement-evidence="leftEvidenceRail.key"
        >
          {{ supplementing ? "正在补入卷宗…" : leftEvidenceRail.supplementLabel }}
          <input type="file" :disabled="supplementing" @change="supplementEvidence" />
        </label>

        <button class="evidence-expand-button" type="button">
          展开证据预览
          <span aria-hidden="true">↗</span>
        </button>
      </aside>

      <section class="courtroom-center courtroom-center--compact-stage">
        <section
          class="hearing-stage-dock hearing-stage-dock--fixed-dashboard hearing-stage-dock--short"
          :class="`hearing-stage-dock--${stageDockMode}`"
          data-hearing-stage-dock
        >
          <header class="hearing-stage-dock__header">
            <div class="hearing-stage-dock__copy hearing-stage-dock__copy--stacked hearing-stage-dock__copy--breathing">
              <span>当前庭审状态</span>
              <h2>{{ stageDockTitle }}</h2>
            </div>
            <div class="hearing-stage-dock__clock hearing-stage-dock__clock--centered" data-hearing-stage-clock>
              <span>{{ stageDockClock.label }}</span>
              <strong>{{ stageDockClock.value }}</strong>
            </div>
          </header>

          <div
            class="round-progress-board round-progress-board--timeline"
            data-hearing-progress-track
          >
            <article
              v-for="item in roundProgressItems"
              :key="item.number"
              class="round-progress-board__item"
              :class="`round-progress-board__item--${item.tone}`"
              data-round-progress-item
              :data-round-progress-state="item.tone"
              :data-round-connector-state="item.connectorTone"
            >
              <b :aria-label="`${item.label}：${item.status}`">
                <span
                  v-if="item.tone === 'active'"
                  class="round-progress-board__active-spinner"
                  data-round-active-spinner
                  aria-hidden="true"
                ></span>
              </b>
              <div>
                <span class="round-progress-board__label">{{ item.label }}</span>
                <em class="round-progress-board__status">{{ item.status }}</em>
              </div>
            </article>
          </div>

        </section>

        <section class="court-transcript" data-court-transcript>
          <div class="court-transcript__messages">
            <article
              v-for="item in courtTranscriptItems"
              :key="item.id"
              class="court-message"
              :class="[
                `court-message--${item.type}`,
                item.type === 'judge' ? 'court-message--judge-bench-card' : '',
                item.type === 'jury' ? 'court-message--jury-review-card' : '',
                ['intake', 'clerk'].includes(item.type) ? 'court-message--court-staff-card' : '',
                ['judge', 'jury', 'intake', 'clerk'].includes(item.type) ? 'court-message--tall-narrow-card' : '',
                ['judge', 'jury', 'intake', 'clerk'].includes(item.type) ? 'court-message--extended-length-card' : '',
                ['judge', 'jury', 'intake', 'clerk'].includes(item.type) ? 'court-message--authority-card' : '',
                ['user', 'merchant'].includes(item.type) ? 'court-message--party-statement-card' : '',
                ['user', 'merchant'].includes(item.type) ? 'court-message--soft-party-card' : '',
                ['judge', 'jury', 'intake', 'clerk', 'user', 'merchant'].includes(item.type) ? 'court-message--flexible-height-card' : '',
              ]"
              :data-court-message="item.type"
            >
              <header>
                <strong>
                  <small v-if="transcriptBadgeForItem(item)">{{ transcriptBadgeForItem(item) }}</small>
                  {{ item.speaker }}
                  <span v-if="item.type === 'jury'" class="court-message__jury-tags" aria-label="评审团辅助指标">
                    <span>风险等级</span>
                    <em>{{ item.riskLevel }}</em>
                    <span>可信分</span>
                    <em>{{ item.confidenceScore }}</em>
                  </span>
                </strong>
                <span>{{ item.time }}</span>
              </header>
              <p>{{ item.text }}</p>
            </article>

            <div
              v-if="loadingState.messages"
              class="court-transcript__empty court-transcript__empty--loading"
              data-court-transcript-loading
            >
              <strong>庭审记录加载中</strong>
              <small>正在读取开庭消息和双方陈述，请稍候。</small>
            </div>
            <div
              v-else-if="!courtTranscriptItems.length"
              class="court-transcript__empty"
              data-court-transcript-empty
            >
              <strong>等待开庭消息</strong>
              <small>当前庭审记录尚未写入可追溯消息；系统不会用示例陈述代替真实案卷。</small>
            </div>
          </div>
        </section>

        <section v-if="isCaseParty" class="round-input-bar round-input-bar--fixed-dock" data-round-input-bar>
          <div class="round-input-bar__body">
            <header class="round-input-bar__header" data-round-input-header>
              <div>
                <h3>本轮陈述输入台</h3>
              </div>
              <div class="round-input-bar__party-statuses" data-round-input-party-statuses>
                <article
                  v-for="party in partySubmissionStatuses"
                  :key="party.role"
                  class="round-input-party-status"
                  :class="`round-input-party-status--${party.tone}`"
                  :data-round-input-party-status="party.role"
                >
                  <span>{{ party.label }}</span>
                  <strong>{{ party.status }}</strong>
                </article>
              </div>
            </header>
            <div
              v-if="reviewHandoffVisible"
              class="round-input-bar__final-status"
              data-round-input-final-status
            >
              <span>🔒</span>
              <div>
                <strong>庭审已封存，等待裁决草案</strong>
                <small>当前输入区已锁定，可在右侧查看裁决草案入口状态。</small>
              </div>
            </div>
            <div
              v-else-if="activeRoundClosed"
              class="round-input-bar__sealed-status"
              data-round-input-sealed
            >
              <span>🔒</span>
              <div>
                <strong>本轮已封存</strong>
                <small>本轮陈述已锁定，等待法官处理并开启下一阶段。</small>
              </div>
            </div>
            <form
              v-else
              class="round-input-bar__composer"
              data-round-input-composer
              data-send-message
              @submit.prevent="submitStatementInput"
            >
              <textarea
                v-model="statementText"
                :disabled="statementInputDisabled"
                placeholder="输入本轮陈述、证据解释或对拟处理方向的确认或说明异议…"
                rows="3"
                aria-label="本轮陈述"
              ></textarea>
              <div class="round-input-bar__submit-column">
                <button
                  v-if="canSubmitStatement"
                  type="button"
                  class="round-input-bar__round-submit"
                  data-send-hearing-statement
                  :disabled="statementInputDisabled || submittingRound || !statementText.trim()"
                  @click="submitStatementInput()"
                >
                  提交陈述
                </button>
              </div>
            </form>
          </div>
        </section>

        <p v-if="error" class="hearing-error" role="alert">{{ error }}</p>
      </section>

      <div
        class="evidence-rail-column evidence-rail-column--right"
        :data-party-evidence-rail="rightEvidenceRail.key"
        data-rail-position="right"
      >
        <aside
          class="party-evidence-rail party-evidence-rail--right"
          :class="`party-evidence-rail--${rightEvidenceRail.key}`"
        >
          <header class="party-evidence-rail__header">
            <div>
              <span>{{ rightEvidenceRail.eyebrow }}</span>
              <h2>{{ rightEvidenceRail.title }}</h2>
              <p>{{ rightEvidenceRail.description }}</p>
            </div>
            <b>{{ rightEvidenceRail.badge }}</b>
          </header>

          <div class="evidence-pocket" :aria-label="rightEvidenceRail.ariaLabel">
            <article
              v-for="item in rightEvidenceItems"
              :key="evidenceId(item)"
              class="evidence-file-card"
              :class="`evidence-file-card--${evidenceCardTone(item)}`"
            >
              <i class="evidence-file-card__icon" :data-type="evidenceCardType(item)">
                {{ evidenceTypeLabel(evidenceCardType(item)) }}
              </i>
              <div>
                <strong>{{ evidenceFilename(item) }}</strong>
                <small>{{ evidenceTypeCopy(item) }} · {{ evidenceSubmissionStatusLabel(item) }}</small>
                <footer>
                  <span>{{ evidenceConfidence(item) }}</span>
                  <em>{{ evidenceVerificationLabel(item) }}</em>
                </footer>
              </div>
            </article>
            <div
              v-if="loadingState.evidence"
              class="evidence-pocket__empty evidence-pocket__empty--loading"
              data-evidence-loading
            >
              <strong>证据材料加载中</strong>
              <small>正在读取双方已提交的庭审证据，请稍候。</small>
            </div>
            <div
              v-else-if="!rightEvidenceItems.length"
              class="evidence-pocket__empty"
              data-evidence-empty="right"
            >
              <strong>暂无已提交证据</strong>
              <small>当前一侧尚未形成可展示的正式证据材料。</small>
            </div>
          </div>

          <label
            v-if="isCaseParty && rightEvidenceRail.role === role"
            class="evidence-supplement-button"
            :class="{ 'evidence-supplement-button--merchant': rightEvidenceRail.key === 'merchant' }"
            :data-supplement-evidence="rightEvidenceRail.key"
          >
            {{ supplementing ? "正在补入卷宗…" : rightEvidenceRail.supplementLabel }}
            <input type="file" :disabled="supplementing" @change="supplementEvidence" />
          </label>

          <button class="evidence-expand-button" type="button">
            展开证据预览
            <span aria-hidden="true">↗</span>
          </button>
        </aside>

        <div class="hearing-side-actions">
          <button
            type="button"
            class="evidence-ledger-button"
            data-open-court-ledger
            @click="ledgerOpen = true"
          >
            查看庭审卷轴
          </button>
          <small class="hearing-side-actions__hint" data-complete-hearing-hint>
            {{ completeHearingHint }}
          </small>
          <button
            type="button"
            class="evidence-complete-button"
            data-complete-hearing
            :disabled="!serverCanCompleteHearing"
            @click="completeHearing"
          >
            {{ completeHearingButtonLabel }}
          </button>
        </div>
      </div>
    </main>

    <div
      v-if="ledgerOpen"
      class="court-ledger-backdrop"
      data-court-ledger-drawer
      role="dialog"
      aria-modal="true"
      aria-label="庭审卷轴"
      @click.self="ledgerOpen = false"
    >
      <aside class="hearing-ledger">
        <header>
          <div>
            <span>TRACEABLE ROUND LEDGER</span>
            <h2>庭审卷轴</h2>
            <p>这里保存每一轮封存后的可追溯记录，用于后续复核、申诉和审核确认。</p>
          </div>
          <button
            type="button"
            aria-label="关闭庭审卷轴"
            @click="ledgerOpen = false"
          >
            ×
          </button>
        </header>
        <ol>
          <li
            v-for="item in courtLedgerItems"
            :key="item.id"
            :class="`hearing-ledger__item--${item.tone}`"
          >
            <div>
              <strong>{{ item.title }}</strong>
              <span :data-round-status="item.statusCode">{{ item.status }}</span>
            </div>
            <p>{{ item.text }}</p>
          </li>
        </ol>
        <div v-if="!courtLedgerItems.length" class="hearing-ledger__empty" data-round-ledger-empty>
          <span aria-hidden="true">📜</span>
          <strong>第一轮庭审记录生成后，书记官会把卷轴挂在这里。</strong>
          <small>目前双方仍可先完成事实陈述、证据解释或方案确认。</small>
        </div>
      </aside>
    </div>
  </RoomShell>
</template>

<style scoped>
.court-agent-strip {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  align-items: stretch;
  min-width: 0;
}
.court-agent-strip :deep(.digital-human) {
  grid-template-columns: 96px minmax(0, 1fr);
  gap: 12px;
  align-items: center;
  min-width: 0;
  min-height: 132px;
  padding: 14px;
  border-radius: 24px;
  box-shadow: 0 16px 38px #536c8b12;
}
.court-agent-strip :deep(.digital-human[data-court-agent-card="judge"]) {
  background: linear-gradient(145deg, #fffaf0, #f6f8ff 56%, #eef8ff);
  border-color: #ecd9ad;
}
.court-agent-strip :deep(.digital-human[data-court-agent-card="jury-a"]) {
  background: linear-gradient(145deg, #ffffff, #f5f0ff 58%, #f8fbff);
}
.court-agent-strip :deep(.digital-human[data-court-agent-card="jury-b"]) {
  background: linear-gradient(145deg, #ffffff, #effff9 52%, #f8f2ff);
}
.court-agent-strip :deep(.digital-human__portrait) {
  min-height: 96px;
}
.court-agent-strip :deep(.digital-human__portrait svg) {
  width: 96px;
  height: 96px;
}
.court-agent-strip :deep(.digital-human__state-dot) {
  right: 3px;
  bottom: 10px;
  width: 14px;
  height: 14px;
  border-width: 3px;
}
.court-agent-strip :deep(.digital-human__identity) {
  align-items: center;
}
.court-agent-strip :deep(.digital-human__identity strong) {
  font-size: 16px;
}
.court-agent-strip :deep(.digital-human__identity span) {
  margin-top: 2px;
  font-size: 11px;
}
.court-agent-strip :deep(.digital-human__identity small) {
  padding: 4px 7px;
  font-size: 10px;
  white-space: nowrap;
}
.court-agent-strip :deep(.digital-human__copy p) {
  display: -webkit-box;
  overflow: hidden;
  margin: 8px 0 0;
  color: #69758a;
  font-size: 11px;
  line-height: 1.42;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.court-agent-strip :deep(.digital-human__boundary) {
  display: none;
}
:deep(.room-shell) {
  gap: 8px;
  min-height: auto;
}
:deep(.room-shell__header) {
  align-items: center;
}
:deep(.room-shell__header h1) {
  margin: 3px 0 0;
  font-size: clamp(24px, 2.4vw, 30px);
  line-height: 1.16;
}
:deep(.room-shell__header p) {
  display: none;
}
:deep(.room-shell__boundary) {
  display: none;
}
:global(.app-page:has(.hearing-courtroom-page)) {
  padding-bottom: 42px;
}
.hearing-courtroom-page {
  position: relative;
  display: grid;
  grid-template-columns: 282px minmax(620px, 1fr) 282px;
  gap: 18px;
  height: clamp(720px, calc(100vh - 150px), 820px);
  min-height: 0;
}
.party-evidence-rail,
.courtroom-center {
  min-width: 0;
  background: #ffffffdf;
  border: 1px solid #dfe9f4;
  box-shadow: 0 22px 56px #536c8b12;
  backdrop-filter: blur(18px);
}
.party-evidence-rail {
  position: sticky;
  top: 96px;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  gap: 12px;
  height: 100%;
  padding: 16px;
  border-radius: 28px;
}
.party-evidence-rail--left {
  grid-column: 1;
}
.evidence-rail-column--right {
  grid-column: 3;
  display: grid;
  grid-template-rows: minmax(0, 1fr) auto;
  gap: 10px;
  min-width: 0;
  height: 100%;
}
.evidence-rail-column--right .party-evidence-rail {
  position: static;
  height: 100%;
  min-height: 0;
}
.party-evidence-rail--right .evidence-pocket {
  gap: 9px;
}
.party-evidence-rail__header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
}
.party-evidence-rail__header span,
.round-input-bar span,
.hearing-ledger header span,
.settlement-card > span,
.settlement-dialog form header span {
  color: #7486a3;
  font-size: 10px;
  font-weight: 900;
  letter-spacing: .16em;
}
.party-evidence-rail__header h2,
.hearing-ledger h2 {
  margin: 5px 0 4px;
  color: #30415c;
}
.party-evidence-rail__header p {
  margin: 0;
  color: #8996a8;
  font-size: 11px;
  line-height: 1.45;
}
.party-evidence-rail__header b {
  flex: 0 0 auto;
  padding: 7px 13px;
  color: #53619a;
  background: #edf7ff;
  border: 1px solid #cfe8f7;
  border-radius: 999px;
  font-size: 11px;
}
.party-evidence-rail--merchant .party-evidence-rail__header b {
  background: #fff3e9;
  border-color: #f4d7c8;
}
.evidence-pocket {
  display: grid;
  align-content: start;
  gap: 12px;
  min-height: 0;
  overflow: auto;
  padding: 2px 3px 10px 0;
}
.evidence-pocket::-webkit-scrollbar,
.court-transcript__messages::-webkit-scrollbar {
  width: 8px;
}
.evidence-pocket::-webkit-scrollbar-thumb,
.court-transcript__messages::-webkit-scrollbar-thumb {
  background: #cbd8e8;
  border-radius: 999px;
}
.evidence-pocket__empty {
  display: grid;
  gap: 7px;
  align-content: center;
  min-height: 132px;
  padding: 18px;
  color: #71809a;
  text-align: center;
  background:
    radial-gradient(circle at 50% 0, #ffffff 0 28%, transparent 29%),
    linear-gradient(145deg, #f8fbff, #fffdf8);
  border: 1px dashed #cedbea;
  border-radius: 22px;
}
.evidence-pocket__empty strong {
  color: #40516d;
  font-size: 13px;
}
.evidence-pocket__empty small {
  color: #8b98aa;
  font-size: 11px;
  line-height: 1.5;
}
.evidence-file-card {
  position: relative;
  display: grid;
  grid-template-columns: 46px minmax(0, 1fr);
  gap: 12px;
  align-items: center;
  min-height: 72px;
  padding: 10px 10px 10px 14px;
  overflow: hidden;
  background: #fff;
  border: 1px solid #ddeaf4;
  border-radius: 20px;
  box-shadow: 0 8px 20px #506c9410;
}
.evidence-file-card::before {
  position: absolute;
  top: 18px;
  bottom: 18px;
  left: 0;
  width: 4px;
  content: "";
  background: #17a8e6;
  border-radius: 0 999px 999px 0;
}
.evidence-file-card--purple::before { background: #afa1ff; }
.evidence-file-card--gold::before { background: #f6bf62; }
.evidence-file-card--mint::before { background: #78d9bd; }
.evidence-file-card__icon {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  color: transparent;
  background: #eaf8ff;
  border-radius: 14px;
  font-style: normal;
}
.evidence-file-card__icon::before {
  color: #17a8e6;
  font-size: 13px;
  font-weight: 900;
  content: attr(data-type);
}
.evidence-file-card__icon[data-type="text"] { background: #f5f2ff; }
.evidence-file-card__icon[data-type="text"]::before { color: #7f70dd; content: "TXT"; }
.evidence-file-card__icon[data-type="image"]::before { content: "IMG"; }
.evidence-file-card__icon[data-type="video"] {
  background: #fff4e5;
}
.evidence-file-card__icon[data-type="video"]::before {
  color: #bd7b15;
  content: "VID";
}
.evidence-file-card strong {
  display: block;
  overflow: hidden;
  color: #33435c;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.evidence-file-card small {
  display: block;
  margin-top: 5px;
  color: #8492a7;
  font-size: 10px;
  font-weight: 700;
}
.evidence-file-card footer {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 8px;
}
.evidence-file-card footer span,
.evidence-file-card footer em {
  padding: 3px 8px;
  color: #2fa870;
  background: #e9fff8;
  border-radius: 999px;
  font-size: 10px;
  font-style: normal;
  font-weight: 900;
}
.evidence-file-card footer em {
  color: #8190a3;
  background: #f4fbff;
}
.party-evidence-rail--right .evidence-file-card {
  grid-template-columns: 40px minmax(0, 1fr);
  gap: 10px;
  min-height: 62px;
  padding: 8px 9px 8px 12px;
}
.party-evidence-rail--right .evidence-file-card__icon {
  width: 38px;
  height: 38px;
  border-radius: 13px;
}
.party-evidence-rail--right .evidence-file-card small {
  margin-top: 3px;
}
.party-evidence-rail--right .evidence-file-card footer {
  margin-top: 5px;
}
.evidence-supplement-button,
.evidence-expand-button,
.evidence-ledger-button,
.evidence-complete-button {
  position: relative;
  display: flex;
  justify-content: center;
  gap: 12px;
  align-items: center;
  height: 38px;
  color: #53619a;
  background: #f6fafd;
  border: 1px solid #e0e7f0;
  border-radius: 999px;
  font-weight: 900;
  cursor: pointer;
}
.evidence-supplement-button {
  color: #0f8abf;
  background: #eaf8ff;
  border-color: #cae9f8;
}
.evidence-supplement-button--merchant {
  color: #9a681c;
  background: #fff5e6;
  border-color: #f1d9ae;
}
.evidence-supplement-button input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
  pointer-events: none;
}
.evidence-ledger-button {
  color: #58648a;
  background: #f3f0ff;
  border-color: #dfd7fb;
}
.hearing-side-actions {
  display: grid;
  gap: 8px;
}
.evidence-complete-button {
  color: #fff;
  background: linear-gradient(135deg, #62cda6, #4aa7d3);
  border-color: transparent;
  box-shadow: 0 12px 24px #4aa7d322;
}
.evidence-complete-button:disabled {
  color: #7890a4;
  background: #eef4f8;
  box-shadow: none;
  cursor: not-allowed;
}
.hearing-side-actions__hint {
  color: #75879a;
  font-size: 10px;
  line-height: 1.45;
  text-align: center;
}
.courtroom-center {
  grid-column: 2;
  display: grid;
  grid-template-rows: auto minmax(360px, 1fr) auto auto;
  gap: 10px;
  height: 100%;
  overflow: hidden;
  padding: 14px 16px;
  border-radius: 30px;
}
.courtroom-center--compact-stage {
  align-content: stretch;
}
.hearing-stage-dock {
  box-sizing: border-box;
  position: relative;
  display: grid;
  grid-template-rows: 46px 56px;
  gap: 0;
  width: 100%;
  height: 122px;
  min-height: 122px;
  max-height: 122px;
  padding: 7px 16px 7px;
  overflow: hidden;
  background:
    radial-gradient(circle at 7% 0, #fff3cf 0 15%, transparent 16%),
    radial-gradient(circle at 95% 12%, #c9f2ff85 0 14%, transparent 15%),
    linear-gradient(135deg, #fffdf8 0%, #f4fbff 48%, #fff8ef 100%);
  border: 1px solid #d9e8f4;
  border-radius: 26px;
  box-shadow:
    inset 0 1px 0 #fff,
    0 18px 38px #4f6d8d14;
}
.hearing-stage-dock--fixed-dashboard { flex: 0 0 auto; }
.hearing-stage-dock::before {
  position: absolute;
  inset: 0;
  pointer-events: none;
  content: "";
  background:
    linear-gradient(110deg, transparent 0 9%, #ffffff92 10% 13%, transparent 14% 100%),
    linear-gradient(180deg, #ffffffb8 0%, transparent 54%);
  opacity: .9;
}
.hearing-stage-dock::after {
  position: absolute;
  right: 16px;
  bottom: 20px;
  width: 96px;
  height: 96px;
  pointer-events: none;
  content: "";
  background:
    radial-gradient(circle, #ffffff00 0 44%, #8bd7ff2e 45% 47%, transparent 48%),
    radial-gradient(circle, #ffd88922 0 30%, transparent 31%);
}
.hearing-stage-dock--waiting {
  background:
    radial-gradient(circle at 7% 0, #f1e9ff 0 15%, transparent 16%),
    radial-gradient(circle at 94% 10%, #d7f4ff8a 0 14%, transparent 15%),
    linear-gradient(135deg, #ffffff 0%, #f7f3ff 48%, #eefaff 100%);
}
.hearing-stage-dock--sealed,
.hearing-stage-dock--handoff {
  background:
    radial-gradient(circle at 7% 0, #ffe4ad 0 15%, transparent 16%),
    radial-gradient(circle at 94% 10%, #d7f4ff8a 0 14%, transparent 15%),
    linear-gradient(135deg, #fff9ec 0%, #f2fbff 52%, #f6f0ff 100%);
  border-color: #f0d7a7;
}
.hearing-stage-dock__header {
  position: relative;
  z-index: 2;
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
  min-width: 0;
  min-height: 0;
  height: 46px;
}
.hearing-stage-dock__copy {
  position: relative;
  z-index: 1;
  min-width: 0;
  max-width: min(520px, calc(100% - 170px));
}
.hearing-stage-dock__copy--stacked {
  display: grid;
  gap: 1px;
  align-content: start;
  max-width: min(610px, calc(100% - 170px));
}
.hearing-stage-dock__copy--breathing {
  gap: 8px;
}
.hearing-stage-dock__copy span {
  color: #7590ad;
  font-size: 10px;
  line-height: 1;
  font-weight: 900;
  letter-spacing: .14em;
}
.hearing-stage-dock__copy h2 {
  display: block;
  overflow: hidden;
  margin: 0;
  color: #30415c;
  font-size: 18px;
  line-height: 1.12;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.hearing-stage-dock__clock {
  position: relative;
  z-index: 2;
  display: grid;
  gap: 2px;
  justify-items: end;
  padding-top: 0;
  white-space: nowrap;
}
.hearing-stage-dock__clock--centered {
  justify-items: center;
  text-align: center;
}
.hearing-stage-dock__clock span {
  color: #8ca0b8;
  font-size: 10px;
  font-weight: 900;
  letter-spacing: .08em;
}
.hearing-stage-dock__clock strong {
  color: #1598d5;
  font-size: 21px;
  line-height: 1;
  letter-spacing: .02em;
}
.hearing-stage-dock--waiting .hearing-stage-dock__badge {
  color: #7d5cc5;
  background: linear-gradient(135deg, #f1ebff, #ffffffd9);
  border-color: #dfd3ff;
}
.hearing-stage-dock--waiting .hearing-stage-dock__badge::before {
  background: #afa1ff;
  box-shadow: 0 0 0 5px #afa1ff24;
}
.hearing-stage-dock--sealed .hearing-stage-dock__badge,
.hearing-stage-dock--handoff .hearing-stage-dock__badge {
  color: #9a6a18;
  background: linear-gradient(135deg, #fff3d5, #ffffffd9);
  border-color: #efd5a2;
}
.hearing-stage-dock--sealed .hearing-stage-dock__badge::before,
.hearing-stage-dock--handoff .hearing-stage-dock__badge::before {
  background: #78d9bd;
  box-shadow: 0 0 0 5px #78d9bd24;
}
.round-progress-board {
  position: relative;
  z-index: 3;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0;
  align-self: end;
  align-items: end;
  height: 56px;
  padding: 2px 18px 0;
  overflow: visible;
  background: transparent;
  border: 0;
  border-radius: 0;
  box-shadow: none;
}
.round-progress-board__item {
  position: relative;
  display: grid;
  justify-items: center;
  gap: 6px;
  align-items: start;
  min-width: 0;
  height: 50px;
  padding: 0;
  overflow: visible;
  color: #91a0b4;
  background: transparent;
  border: 0;
  border-radius: 0;
  font-size: 11px;
  font-weight: 900;
  text-align: center;
}
.round-progress-board__item::after {
  position: absolute;
  top: 7px;
  left: calc(50% + 11px);
  right: calc(-50% + 11px);
  z-index: 0;
  height: 2px;
  content: "";
  background: #d8e3ec;
  border-radius: 999px;
}
.round-progress-board__item[data-round-connector-state="complete"]::after {
  background: linear-gradient(90deg, #78d9bd, #59c6a4);
}
.round-progress-board__item[data-round-connector-state="none"]::after {
  display: none;
}
.round-progress-board__item b {
  position: relative;
  z-index: 2;
  display: grid;
  width: 16px;
  height: 16px;
  place-items: center;
  color: #fff;
  background: #9fb2c7;
  border: 0;
  border-radius: 50%;
  box-shadow: 0 6px 14px #6c87a114;
}
.round-progress-board__item div {
  display: flex;
  flex-wrap: nowrap;
  gap: 6px;
  align-items: center;
  justify-content: center;
  min-width: 0;
  max-width: 100%;
  white-space: nowrap;
}
.round-progress-board__label {
  min-width: 0;
  overflow: hidden;
  color: #34455e;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.round-progress-board__status {
  flex: 0 0 auto;
  color: #8a98ad;
  font-size: 9px;
  font-style: normal;
  font-weight: 900;
  letter-spacing: .04em;
}
.round-progress-board__item--complete {
  color: #4c6f65;
}
.round-progress-board__item--complete b {
  background: linear-gradient(135deg, #a8ded1, #78cbb6);
  box-shadow: 0 0 0 4px #a8ded124, 0 8px 16px #78cbb61a;
}
.round-progress-board__item--complete .round-progress-board__status {
  color: #5f9f8e;
}
.round-progress-board__item--active {
  color: #34455e;
}
.round-progress-board__item--active b {
  color: #128ec4;
  background: #edf8ff;
  box-shadow: 0 0 0 4px #e7f5ff, 0 8px 16px #4eb9e51a;
}
.round-progress-board__active-spinner {
  position: absolute;
  inset: 3px;
  z-index: 1;
  box-sizing: border-box;
  background: #edf8ff;
  border: 1.5px solid #bfe6f8;
  border-top-color: #4eb9e5;
  border-radius: 50%;
  animation: court-progress-inner-spin .82s linear infinite;
}
.round-progress-board__active-spinner::after {
  position: absolute;
  top: -2px;
  left: 50%;
  width: 3px;
  height: 3px;
  content: "";
  background: #4eb9e5;
  border-radius: 50%;
  box-shadow: 0 0 0 2px #4eb9e526;
  transform: translateX(-50%);
}
.round-progress-board__item--active .round-progress-board__status {
  color: #3f9fc9;
}
.round-progress-board__item--pending {
  color: #9d7580;
}
.round-progress-board__item--pending b {
  background: linear-gradient(135deg, #e8c6cf, #d9aebb);
  box-shadow: 0 0 0 4px #e8c6cf22, 0 8px 16px #d9aebb18;
}
.round-progress-board__item--pending .round-progress-board__status {
  color: #a97987;
}
@keyframes court-progress-inner-spin {
  to {
    transform: rotate(360deg);
  }
}
.hearing-stage-dock__status-grid {
  position: relative;
  z-index: 3;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0;
  height: 36px;
}
.hearing-status-chip {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  align-content: center;
  gap: 2px;
  min-width: 0;
  min-height: 0;
  padding: 2px 12px 2px 17px;
  background: transparent;
  border: 0;
  border-radius: 0;
  box-shadow: none;
}
.hearing-status-chip::before {
  position: absolute;
  top: 14px;
  left: 4px;
  width: 5px;
  height: 18px;
  content: "";
  background: #91a0b4;
  border-radius: 999px;
  box-shadow: none;
  transform: translateY(-50%);
}
.hearing-status-chip:not(:last-child)::after {
  position: absolute;
  top: 7px;
  right: 0;
  bottom: 7px;
  width: 1px;
  content: "";
  background: linear-gradient(180deg, transparent, #dbe8f2, transparent);
}
.hearing-status-chip span {
  overflow: hidden;
  color: #8b98aa;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 9px;
  font-weight: 900;
  letter-spacing: .05em;
}
.hearing-status-chip strong {
  overflow: hidden;
  color: #34455e;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 10.5px;
}
.hearing-status-chip--pending::before {
  background: #a6b4c7;
}
.hearing-status-chip--submitted::before,
.hearing-status-chip--active::before {
  background: #17a8e6;
}
.hearing-status-chip--submitted strong,
.hearing-status-chip--active strong {
  color: #128ec4;
}
.hearing-status-chip--sealed::before,
.hearing-status-chip--complete::before {
  background: #78d9bd;
}
.hearing-status-chip--sealed strong,
.hearing-status-chip--complete strong {
  color: #5a718a;
}
.hearing-status-chip--waiting::before {
  background: #f6bf62;
}
.hearing-status-chip--waiting strong {
  color: #a56e13;
}
.hearing-status-chip--processing::before {
  background: #afa1ff;
}
.hearing-status-chip--processing strong {
  color: #7460d7;
}
.hearing-status-chip[data-hearing-status-chip="USER"]::before {
  background: #17a8e6;
}
.hearing-status-chip[data-hearing-status-chip="USER"] strong {
  color: #128ec4;
}
.hearing-status-chip[data-hearing-status-chip="MERCHANT"]::before {
  background: #f09a62;
}
.hearing-status-chip[data-hearing-status-chip="MERCHANT"] strong {
  color: #a96128;
}
.hearing-status-chip[data-hearing-status-chip="time"]::before {
  background: #f6bf62;
}
.hearing-status-chip[data-hearing-status-chip="time"] strong {
  color: #a56e13;
}
.hearing-status-chip[data-hearing-status-chip="judge-review"]::before {
  background: #afa1ff;
}
.hearing-status-chip[data-hearing-status-chip="judge-review"] strong {
  color: #7460d7;
}
.court-transcript {
  min-height: 0;
  height: 100%;
  overflow: visible;
  background: transparent;
  border: 0;
  box-shadow: none;
}
.court-transcript__messages {
  display: flex;
  flex-direction: column;
  gap: 20px;
  align-items: stretch;
  height: 100%;
  padding: 2px 4px 10px;
  overflow: auto;
}
.court-transcript__empty {
  display: grid;
  flex: 1 1 auto;
  gap: 8px;
  place-content: center;
  min-height: 260px;
  padding: 28px;
  color: #7b8ca6;
  text-align: center;
  background:
    radial-gradient(circle at 50% 0, #fff7db 0 18%, transparent 19%),
    linear-gradient(145deg, #fbfdff, #fffaf0);
  border: 1px dashed #d7e3ef;
  border-radius: 26px;
}
.court-transcript__empty strong {
  color: #344762;
  font-size: 16px;
}
.court-transcript__empty small {
  max-width: 460px;
  color: #8795a9;
  font-size: 12px;
  line-height: 1.6;
}
.court-message {
  position: relative;
  display: flex;
  flex: 0 0 auto;
  flex-direction: column;
  gap: 6px;
  max-width: 72%;
  padding: 12px 15px;
  overflow: visible;
  border-radius: 20px;
  box-shadow: 0 8px 20px #506c940d;
}
.court-message header {
  position: relative;
  z-index: 1;
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 12px;
  color: #7c899e;
  font-size: 10px;
  font-weight: 900;
}
.court-message header strong {
  display: inline-flex;
  gap: 7px;
  align-items: center;
}
.court-message header small {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  color: #9a6a18;
  background: #fff4d8;
  border: 1px solid #f0d7a7;
  border-radius: 999px;
  font-size: 9px;
  letter-spacing: .08em;
}
.court-message p {
  position: relative;
  z-index: 1;
  margin: 0 0 4px;
  color: #516178;
  font-size: 12px;
  line-height: 1.65;
}
.court-message--judge {
  align-self: center;
  background:
    radial-gradient(circle at 8% 8%, #ffe8a8b8 0 12%, transparent 13%),
    linear-gradient(135deg, #fffdf6 0%, #f4fbff 52%, #fff7e7 100%);
  border: 1px solid #ecd7a8;
  box-shadow:
    inset 0 1px 0 #fff,
    0 14px 30px #92764717;
}
.court-message--judge header strong::before {
  margin-right: 6px;
  color: #b37a1f;
  content: "⚖";
}
.court-message--judge-bench-card {
  padding: 14px 18px 14px;
  border-radius: 22px 22px 26px 26px;
}
.court-message--judge-bench-card::before {
  position: absolute;
  inset: 8px 10px auto;
  height: 2px;
  content: "";
  background: linear-gradient(90deg, transparent, #e4bd6f, #89d5ef, transparent);
  border-radius: 999px;
}
.court-message--judge-bench-card::after {
  position: absolute;
  right: 14px;
  bottom: 12px;
  width: 52px;
  height: 52px;
  pointer-events: none;
  content: "";
  background:
    radial-gradient(circle, transparent 0 44%, #8bd7ff36 45% 47%, transparent 48%),
    radial-gradient(circle, #ffd8892b 0 30%, transparent 31%);
  opacity: .65;
}
.court-message--intake,
.court-message--clerk {
  align-self: center;
  box-shadow:
    inset 0 1px 0 #fff,
    0 10px 24px #58718e12;
}
.court-message--intake {
  color: #294937;
  background:
    radial-gradient(circle at 10% 6%, #dff8e7 0 16%, transparent 17%),
    linear-gradient(135deg, #fbfff8 0%, #eefbf2 52%, #fffaf0 100%);
  border: 1px solid #bfe7ca;
  box-shadow:
    inset 0 1px 0 #ffffff,
    0 12px 26px #3a9c6514;
}
.court-message--clerk {
  color: #ecf2f8;
  background:
    radial-gradient(circle at 10% 6%, #496f9f 0 15%, transparent 16%),
    linear-gradient(135deg, #10284f 0%, #1d3f70 52%, #0d2346 100%);
  border: 1px solid #315d91;
  box-shadow:
    inset 0 1px 0 #ffffff1c,
    0 14px 30px #102b5530;
}
.court-message--intake header strong::before,
.court-message--clerk header strong::before {
  margin-right: 6px;
  color: #2e9c62;
  content: "◆";
}
.court-message--clerk header strong::before {
  color: #d6b470;
  content: "✎";
}
.court-message--intake header {
  color: #5f806f;
}
.court-message--intake p {
  color: #385945;
}
.court-message--clerk header {
  color: #c4d9f2;
}
.court-message--clerk p {
  color: #f0f6ff;
}
.court-message--court-staff-card {
  box-sizing: border-box;
  padding: 13px 17px 14px;
  border-radius: 24px;
}
.court-message--court-staff-card header small {
  color: #2f8055;
  background: #edfff3;
  border-color: #bde8c9;
}
.court-message--clerk header small {
  color: #cce6ff;
  background: #ffffff16;
  border-color: #78a9dc70;
}
.court-message--user {
  align-self: flex-start;
  background: transparent;
  border: 1px solid #cde9f8;
  border-left: 3px solid #8dddf7;
}
.court-message--merchant {
  align-self: flex-end;
  background: transparent;
  border: 1px solid #f3d8bc;
  border-right: 3px solid #efc28c;
}
.court-message--party-statement-card {
  box-sizing: border-box;
  width: min(62%, 520px);
  max-width: min(62%, 520px);
  min-height: 92px;
  padding: 15px 18px 14px;
  border-radius: 24px;
  box-shadow:
    0 8px 18px #506c9408;
}
.court-message--soft-party-card {
  box-shadow:
    inset 0 1px 0 #ffffffd8,
    0 8px 18px #506c9408;
}
.court-message--user.court-message--soft-party-card {
  background: linear-gradient(135deg, #fbfeff 0%, #f3fbff 100%);
}
.court-message--merchant.court-message--soft-party-card {
  background: linear-gradient(135deg, #fffefd 0%, #fff8f0 100%);
}
.court-message--party-statement-card header {
  font-size: 11px;
}
.court-message--party-statement-card p {
  color: #465a74;
  font-size: 13px;
  line-height: 1.72;
}
.court-message--jury {
  align-self: center;
  background:
    radial-gradient(circle at 7% 0, #efe8ffb8 0 12%, transparent 13%),
    linear-gradient(135deg, #fbf8ff 0%, #f1fbf7 48%, #fffdf4 100%);
  border: 1px solid #ded8ff;
  box-shadow:
    inset 0 1px 0 #fff,
    0 12px 28px #6c5db319;
}
.court-message--jury-review-card {
  grid-template-columns: minmax(0, 1fr);
  gap: 8px;
  padding: 13px 16px 14px;
  border-radius: 24px;
}
.court-message--tall-narrow-card {
  box-sizing: border-box;
  align-content: start;
  width: min(58%, 600px);
  max-width: min(58%, 600px);
  min-height: 96px;
}
.court-message--flexible-height-card {
  height: auto;
  min-height: var(--court-message-min-height, auto);
}
.court-message--party-statement-card.court-message--flexible-height-card {
  --court-message-min-height: 92px;
}
.court-message--user.court-message--flexible-height-card {
  --court-message-min-height: 123px;
}
.court-message--merchant.court-message--flexible-height-card {
  --court-message-min-height: 101px;
}
.court-message--tall-narrow-card.court-message--flexible-height-card {
  --court-message-min-height: 143px;
}
.court-message--jury.court-message--flexible-height-card {
  --court-message-min-height: 149px;
}
.court-message--authority-card {
  align-self: center;
  display: flex;
  flex-direction: column;
  height: auto;
  max-height: none;
  overflow: visible;
  padding-bottom: 18px;
}
.court-message--extended-length-card {
  width: min(58%, 600px);
  max-width: min(58%, 600px);
}
.court-message--tall-narrow-card p {
  max-width: 100%;
}
.court-message--authority-card p {
  max-width: 100%;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
  word-break: break-word;
}
.court-message--jury-review-card header small {
  color: #6b5ac5;
  background: #f0ebff;
  border-color: #ded6ff;
}
.court-message--jury-review-card header strong::before {
  color: #6b5ac5;
  content: "✦";
}
.court-message__jury-tags {
  position: relative;
  z-index: 1;
  display: inline-flex;
  flex-wrap: wrap;
  gap: 5px;
  align-items: center;
  width: auto;
  padding: 3px 7px;
  color: #7d86a0;
  background: #ffffffa8;
  border: 1px solid #e5ecf2;
  border-radius: 999px;
  font-size: 9px;
  font-weight: 900;
}
.court-message__jury-tags em {
  color: #25a883;
  font-style: normal;
}
.round-input-bar {
  display: grid;
  gap: 9px;
  padding: 14px 16px 10px;
  background:
    radial-gradient(circle at 8% 0, #fff5c5 0 14%, transparent 15%),
    linear-gradient(135deg, #fff, #f2fbff 48%, #fff4f8);
  border: 1px solid #dfe8f2;
  border-radius: 24px;
  box-shadow: inset 0 1px 0 #fff, 0 14px 34px #5b769216;
}
.round-input-bar--fixed-dock {
  box-sizing: border-box;
  height: 154px;
  min-height: 154px;
  max-height: 154px;
  overflow: hidden;
}
.round-input-bar__body {
  min-width: 0;
}
.round-input-bar--fixed-dock .round-input-bar__body {
  display: grid;
  grid-template-rows: 24px 1fr;
  height: 100%;
  min-height: 0;
}
.round-input-bar__header {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  align-items: center;
}
.round-input-bar h3 {
  margin: 0;
  color: #34455e;
}
.round-input-bar__party-statuses {
  display: flex;
  flex: 0 0 auto;
  gap: 12px;
  align-items: center;
}
.round-input-party-status {
  position: relative;
  display: grid;
  grid-template-columns: auto auto;
  gap: 6px;
  align-items: baseline;
  padding-left: 12px;
  color: #8b98aa;
  white-space: nowrap;
  font-size: 10px;
  font-weight: 900;
}
.round-input-party-status::before {
  position: absolute;
  top: 50%;
  left: 0;
  width: 6px;
  height: 6px;
  content: "";
  background: #91a0b4;
  border-radius: 50%;
  transform: translateY(-50%);
}
.round-input-party-status strong {
  color: #34455e;
  font-size: 11px;
}
.round-input-party-status[data-round-input-party-status="USER"]::before {
  background: #17a8e6;
}
.round-input-party-status[data-round-input-party-status="USER"] strong {
  color: #128ec4;
}
.round-input-party-status[data-round-input-party-status="MERCHANT"]::before {
  background: #f09a62;
}
.round-input-party-status[data-round-input-party-status="MERCHANT"] strong {
  color: #a96128;
}
.round-input-bar__composer {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 180px;
  gap: 12px;
  align-items: stretch;
  margin-top: 0;
}
.round-input-bar--fixed-dock .round-input-bar__composer {
  height: 100px;
  min-height: 0;
  margin-top: 8px;
}
.round-input-bar__sealed-status,
.round-input-bar__final-status {
  display: flex;
  gap: 12px;
  align-items: center;
  min-height: 82px;
  margin-top: 8px;
  padding: 16px 18px;
  color: #31445d;
  background: linear-gradient(135deg, #f8fbff, #eef7ff);
  border: 1px solid #dce7f3;
  border-radius: 18px;
}
.round-input-bar__sealed-status > span,
.round-input-bar__final-status > span {
  display: grid;
  flex: 0 0 auto;
  width: 34px;
  height: 34px;
  place-items: center;
  background: #e6f5ee;
  border: 1px solid #c5ead6;
  border-radius: 50%;
}
.round-input-bar__sealed-status strong,
.round-input-bar__final-status strong {
  display: block;
  color: #28415d;
  font-size: 14px;
  font-weight: 900;
}
.round-input-bar__sealed-status small,
.round-input-bar__final-status small {
  display: block;
  margin-top: 4px;
  color: #7d8ba0;
  font-size: 11px;
  font-weight: 700;
}
.round-input-bar__composer textarea {
  box-sizing: border-box;
  width: 100%;
  min-height: 86px;
  padding: 14px 15px;
  color: #25344c;
  background: #f8fbff;
  border: 1px solid #dce7f3;
  border-radius: 18px;
  outline: none;
  resize: none;
  font-size: 13px;
  line-height: 1.6;
}
.round-input-bar--fixed-dock .round-input-bar__composer textarea {
  height: 94px;
  min-height: 94px;
  max-height: 94px;
  overflow: auto;
}
.round-input-bar__composer textarea:disabled {
  color: #7f8ca0;
  background: #f3f7fb;
  cursor: not-allowed;
}
.round-input-bar__submit-column {
  display: grid;
  gap: 8px;
  align-content: stretch;
}
.round-input-bar--fixed-dock .round-input-bar__submit-column {
  grid-auto-rows: 44px;
  align-content: start;
}
.round-input-bar__send-button,
.round-input-bar__lock-button,
.round-input-bar__round-submit,
.round-input-bar__settlement-button {
  width: 100%;
  min-height: 44px;
  padding: 10px 12px;
  color: #fff;
  background: linear-gradient(135deg, #20b8f0, #1097d3);
  border: 0;
  border-radius: 16px;
  box-shadow: 0 14px 28px #20a7df26;
  font-weight: 900;
  cursor: pointer;
}
.round-input-bar__send-button:disabled,
.round-input-bar__round-submit:disabled {
  cursor: not-allowed;
  opacity: .72;
}
.round-input-bar__round-submit {
  color: #28664e;
  background: #e2f8ec;
  border: 1px solid #bde8d1;
  box-shadow: none;
}
.round-input-bar__settlement-button {
  color: #8b5272;
  background: #fff0f4;
  border: 1px solid #f2d7df;
  box-shadow: none;
}
.round-input-bar__lock-button {
  color: #277154;
  background: #e1f6e9;
  border: 1px solid #bde8d1;
  box-shadow: none;
  cursor: not-allowed;
}
.round-input-bar__submitted {
  justify-self: stretch;
  padding: 11px 14px;
  color: #267152;
  text-align: center;
  background: #e1f6e9;
  border: 1px solid #bde8d1;
  border-radius: 14px;
}
.settlement-card button {
  padding: 8px 12px; color: #4c5d76; background: #f3f7fb; border: 1px solid #dae4ef; border-radius: 12px; cursor: pointer;
}
.court-ledger-backdrop {
  position: fixed;
  inset: 0;
  z-index: 68;
  display: flex;
  justify-content: flex-end;
  padding: 18px;
  background: #42557536;
  backdrop-filter: blur(10px);
}
.hearing-ledger {
  width: min(520px, 100%);
  min-width: 0;
  height: 100%;
  padding: 18px;
  overflow: auto;
  background:
    radial-gradient(circle at 14% 0, #fff1c7 0 16%, transparent 17%),
    linear-gradient(145deg, #ffffff, #f6fbff 58%, #fff7ec);
  border: 1px solid #dfe9f4;
  border-radius: 28px;
  box-shadow: 0 28px 80px #33445f30;
}
.hearing-ledger header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}
.hearing-ledger header p {
  max-width: 360px;
  margin: 4px 0 0;
  color: #7c899e;
  font-size: 12px;
  line-height: 1.55;
}
.hearing-ledger header button {
  display: grid;
  width: 36px;
  height: 36px;
  flex: 0 0 auto;
  place-items: center;
  color: #53617a;
  background: #f3f7fb;
  border: 1px solid #dce5ef;
  border-radius: 50%;
  cursor: pointer;
  font-size: 20px;
}
.hearing-ledger header h2 {
  margin: 3px 0 0;
  font-size: 24px;
}
.hearing-ledger ol {
  display: grid;
  grid-template-columns: 1fr;
  gap: 10px;
  padding: 0;
  margin: 16px 0 0;
  list-style: none;
}
.hearing-ledger li { padding: 14px; background: #f7f9fc; border: 1px solid #e2eaf3; border-radius: 18px; }
.hearing-ledger li div { display: flex; justify-content: space-between; gap: 8px; }
.hearing-ledger li span { color: #657a9b; font-size: 10px; }
.hearing-ledger li p { margin: 7px 0 0; color: #6d798d; font-size: 12px; line-height: 1.55; }
.hearing-ledger__empty {
  display: grid;
  gap: 4px;
  padding: 10px;
  color: #6d7890;
  text-align: center;
  background: #f7f9fc;
  border: 1px dashed #d8e2ee;
  border-radius: 18px;
}
.hearing-ledger__empty span { font-size: 22px; }
.hearing-ledger__empty strong { color: #3f4d64; line-height: 1.5; }
.hearing-ledger__empty small { color: #8390a2; line-height: 1.5; }
.settlement-card { padding: 15px; background: linear-gradient(135deg, #fff6d9, #fff0ea); border: 1px solid #f0dfbd; border-radius: 20px; }
.settlement-card--dock {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px 14px;
  align-items: center;
  padding: 10px 12px;
}
.settlement-card--dock > span,
.settlement-card--dock .settlement-card__parties,
.settlement-card--dock small {
  display: none;
}
.settlement-card--dock h3,
.settlement-card--dock p {
  margin: 0;
}
.settlement-card--dock p {
  overflow: hidden;
  color: #756b69;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.settlement-card--dock button {
  width: auto;
  white-space: nowrap;
}
.settlement-card h3 { margin: 5px 0; color: #594e4d; }
.settlement-card p { color: #756b69; line-height: 1.55; }
.settlement-card__parties { display: flex; gap: 7px; margin: 10px 0; }
.settlement-card__parties i { padding: 5px 8px; color: #9a7a6d; background: #fff; border-radius: 999px; font-size: 11px; font-style: normal; }
.settlement-card__parties i.confirmed { color: #267152; background: #e1f6e9; }
.settlement-card button { width: 100%; color: white; background: linear-gradient(135deg, #ff8d70, #e9779d); border: 0; font-weight: 800; }
.settlement-card__confirmed { display: block; color: #277154; }
.settlement-card small { display: block; margin-top: 5px; color: #7d7280; }
.hearing-error { color: #a94552; }
.settlement-dialog {
  position: fixed; inset: 0; z-index: 70; display: grid; place-items: center; padding: 20px;
  background: #42557540; backdrop-filter: blur(10px);
}
.settlement-dialog form {
  width: min(620px, 100%); padding: 22px; background: linear-gradient(145deg, #fff, #fff8e7);
  border: 1px solid #eadfbe; border-radius: 28px; box-shadow: 0 30px 80px #40506c32;
}
.settlement-dialog form header { display: flex; justify-content: space-between; gap: 20px; }
.settlement-dialog form header h2 { margin: 6px 0 16px; color: #4a4651; }
.settlement-dialog form header button { width: 36px; height: 36px; background: #f5ecd8; border: 0; border-radius: 50%; font-size: 21px; }
.settlement-dialog label { display: grid; gap: 7px; color: #695f61; font-size: 12px; }
.settlement-dialog textarea { padding: 11px; color: #4c464d; background: #fff; border: 1px solid #e5d9bf; border-radius: 13px; resize: vertical; }
.settlement-dialog p { color: #7d7170; font-size: 11px; }
.settlement-dialog form > button { width: 100%; padding: 12px; color: #fff; background: linear-gradient(135deg, #ff8d70, #e8759a); border: 0; border-radius: 13px; font-weight: 800; }
@media (max-width: 1180px) {
  .hearing-courtroom-page {
    grid-template-columns: minmax(0, 1fr);
  }
  .party-evidence-rail,
  .evidence-rail-column--right,
  .courtroom-center {
    grid-column: 1;
    grid-row: auto;
    position: static;
    height: auto;
  }
  .party-evidence-rail {
    max-height: none;
  }
  .evidence-pocket {
    max-height: 360px;
  }
}
@media (max-width: 680px) {
  .court-agent-strip,
  .hearing-stage-dock,
  .round-input-bar {
    grid-template-columns: 1fr;
  }
  .round-progress-board {
    position: static;
    grid-template-columns: 1fr;
    margin-top: 12px;
  }
  .court-message,
  .court-message--judge {
    width: auto;
    max-width: 100%;
  }
  .hearing-ledger ol {
    grid-template-columns: 1fr;
  }
}
</style>
