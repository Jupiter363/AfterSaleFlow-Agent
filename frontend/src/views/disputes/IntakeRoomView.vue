<script setup>
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  watch,
} from "vue";
import { useRoute, useRouter } from "vue-router";
import { disputeApi } from "../../api/disputes";
import { roomApi } from "../../api/rooms";
import DigitalHuman from "../../components/avatar/DigitalHuman.vue";
import ExpandableText from "../../components/common/ExpandableText.vue";
import ConversationStream from "../../components/room/ConversationStream.vue";
import RoomShell from "../../components/room/RoomShell.vue";
import { actor } from "../../state/actor";
import {
  createRoomState,
  streamRoomEvents,
} from "../../stores/room";
import {
  humanizeDossierList,
  humanizeDossierText,
  roleLabel,
} from "../../utils/displayText";

const props = defineProps({
  initialDispute: { type: Object, default: null },
  initialAnalysis: { type: Object, default: null },
  initialTurnMemory: { type: Object, default: null },
  initialMessages: { type: Array, default: null },
  messagesLoader: { type: Function, default: null },
  turnMemoryLoader: { type: Function, default: null },
  postMessageAction: { type: Function, default: null },
  confirmAction: { type: Function, default: null },
  cancelAction: { type: Function, default: null },
  eventStreamer: { type: Function, default: null },
});

const route = useRoute();
const router = useRouter();
const dispute = ref(props.initialDispute);
const analysis = ref(props.initialAnalysis);
const turnMemory = ref(props.initialTurnMemory);
const messages = ref([...(props.initialMessages || [])]);
const agentState = ref("LISTENING");
const submitting = ref(false);
const admitted = ref(false);
const resolved = ref(false);
const error = ref("");
const dossierFulltext = ref(null);
const dossierFulltextDialog = ref(null);
let dossierFulltextReturnFocus = null;
const eventState = reactive(createRoomState());
const workspaceGeneration = ref(0);
let eventAbortController = new AbortController();

const caseId = computed(() => dispute.value?.id || route.params.caseId);
const caseNoteTitle = computed(() =>
  humanizeDossierText(dispute.value?.title || "履约争端", {
    kind: "title",
    fallback: "履约争端",
  }),
);
const caseNoteDescription = computed(() =>
  humanizeDossierText(dispute.value?.description || "", {
    kind: "summary",
    fallback: "接待官正在整理争议事实，请继续补充订单、证据和处理诉求。",
  }),
);
const partyCanChat = computed(
  () =>
    ["USER", "MERCHANT"].includes(actor.role) &&
    actor.role === normalizedPartyRole(initiatorRoleValue.value),
);
const intakeComposerDisabledReason = computed(() => {
  if (!["USER", "MERCHANT"].includes(actor.role)) {
    return "当前是平台观察/审核身份。请切换为用户或商家身份，才能继续与争议接待官对话。";
  }
  if (!partyCanChat.value) {
    return "接待室仅由发起方补充；另一方请在证据室完成举证和回应。";
  }
  return "";
});
const connectionState = computed(() => {
  if (eventState.connected) return "connected";
  if (eventState.reconnecting) return "reconnecting";
  return "offline";
});
const scrollSnapshot = computed(() => turnMemory.value?.scroll_snapshot || null);
const currentCaseDossier = computed(() => turnMemory.value?.case_intake_dossier || null);
const caseDetailDossier = computed(() => {
  const current = currentCaseDossier.value?.dossier;
  if (current?.schema_version === "intake_case_detail.v1") return current;
  if (scrollSnapshot.value?.schema_version === "intake_case_detail.v1") return scrollSnapshot.value;
  return null;
});
const isCaseDetailDossier = computed(() => Boolean(caseDetailDossier.value));
const caseDetailQuality = computed(() => {
  const quality = caseDetailDossier.value?.intake_quality || {};
  return {
    score: currentCaseDossier.value?.quality_score ?? quality.score ?? 0,
    threshold: quality.threshold ?? 80,
    ready: currentCaseDossier.value?.ready_for_next_step ?? Boolean(quality.ready_for_next_step),
    reason: humanizeDossierText(quality.improvement_reason || "", { fallback: "" }),
  };
});
const dossierQualityPercent = computed(() => {
  const score = Number(caseDetailQuality.value.score || 0);
  if (!Number.isFinite(score)) return 0;
  return Math.max(0, Math.min(100, Math.round(score)));
});
const caseDetailReadyCopy = computed(() =>
  caseDetailQuality.value.ready ? "可以进入下一步" : "继续完善案件信息",
);
const errorDialogTitle = computed(() => {
  if (!error.value) return "";
  if (/HTTP\s*5\d\d|不可解析|服务/i.test(error.value)) return "服务暂时不可用";
  return "操作没有成功";
});
const errorDialogDetail = computed(() =>
  error.value || "请稍后重试，或刷新页面后再次操作。",
);
const caseRiskGradeValue = computed(() =>
  caseDetailDossier.value?.risk_assessment?.case_grade ||
  dispute.value?.risk_level ||
  analysis.value?.risk_level ||
  "UNKNOWN",
);
const caseRiskGradeCopy = computed(() =>
  humanizeDossierText(caseRiskGradeValue.value, { fallback: "风险待确认" }),
);
const caseRiskGradeTone = computed(() => {
  const value = String(caseRiskGradeValue.value || "").toUpperCase();
  if (value.includes("HIGH") || value.includes("高")) return "high";
  if (value.includes("MEDIUM") || value.includes("中")) return "medium";
  if (value.includes("LOW") || value.includes("低")) return "low";
  return "unknown";
});
const caseCover = computed(() => {
  const detail = caseDetailDossier.value;
  return {
    title: humanizeDossierText(detail?.case_story?.title || caseNoteTitle.value, {
      kind: "title",
      fallback: "争议事件待梳理",
    }),
    summary: humanizeDossierText(detail?.case_story?.one_sentence_summary || caseNoteDescription.value, {
      kind: "summary",
      fallback: "接待官正在整理争议事实，请继续补充订单、证据和处理诉求。",
    }),
    coreIssue: humanizeDossierText(detail?.dispute_focus?.core_issue || "UNKNOWN"),
  };
});
function displayReferenceValue(...values) {
  const value = values.find((item) => hasReferenceValue(item));
  if (!value) return "待补充";
  return String(value).trim();
}
const caseIndexItems = computed(() => {
  const detail = caseDetailDossier.value || {};
  const refs = detail.references || {};
  return [
    {
      key: "order",
      label: "订单",
      value: displayReferenceValue(
        refs.order_reference,
        refs.orderReference,
        analysis.value?.order_reference,
        analysis.value?.orderReference,
        dispute.value?.order_id,
        dispute.value?.orderId,
      ),
    },
    {
      key: "after-sale",
      label: "售后",
      value: displayReferenceValue(
        refs.after_sales_reference,
        refs.afterSalesReference,
        analysis.value?.after_sales_reference,
        analysis.value?.afterSalesReference,
        dispute.value?.after_sale_id,
        dispute.value?.afterSaleId,
      ),
    },
    {
      key: "logistics",
      label: "物流",
      value: displayReferenceValue(
        refs.logistics_reference,
        refs.logisticsReference,
        analysis.value?.logistics_reference,
        analysis.value?.logisticsReference,
        dispute.value?.logistics_id,
        dispute.value?.logisticsId,
      ),
    },
  ];
});
const claimResolutionLabels = {
  REFUND: "退款",
  RETURN_REFUND: "退货退款",
  RESHIP: "补发",
  REPLACE_OR_REPAIR: "换货 / 维修",
  REPLACEMENT: "换货 / 维修",
  REPAIR: "换货 / 维修",
  COMPENSATION: "赔付",
  CANCEL_ORDER: "取消订单",
  VERIFY_OR_EXPLAIN_ONLY: "核验 / 解释",
  OTHER: "其他诉求",
  UNKNOWN: "待确认诉求",
};
const respondentAttitudeLabels = {
  NOT_RESPONDED: "尚未回应",
  AGREE: "同意",
  PARTIALLY_AGREE: "部分同意",
  DISAGREE: "不同意",
  ALTERNATIVE_PROPOSED: "提出替代方案",
  NEED_MORE_INFO: "要求补充信息",
  PLATFORM_UNKNOWN: "平台暂未识别",
};
function compactText(...values) {
  return values
    .flat()
    .map((value) => String(value || "").trim())
    .filter(Boolean)
    .join(" ");
}
function legacyDossierSignalText(detail) {
  const detailSignal = compactText(
    detail?.case_story?.title,
    detail?.case_story?.one_sentence_summary,
    detail?.party_positions?.user_claim,
    detail?.party_positions?.merchant_claim,
    detail?.requested_resolution?.expected_resolution_text,
    detail?.requested_resolution?.requested_outcome,
  );
  if (detailSignal) return detailSignal;
  return compactText(
    analysis.value?.requested_outcome,
    analysis.value?.party_claims?.user,
    analysis.value?.party_claims?.merchant,
    dispute.value?.title,
    dispute.value?.description,
  );
}
function inferResolutionCode(detail, claim = {}) {
  const explicit =
    claim.requested_resolution ||
    claim.requestedResolution ||
    detail?.requested_resolution?.requested_outcome ||
    detail?.requested_resolution?.requestedResolution ||
    analysis.value?.claim_resolution_seed?.requested_resolution ||
    analysis.value?.claimResolutionSeed?.requested_resolution;
  const explicitCode = String(explicit || "").trim().toUpperCase();
  if (claimResolutionLabels[explicitCode]) return explicitCode;

  const signal = legacyDossierSignalText(detail);
  if (/退货退款/.test(signal)) return "RETURN_REFUND";
  if (/退款|退钱|原路退回/.test(signal)) return "REFUND";
  if (/补发|重发|重新发/.test(signal)) return "RESHIP";
  if (/换货|维修|修理/.test(signal)) return "REPLACE_OR_REPAIR";
  if (/赔付|赔偿|补偿/.test(signal)) return "COMPENSATION";
  if (/取消订单|撤销订单/.test(signal)) return "CANCEL_ORDER";
  if (/核验|核实|解释|说明/.test(signal)) return "VERIFY_OR_EXPLAIN_ONLY";
  return "UNKNOWN";
}
function meaningfulResponseText(value) {
  const text = humanizeDossierText(value || "", { fallback: "" }).trim();
  if (!text) return "";
  if (/^(待补充|待确认|等待对方回应|等待商家回应|等待用户回应|尚未回应|无|暂无)$/u.test(text)) {
    return "";
  }
  if (/^(用户|商家|对方)?尚未在接待室表达(明确)?态度[。.]?$/u.test(text)) {
    return "";
  }
  return text;
}
function resolveRespondentRole(detail, attitude = {}) {
  const explicitRole = normalizePartyRoleValue(
    attitude.respondent_role || attitude.respondentRole,
  );
  if (explicitRole !== "UNKNOWN") return explicitRole;

  const initiatorRole = normalizePartyRoleValue(
    detail?.claim_resolution?.initiator_role ||
      detail?.claimResolution?.initiatorRole ||
      analysis.value?.initiator_role ||
      dispute.value?.initiator_role ||
      dispute.value?.initiatorRole ||
      initiatorRoleValue.value,
  );
  return oppositePartyRole(initiatorRole);
}
function partyPositionForRole(detail, role) {
  if (role === "USER") {
    return detail?.party_positions?.user_claim || analysis.value?.party_claims?.user;
  }
  if (role === "MERCHANT") {
    return detail?.party_positions?.merchant_claim || analysis.value?.party_claims?.merchant;
  }
  return "";
}
function inferRespondentAttitude(
  detail,
  attitude = {},
  respondentRole = resolveRespondentRole(detail, attitude),
) {
  const structuredLabel =
    respondentAttitudeLabels[String(attitude.attitude || "").toUpperCase()] || "";
  const positionSummary = meaningfulResponseText(attitude.position);
  if (structuredLabel || positionSummary) {
    const hasStructuredResponse =
      structuredLabel &&
      ![respondentAttitudeLabels.NOT_RESPONDED, respondentAttitudeLabels.PLATFORM_UNKNOWN].includes(structuredLabel);
    return {
      label: structuredLabel || "态度待确认",
      summary: positionSummary || `${roleLabel(attitude.respondent_role || "UNKNOWN")}${structuredLabel || "态度待确认"}。`,
      hasResponse: Boolean(positionSummary || hasStructuredResponse),
      showSummary: Boolean(positionSummary),
    };
  }

  const respondentPosition = meaningfulResponseText(
    partyPositionForRole(detail, respondentRole),
  );
  if (!respondentPosition) {
    return {
      label: respondentAttitudeLabels.NOT_RESPONDED,
      summary: respondentNoResponseText(roleLabel(respondentRole)),
      hasResponse: false,
      showSummary: false,
    };
  }

  let label = "态度待确认";
  if (/不同意|不支持|拒绝|驳回/.test(respondentPosition)) label = respondentAttitudeLabels.DISAGREE;
  else if (/部分同意|部分接受/.test(respondentPosition)) label = respondentAttitudeLabels.PARTIALLY_AGREE;
  else if (/同意|接受/.test(respondentPosition)) label = respondentAttitudeLabels.AGREE;
  else if (/补发|换货|维修|替代方案|另行/.test(respondentPosition)) label = respondentAttitudeLabels.ALTERNATIVE_PROPOSED;
  else if (/补充|核验|核实|等待/.test(respondentPosition)) label = respondentAttitudeLabels.NEED_MORE_INFO;

  return {
    label,
    summary: respondentPosition,
    hasResponse: true,
    showSummary: true,
  };
}
function isSignedNotReceivedContext(detail) {
  return /物流|签收|未收到|没收到|包裹|快递/u.test(legacyDossierSignalText(detail));
}
function hasReferenceValue(value) {
  const text = String(value || "").trim();
  return Boolean(text && !/^(待补充|待确认|UNKNOWN|PENDING)$/i.test(text));
}
function fallbackFactsInDispute(detail) {
  if (!isSignedNotReceivedContext(detail)) return [];
  return ["用户是否实际收到商品", "签收记录是否足以证明本人收货"];
}
function fallbackVerificationGaps(
  detail,
  hasRespondentResponse = true,
  respondentRole = resolveRespondentRole(detail),
) {
  const refs = detail?.references || {};
  const gaps = [];
  const logisticsReference =
    refs.logistics_reference ||
    refs.logisticsReference ||
    analysis.value?.logistics_reference ||
    analysis.value?.logisticsReference;

  if (!hasReferenceValue(logisticsReference) && isSignedNotReceivedContext(detail)) {
    gaps.push("物流单号或平台可识别的物流引用");
  }
  if (isSignedNotReceivedContext(detail)) {
    gaps.push("签收截图、取件记录或未收到凭证");
    gaps.push("签收人身份、签收位置或投递轨迹");
  }
  if (!hasRespondentResponse) {
    gaps.push(`${partySubject(roleLabel(respondentRole), "对方")}对诉求的明确回应`);
  }
  return gaps;
}
function hasKnownPartyLabel(label) {
  return Boolean(label && !["待确认", "未知身份"].includes(label));
}
function claimActionTextFor(initiator, resolution) {
  if (resolution === "待确认诉求") return hasKnownPartyLabel(initiator) ? `${initiator}诉求待确认` : "诉求待确认";
  return hasKnownPartyLabel(initiator) ? `${initiator}请求${resolution}` : `请求${resolution}`;
}
function attitudeActionTextFor(respondent, attitudeLabel) {
  return hasKnownPartyLabel(respondent) ? `${respondent}${attitudeLabel}` : `对方${attitudeLabel}`;
}
function partySubject(label, fallback) {
  return hasKnownPartyLabel(label) ? label : fallback;
}
function respondentNoResponseText(respondent) {
  return `${partySubject(respondent, "对方")}尚未回应`;
}
const claimStatus = computed(() => {
  const detail = caseDetailDossier.value;
  if (!detail) {
    return null;
  }
  const claim = detail.claim_resolution || {};
  const attitude = detail.respondent_attitude || {};
  const core = detail.dispute_core_state || {};
  const initiatorRole = normalizePartyRoleValue(
    claim.initiator_role || initiatorRoleValue.value,
  );
  const respondentRole = resolveRespondentRole(detail, attitude);
  const initiator = roleLabel(initiatorRole);
  const respondent = roleLabel(respondentRole);
  const resolutionCode = inferResolutionCode(detail, claim);
  const resolution = claimResolutionLabels[resolutionCode] || "待确认诉求";
  const amount =
    claim.requested_amount ||
    claim.requestedAmount ||
    detail.requested_resolution?.requested_amount ||
    detail.requested_resolution?.requestedAmount;
  const amountText = amount ? `，金额 ${amount}` : "";
  const amountDisplay = amount ? `¥${amount}` : "";
  const requestedItems =
    claim.requested_items ||
    claim.requestedItems ||
    detail.requested_resolution?.requested_items ||
    detail.requested_resolution?.requestedItems ||
    "";
  const itemText = requestedItems ? `，涉及${requestedItems}` : "";
  const inferredAttitude = inferRespondentAttitude(detail, attitude, respondentRole);
  const fallbackFocus = fallbackVerificationGaps(
    detail,
    inferredAttitude.hasResponse,
    respondentRole,
  );
  const fallbackFacts = fallbackFactsInDispute(detail);
  const attitudeSummary =
    inferredAttitude.hasResponse || inferredAttitude.showSummary
      ? inferredAttitude.summary
      : respondentNoResponseText(respondent);
  return {
    initiator,
    respondent,
    resolution,
    resolutionActionText: claimActionTextFor(initiator, resolution),
    requestedItems,
    amountDisplay,
    attitudeLabel: inferredAttitude.label,
    attitudeActionText: attitudeActionTextFor(respondent, inferredAttitude.label),
    claimSummary:
      claim.normalized_statement ||
      claim.request_reason ||
      detail.requested_resolution?.expected_resolution_text ||
      `${initiator}请求${resolution}${amountText}${itemText}。`,
    claimMeta: `${initiator}主张${resolution}${amountText}${itemText}`,
    attitudeSummary,
    showAttitudeSummary: inferredAttitude.showSummary,
    attitudeMeta: `${respondent}：${inferredAttitude.label}`,
    coreConflict:
      core.core_conflict ||
      (inferredAttitude.hasResponse
        ? `${initiator}请求${resolution}，${respondent}已表达回应，核心争议仍待接待官继续归纳。`
        : `${initiator}请求${resolution}，但${respondent}态度尚待补充。`),
    factsInDispute: humanizeDossierList(core.facts_in_dispute || fallbackFacts, "").filter(Boolean),
    nextFocus: humanizeDossierList(core.next_verification_focus || fallbackFocus, "").filter(Boolean),
  };
});
const visibleClaimStatus = computed(() => {
  if (claimStatus.value) return claimStatus.value;
  const detail = caseDetailDossier.value || {};
  const initiator = roleLabel(initiatorRoleValue.value || "UNKNOWN");
  const respondent = roleLabel(oppositePartyRole(initiatorRoleValue.value));
  const resolution = claimResolutionLabels[inferResolutionCode(detail, {})] || "待确认诉求";
  return {
    initiator,
    respondent,
    resolution,
    resolutionActionText: claimActionTextFor(initiator, resolution),
    requestedItems: "",
    amountDisplay: "",
    attitudeLabel: respondentAttitudeLabels.NOT_RESPONDED,
    attitudeActionText: attitudeActionTextFor(respondent, respondentAttitudeLabels.NOT_RESPONDED),
    claimSummary:
      resolution === "待确认诉求"
        ? "诉求待确认"
        : `${initiator}请求${resolution}。`,
    claimMeta: `${initiator}主张${resolution}`,
    attitudeSummary: respondentNoResponseText(respondent),
    showAttitudeSummary: false,
    attitudeMeta: `${respondent}：${respondentAttitudeLabels.NOT_RESPONDED}`,
    coreConflict: `${partySubject(initiator, "发起方")}诉求与${partySubject(respondent, "对方")}回应的核心冲突仍待补齐。`,
    factsInDispute: [],
    nextFocus: [`${partySubject(respondent, "对方")}对诉求的明确回应`],
  };
});
function qualityReasonToGaps(reason) {
  const normalized = String(reason || "")
    .replace(/^仍缺少可信的/, "")
    .replace(/^仍缺少/, "")
    .replace(/[。.]$/u, "")
    .trim();
  if (!normalized) return [];
  return normalized
    .split(/[、,，/]/u)
    .map((item) => item.trim())
    .filter(Boolean);
}
const allVerificationGaps = computed(() => {
  const detail = caseDetailDossier.value || {};
  const missing = detail.missing_information || {};
  const respondentRole = resolveRespondentRole(
    detail,
    detail.respondent_attitude || {},
  );
  const respondentState = inferRespondentAttitude(
    detail,
    detail.respondent_attitude || {},
    respondentRole,
  );
  const candidates = [
    ...(Array.isArray(missing.blocking_gaps) ? missing.blocking_gaps : []),
    ...(Array.isArray(missing.nice_to_have_gaps) ? missing.nice_to_have_gaps : []),
    ...(Array.isArray(missing.next_questions) ? missing.next_questions : []),
    ...(Array.isArray(detail.dispute_focus?.facts_to_verify)
      ? detail.dispute_focus.facts_to_verify
      : []),
    ...qualityReasonToGaps(caseDetailQuality.value.reason),
    ...(claimStatus.value?.nextFocus || []),
    ...fallbackVerificationGaps(
      detail,
      respondentState.hasResponse,
      respondentRole,
    ),
  ];
  const seen = new Set();
  return humanizeDossierList(candidates, "")
    .map((item) => String(item || "").trim())
    .filter((item) => {
      if (!item || seen.has(item)) return false;
      seen.add(item);
      return true;
    });
});
const verificationGaps = computed(() => allVerificationGaps.value.slice(0, 4));
const hiddenVerificationGapCount = computed(() =>
  Math.max(0, allVerificationGaps.value.length - verificationGaps.value.length),
);
const scrollCards = computed(() => scrollSnapshot.value?.cards || []);
function scrollCardValue(key, fallback = "") {
  return scrollCards.value.find((card) => card.key === key)?.value || fallback;
}
const initiatorRoleValue = computed(() => {
  const explicitRole = normalizePartyRoleValue(
    analysis.value?.initiator_role ||
    dispute.value?.initiator_role ||
    dispute.value?.initiatorRole,
  );
  if (explicitRole !== "UNKNOWN") return explicitRole;

  const dossierRole = normalizePartyRoleValue(
    scrollCardValue("initiator_role") ||
      caseDetailDossier.value?.initiator_role ||
      caseDetailDossier.value?.initiatorRole,
  );
  if (dossierRole !== "UNKNOWN") return dossierRole;

  const firstPartyTurn = messages.value.find(
    (message) => normalizePartyRoleValue(message.sender_role || message.senderRole) !== "UNKNOWN",
  );
  return normalizePartyRoleValue(firstPartyTurn?.sender_role || firstPartyTurn?.senderRole);
});
const initiatorRoleCopy = computed(() =>
  roleLabel(initiatorRoleValue.value || "UNKNOWN"),
);
const intakeRecipientView = computed(
  () =>
    ["USER", "MERCHANT"].includes(actor.role) &&
    actor.role !== normalizedPartyRole(initiatorRoleValue.value),
);
const canManageIntake = computed(() => partyCanChat.value);

function currentWorkspaceSnapshot() {
  return {
    generation: workspaceGeneration.value,
    caseId: caseId.value,
    actor: {
      id: actor.id,
      role: actor.role,
    },
  };
}

function isCurrentWorkspace(snapshot) {
  return (
    snapshot &&
    snapshot.generation === workspaceGeneration.value &&
    snapshot.caseId === caseId.value &&
    snapshot.actor?.id === actor.id &&
    snapshot.actor?.role === actor.role
  );
}

function resetWorkspaceForActorChange() {
  workspaceGeneration.value += 1;
  messages.value = [];
  turnMemory.value = null;
  error.value = "";
  agentState.value = "LISTENING";
  submitting.value = false;
  eventAbortController.abort();
  eventAbortController = new AbortController();
  eventState.connected = false;
  eventState.reconnecting = false;
  eventState.streamError = null;
}

function normalizePartyRoleValue(role) {
  const value = String(role || "").trim().toUpperCase();
  if (
    value === "CUSTOMER_SERVICE" ||
    value === "DISPUTE_INTAKE_OFFICER" ||
    value === "INTAKE_OFFICER" ||
    value.includes("SERVICE") ||
    value.includes("OFFICER") ||
    value.includes("AGENT")
  ) {
    return "UNKNOWN";
  }
  if (value === "MERCHANT" || value.includes("MERCHANT") || value.includes("商家")) {
    return "MERCHANT";
  }
  if (
    value === "USER" ||
    value === "CUSTOMER" ||
    value.includes("USER") ||
    value.includes("用户") ||
    value.includes("客户")
  ) {
    return "USER";
  }
  return "UNKNOWN";
}

function oppositePartyRole(role) {
  const normalizedRole = normalizePartyRoleValue(role);
  if (normalizedRole === "USER") return "MERCHANT";
  if (normalizedRole === "MERCHANT") return "USER";
  return "UNKNOWN";
}

function normalizedPartyRole(role) {
  return normalizePartyRoleValue(role);
}

const subjectiveStatement = computed(() => {
  const detail = caseDetailDossier.value;
  const claim = detail?.claim_resolution || {};
  const sourceRole = normalizedPartyRole(initiatorRoleValue.value);
  const sourceRoleName = roleLabel(sourceRole);
  const fallbackPartyStatement =
    sourceRole === "MERCHANT"
      ? detail?.party_positions?.merchant_claim || analysis.value?.party_claims?.merchant
      : detail?.party_positions?.user_claim || analysis.value?.party_claims?.user || dispute.value?.description;
  return {
    titleSuffix: `${sourceRoleName}原话`,
    label: "原始陈述",
    value: humanizeDossierText(
      claim.original_statement ||
        claim.originalStatement ||
        fallbackPartyStatement ||
        "等待继续补充发起方陈述",
    ),
  };
});

async function openDossierFulltext(payload) {
  dossierFulltextReturnFocus = document.activeElement;
  dossierFulltext.value = payload;
  await nextTick();
  dossierFulltextDialog.value?.focus();
}

async function openVerificationGaps() {
  dossierFulltextReturnFocus = document.activeElement;
  dossierFulltext.value = {
    label: "下一步核验重点",
    items: allVerificationGaps.value,
  };
  await nextTick();
  dossierFulltextDialog.value?.focus();
}

async function closeDossierFulltext() {
  dossierFulltext.value = null;
  await nextTick();
  dossierFulltextReturnFocus?.focus();
}

async function load(snapshot = currentWorkspaceSnapshot()) {
  try {
    if (!dispute.value) {
      const loadedDispute = await disputeApi.get(snapshot.actor, snapshot.caseId);
      if (!isCurrentWorkspace(snapshot)) return;
      dispute.value = loadedDispute;
    }
    if (props.initialMessages === null) {
      await refreshMessages(snapshot);
    }
    if (props.initialTurnMemory === null && props.initialMessages === null) {
      await refreshTurnMemory(snapshot);
    }
  } catch (failure) {
    if (!isCurrentWorkspace(snapshot)) return;
    error.value = failure.message;
    agentState.value = "ERROR";
  }
}

async function refreshMessages(snapshot = currentWorkspaceSnapshot()) {
  const loadedMessages = await loadMessages(snapshot);
  if (isCurrentWorkspace(snapshot)) {
    messages.value = loadedMessages;
  }
}

async function loadMessages(snapshot = currentWorkspaceSnapshot()) {
  const loader =
    props.messagesLoader ||
    (() => roomApi.messages(snapshot.actor, snapshot.caseId, "INTAKE"));
  return loader(snapshot);
}

async function refreshTurnMemory(snapshot = currentWorkspaceSnapshot()) {
  const loader =
    props.turnMemoryLoader ||
    (() => roomApi.latestTurnMemory(snapshot.actor, snapshot.caseId, "INTAKE"));
  const loadedMemory = await loader(snapshot);
  if (isCurrentWorkspace(snapshot)) {
    turnMemory.value = loadedMemory;
  }
}

async function refreshRoomSnapshot(snapshot = currentWorkspaceSnapshot()) {
  await Promise.all([refreshMessages(snapshot), refreshTurnMemory(snapshot)]);
}

function nextLocalSequenceNo() {
  return Math.max(0, ...messages.value.map((message) => message.sequence_no || 0)) + 1;
}

function appendOptimisticPartyMessage(command, snapshot = currentWorkspaceSnapshot()) {
  if (!command?.text?.trim()) return "";
  const id = `PENDING_${Date.now()}_${Math.random().toString(16).slice(2)}`;
  messages.value = [
    ...messages.value,
    {
      id,
      sequence_no: nextLocalSequenceNo(),
      sender_role: snapshot.actor.role,
      message_text: command.text.trim(),
      pending: true,
    },
  ];
  return id;
}

function removeOptimisticMessage(id) {
  if (!id) return;
  messages.value = messages.value.filter((message) => message.id !== id);
}

function startEventStream(snapshot = currentWorkspaceSnapshot()) {
  const streamer = props.eventStreamer || streamRoomEvents;
  void streamer({
    actor: snapshot.actor,
    caseId: snapshot.caseId,
    roomType: "INTAKE",
    state: eventState,
    signal: eventAbortController.signal,
    snapshotLoader: () => refreshRoomSnapshot(snapshot),
    applyEvent: async (event) => {
      if (!isCurrentWorkspace(snapshot)) return;
      if (event.event === "EVIDENCE_OPENED") {
        await router.push(`/disputes/${snapshot.caseId}/evidence`);
      }
    },
  });
}

async function postMessage(command) {
  const snapshot = currentWorkspaceSnapshot();
  agentState.value = "THINKING";
  submitting.value = true;
  error.value = "";
  const optimisticId = appendOptimisticPartyMessage(command, snapshot);
  try {
    const submit =
      props.postMessageAction ||
      ((payload) => roomApi.postMessage(snapshot.actor, snapshot.caseId, "INTAKE", payload));
    await submit(command);
    await refreshRoomSnapshot(snapshot);
    if (isCurrentWorkspace(snapshot)) {
      agentState.value = "SPEAKING";
    }
  } catch (failure) {
    if (!isCurrentWorkspace(snapshot)) return;
    removeOptimisticMessage(optimisticId);
    error.value = failure.message;
    agentState.value = "ERROR";
  } finally {
    if (isCurrentWorkspace(snapshot)) {
      submitting.value = false;
    }
  }
}

async function resolveWithoutDispute() {
  const snapshot = currentWorkspaceSnapshot();
  submitting.value = true;
  error.value = "";
  agentState.value = "THINKING";
  const command = {
    reason: "resolved_before_admission",
  };
  try {
    const cancel =
      props.cancelAction ||
      ((payload) => disputeApi.cancelIntake(snapshot.actor, snapshot.caseId, payload.reason));
    const result = await cancel(command);
    if (!isCurrentWorkspace(snapshot)) return;
    if (result) {
      dispute.value = {
        ...(dispute.value || {}),
        ...result,
        id: result.case_id || result.caseId || dispute.value?.id || snapshot.caseId,
      };
    }
    resolved.value = true;
    admitted.value = true;
    agentState.value = "HANDOFF";
  } catch (failure) {
    if (!isCurrentWorkspace(snapshot)) return;
    error.value = failure.message;
    agentState.value = "ERROR";
  } finally {
    if (isCurrentWorkspace(snapshot)) {
      submitting.value = false;
    }
  }
}

async function confirmAdmission() {
  const snapshot = currentWorkspaceSnapshot();
  submitting.value = true;
  error.value = "";
  agentState.value = "THINKING";
  const command = {
    admissible: true,
    dispute_type: dispute.value?.dispute_type || "OTHER",
    risk_level: dispute.value?.risk_level || "MEDIUM",
  };
  try {
    const confirm =
      props.confirmAction ||
      ((payload) => disputeApi.confirmIntake(snapshot.actor, snapshot.caseId, payload));
    await confirm(command);
    if (!isCurrentWorkspace(snapshot)) return;
    admitted.value = true;
    agentState.value = "HANDOFF";
    await router.push(`/disputes/${snapshot.caseId}/evidence`);
  } catch (failure) {
    if (!isCurrentWorkspace(snapshot)) return;
    error.value = failure.message;
    agentState.value = "ERROR";
  } finally {
    if (isCurrentWorkspace(snapshot)) {
      submitting.value = false;
    }
  }
}

async function enterEvidenceRoom() {
  await router.push(`/disputes/${caseId.value}/evidence`);
}

function dismissError() {
  error.value = "";
  if (agentState.value === "ERROR") {
    agentState.value = "LISTENING";
  }
}

onMounted(async () => {
  const snapshot = currentWorkspaceSnapshot();
  await load(snapshot);
  if (props.eventStreamer || props.initialMessages === null) {
    startEventStream(snapshot);
  }
});
watch(
  () => [caseId.value, actor.id, actor.role],
  async () => {
    resetWorkspaceForActorChange();
    const snapshot = currentWorkspaceSnapshot();
    if (props.initialMessages === null) {
      await refreshRoomSnapshot(snapshot);
    }
    if (props.eventStreamer || props.initialMessages === null) {
      startEventStream(snapshot);
    }
  },
);
onBeforeUnmount(() => {
  eventAbortController.abort();
});
</script>

<template>
  <RoomShell
    eyebrow="INTAKE LOUNGE"
    title="争议接待室"
    :case-id="caseId"
    :connection-state="connectionState"
  >
    <template #agent>
      <DigitalHuman
        :state="agentState"
        name="小衡"
        role="争议接待官"
        :message="
          admitted
            ? '受理信息已经上报，证据书记官正在为双方准备证据书房。'
            : '先把事情完整告诉我，我会把引用、主张和诉求整理成可确认的卷宗贴纸。'
        "
      />
    </template>

    <div class="intake-room">
      <section
        class="intake-room__conversation"
      >
        <div class="intake-room__case-note">
          <span>你正在说明</span>
          <h2>{{ caseNoteTitle }}</h2>
          <p>{{ caseNoteDescription }}</p>
        </div>
        <div
          class="intake-room__conversation-lock-frame"
          :class="{ 'intake-room__conversation-lock-frame--locked': intakeRecipientView }"
        >
          <ConversationStream
            :messages="intakeRecipientView ? [] : messages"
            :disabled="submitting || admitted || !partyCanChat"
            :composer-visible="partyCanChat"
            :disabled-reason="intakeComposerDisabledReason"
            placeholder="补充订单、物流、双方沟通或你的期望…"
            @submit="postMessage"
          />
          <div
            v-if="intakeRecipientView"
            class="intake-room__locked-chat"
            data-intake-locked-chat
            aria-label="只有发起方可以查看哦"
          >
            <span aria-hidden="true">🔒</span>
            <strong>只有发起方可以查看哦</strong>
            <p>接待室保存的是发起方与接待官的单方事实梳理。你可以查看右侧案件概况，并进入证据室完成正式举证与回应。</p>
          </div>
        </div>
      </section>

      <section
        class="intake-dossier"
        aria-label="受理分析卷宗"
      >
        <header>
          <div>
            <span>LIVE DOSSIER</span>
            <h2>接待官整理出的争议轮廓</h2>
          </div>
          <small data-dossier-progress-hint>{{ caseDetailReadyCopy }}</small>
        </header>

        <div
          class="intake-case-detail"
          data-case-detail-dossier
        >
          <div
            class="intake-case-detail__status-rail"
            data-dossier-status-rail
          >
            <div class="intake-case-detail__status-copy">
              <strong data-dossier-status-pill>完善度 {{ dossierQualityPercent }}%</strong>
              <span data-dossier-status-hint>{{ caseDetailReadyCopy }}</span>
            </div>
            <div
              class="intake-case-detail__quality-track"
              role="progressbar"
              :aria-valuenow="dossierQualityPercent"
              aria-valuemin="0"
              aria-valuemax="100"
              aria-label="案件完善度"
            >
              <i :style="{ width: `${dossierQualityPercent}%` }" />
            </div>
            <div
              class="intake-case-detail__risk"
              :data-risk="caseRiskGradeTone"
              data-case-risk-grade
            >
              <span>风险</span>
              <strong>{{ caseRiskGradeCopy }}</strong>
            </div>
          </div>
          <section
            class="intake-case-detail__summary-card"
            data-case-detail-summary-card
          >
            <article
              class="intake-case-detail__dispute"
              data-dispute-detail-card
            >
              <span>争议详情</span>
              <div class="intake-case-detail__summary-note">
                <ExpandableText
                  data-dossier-fulltext-trigger="summary"
                  data-dispute-detail-summary
                  :text="caseCover.summary"
                  :title="caseCover.summary"
                  label="案情摘要"
                  :lines="5"
                  :expanded="dossierFulltext?.label === '案情摘要'"
                  @open="openDossierFulltext"
                />
              </div>
              <div
                class="intake-case-detail__meta-rows"
                data-dispute-detail-meta-rows
              >
                <div class="intake-case-detail__fields">
                <article
                  class="intake-case-detail__field"
                  data-dispute-detail-claim
                >
                  <span>发起方诉求</span>
                  <strong
                    :title="[
                      visibleClaimStatus.claimSummary,
                      visibleClaimStatus.amountDisplay,
                      visibleClaimStatus.requestedItems,
                    ].filter(Boolean).join(' · ')"
                  >
                    {{ visibleClaimStatus.claimSummary }}
                    <em v-if="visibleClaimStatus.amountDisplay">{{ visibleClaimStatus.amountDisplay }}</em>
                    <small v-if="visibleClaimStatus.requestedItems">{{ visibleClaimStatus.requestedItems }}</small>
                  </strong>
                </article>
                <article
                  class="intake-case-detail__field"
                  data-dispute-detail-respondent
                >
                  <span>对方回应</span>
                  <strong :title="visibleClaimStatus.attitudeSummary">
                    {{ visibleClaimStatus.attitudeSummary }}
                  </strong>
                </article>
              </div>
              <section
                class="intake-case-detail__index-strip"
                data-case-index-strip
              >
                <span>案件索引</span>
                <div
                  class="intake-case-detail__index-list"
                  data-case-index-list
                >
                  <article
                    v-for="item in caseIndexItems"
                    :key="item.key"
                    class="intake-case-detail__index-field"
                    data-case-index-field
                    :title="`${item.label}：${item.value}`"
                  >
                    <small>{{ item.label }}</small>
                    <strong>{{ item.value }}</strong>
                  </article>
                </div>
              </section>
              </div>
              <section
                class="intake-case-detail__origin-card"
                data-origin-statement-card
              >
                <span
                  class="intake-case-detail__meta-title"
                  data-single-party-statement-label
                >
                  原始陈述
                </span>
                <div
                  class="intake-case-detail__single-statement"
                  data-single-party-statement
                >
                  <ExpandableText
                    data-dossier-fulltext-trigger="origin"
                    data-origin-statement-text
                    :text="subjectiveStatement.value || '待补充'"
                    :title="subjectiveStatement.value || '待补充'"
                    label="原始陈述"
                    :lines="4"
                    :expanded="dossierFulltext?.label === '原始陈述'"
                    @open="openDossierFulltext"
                  />
                </div>
              </section>
            </article>
            <section
              v-if="verificationGaps.length"
              class="intake-case-detail__todo-list"
              data-verification-gaps
            >
              <div class="intake-case-detail__todo-heading">
                <span>下一步核验重点</span>
                <div>
                  <small data-verification-gap-count>{{ verificationGaps.length }} 项</small>
                  <button
                    v-if="hiddenVerificationGapCount"
                    type="button"
                    data-verification-gap-overflow
                    @click="openVerificationGaps"
                  >
                    另有 {{ hiddenVerificationGapCount }} 项
                  </button>
                </div>
              </div>
              <ol>
                <li
                  v-for="gap in verificationGaps"
                  :key="gap"
                  data-verification-gap-item
                  :title="gap"
                >
                  <span
                    class="intake-case-detail__todo-text"
                    data-verification-gap-text
                  >
                    {{ gap }}
                  </span>
                </li>
              </ol>
            </section>
          </section>
        </div>

        <div class="intake-dossier__confirm">
          <div
            v-if="intakeRecipientView"
            class="intake-dossier__actions"
          >
            <button
              type="button"
              data-enter-evidence-room
              :disabled="submitting"
              @click="enterEvidenceRoom"
            >
              进入证据室
            </button>
          </div>
          <div
            v-else-if="resolved"
            class="intake-dossier__result"
            data-intake-result
          >
            争议已取消，接待室已归档
          </div>
          <div v-else-if="canManageIntake" class="intake-dossier__actions intake-dossier__actions--two-column">
            <button
              type="button"
              data-confirm-admission
              :disabled="submitting || admitted"
              @click="confirmAdmission"
            >
              <span v-if="admitted">已上报</span>
              <span v-else-if="submitting">正在盖章…</span>
              <span v-else>确认发起并上报</span>
            </button>
            <button
              type="button"
              class="intake-dossier__secondary"
              data-resolve-without-dispute
              :disabled="submitting || admitted"
              @click="resolveWithoutDispute"
            >
              问题已解决，取消争议
            </button>
          </div>
          <p
            v-else
            class="intake-dossier__readonly-actions"
            data-intake-actions-readonly
            title="当前身份仅可查看接待室卷宗，发起与取消操作只对接待室发起方开放。"
          >
            当前身份仅可查看接待室卷宗
          </p>
        </div>
      </section>
    </div>
    <div
      v-if="dossierFulltext"
      ref="dossierFulltextDialog"
      class="intake-fulltext-dialog"
      data-dossier-fulltext-dialog
      role="dialog"
      aria-modal="true"
      aria-labelledby="intake-fulltext-title"
      tabindex="-1"
      @keydown.esc="closeDossierFulltext"
    >
      <section class="intake-fulltext-dialog__card">
        <h3 id="intake-fulltext-title">{{ dossierFulltext.label }}</h3>
        <p v-if="dossierFulltext.text">{{ dossierFulltext.text }}</p>
        <ol v-else>
          <li v-for="item in dossierFulltext.items" :key="item">{{ item }}</li>
        </ol>
        <button
          type="button"
          data-dismiss-dossier-fulltext
          @click="closeDossierFulltext"
        >
          关闭
        </button>
      </section>
    </div>
    <div
      v-if="error"
      class="intake-error-dialog"
      data-intake-error-dialog
      role="alertdialog"
      aria-modal="true"
      aria-labelledby="intake-error-dialog-title"
    >
      <div class="intake-error-dialog__card">
        <span aria-hidden="true">!</span>
        <h3 id="intake-error-dialog-title">{{ errorDialogTitle }}</h3>
        <p>{{ errorDialogDetail }}</p>
        <button
          type="button"
          data-dismiss-intake-error
          @click="dismissError"
        >
          我知道了
        </button>
      </div>
    </div>
  </RoomShell>
</template>

<style scoped>
.intake-room {
  --intake-panel-height: 740px;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 18px;
  align-items: start;
}

.intake-room__conversation,
.intake-dossier {
  box-sizing: border-box;
  height: var(--intake-panel-height);
  min-width: 0;
  overflow: hidden;
  background: #ffffffbf;
  border: 1px solid #dfe8f4;
  border-radius: 28px;
  box-shadow: 0 20px 55px #556d9512;
}
.intake-room__conversation {
  display: grid;
  grid-template-rows: 120px minmax(0, 1fr);
  min-height: 0;
  padding: 18px;
}
.intake-dossier {
  display: grid;
  grid-template-rows: 44px minmax(0, 1fr) 52px;
  gap: 8px;
  padding: 14px 18px;
}
@container room-workspace (min-width: 1060px) {
  .intake-room {
    grid-template-columns: minmax(0, 1.05fr) minmax(0, .95fr);
  }
}
.intake-room__case-note {
  box-sizing: border-box;
  height: 120px;
  padding: 16px;
  margin: 0;
  overflow: hidden;
  background: linear-gradient(135deg, #e8f6ff, #f4efff);
  border-radius: 20px;
}
.intake-room__case-note span,
.intake-dossier header span {
  color: #7186aa;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: .16em;
}
.intake-room__case-note h2 { margin: 5px 0; color: #34435c; }
.intake-room__case-note p {
  display: -webkit-box;
  margin: 0;
  overflow: hidden;
  color: #6f7d92;
  line-height: 1.5;
  overflow-wrap: anywhere;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.intake-room__conversation-lock-frame {
  position: relative;
  display: grid;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}
.intake-room__conversation-lock-frame :deep(.conversation-stream) {
  height: 100%;
  min-height: 0;
  overflow: hidden;
}
.intake-room__conversation-lock-frame--locked {
  overflow: hidden;
  border-radius: 22px;
  box-shadow:
    inset 0 0 0 1px #dce7f4,
    0 20px 55px #526d9430;
}
.intake-room__conversation-lock-frame--locked :deep(.conversation-stream) {
  min-height: 360px;
  opacity: .38;
  filter: blur(2px) saturate(.8);
  pointer-events: none;
}
.intake-room__locked-chat {
  position: absolute;
  inset: 0;
  z-index: 2;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 10px;
  padding: 28px;
  text-align: center;
  background:
    radial-gradient(circle at 50% 30%, #ffffffef 0 22%, transparent 46%),
    linear-gradient(135deg, #f5fbffe8, #fff7ece8);
  border: 1px solid #dce7f4;
  border-radius: 22px;
  backdrop-filter: blur(9px);
}
.intake-room__locked-chat span {
  display: grid;
  width: 54px;
  height: 54px;
  place-items: center;
  background: #fff;
  border: 1px solid #d9e7f5;
  border-radius: 20px;
  box-shadow: 0 12px 32px #607a9c26;
  font-size: 24px;
}
.intake-room__locked-chat strong {
  color: #334761;
  font-size: 18px;
}
.intake-room__locked-chat p {
  max-width: 380px;
  margin: 0;
  color: #6e7c91;
  font-size: 13px;
  line-height: 1.7;
}
.intake-dossier header {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  align-items: flex-start;
  height: 44px;
  min-width: 0;
  overflow: hidden;
}
.intake-dossier header h2 { margin: 5px 0 0; color: #34435c; font-size: 23px; }
.intake-dossier header small { color: #7384a1; }
.intake-case-detail {
  display: grid;
  grid-template-rows: 44px 412px 112px;
  gap: 8px;
  height: auto;
  min-height: 0;
  margin: 0;
  padding: 0;
  overflow: hidden;
  background: transparent;
  border: 0;
  border-radius: 0;
  box-shadow: none;
}
.intake-case-detail__status-rail {
  box-sizing: border-box;
  display: grid;
  height: 44px;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 5px 10px;
  align-items: center;
  min-height: 0;
  padding: 7px 9px;
  background: linear-gradient(135deg, rgba(255, 255, 255, .82), rgba(248, 252, 255, .68));
  border: 1px solid rgba(219, 232, 246, .95);
  border-radius: 15px;
  box-shadow: inset 0 1px 0 #fff, 0 8px 18px #58779b0d;
}
.intake-case-detail__status-copy {
  display: flex;
  min-width: 0;
  justify-content: flex-start;
  gap: 8px;
  align-items: center;
}
.intake-case-detail__status-copy span,
.intake-case-detail__risk span,
.intake-case-detail__dispute > span,
.intake-case-detail__todo-heading span,
.intake-case-detail__meta-title {
  color: #7788a5;
  font-size: 10px;
  font-weight: 900;
  letter-spacing: .14em;
}
.intake-case-detail__status-copy strong {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 0 10px;
  color: #40536f;
  background: #f7fbff;
  border: 1px solid #e4edf7;
  border-radius: 999px;
  font-size: 12px;
  white-space: nowrap;
}
.intake-case-detail__quality-track {
  position: relative;
  grid-column: 1 / 2;
  height: 5px;
  overflow: hidden;
  background: linear-gradient(90deg, #edf4fb, #f6f1ff);
  border-radius: 999px;
}
.intake-case-detail__quality-track i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, #7fc4f0, #87d7ad, #f2c95c);
  border-radius: inherit;
}
.intake-case-detail__risk strong {
  color: #5b69d8;
  font-size: 13px;
}
.intake-case-detail__risk {
  display: flex;
  grid-row: 1 / span 2;
  grid-column: 2 / 3;
  gap: 7px;
  align-items: center;
  justify-content: center;
  padding: 5px 8px;
  background: #f7fbff;
  border: 1px solid #e4edf7;
  border-radius: 999px;
}
.intake-case-detail__risk[data-risk="high"] strong { color: #d85b4a; }
.intake-case-detail__risk[data-risk="medium"] strong { color: #b1871d; }
.intake-case-detail__risk[data-risk="low"] strong { color: #2f8b64; }
.intake-case-detail__summary-card {
  display: contents;
}
.intake-case-detail__single-statement {
  display: grid;
  height: 100%;
  min-height: 0;
  padding: 0;
  color: #3d4860;
  overflow: hidden;
  background: transparent;
  border: 0;
  border-radius: 0;
}
.intake-case-detail__single-statement :deep(.expandable-text) {
  height: 100%;
}
.intake-case-detail__single-statement :deep(.expandable-text__content) {
  color: #34425a;
  font-size: 12px;
  line-height: 1.52;
  white-space: pre-wrap;
}
.intake-case-detail__dispute {
  position: relative;
  box-sizing: border-box;
  display: grid;
  height: 412px;
  grid-template-rows: 18px 110px 112px 108px;
  gap: 6px;
  min-height: 0;
  padding: 12px 14px;
  overflow: hidden;
  background:
    linear-gradient(90deg, rgba(255, 255, 255, .9), rgba(247, 251, 255, .94)),
    radial-gradient(circle at 94% 16%, rgba(242, 201, 92, .15) 0 16%, transparent 34%),
    radial-gradient(circle at 8% 92%, rgba(126, 196, 240, .14) 0 18%, transparent 36%);
  border: 1px solid #dde9f5;
  border-radius: 19px;
  box-shadow: 0 12px 28px #52779a10;
}
.intake-case-detail__dispute::before {
  content: "";
  position: absolute;
  right: 16px;
  top: 16px;
  width: 42px;
  height: 42px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, .62), rgba(255, 242, 202, .42)),
    linear-gradient(135deg, rgba(126, 196, 240, .18), rgba(242, 201, 92, .14));
  border: 1px solid rgba(221, 233, 245, .85);
  border-radius: 14px;
  transform: rotate(8deg);
  pointer-events: none;
}
.intake-case-detail__summary-note :deep(.expandable-text__content),
.intake-case-detail__field strong,
.intake-case-detail__todo-list li {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  margin: 0;
  color: #68768e;
  font-size: 12px;
  line-height: 1.5;
}
.intake-case-detail__summary-note {
  position: relative;
  display: grid;
  box-sizing: border-box;
  height: 110px;
  min-height: 0;
  align-content: center;
  padding: 12px 16px;
  overflow: hidden;
  background:
    linear-gradient(90deg, rgba(126, 196, 240, .45), transparent 36%) left top / 4px 100% no-repeat,
    radial-gradient(circle at 94% 18%, rgba(126, 196, 240, .18) 0 17%, transparent 30%),
    linear-gradient(135deg, rgba(255, 255, 255, .94), rgba(247, 252, 255, .76));
  border: 1px solid rgba(218, 232, 246, .92);
  border-radius: 16px;
}
.intake-case-detail__summary-note::after {
  content: "摘";
  position: absolute;
  right: 14px;
  bottom: -5px;
  color: rgba(126, 151, 182, .11);
  font-size: 46px;
  font-weight: 900;
  line-height: 1;
  pointer-events: none;
}
.intake-case-detail__summary-note :deep(.expandable-text) {
  z-index: 1;
  height: 100%;
}
.intake-case-detail__summary-note :deep(.expandable-text__content) {
  position: relative;
  z-index: 1;
  color: #314765;
  font-size: 13px;
  font-weight: 900;
}
.intake-case-detail__meta-rows {
  display: grid;
  height: 112px;
  grid-template-rows: 70px 42px;
  gap: 0;
  min-height: 0;
  border-top: 1px dashed #dce8f3;
}
.intake-case-detail__fields {
  display: grid;
  height: 70px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  min-width: 0;
}
.intake-case-detail__field {
  display: grid;
  box-sizing: border-box;
  height: 70px;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 4px;
  min-height: 0;
  min-width: 0;
  padding: 7px 10px;
  border-bottom: 1px dashed #dce8f3;
}
.intake-case-detail__field:last-child {
  border-bottom: 1px dashed #dce8f3;
  border-left: 1px dashed #dce8f3;
}
.intake-case-detail__field span {
  color: #7a8798;
  font-size: 11px;
  font-weight: 900;
}
.intake-case-detail__field strong {
  color: #2d4d70;
  -webkit-line-clamp: 2;
  max-height: 3em;
}
.intake-case-detail__field em,
.intake-case-detail__field small {
  display: inline-block;
  margin-left: 6px;
  vertical-align: 1px;
}
.intake-case-detail__field em {
  width: fit-content;
  padding: 2px 7px;
  color: #9b6b19;
  background: #fff5d9;
  border-radius: 999px;
  font-size: 11px;
  font-style: normal;
  font-weight: 900;
}
.intake-case-detail__field small {
  color: #68768e;
  font-size: 11px;
  font-weight: 800;
}
.intake-case-detail__index-strip {
  box-sizing: border-box;
  display: grid;
  height: 42px;
  grid-template-columns: 58px minmax(0, 1fr);
  gap: 10px;
  align-items: center;
  min-height: 0;
  padding: 0;
  overflow: hidden;
  background: transparent;
  border: 0;
  border-bottom: 1px dashed #dce8f3;
  border-radius: 0;
}
.intake-case-detail__index-strip > span {
  color: #7788a5;
  font-size: 10px;
  font-weight: 900;
  letter-spacing: .12em;
}
.intake-case-detail__index-list {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  align-items: center;
  min-width: 0;
}
.intake-case-detail__index-field {
  display: grid;
  align-content: center;
  min-width: 0;
  gap: 2px;
}
.intake-case-detail__index-field small {
  color: #8b97aa;
  font-size: 10px;
  font-weight: 900;
}
.intake-case-detail__index-field strong {
  overflow: hidden;
  color: #40536f;
  font-size: 11px;
  font-weight: 900;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.intake-case-detail__todo-list {
  box-sizing: border-box;
  display: grid;
  height: 112px;
  grid-template-rows: 20px minmax(0, 1fr);
  gap: 6px;
  min-height: 0;
  padding: 8px 10px;
  overflow: hidden;
  background:
    linear-gradient(135deg, rgba(255, 253, 247, .74), rgba(250, 252, 255, .58));
  border: 1px solid rgba(236, 226, 200, .9);
  border-radius: 15px;
}
.intake-case-detail__todo-heading {
  display: flex;
  height: 20px;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}
.intake-case-detail__todo-heading > div {
  display: flex;
  gap: 5px;
  align-items: center;
}
.intake-case-detail__todo-heading small {
  display: inline-flex;
  align-items: center;
  min-height: 18px;
  padding: 0 7px;
  color: #8b6c24;
  background: #fff4ce;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 900;
  white-space: nowrap;
}
.intake-case-detail__todo-heading button {
  min-height: 18px;
  padding: 0 7px;
  color: #6b72c9;
  background: #f1efff;
  border: 0;
  border-radius: 999px;
  cursor: pointer;
  font-size: 10px;
  font-weight: 900;
  white-space: nowrap;
}
.intake-case-detail__todo-list ol {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  grid-template-rows: repeat(2, minmax(0, 1fr));
  gap: 4px 8px;
  min-height: 0;
  overflow: hidden;
  margin: 0;
  padding: 0;
  list-style: none;
  counter-reset: intake-gaps;
}
.intake-case-detail__todo-list li {
  display: flex;
  gap: 5px;
  align-items: flex-start;
  min-width: 0;
}
.intake-case-detail__todo-text {
  display: -webkit-box;
  min-width: 0;
  overflow: hidden;
  font-size: 11px;
  line-height: 1.3;
  overflow-wrap: anywhere;
  word-break: break-word;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.intake-case-detail__todo-list li::before {
  counter-increment: intake-gaps;
  content: counter(intake-gaps);
  display: grid;
  flex: 0 0 auto;
  width: 16px;
  height: 16px;
  place-items: center;
  color: #8b6c24;
  background: #fff4ce;
  border-radius: 50%;
  font-size: 11px;
  font-weight: 900;
}
.intake-case-detail__origin-card {
  position: relative;
  box-sizing: border-box;
  display: grid;
  height: 108px;
  min-height: 108px;
  grid-template-rows: 18px minmax(0, 1fr);
  gap: 6px;
  padding: 4px 0 0;
  overflow: hidden;
  background: transparent;
  border: 0;
  border-top: 0;
  border-radius: 0;
}
@supports (-webkit-line-clamp: 1) {
  .intake-case-detail__field strong,
  .intake-case-detail__todo-text {
    display: -webkit-box;
    overflow: hidden;
    -webkit-box-orient: vertical;
  }

  .intake-case-detail__field strong {
    -webkit-line-clamp: 2;
  }

  .intake-case-detail__todo-text {
    -webkit-line-clamp: 2;
  }
}
.intake-dossier__confirm {
  position: relative;
  display: grid;
  height: 52px;
  min-height: 52px;
  padding: 0;
  overflow: hidden;
  background: transparent;
  border: 0;
  border-radius: 0;
}
.intake-dossier__confirm p { color: #7b718e; font-size: 12px; }
.intake-dossier__readonly-actions {
  display: grid;
  box-sizing: border-box;
  height: 52px;
  min-height: 52px;
  place-items: center;
  margin: 0;
  padding: 7px 10px;
  overflow: hidden;
  color: #71819a;
  background: #f7fbff;
  border: 1px dashed #d4e0ee;
  border-radius: 16px;
  font-size: 13px;
  line-height: 1.3;
  text-align: center;
}
.intake-dossier__actions {
  display: grid;
  height: 52px;
  min-height: 52px;
  gap: 10px;
}
.intake-dossier__actions--two-column {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.intake-dossier__confirm button {
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  min-height: 52px;
  padding: 7px 10px;
  color: white;
  background: linear-gradient(135deg, #ff8c72, #8e8bef);
  border: 0;
  border-radius: 14px;
  cursor: pointer;
  font-weight: 800;
  white-space: normal;
}
.intake-dossier__confirm button:disabled { opacity: .7; }
.intake-dossier__confirm .intake-dossier__secondary {
  color: #69758a;
  background: #edf4fb;
}
.intake-dossier__result {
  display: grid;
  box-sizing: border-box;
  height: 52px;
  min-height: 52px;
  place-items: center;
  padding: 7px 10px;
  color: #6e5a84;
  background: linear-gradient(135deg, #fff4ec, #f2efff);
  border: 1px solid #eadde9;
  border-radius: 16px;
  font-size: 13px;
  font-weight: 900;
  text-align: center;
}
.intake-fulltext-dialog {
  position: fixed;
  inset: 0;
  z-index: 50;
  display: grid;
  place-items: center;
  padding: 16px;
  background: #25354a66;
  backdrop-filter: blur(8px);
}
.intake-fulltext-dialog__card {
  display: grid;
  width: min(620px, calc(100dvw - 32px));
  max-height: min(680px, calc(100dvh - 32px));
  gap: 12px;
  padding: 20px;
  overflow-y: auto;
  overflow-wrap: anywhere;
  background: #fff;
  border-radius: 22px;
}
.intake-fulltext-dialog__card h3,
.intake-fulltext-dialog__card p {
  margin: 0;
}
.intake-fulltext-dialog__card p {
  white-space: pre-wrap;
}
.intake-fulltext-dialog__card button {
  min-width: 88px;
  min-height: 44px;
  justify-self: end;
}
.intake-error-dialog {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(38, 49, 73, .28);
  backdrop-filter: blur(6px);
}
.intake-error-dialog__card {
  display: grid;
  gap: 10px;
  width: min(420px, 100%);
  padding: 20px;
  text-align: center;
  background: linear-gradient(135deg, #fffaf6, #f7fbff);
  border: 1px solid #f1d6cf;
  border-radius: 24px;
  box-shadow: 0 30px 80px #3e526633;
}
.intake-error-dialog__card span {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  justify-self: center;
  color: #bd4b4b;
  background: #fff0ec;
  border: 1px solid #ffd5ce;
  border-radius: 50%;
  font-weight: 900;
}
.intake-error-dialog__card h3 {
  margin: 0;
  color: #34435c;
  font-size: 18px;
}
.intake-error-dialog__card p {
  margin: 0;
  color: #6d7890;
  font-size: 13px;
  line-height: 1.65;
}
.intake-error-dialog__card button {
  justify-self: center;
  min-width: 120px;
  padding: 10px 16px;
  color: #fff;
  background: linear-gradient(135deg, #ff8c72, #8e8bef);
  border: 0;
  border-radius: 999px;
  cursor: pointer;
  font-weight: 900;
}

@container room-workspace (max-width: 419px) {
  .intake-dossier > header > div,
  .intake-case-detail__status-copy {
    min-width: 0;
  }

  .intake-dossier > header > small,
  .intake-case-detail__status-copy [data-dossier-status-hint] {
    display: none;
  }

  .intake-dossier header h2 {
    overflow: hidden;
    font-size: 16px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .intake-case-detail__dispute {
    grid-template-rows: 18px 96px 126px 108px;
  }

  .intake-case-detail__dispute::before {
    display: none;
  }

  .intake-case-detail__summary-note {
    height: 96px;
  }

  .intake-case-detail__meta-rows {
    height: 126px;
    grid-template-rows: 84px 42px;
  }

  .intake-case-detail__fields,
  .intake-case-detail__field {
    height: 84px;
  }

  .intake-case-detail__origin-card {
    height: 108px;
  }
}
</style>
